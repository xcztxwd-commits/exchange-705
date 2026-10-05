"""Small offline checks. Native implicit-commit/restore tests run separately; these are not MySQL evidence."""
import datetime as dt
import hashlib
import hmac
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from types import SimpleNamespace
from unittest.mock import patch
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

    def test_streamed_trigger_correction_matches_full_parser_without_read_text(self):
        header="/*!50003 SET sql_mode = 'STRICT_TRANS_TABLES' */ ;\nDELIMITER ;;\n/*!50003 CREATE*/ /*!50017 DEFINER=`root`@`localhost`*/ /*!50003 TRIGGER `mt_fixture`"
        sql='-- original facts\n'+header+" BEFORE UPDATE ON `t` FOR EACH ROW SET NEW.x=1;;\nINSERT INTO t VALUES('unchanged');\n"
        modes={'mt_fixture':'STRICT_TRANS_TABLES,NO_AUTO_CREATE_USER'}
        source=self.root/'source.sql';source.write_text(sql)
        output=self.root/'streamed.sql'
        with patch.object(Path,'read_text',side_effect=AssertionError('Large input cannot be materialized')):
            tool.stream_restore_trigger_sql_modes(source,output,modes)
        self.assertEqual(tool.restore_trigger_sql_modes(sql,modes),output.read_text())
        self.assertEqual(sql,source.read_text())

    def test_streamed_restore_rejects_missing_duplicate_unknown_and_unrecognized_headers(self):
        sql="/*!50003 SET sql_mode = 'STRICT_TRANS_TABLES' */ ;\nDELIMITER ;;\n/*!50003 CREATE*/ /*!50017 DEFINER=`root`@`localhost`*/ /*!50003 TRIGGER `mt_fixture` BEFORE UPDATE ON t SET NEW.x=1;;\n"
        cases=[(sql,{}),(sql+sql,{'mt_fixture':'STRICT_TRANS_TABLES'}),('',{'mt_fixture':'STRICT_TRANS_TABLES'}),
               (sql.splitlines()[-1]+'\n',{'mt_fixture':'STRICT_TRANS_TABLES'})]
        for index,(text,modes) in enumerate(cases):
            source=self.root/f'bad-{index}.sql';source.write_text(text)
            with self.subTest(index=index),self.assertRaises(ValueError):
                tool.stream_restore_trigger_sql_modes(source,self.root/f'bad-output-{index}.sql',modes)
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
        def query(self,sql):
            if sql.startswith('SHOW CREATE TABLE '):return self.definition
            if 'information_schema.SCHEMATA' in sql:return ['latin1\tlatin1_swedish_ci']
            return []
    def test_missing_table_definition_cannot_be_fingerprinted_as_empty(self):
        with self.assertRaisesRegex(ValueError,'Missing SHOW CREATE TABLE'):
            tool.schema(self.Database([]))
    def test_empty_table_still_has_a_required_schema_definition(self):
        definition=['fixture_table\tCREATE TABLE fixture_table (id BIGINT)']
        value=tool.schema(self.Database(definition))
        self.assertEqual(tool.digest(definition),value['objects']['table:fixture_table'])
        self.assertNotEqual(tool.digest([]),value['objects']['table:fixture_table'])

class PlanPreflightTests(unittest.TestCase):
    def test_revision_seed_is_exact_and_only_after_its_reviewed_phase(self):
        before={'rows':2,'sha256':'a'*64};after={'rows':3,'sha256':'b'*64}
        original={'asset_history_revision':before,'wallet':{'rows':1,'sha256':'c'*64}}
        proposal={'migrations':[{'name':'earlier.sql'},{'name':tool.HISTORY_REVISION_SEED}],
                  'history_revision_seed':{'before':before,'after':after},'preserved_sha256':tool.digest(original)}
        tool.verify_preserved(proposal,original,0)
        seeded={**original,'asset_history_revision':after};tool.verify_preserved(proposal,seeded,1)
        self.assertEqual(after,seeded['asset_history_revision'])
        for facts,index in [(seeded,0),(original,1),({**seeded,'wallet':before},1),
                            ({**seeded,'asset_history_revision':{'rows':3,'sha256':'d'*64}},1)]:
            with self.assertRaises(ValueError):tool.verify_preserved(proposal,facts,index)

    def test_revision_seed_requires_exact_original_columns_and_keeps_all_key_fields(self):
        with self.assertRaises(ValueError):tool.history_revision_seed(None,{'asset_history_revision':['revision']})
        self.assertIsNone(tool.history_revision_seed(None,{}))
        captured=[]
        def fingerprints(db,queries,columns):
            captured.extend(queries);return {'asset_history_revision':{'rows':1,'sha256':'a'*64}}
        fields=['user_id','basis_version','level','revision']
        with patch.object(tool.core,'row_fingerprints',side_effect=fingerprints):
            result=tool.history_revision_seed(None,{'asset_history_revision':fields})
        self.assertEqual(tool.HISTORY_REVISION_SEED,result['migration'])
        for field in fields:self.assertIn('HEX(CAST(`'+field+'` AS BINARY))',captured[-1])
        self.assertIn('r.revision+IF(p.user_id IS NULL,0,1)',captured[-1])
        self.assertIn('WHERE NOT EXISTS',captured[-1])

    def test_reviewed_legacy_column_reuse_never_executes_duplicate_ddl(self):
        db=SimpleNamespace(sql=lambda sql:self.fail('Already present reviewed columns must not run ALTER'))
        path=Path('V2026092903__activity_delivery_settings.sql')
        with patch.object(tool.core,'already_present_legacy_columns',return_value=True):
            self.assertEqual('REUSED_EXACT_REVIEWED_LEGACY_COLUMNS_NO_DDL',tool.execute_phase(db,path,[path.name]))
            with self.assertRaises(ValueError):tool.execute_phase(db,path,[])
        with patch.object(tool.core,'already_present_legacy_columns',return_value=False),self.assertRaises(ValueError):
            tool.execute_phase(db,path,[path.name])

    def test_unreviewed_phase_executes_exact_sql_without_reuse(self):
        path=Path('reviewed-fixture.sql');db=SimpleNamespace(sql=lambda sql:self.assertEqual('SELECT 1;',sql))
        with patch.object(tool.core,'already_present_legacy_columns',return_value=False),patch.object(Path,'read_text',return_value='SELECT 1;'):
            self.assertEqual('EXECUTED_REVIEWED_DDL',tool.execute_phase(db,path,[]))
    def test_failed_legacy_preflight_never_hashes_data_or_publishes_plan(self):
        db=SimpleNamespace(tables=lambda:['user_account'])
        with patch.object(tool.isolation_gate,'check',return_value=([],{})), \
                patch.object(tool.core,'preflight',return_value={'passed':False}) as preflight, \
                patch.object(tool,'state') as state, patch.object(tool,'publish') as publish:
            with self.assertRaisesRegex(ValueError,'Current legacy inventory failed'):
                tool.plan(db,Path('unused-plan.json'))
            preflight.assert_called_once_with(db);state.assert_not_called();publish.assert_not_called()

    def test_successful_early_preflight_does_not_replace_post_snapshot_check(self):
        db=SimpleNamespace(tables=lambda:['user_account'])
        with patch.object(tool.isolation_gate,'check',return_value=([],{})), \
                patch.object(tool.core,'preflight',side_effect=[{'passed':True},{'passed':False}]) as preflight, \
                patch.object(tool,'state',return_value={}) as state, patch.object(tool,'publish') as publish:
            with self.assertRaisesRegex(ValueError,'Current legacy inventory failed'):
                tool.plan(db,Path('unused-plan.json'))
            self.assertEqual(preflight.call_count,2);state.assert_called_once_with(db);publish.assert_not_called()

    def test_scoped_target_still_requires_existing_completed_ledger(self):
        db=SimpleNamespace(tables=lambda:['tenant_schema_version'])
        with patch.object(tool.isolation_gate,'check',return_value=([],{})), \
                patch.object(tool.core,'preflight') as preflight, patch.object(tool,'state',return_value={}):
            with self.assertRaisesRegex(ValueError,'requires a verified completed ledger'):
                tool.plan(db,Path('unused-plan.json'))
            preflight.assert_not_called()

    def test_source_gate_failure_is_checked_before_any_database_work(self):
        db=SimpleNamespace(tables=lambda:self.fail('Source rejection must not query the DB'))
        with patch.object(tool.isolation_gate,'check',return_value=(['failed'],{})), \
                patch.object(tool.core,'preflight') as preflight, patch.object(tool,'state') as state:
            with self.assertRaisesRegex(ValueError,'Current source review gate failed'):
                tool.plan(db,Path('unused-plan.json'))
            preflight.assert_not_called();state.assert_not_called()

if __name__=='__main__':unittest.main()
