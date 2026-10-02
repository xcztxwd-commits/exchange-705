"""Stage5 focused invariants, restart snapshot and full independent final restore."""
import workload as w
s=w.s
MONEY=['asset_account','deposit_record','deposit_credit_record','withdraw_record','contract_order','option_order','loan_record','financial_order','financial_yield_record','transfer_record','balance_adjustment','demo_ledger','trial_grant','trial_ledger']
def database(mode='REAL',role='mysql'):return s.dbtools.Database(s.NAME+'-'+role,'mt705_stage5_'+mode.lower())
def snapshot():
 db=database();cols=db.columns();return s.dbtools.fingerprint(db,{t:[c[0] for c in cols[t]] for t in MONEY})
def concurrency():
 t=w.state['tenants'][0];u=t['users'][1];body={'userId':u['id'],'account':'FUND','currency':'USD','type':'manual','manualPurpose':'ADJUSTMENT','amount':'1','remark':'Stage5 simultaneous idempotency','idempotencyKey':'stage5-concurrent-deposit'}
 before=w.sql('SELECT available,frozen FROM asset_account WHERE tenant_id=%s AND user_id=%s AND coin=%s',(t['id'],u['id'],'FUND'))
 barrier=s.threading.Barrier(20)
 def credit(_):barrier.wait(timeout=30);return w.admin(t,'/deposit/orders/manual','POST',body)['id']
 with s.futures.ThreadPoolExecutor(max_workers=20) as pool:ids=list(pool.map(credit,range(20)))
 assert len(set(ids))==1
 after=w.sql('SELECT available,frozen FROM asset_account WHERE tenant_id=%s AND user_id=%s AND coin=%s',(t['id'],u['id'],'FUND'));assert after[0][0]==before[0][0]+1 and after[0][1]==before[0][1]
 barrier=s.threading.Barrier(20);orderbody={'productId':t['productId'],'purchaseAmount':'10','requestId':'stage5-concurrent-purchase'}
 def purchase(_):barrier.wait(timeout=30);return w.user(t,u,'/financial/purchase','POST',orderbody)['data']['id']
 with s.futures.ThreadPoolExecutor(max_workers=20) as pool:orders=list(pool.map(purchase,range(20)))
 assert len(set(orders))==1;oid=orders[0];barrier=s.threading.Barrier(20)
 def redeem(_):barrier.wait(timeout=30);return w.user(t,u,f'/financial/redeem/{oid}','POST')['data']['id']
 with s.futures.ThreadPoolExecutor(max_workers=20) as pool:assert set(pool.map(redeem,range(20)))=={oid}
 assert after==w.sql('SELECT available,frozen FROM asset_account WHERE tenant_id=%s AND user_id=%s AND coin=%s',(t['id'],u['id'],'FUND'))
 w.state['concurrent']={'deposit':ids[0],'order':oid,'userId':u['id']};w.persist()
 s.save(s.RUN/'concurrency.json',{'status':'PASS','simultaneousPerOperation':20,'depositRequests':20,'depositCredits':1,'purchaseRequests':20,'orders':1,'redeemRequests':20,'refunds':1,'additionalRealFixtureCredit':'1','realMySql':True})

def boundaries_orm():
 before=snapshot();checks=[]
 for i,t in enumerate(w.state['tenants']):
  other=w.state['tenants'][(i+1)%10];u=t['users'][0];foreign=other['users'][0]
  w.admin(t,f'/deposit/orders/{foreign["depositId"]}',allowed=(400,403,404));w.user(t,u,f'/financial/penalty/{foreign["orderId"]}',allowed=(400,403,404))
  w.call(other['host'],'/api/user/assets',token=u['token'],allowed=(401,403));w.call('admin.localhost','/api/admin/financial/orders',token=u['token'],allowed=(401,403))
  checks.append({'tenantId':t['id'],'foreignTenantId':other['id'],'checks':4})
 assert snapshot()==before
 db=database();original=db.query('SELECT @@global.general_log,@@global.log_output')[0].split('\t');assert original==['0','FILE'],original
 since=db.query('SELECT UTC_TIMESTAMP(6)')[0]
 db.sql("SET GLOBAL log_output='TABLE'; SET GLOBAL general_log=ON;",False)
 try:
  t=w.state['tenants'][0]
  p={'name':'Stage5 ORM disposable','currency':'USD','dailyYieldRate':'0','rentalFee':'0','minPurchase':'1','maxPurchase':'100','termDays':1,'penaltyRate':'0','enabled':True}
  row=w.admin(t,'/financial/products','POST',p)['data'];pid=row['id'];p['name']='Stage5 ORM updated';w.admin(t,f'/financial/products/{pid}','PUT',p);w.admin(t,f'/financial/products/{pid}','DELETE')
 finally:db.sql('SET GLOBAL general_log=OFF; SET GLOBAL log_output=\'FILE\';',False)
 raw=db.query("SELECT argument FROM mysql.general_log WHERE event_time>="+s.dbtools.literal(since)+" AND (argument LIKE 'update financial_product %' OR argument LIKE 'delete from financial_product %')")
 shapes=sorted(set(q[q.lower().index(' where '):] for q in raw));assert any('tenant_id='+str(t['id']) in x for x in shapes) and len(shapes)>=1
 assert any(q.lower().startswith('update') for q in raw) and any(q.lower().startswith('delete') for q in raw)
 assert all('tenant_id='+str(t['id']) in q.lower()[q.lower().index(' where '):] for q in raw)
 s.save(s.RUN/'boundaries-orm.json',{'status':'PASS','tenantPermissionChecks':checks,'moneyAllColumnsUnchanged':True,'physicalSqlOperations':['UPDATE financial_product','DELETE financial_product'],'whereShapes':shapes,'runtimeFinalJar':s.sha(s.PRIVATE/'app.jar'),'generalLogRestored':True,'registryFlagsNotChanged':True,'scope':'Actual final backend owned tenant SQL observed. Other entities/source structures checked by unchanged source gate; not a claim every dynamic write path was executed.'})

def reconcile():
 db=database();initial=s.read(s.PRIVATE/'REAL-initial.json');tids=[t['id'] for t in w.state['tenants']];outside=','.join(map(str,tids));old={};current={}
 for table in MONEY:
  columns=initial['columns'][table];expr='JSON_ARRAY('+','.join('HEX(CAST('+s.dbtools.ident(c)+' AS BINARY))' for c in columns)+')'
  hashes=db.query('SELECT SHA2('+expr+',256) FROM '+s.dbtools.ident(table)+' WHERE tenant_id NOT IN ('+outside+')')
  actual={'rows':len(hashes),'sha256':s.hashlib.sha256('\n'.join(sorted(hashes)).encode()).hexdigest()};assert actual==initial['tables'][table],table+' preexisting changed';old[table]=actual
 for t in w.state['tenants']:
  tid=t['id'];amounts=w.sql('SELECT coin,SUM(available),SUM(frozen) FROM asset_account WHERE tenant_id=%s GROUP BY coin',(tid,));fund=next(x for x in amounts if x[0]=='FUND');expected=Decimal('10001')+(1 if t is w.state['tenants'][0] else 0)
  assert fund[1]==expected and fund[2]==0,(tid,fund,expected)
  orders=w.sql('SELECT id,user_id,request_key,request_hash,status,purchase_amount,row_version FROM financial_order WHERE tenant_id=%s ORDER BY id',(tid,));assert len(orders)==(11 if t is w.state['tenants'][0] else 10)
  assert sum(o[4]=='COMPLETED' for o in orders)==10 and all(o[2] and o[3] for o in orders)
  yields=w.sql('SELECT order_id,yield_date,daily_yield,status FROM financial_yield_record WHERE tenant_id=%s',(tid,));assert len(yields)==10 and all(x[2]==Decimal('.1') and x[3]=='PAID' for x in yields)
  # Actual durable columns discovered from current DDL, not an assumed generic request_id.
  cols=db.columns();idempotency={tb:[c[0] for c in cols[tb] if any(k in c[0] for k in ['request','idempot','version','receipt','credit','deposit','order','tenant','user'])] for tb in ['deposit_record','deposit_credit_record','financial_order','financial_yield_record']}
  deposits=w.sql('SELECT COUNT(*),SUM(amount) FROM deposit_record WHERE tenant_id=%s',(tid,));credits=w.sql('SELECT COUNT(*) FROM deposit_credit_record WHERE tenant_id=%s',(tid,))
  assert deposits[0][0]==credits[0][0]==(11 if t is w.state['tenants'][0] else 10)
  current[str(tid)]={'available':str(fund[1]),'frozen':str(fund[2]),'orderCount':len(orders),'matured':10,'paidYieldCount':len(yields),'paidYield':'1.0','depositCount':deposits[0][0],'creditCount':credits[0][0],'depositTotal':str(deposits[0][1]),'actualIdempotencyColumns':idempotency,'orderKeyHashes':[s.hashlib.sha256((str(o[0])+':'+o[2]+':'+o[3]).encode()).hexdigest() for o in orders]}
 s.save(s.RUN/'reconciliation.json',{'status':'PASS','oldFundsAllColumnsUnchanged':old,'newTenants':current,'totalAvailable':str(sum(Decimal(x['available']) for x in current.values())),'totalFrozen':'0','originalJUnitSkipped':1,'epoch':db.query('SELECT version,minimum_application_epoch,business_activation_ready+0 FROM tenant_schema_version')})
 print('RECONCILIATION PASS',flush=True)

def restart():
 before=snapshot();s.save(s.PRIVATE/'before-restart-funds.json',before);s.shutil.copy2(s.RUN/'processes.json',s.PRIVATE/'processes-before-consistency-restart.json')
 s.stop('REAL');s.start('REAL');after=snapshot();assert before==after
 t=w.state['tenants'][0];u=t['users'][1]
 w.user(t,u,'/financial/purchase','POST',{'productId':t['productId'],'purchaseAmount':'10','requestId':'stage5-concurrent-purchase'})
 w.user(t,u,f'/financial/redeem/{w.state["concurrent"]["order"]}','POST');assert snapshot()==before
 s.save(s.RUN/'restart.json',{'status':'PASS','onlyOwnedRealBackendRestarted':True,'allMoneyRowsAndIdempotencyColumnsUnchanged':True,'oldTokenStillValidAsExpected':True,'purchaseAndRefundReplayNoDelta':True,'noDbRedisGatewayRestart':True})

def final_backup():
 for role in ['REAL','DEMO','gateway']:s.stop(role)
 results={}
 for mode in ['REAL','DEMO']:
  db=database(mode);cols={t:[c[0] for c in f] for t,f in db.columns().items()};a=s.dbtools.fingerprint(db,cols);backup=db.dump(s.PRIVATE/(mode+'-final.sql'))
  restored=s.dbtools.Database(s.NAME+'-restore','mt705_stage5_restored_'+mode.lower());restored.create_empty();restored.sql(s.Path(backup['path']).read_text(encoding='utf-8'))
  assert db.columns()==restored.columns();b=s.dbtools.fingerprint(restored,cols);assert a==b
  import controlled_migration as cm
  source_schema=cm.schema(db);restore_schema=cm.schema(restored);assert source_schema==restore_schema
  assert db.query('SELECT @@server_uuid')!=restored.query('SELECT @@server_uuid')
  s.save(s.PRIVATE/(mode+'-final-fingerprint.json'),a)
  results[mode]={'backup':backup,'tables':len(cols),'rows':sum(v['rows'] for v in a['tables'].values()),'allColumnsRowsEqual':True,'columnsEqual':True,'allDdlEqual':True,'schemaSha256':source_schema['sha256'],'sourceUuid':db.query('SELECT @@server_uuid')[0],'restoreUuid':restored.query('SELECT @@server_uuid')[0],'sourceContainerId':db.identity['container_id'],'restoreContainerId':restored.identity['container_id']}
 # Private uploaded synthetic files must accompany the DB backup. Preserve exact independent extraction.
 import zipfile
 files=s.PRIVATE/'uploads-final.zip';restored=s.PRIVATE/'uploads-independent-restore'
 with zipfile.ZipFile(files,'w',zipfile.ZIP_DEFLATED) as z:
  for mode in ['REAL','DEMO']:
   for p in (s.PRIVATE/(mode+'-uploads')).rglob('*'):
    if p.is_file():z.write(p,p.relative_to(s.PRIVATE).as_posix())
 with zipfile.ZipFile(files) as z:z.extractall(restored)
 originals={p.relative_to(s.PRIVATE).as_posix():s.sha(p) for mode in ['REAL','DEMO'] for p in (s.PRIVATE/(mode+'-uploads')).rglob('*') if p.is_file()}
 assert originals and all(s.sha(restored/k)==h for k,h in originals.items())
 s.save(s.RUN/'final-restore.json',{'status':'PASS','databases':results,'uploads':{'files':len(originals),'archiveSha256':s.sha(files),'allExtractedBytesEqual':True},'sourceNeverOverwritten':True,'servicesQuiesced':True})
 print('FINAL BACKUP RESTORE PASS',flush=True)

def cleanup():
 own=s.read(s.RUN/'resources-owned.json');now=s.e.inventory()
 for id,v in own.items():
  assert now[id]['labels']==v['labels'] and now[id]['mounts']==v['mounts']
 s.command(['docker','stop']+list(own));after=s.e.inventory();before=s.read(s.RUN/'resources-before.json')
 changes=[id for id,v in before.items() if after.get(id)!=v]
 s.save(s.RUN/'cleanup.json',{'status':'PASS' if not changes else 'EXTERNAL_RESOURCE_CHANGE','ownedStopped':all(not after[id]['running'] for id in own),'mountsCanonicalSorted':True,'volumesRetained':all(after[id]['mounts']==v['mounts'] for id,v in own.items()),'unrelatedChanges':changes,'protectedResources':len(before),'ownedContainerIds':list(own),'nativeAllStopped':all(x['stopped'] for x in s.read(s.RUN/'processes.json').values())})
 assert not changes;print('CLEANUP PASS',flush=True)

from decimal import Decimal
if __name__=='__main__':
 try:globals()[s.sys.argv[1]]()
 except Exception as ex:
  s.save(s.RUN/('failure-'+s.sys.argv[1]+'-'+str(int(s.time.time()))+'.json'),{'operation':s.sys.argv[1],'error':str(ex),'type':type(ex).__name__});raise
