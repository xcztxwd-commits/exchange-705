"""Explicit owner-authorized live-test migration, not independent signed approval.

The default signed production route is unchanged. This exceptional route requires
the owner's exact no-real-users instruction, bound backup/target/source receipts,
actual maintenance and an unsigned append-only evidence chain. Only the explicitly
authorized current live-test release can activate its single new 0702 receipt.
"""
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tarfile
import controlled_migration as c

INSTRUCTION = '跳过批准环节，目前线上环节无真实用户，允许直接迁移'
KIND = 'OWNER_AUTHORIZED_LIVE_TEST_MIGRATION_NOT_SIGNED_APPROVAL'
DEPLOY_INSTRUCTION = '我授予你所有权限，我现在需要你将代码整理合并，解决冲突，合入主线，并且正常部署更新到线上，并且只处理有变化的数据库，不做全实例备份迁移'
LIVE_TEST_INSTRUCTION = '线上无真实用户，无真实资金，都为测试数据，但你也要尽量保证数据的完整'
ACTIVATION = 'OWNER_AUTHORIZED_LIVE_TEST_0702_ACTIVATION_NOT_SIGNED_APPROVAL'
BASELINE_SHA256 = '3d4fae2f121413c73ad809a0a23654db99121a961a67f1242c3b0ec0ddf1506b'
NATIVE_WITNESS = 'ACTUAL_1090_0404_AND_EXECUTED_0601_0603_TABLE_WITNESS'
NATIVE_FILES = {
    'frozen':'98ddf5f7eddce1fe70ace8eae83a7da89badfc962b51dcbe39aafc2c5980b42e',
    'original_backup':'fdb8f3f9ea57a4fad1bb0ece2f55949539f2030d082aabfe45ae1910ffad5e57',
    'tables_ddl':'94658c0a9b4d85e17cda408ed4f914cbcd9b20c2e37d19d88dcde28e38d5fae5',
    'full_restore_proof':'bac9baea68acc5d2236a8328880dbabef35cec78d20dd60624ad1495c52fcc48',
}
NATIVE_PHASES = ('885e7bb6a13ffa485e5230cf0fa2b26061d65437db7dee5ca652954d0ea47c3f',
                 'bfa8d95b582bc998b6969b9985f8d2ae5208b3e29ffaa506dcea44973338bc5a',
                 '943d9c852d4c775c4aae88434f86b145e9f89db69edba158ef8abb06d67ebbe8')
STORED_OBJECTS = ('database-defaults','trigger-definitions','routine-definitions','parameter-definitions','event-definitions')

def witness_file(value, expected):
    if not isinstance(value,dict) or set(value) != {'path','sha256'} or value['sha256'] != expected:
        raise ValueError('Exact known physical witness descriptor required')
    path = Path(value['path'])
    if path.is_symlink() or not path.is_file() or c.core.file_hash(path) != expected:
        raise ValueError('Known physical witness bytes changed or absent')
    return path

def same_physical(left, right):
    return (all(left.get(key) == right.get(key) for key in ('server_uuid','database','datadir','port','version'))
            and left.get('database') == '1090'
            and left.get('physical',{}).get('container_id') == right.get('physical',{}).get('container_id')
            and left.get('physical',{}).get('image_id') == right.get('physical',{}).get('image_id'))

def original_table_ddl(text, frozen):
    """Only authenticated 112 CREATE statements; each reconstructs the old raw SHOW hash."""
    expected = {key.removeprefix('table:'):value for key,value in frozen['state']['schema']['objects'].items() if key.startswith('table:')}
    if len(expected) != 112:raise ValueError('Exact original 112-table physical freeze required')
    blocks = {};end = 0
    pattern = re.compile(r'^CREATE TABLE `([A-Za-z0-9_]+)` \(\n.*?^\) ENGINE=[^\n]+;',re.M|re.S)
    for match in pattern.finditer(text):
        name = match[1]
        if text[end:match.start()].strip() or name in blocks:raise ValueError('Only unique original CREATE TABLE witnesses allowed')
        lines = match[0][:-1].splitlines();lines[0] = name+'\t'+lines[0]
        if name not in expected or c.digest(lines) != expected[name]:raise ValueError('Original raw table witness differs from frozen SHOW hash')
        blocks[name] = match[0];end = match.end()
    if text[end:].strip() or set(blocks) != set(expected):raise ValueError('Physical CREATE witness incomplete or contains extra SQL')
    return blocks

def validate_native_witness(db, value):
    if (not isinstance(value,dict) or set(value) != {'kind',*NATIVE_FILES,'phase_receipts'} or value['kind'] != NATIVE_WITNESS
            or len(value['phase_receipts']) != 3):
        raise ValueError('Explicit exact native physical witness contract required')
    paths = {key:witness_file(value[key],expected) for key,expected in NATIVE_FILES.items()}
    frozen = c.read(paths['frozen']);current = c.target(db)
    metadata = [json.loads(row) if isinstance(row,str) else row for row in frozen['metadata']]
    if (db.test or not same_physical(frozen['target'],current) or max(row['version'] for row in metadata) != 2026100404
            or any(row['ready'] != 1 for row in metadata)):
        raise ValueError('Witness belongs to another physical source or unready/non-0404 history')
    restored = c.read(paths['full_restore_proof'])
    if (restored.get('kind') != 'PASS_FRESH_FROZEN_FULL_RESTORE_ALL_FIELDS_AND_SCHEMA'
            or not same_physical(restored['source'],current)
            or restored['backup']['sha256'] != NATIVE_FILES['original_backup'] or restored['backup'].get('schema_only') is not False
            or restored.get('actual_exact_decimal_and_binary_fields_checked') is not True
            or restored.get('independent_signed_approval_claimed') is not False
            or restored['restore']['server_uuid'] == current['server_uuid'] or restored['restore']['datadir'] == current['datadir']
            or restored['restore']['physical'].get('test_instance') is not True):
        raise ValueError('Exact historical full independent restore proof required')
    reviewed = c.migrations()[-5:-2]
    if tuple(item['name'] for item in reviewed) != ('V2026100601__control_policy_definitions.sql','V2026100602__user_avatar.sql','V2026100603__tenant_entry_frontend_roles.sql'):
        raise ValueError('Exact unchanged native 0601/0602/0603 reviewed tails required')
    previous_at = dt.datetime.fromisoformat(restored['at'])
    for descriptor,sha,migration in zip(value['phase_receipts'],NATIVE_PHASES,reviewed):
        phase = c.read(witness_file(descriptor,sha));at = dt.datetime.fromisoformat(phase['at'])
        if (phase.get('kind') != 'EXACT_DDL_PHASE_COMPLETE' or phase.get('migration') != migration
                or not same_physical(phase['target'],current) or at.tzinfo is None or at <= previous_at):
            raise ValueError('Actual ordered same-source executed phase receipt differs')
        previous_at = at
    ddl = paths['tables_ddl']
    if ddl.stat().st_size > 4*1024*1024:raise ValueError('Bounded structure-only table witness required')
    original_table_ddl(ddl.read_text(encoding='utf-8'),frozen)
    return frozen,paths

def native_reference_definitions(db, reference, frozen):
    """Reference has tables, NOT stored objects. Those retain their historical exact hashes."""
    current = baseline_definitions(db);tables = baseline_definitions(reference)
    for key in STORED_OBJECTS:
        if current['objects'].get(key) != frozen['state']['schema']['objects'].get(key):
            raise ValueError('Original stored object/default metadata changed; cannot normalize it away')
    current_tables = {key:value for key,value in current['objects'].items() if key.startswith('table:') or key=='database-defaults'}
    reference_tables = {key:value for key,value in tables['objects'].items() if key.startswith('table:') or key=='database-defaults'}
    if len(current_tables) != 114 or current_tables != reference_tables:
        raise ValueError('All actual 113 tables/defaults must match original physical DDL plus reviewed tails')
    return current

def native_reference_rows(reference, facts):
    for table,value in facts['data']['tables'].items():
        expected = 3 if table=='tenant_schema_version' else 1 if table=='control_policy_definition' else 0
        if value['rows'] != expected:raise ValueError('Table-only reference contains unexpected rows')
    if reference.query('SELECT version,minimum_application_epoch,business_activation_ready+0 FROM tenant_schema_version ORDER BY version') != [f'{v}\t{v}\t0' for v in (2026100601,2026100602,2026100603)]:
        raise ValueError('Exact three inactive reference receipts required')
    if reference.query("SELECT COUNT(*) FROM control_policy_definition WHERE policy_key='feature.registration' AND default_value='false' AND version=0") != ['1']:
        raise ValueError('Exact original reviewed reference catalog seed required')

def current_instruction(value):
    receipt = Path(value['instruction_receipt'])
    if c.core.file_hash(receipt) != value['instruction_sha256']:
        raise ValueError('Exact current owner instruction receipt required')
    instruction = c.read(receipt)
    if instruction.get('format') == 2:
        if (instruction.get('human_instructions') != [DEPLOY_INSTRUCTION, LIVE_TEST_INSTRUCTION]
                or instruction.get('owner_asserts_no_real_users') is not True
                or instruction.get('owner_asserts_no_real_funds') is not True
                or value.get('owner_asserts_no_real_funds') is not True
                or value.get('authorized_databases') != [value['source']['database']]):
            raise ValueError('Exact current live-test facts and single changed database required')
        return 2
    if instruction.get('human_instruction') != INSTRUCTION:
        raise ValueError('Exact current owner instruction receipt required')
    return 1

class OwnerLiveTestPolicy:
    fixture = False
    owner_live_test = True

    def __init__(self, path, db, restore):
        self.path = Path(path).resolve()
        self.value = c.read(self.path)
        self.db, self.restore = db, restore
        self.guard()

    def guard(self):
        value = self.value
        if c.read(self.path) != value or any(k in value for k in ('signatures', 'keys', 'payload', 'journal_key')):
            raise ValueError('Owner instruction must not impersonate signed approval')
        if value.get('kind') != KIND or value.get('owner_asserts_no_real_users') is not True:
            raise ValueError('Explicit owner-authorized live-test scope required')
        current_instruction(value)
        expiry = dt.datetime.fromisoformat(value['expires_at'])
        if expiry.tzinfo is None or expiry <= c.now():
            raise ValueError('Owner live-test authorization expired')
        if self.db.test or not self.restore.test or c.target(self.db) != value['source'] or c.target(self.restore) != value['restore']:
            raise ValueError('Exact live-test source and isolated restore target required')
        if c.sources() != value.get('source_sha256') or c.migrations() != value.get('migrations'):
            raise ValueError('Owner-authorized source or reviewed SQL changed')
        if c.isolation_gate.check()[0]:
            raise ValueError('Reviewed source isolation checks remain mandatory')
        if not value.get('stopped_writers') or not value.get('recovery_owner'):
            raise ValueError('Actual writer inventory and recovery responsibility required')
        c.maintenance(self.db)

    def verify(self, authorization, binding, scope):
        self.guard()
        if authorization != self.value or scope != 'business' or binding != self.value.get('binding'):
            raise ValueError('Owner authorization differs from exact plan/backup/source/target binding')

class OwnerLiveTestLedger:
    def __init__(self, directory, policy):
        if not isinstance(policy, OwnerLiveTestPolicy):
            raise ValueError('Explicit owner live-test policy required')
        self.directory, self.policy = Path(directory), policy

    def rows(self):
        rows, previous = [], '0'*64
        for number, path in enumerate(sorted(self.directory.glob('*.json'))):
            if path.name != f'{number:06d}.json':
                raise ValueError('Owner evidence sequence incomplete')
            item = c.read(path); body = item['body']
            if (body.get('journal_kind') != KIND or body.get('sequence') != number
                    or body.get('previous') != previous or item.get('sha256') != c.digest(body)):
                raise ValueError('Owner unsigned evidence reordered or changed')
            previous = c.digest(item); rows.append(body)
        return rows

    def latest(self):
        rows = self.rows()
        if not rows: raise ValueError('No owner live-test migration evidence')
        return rows[-1]

    def append(self, body):
        if any(k in body for k in ('sequence', 'previous', 'at', 'journal_kind')):
            raise ValueError('Caller cannot replace evidence chain fields')
        rows = self.rows()
        previous = c.digest(c.read(self.directory/f'{len(rows)-1:06d}.json')) if rows else '0'*64
        value = {'sequence':len(rows), 'previous':previous, 'at':c.now().isoformat(), 'journal_kind':KIND, **body}
        c.publish(self.directory/f'{len(rows):06d}.json', {'body':value, 'sha256':c.digest(value)})

def container_artifact_hash(container_id, path):
    """Hash actual bytes even after the verified application is stopped for draining."""
    if not re.fullmatch(r'[0-9a-f]{64}', container_id) or not re.fullmatch(r'/[A-Za-z0-9_./-]+', path) or '..' in path.split('/'):
        raise ValueError('Exact application container and absolute artifact path required')
    process = subprocess.Popen(['docker','cp',container_id+':'+path,'-'], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    result = hashlib.sha256(); count = 0
    try:
        with tarfile.open(fileobj=process.stdout, mode='r|') as archive:
            for entry in archive:
                if not entry.isfile() or count or entry.name != Path(path).name:
                    raise ValueError('Only the exact regular packaged artifact may be hashed')
                count += 1
                with archive.extractfile(entry) as source:
                    for chunk in iter(lambda:source.read(1024*1024), b''):result.update(chunk)
        if process.wait() or count != 1:raise ValueError('Actual application artifact could not be verified')
    finally:
        process.stdout.close()
        if process.poll() is None:process.kill();process.wait()
    return result.hexdigest()

def inspect_application(container):
    values = json.loads(c.core.run(['docker','inspect',container]).stdout)
    if len(values) != 1:raise ValueError('Exactly one live-test application required')
    return values[0]

def baseline_definitions(db):
    """Compare all reviewed DDL; live counters remain exact in the full-state proof."""
    objects = dict(c.schema(db)['objects'])
    for table in db.tables():
        definition = '\n'.join(db.query('SHOW CREATE TABLE '+c.core.ident(table)))
        # Only MySQL's final table-option counter is variable. Never normalize row
        # values, defaults, triggers, functions, column names or their SQL modes.
        definition = re.sub(r'^(\) ENGINE=[A-Za-z0-9_]+) AUTO_INCREMENT=[0-9]+(?= |$)',r'\1',definition,flags=re.M)
        objects['table:'+table] = c.digest(definition)
    return {'objects':objects,'sha256':c.digest(objects)}

def observe_baseline(db, restore, reference, snapshot, output, ledger):
    """Freeze and fully restore one live-test DB; observe, never invent COMPLETE."""
    if not isinstance(ledger,OwnerLiveTestLedger) or not isinstance(ledger.policy,OwnerLiveTestPolicy):
        raise ValueError('Actual owner live-test policy/ledger required for baseline observation')
    policy = ledger.policy;policy.guard()
    if current_instruction(policy.value) != 2 or ledger.rows():
        raise ValueError('Current owner live-test instruction and new baseline evidence required')
    reviewed = c.migrations()
    native = policy.value.get('native_witness')
    if (c.core.EPOCH != 2026100702 or tuple(x['name'] for x in reviewed[-3:]) != ('V2026100603__tenant_entry_frontend_roles.sql',*c.CONTROL0702_TAIL)
            or (native is None and c.core.file_hash(Path(snapshot)) != BASELINE_SHA256)):
        raise ValueError('Exact immutable reviewed 0603 snapshot and two-phase tail required')
    frozen, native_paths = validate_native_witness(db,native) if native is not None else (None,None)
    source_target, restore_target = c.target(db),c.target(restore)
    reference_target = c.target(reference)
    expected_reference = dict(restore_target);expected_reference['database'] = restore.database+'_0603_reference'
    expected_reference['physical'] = dict(restore_target['physical'],database=expected_reference['database'])
    if (not restore.test or not reference.test or reference_target != expected_reference
            or restore_target['server_uuid'] == source_target['server_uuid'] or restore_target['datadir'] == source_target['datadir']):
        raise ValueError('New independent single-DB restore and exact 0603 reference namespace required')
    if db.query('SELECT MAX(version),MAX(minimum_application_epoch),MIN(business_activation_ready+0) FROM tenant_schema_version') != ['2026100603\t2026100603\t1']:
        raise ValueError('Actual already-active reviewed 0603 source metadata required')
    c.metadata_absent_before_plan(db,c.CONTROL0702_METADATA)
    columns = db.query("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND ((TABLE_NAME='market_control_command' AND COLUMN_NAME IN ('retry_count','retry_at')) OR (TABLE_NAME='market_control_flow' AND COLUMN_NAME IN ('history_pending_until','history_retry_at','history_error')))")
    if columns != ['0']:raise ValueError('Unknown or partially migrated control columns; no baseline inference')
    output = Path(output)
    if any(p.exists() for p in (output,output.with_suffix('.sql'),output.with_name(output.stem+'-restore-input.sql'))):
        raise ValueError('Baseline backup/evidence exists; never overwrite')
    c.maintenance(db);before = c.state(db)
    backup = db.dump(output.with_suffix('.sql'))
    restored_input = c.restore_input(db,backup,output.with_name(output.stem+'-restore-input.sql'))
    c.create_restore_database(db,restore);restore.restore_file(restored_input['path'])
    if c.state(restore) != before or c.state(db) != before:
        raise ValueError('Full single-DB restore/current source differs; no baseline accepted')
    c.create_restore_database(db,reference)
    if native is None:
        reference.restore_file(snapshot)
    else:
        # The authenticated extraction is CREATE-only and intentionally contains
        # no dump session headers; permit forward FK references in this one empty
        # isolated import session, never on the source or its full restore.
        reference.sql('SET FOREIGN_KEY_CHECKS=0;\n'+native_paths['tables_ddl'].read_text(encoding='utf-8')+'\nSET FOREIGN_KEY_CHECKS=1;')
        for path in c.core.MIGRATIONS[-5:-2]:reference.sql(path.read_text(encoding='utf-8'))
    reference_state = c.state(reference)
    if native is None:
        if any(value['rows'] for value in reference_state['data']['tables'].values()):
            raise ValueError('Reviewed baseline reference must contain structure only, not live data')
        definitions = baseline_definitions(db)
        if definitions != baseline_definitions(restore) or definitions != baseline_definitions(reference):
            raise ValueError('Actual original definitions differ from the reviewed immutable 0603 snapshot')
    else:
        native_reference_rows(reference,reference_state)
        definitions = native_reference_definitions(db,reference,frozen)
        if definitions != baseline_definitions(restore):raise ValueError('Fully restored current definitions differ')
    c.maintenance(db)
    if c.state(db) != before or c.state(restore) != before:raise ValueError('Source or restored facts changed during baseline review')
    value = {'kind':'OWNER_BASELINE_OBSERVED','target':source_target,'state':before,'source_sha256':c.sources(),
             'migrations':reviewed[:-2],'backup':backup,'restore_input':restored_input,'restore':restore_target,
             'reviewed_snapshot':{'path':str(snapshot),'sha256':BASELINE_SHA256} if native is None else None,'reference':reference_target,
             'reference_state':reference_state,'reviewed_definitions':definitions,
             'instruction_sha256':policy.value['instruction_sha256'],'independent_signed_approval_claimed':False,
             'past_migration_completion_invented':False,'counter_policy':'only final DDL AUTO_INCREMENT option ignored for structural reference; current/restored full counters remain exact'}
    if native is not None:
        value.update(native_witness=native,reference_kind='TABLES_ONLY_ORIGINAL_112_PLUS_EXACT_EXECUTED_0601_0603',
                     reference_contains_original_stored_objects=False,
                     original_stored_objects_historical_hashes={key:frozen['state']['schema']['objects'][key] for key in STORED_OBJECTS})
    c.publish(output,value);ledger.append(value);return value

def verify_baseline(db, ledger, previous):
    if not isinstance(ledger,OwnerLiveTestLedger) or not isinstance(ledger.policy,OwnerLiveTestPolicy):
        raise ValueError('Owner baseline flag cannot substitute for actual owner policy/ledger')
    policy = ledger.policy;policy.guard()
    if (current_instruction(policy.value) != 2 or len(ledger.rows()) != 1 or previous.get('kind') != 'OWNER_BASELINE_OBSERVED'
            or previous.get('source_sha256') != c.sources() or previous.get('instruction_sha256') != policy.value['instruction_sha256']
            or previous.get('target') != c.target(db) or previous.get('state') != c.state(db)
            or previous.get('migrations') != c.migrations()[:-2]
            or c.core.file_hash(Path(previous['backup']['path'])) != previous['backup']['sha256']
            or c.core.file_hash(Path(previous['restore_input']['path'])) != previous['restore_input']['sha256']
            or c.target(policy.restore) != previous.get('restore') or c.state(policy.restore) != previous['state']):
        raise ValueError('Frozen real owner baseline/source/backup/restore proof changed')
    reference = c.core.Database(policy.restore.container,previous['reference']['database'])
    if c.target(reference) != previous['reference'] or c.state(reference) != previous['reference_state']:
        raise ValueError('Actual reviewed baseline structure/reference changed')
    native = policy.value.get('native_witness')
    if native is None:
        if (previous.get('native_witness') is not None or previous.get('reviewed_snapshot',{}).get('sha256') != BASELINE_SHA256
                or c.core.file_hash(Path(previous['reviewed_snapshot']['path'])) != BASELINE_SHA256
                or baseline_definitions(reference) != previous['reviewed_definitions']
                or baseline_definitions(db) != previous['reviewed_definitions']):
            raise ValueError('Actual reviewed baseline structure/reference changed')
    else:
        if (previous.get('native_witness') != native or previous.get('reviewed_snapshot') is not None
                or previous.get('reference_kind') != 'TABLES_ONLY_ORIGINAL_112_PLUS_EXACT_EXECUTED_0601_0603'
                or previous.get('reference_contains_original_stored_objects') is not False):
            raise ValueError('Native table-only witness cannot impersonate a complete stored-object reference')
        frozen,_ = validate_native_witness(db,native);native_reference_rows(reference,previous['reference_state'])
        if (native_reference_definitions(db,reference,frozen) != previous['reviewed_definitions']
                or previous.get('original_stored_objects_historical_hashes') != {key:frozen['state']['schema']['objects'][key] for key in STORED_OBJECTS}):
            raise ValueError('Original native structural/historical witness changed')

def proof_from_baseline(db, proposal, restore, output, ledger):
    """Bind the already verified full single-DB restore to a later immutable plan."""
    if not isinstance(ledger,OwnerLiveTestLedger):raise ValueError('Actual owner baseline ledger required for backup reuse')
    previous = ledger.latest();verify_baseline(db,ledger,previous)
    if (proposal.get('target') != previous['target'] or proposal.get('initial') != previous['state']
            or proposal.get('source_sha256') != previous['source_sha256'] or proposal.get('migrations') != c.migrations()
            or proposal.get('start') != len(previous['migrations']) or proposal.get('schema_epoch') != 2026100702
            or proposal.get('allowed_metadata_append') != c.CONTROL0702_METADATA
            or c.target(restore) != previous['restore'] or c.state(restore) != previous['state']):
        raise ValueError('Frozen baseline restore/source/plan binding differs; no backup reuse')
    c.maintenance(db)
    if c.state(db) != previous['state']:raise ValueError('New writes after full restore; preserve increments and replan')
    value = {'format':1,'result':'PASS','plan_sha256':c.digest(proposal),'source':previous['target'],
             'backup':previous['backup'],'restore_input':previous['restore_input'],'restore':previous['restore'],
             'restored':previous['state'],'ledger_tip':None,'next':proposal['start'],'created_at':c.now().isoformat(),
             'reuse':'ACTUAL_OWNER_BASELINE_FULL_SINGLE_DB_RESTORE','owner_baseline_tip':c.digest(previous)}
    c.publish(output,value);return value

def record_startup(db, artifact, application, artifact_path, output):
    """Observe real healthy startup under read_only; then stop/drain before activation."""
    artifact = Path(artifact); output = Path(output)
    c.core.restrict_directory(output.parent)
    if db.test or c.core.EPOCH != 2026100702 or c.package_epoch(artifact) != 2026100702:
        raise ValueError('Only the exact current live-test 0702 application is supported')
    if c.isolation_gate.check()[0] or db.query('SELECT @@global.read_only') != ['1']:
        raise ValueError('Reviewed source and actual read_only startup required')
    actual = inspect_application(application); status = actual['State']
    if not status['Running'] or status.get('Health',{}).get('Status') != 'healthy':
        raise ValueError('Actual application startup must be healthy')
    artifact_sha256 = c.core.file_hash(artifact)
    if container_artifact_hash(actual['Id'], artifact_path) != artifact_sha256:
        raise ValueError('Running application bytes differ from the tested artifact')
    users = db.query('SELECT DISTINCT USER FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() ORDER BY USER')
    if not users or db.query('SELECT COUNT(*) FROM mysql.user WHERE Super_priv=\'Y\' AND User IN ('+','.join(c.core.literal(x) for x in users)+')') != ['0']:
        raise ValueError('Startup must expose actual non-SUPER application connections under read_only')
    observed = c.now().isoformat()
    logs = c.core.run(['docker','logs','--since',status['StartedAt'],'--until',observed,actual['Id']])
    raw = logs.stdout + logs.stderr
    if not re.search(rb'\bStarted [A-Za-z0-9_.$]+ in [0-9.]+', raw):
        raise ValueError('Current container startup completion is absent from actual logs')
    # Bounded interval allows exact re-verification without accepting a self-declared PASS.
    log_path = output.with_suffix('.startup.log')
    with log_path.open('xb') as out:out.write(raw);out.flush();c.os.fsync(out.fileno())
    value = {'kind':'ACTUAL_LIVE_TEST_STARTUP_UNDER_READ_ONLY','result':'PASS','source_sha256':c.sources(),
             'target':c.target(db),'application':{'container_id':actual['Id'],'image_id':actual['Image'],
             'started_at':status['StartedAt'],'artifact_path':artifact_path},'artifact_sha256':artifact_sha256,
             'artifact_epoch':2026100702,'observed_at':observed,'read_only':True,'actual_non_super_users':users,
             'logs':{'path':str(log_path),'sha256':c.core.file_hash(log_path)}}
    c.publish(output,value);return value

def verify_startup(db, artifact, startup):
    if (startup.get('kind') != 'ACTUAL_LIVE_TEST_STARTUP_UNDER_READ_ONLY' or startup.get('result') != 'PASS'
            or startup.get('source_sha256') != c.sources() or startup.get('target') != c.target(db)
            or startup.get('read_only') is not True or not startup.get('actual_non_super_users')
            or startup.get('artifact_epoch') != 2026100702 or c.package_epoch(artifact) != 2026100702
            or c.core.file_hash(Path(artifact)) != startup.get('artifact_sha256')):
        raise ValueError('Actual startup target/source/artifact facts changed')
    expiry = dt.datetime.fromisoformat(startup['observed_at'])
    if expiry.tzinfo is None or expiry > c.now() or c.now()-expiry > dt.timedelta(hours=2):
        raise ValueError('Fresh actual startup evidence required')
    application = startup['application']; actual = inspect_application(application['container_id'])
    if (actual['Id'] != application['container_id'] or actual['Image'] != application['image_id']
            or actual['State']['StartedAt'] != application['started_at']
            or container_artifact_hash(actual['Id'], application['artifact_path']) != startup['artifact_sha256']):
        raise ValueError('Application identity, startup or packaged bytes changed')
    logs = c.core.run(['docker','logs','--since',application['started_at'],'--until',startup['observed_at'],actual['Id']])
    raw = logs.stdout + logs.stderr
    if (hashlib.sha256(raw).hexdigest() != startup['logs']['sha256']
            or c.core.file_hash(Path(startup['logs']['path'])) != startup['logs']['sha256']
            or not re.search(rb'\bStarted [A-Za-z0-9_.$]+ in [0-9.]+',raw)):
        raise ValueError('Actual bounded startup logs changed')
    users = startup['actual_non_super_users']
    if db.query('SELECT COUNT(*) FROM mysql.user WHERE Super_priv=\'Y\' AND User IN ('+','.join(c.core.literal(x) for x in users)+')') != ['0']:
        raise ValueError('Observed application account can bypass read_only')

def activate(db, proposal, proof, restore, artifact, startup_path, ledger):
    """Activate exactly one new live-test receipt; never approve normal production."""
    if not isinstance(ledger,OwnerLiveTestLedger) or not isinstance(ledger.policy,OwnerLiveTestPolicy):
        raise ValueError('Explicit owner live-test activation policy and ledger required')
    policy = ledger.policy; policy.guard()
    if current_instruction(policy.value) != 2:
        raise ValueError('Current two-instruction live-test deployment authorization required')
    policy.verify(policy.value,c.binding(proposal,proof),'business')
    complete = ledger.latest(); activation = policy.value.get('activation',{})
    startup_path = Path(startup_path); startup = c.read(startup_path)
    expected = {'kind':ACTIVATION,'complete_tip':c.digest(complete),
                'startup_sha256':c.core.file_hash(startup_path),'artifact_sha256':c.core.file_hash(Path(artifact)),
                'schema_version':2026100702,'minimum_application_epoch':2026100603}
    if activation != expected:raise ValueError('Owner activation authorization is not bound to COMPLETE/startup/artifact')
    if (complete.get('kind') != 'COMPLETE' or complete.get('activation_ready') is not False
            or complete.get('binding') != c.binding(proposal,proof) or complete.get('plan_sha256') != c.digest(proposal)
            or complete.get('target') != c.target(db) or complete.get('migrations') != c.migrations()
            or complete.get('state') != c.state(db) or c.metadata_append_contract(proposal['start'],proposal['migrations']) != c.CONTROL0702_METADATA
            or proposal.get('allowed_metadata_append') != c.CONTROL0702_METADATA):
        raise ValueError('Exact complete current 0702 migration and unchanged full source required')
    if (proof.get('result') != 'PASS' or proof.get('plan_sha256') != c.digest(proposal)
            or proof.get('source') != c.target(db) or c.target(restore) != proof.get('restore')
            or c.state(restore) != proof.get('restored')
            or c.core.file_hash(Path(proof['backup']['path'])) != proof['backup']['sha256']
            or c.core.file_hash(Path(proof['restore_input']['path'])) != proof['restore_input']['sha256']):
        raise ValueError('Original full isolated restore/backup proof changed')
    if (not restore.test or c.target(restore)['server_uuid'] == c.target(db)['server_uuid']
            or c.target(restore)['datadir'] == c.target(db)['datadir']):
        raise ValueError('Actual independent isolated restore required for activation')
    verify_startup(db,artifact,startup);c.maintenance(db)
    c.metadata_receipt_after_phase(db,c.CONTROL0702_METADATA)
    if db.query('SELECT COUNT(*) FROM tenant_schema_version WHERE version<>2026100702 AND business_activation_ready<>1') != ['0']:
        raise ValueError('Activation cannot silently enable other existing schema receipts')
    fields = complete['state']['data']['columns']
    preserved = c.preserved(db,fields,c.CONTROL0702_METADATA)
    receipt_sql = 'SELECT version,HEX(CAST(applied_at AS BINARY)),minimum_application_epoch FROM tenant_schema_version WHERE version=2026100702'
    receipt = db.query(receipt_sql)
    ledger.append({'kind':'OWNER_LIVE_TEST_ACTIVATION_INTENT','target':c.target(db),'state':complete['state'],
                   'activation':expected,'binding':c.binding(proposal,proof),'preserved_sha256':c.digest(preserved),
                   'instruction_sha256':policy.value['instruction_sha256'],'independent_signed_approval_claimed':False})
    try:
        c.maintenance(db)
        result = db.sql('START TRANSACTION; UPDATE tenant_schema_version SET business_activation_ready=1 WHERE version=2026100702 AND minimum_application_epoch=2026100603 AND business_activation_ready=0 AND applied_at IS NOT NULL; SELECT ROW_COUNT(); COMMIT;')
        if result.stdout.decode().strip() != '1':raise ValueError('Exact single 0702 activation did not commit')
        if (c.schema(db) != complete['state']['schema'] or c.preserved(db,fields,c.CONTROL0702_METADATA) != preserved
                or db.query(receipt_sql) != receipt):
            raise ValueError('Activation changed original rows/definitions or new receipt facts')
        ready = c.core.guard(db,c.package_epoch(artifact),True);c.maintenance(db)
        value = {'kind':'OWNER_LIVE_TEST_ACTIVATION_COMPLETE','target':c.target(db),'state':c.state(db),
                 'activation':expected,'binding':c.binding(proposal,proof),'activation_ready':ready['activation_ready'],
                 'instruction_sha256':policy.value['instruction_sha256'],'independent_signed_approval_claimed':False,
                 'normal_production_release_approved':False,'writers_reenabled':False}
        ledger.append(value);return value
    except BaseException as error:
        ledger.append({'kind':'OWNER_LIVE_TEST_ACTIVATION_FAILED_UNCERTAIN','target':c.target(db),
                       'activation':expected,'failure_type':type(error).__name__,'writers_reenabled':False})
        raise
