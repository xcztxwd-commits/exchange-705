"""Stage 4 only. Reuses reviewed tools on new, labelled local fixtures; never starts stage 2."""
import argparse, datetime as dt, hashlib, json, os, secrets, socket, subprocess, sys, time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/multitenant'))
import mysql_migration as core
import controlled_migration as controlled
BASE=Path(r'C:\workspace\fx\705\reports\tenant-stage34-baseline-20261002-232106\baseline.json')
RUN='stage4-baseline-20261002-232106'
OUT=ROOT/'reports'/RUN
PRIVATE=ROOT/'rollback/multitenant-20260929'/RUN
STATE=OUT/'run.json'
LABEL='com.gtcfesk.stage4.run'

def save(path,value):
    core.atomic_json(path,value)

def sha(path): return core.file_hash(Path(path))
def read(path): return json.loads(Path(path).read_text(encoding='utf-8-sig'))
def stamp(): return dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).isoformat()
def command(args, data=None, check=True, env=None):
    start=stamp();p=subprocess.run(args,input=data,capture_output=True,env=env)
    OUT.mkdir(parents=True,exist_ok=True)
    with (OUT/'commands.jsonl').open('a',encoding='utf-8') as f:
        f.write(json.dumps({'command':args,'cwd':str(ROOT),'startedAt':start,'endedAt':stamp(),'exitCode':p.returncode,'stdinSha256':hashlib.sha256(data).hexdigest() if data else None,'stdoutSha256':hashlib.sha256(p.stdout).hexdigest(),'stderrSha256':hashlib.sha256(p.stderr).hexdigest()},ensure_ascii=False)+'\n')
    if check and p.returncode: raise RuntimeError(args[0]+' exit '+str(p.returncode))
    return p

def normalized_mounts(mounts):
    return sorted([{k:m.get(k) for k in ['Type','Source','Destination','RW','Mode','Propagation','Name','Driver']} for m in mounts],key=lambda m:json.dumps(m,sort_keys=True))
def snapshot(name):
    x=json.loads(command(['docker','inspect',name]).stdout)[0]
    return {'id':x['Id'],'name':x['Name'].lstrip('/'),'imageId':x['Image'],'image':x['Config']['Image'],'labels':x['Config'].get('Labels',{}),'mounts':normalized_mounts(x.get('Mounts',[])),'ports':x['HostConfig'].get('PortBindings',{}),'networkMode':x['HostConfig']['NetworkMode'],'command':x['Config']['Cmd'],'pid':x['State']['Pid'],'startedAt':x['State']['StartedAt'],'status':x['State']['Status']}
def own(name):
    s=read(STATE);x=snapshot(name);old=s['resources'][name]
    assert x['labels'].get(LABEL)==RUN
    for k in ['id','imageId','labels','mounts','ports','networkMode','command']: assert x[k]==old[k],('ownership changed',name,k)
    return x

class BatchDatabase(core.Database):
    def __init__(self,container,database):
        super().__init__(container,database)
        info=json.loads(core.run(['docker','inspect',container]).stdout)[0]
        assert info['Id']==self.identity['container_id'] and info['Config']['Labels'].get(LABEL)==RUN
        self._password=next(v.split('=',1)[1] for v in info['Config']['Env'] if v.startswith('MYSQL_ROOT_PASSWORD='))
        self._port=int(info['NetworkSettings']['Ports']['3306/tcp'][0]['HostPort'])
    def query(self,sql):
        # Installed driver, real MySQL. Multi-statements preserve exact Decimal/JSON text.
        # Raw imports and DDL still go through the reviewed mysql client transport.
        import pymysql
        from pymysql.constants import CLIENT
        connection=pymysql.connect(host='127.0.0.1',port=self._port,user='root',password=self._password,database=self.database,charset='utf8mb4',autocommit=True,client_flag=CLIENT.MULTI_STATEMENTS)
        rows=[]
        try:
            with connection.cursor() as cursor:
                cursor.execute(sql)
                while True:
                    if cursor.description:
                        for row in cursor.fetchall():
                            rows.extend('\t'.join('NULL' if v is None else v.decode('utf-8') if isinstance(v,bytes) else str(v) for v in row).splitlines())
                    if not cursor.nextset():break
        finally:connection.close()
        return rows

def db(kind,name):
    s=read(STATE);container=s[kind];own(container)
    return BatchDatabase(container,name)

def initial():
    assert not STATE.exists(),'Existing run is preserved; use another action'
    b=read(BASE);assert ROOT==Path(b['stage4Worktree'])
    assert command(['git','rev-parse','HEAD']).stdout.decode().strip()==b['baselineCommit']
    assert command(['git','branch','--show-current']).stdout.decode().strip()==b['stage4Branch']
    results={}
    for key in ['sourceSha256','acceptedBuildTestInputSha256']:
        bad=[p for p,h in b[key].items() if not (ROOT/p).is_file() or sha(ROOT/p)!=h]
        assert not bad,bad;results[key]={'checked':len(b[key]),'mismatches':bad}
    core.restrict_directory(PRIVATE)
    old=read(Path(b['stage2Report'])/'final-current-backup-independent-restore.json')
    hist=read(Path(b['root'])/'reports/multitenant/latest-success.json')
    inputs={'legacy':hist['backup'],**{m:{'path':v['backup'],'sha256':v['backupSha256']} for m,v in old['databases'].items()}}
    for x in inputs.values(): assert sha(x['path'])==x['sha256']
    protected=[]
    for cid in command(['docker','ps','-aq','--filter','name=mt705-stage1-20261002-022430','--filter','name=mt705-stage2-retest-20261002-193957']).stdout.decode().split():
        x=snapshot(cid)
        if 'browser' not in x['name'] and 'java8' not in x['name']: protected.append(x)
    save(OUT/'baseline-check.json',{'at':stamp(),'baselineCommit':b['baselineCommit'],'branch':b['stage4Branch'],'manifestSha256':sha(BASE),'inputs':results,'stage2Resources':protected})
    save(OUT/'coverage-plan.json',{'reused':['same-input stage2 funds/audit and independent final-backup proof','same-input actual package guard tests','existing full public redaction proof'], 'fresh':['owned source/restore identities and restored REAL/DEMO full state','historical non-scoped fixture plan/backup/apply/resume on current DDL','tool portability and uncovered orphan/file/archive cases'], 'notRun':['stage2 four-front business lifecycle','stage3 application changes','stage5 capacity'], 'inputReason':'Legacy evidence has only 4 DDL checksums, current manifest differs; historical proof cannot cover new migration set. Existing fixture name/output contracts need explicit stage4 adaptation.'})
    ports=b['ports']['stage4']
    for p in ports.values():
        with socket.socket() as sock: sock.bind(('127.0.0.1',p))
    network='mt705-'+RUN;assert command(['docker','network','inspect',network],check=False).returncode!=0
    command(['docker','network','create','--label',LABEL+'='+RUN,network])
    s={'run':RUN,'private':str(PRIVATE),'network':network,'resources':{},'inputs':inputs,'baselineCommit':b['baselineCommit']}
    save(STATE,s)
    for kind,port in [('source',ports['mysql']),('restore',ports['restoreMysql']),('redis',ports['redis'])]:
        name=network+'-'+kind;volume=name+'-data'
        assert command(['docker','inspect',name],check=False).returncode!=0
        assert command(['docker','volume','inspect',volume],check=False).returncode!=0
        command(['docker','volume','create','--label',LABEL+'='+RUN,volume])
        image='redis:6.2-alpine' if kind=='redis' else 'mysql:5.7'
        args=['docker','run','-d','--name',name,'--network',network,'--label',LABEL+'='+RUN,'--label','com.gtcfesk.multitenant.test=true','-p',f'127.0.0.1:{port}:'+('6379' if kind=='redis' else '3306')]
        env=os.environ.copy()
        if kind=='redis': args+=['--mount',f'type=volume,source={volume},target=/data',image,'redis-server','--appendonly','yes']
        else:
            directory='/var/lib/stage4-'+kind
            env['MYSQL_ROOT_PASSWORD']=secrets.token_hex(32)
            args+=['--env','MYSQL_ROOT_PASSWORD','--mount',f'type=volume,source={volume},target={directory}',image,'--datadir='+directory,'--character-set-server=utf8mb4','--collation-server=utf8mb4_unicode_ci']
        command(args,env=env);s[kind]=name;s['resources'][name]=snapshot(name);save(STATE,s)
        deadline=time.monotonic()+150
        while time.monotonic()<deadline:
            ping=['docker','exec',name,'redis-cli','PING'] if kind=='redis' else ['docker','exec',name,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT 1"']
            if command(ping,check=False).returncode==0:break
            time.sleep(1)
        else:raise RuntimeError('Owned instance not ready')
    print('BASELINE_INPUTS_OK; isolated source/restore/redis ready')

def relation_counts(database):
    cols=database.columns();queries=[]
    for child,field,parent,key in core.MANIFEST['relations']:
        if child not in cols or parent not in cols: continue
        cf={x[0] for x in cols[child]};pf={x[0] for x in cols[parent]}
        if field not in cf or key not in pf: continue
        scope=' AND c.tenant_id=p.tenant_id' if 'tenant_id' in cf and 'tenant_id' in pf else ''
        queries.append(f'SELECT {core.literal(child+"."+field)},COUNT(*) FROM {core.ident(child)} c LEFT JOIN {core.ident(parent)} p ON c.{core.ident(field)}=p.{core.ident(key)}{scope} WHERE c.{core.ident(field)} IS NOT NULL AND p.{core.ident(key)} IS NULL;')
    return {row.split('\t')[0]:int(row.split('\t')[1]) for row in database.query('\n'.join(queries))}


def restore_current():
    s=read(STATE);result={}
    for mode in ['REAL','DEMO']:
        source=db('source','mt705_s4_'+mode.lower());target=db('restore','mt705_restore_s4_'+mode.lower())
        backup=s['inputs'][mode];assert sha(backup['path'])==backup['sha256']
        data=Path(backup['path']).read_text(encoding='utf-8')
        for instance in [source,target]:
            exists=instance.sql('SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='+core.literal(instance.database),database=False).stdout.strip()==b'1'
            if not exists:instance.create_empty();instance.sql(data)
        # Read-only continuation after interrupted verification; never import over an existing database.
        for table in ['user_account','support_message']:
            assert source.query('SHOW CREATE TABLE '+core.ident(table))==core.Database.query(source,'SHOW CREATE TABLE '+core.ident(table))
        a=controlled.state(source);z=controlled.state(target);assert a==z,'Full source/restore mismatch'
        ids=[controlled.target(x) for x in [source,target]];assert ids[0]['server_uuid']!=ids[1]['server_uuid']
        relations=relation_counts(source);assert relations==relation_counts(target)
        # Actual full-column fingerprint precedes any stage4 business writes. Decimal values remain strings.
        balances=source.query('SELECT tenant_id,coin,CAST(SUM(available) AS CHAR),CAST(SUM(frozen) AS CHAR),COUNT(*) FROM asset_account GROUP BY tenant_id,coin ORDER BY tenant_id,coin')
        assert balances==target.query('SELECT tenant_id,coin,CAST(SUM(available) AS CHAR),CAST(SUM(frozen) AS CHAR),COUNT(*) FROM asset_account GROUP BY tenant_id,coin ORDER BY tenant_id,coin')
        import decimal
        for row in balances:
            f=row.split('\t');decimal.Decimal(f[2]);decimal.Decimal(f[3])
        save(PRIVATE/(mode+'-full-state.json'),a)
        result[mode]={'result':'PASS_FRESH','backupSha256':backup['sha256'],'source':ids[0],'restore':ids[1],'tableCount':len(a['data']['tables']),'totalRows':sum(v['rows'] for v in a['data']['tables'].values()),'schemaSha256':a['schema']['sha256'],'allColumnsRowsEqual':True,'fullFingerprintSha256':controlled.digest(a['data']),'relationCounts':relations,'decimalBalancesSha256':controlled.digest(balances),'frozenNotForcedToZero':True,'schemaEpoch':source.query('SELECT version,minimum_application_epoch,business_activation_ready+0 FROM tenant_schema_version')}
    save(OUT/'current-restore.json',result);print('CURRENT_RESTORE_PASS REAL/DEMO full DDL/rows/relations/Decimal')

def stop():
    s=read(STATE);before=[];after=[]
    for name in s['resources']:
        before.append(own(name));command(['docker','stop',name]);after.append(own(name));assert after[-1]['status']=='exited'
    original=read(OUT/'baseline-check.json')['stage2Resources'];current=[snapshot(x['id']) for x in original]
    assert original==current,'Stage2 resource state changed externally; investigate'
    save(OUT/'cleanup.json',{'result':'PASS','before':before,'after':after,'stage2Unchanged':True,'volumesBackupsEvidenceRetained':True,'networkRetained':True})
    print('OWNED_SERVICES_STOPPED; stage2 unchanged; volumes retained')

if __name__=='__main__':
    action=sys.argv[1]
    {'initial':initial,'restore-current':restore_current,'stop':stop}[action]()
