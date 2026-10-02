"""Start this run's actual backend, TLS gateway and authenticated SMTP on loopback.
Processes are recorded with executable/arguments for ownership-checked shutdown.
"""
import argparse, json, os, subprocess, sys, time, urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--run-dir',type=Path,required=True)
args=parser.parse_args();home=args.run_dir.resolve();private=home/'private'
if ROOT/'reports' not in home.parents or not home.name.startswith('stage1-'): raise SystemExit('Explicit workspace stage1 run directory required')
spec=json.loads((home/'environment.json').read_text());ident=json.loads((private/'identities.json').read_text())
if (home/'processes.json').exists(): raise SystemExit('Existing process record: inspect ownership and stop before restarting; never reuse PID blindly')
sys.path.insert(0,str(ROOT/'scripts/multitenant'));import mysql_migration as migration
db=migration.Database(spec['containers']['mysql'],spec['database'])
bootstrap=db.query('SELECT COUNT(*) FROM control_admin')==['0']
jar=ROOT/'exchange-backend/target/exchange-backend-0.0.1-SNAPSHOT.jar'
if not jar.is_file(): raise SystemExit('Build current backend first')
java_args=['-Duser.timezone=UTC','-Djavax.net.ssl.trustStore='+str(private/'truststore.p12'),
           '-Djavax.net.ssl.trustStorePassword='+ident['truststorePassword'],
           '-jar',str(jar),'--spring.config.additional-location=file:'+str(private/'application.properties').replace('\\','/'),
           '--platform.bootstrap.enabled='+str(bootstrap).lower()]
# Java argfile is access-restricted; no password appears in process command, stdout or report.
(private/'java.args').write_text('\n'.join('"'+arg.replace('\\','\\\\').replace('"','\\"')+'"' for arg in java_args),encoding='utf-8')
commands={'gateway':['node',str(ROOT/'scripts/stage1/gateway.cjs'),str(home)],
          'smtp':[sys.executable,str(ROOT/'scripts/stage1/mailbox.py'),str(home)],
          'backend':['java','@'+str(private/'java.args')]}
records={};processes={};flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
for name,command in commands.items():
    output=(home/(name+'-service.log')).open('ab')
    process=subprocess.Popen(command,cwd=ROOT,stdout=output,stderr=subprocess.STDOUT,creationflags=flags)
    processes[name]=process
    output.close();records[name]={'pid':process.pid,'command':command,'startedAt':time.time(),'runDir':str(home)}
(home/'processes.json').write_text(json.dumps(records,indent=2),encoding='utf-8')
for _ in range(120):
    failed=[name for name,process in processes.items() if process.poll() is not None]
    if failed: raise SystemExit('Owned service exited before readiness: '+', '.join(failed)+'; inspect its preserved service log')
    try:
        with urllib.request.urlopen('http://127.0.0.1:'+str(spec['backendPort'])+'/healthz',timeout=2) as response:
            if response.status==200: print('Actual stage1 backend + local TLS/SMTP ready; identities remain private');sys.exit(0)
    except OSError: pass
    time.sleep(1)
raise SystemExit('Backend readiness timed out; inspect backend-service.log. Do not stop unrelated services.')
