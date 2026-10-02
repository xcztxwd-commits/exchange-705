"""Exercise the full running backend against explicitly restricted synthetic MySQL/Redis.
This is API evidence, not browser/TLS acceptance. No tokens or passwords are printed.
"""
import base64,hashlib,hmac,json,secrets,struct,time,traceback
from pathlib import Path
import bcrypt,pymysql,requests
ROOT=Path(__file__).resolve().parents[2];HOME=ROOT/'rollback/multitenant-20260929/schema/e2e'
ident=json.loads((HOME/'identities.json').read_text());dbspec=json.loads((HOME/'connection.json').read_text())
assert dbspec['url'].startswith('jdbc:mysql://127.0.0.1:64029/mt705_probe_')
db=pymysql.connect(host='127.0.0.1',port=64029,user=dbspec['username'],password=dbspec['password'],database=dbspec['database'],autocommit=True)
checks=[]; session=requests.Session();session.trust_env=False
def sql(query,args=()):
    with db.cursor() as cursor:cursor.execute(query,args);return cursor.fetchall()
def check(value,label):
    if not value:raise AssertionError(label)
    checks.append(label)
def api(host,path,method='GET',body=None,token=None,extra=None,ok=True):
    headers={'Host':host,'Origin':'https://'+host}
    if token:headers['Authorization']='Bearer '+token
    if extra:headers.update(extra)
    r=session.request(method,'http://127.0.0.1:64031'+path,headers=headers,json=body,timeout=25)
    if ok and not 200<=r.status_code<300:raise AssertionError(path+' returned '+str(r.status_code)+' '+r.text[:300])
    try:return r.status_code,r.json()
    except ValueError:return r.status_code,r.text
def totp():
    digest=hmac.new(base64.b32decode(ident['totpSecret']),struct.pack('>Q',int(time.time())//30),hashlib.sha1).digest();offset=digest[-1]&15
    return str((struct.unpack('>I',digest[offset:offset+4])[0]&0x7fffffff)%1000000).zfill(6)
def ticket(control,tenant):
    binding=secrets.token_urlsafe(32)
    _,response=api('control.localhost',f'/api/control/tenants/{tenant}/access-ticket','POST',{'browserBinding':binding},control)
    return {'ticket':response['ticket'],'browserBinding':binding,'expectedTenantId':tenant}
def run():
    # Synthetic user identities only; caller cannot choose a real/business DB.
    password=secrets.token_urlsafe(20);encoded=bcrypt.hashpw(password.encode(),bcrypt.gensalt()).decode()
    sql("UPDATE user_account SET email='same-e2e@example.test',password_hash=%s,status='normal',last_page_sequence=NULL WHERE tenant_id IN (2,3) AND id IN (7000010,7000011)",(encoded,))
    _,login=api('control.localhost','/api/control/auth/login','POST',{'account':ident['account'],'password':ident['password'],'totp':totp()});control=login['token']
    check('passwordHash' not in login['user'] and 'mfaSecret' not in login['user'],'Control login masks credentials')
    check(api('control.localhost','/api/control/auth/login','POST',{'account':ident['account'],'password':ident['password'],'totp':'invalid'},ok=False)[0]>=400,'MFA required')
    check(api('admin.localhost','/api/control/tenants',token=control,ok=False)[0]==403,'Control API rejects admin host')
    check(api('unknown.localhost','/api/tenant/features',ok=False)[0]==403,'Unknown host has no default tenant')
    before=sql('SELECT COUNT(*) FROM admin_user')[0][0]
    enter=ticket(control,2);wrong=dict(enter,browserBinding=secrets.token_urlsafe(32))
    check(api('admin.localhost','/api/admin/auth/control-exchange','POST',wrong,ok=False)[0]>=400,'Wrong browser binding rejected without consuming ticket')
    _,a=api('admin.localhost','/api/admin/auth/control-exchange','POST',enter);at=a['token']
    check(api('admin.localhost','/api/admin/auth/control-exchange','POST',enter,ok=False)[0]>=400,'Ticket replay rejected')
    _,b=api('admin.localhost','/api/admin/auth/control-exchange','POST',ticket(control,3));bt=b['token']
    check(a['user']['tenantId']==2 and b['user']['tenantId']==3 and a['user']['controlActorId']==b['user']['controlActorId'],'Two access sessions retain same real actor and different tenants')
    check(sql('SELECT COUNT(*) FROM admin_user')[0][0]==before,'Control entry creates no hidden tenant admin')
    check(api('admin.localhost','/api/control/tenants',token=at,ok=False)[0]>=400,'Tenant access cannot call control plane')
    own=api('admin.localhost','/api/admin/users/7000010',token=at)[1]
    check('same-e2e@example.test' in json.dumps(own),'Own user detail readable')
    check(api('admin.localhost','/api/admin/users/7000011',token=at,ok=False)[0]>=400,'Foreign user detail denied')
    # Per-tab preference IDs must not collide for the same CONTROL actor.
    columns=[{'id':'email','visible':True,'fixed':''}]
    api('admin.localhost','/api/admin/table-preferences/e2e','PUT',columns,at)
    check(api('admin.localhost','/api/admin/table-preferences/e2e',token=bt)[1]==[],'CONTROL presentation preferences do not cross tenants')
    # Actual user auth, same email but distinct tenant account.
    _,ua=api('a.localhost','/api/auth/login','POST',{'account':'same-e2e@example.test','password':password})
    _,ub=api('b.localhost','/api/auth/login','POST',{'account':'same-e2e@example.test','password':password})
    check(ua['user']['id']!=ub['user']['id'],'Same email signs in to different tenant accounts')
    activity={'pageCode':'assets','deviceType':'PC','sequence':int(time.time()*1000)}
    api('a.localhost','/api/auth/activity','POST',activity,ua['token'])
    check(api('b.localhost','/api/auth/activity','POST',activity,ua['token'],ok=False)[0]>=400,'A user JWT rejected on B domain')
    check(api('a.localhost','/api/auth/activity','POST',dict(activity,pageCode='https://sensitive.test/?secret=1'),ua['token'],ok=False)[0]>=400,'Recent page only accepts whitelist')
    check(api('a.localhost','/api/tenant/features',extra={'X-Forwarded-Host':'b.localhost'},ok=False)[0]==403,'Untrusted forwarded host rejected')
    _,online=api('control.localhost','/api/control/tenants/2/online',token=control)
    check('assets' in json.dumps(online) and '7000011' not in json.dumps(online),'Online page scoped to tenant A')
    check(api('admin.localhost','/api/admin/users/7000010',token=bt,ok=False)[0]>=400,'B access session cannot read A user')
    api('control.localhost','/api/control/access-sessions/'+a['accessSession']['id']+'/revoke','POST',{},control)
    check(api('admin.localhost','/api/admin/users/7000010',token=at,ok=False)[0]>=400,'Revocation immediate at API')
    api('admin.localhost','/api/admin/users/7000011',token=bt)
    check(True,'Revoking A leaves B access usable')
    sql('UPDATE control_access_session SET last_activity_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 16 MINUTE) WHERE id=%s',(b['accessSession']['id'],))
    check(api('admin.localhost','/api/admin/users/7000011',token=bt,ok=False)[0]>=400,'Idle access expires server-side')
    # Store ephemeral fixture login for subsequent browser acceptance; never in a report.
    state={'userEmail':'same-e2e@example.test','userPassword':password,'controlToken':control,'userA':ua,'userB':ub}
    (HOME/'api-identities.json').write_text(json.dumps(state),encoding='utf8')
    return {'passed':True,'scope':'real local backend HTTP API + isolated MySQL/Redis, not browser or production TLS acceptance','checks':checks}
try:
    report=run()
except Exception as error:
    report={'passed':False,'checks':checks,'error':str(error)}
finally:
    db.close()
(ROOT/'reports/multitenant/e2e-api.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps({'passed':report['passed'],'checks':len(checks),'error':report.get('error')},ensure_ascii=True))
raise SystemExit(0 if report['passed'] else 1)
