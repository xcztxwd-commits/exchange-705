"""Reviewed forward recovery of a fully committed uncertain DDL boundary only.

No object-name inference, failure receipt rewriting, source restoration or signing.
Partial/unknown outcomes remain blocked. Reference evidence is a trusted signed
execution with identical before-state and migration bytes on an independent target.
"""
import argparse
from pathlib import Path
import secrets
import controlled_migration as c


def boundary(original, failed, reference_plan, reference):
    rows=failed.rows(); witnesses=reference.rows()
    if len(rows)<3 or rows[-1]['kind']!='FAILED_UNCERTAIN' or rows[-2]['kind']!='INTENT':
        raise ValueError('Retained FAILED_UNCERTAIN and adjacent INTENT required')
    last,intent=rows[-1],rows[-2]; index=last['index']
    if type(index) is not int or not original['start']<=index<len(original['migrations']):
        raise ValueError('Invalid uncertain phase')
    for row in rows:
        if row['plan_sha256']!=c.digest(original) or row['target']!=original['target']:
            raise ValueError('Failed journal is not bound to the original plan/target')
    prior=rows[-3]
    if (prior['kind'] not in ('BEGIN','PHASE_COMPLETE','RESUME_VERIFIED') or prior['next']!=index
            or prior['state']!=intent['before'] or intent['index']!=index
            or intent['migration']!=original['migrations'][index]):
        raise ValueError('Uncertain intent does not follow a certain boundary')
    if (original['migrations']!=c.migrations() or reference_plan['migrations']!=original['migrations']
            or original['schema_epoch']!=c.core.EPOCH or reference_plan['schema_epoch']!=c.core.EPOCH
            or reference_plan['initial']!=original['initial']
            or reference_plan['source_sha256']!=original['source_sha256']
            or reference_plan['target']['server_uuid']==original['target']['server_uuid']):
        raise ValueError('Independent reference inputs/DDL/epoch differ')
    matches=[]
    for n,row in enumerate(witnesses):
        if row['plan_sha256']!=c.digest(reference_plan) or row['target']!=reference_plan['target']:
            raise ValueError('Reference journal is not bound to its plan/target')
        if row['kind']=='PHASE_COMPLETE' and row['next']==index+1:
            before=witnesses[n-1] if n else {}
            if (before.get('kind')!='INTENT' or before.get('index')!=index
                    or before.get('before')!=intent['before']
                    or before.get('migration')!=intent['migration'] or row['migration']!=intent['migration']):
                raise ValueError('Reference execution lacks identical before-state/DDL')
            matches.append(row)
    if len(matches)!=1 or index+1>=len(original['migrations']):
        raise ValueError('Exactly one known completed boundary with remaining forward DDL required')
    return matches[0]['state'],index+1,c.digest(last),c.digest(witnesses[-1])


def reconcile(db, original, failed, reference_plan, reference, output):
    if c.isolation_gate.check()[0]:raise ValueError('Current source review gate failed')
    if failed.policy.path!=reference.policy.path:raise ValueError('Same independently trusted journal policy required')
    if failed.policy.fixture and not db.test:raise ValueError('Fixture evidence cannot authorize business recovery')
    c.maintenance(db)
    expected,start,failed_tip,reference_tip=boundary(original,failed,reference_plan,reference)
    if c.target(db)!=original['target'] or c.state(db)!=expected:
        raise ValueError('Actual full DDL/rows differ or outcome is unknown; retain maintenance')
    if c.digest(c.preserved(db,original['preservation_columns']))!=original['preserved_sha256']:
        raise ValueError('Original business facts differ; forward recovery refused')
    recovery={'failed_plan':original,'failed_ledger':str(failed.directory.resolve()),'failed_tip':failed_tip,
              'reference_plan':reference_plan,'reference_ledger':str(reference.directory.resolve()),'reference_tip':reference_tip,
              'decision':'full-state equals independently recorded completed DDL; skip this phase, never replay'}
    proposal={**original,'id':secrets.token_hex(16),'created_at':c.now().isoformat(),'source_sha256':c.sources(),
              'initial':expected,'start':start,'recovery':recovery}
    value={'result':'RECONCILED_PENDING_REVIEW','proposal':proposal}
    c.publish(output,value);return value


def validate_apply(proposal, approval, policy):
    """Called by ordinary apply too: a candidate cannot bypass explicit recovery review."""
    recovery=proposal['recovery']
    review=approval['payload'].get('recovery_review',{})
    if (review.get('evidence_sha256')!=c.digest(recovery) or review.get('decision')!='FORWARD_ONLY'
            or not isinstance(review.get('reason'),str) or not review['reason'].strip()):
        raise ValueError('Explicit signed forward-only recovery review required')
    failed=c.Ledger(Path(recovery['failed_ledger']),policy)
    reference=c.Ledger(Path(recovery['reference_ledger']),policy)
    old=recovery['failed_plan']
    expected,start,ft,rt=boundary(old,failed,recovery['reference_plan'],reference)
    if (ft!=recovery['failed_tip'] or rt!=recovery['reference_tip'] or proposal['initial']!=expected
            or proposal['start']!=start or proposal['target']!=old['target']
            or proposal['preservation_columns']!=old['preservation_columns']
            or proposal['preserved_sha256']!=old['preserved_sha256']
            or proposal['migrations']!=old['migrations'] or proposal['business_activation_ready'] is not False):
        raise ValueError('Recovery evidence/target/boundary changed; new reconciliation required')


def forward_plan(db, reconciliation, proof, restore_db, approval, policy, output):
    proposal=reconciliation['proposal']
    if reconciliation['result']!='RECONCILED_PENDING_REVIEW':raise ValueError('Unreviewed reconciliation required')
    if policy.fixture and not db.test:raise ValueError('Fixture review cannot authorize business target')
    policy.verify(approval,c.binding(proposal,proof),'isolated-fixture' if db.test else 'business')
    validate_apply(proposal,approval,policy)
    c.maintenance(db);other=c.target(restore_db)
    if (c.sources()!=proposal['source_sha256'] or c.target(db)!=proposal['target'] or c.state(db)!=proposal['initial']
            or proof['result']!='PASS' or proof['plan_sha256']!=c.digest(proposal) or proof['source']!=proposal['target']
            or proof.get('ledger_tip') is not None or proof.get('next')!=proposal['start']
            or proof['restored']!=proposal['initial'] or other!=proof['restore']
            or other['server_uuid']==proposal['target']['server_uuid'] or other['datadir']==proposal['target']['datadir']
            or not restore_db.test or c.state(restore_db)!=proposal['initial']
            or c.core.file_hash(Path(proof['backup']['path']))!=proof['backup']['sha256']
            or c.core.file_hash(Path(proof['restore_input']['path']))!=proof['restore_input']['sha256']):
        raise ValueError('Current target or independent full backup/restore evidence changed')
    c.publish(output,proposal);return proposal


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['reconcile','verify-backup','forward-plan'])
    for name in ['container','database','output']:parser.add_argument('--'+name,required=True)
    for name in ['original-plan','failed-ledger','reference-plan','reference-ledger','reconciliation','proof',
                 'restore-container','restore-database','approval','fixture-policy']:parser.add_argument('--'+name)
    a=parser.parse_args();db=c.core.Database(a.container,a.database)
    if a.fixture_policy and not db.test:raise ValueError('Fixture policy cannot authorize business target')
    policy=c.Policy(a.fixture_policy or c.POLICY,bool(a.fixture_policy))
    if a.action=='reconcile':
        value=reconcile(db,c.read(a.original_plan),c.Ledger(Path(a.failed_ledger),policy),
                        c.read(a.reference_plan),c.Ledger(Path(a.reference_ledger),policy),a.output)
    else:
        receipt=c.read(a.reconciliation);restore=c.core.Database(a.restore_container,a.restore_database)
        if a.action=='verify-backup':value=c.verify_backup(db,receipt['proposal'],restore,Path(a.output))
        else:value=forward_plan(db,receipt,c.read(a.proof),restore,c.read(a.approval),policy,a.output)
    print(c.digest(value))


if __name__=='__main__':main()
