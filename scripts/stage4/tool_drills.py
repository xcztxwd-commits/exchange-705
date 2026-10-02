"""Only uncovered stage4 tool contracts on owned disposable copies; no application writer."""
import base64,decimal,hashlib,json,sys
from rehearse import *
import orphan_quarantine as orphan
import private_files as files
import retention_archive as archive

def clone_legacy(name):
    x=db('source','mt705_s4_'+name);x.create_empty();inp=read(STATE)['inputs']['legacy'];assert sha(inp['path'])==inp['sha256'];x.sql(Path(inp['path']).read_text(encoding='utf-8'));return x

def clone_current(name):
    x=db('source','mt705_s4_'+name);x.create_empty();p=PRIVATE/'controlled/positive/final-restore-input.sql'
    if not p.exists():
        inp=read(STATE)['inputs']['REAL'];assert sha(inp['path'])==inp['sha256'];p=Path(inp['path'])
    x.sql(p.read_text(encoding='utf-8'));return x

def full(x):return core.fingerprint(x,{t:[c[0] for c in fs] for t,fs in x.columns().items()})

def expect(name,call,checks):
    try:call()
    except (ValueError,RuntimeError):checks.append(name);return
    raise AssertionError(name+' accepted')

def orphans():
    x=clone_legacy('orphan');columns=x.columns();initial=full(x);checks=[]
    account=x.query('SELECT account FROM admin_user WHERE id=1')[0]
    agent=x.query("SELECT email FROM user_account WHERE user_type='agent' LIMIT 1")[0]
    x.sql('UPDATE admin_user SET account='+core.literal(agent)+',updated_at=updated_at WHERE id=1')
    assert core.preflight(x)['collisions'].get('backend_namespace')==1
    expect('backend normalized namespace conflict refuses plan without inventing an identity',lambda:controlled.plan(x,PRIVATE/'collision-must-not-publish.json'),checks)
    x.sql('UPDATE admin_user SET account='+core.literal(account)+',updated_at=updated_at WHERE id=1');assert full(x)==initial
    for n,t in enumerate(orphan.TABLES):core.fixture_insert(x,t,{'id':990001+n,'user_id':990001+n},columns)
    before=full(x);plan=orphan.inventory(x);assert full(x)==before;assert all(g['count']==1 for g in plan['tables'].values());checks+=['actual all-column sampling precedes writes','six orphan classes inventoried without mutation']
    out=PRIVATE/'orphan';out.mkdir();save(out/'inventory.json',plan)
    identity=x.identity['container_id']
    def apply(path,restore_name):return orphan.apply_fixture(x,plan,path,fixture_container_id=identity,restore_db=db('restore',restore_name))
    x.sql('UPDATE asset_account SET available=available+1,updated_at=updated_at WHERE id=990001')
    expect('stale row refuses deletion',lambda:apply(out/'stale','mt705_restore_s4_orphan_stale'),checks)
    x.sql('UPDATE asset_account SET available=available-1,updated_at=updated_at WHERE id=990001')
    original=x.query
    def concurrent(sql):
        if 'SET @quarantine_ok=1' in sql:core.fixture_insert(x,'user_account',{'id':990003,'email':'race@fixture.invalid','phone':'stage4-race','password_hash':'NOT_A_LOGIN_PASSWORD'},columns)
        return original(sql)
    x.query=concurrent
    try:expect('new parent race rolls back the entire six-table delete',lambda:apply(out/'race','mt705_restore_s4_orphan_race'),checks)
    finally:x.query=original
    x.sql('DELETE FROM user_account WHERE id=990003');assert full(x)==before
    result=apply(out/'apply','mt705_restore_s4_orphan_apply')
    assert result['passed'] and result['unrelated_rows_identical'] and not any(g['count'] for g in orphan.inventory(x)['tables'].values())
    checks+=['full backup independently restored before deletion','only six reviewed orphan rows quarantined; all unrelated bytes unchanged']
    replay=apply(out/'apply','mt705_restore_s4_orphan_apply');assert replay==result;checks.append('completed receipt resume performs no additional deletion')
    x.sql((out/'apply/restore-rows.sql').read_text(encoding='utf-8'));assert full(x)==before;checks.append('archived rows restore every original column exactly')
    return {'status':'PASS_FRESH','checks':checks,'count':len(checks),'syntheticOnly':True,'backupSha256':result['backup']['sha256'],'restoreTarget':result['restore_identity'],'sourceId':identity}

def private_files():
    x=clone_current('files');home=PRIVATE/'files';source=home/'source';dest=home/'destination';(source/'images').mkdir(parents=True);dest.mkdir()
    binary=archive.PNG+b'stage4-synthetic-bytes'
    for name in ['owned.png','shared.png','public.png','unreferenced.png']:(source/'images'/name).write_bytes(binary)
    cols=x.columns();save(home/'sampling-before.json',full(x))
    x.sql("INSERT INTO tenant(id,code,name,status,created_at) VALUES(901,'s4a','Stage4 A','MAINTENANCE',UTC_TIMESTAMP(6)),(902,'s4b','Stage4 B','MAINTENANCE',UTC_TIMESTAMP(6))")
    for tenant,user in [(901,9801001),(902,9801002)]:core.fixture_insert(x,'user_account',{'id':user,'tenant_id':tenant,'email':'s4-'+str(user)+'@fixture.invalid','phone':'','password_hash':'NOT_A_LOGIN_PASSWORD'},cols)
    core.fixture_insert(x,'kyc_record',{'id':9802001,'tenant_id':901,'user_id':9801001,'id_front_image':'/api/uploads/images/owned.png','id_back_image':'/uploads/images/shared.png'},cols)
    core.fixture_insert(x,'kyc_record',{'id':9802002,'tenant_id':902,'user_id':9801002,'id_front_image':'/uploads/images/shared.png'},cols)
    core.fixture_insert(x,'loan_personal_info',{'id':9802003,'tenant_id':901,'user_id':9801001,'id_front_image':'/uploads/images/owned.png','id_back_image':'/uploads/images/missing.png'},cols)
    before=full(x);plan=files.inventory(x,source);assert full(x)==before;save(home/'inventory.json',plan)
    checks=['inventory has no business side effects'];counts=files.summary(plan)['status_counts']
    assert counts.get('ready')==1 and counts.get('ambiguous_owner_or_public_private_mix')==1 and counts.get('missing_or_linked_source',0)>=1
    checks+=['same-owner references accepted, cross-owner shared file not assigned','missing and unreferenced files not published']
    save(dest/'.multitenant-file-fixture.json',{'container_id':x.identity['container_id'],'database':x.database})
    def apply():return files.apply_fixture(x,plan,dest,home/'backup',fixture_container_id=x.identity['container_id'])
    x.sql("UPDATE loan_personal_info SET id_front_image='/uploads/images/concurrent.png' WHERE tenant_id=901 AND id=9802003")
    expect('stale second reference rolls back first reference',apply,checks)
    assert x.query('SELECT id_front_image FROM kyc_record WHERE id=9802001')==['/api/uploads/images/owned.png']
    x.sql("UPDATE loan_personal_info SET id_front_image='/uploads/images/owned.png' WHERE tenant_id=901 AND id=9802003")
    result=apply();assert result['completed_files']==1;after=full(x);assert apply()==result and full(x)==after;checks.append('copy/repoint and receipt resume idempotent')
    ready=next(v for v in plan['files'] if v['status']=='ready');assert sha(dest/ready['destination'])==sha(source/'images/owned.png')==ready['sha256'];checks.append('source, preserved copy and destination bytes match')
    (source/'images/owned.png').write_bytes(binary+b'changed');expect('changed source cannot resume',apply,checks);(source/'images/owned.png').write_bytes(binary)
    for url in ['/uploads/images/../secret.png','/uploads/images/%2e%2e/secret.png','https://unapproved.invalid/uploads/a.png']:
        assert files.relative_upload(url,set())[1]
    checks.append('path traversal and unapproved external reference refused')
    # Only integrity constraints touched by tool fixtures; no repeated funds lifecycle.
    failures=[]
    for label,sql,code in [
        ('cross-tenant FK',"INSERT INTO asset_account(tenant_id,user_id,coin,available,frozen,row_version) VALUES(902,9801001,'BAD',1,0,0)",1452),
        ('tenant reassignment',"UPDATE user_account SET tenant_id=902 WHERE id=9801001",1644),
        ('missing tenant',"INSERT INTO asset_account(user_id,coin,available,frozen,row_version) VALUES(9801001,'BAD',1,0,0)",1364)]:
        failures.append(core.assert_failure(x,sql,label,code))
    assert full(x)==after
    return {'status':'PASS_FRESH','checks':checks,'count':len(checks),'constraintChecks':failures,'sourceFilesRetained':True,'originalPublicLegacyArtifactsNotReclassified':True,'apiAuthorization':'PASS_REUSED from unchanged stage2 materials browser proof; this tool test is not an HTTP authorization test'}

def archive_rows(x,table,where):
    fields=[]
    for name,kind,*rest in x.columns()[table]:
        v=core.ident(name)
        if kind in ('datetime','timestamp','date'):v="DATE_FORMAT("+v+",'%Y-%m-%dT%H:%i:%s.%fZ')"
        elif kind in ('blob','longblob','mediumblob','tinyblob','binary','varbinary'):v="REPLACE(TO_BASE64("+v+"),'\\n','')"
        elif kind in ('bit','boolean'):v='CAST('+v+' AS UNSIGNED)'
        fields += [core.literal(name),v]
    return [json.loads(row) for row in x.query('SELECT JSON_OBJECT('+','.join(fields)+') FROM '+core.ident(table)+' WHERE '+where)]

def retention():
    x=clone_current('archive');home=PRIVATE/'archive';home.mkdir();cols=x.columns();save(home/'sampling-before.json',full(x))
    owner=int(x.query('SELECT id FROM user_account WHERE tenant_id=1 ORDER BY id LIMIT 1')[0]);conversation=9803001;message=9803002;binary=archive.PNG+b'stage4-archive';image=hashlib.sha256(binary).hexdigest()
    canonical=[conversation,'USER',owner,'stage4','stage4-archive-request','stage4 archive message',image,'2026-10-01T00:00:00Z',''];digest=hashlib.sha256(json.dumps(canonical,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
    core.fixture_insert(x,'support_conversation',{'id':conversation,'tenant_id':1,'user_id':owner,'admin_id':None,'status':'CLOSED','active_user_id':None,'closed_at':'2026-10-01 00:00:00','created_at':'2026-10-01 00:00:00','last_hash':digest},cols)
    core.fixture_insert(x,'support_message',{'id':message,'tenant_id':1,'conversation_id':conversation,'sender':'USER','sender_id':owner,'sender_name':'stage4','request_id':'stage4-archive-request','text':'stage4 archive message','image':True,'image_hash':image,'previous_hash':'','hash':digest,'created_at':'2026-10-01 00:00:00'},cols)
    # Binary payload goes through literal HEX, never Python bytes repr.
    x.sql('INSERT INTO support_attachment(tenant_id,message_id,content) VALUES(1,'+str(message)+',0x'+binary.hex()+')')
    body={'tenantId':1,'conversation':archive_rows(x,'support_conversation','id='+str(conversation)),'messages':archive_rows(x,'support_message','id='+str(message)),'attachments':archive_rows(x,'support_attachment','message_id='+str(message))}
    raw=json.dumps(body,ensure_ascii=False,separators=(',',':')).encode();file=home/(str(conversation)+'-'+hashlib.sha256(raw).hexdigest()+'.json');file.write_bytes(raw);verified=archive.verify(file)
    before=full(x)
    # Remove only this just-created synthetic chain, never existing history or policy rows.
    x.sql('START TRANSACTION;DELETE FROM support_attachment WHERE tenant_id=1 AND message_id='+str(message)+';DELETE FROM support_message WHERE tenant_id=1 AND id='+str(message)+';DELETE FROM support_conversation WHERE tenant_id=1 AND id='+str(conversation)+';COMMIT;')
    absent=full(x);target=db('restore','mt705_restore_s4_archive');proof=archive.restore_to_new_fixture(x,target,file,home/'restore',apply=True,publish_marker=False)
    assert proof['databaseRestored'] and full(target)==before and full(x)==absent
    assert x.query("SELECT policy_value FROM tenant_policy WHERE tenant_id=1 AND policy_key='retention.auto_delete_enabled'")==['false']
    return {'status':'PASS_FRESH','checks':['actual schema-exact archive contains complete conversation/message/attachment columns','message hash chain and PNG attachment bytes validated','synthetic deleted chain recovered only into new independent instance','all rows equal pre-archive snapshot; source remains absent and unchanged','default cleanup stays disabled; no publication or approval marker'], 'count':5,'verified':verified,'restoreProofSha256':sha(home/'restore/restore-report.json')}

def main():
    result={}
    for name,operation in [('orphans',orphans),('privateFiles',private_files),('retentionArchive',retention)]:
        try:result[name]=operation()
        except Exception as e:result[name]={'status':'BLOCKED','failureType':type(e).__name__,'failure':str(e)}
        save(OUT/'tool-drills.json',result)
        print(name,result[name]['status'],result[name].get('failure',''),flush=True)
    if any(x['status']=='BLOCKED' for x in result.values()):sys.exit(1)

if __name__=='__main__':main()
