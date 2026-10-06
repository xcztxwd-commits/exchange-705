"""Offline contract checks only; native DDL/restore evidence has its own runner."""
import copy
import unittest
from unittest.mock import patch

import controlled_migration as c
import local_test_migration as local
import test_local_test_migration as old
from test_source0403_metadata_contract import Db

FILES = [{'name':'V2026100603__tenant_entry_frontend_roles.sql','sha256':'a'*64}] + [
    {'name':name,'sha256':str(index)*64} for index,name in enumerate(c.CONTROL0702_TAIL,1)]


class ControlTailTests(unittest.TestCase):
    def test_exact_two_tail_only_after_0603(self):
        self.assertEqual(c.CONTROL0702_METADATA,c.metadata_append_contract(1,FILES))
        bad = [FILES[:-1],FILES[1:],FILES + [{'name':'V2026100703__unknown.sql'}],
               [FILES[0],FILES[2],FILES[1]],
               [dict(FILES[0],name='V2026100602__user_avatar.sql'),*FILES[1:]],
               [FILES[0],dict(FILES[1],name='V2026100701__other.sql'),FILES[2]]]
        for files in bad:
            with self.subTest(files=files):self.assertIsNone(c.metadata_append_contract(1,files))
        self.assertIsNone(c.metadata_append_contract(0,FILES))
        self.assertIsNone(c.metadata_append_contract(2,FILES))

    def test_0701_has_no_receipt_and_0702_is_inactive_0603_compatible(self):
        first=Db(count='0');c.metadata_receipt_after_phase(first,c.CONTROL0702_METADATA,c.CONTROL0702_TAIL[0])
        with self.assertRaises(ValueError):c.metadata_receipt_after_phase(Db(count='1'),c.CONTROL0702_METADATA,c.CONTROL0702_TAIL[0])
        final=Db(count='1');c.metadata_receipt_after_phase(final,c.CONTROL0702_METADATA,c.CONTROL0702_TAIL[1])
        self.assertIn('version=2026100702 AND minimum_application_epoch=2026100603 AND business_activation_ready=0',final.sql[0])
        with self.assertRaises(ValueError):c.metadata_receipt_after_phase(Db(count='0'),c.CONTROL0702_METADATA,c.CONTROL0702_TAIL[1])
        with self.assertRaises(ValueError):c.metadata_receipt_after_phase(Db(count='1'),c.CONTROL0702_METADATA,'unknown.sql')

    def test_old_metadata_and_cancel_credentials_are_not_excluded(self):
        db=Db(['tenant_schema_version\t'+'a'*64,'market_control_command\t'+'b'*64])
        columns={'tenant_schema_version':['version','applied_at','minimum_application_epoch','business_activation_ready'],
                 'market_control_command':['tenant_id','request_key','state','parameters_json'],
                 'user_account':['current_token'],'deposit_record':['source','order_no','account_type']}
        actual=c.preserved(db,columns,c.CONTROL0702_METADATA)
        sql=db.sql[0]
        self.assertEqual(1,sql.count(' WHERE version<>2026100702'))
        self.assertNotIn('version<>2026100603',sql);self.assertNotIn('COALESCE(',sql);self.assertNotIn('CAST(NULL',sql)
        proposal={'preserved_sha256':c.digest(actual),'history_revision_seed':None}
        c.verify_preserved(proposal,actual,1)
        changed=copy.deepcopy(actual);changed['market_control_command']['sha256']='c'*64
        with self.assertRaises(ValueError):c.verify_preserved(proposal,changed,1)

    def test_receipt_replay_and_minimum_epoch_changes_fail_closed(self):
        with self.assertRaises(ValueError):c.metadata_absent_before_plan(Db(count='1'),c.CONTROL0702_METADATA)
        for bad in [dict(c.CONTROL0702_METADATA,minimum_application_epoch=2026100702),
                    dict(c.CONTROL0702_METADATA,business_activation_ready=True)]:
            with self.assertRaises(ValueError):c.metadata_version(bad)


class Local0702Db(old.FakeDb):
    def __init__(self,identity):
        super().__init__(identity);self.epoch=self.minimum_epoch='2026100603'
    def query(self,sql):
        if 'COUNT(*) FROM tenant_schema_version WHERE version=2026100702' in sql:
            self.sql_seen.append(sql);return [self.new_receipts]
        return super().query(sql)


class LocalControlTailTests(unittest.TestCase):
    def setUp(self):
        self.fixture=old.LocalModeReviewTests('runTest');self.fixture.setUp();f=self.fixture
        for identity in (f.source,f.restore):
            identity['database']=identity['database'].replace('local403_','local702_')
            identity['physical']['database']=identity['database']
        f.db=Local0702Db(f.source);f.other=Local0702Db(f.restore)
        self.overrides=[patch.object(c.core,'EPOCH',2026100702),patch.object(c,'migrations',return_value=copy.deepcopy(FILES))]
        for value in self.overrides:value.start()
        f.authorization.update(migrations=copy.deepcopy(FILES),source=copy.deepcopy(f.source),restore=copy.deepcopy(f.restore));f.write_auth()
    def tearDown(self):
        for value in reversed(self.overrides):value.stop()
        self.fixture.tearDown()
    def test_current_schema_keeps_0603_minimum_and_cannot_activate(self):
        f=self.fixture;f.db.epoch='2026100702';f.policy()
        for minimum,ready in [('2026100702','0'),('2026100603','1')]:
            f.db.minimum_epoch,f.db.ready=minimum,ready
            with self.assertRaises(ValueError):f.policy()
    def test_local_observation_excludes_both_not_only_the_last_migration(self):
        f=self.fixture;value,_=f.baseline()
        self.assertEqual(FILES[:-2],value['migrations'])
        self.assertEqual('LOCAL_BASELINE_OBSERVED',value['kind'])
        self.assertFalse(value['activation_ready'])
    def test_unknown_tail_or_wrong_namespace_cannot_be_authorized(self):
        f=self.fixture;changed=copy.deepcopy(FILES);changed[-2]['name']='V2026100701__other.sql'
        f.authorization['migrations']=changed;f.write_auth()
        with patch.object(c,'migrations',return_value=changed),self.assertRaises(ValueError):f.policy()
        f.authorization['migrations']=copy.deepcopy(FILES);f.source['database']=f.source['database'].replace('local702_','local403_')
        f.source['physical']['database']=f.source['database'];f.db.database=f.source['database'];f.authorization['source']=copy.deepcopy(f.source);f.write_auth()
        with self.assertRaises(ValueError):f.policy()


if __name__=='__main__':unittest.main()
