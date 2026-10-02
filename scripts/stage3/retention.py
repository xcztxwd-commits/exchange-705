"""Aged synthetic COPY only; real retention/archive APIs, independent recovery, bounded retry."""
import base64, datetime as dt, hashlib, json, os, shutil, time, uuid
from acceptance import Acceptance
import environment as e
import retention_archive as archive

def rows(db,table,where):
    columns=db.columns()[table];parts=[]
    for name,kind,*_ in columns:
        value=e.dbtools.ident(name)
        if kind=='bit':value='CAST('+value+' AS UNSIGNED)'
        elif kind in ('datetime','timestamp'):value="DATE_FORMAT("+value+",'%Y-%m-%dT%H:%i:%s.%fZ')"
        elif 'blob' in kind:value='TO_BASE64('+value+')'
        parts.extend([e.dbtools.literal(name),value])
    return [json.loads(s) for s in db.query('SELECT JSON_OBJECT('+','.join(parts)+') FROM '+e.dbtools.ident(table)+' WHERE '+where)]

def insert(db,table,row):
    sql='INSERT INTO '+e.dbtools.ident(table)+'('+','.join(e.dbtools.ident(k) for k in row)+') VALUES('+','.join(archive.sql_value(v) for v in row.values())+');SELECT LAST_INSERT_ID();'
    return int(db.query(sql)[-1])

def aged_copy(a):
    t=a.state['tenants']['A'];db=a.db['REAL'];old=t['conversationId'];meta=db.columns();source=rows(db,'support_conversation','id='+str(old))[0]
    source.pop('id');source.update(active_user_id=None,last_hash='',legal_hold=False)
    for k in ['created_at','updated_at','accepted_at','closed_at']:
        if source.get(k):source[k]=(dt.datetime.fromisoformat(source[k].replace('Z','+00:00'))-dt.timedelta(days=366)).strftime('%Y-%m-%d %H:%M:%S.%f')
    source.update(user_read_id=0,admin_read_id=0);cid=insert(db,'support_conversation',source);previous='';count=0
    for message in rows(db,'support_message',f'conversation_id={old} ORDER BY id'):
        oldid=message.pop('id');message['conversation_id']=cid
        when=dt.datetime.fromisoformat(message['created_at'].replace('Z','+00:00'))-dt.timedelta(days=366)
        canonical=[cid,message['sender'],message['sender_id'],message['sender_name'],message['request_id'],message['text'],message['image_hash'],archive.instant(when.isoformat()),previous]
        message.update(previous_hash=previous,hash=hashlib.sha256(json.dumps(canonical,ensure_ascii=False,separators=(',',':')).encode()).hexdigest(),created_at=when.strftime('%Y-%m-%d %H:%M:%S.%f'))
        mid=insert(db,'support_message',message);previous=message['hash'];count+=1
        for attachment in rows(db,'support_attachment','message_id='+str(oldid)):
            attachment.update(message_id=mid,content=base64.b64decode(attachment['content']));insert(db,'support_attachment',attachment)
    db.sql('UPDATE support_conversation SET last_hash='+e.dbtools.literal(previous)+' WHERE id='+str(cid))
    return cid,count

def run(a):
    t=a.state['tenants']['A'];tenant=t['id'];db=a.db['REAL'];prefix=f'/tenants/{tenant}';base=e.PRIVATE/'REAL-archives'
    for other in a.state['tenants'].values():
        policy=a.control(f'/tenants/{other["id"]}/retention')['data'];assert policy['retentionDays']==365 and policy['autoDeleteEnabled'] is False
    cid,count=aged_copy(a);a.state['retentionConversation']=cid;a.save()
    original=db.query(f'SELECT * FROM support_conversation WHERE id={t["conversationId"]}')
    preview=a.control(prefix+'/retention')['data'];assert cid in preview['candidateIds'] and t['conversationId'] not in preview['candidateIds']
    a.control(prefix+f'/retention/hold/{cid}','POST',{'enabled':True,'reason':'Stage3 synthetic preservation check'})
    assert cid not in a.control(prefix+'/retention')['data']['candidateIds']
    a.control(prefix+f'/retention/hold/{cid}','POST',{'enabled':False,'reason':'Stage3 owned copy preservation released'})
    key=str(uuid.uuid4());body={'requestKey':key,'reason':'Stage3 independent archive recovery'}
    job=a.control(prefix+f'/support/conversations/{cid}/archive-jobs','POST',body)['data'];jid=job['id'];a.state['archiveJob']=jid;a.save()
    assert a.control(prefix+f'/support/conversations/{cid}/archive-jobs','POST',body)['data']['id']==jid
    directory=base/f'tenant-{tenant}'/('job-'+jid);metadata=directory/'job.json';backup=e.PRIVATE/('job-original-'+jid+'.json');shutil.copy2(metadata,backup)
    temporary=metadata.with_suffix('.tmp');temporary.write_bytes(b'{}');os.replace(temporary,metadata)
    def wait(state):
        until=time.monotonic()+40
        while time.monotonic()<until:
            value=a.control(prefix+f'/support/archive-jobs/{jid}')['data']
            if value['state']==state:return value
            time.sleep(.3)
        raise AssertionError('Archive did not reach '+state+'; state='+value['state'])
    failed=wait('FAILED');assert failed['processed_messages']==0
    assert int(db.query(f'SELECT COUNT(*) FROM support_message WHERE conversation_id={cid}')[0])==count
    temporary.write_bytes(backup.read_bytes());os.replace(temporary,metadata)
    a.control(prefix+f'/support/archive-jobs/{jid}/retry','POST',{'reason':'Stage3 original metadata hash restored'})
    complete=wait('COMPLETE');assert complete['processed_messages']==count and complete['chunk_sequence']==1
    manifest=directory/('manifest-'+complete['final_manifest_sha256']+'.json');verified=archive.verify(manifest)
    # v1 uses exactly the service-produced archive fields/PNG bytes, not fabricated restore markers.
    _,doc,_=archive.v2_manifest(manifest);v1={'tenantId':tenant,'conversation':[doc['conversation']],'messages':[],'attachments':[]}
    for chunk,images in archive.v2_chunks(manifest):
        v1['messages']+=chunk['messages']
        for image in chunk['attachments']:v1['attachments'].append({**image['row'],'content':base64.b64encode(images[image['row']['message_id']].read_bytes()).decode()})
    raw=json.dumps(v1,ensure_ascii=False,separators=(',',':')).encode();v1file=directory.parent/(str(cid)+'-'+hashlib.sha256(raw).hexdigest()+'.json');v1file.write_bytes(raw);archive.verify(v1file)
    # Retention rehearsal source is a new data copy; only the test conversation is removed there.
    copy=e.dbtools.Database(db.container,'mt705_probe_stage3_archive_source_'+a.attempt);dump=db.dump(e.PRIVATE/('archive-source-'+a.attempt+'.sql'));copy.create_empty();copy.sql(e.Path(dump['path']).read_text(encoding='utf-8'))
    copy.sql(f'DELETE a FROM support_attachment a JOIN support_message m ON a.message_id=m.id AND a.tenant_id=m.tenant_id WHERE m.tenant_id={tenant} AND m.conversation_id={cid};DELETE FROM support_message WHERE tenant_id={tenant} AND conversation_id={cid};DELETE FROM support_conversation WHERE tenant_id={tenant} AND id={cid};')
    proofs=[]
    for name,file in [('v1',v1file),('v2',manifest)]:
        target=e.dbtools.Database(a.spec['containers']['restore'],'mt705_restore_stage3_'+name+'_'+a.attempt)
        proof=archive.restore_to_new_fixture(copy,target,file,e.PRIVATE/('archive-restore-'+name+'-'+a.attempt),apply=True,publish_marker=True)
        assert all(proof['checks'][k] for k in ['rows','tenantRelations','messageChain','lastHash','attachmentBytes','schema'])
        proofs.append({k:proof[k] for k in ['result','format','toolVersion','databaseRestored','independentCurrentBackupRestored','checks','targetDatabase','sourceServerIdentity','targetServerIdentity','toolSha256']})
    preview=a.control(prefix+'/retention')['data'];body={'enabled':True,'confirm':True,'policyVersion':preview['policyVersion'],'previewHash':preview['previewHash'],'reason':'Stage3 owned-copy cleanup with independent restore proof'}
    a.control(prefix+'/retention','PUT',body);preview=a.control(prefix+'/retention')['data'];body.update(policyVersion=preview['policyVersion'],previewHash=preview['previewHash'])
    result=a.control(prefix+'/retention/clean','POST',body);assert result['data']>=1
    assert db.query(f'SELECT COUNT(*) FROM support_conversation WHERE id={cid}')==['0']
    preview=a.control(prefix+'/retention')['data'];body.update(policyVersion=preview['policyVersion'],previewHash=preview['previewHash']);assert a.control(prefix+'/retention/clean','POST',body)['data']==0
    a.control(prefix+'/retention','PUT',{'enabled':False,'reason':'Stage3 cleanup rehearsal finished; default remains off'})
    assert db.query(f'SELECT * FROM support_conversation WHERE id={t["conversationId"]}')==original
    return {'conversation':cid,'originalConversationPreserved':True,'days':365,'defaultOff':True,'heldExcluded':True,'newerExcluded':True,'messages':count,'boundedFailedCursor':failed['cursor_id'],'chunks':complete['chunk_sequence'],'retry':'FAILED -> explicit retry -> COMPLETE','independentRestores':proofs,'cleanupOnOwnedCopyOnly':True,'repeatCleanupCount':0,'disabledAtEnd':True}

if __name__=='__main__':
    a=Acceptance();a.check('retention-archive-independent-restore',lambda:run(a))
