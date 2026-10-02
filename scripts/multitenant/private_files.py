"""Read-only legacy ownership inventory; explicit copy-and-repoint rehearsal only on isolated fixtures.

Never delete/move originals, infer an uploader, download remote URLs, rewrite support evidence,
or silently choose an owner when a single file is referenced by different security principals.
Detailed plans/backups contain private paths and must remain in the protected rollback directory.
"""
import argparse
import collections
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import sys
from urllib.parse import urlsplit, unquote
from mysql_migration import Database, TEST_CONTAINER, ROOT, ident, literal, file_hash, restrict_directory, atomic_json

# table, column, business category, authoritative owner field (None means tenant-owned, uploader unknown)
REFERENCES = [
 ('kyc_record','id_front_image','kyc','user_id'),('kyc_record','id_back_image','kyc','user_id'),
 ('loan_personal_info','id_front_image','loan_identity','user_id'),('loan_personal_info','id_back_image','loan_identity','user_id'),
 ('loan_personal_info','handheld_image','loan_identity','user_id'),('loan_record','signature_image','loan_signature','user_id'),
 ('deposit_record','proof_image','deposit_proof','user_id'),('financial_product','image_url','public_product',None),
 ('trading_symbol','icon_url','public_symbol',None),('trading_symbol','flag_url','public_symbol',None),
 ('deposit_setting','qr_code','signed_in_payment_qr',None)]
PRIVATE = {'kyc','loan_identity','loan_signature','deposit_proof'}
PREFIXES = ('/api/uploads/','/uploads/','/demo-uploads/')

def relative_upload(value, hosts):
    try:
        url=urlsplit(value)
        if url.query or url.fragment or url.username or url.password: return None,'url_requires_review'
        if url.scheme and (url.scheme not in ('http','https') or (url.hostname or '').lower() not in hosts):
            return None,'external_or_unapproved_host'
        if url.netloc and not url.scheme: return None,'external_or_unapproved_host'
        path=unquote(url.path)
        prefix=next((p for p in PREFIXES if path.startswith(p)),None)
        if not prefix: return None,'not_local_upload'
        if prefix=='/demo-uploads/': return None,'simulation_storage_requires_separate_inventory'
        value=path[len(prefix):]
        parts=value.split('/')
        if len(parts)<2 or parts[0] not in ('images','audio'): return None,'unknown_storage_kind'
        if any(not part or part in ('.','..') or not re.fullmatch(r'[A-Za-z0-9_.-]+',part) for part in parts):
            return None,'unsafe_path'
        return value,None
    except ValueError:return None,'unsafe_url'

def checked_file(root, relative, require_exists=True):
    root=root.absolute();path=root.joinpath(*relative.split('/'))
    if path.resolve().is_relative_to(root.resolve()) is False: raise ValueError('Path escapes reviewed storage root')
    # Do not follow even an internal symlink/reparse point for a migration.
    cursor=path
    while cursor!=root:
        if cursor.is_symlink() or (hasattr(cursor,'is_junction') and cursor.is_junction()):raise ValueError('Linked paths require manual review')
        cursor=cursor.parent
    if require_exists and not path.is_file():raise FileNotFoundError('Referenced source is absent')
    return path

def field_rows(db, table, column, owner, legacy_tenant, columns):
    fields={c[0] for c in columns.get(table,[])}
    if column not in fields:return []
    tenant='tenant_id' if 'tenant_id' in fields else str(legacy_tenant or 'NULL')
    user=ident(owner) if owner else 'NULL'
    query=f"SELECT JSON_OBJECT('table',{literal(table)},'column',{literal(column)},'id',id,'tenant_id',{tenant},'user_id',{user},'url',{ident(column)}) FROM {ident(table)} WHERE {ident(column)} IS NOT NULL AND {ident(column)}<>''"
    return [json.loads(row) for row in db.query(query)]

def inventory(db, storage, legacy_tenant=None, hosts=()):
    if legacy_tenant is not None and legacy_tenant!=1:raise ValueError('Only the explicitly reviewed legacy default tenant 1 is supported')
    columns=db.columns();refs=[];exceptions=[]
    for table,column,category,owner in REFERENCES:
        for row in field_rows(db,table,column,owner,legacy_tenant,columns):
            row['category']=category;refs.append(row)
    # Configuration JSON/HTML may contain several URLs. Discover it, but do not alter opaque configuration.
    if 'system_config' in columns:
        tenant='tenant_id' if any(c[0]=='tenant_id' for c in columns['system_config']) else str(legacy_tenant or 'NULL')
        query=f"SELECT JSON_OBJECT('id',id,'tenant_id',{tenant},'key',config_key,'value',config_value) FROM system_config WHERE config_value LIKE '%/uploads/%' OR config_value LIKE '%/demo-uploads/%'"
        for raw in db.query(query):
            row=json.loads(raw);category='public_brand' if re.search(r'logo|favicon|brand|banner',row['key'],re.I) else 'opaque_config'
            exceptions.append({'table':'system_config','id':row['id'],'tenant_id':row['tenant_id'],'category':category,'status':'configuration_reference_requires_review','value_sha256':hashlib.sha256((row['value'] or '').encode()).hexdigest()})
    # BLOB-backed support attachments are already owned through message/conversation joins, not disk uploads.
    blobs=[]
    if all(t in columns for t in ('support_attachment','support_message','support_conversation')):
        scope='c.tenant_id' if any(c[0]=='tenant_id' for c in columns['support_conversation']) else str(legacy_tenant or 'NULL')
        blobs=[json.loads(x) for x in db.query(f"SELECT JSON_OBJECT('message_id',a.message_id,'tenant_id',{scope},'user_id',c.user_id,'bytes',OCTET_LENGTH(a.content),'sha256',SHA2(a.content,256)) FROM support_attachment a LEFT JOIN support_message m ON m.id=a.message_id LEFT JOIN support_conversation c ON c.id=m.conversation_id")]
        for row in db.query("SELECT JSON_OBJECT('id',id,'content_sha256',SHA2(text,256)) FROM support_message WHERE image=1 AND (text LIKE '%/uploads/%' OR text LIKE '%/demo-uploads/%')"):
            exceptions.append(dict(json.loads(row),table='support_message',category='support_evidence',status='immutable_evidence_reference_requires_review'))
    groups=collections.defaultdict(list)
    for row in refs:
        rel,reason=relative_upload(row['url'],set(hosts))
        if reason:exceptions.append({**{k:v for k,v in row.items() if k!='url'},'url_sha256':hashlib.sha256(row['url'].encode()).hexdigest(),'status':reason});continue
        groups[rel].append(row)
    plan=[]
    for rel,rows in sorted(groups.items()):
        owners={(r['tenant_id'],r['user_id'],'user' if r['category'] in PRIVATE else 'tenant_public') for r in rows}
        entry={'source':rel,'references':rows,'categories':sorted({r['category'] for r in rows})}
        if len(owners)!=1:entry['status']='ambiguous_owner_or_public_private_mix'
        else:
            tenant,user,kind=next(iter(owners))
            if not tenant or (kind=='user' and (not user or user<=0)):entry['status']='missing_authoritative_owner'
            elif kind!='user':entry['status']='tenant_owned_uploader_unknown_requires_review'
            else:
                tenant_condition=' AND tenant_id='+str(tenant) if any(c[0]=='tenant_id' for c in columns['user_account']) else ''
                exists=db.query('SELECT COUNT(*) FROM user_account WHERE id='+str(user)+tenant_condition)==['1']
                entry['status']='ready' if exists else 'orphan_owner_requires_review'
                entry['tenant_id']=tenant;entry['user_id']=user
        try:
            path=checked_file(storage,rel);entry['sha256']=file_hash(path);entry['bytes']=path.stat().st_size
            if entry['status']=='ready':
                suffix=path.suffix.lower()
                if rel.split('/')[0]!='images' or suffix not in ('.jpg','.jpeg','.png','.gif','.webp'):entry['status']='private_media_type_requires_review'
                else:
                    entry['destination']=f"images/{entry['tenant_id']}/user/{entry['user_id']}/{entry['sha256']}{suffix}"
                    entry['new_url']='/api/uploads/'+entry['destination']
                    if rel==entry['destination']:entry['status']='already_scoped'
        except (ValueError,FileNotFoundError):entry['status']='missing_or_linked_source'
        plan.append(entry)
    referenced=set(groups);unreferenced=[]
    if storage.exists():
        for path in storage.rglob('*'):
            if path.is_file():
                rel=path.relative_to(storage).as_posix()
                if rel not in referenced:unreferenced.append({'path_sha256':hashlib.sha256(rel.encode()).hexdigest(),'status':'unreferenced_do_not_publish'})
    return {'format':1,'database':db.identity,'storage_root':str(storage.absolute()),'legacy_tenant_explicit':legacy_tenant,
            'created_at':dt.datetime.now(dt.timezone.utc).isoformat(),'files':plan,'exceptions':exceptions,'database_support_blobs':blobs,'unreferenced':unreferenced}

def summary(plan):
    return {'files':len(plan['files']),'status_counts':dict(collections.Counter(x['status'] for x in plan['files'])),'unreferenced_count':len(plan['unreferenced']),
            'exceptions':dict(collections.Counter(x['status'] for x in plan['exceptions'])),'support_blob_count':len(plan['database_support_blobs'])}

def apply_fixture(db,plan,destination,backup):
    marker=destination/'.multitenant-file-fixture.json'
    if not db.test or db.container!=TEST_CONTAINER or not db.database.startswith('mt705_') or not marker.is_file():raise ValueError('Apply is limited to explicitly marked isolated fixtures')
    mark=json.loads(marker.read_text(encoding='utf-8'))
    if mark!={'container_id':db.identity['container_id'],'database':db.database}:raise ValueError('Fixture target mismatch')
    if plan['database']['container_id']!=db.identity['container_id'] or plan['database']['database']!=db.database:raise ValueError('Inventory target mismatch')
    if not destination.resolve().is_relative_to((ROOT/'rollback/multitenant-20260929').resolve()):raise ValueError('Fixture output must remain in protected rehearsal directory')
    source=Path(plan['storage_root']);restrict_directory(backup)
    receipt=backup/'receipt.json';state=json.loads(receipt.read_text()) if receipt.exists() else {'files':[]}
    if not (backup/'before.sql').exists():db.dump(backup/'before.sql');atomic_json(backup/'inventory-before.json',plan)
    elif json.loads((backup/'inventory-before.json').read_text(encoding='utf-8'))!=plan:raise ValueError('Backup receipt belongs to another inventory')
    columns=db.columns()
    for entry in plan['files']:
        if entry['status']!='ready':continue
        if not isinstance(entry['tenant_id'],int) or entry['tenant_id']<=0 or not isinstance(entry['user_id'],int) or entry['user_id']<=0:raise ValueError('Invalid owner')
        if not re.fullmatch('[0-9a-f]{64}',entry['sha256']):raise ValueError('Invalid content hash')
        suffix=Path(entry['source']).suffix.lower()
        expected=f"images/{entry['tenant_id']}/user/{entry['user_id']}/{entry['sha256']}{suffix}"
        if suffix not in ('.jpg','.jpeg','.png','.gif','.webp') or entry['destination']!=expected or entry['new_url']!='/api/uploads/'+expected:raise ValueError('Unreviewed destination')
        if not entry['references']:raise ValueError('No authoritative file reference')
        for ref in entry['references']:
            if (ref['table'],ref['column'],ref['category'],'user_id') not in REFERENCES or ref['category'] not in PRIVATE:raise ValueError('Unreviewed database field')
            if ref['tenant_id']!=entry['tenant_id'] or ref['user_id']!=entry['user_id']:raise ValueError('Reference ownership differs')
        src=checked_file(source,entry['source'])
        if file_hash(src)!=entry['sha256']:raise ValueError('Source changed after inventory')
        target=checked_file(destination,entry['destination'],False);target.parent.mkdir(parents=True,exist_ok=True)
        checked_file(destination,entry['destination'],False)
        preserve=backup/'files'/entry['sha256'];preserve.parent.mkdir(parents=True,exist_ok=True)
        if not preserve.exists():shutil.copyfile(src,preserve)
        if file_hash(preserve)!=entry['sha256']:raise ValueError('Backup hash mismatch')
        if target.exists():
            if file_hash(target)!=entry['sha256']:raise ValueError('Destination collision; never overwrite')
        else:
            temp=target.with_name(target.name+'.'+secrets.token_hex(6)+'.tmp')
            with temp.open('xb') as out,src.open('rb') as inp:shutil.copyfileobj(inp,out)
            if file_hash(temp)!=entry['sha256']:raise ValueError('Copied hash mismatch')
            # Refuse a race with another destination creator. No replace-overwrite operation.
            os.link(temp,target)
            temp.unlink()
        if file_hash(target)!=entry['sha256']:raise ValueError('Destination verification failed')
        sql=['START TRANSACTION;','SET @files_ok=1;']
        for ref in entry['references']:
            table=ident(ref['table']);col=ident(ref['column']);where=f"id={int(ref['id'])} AND tenant_id={entry['tenant_id']} AND user_id={entry['user_id']}"
            old=literal(ref['url']);new=literal(entry['new_url'])
            keep_time=',updated_at=updated_at' if any(c[0]=='updated_at' for c in columns[ref['table']]) else ''
            sql.extend([f'SELECT id FROM {table} WHERE {where} FOR UPDATE;',f'SET @files_ok=@files_ok AND ((SELECT COUNT(*) FROM {table} WHERE {where} AND (BINARY {col}=BINARY {old} OR BINARY {col}=BINARY {new}))=1);',f'UPDATE {table} SET {col}={new}{keep_time} WHERE {where} AND BINARY {col}=BINARY {old};'])
        sql.extend(["SET @files_action=IF(@files_ok,'COMMIT','ROLLBACK');","PREPARE files_commit FROM @files_action; EXECUTE files_commit; DEALLOCATE PREPARE files_commit;","SELECT @files_ok;"])
        if db.query('\n'.join(sql))[-1:]!=['1']:raise ValueError('Reference changed; entire file-reference transaction rolled back')
        if entry['source'] not in state['files']:state['files'].append(entry['source'])
        atomic_json(receipt,state)
    return {'completed_files':len(state['files']),'originals_retained':True,'database_backup_sha256':file_hash(backup/'before.sql')}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--container',required=True);parser.add_argument('--database',required=True)
    parser.add_argument('--storage-root',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--legacy-tenant',type=int);parser.add_argument('--legacy-host',action='append',default=[])
    parser.add_argument('--apply',action='store_true');parser.add_argument('--destination',type=Path);parser.add_argument('--backup',type=Path)
    args=parser.parse_args();db=Database(args.container,args.database)
    restrict_directory(args.output.parent)
    if args.apply:
        if not args.destination or not args.backup:parser.error('--apply requires reviewed --destination and --backup')
        plan=json.loads(args.output.read_text(encoding='utf-8'))
        if Path(plan['storage_root']).resolve()!=args.storage_root.resolve():raise ValueError('Storage root mismatch')
        print(json.dumps(apply_fixture(db,plan,args.destination,args.backup)))
    else:
        plan=inventory(db,args.storage_root,args.legacy_tenant,args.legacy_host)
        if args.output.exists():raise ValueError('Inventory output exists; choose a new path to preserve evidence')
        atomic_json(args.output,plan);print(json.dumps(summary(plan)))

if __name__=='__main__':
    try:main()
    except Exception as error:
        print('File migration stopped: '+type(error).__name__+'. Inspect protected evidence; no source file was removed.',file=sys.stderr);sys.exit(1)
