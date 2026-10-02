"""Targeted current-DDL rehearsal from a hash-verified historical non-scoped fixture."""
import datetime as dt, hashlib,hmac,json,secrets,sys
from pathlib import Path
from rehearse import *

class BoundaryStop(Exception): pass

def sign(policy,proposal,proof):
    payload={'binding':controlled.binding(proposal,proof),'scope':'isolated-fixture','expires_at':(controlled.now()+dt.timedelta(hours=6)).isoformat(),'stopped_writers':['stage4 has no app writers; actual read_only and processlist checked by tool'],'recovery_owner':'stage4-local-fixture','not_a_production_approval':True}
    return {'payload':payload,'signatures':[{'signer':n,'sha256':hmac.new(policy.key(n),controlled.canonical(payload),hashlib.sha256).hexdigest()} for n in ['operator','approver']]}

def rejection(name,call,checks):
    try:call()
    except (ValueError,RuntimeError) as e: checks.append({'name':name,'status':'PASS_FRESH','expectedRejection':str(e)});return
    raise AssertionError(name+' not rejected')

def setup(name,policy):
    folder=PRIVATE/'controlled'/name;folder.mkdir()
    source=db('source','mt705_s4_legacy_'+name)
    source.create_empty();b=read(STATE)['inputs']['legacy'];assert sha(b['path'])==b['sha256']
    source.sql(Path(b['path']).read_text(encoding='utf-8'))
    assert not core.preflight(source)['already_scoped']
    proposal=controlled.plan(source,folder/'plan.json')
    target=db('restore','mt705_restore_s4_'+name)
    proof=controlled.verify_backup(source,proposal,target,folder/'initial-proof.json')
    signed=sign(policy,proposal,proof);controlled.publish(folder/'fixture-only-approval.json',signed)
    return source,proposal,target,proof,signed,controlled.Ledger(folder/'ledger',policy),folder

def main():
    home=PRIVATE/'controlled';home.mkdir()
    keys={}
    for name in ['operator','approver','journal']:
        path=home/(name+'.key');path.write_bytes(secrets.token_bytes(48));keys[name]={'role':name,'key_file':str(path)}
    controlled.publish(home/'fixture-only-policy.json',{'scope':'isolated-fixture','keys':keys,'journal_key':'journal'})
    policy=controlled.Policy(home/'fixture-only-policy.json',True)
    result={'status':'INCOMPLETE','scope':'historical synthetic non-scoped MySQL data, not a production legacy specimen','checks':[],'migrationCount':len(core.MIGRATIONS),'inputBackupSha256':read(STATE)['inputs']['legacy']['sha256'],'productionApproval':False,'activationChanged':False}
    physical=db('source','mysql');assert physical.query('SELECT @@global.read_only')==['0']
    physical.sql('SET GLOBAL read_only=1')
    try:
        source,proposal,target,proof,signed,ledger,folder=setup('positive',policy)
        result['preflight']=core.preflight(source);result['planSha256']=controlled.digest(proposal)
        result['checks'].append({'name':'legacy plan, full backup review, independent DDL/all-column restoration','status':'PASS_FRESH'})
        rejection('misbound approval before DDL',lambda:controlled.apply(source,proposal,proof,target,{**signed,'payload':{**signed['payload'],'binding':{}}},ledger),result['checks'])
        def stop_at(n):
            if n==3: raise BoundaryStop()
        try:controlled.apply(source,proposal,proof,target,signed,ledger,after_phase=stop_at)
        except BoundaryStop:pass
        assert ledger.latest()['kind']=='PHASE_COMPLETE' and ledger.latest()['next']==3
        result['checks'].append({'name':'actual three completed implicit-commit DDL phases then boundary interruption','status':'PASS_FRESH'})
        rejection('stale pre-DDL proof cannot resume',lambda:controlled.apply(source,proposal,proof,target,signed,ledger,True),result['checks'])
        # Restart only this verified owned MySQL; read_only is re-established before resume.
        name=source.container;own(name);command(['docker','restart',name])
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            p=command(['docker','exec',name,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT 1"'],check=False)
            if p.returncode==0: break
            time.sleep(1)
        own(name);physical.sql('SET GLOBAL read_only=1')
        assert controlled.state(source)==ledger.latest()['state']
        fresh=db('restore','mt705_restore_s4_resume')
        newest=controlled.verify_backup(source,proposal,fresh,folder/'partial-proof.json',ledger)
        renewed=sign(policy,proposal,newest);controlled.publish(folder/'renewed-fixture-only-approval.json',renewed)
        completed=controlled.apply(source,proposal,newest,fresh,renewed,ledger,True)
        phases=[r for r in ledger.rows() if r['kind']=='PHASE_COMPLETE']
        assert [r['next'] for r in phases]==list(range(1,len(core.MIGRATIONS)+1))
        assert controlled.digest(controlled.preserved(source,proposal['preservation_columns']))==proposal['preserved_sha256']
        assert source.query('SELECT MIN(business_activation_ready+0) FROM tenant_schema_version')==['0']
        result['checks'].append({'name':'restart plus newest backup approval resume; every phase exactly once, original facts preserved','status':'PASS_FRESH','phases':len(phases)})
        rejection('COMPLETE cannot replay',lambda:controlled.apply(source,proposal,newest,fresh,renewed,ledger,True),result['checks'])
        result['defaultTenantCounts']={}
        columns=source.columns()
        for t in core.MANIFEST['private']:
            if t in columns:
                assert source.query('SELECT COUNT(*) FROM '+core.ident(t)+' WHERE tenant_id<>1 OR tenant_id IS NULL')==['0']
                result['defaultTenantCounts'][t]=int(source.query('SELECT COUNT(*) FROM '+core.ident(t))[0])
        result['relationCounts']=relation_counts(source);assert not any(result['relationCounts'].values())
        result['checks'].append({'name':'every historical private row belongs to default tenant; full relation matrix','status':'PASS_FRESH'})
        result['guard']=core.guard(source,core.EPOCH)
        rejection('actual legacy artifact epoch rejected',lambda:core.guard(source,controlled.package_epoch(Path(r'C:\workspace\fx\new\mt705-cont-20261001-r1\s4i-owned-candidate\exchange-backend\target\exchange-backend-0.0.1-SNAPSHOT.jar'))),result['checks'])
        rejection('unapproved activation stays closed',lambda:core.guard(source,core.EPOCH,True),result['checks'])
        backup=source.dump(folder/'final.sql');restored_input=controlled.restore_input(source,backup,folder/'final-restore-input.sql')
        final=db('restore','mt705_restore_s4_final');final.create_empty();final.sql(Path(restored_input['path']).read_text(encoding='utf-8'))
        assert controlled.state(final)==controlled.state(source)
        result['checks'].append({'name':'post-migration full DDL, rows, messages, attachments independently restored','status':'PASS_FRESH'})
        result['finalStateSha256']=controlled.digest(controlled.state(source));result['status']='PASS_FRESH'
    except Exception as e:
        result.update(status='BLOCKED',failureType=type(e).__name__,failure=str(e))
        if 'ledger' in locals() and ledger.rows():result['lastLedger']={'kind':ledger.latest()['kind'],'index':ledger.latest().get('index'),'next':ledger.latest().get('next')}
        raise
    finally:
        physical.sql('SET GLOBAL read_only=0');save(OUT/'controlled-migration.json',result)
        print(json.dumps({'status':result['status'],'checks':len(result['checks']),'failure':result.get('failure')},ensure_ascii=False))

if __name__=='__main__':main()
