"""Stage 3 only: restore frozen backups to new owned resources; never migrate/reset."""
import hashlib, ipaddress, json, os, secrets, shutil, socket, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / 'reports/stage3-20261002-232106'
PRIVATE = RUN / 'private'
BASE = Path('C:/workspace/fx/705/reports/tenant-stage34-baseline-20261002-232106/baseline.json')
BASELINE = json.loads(BASE.read_text(encoding='utf-8-sig'))
OLD = Path(BASELINE['stage2Report'])
OLDHOME = OLD / 'fixture/stage1-retest-20261002-193957'
sys.path.insert(0, str(ROOT / 'scripts/multitenant'))
import mysql_migration as dbtools
save = dbtools.atomic_json
sha = dbtools.file_hash

def read(p): return json.loads(Path(p).read_text(encoding='utf-8-sig'))
def command(args): return dbtools.run(args).stdout
def inventory():
    ids = command(['docker','ps','-aq','--no-trunc']).decode().split()
    rows = json.loads(command(['docker','inspect']+ids)) if ids else []
    return {r['Id']: {'id':r['Id'],'name':r['Name'],'imageId':r['Image'],
        'image':r['Config']['Image'],'labels':r['Config'].get('Labels',{}),
        'mounts':sorted([{k:m.get(k) for k in ['Type','Name','Source','Destination','RW','Mode','Propagation']} for m in r.get('Mounts',[])],key=lambda m:json.dumps(m,sort_keys=True)),
        'ports':r['HostConfig'].get('PortBindings'),'running':r['State']['Running'],
        'startedAt':r['State']['StartedAt']} for r in rows}

def prepare():
    assert str(ROOT)==BASELINE['stage3Worktree']
    assert command(['git','-C',str(ROOT),'branch','--show-current']).decode().strip()==BASELINE['stage3Branch']
    assert not (RUN/'environment.json').exists(), 'Preserve existing run; do not reset'
    dbtools.restrict_directory(PRIVATE)
    ports=BASELINE['ports']['stage3']
    for port in ports.values():
        with socket.socket() as sock: sock.bind(('127.0.0.1',port))
    before=inventory();save(RUN/'resources-before.json',before)
    networkids=command(['docker','network','ls','-q']).decode().split()
    networks=json.loads(command(['docker','network','inspect']+networkids))
    used=[ipaddress.ip_network(c['Subnet'],strict=False) for n in networks for c in (n.get('IPAM',{}).get('Config') or []) if c.get('Subnet')]
    subnet=next(str(n) for n in ipaddress.ip_network('10.236.0.0/14').subnets(new_prefix=24) if not any(u.version==4 and n.overlaps(u) for u in used))
    name='mt705-stage3-20261002-232106';labels={'com.gtcfesk.stage3.run':name,'com.gtcfesk.multitenant.test':'true'}
    password=secrets.token_hex(32)
    (PRIVATE/'compose.env').write_text('STAGE3_MYSQL_PASSWORD='+password+'\n',encoding='utf-8')
    services={}
    for role in ['mysql','restore']:
        services[role]={'image':'mysql:5.7','container_name':name+'-'+role,'labels':labels,
            'environment':{'MYSQL_ROOT_PASSWORD':'${STAGE3_MYSQL_PASSWORD:?required}'},
            'command':['--character-set-server=utf8mb4','--collation-server=utf8mb4_unicode_ci','--sql-mode=STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION'],
            'volumes':[role+'-data:/var/lib/mysql'],'restart':'no'}
    services['mysql']['ports']=['127.0.0.1:'+str(ports['mysql'])+':3306']
    services['redis']={'image':'redis:6.2-alpine','container_name':name+'-redis','labels':labels,
        'ports':['127.0.0.1:'+str(ports['redis'])+':6379'],'command':['redis-server','--appendonly','yes'],
        'volumes':['redis-data:/data'],'restart':'no'}
    compose={'name':name,'services':services,'volumes':{r+'-data':{} for r in services},'networks':{'default':{'ipam':{'config':[{'subnet':subnet}]}}}}
    save(RUN/'compose.json',compose)
    args=['docker','compose','--env-file',str(PRIVATE/'compose.env'),'-f',str(RUN/'compose.json'),'up','-d']
    result=dbtools.run(args,check=False);(RUN/'logs/environment.log').write_bytes(result.stdout+result.stderr)
    save(RUN/'logs/environment-command.json',{'command':args,'exitCode':result.returncode});assert result.returncode==0
    own={k:v for k,v in inventory().items() if v['labels'].get('com.gtcfesk.stage3.run')==name}
    assert len(own)==3 and not set(own)&set(before)
    save(RUN/'resources-owned.json',own)
    spec={'run':name,'ports':ports,'containers':{r:services[r]['container_name'] for r in services},'databases':{m:'mt705_probe_stage3_'+m.lower()+'_20261002_232106'+('_demo' if m=='DEMO' else '') for m in ['REAL','DEMO']}}
    save(RUN/'environment.json',spec)
    for role in ['mysql','restore']:
        db=dbtools.Database(spec['containers'][role],spec['databases']['REAL'])
        for _ in range(90):
            if db.sql('SELECT 1',False,False).returncode==0:break
            time.sleep(1)
        else:raise RuntimeError('Owned MySQL not ready')
    restore_and_configure()

def restore_and_configure():
    spec=read(RUN/'environment.json');ports=spec['ports']
    current=inventory();owned=read(RUN/'resources-owned.json')
    assert all(current[id]==row for id,row in owned.items()), 'Resource identity changed'
    proof=read(OLD/'final-current-backup-independent-restore.json');results={}
    for mode,prior in proof['databases'].items():
        backup=Path(prior['backup']);assert sha(backup)==prior['backupSha256']
        db=dbtools.Database(spec['containers']['mysql'],spec['databases'][mode])
        exists=db.sql('SHOW DATABASES',False).stdout.decode().split()
        if db.database not in exists:db.create_empty();db.sql(backup.read_text(encoding='utf-8'))
        else:assert (PRIVATE/(mode+'-initial-fingerprint.json')).exists(), 'Unproved preexisting data'
        restore=dbtools.Database(spec['containers']['restore'],spec['databases'][mode]+'_restore')
        if restore.database not in restore.sql('SHOW DATABASES',False).stdout.decode().split():restore.create_empty();restore.sql(backup.read_text(encoding='utf-8'))
        columns={t:[f[0] for f in fields] for t,fields in db.columns().items()}
        a=dbtools.fingerprint(db,columns);b=dbtools.fingerprint(restore,columns);assert a==b
        if (PRIVATE/(mode+'-initial-fingerprint.json')).exists():assert a==read(PRIVATE/(mode+'-initial-fingerprint.json'))
        uuid=db.query('SELECT @@server_uuid')[0];other=restore.query('SELECT @@server_uuid')[0]
        assert uuid!=other and uuid!=prior['sourceServerUuid']
        save(PRIVATE/(mode+'-initial-fingerprint.json'),a)
        results[mode]={'status':'PASS_FRESH','backupSha256':sha(backup),'sourceProof':str(OLD/'final-current-backup-independent-restore.json'),
            'targetId':db.identity['container_id'],'targetUuid':uuid,'restoreId':restore.identity['container_id'],'restoreUuid':other,
            'tables':len(columns),'allColumnsRowsEqual':True,'columnsSha256':hashlib.sha256(json.dumps(columns,sort_keys=True).encode()).hexdigest(),
            'epoch':db.query('SELECT version,minimum_application_epoch,business_activation_ready+0 FROM tenant_schema_version')}
    save(RUN/'restore-proof.json',results)
    # Copy only fixed accepted assets/configuration into this run, then adapt all mutable paths.
    jar=Path(BASELINE['jar']['path']);assert sha(jar)==BASELINE['jar']['sha256'];shutil.copy2(jar,PRIVATE/'app.jar')
    for f in ['identities.json','ca.crt','server.crt','server.key','truststore.p12']:shutil.copy2(OLDHOME/'private'/f,PRIVATE/f)
    identity=read(PRIVATE/'identities.json')
    # Windows Java argument files and protoc require the verified ASCII alias.
    alias=Path('V:/');assert os.path.samefile(alias,ROOT), 'Map V: to this exact existing worktree before preparing'
    runtime=alias/PRIVATE.relative_to(ROOT)
    user='stage3_'+secrets.token_hex(4);secret=secrets.token_hex(24)
    db.sql("CREATE USER '"+user+"'@'%' IDENTIFIED BY '"+secret+"';",False)
    for mode,filename in [('REAL','application.properties'),('DEMO','demo.properties')]:
        database=spec['databases'][mode];db.sql("GRANT ALL PRIVILEGES ON "+dbtools.ident(database)+".* TO '"+user+"'@'%';",False)
        props={l.split('=',1)[0]:l.split('=',1)[1] for l in (OLDHOME/'private'/filename).read_text(encoding='utf-8').splitlines() if '=' in l and not l.startswith('#')}
        for k,v in list(props.items()):
            for old,new in [('33318',str(ports['mysql'])),('33630',str(ports['redis'])),('33631',str(ports['real'])),('33632',str(ports['quote'])),('33633',str(ports['demo'])),('8443',str(ports['https']))]:v=v.replace(old,new)
            props[k]=v
        props.update({'spring.datasource.url':'jdbc:mysql://127.0.0.1:'+str(ports['mysql'])+'/'+database+'?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=UTC',
            'spring.datasource.username':user,'spring.datasource.password':secret,'platform.bootstrap.enabled':'false',
            'tenant.sms.sink-directory':(runtime/(mode+'-sms')).as_posix(),'file.upload-dir':(runtime/(mode+'-uploads')).as_posix(),
            'control.retention.archive-directory':(runtime/(mode+'-archives')).as_posix(),
            'control.operations.failure-directory':(runtime/(mode+'-failures')).as_posix()})
        for suffix in ['sms','uploads','archives','failures']:dbtools.restrict_directory(PRIVATE/(mode+'-'+suffix))
        (PRIVATE/filename).write_text('\n'.join(k+'='+v for k,v in props.items())+'\n',encoding='utf-8')
        arguments=['-Duser.timezone=UTC','-Djavax.net.ssl.trustStore='+(runtime/'truststore.p12').as_posix(),'-Djavax.net.ssl.trustStorePassword='+identity['truststorePassword'],'-jar',(runtime/'app.jar').as_posix(),'--spring.config.additional-location='+ (runtime/filename).as_uri(),'--platform.bootstrap.enabled=false']
        (PRIVATE/(mode+'.args')).write_text('\n'.join(json.dumps(x,ensure_ascii=False) for x in arguments),encoding='utf-8')
    save(PRIVATE/'database-access.json',{'username':user,'password':secret})
    print('Stage3 isolated restore PASS; 92 tables per mode, independent IDs/UUIDs; bootstrap disabled')

def native(action):
    spec=read(RUN/'environment.json');owned=read(RUN/'resources-owned.json');now=inventory()
    for id,previous in owned.items():
        assert all(now[id][k]==previous[k] for k in ['imageId','labels','mounts','ports'])
    p=RUN/'processes.json';records=read(p) if p.exists() else {}
    if action=='start':
        assert not records,'Do not replace a running process record'
        for mode in ['REAL','DEMO']:
            args=['java','@'+str(PRIVATE/(mode+'.args'))]
            with (PRIVATE/(mode+'-service.log')).open('ab') as log:
                process=subprocess.Popen(args,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
            records[mode]={'pid':process.pid,'argsFile':str(PRIVATE/(mode+'.args')),'startedAt':time.time()};save(p,records)
        print('Owned REAL/DEMO processes started; readiness is a separate check')
    else:
        for role,item in records.items():
            script="$p=Get-CimInstance Win32_Process -Filter 'ProcessId="+str(item['pid'])+"';if($p){@{command=$p.CommandLine;started=([DateTimeOffset]$p.CreationDate).ToUnixTimeSeconds()}|ConvertTo-Json -Compress}"
            raw=command(['pwsh','-NoProfile','-Command',script]).decode('utf-8-sig').strip()
            if raw:
                live=json.loads(raw);assert item['argsFile'] in live['command'] and abs(live['started']-item['startedAt'])<15
                command(['pwsh','-NoProfile','-Command','Stop-Process -Id '+str(item['pid'])+' -ErrorAction Stop'])
        command(['docker','stop']+list(owned))
        after=inventory();before=read(RUN/'resources-before.json')
        save(RUN/'cleanup-proof.json',{'ownedStopped':all(not after[id]['running'] for id in owned),'volumesPreserved':all(after[id]['mounts']==owned[id]['mounts'] for id in owned),'unrelatedChanges':[id for id,v in before.items() if after.get(id)!=v],'processes':records})
        print('Owned services stopped; retained volumes and evidence')

if __name__=='__main__':
    if sys.argv[1]=='prepare':prepare()
    elif sys.argv[1]=='resume':restore_and_configure()
    else:native(sys.argv[1])
