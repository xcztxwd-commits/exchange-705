"""Additional offline local control-flow checks, not native migration evidence."""
import copy
import hashlib
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import controlled_migration as c
import local_test_migration as local
import test_local_test_migration as setup
from test_local_test_migration import FakeDb,SOURCE,MIGRATIONS

class LocalFlowReviewTests(unittest.TestCase):
    setUp=setup.LocalModeReviewTests.setUp
    tearDown=setup.LocalModeReviewTests.tearDown
    target=setup.LocalModeReviewTests.target
    transport=setup.LocalModeReviewTests.transport
    write_auth=setup.LocalModeReviewTests.write_auth
    policy=setup.LocalModeReviewTests.policy
    binding=setup.LocalModeReviewTests.binding
    def state(self):return {'schema':{'objects':{'table:toy':'a'*64},'sha256':'b'*64},'data':{'columns':{},'tables':{}}}
    def proposal(self):return {'initial':self.state(),'start':1,'target':copy.deepcopy(self.source),'source_sha256':SOURCE,
                               'migrations':copy.deepcopy(MIGRATIONS),'schema_epoch':2026100403}
    def proof_fixture(self):
        target=copy.deepcopy(self.restore);target['database']=self.source['database']+'_restore'
        target['physical']['database']=target['database'];return FakeDb(target)
    def dump(self,path):
        path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(b'OFFLINE-TOY-BACKUP-NOT-MYSQL')
        return {'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'schema_only':False}
    def restore_input(self,db,backup,path):
        path.write_bytes(b'OFFLINE-TOY-RESTORE-NOT-SQL')
        return {'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
    def test_initial_local_backup_accepts_same_single_observed_baseline_ledger_without_fake_begin(self):
        policy=self.policy();ledger=local.LocalLedger(self.root/'ledger',policy)
        imported=self.dump(self.root/'imported0402.sql');restored=self.proof_fixture()
        with patch.object(c,'state',side_effect=lambda db:copy.deepcopy(self.state())):
            local.observe_local_baseline(self.db,self.other,self.state(),imported,ledger)
            with patch.object(FakeDb,'dump',side_effect=self.dump,create=True),\
                    patch.object(FakeDb,'create_empty',return_value=None,create=True),\
                    patch.object(FakeDb,'restore_file',return_value=None,create=True),\
                    patch.object(c,'restore_input',side_effect=self.restore_input):
                proof=c.verify_backup(self.db,self.proposal(),restored,self.root/'proof.json',ledger,local_policy=policy)
        self.assertEqual('PASS',proof['result']);self.assertIsNone(proof['ledger_tip'])
        self.assertEqual(['LOCAL_BASELINE_OBSERVED'],[x['kind'] for x in ledger.rows()])
    def test_default_backup_still_rejects_equal_datadir_text_without_local_policy(self):
        with patch.object(c,'state',return_value=self.state()),self.assertRaises(ValueError):
            c.verify_backup(self.db,self.proposal(),self.other,self.root/'proof.json')
    def test_fake_local_policy_cannot_remove_independence_guard(self):
        with patch.object(c,'state',return_value=self.state()),self.assertRaises(ValueError):
            c.verify_backup(self.db,self.proposal(),self.other,self.root/'proof.json',local_policy=SimpleNamespace(local=True))
    def test_plan_rejects_duck_typed_local_policy_on_baseline(self):
        state=self.state();row={'kind':'LOCAL_BASELINE_OBSERVED','target':copy.deepcopy(self.source),'state':state,
            'source_sha256':SOURCE,'migrations':copy.deepcopy(MIGRATIONS[:-1]),'imported_backup':self.dump(self.root/'old.sql')}
        policy=SimpleNamespace(local=True,restore_db=self.other,guard_targets=lambda *args:None)
        ledger=SimpleNamespace(policy=policy,latest=lambda:row)
        with patch.object(c.isolation_gate,'check',return_value=([],{})),\
                patch.object(c,'state',return_value=state),\
                patch.object(FakeDb,'tables',return_value=['tenant_schema_version'],create=True),\
                patch.object(c,'preserved',return_value={}),self.assertRaises(ValueError):
            c.plan(self.db,self.root/'plan.json',ledger)
if __name__=='__main__':unittest.main()
