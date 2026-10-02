"""Dedicated browser container; reuse immutable installed image, not old runtime."""
import json,re,subprocess,sys,time
import environment as e
owned=e.read(e.RUN/'resources-owned.json');now=e.inventory()
assert all(now[id]==row for id,row in owned.items())
records=e.read(e.RUN/'processes.json')
for item in records.values():
    script="$p=Get-CimInstance Win32_Process -Filter 'ProcessId="+str(item['pid'])+"';@{command=$p.CommandLine;started=([DateTimeOffset]$p.CreationDate).ToUnixTimeSeconds()}|ConvertTo-Json -Compress"
    live=json.loads(e.command(['pwsh','-NoProfile','-Command',script]).decode('utf-8-sig'))
    assert item['argsFile'] in live['command'] and abs(live['started']-item['startedAt'])<15
image='sha256:54afa3c997046ff287711fe8bca9a06a27cfdddf9c4b880b363c9faa4a5290a6'
details=json.loads(e.command(['docker','image','inspect',image]))[0]
assert details['Id']==image and details['Config']['Labels']['com.gtcfesk.stage1.role']=='phase2-browser-fixture'
profile=e.Path('C:/Users/徐乾妖/AppData/Local/Temp/codex-mt705-stage2-retest/01a0fc66-736d-7b62-89ac-b5c0bb9b19a0/browser/seccomp.json')
assert e.sha(profile)=='cc3e61cabda6bbc1e53e54d27ba4d55a9d3be829b6dd1a596f4a7b31b1cc7849'
spec=e.read(e.RUN/'environment.json');dns=e.command(['docker','exec',spec['containers']['redis'],'nslookup','host.docker.internal']).decode()
ip=re.findall(r'^Address:\s+((?:[0-9]{1,3}\.){3}[0-9]{1,3})\s*$',dns,re.M)[0]
attempt=str(int(time.time()));name=spec['run']+'-browser-'+attempt
args=['docker','run','--pull=never','--init','--name',name,'--label','com.gtcfesk.stage3.run='+spec['run'],'--label','com.gtcfesk.stage3.role=browser','--network',spec['run']+'_default','--shm-size=256m','--memory=1g','--cpus=2','--security-opt','seccomp='+str(profile),'--mount','type=bind,source='+str(e.ROOT/'scripts/stage3')+',target=/workspace/705/scripts/stage3,readonly','--mount','type=bind,source='+str(e.RUN)+',target=/workspace/705/reports/stage3-20261002-232106','-e','STAGE3_HOST_IP='+ip,'-e','STAGE3_CA_SHA256='+e.sha(e.PRIVATE/'ca.crt'),'--entrypoint','/bin/sh',image,'/workspace/705/scripts/stage3/browser-entrypoint.sh']+sys.argv[1:]
with (e.PRIVATE/('browser-linux-'+attempt+'.log')).open('wb') as log:p=subprocess.run(args,stdout=log,stderr=subprocess.STDOUT)
actual=e.inventory();record=next(v for v in actual.values() if v['name']=='/'+name)
e.save(e.RUN/('browser-command-'+attempt+'.json'),{'arguments':args,'exitCode':p.returncode,'container':record,'immutableImageReused':image,'caSha256':e.sha(e.PRIVATE/'ca.crt'),'windowsTrustUnchanged':True})
print('Browser exit',p.returncode,'attempt',attempt);print((e.PRIVATE/('browser-linux-'+attempt+'.log')).read_text(encoding='utf-8')[-1600:]);sys.exit(p.returncode)
