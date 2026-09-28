"""Bounded real HTTP/SQL acceptance; only the named loopback test account is writable."""
import json
import time
import uuid
from decimal import Decimal
from pathlib import Path
from curve_fixture import ROOT, sql, verify_target
from verify_curve_business import api

USER = 9000070
PATH = '/admin/orders/contract/manual'

def snapshot():
    return {'wallet': sql(f"select available,frozen,row_version from asset_account where user_id={USER} and coin='CONTRACT'"),
            'orders': sql(f"select id,order_source,status,profit-fee,open_time,close_time from contract_order where user_id={USER} order by id"),
            'audit': sql(f"select order_id from manual_order_record where user_id={USER} order by order_id"),
            'history': {g: sql(f"select * from asset_history_{g} where user_id={USER} order by bucket_start") for g in ['1m','1h','4h','1d']}}

def main():
    verify_target()
    assert sql(f'select email from user_account where id={USER}') == 'manual-full-9000070@local.invalid'
    report = ROOT/(ROOT/'reports/manual-generation-full-test/latest.txt').read_text().strip()
    secrets = json.loads((ROOT/'reports/manual-order-browser/secrets.json').read_text(encoding='utf-8-sig'))
    status, login = api('POST','/admin/auth/login',{'account':'manual-admin','password':secrets['adminPassword'],'loginType':'email'})
    assert status == 200 and login.get('token')
    token = login['token']; cases=[]
    def call(name, body, expected=200):
        before=snapshot(); started=time.perf_counter()
        status,out=api('POST',PATH+'/generate',body,token)
        after=snapshot()
        assert before==after, 'generation wrote durable data'
        row={'case':name,'status':status,'expected':expected,'milliseconds':round((time.perf_counter()-started)*1000,2),
             'request':body,'error':out.get('message'),'calculation':out.get('calculation'),'generation':out.get('generation'),'readonly':True}
        cases.append(row); (report/'http-cases.json').write_text(json.dumps(cases,indent=2,ensure_ascii=False),encoding='utf8')
        assert status==expected,(name,status,out.get('message'))
        return out
    base={'userId':USER,'symbol':'BTCUSDT','timezone':'UTC','openLocal':'2026-09-26T17:00','closeLocal':'2026-09-26T17:10','side':'SELL','leverage':'10000','quantity':'0.01','walletEnabled':False,'historyEnabled':False}
    out=call('fixed-real-prices-cold',base)
    call('fixed-real-prices-hot',base)
    c=out['calculation']; quotes=out['quotes']; q=Decimal(str(c['quantity']))
    net=(Decimal(str(quotes['openPrice']))-Decimal(str(quotes['closePrice'])))*q*Decimal(str(out['lotSize']))*Decimal(str(quotes['closeRate']))-Decimal(str(out['feePerLot']))*q
    assert net==Decimal(str(c['net']))
    for name,change in [('conflict-target',{'targetNet':'999999'}),('illegal-flags',{'historyEnabled':True}),('forged-price',{'openPrice':'1'}),('unknown',{'xyz':1}),('bad-lot',{'quantity':'0.001'}),('reverse-time',{'openLocal':base['closeLocal'],'closeLocal':base['openLocal']})]:
        call(name,dict(base,**change),400)
    before=snapshot(); status,_=api('POST',PATH+'/generate',base);assert status in (401,403);assert snapshot()==before
    cases.append({'case':'unauthenticated','status':status,'readonly':True})
    creates=[]
    for wallet,history in [(False,False),(True,False),(True,True)]:
        out=call(f'generate-flags-{wallet}-{history}',dict(base,walletEnabled=wallet,historyEnabled=history))
        request=out['request'];request.update(previewToken=out['previewToken'],idempotencyKey=uuid.uuid4().hex)
        before=snapshot(); status,created=api('POST',PATH,request,token);assert status==200,(status,created.get('message'))
        after=snapshot(); expected=Decimal(before['wallet'].split('\t')[0])+(Decimal(str(out['calculation']['net'])) if wallet else 0)
        assert Decimal(after['wallet'].split('\t')[0])==expected
        assert after['wallet'].split('\t')[1]==before['wallet'].split('\t')[1]
        if not history: assert after['history']==before['history']
        assert sql(f"select order_source,status from contract_order where id={int(created['orderId'])}")=='MANUAL_TEST\tCLOSED'
        status,replay=api('POST',PATH,request,token);assert status==200 and replay['orderId']==created['orderId'] and snapshot()==after
        status,_=api('POST',PATH,dict(request,input='0.02'),token);assert status==400 and snapshot()==after
        creates.append({'flags':[wallet,history],'orderId':created['orderId'],'net':str(net),'before':before,'after':after,'replay':True,'conflictRejected':True})
        (report/'http-create-sql.json').write_text(json.dumps(creates,indent=2,ensure_ascii=False),encoding='utf8')
    (report/'http-cases.json').write_text(json.dumps(cases,indent=2,ensure_ascii=False),encoding='utf8')
    print('PASS',len(cases),'HTTP cases; 3 legal creates with SQL and durable retry assertions; actual historical price chain')

if __name__=='__main__':main()
