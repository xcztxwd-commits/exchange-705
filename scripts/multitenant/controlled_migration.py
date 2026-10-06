"""Approval-bound forward-only schema migration. Never restores over a source database.

The older fixture/orphan/file tools retain their restrictions. This entry point only
handles the reviewed schema migration set; it does not approve cleanup or release.
"""
import argparse
import datetime as dt
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import sys
import zipfile
import mysql_migration as core
import isolation_gate

POLICY = core.ROOT/'deployment/multitenant/approval-policy.json'

def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode('utf-8')

def digest(value): return hashlib.sha256(canonical(value)).hexdigest()
def now(): return dt.datetime.now(dt.timezone.utc)
def read(path):
    path=Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size>16*1024*1024:raise ValueError('Regular bounded receipt required')
    return json.loads(path.read_text(encoding='utf-8'))

def publish(path,value):
    """Exclusive, durable publication: a previous plan/receipt is never overwritten."""
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    if path.exists() or path.is_symlink():raise ValueError('Evidence already exists; never overwrite')
    temp=path.with_name(path.name+'.'+secrets.token_hex(6)+'.tmp')
    with temp.open('xb') as out:out.write(canonical(value)+b'\n');out.flush();os.fsync(out.fileno())
    os.link(temp,path);temp.unlink()
    return core.file_hash(path)

def target(db):
    row=db.sql('SELECT @@server_uuid,@@datadir,@@port,VERSION()',database=False).stdout.decode('utf-8').strip().split('\t')
    if not row[3].startswith('5.7.'):raise ValueError('Reviewed migration requires MySQL 5.7')
    identity=dict(db.identity)
    if identity.get('adapter')!='this-run-native-only':
        item=json.loads(core.run(['docker','inspect',db.container]).stdout)[0]
        if item['Id']!=identity['container_id']:raise ValueError('Container identity changed')
        identity['image_id']=item['Image'];identity['mounts']=item.get('Mounts',[])
    return {'physical':identity,'server_uuid':row[0],'datadir':row[1],'port':int(row[2]),'version':row[3],'database':db.database}

def schema(db):
    # Object definitions may contain sensitive defaults or routines; retain hashes only.
    objects={}
    for table in db.tables():
        definition=db.query('SHOW CREATE TABLE '+core.ident(table))
        if not definition:raise ValueError('Missing SHOW CREATE TABLE metadata; restoration evidence is incomplete')
        objects['table:'+table]=digest(definition)
    objects['database-defaults']=digest(database_defaults(db))
    definitions={
        'trigger':('TRIGGERS','TRIGGER_SCHEMA',['TRIGGER_NAME','EVENT_MANIPULATION','EVENT_OBJECT_TABLE','ACTION_ORDER','ACTION_TIMING','ACTION_STATEMENT','SQL_MODE','DEFINER','CHARACTER_SET_CLIENT','COLLATION_CONNECTION','DATABASE_COLLATION']),
        'routine':('ROUTINES','ROUTINE_SCHEMA',['ROUTINE_NAME','ROUTINE_TYPE','DTD_IDENTIFIER','ROUTINE_DEFINITION','SQL_MODE','SECURITY_TYPE','SQL_DATA_ACCESS','IS_DETERMINISTIC','DEFINER','CHARACTER_SET_CLIENT','COLLATION_CONNECTION','DATABASE_COLLATION']),
        'parameter':('PARAMETERS','SPECIFIC_SCHEMA',['SPECIFIC_NAME','ORDINAL_POSITION','PARAMETER_MODE','PARAMETER_NAME','DTD_IDENTIFIER']),
        'event':('EVENTS','EVENT_SCHEMA',['EVENT_NAME','EVENT_DEFINITION','EVENT_TYPE','EXECUTE_AT','INTERVAL_VALUE','INTERVAL_FIELD','STARTS','ENDS','STATUS','ON_COMPLETION','DEFINER','SQL_MODE','TIME_ZONE'])
    }
    for kind,(table,scope,fields) in definitions.items():
        expression=','.join(core.literal(field)+','+core.ident(field) for field in fields)
        rows=db.query('SELECT JSON_OBJECT('+expression+') FROM information_schema.'+table+' WHERE '+scope+'=DATABASE()')
        # JSON escapes embedded newlines/tabs, unlike raw SHOW CREATE output splitting.
        normalized=[]
        for row in rows:
            value=json.loads(row)
            value={k:(v.replace('`'+db.database+'`.','`__SOURCE__`.') if isinstance(v,str) else v) for k,v in value.items()}
            normalized.append(value)
        objects[kind+'-definitions']=digest(sorted(normalized,key=lambda x:canonical(x)))
    return {'objects':objects,'sha256':digest(objects)}

def database_defaults(db):
    rows=db.query('SELECT DEFAULT_CHARACTER_SET_NAME,DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=DATABASE()')
    if len(rows)!=1:raise ValueError('Exact source database defaults required')
    values=rows[0].split('\t')
    if len(values)!=2 or any(not re.fullmatch('[A-Za-z0-9_]+',v) for v in values):raise ValueError('Invalid source database defaults')
    return {'character_set':values[0],'collation':values[1]}

def create_restore_database(source,restore):
    """Preserve the source defaults before restoring tables, routines and triggers."""
    if not restore.test:raise ValueError('Database creation/default adjustment is isolated-restore only')
    defaults=database_defaults(source)
    restore.create_empty()
    restore.sql('ALTER DATABASE '+core.ident(restore.database)+' CHARACTER SET '+defaults['character_set']+' COLLATE '+defaults['collation'],False)
    if database_defaults(restore)!=defaults:raise ValueError('Restored database defaults differ')

def all_fields(db): return core.fingerprint(db,{t:[f[0] for f in fs] for t,fs in db.columns().items()})
def state(db): return {'schema':schema(db),'data':all_fields(db)}
def migrations(): return [{'name':p.name,'sha256':core.file_hash(p)} for p in core.MIGRATIONS]

def sources():
    files={}
    for top in ['exchange-backend/src','scripts/multitenant']:
        for path in sorted((core.ROOT/top).rglob('*')):
            if path.is_file() and not path.is_symlink() and '__pycache__' not in path.parts and path.suffix not in ('.class','.pyc','.log'):
                files[path.relative_to(core.ROOT).as_posix()]=core.file_hash(path)
    for top in ['exchange-admin','exchange-frontend','exchange-pc']:
        for name in ['package.json','package-lock.json','pnpm-lock.yaml']:
            path=core.ROOT/top/name
            if path.is_file():files[path.relative_to(core.ROOT).as_posix()]=core.file_hash(path)
    return digest(files)

def maintenance(db):
    if db.query('SELECT @@global.read_only')!=['1']:raise ValueError('Actual MySQL read_only=1 required; a command-line stop flag is insufficient')
    if db.query('SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID()')!=['0']:
        raise ValueError('Target has active sessions; preserve maintenance and drain writers')

SOURCE0403_METADATA = {'table':'tenant_schema_version','version':2026100403,
                       'minimum_application_epoch':2026100403,'business_activation_ready':False}

HISTORY0404_METADATA = {'table':'tenant_schema_version','version':2026100404,
                        'minimum_application_epoch':2026100404,'business_activation_ready':False}

ENTRY0602_METADATA = {'table':'tenant_schema_version','version':2026100602,
                      'minimum_application_epoch':2026100602,'business_activation_ready':False}

def metadata_append_contract(start,reviewed):
    # Only these individually reviewed additive tails; never infer arbitrary future metadata.
    tails = {
        'V2026100403__source_history_input_revision.sql': SOURCE0403_METADATA,
        'V2026100404__history_ordering_and_response_receipts.sql': HISTORY0404_METADATA,
        'V2026100602__tenant_entry_frontend_roles.sql': ENTRY0602_METADATA,
    }
    if start>0 and len(reviewed[start:])==1:
        name=reviewed[start]['name']
        predecessor = {
            'V2026100404__history_ordering_and_response_receipts.sql': 'V2026100403__source_history_input_revision.sql',
            'V2026100602__tenant_entry_frontend_roles.sql': 'V2026100601__control_policy_definitions.sql',
        }
        if name in tails and (name not in predecessor or reviewed[start-1]['name']==predecessor[name]):
            return dict(tails[name])
    return None

def metadata_version(contract):
    if contract not in (SOURCE0403_METADATA,HISTORY0404_METADATA,ENTRY0602_METADATA):
        raise ValueError('Unreviewed metadata append contract')
    return contract['version']

def metadata_absent_before_plan(db,contract):
    if contract is not None:
        version=metadata_version(contract)
        if db.query(f'SELECT COUNT(*) FROM tenant_schema_version WHERE version={version}')!=['0']:
            raise ValueError(f'{version} metadata already exists; no additive plan/replay may infer a completed phase')

def metadata_receipt_after_phase(db,contract):
    if contract is not None:
        version=metadata_version(contract)
        if db.query(f'SELECT COUNT(*) FROM tenant_schema_version WHERE version={version} AND minimum_application_epoch={version} AND business_activation_ready=0 AND applied_at IS NOT NULL')!=['1']:
            raise ValueError(f'Exact newly approved {version} inactive metadata receipt is missing or changed')

def preserved(db,columns,metadata_append=None):
    version=metadata_version(metadata_append) if metadata_append is not None else None
    queries=[]
    for table,fields in columns.items():
        expr=[]
        for field in fields:
            value=core.ident(field)
            if metadata_append is None and field=='current_token':value='NULL' # Only the original reviewed migration transforms.
            if metadata_append is None and table=='deposit_record':
                if field=='source':value="COALESCE(source,'LEGACY_UNKNOWN')"
                if field=='order_no':value="COALESCE(order_no,CONCAT('LEGACY-DEP-',id))"
                if field=='account_type':value="COALESCE(account_type,'FUND')"
            expr.append('HEX(CAST('+value+' AS BINARY))')
        where=''
        if metadata_append is not None:
            if table=='tenant_schema_version':where=f' WHERE version<>{version}'
        # Every original metadata row remains hashed with all old columns. Only the single planned receipt is excluded.
        queries.append('SELECT '+core.literal(table)+',SHA2(JSON_ARRAY('+','.join(expr)+'),256) AS row_sha256 FROM '+core.ident(table)+where+' ORDER BY row_sha256;')
    return core.row_fingerprints(db,queries,columns)

HISTORY_REVISION_SEED = 'V2026100101__tenant_history_revision_and_legacy_markers.sql'

def history_revision_seed(db,columns):
    """Exact expected cache revision seed; never exclude this table from preservation."""
    fields=columns.get('asset_history_revision')
    if fields is None:return None
    if set(fields)!={'user_id','basis_version','level','revision'}:
        raise ValueError('Unreviewed original history revision columns')
    parents=' UNION '.join('SELECT user_id,basis_version,'+str(level)+' AS level FROM '+core.ident(table)+' GROUP BY user_id,basis_version'
                          for level,table in enumerate(('asset_history_1h','asset_history_4h','asset_history_1d'),1))
    match='p.user_id=r.user_id AND p.basis_version=r.basis_version AND p.level=r.level'
    rows=('SELECT r.user_id,r.basis_version,r.level,r.revision+IF(p.user_id IS NULL,0,1) AS revision '
          'FROM asset_history_revision r LEFT JOIN ('+parents+') p ON '+match+
          ' UNION ALL SELECT p.user_id,p.basis_version,p.level,1 FROM ('+parents+') p '
          'WHERE NOT EXISTS(SELECT 1 FROM asset_history_revision r WHERE '+match+')')
    expr='JSON_ARRAY('+','.join('HEX(CAST('+core.ident(f)+' AS BINARY))' for f in fields)+')'
    sql="SELECT 'asset_history_revision',SHA2("+expr+',256) AS row_sha256 FROM ('+rows+') expected ORDER BY row_sha256;'
    reference={'asset_history_revision':fields}
    return {'migration':HISTORY_REVISION_SEED,
            'before':preserved(db,reference)['asset_history_revision'],
            'after':core.row_fingerprints(db,[sql],reference)['asset_history_revision']}

def verify_preserved(proposal,actual,index):
    contract=proposal.get('history_revision_seed')
    actual=dict(actual)
    if contract and index>=next(i for i,m in enumerate(proposal['migrations']) if m['name']==HISTORY_REVISION_SEED):
        if actual.get('asset_history_revision')!=contract['after']:
            raise ValueError('Exact reviewed derived history revision seed differs')
        actual['asset_history_revision']=contract['before']
    if digest(actual)!=proposal['preserved_sha256']:
        raise ValueError('Original money/order/chat/actor/metadata facts changed; preserve maintenance and forward-repair only')

def plan(db,output,baseline=None):
    if isolation_gate.check()[0]:raise ValueError('Current source review gate failed')
    # Reject known-invalid legacy metadata before hashing every business row.
    # Keep the later preflight: a successful early check does not freeze live writers.
    if 'tenant_schema_version' not in db.tables() and not core.preflight(db)['passed']:
        raise ValueError('Current legacy inventory failed; no automatic orphan deletion or owner inference')
    current=state(db);start=0
    if 'tenant_schema_version' in db.tables():
        if baseline is None:raise ValueError('Already scoped/partly migrated target requires a verified completed ledger; never infer completed DDL from object names')
        previous=baseline.latest()
        local=getattr(baseline.policy,'local',False)
        if local:
            from local_test_migration import LocalPolicy,LocalLedger
            if not isinstance(baseline.policy,LocalPolicy) or not isinstance(baseline,LocalLedger):raise ValueError('Actual local policy and evidence ledger required')
            baseline.policy.guard_targets(db,baseline.policy.restore_db)
            if previous['kind']!='LOCAL_BASELINE_OBSERVED' or previous['source_sha256']!=sources():
                raise ValueError('Actual local imported baseline observation required; do not fabricate COMPLETE')
            if core.file_hash(Path(previous['imported_backup']['path']))!=previous['imported_backup']['sha256']:
                raise ValueError('Imported local baseline dump changed')
        if previous['kind']!=('LOCAL_BASELINE_OBSERVED' if local else 'COMPLETE') or previous['target']!=target(db) or previous['state']!=current:raise ValueError('Completed baseline ledger or current data differs')
        old=previous['migrations'];new=migrations()
        if new[:len(old)]!=old:raise ValueError('Previously applied migration checksums changed')
        start=len(old)
    else:
        checked=core.preflight(db)
        if not checked['passed']:raise ValueError('Current legacy inventory failed; no automatic orphan deletion or owner inference')
    columns=current['data']['columns']
    metadata_append=metadata_append_contract(start,migrations())
    if start>0 and metadata_append is None:raise ValueError('An existing scoped database requires an exact reviewed additive tail')
    metadata_absent_before_plan(db,metadata_append)
    value={'format':1,'id':secrets.token_hex(16),'created_at':now().isoformat(),'target':target(db),'initial':current,
           'source_sha256':sources(),'migrations':migrations(),'start':start,'schema_epoch':core.EPOCH,
           'legacy_column_reuse':[p.name for p in core.MIGRATIONS if start==0 and core.already_present_legacy_columns(db,p)],
           'preservation_columns':columns,'preserved_sha256':digest(preserved(db,columns,metadata_append)),
           'allowed_metadata_append':metadata_append,
           'allowed_transforms':['old current_token revocation','only NULL legacy deposit source/order_no/account_type deterministic markers'],
           'restore_policy':'new isolated physical instance only; never overwrite source or new increments','business_activation_ready':False}
    value['history_revision_seed']=history_revision_seed(db,columns) if start==0 else None
    if value['history_revision_seed']:
        if value['history_revision_seed']['before']!=current['data']['tables']['asset_history_revision']:
            raise ValueError('History revision facts changed during planning')
        value['allowed_transforms'].append('only exact V2026100101 derived cache revision +1 per existing parent group and revision=1 for missing groups')
    if metadata_append:value['allowed_transforms']=[f"only newly approved {metadata_append['version']} inactive schema-version receipt; all original column values unchanged"]
    if start>=len(value['migrations']):raise ValueError('No forward migration to apply')
    publish(output,value);return value


def restore_trigger_sql_modes(sql,modes):
    """MySQL 5.7.44 mysqldump strips NO_AUTO_CREATE_USER from stored trigger modes.

    Correct only its recognized trigger header, using the actual source metadata.
    Original dump/body/data stay untouched; unknown layouts or mode changes fail closed.
    """
    header=re.compile(r"(^/\*!50003 SET sql_mode\s*=\s*')([^']*)(' \*/ ;\nDELIMITER [^\n]+\n/\*!50003 CREATE\*/ /\*!50017 DEFINER=[^\n]*?\*/ /\*!50003 TRIGGER (`?[A-Za-z0-9_]+`?)(?=\s))",re.M)
    found=[]
    def correct(match):
        name=match[4].strip("`");found.append(name)
        if name not in modes:raise ValueError('Unknown trigger in restore input')
        actual=modes[name]
        if not re.fullmatch(r'[A-Z0-9_,]*',actual):raise ValueError('Unrecognized source trigger SQL mode')
        stripped=','.join(x for x in actual.split(',') if x!='NO_AUTO_CREATE_USER')
        if match[2] not in (actual,stripped):raise ValueError('Unexpected dump/source trigger SQL mode difference')
        return match[1]+actual+match[3]
    result=header.sub(correct,sql.replace('\r\n','\n'))
    headers=re.findall(r'/\*!50003 TRIGGER (`?[A-Za-z0-9_]+`?)(?=\s)',sql)
    headers=[x.strip('`') for x in headers]
    if headers!=found or len(found)!=len(set(found)) or set(found)!=set(modes):raise ValueError('Missing/duplicate trigger restore header')
    return result

def restore_input(db,backup,path):
    rows=db.query("SELECT JSON_OBJECT('name',TRIGGER_NAME,'mode',SQL_MODE) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()")
    modes={x['name']:x['mode'] for x in map(json.loads,rows)}
    rows=db.query("SELECT JSON_OBJECT('name',ROUTINE_NAME,'type',ROUTINE_TYPE,'mode',SQL_MODE) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE()")
    routines={x['type']+':'+x['name']:x['mode'] for x in map(json.loads,rows)}
    stream_restore_trigger_sql_modes(Path(backup['path']),path,modes,routines)
    return {'path':str(path),'sha256':core.file_hash(Path(path)),'source_trigger_modes_sha256':digest(modes),
            'source_routine_modes_sha256':digest(routines),'correction':'only recognized MySQL 5.7 trigger/routine SQL_MODE headers; original dump unmodified'}

def stream_restore_trigger_sql_modes(source,path,modes,routine_modes=None):
    """Keep large snapshot bodies out of memory; only recognized three-line headers change."""
    found=[];routine_found=[];routine_modes={} if routine_modes is None else routine_modes
    routine_header=re.compile(r'^CREATE DEFINER=`[^`]+`@`[^`]+` (PROCEDURE|FUNCTION) (`?[A-Za-z0-9_]+`?)(?=\s|\()',re.M)
    with Path(source).open(encoding='utf-8') as inp,Path(path).open('x',encoding='utf-8',newline='') as out:
        for line in inp:
            block=line
            if re.match(r'^/\*!50003 SET sql_mode\s*=\s*\x27',line):
                block+=inp.readline()+inp.readline()
                names=re.findall(r'/\*!50003 TRIGGER (`?[A-Za-z0-9_]+`?)(?=\s)',block)
                if names:
                    if len(names)!=1:raise ValueError('Duplicate trigger restore header')
                    name=names[0].strip('`')
                    if name not in modes or name in found:raise ValueError('Unknown/duplicate trigger in restore input')
                    block=restore_trigger_sql_modes(block,{name:modes[name]});found.append(name)
                routines=routine_header.findall(block)
                if routines:
                    if names or len(routines)!=1:raise ValueError('Ambiguous stored-object restore header')
                    kind,name=routines[0];key=kind+':'+name.strip('`')
                    if key not in routine_modes or key in routine_found:raise ValueError('Unknown/duplicate routine in restore input')
                    actual=routine_modes[key]
                    if not re.fullmatch(r'[A-Z0-9_,]*',actual):raise ValueError('Unrecognized source routine SQL mode')
                    header=re.match(r"(^/\*!50003 SET sql_mode\s*=\s*')([^']*)(' \*/ ;\nDELIMITER [^\n]+\n)",block)
                    stripped=','.join(x for x in actual.split(',') if x!='NO_AUTO_CREATE_USER')
                    if header is None or header[2] not in (actual,stripped):raise ValueError('Unexpected routine restore layout or SQL mode difference')
                    block=header[1]+actual+header[3]+block[header.end():];routine_found.append(key)
            elif re.search(r'/\*!50003 TRIGGER (`?[A-Za-z0-9_]+`?)(?=\s)',line) or routine_header.search(line):
                raise ValueError('Unrecognized stored-object restore layout')
            out.write(block)
        if set(found)!=set(modes):raise ValueError('Missing trigger restore header')
        if set(routine_found)!=set(routine_modes):raise ValueError('Missing routine restore header')
        out.flush();os.fsync(out.fileno())

def verify_backup(db,proposal,restore_db,output,ledger=None,local_policy=None):
    expected=proposal['initial'];tip=None;next_phase=proposal['start']
    if ledger is not None:
        if local_policy is not None:
            from local_test_migration import LocalLedger
            if not isinstance(ledger,LocalLedger) or ledger.policy is not local_policy:raise ValueError('Local backup requires its actual owner-bound evidence ledger')
        last=ledger.latest()
        imported=local_policy is not None and getattr(ledger.policy,'local',False) and len(ledger.rows())==1 and last['kind']=='LOCAL_BASELINE_OBSERVED'
        if imported:
            if ledger.policy is not local_policy or last['target']!=proposal['target'] or last['state']!=expected or last['migrations']!=proposal['migrations'][:proposal['start']] or last['source_sha256']!=proposal['source_sha256']:
                raise ValueError('Local initial proof differs from the actual baseline observation')
        else:
            if last['kind'] not in ('BEGIN','PHASE_COMPLETE','RESUME_VERIFIED') or last['plan_sha256']!=digest(proposal) or last['target']!=proposal['target']:raise ValueError('No certain approved phase boundary for newest backup')
            expected=last['state'];tip=digest(last);next_phase=last['next']
    if target(db)!=proposal['target'] or sources()!=proposal['source_sha256'] or state(db)!=expected:raise ValueError('Plan/source/target changed; replan before backup')
    maintenance(db);other=target(restore_db)
    if local_policy is not None:
        from local_test_migration import LocalPolicy
        if not isinstance(local_policy,LocalPolicy):raise ValueError('Explicit owner-authorized local policy required')
        local_policy.guard_targets(db,restore_db)
    elif not restore_db.test or other['server_uuid']==proposal['target']['server_uuid'] or other['datadir']==proposal['target']['datadir']:
        raise ValueError('Different labelled isolated MySQL process/datadir required')
    output=Path(output)
    if output.exists():raise ValueError('Restore receipt already exists')
    backup=db.dump(output.with_suffix('.sql'))
    restored_input=restore_input(db,backup,output.with_name(output.stem+'-restore-input.sql'))
    create_restore_database(db,restore_db);restore_db.restore_file(restored_input['path'])
    restored=state(restore_db)
    if restored!=expected or state(db)!=expected:raise ValueError('Full restore or newest source changed; first DDL prohibited')
    value={'format':1,'result':'PASS','plan_sha256':digest(proposal),'source':target(db),'backup':backup,'restore_input':restored_input,'restore':other,'restored':restored,'ledger_tip':tip,'next':next_phase,'created_at':now().isoformat()}
    publish(output,value);return value

class Policy:
    """Keys are preconfigured by operators; this tool never creates approvals or policy keys."""
    def __init__(self,path,fixture=False):
        self.path=Path(path).resolve();self.fixture=fixture;self.value=read(self.path)
        if fixture and self.value.get('scope')!='isolated-fixture':raise ValueError('Fixture-only approval policy required')
        if not fixture and self.path!=POLICY.resolve():raise ValueError('Fixed independently managed business approval policy required')
    def key(self,name):
        path=Path(self.value['keys'][name]['key_file'])
        if not path.is_absolute() or path.is_symlink():raise ValueError('Restricted absolute approval key reference required')
        key=path.read_bytes()
        if len(key)<32:raise ValueError('Approval key too short')
        return key
    def verify(self,approval,expected,scope):
        payload=approval['payload']
        if payload.get('binding')!=expected or payload.get('scope')!=scope:raise ValueError('Approval is for another plan/backup/target/source/scope')
        if dt.datetime.fromisoformat(payload['expires_at'])<=now():raise ValueError('Approval expired')
        if payload.get('stopped_writers') is None or not payload['stopped_writers'] or not payload.get('recovery_owner'):raise ValueError('Writer inventory and recovery responsibility missing')
        signers=set();roles=set()
        for signed in approval['signatures']:
            signer=signed['signer'];role=self.value['keys'][signer]['role']
            actual=hmac.new(self.key(signer),canonical(payload),hashlib.sha256).hexdigest()
            if not hmac.compare_digest(actual,signed['sha256']):raise ValueError('Approval signature invalid')
            signers.add(signer);roles.add(role)
        if len(signers)<2 or not {'operator','approver'}.issubset(roles):raise ValueError('Distinct trusted operator and approver signatures required')

class Ledger:
    def __init__(self,directory,policy):self.directory=Path(directory);self.policy=policy
    def rows(self):
        rows=[];previous='0'*64
        for i,path in enumerate(sorted(self.directory.glob('*.json'))):
            item=read(path);body=item['body'];signature=hmac.new(self.policy.key(self.policy.value['journal_key']),canonical(body),hashlib.sha256).hexdigest()
            if body['sequence']!=i or body['previous']!=previous or not hmac.compare_digest(signature,item['sha256']):raise ValueError('Ledger incomplete, reordered or tampered; retain maintenance')
            previous=digest(item);rows.append(body)
        return rows
    def latest(self):
        rows=self.rows()
        if not rows:raise ValueError('No migration ledger')
        return rows[-1]
    def append(self,body):
        rows=self.rows();previous=digest(read(self.directory/(f'{len(rows)-1:06d}.json'))) if rows else '0'*64
        body={'sequence':len(rows),'previous':previous,'at':now().isoformat(),**body}
        signature=hmac.new(self.policy.key(self.policy.value['journal_key']),canonical(body),hashlib.sha256).hexdigest()
        publish(self.directory/(f'{len(rows):06d}.json'),{'body':body,'sha256':signature})

def binding(proposal,proof):
    return {'plan_sha256':digest(proposal),'restore_proof_sha256':digest(proof),'backup_sha256':proof['backup']['sha256'],'target_sha256':digest(proposal['target']),'source_sha256':proposal['source_sha256']}

def apply(db,proposal,proof,restore_db,approval,ledger,resume=False,after_phase=None):
    if sources()!=proposal['source_sha256'] or migrations()!=proposal['migrations'] or target(db)!=proposal['target']:raise ValueError('Source/DDL/physical target differs from the immutable plan')
    local=getattr(ledger.policy,'local',False)
    owner_live_test=getattr(ledger.policy,'owner_live_test',False)
    if owner_live_test:
        from owner_live_test_migration import OwnerLiveTestPolicy,OwnerLiveTestLedger
        if not isinstance(ledger.policy,OwnerLiveTestPolicy) or not isinstance(ledger,OwnerLiveTestLedger):
            raise ValueError('Owner live-test policy and ledger cannot be guessed')
        ledger.policy.guard()
        if local or proposal.get('recovery'):
            raise ValueError('Owner live-test mode cannot infer local mode or uncertain DDL recovery')
    if local:
        from local_test_migration import LocalPolicy,LocalLedger
        if not isinstance(ledger.policy,LocalPolicy) or not isinstance(ledger,LocalLedger):raise ValueError('Local policy/ledger cannot be guessed')
        ledger.policy.guard_targets(db,restore_db)
        if proposal.get('recovery'):raise ValueError('Local tests preserve uncertain DDL; no signed recovery substitution')
    scope='isolated-fixture' if db.test else 'business'
    if ledger.policy.fixture and not db.test:raise ValueError('Fixture approval cannot authorize a business target')
    if not db.test and not owner_live_test:
        if isolation_gate.check(release=True)[0]:raise ValueError('Production acceptance/release blockers remain; no migration')
        acceptance=read(approval['payload']['acceptance_file'])
        if acceptance.get('result')!='PASS' or acceptance.get('source_sha256')!=proposal['source_sha256'] or acceptance.get('required_failures')!=0 or acceptance.get('blocked')!=[]:
            raise ValueError('Complete current-version acceptance is absent')
        if core.file_hash(Path(approval['payload']['acceptance_file']))!=approval['payload'].get('acceptance_sha256'):raise ValueError('Acceptance hash differs from approval')
    metadata_append=metadata_append_contract(proposal['start'],proposal['migrations'])
    if proposal.get('allowed_metadata_append')!=metadata_append:
        raise ValueError('Approved plan lacks the exact additive0403 metadata preservation contract; replan/reapprove')
    if metadata_append and 'tenant_schema_version' not in proposal['preservation_columns']:
        raise ValueError('Original schema-version rows are not frozen in the approved plan')
    ledger.policy.verify(approval,binding(proposal,proof),scope)
    if proposal.get('recovery'):
        from forward_recovery import validate_apply
        validate_apply(proposal,approval,ledger.policy)
    if proposal.get('partial_recovery'):
        from partial_forward_recovery import validate_apply
        validate_apply(proposal,proof,approval,ledger.policy)
    if proposal.get('repaired_suffix_recovery'):
        from repaired_suffix_recovery import validate_apply
        validate_apply(proposal,proof,approval,ledger.policy)
    if proof['result']!='PASS' or proof['plan_sha256']!=digest(proposal) or proof['source']!=proposal['target'] or core.file_hash(Path(proof['backup']['path']))!=proof['backup']['sha256']:
        raise ValueError('Full backup proof/hash is not bound to this plan')
    if core.file_hash(Path(proof['restore_input']['path']))!=proof['restore_input']['sha256']:raise ValueError('Bound restore input changed')
    other=target(restore_db)
    if other!=proof['restore'] or (not local and (other['server_uuid']==proposal['target']['server_uuid'] or other['datadir']==proposal['target']['datadir'])) or state(restore_db)!=proof['restored']:
        raise ValueError('Independent restore evidence no longer matches')
    if not resume and proposal.get('history_revision_seed')!=(history_revision_seed(restore_db,proposal['preservation_columns']) if proposal['start']==0 else None):
        raise ValueError('History revision seed contract differs from independently restored original facts')
    legacy_reuse=proposal.get('legacy_column_reuse',[])
    if not isinstance(legacy_reuse,list) or len(legacy_reuse)!=len(set(legacy_reuse)) or not set(legacy_reuse).issubset(core.LEGACY_COLUMN_MIGRATIONS) or (proposal['start']>0 and legacy_reuse):
        raise ValueError('Unreviewed legacy-column reuse contract')
    maintenance(db);rows=ledger.rows()
    if resume:
        if not rows or rows[-1]['kind'] not in ('BEGIN','PHASE_COMPLETE','RESUME_VERIFIED'):raise ValueError('Uncertain/incomplete/failed/complete DDL cannot be resumed or blindly replayed')
        last=rows[-1]
        tip_matches=proof.get('ledger_tip')==digest(last) or (last['kind']=='RESUME_VERIFIED' and last['binding']==binding(proposal,proof) and proof.get('ledger_tip')==last['previous_tip'])
        if last['plan_sha256']!=digest(proposal) or last['target']!=target(db) or state(db)!=last['state'] or proof['restored']!=last['state'] or proof.get('next')!=last['next'] or not tip_matches:raise ValueError('Receipt/plan/latest source differs; preserve all increments and use a new reviewed forward plan')
        start=last['next']
        verify_preserved(proposal,preserved(restore_db,proposal['preservation_columns'],metadata_append),start-1)
        ledger.append({'kind':'RESUME_VERIFIED','plan_sha256':digest(proposal),'target':target(db),'state':last['state'],'next':start,'binding':binding(proposal,proof),'previous_tip':proof['ledger_tip']})
    else:
        observed=local and len(rows)==1 and rows[0]['kind']=='LOCAL_BASELINE_OBSERVED' and rows[0]['target']==proposal['target'] and rows[0]['state']==proposal['initial'] and rows[0]['migrations']==proposal['migrations'][:proposal['start']] and rows[0]['source_sha256']==proposal['source_sha256']
        initial=proposal['partial_recovery']['actual']['after'] if proposal.get('partial_recovery') else proposal['initial']
        if proposal.get('repaired_suffix_recovery'):initial=proposal['repaired_suffix_recovery']['actual']['after']
        if (rows and not observed) or state(db)!=initial or proof['restored']!=initial or proof.get('ledger_tip') is not None:raise ValueError('Source changed or receipt exists; never replay apply')
        start=3 if proposal.get('partial_recovery') or proposal.get('repaired_suffix_recovery') else proposal['start']
        ledger.append({'kind':'BEGIN','plan_sha256':digest(proposal),'target':target(db),'state':initial,'next':start,'binding':binding(proposal,proof)})
    for index in range(start,len(core.MIGRATIONS)):
        maintenance(db)
        expected=ledger.latest()['state']
        if state(db)!=expected:raise ValueError('New writes detected; no restore/replay or further DDL')
        ledger.append({'kind':'INTENT','plan_sha256':digest(proposal),'target':target(db),'migration':proposal['migrations'][index],'index':index,'before':expected})
        try:
            core_path=core.MIGRATIONS[index]
            execution=execute_phase(db,core_path,legacy_reuse)
            verify_preserved(proposal,preserved(db,proposal['preservation_columns'],metadata_append),index)
            metadata_receipt_after_phase(db,metadata_append)
            ledger.append({'kind':'PHASE_COMPLETE','plan_sha256':digest(proposal),'target':target(db),'state':state(db),'next':index+1,'binding':binding(proposal,proof),'migration':proposal['migrations'][index],'execution':execution})
        except BaseException as error:
            ledger.append({'kind':'FAILED_UNCERTAIN','plan_sha256':digest(proposal),'target':target(db),'index':index,'failure_type':type(error).__name__})
            raise
        if after_phase:after_phase(index+1) # Test harness or controlled boundary interruption; never a DDL retry.
    core.guard(db,proposal['schema_epoch'])
    result={'kind':'COMPLETE','plan_sha256':digest(proposal),'target':target(db),'state':state(db),'migrations':proposal['migrations'],'activation_ready':False,'binding':binding(proposal,proof)}
    ledger.append(result);return result

def execute_phase(db,path,legacy_reuse):
    present=core.already_present_legacy_columns(db,path)
    if present!=(path.name in legacy_reuse):
        raise ValueError('Legacy column state differs from bound plan; no inferred DDL completion or replay')
    if present:return 'REUSED_EXACT_REVIEWED_LEGACY_COLUMNS_NO_DDL'
    db.sql(path.read_text(encoding='utf-8'))
    return 'EXECUTED_REVIEWED_DDL'

def package_epoch(path):
    with zipfile.ZipFile(path) as jar:
        candidates=[n for n in jar.namelist() if n in ('META-INF/mt705-schema-epoch','BOOT-INF/classes/META-INF/mt705-schema-epoch')]
        if not candidates:return 0
        if len(candidates)!=1 or jar.getinfo(candidates[0]).file_size>32:raise ValueError('Ambiguous/bounded package epoch required')
        value=jar.read(candidates[0]).decode('ascii').strip()
        if not re.fullmatch(r'20[0-9]{8}',value):raise ValueError('Invalid packaged schema epoch')
        return int(value)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['plan','verify-backup','apply','resume','package-check'])
    parser.add_argument('--container',required=True);parser.add_argument('--database',required=True)
    parser.add_argument('--plan',type=Path);parser.add_argument('--proof',type=Path);parser.add_argument('--output',type=Path)
    parser.add_argument('--restore-container');parser.add_argument('--restore-database');parser.add_argument('--approval',type=Path);parser.add_argument('--ledger',type=Path)
    parser.add_argument('--fixture-policy',type=Path,help='Only on an actually labelled isolated target; never a production bypass')
    parser.add_argument('--artifact',type=Path)
    parser.add_argument('--local-owner-authorization',type=Path,help='Explicit local disposable-test owner authorization; never business approval')
    parser.add_argument('--owner-live-test-authorization',type=Path,help='Explicit current owner no-real-users instruction; not independent signed production approval; activation remains off')
    args=parser.parse_args();db=core.Database(args.container,args.database)
    local_policy=None;owner_policy=None
    if args.owner_live_test_authorization:
        if args.local_owner_authorization or args.fixture_policy or args.approval or not args.restore_container or not args.restore_database or args.action not in ('apply','resume'):
            raise ValueError('Owner live-test apply/resume is exclusive of fixture/local/signed approval')
        from owner_live_test_migration import OwnerLiveTestPolicy,OwnerLiveTestLedger
        owner_policy=OwnerLiveTestPolicy(args.owner_live_test_authorization,db,core.Database(args.restore_container,args.restore_database))
    if args.local_owner_authorization:
        if args.fixture_policy or args.approval or not args.restore_container or not args.restore_database:
            raise ValueError('Local owner mode is exclusive of signed approval and requires an exact restore target')
        from local_test_migration import LocalPolicy,LocalLedger
        local_policy=LocalPolicy(args.local_owner_authorization,db,core.Database(args.restore_container,args.restore_database))
    if args.action=='package-check':
        if args.artifact is None:raise ValueError('Actual artifact required; no caller-supplied epoch override')
        result=core.guard(db,package_epoch(args.artifact),not db.test);result['artifact_sha256']=core.file_hash(args.artifact);print(json.dumps(result));return
    if args.action=='plan':
        if args.output is None:raise ValueError('--output required')
        baseline=None
        if args.ledger:
            if args.fixture_policy and not db.test:raise ValueError('Fixture policy cannot authorize business target')
            baseline=LocalLedger(args.ledger,local_policy) if local_policy else Ledger(args.ledger,Policy(args.fixture_policy or POLICY,bool(args.fixture_policy)))
        result=plan(db,args.output,baseline);print(json.dumps({'plan_sha256':digest(result),'target_sha256':digest(result['target']),'first_phase':result['start']}));return
    if not args.plan:raise ValueError('Immutable --plan required')
    proposal=read(args.plan)
    if not args.restore_container or not args.restore_database:raise ValueError('Exact independent restore target required')
    restore=core.Database(args.restore_container,args.restore_database)
    if args.action=='verify-backup':
        if not args.output:raise ValueError('--output required')
        baseline=None
        if args.ledger:
            if args.fixture_policy and not db.test:raise ValueError('Fixture policy cannot authorize business target')
            baseline=LocalLedger(args.ledger,local_policy) if local_policy else Ledger(args.ledger,Policy(args.fixture_policy or POLICY,bool(args.fixture_policy)))
        result=verify_backup(db,proposal,restore,args.output,baseline,local_policy=local_policy);print(json.dumps({'result':result['result'],'backup_sha256':result['backup']['sha256'],'restore_proof_sha256':digest(result)}));return
    if not args.proof or (not args.approval and not local_policy and not owner_policy) or not args.ledger:raise ValueError('Bound proof, authorization and ledger required')
    if args.fixture_policy and not db.test:raise ValueError('Fixture policy cannot authorize business target')
    policy=local_policy or owner_policy or Policy(args.fixture_policy or POLICY,bool(args.fixture_policy))
    evidence=LocalLedger(args.ledger,policy) if local_policy else OwnerLiveTestLedger(args.ledger,policy) if owner_policy else Ledger(args.ledger,policy)
    result=apply(db,proposal,read(args.proof),restore,policy.value if local_policy or owner_policy else read(args.approval),evidence,args.action=='resume')
    print(json.dumps({'result':result['kind'],'activation_ready':False,'production_deployed':False}))

if __name__=='__main__':
    try:main()
    except Exception as error:
        print(type(error).__name__+': '+str(error),file=sys.stderr);sys.exit(1)
