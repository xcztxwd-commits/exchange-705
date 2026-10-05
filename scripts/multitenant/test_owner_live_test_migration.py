"""Offline owner-instruction contract tests; not production migration evidence."""
import datetime as dt
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
import controlled_migration as c
import owner_live_test_migration as owner

class OwnerLiveTestTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.db=SimpleNamespace(test=False)
        self.restore=SimpleNamespace(test=True)
        self.source={'server_uuid':'source-fixture','database':'fixture-source'}
        self.other={'server_uuid':'restore-fixture','database':'fixture-restore'}
        self.receipt=self.root/'instruction.json'
        c.publish(self.receipt,{'human_instruction':owner.INSTRUCTION})
        self.value={'kind':owner.KIND,'owner_asserts_no_real_users':True,
                    'instruction_receipt':str(self.receipt),'instruction_sha256':c.core.file_hash(self.receipt),
                    'source':self.source,'restore':self.other,'source_sha256':'s'*64,'migrations':[],
                    'expires_at':(c.now()+dt.timedelta(minutes=5)).isoformat(),
                    'stopped_writers':['offline fixture only'],'recovery_owner':'offline fixture only',
                    'binding':{'plan_sha256':'p'*64}}
        for mock in [patch.object(c,'target',side_effect=lambda db:self.source if db is self.db else self.other),
                     patch.object(c,'sources',return_value='s'*64),patch.object(c,'migrations',return_value=[]),
                     patch.object(c.isolation_gate,'check',return_value=([],{})),patch.object(c,'maintenance')]:
            mock.start();self.addCleanup(mock.stop)

    def policy(self,value=None):
        path=self.root/('authorization-'+str(len(list(self.root.glob('authorization*'))))+'.json')
        c.publish(path,value or self.value)
        return owner.OwnerLiveTestPolicy(path,self.db,self.restore)

    def test_owner_instruction_binds_without_fake_signatures(self):
        policy=self.policy();policy.verify(policy.value,self.value['binding'],'business')
        self.assertFalse(policy.fixture)
        self.assertNotIn('signatures',policy.value)

    def test_explicit_scope_real_user_claim_expiry_and_actual_targets_required(self):
        for value in [dict(self.value,kind='business'),dict(self.value,owner_asserts_no_real_users=False),
                      dict(self.value,expires_at=(c.now()-dt.timedelta(seconds=1)).isoformat()),
                      dict(self.value,source={}),dict(self.value,restore={}),dict(self.value,stopped_writers=[]),
                      dict(self.value,recovery_owner='')]:
            with self.subTest(value=value),self.assertRaises(ValueError):self.policy(value)

    def test_signed_or_fixture_policy_cannot_be_impersonated(self):
        for key in ('signatures','keys','payload','journal_key'):
            with self.subTest(key=key),self.assertRaises(ValueError):self.policy(dict(self.value,**{key:[]}))
        self.db.test=True
        with self.assertRaises(ValueError):self.policy()

    def test_changed_instruction_or_source_rejected(self):
        for key in ('instruction_sha256','source_sha256','migrations'):
            with self.subTest(key=key),self.assertRaises(ValueError):self.policy(dict(self.value,**{key:'changed'}))
        with patch.object(c.isolation_gate,'check',return_value=(['failed'],{})),self.assertRaises(ValueError):self.policy()
        with patch.object(c,'maintenance',side_effect=ValueError('active writers')),self.assertRaises(ValueError):self.policy()

    def test_fixture_scope_or_wrong_binding_rejected(self):
        policy=self.policy()
        for binding,scope in [({},'business'),(self.value['binding'],'isolated-fixture')]:
            with self.assertRaises(ValueError):policy.verify(policy.value,binding,scope)

    def test_ledger_exclusive_chain_detects_tampering_and_missing_sequence(self):
        ledger=owner.OwnerLiveTestLedger(self.root/'ledger',self.policy())
        ledger.append({'kind':'BEGIN'});ledger.append({'kind':'INTENT'})
        self.assertEqual('INTENT',ledger.latest()['kind'])
        path=ledger.directory/'000001.json';original=path.read_bytes()
        path.write_bytes(original.replace(b'INTENT',b'CHANGED'))
        with self.assertRaises(ValueError):ledger.rows()
        path.write_bytes(original);path.rename(ledger.directory/'000002.json')
        with self.assertRaises(ValueError):ledger.rows()

    def test_caller_cannot_forge_ledger_fields_or_policy_type(self):
        with self.assertRaises(ValueError):owner.OwnerLiveTestLedger(self.root/'fake',SimpleNamespace(owner_live_test=True))
        ledger=owner.OwnerLiveTestLedger(self.root/'ledger',self.policy())
        with self.assertRaises(ValueError):ledger.append({'sequence':7})

    def test_duck_typed_owner_flag_cannot_bypass_default_business_route(self):
        proposal={'source_sha256':'s'*64,'migrations':[],'target':self.source}
        ledger=SimpleNamespace(policy=SimpleNamespace(owner_live_test=True))
        with self.assertRaisesRegex(ValueError,'cannot be guessed'):
            c.apply(self.db,proposal,{},self.restore,{},ledger)

if __name__=='__main__':unittest.main()
