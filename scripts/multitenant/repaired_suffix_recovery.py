"""Exact suffix-only recovery after the retained 75-statement ID repair failed.

The old protocol, old plan/source and both FAILED_UNCERTAIN chains stay unchanged.
Only the diagnosed five exact index-line moves are admitted at this new boundary;
all subsequent schema and row comparisons use the unchanged full-state algorithm.
"""
import hashlib
import re
import secrets
from pathlib import Path
from types import SimpleNamespace
import controlled_migration as c
import partial_forward_recovery as p
from owner_live_test_migration import OwnerLiveTestLedger, OwnerLiveTestPolicy

KIND = 'BOUNDED_DEMO_V02_REPAIRED_SUFFIX_FORWARD_RECOVERY_V1'
MOVED = {
    'demo_ledger':'mt_fk_21b8187c084d5be03533',
    'financial_order':'mt_fk_e9329799dd0d7b84068e',
    'financial_yield_record':'mt_fk_203e06ffb45190de8b0b',
    'support_conversation':'mt_fk_6729c78e35f7c7efebec',
    'user_account':'mt_fk_72d601353dacb02f6447',
}


class Evidence:
    def __init__(self,directory): self.directory = Path(directory)

    def rows(self):
        rows = []; previous = '0'*64
        for number,path in enumerate(sorted(self.directory.glob('*.json'))):
            item = c.read(path); body = item['body']
            if (path.name != f'{number:06d}.json' or body.get('sequence') != number or
                    body.get('previous') != previous or body.get('journal_kind') != KIND or item.get('sha256') != c.digest(body)):
                raise ValueError('Suffix evidence chain changed or incomplete')
            rows.append(body); previous = c.digest(item)
        return rows

    def append(self,body):
        if set(body).intersection(('sequence','previous','at','journal_kind')): raise ValueError('Reserved chain fields')
        rows = self.rows(); previous = c.digest(c.read(self.directory/f'{len(rows)-1:06d}.json')) if rows else '0'*64
        value = {'sequence':len(rows),'previous':previous,'at':c.now().isoformat(),'journal_kind':KIND,**body}
        c.publish(self.directory/f'{len(rows):06d}.json',{'body':value,'sha256':c.digest(value)})


def failed_repair_boundary(old,directory):
    rows = p.Evidence(directory).rows(); operations = p.operations(old['foreign_keys'])
    if len(rows) != 152 or len(operations) != 75 or rows[0].get('kind') != 'INTENT' or rows[0].get('before') != old['initial']:
        raise ValueError('Exact 75 committed repair statements and retained failure required')
    for index,sql in enumerate(operations):
        intent,complete = rows[2*index+1:2*index+3]
        if (intent.get('kind') != 'SQL_INTENT' or complete.get('kind') != 'SQL_COMPLETE' or
                intent.get('index') != index or complete.get('index') != index or
                intent.get('sql_sha256') != hashlib.sha256(sql.encode()).hexdigest()):
            raise ValueError('Repair SQL completion sequence differs; never infer a partial outcome')
    if rows[-1].get('kind') != 'FAILED_UNCERTAIN' or rows[-1].get('failure_type') != 'ValueError':
        raise ValueError('Exact retained post-repair refusal required')
    if any(row.get('plan_sha256') != c.digest(old) or row.get('target') != old['target'] for row in rows):
        raise ValueError('Repair chain source/plan/target differs')
    return c.digest(rows[-1])


def original_failure(old):
    # Read historical evidence with the unchanged reader, never instantiate an
    # expired policy or represent historical source as current authorization.
    rows = OwnerLiveTestLedger.rows(SimpleNamespace(directory=Path(old['failed_ledger'])))
    original = old['failed_plan']
    if (len(rows) < 3 or rows[-1].get('kind') != 'FAILED_UNCERTAIN' or rows[-1].get('index') != 2 or
            c.digest(rows[-1]) != old['failed_tip'] or rows[-2].get('kind') != 'INTENT' or
            rows[-2].get('index') != 2 or rows[-2].get('migration') != old['migration'] or
            rows[-3].get('kind') not in ('BEGIN','PHASE_COMPLETE','RESUME_VERIFIED') or
            rows[-3].get('next') != 2 or rows[-3].get('state') != rows[-2].get('before') or
            original.get('start') != 0 or original['migrations'] != c.migrations() or
            original['schema_epoch'] != c.core.EPOCH or
            any(row.get('plan_sha256') != c.digest(original) or row.get('target') != original['target'] for row in rows)):
        raise ValueError('Original third-phase failure/history contract changed')


def validate_diagnosis(old,qualified,diagnosis,failed_tip):
    names = {'table:'+table for table in MOVED}; expected = qualified['repaired']['schema']; actual = diagnosis['actual_schema']
    if (diagnosis.get('kind') != 'READ_ONLY_DIAGNOSTIC_EXACT_ACTUAL_REPAIR_FAILED_SCHEMA' or
            diagnosis.get('ddl_dml') != 0 or diagnosis.get('failed_tip') != failed_tip or
            diagnosis.get('source_sha256') != old['source_sha256'] or diagnosis.get('target') != old['target'] or
            diagnosis.get('expected_repaired_schema') != expected or set(diagnosis['different_objects']) != names or
            set(diagnosis['definitions']) != names or set(actual['objects']) != set(expected['objects']) or
            {name for name in expected['objects'] if actual['objects'][name] != expected['objects'][name]} != names or
            c.digest(actual['objects']) != actual['sha256']):
        raise ValueError('Exact read-only five-object schema diagnosis required')
    mode = old['sql_mode']+'\t'+old['sql_mode']+'\t1\t1\t1\t1'
    if diagnosis['modes'] != {'actual':[mode],'qualified':[mode]}: raise ValueError('Historical mode/offset/FK diagnosis changed')
    for table,index in MOVED.items():
        key = 'table:'+table; definitions = diagnosis['definitions'][key]; initial = definitions['protected_initial']
        if c.digest(initial) != old['initial']['schema']['objects'][key]: raise ValueError('Original table definition differs')
        repaired = list(initial)
        if table == 'user_account':
            if repaired.count('  `id` bigint(20) NOT NULL,') != 1 or ' AUTO_INCREMENT=' in repaired[-1]:
                raise ValueError('Exact original non-autoincrement user ID required')
            repaired[repaired.index('  `id` bigint(20) NOT NULL,')] = '  `id` bigint(20) NOT NULL AUTO_INCREMENT,'
            repaired[-1] = repaired[-1].replace(') ENGINE=InnoDB ',') ENGINE=InnoDB AUTO_INCREMENT=7000020 ',1)
        if c.digest(repaired) != expected['objects'][key]: raise ValueError('Exact qualified repaired definition differs')
        selected = [line for line in repaired if line.startswith('  KEY `'+index+'` ')]
        if len(selected) != 1: raise ValueError('Exact moved FK-support index required')
        repaired.remove(selected[0]); position = next(i for i,line in enumerate(repaired) if line.startswith('  CONSTRAINT '))
        repaired.insert(position,selected[0])
        if repaired != definitions['actual'] or c.digest(repaired) != actual['objects'][key]:
            raise ValueError('Difference is not the five exact observed KEY-line moves')


def historical_engine(root,old):
    engine = p.engine_binding(root,old['source_sha256'])
    for name in ('partial_forward_recovery.py','owner_live_test_migration.py'):
        if c.core.file_hash(Path(root)/'scripts/multitenant'/name) != c.core.file_hash(Path(__file__).parent/name):
            raise ValueError('Historical partial-repair/evidence algorithms changed')
    return engine


def validate_contract(plan):
    old = plan['historical_plan']; qualified = plan['historical_qualification']; before,tail = p.sql_parts()
    if (plan.get('kind') != KIND or plan['source_sha256'] != c.sources() or plan.get('business_activation_ready') is not False or
            plan['target'] != old['target'] or plan['target']['database'] != 'exchange_demo' or plan.get('start') != 2 or
            old['migration'] != {'name':p.NAME,'sha256':p.SQL_SHA256} or old['kind'] != p.KIND or
            old['prefix_sha256'] != hashlib.sha256(before).hexdigest() or old['suffix_sha256'] != hashlib.sha256(tail).hexdigest() or
            old['operations_sha256'] != c.digest(p.operations(old['foreign_keys'])) or
            plan['suffix_sha256'] != old['suffix_sha256'] or plan['sql_mode'] != old['sql_mode'] or
            plan['timestamp'] != old['timestamp'] or type(plan['timestamp']) is not int or
            not 1700000000 <= plan['timestamp'] <= int(c.now().timestamp()) or not re.fullmatch('[A-Z0-9_,]*',plan['sql_mode'])):
        raise ValueError('Immutable suffix/source/history binding differs')
    if historical_engine(plan['engine']['frozen_root'],old) != plan['engine']: raise ValueError('Historical frozen engine changed')
    original_failure(old); p.validate_qualification(old,qualified); p.proof_files(old['preservation_proof'])
    tip = failed_repair_boundary(old,plan['failed_repair_journal'])
    if tip != plan['failed_repair_tip']: raise ValueError('Retained repair-failure tip changed')
    validate_diagnosis(old,qualified,plan['diagnosis'],tip)
    observation = plan['observation']; proof = plan['preservation_proof']; p.proof_files(proof)
    if (observation.get('kind') != 'EXACT_CURRENT_FAILED_AFTER_ID_REPAIR_PRESERVATION_ONLY_NOT_RECOVERY' or
            observation.get('source_sha256') != old['source_sha256'] or observation.get('target') != plan['target'] or
            observation.get('original_recovery_plan_sha256') != c.digest(old) or
            observation.get('failed_repair_ledger_tip') != tip or observation.get('diagnostic_sha256') != c.digest(plan['diagnosis']) or
            observation.get('start') != 2 or observation.get('migrations') != c.migrations() or
            observation.get('business_activation_ready') is not False or observation.get('initial') != plan['initial'] or
            plan['initial']['schema'] != plan['diagnosis']['actual_schema'] or plan['initial']['data'] != old['initial']['data'] or
            proof.get('plan_sha256') != c.digest(observation) or proof.get('source') != plan['target'] or
            proof.get('restored') != plan['initial'] or proof.get('ledger_tip') is not None or proof.get('next') != 2 or
            not p.independent(proof['restore'],plan['target'])):
        raise ValueError('Original exact current-state preservation proof cannot be relabelled or replaced')
    owner_path = Path(plan['owner_order'])
    if (c.core.file_hash(owner_path) != observation.get('owner_order_sha256') or
            c.read(owner_path).get('kind') != 'ACTUAL_HUMAN_CONTINUATION_ORDER_NOT_SIGNED_APPROVAL' or
            not c.read(owner_path).get('human_instruction_excerpt','').strip()):
        raise ValueError('Actual current owner continuation work order changed')


def runtime(db,plan):
    c.maintenance(db)
    if p.runtime_mode(db) != plan['sql_mode'] or db.query('SELECT @@global.event_scheduler') != ['OFF']:
        raise ValueError('Maintenance/original SQL mode/FK/event-writer boundary changed')


def prepare(db,old,qualified,failed_directory,diagnosis,observation,proof,protected_restore,frozen_root,owner_order,output):
    value = {'kind':KIND,'id':secrets.token_hex(16),'created_at':c.now().isoformat(),'source_sha256':c.sources(),
             'target':old['target'],'initial':observation['initial'],'start':2,'historical_plan':old,
             'historical_qualification':qualified,'failed_repair_journal':str(Path(failed_directory).resolve()),
             'failed_repair_tip':failed_repair_boundary(old,failed_directory),'diagnosis':diagnosis,
             'observation':observation,'preservation_proof':proof,'engine':historical_engine(frozen_root,old),
             'owner_order':str(Path(owner_order).resolve()),'suffix_sha256':old['suffix_sha256'],
             'sql_mode':old['sql_mode'],'timestamp':old['timestamp'],'business_activation_ready':False}
    validate_contract(value); runtime(db,value)
    if (c.target(db) != value['target'] or not protected_restore.test or c.target(protected_restore) != proof['restore'] or
            c.state(db) != value['initial'] or c.state(protected_restore) != value['initial'] or
            p.foreign_keys(db) != old['foreign_keys']):
        raise ValueError('Exact current repaired source/protected independent restore differs')
    c.verify_preserved(old['failed_plan'],c.preserved(db,old['failed_plan']['preservation_columns']),2)
    c.publish(output,value); return value


def execute(db,plan,directory,expected=None,policy=None):
    journal = Evidence(directory)
    if journal.rows(): raise ValueError('Suffix attempted before; uncertain committed DDL must not be replayed')
    if not db.test:
        if not isinstance(policy,OwnerLiveTestPolicy) or policy.db is not db or expected is None:
            raise ValueError('Explicit real owner authorization and current full-copy qualification required')
        policy.guard(); validate_qualification(plan,expected)
        if (policy.value.get('repaired_suffix_review') != review_binding(plan,expected) or
                policy.value.get('binding') != c.binding(plan,plan['preservation_proof'])):
            raise ValueError('Owner suffix-only review binding differs')
    validate_contract(plan); runtime(db,plan)
    if c.state(db) != plan['initial'] or p.foreign_keys(db) != plan['historical_plan']['foreign_keys']:
        raise ValueError('Exact repaired full-state boundary changed; no suffix permitted')
    target = c.target(db)
    if not db.test and target != plan['target']: raise ValueError('Actual source target differs')
    base = {'plan_sha256':c.digest(plan),'target':target}
    journal.append({**base,'kind':'SUFFIX_INTENT','before':plan['initial'],'sql_sha256':plan['suffix_sha256']})
    try:
        p.execute_sql(db,plan,p.sql_parts()[1].decode('utf-8'))
        original = plan['historical_plan']['failed_plan']
        c.verify_preserved(original,c.preserved(db,original['preservation_columns']),2)
        after = c.state(db)
        if (p.foreign_keys(db) != plan['historical_plan']['foreign_keys'] or
                db.query('SELECT version,business_activation_ready+0 FROM tenant_schema_version') != ['2026092902\t0'] or
                (expected is not None and after != expected['after'])):
            raise ValueError('Exact original suffix final full-field/schema boundary differs')
        journal.append({**base,'kind':'SUFFIX_COMPLETE','state':after,'next':3})
        return {'after':after,'target':target,'journal':str(journal.directory.resolve()),'tip':c.digest(journal.rows()[-1])}
    except BaseException as failure:
        journal.append({**base,'kind':'FAILED_UNCERTAIN','failure_type':type(failure).__name__})
        raise


def qualify(plan,protected_restore,candidate,directory,output,candidate_receipt=None):
    validate_contract(plan); proof = plan['preservation_proof']; other = c.target(candidate)
    if (not protected_restore.test or not candidate.test or c.target(protected_restore) != proof['restore'] or
            not p.independent(other,plan['target']) or other == proof['restore'] or
            c.state(protected_restore) != plan['initial']):
        raise ValueError('New isolated candidate and unchanged protected current-state restore required')
    if candidate_receipt is None:
        c.create_restore_database(protected_restore,candidate); candidate.restore_file(proof['restore_input']['path'])
    elif (candidate_receipt.get('kind') != 'PASS_REUSED_EXACT_CURRENT_REPAIRED_BACKUP_NEW_INDEPENDENT_QUALIFICATION_COPY' or
            candidate_receipt.get('backup') != proof['backup'] or candidate_receipt.get('restore_input') != proof['restore_input'] or
            candidate_receipt.get('target') != other or candidate_receipt.get('state') != plan['initial'] or
            candidate_receipt.get('original_proof_sha256') != c.digest(proof) or
            c.read(candidate_receipt['original_proof_path']) != proof or candidate_receipt.get('production_ddl_dml') != 0 or
            candidate_receipt.get('source_sha256') != plan['historical_plan']['source_sha256'] or
            candidate_receipt.get('original_restore_copy_unchanged') is not True):
        raise ValueError('Pre-restored current-state candidate receipt differs')
    result = execute(candidate,plan,directory)
    try: execute(candidate,plan,directory)
    except ValueError as error:
        if 'must not be replayed' not in str(error): raise
    else: raise ValueError('Completed suffix was replayable')
    value = {'kind':KIND+'_QUALIFICATION','result':'PASS','plan_sha256':c.digest(plan),'source_sha256':c.sources(),
             'restored_boundary':plan['initial'],'backup_sha256':proof['backup']['sha256'],
             'restore_input_sha256':proof['restore_input']['sha256'],'replay_refused':True,
             **result,'activation_ready':False}
    c.publish(output,value); return value


def validate_qualification(plan,qualified):
    proof = plan['preservation_proof']; rows = Evidence(qualified['journal']).rows()
    if (qualified.get('kind') != KIND+'_QUALIFICATION' or qualified.get('result') != 'PASS' or
            qualified.get('source_sha256') != plan['source_sha256'] or qualified.get('plan_sha256') != c.digest(plan) or
            qualified.get('restored_boundary') != plan['initial'] or qualified.get('backup_sha256') != proof['backup']['sha256'] or
            qualified.get('restore_input_sha256') != proof['restore_input']['sha256'] or qualified.get('replay_refused') is not True or
            qualified.get('activation_ready') is not False or not qualified['target']['physical'].get('test_instance') or
            not p.independent(qualified['target'],plan['target']) or qualified['target'] == proof['restore'] or
            len(rows) != 2 or rows[0].get('kind') != 'SUFFIX_INTENT' or rows[0].get('before') != plan['initial'] or
            rows[0].get('sql_sha256') != plan['suffix_sha256'] or rows[-1].get('kind') != 'SUFFIX_COMPLETE' or
            rows[-1].get('next') != 3 or rows[-1].get('state') != qualified['after'] or c.digest(rows[-1]) != qualified['tip'] or
            any(row.get('plan_sha256') != c.digest(plan) or row.get('target') != qualified['target'] for row in rows)):
        raise ValueError('Exact suffix-only independent full-state qualification differs')


def review_binding(plan,qualified):
    return {'decision':'EXACT_REPAIRED_BOUNDARY_ORIGINAL_SUFFIX_ONLY','plan_sha256':c.digest(plan),
            'qualification_sha256':c.digest(qualified),'failed_repair_tip':plan['failed_repair_tip'],
            'historical_plan_sha256':c.digest(plan['historical_plan'])}


def apply(db,plan,qualified,candidate,policy,directory,output):
    validate_contract(plan); validate_qualification(plan,qualified)
    if not isinstance(policy,OwnerLiveTestPolicy): raise ValueError('Actual current owner policy required')
    policy.guard()
    if (policy.db is not db or policy.restore is not candidate or c.target(candidate) != qualified['target'] or
            c.state(candidate) != qualified['after']):
        raise ValueError('Actual owner and qualified independent target/full-state differ')
    result = execute(db,plan,directory,qualified,policy)
    value = {'kind':KIND+'_ACTUAL_COMPLETE','result':'PASS','plan':plan,'qualification':qualified,
             'owner_authorization':str(policy.path),'owner_authorization_sha256':c.core.file_hash(policy.path),
             **result,'activation_ready':False}
    c.publish(output,value); return value


def validate_actual(actual):
    plan = actual['plan']; validate_contract(plan); validate_qualification(plan,actual['qualification'])
    rows = Evidence(actual['journal']).rows()
    if (actual.get('kind') != KIND+'_ACTUAL_COMPLETE' or actual.get('result') != 'PASS' or
            actual.get('activation_ready') is not False or actual['target'] != plan['target'] or
            actual['after'] != actual['qualification']['after'] or len(rows) != 2 or
            rows[0].get('kind') != 'SUFFIX_INTENT' or rows[0].get('before') != plan['initial'] or
            rows[0].get('sql_sha256') != plan['suffix_sha256'] or rows[-1].get('kind') != 'SUFFIX_COMPLETE' or
            rows[-1].get('next') != 3 or rows[-1].get('state') != actual['after'] or c.digest(rows[-1]) != actual['tip'] or
            any(row.get('plan_sha256') != c.digest(plan) or row.get('target') != plan['target'] for row in rows) or
            c.core.file_hash(Path(actual['owner_authorization'])) != actual['owner_authorization_sha256']):
        raise ValueError('Actual exact suffix completion/owner provenance changed')
    authorization = c.read(actual['owner_authorization'])
    if (authorization.get('binding') != c.binding(plan,plan['preservation_proof']) or
            authorization.get('repaired_suffix_review') != review_binding(plan,actual['qualification']) or
            authorization.get('source_sha256') != plan['source_sha256'] or authorization.get('source') != plan['target'] or
            authorization.get('restore') != actual['qualification']['target']):
        raise ValueError('Actual completed suffix owner authorization changed')


def continuation(actual,output_plan,output_proof):
    validate_actual(actual); plan = actual['plan']; original = plan['historical_plan']['failed_plan']
    proposal = {**original,'id':secrets.token_hex(16),'created_at':c.now().isoformat(),'source_sha256':c.sources(),
                'repaired_suffix_recovery':{'actual':actual},'business_activation_ready':False}
    proof = {**plan['preservation_proof'],'kind':KIND+'_TRANSFORMED_RESTORE_PROOF','plan_sha256':c.digest(proposal),
             'restore':actual['qualification']['target'],'restored':actual['after'],'ledger_tip':None,'next':3,
             'transformation_sha256':c.digest(actual),'created_at':c.now().isoformat()}
    c.publish(output_plan,proposal); c.publish(output_proof,proof); return proposal,proof


def validate_apply(proposal,proof,authorization,policy):
    actual = proposal['repaired_suffix_recovery']['actual']; validate_actual(actual)
    if not isinstance(policy,OwnerLiveTestPolicy): raise ValueError('Actual owner continuation policy required')
    policy.guard(); plan = actual['plan']; original = plan['historical_plan']['failed_plan']
    if (proposal.get('partial_recovery') or proposal.get('recovery') or
            authorization.get('repaired_suffix_review') != review_binding(plan,actual['qualification']) or
            any(proposal.get(key) != original.get(key) for key in original if key not in ('id','created_at','source_sha256'))):
        raise ValueError('Exact original preservation/start/history contract changed')
    if proof.get('kind') == KIND+'_TRANSFORMED_RESTORE_PROOF':
        if (proof.get('transformation_sha256') != c.digest(actual) or proof['backup'] != plan['preservation_proof']['backup'] or
                proof['restore_input'] != plan['preservation_proof']['restore_input'] or proof['restore'] != actual['qualification']['target'] or
                proof['restored'] != actual['after'] or proof['next'] != 3):
            raise ValueError('Explicit current-backup transformed suffix proof differs')
    elif not proof.get('ledger_tip'):
        raise ValueError('Initial suffix continuation requires explicit transformed proof')
