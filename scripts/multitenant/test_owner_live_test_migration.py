"""Offline owner-instruction contract tests; not production migration evidence."""
import datetime as dt
import copy
import json
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
        self.source={'server_uuid':'source-fixture','database':'fixture-source','datadir':'/source/'}
        self.other={'server_uuid':'restore-fixture','database':'fixture-restore','datadir':'/restore/'}
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

    def current_value(self):
        path=self.root/'current-instruction.json'
        if not path.exists():c.publish(path,{'format':2,'human_instructions':[owner.DEPLOY_INSTRUCTION,owner.LIVE_TEST_INSTRUCTION],
                                             'owner_asserts_no_real_users':True,'owner_asserts_no_real_funds':True})
        return dict(self.value,instruction_receipt=str(path),instruction_sha256=c.core.file_hash(path),
                    owner_asserts_no_real_funds=True,authorized_databases=[self.source['database']])

    def test_current_real_owner_words_are_preserved_not_relabelled_magic(self):
        policy=self.policy(self.current_value())
        receipt=c.read(policy.value['instruction_receipt'])
        self.assertEqual([owner.DEPLOY_INSTRUCTION,owner.LIVE_TEST_INSTRUCTION],receipt['human_instructions'])
        self.assertNotIn('human_instruction',receipt)
        self.assertEqual(2,owner.current_instruction(policy.value))

    def test_current_live_test_requires_no_funds_claim_and_exact_single_database(self):
        value=self.current_value()
        for changed in [dict(value,owner_asserts_no_real_funds=False),dict(value,authorized_databases=[]),
                        dict(value,authorized_databases=[self.source['database'],'unrelated'])]:
            with self.subTest(changed=changed),self.assertRaises(ValueError):self.policy(changed)

    def test_current_receipt_wrong_words_order_or_claim_fail_even_with_new_hash(self):
        for number,change in enumerate([{'human_instructions':[owner.LIVE_TEST_INSTRUCTION,owner.DEPLOY_INSTRUCTION]},
                                         {'human_instructions':[owner.INSTRUCTION,owner.LIVE_TEST_INSTRUCTION]},
                                         {'owner_asserts_no_real_funds':False},{'owner_asserts_no_real_users':False}]):
            value=self.current_value();receipt=c.read(value['instruction_receipt']);receipt.update(change)
            path=self.root/f'invalid-current-{number}.json';c.publish(path,receipt)
            with self.assertRaises(ValueError):self.policy(dict(value,instruction_receipt=str(path),instruction_sha256=c.core.file_hash(path)))

    def test_baseline_never_accepts_duck_policy_or_fabricates_complete(self):
        fake=SimpleNamespace(policy=SimpleNamespace(owner_live_test=True))
        with self.assertRaisesRegex(ValueError,'actual owner policy/ledger'):
            owner.verify_baseline(self.db,fake,{'kind':'COMPLETE'})
        with self.assertRaisesRegex(ValueError,'Actual owner live-test'):
            owner.observe_baseline(self.db,self.restore,self.restore,Path('unused'),Path('unused'),fake)

    def test_structural_reference_ignores_only_final_table_counter_not_default_or_rows(self):
        definition=['fixture\tCREATE TABLE `fixture` (',"  `value` varchar(64) DEFAULT 'AUTO_INCREMENT=41'",') ENGINE=InnoDB AUTO_INCREMENT=8000001 DEFAULT CHARSET=utf8mb4']
        db=SimpleNamespace(tables=lambda:['fixture'],query=lambda sql:definition)
        with patch.object(c,'schema',return_value={'objects':{'table:fixture':'raw','trigger-definitions':'t'*64}}):
            value=owner.baseline_definitions(db)
            expected='\n'.join(definition).replace(') ENGINE=InnoDB AUTO_INCREMENT=8000001',') ENGINE=InnoDB')
            self.assertEqual(c.digest(expected),value['objects']['table:fixture'])
            self.assertIn('AUTO_INCREMENT=41',expected)
            self.assertEqual('t'*64,value['objects']['trigger-definitions'])

    def reuse_fixture(self):
        migrations=[{'name':'V2026100603__tenant_entry_frontend_roles.sql'},*({'name':name} for name in c.CONTROL0702_TAIL)]
        policy=self.policy(self.current_value());ledger=owner.OwnerLiveTestLedger(self.root/'reuse-ledger',policy)
        facts={'schema':{'all':'real fixture definitions'},'data':{'all':'real fixture hashes'}}
        previous={'kind':'OWNER_BASELINE_OBSERVED','target':self.source,'state':facts,'source_sha256':'s'*64,'migrations':migrations[:-2],
                  'backup':{'path':'actual-previous-single-db.sql','sha256':'b'*64},'restore_input':{'path':'actual-corrected-input.sql','sha256':'r'*64},'restore':self.other}
        ledger.append(previous)
        proposal={'target':self.source,'initial':facts,'source_sha256':'s'*64,'migrations':migrations,'start':1,
                  'schema_epoch':2026100702,'allowed_metadata_append':c.CONTROL0702_METADATA}
        return ledger,facts,proposal,migrations

    def test_backup_reuses_actual_full_restore_only_after_reverification_without_redump(self):
        ledger,facts,proposal,migrations=self.reuse_fixture()
        self.db.dump=lambda *args:self.fail('Verified single-DB backup must not be repeated')
        self.restore.restore_file=lambda *args:self.fail('Verified isolated full restore must not be repeated')
        with patch.object(owner,'verify_baseline') as verify,patch.object(c,'migrations',return_value=migrations),patch.object(c,'state',return_value=facts):
            proof=owner.proof_from_baseline(self.db,proposal,self.restore,self.root/'reused-proof.json',ledger)
        verify.assert_called_once_with(self.db,ledger,ledger.latest())
        self.assertEqual(ledger.latest()['backup'],proof['backup']);self.assertEqual(ledger.latest()['restore_input'],proof['restore_input'])
        self.assertEqual(c.digest(proposal),proof['plan_sha256']);self.assertIsNone(proof['ledger_tip'])
        self.assertEqual('ACTUAL_OWNER_BASELINE_FULL_SINGLE_DB_RESTORE',proof['reuse'])

    def test_backup_reuse_wrong_plan_restore_or_source_increment_refused(self):
        ledger,facts,proposal,migrations=self.reuse_fixture()
        with patch.object(owner,'verify_baseline'),patch.object(c,'migrations',return_value=migrations),patch.object(c,'state',return_value=facts):
            for field,value in [('target',{}),('initial',{}),('source_sha256','changed'),('migrations',[]),('start',0),
                                ('schema_epoch',2026100603),('allowed_metadata_append',None)]:
                with self.subTest(field=field),self.assertRaisesRegex(ValueError,'binding differs'):
                    owner.proof_from_baseline(self.db,dict(proposal,**{field:value}),self.restore,self.root/'bad-proof.json',ledger)
            with patch.object(c,'target',side_effect=lambda db:self.source),self.assertRaisesRegex(ValueError,'binding differs'):
                owner.proof_from_baseline(self.db,proposal,self.restore,self.root/'bad-proof.json',ledger)
        with patch.object(owner,'verify_baseline'),patch.object(c,'migrations',return_value=migrations),\
                patch.object(c,'state',side_effect=lambda db:facts if db is self.restore else {'new':'actual increment'}):
            with self.assertRaisesRegex(ValueError,'New writes'):
                owner.proof_from_baseline(self.db,proposal,self.restore,self.root/'bad-proof.json',ledger)
        self.assertFalse((self.root/'bad-proof.json').exists())

    def test_backup_reuse_cannot_bypass_baseline_failure_or_use_duck_ledger(self):
        ledger,_,proposal,_=self.reuse_fixture()
        with patch.object(owner,'verify_baseline',side_effect=ValueError('actual restore changed')):
            with self.assertRaisesRegex(ValueError,'actual restore changed'):
                owner.proof_from_baseline(self.db,proposal,self.restore,self.root/'bad-proof.json',ledger)
        with self.assertRaisesRegex(ValueError,'Actual owner baseline ledger'):
            owner.proof_from_baseline(self.db,proposal,self.restore,self.root/'bad-proof.json',SimpleNamespace(latest=ledger.latest))

    def activation_fixture(self):
        value=self.current_value()
        backup=self.root/'backup.sql';backup.write_bytes(b'offline fixture full backup')
        restore_input=self.root/'restore-input.sql';restore_input.write_bytes(b'offline fixture restore input')
        artifact=self.root/'application.jar';artifact.write_bytes(b'offline fixture artifact')
        startup=self.root/'startup.json';c.publish(startup,{'kind':'offline mock only'})
        migrations=[{'name':'V2026100603__tenant_entry_frontend_roles.sql'},*({'name':name} for name in c.CONTROL0702_TAIL)]
        facts={'schema':{'objects':{'fixture':'x'},'sha256':'x'},'data':{'columns':{'tenant_schema_version':['version','applied_at','minimum_application_epoch','business_activation_ready']},'tables':{}}}
        proposal={'start':1,'migrations':migrations,'source_sha256':'s'*64,'target':self.source,'allowed_metadata_append':c.CONTROL0702_METADATA}
        proof={'result':'PASS','plan_sha256':c.digest(proposal),'source':self.source,'restore':self.other,'restored':facts,
               'backup':{'path':str(backup),'sha256':c.core.file_hash(backup)},'restore_input':{'path':str(restore_input),'sha256':c.core.file_hash(restore_input)}}
        complete={'kind':'COMPLETE','activation_ready':False,'binding':c.binding(proposal,proof),'plan_sha256':c.digest(proposal),
                  'target':self.source,'migrations':migrations,'state':facts}
        value['binding']=c.binding(proposal,proof)
        value['migrations']=migrations
        value['activation']={'kind':owner.ACTIVATION,'complete_tip':c.digest(complete),'startup_sha256':c.core.file_hash(startup),
                             'artifact_sha256':c.core.file_hash(artifact),'schema_version':2026100702,'minimum_application_epoch':2026100603}
        with patch.object(c,'migrations',return_value=migrations):policy=self.policy(value)
        ledger=owner.OwnerLiveTestLedger(self.root/'activation-ledger',policy)
        ledger.append(complete)
        # Chain-managed sequence/timestamp are part of the actual COMPLETE binding.
        value['activation']['complete_tip']=c.digest(ledger.latest())
        policy.path.unlink();c.publish(policy.path,value);policy.value=value
        return proposal,proof,artifact,startup,ledger,facts,migrations

    def test_activation_commits_only_0702_and_preserves_old_facts(self):
        proposal,proof,artifact,startup,ledger,facts,migrations=self.activation_fixture()
        sql=[]
        self.db.query=lambda statement:['0'] if 'version<>2026100702' in statement else ['1'] if 'COUNT(*)' in statement else ['2026100702\told-applied-at\t2026100603']
        self.db.sql=lambda statement:sql.append(statement) or SimpleNamespace(stdout=b'1\n')
        with patch.object(c,'migrations',return_value=migrations),patch.object(c,'state',return_value=facts),\
                patch.object(c,'schema',return_value=facts['schema']),patch.object(c,'preserved',return_value={'all-old-rows':'unchanged'}),\
                patch.object(owner,'verify_startup') as startup_check,patch.object(c,'package_epoch',return_value=2026100702),\
                patch.object(c.core,'guard',return_value={'activation_ready':True}):
            result=owner.activate(self.db,proposal,proof,self.restore,artifact,startup,ledger)
        self.assertEqual('OWNER_LIVE_TEST_ACTIVATION_COMPLETE',result['kind'])
        self.assertFalse(result['normal_production_release_approved']);self.assertFalse(result['writers_reenabled'])
        self.assertEqual(1,len(sql));self.assertIn('version=2026100702 AND minimum_application_epoch=2026100603 AND business_activation_ready=0',sql[0])
        self.assertNotIn('SET GLOBAL',sql[0]);startup_check.assert_called_once()
        self.assertEqual(['COMPLETE','OWNER_LIVE_TEST_ACTIVATION_INTENT','OWNER_LIVE_TEST_ACTIVATION_COMPLETE'],[x['kind'] for x in ledger.rows()])

    def test_activation_refuses_replay_stale_binding_and_changed_full_state(self):
        proposal,proof,artifact,startup,ledger,facts,migrations=self.activation_fixture()
        self.db.sql=lambda statement:self.fail('Invalid activation must not mutate DB')
        with patch.object(c,'migrations',return_value=migrations),patch.object(c,'state',return_value={'changed':'real new rows'}):
            with self.assertRaisesRegex(ValueError,'unchanged full source'):owner.activate(self.db,proposal,proof,self.restore,artifact,startup,ledger)
        ledger.append({'kind':'OWNER_LIVE_TEST_ACTIVATION_INTENT'})
        with patch.object(c,'migrations',return_value=migrations),patch.object(c,'state',return_value=facts):
            with self.assertRaisesRegex(ValueError,'not bound'):owner.activate(self.db,proposal,proof,self.restore,artifact,startup,ledger)

    def test_activation_old_magic_and_duck_policy_never_enable_current_release(self):
        ledger=owner.OwnerLiveTestLedger(self.root/'old-activation',self.policy())
        with self.assertRaisesRegex(ValueError,'two-instruction'):owner.activate(self.db,{}, {},self.restore,Path('unused'),Path('unused'),ledger)
        with self.assertRaisesRegex(ValueError,'Explicit owner live-test activation'):
            owner.activate(self.db,{}, {},self.restore,Path('unused'),Path('unused'),SimpleNamespace(policy=SimpleNamespace(owner_live_test=True)))

    def test_activation_preservation_failure_records_uncertain_and_leaves_writers_off(self):
        proposal,proof,artifact,startup,ledger,facts,migrations=self.activation_fixture()
        self.db.query=lambda statement:['0'] if 'version<>2026100702' in statement else ['1'] if 'COUNT(*)' in statement else ['2026100702\told-applied-at\t2026100603']
        self.db.sql=lambda statement:SimpleNamespace(stdout=b'1\n')
        with patch.object(c,'migrations',return_value=migrations),patch.object(c,'state',return_value=facts),\
                patch.object(c,'schema',return_value=facts['schema']),patch.object(c,'preserved',side_effect=[{'fact':'old'},{'fact':'changed'}]),\
                patch.object(owner,'verify_startup'):
            with self.assertRaisesRegex(ValueError,'original rows'):owner.activate(self.db,proposal,proof,self.restore,artifact,startup,ledger)
        self.assertEqual('OWNER_LIVE_TEST_ACTIVATION_FAILED_UNCERTAIN',ledger.latest()['kind'])
        self.assertFalse(ledger.latest()['writers_reenabled'])

    def test_record_startup_rejects_self_declared_unhealthy_or_super_connections(self):
        artifact=self.root/'startup.jar';artifact.write_bytes(b'fixture')
        self.db.query=lambda statement:['1']
        actual={'Id':'a'*64,'Image':'sha256:'+'b'*64,'State':{'Running':True,'StartedAt':'current','Health':{'Status':'starting'}}}
        with patch.object(c,'package_epoch',return_value=2026100702),patch.object(owner,'inspect_application',return_value=actual):
            with self.assertRaisesRegex(ValueError,'must be healthy'):owner.record_startup(self.db,artifact,'fixture','/app/app.jar',self.root/'startup-unused.json')
        actual['State']['Health']['Status']='healthy'
        with patch.object(c,'package_epoch',return_value=2026100702),patch.object(owner,'inspect_application',return_value=actual),\
                patch.object(owner,'container_artifact_hash',return_value=c.core.file_hash(artifact)):
            with self.assertRaisesRegex(ValueError,'non-SUPER'):owner.record_startup(self.db,artifact,'fixture','/app/app.jar',self.root/'startup-unused.json')

    def native_fixture(self):
        # Offline fixtures replace known witness hashes only inside unittest.mock;
        # no CLI accepts arbitrary hashes or a caller-supplied approval policy.
        self.source={'database':'1090','server_uuid':'OFFLINE_FIXTURE_ONLY','datadir':'/offline-source/',
                     'port':3306,'version':'5.7.44','physical':{'container_id':'a'*64,'image_id':'sha256:'+'b'*64}}
        objects={key:c.digest({'fixture':key}) for key in owner.STORED_OBJECTS}
        sql=[]
        for number in range(112):
            name=f'offline_table_{number:03d}'
            block=f'CREATE TABLE `{name}` (\n  `id` int(11) NOT NULL\n) ENGINE=InnoDB DEFAULT CHARSET=latin1;'
            lines=block[:-1].splitlines();lines[0]=name+'\t'+lines[0]
            objects['table:'+name]=c.digest(lines);sql.append(block)
        frozen={'at':(c.now()-dt.timedelta(minutes=9)).isoformat(),'target':copy.deepcopy(self.source),
                'metadata':[json.dumps({'version':2026100404,'epoch':2026100404,'ready':1})],
                'state':{'schema':{'objects':objects},'data':{}}}
        paths={key:self.root/('offline-native-'+key+('.sql' if key in ('original_backup','tables_ddl') else '.json')) for key in owner.NATIVE_FILES}
        c.publish(paths['frozen'],frozen);paths['original_backup'].write_bytes(b'OFFLINE_SYNTHETIC_FULL_BACKUP_NOT_PRODUCTION')
        paths['tables_ddl'].write_bytes(('\n\n'.join(sql)+'\n').encode())
        hashes={key:c.core.file_hash(path) for key,path in paths.items() if key!='full_restore_proof'}
        restore_proof={'kind':'PASS_FRESH_FROZEN_FULL_RESTORE_ALL_FIELDS_AND_SCHEMA','at':(c.now()-dt.timedelta(minutes=8)).isoformat(),
                       'source':copy.deepcopy(self.source),'backup':{'sha256':hashes['original_backup'],'schema_only':False},
                       'restore':{'server_uuid':'OFFLINE_OTHER','datadir':'/offline-restore/','physical':{'test_instance':True}},
                       'actual_exact_decimal_and_binary_fields_checked':True,'independent_signed_approval_claimed':False}
        c.publish(paths['full_restore_proof'],restore_proof);hashes['full_restore_proof']=c.core.file_hash(paths['full_restore_proof'])
        reviewed=[{'name':path.name,'sha256':c.core.file_hash(path)} for path in c.core.MIGRATIONS]
        phases=[]
        for number,migration in enumerate(reviewed[-5:-2]):
            path=self.root/f'offline-native-phase-{number}.json'
            c.publish(path,{'kind':'EXACT_DDL_PHASE_COMPLETE','at':(c.now()-dt.timedelta(minutes=7-number)).isoformat(),
                            'target':copy.deepcopy(self.source),'migration':migration})
            phases.append({'path':str(path),'sha256':c.core.file_hash(path)})
        for mock in (patch.object(owner,'NATIVE_FILES',hashes),patch.object(owner,'NATIVE_PHASES',tuple(x['sha256'] for x in phases)),
                     patch.object(c,'migrations',return_value=reviewed)):
            mock.start();self.addCleanup(mock.stop)
        value={'kind':owner.NATIVE_WITNESS,**{key:{'path':str(path),'sha256':hashes[key]} for key,path in paths.items()},'phase_receipts':phases}
        return value,frozen

    def test_native_witness_validates_every_old_raw_create_and_actual_receipts(self):
        value,frozen=self.native_fixture()
        actual,paths=owner.validate_native_witness(self.db,value)
        self.assertEqual(frozen,actual)
        self.assertEqual(112,len(owner.original_table_ddl(paths['tables_ddl'].read_text(),actual)))
        self.assertNotEqual(owner.BASELINE_SHA256,value['tables_ddl']['sha256'])

    def test_native_witness_rejects_another_source_scope_or_self_declared_pass(self):
        value,_=self.native_fixture()
        for changed in [dict(value,kind='PASS'),dict(value,review_pass=True),dict(value,phase_receipts=[])]:
            with self.subTest(changed=changed),self.assertRaises(ValueError):owner.validate_native_witness(self.db,changed)
        self.source['server_uuid']='ANOTHER_OFFLINE_SOURCE'
        with self.assertRaisesRegex(ValueError,'another physical'):owner.validate_native_witness(self.db,value)

    def test_native_witness_rejects_mutated_backup_or_create_sql(self):
        value,frozen=self.native_fixture();path=Path(value['original_backup']['path'])
        path.write_bytes(b'changed old full backup')
        with self.assertRaisesRegex(ValueError,'bytes changed'):owner.validate_native_witness(self.db,value)
        text=Path(value['tables_ddl']['path']).read_text()
        for changed in (text+'\nDROP TABLE offline_table_000;',text.replace('`id` int(11)','`other` int(11)',1),text+text):
            with self.subTest(changed=changed[:50]),self.assertRaises(ValueError):owner.original_table_ddl(changed,frozen)

    def test_native_witness_phase_body_must_match_current_exact_sql_not_only_sha(self):
        value,_=self.native_fixture();path=Path(value['phase_receipts'][0]['path']);phase=c.read(path)
        phase['migration']['sha256']='0'*64;path.write_bytes(c.canonical(phase)+b'\n')
        sha=c.core.file_hash(path);value['phase_receipts'][0]['sha256']=sha
        with patch.object(owner,'NATIVE_PHASES',(sha,*owner.NATIVE_PHASES[1:])):
            with self.assertRaisesRegex(ValueError,'executed phase'):owner.validate_native_witness(self.db,value)

    def native_definition_fixture(self):
        _,frozen=self.native_fixture()
        current={'objects':{key:value for key,value in frozen['state']['schema']['objects'].items()}}
        current['objects']['table:control_policy_definition']='new-exact-reviewed-table'
        reference=copy.deepcopy(current)
        for key in owner.STORED_OBJECTS:
            if key!='database-defaults':reference['objects'][key]='ACTUALLY_EMPTY_REFERENCE_NOT_ORIGINAL_OBJECTS'
        current['sha256']=c.digest(current['objects']);reference['sha256']=c.digest(reference['objects'])
        return frozen,current,reference

    def test_native_table_only_reference_does_not_fake_stored_objects(self):
        frozen,current,reference=self.native_definition_fixture()
        with patch.object(owner,'baseline_definitions',side_effect=[current,reference]):
            self.assertEqual(current,owner.native_reference_definitions(self.db,self.restore,frozen))
        self.assertNotEqual(current['objects']['trigger-definitions'],reference['objects']['trigger-definitions'])

    def test_native_reference_rejects_real_table_default_or_stored_mode_drift(self):
        frozen,current,reference=self.native_definition_fixture()
        for key in ('table:offline_table_000','database-defaults'):
            changed=copy.deepcopy(reference);changed['objects'][key]='REAL_DEFINITION_CHANGED'
            with patch.object(owner,'baseline_definitions',side_effect=[current,changed]),self.assertRaises(ValueError):
                owner.native_reference_definitions(self.db,self.restore,frozen)
        for key in owner.STORED_OBJECTS:
            changed=copy.deepcopy(current);changed['objects'][key]='REAL_ORIGINAL_OBJECT_CHANGED'
            with patch.object(owner,'baseline_definitions',side_effect=[changed,reference]),self.assertRaisesRegex(ValueError,'stored object/default'):
                owner.native_reference_definitions(self.db,self.restore,frozen)

    def test_native_reference_rows_allow_only_real_original_catalog_and_three_receipts(self):
        facts={'data':{'tables':{'tenant_schema_version':{'rows':3},'control_policy_definition':{'rows':1},'original':{'rows':0}}}}
        self.restore.query=lambda statement:[f'{v}\t{v}\t0' for v in (2026100601,2026100602,2026100603)] if statement.startswith('SELECT version,') else ['1']
        owner.native_reference_rows(self.restore,facts)
        for table,count in [('original',1),('control_policy_definition',0),('tenant_schema_version',4)]:
            changed=copy.deepcopy(facts);changed['data']['tables'][table]['rows']=count
            with self.assertRaisesRegex(ValueError,'unexpected rows'):owner.native_reference_rows(self.restore,changed)

if __name__=='__main__':unittest.main()
