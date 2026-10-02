"""Stage3/4 local recovery gap only; new owned MySQL resources, no application or stage5 run."""
import datetime as dt
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import sys
import time
import rehearse as h
import forward_recovery as recovery

c=h.controlled;core=h.core
ROOT=Path(__file__).resolve().parents[2]
RUN='stage34-integration-20261003'
OUT=ROOT/'reports'/RUN
PRIVATE=OUT/'private'
STATE=OUT/'run.json'
LABEL='com.gtcfesk.stage34.run'
h.RUN=RUN;h.ROOT=ROOT;h.OUT=OUT;h.PRIVATE=PRIVATE;h.STATE=STATE;h.LABEL=LABEL


def sign(policy,proposal,proof,review=False):
    # Local synthetic fixture signatures only. No production policy/approval is created.
    payload={'binding':c.binding(proposal,proof),'scope':'isolated-fixture',
             'expires_at':(c.now()+dt.timedelta(hours=4)).isoformat(),
             'stopped_writers':['dedicated source: no app services; actual read_only/processlist checked'],
             'recovery_owner':'stage34-local-fixture','not_a_production_approval':True}
    if review:
        payload['recovery_review']={'evidence_sha256':c.digest(proposal['recovery']),'decision':'FORWARD_ONLY',
            'reason':'Exact signed independent before/after DDL and all-row comparison; newest backup independently restored; retain failed ledger and skip committed phase 0.'}
    return {'payload':payload,'signatures':[{'signer':n,'sha256':hmac.new(policy.key(n),c.canonical(payload),hashlib.sha256).hexdigest()} for n in ['operator','approver']]}


def initial(manifest):
    if STATE.exists():raise ValueError('Existing run preserved; never recreate resources')
    core.restrict_directory(PRIVATE)
    legacy=h.read(manifest)['inputs']['legacy'];assert h.sha(legacy['path'])==legacy['sha256']
    old=h.read(OUT/'input-review.json')['protectedResources']
    network='mt705-'+RUN
    assert h.command(['docker','network','inspect',network],check=False).returncode!=0
    h.command(['docker','network','create','--label',LABEL+'='+RUN,network])
    s={'run':RUN,'network':network,'resources':{},'inputs':{'legacy':legacy},'protected':old}
    h.save(STATE,s)
    image='mysql:5.7'
    assert json.loads(h.command(['docker','image','inspect',image]).stdout)[0]['Id']=='sha256:4bc6bc963e6d8443453676cae56536f4b8156d78bae03c0145cbe47c2aad73bb'
    for kind in ['source','restore']:
        name=network+'-'+kind;volume=name+'-data'
        assert h.command(['docker','inspect',name],check=False).returncode!=0
        assert h.command(['docker','volume','inspect',volume],check=False).returncode!=0
        h.command(['docker','volume','create','--label',LABEL+'='+RUN,volume])
        env=os.environ.copy();env['MYSQL_ROOT_PASSWORD']=secrets.token_hex(32)
        args=['docker','run','-d','--pull=never','--name',name,'--network',network,
              '--label',LABEL+'='+RUN,'--label','com.gtcfesk.multitenant.test=true',
              '-p','127.0.0.1::3306','--env','MYSQL_ROOT_PASSWORD','--mount',
              f'type=volume,source={volume},target=/var/lib/mysql',image,
              '--datadir=/var/lib/mysql/'+kind,'--character-set-server=utf8mb4','--collation-server=utf8mb4_unicode_ci']
        h.command(args,env=env);s[kind]=name;s['resources'][name]=h.snapshot(name);h.save(STATE,s)
        deadline=time.monotonic()+150
        while time.monotonic()<deadline:
            result=h.command(['docker','exec',name,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT 1"'],check=False)
            if result.returncode==0:break
            time.sleep(1)
        else:raise RuntimeError('Owned MySQL not ready')
    print('NEW_SOURCE_RESTORE_READY',flush=True)


def run(attempt='recovery'):
    core.ident(attempt)
    home=PRIVATE/attempt;home.mkdir()
    keys={}
    for name in ['operator','approver','journal']:
        path=home/(name+'.key');path.write_bytes(secrets.token_bytes(48));keys[name]={'role':name,'key_file':str(path)}
    c.publish(home/'fixture-policy.json',{'scope':'isolated-fixture','keys':keys,'journal_key':'journal'})
    policy=c.Policy(home/'fixture-policy.json',True)
    result={'status':'INCOMPLETE','scope':'local historical synthetic fixture only','checks':[],
            'stage5Executed':False,'productionValidated':False,'sourceOverwritten':False}
    def passed(name,**detail):
        result['checks'].append({'name':name,'status':'PASS_FRESH',**detail});h.save(OUT/'recovery-progress.json',result);print('PASS '+name,flush=True)
    def rejects(name,call,contains):
        try:call()
        except ValueError as error:
            assert contains in str(error),(name,str(error));passed(name,expectedRejection=str(error));return
        raise AssertionError(name+' was not rejected')
    try:
        source=h.db('source','mt705_s34_uncertain');reference=h.db('restore','mt705_s34_reference')
        legacy=h.read(STATE)['inputs']['legacy'];assert h.sha(legacy['path'])==legacy['sha256']
        for database in [source,reference]:
            database.create_empty();database.sql(Path(legacy['path']).read_text(encoding='utf-8'))
            database.sql('SET GLOBAL read_only=1',False)
        original=c.plan(source,home/'original-plan.json');refplan=c.plan(reference,home/'reference-plan.json')
        assert original['initial']==refplan['initial']
        backup_target=h.db('restore','mt705_restore_s34_original')
        proof=c.verify_backup(source,original,backup_target,home/'original-proof.json')
        approval=sign(policy,original,proof);c.publish(home/'original-fixture-approval.json',approval)
        failed=c.Ledger(home/'failed-ledger',policy)
        original_sql=source.sql
        def lost(sql,*args,**kwargs):
            output=original_sql(sql,*args,**kwargs)
            if sql==core.MIGRATIONS[0].read_text(encoding='utf-8'):
                raise RuntimeError('fixture lost acknowledgement AFTER actual MySQL commit')
            return output
        source.sql=lost
        try:c.apply(source,original,proof,backup_target,approval,failed)
        except RuntimeError as error:assert 'AFTER actual MySQL commit' in str(error)
        finally:source.sql=original_sql
        assert failed.latest()['kind']=='FAILED_UNCERTAIN'
        retained={p.name:h.sha(p) for p in failed.directory.glob('*.json')}
        passed('actual committed DDL lost acknowledgement retained',index=failed.latest()['index'],failedLedgerHashes=retained)
        rejects('old failed plan cannot resume',lambda:c.apply(source,original,proof,backup_target,approval,failed,True),'Uncertain/incomplete')
        witness=c.Ledger(home/'reference-ledger',policy)
        refrestore=h.db('source','mt705_restore_s34_reference')
        refproof=c.verify_backup(reference,refplan,refrestore,home/'reference-proof.json')
        refapproval=sign(policy,refplan,refproof);c.publish(home/'reference-fixture-approval.json',refapproval)
        class StopBoundary(Exception):pass
        def stop(index):
            assert index==1;raise StopBoundary()
        try:c.apply(reference,refplan,refproof,refrestore,refapproval,witness,after_phase=stop)
        except StopBoundary:pass
        assert witness.latest()['kind']=='PHASE_COMPLETE' and witness.latest()['next']==1
        passed('independent exact before-state current-DDL execution supplies signed phase-1 witness')
        rec=recovery.reconcile(source,original,failed,refplan,witness,home/'reconciliation.json')
        candidate=rec['proposal'];fresh=h.db('restore','mt705_restore_s34_newest')
        newest=c.verify_backup(source,candidate,fresh,home/'newest-proof.json')
        passed('actual full state reconciled and newest backup independently restored',
               schemaSha256=candidate['initial']['schema']['sha256'],stateSha256=c.digest(candidate['initial']),
               backupSha256=newest['backup']['sha256'],source=newest['source'],restore=newest['restore'])
        unreviewed=sign(policy,candidate,newest)
        rejects('ordinary signed approval without explicit recovery review refused',
                lambda:recovery.forward_plan(source,rec,newest,fresh,unreviewed,policy,home/'should-not-exist.json'),'Explicit signed')
        rejects('direct apply of recovery candidate cannot bypass review',
                lambda:c.apply(source,candidate,newest,fresh,unreviewed,c.Ledger(home/'bypass-ledger',policy)),'Explicit signed')
        approved=sign(policy,candidate,newest,True);c.publish(home/'reviewed-fixture-approval.json',approved)
        tampered={**approved,'payload':{**approved['payload'],'recovery_review':{'decision':'FORWARD_ONLY'}}}
        rejects('tampered review signature refused',lambda:recovery.forward_plan(source,rec,newest,fresh,tampered,policy,home/'should-not-exist.json'),'signature invalid')
        forward=recovery.forward_plan(source,rec,newest,fresh,approved,policy,home/'forward-plan.json')
        assert forward['id']!=original['id'] and forward['start']==1
        passed('explicit signed review publishes new bound forward plan',originalPlan=c.digest(original),forwardPlan=c.digest(forward),start=forward['start'])
        ledger=c.Ledger(home/'forward-ledger',policy)
        completed=c.apply(source,forward,newest,fresh,approved,ledger)
        phases=[r['index'] for r in ledger.rows() if r['kind']=='INTENT']
        assert phases==list(range(1,len(core.MIGRATIONS))) and completed['kind']=='COMPLETE'
        assert retained=={p.name:h.sha(p) for p in failed.directory.glob('*.json')}
        assert c.digest(c.preserved(source,original['preservation_columns']))==original['preserved_sha256']
        assert not source.query('SELECT version FROM tenant_schema_version WHERE business_activation_ready<>0')
        passed('forward-only completion with original failure unchanged; committed DDL not replayed',
               sourceDdlIndices=[0]+phases,originalBusinessFactsPreserved=True,activationReady=False)
        rejects('changed actual state cannot reuse old reconciliation',lambda:recovery.reconcile(source,original,failed,refplan,witness,home/'should-not-exist.json'),'Actual full DDL/rows differ')
        rejects('completed forward plan cannot replay',lambda:c.apply(source,forward,newest,fresh,approved,ledger,True),'Uncertain/incomplete')
        # New backup of completed target; restore ONLY into a fresh independent database.
        finalbackup=source.dump(home/'final.sql');derived=c.restore_input(source,finalbackup,home/'final-restore-input.sql')
        final=h.db('restore','mt705_restore_s34_final');final.create_empty();final.sql(Path(derived['path']).read_text(encoding='utf-8'))
        assert c.state(final)==c.state(source)
        relations=h.relation_counts(source);assert not any(relations.values())
        passed('completed full DDL/all-column data independently restored',stateSha256=c.digest(c.state(source)),
               backupSha256=finalbackup['sha256'],declaredRelations=len(relations))
        result.update(status='PASS_FRESH',forwardPlanSha256=c.digest(forward),finalLedgerTip=c.digest(ledger.latest()),
                      failedLedgerUnchanged=True,legacyBackupSha256=legacy['sha256'])
    except BaseException as error:
        result.update(status='FAIL',failureType=type(error).__name__,failure=str(error));raise
    finally:
        h.save(OUT/'recovery-drill.json',result)


def cleanup(stop_services=True):
    s=h.read(STATE);before=[];after=[]
    for name in s['resources']:
        before.append(h.own(name))
        if stop_services:h.command(['docker','stop',name])
        after.append(h.own(name));assert after[-1]['status']=='exited'
    changes=[]
    actuals={x['Id']:x for x in json.loads(h.command(['docker','inspect',*[x['id'] for x in s['protected']]]).stdout)}
    for old in s['protected']:
        actual=actuals[old['id']]
        new={'id':actual['Id'],'name':actual['Name'],'status':actual['State']['Status'],
             'startedAt':actual['State']['StartedAt'],'image':actual['Image'],'mounts':h.normalized_mounts(actual['Mounts']),
             'labels':actual['Config'].get('Labels'),'ports':actual['HostConfig']['PortBindings']}
        if new!={**old,'mounts':h.normalized_mounts(old['mounts'])}:changes.append(old['id'])
    assert not changes,changes
    volumes=[]
    for row in after:
        for mount in row['mounts']:
            volume=json.loads(h.command(['docker','volume','inspect',mount['Name']]).stdout)[0]
            assert volume['Labels'].get(LABEL)==RUN
            bound=h.command(['docker','ps','-aq','--no-trunc','--filter','volume='+mount['Name']]).stdout.decode().split()
            assert bound==[row['id']]
            volumes.append({'name':mount['Name'],'labels':volume['Labels'],'exclusiveContainer':row['id']})
    h.save(OUT/'cleanup.json',{'status':'PASS','before':before,'after':after,'volumes':volumes,
                             'protectedResourcesUnchanged':len(s['protected']),'backupsEvidenceVolumesRetained':True,
                             'mountsNormalizedWithInheritedHelper':True,'checkOnly':not stop_services})
    print('OWNED_MYSQL_STOPPED; prior resources unchanged; evidence and volumes retained',flush=True)


if __name__=='__main__':
    action=sys.argv[1]
    if action=='initial':initial(Path(sys.argv[2]))
    elif action=='run':run(sys.argv[2] if len(sys.argv)>2 else 'recovery')
    elif action=='cleanup':cleanup()
    elif action=='check-cleanup':cleanup(False)
    else:raise ValueError('Unknown action')
