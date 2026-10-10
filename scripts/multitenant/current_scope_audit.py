"""Count current scoped orphans without modifying historical business data.

Legacy preflight deliberately refuses scoped tables. This audit instead checks all
manifest tenant relations and physical FKs in one READ ONLY RR snapshot. Equivalent
checks share one query. Grouping repeated references preserves row counts while
avoiding millions of repeated parent probes on historical tables.
"""
import json
import mysql_migration as core
import controlled_migration as c


def build(db):
    columns = db.columns()
    checks = {}
    unavailable = []

    def add(label, child, parent, pairs, tenant_required=False, nonnull=None):
        pairs = tuple(sorted(set(pairs)))
        nullable = {row[0] for row in columns[child] if row[2]=='YES'}
        nonnull = tuple(sorted(set(field for field,_ in pairs) & nullable if nonnull is None else set(nonnull) & nullable))
        key = (child, parent, pairs, tenant_required, nonnull)
        checks.setdefault(key, []).append(label)

    for table in sorted(core.MANIFEST['private']):
        fields = {row[0] for row in columns.get(table, [])}
        if 'tenant_id' not in fields:
            unavailable.append({'table':table, 'reason':'private tenant column absent'})
        else:
            add('tenant-parent:'+table, table, 'tenant', [('tenant_id','id')], True)
    for table in ('backend_login',):
        fields = {row[0] for row in columns.get(table, [])}
        if 'tenant_id' not in fields:
            unavailable.append({'table':table,'reason':'scoped control tenant column absent'})
        else:
            add('tenant-parent:'+table,table,'tenant',[('tenant_id','id')])
    for child, field, parent, parent_field in core.MANIFEST['relations']:
        child_fields = {row[0] for row in columns.get(child, [])}
        parent_fields = {row[0] for row in columns.get(parent, [])}
        if field not in child_fields or parent_field not in parent_fields:
            unavailable.append({'relation':[child,field,parent,parent_field], 'reason':'manifest field absent'})
            continue
        pairs = [(field,parent_field)]
        if parent in core.MANIFEST['private'] and child in set(core.MANIFEST['private'])|{'backend_login'} and (
                'tenant_id' not in child_fields or 'tenant_id' not in parent_fields):
            unavailable.append({'relation':[child,field,parent,parent_field],'reason':'scoped tenant column absent'})
            continue
        if 'tenant_id' in child_fields and 'tenant_id' in parent_fields:
            pairs.append(('tenant_id','tenant_id'))
        add('relation:'+child+'.'+field+'->'+parent+'.'+parent_field, child, parent, pairs, nonnull=[field])
    rows = db.query('SELECT TABLE_NAME,CONSTRAINT_NAME,COLUMN_NAME,REFERENCED_TABLE_SCHEMA,'
                    'REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE '
                    'WHERE TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL '
                    'ORDER BY TABLE_NAME,CONSTRAINT_NAME,ORDINAL_POSITION')
    foreign = {}
    for row in rows:
        child, name, field, schema, parent, parent_field = row.split('\t')
        foreign.setdefault((child,name,schema,parent), []).append((field,parent_field))
    for (child,name,schema,parent), pairs in foreign.items():
        if schema != db.database:
            unavailable.append({'table':child,'constraint':name,'reason':'cross-database FK'})
        else:
            add('fk:'+child+'.'+name, child, parent, pairs)
    queries = []
    inventory = []
    for number, ((child,parent,pairs,required,nonnull), labels) in enumerate(sorted(checks.items())):
        fields = sorted({field for field,_ in pairs})
        selected = ','.join(core.ident(field) for field in fields)
        where = '' if required or not nonnull else ' WHERE '+' AND '.join(core.ident(field)+' IS NOT NULL' for field in nonnull)
        matches = ' AND '.join('p.'+core.ident(pf)+'=r.'+core.ident(cf) for cf,pf in pairs)
        queries.append('SELECT /*+ MAX_EXECUTION_TIME(60000) */ '+str(number)+',COALESCE(SUM(r.n),0) FROM '
                       '(SELECT '+selected+',COUNT(*) n FROM '+core.ident(child)+where+' GROUP BY '+selected+') r '
                       'WHERE NOT EXISTS(SELECT 1 FROM '+core.ident(parent)+' p WHERE '+matches+');')
        inventory.append({'number':number,'table':child,'parent':parent,'pairs':pairs,'requiredTenant':required,'nonNullFields':nonnull,'labels':labels})
    sql = ('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ; '
           'START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;\n'+'\n'.join(queries)+'\nCOMMIT;\n')
    return sql, inventory, unavailable


def audit(db, directory):
    directory.mkdir(parents=True, exist_ok=True)
    identity = c.target(db)
    source_sha256 = c.sources()
    sql, inventory, unavailable = build(db)
    with (directory/'queries.sql').open('x', encoding='utf-8', newline='\n') as out:
        out.write(sql)
    observed = {}
    try:
        with (directory/'counts.tsv').open('x', encoding='utf-8', newline='\n') as out:
            for row in db.query_stream(sql):
                out.write(row+'\n');out.flush()
                number, count = map(int,row.split('\t'))
                if number in observed or number < 0 or number >= len(inventory) or count < 0:
                    raise ValueError('Malformed, duplicate or extra current audit row')
                observed[number] = count
        if set(observed) != set(range(len(inventory))) or c.target(db) != identity or c.sources()!=source_sha256:
            raise ValueError('Current audit incomplete or target changed')
        proof = {'kind':'CURRENT_SCOPED_READ_ONLY_ORPHAN_AUDIT','target':identity,'source_sha256':source_sha256,
                 'observed_at':c.now().isoformat(),'result':'PASS' if not unavailable and not any(observed.values()) else 'UNRESOLVED',
                 'checks':[dict(item,count=observed[item['number']]) for item in inventory], 'unavailable':unavailable,
                 'sql_sha256':core.file_hash(directory/'queries.sql'),'counts_sha256':core.file_hash(directory/'counts.tsv'),
                 'actual_foreign_key_and_manifest_relations':True,'snapshot':'REPEATABLE READ / READ ONLY',
                 'historical_data_modified':False,'writer_quiescence_proven':False}
        c.publish(directory/'result.json',proof)
        return proof
    except BaseException as error:
        c.publish(directory/'failure.json',{'result':'FAIL_NO_PARTIAL_PASS','error_type':type(error).__name__,
                  'completed_queries':len(observed),'expected_queries':len(inventory),'target':identity,
                  'historical_data_modified':False})
        raise
