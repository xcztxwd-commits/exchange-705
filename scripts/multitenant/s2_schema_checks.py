"""Read-only S2 compatibility inventory for an explicitly identified owned MySQL fixture.

No DDL, writer session variables, approval changes, activation or constraint bypasses.
Metadata checks are not a replacement for actual write-rejection/runtime acceptance tests.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
from build_manifest import validate
from mysql_migration import Database, ROOT, atomic_json, require_fixture

TABLES = ('market_engine_tenant', 'market_engine_runtime', 'market_control_command')
DDL = ROOT / 'exchange-backend/src/main/resources/db/migration'
RUNTIME = 'V2026100304__market_engine_runtime.sql'
FORWARD = 'V2026100305__s2_runtime_tenant_constraints.sql'


def expected_triggers():
    result = {}
    for name in (RUNTIME, FORWARD):
        for match in re.finditer(r'CREATE TRIGGER (\w+) (BEFORE|AFTER) (INSERT|UPDATE|DELETE) ON (\w+) FOR EACH ROW\s*(BEGIN.*?END)\$\$', (DDL / name).read_text(encoding='utf-8'), re.S):
            trigger, timing, event, table, body = match.groups()
            result[trigger] = (table, event, timing, hashlib.sha256(body.encode()).hexdigest())
    return result


def checks(db, reviewed_runtime_sha256):
    results = []
    def check(name, passed, evidence):
        results.append({'test': name, 'passed': bool(passed), 'evidence': evidence})
    manifest = json.loads((ROOT / 'scripts/multitenant/table_manifest.json').read_text(encoding='utf-8'))
    check('current reviewed manifest and packaged epoch', not validate(), validate())
    check('immutable applied 0304 bytes', hashlib.sha256((DDL / RUNTIME).read_bytes()).hexdigest() == reviewed_runtime_sha256, reviewed_runtime_sha256)
    check('S2 additive tables classified private', all(t in manifest['private'] and manifest['introduced_tables'].get(t) == RUNTIME for t in TABLES), list(TABLES))
    check('0305 is the forward compatibility migration', FORWARD in manifest['migration_files'] and manifest['migration_files'].index(FORWARD) > manifest['migration_files'].index(RUNTIME) and manifest['schema_epoch'] >= 2026100305, manifest['schema_epoch'])
    known = db.columns()
    for table in TABLES:
        tenant = [c for c in known.get(table, []) if c[0] == 'tenant_id']
        check(table + ' nonnull tenant_id', len(tenant) == 1 and tenant[0][2] == 'NO', tenant)
    usage = db.query("SELECT TABLE_NAME,CONSTRAINT_NAME,COLUMN_NAME,ORDINAL_POSITION,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('market_engine_tenant','market_engine_runtime','market_control_command') ORDER BY TABLE_NAME,CONSTRAINT_NAME,ORDINAL_POSITION")
    groups = {}
    for row in usage:
        table, constraint, column, position, parent, parent_column = row.split('\t')
        groups.setdefault((table, constraint), []).append((column, parent, parent_column))
    for table in TABLES:
        check(table + ' tenant foreign key', [('tenant_id', 'tenant', 'id')] in [value for (name, key), value in groups.items() if name == table], 'tenant_id -> tenant.id')
    for table in TABLES[1:]:
        expected = [('tenant_id', 'trading_symbol', 'tenant_id'), ('symbol_id', 'trading_symbol', 'id')]
        check(table + ' composite symbol foreign key', expected in [value for (name, key), value in groups.items() if name == table], expected)
    unique = db.query("SELECT INDEX_NAME,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='market_control_command' AND NON_UNIQUE=0 GROUP BY INDEX_NAME ORDER BY INDEX_NAME")
    check('command idempotency key scoped by tenant and symbol', any(row.split('\t')[1] == 'tenant_id,symbol_id,request_key' for row in unique), unique)
    expected = expected_triggers()
    check('reviewed trigger inventory contains 42 fences plus 3 immutable guards', len(expected) == 45 and sum(name.startswith('s2_') for name in expected) == 42, len(expected))
    actual = {}
    for row in db.query("SELECT TRIGGER_NAME,EVENT_OBJECT_TABLE,EVENT_MANIPULATION,ACTION_TIMING,SHA2(ACTION_STATEMENT,256) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() ORDER BY TRIGGER_NAME"):
        name, *shape = row.split('\t'); actual[name] = tuple(shape)
    for name, shape in sorted(expected.items()):
        check('exact trigger ' + name, actual.get(name) == shape, {'expected': shape, 'actual': actual.get(name)})
    epoch = db.query('SELECT version,minimum_application_epoch,business_activation_ready FROM tenant_schema_version ORDER BY version DESC LIMIT 1')
    check('old application epoch rejected; activation remains disabled', epoch == [str(manifest['schema_epoch']) + '\t' + str(manifest['schema_epoch']) + '\t0'], epoch)
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--container', required=True)
    parser.add_argument('--container-id', required=True)
    parser.add_argument('--database', required=True)
    parser.add_argument('--server-uuid', required=True)
    parser.add_argument('--reviewed-runtime-sha256', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists(): raise RuntimeError('Preserve earlier evidence: choose a fresh output path')
    if not re.fullmatch('[0-9a-f]{64}', args.reviewed_runtime_sha256): raise ValueError('Explicit reviewed 0304 byte fingerprint required')
    if not re.fullmatch('mt705_s2_[0-9a-f]{16}', args.database): raise ValueError('Only explicitly owned S2 component fixtures are accepted')
    db = Database(args.container, args.database); require_fixture(db, args.container_id)
    if any(binding['HostIp'] != '127.0.0.1' for bindings in db.identity['ports'].values() for binding in bindings or []):
        raise RuntimeError('MySQL fixture must bind loopback only')
    if db.query('SELECT @@server_uuid') != [args.server_uuid]: raise RuntimeError('Live server UUID mismatch')
    report = {'target': db.identity, 'server_uuid': args.server_uuid, 'scope': 'read-only exact S2 DDL/compatibility metadata; behavioral rejection verified separately', 'passed': False, 'tests': []}
    try:
        report['tests'] = checks(db, args.reviewed_runtime_sha256)
        report['passed'] = all(row['passed'] for row in report['tests'])
    finally:
        report['counts'] = {'passed': sum(row['passed'] for row in report['tests']), 'failed': sum(not row['passed'] for row in report['tests']), 'skipped': 0}
        atomic_json(args.output, report)
    print(json.dumps(report['counts']))
    raise SystemExit(0 if report['passed'] else 1)


if __name__ == '__main__': main()
