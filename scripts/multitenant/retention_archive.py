"""Bounded archive verification and recovery into a fresh independent isolated database only."""
import argparse,base64,datetime as dt,hashlib,json,os,re,subprocess
from pathlib import Path
MAX_FILE_BYTES=192*1024*1024
PNG=b'\x89PNG\r\n\x1a\n'

def unique_pairs(pairs):
    result={}
    for key,value in pairs:
        if key in result:raise ValueError('Duplicate JSON field')
        result[key]=value
    return result

def positive(value):
    if type(value) is not int or value<=0:raise ValueError('Positive integral owner required')
    return value

def instant(value):
    # Jackson JDBC Timestamp is epoch milliseconds. Canonical Instant.toString keeps 0 or 3/6 fractional digits.
    if type(value) is int:
        seconds,millis=divmod(value,1000)
        time=dt.datetime.fromtimestamp(seconds,dt.timezone.utc)
        return time.isoformat(timespec='seconds').replace('+00:00','')+(f'.{millis:03}' if millis else '')+'Z'
    if not isinstance(value,str):raise ValueError('Invalid message timestamp')
    if re.search(r'[.][0-9]{7,}',value):raise ValueError('Archive timestamp precision exceeds DATETIME(6)')
    parsed=dt.datetime.fromisoformat(value.replace('Z','+00:00'))
    if parsed.tzinfo is None:raise ValueError('Timestamp offset required')
    parsed=parsed.astimezone(dt.timezone.utc)
    fraction=parsed.microsecond
    return parsed.isoformat(timespec='seconds').replace('+00:00','')+(f'.{fraction//1000:03}' if fraction and fraction%1000==0 else f'.{fraction:06}' if fraction else '')+'Z'

def verify_v1(file):
    file=Path(file)
    if file.is_symlink() or not file.is_file() or file.stat().st_size>MAX_FILE_BYTES:raise ValueError('Archive file is missing, linked or exceeds the bounded verifier limit')
    raw=file.read_bytes();digest=hashlib.sha256(raw).hexdigest()
    if file.name.split('-')[-1].split('.')[0]!=digest:raise ValueError('Archive checksum mismatch')
    body=json.loads(raw,object_pairs_hook=unique_pairs)
    tenant=positive(body['tenantId']);rows=body['conversation']
    if not isinstance(rows,list) or len(rows)!=1 or rows[0]['tenant_id']!=tenant:raise ValueError('Conversation tenant mismatch')
    conversation=positive(rows[0]['id'])
    if rows[0].get('status')!='CLOSED':raise ValueError('Retention archive requires an entire closed conversation')
    messages={};images={};previous='';cursor=0
    for message in body['messages']:
        mid=positive(message['id'])
        if mid<=cursor or message['tenant_id']!=tenant or message['conversation_id']!=conversation:raise ValueError('Message order or ownership mismatch')
        cursor=mid;messages[mid]=message
        for key in ('sender','sender_name','request_id','text','image_hash','previous_hash','hash'):
            if not isinstance(message.get(key),str):raise ValueError('Invalid canonical message field')
        sender_id=message['sender_id']
        if message['sender'] not in ('USER','ADMIN','CONTROL','SYSTEM') or type(sender_id) is not int or sender_id<0 or (sender_id==0 and message['sender']!='SYSTEM'):raise ValueError('Invalid canonical sender identity')
        canonical=[conversation,message['sender'],sender_id,message['sender_name'],message['request_id'],message['text'],message['image_hash'],instant(message['created_at']),previous]
        expected=hashlib.sha256(json.dumps(canonical,ensure_ascii=False,separators=(',',':')).encode('utf-8')).hexdigest()
        if message['previous_hash']!=previous or message['hash']!=expected:raise ValueError('Message hash chain mismatch')
        previous=expected;flag=message.get('image')
        if type(flag) not in (int,bool) or flag not in (0,1):raise ValueError('Invalid image flag')
        if flag:
            if not re.fullmatch('[a-f0-9]{64}',message['image_hash']):raise ValueError('Invalid image checksum')
            images[mid]=message
        elif message['image_hash']!='':raise ValueError('Nonimage message has image evidence')
    if rows[0].get('last_hash')!=previous:raise ValueError('Conversation lastHash mismatch')
    seen=set();attachment_ids=set();byte_count=0
    for attachment in body['attachments']:
        mid=positive(attachment['message_id']);aid=mid
        if attachment['tenant_id']!=tenant or mid not in images or mid in seen or aid in attachment_ids:raise ValueError('Attachment ownership/count mismatch')
        seen.add(mid);attachment_ids.add(aid)
        if not isinstance(attachment.get('content'),str):raise ValueError('Invalid attachment encoding')
        binary=base64.b64decode(attachment['content'],validate=True);byte_count+=len(binary)
        if byte_count>32*1024*1024:raise ValueError('Attachment verification bound exceeded; preserve source and use reviewed streaming restore')
        if not binary.startswith(PNG) or hashlib.sha256(binary).hexdigest()!=images[mid]['image_hash']:raise ValueError('Attachment PNG/checksum mismatch')
    if set(images)!=seen:raise ValueError('Image attachment missing')
    return {'format':'retention-json-v1','tenantId':tenant,'conversationId':conversation,'messages':len(messages),'attachments':len(seen),'attachmentBytes':byte_count,'sha256':digest,
            'checks':{'tenantRelations':True,'messageChain':True,'lastHash':True,'attachmentBytes':True},'databaseRestored':False}

def initialize(directory):
    directory=Path(directory).absolute()
    if directory.is_symlink():raise ValueError('Symlinks are not allowed')
    directory.mkdir(parents=True,exist_ok=True)
    if os.name=='nt':
        who='*'+re.search(r'S-1-5-[0-9-]+',subprocess.check_output(['whoami','/user','/fo','csv']).decode('utf8',errors='replace')).group()
        subprocess.run(['icacls',str(directory),'/inheritance:r','/grant:r',who+':(OI)(CI)F','*S-1-5-18:(OI)(CI)F'],check=True,capture_output=True)
    else:directory.chmod(0o700)
    # No synthetic archive, success marker, production approval or deletion policy is written.
    print('Private archive directory prepared. No database restoration verified; cleanup remains closed.')



def server_identity(db):
    identity=db.identity
    value=identity.get('server_uuid') or identity.get('container_id')
    if not value: raise ValueError('Exact server identity required')
    return str(value)


def restore_target(source,target):
    if target.test is not True or target.identity.get('test_instance') is not True:
        raise ValueError('Restore target must be an explicitly isolated test instance')
    if not re.fullmatch(r'mt705_restore_[a-z0-9_]+',target.database):
        raise ValueError('Only a new mt705_restore_* fixture is allowed')
    if server_identity(source)==server_identity(target):
        raise ValueError('Independent restore server required')
    return {'sourceServerIdentity':server_identity(source),'targetServerIdentity':server_identity(target),'targetDatabase':target.database}


def archive_body(file):
    from decimal import Decimal
    verified=verify(file)
    body=json.loads(Path(file).read_bytes(),object_pairs_hook=unique_pairs,parse_float=Decimal)
    return verified,body


def typed_row(row,columns):
    """Archive JDBC timestamps are epoch milliseconds; blobs are exact base64 bytes."""
    if set(row)!=set(x[0] for x in columns): raise ValueError('Archive columns differ from the reviewed current schema')
    values={}
    for name,kind,nullable,*_ in columns:
        value=row[name]
        if value is None:
            if nullable!='YES':raise ValueError('NULL violates archive schema')
        elif kind in ('datetime','timestamp','date'):
            canonical=instant(value)
            value=dt.datetime.fromisoformat(canonical.replace('Z','+00:00')).astimezone(dt.timezone.utc).strftime('%Y-%m-%d %H:%M:%S.%f')
            if kind=='date':value=value[:10]
        elif kind in ('blob','longblob','mediumblob','tinyblob','binary','varbinary'):
            if not isinstance(value,str):raise ValueError('Binary archive field must be base64')
            value=base64.b64decode(value,validate=True)
        elif kind in ('bigint','int','smallint','mediumint'):
            if type(value) is not int:raise ValueError('Integral archive column cannot be text, Boolean or decimal')
        elif kind in ('bit','boolean'):
            if type(value) not in (int,bool) or value not in (0,1):raise ValueError('Invalid archived Boolean')
            value=bool(value)
        values[name]=value
    return values


def dry_run(db,file):
    import mysql_migration as migration
    verified,body=archive_body(file);tenant=verified['tenantId'];conversation=verified['conversationId']
    columns=db.columns();required=('support_conversation','support_message','support_attachment')
    if any(t not in columns for t in required):raise ValueError('Current archive schema is missing')
    epoch=int(db.query('SELECT MAX(minimum_application_epoch) FROM tenant_schema_version')[0])
    if epoch<migration.EPOCH:raise ValueError('Archive target schema is behind the reviewed migration epoch')
    rows={table:[typed_row(row,columns[table]) for row in body[key]] for table,key in zip(required,('conversation','messages','attachments'))}
    c=rows['support_conversation'][0]
    if c.get('active_user_id') is not None:raise ValueError('A closed archived conversation cannot retain an active-user claim')
    relations=[('tenant','id',tenant,None),('user_account','id',c['user_id'],tenant)]
    if c.get('admin_id') is not None:relations.append(('admin_user','id',c['admin_id'],tenant))
    if c.get('control_actor_id') is not None:relations.append(('control_admin','id',c['control_actor_id'],None))
    for table,key,value,owner in relations:
        where=migration.ident(key)+'='+migration.literal(value)+((' AND tenant_id='+str(owner)) if owner is not None else '')
        if db.query('SELECT COUNT(*) FROM '+migration.ident(table)+' WHERE '+where)!=['1']:raise ValueError('Archive parent relation is absent or has a different tenant')
    for message in rows['support_message']:
        sender=message['sender'];sender_id=message['sender_id']
        if sender=='USER' and sender_id!=c['user_id']:raise ValueError('Archived user sender is not the conversation owner')
        table={'USER':'user_account','ADMIN':'admin_user','CONTROL':'control_admin'}.get(sender)
        if table:
            scope=(' AND tenant_id='+str(tenant)) if sender!='CONTROL' else ''
            if db.query('SELECT COUNT(*) FROM '+migration.ident(table)+' WHERE id='+str(sender_id)+scope)!=['1']:raise ValueError('Archived sender parent is absent or has another tenant')
        elif sender_id!=0:
            query='SELECT (SELECT COUNT(*) FROM user_account WHERE tenant_id='+str(tenant)+' AND id='+str(sender_id)+')+(SELECT COUNT(*) FROM admin_user WHERE tenant_id='+str(tenant)+' AND id='+str(sender_id)+')'
            if int(db.query(query)[0])<1:raise ValueError('Archived system event actor does not belong to this tenant')
    for table,key in zip(required,('id','id','message_id')):
        ids=[r[key] for r in rows[table]]
        # Read bounded IDs per query; no guessed ownership or INSERT IGNORE/REPLACE.
        for start in range(0,len(ids),100):
            group=ids[start:start+100]
            if db.query('SELECT COUNT(*) FROM '+migration.ident(table)+' WHERE '+migration.ident(key)+' IN ('+','.join(str(positive(i)) for i in group)+')')!=['0']:
                raise ValueError('Archive primary key conflict; existing rows are never overwritten')
    return {**verified,'toolVersion':2,'schemaEpoch':epoch,'checks':{**verified['checks'],'schema':True,'primaryKeys':True,'parentRelations':True},'databaseRestored':False},rows


def sql_value(value):
    import mysql_migration as migration
    return '0x'+value.hex() if isinstance(value,bytes) else migration.literal(value)


def restore_rows(db,file):
    """One transaction for an entire bounded v1 archive; mysql exits on errors and rolls back."""
    import mysql_migration as migration
    verified,rows=dry_run(db,file)
    statements=["SET time_zone='+00:00';", "SET SESSION sql_mode='STRICT_ALL_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE';",'START TRANSACTION;']
    for table,items in rows.items():
        for row in items:
            statements.append('INSERT INTO '+migration.ident(table)+'('+','.join(migration.ident(k) for k in row)+') VALUES('+','.join(sql_value(v) for v in row.values())+');')
    statements.append('COMMIT;');db.sql('\n'.join(statements))
    # Read back every original column and byte, not just a superficial row count.
    for table,items in rows.items():
        for row in items:
            date_columns={field[0] for field in db.columns()[table] if field[1] in ('datetime','timestamp','date')}
            predicates=['CAST('+migration.ident(k)+' AS BINARY) <=> CAST('+sql_value(v)+' AS BINARY)' if isinstance(v,(str,bytes)) and k not in date_columns else migration.ident(k)+' <=> '+sql_value(v) for k,v in row.items()]
            if db.query('SELECT COUNT(*) FROM '+migration.ident(table)+' WHERE '+' AND '.join(predicates))!=['1']:raise ValueError('Restored archive column or byte mismatch')
    return {**verified,'result':'RESTORED_AND_VERIFIED','databaseRestored':True,'checks':{**verified['checks'],'rows':True}}


def restore_to_new_fixture(source,target,file,directory,apply=False,publish_marker=False):
    """Clone a current backup into a fresh independent test DB, then recover absent archive rows."""
    import mysql_migration as migration
    identities=restore_target(source,target)
    chunked=Path(file).name.startswith('manifest-');prepared=dry_run_v2(source,file) if chunked else dry_run(source,file)[0]
    if target.sql('SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='+migration.literal(target.database),database=False).stdout.decode('utf-8').strip()!='0':
        raise ValueError('Fresh restore database required; name collisions are refused')
    report={**prepared,**identities,'result':'DRY_RUN','databaseRestored':False,'productionApplied':False,'toolSha256':migration.file_hash(Path(__file__))}
    if not apply:return report
    directory=Path(directory).absolute()
    if directory.is_symlink() or directory.exists():raise ValueError('Fresh restricted evidence directory required')
    initialize(directory)
    before=migration.fingerprint(source,{t:[x[0] for x in fields] for t,fields in source.columns().items()})
    backup=source.dump(directory/'current-source.sql')
    if migration.fingerprint(source,before['columns'])!=before:raise ValueError('Source changed during backup; create a fresh plan and backup')
    target.create_empty();execute_sql_file(target,directory/'current-source.sql')
    if migration.fingerprint(target,before['columns'])!=before:raise ValueError('Independent full backup restore mismatch before archive writes')
    report.update(restore_v2_rows(target,file,directory) if chunked else restore_rows(target,file));report.update(identities);report['backup']=backup;report['independentCurrentBackupRestored']=True
    report['completedAt']=dt.datetime.now(dt.timezone.utc).isoformat();report['performedByType']='LOCAL_TOOL';report['performedBy']=__import__('getpass').getuser()
    output=directory/'restore-report.json';migration.atomic_json(output,report)
    if publish_marker:
        base=Path(file).absolute().parent.parent.parent if chunked else Path(file).absolute().parent.parent
        tenant_directory=Path(file).parent.parent if chunked else Path(file).parent
        if base.is_symlink() or not base.is_dir() or not re.fullmatch(r'tenant-[1-9][0-9]*',tenant_directory.name):raise ValueError('Marker requires the restricted tenant archive layout')
        prefix='.retention-chunks-restore' if chunked else '.retention-restore'
        marker=base/(prefix+'-verified');result=base/(prefix+'-report.json')
        if marker.exists() or result.exists():raise ValueError('Existing restore marker/report must be preserved, not overwritten')
        migration.atomic_json(result,report)
        migration.atomic_json(marker,{'toolVersion':report['toolVersion'],'format':report['format'],'reportSha256':migration.file_hash(result)})
    return report


# v2 keeps only one 100-message JSON chunk and one <=10MiB attachment in memory.
MAX_CHUNK_BYTES=16*1024*1024
MAX_ATTACHMENT_BYTES=10*1024*1024
MAX_V2_ATTACHMENT_BYTES=2*1024*1024*1024
MAX_V2_JSON_BYTES=8*1024*1024*1024

def read_checked(file,limit,expected=None):
    from decimal import Decimal
    file=Path(file)
    if file.is_symlink() or not file.is_file() or file.stat().st_size>limit:raise ValueError('Archive file is missing, linked or exceeds its bound')
    if file.absolute()!=file.resolve():raise ValueError('Archive ancestors cannot contain links')
    raw=file.read_bytes();checksum=hashlib.sha256(raw).hexdigest()
    if expected is not None and checksum!=expected:raise ValueError('Archive content checksum mismatch')
    return json.loads(raw,object_pairs_hook=unique_pairs,parse_float=Decimal),checksum

def whole(value):
    if type(value) is not int or value<0:raise ValueError('Nonnegative integral archive count required')
    return value

def checked_file(base,name,pattern):
    if not isinstance(name,str) or not re.fullmatch(pattern,name):raise ValueError('Archive file name is outside the reviewed layout')
    file=base/name
    if file.is_symlink() or file.absolute()!=file.resolve() or file.parent!=base:raise ValueError('Archive file path is linked or outside its job directory')
    return file

def v2_manifest(file):
    import uuid
    file=Path(file).absolute();match=re.fullmatch(r'manifest-([a-f0-9]{64})[.]json',file.name)
    if not match:raise ValueError('Exact final v2 manifest name required')
    body,checksum=read_checked(file,MAX_CHUNK_BYTES,match[1])
    if body.get('format')!='retention-chunks-v2':raise ValueError('Unsupported chunked archive format')
    job=body.get('jobId')
    if not isinstance(job,str) or str(uuid.UUID(job))!=job:raise ValueError('Exact archive job UUID required')
    tenant=positive(body['tenantId']);conversation=body['conversation']
    if not isinstance(conversation,dict) or conversation.get('tenant_id')!=tenant:raise ValueError('Archive conversation scope mismatch')
    positive(conversation['id']);whole(body['messages']);whole(body['attachmentBytes']);whole(body['lastId'])
    if body['messages']>1000000 or body['attachmentBytes']>MAX_V2_ATTACHMENT_BYTES:raise ValueError('Archive task exceeds the reviewed recovery budget')
    if not isinstance(body.get('lastHash'),str) or not isinstance(body.get('snapshotSha256'),str) or not re.fullmatch('[a-f0-9]{64}',body['snapshotSha256']):raise ValueError('Invalid archive hash metadata')
    if not isinstance(body.get('chunks'),list) or len(body['chunks'])>10000:raise ValueError('Archive has too many chunks')
    return file,body,checksum

def v2_chunks(file):
    file,manifest,_=v2_manifest(file);base=file.parent;tenant=manifest['tenantId'];conversation=manifest['conversation']['id']
    cursor=0;previous='';total=0;images_total=0;json_bytes=0
    for sequence,reference in enumerate(manifest['chunks'],1):
        if not isinstance(reference,dict) or reference.get('sequence')!=sequence:raise ValueError('Archive chunk sequence mismatch')
        chunk_file=checked_file(base,reference['file'],r'chunk-[0-9]{6}-[a-f0-9]{64}[.]json')
        expected=reference.get('sha256')
        if not isinstance(expected,str) or chunk_file.name!=f'chunk-{sequence:06}-{expected}.json':raise ValueError('Archive chunk reference checksum mismatch')
        chunk,_=read_checked(chunk_file,MAX_CHUNK_BYTES,expected);json_bytes+=chunk_file.stat().st_size
        if json_bytes>MAX_V2_JSON_BYTES:raise ValueError('Archive JSON disk budget exceeded')
        if chunk.get('format')!='retention-chunk-v2' or chunk.get('jobId')!=manifest['jobId'] or chunk.get('tenantId')!=tenant or chunk.get('conversationId')!=conversation or chunk.get('sequence')!=sequence:raise ValueError('Archive chunk ownership mismatch')
        if chunk.get('afterId')!=cursor or chunk.get('previousHash')!=previous or reference.get('previousHash')!=previous:raise ValueError('Archive chunk boundary mismatch')
        messages=chunk.get('messages');attachments=chunk.get('attachments')
        if not isinstance(messages,list) or not 1<=len(messages)<=100 or reference.get('messages')!=len(messages) or not isinstance(attachments,list) or len(attachments)>100:raise ValueError('Archive chunk row bound or count mismatch')
        images={}
        for message in messages:
            if not isinstance(message,dict):raise ValueError('Invalid archived message')
            mid=positive(message['id'])
            if mid<=cursor or message.get('tenant_id')!=tenant or message.get('conversation_id')!=conversation:raise ValueError('Archive message order or tenant mismatch')
            for key in ('sender','sender_name','request_id','text','image_hash','previous_hash','hash'):
                if not isinstance(message.get(key),str):raise ValueError('Invalid canonical archived message field')
            sender_id=message.get('sender_id');sender=message['sender']
            if sender not in ('USER','ADMIN','CONTROL','SYSTEM') or type(sender_id) is not int or sender_id<0 or sender_id==0 and sender!='SYSTEM':raise ValueError('Invalid archived sender')
            canonical=[conversation,sender,sender_id,message['sender_name'],message['request_id'],message['text'],message['image_hash'],instant(message['created_at']),previous]
            expected=hashlib.sha256(json.dumps(canonical,ensure_ascii=False,separators=(',',':')).encode('utf-8')).hexdigest()
            if message['previous_hash']!=previous or message['hash']!=expected:raise ValueError('Archive full message chain mismatch')
            flag=message.get('image')
            if type(flag) not in (int,bool) or flag not in (0,1):raise ValueError('Invalid archive image flag')
            if flag:
                if not re.fullmatch('[a-f0-9]{64}',message['image_hash']):raise ValueError('Invalid image checksum')
                images[mid]=message['image_hash']
            elif message['image_hash']!='':raise ValueError('Nonimage message contains image evidence')
            cursor=mid;previous=expected
        if chunk.get('firstId')!=messages[0]['id'] or reference.get('firstId')!=messages[0]['id'] or chunk.get('lastId')!=cursor or reference.get('lastId')!=cursor or chunk.get('lastHash')!=previous or reference.get('lastHash')!=previous:raise ValueError('Archive sequence edge mismatch')
        found={}
        for attachment in attachments:
            if not isinstance(attachment,dict) or not isinstance(attachment.get('row'),dict):raise ValueError('Invalid archive attachment row')
            row=attachment['row'];mid=positive(row['message_id']);size=whole(attachment['bytes']);checksum=attachment['sha256']
            if row.get('tenant_id')!=tenant or 'content' in row or mid in found or images.get(mid)!=checksum or not 8<=size<=MAX_ATTACHMENT_BYTES:raise ValueError('Archive attachment ownership or bound mismatch')
            image_file=checked_file(base,attachment['file'],r'image-[1-9][0-9]*-[a-f0-9]{64}[.]png')
            if image_file.name!=f'image-{mid}-{checksum}.png' or not image_file.is_file() or image_file.stat().st_size!=size:raise ValueError('Archive attachment name or bytes mismatch')
            with image_file.open('rb') as stream:
                if stream.read(8)!=PNG:raise ValueError('Archive PNG header mismatch')
                stream.seek(0)
                if hashlib.file_digest(stream,'sha256').hexdigest()!=checksum:raise ValueError('Archive PNG checksum mismatch')
            found[mid]=image_file;images_total+=size
            if images_total>MAX_V2_ATTACHMENT_BYTES:raise ValueError('Archive attachment total exceeds recovery budget')
        if set(found)!=set(images):raise ValueError('Archive image is missing or duplicated')
        total+=len(messages)
        yield chunk,found
    if total!=manifest['messages'] or images_total!=manifest['attachmentBytes'] or cursor!=manifest['lastId'] or previous!=manifest['lastHash'] or previous!=manifest['conversation'].get('last_hash'):raise ValueError('Archive count, final cursor or lastHash mismatch')

def verify_v2(file):
    _,manifest,checksum=v2_manifest(file);attachments=0
    for chunk,images in v2_chunks(file):attachments+=len(images)
    return {'format':'retention-chunks-v2','toolVersion':3,'tenantId':manifest['tenantId'],'conversationId':manifest['conversation']['id'],'jobId':manifest['jobId'],'messages':manifest['messages'],'attachments':attachments,'attachmentBytes':manifest['attachmentBytes'],'sha256':checksum,'chunks':len(manifest['chunks']),
            'checks':{'tenantRelations':True,'messageChain':True,'lastHash':True,'attachmentBytes':True,'chunkSequence':True,'boundedStreaming':True},'databaseRestored':False}

def verify(file):
    return verify_v2(file) if Path(file).name.startswith('manifest-') else verify_v1(file)

def v2_rows(file,columns):
    _,manifest,_=v2_manifest(file);yield 'support_conversation',typed_row(manifest['conversation'],columns['support_conversation'])
    for chunk,images in v2_chunks(file):
        for row in chunk['messages']:yield 'support_message',typed_row(row,columns['support_message'])
        for attachment in chunk['attachments']:
            raw=images[attachment['row']['message_id']].read_bytes()
            if hashlib.sha256(raw).hexdigest()!=attachment['sha256']:raise ValueError('Attachment changed during recovery')
            row={**attachment['row'],'content':base64.b64encode(raw).decode('ascii')}
            yield 'support_attachment',typed_row(row,columns['support_attachment'])

def dry_run_v2(db,file):
    import mysql_migration as migration
    verified=verify_v2(file);_,manifest,_=v2_manifest(file);tenant=verified['tenantId'];c=manifest['conversation'];columns=db.columns()
    for table in ('support_conversation','support_message','support_attachment'):
        if table not in columns:raise ValueError('Archive schema missing')
    epoch=int(db.query('SELECT MAX(minimum_application_epoch) FROM tenant_schema_version')[0])
    if epoch<migration.EPOCH:raise ValueError('Archive target schema is behind the reviewed epoch')
    if c.get('status')!='CLOSED' or c.get('active_user_id') is not None:raise ValueError('Recovery requires an entire closed conversation without an active-user claim')
    relations=[('tenant',tenant,None),('user_account',positive(c['user_id']),tenant)]
    if c.get('admin_id') is not None:relations.append(('admin_user',positive(c['admin_id']),tenant))
    if c.get('control_actor_id') is not None:relations.append(('control_admin',positive(c['control_actor_id']),None))
    for table,key,owner in relations:
        if db.query('SELECT COUNT(*) FROM '+migration.ident(table)+' WHERE id='+str(key)+((' AND tenant_id='+str(owner)) if owner is not None else ''))!=['1']:raise ValueError('Archive parent is missing or belongs to another tenant')
    # <=100 IDs per collision query; no global message index and no guessed/default tenant.
    pending={t:[] for t in ('support_conversation','support_message','support_attachment')}
    def collision(table,ids):
        key='message_id' if table=='support_attachment' else 'id'
        if ids and db.query('SELECT COUNT(*) FROM '+migration.ident(table)+' WHERE '+migration.ident(key)+' IN ('+','.join(str(positive(i)) for i in ids)+')')!=['0']:raise ValueError('Archive primary key conflict; no overwrite is allowed')
    for table,row in v2_rows(file,columns):
        key='message_id' if table=='support_attachment' else 'id';pending[table].append(row[key])
        if len(pending[table])==100:collision(table,pending[table]);pending[table].clear()
    for table,ids in pending.items():collision(table,ids)
    for chunk,_ in v2_chunks(file):
        senders={'ADMIN':set(),'CONTROL':set(),'SYSTEM':set()}
        for m in chunk['messages']:
            if m['sender']=='USER':
                if m['sender_id']!=c['user_id']:raise ValueError('Archived USER sender is not its conversation owner')
            elif m['sender_id']:senders[m['sender']].add(m['sender_id'])
        for role,ids in senders.items():
            if not ids:continue
            values=','.join(str(i) for i in sorted(ids))
            if role=='SYSTEM':
                query='SELECT COUNT(*) FROM (SELECT id FROM user_account WHERE tenant_id='+str(tenant)+' AND id IN ('+values+') UNION SELECT id FROM admin_user WHERE tenant_id='+str(tenant)+' AND id IN ('+values+')) owned'
            else:query='SELECT COUNT(*) FROM '+('control_admin' if role=='CONTROL' else 'admin_user')+' WHERE id IN ('+values+')'+('' if role=='CONTROL' else ' AND tenant_id='+str(tenant))
            if db.query(query)!=[str(len(ids))]:raise ValueError('Archived sender parent is missing or belongs to another tenant')
    return {**verified,'schemaEpoch':epoch,'checks':{**verified['checks'],'schema':True,'primaryKeys':True,'parentRelations':True}}

def execute_sql_file(db,file):
    result=None
    with Path(file).open('rb') as data:result=subprocess.run(db.command(),stdin=data,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    if result.returncode:
        code=re.search(r'ERROR [0-9]+ [(][^)]+[)]',result.stderr.decode('utf-8',errors='replace'))
        raise RuntimeError('Isolated archive transaction failed: '+(code.group(0) if code else 'mysql exit '+str(result.returncode)))

def restore_v2_rows(db,file,directory):
    import mysql_migration as migration,tempfile,shutil
    verified=dry_run_v2(db,file);columns=db.columns();directory=Path(directory).absolute()
    if not directory.is_dir() or directory.is_symlink() or directory.resolve()!=directory:raise ValueError('Restricted precreated SQL evidence directory required')
    handle,path=tempfile.mkstemp(prefix='archive-transaction-',suffix='.sql',dir=directory);path=Path(path)
    # A disk spool avoids materialising the entire SQL/PNG archive in Python memory.
    with os.fdopen(handle,'w',encoding='utf-8',newline='\n') as sql:
        sql.write("SET time_zone='+00:00'; SET SESSION sql_mode='STRICT_ALL_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE'; START TRANSACTION;\n")
        for table,row in v2_rows(file,columns):
            statement='INSERT INTO '+migration.ident(table)+'('+','.join(migration.ident(k) for k in row)+') VALUES('+','.join(sql_value(v) for v in row.values())+');\n'
            if shutil.disk_usage(directory).free<len(statement.encode('utf-8'))+16*1024*1024:raise ValueError('Insufficient disk budget; source preserved and transaction not applied')
            sql.write(statement)
        sql.write('COMMIT;\n');sql.flush();os.fsync(sql.fileno())
    execute_sql_file(db,path)
    # Every archived field/byte is read back, at most 100 nonbinary rows or one PNG per query.
    current=None;predicates=[]
    def flush():
        if predicates and db.query('SELECT COUNT(*) FROM '+migration.ident(current)+' WHERE '+' OR '.join(predicates))!=[str(len(predicates))]:raise ValueError('Recovered archive field or binary byte mismatch')
    for table,row in v2_rows(file,columns):
        if table!=current:flush();predicates=[];current=table
        dates={c[0] for c in columns[table] if c[1] in ('date','datetime','timestamp')}
        fields=['CAST('+migration.ident(k)+' AS BINARY) <=> CAST('+sql_value(v)+' AS BINARY)' if isinstance(v,(str,bytes)) and k not in dates else migration.ident(k)+' <=> '+sql_value(v) for k,v in row.items()]
        predicates.append('('+' AND '.join(fields)+')')
        if len(predicates)==100 or table=='support_attachment':flush();predicates=[]
    flush()
    return {**verified,'result':'RESTORED_AND_VERIFIED','databaseRestored':True,'checks':{**verified['checks'],'rows':True},'sqlSpoolSha256':migration.file_hash(path)}

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['initialize','verify','restore-to-new-fixture']);parser.add_argument('path',type=Path)
    parser.add_argument('--source-container');parser.add_argument('--source-database');parser.add_argument('--target-container');parser.add_argument('--target-database');parser.add_argument('--evidence-directory',type=Path)
    parser.add_argument('--apply',action='store_true');parser.add_argument('--publish-marker',action='store_true');args=parser.parse_args()
    if args.action=='initialize':initialize(args.path)
    elif args.action=='verify':print(json.dumps(verify(args.path)))
    else:
        import mysql_migration as migration
        if not all((args.source_container,args.source_database,args.target_container,args.target_database)):parser.error('Exact source and independent isolated target are required')
        if args.apply and args.evidence_directory is None:parser.error('Fresh restricted evidence directory required for apply')
        source=migration.Database(args.source_container,args.source_database);target=migration.Database(args.target_container,args.target_database)
        report=restore_to_new_fixture(source,target,args.path,args.evidence_directory,args.apply,args.publish_marker)
        print(json.dumps({k:v for k,v in report.items() if k not in ('performedBy',)},ensure_ascii=False))
