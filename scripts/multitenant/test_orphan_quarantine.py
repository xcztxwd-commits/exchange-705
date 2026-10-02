"""Synthetic real-MySQL rehearsal of six-class archive/delete and exact row restoration."""
import json
import secrets
import subprocess
import sys
from mysql_migration import ROOT, TEST_CONTAINER, MIGRATIONS, Database, seed, fixture_insert, fingerprint, restrict_directory, atomic_json
from orphan_quarantine import TABLES, inventory, apply_fixture


def main():
    run='orphans_'+secrets.token_hex(5);out=ROOT/'rollback/multitenant-20260929/schema'/run;restrict_directory(out)
    db=Database(TEST_CONTAINER,'mt705_'+run);db.create_empty()
    db.sql((ROOT/'scripts/multitenant/legacy-schema.sql').read_text(encoding='utf-8-sig'));db.sql(MIGRATIONS[0].read_text(encoding='utf-8'));seed(db)
    metadata=db.columns()
    for n,table in enumerate(TABLES):
        fixture_insert(db,table,{'id':990001+n,'user_id':990001+n},metadata)
    columns={t:[c[0] for c in fields] for t,fields in metadata.items()}
    before=fingerprint(db,columns);plan=inventory(db)
    assert fingerprint(db,columns)==before
    assert all(p['count']==1 for p in plan['tables'].values())
    checks=['six_exact_orphan_classes','inventory_readonly']
    atomic_json(out/'inventory.json',plan)
    db.sql('UPDATE asset_account SET available=available+1,updated_at=updated_at WHERE id=990001')
    try:apply_fixture(db,plan,out/'stale-rejected')
    except ValueError:checks.append('changed_row_rejected_before_delete')
    else:raise AssertionError('Stale archive accepted')
    db.sql('UPDATE asset_account SET available=available-1,updated_at=updated_at WHERE id=990001')
    original_query=db.query
    def concurrent_parent(sql):
        if 'SET @quarantine_ok=1' in sql:
            fixture_insert(db,'user_account',{'id':990003,'email':'race-parent@fixture.invalid','phone':'fixture-990003','password_hash':'NOT_A_PASSWORD'},metadata)
        return original_query(sql)
    db.query=concurrent_parent
    try:apply_fixture(db,plan,out/'concurrent-parent')
    except ValueError:pass
    else:raise AssertionError('Concurrent parent accepted')
    finally:db.query=original_query
    # Only this synthetic parent is removed, restoring the fixture precondition.
    db.sql('DELETE FROM user_account WHERE id=990003')
    assert fingerprint(db,columns)==before;checks.append('later_table_parent_race_rolls_back_earlier_deletions')
    result=apply_fixture(db,plan,out/'apply')
    assert result['restore_verified'] and result['unrelated_rows_identical']
    assert all(p['count']==0 for p in inventory(db)['tables'].values())
    checks+=['full_database_backup_restored_and_verified','only_six_orphan_rows_removed','unrelated_money_orders_messages_bytes_unchanged']
    cli=subprocess.run([sys.executable,str(ROOT/'scripts/multitenant/orphan_quarantine.py'),'--container',TEST_CONTAINER,'--database',db.database,'--inventory',str(out/'inventory.json'),'--apply','--evidence',str(out/'apply')],capture_output=True,text=True,check=True)
    assert json.loads(cli.stdout)['passed'];checks.append('explicit_apply_cli_resume')
    db.sql((out/'apply/restore-rows.sql').read_text(encoding='utf-8'))
    assert fingerprint(db,columns)==before;checks.append('per_row_archive_restore_exactly_recovers_original_database')
    fixture_insert(db,'user_account',{'id':990001,'email':'recovered-parent@fixture.invalid','phone':'fixture-990001','password_hash':'NOT_A_PASSWORD'},metadata)
    assert inventory(db)['tables']['asset_account']['count']==0;checks.append('now_existing_parent_is_never_orphan')
    try:apply_fixture(db,plan,out/'parent-rejected')
    except ValueError:checks.append('parent_reappearance_rejects_stale_plan')
    else:raise AssertionError('Recovered parent deletion accepted')
    atomic_json(ROOT/'reports/multitenant/orphan-quarantine-rehearsal.json',{'passed':True,'synthetic_only':True,'database':db.database,'checks':checks,'check_count':len(checks),'evidence_directory':str(out),'backup_sha256':result['backup']['sha256'],'row_restore_sha256':result['row_restore_sha256']})
    print('ORPHAN_QUARANTINE_MYSQL_PASS checks='+str(len(checks)))


if __name__=='__main__':main()
