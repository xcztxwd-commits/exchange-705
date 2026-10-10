"""Exercise preview/delete/repeat/restore and trigger-failure recovery on owned loopback MySQL 5.7 + Redis.

Never reads application credentials, restarts existing containers or connects to another business database.
Usage: python scripts/market/test_monthly_retirement_mysql.py --output C:/workspace/fx/new/<new-evidence-dir>
"""
import argparse
import base64
import contextlib
import datetime
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--output', required=True)
    args = parser.parse_args(); output = Path(args.output).resolve(); output.mkdir(parents=True, exist_ok=False)
    repo = Path(__file__).resolve().parents[2]
    spec = importlib.util.spec_from_file_location('monthly_retirement', Path(__file__).with_name('monthly_retirement.py'))
    retirement = importlib.util.module_from_spec(spec); spec.loader.exec_module(retirement)
    owner = secrets.token_hex(12); password = secrets.token_hex(24); commands = []; containers = []
    environment = dict(os.environ, MONTHLY_MYSQL_PASSWORD=password, MYSQL_ROOT_PASSWORD=password, MYSQL_PWD=password)

    def docker(*values, data=None):
        result = subprocess.run(['docker', *values], env=environment, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        commands.append(dict(command=['docker', *values], exitCode=result.returncode))
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors='replace')[-1200:])
        return result.stdout.decode('utf-8').strip()

    def port():
        with socket.socket() as available:
            available.bind(('127.0.0.1', 0)); return available.getsockname()[1]

    mysql_port, redis_port = port(), port()
    try:
        mysql = docker('run', '-d', '--name', 'monthly-retirement-mysql-' + owner, '--label', 'monthly.retirement.owner=' + owner,
                       '-p', '127.0.0.1:' + str(mysql_port) + ':3306', '-e', 'MYSQL_ROOT_PASSWORD', '--memory', '768m', 'mysql:5.7', '--innodb-use-native-aio=0')
        containers.append(mysql)
        redis_container = docker('run', '-d', '--name', 'monthly-retirement-redis-' + owner, '--label', 'monthly.retirement.owner=' + owner,
                                '-p', '127.0.0.1:' + str(redis_port) + ':6379', '--memory', '128m', 'redis:7-alpine')
        containers.append(redis_container)

        def sql(text):
            return docker('exec', '-i', '-e', 'MYSQL_PWD', mysql, 'mysql', '-h127.0.0.1', '-uroot', '--default-character-set=utf8mb4', '--batch', '--raw', '--skip-column-names', data=text.encode('utf-8'))

        deadline = time.monotonic() + 100
        while True:
            try:
                sql('SELECT 1;'); break
            except RuntimeError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(1)
        database = 'monthly_retirement_' + owner
        schema = (repo / 'exchange-backend/src/test/resources/multitenant-market-test.sql').read_text(encoding='utf-8')
        # Exact production archive/restore DDL replaces the H2 fixture's constraint-free definitions.
        schema = re.sub(r'CREATE TABLE (?:IF NOT EXISTS )?market_history_(?:ordering|response|restore_job|restore_minute) \(.*?\)(?: ENGINE=InnoDB)?;', '', schema, flags=re.S)
        schema = schema.replace('history_restore_revision BIGINT NOT NULL DEFAULT 0,', '')
        sql('CREATE DATABASE ' + database + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; USE ' + database + '; CREATE TABLE tenant(id BIGINT PRIMARY KEY); INSERT INTO tenant VALUES(1),(2); CREATE TABLE tenant_schema_version(version BIGINT,applied_at DATETIME(6),minimum_application_epoch BIGINT,business_activation_ready INT);' + schema +
            'ALTER TABLE trading_symbol ADD UNIQUE KEY tenant_symbol(tenant_id,id); INSERT INTO trading_symbol(id,tenant_id) VALUES(1,1),(2,2);')
        migrations = repo / 'exchange-backend/src/main/resources/db/migration'
        restore = (migrations / 'V2026100703__history_source_restore.sql').read_text(encoding='utf-8')
        archive = (migrations / 'V2026100404__history_ordering_and_response_receipts.sql').read_text(encoding='utf-8')
        # Seed historical legacy facts before enabling the real immutable/fence triggers.
        sql('USE ' + database + ';\n' + restore.split('DELIMITER $$')[0])
        sql('USE ' + database + ';\n' + archive.split('DELIMITER $$')[0])
        now = int(time.time() * 1000); minute = now // 60000 * 60000; old = 1706745600000
        body = json.dumps(dict(timestamp=old, open_price=100, high_price=110, low_price=90, close_price=105, volume=7), separators=(',', ':'))
        archive_rows, policies = [], []
        for tenant in (1, 2):
            sql("USE " + database + "; INSERT INTO market_engine_runtime(tenant_id,symbol_id,writer_generation,owner_id,lease_until) VALUES(" + str(tenant) + ',' + str(tenant) + ",1,'fixture'," + str(now + 3600000) + ');')
            for table in ('market_source_candle', 'market_simulation_source_candle'):
                for period, at in [('1M', old), ('mo', old + 2000), ('1m', minute), ('1w', minute - 604800000), ('1month', old + 1000)]:
                    value = dict(json.loads(body), timestamp=at, tag='preserve-' + period)
                    columns = 'tenant_id,symbol_id,period,candle_at,body' + (',received_at' if table == 'market_source_candle' else ',session_at')
                    sql('USE ' + database + '; INSERT INTO ' + table + '(' + columns + ') VALUES(' + ','.join([str(tenant), str(tenant), retirement.cell(retirement.encoded(period)), str(at), retirement.cell(retirement.encoded(json.dumps(value, separators=(',', ':')))), str(now if table == 'market_source_candle' else old - 60000)]) + ');')
            manifest = {}
            for period in ('1M', '1m'):
                request = json.dumps(dict(tenant=tenant, symbol=tenant, interval=period, limit=1, cursor=old + 1, utcAnchors=True, code='TEST', source='fixture'), sort_keys=True, separators=(',', ':'))
                response = json.dumps(dict(ret=200, data=dict(code='TEST', source='fixture', kline_list=[json.loads(body)])), separators=(',', ':'))
                request_hash, response_hash, artifact = retirement.sha(request.encode()), retirement.sha(response.encode()), 'a' * 64
                pointer = '/fixture/' + period; manifest[request_hash] = retirement.sha((request_hash + ':' + response_hash + ':' + artifact + ':' + pointer).encode())
                archive_rows.append('INSERT INTO market_history_response VALUES(' + ','.join([str(tenant), str(tenant), retirement.cell(retirement.encoded(request_hash)), retirement.cell(retirement.encoded(request)), retirement.cell(retirement.encoded(response)), retirement.cell(retirement.encoded(response_hash)), retirement.cell(retirement.encoded(artifact)), retirement.cell(retirement.encoded(pointer)), retirement.cell(retirement.encoded('b' * 64)), str(now), '1']) + ');')
            policies.append('INSERT INTO market_history_ordering VALUES(' + ','.join([str(tenant), str(tenant), '2', str(minute + 120000), '0', retirement.cell(retirement.encoded(json.dumps(manifest, separators=(',', ':')))), retirement.cell(retirement.encoded('b' * 64)), retirement.cell(retirement.encoded('c' * 64)), str(now), '1']) + ');')
            # Deliberately high precision lexical price must survive raw JSON child removal exactly.
            quote = '{"price":100.12345678901234567890,"projectionKind":"SOURCE_1M","liveKlines":{"1m":{"tag":"minute"},"1M":{"tag":"month"},"mo":{"tag":"alias"},"1d":{"tag":"day"}}}'
            sql('USE ' + database + '; UPDATE market_engine_runtime SET quote_json=' + retirement.cell(retirement.encoded(quote)) + ' WHERE tenant_id=' + str(tenant) + ';')
        sql('USE ' + database + ';\n' + '\n'.join(policies + archive_rows))
        runtime = (migrations / 'V2026100304__market_engine_runtime.sql').read_text(encoding='utf-8')
        for definition in (runtime[runtime.index('DELIMITER $$'):], archive[archive.index('DELIMITER $$'):], restore[restore.index('DELIMITER $$'):], (migrations / 'V2026100305__s2_runtime_tenant_constraints.sql').read_text(encoding='utf-8')):
            sql('USE ' + database + ';\n' + definition)
        redis_args = argparse.Namespace(redis_host='127.0.0.1', redis_port=redis_port, redis_db=0, redis_password_env=None)
        redis = retirement.Redis(redis_args)
        for tenant in (1, 2):
            for suffix in ('1M', '1m', 'mo', '1month'):
                redis.command('SET', 'tenant:' + str(tenant) + ':market:kline:TEST:' + suffix, '[1,2,3]', 'PX', 3600000)
            redis.command('SET', 'tenant:' + str(tenant) + ':market:kline:simulation-history:' + str(tenant) + ':' + str(old) + ':1M', '[4,5,6]')
            redis.command('SET', 'tenant:' + str(tenant) + ':market:price:TEST', quote, 'PX', 3600000)
        redis.command('SET', 'unrelated:1M', 'keep'); redis.command('SET', 'tenant:1:finance:1M', 'financial month remains')
        cli = [sys.executable, str(Path(__file__).with_name('monthly_retirement.py'))]
        common = ['--database', database, '--mysql-container', mysql, '--owner', owner, '--redis-port', str(redis_port)]

        def run(action, name, *extra, expected=0):
            command = cli + [action] + common + ['--output', str(output / (name + '.json'))] + list(extra)
            result = subprocess.run(command, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            (output / (name + '.log')).write_bytes(result.stdout); commands.append(dict(command=command, exitCode=result.returncode))
            if result.returncode != expected:
                raise RuntimeError(name + ' exit=' + str(result.returncode) + ': ' + result.stdout.decode(errors='replace')[-2000:])
            return retirement.read(output / (name + '.json')) if result.returncode == 0 else None

        # Unknown storage and unknown market cache may be previewed, but may never be silently omitted from delete.
        sql('USE ' + database + "; CREATE TABLE extra_market_month_probe(id INT PRIMARY KEY,tenant_id BIGINT,payload TEXT); INSERT INTO extra_market_month_probe VALUES(1,2,'{\"interval\":\"1M\",\"kline_list\":[]}');")
        redis.command('SET', 'tenant:2:market:unknown-history', '{"interval":"1M","kline_list":[]}')
        unknown = run('preview', 'unknown-preview'); assert unknown['unclassified'] and unknown['redisUnclassified']
        fingerprint = unknown['environment']['fingerprint']
        stop = output / 'writers-stopped.json'
        retirement.save(stop, dict(allWritersStopped=True, environmentFingerprint=fingerprint, writerInventory=['No application connected: newly created owned fixture only'], verifiedBy='owned fixture harness', verifiedAt=datetime.datetime.now(datetime.timezone.utc).isoformat()))
        gated = ['--expect-environment', fingerprint, '--stop-evidence', str(stop), '--owned-trigger-maintenance']
        run('delete', 'gate-unclassified', *gated, '--backup', str(output / 'unknown-backup.json'), '--reviewed-preview', str(output / 'unknown-preview.json'), '--expect-preview', unknown['previewSha256'], expected=1)
        sql('USE ' + database + '; DROP TABLE extra_market_month_probe;')
        redis.command('DEL', 'tenant:2:market:unknown-history')
        preview = run('preview', 'preview')
        # Missing stop evidence and automatic production trigger DDL must both fail before any data mutation.
        run('delete', 'gate-no-stop', '--backup', str(output / 'no-stop-backup.json'), '--reviewed-preview', str(output / 'preview.json'), '--expect-preview', preview['previewSha256'], '--expect-environment', fingerprint, expected=1)
        denied = run('delete', 'gate-triggers', '--backup', str(output / 'denied-backup.json'), '--reviewed-preview', str(output / 'preview.json'), '--expect-preview', preview['previewSha256'], '--expect-environment', fingerprint, '--stop-evidence', str(stop), expected=1)
        before = retirement.read(output / 'denied-backup.json')['snapshot']
        # After several real DML operations, force one optimistic predicate to match zero.
        # The unique temporary assertion must fail BEFORE COMMIT, rolling every preceding mutation back.
        original_sql = retirement.Mysql.sql; original_argv = sys.argv; os.environ['MONTHLY_MYSQL_PASSWORD'] = password

        def stale_row(self, statement):
            if 'START TRANSACTION;' in statement:
                statement = statement.replace('DELETE FROM `market_source_candle` WHERE ', 'DELETE FROM `market_source_candle` WHERE 1=0 AND ', 1)
            return original_sql(self, statement)

        retirement.Mysql.sql = stale_row
        sys.argv = ['monthly_retirement.py', 'delete'] + common + ['--output', str(output / 'fault-delete.json')] + gated + ['--backup', str(output / 'fault-backup.json'), '--reviewed-preview', str(output / 'preview.json'), '--expect-preview', preview['previewSha256']]
        with (output / 'fault-transaction.log').open('w', encoding='utf-8') as log, contextlib.redirect_stdout(log), contextlib.redirect_stderr(log):
            try:
                retirement.main(); raise AssertionError('Fault injection did not fail')
            except RuntimeError as failure:
                assert 'Duplicate entry' in str(failure); print(str(failure))
            finally:
                retirement.Mysql.sql = original_sql; sys.argv = original_argv
        after_fault = run('preview', 'fault-after-preview')
        assert after_fault['previewSha256'] == preview['previewSha256'], 'No DML or trigger changes may survive the mid-transaction failure'
        result = run('delete', 'delete', *gated, '--backup', str(output / 'backup.json'), '--reviewed-preview', str(output / 'preview.json'), '--expect-preview', preview['previewSha256'])
        clean = run('preview', 'clean-preview'); assert not clean['mutations'] and not clean['redisMutations']
        repeated = run('delete', 'repeat-delete', *gated, '--backup', str(output / 'empty-backup.json'), '--reviewed-preview', str(output / 'clean-preview.json'), '--expect-preview', clean['previewSha256']); assert repeated['affected'] == 0
        restored = run('restore', 'restore', *gated, '--backup', str(output / 'backup.json'))
        repeated_restore = run('restore', 'repeat-restore', *gated, '--backup', str(output / 'backup.json')); assert repeated_restore['affected'] == 0
        for name, table in before['tables'].items():
            assert restored['tableContentSha256'][name] == table['sha256']
        for key in ('unrelated:1M', 'tenant:1:finance:1M'):
            assert redis.command('GET', key) is not None
        backup = retirement.read(output / 'backup.json')
        for key, item in backup['redis']['keys'].items():
            assert redis.command('DUMP', key) == base64.b64decode(item['dump'])
            if item['pttl'] >= 0:
                assert redis.command('PEXPIRETIME', key) == item['expiresAt'], 'Original absolute expiry is exact; no TTL extension'
            else:
                assert redis.command('PTTL', key) == -1
        short_key = 'tenant:1:market:kline:expiry-probe:1M'
        redis.command('SET', short_key, '[7,8]', 'PX', 200)
        expiry_snapshot = redis.snapshot(100000); item = expiry_snapshot['keys'][short_key]
        mutation = {short_key: {field: value for field, value in item.items() if field not in ('pttl', 'capturedAt', 'expiresAt')}}
        redis.apply(expiry_snapshot, mutation, False); time.sleep(0.25)
        expired = redis.apply(expiry_snapshot, mutation, True)
        assert expired == [short_key] and redis.command('GET', short_key) is None
        proof = dict(mysql=sql('SELECT VERSION();'), serverUuid=before['environment']['serverUuid'], database=database, container=mysql,
                     ownerLabelChecked=True, newLoopbackMysqlPort=mysql_port, newLoopbackRedisPort=redis_port,
                     productionExecuted=False, allTenants=[1, 2], monthlyDeleted=result['affected'], monthlyRedisDeleted=result['redisAffected'], repeatDelete=0,
                     fullRestoreSha256=True, nonMonthlyCountsAndContentSha256Unchanged=True, exactMinutePreserved=True,
                     originalSchemaAndTriggersRestored=True, realMigrations=['2026100304', '2026100305', '2026100404', '2026100703'], redisDumpAndPttlRestored=True,
                     unclassifiedTableAndCacheBlocked=True, midTransactionZeroRowCountRolledBackAllMutations=True,
                     originalAbsoluteRedisExpiryPreserved=True, naturallyExpiredMonthlyKeyNotRevived=True, requestOnlyOneMonthAliasPreserved=True)
        retirement.save(output / 'result.json', proof); print(json.dumps(proof))
    finally:
        for container in reversed(containers):
            identity = json.loads(docker('inspect', container))[0]
            if identity['Id'] != container or identity['Config'].get('Labels', {}).get('monthly.retirement.owner') != owner:
                raise RuntimeError('Container ownership changed; refuse removal')
            docker('rm', '-f', '-v', container)
        retirement.save(output / 'commands.json', commands)
        retirement.save(output / 'cleanup.json', dict(containers=containers, removed=True, ownerLabelsChecked=True, anonymousVolumesRemoved=True))


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        print(str(failure), file=sys.stderr); sys.exit(1)
