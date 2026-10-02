"""Stop/start only a verified Phase2 backend, preserving gateway, SMTP, databases and evidence."""
import argparse,json,subprocess,sys,time,os,hashlib,shutil,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def save(p,data):
 q=p.with_suffix(p.suffix+'.tmp');q.write_text(json.dumps(data,indent=2),encoding='utf-8');os.replace(q,p)
def live(pid):
 script="$p=Get-CimInstance Win32_Process -Filter 'ProcessId="+str(int(pid))+"';if($p){@{pid=$p.ProcessId;command=$p.CommandLine;startedAt=([DateTimeOffset]$p.CreationDate).ToUnixTimeSeconds()}|ConvertTo-Json -Compress}"
 raw=subprocess.check_output(['pwsh','-NoProfile','-Command',script],text=True).strip();return json.loads(raw) if raw else None
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('action',choices=['stop','start']);parser.add_argument('--fixture',type=Path,required=True);parser.add_argument('--role',choices=['backend','demoBackend'],default='backend');args=parser.parse_args()
home=args.fixture.resolve();run=home.parent.parent
if ROOT/'reports' not in run.parents or not run.name.startswith('stage2-') or home.name!='stage1-'+run.name[7:]:raise SystemExit('Exact Phase2 owned fixture required')
spec=json.loads((home/'environment.json').read_text());assert spec['run']==home.name
p=home/'processes.json';raw=p.read_bytes();records=json.loads(raw);owned=records[args.role];assert owned['runDir']==str(home)
argumentfile=home/('private/demo-java.args' if args.role=='demoBackend' else 'private/java.args')
if args.role=='demoBackend':assert spec['demoDatabase'].endswith('_demo') and spec['demoDatabase']!=spec['database']
actual=live(owned['pid']);stamp=time.strftime('%Y%m%d-%H%M%S');record=run/'logs'/(args.role+'-process-'+args.action+'-'+stamp+'.json')
if record.exists():raise RuntimeError('Choose a fresh attempt; preserve evidence')
shutil.copy2(p,home/('processes-before-'+args.role+'-'+args.action+'-'+stamp+'.json'))
if args.action=='stop':
 if not actual or owned.get('status')=='STOPPED' or str(argumentfile) not in actual['command'] or abs(actual['startedAt']-owned['startedAt'])>10:raise RuntimeError('Backend PID/time/command ownership mismatch')
 subprocess.run(['pwsh','-NoProfile','-Command','Stop-Process -Id '+str(int(owned['pid']))+' -ErrorAction Stop'],check=True)
 for _ in range(30):
  if not live(owned['pid']):break
  time.sleep(.2)
 else:raise RuntimeError('Owned backend did not stop')
 owned.update(status='STOPPED',stoppedAt=time.time());assert p.read_bytes()==raw;save(p,records);save(record,{'result':'PASS','owned':owned,'databaseReset':False});print('Exact owned backend stopped; no other resource changed')
else:
 if actual or owned.get('status')!='STOPPED':raise RuntimeError('Verified backend-only stop record required')
 old=argumentfile.read_bytes();shutil.copy2(argumentfile,argumentfile.with_name('java-before-restart-'+stamp+'.args'))
 # Existing accounts remain authoritative; restart must never re-enable bootstrap.
 text=old.decode('utf-8').replace('--platform.bootstrap.enabled=true','--platform.bootstrap.enabled=false');temporary=argumentfile.with_suffix('.restart.tmp');temporary.write_text(text,encoding='utf-8');assert argumentfile.read_bytes()==old;os.replace(temporary,argumentfile)
 output=(home/(args.role+'-service.log')).open('ab');command=['java','@'+str(argumentfile)];process=subprocess.Popen(command,cwd=ROOT,stdout=output,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW);output.close()
 records[args.role]={'pid':process.pid,'command':command,'startedAt':time.time(),'runDir':str(home),'status':'RUNNING'};assert p.read_bytes()==raw;save(p,records)
 for _ in range(120):
  if process.poll() is not None:raise RuntimeError('Owned current backend exited; preserve service log')
  try:
   with urllib.request.urlopen('http://127.0.0.1:'+str(spec['demoBackendPort'] if args.role=='demoBackend' else spec['backendPort'])+'/healthz',timeout=2) as reply:
    if reply.status==200 and reply.read()==b'ok':break
  except OSError:pass
  time.sleep(1)
 else:raise RuntimeError('Owned current backend readiness timed out')
 jar=ROOT/'exchange-backend/target/exchange-backend-0.0.1-SNAPSHOT.jar';save(record,{'result':'PASS','owned':records[args.role],'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'databaseReset':False});print('Exact owned current backend restarted; data and gateway retained')
