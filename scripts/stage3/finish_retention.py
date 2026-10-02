"""Resume only the failed cleanup check; preserve completed independent restores."""
import json, shutil, subprocess, sys, time, urllib.request
import environment as e
from acceptance import Acceptance
import retention

def restart():
    records=e.read(e.RUN/'processes.json');e.save(e.PRIVATE/'processes-before-retention-fix.json',records)
    jar=e.PRIVATE/'app-retention-v2.jar';assert not jar.exists()
    shutil.copy2(e.ROOT/'exchange-backend/target/exchange-backend-0.0.1-SNAPSHOT.jar',jar)
    for mode in ['REAL','DEMO']:
        item=records[mode]
        script="$p=Get-CimInstance Win32_Process -Filter 'ProcessId="+str(item['pid'])+"';@{command=$p.CommandLine;started=([DateTimeOffset]$p.CreationDate).ToUnixTimeSeconds()}|ConvertTo-Json -Compress"
        live=json.loads(e.command(['pwsh','-NoProfile','-Command',script]).decode('utf-8-sig'))
        assert item['argsFile'] in live['command'] and abs(live['started']-item['startedAt'])<15
        e.command(['pwsh','-NoProfile','-Command','Stop-Process -Id '+str(item['pid'])+' -ErrorAction Stop'])
        path=e.Path(item['argsFile']);shutil.copy2(path,e.PRIVATE/(mode+'-before-retention-fix.args'))
        temporary=path.with_suffix('.tmp');temporary.write_text(path.read_text(encoding='utf-8').replace('/app.jar','/app-retention-v2.jar'),encoding='utf-8');temporary.replace(path)
        with (e.PRIVATE/(mode+'-retention-fixed.log')).open('ab') as log:
            p=subprocess.Popen(['java','@'+str(path)],cwd=e.ROOT,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
        records[mode]={'pid':p.pid,'argsFile':str(path),'startedAt':time.time()};e.save(e.RUN/'processes.json',records)
    spec=e.read(e.RUN/'environment.json')
    for mode,port in [('REAL',spec['ports']['real']),('DEMO',spec['ports']['demo'])]:
        deadline=time.monotonic()+100
        while time.monotonic()<deadline:
            try:
                with urllib.request.urlopen('http://127.0.0.1:'+str(port)+'/healthz',timeout=2) as r:
                    if r.status==200:break
            except OSError:time.sleep(.5)
        else:raise RuntimeError(mode+' not ready')
    e.save(e.RUN/'retention-fixed-runtime.json',{'jarSha256':e.sha(jar),'processes':records,'healthz':{'REAL':200,'DEMO':200},'bootstrap':False})

def cleanup(a):
    t=a.state['tenants']['A'];tenant=t['id'];cid=a.state['retentionConversation'];db=a.db['REAL'];prefix=f'/tenants/{tenant}'
    original=db.query(f'SELECT * FROM support_conversation WHERE id={t["conversationId"]}')
    job=a.control(prefix+'/support/archive-jobs/'+a.state['archiveJob'])['data'];assert job['state']=='COMPLETE'
    assert int(db.query(f'SELECT COUNT(*) FROM support_message WHERE conversation_id={cid}')[0])==job['processed_messages']
    assert db.query(f'SELECT COUNT(*) FROM support_conversation WHERE id={cid}')==['1']
    proofs=[]
    for name in ['v1','v2']:
        files=list(e.PRIVATE.glob('archive-restore-'+name+'-*/restore-report.json'));assert len(files)==1
        proof=e.read(files[0]);assert all(proof['checks'][k] for k in ['rows','tenantRelations','messageChain','lastHash','attachmentBytes','schema'])
        proofs.append({'path':str(files[0]),'sha256':e.sha(files[0]),'checks':proof['checks'],'format':name})
    preview=a.control(prefix+'/retention')['data'];assert preview['autoDeleteEnabled'] and cid in preview['candidateIds']
    body={'confirm':True,'policyVersion':preview['policyVersion'],'previewHash':preview['previewHash'],'reason':'Stage3 cleanup after JDBC timestamp canonicalization fix'}
    result=a.control(prefix+'/retention/clean','POST',body);assert result['data']==1
    assert db.query(f'SELECT COUNT(*) FROM support_conversation WHERE id={cid}')==['0']
    assert db.query(f'SELECT COUNT(*) FROM support_message WHERE conversation_id={cid}')==['0']
    preview=a.control(prefix+'/retention')['data'];body.update(policyVersion=preview['policyVersion'],previewHash=preview['previewHash']);assert a.control(prefix+'/retention/clean','POST',body)['data']==0
    a.control(prefix+'/retention','PUT',{'enabled':False,'reason':'Stage3 rehearsal complete; leave deletion disabled'})
    assert db.query(f'SELECT * FROM support_conversation WHERE id={t["conversationId"]}')==original
    files=list((e.PRIVATE/'REAL-archives'/f'tenant-{tenant}').glob(str(cid)+'-*.json'))
    checks=[{'sha256':e.sha(f),'verification':retention.archive.verify(f)} for f in files]
    return {'conversationId':cid,'failedCleanupPreservedRows':True,'deletedOwnedAgedCopy':1,'repeatDelete':0,'originalConversationPreserved':True,'disabledAtEnd':True,'independentRestores':proofs,'actualV1Archives':checks,'jdbcLocalDateTimePathAcceptedByActualBackend':True}

if __name__=='__main__':
    if sys.argv[1]=='restart':restart()
    else:
        a=Acceptance();a.check('retention-cleanup-after-fix',lambda:cleanup(a))
