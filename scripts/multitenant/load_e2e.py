"""Bounded, 100-synthetic-user API load test. No production addresses accepted."""
import concurrent.futures as futures,json,time,secrets,statistics,threading
from pathlib import Path
import bcrypt,pymysql,requests
ROOT=Path(__file__).resolve().parents[2];HOME=ROOT/'rollback/multitenant-20260929/schema/e2e'
c=json.loads((HOME/'connection.json').read_text());assert c['url'].startswith('jdbc:mysql://127.0.0.1:64029/mt705_probe_')
db=pymysql.connect(host='127.0.0.1',port=64029,user=c['username'],password=c['password'],database=c['database'],autocommit=True)
suffix=secrets.token_hex(4);password=secrets.token_urlsafe(24);encoded=bcrypt.hashpw(password.encode(),bcrypt.gensalt(rounds=10)).decode()
users=[]
with db.cursor() as q:
 for n in range(100):
  tenant=2 if n<50 else 3;email=f'load-{suffix}-{n%50}@example.test'
  q.execute("INSERT INTO user_account(tenant_id,email,password_hash,status,created_at,updated_at,row_version) VALUES(%s,%s,%s,'normal',UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)",(tenant,email,encoded));users.append((tenant,email,q.lastrowid))
db.close();barrier=threading.Barrier(100);results=[];mutex=threading.Lock()
def record(route,status,elapsed):
 with mutex:results.append({'route':route,'status':status,'ms':round(elapsed*1000,2)})
def worker(user):
 tenant,email,user_id=user;s=requests.Session();s.trust_env=False;s.headers.update({'Host':'a.localhost' if tenant==2 else 'b.localhost','Origin':'https://a.localhost' if tenant==2 else 'https://b.localhost'})
 def call(route,method='GET',body=None):
  start=time.perf_counter()
  try:r=s.request(method,'http://127.0.0.1:64031'+route,json=body,timeout=20);record(route,r.status_code,time.perf_counter()-start);return r
  except requests.RequestException:record(route,0,time.perf_counter()-start);return None
 login=call('/api/auth/login','POST',{'account':email,'password':password})
 if login is None or login.status_code!=200:barrier.abort();return
 s.headers['Authorization']='Bearer '+login.json()['token']
 try:barrier.wait(timeout=45)
 except threading.BrokenBarrierError:return
 for cycle in range(5):
  call('/api/auth/activity','POST',{'pageCode':'assets' if cycle%2 else 'orders','deviceType':'PC' if user_id%2 else 'MOBILE','sequence':int(time.time()*1000)})
  call('/api/user/assets')
  call('/api/financial/orders')
 s.close()
start=time.perf_counter()
with futures.ThreadPoolExecutor(max_workers=100) as pool:list(pool.map(worker,users))
elapsed=time.perf_counter()-start;latencies=sorted(r['ms'] for r in results);errors=[r for r in results if r['status']!=200]
report={'scope':'100 synthetic active users in two tenants, real local HTTP backend and isolated MySQL/Redis. Excludes browser/TLS cost and does not prove production capacity or export/settlement concurrency.',
        'users':100,'plannedRequests':1600,'completedRequests':len(results),'errors':len(errors),'elapsedSeconds':round(elapsed,3),'requestsPerSecond':round(len(results)/elapsed,2),
        'p50ms':statistics.median(latencies),'p95ms':latencies[int(len(latencies)*.95)-1],'p99ms':latencies[int(len(latencies)*.99)-1],
        'routes':{},'passed':not errors and len(results)==1600}
for route in sorted(set(r['route'] for r in results)):
 rows=[r for r in results if r['route']==route];report['routes'][route]={'requests':len(rows),'statuses':{str(s):sum(r['status']==s for r in rows) for s in sorted(set(r['status'] for r in rows))}}
(ROOT/'reports/multitenant/load-100-users.json').write_text(json.dumps(report,indent=2),encoding='utf8');print(json.dumps(report));raise SystemExit(0 if report['passed'] else 1)
