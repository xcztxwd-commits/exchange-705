"""Stage 5 delta acceptance only. Local owned resources; no migration or release flag changes."""
import concurrent.futures as futures, datetime as dt, hashlib, http.client, json, os, secrets, shutil, socket, ssl, statistics, subprocess, sys, threading, time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'scripts/stage3'),str(ROOT/'scripts/multitenant'),str(ROOT/'scripts/stage1')]
import environment as e
from api_smoke import PinnedTLS,totp
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
import bcrypt,pymysql
RUN=ROOT/'reports/stage5-final';PRIVATE=RUN/'private';NAME='mt705-stage5-final-20261003'
read=e.read;save=e.save;sha=e.sha;dbtools=e.dbtools
PORTS=dict(mysql=33918,redis=33930,real=33931,quote=33932,demo=33933,https=8643)
HOSTS=['default.localhost','a.localhost','a2.localhost','b.localhost','admin.localhost','control.localhost']+[f's5-{i}.localhost' for i in range(10)]

def command(args,data=None):
 p=subprocess.run(args,input=data,capture_output=True)
 RUN.mkdir(exist_ok=True,parents=True)
 with (RUN/'commands.jsonl').open('a',encoding='utf-8') as f:f.write(json.dumps({'args':args,'exitCode':p.returncode,'at':dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).isoformat(),'stdoutSha256':hashlib.sha256(p.stdout).hexdigest(),'stderrSha256':hashlib.sha256(p.stderr).hexdigest()},ensure_ascii=False)+'\n')
 if p.returncode:raise RuntimeError(args[0]+' exit '+str(p.returncode))
 return p.stdout

def certs():
 key=rsa.generate_private_key(public_exponent=65537,key_size=2048);name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Stage5 isolated fixture')]);now=dt.datetime.now(dt.timezone.utc)
 cert=x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-dt.timedelta(minutes=5)).not_valid_after(now+dt.timedelta(days=7)).add_extension(x509.BasicConstraints(ca=True,path_length=None),critical=True).add_extension(x509.SubjectAlternativeName([x509.DNSName(h) for h in HOSTS]),critical=False).sign(key,hashes.SHA256())
 (PRIVATE/'server.key').write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
 for f in ['server.crt','ca.crt']:(PRIVATE/f).write_bytes(cert.public_bytes(serialization.Encoding.PEM))
 command(['keytool','-importcert','-noprompt','-alias','stage5','-file',str(PRIVATE/'ca.crt'),'-keystore',str(PRIVATE/'truststore.p12'),'-storepass','stage5-local-trust','-storetype','PKCS12'])

def prepare():
 assert command(['git','rev-parse','HEAD']).decode().strip()=='2e6c4cb1accf9717518bbc0ea85713add41788c5'
 assert not (RUN/'environment.json').exists(),'Never overwrite previous run'
 dbtools.restrict_directory(PRIVATE)
 for port in PORTS.values():
  with socket.socket() as s:s.bind(('127.0.0.1',port))
 before=e.inventory();save(RUN/'resources-before.json',before)
 evidence=read(ROOT/'reports/stage34-integration-20261003/evidence.json');s3=Path(evidence['stage3Evidence']['path']).parent
 jar=s3.parents[1]/'exchange-backend/target/exchange-backend-0.0.1-SNAPSHOT.jar'
 assert sha(jar)==evidence['reused']['stage3ApplicationBuild']['finalJarSha256'];shutil.copy2(jar,PRIVATE/'app.jar')
 certs();shutil.copy2(s3/'private/identities.json',PRIVATE/'identities.json');shutil.copy2(s3/'private/stage3-state.json',PRIVATE/'prior-state.json')
 labels={'com.gtcfesk.multitenant.test':'true','com.gtcfesk.stage5.run':NAME};password=secrets.token_hex(32)
 (PRIVATE/'compose.env').write_text('S5_MYSQL_PASSWORD='+password+'\n',encoding='utf-8')
 services={r:{'image':'mysql:5.7','container_name':NAME+'-'+r,'labels':labels,'environment':{'MYSQL_ROOT_PASSWORD':'${S5_MYSQL_PASSWORD:?required}'},'command':['--character-set-server=utf8mb4','--collation-server=utf8mb4_unicode_ci','--sql-mode=STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION'],'volumes':[r+'-data:/var/lib/mysql'],'restart':'no'} for r in ['mysql','restore']}
 services['mysql']['ports']=[f'127.0.0.1:{PORTS["mysql"]}:3306']
 services['redis']={'image':'redis:6.2-alpine','container_name':NAME+'-redis','labels':labels,'ports':[f'127.0.0.1:{PORTS["redis"]}:6379'],'command':['redis-server','--appendonly','yes'],'volumes':['redis-data:/data'],'restart':'no'}
 save(RUN/'compose.json',{'name':NAME,'services':services,'volumes':{r+'-data':{} for r in services}})
 spec={'run':NAME,'ports':PORTS,'containers':{r:NAME+'-'+r for r in services},'databases':{'REAL':'mt705_stage5_real','DEMO':'mt705_stage5_demo'}};save(RUN/'environment.json',spec)
 command(['docker','compose','--env-file',str(PRIVATE/'compose.env'),'-f',str(RUN/'compose.json'),'up','-d'])
 own={k:v for k,v in e.inventory().items() if v['labels'].get('com.gtcfesk.stage5.run')==NAME};assert len(own)==3 and not set(own)&set(before);save(RUN/'resources-owned.json',own)
 for role in ['mysql','restore']:
  db=dbtools.Database(spec['containers'][role],spec['databases']['REAL'])
  for _ in range(90):
   if db.sql('SELECT 1',False,False).returncode==0:break
   time.sleep(1)
  else:raise RuntimeError('Owned MySQL readiness timeout')
 proof=read(e.OLD/'final-current-backup-independent-restore.json');initial={}
 for mode,prior in proof['databases'].items():
  backup=Path(prior['backup']);assert sha(backup)==prior['backupSha256'];db=dbtools.Database(spec['containers']['mysql'],spec['databases'][mode]);db.create_empty();db.sql(backup.read_text(encoding='utf-8'))
  cols={t:[c[0] for c in fields] for t,fields in db.columns().items()};finger=dbtools.fingerprint(db,cols);save(PRIVATE/(mode+'-initial.json'),finger)
  initial[mode]={'backupSha256':sha(backup),'tables':len(cols),'rows':sum(t['rows'] for t in finger['tables'].values()),'epoch':db.query('SELECT version,minimum_application_epoch,business_activation_ready+0 FROM tenant_schema_version')}
 save(RUN/'initial-restore.json',{'sourceProof':str(e.OLD/'final-current-backup-independent-restore.json'),'modes':initial,'jarSha256':sha(PRIVATE/'app.jar'),'sourceCommit':'2e6c4cb1accf9717518bbc0ea85713add41788c5'})
 configure()

def configure():
 spec=read(RUN/'environment.json');evidence=read(ROOT/'reports/stage34-integration-20261003/evidence.json');s3=Path(evidence['stage3Evidence']['path']).parent;db=dbtools.Database(spec['containers']['mysql'],spec['databases']['REAL'])
 user='s5_'+secrets.token_hex(4);secret=secrets.token_hex(24);db.sql('CREATE USER '+"'"+user+"'@'%' IDENTIFIED BY '"+secret+"'",False)
 for mode,filename in [('REAL','application.properties'),('DEMO','demo.properties')]:
  db.sql('GRANT ALL PRIVILEGES ON '+dbtools.ident(spec['databases'][mode])+'.* TO '+"'"+user+"'@'%'",False)
  props=dict(l.split('=',1) for l in (s3/'private'/filename).read_text(encoding='utf-8').splitlines() if '=' in l and not l.startswith('#'))
  old=read(s3/'environment.json')['ports']
  for k,v in list(props.items()):
   for role,port in old.items():v=v.replace(str(port),str(PORTS[role]))
   props[k]=v
  props.update({'spring.datasource.url':f'jdbc:mysql://127.0.0.1:{PORTS["mysql"]}/{spec["databases"][mode]}?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=UTC','spring.datasource.username':user,'spring.datasource.password':secret,'platform.bootstrap.enabled':'false','platform.outbound.local-loopback-endpoints':','.join(h+':8643' for h in HOSTS)+',smtp.localhost:465','spring.jpa.show-sql':'false'})
  for k,suffix in [('tenant.sms.sink-directory','sms'),('file.upload-dir','uploads'),('control.retention.archive-directory','archives'),('control.operations.failure-directory','failures')]:
   path=PRIVATE/(mode+'-'+suffix);dbtools.restrict_directory(path);props[k]=path.relative_to(ROOT).as_posix()
  (PRIVATE/filename).write_text('\n'.join(k+'='+v for k,v in props.items())+'\n',encoding='utf-8')
 save(PRIVATE/'db-access.json',{'username':user,'password':secret})
 print('CONFIGURE PASS',flush=True)

def start(role):
 records=read(RUN/'processes.json') if (RUN/'processes.json').exists() else {}
 assert role not in records or records[role].get('stopped')
 if role=='gateway':args=['node',str(ROOT/'scripts/stage5/gateway.cjs'),str(RUN)]
 else:
  filename='application.properties' if role=='REAL' else 'demo.properties'
  args=['java','-Duser.timezone=UTC','-Djavax.net.ssl.trustStore='+str(PRIVATE/'truststore.p12'),'-Djavax.net.ssl.trustStorePassword=stage5-local-trust','-jar',str(PRIVATE/'app.jar'),'--spring.config.additional-location='+(PRIVATE/filename).as_uri(),'--platform.bootstrap.enabled=false']
 with (PRIVATE/(role+'-service.log')).open('ab') as log:p=subprocess.Popen(args,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
 records[role]={'pid':p.pid,'startedAt':time.time(),'marker':str(RUN) if role=='gateway' else str(PRIVATE/'app.jar'),'stopped':False};save(RUN/'processes.json',records)
 if role!='gateway':
  for _ in range(150):
   try:
    c=http.client.HTTPConnection('127.0.0.1',PORTS[role.lower()],timeout=2);c.request('GET','/healthz');r=c.getresponse();ok=r.status==200 and r.read()==b'ok';c.close()
    if ok:print(role+' READY',flush=True);return
   except OSError:pass
   if p.poll() is not None:raise RuntimeError(role+' exited; preserve private log')
   time.sleep(1)
  raise RuntimeError(role+' readiness deadline')

def stop(role):
 records=read(RUN/'processes.json');item=records[role]
 if item.get('stopped'):return
 script="$p=Get-CimInstance Win32_Process -Filter 'ProcessId="+str(item['pid'])+"';if($p){@{command=$p.CommandLine;started=([DateTimeOffset]$p.CreationDate).ToUnixTimeSeconds()}|ConvertTo-Json -Compress}"
 raw=command(['pwsh','-NoProfile','-Command',script]).decode('utf-8-sig').strip()
 if raw:
  live=json.loads(raw);assert item['marker'] in live['command'] and abs(live['started']-item['startedAt'])<15
  command(['pwsh','-NoProfile','-Command','Stop-Process -Id '+str(item['pid'])+' -ErrorAction Stop'])
 item['stopped']=True;save(RUN/'processes.json',records)

if __name__=='__main__':
 if sys.argv[1]=='prepare':prepare()
 elif sys.argv[1]=='configure':configure()
 elif sys.argv[1]=='start':start(sys.argv[2])
 elif sys.argv[1]=='stop':stop(sys.argv[2])
