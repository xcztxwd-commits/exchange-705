"""Real isolated MySQL copy/repoint/rollback rehearsal. Only synthetic files and rows."""
import base64
import json
from pathlib import Path
import secrets
import subprocess
import sys
from private_files import inventory,apply_fixture,summary,relative_upload
from mysql_migration import ROOT,TEST_CONTAINER,Database,fixture_insert,fingerprint,atomic_json,restrict_directory,file_hash

def main():
    run='files_'+secrets.token_hex(5);root=ROOT/'rollback/multitenant-20260929/schema'/run;restrict_directory(root)
    subprocess.run([sys.executable,str(ROOT/'scripts/multitenant/mysql_migration.py'),'fixture-connection','--output',str(root/'connection.json')],check=True,capture_output=True)
    connection=json.loads((root/'connection.json').read_text());db=Database(TEST_CONTAINER,connection['database']);assert db.test
    source=root/'source';target=root/'target';source.mkdir();target.mkdir();(source/'images').mkdir()
    content=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jD1sAAAAASUVORK5CYII=')
    for name in ('owned.png','shared.png','public.png','unreferenced.png'):(source/'images'/name).write_bytes(content)
    metadata=db.columns()
    fixture_insert(db,'kyc_record',{'id':900001,'tenant_id':2,'user_id':7000010,'id_front_image':'/api/uploads/images/owned.png','id_back_image':'/uploads/images/shared.png'},metadata)
    fixture_insert(db,'kyc_record',{'id':900002,'tenant_id':3,'user_id':7000011,'id_front_image':'/uploads/images/shared.png'},metadata)
    fixture_insert(db,'loan_personal_info',{'id':900003,'tenant_id':2,'user_id':7000010,'id_front_image':'/uploads/images/owned.png','id_back_image':'/uploads/images/missing.png'},metadata)
    db.sql("UPDATE trading_symbol SET icon_url='/uploads/images/public.png' WHERE id=1 AND tenant_id=1")
    before=fingerprint(db);plan=inventory(db,source);assert fingerprint(db)==before
    atomic_json(root/'inventory.json',plan)
    assert summary(plan)['status_counts']=={'missing_or_linked_source':1,'ready':1,'tenant_owned_uploader_unknown_requires_review':1,'ambiguous_owner_or_public_private_mix':1}
    assert len(plan['unreferenced'])==1 and plan['database_support_blobs']
    checks=['dry_run_no_database_mutation','authoritative_same_owner_multiple_references','cross_owner_ambiguity_blocked','public_uploader_not_fabricated','missing_source_blocked','unreferenced_not_published','support_blobs_not_rewritten']
    for value in ['/api/uploads/images/../secret.png','/api/uploads/images/%2e%2e/secret.png','/api/uploads/images/a.png?token=private','https://unapproved.invalid/uploads/images/a.png']:
        assert relative_upload(value,set())[1];checks.append('unsafe_or_external_reference_rejected')
    marker={'container_id':db.identity['container_id'],'database':db.database};atomic_json(target/'.multitenant-file-fixture.json',marker)
    # A stale second reference must roll back the first update in the SAME SQL transaction.
    db.sql("UPDATE loan_personal_info SET id_front_image='/uploads/images/concurrently-changed.png' WHERE tenant_id=2 AND id=900003")
    try:apply_fixture(db,plan,target,root/'backup')
    except ValueError:pass
    else:raise AssertionError('Stale reference accepted')
    assert db.query("SELECT id_front_image FROM kyc_record WHERE tenant_id=2 AND id=900001")==['/api/uploads/images/owned.png'];checks.append('stale_second_reference_rolls_back_entire_file')
    db.sql("UPDATE loan_personal_info SET id_front_image='/uploads/images/owned.png' WHERE tenant_id=2 AND id=900003")
    result=apply_fixture(db,plan,target,root/'backup');assert result['completed_files']==1
    assert apply_fixture(db,plan,target,root/'backup')['completed_files']==1;checks.append('resume_is_idempotent')
    cli=subprocess.run([sys.executable,str(ROOT/'scripts/multitenant/private_files.py'),'--container',TEST_CONTAINER,'--database',db.database,'--storage-root',str(source),'--output',str(root/'inventory.json'),'--apply','--destination',str(target),'--backup',str(root/'backup')],check=True,capture_output=True,text=True)
    assert json.loads(cli.stdout)['completed_files']==1;checks.append('explicit_apply_cli_resume')
    ready=next(e for e in plan['files'] if e['status']=='ready')
    assert file_hash(target/ready['destination'])==ready['sha256']==file_hash(source/'images/owned.png');checks.append('source_backup_destination_sha256_match')
    assert db.query("SELECT id_front_image FROM kyc_record WHERE tenant_id=2 AND id=900001")==[ready['new_url']]
    assert db.query("SELECT id_front_image FROM loan_personal_info WHERE tenant_id=2 AND id=900003")==[ready['new_url']];checks.append('all_authoritative_references_repointed')
    assert len(list((source/'images').glob('*.png')))==4;checks.append('all_original_files_retained')
    (source/'images/owned.png').write_bytes(content+b'changed')
    try:apply_fixture(db,plan,target,root/'backup')
    except ValueError:checks.append('source_changes_rejected')
    else:raise AssertionError('Changed source accepted')
    (source/'images/owned.png').write_bytes(content)
    report={'database':db.database,'synthetic_only':True,'checks':checks,'check_count':len(checks),'summary':summary(plan),'backup_sha256':result['database_backup_sha256'],'protected_evidence':str(root)}
    atomic_json(ROOT/'reports/multitenant/private-files-rehearsal.json',report)
    print('PRIVATE_FILES_MYSQL_PASS checks='+str(len(checks)))

if __name__=='__main__':main()
