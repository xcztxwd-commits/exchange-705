"""Offline recovery contract checks; synthetic receipts here are NOT MySQL evidence."""
import copy
import datetime as dt
import hashlib
import hmac
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import controlled_migration as c
import forward_recovery as f


class ForwardRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='mt705-forward-unit-');self.root=Path(self.tmp.name)
        keys={}
        for name in ['operator','approver','journal']:
            path=self.root/(name+'.key');path.write_bytes(('unit-only-'+name+'-'*32).encode())
            keys[name]={'role':name,'key_file':str(path)}
        c.publish(self.root/'policy.json',{'scope':'isolated-fixture','keys':keys,'journal_key':'journal'})
        self.policy=c.Policy(self.root/'policy.json',True)
        self.before={'unit':'before'};self.after={'unit':'after'}
        self.original={'id':'old','start':0,'target':{'server_uuid':'source'},'initial':self.before,
                       'migrations':c.migrations(),'schema_epoch':c.core.EPOCH,'source_sha256':'unit-input',
                       'preservation_columns':{'unit':['id']},'preserved_sha256':'unit-preserved'}
        self.reference_plan={**self.original,'id':'reference','target':{'server_uuid':'independent'}}
        self.failed=c.Ledger(self.root/'failed',self.policy);self.reference=c.Ledger(self.root/'reference',self.policy)
        for ledger,plan in [(self.failed,self.original),(self.reference,self.reference_plan)]:
            base={'plan_sha256':c.digest(plan),'target':plan['target']}
            ledger.append({**base,'kind':'BEGIN','state':self.before,'next':0})
            ledger.append({**base,'kind':'INTENT','index':0,'before':self.before,'migration':plan['migrations'][0]})
            ledger.append({**base,**({'kind':'FAILED_UNCERTAIN','index':0} if ledger is self.failed else
                {'kind':'PHASE_COMPLETE','next':1,'state':self.after,'migration':plan['migrations'][0]})})
        self.recovery={'failed_plan':self.original,'failed_ledger':str(self.failed.directory),
                       'failed_tip':c.digest(self.failed.latest()),'reference_plan':self.reference_plan,
                       'reference_ledger':str(self.reference.directory),'reference_tip':c.digest(self.reference.latest())}
        self.proposal={**self.original,'id':'new','initial':self.after,'start':1,'recovery':self.recovery,'business_activation_ready':False}
        self.approval={'payload':{'recovery_review':{'evidence_sha256':c.digest(self.recovery),'decision':'FORWARD_ONLY','reason':'unit review'}}}

    def tearDown(self):self.tmp.cleanup()
    def boundary(self):return f.boundary(self.original,self.failed,self.reference_plan,self.reference)

    def test_exact_signed_boundary_skips_only_completed_phase(self):
        self.assertEqual((self.after,1),self.boundary()[:2]);f.validate_apply(self.proposal,self.approval,self.policy)

    def test_failure_receipts_never_changed(self):
        before={p.name:p.read_bytes() for p in self.failed.directory.iterdir()}
        f.validate_apply(self.proposal,self.approval,self.policy)
        self.assertEqual(before,{p.name:p.read_bytes() for p in self.failed.directory.iterdir()})

    def test_missing_or_non_forward_review_refused(self):
        for value in [{},{'decision':'RESUME'},{'decision':'FORWARD_ONLY','evidence_sha256':'wrong','reason':'x'}]:
            with self.subTest(value=value),self.assertRaisesRegex(ValueError,'Explicit signed'):
                f.validate_apply(self.proposal,{'payload':{'recovery_review':value}},self.policy)

    def test_rebased_facts_target_and_start_refused(self):
        for key,value in [('start',2),('target',{'server_uuid':'other'}),('initial',self.before),
                          ('preserved_sha256','changed'),('preservation_columns',{}),('business_activation_ready',True)]:
            with self.subTest(key=key),self.assertRaises(ValueError):
                f.validate_apply({**self.proposal,key:value},self.approval,self.policy)

    def test_changed_reference_ledger_refused(self):
        self.reference.append({**self.reference.latest(),'sequence':3,'kind':'COMPLETE'})
        with self.assertRaises(ValueError):f.validate_apply(self.proposal,self.approval,self.policy)

    def test_tampered_failure_signature_refused(self):
        path=self.failed.directory/'000002.json';value=c.read(path);value['body']['kind']='PHASE_COMPLETE'
        path.write_bytes(c.canonical(value))
        with self.assertRaises(ValueError):self.boundary()

    def test_missing_failure_receipt_refused(self):
        (self.failed.directory/'000002.json').unlink()
        with self.assertRaisesRegex(ValueError,'FAILED_UNCERTAIN'):self.boundary()

    def test_same_physical_reference_refused(self):
        # Rebind test witness with valid unit signatures, still invalid independent target.
        plan={**self.reference_plan,'target':self.original['target']}
        ledger=c.Ledger(self.root/'same-physical',self.policy)
        for row in self.reference.rows():ledger.append({k:v for k,v in {**row,'plan_sha256':c.digest(plan),'target':plan['target']}.items() if k not in ('sequence','previous','at')})
        with self.assertRaisesRegex(ValueError,'Independent reference'):
            f.boundary(self.original,self.failed,plan,ledger)

    def test_different_reference_before_state_refused(self):
        ledger=c.Ledger(self.root/'wrong-before',self.policy)
        for row in self.reference.rows():
            row={k:v for k,v in row.items() if k not in ('sequence','previous','at')}
            if row['kind']=='INTENT':row['before']={'unit':'different'}
            ledger.append(row)
        with self.assertRaisesRegex(ValueError,'identical before-state'):
            f.boundary(self.original,self.failed,self.reference_plan,ledger)

    def test_review_payload_is_covered_by_real_signature_verification(self):
        payload={'binding':{'unit':True},'scope':'isolated-fixture','expires_at':(c.now()+dt.timedelta(minutes=2)).isoformat(),
                 'stopped_writers':['unit'],'recovery_owner':'unit',**self.approval['payload']}
        signed={'payload':payload,'signatures':[{'signer':n,'sha256':hmac.new(self.policy.key(n),c.canonical(payload),hashlib.sha256).hexdigest()} for n in ['operator','approver']]}
        self.policy.verify(signed,{'unit':True},'isolated-fixture')
        signed=copy.deepcopy(signed);signed['payload']['recovery_review']['reason']='forged'
        with self.assertRaisesRegex(ValueError,'signature invalid'):self.policy.verify(signed,{'unit':True},'isolated-fixture')

    def test_partial_unknown_and_new_rows_refuse_reconciliation(self):
        class Database: test=True
        for state in [self.before,{'unit':'partial DDL'},{'unit':'after','new_rows':1},None]:
            with self.subTest(state=state),patch.object(c.isolation_gate,'check',return_value=([],{})),patch.object(c,'maintenance'),patch.object(c,'target',return_value=self.original['target']),patch.object(c,'state',return_value=state):
                with self.assertRaisesRegex(ValueError,'Actual full DDL/rows differ'):
                    f.reconcile(Database(),self.original,self.failed,self.reference_plan,self.reference,self.root/'forbidden.json')
                self.assertFalse((self.root/'forbidden.json').exists())

    def test_changed_migration_bytes_refused_without_publication(self):
        changed=copy.deepcopy(c.migrations());changed[0]['sha256']='0'*64
        with patch.object(c,'migrations',return_value=changed),self.assertRaisesRegex(ValueError,'Independent reference'):
            self.boundary()


if __name__=='__main__':unittest.main()
