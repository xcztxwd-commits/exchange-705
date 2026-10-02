"""Small offline checks. Native implicit-commit/restore tests run separately; these are not MySQL evidence."""
import datetime as dt
import hashlib
import hmac
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
import controlled_migration as tool

class ControlledMigrationTests(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory(prefix='mt705-receipt-unit-')
        self.root=Path(self.temporary.name).resolve()
        self.assertTrue(self.root.is_relative_to(Path(tempfile.gettempdir()).resolve()))
        for name in ['operator','approver','journal']:(self.root/(name+'.key')).write_bytes(('fixture-only-key-'+name+'-'*32).encode())
        keys={n:{'role':n,'key_file':str(self.root/(n+'.key'))} for n in ['operator','approver','journal']}
        tool.publish(self.root/'policy.json',{'scope':'isolated-fixture','keys':keys,'journal_key':'journal'})
        self.policy=tool.Policy(self.root/'policy.json',True)
        self.binding={'plan_sha256':'a'*64,'restore_proof_sha256':'b'*64,'backup_sha256':'c'*64,'target_sha256':'d'*64,'source_sha256':'e'*64}
    def tearDown(self):
        self.assertTrue(self.root.is_relative_to(Path(tempfile.gettempdir()).resolve()))
        self.temporary.cleanup()
    def approval(self):
        payload={'binding':self.binding,'scope':'isolated-fixture','expires_at':(tool.now()+dt.timedelta(minutes=5)).isoformat(),'stopped_writers':['synthetic-writer'],'recovery_owner':'fixture-maintainer'}
        return {'payload':payload,'signatures':[{'signer':n,'sha256':hmac.new(self.policy.key(n),tool.canonical(payload),hashlib.sha256).hexdigest()} for n in ['operator','approver']]}
    def test_canonical_binding_is_order_independent(self):
        self.assertEqual(tool.digest({'a':1,'b':2}),tool.digest({'b':2,'a':1}))
    def test_durable_publication_never_overwrites_prior_evidence(self):
        path=self.root/'plan.json';tool.publish(path,{'old':True});before=path.read_bytes()
        with self.assertRaises(ValueError):tool.publish(path,{'new':True})
        self.assertEqual(before,path.read_bytes())
    def test_two_bound_fixture_signatures_validate_without_real_approval_claim(self):
        self.policy.verify(self.approval(),self.binding,'isolated-fixture')
    def test_other_plan_or_scope_cannot_use_approval(self):
        for binding,scope in [(dict(self.binding,plan_sha256='0'*64),'isolated-fixture'),(self.binding,'business')]:
            with self.assertRaises(ValueError):self.policy.verify(self.approval(),binding,scope)
    def test_tampered_payload_signature_fails(self):
        value=self.approval();value['payload']['recovery_owner']='altered'
        with self.assertRaises(ValueError):self.policy.verify(value,self.binding,'isolated-fixture')
    def test_single_signer_is_not_an_approval(self):
        value=self.approval();value['signatures']=value['signatures'][:1]
        with self.assertRaises(ValueError):self.policy.verify(value,self.binding,'isolated-fixture')
    def test_expired_approval_fails(self):
        value=self.approval();value['payload']['expires_at']=(tool.now()-dt.timedelta(minutes=1)).isoformat()
        with self.assertRaises(ValueError):self.policy.verify(value,self.binding,'isolated-fixture')
    def test_business_policy_cannot_be_supplied_from_fixture_path(self):
        with self.assertRaises(ValueError):tool.Policy(self.root/'policy.json',False)
    def test_append_only_ledger_detects_tamper(self):
        ledger=tool.Ledger(self.root/'ledger',self.policy);ledger.append({'kind':'BEGIN'});ledger.append({'kind':'INTENT'})
        self.assertEqual('INTENT',ledger.latest()['kind'])
        path=self.root/'ledger/000001.json';value=json.loads(path.read_text());value['body']['kind']='PHASE_COMPLETE';path.write_text(json.dumps(value))
        with self.assertRaises(ValueError):ledger.latest()
    def test_missing_phase_receipt_breaks_chain(self):
        ledger=tool.Ledger(self.root/'ledger',self.policy);ledger.append({'kind':'BEGIN'});ledger.append({'kind':'INTENT'});ledger.append({'kind':'PHASE_COMPLETE'})
        (self.root/'ledger/000001.json').unlink()
        with self.assertRaises(ValueError):ledger.latest()

    def test_restore_corrects_only_proven_trigger_header_not_body_or_data(self):
        header="/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES' */ ;\nDELIMITER ;;\n/*!50003 CREATE*/ /*!50017 DEFINER=`root`@`localhost`*/ /*!50003 TRIGGER `mt_fixture`"
        tail=" BEFORE UPDATE ON `t` FOR EACH ROW SET NEW.note='NO_AUTO_CREATE_USER';;\nINSERT INTO t VALUES('STRICT_TRANS_TABLES');\n"
        mode='STRICT_TRANS_TABLES,NO_AUTO_CREATE_USER'
        corrected=tool.restore_trigger_sql_modes(header+tail,{'mt_fixture':mode})
        self.assertTrue(corrected.endswith(tail));self.assertIn("sql_mode              = '"+mode+"'",corrected)
        self.assertEqual(corrected,tool.restore_trigger_sql_modes(corrected,{'mt_fixture':mode}))
        self.assertEqual(corrected.replace('`mt_fixture`','mt_fixture'),tool.restore_trigger_sql_modes((header+tail).replace('`mt_fixture`','mt_fixture'),{'mt_fixture':mode}))
        for dump,modes in [(header+tail,{}),(header+tail,{'mt_fixture':'ANSI'}),(header+tail,{'mt_fixture':mode,'mt_missing':mode}),(header+header+tail,{'mt_fixture':mode})]:
            with self.assertRaises(ValueError):tool.restore_trigger_sql_modes(dump,modes)

    def test_actual_jar_resource_not_caller_epoch(self):
        old=self.root/'old.jar';new=self.root/'current.jar'
        with zipfile.ZipFile(old,'w') as jar:jar.writestr('META-INF/MANIFEST.MF','fixture')
        with zipfile.ZipFile(new,'w') as jar:jar.writestr('BOOT-INF/classes/META-INF/mt705-schema-epoch','2026100101\n')
        self.assertEqual(0,tool.package_epoch(old));self.assertEqual(2026100101,tool.package_epoch(new))
    def test_ambiguous_jar_resource_fails_closed(self):
        path=self.root/'ambiguous.jar'
        with zipfile.ZipFile(path,'w') as jar:
            jar.writestr('META-INF/mt705-schema-epoch','2026093006');jar.writestr('BOOT-INF/classes/META-INF/mt705-schema-epoch','2026100101')
        with self.assertRaises(ValueError):tool.package_epoch(path)


class SchemaMetadataTests(unittest.TestCase):
    class Database:
        database='fixture'
        def __init__(self,definition):self.definition=definition
        def tables(self):return ['fixture_table']
        def query(self,sql):return self.definition if sql.startswith('SHOW CREATE TABLE ') else []
    def test_missing_table_definition_cannot_be_fingerprinted_as_empty(self):
        with self.assertRaisesRegex(ValueError,'Missing SHOW CREATE TABLE'):
            tool.schema(self.Database([]))
    def test_empty_table_still_has_a_required_schema_definition(self):
        definition=['fixture_table\tCREATE TABLE fixture_table (id BIGINT)']
        value=tool.schema(self.Database(definition))
        self.assertEqual(tool.digest(definition),value['objects']['table:fixture_table'])
        self.assertNotEqual(tool.digest([]),value['objects']['table:fixture_table'])

if __name__=='__main__':unittest.main()
