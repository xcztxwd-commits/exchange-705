"""Stage-one constraints on a caller-supplied, dedicated actual MySQL test database.

Each mutation runs in its own transaction and rolls back (including failures).
Never creates/reuses old containers, changes approvals, or disables constraints.
"""
import argparse
import json
from pathlib import Path
import re
from mysql_migration import Database, fixture_insert, atomic_json

TABLES = ('activity_selection', 'activity_selection_member', 'activity_send_receipt',
          'trial_grant', 'announcement_receipt')


def checks(db, results=None):
    if not db.test:
        raise RuntimeError('Constraint checks require a labelled dedicated test container')
    for bindings in db.identity['ports'].values():
        if any(b['HostIp'] != '127.0.0.1' for b in bindings or []):
            raise RuntimeError('Constraint test MySQL must bind loopback only')
    metadata = db.columns()
    statements = []
    class Collector:
        def sql(self, sql): statements.append(sql)
    def insert(table, values): fixture_insert(Collector(), table, values, metadata)
    for tenant in (901, 902):
        insert('tenant', {'id': tenant, 'code': 'constraint-' + str(tenant),
                         'name': 'Constraint fixture', 'status': 'DRAFT'})
        insert('user_account', {'id': tenant * 100 + 1, 'tenant_id': tenant,
                               'email': 'constraint-' + str(tenant) + '@fixture.invalid',
                               'password_hash': 'NON_LOGIN_FIXTURE', 'phone': None})
        insert('activity_campaign', {'id': tenant * 100 + 11, 'tenant_id': tenant})
        insert('announcement', {'id': tenant * 100 + 21, 'tenant_id': tenant})
        insert('activity_delivery', {'id': tenant * 100 + 41, 'tenant_id': tenant,
                                    'campaign_id': tenant * 100 + 11, 'user_id': tenant * 100 + 1})
        insert('activity_selection', {'id': tenant * 100 + 31, 'tenant_id': tenant,
                                     'campaign_id': tenant * 100 + 11, 'operation_id': 'shared-operation'})
        insert('activity_selection_member', {'id': tenant * 100 + 61, 'tenant_id': tenant,
                                            'selection_id': tenant * 100 + 31, 'user_id': tenant * 100 + 1})
        insert('activity_send_receipt', {'id': tenant * 100 + 71, 'tenant_id': tenant,
                                        'campaign_id': tenant * 100 + 11, 'operation_id': 'shared-operation'})
        insert('trial_grant', {'id': tenant * 100 + 51, 'tenant_id': tenant,
                              'user_id': tenant * 100 + 1, 'campaign_id': tenant * 100 + 11,
                              'delivery_id': tenant * 100 + 41, 'request_key': 'shared-request'})
        insert('announcement_receipt', {'id': tenant * 100 + 81, 'tenant_id': tenant,
                                       'user_id': tenant * 100 + 1, 'announcement_id': tenant * 100 + 21})
    prefix = 'START TRANSACTION;\n' + '\n'.join(statements) + '\n'
    if results is None: results = []
    def expect(name, operation='', errno=None):
        result = db.sql(prefix + operation + ';\nROLLBACK;', check=False)
        error = re.search(r'ERROR (\d+) \(', result.stderr.decode('utf-8', errors='replace'))
        passed = result.returncode == 0 if errno is None else result.returncode != 0 and error and int(error[1]) == errno
        entry = {'test': name, 'passed': bool(passed), 'exit_code': result.returncode,
                 'expected_mysql_errno': errno, 'mysql_errno': int(error[1]) if error else None}
        results.append(entry)
        if not passed: raise AssertionError(json.dumps(entry))
    expect('A/B independently accept identical operation/request keys')
    for table, row in zip(TABLES, (90131, 90161, 90171, 90151, 90181)):
        expect(table + ' immutable owner', 'UPDATE ' + table + ' SET tenant_id=902 WHERE id=' + str(row), 1644)
        columns = [field[0] for field in metadata[table] if field[0] != 'id']
        expect(table + ' duplicate receipt rejected',
               'INSERT INTO ' + table + '(' + ','.join(columns) + ') SELECT ' + ','.join(columns)
               + ' FROM ' + table + ' WHERE id=' + str(row), 1062)
    relations = [('activity_selection', 90131, 'campaign_id', 90211),
                 ('activity_selection_member', 90161, 'selection_id', 90231),
                 ('activity_selection_member', 90161, 'user_id', 90201),
                 ('activity_send_receipt', 90171, 'campaign_id', 90211),
                 ('trial_grant', 90151, 'user_id', 90201),
                 ('trial_grant', 90151, 'campaign_id', 90211),
                 ('trial_grant', 90151, 'delivery_id', 90241),
                 ('announcement_receipt', 90181, 'user_id', 90201),
                 ('announcement_receipt', 90181, 'announcement_id', 90221)]
    for table, row, field, foreign_id in relations:
        expect(table + '.' + field + ' rejects foreign tenant',
               'UPDATE ' + table + ' SET ' + field + '=' + str(foreign_id) + ' WHERE id=' + str(row), 1452)
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--container', required=True)
    parser.add_argument('--database', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists(): raise RuntimeError('Keep every earlier test result; select a fresh output file')
    db = Database(args.container, args.database)
    report = {'target': db.identity, 'passed': False, 'tests': [], 'scope': 'Actual MySQL stage-one additive tenant constraints'}
    try:
        checks(db, report['tests'])
        report['passed'] = True
        report['counts'] = {'passed': len(report['tests']), 'failed': 0, 'skipped': 0}
    except Exception as error:
        report['error'] = str(error)
        raise
    finally:
        report['counts'] = {'passed': sum(t['passed'] for t in report['tests']),
                            'failed': sum(not t['passed'] for t in report['tests']), 'skipped': 0}
        atomic_json(args.output, report)
    print('STAGE1_MYSQL_SCHEMA_PASS checks=' + str(len(report['tests'])))


if __name__ == '__main__': main()
