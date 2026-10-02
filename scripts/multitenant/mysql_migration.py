"""MySQL 5.7 migration, backup/restore rehearsal and fail-closed release gate.

No third-party Python dependency. Docker's mysql client transports SQL through stdin;
credentials stay inside the named container and never appear in reports/arguments.
The default rehearsal imports SCHEMA ONLY from the reviewed snapshot, then synthetic
data. Existing databases/volumes are never dropped by this program.
"""
import argparse
import concurrent.futures
import datetime as dt
import decimal
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = json.loads((ROOT/'scripts/multitenant/table_manifest.json').read_text(encoding='utf-8'))
MIGRATIONS = [ROOT/'exchange-backend/src/main/resources/db/migration'/name for name in MANIFEST['migration_files']]
if not all(p.is_file() and p.name.startswith('V20') and p.parent == ROOT/'exchange-backend/src/main/resources/db/migration' for p in MIGRATIONS):
    raise RuntimeError('Reviewed migration set is incomplete or invalid')
TEST_CONTAINER = 'mt705-20260929-test-mysql'
EPOCH = MANIFEST['schema_epoch']

def ident(value):
    if not re.fullmatch(r'[A-Za-z0-9_]+', value):
        raise ValueError('Invalid SQL identifier')
    return '`'+value+'`'

def literal(value):
    if value is None: return 'NULL'
    if value=='': return "''"
    if isinstance(value, bool): return '1' if value else '0'
    if isinstance(value, (int, decimal.Decimal)): return str(value)
    return "CONVERT(0x"+str(value).encode('utf-8').hex()+" USING utf8mb4)"

def run(args, data=None, check=True):
    result = subprocess.run(args,input=data,capture_output=True)
    if check and result.returncode:
        # SQL errors can include duplicate emails; omit offending values from console.
        err=result.stderr.decode('utf-8',errors='replace')
        code=re.search(r'ERROR (\d+) \(([^)]+)\)',err)
        raise RuntimeError('Command failed: '+('mysql '+code.group(0) if code else args[0]+' exit '+str(result.returncode)))
    return result

class Database:
    def __init__(self, container, database):
        self.container, self.database = container, database
        ident(database)
        info=json.loads(run(['docker','inspect',container]).stdout)[0]
        self.test=info['Config'].get('Labels',{}).get('com.gtcfesk.multitenant.test')=='true'
        self.identity={'container':info['Name'].lstrip('/'),'container_id':info['Id'],
                       'image':info['Config']['Image'],'database':database,
                       'test_instance':self.test,'mounts':[{k:x.get(k) for k in ['Type','Name','Destination']} for x in info.get('Mounts',[])],
                       'ports':info['NetworkSettings'].get('Ports',{})}
        if 'mysql' not in self.identity['image'].lower(): raise RuntimeError('Target is not a MySQL container')

    def command(self, database=True):
        return ['docker','exec','-i',self.container,'sh','-c',
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --max-allowed-packet=64M --default-character-set=utf8mb4 --batch --skip-column-names --raw'+
                (' '+self.database if database else '')]

    def sql(self, sql, database=True, check=True):
        return run(self.command(database),sql.encode('utf-8'),check)

    def query(self, sql):
        return self.sql(sql).stdout.decode('utf-8').rstrip('\n').splitlines()

    def create_empty(self):
        if not self.test: raise RuntimeError('Restore/rehearsal creates databases only in an isolated labelled test instance')
        # Deliberately no IF NOT EXISTS: refusing collisions protects old rehearsal evidence.
        self.sql('CREATE DATABASE '+ident(self.database)+' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;',False)

    def tables(self):
        return self.query('SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE=\'BASE TABLE\' ORDER BY TABLE_NAME')

    def columns(self):
        rows=self.query("SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE,IS_NULLABLE,COALESCE(COLUMN_DEFAULT,'__NO_DEFAULT__'),EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION")
        result={}
        for row in rows:
            table,*values=row.split('\t')
            result.setdefault(table,[]).append(values)
        return result

    def dump(self, path, schema_only=False):
        restrict_directory(path.parent)
        command=['docker','exec',self.container,'sh','-c',
                 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers --events --hex-blob --default-character-set=utf8mb4 --set-gtid-purged=OFF '+
                 ('--no-data ' if schema_only else '')+self.database]
        temporary=path.with_suffix(path.suffix+'.tmp')
        with temporary.open('wb') as output:
            result=subprocess.run(command,stdout=output,stderr=subprocess.PIPE)
        if result.returncode: raise RuntimeError('Backup failed; no migration permitted')
        temporary.replace(path)
        return {'path':str(path),'sha256':file_hash(path),'bytes':path.stat().st_size,'schema_only':schema_only}

def require_fixture(db, fixture_container_id=None):
    """Keep legacy default; explicit new fixtures bind a full live container ID, never a port."""
    if not db.test or not db.database.startswith('mt705_'):
        raise ValueError('Explicit isolated fixture database required')
    if fixture_container_id is None:
        if db.container != TEST_CONTAINER:raise ValueError('Dedicated fixture or explicit full container ID required')
        return
    if not re.fullmatch(r'[0-9a-f]{64}', fixture_container_id) or fixture_container_id != db.identity['container_id']:
        raise ValueError('Fixture container identity mismatch')
    info=json.loads(run(['docker','inspect',db.container]).stdout)[0]
    if info['Id'] != fixture_container_id or info['Config'].get('Labels',{}).get('com.gtcfesk.multitenant.test') != 'true':
        raise ValueError('Live fixture identity/label changed')


def file_hash(path):
    digest=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''): digest.update(chunk)
    return digest.hexdigest()

def restrict_directory(path):
    path.mkdir(parents=True,exist_ok=True)
    if os.name=='nt':
        principal=run(['whoami']).stdout.decode().strip()
        run(['icacls',str(path),'/inheritance:r','/grant:r',principal+':(OI)(CI)F'])
    else: path.chmod(0o700)

def atomic_json(path, data):
    path.parent.mkdir(parents=True,exist_ok=True)
    temp=path.with_suffix(path.suffix+'.tmp')
    temp.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    temp.replace(path)

def business_columns(db):
    """Freeze the current business-column scope before prerequisite DDL adds provenance fields."""
    return {table:[row[0] for row in fields if row[0]!='current_token']
            for table,fields in db.columns().items() if table not in MANIFEST['control']}

def fingerprint(db, reference=None):
    """Hash every original business column including messages/bytes/timestamps, not credentials in reports.

    Hashes are sorted before combining so physical row ordering cannot cause false failures.
    Backup/schema, money totals and each table's count are separately retained.
    """
    columns=business_columns(db) if reference is None else reference
    queries=[]
    for table,fields in columns.items():
        # HEX(number) rounds numeric arguments in MySQL; cast first to preserve every
        # DECIMAL digit, datetime fraction and binary evidence byte.
        expr="JSON_ARRAY("+','.join('HEX(CAST('+ident(c)+' AS BINARY))' for c in fields)+')'
        queries.append('SELECT '+literal(table)+',SHA2('+expr+',256) FROM '+ident(table)+';')
    result=db.query('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ; START TRANSACTION WITH CONSISTENT SNAPSHOT;\n'+'\n'.join(queries)+'\nCOMMIT;')
    groups={table:[] for table in columns}
    for row in result:
        table,digest=row.split('\t');groups[table].append(digest)
    return {'columns':columns,'tables':{table:{'rows':len(hashes),'sha256':hashlib.sha256(('\n'.join(sorted(hashes))).encode()).hexdigest()}
                                      for table,hashes in groups.items()}}

def preflight(db):
    tables=db.tables(); columns=db.columns()
    known=set(MANIFEST['private']+MANIFEST['shared']+MANIFEST['control'])
    result={'target':db.identity,'mysql_version':db.query('SELECT VERSION()')[0],
            'unknown_tables':sorted(set(tables)-known),'present_tables':tables,
            'missing_private_tables':sorted(set(MANIFEST['private'])-set(tables)),
            'missing_legacy_tables':sorted((set(MANIFEST['private'])-set(MANIFEST.get('introduced_tables',{})))-set(tables)),
            'orphans':{},'collisions':{},'unsupported_triggers':[]}
    queries=[]
    labels=[]
    for child,field,parent,parent_field in MANIFEST['relations']:
        if child in columns and parent in columns and field in [x[0] for x in columns[child]]:
            label=child+'.'+field
            queries.append('SELECT '+literal(label)+',COUNT(*) FROM '+ident(child)+' c LEFT JOIN '+ident(parent)+' p ON c.'+ident(field)+'=p.'+ident(parent_field)+' WHERE c.'+ident(field)+' IS NOT NULL AND p.'+ident(parent_field)+' IS NULL;')
    if queries:
        for row in db.query('\n'.join(queries)):
            key,count=row.split('\t');
            if int(count):result['orphans'][key]=int(count)
    for name,sql in {
        'normalized_email':"SELECT COUNT(*) FROM (SELECT LOWER(TRIM(email)) FROM user_account GROUP BY LOWER(TRIM(email)) HAVING COUNT(*)>1) d",
        'normalized_phone':"SELECT COUNT(*) FROM (SELECT NULLIF(TRIM(phone),'') FROM user_account WHERE NULLIF(TRIM(phone),'') IS NOT NULL GROUP BY NULLIF(TRIM(phone),'') HAVING COUNT(*)>1) d",
        'backend_namespace':"SELECT COUNT(*) FROM (SELECT name FROM (SELECT LOWER(TRIM(account)) name FROM admin_user UNION ALL SELECT LOWER(TRIM(email)) FROM user_account WHERE user_type='agent') identities GROUP BY name HAVING COUNT(*)>1 OR name='' OR name IS NULL) conflicts"
    }.items():
        count=int(db.query(sql)[0])
        if count:result['collisions'][name]=count
    result['unsupported_triggers']=db.query("SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME NOT LIKE 'mt_%'")
    result['already_scoped']=any('tenant_id' in [x[0] for x in fields] for table,fields in columns.items() if table in MANIFEST['private'])
    result['existing_control_plane']=sorted(set(tables).intersection(MANIFEST['control']))
    result['passed']=not (result['unknown_tables'] or result['missing_legacy_tables'] or result['orphans'] or result['collisions'] or result['unsupported_triggers'] or result['already_scoped'] or result['existing_control_plane'])
    return result

def apply(db):
    for path in MIGRATIONS:
        db.sql(path.read_text(encoding='utf-8'))
    return {path.name:file_hash(path) for path in MIGRATIONS}

def guard(db, application_epoch, require_ready=False):
    if 'tenant_schema_version' not in db.tables():
        if application_epoch:raise RuntimeError('Tenant-aware application requires a migrated schema')
        return {'passed':True,'schema_epoch':0}
    row=db.query('SELECT MAX(minimum_application_epoch),MIN(business_activation_ready+0) FROM tenant_schema_version')[0].split('\t')
    minimum=int(row[0]);ready=row[1]=='1'
    if application_epoch<minimum:raise RuntimeError('Release rejected: legacy application cannot access a multi-tenant database')
    if require_ready and not ready:raise RuntimeError('Release rejected: isolation acceptance is not approved; activation stays disabled')
    return {'passed':True,'schema_epoch':minimum,'activation_ready':ready}

def fixture_insert(db, table, values, metadata):
    data={}
    for name,kind,nullable,default,extra in metadata[table]:
        if name in values:data[name]=values[name];continue
        if 'GENERATED' in extra or 'auto_increment' in extra or nullable=='YES' or default!='__NO_DEFAULT__':continue
        if kind in ['bigint','int','smallint','tinyint','bit','decimal','float','double']:data[name]=0
        elif kind in ['datetime','timestamp','date']:data[name]='2026-09-20 12:00:00'
        else:data[name]='TEST'
    data.update(values)
    db.sql('INSERT INTO '+ident(table)+'('+','.join(map(ident,data))+') VALUES('+','.join(literal(x) for x in data.values())+');')

def seed(db):
    m=db.columns()
    fixture_insert(db,'admin_role',{'id':1,'role_name':'Fixture owner','role_code':'super_admin'},m)
    fixture_insert(db,'admin_user',{'id':1,'account':'mt_fixture_admin','email':'admin@fixture.invalid','password_hash':'NOT_A_LOGIN_PASSWORD','role':'super_admin','role_id':1,'enabled':True},m)
    fixture_insert(db,'user_account',{'id':7000001,'email':'user@fixture.invalid','phone':'','password_hash':'NOT_A_LOGIN_PASSWORD','user_type':'agent','status':'normal'},m)
    fixture_insert(db,'asset_account',{'id':1,'user_id':7000001,'coin':'USDT','available':decimal.Decimal('1234.5678901234567890'),'frozen':decimal.Decimal('2.0000000000000000')},m)
    fixture_insert(db,'trading_symbol',{'id':1,'symbol':'BTCUSD','market_instrument_key':'fixture:btc','base_currency':'BTC','quote_currency':'USD'},m)
    fixture_insert(db,'contract_order',{'id':1,'user_id':7000001,'symbol':'BTCUSD','status':'OPEN','margin':decimal.Decimal('15.2500000000000000'),'quantity':decimal.Decimal('0.01')},m)
    fixture_insert(db,'deposit_record',{'id':1,'user_id':7000001,'amount':decimal.Decimal('1234.5678901234567890'),'status':'COMPLETED'},m)
    fixture_insert(db,'support_conversation',{'id':1,'user_id':7000001,'admin_id':1,'status':'CLOSED','closed_at':'2024-01-01 00:00:00'},m)
    fixture_insert(db,'support_message',{'id':1,'conversation_id':1,'sender':'USER','sender_id':7000001,'sender_name':'Fixture user','request_id':'fixture-message','text':'Synthetic migration evidence only.','image':False,'image_hash':'','previous_hash':'','hash':hashlib.sha256(b'fixture-evidence').hexdigest()},m)
    fixture_insert(db,'support_attachment',{'message_id':1,'content':'synthetic attachment bytes'},m)

def assert_failure(db,sql,label,expected=None):
    result=db.sql(sql,check=False)
    if result.returncode==0:raise AssertionError(label+' unexpectedly accepted')
    error=re.search(r'ERROR (\d+) \(([^)]+)\)',result.stderr.decode(errors='replace'))
    if expected is not None and (error is None or int(error.group(1))!=expected):
        raise AssertionError(label+' failed for the wrong reason; expected MySQL '+str(expected))
    return {'test':label,'passed':True,'mysql_error':error.group(0) if error else 'rejected'}

def tests(db):
    results=[];m=db.columns()
    distinct=db.query("SELECT SHA2(JSON_ARRAY(HEX(CAST(CAST('1.0000000000000000' AS DECIMAL(32,16)) AS BINARY))),256) <> SHA2(JSON_ARRAY(HEX(CAST(CAST('1.0000000000000001' AS DECIMAL(32,16)) AS BINARY))),256)")[0]
    assert distinct=='1'
    results.append({'test':'migration fingerprints preserve all 16 decimal fraction digits','passed':True})
    db.sql("INSERT INTO tenant(id,code,name,status,created_at) VALUES(2,'A','Fixture A','MAINTENANCE',UTC_TIMESTAMP(6)),(3,'B','Fixture B','MAINTENANCE',UTC_TIMESTAMP(6));")
    for tenant,user in [(2,7000010),(3,7000011)]:
        fixture_insert(db,'user_account',{'id':user,'tenant_id':tenant,'email':'duplicate@fixture.invalid','phone':'','password_hash':'NOT_A_LOGIN_PASSWORD'},m)
    results.append({'test':'A/B same email and empty phone accepted','passed':True})
    fixture_insert(db,'user_account',{'id':7000012,'tenant_id':2,'email':'second@fixture.invalid','phone':'','password_hash':'NOT_A_LOGIN_PASSWORD'},m)
    fixture_insert(db,'support_message',{'id':2,'tenant_id':1,'conversation_id':1,'sender':'USER','sender_id':7000001,'sender_name':'Fixture user','request_id':'fixture-fk','text':'FK fixture','image':False,'image_hash':'','previous_hash':'','hash':'fixture-fk'},m)
    for label,sql in [
        ('missing tenant insert',"INSERT INTO asset_account(user_id,coin,available,frozen,row_version) VALUES(7000010,'BAD',1,0,0)"),
        ('cross-tenant account FK',"INSERT INTO asset_account(tenant_id,user_id,coin,available,frozen,row_version) VALUES(3,7000010,'BAD',1,0,0)"),
        ('tenant reassignment',"UPDATE user_account SET tenant_id=3 WHERE id=7000010"),
        ('cross-tenant role FK',"INSERT INTO admin_user(id,tenant_id,account,email,password_hash,enabled,role,row_version,role_id) VALUES(8,2,'cross-role','cross-role@fixture.invalid','NOT_A_PASSWORD',1,'admin',0,1)"),
        ('cross-tenant support attachment FK',"INSERT INTO support_attachment(tenant_id,message_id,content) VALUES(2,2,'bad')"),
        ('backend account global uniqueness',"INSERT INTO backend_login(normalized_account,tenant_id,subject_type,user_id,enabled) VALUES('mt_fixture_admin',2,'AGENT',7000010,1)"),
        ('backend polymorphic ambiguity',"INSERT INTO backend_login(normalized_account,tenant_id,subject_type,user_id,admin_user_id,enabled) VALUES('bad',1,'ADMIN',7000001,1,1)"),
        ('audit update forbidden',"UPDATE control_audit_log SET detail='tamper' WHERE id=1"),
        ('audit delete forbidden',"DELETE FROM control_audit_log WHERE id=1")]:
        if label.startswith('audit update'):
            db.sql("INSERT INTO control_audit_log(id,actor_id,tenant_id,action,outcome,created_at) VALUES(1,NULL,1,'FIXTURE','SUCCESS',UTC_TIMESTAMP(6))")
        results.append(assert_failure(db,sql,label,1452 if label.endswith(' FK') else None))
    fixture_insert(db,'asset_account',{'id':2,'tenant_id':2,'user_id':7000010,'coin':'USDT','available':decimal.Decimal('0'),'frozen':decimal.Decimal('0')},m)
    fixture_insert(db,'deposit_record',{'id':2,'tenant_id':2,'user_id':7000010,'amount':decimal.Decimal('25'),'status':'PENDING','order_no':'FIXTURE-ONE'},m)
    # This exercises the real schema's lock + unique ledger + audit transaction, not an HTTP/mock replacement.
    statement="""START TRANSACTION;
SELECT id FROM deposit_record WHERE tenant_id=2 AND id=2 FOR UPDATE;
INSERT INTO deposit_credit_record(tenant_id,deposit_record_id,user_id,account_type,amount_usd,balance_before,balance_after,operator_type,operator_id,operator_name,credited_at)
SELECT 2,2,7000010,'FUND',25,a.available,a.available+25,'CONTROL',1,'Fixture operator',UTC_TIMESTAMP(6)
FROM deposit_record d JOIN asset_account a ON a.tenant_id=d.tenant_id AND a.user_id=d.user_id AND a.coin='USDT'
WHERE d.tenant_id=2 AND d.id=2 AND d.status='PENDING';
SET @changed=ROW_COUNT();
UPDATE asset_account SET available=available+25,row_version=row_version+1 WHERE tenant_id=2 AND user_id=7000010 AND coin='USDT' AND @changed=1;
UPDATE deposit_record SET status='COMPLETED',credited_at=UTC_TIMESTAMP(6),row_version=row_version+1 WHERE tenant_id=2 AND id=2 AND status='PENDING' AND @changed=1;
INSERT INTO control_audit_log(actor_id,tenant_id,request_id,action,outcome,created_at) SELECT NULL,2,'fixture-concurrent','DEPOSIT_CREDIT','SUCCESS',UTC_TIMESTAMP(6) FROM DUAL WHERE @changed=1;
COMMIT;"""
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(lambda _:db.sql(statement),range(16)))
    values=db.query("SELECT (SELECT CAST(available AS CHAR) FROM asset_account WHERE tenant_id=2 AND id=2),(SELECT COUNT(*) FROM deposit_credit_record WHERE tenant_id=2 AND deposit_record_id=2),(SELECT COUNT(*) FROM control_audit_log WHERE tenant_id=2 AND request_id='fixture-concurrent')")[0].split('\t')
    assert decimal.Decimal(values[0])==25 and values[1:]==['1','1'],values
    results.append({'test':'16 concurrent retries: one credit, one ledger, one audit','passed':True,'balance':values[0],'ledger_rows':1,'audit_rows':1})
    # An audit failure must abort its transaction, including the balance mutation.
    results.append(assert_failure(db,"START TRANSACTION; UPDATE asset_account SET available=999 WHERE tenant_id=2 AND id=2; INSERT INTO control_audit_log(id,action,outcome,created_at) VALUES(1,'BAD','FAIL',UTC_TIMESTAMP(6)); COMMIT;",'audit failure rolls back financial transaction'))
    assert decimal.Decimal(db.query('SELECT available FROM asset_account WHERE tenant_id=2 AND id=2')[0])==25
    assert db.query("SELECT policy_value FROM tenant_policy WHERE tenant_id=1 AND policy_key='retention.auto_delete_enabled'")==['false']
    assert db.query('SELECT COUNT(*) FROM support_message WHERE tenant_id=1')==['2']
    results.append({'test':'retention initially disabled; expired fixture evidence intact','passed':True})
    auto=int(db.query("SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_account'")[0])
    assert auto>7000012
    results.append({'test':'historical IDs preserved; next user ID greater than maximum','passed':True,'next_id':auto})
    try:guard(db,0)
    except RuntimeError:results.append({'test':'legacy application release rejected','passed':True})
    else:raise AssertionError('Legacy release permitted')
    try:guard(db,EPOCH,True)
    except RuntimeError:results.append({'test':'unapproved multi-tenant activation rejected','passed':True})
    else:raise AssertionError('Premature activation permitted')
    return results

def ensure_test():
    existing=run(['docker','inspect',TEST_CONTAINER],check=False)
    if existing.returncode:
        env=os.environ.copy();env['MT_TEST_MYSQL_PASSWORD']=secrets.token_urlsafe(32)
        result=subprocess.run(['docker','compose','-f',str(ROOT/'compose.multitenant-test.yaml'),'up','-d'],env=env,capture_output=True)
        if result.returncode:raise RuntimeError('Isolated MySQL startup failed; check port 64029/subnet 10.235.73.0/24 availability')
    verified=Database(TEST_CONTAINER,'mt705_fixture')
    if not verified.test:raise RuntimeError('Existing test container label mismatch')
    for bindings in verified.identity['ports'].values():
        for binding in bindings or []:
            if binding['HostIp']!='127.0.0.1':raise RuntimeError('Test database must bind localhost only')
    for _ in range(90):
        if verified.sql('SELECT 1',False,False).returncode==0:return
        time.sleep(1)
    raise RuntimeError('Isolated MySQL did not become ready')

def rehearse():
    ensure_test()
    stamp=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'_'+secrets.token_hex(2)
    folder=ROOT/'reports/multitenant'/stamp
    restricted=ROOT/'rollback/multitenant-20260929/schema'/stamp
    db=Database(TEST_CONTAINER,'mt705_'+stamp.lower());db.create_empty()
    report={'run_id':stamp,'started_at':dt.datetime.now(dt.timezone.utc).isoformat(),
            'scope':'Synthetic fixture on actual MySQL; production unchanged; not application/API/browser acceptance.',
            'target':db.identity,'passed':False}
    try:
        baseline=ROOT/'scripts/multitenant/legacy-schema.sql'
        if not baseline.exists():raise RuntimeError('Reviewed schema-only snapshot missing')
        db.sql(baseline.read_text(encoding='utf-8-sig'))
        original_columns=business_columns(db)
        db.sql(MIGRATIONS[0].read_text(encoding='utf-8'))
        seed(db)
        report['preflight']=preflight(db)
        if not report['preflight']['passed']:raise RuntimeError('Preflight rejected; inspect redacted report')
        before=fingerprint(db,original_columns);atomic_json(folder/'before.json',before)
        report['backup']=db.dump(restricted/'before.sql')
        restore=Database(TEST_CONTAINER,db.database+'_restore');restore.create_empty()
        restore.sql((restricted/'before.sql').read_text(encoding='utf-8'))
        restored=fingerprint(restore,before['columns'])
        if restored!=before:raise AssertionError('Backup restore verification mismatch')
        report['restore_verified']=True;report['restore_target']=restore.database
        report['migration_checksums']=apply(db)
        after=fingerprint(db,before['columns']);atomic_json(folder/'after.json',after)
        if after!=before:raise AssertionError('Business records/evidence changed during tenant migration')
        # These fields did not exist in the baseline. Their safe provenance initialization
        # is asserted separately; no original amount/status/evidence column is excluded.
        if db.query("SELECT order_no,source,account_type FROM deposit_record WHERE id=1")!=['LEGACY-DEP-1\tLEGACY_UNKNOWN\tFUND']:
            raise AssertionError('New deposit provenance fields were not safely initialized')
        report['new_provenance_initialized']=True
        report['migration_data_identical']=True
        report['private_tables_scoped']=len(db.query("SELECT TABLE_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME='tenant_id'"))
        report['tests']=tests(db)
        report['passed']=True
        report['migrated_backup']=db.dump(restricted/'migrated.sql')
    except Exception as error:
        report['error']=str(error)
        raise
    finally:
        report['completed_at']=dt.datetime.now(dt.timezone.utc).isoformat()
        atomic_json(folder/'result.json',report)
        if report['passed']:atomic_json(ROOT/'reports/multitenant/latest-success.json',report)
        print(str(folder/'result.json'),flush=True)
    print('MySQL migration/restore/concurrency checks passed. Application deployment remains gated.',flush=True)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['rehearse','inventory','backup','guard','migrate','restore-new','fixture-connection'])
    parser.add_argument('--container',default=TEST_CONTAINER)
    parser.add_argument('--database',default='mt705_fixture')
    parser.add_argument('--output',type=Path)
    parser.add_argument('--backup',type=Path)
    parser.add_argument('--application-epoch',type=int,default=0)
    parser.add_argument('--require-ready',action='store_true')
    parser.add_argument('--maintenance-confirmed',action='store_true')
    parser.add_argument('--allow-business-target',help='Explicit container/database acknowledgement; never a wildcard')
    args=parser.parse_args()
    if args.action=='rehearse':rehearse();return
    if args.action=='fixture-connection':
        if args.output is None:raise RuntimeError('--output is required; credentials must stay in the restricted rollback directory')
        allowed=(ROOT/'rollback/multitenant-20260929/schema').resolve()
        if allowed not in args.output.resolve().parents:raise RuntimeError('Connection file must be under the protected schema rollback directory')
        ensure_test()
        proof=json.loads((ROOT/'reports/multitenant/latest-success.json').read_text(encoding='utf-8'))
        if not proof.get('passed'):raise RuntimeError('Successful rehearsal required')
        name='mt705_probe_'+dt.datetime.now(dt.timezone.utc).strftime('%H%M%S')+'_'+secrets.token_hex(2)
        fresh=Database(TEST_CONTAINER,name);fresh.create_empty()
        backup=Path(proof['migrated_backup']['path'])
        if file_hash(backup)!=proof['migrated_backup']['sha256']:raise RuntimeError('Fixture backup checksum mismatch')
        fresh.sql(backup.read_text(encoding='utf-8'))
        username='mt705_'+secrets.token_hex(4);password=secrets.token_hex(24)
        fresh.sql("CREATE USER '"+username+"'@'%' IDENTIFIED BY '"+password+"'; GRANT ALL PRIVILEGES ON "+ident(name)+".* TO '"+username+"'@'%';")
        restrict_directory(args.output.parent)
        if args.output.exists():
            previous=args.output.with_name(args.output.stem+'-previous-'+secrets.token_hex(4)+'.json')
            previous.write_bytes(args.output.read_bytes())
        atomic_json(args.output,{'url':'jdbc:mysql://127.0.0.1:64029/'+name+'?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=UTC',
                                'username':username,'password':password,'database':name,'container':TEST_CONTAINER})
        print('Isolated database '+name+'; restricted connection file '+str(args.output.resolve()));return
    db=Database(args.container,args.database)
    if args.action=='inventory':
        result=preflight(db)
        atomic_json(args.output or ROOT/'reports/multitenant/inventory.json',result)
        print(json.dumps({'target':db.identity,'passed':result['passed'],'unknown_tables':result['unknown_tables'],'orphans':result['orphans'],'collisions':result['collisions']},ensure_ascii=False))
    elif args.action=='backup':
        if args.output is None:raise RuntimeError('--output is required')
        print(json.dumps(db.dump(args.output)))
    elif args.action=='guard':print(json.dumps(guard(db,args.application_epoch,args.require_ready)))
    elif args.action=='restore-new':
        if args.backup is None:raise RuntimeError('--backup is required')
        db.create_empty();db.sql(args.backup.read_text(encoding='utf-8'))
        print('Backup restored into new isolated database '+db.database)
    elif args.action=='migrate':
        if not db.test:raise RuntimeError('Business apply requires controlled_migration.py with immutable plan, independent newest restore, trusted approvals and phase ledger; legacy flags cannot authorize it')
        if not args.maintenance_confirmed:raise RuntimeError('Confirm stopped writers using --maintenance-confirmed')
        if not db.test and args.allow_business_target!=db.container+'/'+db.database:
            raise RuntimeError('Business target requires exact --allow-business-target container/database')
        proof=json.loads((ROOT/'reports/multitenant/latest-success.json').read_text(encoding='utf-8'))
        if not proof.get('passed') or proof.get('migration_checksums')!={p.name:file_hash(p) for p in MIGRATIONS}:
            raise RuntimeError('No successful rehearsal of the exact migration scripts')
        pre=preflight(db)
        if not pre['passed']:raise RuntimeError('Preflight rejected; run inventory and resolve findings')
        stamp=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'_'+secrets.token_hex(2)
        out=ROOT/'rollback/multitenant-20260929/schema'/('migration-'+stamp)
        before=fingerprint(db);backup=db.dump(out/'before.sql')
        ensure_test();restore=Database(TEST_CONTAINER,'mt705_restore_'+stamp.lower());restore.create_empty()
        restore.sql((out/'before.sql').read_text(encoding='utf-8'))
        if fingerprint(restore,before['columns'])!=before:raise AssertionError('Current target backup restore failed')
        # Backup and restore are proven before the first target DDL.
        checksums=apply(db)
        if fingerprint(db,before['columns'])!=before:raise AssertionError('Post-migration consistency failed; keep maintenance and use forward repair')
        atomic_json(out/'migration-result.json',{'target':db.identity,'backup':backup,'restore_verified':True,'migration_checksums':checksums,'passed':True,'activation_ready':False})
        print('Migration verified; business activation is still disabled. '+str(out))

if __name__=='__main__':
    try:main()
    except Exception as error:
        print(type(error).__name__+': '+str(error),file=sys.stderr)
        sys.exit(1)
