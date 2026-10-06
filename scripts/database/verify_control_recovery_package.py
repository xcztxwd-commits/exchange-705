"""Owned MySQL 5.7 two-tail/schema restore proof; never production approval or data restore.

Use the immutable, structure-only 0603 snapshot as --baseline. Output is exclusive;
--write-snapshot publishes only after two stable exports and a new empty-DB roundtrip.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time

BASELINE_SHA256='3d4fae2f121413c73ad809a0a23654db99121a961a67f1242c3b0ec0ddf1506b'
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/multitenant'))
import controlled_migration as c


class PackageDatabase(c.core.Database):
    """Batch actual SHOW CREATE transport, not cached state across phase boundaries."""
    def tables(self):
        tables=super().tables()
        frames={};current=None
        for row in super().query(';'.join('SHOW CREATE TABLE '+c.core.ident(t) for t in tables)):
            header=re.match(r'^([A-Za-z0-9_]+)\tCREATE TABLE ',row)
            if header:
                current=header[1]
                if current in frames:raise ValueError('Duplicate actual table definition')
                frames[current]=[]
            if current is None:raise ValueError('Unrecognized actual SHOW CREATE framing')
            frames[current].append(row)
        if set(frames)!=set(tables):raise ValueError('Missing actual table definition')
        self._definitions=frames
        return tables

    def query(self,sql):
        if sql.startswith('SHOW CREATE TABLE '):
            name=sql.removeprefix('SHOW CREATE TABLE ').strip('`')
            if name not in self._definitions:raise ValueError('Unknown table in current actual schema capture')
            return self._definitions[name]
        return super().query(sql)


def docker(*args,data=None):
    value=subprocess.run(['docker',*args],input=data,capture_output=True,timeout=120)
    if value.returncode:raise RuntimeError('Owned Docker/MySQL command failed: '+value.stderr.decode(errors='replace')[-1200:])
    return value.stdout


def write(path,text):
    temporary=path.with_name(path.name+'.next')
    temporary.write_text(text,encoding='utf-8',newline='\n');os.replace(temporary,path)


def metadata(db):
    triggers={x['name']:x['mode'] for x in map(json.loads,db.query("SELECT JSON_OBJECT('name',TRIGGER_NAME,'mode',SQL_MODE) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()"))}
    routines={x['type']+':'+x['name']:x['mode'] for x in map(json.loads,db.query("SELECT JSON_OBJECT('name',ROUTINE_NAME,'type',ROUTINE_TYPE,'mode',SQL_MODE) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE()"))}
    return triggers,routines


def export(db):
    sql=docker('exec',db.container,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --no-data --routines --triggers --events --skip-lock-tables --skip-comments --skip-add-drop-table --no-tablespaces --default-character-set=utf8mb4 --set-gtid-purged=OFF '+db.database).decode('utf-8')
    triggers,routines=metadata(db)
    # Reuse the strict recognized MySQL SQL_MODE header parser; never alter stored bodies.
    temporary=db._output/(db.database+'-raw.sql');temporary.write_text(sql,encoding='utf-8')
    corrected=db._output/(db.database+'-'+secrets.token_hex(4)+'-corrected.sql')
    c.stream_restore_trigger_sql_modes(temporary,corrected,triggers,routines)
    sql=corrected.read_text(encoding='utf-8')
    sql=re.sub(r'DEFINER=`[^`]+`@`[^`]+`','DEFINER=CURRENT_USER',sql)
    sql=re.sub(r'\) ENGINE=([^\n;]+?) AUTO_INCREMENT=\d+',r') ENGINE=\1',sql)
    # Keep the reviewed empty user-account floor, not a live increment counter.
    sql=re.sub(r'(CREATE TABLE `user_account` \(.*?\) ENGINE=InnoDB)(?= DEFAULT)',r'\1 AUTO_INCREMENT=7000001',sql,flags=re.S)
    return sql.rstrip()+'\n'


def normalized(sql):
    return '\n'.join(line for line in sql.splitlines() if not line.startswith('--')).strip()+'\n'


def counts(sql):
    blocks=re.findall(r'^CREATE TABLE `\w+` \(\n(.*?)^\) ENGINE=[^\n]+;',sql,re.M|re.S)
    return {'tables':len(blocks),'columns':sum(len(re.findall(r'^  `\w+` ',x,re.M)) for x in blocks),
            'indexes':sum(len(re.findall(r'^  (?:PRIMARY KEY|(?:UNIQUE |FULLTEXT |SPATIAL )?KEY)\b',x,re.M)) for x in blocks),
            'foreign_keys':sum(len(re.findall(r'^  CONSTRAINT .* FOREIGN KEY ',x,re.M)) for x in blocks),
            'triggers':len(re.findall(r'/\*!50003 TRIGGER `?[A-Za-z0-9_]+`?(?=\s)',sql)),
            'routines':len(re.findall(r'^CREATE DEFINER=CURRENT_USER (?:PROCEDURE|FUNCTION) `\w+`',sql,re.M)),
            'views':0,'events':0}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--write-snapshot',action='store_true')
    args=parser.parse_args();output=args.output.resolve();output.mkdir(parents=True,exist_ok=False)
    baseline=args.baseline.resolve();old=baseline.read_text(encoding='utf-8')
    if c.core.file_hash(baseline)!=BASELINE_SHA256 or 'schema epoch 2026100603' not in old or 'history_pending_until' in old or 'retry_count' in old:
        raise ValueError('Exact immutable structure-only 0603 baseline required')
    owner=secrets.token_hex(16);run='package0702-'+secrets.token_hex(8);containers=[];result={'productionApproval':False,'productionOrApplicationAcceptance':False}
    try:
        for role in ('source','restore'):
            name='mt705-'+run+'-'+role;password=secrets.token_hex(24)
            identifier=docker('run','-d','--name',name,'--network','none','--memory','1g','--memory-swap','1g','--cpus','1','--pids-limit','128',
                '--label','com.gtcfesk.multitenant.test=true','--label','com.gtcfesk.joint.owner='+owner,
                '--label','com.gtcfesk.stage1.run='+run,'-e','MYSQL_ROOT_PASSWORD='+password,'mysql:5.7','--innodb-use-native-aio=0').decode().strip()
            if not re.fullmatch('[a-f0-9]{64}',identifier):raise ValueError('Full owned container ID required')
            containers.append((identifier,name));deadline=time.monotonic()+120
            while True:
                try:
                    docker('exec',identifier,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 --protocol=TCP -uroot -N -e "SELECT 1"');break
                except RuntimeError:
                    if time.monotonic()>deadline:raise
                    time.sleep(1)
        source=PackageDatabase(containers[0][0],'mt705_package_source');restore=PackageDatabase(containers[1][0],'mt705_package_restore')
        source._output=restore._output=output
        source.create_empty();source.restore_file(baseline)
        # Synthetic data only in this owned fixture. A legal max-ID anchor preserves
        # MySQL 5.7's reviewed 7000001 counter through restart without ALTERing it.
        source.sql("INSERT INTO tenant(id,code,name,created_at) VALUES(1,'package-fixture','Owned package fixture','2026-10-07 00:00:00'); "
                   "INSERT INTO user_account(id,email,password_hash,row_version,tenant_id) "
                   "VALUES(7000000,'package-fixture@example.invalid','FIXTURE_ONLY_NOT_AUTHENTICATABLE',0,1)")
        before=c.schema(source)
        source.sql("INSERT INTO tenant_schema_version VALUES(2026100603,'2026-10-07 00:00:00.000000',2026100603,0)")
        preservation=c.all_fields(source)['columns']
        preserved=c.preserved(source,preservation,c.CONTROL0702_METADATA)
        c.metadata_absent_before_plan(source,c.CONTROL0702_METADATA)
        tails=[c.core.ROOT/'exchange-backend/src/main/resources/db/migration'/name for name in c.CONTROL0702_TAIL]
        source.sql(tails[0].read_text(encoding='utf-8'));c.metadata_receipt_after_phase(source,c.CONTROL0702_METADATA,tails[0].name)
        if c.preserved(source,preservation,c.CONTROL0702_METADATA)!=preserved:raise AssertionError('0701 changed prior metadata')
        phase_one=c.state(source)
        write(output/'0701-state.json',json.dumps(phase_one,indent=2)+'\n')
        backup=source.dump(output/'0701-full-fixture.sql');restore_input=c.restore_input(source,backup,output/'0701-full-restore-input.sql')
        restore.create_empty();restore.restore_file(restore_input['path'])
        if c.state(restore)!=phase_one:raise AssertionError('Independent 0701 full restore differs')
        # Restart the actual owned MySQL process after the committed phase; never replay 0701.
        docker('restart',source.container)
        deadline=time.monotonic()+120
        while True:
            try:
                source.query('SELECT 1');break
            except RuntimeError:
                if time.monotonic()>deadline:raise
                time.sleep(1)
        restarted=c.state(source)
        write(output/'0701-restarted-state.json',json.dumps(restarted,indent=2)+'\n')
        if restarted!=phase_one:raise AssertionError('0701 restart changed strict raw schema/data state')
        c.metadata_receipt_after_phase(source,c.CONTROL0702_METADATA,tails[0].name)
        source.sql(tails[1].read_text(encoding='utf-8'));c.metadata_receipt_after_phase(source,c.CONTROL0702_METADATA,tails[1].name)
        if c.preserved(source,preservation,c.CONTROL0702_METADATA)!=preserved:raise AssertionError('0702 changed prior metadata')
        after=c.schema(source)
        if any(before['objects'][key]!=after['objects'][key] for key in before['objects'] if key not in {'table:market_control_command','table:market_control_flow'}):
            raise AssertionError('Additive migration changed unrelated table/trigger/routine definitions')
        if c.core.guard(source,2026100603)['passed'] is not True:raise AssertionError('0603 rollback package no longer schema-compatible')
        sql=export(source)
        if sql!=export(source):raise AssertionError('Source DDL changed during capture')
        sql='-- Exchange 705 structure-only snapshot 2026-10-07; schema epoch 2026100702.\n-- No business data, migration receipts or activation approval.\n\n'+sql
        published=output/'schema.sql';published.write_text(sql,encoding='utf-8',newline='\n')
        fresh=PackageDatabase(containers[1][0],'mt705_package_empty');fresh._output=output;fresh.create_empty();fresh.restore_file(published)
        table_rows=fresh.query(' UNION ALL '.join('SELECT '+c.core.literal(t)+',COUNT(*) FROM '+c.core.ident(t) for t in fresh.tables()))
        if any(line.split('\t')[1]!='0' for line in table_rows):raise AssertionError('Public snapshot contains data')
        if normalized(sql)!=normalized(export(fresh)):raise AssertionError('Fresh empty schema DDL roundtrip differs')
        structure=counts(sql)
        result.update(result='PASS',baselineSha256=c.core.file_hash(baseline),schema_epoch=2026100702,
            counts=structure,full0701Restore='PASS',phaseBoundaryRestartDataAndDefinitions='PASS',
            strictRawStateRestart='PASS',legalSyntheticUserFloorAnchor=True,allOriginalColumnFactsPreserved=True,
            originalMetadataPreserved=True,
            unrelatedDefinitionsUnchanged=True,all161TriggersPreserved=True,newReceiptInactive=True,minimumApplicationEpoch=2026100603,
            emptyTableRows=0,normalizedDdlRoundtrip='PASS',normalizedDdlSha256=hashlib.sha256(normalized(sql).encode()).hexdigest(),
            migrations=[{'name':p.name,'sha256':c.core.file_hash(p)} for p in tails],
            ownedContainerIds=[x[0] for x in containers],owner=owner)
        if args.write_snapshot:
            folder=ROOT/'docs/database';dictionary=(folder/'data-dictionary.md').read_text(encoding='utf-8')
            if '结构版本：`2026100603`' not in dictionary:
                raise ValueError('Public dictionary is no longer 0603; refuse duplicate or concurrent publication')
            dictionary=dictionary.replace('2026-10-06','2026-10-07').replace('2026100603','2026100702').replace('1333','1338')
            dictionary=dictionary.replace('对应业务源码提交：`8679fff2ead54e53a90757366ab6a2e9270af763`。','对应合并候选源码；未宣称已发布或获得生产批准。')
            columns=source.query("SELECT JSON_OBJECT('table',TABLE_NAME,'column',COLUMN_NAME,'type',COLUMN_TYPE,'nullable',IS_NULLABLE,'default',COLUMN_DEFAULT,'extra',EXTRA,'collation',COLLATION_NAME,'comment',COLUMN_COMMENT) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME IN ('retry_count','retry_at','history_pending_until','history_retry_at','history_error') ORDER BY TABLE_NAME,ORDINAL_POSITION")
            for table in ('market_control_command','market_control_flow'):
                start=dictionary.index('## '+table+'\n');end=dictionary.find('\n## ',start+1)
                section=dictionary[start:end if end!=-1 else None];lines=[]
                for row in map(json.loads,columns):
                    if row['table']==table:
                        default='NULL' if row['default'] is None else json.dumps(row['default'],ensure_ascii=False)
                        lines.append('| `'+row['column']+'` | `'+row['type']+'` | '+row['nullable']+' | '+default+' | '+row['extra']+' | '+(row['collation'] or '—')+' | '+row['comment']+' |')
                section=section.replace('\n\n### 索引','\n'+'\n'.join(lines)+'\n\n### 索引',1)
                dictionary=dictionary[:start]+section+dictionary[end if end!=-1 else len(dictionary):]
                old_count=20 if table=='market_control_command' else 10
                dictionary=dictionary.replace('| ['+table+'](#'+table+') | 租户私有 | '+str(old_count)+' |','| ['+table+'](#'+table+') | 租户私有 | '+str(old_count+len(lines))+' |')
            write(folder/'schema.sql',sql);write(folder/'data-dictionary.md',dictionary)
            manifest=json.loads((folder/'schema-manifest.json').read_text(encoding='utf-8'))
            manifest.update(snapshot_date='2026-10-07',schema_epoch=2026100702,source_code_commit=None,
                source_kind='Merged candidate; immutable 0603 structure imported into owned MySQL 5.7; exact 0701/0702 executed; data-free stable capture; not production approval',counts=structure)
            manifest['migrations']=c.migrations()
            required=list(manifest['sha256'])+['exchange-backend/src/main/resources/db/migration/'+name for name in c.CONTROL0702_TAIL]
            manifest['sha256']={name:c.core.file_hash(ROOT/name) for name in required}
            manifest['verification'].update(normalized_ddl_sha256=result['normalizedDdlSha256'],tail_migrations=result['migrations'],
                phase_boundary_full_restore='PASS',phase_boundary_mysql_restart_data_definitions='PASS',
                strict_raw_state_restart=result['strictRawStateRestart'],legal_synthetic_fixture_floor_anchor=True,
                original_metadata_preserved=True,
                minimum_application_epoch=2026100603,new_tail_receipt_inactive=True,production_approval=False)
            write(folder/'schema-manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    except BaseException as error:
        result.update(result='FAIL',errorType=type(error).__name__,error=str(error))
        raise
    finally:
        for identifier,name in reversed(containers):
            item=json.loads(docker('inspect',identifier))[0]
            if item['Id']!=identifier or item['Name']!='/'+name or item['Config']['Labels'].get('com.gtcfesk.joint.owner')!=owner:
                raise ValueError('Refuse cleanup: owned full container identity changed')
            docker('rm','-f','-v',identifier)
        result['ownedFixturesCleaned']=True
        write(output/'result.json',json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False))


if __name__=='__main__':main()
