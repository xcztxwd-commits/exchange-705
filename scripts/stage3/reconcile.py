"""Read-only final operating-money, audit, schema and cache reconciliation."""
import hashlib,json,sys
from decimal import Decimal
import environment as e
from acceptance import Acceptance
from retention import rows

def cache(a):
    raw={}
    for mode,filename in [('REAL','application.properties'),('DEMO','demo.properties')]:
        properties=dict(line.split('=',1) for line in (e.PRIVATE/filename).read_text(encoding='utf-8').splitlines() if '=' in line)
        number=properties['spring.redis.database']
        raw[mode]=e.command(['docker','exec',a.spec['containers']['redis'],'redis-cli','-n',number,'--scan','--pattern','tenant:*:market:home-sparkline:v1:*']).decode().splitlines()
    out=[]
    for label,t in a.state['tenants'].items():
        for mode in ['REAL','DEMO']:
            response=a.request(t['host'],'/api/market/home-sparkline/batch','POST',{'symbols':['BTCUSDT']},mode=mode)
            data=response['data'];assert data['mode']==mode and data['status'] in ['fresh','stale','empty']
            assert len(data['items'])==1 and data['items'][0]['symbol']=='BTCUSDT' 
            item=data['items'][0]
            if item['status']=='empty':assert item['points']==[] and item['updatedAt'] is None and item['reason']=='source_empty'
            else:assert item['points']
            keys=[k for k in raw[mode] if k.startswith(f'tenant:{t["id"]}:market:home-sparkline:v1:{mode}:') and ':attempt:' not in k and ':lock' not in k]
            assert keys
            assert 'no-store' in {v.strip().lower() for v in a.calls[-1]['cacheControl'].split(',')}
            out.append({'tenant':label,'tenantId':t['id'],'mode':mode,'redisKeys':keys,'responseStatus':data['status'],'rowReason':item['reason'],'pointCount':len(item['points']),'httpNoStore':True})
    assert len({key for row in out for key in row['redisKeys']})==sum(len(row['redisKeys']) for row in out)
    return {'scheduledProducerRealRedisNamespaces':out,'unitDifferentPayloadIsolation':'TenantMarketIsolationTest','historicalRedisFailureAndRestart':'PASS_REUSED gated separately'}

def reconcile(a):
    tables=['deposit_record','deposit_credit_record','withdraw_record','contract_order','option_order','loan_record','financial_order','transfer_record','demo_ledger']
    modes={}
    for mode,db in a.db.items():
        initial=e.dbtools.Database(a.spec['containers']['restore'],'mt705_probe_stage3_'+mode.lower()+'_20261002_232106_restore')
        columns=db.columns();selected={t:[c[0] for c in columns[t]] for t in tables}
        now=e.dbtools.fingerprint(db,selected);before=e.dbtools.fingerprint(initial,selected)
        assert now==before,(mode,'Money/order fingerprint changed')
        old_wallets={r['id']:r for r in rows(initial,'asset_account','1=1')};new_wallets={r['id']:r for r in rows(db,'asset_account','1=1')}
        assert all(new_wallets.get(id)==row for id,row in old_wallets.items())
        added=[row for id,row in new_wallets.items() if id not in old_wallets]
        expected={(t['id'],t['user']['id'],coin) for label,t in a.state['tenants'].items() if label in ['default','B'] for coin in ['FUND','CONTRACT','OPTION']} if mode=='DEMO' else set()
        assert {(r['tenant_id'],r['user_id'],r['coin']) for r in added}==expected
        for r in added:
            assert Decimal(str(r['available']))==Decimal('100000') and Decimal(str(r['frozen']))==0 and r['row_version']==0
            seed=db.query(f"SELECT CAST(amount_per_wallet AS CHAR) FROM simulation_seed WHERE tenant_id={r['tenant_id']} AND user_id={r['user_id']}")
            assert len(seed)==1 and Decimal(seed[0])==Decimal('100000')
        assert columns==initial.columns(), 'DDL or schema changed'
        epoch=db.query('SELECT version,minimum_application_epoch,business_activation_ready+0 FROM tenant_schema_version')
        assert epoch==initial.query('SELECT version,minimum_application_epoch,business_activation_ready+0 FROM tenant_schema_version') and all(x.endswith('\t0') for x in epoch)
        modes[mode]={'preexistingWalletsAllColumnsUnchanged':True,'expectedFreshDemoSeedWallets':len(added),'expectedFreshDemoSeedTotal':str(sum(Decimal(str(r['available'])) for r in added)),'allOrderLedgerColumnsEqualToFrozenRestore':True,'tables':list(selected)+['asset_account'],'fingerprintSha256':hashlib.sha256(json.dumps(now,sort_keys=True).encode()).hexdigest(),'all92TableColumnsUnchanged':True,'schemaEpochApproval':epoch}
    grants=[]
    for label,t in a.state['tenants'].items():
        tid=t['id'];cid=t['campaignId'];did=t['deliveryId'];uid=t['user']['id']
        g=a.query(f'SELECT id,tenant_id,user_id,campaign_id,delivery_id,request_key,CAST(available AS CHAR),CAST(frozen AS CHAR),CAST(consumed AS CHAR),CAST(expired AS CHAR),row_version FROM trial_grant WHERE tenant_id={tid} AND campaign_id={cid}')
        assert len(g)==1;fields=g[0].split('\t');assert list(map(int,fields[1:5]))==[tid,uid,cid,did] and fields[5] not in ['','NULL']
        assert sum(map(Decimal,fields[6:10]))==Decimal('7.50')
        delivery=a.query(f'SELECT received_at IS NOT NULL,opened_at IS NOT NULL,closed_at IS NOT NULL,claimed_at IS NOT NULL,sent_by FROM activity_delivery WHERE tenant_id={tid} AND id={did}')[0].split('\t')
        assert delivery[:4]==['1']*4 and int(delivery[4])==t['owner']['id']
        ledger=a.query(f'SELECT COUNT(*),CAST(SUM(delta) AS CHAR) FROM trial_ledger WHERE tenant_id={tid} AND user_id={uid} AND reason='+e.dbtools.literal('CLAIM:'+str(did)+':'+fields[0]))
        assert len(ledger)==1 and ledger[0].split('\t')[0]=='1' and Decimal(ledger[0].split('\t')[1])==Decimal('7.50')
        grants.append({'tenant':label,'tenantId':tid,'userId':uid,'campaignId':cid,'deliveryId':did,'grantId':int(fields[0]),'requestKeySha256':hashlib.sha256(fields[5].encode()).hexdigest(),'grantVersion':int(fields[10]),'conservedTrialAmount':'7.50','realCashFrozenUnchanged':True,'singleClaimLedgerDelta':'7.50','sentByActualOwner':int(delivery[4])})
    actions=a.query("SELECT actor_id,action,COUNT(*) FROM control_audit_log WHERE reason LIKE 'Stage3%' GROUP BY actor_id,action")
    assert actions and all(r.split('\t')[0]=='1' for r in actions)
    deletion=a.query("SELECT actor_id,tenant_id,object_ref,outcome FROM control_audit_log WHERE action='CHAT_RETENTION_DELETE' AND object_ref="+e.dbtools.literal(str(a.state['retentionConversation'])))
    assert deletion==[f'1\t{a.state["tenants"]["A"]["id"]}\t{a.state["retentionConversation"]}\tSUCCESS']
    assert not a.query("SELECT tenant_id FROM tenant_policy WHERE policy_key='retention.auto_delete_enabled' AND policy_value='true'")
    return {'business':{'modes':modes,'grants':grants,'trialGrantedTotal':str(sum(Decimal(x['conservedTrialAmount']) for x in grants))},'auditSeparatelyJudged':{'actualControlActor':1,'groupedEvents':actions,'singleSuccessfulDelete':deletion}}

if __name__=='__main__':
    a=Acceptance()
    if len(sys.argv)==1 or sys.argv[1]=='cache':a.check('cache-runtime-default-A-B-real-demo',lambda:cache(a))
    if len(sys.argv)==1 or sys.argv[1]=='money':a.check('final-business-and-audit-reconciliation',lambda:reconcile(a))
