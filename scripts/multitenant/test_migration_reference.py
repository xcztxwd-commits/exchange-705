"""Offline regression checks; actual migration/restore proof is produced separately on MySQL."""
import unittest
import mysql_migration as migration
from pathlib import Path

class ReferenceColumnsTest(unittest.TestCase):
    def test_original_business_columns_survive_prerequisite_and_new_provenance(self):
        class Database:
            def __init__(self):
                self.metadata={'deposit_record':[['id'],['amount'],['status']],
                               'support_message':[['id'],['text'],['hash']],
                               'user_account':[['id'],['email'],['password_hash'],['current_token']]}
                self.last_query=''
            def columns(self): return self.metadata
            def query(self, sql): self.last_query=sql;return []
        db=Database()
        original=migration.business_columns(db)
        db.metadata['deposit_record'] += [['order_no'],['source'],['account_type']]
        db.metadata[migration.MANIFEST['control'][0]]=[['id'],['config_ready']]
        proof=migration.fingerprint(db,original)
        self.assertEqual(['id','amount','status'],proof['columns']['deposit_record'])
        self.assertIn('password_hash',proof['columns']['user_account'])
        self.assertNotIn('current_token',proof['columns']['user_account'])
        self.assertEqual(['id','text','hash'],proof['columns']['support_message'])
        self.assertIn('HEX(CAST(`amount` AS BINARY))',db.last_query)
        self.assertNotIn('`order_no`',db.last_query)
        self.assertNotIn(migration.MANIFEST['control'][0],proof['columns'])
        self.assertIn('order_no',migration.business_columns(db)['deposit_record'])

    def test_only_exact_legacy_cache_triggers_are_reviewed(self):
        name, expected = next(iter(migration.MANIFEST['legacy_triggers'].items()))
        row = '\t'.join([name] + [expected[k] for k in ('table','event','timing','body_sha256')])
        self.assertEqual(([name], []), migration.reviewed_legacy_triggers([row]))
        changed = row[:-1] + ('0' if row[-1] != '0' else '1')
        self.assertEqual(([], [name]), migration.reviewed_legacy_triggers([changed]))
        self.assertEqual(([], ['unknown']), migration.reviewed_legacy_triggers([row.replace(name,'unknown',1)]))
        self.assertEqual(([], [name]), migration.reviewed_legacy_triggers([row + '\textra']))

    def test_explicit_empty_reference_never_expands_to_new_columns(self):
        class Database:
            def columns(self): raise AssertionError('Reference unexpectedly recomputed')
            def query(self,sql): return []
        self.assertEqual({'columns':{},'tables':{}},migration.fingerprint(Database(),{}))

    def test_existing_activity_columns_are_skipped_only_when_complete_and_exact(self):
        class Database:
            def __init__(self, values): self.values=values
            def query(self, sql): return ['\t'.join((name,)+shape) for name,shape in self.values.items()]
        settings=Path('V2026092903__activity_delivery_settings.sql')
        expected=migration.LEGACY_COLUMN_MIGRATIONS[settings.name]
        self.assertFalse(migration.already_present_legacy_columns(Database({}),settings))
        self.assertTrue(migration.already_present_legacy_columns(Database(expected),settings))
        with self.assertRaises(RuntimeError):
            migration.already_present_legacy_columns(Database({'deleted':expected['deleted']}),settings)
        with self.assertRaises(RuntimeError):
            migration.already_present_legacy_columns(Database({**expected,'deleted':('tinyint(1)','NO','0')}),settings)

if __name__=='__main__':unittest.main()
