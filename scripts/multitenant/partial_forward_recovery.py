"""One bounded recovery for the retained demo V2026092902 / ERROR 1833 boundary.

The complete-only forward_recovery protocol is intentionally unchanged. Workflow:
prepare -> qualify on a NEW restored database -> apply once -> continue_with_plan.
Original failure, SQL, backup, restore proof and protected restore remain immutable.
Evidence is explicitly unsigned; only the existing real owner policy authorizes DDL.
"""
import ast
import hashlib
import inspect
import re
import secrets
from pathlib import Path
import controlled_migration as c
from owner_live_test_migration import OwnerLiveTestLedger, OwnerLiveTestPolicy

NAME = 'V2026092902__multitenant_isolation.sql'
SQL_SHA256 = '24ffda4124a169d2eb8d866d0af92f7f46476d9156e7edc9333651b72bd450bd'
ALTER = b'ALTER TABLE user_account MODIFY id BIGINT NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=7000001;'
KIND = 'BOUNDED_DEMO_V02_PARTIAL_FORWARD_RECOVERY_V1'
ENGINE_FUNCTIONS = ('canonical', 'digest', 'schema', 'database_defaults', 'create_restore_database',
                    'all_fields', 'state', 'preserved', 'verify_preserved', 'restore_input',
                    'restore_trigger_sql_modes', 'stream_restore_trigger_sql_modes')


def source_hash(root):
    """Same immutable source scope as controlled_migration.sources, at a frozen root."""
    files = {}
    for top in ('exchange-backend/src', 'scripts/multitenant'):
        for path in sorted((root / top).rglob('*')):
            if path.is_file() and not path.is_symlink() and '__pycache__' not in path.parts and path.suffix not in ('.class', '.pyc', '.log'):
                files[path.relative_to(root).as_posix()] = c.core.file_hash(path)
    for top in ('exchange-admin', 'exchange-frontend', 'exchange-pc'):
        for name in ('package.json', 'package-lock.json', 'pnpm-lock.yaml'):
            path = root / top / name
            if path.is_file(): files[path.relative_to(root).as_posix()] = c.core.file_hash(path)
    return c.digest(files)


def engine_binding(frozen_root, expected_source):
    """Reuse old backup evidence only with identical row/schema/restore algorithms."""
    root = Path(frozen_root).resolve()
    if source_hash(root) != expected_source:
        raise ValueError('Old frozen source does not match preservation evidence')
    old = root / 'scripts/multitenant'
    if c.core.file_hash(old / 'mysql_migration.py') != c.core.file_hash(Path(c.core.__file__)):
        raise ValueError('Original fingerprint/transport engine changed; old baseline cannot be reused')
    text = (old / 'controlled_migration.py').read_text(encoding='utf-8')
    nodes = {node.name: ast.get_source_segment(text, node) for node in ast.parse(text).body if isinstance(node, ast.FunctionDef)}
    hashes = {}
    for name in ENGINE_FUNCTIONS:
        current = inspect.getsource(getattr(c, name)).strip()
        if nodes.get(name, '').strip() != current:
            raise ValueError('Preservation/restore algorithm differs: ' + name)
        hashes[name] = hashlib.sha256(current.encode()).hexdigest()
    return {'frozen_root': str(root), 'source_sha256': expected_source,
            'mysql_migration_sha256': c.core.file_hash(old / 'mysql_migration.py'), 'functions': hashes}


class Evidence:
    """Exclusive, append-only unsigned evidence; never an approval or source ledger."""
    def __init__(self, directory): self.directory = Path(directory)

    def rows(self):
        rows = []; previous = '0' * 64
        for number, path in enumerate(sorted(self.directory.glob('*.json'))):
            item = c.read(path); body = item['body']
            if (path.name != f'{number:06d}.json' or body.get('sequence') != number or
                    body.get('previous') != previous or body.get('journal_kind') != KIND or item.get('sha256') != c.digest(body)):
                raise ValueError('Partial recovery evidence incomplete or changed')
            rows.append(body); previous = c.digest(item)
        return rows

    def append(self, body):
        if set(body).intersection(('sequence', 'previous', 'at', 'journal_kind')):
            raise ValueError('Cannot replace recovery chain fields')
        rows = self.rows(); previous = c.digest(c.read(self.directory / f'{len(rows)-1:06d}.json')) if rows else '0' * 64
        value = {'sequence': len(rows), 'previous': previous, 'at': c.now().isoformat(), 'journal_kind': KIND, **body}
        c.publish(self.directory / f'{len(rows):06d}.json', {'body': value, 'sha256': c.digest(value)})


def original_boundary(original, failed):
    if not isinstance(failed, OwnerLiveTestLedger):
        raise ValueError('Retained real owner ledger required; no fabricated signed boundary')
    rows = failed.rows()
    if len(rows) < 3 or rows[-1].get('kind') != 'FAILED_UNCERTAIN' or rows[-1].get('index') != 2:
        raise ValueError('Exact retained third-stage FAILED_UNCERTAIN required')
    intent, prior = rows[-2], rows[-3]
    if (intent.get('kind') != 'INTENT' or intent.get('index') != 2 or
            intent.get('migration') != {'name': NAME, 'sha256': SQL_SHA256} or
            prior.get('kind') not in ('BEGIN', 'PHASE_COMPLETE', 'RESUME_VERIFIED') or
            prior.get('next') != 2 or prior.get('state') != intent.get('before')):
        raise ValueError('Original uncertain intent/certain boundary differs')
    if (original.get('start') != 0 or original['migrations'] != c.migrations() or
            original['migrations'][2] != intent['migration'] or original['schema_epoch'] != c.core.EPOCH):
        raise ValueError('Original phase/migration bytes/epoch changed')
    if any(row.get('plan_sha256') != c.digest(original) or row.get('target') != original['target'] for row in rows):
        raise ValueError('Retained ledger is not bound to original plan/target')
    return c.digest(rows[-1])


def sql_parts():
    raw = c.core.MIGRATIONS[2].read_bytes()
    if hashlib.sha256(raw).hexdigest() != SQL_SHA256 or raw.count(ALTER) != 1:
        raise ValueError('Only exact original V02 bytes may be recovered')
    before, tail = raw.split(ALTER)
    if not tail.startswith(b'\nALTER TABLE user_account ADD COLUMN last_page_code'):
        raise ValueError('Exact unexecuted V02 suffix not found')
    return before, tail


def foreign_keys(db):
    rows = db.query("SELECT JSON_OBJECT('table',k.TABLE_NAME,'name',k.CONSTRAINT_NAME,'column',k.COLUMN_NAME,"
                    "'parent',k.REFERENCED_TABLE_NAME,'parent_column',k.REFERENCED_COLUMN_NAME,'position',k.ORDINAL_POSITION,"
                    "'update',r.UPDATE_RULE,'delete',r.DELETE_RULE,'schema',k.TABLE_SCHEMA,'parent_schema',k.REFERENCED_TABLE_SCHEMA) "
                    "FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r "
                    "ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.TABLE_NAME=k.TABLE_NAME AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME "
                    "WHERE k.REFERENCED_TABLE_SCHEMA=DATABASE() AND k.REFERENCED_TABLE_NAME='user_account' "
                    "ORDER BY k.TABLE_NAME,k.CONSTRAINT_NAME,k.ORDINAL_POSITION")
    import json
    groups = {}
    for row in map(json.loads, rows):
        if row.pop('schema') != db.database or row.pop('parent_schema') != db.database:
            raise ValueError('Cross-database inbound foreign key is outside recovery scope')
        key = (row['table'], row['name'])
        entry = groups.setdefault(key, {key: row[key] for key in ('table', 'name', 'parent', 'update', 'delete')})
        columns = entry.setdefault('columns', []); parents = entry.setdefault('parent_columns', [])
        if row['position'] != len(columns) + 1: raise ValueError('Foreign key ordering is ambiguous')
        columns.append(row['column']); parents.append(row['parent_column'])
    result = list(groups.values()); operations(result)
    return result


def operations(keys):
    if len(keys) != 37 or len({(key['table'], key['name']) for key in keys}) != 37:
        raise ValueError('Exactly the bound 37 inbound foreign keys required')
    drops = []; adds = []
    for key in keys:
        if (key['parent'] != 'user_account' or key['parent_columns'] != ['tenant_id', 'id'] or
                len(key['columns']) != 2 or key['columns'][0] != 'tenant_id' or
                key['update'] != 'RESTRICT' or key['delete'] != 'RESTRICT' or not re.fullmatch('mt_fk_[0-9a-f]{20}', key['name'])):
            raise ValueError('Unreviewed inbound foreign-key shape')
        table, name = c.core.ident(key['table']), c.core.ident(key['name'])
        drops.append('ALTER TABLE ' + table + ' DROP FOREIGN KEY ' + name + ';')
        adds.append('ALTER TABLE ' + table + ' ADD CONSTRAINT ' + name + ' FOREIGN KEY(' + ','.join(map(c.core.ident, key['columns'])) +
                    ') REFERENCES `user_account`(`tenant_id`,`id`) ON UPDATE RESTRICT ON DELETE RESTRICT;')
    return drops + [ALTER.decode()] + adds


def runtime_mode(db):
    modes = db.query('SELECT @@global.sql_mode,@@session.sql_mode,@@global.foreign_key_checks,@@session.foreign_key_checks')
    if len(modes) != 1: raise ValueError('Exact runtime SQL mode required')
    global_mode, mode, global_fk, session_fk = modes[0].split('\t')
    if global_mode != mode or global_fk != '1' or session_fk != '1' or not re.fullmatch('[A-Z0-9_,]*', mode):
        raise ValueError('Original SQL_MODE and enabled foreign-key checks required')
    return mode


def proof_files(proof):
    if proof.get('result') != 'PASS' or proof['backup'].get('schema_only') is not False:
        raise ValueError('Qualified full backup/restore proof required')
    for name in ('backup', 'restore_input'):
        if c.core.file_hash(Path(proof[name]['path'])) != proof[name]['sha256']:
            raise ValueError('Bound backup/restore-input bytes changed')


def independent(db_target, source_target):
    return (db_target['server_uuid'] != source_target['server_uuid'] and db_target['datadir'] != source_target['datadir'])


def prepare(db, original, failed, observation, proof, protected_restore, frozen_root, output):
    """Freeze an exact current fault, never infer partial completion from names."""
    failed_tip = original_boundary(original, failed); before, tail = sql_parts()
    engine = engine_binding(frozen_root, observation['source_sha256'])
    proof_files(proof); c.maintenance(db)
    if (observation.get('kind') != 'UNCERTAIN_STATE_PRESERVATION_ONLY_NOT_APPLY_PLAN' or
            observation.get('retained_failed_plan_sha256') != c.digest(original) or
            observation.get('retained_failed_tip_sha256') != failed_tip or observation.get('start') != 2 or
            observation.get('original_failed_source_sha256') != original['source_sha256'] or
            observation['target'] != original['target'] or c.target(db) != original['target'] or
            proof['plan_sha256'] != c.digest(observation) or proof['source'] != original['target'] or
            proof['restored'] != observation['initial'] or proof.get('ledger_tip') is not None or proof.get('next') != 2 or
            not protected_restore.test or c.target(protected_restore) != proof['restore'] or
            not independent(proof['restore'], original['target'])):
        raise ValueError('Current failure and independent preservation evidence differ')
    if c.state(db) != observation['initial'] or c.state(protected_restore) != observation['initial']:
        raise ValueError('Source or protected full restored fault differs')
    c.verify_preserved(original, c.preserved(db, original['preservation_columns']), 2)
    mode = runtime_mode(db)
    if db.query("SELECT DISTINCT SQL_MODE FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME IN ('mt_scope','mt_link','mt_exec')") != [mode]:
        raise ValueError('Original retained routine SQL_MODE differs from recovery mode')
    keys = foreign_keys(db)
    if db.query('SELECT COUNT(*) FROM user_account WHERE id=0') != ['0']:
        raise ValueError('ID zero could be renumbered by AUTO_INCREMENT; bounded repair refused')
    if 'auto_increment' in '\n'.join(db.query("SELECT EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_account' AND COLUMN_NAME='id'")):
        raise ValueError('This protocol only repairs the exact unapplied ID attribute')
    value = {'kind': KIND, 'id': secrets.token_hex(16), 'created_at': c.now().isoformat(),
             'source_sha256': c.sources(), 'target': original['target'], 'initial': observation['initial'],
             'failed_plan': original, 'failed_ledger': str(failed.directory.resolve()), 'failed_tip': failed_tip,
             'observation': observation, 'preservation_proof': proof, 'engine': engine,
             'migration': original['migrations'][2], 'prefix_sha256': hashlib.sha256(before).hexdigest(),
             'suffix_sha256': hashlib.sha256(tail).hexdigest(), 'sql_mode': mode,
             'timestamp': int(c.now().timestamp()), 'foreign_keys': keys,
             'operations_sha256': c.digest(operations(keys)), 'business_activation_ready': False}
    c.publish(output, value); return value


def validate_contract(plan):
    before, tail = sql_parts()
    if (plan['kind'] != KIND or c.sources() != plan['source_sha256'] or plan['business_activation_ready'] is not False or
            plan['migration'] != {'name': NAME, 'sha256': SQL_SHA256} or
            plan['failed_plan']['migrations'] != c.migrations() or
            hashlib.sha256(before).hexdigest() != plan['prefix_sha256'] or hashlib.sha256(tail).hexdigest() != plan['suffix_sha256'] or
            c.digest(operations(plan['foreign_keys'])) != plan['operations_sha256'] or
            type(plan['timestamp']) is not int or not 1700000000 <= plan['timestamp'] <= int(c.now().timestamp()) or
            not re.fullmatch('[A-Z0-9_,]*', plan['sql_mode'])):
        raise ValueError('Immutable partial recovery source/SQL/scope changed')
    if engine_binding(plan['engine']['frozen_root'], plan['engine']['source_sha256']) != plan['engine']:
        raise ValueError('Frozen preservation engine binding changed')
    proof_files(plan['preservation_proof'])


def execute_sql(db, plan, sql):
    # No FK_CHECKS override: each fresh connection must inherit enabled checks.
    if runtime_mode(db) != plan['sql_mode']: raise ValueError('Original SQL_MODE changed')
    db.sql("SET SESSION sql_mode='" + plan['sql_mode'] + "'; SET SESSION time_zone='+00:00'; SET timestamp=" + str(plan['timestamp']) + ';\n' + sql)


def execute(db, plan, directory, expected=None, fail_after=None, policy=None):
    """Never resume this DDL batch; any intent, failure or prior run forbids replay."""
    journal = Evidence(directory)
    if journal.rows(): raise ValueError('Partial recovery already attempted; uncertain DDL must not be replayed')
    if fail_after is not None and (not db.test or fail_after != 1):
        raise ValueError('Failure injection is isolated first-FK boundary only')
    if not db.test:
        if not isinstance(policy, OwnerLiveTestPolicy) or policy.db is not db or expected is None:
            raise ValueError('Actual DDL requires explicit qualified owner authorization')
        policy.guard(); validate_qualification(plan, expected)
        if (policy.value.get('partial_recovery_review') != review_binding(plan, expected) or
                policy.value.get('binding') != c.binding(plan, plan['preservation_proof'])):
            raise ValueError('Actual DDL owner recovery binding differs')
    validate_contract(plan); c.maintenance(db)
    if (foreign_keys(db) != plan['foreign_keys'] or runtime_mode(db) != plan['sql_mode'] or c.state(db) != plan['initial']):
        raise ValueError('Full fault boundary differs; no repair DDL permitted')
    target = c.target(db); base = {'plan_sha256': c.digest(plan), 'target': target}
    journal.append({**base, 'kind': 'INTENT', 'before': plan['initial']})
    try:
        for index, sql in enumerate(operations(plan['foreign_keys'])):
            journal.append({**base, 'kind': 'SQL_INTENT', 'index': index, 'sql_sha256': hashlib.sha256(sql.encode()).hexdigest()})
            execute_sql(db, plan, sql)
            journal.append({**base, 'kind': 'SQL_COMPLETE', 'index': index})
            if fail_after == index + 1: raise RuntimeError('Injected isolated first-FK committed failure')
        repaired = c.state(db)
        if repaired['data'] != plan['initial']['data'] or foreign_keys(db) != plan['foreign_keys']:
            raise ValueError('ID attribute repair changed rows or foreign-key definitions')
        if expected is not None and repaired != expected['repaired']:
            raise ValueError('Repaired full schema/field state differs from qualification')
        journal.append({**base, 'kind': 'ID_ATTRIBUTE_COMPLETE', 'state': repaired})
        tail = sql_parts()[1]
        journal.append({**base, 'kind': 'SUFFIX_INTENT', 'sql_sha256': plan['suffix_sha256']})
        execute_sql(db, plan, tail.decode('utf-8'))
        c.verify_preserved(plan['failed_plan'], c.preserved(db, plan['failed_plan']['preservation_columns']), 2)
        after = c.state(db)
        if foreign_keys(db) != plan['foreign_keys'] or (expected is not None and after != expected['after']):
            raise ValueError('Completed full schema/field state differs from qualification')
        if db.query('SELECT version,business_activation_ready+0 FROM tenant_schema_version') != ['2026092902\t0']:
            raise ValueError('Exact inactive V02 boundary receipt required')
        journal.append({**base, 'kind': 'PARTIAL_PHASE_COMPLETE', 'state': after, 'next': 3})
        return {'repaired': repaired, 'after': after, 'target': target,
                'journal': str(journal.directory.resolve()), 'tip': c.digest(journal.rows()[-1])}
    except BaseException as error:
        journal.append({**base, 'kind': 'FAILED_UNCERTAIN', 'failure_type': type(error).__name__})
        raise


def qualify(plan, protected_restore, candidate, directory, output, candidate_receipt=None):
    """Restore once into a new named copy, test committed failure and exact FK repair."""
    validate_contract(plan); proof = plan['preservation_proof']; folder = Path(directory)
    other = c.target(candidate)
    if (not candidate.test or not protected_restore.test or c.target(protected_restore) != proof['restore'] or
            not independent(other, plan['target']) or other == proof['restore']):
        raise ValueError('New isolated qualification database; protected restore must remain untouched')
    if c.state(protected_restore) != plan['initial']:
        raise ValueError('Protected independently restored fault changed')
    if candidate_receipt is None:
        c.create_restore_database(protected_restore, candidate)
        candidate.restore_file(proof['restore_input']['path'])
    elif (candidate_receipt.get('kind') != 'PASS_REUSED_EXACT_FAULT_BACKUP_NEW_INDEPENDENT_QUALIFICATION_COPY' or
            candidate_receipt.get('backup') != proof['backup'] or candidate_receipt.get('restore_input') != proof['restore_input'] or
            candidate_receipt.get('target') != other or candidate_receipt.get('state') != plan['initial'] or
            candidate_receipt.get('original_proof_sha256') != c.digest(proof) or
            c.read(candidate_receipt['original_proof_path']) != proof or
            candidate_receipt.get('source_sha256') != plan['engine']['source_sha256'] or
            candidate_receipt.get('production_ddl_dml') != 0 or candidate_receipt.get('original_restore_copy_unchanged') is not True):
        raise ValueError('Pre-restored candidate receipt has different bytes/target/full fault state')
    if c.state(candidate) != plan['initial']: raise ValueError('Fresh recovery copy is not the full bound fault')
    # The test instance itself must be placed in read_only maintenance by its operator.
    try: execute(candidate, plan, folder / 'injected-failure', fail_after=1)
    except RuntimeError as error:
        if str(error) != 'Injected isolated first-FK committed failure': raise
    else: raise ValueError('Required committed-DDL failure injection did not occur')
    failed = Evidence(folder / 'injected-failure')
    try: execute(candidate, plan, folder / 'injected-failure')
    except ValueError as error:
        if 'must not be replayed' not in str(error): raise
    else: raise ValueError('Failed batch was incorrectly replayable')
    # This isolated, exact one-step completion is not a general source rollback path.
    # It restores enforcement with FK checks enabled; no user rows/IDs are rewritten.
    execute_sql(candidate, plan, operations(plan['foreign_keys'])[38])
    if c.state(candidate) != plan['initial'] or foreign_keys(candidate) != plan['foreign_keys']:
        raise ValueError('Injected failure cannot be restored to exact full fault boundary')
    failure_proof = {'journal': str(failed.directory.resolve()), 'tip': c.digest(failed.rows()[-1]),
                     'failed_replay_refused': True, 'exact_fk_forward_restore_verified': True}
    result = execute(candidate, plan, folder / 'qualified-execution')
    value = {'kind': KIND + '_QUALIFICATION', 'result': 'PASS', 'plan_sha256': c.digest(plan),
             'source_sha256': plan['source_sha256'], 'backup_sha256': proof['backup']['sha256'],
             'restore_input_sha256': proof['restore_input']['sha256'], 'restored_fault': plan['initial'],
             'failure_test': failure_proof, **result, 'activation_ready': False}
    c.publish(output, value); return value


def validate_qualification(plan, qualification):
    if (qualification.get('kind') != KIND + '_QUALIFICATION' or qualification.get('result') != 'PASS' or
            qualification.get('plan_sha256') != c.digest(plan) or qualification.get('source_sha256') != plan['source_sha256'] or
            qualification.get('backup_sha256') != plan['preservation_proof']['backup']['sha256'] or
            qualification.get('restore_input_sha256') != plan['preservation_proof']['restore_input']['sha256'] or
            qualification.get('restored_fault') != plan['initial'] or qualification.get('activation_ready') is not False or
            not independent(qualification['target'], plan['target'])):
        raise ValueError('Current-version exact full-copy qualification required')
    rows = Evidence(qualification['journal']).rows()
    failed = qualification['failure_test']; failures = Evidence(failed['journal']).rows()
    repaired = [row for row in rows if row.get('kind') == 'ID_ATTRIBUTE_COMPLETE']
    if (not rows or rows[-1].get('kind') != 'PARTIAL_PHASE_COMPLETE' or rows[-1].get('state') != qualification['after'] or
            c.digest(rows[-1]) != qualification['tip'] or not failures or failures[-1].get('kind') != 'FAILED_UNCERTAIN' or
            c.digest(failures[-1]) != failed['tip'] or failed.get('failed_replay_refused') is not True or
            failed.get('exact_fk_forward_restore_verified') is not True or
            len(repaired) != 1 or repaired[0].get('state') != qualification['repaired'] or
            qualification['repaired']['data'] != plan['initial']['data'] or
            any(row.get('plan_sha256') != c.digest(plan) or row.get('target') != qualification['target'] for row in rows + failures)):
        raise ValueError('Qualification success/failure evidence changed')


def review_binding(plan, qualification):
    return {'decision': 'EXACT_PARTIAL_FORWARD_ONLY', 'plan_sha256': c.digest(plan),
            'qualification_sha256': c.digest(qualification), 'failed_tip': plan['failed_tip']}


def apply(db, plan, qualification, qualified_restore, policy, directory, output):
    """Only the actual owner-authorized target receives this exact qualified sequence."""
    validate_contract(plan); validate_qualification(plan, qualification)
    if not isinstance(policy, OwnerLiveTestPolicy): raise ValueError('Real owner live-test authorization required')
    policy.guard()
    if (policy.db is not db or policy.restore is not qualified_restore or
            policy.value.get('partial_recovery_review') != review_binding(plan, qualification) or
            policy.value.get('binding') != c.binding(plan, plan['preservation_proof']) or
            c.target(db) != plan['target'] or c.target(qualified_restore) != qualification['target'] or
            c.state(qualified_restore) != qualification['after']):
        raise ValueError('Actual owner/physical source/qualified restore binding differs')
    failed = OwnerLiveTestLedger(Path(plan['failed_ledger']), policy)
    if original_boundary(plan['failed_plan'], failed) != plan['failed_tip']:
        raise ValueError('Original failed ledger changed')
    result = execute(db, plan, directory, expected=qualification, policy=policy)
    value = {'kind': KIND + '_ACTUAL_COMPLETE', 'result': 'PASS', 'plan': plan, 'qualification': qualification,
             'owner_authorization': str(policy.path), 'owner_authorization_sha256': c.core.file_hash(policy.path),
             **result, 'activation_ready': False}
    c.publish(output, value); return value


def continuation(actual, output_plan, output_proof):
    """A transformed-restore proof is explicit; it never pretends a fresh dump exists."""
    if actual.get('kind') != KIND + '_ACTUAL_COMPLETE' or actual.get('result') != 'PASS':
        raise ValueError('Actual completed partial recovery required')
    plan = actual['plan']; validate_contract(plan); validate_qualification(plan, actual['qualification'])
    recovery = {'actual': actual}
    proposal = {**plan['failed_plan'], 'id': secrets.token_hex(16), 'created_at': c.now().isoformat(),
                'source_sha256': c.sources(), 'partial_recovery': recovery, 'business_activation_ready': False}
    proof = {**plan['preservation_proof'], 'kind': KIND + '_TRANSFORMED_RESTORE_PROOF',
             'plan_sha256': c.digest(proposal), 'restore': actual['qualification']['target'],
             'restored': actual['after'], 'ledger_tip': None, 'next': 3,
             'transformation_sha256': c.digest(actual), 'created_at': c.now().isoformat()}
    c.publish(output_plan, proposal); c.publish(output_proof, proof)
    return proposal, proof


def validate_apply(proposal, proof, authorization, policy):
    actual = proposal['partial_recovery']['actual']; plan = actual['plan']
    validate_contract(plan); validate_qualification(plan, actual['qualification'])
    if not isinstance(policy, OwnerLiveTestPolicy): raise ValueError('Partial continuation requires real owner policy')
    policy.guard()
    original = plan['failed_plan']; failed = OwnerLiveTestLedger(Path(plan['failed_ledger']), policy)
    rows = Evidence(actual['journal']).rows()
    if (original_boundary(original, failed) != plan['failed_tip'] or actual.get('kind') != KIND + '_ACTUAL_COMPLETE' or
            actual.get('result') != 'PASS' or actual['target'] != plan['target'] or actual['after'] != actual['qualification']['after'] or
            not rows or rows[-1].get('kind') != 'PARTIAL_PHASE_COMPLETE' or rows[-1].get('state') != actual['after'] or
            c.digest(rows[-1]) != actual['tip'] or
            any(row.get('plan_sha256') != c.digest(plan) or row.get('target') != plan['target'] for row in rows) or
            c.core.file_hash(Path(actual['owner_authorization'])) != actual['owner_authorization_sha256'] or
            authorization.get('partial_recovery_review') != review_binding(plan, actual['qualification']) or
            any(proposal.get(key) != original.get(key) for key in original if key not in ('id', 'created_at', 'source_sha256'))):
        raise ValueError('Exact original facts/recovered boundary/owner evidence changed')
    if proof.get('kind') == KIND + '_TRANSFORMED_RESTORE_PROOF' and (
            proof.get('transformation_sha256') != c.digest(actual) or proof['backup'] != plan['preservation_proof']['backup'] or
            proof['restore_input'] != plan['preservation_proof']['restore_input'] or proof['restore'] != actual['qualification']['target'] or
            proof['restored'] != actual['after'] or proof['next'] != 3):
        raise ValueError('Explicit transformed independent restore proof differs')
    if proof.get('kind') != KIND + '_TRANSFORMED_RESTORE_PROOF' and not proof.get('ledger_tip'):
        raise ValueError('Initial partial continuation requires the explicit transformed proof')


def continue_with_plan(db, proposal, proof, restore, policy, ledger_directory):
    """Establish a new explicit recovery boundary, then use ordinary controlled resume."""
    validate_apply(proposal, proof, policy.value, policy)
    ledger = OwnerLiveTestLedger(Path(ledger_directory), policy)
    if ledger.rows(): raise ValueError('Continuation ledger exists; use ordinary controlled resume after a new bound proof')
    return c.apply(db, proposal, proof, restore, policy.value, ledger)
