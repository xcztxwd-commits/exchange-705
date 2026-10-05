"""Offline refusal/continuation checks only; native full-copy qualification is separate."""
import copy
import hashlib
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import controlled_migration as c
import partial_forward_recovery as p
from owner_live_test_migration import OwnerLiveTestLedger, OwnerLiveTestPolicy


class PartialRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='mt705-partial-unit-')
        self.addCleanup(self.temp.cleanup); self.root = Path(self.temp.name)
        self.keys = [{'table': 'child_' + str(i), 'name': 'mt_fk_' + format(i, '020x'),
                      'parent': 'user_account', 'columns': ['tenant_id', 'user_id'],
                      'parent_columns': ['tenant_id', 'id'], 'update': 'RESTRICT', 'delete': 'RESTRICT'} for i in range(37)]
        self.mode = 'STRICT_TRANS_TABLES,NO_AUTO_CREATE_USER,NO_ENGINE_SUBSTITUTION'
        self.source = {'server_uuid': 'source', 'datadir': '/source', 'database': 'exchange_demo'}
        self.other = {'server_uuid': 'restore', 'datadir': '/restore', 'database': 'qualification'}
        self.before = {'schema': 'fault', 'data': {'all': 'old full rows'}}
        self.repaired = {'schema': 'auto_increment', 'data': self.before['data']}
        self.after = {'schema': 'v02', 'data': {'all': 'new full rows'}}
        before, tail = p.sql_parts()
        self.original = {'start': 0, 'migrations': c.migrations(), 'schema_epoch': c.core.EPOCH,
                         'target': self.source, 'preservation_columns': {'user_account': ['id']},
                         'preserved_sha256': 'old business', 'source_sha256': 'old', 'initial': {'original': True},
                         'legacy_column_reuse': ['V2026092903__activity_delivery_settings.sql'],
                         'history_revision_seed': {'old': 'seed'}, 'business_activation_ready': False}
        self.plan = {'kind': p.KIND, 'source_sha256': 'current', 'target': self.source, 'initial': self.before,
                     'failed_plan': self.original, 'migration': {'name': p.NAME, 'sha256': p.SQL_SHA256},
                     'prefix_sha256': hashlib.sha256(before).hexdigest(), 'suffix_sha256': hashlib.sha256(tail).hexdigest(),
                     'timestamp': int(c.now().timestamp()), 'sql_mode': self.mode, 'foreign_keys': self.keys,
                     'operations_sha256': c.digest(p.operations(self.keys)), 'business_activation_ready': False,
                     'engine': {'frozen_root': '/unit-only', 'source_sha256': 'old'}, 'preservation_proof': {}}
        self.sql = []
        self.db = SimpleNamespace(test=True, database='qualification',
                                  sql=lambda sql: self.sql.append(sql), query=lambda sql: ['2026092902\t0'])

    def execution_patches(self, states):
        from contextlib import ExitStack
        stack = ExitStack()
        for item in [patch.object(p, 'validate_contract'), patch.object(c, 'maintenance'),
                     patch.object(p, 'foreign_keys', return_value=self.keys), patch.object(p, 'runtime_mode', return_value=self.mode),
                     patch.object(c, 'target', return_value=self.other), patch.object(c, 'state', side_effect=states),
                     patch.object(c, 'verify_preserved'), patch.object(c, 'preserved', return_value={})]:
            stack.enter_context(item)
        return stack

    def test_exact_original_bytes_suffix_and_fk_operation_order(self):
        before, tail = p.sql_parts(); ops = p.operations(self.keys)
        self.assertEqual(hashlib.sha256(before + p.ALTER + tail).hexdigest(), p.SQL_SHA256)
        self.assertEqual(hashlib.sha256(tail).hexdigest(), '3e8981e76e5519d59bc87719f5f7f53ec54929882bdb8183fd28b562314e5dc4')
        self.assertEqual(75, len(ops)); self.assertEqual(p.ALTER.decode(), ops[37])
        self.assertIn('`child_0` ADD CONSTRAINT', ops[38])
        self.assertNotIn('FOREIGN_KEY_CHECKS', '\n'.join(ops).upper())

    def test_unknown_counts_shapes_and_sql_identifiers_refused(self):
        for keys in [self.keys[:-1], self.keys + [self.keys[0]],
                     [dict(self.keys[0], table='users; DROP TABLE users')] + self.keys[1:],
                     [dict(self.keys[0], delete='CASCADE')] + self.keys[1:]]:
            with self.subTest(keys=keys[0]), self.assertRaises(ValueError): p.operations(keys)

    def test_evidence_is_exclusive_unsigned_and_detects_tamper(self):
        evidence = p.Evidence(self.root / 'chain'); evidence.append({'kind': 'INTENT'})
        evidence.append({'kind': 'FAILED_UNCERTAIN'})
        self.assertEqual('FAILED_UNCERTAIN', evidence.rows()[-1]['kind'])
        path = evidence.directory / '000001.json'
        raw = path.read_bytes(); path.write_bytes(raw.replace(b'FAILED_UNCERTAIN', b'COMPLETE'))
        with self.assertRaises(ValueError): evidence.rows()
        path.write_bytes(raw); path.rename(evidence.directory / '000002.json')
        with self.assertRaises(ValueError): evidence.rows()

    def test_fake_evidence_chain_fields_refused(self):
        with self.assertRaises(ValueError): p.Evidence(self.root / 'fake').append({'sequence': 3})

    def test_disabled_fk_checks_or_changed_session_mode_refused(self):
        for row in [self.mode + '\t' + self.mode + '\t0\t1',
                    self.mode + '\t' + self.mode + '\t1\t0',
                    self.mode + '\tANSI\t1\t1']:
            with self.subTest(row=row), self.assertRaises(ValueError):
                p.runtime_mode(SimpleNamespace(query=lambda sql: [row]))

    def test_full_repair_and_only_original_suffix_run_under_frozen_mode_and_time(self):
        with self.execution_patches([self.before, self.repaired, self.after]):
            result = p.execute(self.db, self.plan, self.root / 'success')
        self.assertEqual(self.after, result['after']); self.assertEqual(76, len(self.sql))
        self.assertTrue(self.sql[-1].endswith(p.sql_parts()[1].decode()))
        self.assertTrue(all('SET timestamp=' + str(self.plan['timestamp']) in sql for sql in self.sql))
        self.assertTrue(all("sql_mode='" + self.mode + "'" in sql for sql in self.sql))
        self.assertNotIn('FOREIGN_KEY_CHECKS', '\n'.join(self.sql).upper())

    def test_changed_full_fault_refused_before_any_ddl(self):
        with self.execution_patches([{'changed': True}]), self.assertRaisesRegex(ValueError, 'Full fault'):
            p.execute(self.db, self.plan, self.root / 'wrong')
        self.assertEqual([], self.sql)

    def test_partial_failure_records_uncertainty_and_never_replays(self):
        folder = self.root / 'injected'
        with self.execution_patches([self.before]), self.assertRaisesRegex(RuntimeError, 'committed failure'):
            p.execute(self.db, self.plan, folder, fail_after=1)
        self.assertEqual(1, len(self.sql)); self.assertEqual('FAILED_UNCERTAIN', p.Evidence(folder).rows()[-1]['kind'])
        with self.assertRaisesRegex(ValueError, 'must not be replayed'): p.execute(self.db, self.plan, folder)
        self.assertEqual(1, len(self.sql))

    def test_failure_injection_cannot_target_business_database(self):
        self.db.test = False
        with self.assertRaisesRegex(ValueError, 'isolated first-FK'): p.execute(self.db, self.plan, self.root / 'bad', fail_after=1)
        self.assertEqual([], self.sql)

    def test_business_executor_without_real_owner_policy_is_refused(self):
        self.db.test = False
        with self.assertRaisesRegex(ValueError, 'qualified owner authorization'):
            p.execute(self.db, self.plan, self.root / 'unauthorized')
        self.assertEqual([], self.sql)

    def test_user_id_or_any_field_change_stops_before_suffix(self):
        changed = {'schema': 'auto_increment', 'data': {'all': 'ID OR MONEY CHANGED'}}
        with self.execution_patches([self.before, changed]), self.assertRaisesRegex(ValueError, 'changed rows'):
            p.execute(self.db, self.plan, self.root / 'changed')
        self.assertEqual(75, len(self.sql))
        self.assertEqual('FAILED_UNCERTAIN', p.Evidence(self.root / 'changed').rows()[-1]['kind'])

    def test_repaired_schema_and_final_full_state_must_match_qualification(self):
        for expected in [{'repaired': {'wrong': True}, 'after': self.after},
                         {'repaired': self.repaired, 'after': {'wrong': True}}]:
            folder = self.root / str(len(list(self.root.iterdir())))
            with self.execution_patches([self.before, self.repaired, self.after]), self.assertRaises(ValueError):
                p.execute(self.db, self.plan, folder, expected=expected)

    def test_changed_source_suffix_mode_timestamp_or_engine_binding_refused(self):
        with patch.object(c, 'sources', return_value='current'), patch.object(p, 'engine_binding', return_value=self.plan['engine']), patch.object(p, 'proof_files'):
            p.validate_contract(self.plan)
            for key, value in [('source_sha256', 'changed'), ('suffix_sha256', 'changed'), ('timestamp', True),
                               ('sql_mode', "ANSI'; DROP TABLE users;"), ('business_activation_ready', True)]:
                with self.subTest(key=key), self.assertRaises(ValueError): p.validate_contract(dict(self.plan, **{key: value}))
            with patch.object(p, 'engine_binding', return_value={'changed': True}), self.assertRaises(ValueError): p.validate_contract(self.plan)

    def test_original_failed_ledger_remains_byte_identical_and_bound(self):
        policy = object.__new__(OwnerLiveTestPolicy)
        ledger = OwnerLiveTestLedger(self.root / 'original', policy)
        base = {'plan_sha256': c.digest(self.original), 'target': self.source}
        ledger.append({**base, 'kind': 'PHASE_COMPLETE', 'next': 2, 'state': self.before})
        ledger.append({**base, 'kind': 'INTENT', 'index': 2, 'before': self.before, 'migration': self.original['migrations'][2]})
        ledger.append({**base, 'kind': 'FAILED_UNCERTAIN', 'index': 2})
        raw = {file.name: file.read_bytes() for file in ledger.directory.iterdir()}
        self.assertEqual(c.digest(ledger.latest()), p.original_boundary(self.original, ledger))
        self.assertEqual(raw, {file.name: file.read_bytes() for file in ledger.directory.iterdir()})
        with self.assertRaises(ValueError): p.original_boundary(dict(self.original, start=3), ledger)

    def test_continuation_retains_original_business_legacy_history_contracts(self):
        original_proof = {'backup': {'sha256': 'backup'}, 'restore_input': {'sha256': 'input'}}
        self.plan['preservation_proof'] = original_proof
        actual = {'kind': p.KIND + '_ACTUAL_COMPLETE', 'result': 'PASS', 'plan': self.plan,
                  'after': self.after, 'qualification': {'target': self.other}}
        with patch.object(p, 'validate_contract'), patch.object(p, 'validate_qualification'), patch.object(c, 'sources', return_value='current'):
            proposal, proof = p.continuation(actual, self.root / 'proposal.json', self.root / 'proof.json')
        self.assertEqual(0, proposal['start']); self.assertEqual(3, proof['next'])
        for key in ('initial', 'preservation_columns', 'preserved_sha256', 'legacy_column_reuse', 'history_revision_seed'):
            self.assertEqual(self.original[key], proposal[key])
        self.assertEqual(self.after, proof['restored']); self.assertIn('TRANSFORMED_RESTORE_PROOF', proof['kind'])
        self.assertEqual(original_proof['backup'], proof['backup'])

    def test_controlled_apply_starts_at_explicit_phase_four_without_replaying_phase_three(self):
        """Synthetic plumbing only: the protocol validation is tested independently."""
        from contextlib import ExitStack
        self.db.test = False
        restore = SimpleNamespace(test=True)
        policy = object.__new__(OwnerLiveTestPolicy)
        policy.guard = lambda: None; policy.verify = lambda *args: None
        ledger = OwnerLiveTestLedger(self.root / 'controlled', policy)
        proposal = {**self.original, 'source_sha256': 'current', 'allowed_metadata_append': None,
                    'partial_recovery': {'actual': {'after': self.after}}}
        proof = {'result': 'PASS', 'plan_sha256': c.digest(proposal), 'source': self.source,
                 'backup': {'path': 'unit', 'sha256': 'bytes'}, 'restore_input': {'path': 'unit', 'sha256': 'bytes'},
                 'restore': self.other, 'restored': self.after, 'ledger_tip': None, 'next': 3}
        calls = []
        with ExitStack() as stack:
            for item in [patch.object(c, 'sources', return_value='current'), patch.object(c, 'migrations', return_value=self.original['migrations']),
                         patch.object(c, 'target', side_effect=lambda db: self.source if db is self.db else self.other),
                         patch.object(c, 'state', return_value=self.after), patch.object(c, 'maintenance'),
                         patch.object(c.core, 'file_hash', return_value='bytes'), patch.object(p, 'validate_apply'),
                         patch.object(c, 'history_revision_seed', return_value=self.original['history_revision_seed']),
                         patch.object(c, 'preserved', return_value={}), patch.object(c, 'verify_preserved'),
                         patch.object(c, 'metadata_receipt_after_phase'), patch.object(c.core, 'guard'),
                         patch.object(c, 'execute_phase', side_effect=lambda db, path, reuse: calls.append(path.name) or 'TEST')]:
                stack.enter_context(item)
            result = c.apply(self.db, proposal, proof, restore, {}, ledger)
        self.assertEqual('COMPLETE', result['kind'])
        self.assertEqual([item['name'] for item in self.original['migrations'][3:]], calls)
        self.assertEqual(3, ledger.rows()[0]['next']); self.assertEqual(self.after, ledger.rows()[0]['state'])
        self.assertEqual(0, proposal['start'])


if __name__ == '__main__': unittest.main()
