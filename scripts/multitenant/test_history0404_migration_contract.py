"""Offline guards only; actual MySQL migration/restore require their own receipts."""
import copy
import unittest
from unittest.mock import patch
import controlled_migration as c
import local_test_migration as local
import test_local_test_migration as old
from test_source0403_metadata_contract import Db

FILES=copy.deepcopy(old.MIGRATIONS)+[{'name':local.HISTORY_MIGRATION,'sha256':'d'*64}]

class HistoryMetadataTests(unittest.TestCase):
    def test_only_exact_reviewed_0403_to_0404_tail(self):
        self.assertEqual(c.HISTORY0404_METADATA,c.metadata_append_contract(2,FILES))
        self.assertIsNone(c.metadata_append_contract(1,FILES))
        self.assertIsNone(c.metadata_append_contract(1,[FILES[0],FILES[2]]))
        self.assertIsNone(c.metadata_append_contract(2,FILES+[{'name':'V2026100405__unknown.sql'}]))

    def test_existing_receipt_cannot_infer_success_and_only_exact_inactive_receipt_passes(self):
        c.metadata_absent_before_plan(Db(count='0'),c.HISTORY0404_METADATA)
        with self.assertRaises(ValueError):c.metadata_absent_before_plan(Db(count='1'),c.HISTORY0404_METADATA)
        db=Db(count='1');c.metadata_receipt_after_phase(db,c.HISTORY0404_METADATA)
        self.assertIn('version=2026100404 AND minimum_application_epoch=2026100404 AND business_activation_ready=0',db.sql[0])
        with self.assertRaises(ValueError):c.metadata_receipt_after_phase(Db(count='0'),c.HISTORY0404_METADATA)

    def test_no_old_money_session_metadata_transform_or_row_omission(self):
        db=Db(['tenant_schema_version\t'+'a'*64]*2)
        rows=c.preserved(db,{'tenant_schema_version':['version','applied_at'],'user_account':['current_token'],
                             'deposit_record':['source','order_no','account_type']},c.HISTORY0404_METADATA)
        sql=db.sql[0]
        self.assertEqual(1,sql.count(' WHERE version<>2026100404'))
        self.assertNotIn('version<>2026100403',sql);self.assertNotIn('COALESCE(',sql);self.assertNotIn('CAST(NULL',sql)
        self.assertEqual(2,rows['tenant_schema_version']['rows'])
        for bad in [dict(c.HISTORY0404_METADATA,minimum_application_epoch=2026100403),{},dict(c.HISTORY0404_METADATA,business_activation_ready=True)]:
            db=Db()
            with self.assertRaises(ValueError):c.preserved(db,{},bad)
            self.assertEqual([],db.sql)

class HistoryDb(old.FakeDb):
    def __init__(self,identity):
        super().__init__(identity);self.epoch=self.minimum_epoch='2026100403'
    def query(self,sql):
        if 'COUNT(*) FROM tenant_schema_version WHERE version=2026100404' in sql:
            self.sql_seen.append(sql);return [self.new_receipts]
        return super().query(sql)

class LocalHistoryTests(unittest.TestCase):
    def setUp(self):
        self.fixture=old.LocalModeReviewTests('runTest');self.fixture.setUp()
        f=self.fixture;f.db=HistoryDb(f.source);f.other=HistoryDb(f.restore)
        self.overrides=[patch.object(c.core,'EPOCH',2026100404),patch.object(c,'migrations',return_value=copy.deepcopy(FILES))]
        for value in self.overrides:value.start()
        f.authorization['migrations']=copy.deepcopy(FILES);f.write_auth()
    def tearDown(self):
        for value in reversed(self.overrides):value.stop()
        self.fixture.tearDown()
    def test_actual_403_namespace_can_continue_without_rebuilding_capacity(self):
        self.fixture.policy()
        value,_=self.fixture.baseline();self.assertEqual('LOCAL_BASELINE_OBSERVED',value['kind'])
        self.assertEqual(FILES[:-1],value['migrations'])
    def test_new_history_roles_are_phase_bound_and_not_arbitrary_namespaces(self):
        f=self.fixture
        for role in sorted(local.HISTORY_RESTORE_ROLES):
            f.restore['database']=f.source['database']+'_'+role
            f.restore['physical']['database']=f.restore['database'];f.other.database=f.restore['database']
            f.authorization['restore']=copy.deepcopy(f.restore);f.write_auth();f.policy()
            self.assertLessEqual(len(f.restore['database']),64)
            f.authorization['migrations']=copy.deepcopy(old.MIGRATIONS);f.write_auth()
            with patch.object(c.core,'EPOCH',2026100403),patch.object(c,'migrations',return_value=copy.deepcopy(old.MIGRATIONS)):
                with self.assertRaisesRegex(ValueError,'Restore must use a new namespace'):f.policy()
            f.authorization['migrations']=copy.deepcopy(FILES);f.write_auth()
        for suffix in ['h4_anything','h4_restore_2','h5_restore']:
            f.restore['database']=f.source['database']+'_'+suffix
            f.restore['physical']['database']=f.restore['database'];f.other.database=f.restore['database']
            f.authorization['restore']=copy.deepcopy(f.restore);f.write_auth()
            with self.assertRaisesRegex(ValueError,'Restore must use a new namespace'):f.policy()

    def test_new_404_namespace_exact_identity_is_required(self):
        f=self.fixture
        for target in [f.source,f.restore]:
            target['database']=target['database'].replace('local403_','local404_');target['physical']['database']=target['database']
        f.db.database=f.source['database'];f.other.database=f.restore['database']
        f.authorization.update(source=copy.deepcopy(f.source),restore=copy.deepcopy(f.restore));f.write_auth();f.policy()
        f.source['database']=f.source['database'].replace('local404_','unowned_')
        f.source['physical']['database']=f.source['database'];f.authorization['source']=copy.deepcopy(f.source);f.write_auth()
        with self.assertRaises(ValueError):f.policy()
    def test_402_or_activated_or_wrong_minimum_baseline_is_rejected(self):
        f=self.fixture
        for epoch,minimum,ready in [('2026100402','2026100402','0'),('2026100403','2026100402','0'),('2026100403','2026100403','1')]:
            f.db.epoch,f.db.minimum_epoch,f.db.ready=epoch,minimum,ready
            with self.assertRaises(ValueError):f.policy()
    def test_404_receipt_already_present_is_not_a_baseline(self):
        self.fixture.db.new_receipts='1'
        with self.assertRaises(ValueError):self.fixture.baseline()
    def test_unknown_or_wrong_final_migration_not_authorized(self):
        with patch.object(c.core,'EPOCH',2026100405),self.assertRaises(ValueError):self.fixture.policy()
        changed=copy.deepcopy(FILES);changed[-1]['name']='V2026100404__other.sql'
        self.fixture.authorization['migrations']=changed;self.fixture.write_auth()
        with patch.object(c,'migrations',return_value=changed),self.assertRaises(ValueError):self.fixture.policy()

if __name__=='__main__':unittest.main()
