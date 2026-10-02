"""Bounded 10 tenant / 200 users each / 100 active actors; real HTTP/MySQL operations."""
import acceptance as s
import requests,traceback
from decimal import Decimal
ROOT,RUN,P=s.ROOT,s.RUN,s.PRIVATE
statefile=P/'load-state.json'
state=s.read(statefile) if statefile.exists() else {'tenants':[]}
rows=[];lock=s.threading.Lock();inflight=0;peak=0

def persist():s.save(statefile,state)
def db(mode='REAL'):
 a=s.read(P/'db-access.json');return s.pymysql.connect(host='127.0.0.1',port=s.PORTS['mysql'],user=a['username'],password=a['password'],database='mt705_stage5_'+mode.lower(),autocommit=True,charset='utf8mb4')
def sql(q,args=None):
 with db() as d:
  with d.cursor() as c:c.execute(q,args);return c.fetchall()
def call(host,path,method='GET',body=None,token=None,allowed=(200,),binary=False,measure=False):
 global inflight,peak
 headers={'Host':host,'Origin':'https://'+host+':8643','Content-Type':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 c=s.http.client.HTTPConnection('127.0.0.1',s.PORTS['real'],timeout=45)
 start=s.time.perf_counter()
 if measure:
  with lock:inflight+=1;peak=max(peak,inflight)
 status=0;ok=False
 try:
  c.request(method,path,s.json.dumps(body).encode() if body is not None else None,headers);r=c.getresponse();raw=r.read();status=r.status
  try:v=raw if binary else s.json.loads(raw)
  except ValueError:v={}
  ok=status in allowed and not (status==200 and isinstance(v,dict) and v.get('success') is False)
  if not ok:raise AssertionError(f'{method} {path} HTTP {status}: '+str(v.get('message',''))[:160])
  return v
 finally:
  c.close()
  if measure:
   with lock:
    inflight-=1;rows.append({'path':path,'status':status,'ok':ok,'ms':round((s.time.perf_counter()-start)*1000,3),'bytes':len(raw) if status else 0})
def control(path,method='GET',body=None):return call('control.localhost','/api/control'+path,method,body,state['control'])
def admin(t,path,method='GET',body=None,**kw):return call('admin.localhost','/api/admin'+path,method,body,t['owner']['token'],**kw)
def user(t,u,path,method='GET',body=None,**kw):return call(t['host'],'/api'+path,method,body,u['token'],**kw)

def setup():
 identity=s.read(P/'identities.json');state['control']=call('control.localhost','/api/control/auth/login','POST',{'account':identity['account'],'password':identity['password'],'totp':s.totp(identity['totpSecret'])})['token'];persist()
 for n in range(len(state['tenants']),10):
  t={'host':f's5-{n}.localhost','users':[]};row=control('/tenants','POST',{'code':f's5_load_{n}','name':f'Stage5 load {n}','reason':'Stage5 isolated bounded acceptance'})['data'];t['id']=row['id'];state['tenants'].append(t);persist()
  owner={'account':f's5_owner_{n}','password':s.secrets.token_urlsafe(24)};t['owner']=owner;persist()
  r=control(f'/tenants/{t["id"]}/backend-accounts','POST',dict(type='ADMIN',account=owner['account'],password=owner['password'],email=f's5-owner-{n}@example.test',role='super_admin',reason='Stage5 local fixture owner'))['data'];owner['id']=r['adminUserId']
  r=call('admin.localhost','/api/admin/auth/login','POST',{'account':owner['account'],'password':owner['password']});owner['token']=r['token'];persist()
  if r['user'].get('mustChangePassword'):
   new=s.secrets.token_urlsafe(24);admin(t,'/auth/profile/password','PUT',{'oldPassword':owner['password'],'newPassword':new});owner['password']=new;persist();owner['token']=call('admin.localhost','/api/admin/auth/login','POST',{'account':owner['account'],'password':new})['token'];persist()
  base=f'/tenants/{t["id"]}/domains/';v=control(base+'prepare','POST',{'hostname':t['host'],'reason':'Stage5 owned TLS route'})['data'];v=control(base+'verify','POST',{'hostname':t['host'],'version':v['version']})['data'];control(base+'activate','POST',{'hostname':t['host'],'version':v['version'],'reason':'Stage5 actual TLS challenge verified'})
  for k,v in [('site.name',f'Stage5 load {n}'),('system.timezone','UTC')]:admin(t,'/config/save','POST',{'key':k,'value':v,'description':'Stage5 isolated fixture','reason':'Stage5 local settings'})
  product={'name':'Stage5 bounded','currency':'USD','dailyYieldRate':'1','rentalFee':'0','minPurchase':'1','maxPurchase':'100','termDays':1,'penaltyRate':'0','enabled':True,'sortOrder':0}
  t['productId']=admin(t,'/financial/products','POST',product)['data']['id']
  control(f'/tenants/{t["id"]}/policies','PUT',{'key':'feature.financial','value':'true','locked':True,'reason':'Stage5 bounded local financial flow'})
  report=control(f'/tenants/{t["id"]}/readiness')['data'];assert report['ready'],report['missing']
  control(f'/tenants/{t["id"]}','PUT',{'status':'ACTIVE','configReady':True,'reason':'Stage5 server-validated readiness'})
  password=s.secrets.token_urlsafe(24);hashed=s.bcrypt.hashpw(password.encode(),s.bcrypt.gensalt(rounds=10)).decode()
  with db() as d:
   with d.cursor() as c:
    for j in range(200):
     email=f's5-load-{j}@example.test';c.execute("INSERT INTO user_account(tenant_id,email,password_hash,status,created_at,updated_at,row_version) VALUES(%s,%s,%s,'normal',UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)",(t['id'],email,hashed))
     if j<10:t['users'].append({'id':c.lastrowid,'email':email,'password':password,'key':f'stage5-financial-{n}-{j}'})
  persist();print('TENANT READY',n,flush=True)
 s.save(RUN/'population.json',{'tenants':[{'id':t['id'],'users':sql('SELECT COUNT(*) FROM user_account WHERE tenant_id=%s',(t['id'],))[0][0],'activePlanned':10} for t in state['tenants']],'sqlFixtureScope':'only 200 synthetic accounts per new tenant; no readiness, approval, wallet or order results injected'})

def login_users():
 for t in state['tenants']:
  for u in t['users']:
   r=call(t['host'],'/api/auth/login','POST',{'account':u['email'],'password':u['password']});assert r['user']['id']==u['id'] and r['user']['tenantId']==t['id'];u['token']=r['token']
 persist();print('100 USERS AUTHENTICATED',flush=True)

def kyc():
 import struct,zlib
 def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data))
 png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',1,1,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(b'\x00\x40\x80\xc0'))+chunk(b'IEND',b'')
 for t in state['tenants']:
  for u in t['users']:
   if u.get('kycId'):continue
   with requests.Session() as session:
    session.trust_env=False
    response=session.post('http://127.0.0.1:33931/api/kyc/submit',headers={'Host':t['host'],'Origin':'https://'+t['host']+':8643','Authorization':'Bearer '+u['token']},data={'realName':'SYNTHETIC TEST ONLY','idNumber':'STAGE5-TEST-'+str(u['id'])},files={'idFrontImage':('fixture.png',png,'image/png'),'idBackImage':('fixture.png',png,'image/png')},timeout=30)
    assert response.status_code==200,'Synthetic KYC upload failed '+str(response.status_code)
   found=sql('SELECT id FROM kyc_record WHERE tenant_id=%s AND user_id=%s',(t['id'],u['id']));assert len(found)==1
   admin(t,'/kyc/'+str(found[0][0])+'/approve','POST',{'remark':'Stage5 synthetic fixture only, no real identity'})
   u['kycId']=found[0][0];persist()
 print('100 SYNTHETIC KYC FLOWS PASS',flush=True)

def funds():
 for t in state['tenants']:
  for u in t['users']:
   if 'orderId' in u:continue
   body={'userId':u['id'],'account':'FUND','currency':'USD','type':'manual','manualPurpose':'ADJUSTMENT','amount':'1000','remark':'Stage5 synthetic local funding','idempotencyKey':'stage5-deposit-'+str(u['id'])}
   dep=admin(t,'/deposit/orders/manual','POST',body);u['depositId']=dep['id'];persist()
   order=user(t,u,'/financial/purchase','POST',{'productId':t['productId'],'purchaseAmount':'10','requestId':u['key']})['data'];u['orderId']=order['id'];persist()
 print('FUNDS PREPARED',flush=True)

def load():
 assert len(state['tenants'])==10 and all(len(t['users'])==10 for t in state['tenants'])
 # Set fixture clock dates only; the application must calculate and post all maturity and yield results.
 ids=[u['orderId'] for t in state['tenants'] for u in t['users']]
 assert len(ids)==100 and len(set(ids))==100
 sql('UPDATE financial_order SET purchase_time=UTC_TIMESTAMP()-INTERVAL 1 DAY,end_time=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE id IN ('+','.join(['%s']*100)+") AND status='IN_PROGRESS'",ids)
 s.save(RUN/'clock-fixture.json',{'orderIds':ids,'columns':['purchase_time','end_time'],'purpose':'Maturity due during bounded load; no balances/status/yield/audit writes by fixture'})
 barrier=s.threading.Barrier(100);stop=s.threading.Event();resources=[];errors=[];exports=[];start=s.time.perf_counter()
 def sample():
  while not stop.is_set():
   raw=s.command(['docker','stats','--no-stream','--format','{{json .}}',s.NAME+'-mysql',s.NAME+'-redis'])
   pids=','.join(str(x['pid']) for x in s.read(RUN/'processes.json').values() if not x.get('stopped'))
   native=s.command(['pwsh','-NoProfile','-Command','Get-Process -Id '+pids+' | Select-Object Id,CPU,WorkingSet64,PrivateMemorySize64 | ConvertTo-Json -Compress'])
   resources.append({'elapsed':round(s.time.perf_counter()-start,3),'docker':[s.json.loads(x) for x in raw.decode().splitlines()],'native':s.json.loads(native.decode('utf-8-sig'))});stop.wait(2)
 thread=s.threading.Thread(target=sample);thread.start()
 def worker(pair):
  t,u=pair
  try:
   barrier.wait(timeout=60)
   for cycle in range(4):
    user(t,u,'/auth/activity','POST',{'pageCode':'assets','deviceType':'PC' if u['id']%2 else 'MOBILE','sequence':int(s.time.time()*1000)},measure=True)
    user(t,u,'/user/assets',measure=True)
    repeat=user(t,u,'/financial/purchase','POST',{'productId':t['productId'],'purchaseAmount':'10','requestId':u['key']},measure=True);assert repeat['data']['id']==u['orderId']
    user(t,u,'/financial/orders',measure=True)
    if u is t['users'][0]:
     raw=admin(t,'/deposit/orders/export','GET',binary=True,measure=True);assert len(raw)>100
     with lock:exports.append({'tenant':t['id'],'cycle':cycle,'bytes':len(raw),'sha256':s.hashlib.sha256(raw).hexdigest()})
    if u in t['users'][:2]:
     admin(t,'/financial/yield/calculate','POST',measure=True);admin(t,'/financial/yield/payout-all','POST',measure=True)
    s.time.sleep(.03)
  except Exception as ex:
   with lock:errors.append({'tenant':t['id'],'userId':u['id'],'error':str(ex)})
 try:
  with s.futures.ThreadPoolExecutor(max_workers=100) as pool:list(pool.map(worker,[(t,u) for t in state['tenants'] for u in t['users']]))
 finally:stop.set();thread.join()
 elapsed=s.time.perf_counter()-start;latencies=sorted(r['ms'] for r in rows)
 online=[{'tenant':t['id'],'online':sql('SELECT COUNT(*) FROM user_account WHERE tenant_id=%s AND last_activity_at>UTC_TIMESTAMP()-INTERVAL 5 MINUTE',(t['id'],))[0][0]} for t in state['tenants']]
 report={'status':'PASS' if not errors and all(r['ok'] for r in rows) else 'FAIL','tenants':10,'population':2000,'actors':100,'peakObservedClientInflight':peak,'completedRequests':len(rows),'errors':errors,'httpErrors':sum(not r['ok'] for r in rows),'elapsedSeconds':elapsed,'p50ms':s.statistics.median(latencies),'p95ms':latencies[int(len(latencies)*.95)-1],'p99ms':latencies[int(len(latencies)*.99)-1],'maxMs':max(latencies),'onlineByTenant':online,'exports':exports,'resourceSamples':resources,'calls':rows,'scope':'Local plain HTTP with verified Host/Origin, actual final jar/MySQL/Redis, 100 actors; not 100 continuously inflight requests or production capacity/SLA. TLS provisioning passed; no artificial latency threshold.'}
 s.save(RUN/'mixed-load.json',report);print('LOAD',report['status'],len(rows),'requests',len(errors),'errors','peak',peak,flush=True)
 if errors:raise AssertionError(errors[0])

if __name__=='__main__':
 try:globals()[s.sys.argv[1]]()
 except Exception as ex:
  s.save(RUN/('failure-'+s.sys.argv[1]+'-'+str(int(s.time.time()))+'.json'),{'operation':s.sys.argv[1],'error':str(ex),'type':type(ex).__name__});raise
