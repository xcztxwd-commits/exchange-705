"""Offline parser/gate checks only. Fake metadata is never actual MySQL evidence."""
import hashlib
import unittest
import s2_schema_checks as schema


class FakeMetadata:
    def __init__(self):
        self.epoch = '2026100402\t2026100402\t0'
        self.missing_foreign_key = False
        self.changed_trigger = False
    def columns(self):
        return {table: [['tenant_id', 'bigint', 'NO', '__NO_DEFAULT__', '']] for table in schema.TABLES}
    def query(self, sql):
        if 'KEY_COLUMN_USAGE' in sql:
            rows = [f'{table}\towner\ttenant_id\t1\ttenant\tid' for table in schema.TABLES]
            if self.missing_foreign_key: rows.pop()
            for table in schema.TABLES[1:]:
                rows.extend([f'{table}\tsymbol\ttenant_id\t1\ttrading_symbol\ttenant_id', f'{table}\tsymbol\tsymbol_id\t2\ttrading_symbol\tid'])
            return rows
        if 'STATISTICS' in sql: return ['command_request\ttenant_id,symbol_id,request_key']
        if 'TRIGGERS' in sql:
            rows = ['\t'.join((name,) + shape) for name, shape in schema.expected_triggers().items()]
            if self.changed_trigger: rows[0] = rows[0][:-64] + '0' * 64
            return rows
        if 'tenant_schema_version' in sql: return [self.epoch]
        raise AssertionError('Unexpected query: ' + sql)


class S2SchemaInventoryTest(unittest.TestCase):
    def run_checks(self, db):
        return schema.checks(db, hashlib.sha256((schema.DDL / schema.RUNTIME).read_bytes()).hexdigest())
    def test_complete_metadata_is_registered_but_remains_metadata_only(self):
        rows = self.run_checks(FakeMetadata())
        self.assertTrue(all(row['passed'] for row in rows), [row for row in rows if not row['passed']])
        self.assertEqual(45, len(schema.expected_triggers()))
    def test_missing_fk_changed_fence_and_old_epoch_fail_closed(self):
        for field, value in [('missing_foreign_key', True), ('changed_trigger', True), ('epoch', '2026100201\t2026100201\t1')]:
            db = FakeMetadata(); setattr(db, field, value)
            self.assertTrue(any(not row['passed'] for row in self.run_checks(db)), field)
    def test_changed_applied_bytes_fail_without_auto_accepting_new_hash(self):
        rows = schema.checks(FakeMetadata(), '0' * 64)
        self.assertFalse(next(row['passed'] for row in rows if row['test'] == 'immutable applied 0304 bytes'))


if __name__ == '__main__': unittest.main()
