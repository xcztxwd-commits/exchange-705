"""Archive exactly six reviewed legacy orphan classes. Default read-only, apply fixture-only.

Never invent parents, disable FKs, truncate tables or touch current business targets.
Back up the complete database and verify an independent restore before any fixture delete.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import secrets
import sys
from mysql_migration import ROOT, TEST_CONTAINER, Database, ident, literal, fingerprint, file_hash, atomic_json, restrict_directory, ensure_test, require_fixture

TABLES = ('asset_account','asset_snapshot','financial_yield_record','transfer_record','user_digital_address','user_menu')
MONEY = {'asset_account':('available','frozen'), 'asset_snapshot':('total',),
         'financial_yield_record':('daily_yield','cumulative_yield'), 'transfer_record':('amount',)}


def protected(path):
    if not path.resolve().is_relative_to((ROOT/'rollback/multitenant-20260929').resolve()):
        raise ValueError('Sensitive quarantine evidence must remain in protected rollback directory')
    restrict_directory(path)


def orphan(table):
    return f"c.user_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM user_account p WHERE p.id=c.user_id)"


def row_hash(fields, alias='c'):
    return "SHA2(JSON_ARRAY("+','.join('HEX(CAST('+alias+'.'+ident(c)+' AS BINARY))' for c in fields)+'),256)'


def inventory(db):
    columns=db.columns()
    if any(any(c[0]=='tenant_id' for c in columns.get(t,[])) for t in TABLES):
        raise ValueError('This reviewed cleanup is legacy-only, not a tenant-migrated database')
    tables={}
    for table in TABLES:
        if table not in columns:raise ValueError('Reviewed source table absent')
        fields=[c[0] for c in columns[table]]
        if not {'id','user_id'}.issubset(fields):raise ValueError('Unexpected source key schema')
        values=','.join(literal(c)+',HEX(CAST(c.'+ident(c)+' AS BINARY))' for c in fields)
        rows=[json.loads(x) for x in db.query(f"SELECT JSON_OBJECT('id',c.id,'user_id',c.user_id,'row_sha256',{row_hash(fields)},'values_hex',JSON_OBJECT({values})) FROM {ident(table)} c WHERE {orphan(table)} ORDER BY c.id")]
        amounts={}
        for column in MONEY.get(table,()):
            if column in fields:
                group='c.coin' if 'coin' in fields else 'c.currency' if 'currency' in fields else literal('UNSPECIFIED_UNIT')
                amounts[column]=[json.loads(x) for x in db.query(f"SELECT JSON_OBJECT('unit',{group},'total',CAST(SUM(c.{ident(column)}) AS CHAR),'rows',COUNT(*)) FROM {ident(table)} c WHERE {orphan(table)} GROUP BY {group}")]
        tables[table]={'rows':rows,'count':len(rows),'amounts':amounts,'columns':columns[table]}
    return {'format':1,'target':db.identity,'tables':tables,'rule':'Non-null user_id whose parent user_account.id does not exist; only six reviewed legacy tables'}


def summary(plan):
    return {t:{'count':p['count'],'amounts':p['amounts']} for t,p in plan['tables'].items()}


def restore_sql(plan):
    sql=['-- Exact archived orphan rows. Isolated recovery only; INSERT refuses existing-key overwrite.','START TRANSACTION;']
    for table,group in plan['tables'].items():
        for row in group['rows']:
            values=[]
            for column,data_type,*_ in group['columns']:
                value=row['values_hex'][column]
                if value is None:values.append('NULL')
                elif value=='':values.append("''")
                elif data_type in ('bit','binary','varbinary','blob','tinyblob','mediumblob','longblob'):values.append('0x'+value)
                else:values.append('CONVERT(0x'+value+' USING utf8mb4)')
            sql.append('INSERT INTO '+ident(table)+' ('+','.join(ident(c[0]) for c in group['columns'])+') VALUES ('+','.join(values)+');')
    sql.append('COMMIT;')
    return '\n'.join(sql)+'\n'


def apply_fixture(db,plan,out,*,fixture_container_id=None,restore_db=None):
    require_fixture(db,fixture_container_id)
    if fixture_container_id is not None:
        if restore_db is None:raise ValueError('Explicit independent restore target required')
        require_fixture(restore_db,restore_db.identity['container_id'])
        if restore_db.identity['container_id']==db.identity['container_id'] or restore_db.sql('SELECT @@server_uuid',database=False).stdout==db.sql('SELECT @@server_uuid',database=False).stdout:
            raise ValueError('Independent restore instance required')
    if plan['target']!=db.identity or tuple(plan['tables'])!=TABLES:raise ValueError('Reviewed inventory target differs')
    protected(out)
    if (out/'result.json').exists():
        result=json.loads((out/'result.json').read_text(encoding='utf-8'))
        if result['inventory_sha256']!=hashlib.sha256(json.dumps(plan,sort_keys=True).encode()).hexdigest():raise ValueError('Receipt belongs to another inventory')
        if fingerprint(db,result['after']['columns'])!=result['after']:raise ValueError('Database changed after cleanup; fresh inventory required')
        return result
    if inventory(db)!=plan:raise ValueError('Orphan set or row contents changed; no deletions')
    all_columns={t:[c[0] for c in fields] for t,fields in db.columns().items()}
    before=fingerprint(db,all_columns)
    if (out/'before.sql').exists():raise ValueError('Interrupted attempt preserved; choose fresh evidence directory')
    backup=db.dump(out/'before.sql')
    restored=restore_db if restore_db is not None else Database(TEST_CONTAINER,'mt705_quarantine_restore_'+secrets.token_hex(5));restored.create_empty()
    restored.restore_file(out/'before.sql')
    if fingerprint(restored,all_columns)!=before:raise ValueError('Full backup restoration mismatch; no deletions')
    atomic_json(out/'archive.json',plan)
    (out/'restore-rows.sql').write_text(restore_sql(plan),encoding='utf-8')
    expected=copy.deepcopy(before)
    for table,group in plan['tables'].items():
        ids=','.join(str(int(r['id'])) for r in group['rows'])
        if ids:
            hashes=db.query(f"SELECT {row_hash(all_columns[table])} FROM {ident(table)} c WHERE c.id NOT IN ({ids})")
            expected['tables'][table]={'rows':len(hashes),'sha256':hashlib.sha256('\n'.join(sorted(hashes)).encode()).hexdigest()}
    # Lock missing parent key ranges plus source rows; compare exact archived row hashes in one transaction.
    sql=['SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;','START TRANSACTION;','SET @quarantine_ok=1;']
    for table,group in plan['tables'].items():
        rows=group['rows']
        if not rows:continue
        users=','.join(str(int(r['user_id'])) for r in rows)
        ids=','.join(str(int(r['id'])) for r in rows)
        matches=' OR '.join(f"(c.id={int(r['id'])} AND {row_hash(all_columns[table])}={literal(r['row_sha256'])})" for r in rows)
        exact=f"({matches}) AND {orphan(table)}"
        sql += [f'SELECT id FROM user_account WHERE id IN ({users}) FOR UPDATE;',
                f'SELECT id FROM {ident(table)} WHERE id IN ({ids}) FOR UPDATE;',
                f'SET @quarantine_ok=@quarantine_ok AND ((SELECT COUNT(*) FROM {ident(table)} c WHERE {exact})={len(rows)});',
                f'DELETE c FROM {ident(table)} c WHERE @quarantine_ok=1 AND {exact};',
                f'SET @quarantine_ok=@quarantine_ok AND (ROW_COUNT()={len(rows)});']
    sql += ["SET @action=IF(@quarantine_ok,'COMMIT','ROLLBACK');",'PREPARE quarantine_finish FROM @action; EXECUTE quarantine_finish; DEALLOCATE PREPARE quarantine_finish;','SELECT @quarantine_ok;']
    if db.query('\n'.join(sql))[-1:]!=['1']:raise ValueError('Concurrent owner/row change; entire cleanup rolled back')
    after=fingerprint(db,all_columns)
    if after!=expected:raise ValueError('Unexpected non-target change; retain maintenance and recovery evidence')
    result={'target':db.identity,'counts_and_amounts':summary(plan),'backup':backup,'restore_verified':True,'restore_database':restored.database,'restore_identity':restored.identity,
            'inventory_sha256':hashlib.sha256(json.dumps(plan,sort_keys=True).encode()).hexdigest(),'archive_sha256':file_hash(out/'archive.json'),
            'row_restore_sha256':file_hash(out/'restore-rows.sql'),'before':before,'after':after,'unrelated_rows_identical':True,'passed':True}
    atomic_json(out/'result.json',result)
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--container',required=True);p.add_argument('--database',required=True);p.add_argument('--inventory',type=Path,required=True)
    p.add_argument('--apply',action='store_true');p.add_argument('--evidence',type=Path)
    args=p.parse_args();db=Database(args.container,args.database);protected(args.inventory.parent)
    if args.apply:
        if not args.evidence:p.error('--apply requires --evidence')
        result=apply_fixture(db,json.loads(args.inventory.read_text(encoding='utf-8')),args.evidence)
        print(json.dumps({'passed':result['passed'],'restore_verified':True,'unrelated_rows_identical':True,'tables':result['counts_and_amounts']}))
    else:
        if args.inventory.exists():raise ValueError('Inventory exists; preserve evidence and choose new path')
        plan=inventory(db);atomic_json(args.inventory,plan);print(json.dumps(summary(plan)))


if __name__=='__main__':
    try:main()
    except Exception as e:print('Quarantine stopped: '+type(e).__name__+'; inspect protected evidence. No unrestricted cleanup is provided.',file=sys.stderr);sys.exit(1)
