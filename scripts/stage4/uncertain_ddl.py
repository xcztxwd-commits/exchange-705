"""Actual lost DDL acknowledgement: reconcile current state, restore it independently, never replay blindly."""
from rehearse import *
from migrate import setup,sign,rejection

def main():
    policy=controlled.Policy(PRIVATE/'controlled/fixture-only-policy.json',True);physical=db('source','mysql');physical.sql('SET GLOBAL read_only=1');result={'status':'INCOMPLETE','checks':[]}
    try:
        source,proposal,target,proof,signed,ledger,folder=setup('uncertain',policy)
        original=source.sql;armed={'value':True}
        def lost(sql,*args,**kw):
            output=original(sql,*args,**kw)
            if armed['value'] and sql==core.MIGRATIONS[0].read_text(encoding='utf-8'):
                armed['value']=False;raise RuntimeError('synthetic lost acknowledgement after actual committed DDL')
            return output
        source.sql=lost
        try:controlled.apply(source,proposal,proof,target,signed,ledger)
        except RuntimeError as e:assert 'lost acknowledgement' in str(e)
        finally:source.sql=original
        assert ledger.latest()['kind']=='FAILED_UNCERTAIN'
        current=controlled.state(source)
        rejection('FAILED_UNCERTAIN denies same-plan blind resume',lambda:controlled.apply(source,proposal,proof,target,signed,ledger,True),result['checks'])
        reference=controlled.Ledger(PRIVATE/'controlled/positive/ledger',policy)
        expected=next(r['state'] for r in reference.rows() if r['kind']=='PHASE_COMPLETE' and r['next']==1)
        assert current==expected and controlled.state(source)==current
        result['checks'].append({'name':'uncertain actual DDL independently matches known phase-1 complete schema and every row; no increment lost','status':'PASS_FRESH'})
        backup=source.dump(folder/'uncertain-current.sql');derived=controlled.restore_input(source,backup,folder/'uncertain-restore-input.sql');fresh=db('restore','mt705_restore_s4_uncertain_current');fresh.create_empty();fresh.sql(Path(derived['path']).read_text(encoding='utf-8'))
        assert controlled.state(fresh)==current and controlled.state(source)==current
        result['checks'].append({'name':'uncertain newest state restored to independent instance; source never overwritten','status':'PASS_FRESH'})
        result.update(status='PARTIAL_BLOCKED',forwardRecovery={'status':'BLOCKED','reason':'controlled_migration only accepts certain PHASE_COMPLETE receipts. No reviewed reconciliation-to-forward-plan entry exists for FAILED_UNCERTAIN. No ledger/approval forged or patched.'},newestStateSha256=controlled.digest(current),snapshotSha256=backup['sha256'],productionDataTouched=False)
    except Exception as e:result.update(status='BLOCKED',failureType=type(e).__name__,failure=str(e));raise
    finally:
        physical.sql('SET GLOBAL read_only=0');save(OUT/'uncertain-ddl.json',result);print(json.dumps({'status':result['status'],'checks':len(result['checks']),'forwardRecovery':result.get('forwardRecovery')},ensure_ascii=False))

if __name__=='__main__':main()
