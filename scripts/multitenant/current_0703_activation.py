"""Observe and activate an already-present 0703 receipt in an owner live test.

This does not replay migrations, certify an unobserved past migration, relax the
signed production policy, or normalize historical rows. Only the latest ready bit
may change after a fresh full independent restore and actual read-only startup.
"""
import datetime as dt
import argparse
import hashlib
import ipaddress
import json
from pathlib import Path, PurePosixPath
import re
import xml.etree.ElementTree as ET
import controlled_migration as c
import owner_live_test_migration as owner
import current_scope_audit

EPOCH = 2026100703
MINIMUM = 2026100603
KIND = 'CURRENT_0703_OWNER_LIVE_TEST_OBSERVED_NOT_SIGNED_APPROVAL'
INSTRUCTIONS = [
    '确定，修复完成关闭前置，然后确定没问题后，提交git和github，然后继续上线',
    '无真实用户的测试环境，不过不要去改动历史测试数据',
    '月线可以删，这个是例外',
]


def structure(db):
    """Validate the published 0703 objects; matching backup bytes alone is insufficient."""
    path = c.core.ROOT/'exchange-backend/src/main/resources/db/migration/V2026100703__history_source_restore.sql'
    if c.core.file_hash(path)!='7d68bdc120035c9e95dc8c40b97e054ebb4c09902e1ae28d1bbb255bd9d83dce':
        raise ValueError('Published reviewed 0703 SQL changed')
    specs = {
        'market_history_restore_job':[
            ('tenant_id','bigint(20)','NO',None),('id','varchar(36)','NO',None),('symbol_id','bigint(20)','NO',None),
            ('kind','varchar(16)','NO',None),('state','varchar(16)','NO',None),('request_key','varchar(64)','YES',None),
            ('source_identity','varchar(192)','NO',None),('from_at','bigint(20)','NO',None),('to_at','bigint(20)','NO',None),
            ('timezone','varchar(64)','NO',None),('total','int(11)','NO',None),('completed','int(11)','NO','0'),
            ('actor_id','bigint(20)','NO',None),('session_id','varchar(64)','YES',None),('created_at','bigint(20)','NO',None),
            ('expires_at','bigint(20)','NO',None),('updated_at','bigint(20)','YES',None),('undo_of','varchar(36)','YES',None),
            ('error_message','varchar(255)','YES',None)],
        'market_history_restore_minute':[
            ('tenant_id','bigint(20)','NO',None),('job_id','varchar(36)','NO',None),('symbol_id','bigint(20)','NO',None),
            ('minute_at','bigint(20)','NO',None),('before_json','mediumtext','NO',None),('source_json','mediumtext','YES',None),
            ('previous_json','mediumtext','YES',None),('checksum','char(64)','NO',None),('effective_version','bigint(20)','NO','0')],
    }
    facts = {}
    for table,expected in specs.items():
        rows = db.query("SELECT JSON_ARRAY(COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,CHARACTER_SET_NAME,COLLATION_NAME) "
                        "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME="+c.core.literal(table)+' ORDER BY ORDINAL_POSITION')
        actual = [json.loads(row) for row in rows]
        if [tuple(row[:4]) for row in actual]!=expected or any(row[4] or row[5:] not in ([None,None],['utf8mb4','utf8mb4_bin']) for row in actual):
            raise ValueError('Exact reviewed 0703 column contract differs: '+table)
        facts[table] = actual
    if db.query("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='market_engine_runtime' "
                "AND COLUMN_NAME='history_restore_revision' AND COLUMN_TYPE='bigint(20)' AND IS_NULLABLE='NO' AND COLUMN_DEFAULT='0'") != ['1']:
        raise ValueError('Reviewed runtime history revision column absent')
    indexes = db.query("SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS "
                       "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('market_history_restore_job','market_history_restore_minute') GROUP BY TABLE_NAME,INDEX_NAME,NON_UNIQUE ORDER BY TABLE_NAME,INDEX_NAME")
    expected_indexes = [
        'market_history_restore_job\tPRIMARY\t0\ttenant_id,id','market_history_restore_job\trestore_job_symbol\t0\ttenant_id,id,symbol_id',
        'market_history_restore_job\trestore_queue\t1\ttenant_id,state,created_at','market_history_restore_job\trestore_request\t0\ttenant_id,symbol_id,request_key',
        'market_history_restore_job\trestore_job_undo\t1\ttenant_id,undo_of',
        'market_history_restore_minute\tPRIMARY\t0\ttenant_id,job_id,minute_at','market_history_restore_minute\trestore_minute_job\t1\ttenant_id,job_id,symbol_id',
        'market_history_restore_minute\trestore_visible\t1\ttenant_id,symbol_id,minute_at,effective_version']
    # InnoDB creates the reviewed composite FK's required supporting indexes.
    if sorted(indexes)!=sorted(expected_indexes):raise ValueError('Reviewed 0703 indexes/FK support differs')
    relations = db.query("SELECT TABLE_NAME,CONSTRAINT_NAME,GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION),REFERENCED_TABLE_NAME,"
                         "GROUP_CONCAT(REFERENCED_COLUMN_NAME ORDER BY ORDINAL_POSITION) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() "
                         "AND TABLE_NAME IN ('market_history_restore_job','market_history_restore_minute') AND REFERENCED_TABLE_NAME IS NOT NULL "
                         'GROUP BY TABLE_NAME,CONSTRAINT_NAME,REFERENCED_TABLE_NAME ORDER BY TABLE_NAME,CONSTRAINT_NAME')
    if relations != [
        'market_history_restore_job\trestore_job_symbol\ttenant_id,symbol_id\ttrading_symbol\ttenant_id,id',
        'market_history_restore_job\trestore_job_tenant\ttenant_id\ttenant\tid',
        'market_history_restore_job\trestore_job_undo\ttenant_id,undo_of\tmarket_history_restore_job\ttenant_id,id',
        'market_history_restore_minute\trestore_minute_job\ttenant_id,job_id,symbol_id\tmarket_history_restore_job\ttenant_id,id,symbol_id']:
        raise ValueError('Reviewed 0703 tenant/composite foreign keys differ')
    if (db.query("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('market_history_restore_job','market_history_restore_minute') AND ENGINE='InnoDB' AND TABLE_COLLATION='utf8mb4_bin'")!=['2']
            or db.query("SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME IN ('market_history_restore_job','market_history_restore_minute') AND (UPDATE_RULE<>'RESTRICT' OR DELETE_RULE<>'RESTRICT' OR UNIQUE_CONSTRAINT_SCHEMA<>CONSTRAINT_SCHEMA)")!=['0']):
        raise ValueError('Reviewed 0703 table engine/collation/foreign-key rules differ')
    expected_triggers = {'history_restore_minute_i':('INSERT','cb43c29b07af783a1eaa78e66ff543c2327bace2cca8b4d1abc332921b0664fd'),
        'history_restore_minute_u':('UPDATE','dfd2fb68a95f35c496090c9a7e644076c143570b1f1f9d3a4b7d90486d789e01'),
        'history_restore_minute_d':('DELETE','7b0cb1567840656c3d5b4b19c8ee6389a1a4ad9bc564ff04ec881515a54ec630')}
    rows = db.query("SELECT JSON_OBJECT('name',TRIGGER_NAME,'table',EVENT_OBJECT_TABLE,'event',EVENT_MANIPULATION,'timing',ACTION_TIMING,"
                    "'order',ACTION_ORDER,'body',SHA2(ACTION_STATEMENT,256),'mode',SQL_MODE,'charset',CHARACTER_SET_CLIENT,'collation',COLLATION_CONNECTION,"
                    "'database_collation',DATABASE_COLLATION) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE='market_history_restore_minute' ORDER BY TRIGGER_NAME")
    triggers = [json.loads(row) for row in rows]
    if len(triggers)!=3 or {row['name'] for row in triggers}!=set(expected_triggers):raise ValueError('Exact three reviewed 0703 triggers required')
    for row in triggers:
        event,body = expected_triggers[row['name']]
        if (row['table']!='market_history_restore_minute' or row['event']!=event or row['timing']!='BEFORE' or row['order']!=1
                or row['body']!=body or row['charset']!='utf8mb4' or not row['collation'].startswith('utf8mb4_') or not row['mode']):
            raise ValueError('Exact reviewed 0703 trigger body/attributes differ')
    return {'columns':facts,'indexes':indexes,'relations':relations,'triggers':triggers}


def instructions(path):
    value = c.read(path)
    if (value.get('human_instructions') != INSTRUCTIONS or value.get('no_real_users') is not True
            or value.get('historical_data_policy') != 'PRESERVE_EXCEPT_EXACT_MARKET_MONTHLY'
            or not value.get('stopped_writers') or not value.get('recovery_owner')
            or not value.get('activation_output') or not value.get('observation_output')
            or any(key in value for key in ('signatures','keys','journal_key'))):
        raise ValueError('Exact current human live-test instructions required; no fabricated signed approval')
    expires = dt.datetime.fromisoformat(value['expires_at'])
    if expires.tzinfo is None or not c.now() < expires <= c.now()+dt.timedelta(hours=24):
        raise ValueError('Bounded current owner authorization required')
    return value


def backend_sources():
    files = {}
    for path in sorted((c.core.ROOT/'exchange-backend/src').rglob('*')):
        if path.is_file():files[path.relative_to(c.core.ROOT).as_posix()] = c.core.file_hash(path)
    files['exchange-backend/pom.xml'] = c.core.file_hash(c.core.ROOT/'exchange-backend/pom.xml')
    return c.digest(files)


def build_binding(instruction,artifact=None):
    build = instruction['tested_build']
    receipt = c.read(build['path'])
    if (c.core.file_hash(Path(build['path']))!=build['sha256'] or receipt.get('backend_source_sha256')!=backend_sources()
            or not receipt.get('raw_validation_inputs') or receipt.get('kind')!='TESTED_CURRENT_FULL_JAR_BINDING'
            or receipt.get('result')!='PASS_REQUIRED_PRE_DEPLOYMENT_VALIDATION' or receipt.get('blocked') is not False
            or not re.fullmatch('[0-9a-f]{64}',receipt.get('artifact_sha256',''))):
        raise ValueError('Tested full JAR/backend source/raw validation binding required')
    validations = receipt.get('required_validation',{})
    if set(validations)!={'backend_build','related_backend','physical_mysql','orm11'}:
        raise ValueError('Complete directly required same-version validation results required')
    raw = {}
    for value in receipt['raw_validation_inputs']:
        path = Path(value['path'])
        if path.is_symlink() or not path.is_file() or c.core.file_hash(path)!=value['sha256'] or str(path.resolve()) in raw:
            raise ValueError('Raw tested build evidence changed, duplicated or aliased')
        raw[str(path.resolve())] = value['sha256']
    def bound(reference, parse=True):
        path = Path(reference['path'])
        if path.is_symlink() or raw.get(str(path.resolve()))!=reference.get('sha256') or c.core.file_hash(path)!=reference['sha256']:
            raise ValueError('Each validation must bind its actual raw result')
        return c.read(path) if parse else path
    manifest = bound(receipt['sealed_source_manifest'])['files']
    selected = {name:sha for name,sha in manifest.items() if name.startswith('exchange-backend/src/') or name=='exchange-backend/pom.xml'}
    if c.digest(selected)!=backend_sources():raise ValueError('Tested snapshot backend differs from current source')
    artifact_manifest = bound(receipt['artifact_manifest'])
    if not any(row['sha256']==receipt['artifact_sha256'] and row['file'].endswith('.jar') for row in artifact_manifest['artifacts']):
        raise ValueError('Tested full JAR absent from actual artifact manifest')
    for name,value in validations.items():
        if value.get('result')!='PASS' or any(type(value.get(key)) is not int or value[key]!=0 for key in ('exit_code','failures','errors')):
            raise ValueError('A required validation failed, errored or did not execute successfully')
        evidence = value['raw']
        if name=='orm11':
            verified,probe,commands = (bound(evidence[key]) for key in ('verification','probe','commands'))
            if (verified.get('result')!='PASS_CURRENT_SEALED_JAR_ORIGINAL_11_ORM_GROUPS'
                    or any(verified.get(key)!=0 for key in ('verificationExitCode','originalCompileExitCode','originalDockerStartExitCode','originalJavaExitCode'))
                    or verified.get('jar',{}).get('sha256')!=receipt['artifact_sha256']
                    or verified.get('rawProbeReport',{}).get('sha256')!=evidence['probe']['sha256']
                    or verified.get('commands',{}).get('sha256')!=evidence['commands']['sha256']
                    or verified.get('currentSourceArtifactRuntimeMappingsExact') is not True
                    or probe.get('status')!='PASS' or len(probe.get('checks',[]))!=11
                    or any(row.get('status')!='PASS' or not row.get('assertions') for row in probe['checks'])
                    or probe.get('assertions')!=verified.get('assertions')
                    or len(probe.get('privateMappings',[]))!=verified.get('currentPrivateMappingCount')):
                raise ValueError('Actual original eleven ORM groups did not pass for this full JAR')
            executions = [row for row in commands if row.get('label')=='java8-full-11-groups']
            if len(executions)!=1 or executions[0].get('exitCode')!=0:
                raise ValueError('Actual original Java8 ORM command failed or missing')
            for key,hash_key in (('rawStdout','stdoutSha256'),('rawStderr','stderrSha256')):
                if verified.get(key,{}).get('sha256')!=executions[0].get(hash_key):raise ValueError('Original ORM output binding differs')
                bound(evidence[key],False)
                if evidence[key]['sha256']!=verified[key]['sha256']:raise ValueError('Original ORM output changed')
            continue
        command = bound(evidence['command'])
        expected = {'backend_build':'backend-build-quoted','related_backend':'backend-related','physical_mysql':'mysql-related'}[name]
        if command.get('label')!=expected or command.get('exitCode')!=0 or not command.get('command'):
            raise ValueError('Actual required validation command failed or missing')
        log = bound(evidence['log'],False)
        if log.name!=command.get('log','').replace('\\','/').rsplit('/',1)[-1]:raise ValueError('Actual command log binding differs')
        if name=='backend_build':
            output = log.read_text(encoding='utf-8',errors='replace')
            if command['command'][-1]!='package' or 'BUILD SUCCESS' not in output or 'BUILD FAILURE' in output:
                raise ValueError('Actual Maven package did not pass')
            continue
        suites = [ET.parse(bound(row,False)).getroot() for row in evidence['reports']]
        names = [suite.get('name') for suite in suites]
        tests = [test for suite in suites for test in suite.findall('testcase')]
        if (not tests or len(names)!=len(set(names)) or any(test.find('failure') is not None or test.find('error') is not None for test in tests)
                or all(test.find('skipped') is not None for test in tests)
                or any(int(suite.get('tests','-1'))!=len(suite.findall('testcase')) or int(suite.get('failures','-1'))!=0 or int(suite.get('errors','-1'))!=0 for suite in suites)):
            raise ValueError('Actual required JUnit suites failed, were partial or did not execute')
        if name=='physical_mysql':
            environment = bound(evidence['environment'])
            required = {'KlineGapBackfillMysqlTest','HistoryRestoreMysqlTest','LiveKlineMysqlTest'}
            if (environment.get('mysql')!='5.7.44' or environment.get('transactionIsolation')!='REPEATABLE-READ'
                    or environment.get('junitExitCode')!=0 or environment.get('onlyOwnedLocalResources') is not True
                    or not environment.get('serverUuid') or not required.issubset({n.rsplit('.',1)[-1] for n in names})):
                raise ValueError('Actual physical MySQL5.7/RR test results missing; H2 cannot substitute')
            if any(not any(test.find('skipped') is None for test in suite.findall('testcase'))
                   for suite in suites if suite.get('name').rsplit('.',1)[-1] in required):
                raise ValueError('Each required physical MySQL suite must actually execute')
        else:
            selectors = [argument[7:].split(',') for argument in command['command'] if argument.startswith('-Dtest=')]
            if len(selectors)!=1 or set(selectors[0])!={n.rsplit('.',1)[-1] for n in names}:
                raise ValueError('Required backend selection and actual JUnit suites differ')
    if artifact is not None and (c.core.file_hash(Path(artifact))!=receipt['artifact_sha256'] or c.package_epoch(artifact)!=EPOCH):
        raise ValueError('Actual full JAR differs from the tested current backend build')
    return build


def new_output(path,suffixes=()):
    """Use one canonical attempt identity, including its no-replay sidecars."""
    path = Path(path)
    if path.is_symlink():raise ValueError('Evidence output cannot be a symbolic link')
    path = path.resolve()
    for candidate in (path,*(path.with_suffix(suffix) for suffix in suffixes)):
        if candidate.exists() or candidate.is_symlink():raise ValueError('Existing evidence; never overwrite or replay')
    return path


def independent(source,other):
    def mounts(target):
        datadir = target['datadir'].rstrip('/')
        candidates = [m for m in target['physical']['mounts'] if datadir==m['Destination'].rstrip('/') or datadir.startswith(m['Destination'].rstrip('/')+'/')]
        if len(candidates)!=1 or not candidates[0].get('Source'):raise ValueError('Exact independent data mount required')
        mount=candidates[0]
        return PurePosixPath(mount['Source'])/PurePosixPath(datadir).relative_to(PurePosixPath(mount['Destination']))
    left,right=mounts(source),mounts(other)
    if (source['server_uuid']==other['server_uuid'] or source['datadir']==other['datadir']
            or source['physical']['container_id']==other['physical']['container_id']
            or left==right or left in right.parents or right in left.parents):
        raise ValueError('Independent server/UUID/datadir/data mount required')


def metadata(db, ready):
    if c.core.EPOCH != EPOCH or db.query('SELECT MAX(version) FROM tenant_schema_version') != [str(EPOCH)]:
        raise ValueError('Only the already-present latest reviewed 0703 receipt is supported')
    expected = ('SELECT COUNT(*) FROM tenant_schema_version WHERE version=2026100703 '
                'AND minimum_application_epoch=2026100603 AND business_activation_ready='+str(int(ready))+' AND applied_at IS NOT NULL')
    if (db.query(expected) != ['1'] or db.query('SELECT COUNT(*) FROM tenant_schema_version WHERE '
            'version<>2026100703 AND (business_activation_ready IS NULL OR business_activation_ready<>1)') != ['0']):
        raise ValueError('Exact latest receipt and all previous ready metadata required')
    if db.query("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='withdraw_record' "
                "AND COLUMN_NAME='account_type' AND DATA_TYPE='varchar' AND CHARACTER_MAXIMUM_LENGTH=20 AND IS_NULLABLE='NO' AND BINARY COLUMN_DEFAULT=BINARY 'FUND'") != ['1']:
        raise ValueError('Current immutable 0703_1 schema prerequisite is absent; no inferred migration or automatic DDL')


def protected_state(db):
    """Hash every field; only the latest ready bit is excluded from the comparison."""
    columns = {table:[row[0] for row in rows] for table,rows in db.columns().items()}
    queries = []
    for table,fields in columns.items():
        expressions = []
        for field in fields:
            expression = c.core.ident(field)
            if table == 'tenant_schema_version' and field == 'business_activation_ready':
                expression = 'CASE WHEN version=2026100703 THEN NULL ELSE '+expression+' END'
            expressions.append('HEX(CAST('+expression+' AS BINARY))')
        queries.append('SELECT '+c.core.literal(table)+',SHA2(JSON_ARRAY('+','.join(expressions)+'),256) row_sha256 '
                       'FROM '+c.core.ident(table)+' ORDER BY row_sha256;')
    return {'schema':c.schema(db),'data':{'columns':columns,'tables':c.core.row_fingerprints(db,queries,columns)}}


def observe(db, restore, authorization, output):
    """Fresh full backup and exact independent restore; historical completion is unknown."""
    authorization = Path(authorization); output = new_output(output,('.sql','.sql.tmp'))
    for path in (output,output.with_suffix('.sql'),output.with_suffix('.sql.tmp'),output.with_name(output.stem+'-restore-input.sql')):
        if path.exists() or path.is_symlink():raise ValueError('Observation/backup already exists; never overwrite')
    instruction = instructions(authorization);c.maintenance(db);metadata(db,False)
    if str(output.resolve())!=instruction['observation_output']:
        raise ValueError('Current authorization fixes one observation/backup identity')
    build_binding(instruction)
    source, other = c.target(db), c.target(restore)
    if (source['database']!='1090' or instruction.get('source')!=source or instruction.get('restore')!=other
            or instruction.get('source_sha256')!=c.sources() or instruction.get('migrations')!=c.migrations()):
        raise ValueError('Current owner authorization must bind exact physical/source/restore targets')
    if not restore.test:
        raise ValueError('Physically independent labelled MySQL5.7 restore required')
    independent(source,other)
    if c.isolation_gate.check()[0]:raise ValueError('Current reviewed source isolation remains mandatory')
    objects = structure(db)
    relations = current_scope_audit.audit(db,output.with_name(output.stem+'-relations'))
    if relations['result']!='PASS':raise ValueError('Current scoped orphan/manifest audit unresolved')
    before = c.state(db)
    backup = db.dump(output.with_suffix('.sql'))
    restored_input = c.restore_input(db,backup,output.with_name(output.stem+'-restore-input.sql'))
    c.create_restore_database(db,restore);restore.restore_file(restored_input['path'])
    restored = c.state(restore)
    c.maintenance(db)
    if restored != before or c.state(db) != before:raise ValueError('Exact full restored or current source bytes differ')
    value = {'kind':KIND,'result':'FULL_RESTORE_VERIFIED','target':source,'restore':other,'state':before,
             'reviewed_structure':objects,'current_relations':relations,
             'protected_state':protected_state(db),'source_sha256':c.sources(),'migrations':c.migrations(),
             'authorization':{'path':str(authorization),'sha256':c.core.file_hash(authorization)},
             'backup':backup,'restore_input':restored_input,'activation_output':instruction['activation_output'],
             'observed_at':c.now().isoformat(),
             'past_migration_completion_invented':False,'signed_production_approval_claimed':False}
    c.publish(output,value);return value


def application_networks(actual, stopped=False):
    """Bind real startup IPs to retained static configuration; Docker clears stopped IPAddress."""
    result = {}
    for name, network in actual['NetworkSettings']['Networks'].items():
        configured = (network.get('IPAMConfig') or {}).get('IPv4Address')
        address = network.get('IPAddress') or (configured if stopped else None)
        network_id = network.get('NetworkID', '')
        if not address or address != configured or not re.fullmatch('[0-9a-f]{64}', network_id):
            raise ValueError('Canary requires an explicit static IP equal to its actual startup address and network identity')
        ipaddress.IPv4Address(address)
        result[name] = {'network_id':network_id,'address':address}
    if not result:raise ValueError('Canary actual network identity is absent')
    return result


def record_startup(db, artifact, application, artifact_path, output, observation):
    """Observe the real current full JAR; no self-declared healthy/startup receipt."""
    artifact = Path(artifact);output = new_output(output,('.startup.log',))
    authorization = observation['authorization']
    if c.core.file_hash(Path(authorization['path']))!=authorization['sha256'] or observation['target']!=c.target(db):
        raise ValueError('Actual canary observation/owner authorization binding changed')
    build_binding(instructions(authorization['path']),artifact)
    if c.package_epoch(artifact) != EPOCH or db.query('SELECT @@global.read_only') != ['1']:
        raise ValueError('Current 0703 full JAR startup under real read_only required')
    metadata(db,False)
    actual = owner.inspect_application(application);status = actual['State']
    if not status['Running'] or status.get('Health',{}).get('Status') != 'healthy':
        raise ValueError('Actual read-only canary must be healthy')
    artifact_hash = c.core.file_hash(artifact)
    if owner.container_artifact_hash(actual['Id'],artifact_path) != artifact_hash:
        raise ValueError('Running full JAR differs from the tested artifact')
    networks = application_networks(actual)
    addresses = [value['address'] for value in networks.values()]
    rows = db.query('SELECT USER,SUBSTRING_INDEX(HOST,\':\',1),DB FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() '
                    'AND DB=DATABASE() AND SUBSTRING_INDEX(HOST,\':\',1) IN ('+','.join(c.core.literal(x) for x in addresses)+') ORDER BY USER,HOST')
    connections = [row.split('\t') for row in rows]
    users = sorted({row[0] for row in connections})
    if not users or db.query("SELECT COUNT(*) FROM mysql.user WHERE Super_priv='Y' AND User IN ("+','.join(c.core.literal(x) for x in users)+')') != ['0']:
        raise ValueError('Observed application connections must be non-SUPER')
    observed = c.now().isoformat()
    logs = c.core.run(['docker','logs','--since',status['StartedAt'],'--until',observed,actual['Id']])
    raw = logs.stdout+logs.stderr
    if not re.search(rb'\bStarted [A-Za-z0-9_.$]+ in [0-9.]+',raw):raise ValueError('Actual startup completion absent')
    log_path = output.with_suffix('.startup.log')
    with log_path.open('xb') as out:out.write(raw)
    value = {'kind':'CURRENT_0703_ACTUAL_READ_ONLY_STARTUP','target':c.target(db),'source_sha256':c.sources(),
             'artifact_sha256':artifact_hash,'observed_at':observed,'read_only':True,'actual_non_super_users':users,
             'actual_target_connections':connections,'observation_sha256':c.digest(observation),
             'application':{'container_id':actual['Id'],'image_id':actual['Image'],'started_at':status['StartedAt'],
                            'artifact_path':artifact_path,'networks':networks},
             'logs':{'path':str(log_path),'sha256':c.core.file_hash(log_path)}}
    c.publish(output,value);return value


def verify_startup(db,artifact,value):
    artifact = Path(artifact);application = value['application']
    observed = dt.datetime.fromisoformat(value['observed_at'])
    if (value.get('kind')!='CURRENT_0703_ACTUAL_READ_ONLY_STARTUP' or value.get('target')!=c.target(db)
            or value.get('source_sha256')!=c.sources() or c.package_epoch(artifact)!=EPOCH
            or value.get('artifact_sha256')!=c.core.file_hash(artifact) or value.get('read_only') is not True
            or not value.get('actual_non_super_users') or not value.get('actual_target_connections') or observed.tzinfo is None
            or not observed<=c.now()<observed+dt.timedelta(hours=2)):
        raise ValueError('Current target/source/artifact/fresh startup binding differs')
    actual = owner.inspect_application(application['container_id'])
    if (actual['Id']!=application['container_id'] or actual['Image']!=application['image_id']
            or actual['State']['StartedAt']!=application['started_at'] or actual['State']['Running']
            or owner.container_artifact_hash(actual['Id'],application['artifact_path'])!=value['artifact_sha256']):
        raise ValueError('Canary must be the same verified full JAR and stopped before activation')
    networks = application_networks(actual, stopped=True)
    addresses = [network['address'] for network in networks.values()]
    if networks != application.get('networks') or any(row[1] not in addresses or row[2]!=db.database for row in value['actual_target_connections']):
        raise ValueError('Actual stopped canary network or original target connections changed')
    logs = c.core.run(['docker','logs','--since',application['started_at'],'--until',value['observed_at'],actual['Id']])
    if (hashlib.sha256(logs.stdout+logs.stderr).hexdigest()!=value['logs']['sha256']
            or c.core.file_hash(Path(value['logs']['path']))!=value['logs']['sha256']):
        raise ValueError('Actual bounded startup logs differ')
    users = value['actual_non_super_users']
    if db.query("SELECT COUNT(*) FROM mysql.user WHERE Super_priv='Y' AND User IN ("+','.join(c.core.literal(x) for x in users)+')') != ['0']:
        raise ValueError('Observed account can bypass read_only')


def activate(db,restore,observation_path,artifact,startup_path,output):
    """One single CAS; an uncertain operation is recorded and never replayed."""
    output = new_output(output,('.intent.json','.failure.json'));startup_path = Path(startup_path)
    observation_path = Path(observation_path)
    observation = c.read(observation_path)
    if str(output.resolve())!=observation.get('activation_output'):
        raise ValueError('Activation attempt identity/output is fixed by the immutable observation')
    if output.exists() or output.with_suffix('.intent.json').exists() or output.with_suffix('.failure.json').exists():
        raise ValueError('Existing activation evidence; no automatic replay')
    authorization = observation['authorization']
    instruction = instructions(authorization['path'])
    if str(observation_path.resolve())!=instruction['observation_output']:
        raise ValueError('Activation requires the original authorized observation file')
    build_binding(instruction,artifact)
    if str(output.resolve())!=instruction.get('activation_output'):
        raise ValueError('Current human authorization fixes a single activation attempt path')
    if (c.core.file_hash(Path(authorization['path']))!=authorization['sha256'] or observation.get('kind')!=KIND
            or observation.get('result')!='FULL_RESTORE_VERIFIED' or observation.get('target')!=c.target(db)
            or observation.get('source_sha256')!=c.sources() or observation.get('migrations')!=c.migrations()
            or c.target(restore)!=observation.get('restore') or not restore.test
            or c.state(restore)!=observation['state'] or c.state(db)!=observation['state']):
        raise ValueError('Exact current observation/full restore/source binding differs')
    if (instruction.get('source')!=c.target(db) or instruction.get('restore')!=c.target(restore)
            or instruction.get('source_sha256')!=c.sources() or instruction.get('migrations')!=c.migrations()):
        raise ValueError('Current authorization target/source binding changed')
    independent(c.target(db),c.target(restore))
    for key in ('backup','restore_input'):
        if c.core.file_hash(Path(observation[key]['path']))!=observation[key]['sha256']:
            raise ValueError('Original full backup/restore input changed')
    if structure(db)!=observation.get('reviewed_structure') or observation.get('current_relations',{}).get('result')!='PASS':
        raise ValueError('Current reviewed 0703 structures/relations changed')
    if protected_state(db)!=observation['protected_state']:raise ValueError('Original fields changed')
    startup = c.read(startup_path)
    if startup.get('observation_sha256')!=c.digest(observation):raise ValueError('Startup belongs to another observation')
    verify_startup(db,artifact,startup);c.maintenance(db);metadata(db,False)
    intent = {'kind':'CURRENT_0703_ACTIVATION_INTENT','target':c.target(db),'observation_sha256':c.digest(observation),
              'startup_sha256':c.core.file_hash(startup_path),'artifact_sha256':c.core.file_hash(Path(artifact)),
              'authorization_sha256':authorization['sha256'],'observed_at':c.now().isoformat()}
    c.publish(output.with_suffix('.intent.json'),intent)
    try:
        c.maintenance(db)
        result = db.sql('START TRANSACTION; UPDATE tenant_schema_version SET business_activation_ready=1 '
                        'WHERE version=2026100703 AND minimum_application_epoch=2026100603 '
                        'AND business_activation_ready=0 AND applied_at IS NOT NULL; SELECT ROW_COUNT(); COMMIT;')
        if result.stdout.decode().strip()!='1':raise ValueError('Single latest 0703 activation did not commit')
        metadata(db,True);c.core.guard(db,c.package_epoch(artifact),True);c.maintenance(db)
        if protected_state(db)!=observation['protected_state']:raise ValueError('Historical fields or definitions changed')
        value = {'kind':'CURRENT_0703_ACTIVATION_COMPLETE','intent_sha256':c.digest(intent),'target':c.target(db),
                 'activation_ready':True,'historical_fields_preserved':True,'normal_production_release_approved':False,
                 'writers_reenabled':False,'observed_at':c.now().isoformat()}
        c.publish(output,value);return value
    except BaseException as error:
        c.publish(output.with_suffix('.failure.json'),{'kind':'CURRENT_0703_FAILED_UNCERTAIN_NO_REPLAY',
                  'intent_sha256':c.digest(intent),'error_type':type(error).__name__,'writers_reenabled':False})
        raise


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['observe','record-startup','activate'])
    parser.add_argument('--container',required=True);parser.add_argument('--database',required=True)
    parser.add_argument('--restore-container');parser.add_argument('--restore-database')
    parser.add_argument('--authorization',type=Path);parser.add_argument('--observation',type=Path)
    parser.add_argument('--artifact',type=Path);parser.add_argument('--application');parser.add_argument('--artifact-path')
    parser.add_argument('--startup',type=Path);parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args();db=c.core.Database(args.container,args.database)
    if args.action=='observe':
        if not all([args.restore_container,args.restore_database,args.authorization]):parser.error('observe requires restore target and authorization')
        value=observe(db,c.core.Database(args.restore_container,args.restore_database),args.authorization,args.output)
    elif args.action=='record-startup':
        if not all([args.artifact,args.application,args.artifact_path,args.observation]):parser.error('record-startup requires actual application/artifact/observation')
        value=record_startup(db,args.artifact,args.application,args.artifact_path,args.output,c.read(args.observation))
    else:
        if not all([args.restore_container,args.restore_database,args.observation,args.artifact,args.startup]):parser.error('activate requires original observation/restore/artifact/startup')
        value=activate(db,c.core.Database(args.restore_container,args.restore_database),args.observation,args.artifact,args.startup,args.output)
    print(json.dumps({'kind':value['kind'],'target':value['target']['database'],'normal_production_release_approved':False}))


if __name__=='__main__':main()
