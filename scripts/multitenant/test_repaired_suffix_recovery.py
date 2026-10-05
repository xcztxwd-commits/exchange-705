"""Offline guards only; actual full-copy MySQL qualification remains mandatory."""
import copy
import hashlib
import tempfile
from contextlib import ExitStack
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import controlled_migration as c
import partial_forward_recovery as p
import repaired_suffix_recovery as r
from owner_live_test_migration import OwnerLiveTestLedger, OwnerLiveTestPolicy
import test_partial_forward_recovery as fixtures


class RepairedSuffixTests(unittest.TestCase):
    def setUp(self):
        fixtures.PartialRecoveryTests.setUp(self)
        self.old = self.plan
        self.plan = {'kind':r.KIND,'source_sha256':'current','target':self.source,'initial':self.repaired,
                     'historical_plan':self.old,'suffix_sha256':self.old['suffix_sha256'],
                     'sql_mode':self.mode,'timestamp':self.old['timestamp'],'preservation_proof':{},'failed_repair_tip':'tip'}
        self.db.query = lambda sql: ['OFF'] if 'event_scheduler' in sql else ['2026092902\t0']

    def execution_patches(self,states):
        stack = ExitStack()
        for item in [patch.object(r,'validate_contract'),patch.object(c,'maintenance'),
                     patch.object(p,'foreign_keys',return_value=self.keys),patch.object(p,'runtime_mode',return_value=self.mode),
                     patch.object(c,'target',return_value=self.other),patch.object(c,'state',side_effect=states),
                     patch.object(c,'verify_preserved'),patch.object(c,'preserved',return_value={})]:
            stack.enter_context(item)
        return stack

    def repair_chain(self):
        directory = self.root/'old-failure'; base = {'plan_sha256':c.digest(self.old),'target':self.source}
        bodies = [{**base,'kind':'INTENT','before':self.old['initial']}]
        for index,sql in enumerate(p.operations(self.keys)):
            bodies.extend([{**base,'kind':'SQL_INTENT','index':index,'sql_sha256':hashlib.sha256(sql.encode()).hexdigest()},
                           {**base,'kind':'SQL_COMPLETE','index':index}])
        bodies.append({**base,'kind':'FAILED_UNCERTAIN','failure_type':'ValueError'})
        previous = '0'*64
        for index,body in enumerate(bodies):
            value = {'sequence':index,'previous':previous,'at':c.now().isoformat(),'journal_kind':p.KIND,**body}
            item = {'body':value,'sha256':c.digest(value)}; c.publish(directory/f'{index:06d}.json',item); previous = c.digest(item)
        return directory

    def diagnosis(self):
        before = {}; repaired = {}; actual = {}; definitions = {}
        for table,index in r.MOVED.items():
            lines = [table+'\tCREATE TABLE `'+table+'` (','  `id` bigint(20) NOT NULL,',
                     '  PRIMARY KEY (`id`),','  KEY `'+index+'` (`tenant_id`,`user_id`),',
                     '  KEY `other` (`tenant_id`,`other_id`),','  CONSTRAINT `fixed` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)',
                     ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci']
            key = 'table:'+table; before[key] = c.digest(lines); expected = list(lines)
            if table == 'user_account':
                expected[1] = '  `id` bigint(20) NOT NULL AUTO_INCREMENT,'
                expected[-1] = ') ENGINE=InnoDB AUTO_INCREMENT=7000020 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci'
            repaired[key] = c.digest(expected); changed = list(expected); changed[3],changed[4] = changed[4],changed[3]
            actual[key] = c.digest(changed); definitions[key] = {'protected_initial':lines,'actual':changed}
        old = copy.deepcopy(self.old); old['initial']['schema'] = {'objects':before,'sha256':c.digest(before)}
        expected = {'objects':repaired,'sha256':c.digest(repaired)}
        qualified = {'repaired':{'schema':expected}}
        mode = self.mode+'\t'+self.mode+'\t1\t1\t1\t1'
        diagnosis = {'kind':'READ_ONLY_DIAGNOSTIC_EXACT_ACTUAL_REPAIR_FAILED_SCHEMA','ddl_dml':0,'failed_tip':'tip',
                     'source_sha256':old['source_sha256'],'target':old['target'],'expected_repaired_schema':expected,
                     'actual_schema':{'objects':actual,'sha256':c.digest(actual)},'different_objects':list(actual),
                     'definitions':definitions,'modes':{'actual':[mode],'qualified':[mode]}}
        return old,qualified,diagnosis

    def test_only_exact_75_committed_statements_and_failure_accepted_without_rewrite(self):
        directory = self.repair_chain(); original = {path.name:path.read_bytes() for path in directory.iterdir()}
        self.assertEqual(c.digest(p.Evidence(directory).rows()[-1]),r.failed_repair_boundary(self.old,directory))
        self.assertEqual(original,{path.name:path.read_bytes() for path in directory.iterdir()})
        p.Evidence(directory).append({'kind':'SUFFIX_INTENT'})
        with self.assertRaisesRegex(ValueError,'75 committed'): r.failed_repair_boundary(self.old,directory)

    def test_incomplete_or_changed_old_repair_chain_refused(self):
        directory = self.repair_chain(); path = directory/'000150.json'; saved = path.read_bytes()
        path.write_bytes(saved.replace(b'SQL_COMPLETE',b'SQL_UNKNOWN'))
        with self.assertRaises(ValueError): r.failed_repair_boundary(self.old,directory)
        path.write_bytes(saved); path.unlink()
        with self.assertRaises(ValueError): r.failed_repair_boundary(self.old,directory)

    def test_exact_five_schema_permutations_only_not_global_sorting(self):
        old,qualified,diagnosis = self.diagnosis(); r.validate_diagnosis(old,qualified,diagnosis,'tip')
        for kind in ('index','column','counter','extra','mode'):
            bad = copy.deepcopy(diagnosis)
            if kind == 'index': bad['definitions']['table:demo_ledger']['actual'][3] = '  KEY `different` (`tenant_id`),'
            elif kind == 'column': bad['definitions']['table:demo_ledger']['actual'][1] = '  `id` bigint(20) NULL,'
            elif kind == 'counter': bad['definitions']['table:user_account']['actual'][-1] = bad['definitions']['table:user_account']['actual'][-1].replace('7000020','7000021')
            elif kind == 'extra': bad['actual_schema']['objects']['table:other'] = 'extra'
            else: bad['modes']['actual'] = ['changed']
            with self.subTest(kind=kind),self.assertRaises(ValueError): r.validate_diagnosis(old,qualified,bad,'tip')

    def test_new_source_binds_historical_proof_without_relabelling_old_plan(self):
        old,qualified,diagnosis = self.diagnosis(); old['source_sha256'] = 'historical-fa'
        diagnosis['source_sha256'] = 'historical-fa'; current = {'schema':diagnosis['actual_schema'],'data':old['initial']['data']}
        owner = self.root/'owner.json'; c.publish(owner,{'kind':'ACTUAL_HUMAN_CONTINUATION_ORDER_NOT_SIGNED_APPROVAL','human_instruction_excerpt':'Actual test instruction, not production approval'})
        observation = {'kind':'EXACT_CURRENT_FAILED_AFTER_ID_REPAIR_PRESERVATION_ONLY_NOT_RECOVERY','source_sha256':'historical-fa',
                       'target':self.source,'initial':current,'original_recovery_plan_sha256':c.digest(old),
                       'failed_repair_ledger_tip':'tip','diagnostic_sha256':c.digest(diagnosis),'start':2,
                       'migrations':c.migrations(),'business_activation_ready':False,'owner_order_sha256':c.core.file_hash(owner)}
        proof = {'plan_sha256':c.digest(observation),'source':self.source,'restored':current,'ledger_tip':None,'next':2,'restore':self.other}
        plan = {**self.plan,'start':2,'initial':current,'historical_plan':old,'historical_qualification':qualified,
                'business_activation_ready':False,'engine':{'frozen_root':'historical-fa'},'failed_repair_journal':'old-chain',
                'diagnosis':diagnosis,'observation':observation,'preservation_proof':proof,'owner_order':str(owner)}
        with ExitStack() as stack:
            for item in [patch.object(c,'sources',return_value='current'),patch.object(r,'historical_engine',return_value=plan['engine']),
                         patch.object(r,'original_failure'),patch.object(p,'validate_qualification'),patch.object(p,'proof_files'),
                         patch.object(r,'failed_repair_boundary',return_value='tip')]: stack.enter_context(item)
            r.validate_contract(plan)
            for kind in ('new-source','old-source','proof-binding','counter','timestamp','suffix'):
                bad = copy.deepcopy(plan)
                if kind == 'new-source': bad['source_sha256'] = 'historical-fa'
                elif kind == 'old-source': bad['historical_plan']['source_sha256'] = 'current'
                elif kind == 'proof-binding': bad['preservation_proof']['plan_sha256'] = c.digest(plan)
                elif kind == 'counter': bad['initial']['schema']['objects']['table:user_account'] = 'different'
                elif kind == 'timestamp': bad['timestamp'] = True
                else: bad['suffix_sha256'] = 'changed'
                with self.subTest(kind=kind),self.assertRaises(ValueError): r.validate_contract(bad)

    def test_exact_original_suffix_only_no_completed_repair_ddl(self):
        with self.execution_patches([self.repaired,self.after]): result = r.execute(self.db,self.plan,self.root/'success')
        self.assertEqual(self.after,result['after']); self.assertEqual(1,len(self.sql))
        self.assertTrue(self.sql[0].endswith(p.sql_parts()[1].decode()))
        self.assertNotIn(p.ALTER.decode(),self.sql[0]); self.assertNotIn('DROP FOREIGN KEY',self.sql[0])
        self.assertIn('SET timestamp='+str(self.plan['timestamp']),self.sql[0])
        self.assertEqual(['SUFFIX_INTENT','SUFFIX_COMPLETE'],[row['kind'] for row in r.Evidence(self.root/'success').rows()])
        with self.assertRaisesRegex(ValueError,'must not be replayed'): r.execute(self.db,self.plan,self.root/'success')

    def test_changed_current_full_state_refused_before_suffix(self):
        with self.execution_patches([{'changed':'any row or index order'}]),self.assertRaisesRegex(ValueError,'full-state'):
            r.execute(self.db,self.plan,self.root/'changed')
        self.assertEqual([],self.sql)

    def test_partial_suffix_failure_is_uncertain_and_no_replay(self):
        with self.execution_patches([self.repaired]),patch.object(p,'execute_sql',side_effect=RuntimeError('committed first suffix DDL')),self.assertRaises(RuntimeError):
            r.execute(self.db,self.plan,self.root/'failed')
        self.assertEqual('FAILED_UNCERTAIN',r.Evidence(self.root/'failed').rows()[-1]['kind'])
        with self.assertRaisesRegex(ValueError,'must not be replayed'): r.execute(self.db,self.plan,self.root/'failed')

    def test_full_final_schema_difference_is_not_ignored(self):
        with self.execution_patches([self.repaired,self.after]),self.assertRaisesRegex(ValueError,'final full-field/schema'):
            r.execute(self.db,self.plan,self.root/'mismatch',expected={'after':{'same_data':'different index order'}})
        self.assertEqual('FAILED_UNCERTAIN',r.Evidence(self.root/'mismatch').rows()[-1]['kind'])

    def test_business_suffix_requires_real_owner_policy(self):
        self.db.test = False
        with self.assertRaisesRegex(ValueError,'real owner'): r.execute(self.db,self.plan,self.root/'no-auth')
        self.assertEqual([],self.sql)

    def test_events_or_foreign_key_protection_not_removed(self):
        self.db.query = lambda sql: ['ON']
        with patch.object(c,'maintenance'),patch.object(p,'runtime_mode',return_value=self.mode),self.assertRaisesRegex(ValueError,'event-writer'):
            r.runtime(self.db,self.plan)

    def test_new_evidence_detects_tamper_and_reserved_fields(self):
        evidence = r.Evidence(self.root/'evidence'); evidence.append({'kind':'SUFFIX_INTENT'})
        with self.assertRaisesRegex(ValueError,'Reserved'): evidence.append({'sequence':0})
        path = evidence.directory/'000000.json'; path.write_bytes(path.read_bytes().replace(b'SUFFIX_INTENT',b'SUFFIX_COMPLETE'))
        with self.assertRaisesRegex(ValueError,'changed'): evidence.rows()

    def test_continuation_keeps_original_baseline_history_start_and_current_backup(self):
        self.plan['preservation_proof'] = {'backup':{'sha256':'current-repaired-backup'},'restore_input':{'sha256':'exact-input'}}
        actual = {'plan':self.plan,'qualification':{'target':self.other},'after':self.after}
        with patch.object(r,'validate_actual'),patch.object(c,'sources',return_value='new-source'):
            proposal,proof = r.continuation(actual,self.root/'plan.json',self.root/'proof.json')
        self.assertEqual(0,proposal['start']); self.assertEqual(3,proof['next'])
        self.assertIn('repaired_suffix_recovery',proposal); self.assertNotIn('partial_recovery',proposal)
        for key in ('initial','preservation_columns','preserved_sha256','legacy_column_reuse','history_revision_seed'):
            self.assertEqual(self.original[key],proposal[key])
        self.assertEqual('new-source',proposal['source_sha256']); self.assertEqual('current-repaired-backup',proof['backup']['sha256'])

    def test_controlled_apply_starts_at_phase_four_without_old_replay(self):
        self.db.test = False; restore = SimpleNamespace(test=True)
        policy = object.__new__(OwnerLiveTestPolicy); policy.guard = lambda:None; policy.verify = lambda *args:None
        ledger = OwnerLiveTestLedger(self.root/'continuation',policy)
        proposal = {**self.original,'source_sha256':'current','allowed_metadata_append':None,
                    'repaired_suffix_recovery':{'actual':{'after':self.after}}}
        proof = {'result':'PASS','plan_sha256':c.digest(proposal),'source':self.source,'backup':{'path':'unit','sha256':'bytes'},
                 'restore_input':{'path':'unit','sha256':'bytes'},'restore':self.other,'restored':self.after,'ledger_tip':None,'next':3}
        calls = []
        with ExitStack() as stack:
            for item in [patch.object(c,'sources',return_value='current'),patch.object(c,'migrations',return_value=self.original['migrations']),
                         patch.object(c,'target',side_effect=lambda db:self.source if db is self.db else self.other),
                         patch.object(c,'state',return_value=self.after),patch.object(c,'maintenance'),patch.object(c.core,'file_hash',return_value='bytes'),
                         patch.object(r,'validate_apply'),patch.object(c,'history_revision_seed',return_value=self.original['history_revision_seed']),
                         patch.object(c,'preserved',return_value={}),patch.object(c,'verify_preserved'),patch.object(c,'metadata_receipt_after_phase'),
                         patch.object(c.core,'guard'),patch.object(c,'execute_phase',side_effect=lambda db,path,reuse:calls.append(path.name) or 'TEST')]:
                stack.enter_context(item)
            result = c.apply(self.db,proposal,proof,restore,{},ledger)
        self.assertEqual('COMPLETE',result['kind']); self.assertEqual([item['name'] for item in self.original['migrations'][3:]],calls)
        self.assertEqual(3,ledger.rows()[0]['next']); self.assertEqual(self.after,ledger.rows()[0]['state'])


if __name__ == '__main__': unittest.main()
