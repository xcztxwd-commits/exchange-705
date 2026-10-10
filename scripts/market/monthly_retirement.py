"""Preview, back up, retire and restore precisely identified market-month state (Python stdlib only).

All writes require a matching environment, reviewed preview hash, complete backup and fresh stop-writer evidence.
Automatic trigger DDL is restricted to a disposable container with the requested monthly.retirement.owner label.
"""
import argparse
import base64
import collections
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile

TABLES = ('market_source_candle', 'market_simulation_source_candle', 'market_history_response',
          'market_history_ordering', 'market_engine_runtime', 'market_history_restore_job', 'market_history_restore_minute')
MONTHS = {'m', 'mo', '1mo'}  # Only aliases the old supplier adapters actually mapped to month candles.
SUSPECTED_MONTHS = {'month', '1month', 'monthly', '月', '月线', '1月', '10', 'type10', 'type=10'}


def retired(value):
    return isinstance(value, str) and (value.strip() == '1M' or value.strip().lower() in MONTHS)


def suspected(value):
    return isinstance(value, str) and value.strip().lower() in SUSPECTED_MONTHS


def sha(value):
    if not isinstance(value, bytes):
        value = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')
    return hashlib.sha256(value).hexdigest()


def save(path, value):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8') as output:
        json.dump(value, output, ensure_ascii=False, indent=2)


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def identifier(value):
    if not re.fullmatch(r'[A-Za-z0-9_]+', value):
        raise ValueError('Unsafe SQL identifier')
    return '`' + value + '`'


def cell(value):
    return 'NULL' if value is None else "CONVERT(X'" + value + "' USING utf8mb4)"


def decoded(value):
    return None if value is None else bytes.fromhex(value).decode('utf-8')


def encoded(value):
    return value.encode('utf-8').hex().upper()


class Mysql:
    def __init__(self, args):
        self.args = args
        identifier(args.database)
        self.owned = False
        if args.mysql_container:
            inspected = json.loads(subprocess.check_output(['docker', 'inspect', args.mysql_container]))[0]
            if not args.owner or inspected['Config'].get('Labels', {}).get('monthly.retirement.owner') != args.owner:
                raise ValueError('Container owner label mismatch; refuse even a preview against an unowned container')
            self.container = inspected['Id']; self.owned = True
            bindings = inspected['HostConfig']['PortBindings'].get('3306/tcp', [])
            if len(bindings) != 1 or bindings[0]['HostIp'] != '127.0.0.1':
                raise ValueError('Owned MySQL must have exactly one loopback binding')
            self.command = ['docker', 'exec', '-i', '-e', 'MYSQL_PWD', self.container, 'mysql', '-h127.0.0.1', '-uroot']
        else:
            if not args.mysql_defaults:
                raise ValueError('Use an existing mysql --defaults-extra-file; never put passwords in command arguments')
            self.command = [args.mysql_cli, '--defaults-extra-file=' + str(Path(args.mysql_defaults).resolve()),
                            '--protocol=TCP', '--host=' + args.host, '--port=' + str(args.port)]
        self.command += ['--default-character-set=utf8mb4', '--batch', '--skip-force', '--raw', '--skip-column-names', args.database]
        self.env = dict(os.environ)
        if self.owned:
            self.env['MYSQL_PWD'] = os.environ[args.mysql_password_env]

    def sql(self, statement):
        result = subprocess.run(self.command, input=statement.encode('utf-8'), env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            raise RuntimeError('MySQL failed: ' + result.stderr.decode(errors='replace')[-1500:])
        return result.stdout.decode('utf-8').rstrip('\r\n')

    def scan_rows(self, statement):
        """Stream large unclassified history scans; never accept partial CLI output."""
        with tempfile.TemporaryFile() as errors:
            process = subprocess.Popen(self.command + ['--quick'], stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=errors, env=self.env)
            try:
                process.stdin.write((statement+'\n').encode('utf-8')); process.stdin.close()
                for line in process.stdout:
                    if not line.endswith(b'\n'):raise ValueError('Truncated storage scan line')
                    values = line.decode('utf-8').rstrip('\r\n').split('\t')
                    if any(value!='N' and (not value.startswith('H') or len(value[1:])%2 or not re.fullmatch('[0-9A-F]*',value[1:])) for value in values):
                        raise ValueError('Malformed storage scan field')
                    yield [None if value == 'N' else value[1:] for value in values]
                if process.wait():
                    raise RuntimeError('MySQL storage scan failed; partial rows are not a completed inventory')
            finally:
                process.stdout.close()
                if not process.stdin.closed:process.stdin.close()
                if process.poll() is None:process.kill();process.wait()

    def snapshot(self, maximum):
        identity = self.sql('SELECT @@server_uuid,@@version,DATABASE(),@@hostname,@@port;').split('\t')
        if not identity[1].startswith('5.7.'):
            raise ValueError('This reviewed maintenance tool requires actual MySQL 5.7')
        environment = dict(serverUuid=identity[0], mysql=identity[1], database=identity[2], server=identity[3], port=identity[4])
        environment['fingerprint'] = sha(environment)
        tables = {}
        for name in TABLES:
            info = self.sql("SELECT COLUMN_NAME,COALESCE(CHARACTER_SET_NAME,''),COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + name + "' ORDER BY ORDINAL_POSITION;")
            if not info:
                raise ValueError('Required storage table missing: ' + name)
            columns = [line.split('\t') for line in info.splitlines()]
            names = [column[0] for column in columns]
            keys = self.sql("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + name + "' AND INDEX_NAME='PRIMARY' ORDER BY SEQ_IN_INDEX;").splitlines()
            if not keys or 'tenant_id' not in names:
                raise ValueError('Unreviewed key/tenant storage: ' + name)
            count = int(self.sql('SELECT COUNT(*) FROM ' + identifier(name)))
            if count > maximum:
                raise ValueError(name + ' exceeds --max-rows; review capacity before increasing this explicit ceiling')
            fields = ["IF(" + identifier(n) + " IS NULL,'N',CONCAT('H',HEX(CAST(" + identifier(n) + ' AS BINARY))))' for n in names]
            body = self.sql('SELECT ' + ','.join(fields) + ' FROM ' + identifier(name) + ' ORDER BY ' + ','.join(map(identifier, keys)))
            rows = [[None if value == 'N' else value[1:] for value in line.split('\t')] for line in body.splitlines()] if body else []
            if len(rows) != count:
                raise ValueError('Snapshot row count changed; stop all writers and preview again')
            ddl = self.sql('SHOW CREATE TABLE ' + identifier(name)).split('\t', 1)[1]
            tables[name] = dict(columns=names, columnInfo=columns, primaryKey=keys, rows=rows, count=count, sha256=sha(rows), schema=ddl)
        triggers = []
        wanted = ','.join("'" + name + "'" for name in TABLES)
        info = self.sql("SELECT TRIGGER_NAME,SQL_MODE,CHARACTER_SET_CLIENT,COLLATION_CONNECTION,DATABASE_COLLATION,EVENT_OBJECT_TABLE,EVENT_MANIPULATION FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE IN (" + wanted + ') ORDER BY TRIGGER_NAME;')
        for line in info.splitlines():
            name, mode, charset, collation, database_collation, table, event = line.split('\t')
            definition = self.sql('SHOW CREATE TRIGGER ' + identifier(name)).split('\t')[2]
            triggers.append(dict(name=name, sqlMode=mode, charset=charset, collation=collation, databaseCollation=database_collation, table=table, event=event, definition=definition))
        reconnaissance = self.scan_remaining(maximum, tables)
        return dict(environment=environment, tables=tables, triggers=triggers, schemaSha256=sha({n: t['schema'] for n, t in tables.items()}), triggersSha256=sha(triggers), reconnaissance=reconnaissance)

    def scan_remaining(self, maximum, managed):
        inventory, unknown = {}, []
        schema = self.sql("SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION;")
        columns = collections.defaultdict(list)
        for line in schema.splitlines():
            name, column, kind = line.split('\t'); columns[name].append((column, kind))
        for name, metadata in columns.items():
            names = [column for column, kind in metadata]
            total = int(self.sql('SELECT COUNT(*) FROM ' + identifier(name)))
            selected = [column for column, kind in metadata if column in ('period', 'interval', 'kline_type', 'klineType') or kind in ('json', 'text', 'mediumtext', 'longtext', 'tinytext', 'varchar', 'char')]
            inventory[name] = dict(total=total, scannedColumns=selected, columns=names)
            if not selected:
                continue
            if total > maximum:
                raise ValueError('Unclassified storage scan exceeds --max-rows: ' + name)
            identity = ['tenant_id'] if 'tenant_id' in names else []
            selected = identity + [column for column in selected if column not in identity]
            fields = ["IF(" + identifier(n) + " IS NULL,'N',CONCAT('H',HEX(CAST(" + identifier(n) + ' AS BINARY))))' for n in selected]
            if name in managed:
                raw_rows = ([row[managed[name]['columns'].index(column)] for column in selected] for row in managed[name]['rows'])
            else:
                raw_rows = self.scan_rows('SELECT ' + ','.join(fields) + ' FROM ' + identifier(name))
            scanned = 0
            try:
                for ordinal, raw in enumerate(raw_rows):
                    scanned += 1
                    if len(raw)!=len(selected):raise ValueError('Storage scan column count differs: '+name)
                    row = dict(zip(selected, map(decoded, raw)))
                    for field, value in row.items():
                        if value is None:
                            continue
                        if name in ('market_source_candle', 'market_simulation_source_candle') and field in ('period', 'body'):
                            continue  # Exact periods handled by plan; ambiguous non-month bodies are report-only.
                        if name == 'market_history_restore_minute' and field in ('before_json', 'source_json', 'previous_json'):
                            continue  # Minute-only receipts are never inferred to be monthly data.
                        if name == 'market_history_response':
                            if field == 'request_json' or field == 'response_json' and retired(json.loads(row['request_json']).get('interval')):
                                continue  # Classified by exact archived request identity, not response shape.
                        if name == 'market_engine_runtime' and field in ('quote_json', 'status_json'):
                            value, _ = strip_live(value)  # Scan everything outside the precisely classified live children.
                        marker = field in ('period', 'interval') and retired(value) or field in ('kline_type', 'klineType') and value == '10'
                        if value.lstrip().startswith(('{', '[')):
                            try:
                                marker |= monthly_marker(json.loads(value))
                            except ValueError:
                                if name.startswith('market_'):
                                    unknown.append(dict(table=name, column=field, tenant=row.get('tenant_id'), rowOrdinal=ordinal, reason='unparseable_market_json', sha256=sha(value.encode())))
                        if marker:
                            unknown.append(dict(table=name, column=field, tenant=row.get('tenant_id'), rowOrdinal=ordinal, reason='unclassified_monthly_kline_marker', sha256=sha(value.encode())))
            finally:
                raw_rows.close()
            if scanned != total:
                raise ValueError('Storage scan row count changed; stop all writers and preview again: ' + name)
            inventory[name]['scannedRows'] = scanned
        return dict(tableInventory=inventory, unclassified=unknown)


class Redis:
    """Small RESP client; DUMP bytes and PTTL are backed up without adding a Python dependency."""
    def __init__(self, args):
        self.args = args
        self.socket = socket.create_connection((args.redis_host, args.redis_port), timeout=10)
        self.file = self.socket.makefile('rb')
        if args.redis_password_env:
            self.command('AUTH', os.environ[args.redis_password_env])
        self.command('SELECT', args.redis_db)
        info = self.command('INFO', 'server').decode('utf-8')
        run_id = re.search(r'^run_id:([^\r\n]+)', info, re.M)
        version = re.search(r'^redis_version:([^\r\n]+)', info, re.M)
        if not run_id or not version or int(version[1].split('.')[0]) < 7:
            raise ValueError('Redis 7+ is required for exact PEXPIRETIME/ABSTTL maintenance')
        if not run_id:
            raise ValueError('Redis server identity is unavailable')
        self.identity = dict(host=args.redis_host, port=args.redis_port, database=args.redis_db, runId=run_id[1], version=version[1])

    def _read(self):
        line = self.file.readline(); kind, text = line[:1], line[1:-2]
        if kind == b'-':
            raise RuntimeError('Redis command failed: ' + text.decode(errors='replace'))
        if kind == b':':
            return int(text)
        if kind == b'+':
            return text
        if kind == b'$':
            length = int(text)
            if length < 0:
                return None
            result = self.file.read(length)
            if len(result) != length or self.file.read(2) != b'\r\n':
                raise RuntimeError('Incomplete Redis reply')
            return result
        if kind == b'*':
            return [self._read() for _ in range(int(text))]
        raise RuntimeError('Invalid Redis reply')

    def command(self, *values):
        values = [value if isinstance(value, bytes) else str(value).encode('utf-8') for value in values]
        self.socket.sendall(b'*' + str(len(values)).encode() + b'\r\n' + b''.join(b'$' + str(len(v)).encode() + b'\r\n' + v + b'\r\n' for v in values))
        return self._read()

    def snapshot(self, maximum):
        keys = set()
        for pattern in ('*',):
            cursor = b'0'
            while True:
                cursor, page = self.command('SCAN', cursor, 'MATCH', pattern, 'COUNT', 1000); keys.update(page)
                if len(keys) > maximum:
                    raise ValueError('Redis key scan exceeds --max-rows')
                if cursor == b'0':
                    break
        result, reports = {}, []
        for raw_key in sorted(keys):
            key = raw_key.decode('utf-8')
            candle = re.fullmatch(r'(?:tenant:([1-9][0-9]*):)?market:kline:(.+):([^:]+)', key)
            price = re.fullmatch(r'tenant:([1-9][0-9]*):market:price:([^:]+)', key)
            if not candle and not price:
                if self.command('TYPE', raw_key) == b'string':
                    body = self.command('GET', raw_key)
                    if body and body.lstrip().startswith((b'{', b'[')):
                        try:
                            if monthly_marker(json.loads(body)):
                                reports.append(dict(key=key, action='unclassified_monthly_kline_marker', sha256=sha(body)))
                        except ValueError:
                            if ':market:' in key or key.startswith('market:'):
                                reports.append(dict(key=key, action='unparseable_market_json', sha256=sha(body)))
                if ('kline' in key.lower() or 'candle' in key.lower()) and retired(key.rsplit(':', 1)[-1]):
                    reports.append(dict(key=key, action='unclassified_monthly_kline_key'))
                continue
            captured = self.command('EVAL', "local d=redis.call('DUMP',KEYS[1]); local ttl=redis.call('PTTL',KEYS[1]); local t=redis.call('TIME'); return {d or false,ttl,t[1],t[2],redis.call('PEXPIRETIME',KEYS[1])}", 1, raw_key)
            dump, expiry = captured[0], captured[1]
            if dump is None:
                continue
            if expiry == -2:
                continue
            captured_at = int(captured[2]) * 1000 + int(captured[3]) // 1000
            record = dict(dump=base64.b64encode(dump).decode(), pttl=expiry, capturedAt=captured_at,
                          expiresAt=captured[4] if captured[4] >= 0 else None, after=None, monthly=False)
            if candle and retired(candle[3]):
                record.update(monthly=True, tenant=candle[1] or 'legacy-unscoped', reason='exact_kline_key_suffix')
            elif candle and suspected(candle[3]):
                reports.append(dict(key=key, action='preserve_request_only_alias_not_proven_month'))
            elif price:
                if self.command('TYPE', raw_key) != b'string':
                    raise ValueError('Unreviewed price cache type: ' + key)
                body = self.command('GET', raw_key)
                if body is None:
                    continue
                changed, removed = strip_live(body.decode('utf-8'))
                if removed:
                    record.update(monthly=True, tenant=price[1], after=changed, reason='price.liveKlines', children=removed)
            result[key] = record
        return dict(environment=self.identity, keys=result, reports=reports)

    def apply(self, snapshots, mutations, restore):
        expired = []
        for key, item in mutations.items():
            current = self.command('DUMP', key)
            original = base64.b64decode(item['dump'])
            if restore:
                expiry = snapshots['keys'][key]['expiresAt']
                clock = self.command('TIME'); now = int(clock[0]) * 1000 + int(clock[1]) // 1000
                if expiry is not None and expiry <= now:
                    if current is not None:
                        raise ValueError('Naturally expired backup key has been replaced; refuses delete: ' + key)
                    expired.append(key); continue
                if current == original:
                    if self.command('PEXPIRETIME', key) != (expiry if expiry is not None else -1):
                        raise ValueError('Restored Redis value has a changed expiry; refuses overwrite: ' + key)
                    continue
                if item['after'] is None:
                    if current is not None:
                        raise ValueError('Redis restore key conflict: ' + key)
                elif self.command('GET', key) != item['after'].encode('utf-8'):
                    raise ValueError('Redis restore value changed: ' + key)
                if expiry is None:
                    self.command('RESTORE', key, 0, original, 'REPLACE')
                else:
                    self.command('RESTORE', key, expiry, original, 'REPLACE', 'ABSTTL')
            else:
                script = "if redis.call('DUMP',KEYS[1])~=ARGV[1] then return redis.error_reply('Preview value changed') end; "
                if item['after'] is None:
                    script += "return redis.call('DEL',KEYS[1])"
                    self.command('EVAL', script, 1, key, original)
                else:
                    script += "local expires=redis.call('PEXPIRETIME',KEYS[1]); redis.call('SET',KEYS[1],ARGV[2]); if expires>=0 then redis.call('PEXPIREAT',KEYS[1],expires) end; return 1"
                    self.command('EVAL', script, 1, key, original, item['after'])
        return expired


def attach_redis(current, snapshot):
    # TTL is backed up but does not participate in the preview hash; time passing is not a data change.
    current.pop('previewSha256', None)
    current['redisEnvironment'] = snapshot['environment']
    current['redisMutations'] = {key: {field: value for field, value in item.items() if field not in ('pttl', 'capturedAt', 'expiresAt')}
                                 for key, item in snapshot['keys'].items() if item['monthly']}
    current['redisCandidateCount'] = len(current['redisMutations'])
    current['redisUnclassified'] = [report for report in snapshot['reports'] if report['action'] != 'preserve_request_only_alias_not_proven_month']
    current['redisSuspected'] = [report for report in snapshot['reports'] if report['action'] == 'preserve_request_only_alias_not_proven_month']
    current['redisTenants'] = dict(collections.Counter(item['tenant'] for item in current['redisMutations'].values()))
    current['previewSha256'] = sha(current)


def object_members(text, start):
    """Locate raw member spans, retaining financial number text and every unrelated value byte."""
    decoder = json.JSONDecoder(); index = start + 1; members = []
    while True:
        while text[index].isspace():
            index += 1
        if text[index] == '}':
            return members, index + 1
        first = index; key, index = decoder.raw_decode(text, index)
        while text[index].isspace():
            index += 1
        if text[index] != ':':
            raise ValueError('Invalid JSON member separator')
        index += 1
        while text[index].isspace():
            index += 1
        value_start = index; value, index = decoder.raw_decode(text, index)
        members.append((key, first, value_start, index, value))
        while text[index].isspace():
            index += 1
        if text[index] == '}':
            return members, index + 1
        if text[index] != ',':
            raise ValueError('Invalid JSON object separator')
        index += 1


def strip_live(body):
    value = json.loads(body)
    if not isinstance(value, dict):
        raise ValueError('Unreviewed runtime/price object')
    live = value.get('liveKlines')
    if live is None:
        return body, []
    if not isinstance(live, dict):
        raise ValueError('Unreviewed liveKlines structure')
    months = [key for key in live if retired(key)]
    if months:
        outer, _ = object_members(body, len(body) - len(body.lstrip()))
        containers = [member for member in outer if member[0] == 'liveKlines']
        if len(containers) != 1:
            raise ValueError('Duplicate/ambiguous liveKlines member')
        container = containers[0]; members, end = object_members(body, container[2])
        if len({member[0] for member in members}) != len(members):
            raise ValueError('Duplicate live period key')
        kept = [body[first:last] for key, first, value_start, last, item in members if key not in months]
        return body[:container[2]] + '{' + ','.join(kept) + '}' + body[end:], months
    return body, []


def plan(snapshot):
    mutations, reports = [], []
    archive_keys = collections.defaultdict(set)
    for name, table in snapshot['tables'].items():
        names = table['columns']
        for raw in table['rows']:
            row = {key: decoded(raw[i]) for i, key in enumerate(names)}
            after, monthly = list(raw), False
            if 'period' in row:
                monthly = retired(row['period'])
                if suspected(row['period']):
                    reports.append(dict(table=name, tenant=row['tenant_id'], period=row['period'], action='preserve_request_only_alias_not_proven_month'))
            elif name == 'market_history_response':
                request = json.loads(row['request_json'])
                if not isinstance(request, dict):
                    raise ValueError('Unreviewed archive request')
                monthly = retired(request.get('interval'))
                if suspected(request.get('interval')):
                    reports.append(dict(table=name, tenant=row['tenant_id'], request=row['request_sha256'], action='preserve_request_only_alias_not_proven_month'))
                if monthly:
                    if sha(row['request_json'].encode('utf-8')) != row['request_sha256'] or sha(row['response_json'].encode('utf-8')) != row['response_sha256']:
                        raise ValueError('Archive exact byte checksum mismatch')
                    if str(request.get('tenant')) != row['tenant_id'] or str(request.get('symbol')) != row['symbol_id']:
                        raise ValueError('Archive identity mismatch')
                    archive_keys[(row['tenant_id'], row['symbol_id'])].add(row['request_sha256'])
            elif name == 'market_engine_runtime':
                children = {}
                for field in ('quote_json', 'status_json'):
                    if row[field] is not None:
                        changed, removed = strip_live(row[field])
                        if removed:
                            after[names.index(field)] = encoded(changed); children[field] = removed
                if children:
                    mutations.append(dict(table=name, before=raw, after=after, tenant=row['tenant_id'], reason='runtime.liveKlines', children=children))
            if monthly:
                mutations.append(dict(table=name, before=raw, after=None, tenant=row['tenant_id'], reason='explicit_month_period'))
            elif 'body' in row and row['body'] is not None and monthly_marker(json.loads(row['body'])):
                reports.append(dict(table=name, tenant=row['tenant_id'], action='preserve_ambiguous_period_body', primaryKey={key: row[key] for key in table['primaryKey']}))
            if name == 'market_history_restore_minute':
                # Minute restores have no month discriminator. Never infer a month from a date/shape/OHLC.
                fields = [field for field in ('before_json', 'source_json', 'previous_json') if row[field] is not None and monthly_marker(json.loads(row[field]))]
                if fields:
                    reports.append(dict(table=name, tenant=row['tenant_id'], minute=row['minute_at'], action='preserve_ambiguous_minute_restore', fields=fields))
    table = snapshot['tables']['market_history_ordering']; names = table['columns']
    for raw in table['rows']:
        row = {key: decoded(raw[i]) for i, key in enumerate(names)}
        removed = archive_keys.get((row['tenant_id'], row['symbol_id']), set())
        if removed:
            manifest = json.loads(row['responses_json'])
            if not isinstance(manifest, dict) or not removed.issubset(manifest):
                raise ValueError('Archive/policy manifest mismatch; refuse automatic cleanup')
            after = list(raw); after[names.index('responses_json')] = encoded(json.dumps({key: value for key, value in manifest.items() if key not in removed}, ensure_ascii=False, separators=(',', ':')))
            mutations.append(dict(table='market_history_ordering', before=raw, after=after, tenant=row['tenant_id'], reason='exact_month_archive_manifest_keys', children=sorted(removed)))
    counts = collections.Counter((m['table'], m['tenant']) for m in mutations)
    inventory = {name: dict(total=table['count'], candidates=sum(count for (n, tenant), count in counts.items() if n == name),
                           tenants={tenant: count for (n, tenant), count in sorted(counts.items()) if n == name}, sha256=table['sha256']) for name, table in snapshot['tables'].items()}
    ambiguity = 'Legacy case-insensitive PK can have overwritten 1m/1M bodies already. Those rows cannot be reconstructed or identified from timestamp/OHLC; no inferred deletion or rewrite.'
    result = dict(environment=snapshot['environment'], tables=inventory, mutations=mutations, reports=reports,
                  unresolved=ambiguity, schemaSha256=snapshot['schemaSha256'], triggersSha256=snapshot['triggersSha256'],
                  fullTableInventory=snapshot['reconnaissance']['tableInventory'], unclassified=snapshot['reconnaissance']['unclassified'])
    result['previewSha256'] = sha(result)
    return result


def monthly_marker(value):
    if isinstance(value, dict):
        return any((key in ('period', 'interval') and retired(item)) or (key in ('kline_type', 'klineType') and str(item) == '10')
                   or monthly_marker(item) for key, item in value.items())
    return isinstance(value, list) and any(monthly_marker(item) for item in value)


def where(table, raw, keys_only=False):
    columns = table['primaryKey'] if keys_only else table['columns']
    return ' AND '.join(identifier(name) + ' IS NULL' if raw[table['columns'].index(name)] is None else
                        'BINARY ' + identifier(name) + '=' + "X'" + raw[table['columns'].index(name)] + "'" for name in columns)


def mutations_sql(snapshot, mutations, restore=False):
    statements = ['SET SESSION sql_mode=CONCAT(@@session.sql_mode,\',STRICT_ALL_TABLES\');',
                  'CREATE TEMPORARY TABLE monthly_retirement_assert(id INT PRIMARY KEY) ENGINE=InnoDB;',
                  'INSERT INTO monthly_retirement_assert VALUES(1);', 'START TRANSACTION;']
    # Parent policy rows must exist for restored archive rows. Updates also compare the complete reviewed current row.
    ordered = sorted(mutations, key=lambda m: (m['table'] != 'market_history_ordering', m['table']))
    for mutation in ordered:
        table = snapshot['tables'][mutation['table']]; before, after = mutation['before'], mutation['after']; name = identifier(mutation['table'])
        if restore and after is None:
            statements.append('INSERT INTO ' + name + '(' + ','.join(map(identifier, table['columns'])) + ') VALUES(' + ','.join(map(cell, before)) + ');')
        elif after is None:
            statements.append('DELETE FROM ' + name + ' WHERE ' + where(table, before) + ';')
        else:
            old, new = (after, before) if restore else (before, after)
            changed = [identifier(column) + '=' + cell(new[i]) for i, column in enumerate(table['columns']) if new[i] != old[i]]
            statements.append('UPDATE ' + name + ' SET ' + ','.join(changed) + ' WHERE ' + where(table, old) + ';')
        statements += ['SET @monthly_retirement_changed=ROW_COUNT();', 'SELECT @monthly_retirement_changed;',
                       'INSERT INTO monthly_retirement_assert SELECT 1 WHERE @monthly_retirement_changed<>1;']
    statements.append('COMMIT;')
    return '\n'.join(statements)


def trigger_restore_sql(triggers):
    result = ['-- MySQL DDL implicitly commits. Keep ALL writers stopped until every definition is restored and verified.', 'DELIMITER $$']
    for trigger in triggers:
        result += ['SET SESSION sql_mode=' + cell(encoded(trigger['sqlMode'])) + '$$',
                   'SET NAMES ' + trigger['charset'] + ' COLLATE ' + trigger['collation'] + '$$', trigger['definition'] + '$$']
    return '\n'.join(result + ['DELIMITER ;'])


def stop_gate(args, environment):
    if args.expect_environment != environment['fingerprint']:
        raise ValueError('Explicit --expect-environment fingerprint does not match this DB')
    if not args.stop_evidence:
        raise ValueError('Fresh externally confirmed writer-stop evidence is required')
    evidence = read(args.stop_evidence)
    now = datetime.datetime.now(datetime.timezone.utc)
    checked = datetime.datetime.fromisoformat(evidence['verifiedAt'].replace('Z', '+00:00'))
    if not evidence.get('allWritersStopped') or evidence.get('environmentFingerprint') != environment['fingerprint'] or not evidence.get('writerInventory') or not evidence.get('verifiedBy') or not 0 <= (now - checked).total_seconds() <= 900:
        raise ValueError('Writer-stop evidence must identify every writer and the verifier; valid for only 15 minutes')
    if args.production and not evidence.get('productionMaintenanceApproved'):
        raise ValueError('Production maintenance approval is absent; production data was not changed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['preview', 'delete', 'restore'])
    parser.add_argument('--database', required=True); parser.add_argument('--output', required=True, help='New report file; existing files are never overwritten')
    parser.add_argument('--mysql-container'); parser.add_argument('--owner'); parser.add_argument('--mysql-password-env', default='MONTHLY_MYSQL_PASSWORD')
    parser.add_argument('--mysql-cli', default='mysql'); parser.add_argument('--mysql-defaults'); parser.add_argument('--host', default='127.0.0.1'); parser.add_argument('--port', type=int, default=3306)
    parser.add_argument('--max-rows', type=int, default=100000); parser.add_argument('--reviewed-preview'); parser.add_argument('--expect-preview'); parser.add_argument('--expect-environment')
    parser.add_argument('--stop-evidence'); parser.add_argument('--backup'); parser.add_argument('--production', action='store_true')
    parser.add_argument('--owned-trigger-maintenance', action='store_true', help='Only for this tool\'s labelled disposable fixture; never production')
    parser.add_argument('--redis-port', type=int); parser.add_argument('--redis-host', default='127.0.0.1'); parser.add_argument('--redis-db', type=int, default=0); parser.add_argument('--redis-password-env')
    args = parser.parse_args(); db = Mysql(args)
    snapshot = db.snapshot(args.max_rows); current = plan(snapshot)
    redis = Redis(args) if args.redis_port else None
    redis_snapshot = redis.snapshot(args.max_rows) if redis else None
    if redis:
        attach_redis(current, redis_snapshot)
    if args.action == 'preview':
        save(args.output, current); print(json.dumps({key: current[key] for key in ('environment', 'tables', 'previewSha256')}, ensure_ascii=False)); return
    stop_gate(args, current['environment'])
    if not args.backup:
        raise ValueError('--backup is required')
    if args.action == 'delete':
        reviewed = read(args.reviewed_preview)
        if args.expect_preview != reviewed['previewSha256'] or reviewed['previewSha256'] != current['previewSha256']:
            raise ValueError('Reviewed preview/candidate bytes changed; no deletion performed')
        backup = dict(snapshot=snapshot, plan=current, snapshotSha256=sha(snapshot), redis=redis_snapshot); backup['backupSha256'] = sha(backup); save(args.backup, backup)
        if current['unclassified'] or current.get('redisUnclassified'):
            raise ValueError('Unclassified monthly state remains; complete backup saved, manual classification required before deletion')
    else:
        backup = read(args.backup)
        checksum = backup.pop('backupSha256')
        if sha(backup) != checksum:
            raise ValueError('Complete backup checksum failed')
        backup['backupSha256'] = checksum
        if backup['snapshotSha256'] != sha(backup['snapshot']):
            raise ValueError('Backup integrity check failed')
        if backup['snapshot']['environment'] != snapshot['environment']:
            raise ValueError('Restore environment differs from backup')
        if backup['snapshot']['schemaSha256'] != snapshot['schemaSha256']:
            raise ValueError('Restore schema differs from backup')
        if bool(backup.get('redis')) != bool(redis) or redis and backup['redis']['environment'] != redis_snapshot['environment']:
            raise ValueError('Restore requires the same Redis server/database as the backup')
        if current['unclassified'] or current.get('redisUnclassified'):
            raise ValueError('Unclassified state changed during maintenance; manual review required before restore')
    mutations = backup['plan']['mutations']
    if args.action == 'restore':
        for mutation in mutations:
            table = snapshot['tables'][mutation['table']]
            matches = [row for row in table['rows'] if all(row[table['columns'].index(k)] == mutation['before'][table['columns'].index(k)] for k in table['primaryKey'])]
            expected = [] if mutation['after'] is None else [mutation['after']]
            if matches == [mutation['before']]:
                mutation['alreadyRestored'] = True
            elif matches != expected:
                raise ValueError('Restore conflicts with changed data; refuses overwrite: ' + mutation['table'])
        mutations = [m for m in mutations if not m.get('alreadyRestored')]
    trigger_file = Path(args.backup).with_suffix('.restore-triggers.sql'); drop_file = Path(args.backup).with_suffix('.drop-triggers.sql')
    operations = {(m['table'], 'UPDATE' if m['after'] is not None else 'INSERT' if args.action == 'restore' else 'DELETE') for m in mutations}
    maintenance = [trigger for trigger in snapshot['triggers'] if (trigger['table'], trigger['event']) in operations]
    if args.action == 'delete':
        trigger_file.write_text(trigger_restore_sql(maintenance), encoding='utf-8')
        drop_file.write_text('\n'.join('DROP TRIGGER ' + identifier(t['name']) + ';' for t in maintenance), encoding='utf-8')
    if maintenance and not (args.owned_trigger_maintenance and db.owned and not args.production):
        raise ValueError('Triggers require reviewed manual maintenance. Backup and SQL saved. Automatic production DDL is prohibited.')
    dropped = []
    expired_redis = []
    try:
        if maintenance:
            for trigger in maintenance:
                db.sql('DROP TRIGGER ' + identifier(trigger['name'])); dropped.append(trigger)
        if mutations:
            counts = db.sql(mutations_sql(backup['snapshot'], mutations, args.action == 'restore')).splitlines()
            if counts != ['1'] * len(mutations):
                raise RuntimeError('Write count mismatch; stop all writers and use the complete backup before resuming')
        if redis:
            expired_redis = redis.apply(backup['redis'], backup['plan']['redisMutations'], args.action == 'restore')
    finally:
        # Trigger restoration is a separate DDL phase, never claimed to be part of the DML transaction.
        failed_triggers = []
        for trigger in dropped:
            try:
                db.sql(trigger_restore_sql([trigger]))
            except Exception as failure:
                failed_triggers.append(dict(name=trigger['name'], error=str(failure)))
        if failed_triggers:
            save(Path(args.output).with_suffix('.trigger-recovery-failed.json'), dict(unrestored=failed_triggers, allRestorationsAttempted=True, writersMustRemainStopped=True))
            raise RuntimeError('Trigger recovery incomplete; failed list saved, keep every writer stopped')
    after = db.snapshot(args.max_rows)
    if after['triggersSha256'] != snapshot['triggersSha256'] or after['schemaSha256'] != snapshot['schemaSha256']:
        raise RuntimeError('Schema/trigger recovery differs; do not resume writers')
    if args.action == 'restore':
        if any(after['tables'][name]['sha256'] != backup['snapshot']['tables'][name]['sha256'] for name in TABLES):
            raise RuntimeError('Restored table contents differ from backup; do not resume writers')
    else:
        if plan(after)['mutations']:
            raise RuntimeError('Monthly candidates remain; do not resume writers')
        for name in TABLES:
            expected = list(snapshot['tables'][name]['rows'])
            for mutation in mutations:
                if mutation['table'] == name:
                    expected.remove(mutation['before'])
                    if mutation['after'] is not None:
                        expected.append(mutation['after'])
            if sha(sorted(expected, key=repr)) != sha(sorted(after['tables'][name]['rows'], key=repr)):
                raise RuntimeError('Unrelated rows changed: ' + name)
    if redis:
        for key, item in backup['redis']['keys'].items():
            actual = redis.command('DUMP', key)
            clock = redis.command('TIME'); now = int(clock[0]) * 1000 + int(clock[1]) // 1000
            if item['expiresAt'] is not None and item['expiresAt'] <= now:
                if actual is not None:
                    raise RuntimeError('Naturally expired cache was revived: ' + key)
                continue
            if (args.action == 'restore' or not item['monthly'] or item['after'] is not None) and redis.command('PEXPIRETIME', key) != (item['expiresAt'] if item['expiresAt'] is not None else -1):
                raise RuntimeError('Redis original absolute expiry differs: ' + key)
            if args.action == 'restore' or not item['monthly']:
                if actual != base64.b64decode(item['dump']):
                    raise RuntimeError('Redis restore/unrelated value differs: ' + key)
            elif item['after'] is None:
                if actual is not None:
                    raise RuntimeError('Monthly Redis key remains: ' + key)
            elif redis.command('GET', key) != item['after'].encode('utf-8'):
                raise RuntimeError('Monthly runtime cache child cleanup differs: ' + key)
    result = dict(action=args.action, environment=after['environment'], affected=len(mutations), completeBackup=args.backup,
                  schemaUnchanged=True, triggersRestored=True, tableContentSha256={name: table['sha256'] for name, table in after['tables'].items()},
                  redisAffected=len(backup['plan'].get('redisMutations', {})), redisPttlBackedUp=bool(redis), production=args.production)
    result['redisExpiredSkipped'] = expired_redis
    save(args.output, result); print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        print(str(failure), file=sys.stderr); sys.exit(1)
