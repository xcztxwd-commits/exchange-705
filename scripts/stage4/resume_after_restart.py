"""Resume the existing three-phase ledger only; compensate proven MySQL 5.7 sequence metadata loss."""
from rehearse import *
from migrate import sign,rejection

def main():
    folder=PRIVATE/'controlled/positive';policy=controlled.Policy(PRIVATE/'controlled/fixture-only-policy.json',True);ledger=controlled.Ledger(folder/'ledger',policy)
    source=db('source','mt705_s4_legacy_positive');proposal=read(folder/'plan.json');plan=read(OUT/'restart-counter-plan.json');result=read(OUT/'controlled-migration.json')
    backup=OUT/'controlled-migration.before-counter-compensation.json';assert not backup.exists();backup.write_bytes((OUT/'controlled-migration.json').read_bytes())
    assert plan['status']=='READY_FOR_FIXTURE_FORWARD_COMPENSATION' and not plan['unresolved'] and plan['sourceId']==source.identity['container_id']
    before=controlled.state(source);expected=ledger.latest()['state'];assert before['data']==expected['data']
    columns=source.columns();statements=[]
    for row in plan['counterOnlyChanges']:
        t=row['table'];key=next(c[0] for c in columns[t] if 'auto_increment' in c[-1])
        minimum=int(source.query('SELECT COALESCE(MAX('+core.ident(key)+'),0)+1 FROM '+core.ident(t))[0]);assert row['nextId']>=minimum
        statements.append('ALTER TABLE '+core.ident(t)+' AUTO_INCREMENT='+str(row['nextId'])+';')
    source.sql('SET GLOBAL read_only=1')
    try:
        source.sql('\n'.join(statements));assert controlled.state(source)==expected
        result['checks'].append({'name':'29 MySQL 5.7 restart counters restored to hash-proven prior DDL, never below MAX(id)+1; complete state equals ledger','status':'PASS_FRESH','tables':len(statements)})
        fresh=db('restore','mt705_restore_s4_resume')
        newest=controlled.verify_backup(source,proposal,fresh,folder/'partial-proof.json',ledger)
        renewed=sign(policy,proposal,newest);controlled.publish(folder/'renewed-fixture-only-approval.json',renewed)
        controlled.apply(source,proposal,newest,fresh,renewed,ledger,True)
        phases=[r for r in ledger.rows() if r['kind']=='PHASE_COMPLETE']
        assert [r['next'] for r in phases]==list(range(1,len(core.MIGRATIONS)+1))
        assert controlled.digest(controlled.preserved(source,proposal['preservation_columns']))==proposal['preserved_sha256']
        assert source.query('SELECT MIN(business_activation_ready+0) FROM tenant_schema_version')==['0']
        result['checks'].append({'name':'restart plus newest backup approval resume; every current DDL phase exactly once, original facts preserved','status':'PASS_FRESH','phases':len(phases)})
        rejection('COMPLETE cannot replay',lambda:controlled.apply(source,proposal,newest,fresh,renewed,ledger,True),result['checks'])
        counts={};queries=[]
        for t in core.MANIFEST['private']:
            if t in source.columns():queries.append('SELECT '+core.literal(t)+',COUNT(*),COALESCE(SUM(tenant_id<>1 OR tenant_id IS NULL),0) FROM '+core.ident(t)+';')
        for line in source.query('\n'.join(queries)):
            t,n,bad=line.split('\t');assert bad=='0';counts[t]=int(n)
        result['defaultTenantCounts']=counts;result['relationCounts']=relation_counts(source);assert not any(result['relationCounts'].values())
        result['checks'].append({'name':'all historical private rows mapped to default tenant; complete known relation matrix','status':'PASS_FRESH'})
        b=read(BASE);assert sha(b['jar']['path'])==b['jar']['sha256'];epoch=controlled.package_epoch(Path(b['jar']['path']));assert epoch==core.EPOCH;result['actualCurrentPackageGuard']=core.guard(source,epoch)
        old=Path(r'C:\workspace\fx\new\mt705-cont-20261001-r1\s4i-owned-candidate\exchange-backend\target\exchange-backend-0.0.1-SNAPSHOT.jar');old_epoch=controlled.package_epoch(old)
        rejection('actual previous artifact epoch rejected',lambda:core.guard(source,old_epoch),result['checks']);result['oldArtifact']={'path':str(old),'sha256':sha(old),'epoch':old_epoch}
        rejection('unapproved activation stays closed',lambda:core.guard(source,core.EPOCH,True),result['checks'])
        copied=source.dump(folder/'final.sql');inp=controlled.restore_input(source,copied,folder/'final-restore-input.sql');final=db('restore','mt705_restore_s4_final');final.create_empty();final.sql(Path(inp['path']).read_text(encoding='utf-8'))
        assert controlled.state(final)==controlled.state(source)
        result['checks'].append({'name':'post-migration full DDL, rows, messages and attachment bytes independently restored','status':'PASS_FRESH'})
        result.update(status='PASS_FRESH',finalStateSha256=controlled.digest(controlled.state(source)),failure=None,failureType=None,lastLedger={'kind':'COMPLETE','phaseCount':len(phases)})
    except Exception as e:
        result.update(status='BLOCKED',failureType=type(e).__name__,failure=str(e));raise
    finally:
        source.sql('SET GLOBAL read_only=0');save(OUT/'controlled-migration.json',result);print(json.dumps({'status':result['status'],'checks':len(result['checks']),'failure':result.get('failure')},ensure_ascii=False))

if __name__=='__main__':main()
