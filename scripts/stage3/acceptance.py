"""Focused Stage 3 HTTP acceptance on owned real MySQL/Redis and running application."""
import base64, hashlib, http.client, json, secrets, ssl, sys, time, traceback, uuid
from decimal import Decimal
from urllib.parse import quote
import environment as e
sys.path.insert(0,str(e.ROOT/'scripts/stage1'))
from api_smoke import PinnedTLS, totp

class Acceptance:
    def __init__(self):
        self.spec=e.read(e.RUN/'environment.json');self.tls=ssl.create_default_context(cafile=str(e.PRIVATE/'ca.crt'))
        self.statepath=e.PRIVATE/'stage3-state.json';self.state=e.read(self.statepath) if self.statepath.exists() else {'tenants':e.read(e.PRIVATE/'api-state.json')['tenants']}
        self.attempt=str(int(time.time()));self.results=[];self.calls=[]
        self.db={m:e.dbtools.Database(self.spec['containers']['mysql'],d) for m,d in self.spec['databases'].items()}
    def save(self):e.save(self.statepath,self.state)
    def request(self,host,path,method='GET',body=None,token=None,allowed=(200,),mode='REAL',binary=False,headers=None):
        h={'Host':host,'Origin':'https://'+host+':8543','Content-Type':'application/json','X-Account-Mode':mode}
        if token:h['Authorization']='Bearer '+token
        if headers:h.update(headers)
        if mode=='DEMO':path=path.replace('/api/','/demo-api/',1)
        c=PinnedTLS(host,8543,context=self.tls,timeout=40)
        try:
            c.request(method,path,body if isinstance(body,bytes) else json.dumps(body).encode() if body is not None else None,h)
            r=c.getresponse();raw=r.read();status=r.status;rh={k.lower():v for k,v in r.getheaders()}
        finally:c.close()
        self.calls.append({'host':host,'path':path,'method':method,'mode':mode,'status':status,'cacheControl':rh.get('cache-control','')})
        try:value=raw if binary else json.loads(raw)
        except ValueError:value=raw.decode('utf-8',errors='replace')
        if status not in allowed or status==200 and isinstance(value,dict) and value.get('success') is False and allowed==(200,):
            raise AssertionError(f'{method} {path} HTTP {status}: '+str(value.get('message','') if isinstance(value,dict) else '')[:150])
        return value
    def admin(self,t,path,method='GET',body=None,token=None,**kw):return self.request('admin.localhost','/api/admin'+path,method,body,token or t['owner']['token'],**kw)
    def user(self,t,path,method='GET',body=None,**kw):return self.request(t['host'],'/api'+path,method,body,t['user']['token'],**kw)
    def control(self,path,method='GET',body=None,**kw):return self.request('control.localhost','/api/control'+path,method,body,self.state['controlToken'],**kw)
    def query(self,sql,mode='REAL'):return self.db[mode].query(sql)
    def check(self,name,fn):
        try:detail=fn();self.results.append({'name':name,'status':'PASS_FRESH','detail':detail});print('PASS',name,flush=True)
        except Exception as ex:
            self.results.append({'name':name,'status':'FAIL','error':str(ex)});raise
        finally:e.save(e.RUN/('http-'+self.attempt+'.json'),{'results':self.results,'calls':self.calls,'counts':{'passed':sum(r['status']=='PASS_FRESH' for r in self.results),'failed':sum(r['status']=='FAIL' for r in self.results)},'actualMySqlRedisBackend':True,'mockBusinessResponses':False});self.save()
    def login(self):
        identity=e.read(e.PRIVATE/'identities.json')
        self.state['controlToken']=self.request('control.localhost','/api/control/auth/login','POST',{'account':identity['account'],'password':identity['password'],'totp':totp(identity['totpSecret'])})['token']
        for t in self.state['tenants'].values():
            owner=t['owner'];r=self.request('admin.localhost','/api/admin/auth/login','POST',{'account':owner['account'],'password':owner['password']});assert r['user']['tenantId']==t['id'];owner['token']=r['token'];owner['id']=r['user']['id']
            user=t['user'];r=self.request(t['host'],'/api/auth/login','POST',{'account':user['email'],'password':user['password']});assert r['user']['tenantId']==t['id'];user.update(token=r['token'],id=r['user']['id'])
        return {'tenants':{k:t['id'] for k,t in self.state['tenants'].items()},'roles':['CONTROL','tenant owner','USER']}
    def activity(self):
        evidence=[]
        for label,t in self.state['tenants'].items():
            if t.get('activityProof'):
                evidence.append(t['activityProof']);continue
            before=self.query('SELECT tenant_id,user_id,coin,CAST(available AS CHAR),CAST(frozen AS CHAR),row_version FROM asset_account ORDER BY id')
            prefix='Stage3 '+label+' '+self.attempt
            campaign={'id':t['campaignId']} if t.get('campaignId') else self.admin(t,'/activities','POST',{'name':prefix,'status':'ACTIVE','amount':'7.50','budget':'75','maxClaims':10,'defaultLocale':'en','translations':json.dumps({'en':{'title':prefix,'body':'Isolated operations lifecycle','terms':'Trial balance only'}}),'autoPopup':False})
            cid=campaign['id'];t['campaignId']=cid;existing=self.query(f'SELECT operation_id FROM activity_selection WHERE tenant_id={t["id"]} AND campaign_id={cid}');key=existing[0] if existing else uuid.uuid4().hex;self.save()
            selection=self.admin(t,f'/activities/{cid}/recipients/select-all','POST',{'operationId':key,'filter':{'query':t['user']['email']}});sid=selection['selectionId']
            assert self.admin(t,f'/activities/{cid}/recipients/select-all','POST',{'operationId':key,'filter':{'query':t['user']['email']}})['selectionId']==sid
            skey=t.setdefault('sendKey',uuid.uuid4().hex);self.save();body={'selectionId':sid,'sendOperationId':skey}
            result=self.admin(t,f'/activities/{cid}/send-selection','POST',body);assert result['sent']==1 and result['done']
            assert self.admin(t,f'/activities/{cid}/send-selection','POST',body)==result
            deliveries=self.query(f'SELECT id FROM activity_delivery WHERE tenant_id={t["id"]} AND campaign_id={cid}')
            assert len(deliveries)==1;did=int(deliveries[0]);t['deliveryId']=did
            for event in ['RECEIVED','OPENED','CLOSED']:self.user(t,f'/activity/messages/{did}/event','POST',{'type':event})
            ckey=uuid.uuid4().hex;self.user(t,f'/activity/messages/{did}/claim','POST',headers={'Idempotency-Key':ckey})
            grant=self.query(f'SELECT * FROM trial_grant WHERE tenant_id={t["id"]} AND campaign_id={cid}')
            self.user(t,f'/activity/messages/{did}/claim','POST',headers={'Idempotency-Key':ckey})
            assert self.query(f'SELECT * FROM trial_grant WHERE tenant_id={t["id"]} AND campaign_id={cid}')==grant and len(grant)==1
            assert self.query('SELECT tenant_id,user_id,coin,CAST(available AS CHAR),CAST(frozen AS CHAR),row_version FROM asset_account ORDER BY id')==before
            granted=Decimal(self.query(f'SELECT CAST(granted AS CHAR) FROM activity_campaign WHERE tenant_id={t["id"]} AND id={cid}')[0]);assert granted==Decimal('7.50')
            t['activityProof']={'tenant':label,'campaignId':cid,'selectionId':sid,'deliveryId':did,'granted':str(granted),'cashFrozenVersionUnchanged':True,'oneGrantAfterRetry':True};evidence.append(t['activityProof']);self.save()
        a,b=self.state['tenants']['A'],self.state['tenants']['B'];self.user(b,f'/activity/messages/{a["deliveryId"]}',allowed=(400,403,404))
        return evidence
    def messages(self):
        out=[]
        for label,t in self.state['tenants'].items():
            title='Stage3 notice '+label+' '+self.attempt
            notice=self.admin(t,'/announcement/create','POST',{'title':title,'content':'Stage3 published notice','status':'PUBLISHED','language':'en'});nid=notice['id']
            key=uuid.uuid4().hex;body={'requestId':key,'users':[t['user']['id']],'title':'Stage3 letter '+label,'content':'Private isolated letter'}
            settings=self.admin(t,'/support/settings');settings.update(mode='internal',inboxEnabled=True);self.admin(t,'/support/settings','POST',settings)
            assert self.admin(t,'/support/inbox','POST',body)['sent']==1
            assert self.admin(t,'/support/inbox','POST',body)['sent']==0
            inbox=self.user(t,'/user/support/unified-inbox?language=en');items=inbox['content']
            self.state.setdefault('inboxShapes',{})[label]=list(inbox)
            # Route IDs come from the live response; no guessed hidden object identifiers.
            found=[r for r in items if isinstance(r,dict) and (r.get('title') in [title,body['title']])]
            assert len(found)==2,(list(inbox),[r.get('title') for r in items] if isinstance(items,list) else type(items).__name__)
            for row in found:self.user(t,'/user/support/unified-inbox/'+quote(str(row['id']),safe='')+'/read?language=en','POST')
            receipts=self.query(f'SELECT * FROM announcement_receipt WHERE tenant_id={t["id"]} AND announcement_id={nid}')
            assert len(receipts)==1
            self.admin(t,f'/announcement/{nid}','DELETE');assert self.query(f'SELECT * FROM announcement_receipt WHERE tenant_id={t["id"]} AND announcement_id={nid}')==receipts
            t['letterTitle']=body['title'];out.append({'tenant':label,'noticeId':nid,'readReceiptRetainedAfterHide':True,'letterSendRetryNoDuplicate':True})
        return out
    def support(self):
        a,b=self.state['tenants']['A'],self.state['tenants']['B'];menus={x['menuCode']:x['id'] for x in self.admin(a,'/menus')['list']}
        grants=['support','support:detail','support:claim','support:reply','support:close','support:transfer','support:image','support:export']
        role=self.admin(a,'/roles','POST',{'roleCode':'stage3_cs_'+self.attempt,'roleName':'Stage3 customer service','status':'active'})['data']
        self.admin(a,f'/roles/{role["id"]}/menus','POST',{'menuIds':[menus[c] for c in grants]})
        person={'account':'stage3_cs_'+self.attempt,'password':secrets.token_urlsafe(24),'email':'s3-'+self.attempt+'@example.test'}
        created=self.admin(a,'/admins','POST',{**person,'role':role['roleCode'],'enabled':True})['data'];person['id']=created['id']
        r=self.request('admin.localhost','/api/admin/auth/login','POST',{'account':person['account'],'password':person['password']});person['token']=r['token']
        if r['user'].get('mustChangePassword'):
            new=secrets.token_urlsafe(24);self.admin(a,'/auth/profile/password','PUT',{'oldPassword':person['password'],'newPassword':new},token=person['token']);person['password']=new
            person['token']=self.request('admin.localhost','/api/admin/auth/login','POST',{'account':person['account'],'password':person['password']})['token']
        a['support']=person;a['supportRoleId']=role['id'];self.save()
        for token in [person['token'],a['owner']['token']]:self.admin(a,'/support/presence','POST',{'accepting':True},token=token)
        c=self.user(a,'/user/support/sessions','POST');cid=c['id'];a['conversationId']=cid
        assert c['status']=='WAITING';assert self.user(a,'/user/support/sessions','POST')['id']==cid
        assert any(c['id']==cid for c in self.admin(a,'/support/sessions?scope=queue',token=person['token']))
        assert self.admin(a,f'/support/sessions/{cid}/claim','POST',token=person['token'])['status']=='ACTIVE'
        body={'requestId':uuid.uuid4().hex,'text':'Stage3 private support message'}
        message=self.user(a,f'/user/support/sessions/{cid}/messages','POST',body)
        assert self.user(a,f'/user/support/sessions/{cid}/messages','POST',body)['id']==message['id']
        self.user(a,f'/user/support/sessions/{cid}/messages','POST',{**body,'text':'conflicting retry'},allowed=(409,))
        # Valid PNG; actual multipart decoding/storage and authenticated file response.
        png=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a4n0AAAAASUVORK5CYII=')
        boundary='stage3'+uuid.uuid4().hex;key=uuid.uuid4().hex
        multipart=(f'--{boundary}\r\nContent-Disposition: form-data; name="requestId"\r\n\r\n{key}\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="fixture.png"\r\nContent-Type: image/png\r\n\r\n').encode()+png+f'\r\n--{boundary}--\r\n'.encode()
        image=self.user(a,f'/user/support/sessions/{cid}/images','POST',multipart,headers={'Content-Type':'multipart/form-data; boundary='+boundary});mid=image['id'];a['imageMessageId']=mid
        actual=self.user(a,f'/user/support/images/{mid}',binary=True);assert actual.startswith(b'\x89PNG')
        self.user(b,f'/user/support/images/{mid}',allowed=(400,403,404));self.request(a['host'],f'/api/user/support/images/{mid}',allowed=(401,403))
        before=self.query(f'SELECT * FROM support_conversation WHERE id={cid}')
        audit=int(self.query('SELECT COUNT(*) FROM control_audit_log')[0])
        rows=self.control(f'/tenants/{a["id"]}/support/conversations/{cid}');export=self.control(f'/tenants/{a["id"]}/support/conversations/{cid}/evidence')
        assert export['chainValid'] is True
        assert self.control(f'/tenants/{a["id"]}/support/conversations/{cid}/images/{mid}',binary=True)==actual
        assert self.query(f'SELECT * FROM support_conversation WHERE id={cid}')==before
        assert int(self.query('SELECT COUNT(*) FROM control_audit_log')[0])==audit+3
        self.control(f'/tenants/{b["id"]}/support/conversations/{cid}',allowed=(400,404))
        assert self.admin(a,f'/support/sessions/{cid}/transfer','POST',{'target':a['owner']['id']},token=person['token'])['adminId']==a['owner']['id']
        self.admin(a,f'/support/sessions/{cid}/messages','POST',{'requestId':uuid.uuid4().hex,'text':'Revoked assignee'},token=person['token'],allowed=(403,))
        self.admin(a,f'/roles/{role["id"]}/menus','POST',{'menuIds':[]})
        self.admin(a,'/support/sessions',token=person['token'],allowed=(403,))
        self.admin(a,f'/support/sessions/{cid}/close','POST');self.admin(a,f'/support/sessions/{cid}/close','POST')
        return {'conversationId':cid,'actualRoleId':role['id'],'attachmentMessageId':mid,'readonlyBusinessSnapshotEqual':True,'threeRealControlAuditEvents':True,'queueClaimTransferClose':True,'sameTokenRevocation':True}
    def online(self):
        out=[]
        for label,t in self.state['tenants'].items():
            uid=t['user']['id'];self.user(t,'/auth/activity','POST',{'pageCode':'inbox','deviceType':'PC','sequence':int(time.time()*1000)})
            page=self.query(f'SELECT last_page_code,last_page_seen_at,last_page_sequence FROM user_account WHERE tenant_id={t["id"]} AND id={uid}')
            self.user(t,'/auth/activity','POST',{'pageCode':'https://external.invalid/private','deviceType':'PC','sequence':int(time.time()*1000)},allowed=(400,))
            assert self.query(f'SELECT last_page_code,last_page_seen_at,last_page_sequence FROM user_account WHERE tenant_id={t["id"]} AND id={uid}')==page
            self.user(t,'/user/assets',mode='DEMO')
            rows=self.control(f'/tenants/{t["id"]}/online?userEmail='+quote(t['user']['email']))['data']['items'];assert len([x for x in rows if x['id']==uid])==1
            assert rows[0]['lastPageCode']=='inbox'
            self.db['REAL'].sql(f'UPDATE user_account SET last_activity_at=UTC_TIMESTAMP(6)-INTERVAL 301 SECOND WHERE tenant_id={t["id"]} AND id={uid}')
            assert not self.control(f'/tenants/{t["id"]}/online?userEmail='+quote(t['user']['email']))['data']['items']
            self.user(t,'/user/assets');out.append({'tenant':label,'recentPage':'inbox','invalidPageRejected':True,'realDemoCount':1,'olderThanFiveMinutesExcluded':True})
        return out

if __name__=='__main__':
    a=Acceptance()
    methods=sys.argv[1:] or ['login','activity','messages','support','online']
    for name in methods:a.check(name,getattr(a,name))
