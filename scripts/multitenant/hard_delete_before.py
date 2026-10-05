"""Bounded date cleanup. Plans contain private keys; never publish them to Git.

User masters are always protected. Unknown dates are not inferred from identifiers.
Only actual FK/manifest dependencies cascade; historical audit actor IDs stay factual.
"""
import datetime as dt
import json
import re
import mysql_migration as core
import controlled_migration as controlled

LIMIT=10000
CREATION={'created_at','create_time','created_time','createtime','createdat','created'}
# Reviewed creation-equivalent or explicitly identified business/activity timestamps.
ALTERNATIVES={
    'activity_delivery':('sent_at','datetime','last sending'),
    'asset_history_1d':('bucket_end','ms_end','business bucket'),
    'asset_history_1h':('bucket_end','ms_end','business bucket'),
    'asset_history_4h':('bucket_end','ms_end','business bucket'),
    'asset_history_quote_batch':('prepared_at','ms','batch creation'),
    'asset_snapshot':('captured_at','ms','snapshot capture'),
    'deposit_credit_record':('credited_at','datetime','credit receipt'),
    'market_control_publication':('published_at','ms','publication receipt'),
    'market_control_resume':('resumed_at','ms','resume receipt'),
    'market_control_sample':('generated_at','ms','sample timeline'),
    'market_control_task':('started_at','ms','task creation'),
    'market_legacy_minute_snapshot':('minute_at','minute','business minute'),
    'market_mixed_minute':('minute_at','minute','business minute'),
    'market_simulation_source_candle':('candle_at','ms','business candle'),
    'market_source_candle':('received_at','ms','last receipt'),
    'market_source_event':('received_at','ms','first receipt'),
    'market_source_quote':('source_time','ms','last source quote'),
    'market_source_tick':('received_at','ms','first receipt'),
    'support_presence':('heartbeat_at','datetime','last heartbeat'),
}
EXTRA_RELATIONS=[('admin_role_menu','menu_id','admin_menu','id'),
                 ('user_menu','menu_id','admin_menu','id'),
                 ('menu_action','menu_id','admin_menu','id'),
                 ('user_action','menu_id','admin_menu','id')]

def time_condition(field,kind,cutoff):
    utc=cutoff.astimezone(dt.timezone.utc)
    value=core.ident(field)
    if kind=='datetime':
        return value+">='1970-01-01' AND "+value+'<'+core.literal(utc.strftime('%Y-%m-%d %H:%M:%S'))
    ms=int(utc.timestamp()*1000)
    if kind not in ('ms','ms_end','minute'):raise ValueError('Unknown timestamp unit')
    end=value+('+60000' if kind=='minute' else '')
    return value+'>100000000000 AND '+end+('<=' if kind in ('ms_end','minute') else '<')+str(ms)

def key_predicate(keys,rows,alias=''):
    if not rows:return '0=1'
    return '('+' OR '.join('('+' AND '.join(alias+core.ident(k)+'='+core.literal(v) for k,v in zip(keys,row)) +')' for row in rows)+')'

def delete_order(tables,relations):
    remaining=set(tables);result=[]
    while remaining:
        leaves=sorted(t for t in remaining if not any(c in remaining and p==t and c!=p for c,p,pairs in relations))
        if not leaves:raise ValueError('Cyclic cleanup dependencies require explicit review')
        result.extend(leaves);remaining.difference_update(leaves)
    return result

def plan(db,cutoff):
    if cutoff.tzinfo is None:raise ValueError('Timezone-aware cutoff required')
    columns=db.columns();tables=set(columns)
    key_rows=db.query("SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND CONSTRAINT_NAME='PRIMARY' ORDER BY TABLE_NAME,ORDINAL_POSITION")
    keys={t:[] for t in tables}
    for row in key_rows:
        table,column=row.split('\t');keys[table].append(column)
    fields={t:{f[0]:f[1] for f in fs} for t,fs in columns.items()}
    selected={t:{} for t in tables};rules={};unknown=[]
    def select(table,where):
        if not keys[table]:
            if db.query('SELECT COUNT(*) FROM '+core.ident(table)+' WHERE '+where)!=['0']:
                raise ValueError('Selected table has no exact primary key')
            return 0
        key='JSON_ARRAY('+','.join('CAST(c.'+core.ident(k)+' AS CHAR)' for k in keys[table])+')'
        expr='JSON_ARRAY('+','.join('HEX(CAST(c.'+core.ident(f[0])+' AS BINARY))' for f in columns[table])+')'
        sql='SELECT JSON_OBJECT(\'key\','+key+",'sha256',SHA2("+expr+',256)) FROM '+core.ident(table)+' c WHERE '+where+' LIMIT '+str(LIMIT+1)
        rows=db.query(sql)
        if len(rows)>LIMIT:raise ValueError('Cleanup exceeds reviewed bounded row limit')
        before=len(selected[table])
        for row in rows:
            item=json.loads(row);k=tuple(item['key'])
            if len(k)!=len(keys[table]) or any(v is None for v in k):raise ValueError('Invalid primary key')
            if k in selected[table] and selected[table][k]!=item['sha256']:raise ValueError('Source changed during planning')
            selected[table][k]=item['sha256']
        if sum(map(len,selected.values()))>LIMIT:raise ValueError('Cleanup exceeds reviewed bounded row limit')
        return len(selected[table])-before
    for table in sorted(tables):
        if table=='user_account':rules[table]='PROTECTED_ALL_ROWS';continue
        matches=sorted(set(fields[table])&CREATION)
        if len(matches)>1:raise ValueError('Ambiguous creation fields')
        rule=None
        if matches:
            f=matches[0];kind=fields[table][f].split('(')[0]
            if kind in ('datetime','timestamp','date'):kind='datetime'
            elif kind=='bigint' and table in ('asset_history_1m','manual_order_record'):kind='ms'
            else:raise ValueError('Unknown creation-time unit')
            rule=(f,kind,'creation')
        elif table in ALTERNATIVES:rule=ALTERNATIVES[table]
        if rule:
            f,kind,meaning=rule
            if f not in fields[table]:raise ValueError('Reviewed time field missing')
            rules[table]={'field':f,'unit':kind,'meaning':meaning};select(table,time_condition(f,kind,cutoff))
        else:unknown.append(table)
    relations=[]
    for child,field,parent,parent_field in core.MANIFEST['relations']+EXTRA_RELATIONS:
        if child in tables and parent in tables and field in fields[child] and parent_field in fields[parent]:
            relation=(child,parent,[(field,parent_field)])
            if relation not in relations:relations.append(relation)
    actual={}
    for row in db.query("SELECT TABLE_NAME,CONSTRAINT_NAME,COLUMN_NAME,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL ORDER BY TABLE_NAME,CONSTRAINT_NAME,ORDINAL_POSITION"):
        child,name,field,parent,parent_field=row.split('\t')
        actual.setdefault((child,parent,name),[]).append((field,parent_field))
    for (child,parent,name),pairs in actual.items():
        relation=(child,parent,pairs)
        if relation not in relations:relations.append(relation)
    # These no-date rows have unambiguous original parent timestamps, not guessed IDs.
    if 'demo_account' in tables and 'demo_ledger' in tables:
        where="EXISTS(SELECT 1 FROM demo_ledger p WHERE p.user_id=c.user_id AND p.generation=1 AND p.type='SEED' AND "+time_condition('created_at','datetime',cutoff).replace('`created_at`','p.`created_at`')+')'
        select('demo_account',where);rules['demo_account']='original generation=1 SEED creation'
    for table,parent,child_field,parent_field,time_field,kind in [
        ('support_attachment','support_message','message_id','id','created_at','datetime'),
        ('market_control_flow','market_control_task','task_id','id','started_at','ms'),
        ('market_control_hold','market_control_task','task_id','id','started_at','ms'),
        ('market_control_plan','market_control_task','task_id','id','started_at','ms')]:
        if table in tables and parent in tables:
            where='EXISTS(SELECT 1 FROM '+core.ident(parent)+' p WHERE p.'+core.ident(parent_field)+'=c.'+core.ident(child_field)+' AND '+time_condition(time_field,kind,cutoff).replace(core.ident(time_field),'p.'+core.ident(time_field))+')'
            select(table,where);rules[table]={'parent':parent,'field':time_field,'meaning':'parent creation'}
    changed=True
    while changed:
        changed=False
        for child,parent,pairs in relations:
            if not selected[parent]:continue
            join=' AND '.join('c.'+core.ident(cf)+'=p.'+core.ident(pf) for cf,pf in pairs)
            where='EXISTS(SELECT 1 FROM '+core.ident(parent)+' p WHERE '+join+' AND '+key_predicate(keys[parent],selected[parent], 'p.')+')'
            if child=='user_account':
                if db.query('SELECT COUNT(*) FROM user_account c WHERE '+where)!=['0']:raise ValueError('Cleanup would affect protected user master relation')
                continue
            changed=bool(select(child,where)) or changed
    selected={t:[{'key':list(k),'sha256':v} for k,v in sorted(rows.items())] for t,rows in selected.items() if rows}
    if 'user_account' in selected:raise ValueError('User master deletion forbidden')
    return {'format':1,'kind':'OWNER_DATE_HARD_DELETE_PLAN_NOT_APPROVAL','target':controlled.target(db),
            'cutoff':cutoff.isoformat(),'protected':['user_account'],'rules':rules,'unknown_age':sorted(set(unknown)-set(rules)),
            'keys':keys,'selected':selected,'counts':{t:len(r) for t,r in selected.items()},
            'delete_order':delete_order(selected,relations),
            'relations':[[child,parent,[list(pair) for pair in pairs]] for child,parent,pairs in relations],
            'source_sha256':controlled.sources()}

def deletion_sql(proposal):
    if proposal['protected']!=['user_account'] or 'user_account' in proposal['selected']:raise ValueError('Protected user master changed')
    sql=["SET SESSION sql_mode='STRICT_ALL_TABLES'; CREATE TEMPORARY TABLE cleanup_assertion(value BIGINT NOT NULL); START TRANSACTION;"]
    for table in proposal['delete_order']:
        rows=proposal['selected'][table]
        where=key_predicate(proposal['keys'][table],[r['key'] for r in rows])
        sql+=['DELETE FROM '+core.ident(table)+' WHERE '+where+';',
              'INSERT INTO cleanup_assertion VALUES(IF(ROW_COUNT()='+str(len(rows))+',1,NULL));']
    sql+=['COMMIT; SELECT \'COMMITTED_EXACT_BOUNDED_HARD_DELETE\';']
    return '\n'.join(sql)

def retained_fingerprints(db,proposal):
    """Prove surviving original fields, including rows in partly deleted tables."""
    columns=db.columns();queries=[]
    for table,rows in proposal['selected'].items():
        where=key_predicate(proposal['keys'][table],[r['key'] for r in rows])
        expr='JSON_ARRAY('+','.join('HEX(CAST('+core.ident(f[0])+' AS BINARY))' for f in columns[table])+')'
        queries.append('SELECT '+core.literal(table)+',SHA2('+expr+',256) AS row_sha256 FROM '+core.ident(table)+' WHERE NOT '+where+' ORDER BY row_sha256;')
    return core.row_fingerprints(db,queries,proposal['selected'])

def verify_backup(db,proposal,restore,output):
    """Fresh frozen full backup, restored in a physically distinct labelled instance."""
    from pathlib import Path
    controlled.maintenance(db)
    source,other=controlled.target(db),controlled.target(restore)
    def volumes(target):return {m.get('Name') or m.get('Source') for m in target['physical']['mounts']}
    if (source!=proposal['target'] or not restore.test or source['server_uuid']==other['server_uuid']
            or volumes(source)&volumes(other)):
        raise ValueError('Exact source and independent isolated physical restore required')
    if plan(db,dt.datetime.fromisoformat(proposal['cutoff']))!=proposal:
        raise ValueError('Cleanup source/plan changed before backup')
    output=Path(output)
    if output.exists():raise ValueError('Never overwrite a prior restore receipt')
    initial=controlled.state(db)
    backup=db.dump(output.with_suffix('.sql'))
    corrected=controlled.restore_input(db,backup,output.with_name(output.stem+'-restore-input.sql'))
    restore.create_empty();restore.restore_file(corrected['path'])
    restored=controlled.state(restore)
    if initial!=restored or controlled.state(db)!=initial:
        raise ValueError('Frozen source or full restored original fields differ; hard deletion prohibited')
    result={'kind':'FROZEN_SOURCE_FULL_FIELD_RESTORE_FOR_DATE_CLEANUP','result':'PASS',
            'source':source,'restore':other,'plan_sha256':controlled.digest(proposal),
            'initial':initial,'restored':restored,'backup':backup,'restore_input':corrected,
            'created_at':controlled.now().isoformat()}
    controlled.publish(output,result);return result

def apply(db,proposal,restore,proof,instruction,scope,output):
    """Owner-authorized exception, never a signed approval or automatic fixture bypass."""
    from pathlib import Path
    expected='允许数据库先清理所有表的创建时间为2026.9.1日之前的所有数据，是彻底清理硬删除，再分析是否有这问题'
    clarifications=['整理一下没有创建时间字段的表，分析一下是否存在其他适配可用字段，使用其他类似字段',
                    '除了用户数据以外，允许一并删除这些关联记录',
                    '仅保留 user_account 用户主档，其余关联业务记录可按清理范围删除']
    if instruction.get('human_instruction')!=expected or scope.get('human_instructions')!=clarifications:
        raise ValueError('Exact human hard-delete and user-master preservation instructions required')
    if scope.get('protected_table')!='user_account' or scope.get('newer_non_user_dependents_may_be_deleted') is not True:
        raise ValueError('User master preservation scope changed')
    controlled.maintenance(db)
    if (proof.get('kind')!='FROZEN_SOURCE_FULL_FIELD_RESTORE_FOR_DATE_CLEANUP' or proof.get('result')!='PASS'
            or proof['source']!=controlled.target(db) or proof['restore']!=controlled.target(restore)
            or proof['plan_sha256']!=controlled.digest(proposal) or not restore.test
            or proposal['source_sha256']!=controlled.sources()):
        raise ValueError('Frozen independent restore proof not bound to exact source/plan')
    if any(core.file_hash(Path(proof[k]['path']))!=proof[k]['sha256'] for k in ('backup','restore_input')):
        raise ValueError('Original backup or restored input changed')
    if controlled.state(restore)!=proof['restored'] or proof['restored']!=proof['initial'] or controlled.state(db)!=proof['initial']:
        raise ValueError('Full original source or restore evidence changed')
    if plan(db,dt.datetime.fromisoformat(proposal['cutoff']))!=proposal:raise ValueError('Bound delete keys/facts changed')
    expected_retained=retained_fingerprints(db,proposal)
    output=Path(output)
    if output.exists():raise ValueError('Cleanup result already exists; never replay uncertain deletion')
    controlled.publish(output.with_name(output.stem+'-intent.json'),{'kind':'OWNER_DATE_HARD_DELETE_INTENT_NOT_SIGNED_APPROVAL',
                       'plan_sha256':controlled.digest(proposal),'proof_sha256':controlled.digest(proof),
                       'target':proposal['target'],'instruction_sha256':controlled.digest(instruction),
                       'clarifications_sha256':controlled.digest(scope),'at':controlled.now().isoformat()})
    db.sql(deletion_sql(proposal))
    final=controlled.state(db)
    if final['schema']!=proof['initial']['schema']:raise ValueError('Cleanup changed schema; retain freeze and original proof')
    for table,old in proof['initial']['data']['tables'].items():
        expected_fields=expected_retained.get(table,old)
        if final['data']['tables'][table]!=expected_fields:
            raise ValueError('Retained original fields changed; retain freeze, never fake balances/audit')
    remaining=plan(db,dt.datetime.fromisoformat(proposal['cutoff']))
    if remaining['selected']:raise ValueError('Date/dependent cleanup incomplete')
    result={'kind':'OWNER_DATE_HARD_DELETE_COMPLETE_NOT_DEPLOYMENT_ACCEPTANCE','result':'PASS',
            'counts':proposal['counts'],'total':sum(proposal['counts'].values()),'target':proposal['target'],
            'all_original_user_master_fields_identical':True,'all_surviving_original_fields_identical':True,
            'schema_identical':True,'remaining_date_candidates':0,'final':final,'preflight':core.preflight(db),
            'plan_sha256':controlled.digest(proposal),'proof_sha256':controlled.digest(proof),'at':controlled.now().isoformat()}
    controlled.publish(output,result);return result
