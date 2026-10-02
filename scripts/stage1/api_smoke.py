"""Stage 1 acceptance against the owned real MySQL/Redis/backend/TLS front door.
Only loopback destinations; real server routes and SMTP, no mocks or readiness SQL.
Every attempt preserves evidence. Secrets and resume state stay in the restricted private directory.
"""
import argparse, base64, datetime, email, email.policy, hashlib, hmac, http.client
import json, os, re, secrets, socket, ssl, struct, subprocess, sys, time, zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'scripts/multitenant'))
import mysql_migration as migration

for stream in (sys.stdout,sys.stderr):
    if hasattr(stream,'reconfigure'): stream.reconfigure(encoding='utf-8')

class PinnedTLS(http.client.HTTPSConnection):
    def connect(self):
        self.sock = self._context.wrap_socket(socket.create_connection(('127.0.0.1',self.port),self.timeout),server_hostname=self.host)

def save(path, value):
    temporary=path.with_suffix(path.suffix+'.tmp')
    temporary.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
    os.replace(temporary,path)

def totp(secret):
    raw=hmac.new(base64.b32decode(secret),struct.pack('>Q',int(time.time())//30),hashlib.sha1).digest()
    return str((struct.unpack('>I',raw[raw[-1]&15:(raw[-1]&15)+4])[0]&0x7fffffff)%1000000).zfill(6)

class Acceptance:
    def __init__(self, home):
        self.home=home;self.private=home/'private';self.spec=json.loads((home/'environment.json').read_text())
        assert self.spec['run']==home.name and self.spec['database'].startswith('mt705_'), 'Owned fixture required'
        self.ident=json.loads((self.private/'identities.json').read_text())
        self.db=migration.Database(self.spec['containers']['mysql'],self.spec['database']);assert self.db.test and self.db.identity['container']==self.spec['containers']['mysql'], 'Explicit owned MySQL test instance required'
        connection=json.loads((self.private/'connection.json').read_text())
        assert connection['runId']==self.spec['run'] and connection['database']==self.spec['database']
        assert connection['containerId']==self.db.identity['container_id'] and connection['containerName']==self.spec['containers']['mysql']
        assert re.match(r'jdbc:mysql://127[.]0[.]0[.]1:'+str(self.spec['mysqlPort'])+'/'+re.escape(self.spec['database'])+r'(?:[?]|$)',connection['url'])
        assert self.db.query('select @@server_uuid')[0]==connection['serverUuid']
        self.container_ids={}
        for kind,container_port in [('mysql',3306),('redis',6379)]:
            instance=json.loads(subprocess.check_output(['docker','inspect',self.spec['containers'][kind]]))[0]
            labels=instance['Config']['Labels'];expected={str(container_port)+'/tcp':[{'HostIp':'127.0.0.1','HostPort':str(self.spec[kind+'Port'])}]}
            assert instance['State']['Running'] and instance['Name']=='/'+self.spec['containers'][kind]
            assert labels.get('com.gtcfesk.stage1.run')==self.spec['run'] and labels.get('com.docker.compose.project')=='mt705-'+self.spec['run']
            assert Path(labels['com.docker.compose.project.config_files']).resolve()==(home/'compose.json').resolve()
            assert instance['NetworkSettings']['Ports']==expected and instance['HostConfig']['PortBindings']==expected, 'Exact loopback-only published fixture ports required'
            assert re.fullmatch('[0-9a-f]{64}',instance['Id'])
            if kind=='mysql': assert instance['Id']==connection['containerId'] and labels.get('com.gtcfesk.multitenant.test')=='true'
            else: assert instance['Config']['Image'].startswith('redis:')
            self.container_ids[kind]=instance['Id']
        proof=self.private/'api-container-identities.json'
        if proof.exists(): assert json.loads(proof.read_text())==self.container_ids, 'Fixture container replacement requires a new independent run'
        else: save(proof,self.container_ids)
        self.tls=ssl.create_default_context(cafile=str(self.private/'ca.crt'))
        self.statepath=self.private/'api-state.json'
        self.state=json.loads(self.statepath.read_text()) if self.statepath.exists() else {'tenants':{}}
        self.directory=home/'api'/datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        self.directory.mkdir(parents=True);self.results=[];self.calls=[];self.control=None;self.probes=[];self.fresh_agent_evidence=[];self.scope='full-stage1'
        self.persist()
    def persist(self): save(self.statepath,self.state)
    def redact(self,text):
        values=[]
        def collect(value):
            if isinstance(value,dict):
                for key,item in value.items():
                    if isinstance(item,str) and re.search('password|secret|token|ticket',key,re.I):values.append(item)
                    else:collect(item)
            elif isinstance(value,list):
                for item in value:collect(item)
        collect(self.ident);collect(self.state)
        for value in values:
            if value:text=text.replace(value,'[REDACTED]')
        return text
    def request(self,host,path,method='GET',body=None,token=None,allowed=(200,),headers=None,direct=False,binary=False):
        port=self.spec.get('httpsPort',443)
        suffix='' if port==443 else ':'+str(port)
        h={'Host':host,'Origin':'https://'+host+suffix,'Content-Type':'application/json'}
        if token:h['Authorization']='Bearer '+token
        if headers:h.update(headers)
        connection=http.client.HTTPConnection('127.0.0.1',self.spec['backendPort'],timeout=35) if direct else PinnedTLS(host if host in ['control.localhost','admin.localhost','default.localhost','a.localhost','b.localhost','a2.localhost'] else 'a.localhost',port=port,context=self.tls,timeout=35)
        try:
            connection.request(method,path,body if isinstance(body,bytes) else json.dumps(body).encode() if body is not None else None,h)
            reply=connection.getresponse();payload=reply.read();raw=payload if binary else payload.decode('utf-8',errors='replace');status=reply.status;cache_control=reply.getheader('Cache-Control','')
        finally:connection.close()
        self.calls.append({'method':method,'host':host,'path':path,'status':status,'transport':'http-loopback' if direct else 'TLS-verified','cacheControl':cache_control})
        try:value=raw if binary else json.loads(raw)
        except ValueError:value=raw
        if status not in allowed or (status==200 and isinstance(value,dict) and value.get('success') is False and allowed==(200,)):
            message=value.get('message',value.get('error','')) if isinstance(value,dict) else raw[:180]
            raise AssertionError(self.redact(f'{method} {host}{path}: HTTP {status}; {message}'))
        return value
    def check(self,name,action):
        start=time.monotonic()
        try:
            action();self.results.append({'name':name,'status':'PASS','seconds':round(time.monotonic()-start,3)})
            print('PASS '+name,flush=True)
        except Exception as failure:
            self.results.append({'name':name,'status':'FAIL','seconds':round(time.monotonic()-start,3),'error':self.redact(str(failure))})
            raise
        finally:self.write_report()
    def write_report(self):
        save(self.directory/'report.json',{'run':self.spec['run'],'database':self.spec['database'],'tests':self.results,'passed':sum(x['status']=='PASS' for x in self.results),'failed':sum(x['status']=='FAIL' for x in self.results),'skipped':0,'requests':self.calls,'currentVersionDraftProbes':self.probes,'currentVersionFreshAgentProvisioning':self.fresh_agent_evidence,'executionScope':self.scope})
    def sql(self,query): return self.db.query(query)
    def scalar(self,query): return self.sql(query)[0]
    def c(self,path,method='GET',body=None,allowed=(200,)):
        return self.request('control.localhost','/api/control'+path,method,body,self.control,allowed)
    def admin(self,t,path,method='GET',body=None,token=None,allowed=(200,)):
        return self.request('admin.localhost','/api/admin'+path,method,body,token or t['owner']['token'],allowed)
    def front(self,t,path,method='GET',body=None,token=None,allowed=(200,)):
        return self.request(t['host'],'/api'+path,method,body,token,allowed)
    def login_control(self):
        self.control=self.request('control.localhost','/api/control/auth/login','POST',{'account':self.ident['account'],'password':self.ident['password'],'totp':totp(self.ident['totpSecret'])})['token']
        assert self.c('/auth/me')['data']['account']==self.ident['account']
    def policy(self,t,key,value,locked=True):
        return self.c(f"/tenants/{t['id']}/policies",'PUT',{'key':key,'value':value,'locked':locked,'reason':'stage1 actual local acceptance'})['data']
    def access(self,t):
        binding=secrets.token_urlsafe(32);ticket=self.c(f"/tenants/{t['id']}/access-ticket",'POST',{'browserBinding':binding})
        result=self.request('admin.localhost','/api/admin/auth/control-exchange','POST',{'ticket':ticket['ticket'],'browserBinding':binding,'expectedTenantId':t['id']})
        assert result['user']['displayName']=='总控管理' and result['user']['controlActorId']>0 and result['user']['id']<0
        assert result['user']['tenantId']==t['id'] and result['user']['role']=='super_admin'
        return result
    def config(self,t,key,value,token):
        return self.admin(t,'/config/save','POST',{'key':key,'value':value,'description':'stage1 local acceptance','reason':'stage1 actual business write'},token)
    def domain(self,t,hostname):
        current=next(x for x in self.c('/tenants')['data'] if x['id']==t['id'])
        if current.get('frontendHost')==hostname and current.get('domainVerified'):t['host']=hostname;self.persist();return
        base=f"/tenants/{t['id']}/domains/"
        candidate=self.c(base+'prepare','POST',{'hostname':hostname,'reason':'stage1 owned local TLS route'})['data']
        assert candidate['status']=='PENDING'
        self.c(base+'activate','POST',{'hostname':hostname,'version':candidate['version'],'reason':'must reject unverified'},allowed=(400,403))
        verified=self.c(base+'verify','POST',{'hostname':hostname,'version':candidate['version']})['data']
        assert verified['status']=='VERIFIED'
        active=self.c(base+'activate','POST',{'hostname':hostname,'version':verified['version'],'reason':'stage1 verified local activation'})['data']
        assert active['status']=='ACTIVE';t['host']=hostname;self.persist()
    def empty_template(self,row):
        assert row['status']=='DRAFT' and not row['configReady'] and not row['domainVerified'] and not row.get('frontendHost')
        for table in ['user_account','asset_account','admin_user','backend_login','contract_order','option_order','deposit_record','withdraw_record','verify_code','deposit_setting','activity_selection','activity_selection_member','activity_send_receipt','trial_grant','announcement_receipt']:
            assert self.scalar(f"select count(*) from {table} where tenant_id={row['id']}")=='0',table+' must not be cloned'
        assert set(self.sql(f"select config_key from system_config where tenant_id={row['id']}"))=={'site.name','system.timezone','support.settings'}, 'Only audited non-secret safe template configuration may be initialized'
        assert self.scalar(f"select count(*) from tenant_policy where tenant_id={row['id']} and policy_key like 'feature.%' and policy_value<>'false'")=='0', 'No feature may activate from the template'
        self.c(f"/tenants/{row['id']}",'PUT',{'status':'ACTIVE','configReady':True,'reason':'must reject false readiness'},allowed=(400,403))
    def factory_probe(self,label):
        suffix=secrets.token_hex(6);code='stage1_'+label.lower()+'_probe_'+suffix
        row=self.c('/tenants','POST',{'code':code,'name':'stage1 '+label+' final factory probe','reason':'explicit final-version DRAFT template and real owner probe'})['data']
        self.empty_template(row)
        account='stage1_probe_'+suffix+'_owner';password=secrets.token_urlsafe(24)
        owner=self.c(f"/tenants/{row['id']}/backend-accounts",'POST',{'type':'ADMIN','account':account,'email':suffix+'-probe@stage1.example.test','password':password,'role':'super_admin','reason':'explicit real probe owner, not a control-entry identity'})['data']
        assert owner['tenantId']==row['id'] and owner['subjectType']=='ADMIN' and owner['normalizedAccount']==account and owner['enabled']
        assert self.scalar(f"select count(*) from admin_user where tenant_id={row['id']} and id={owner['adminUserId']} and enabled=1 and role='super_admin' and must_change_password=1")=='1'
        assert self.scalar(f"select count(*) from backend_login where tenant_id={row['id']} and admin_user_id={owner['adminUserId']} and enabled=1 and subject_type='ADMIN'")=='1'
        report=self.c(f"/tenants/{row['id']}/readiness")['data'];assert not report['ready']
        login=self.request('admin.localhost','/api/admin/auth/login','POST',{'account':account,'password':password})
        assert login['user']['tenantId']==row['id'] and login['user']['mustChangePassword'], 'A draft owner may configure but must change the issued password first'
        self.request('admin.localhost','/api/admin/config/list',token=login['token'],allowed=(401,403))
        replacement=secrets.token_urlsafe(24)
        self.request('admin.localhost','/api/admin/auth/profile/password','PUT',{'oldPassword':password,'newPassword':replacement},login['token'])
        login=self.request('admin.localhost','/api/admin/auth/login','POST',{'account':account,'password':replacement})
        assert login['user']['tenantId']==row['id'] and not login['user']['mustChangePassword']
        self.request('admin.localhost','/api/admin/config/list',token=login['token'])
        current=next(t for t in self.c('/tenants')['data'] if t['id']==row['id'])
        assert current['status']=='DRAFT' and not current['configReady'] and not current['domainVerified'] and not current.get('frontendHost')
        self.probes.append({'label':label,'tenantId':row['id'],'code':code,'ownerId':owner['adminUserId'],'backendLoginId':owner['id'],'draft':True,'noCopiedIdentityAssetsOrdersSecretsAddresses':True,'actualFactoryAndRealOwnerCreated':True,'firstPasswordChangeEnforced':True,'draftOwnerConfigurationAccessVerified':True,'notActivatedOrBoundToDomain':True})
        self.write_report()

    def setup(self,label,code,host):
        if label in ('A','B'): self.factory_probe(label)
        tenants=self.c('/tenants')['data']; t=self.state['tenants'].get(label)
        if t is None:
            row=next((x for x in tenants if x['code']==code),None)
            if row is None:
                row=self.c('/tenants','POST',{'code':code,'name':'stage1 '+label,'reason':'stage1 API safe template'})['data']
                self.empty_template(row)
                self.state.setdefault('safeTemplateEvidence',{})[label]={'tenantId':row['id'],'emptyIdentityAssetsOrders':True,'draft':True,'verifiedByServer':False}
            t={'id':row['id'],'host':host,'label':label,'owner':{'account':'stage1_'+label+'_owner','email':label+'-owner@stage1.example.test','password':secrets.token_urlsafe(24)}}
            self.state['tenants'][label]=t;self.persist()
        owner=t['owner']
        if 'id' not in owner:
            created=self.c(f"/tenants/{t['id']}/backend-accounts",'POST',{'type':'ADMIN','account':owner['account'],'email':owner['email'],'password':owner['password'],'role':'super_admin','reason':'create real tenant owner'})['data']
            owner['id']=created['adminUserId'];self.persist()
        self.domain(t,t['host']); access=self.access(t)
        self.policy(t,'config.site.name','stage1 '+label,False)
        for key,value in {'site.name':'stage1 '+label,'system.timezone':'UTC','mail.host':'smtp.localhost','mail.port':'465','mail.username':'stage1','mail.password':self.ident['smtpPassword'],'mail.from':'stage1@stage1.example.test'}.items():
            self.config(t,key,value,access['token'])
        self.config(t,'support.settings',json.dumps({'mode':'off','inboxEnabled':False}),access['token'])
        for feature in ['registration','agent','support','inbox']:self.policy(t,'feature.'+feature,'true')
        settings=json.dumps({'mode':'internal','inboxEnabled':True,'capacity':5})
        self.config(t,'support.settings',settings,access['token'])
        ready=self.c(f"/tenants/{t['id']}/readiness")['data'];assert ready['ready'],str(ready['missing'])
        self.c(f"/tenants/{t['id']}",'PUT',{'status':'ACTIVE','configReady':True,'reason':'server validated completeness'})
        self.c('/access-sessions/'+access['accessSession']['id']+'/revoke','POST')
        self.login_owner(t);self.persist()
        assert self.admin(t,'/menus/current')['superAdmin']
    def login_owner(self,t):
        owner=t['owner']; response=self.request('admin.localhost','/api/admin/auth/login','POST',{'account':owner['account'],'password':owner['password']})
        assert response['user']['tenantId']==t['id']
        if response['user'].get('mustChangePassword'):
            new=secrets.token_urlsafe(24)
            self.admin(t,'/config/list',token=response['token'],allowed=(401,403))
            self.admin(t,'/auth/profile/password','PUT',{'oldPassword':owner['password'],'newPassword':new},response['token'])
            owner['password']=new;self.persist()
            response=self.request('admin.localhost','/api/admin/auth/login','POST',{'account':owner['account'],'password':new})
        owner['token']=response['token'];self.persist()
    def captcha(self,t):
        session=secrets.token_hex(16);challenge=self.front(t,'/auth/captcha','POST',{'captchaSession':session})
        command=['docker','exec',self.container_ids['redis'],'redis-cli','--raw','GET',f"security:{{registration}}:tenant:{t['id']}:challenge:{session}"]
        output=subprocess.run(command,check=True,capture_output=True,text=True,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0).stdout.strip()
        identifier,answer=output.split('|');assert identifier==challenge['captchaId'] and re.fullmatch('[A-Z0-9]{4}',answer)
        return {'captchaSession':session,'captchaId':identifier,'captchaCode':answer}
    def register(self,t,credentials,allowed=(200,)):
        body={'email':credentials['email'],'password':credentials['password'],'confirmPassword':credentials['password'],'countryCode':'+65','phone':credentials.get('phone','2025550141'),**self.captcha(t)}
        return self.front(t,'/auth/register','POST',body,allowed=allowed)
    def login_user(self,t,user=None):
        user=user or t['user'];response=self.front(t,'/auth/login','POST',{'account':user['email'],'password':user['password']})
        assert response['user']['tenantId']==t['id'];user['id']=response['user']['id'];user['token']=response['token'];self.persist();return response
    def users(self):
        for t in self.state['tenants'].values():
            user=t.setdefault('user',{'email':'shared@stage1.example.test','password':secrets.token_urlsafe(24),'phone':'2025550141'});self.persist()
            if 'id' not in user:
                reply=self.register(t,user);assert reply['user']['tenantId']==t['id'];user['id']=reply['user']['id'];self.persist()
            self.login_user(t);assert self.front(t,f"/user/{user['id']}/info",token=user['token'])['id']==user['id']
        a,b=self.state['tenants']['A'],self.state['tenants']['B']
        assert a['user']['id']!=b['user']['id'] and a['user']['password']!=b['user']['password']
        hashes=self.sql(f"select password_hash from user_account where id in ({a['user']['id']},{b['user']['id']})")
        assert len(hashes)==2 and hashes[0]!=hashes[1]
        assert self.scalar(f"select count(distinct tenant_id) from user_account where email='shared@stage1.example.test' and phone='2025550141'")=='3'
        self.front(b,'/auth/login','POST',{'account':b['user']['email'],'password':a['user']['password']},allowed=(400,401,403))
        # A repeated national phone inside one tenant must be controlled, including normalized input.
        for t in [a,b]:
            original=lambda:self.sql(f"select id,email,phone,country_code,password_hash,current_token from user_account where tenant_id={t['id']} order by id")+self.sql(f"select id,user_id,coin,available,frozen,row_version from asset_account where tenant_id={t['id']} order by id")
            before=original();count=self.scalar(f"select concat((select count(*) from user_account where tenant_id={t['id']}),':',(select count(*) from asset_account where tenant_id={t['id']}))")
            duplicate={'email':'duplicate-phone-'+secrets.token_hex(8)+'@stage1.example.test','phone':t['user']['phone'],'password':secrets.token_urlsafe(24)}
            self.register(t,duplicate,allowed=(400,409))
            assert self.calls[-1]['status'] in (400,409), 'Duplicate same-tenant phone may never return HTTP500'
            assert original()==before, 'Rejected duplicate phone must not mutate existing identities or accounts'
            assert count==self.scalar(f"select concat((select count(*) from user_account where tenant_id={t['id']}),':',(select count(*) from asset_account where tenant_id={t['id']}))")
            assert self.scalar(f"select count(*) from user_account where tenant_id={t['id']} and email='{duplicate['email']}'")=='0'
        self.state['registrationPhoneCollisionEvidence']={'tenantIds':[a['id'],b['id']],'sameTenantDuplicatePhoneRejected':True,'noNewUserOrAssetAndExistingIdentityUnchanged':True,'sameEmailAndPhoneAllowedAcrossTenants':True};self.persist()

    def email_delivery(self):
        a,b=self.state['tenants']['A'],self.state['tenants']['B'];before=set((self.private/'mail').glob('*.eml')) if (self.private/'mail').exists() else set()
        self.front(a,'/auth/sendEmailCode','POST',{'email':a['user']['email'],'scene':'forget_password'})
        for _ in range(40):
            added=set((self.private/'mail').glob('*.eml'))-before
            if added:break
            time.sleep(.25)
        assert added,'Real TLS SMTP must deliver MIME'
        message=email.message_from_bytes(next(iter(added)).read_bytes(),policy=email.policy.default)
        text='\n'.join(part.get_content() for part in message.walk() if part.get_content_maintype()=='text')
        code=re.search(r'(?<!\d)\d{6}(?!\d)',text);assert code,'Delivered verification code required'
        self.front(b,'/auth/resetPassword','POST',{'email':b['user']['email'],'password':b['user']['password'],'confirmPassword':b['user']['password'],'verifyCode':code.group(),'scene':'forget_password'},allowed=(400,403))
        # Consume actual delivered code on its own tenant; never write a fabricated VerifyCode.
        replacement=secrets.token_urlsafe(24)
        self.front(a,'/auth/resetPassword','POST',{'email':a['user']['email'],'password':replacement,'confirmPassword':replacement,'verifyCode':code.group(),'scene':'forget_password'})
        a['user']['password']=replacement;self.persist();self.login_user(a);self.login_user(b)
    def sms_sink(self):
        a,b=self.state['tenants']['A'],self.state['tenants']['B'];recipient='+120255501'+str(secrets.randbelow(100)).zfill(2)
        deliveries=[]
        for t in [a,b]:
            self.config(t,'sms.provider','local-sink',t['owner']['token'])
            status=self.admin(t,'/config/sms/status');assert status['status']=='LOCAL_SINK_ONLY' and status['realSupplierVerified'] is False
            self.admin(t,'/config/sms/test','POST',{'recipient':recipient,'purpose':'REGISTER'},allowed=(400,))
            sent=self.admin(t,'/config/sms/test','POST',{'recipient':recipient,'purpose':'CONFIG_TEST'})
            assert sent['status']=='LOCAL_SINK_ONLY' and sent['realSupplierVerified'] is False and 'code' not in sent
            path=self.private/'sms-api'/('tenant-'+str(t['id']))/(sent['requestId']+'.json')
            actual=json.loads(path.read_text(encoding='utf-8'));assert actual['tenantId']==t['id'] and actual['recipient']==recipient and actual['purpose']=='CONFIG_TEST'
            key=f"security:{{sms}}:tenant:{t['id']}:CONFIG_TEST:{sent['requestId']}"
            present=subprocess.run(['docker','exec',self.container_ids['redis'],'redis-cli','--raw','GET',key],capture_output=True,check=True,text=True,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0).stdout.strip()
            assert present.startswith('A|') and actual['code'] not in present
            deliveries.append({'tenant':t,'requestId':sent['requestId'],'recipient':recipient,'purpose':'CONFIG_TEST','code':actual['code'],'key':key})
        assert deliveries[0]['requestId']!=deliveries[1]['requestId']
        body={k:deliveries[0][k] for k in ['requestId','recipient','purpose','code']}
        self.admin(b,'/config/sms/consume','POST',body,allowed=(400,))
        for delivered in deliveries:
            t=delivered['tenant'];body={k:delivered[k] for k in ['requestId','recipient','purpose','code']}
            result=self.admin(t,'/config/sms/consume','POST',body);assert result['verified'] is True and result['realSupplierVerified'] is False
            self.admin(t,'/config/sms/consume','POST',body,allowed=(400,))
            assert self.scalar(f"select count(*) from operation_log where tenant_id={t['id']} and admin_id={t['owner']['id']} and operation_action='SMS_LOCAL_PREPARED'")!='0'
            assert self.scalar(f"select count(*) from operation_log where tenant_id={t['id']} and admin_id={t['owner']['id']} and operation_action='SMS_LOCAL_CONSUMED'")!='0'
            remaining=subprocess.run(['docker','exec',self.container_ids['redis'],'redis-cli','--raw','EXISTS',delivered['key']],capture_output=True,check=True,text=True,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0).stdout.strip()
            assert remaining=='0'
        self.state['smsSupplierEvidence']={'provider':'LOCAL_SINK_ONLY','realSupplierVerified':False,'tenantIds':[a['id'],b['id']],'separateFilesRedisAudit':True};self.persist()

    def identities_permissions(self):
        a,b=self.state['tenants']['A'],self.state['tenants']['B'];menus=self.admin(a,'/menus')['list'];codes={m['menuCode']:m['id'] for m in menus}
        for label,rolecode,grants in [('staff','stage1_staff',['announcement','announcement:create','roles','roles:assign_permission']),('support','stage1_support',['support','support:detail','support:claim','support:reply','support:close'])]:
            roles=self.admin(a,'/roles')['list'];role=next((x for x in roles if x['roleCode']==rolecode),None)
            if role is None:role=self.admin(a,'/roles','POST',{'roleCode':rolecode,'roleName':'stage1 '+label,'status':'active'})['data']
            self.admin(a,f"/roles/{role['id']}/menus",'POST',{'menuIds':[codes[c] for c in grants]})
            person=a.setdefault(label,{'account':'stage1_A_'+label,'email':label+'@stage1.example.test','password':secrets.token_urlsafe(24)});self.persist()
            if 'id' not in person:
                created=self.admin(a,'/admins','POST',{'account':person['account'],'email':person['email'],'password':person['password'],'role':rolecode,'enabled':True})['data'];person['id']=created['id'];self.persist()
            response=self.request('admin.localhost','/api/admin/auth/login','POST',{'account':person['account'],'password':person['password']})
            if response['user'].get('mustChangePassword'):
                replacement=secrets.token_urlsafe(24);self.admin(a,'/auth/profile/password','PUT',{'oldPassword':person['password'],'newPassword':replacement},response['token']);person['password']=replacement;self.persist()
                response=self.request('admin.localhost','/api/admin/auth/login','POST',{'account':person['account'],'password':replacement})
            person['token']=response['token'];person['roleId']=role['id'];self.persist()
        self.admin(a,'/support/sessions',token=a['support']['token'])
        self.admin(a,'/support/presence','POST',{'accepting':True},a['support']['token'])
        self.admin(a,'/config/list',token=a['support']['token'],allowed=(403,))
        staff=a['staff']
        self.admin(a,'/announcement/create','POST',{'title':'staff scope proof','content':'local stage1','language':'en','status':'PUBLISHED'},staff['token'])
        self.admin(a,f"/roles/{staff['roleId']}/menus",'POST',{'menuIds':[codes['announcement'],codes['announcement:delete']]},staff['token'],allowed=(403,))
        self.admin(a,f"/roles/{staff['roleId']}/menus",'POST',{'menuIds':[codes['announcement'],codes['roles'],codes['roles:assign_permission']]})
        self.admin(a,'/announcement/list',token=staff['token'])
        self.admin(a,'/announcement/create','POST',{'title':'revoked must reject','content':'local stage1','language':'en','status':'PUBLISHED'},staff['token'],allowed=(403,))
        self.admin(a,'/admins','POST',{'account':'stage1_forbidden_super','email':'forbidden@stage1.example.test','password':secrets.token_urlsafe(24),'role':'super_admin'},staff['token'],allowed=(403,))
        # Every backend subject shares one normalized namespace, across tenants and roles.
        for account in [a['owner']['account'],a['staff']['account'],a['support']['account']]:
            self.c(f"/tenants/{b['id']}/backend-accounts",'POST',{'type':'ADMIN','account':account.upper(),'email':secrets.token_hex(6)+'@stage1.example.test','password':secrets.token_urlsafe(24),'role':'admin','reason':'must reject global name collision'},allowed=(400,403))
        self.admin(b,'/admins','POST',{'account':a['support']['account'].upper(),'email':'owner-collision@stage1.example.test','password':secrets.token_urlsafe(24),'role':'admin','enabled':True},allowed=(400,403))
        for other in [b['owner']['account'],a['support']['account']]:
            self.admin(a,f"/admins/{a['staff']['id']}",'PUT',{'account':other.upper()},allowed=(400,403))
        self.admin(a,f"/admins/{a['staff']['id']}",'PUT',{'account':a['staff']['account'].upper()})
        self.admin(a,'/announcement/list',token=a['staff']['token'])
        agent=a.setdefault('agent',{'email':'agent-a@stage1.example.test','password':secrets.token_urlsafe(24),'phone':'2025550142','account':'stage1_a_agent'});self.persist()
        if 'id' not in agent:
            response=self.register(a,agent);agent['id']=response['user']['id'];self.persist()
        self.admin(a,'/users/updateUserType','POST',{'userId':agent['id'],'userType':'agent'})
        self.admin(a,'/backend-accounts','POST',{'type':'AGENT','subjectId':agent['id'],'account':agent['account'],'reason':'owner provisions real agent'})
        response=self.request('admin.localhost','/api/admin/auth/login','POST',{'account':agent['account'],'password':agent['password']});assert response['user']['tenantId']==a['id'] and response['user']['userType']=='agent'
        agent['token']=response['token'];self.persist()
        self.admin(a,f"/users/{agent['id']}/menus",'POST',{'menuIds':[codes['users']],'actions':{}})
        query=self.admin(a,'/users/query','POST',{'page':1,'size':20},agent['token'])
        assert query['total']==0, 'Agent cannot query unrelated tenant users'
        self.admin(a,f"/users/{agent['id']}/menus",'POST',{'menuIds':[],'actions':{}})
        self.admin(a,'/users/query','POST',{'page':1,'size':20},agent['token'],allowed=(403,))
        self.c(f"/tenants/{b['id']}/backend-accounts",'POST',{'type':'ADMIN','account':agent['account'],'email':'conflict-agent@stage1.example.test','password':secrets.token_urlsafe(24),'role':'admin','reason':'agent global name conflict'},allowed=(400,403))
        self.fresh_agent()
    def fresh_agent(self):
        a,b=self.state['tenants']['A'],self.state['tenants']['B']
        foreign=lambda: self.sql(f"select SHA2(CONCAT_WS('|',id,email,COALESCE(phone,''),password_hash,COALESCE(current_token,''),user_type,status,row_version),256) from user_account where tenant_id={b['id']} order by id")+self.sql(f"select SHA2(CONCAT_WS('|',id,user_id,coin,available,frozen,row_version),256) from asset_account where tenant_id={b['id']} order by id")+self.sql(f"select SHA2(CONCAT_WS('|',id,normalized_account,subject_type,COALESCE(user_id,0),COALESCE(admin_user_id,0),enabled),256) from backend_login where tenant_id={b['id']} order by id")
        b_before=foreign();before_users=int(self.scalar(f"select count(*) from user_account where tenant_id={a['id']}"));before_assets=int(self.scalar(f"select count(*) from asset_account where tenant_id={a['id']}"));before_logins=int(self.scalar(f"select count(*) from backend_login where tenant_id={a['id']}"))
        suffix=secrets.token_hex(8);agent={'attempt':self.directory.name,'email':'fresh-agent-'+suffix+'@stage1.example.test','password':secrets.token_urlsafe(24),'phone':'9'+str(secrets.randbelow(10**10)).zfill(10),'account':'stage1_agent_'+suffix}
        self.state.setdefault('freshAgentAttempts',[]).append(agent);self.persist()
        assert self.scalar(f"select count(*) from user_account where tenant_id={a['id']} and (email='{agent['email']}' or phone='{agent['phone']}')")=='0'
        assert self.scalar(f"select count(*) from backend_login where normalized_account in ('{agent['email']}','{agent['account']}')")=='0'
        registered=self.register(a,agent);assert self.calls[-1]['status']==200 and registered['user']['tenantId']==a['id'];agent['id']=registered['user']['id'];self.persist()
        assert self.scalar(f"select count(*) from user_account where tenant_id={a['id']} and id={agent['id']} and email='{agent['email']}' and phone='{agent['phone']}' and user_type='normal'")=='1'
        assert int(self.scalar(f"select count(*) from user_account where tenant_id={a['id']}"))==before_users+1
        assert int(self.scalar(f"select count(*) from asset_account where tenant_id={a['id']}"))==before_assets+3
        assert set(self.sql(f"select coin from asset_account where tenant_id={a['id']} and user_id={agent['id']}"))=={'FUND','CONTRACT','OPTION'}
        assert self.scalar(f"select count(*) from asset_account where tenant_id={a['id']} and user_id={agent['id']} and (available<>0 or frozen<>0)")=='0'
        assert self.scalar(f"select count(*) from backend_login where tenant_id={a['id']} and user_id={agent['id']}")=='0'
        # The real owner type transition creates the first AGENT row; the account endpoint then binds its login name.
        self.admin(a,'/users/updateUserType','POST',{'userId':agent['id'],'userType':'agent'});assert self.calls[-1]['status']==200
        assert int(self.scalar(f"select count(*) from backend_login where tenant_id={a['id']}"))==before_logins+1
        rows=self.sql(f"select id from backend_login where tenant_id={a['id']} and user_id={agent['id']} and subject_type='AGENT' and enabled=1");assert len(rows)==1;login_id=int(rows[0])
        bound=self.admin(a,'/backend-accounts','POST',{'type':'AGENT','subjectId':agent['id'],'account':agent['account'],'reason':'fresh current-version owner agent provisioning'})['data']
        assert self.calls[-1]['status']==200 and bound['id']==login_id and bound['tenantId']==a['id'] and bound['userId']==agent['id'] and bound['subjectType']=='AGENT' and bound['enabled'] and bound['normalizedAccount']==agent['account']
        assert int(self.scalar(f"select count(*) from backend_login where tenant_id={a['id']}"))==before_logins+1
        response=self.request('admin.localhost','/api/admin/auth/login','POST',{'account':agent['account'],'password':agent['password']})
        assert self.calls[-1]['status']==200 and response['user']['id']==agent['id'] and response['user']['tenantId']==a['id'] and response['user']['userType']=='agent';agent['token']=response['token'];self.persist()
        menus=self.admin(a,'/menus')['list'];users_menu=next(x['id'] for x in menus if x['menuCode']=='users')
        self.admin(a,f"/users/{agent['id']}/menus",'POST',{'menuIds':[users_menu],'actions':{}})
        query=self.admin(a,'/users/query','POST',{'page':1,'size':20},agent['token']);assert self.calls[-1]['status']==200 and query['total']==0
        self.admin(a,f"/users/{agent['id']}/menus",'POST',{'menuIds':[],'actions':{}})
        self.admin(a,'/users/query','POST',{'page':1,'size':20},agent['token'],allowed=(403,));assert self.calls[-1]['status']==403
        assert self.scalar(f"select count(*) from backend_login where tenant_id={a['id']} and id={login_id} and user_id={agent['id']} and subject_type='AGENT' and enabled=1")=='1'
        assert foreign()==b_before, 'Fresh A agent provisioning must not add or mutate B identities, sessions, backend logins or assets'
        self.fresh_agent_evidence.append({'tenantId':a['id'],'subjectId':agent['id'],'backendLoginId':login_id,'subjectAbsentBeforeRegistration':True,'registrationHttp':200,'newUserCount':1,'newZeroAssetAccounts':3,'ownerTypeTransitionHttp':200,'ownerBackendLoginCountBefore':before_logins,'ownerBackendLoginCountAfter':before_logins+1,'ownerBackendAccountBindingHttp':200,'sameNewBackendLoginIdBound':True,'accountPasswordLoginHttp':200,'authorizedRequestHttp':200,'sameTokenAfterRevocationHttp':403,'unrelatedBUnchanged':True,'backendLoginStillEnabledAfterPermissionRevocation':True})
        self.persist()
    def boundaries(self):
        a,b=self.state['tenants']['A'],self.state['tenants']['B'];user=a['user']
        self.front(b,f"/user/{user['id']}/info",token=user['token'],allowed=(401,403))
        self.front(a,f"/user/{b['user']['id']}/info",token=user['token'],allowed=(403,))
        for path in ['/api/market/currencies','/api/upload/image','/api/user/support/config']:
            self.request(b['host'],path,'GET',token=a['owner']['token'],allowed=(401,))
        self.request('unknown.localhost','/api/auth/login','POST',{'account':user['email'],'password':user['password']},allowed=(403,),direct=True)
        self.request(a['host'],'/api/user/support/config',headers={'X-Forwarded-Host':b['host']},allowed=(403,),direct=True)
        missing=self.admin(a,'/admins?id='+str(b['owner']['id']));assert missing['total']==0
        own=self.request(a['host'],f"/api/user/{user['id']}/info",token=user['token'],headers={'X-Tenant-Id':str(b['id'])},direct=True);assert own['id']==user['id']
    def private_files(self):
        def chunk(kind,data): return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data))
        image=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',1,1,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(b'\x00\x40\x80\xc0'))+chunk(b'IEND',b'')
        evidence=[]
        for label,foreign in [('A','B'),('B','A')]:
            t=self.state['tenants'][label];other=self.state['tenants'][foreign];boundary='stage1-'+secrets.token_hex(16)
            multipart=('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="stage1.png"\r\nContent-Type: image/png\r\n\r\n').encode()+image+('\r\n--'+boundary+'--\r\n').encode()
            result=self.request(t['host'],'/api/upload/image','POST',multipart,t['user']['token'],headers={'Content-Type':'multipart/form-data; boundary='+boundary})
            url=result['url'];prefix=f"/api/uploads/images/{t['id']}/user/{t['user']['id']}/"
            assert url.startswith(prefix) and re.fullmatch('[a-f0-9-]+[.]png',url[len(prefix):]), 'Server must derive file tenant and user ownership'
            path=(self.private/'uploads/images'/url[len('/api/uploads/images/'):]).resolve()
            assert self.private.resolve() in path.parents and path.is_file(), 'Uploads must land in the independent private run directory'
            delivered=self.request(t['host'],url,token=t['user']['token'],binary=True)
            assert delivered==path.read_bytes() and delivered.startswith(b'\x89PNG\r\n\x1a\n')
            assert {'private','no-store'}.issubset({part.strip() for part in self.calls[-1]['cacheControl'].split(',')}), 'Private file must not enter shared/browser caches'
            self.request(other['host'],url,token=other['user']['token'],allowed=(404,),binary=True)
            self.request(t['host'],url,allowed=(401,),binary=True)
            self.request(t['host'],url,token=other['user']['token'],allowed=(401,403),binary=True)
            self.request('admin.localhost',url,token=t['owner']['token'],allowed=(404,),binary=True)
            evidence.append({'tenantId':t['id'],'userId':t['user']['id'],'url':url,'privatePath':str(path),'sha256':hashlib.sha256(delivered).hexdigest(),'ownRead':200,'foreignTenantRead':404,'anonymousRead':401,'privateNoStore':True})
        assert evidence[0]['url']!=evidence[1]['url']
        self.state['privateFileEvidence']=evidence;self.persist()

    def readonly_and_control(self):
        a,b=self.state['tenants']['A'],self.state['tenants']['B']
        fingerprint=lambda:self.sql(f"select tenant_id,id,status,password_hash from user_account where tenant_id in ({a['id']},{b['id']}) order by tenant_id,id")+self.sql(f"select tenant_id,id,status from announcement where tenant_id in ({a['id']},{b['id']}) order by tenant_id,id")
        before=fingerprint()
        for path in ['/business/users','/business/wallets','/supervision/admins','/support/conversations']:
            self.c(f"/tenants/{a['id']}"+path)
        self.c(f"/tenants/{a['id']}/business/users",'POST',{},allowed=(403,405))
        assert before==fingerprint(),'Regulatory view may not mutate business state'
        counts=self.sql(f"select tenant_id,count(*) from admin_user where tenant_id in ({a['id']},{b['id']}) group by tenant_id")
        notifications=lambda: {'mail':len(list((self.private/'mail').glob('*.eml'))),'business':self.sql(f"select 'letter',tenant_id,count(*) from inbox_letter where tenant_id in ({a['id']},{b['id']}) group by tenant_id union all select 'message',tenant_id,count(*) from support_message where tenant_id in ({a['id']},{b['id']}) group by tenant_id order by 1,2")}
        notices_before=notifications()
        accessa=self.access(a);accessb=self.access(b);accessa2=self.access(a)
        assert notifications()==notices_before, 'Control entry must not send mail, inbox letters or support messages'
        originalb=self.admin(b,'/config/get?key=site.name')['value']
        self.config(a,'site.name','A control real write',accessa['token'])
        self.config(b,'system.timezone','UTC',accessb['token'])
        self.config(a,'system.timezone','UTC',accessa2['token'])
        assert self.admin(a,'/config/get?key=site.name')['value']=='A control real write'
        assert self.admin(b,'/config/get?key=site.name')['value']==originalb
        assert counts==self.sql(f"select tenant_id,count(*) from admin_user where tenant_id in ({a['id']},{b['id']}) group by tenant_id")
        self.admin(a,'/menus/current');self.admin(b,'/menus/current') # Enter never displaces normal login.
        assert int(self.scalar(f"select count(*) from control_audit_log where actor_id>0 and tenant_id={a['id']} and access_session_id='{accessa['accessSession']['id']}' and action='CONFIG_UPDATE'"))>0
        self.request('control.localhost','/api/control/tenants',token=accessa['token'],allowed=(401,403))
        for access in [accessa,accessb,accessa2]:
            self.c('/access-sessions/'+access['accessSession']['id']+'/revoke','POST')
            self.admin(a,'/config/list',token=access['token'],allowed=(401,))
    def tickets(self):
        a,b=self.state['tenants']['A'],self.state['tenants']['B'];binding=secrets.token_urlsafe(32)
        ticket=self.c(f"/tenants/{a['id']}/access-ticket",'POST',{'browserBinding':binding})
        body={'ticket':ticket['ticket'],'browserBinding':binding,'expectedTenantId':a['id']}
        for bad in [{**body,'browserBinding':secrets.token_urlsafe(32)},{**body,'expectedTenantId':b['id']}]:
            self.request('admin.localhost','/api/admin/auth/control-exchange','POST',bad,allowed=(401,403))
        consumed=self.request('admin.localhost','/api/admin/auth/control-exchange','POST',body)
        self.request('admin.localhost','/api/admin/auth/control-exchange','POST',body,allowed=(401,403))
        self.c('/access-sessions/'+consumed['accessSession']['id']+'/revoke','POST')
        # Only test-owned access-session timing metadata is adjusted; no business flags/data.
        for column,minutes in [('expires_at',1),('last_activity_at',16)]:
            aged=self.access(a);identifier=aged['accessSession']['id']
            assert re.fullmatch('[A-Za-z0-9_-]{1,64}',identifier)
            self.db.sql(f"update control_access_session set {column}=date_sub(utc_timestamp(6),interval {minutes} minute) where id='{identifier}' and tenant_id={a['id']}")
            self.admin(a,'/config/list',token=aged['token'],allowed=(401,))
            self.admin(a,'/menus/current')
            self.c('/access-sessions/'+identifier+'/revoke','POST')
        expiring=self.c(f"/tenants/{a['id']}/access-ticket",'POST',{'browserBinding':binding})
        time.sleep(max(0,expiring['expiresAt']/1000-time.time())+1)
        self.request('admin.localhost','/api/admin/auth/control-exchange','POST',{'ticket':expiring['ticket'],'browserBinding':binding,'expectedTenantId':a['id']},allowed=(401,403))
    def support_lifecycle(self,a):
        b=self.state['tenants']['B'];user=a['user'];marker='stage1-support-lifecycle-'+secrets.token_hex(10)
        conversation=self.front(a,'/user/support/sessions','POST',{},user['token']);identifier=conversation['id']
        sent=self.front(a,f'/user/support/sessions/{identifier}/messages','POST',{'requestId':secrets.token_hex(16),'text':marker},user['token'])
        assert sent['text']==marker
        before=self.sql(f"select id,text,hash from support_message where tenant_id={a['id']} and conversation_id={identifier} order by id")
        self.policy(a,'feature.support','false')
        self.front(a,'/user/support/sessions','POST',{},user['token'],allowed=(409,))
        detail=self.front(a,f'/user/support/sessions/{identifier}',token=user['token'])
        assert any(message['id']==sent['id'] and message['text']==marker for message in detail['messages'])
        assert before==self.sql(f"select id,text,hash from support_message where tenant_id={a['id']} and conversation_id={identifier} order by id")
        self.front(b,f'/user/support/sessions/{identifier}',token=b['user']['token'],allowed=(404,))
        self.front(b,f'/user/support/sessions/{identifier}/close','POST',{},b['user']['token'],allowed=(404,))
        self.front(a,f'/user/support/sessions/{identifier}/close','POST',{},user['token'])
        closed=self.front(a,f'/user/support/sessions/{identifier}',token=user['token']);assert closed['conversation']['status']=='CLOSED'
        assert self.scalar(f"select count(*) from support_conversation where tenant_id={a['id']} and id={identifier} and status='CLOSED' and active_user_id is null")=='1'
        after=self.sql(f"select id,text,hash from support_message where tenant_id={a['id']} and conversation_id={identifier} order by id")
        assert set(before).issubset(set(after)), 'Feature closure and required exit may not delete or alter existing history'
        self.policy(a,'feature.support','true')
        self.state['supportLifecycleEvidence']={'tenantId':a['id'],'conversationId':identifier,'messageId':sent['id'],'newStartRejected':409,'historyAndCloseAvailable':True,'crossTenantReadAndCloseRejected':404,'historyPreserved':True};self.persist()

    def locks_states(self):
        a=self.state['tenants']['A'];user=a['user']
        self.policy(a,'config.site.name','A locked name')
        self.admin(a,'/config/save','POST',{'key':'site.name','value':'forbidden owner change'},allowed=(403,))
        access=self.access(a)
        self.admin(a,'/config/save','POST',{'key':'site.name','value':'forbidden control business change'},access['token'],allowed=(403,))
        self.policy(a,'config.site.name','A locked name',False);self.config(a,'site.name','A unlocked actual name',access['token'])
        self.c('/access-sessions/'+access['accessSession']['id']+'/revoke','POST')
        self.support_lifecycle(a)
        attempt={'email':'blocked-'+secrets.token_hex(6)+'@stage1.example.test','password':secrets.token_urlsafe(24)}
        self.policy(a,'feature.registration','false');self.register(a,attempt,allowed=(403,))
        self.front(a,f"/user/{user['id']}/info",token=user['token']);self.login_user(a)
        self.policy(a,'feature.registration','true')
        self.c(f"/tenants/{a['id']}",'PUT',{'status':'STOP_NEW','reason':'local stop new case'})
        self.register(a,attempt,allowed=(403,));self.front(a,f"/user/{user['id']}/info",token=user['token'])
        self.c(f"/tenants/{a['id']}",'PUT',{'status':'DISABLED','reason':'local disable session case'})
        self.front(a,'/auth/login','POST',{'account':user['email'],'password':user['password']},allowed=(403,))
        self.request('admin.localhost','/api/admin/auth/login','POST',{'account':a['owner']['account'],'password':a['owner']['password']},allowed=(403,))
        self.front(a,f"/user/{user['id']}/info",token=user['token'],allowed=(401,403))
        self.admin(a,'/config/list',allowed=(401,403))
        access=self.access(a);self.config(a,'system.timezone','UTC',access['token'])
        self.c(f"/tenants/{a['id']}",'PUT',{'status':'ACTIVE','configReady':True,'reason':'restore after actual server validation'})
        self.c('/access-sessions/'+access['accessSession']['id']+'/revoke','POST')
        self.login_owner(a);self.login_user(a)
    def announcement_retention(self):
        a,b=self.state['tenants']['A'],self.state['tenants']['B'];user=a['user']
        created=self.admin(a,'/announcement/create','POST',{'title':'receipt retention '+secrets.token_hex(5),'content':'real local acceptance','status':'PUBLISHED','language':'en'})
        identifier=created['id'];key='ANNOUNCEMENT:'+str(identifier)
        self.front(a,'/user/support/unified-inbox/'+key+'/read','POST',{},user['token'])
        self.front(b,'/user/support/unified-inbox/'+key+'/read','POST',{},b['user']['token'],allowed=(404,))
        assert self.scalar(f"select count(*) from announcement_receipt where tenant_id={a['id']} and user_id={user['id']} and announcement_id={identifier}")=='1'
        self.admin(a,'/announcement/'+str(identifier),'DELETE')
        assert self.scalar(f"select status from announcement where tenant_id={a['id']} and id={identifier}")=='HIDDEN'
        assert self.scalar(f"select count(*) from announcement_receipt where tenant_id={a['id']} and announcement_id={identifier}")=='1'
        self.front(a,'/user/announcements/'+str(identifier),token=user['token'],allowed=(400,404))
    def domain_switch(self):
        a,b=self.state['tenants']['A'],self.state['tenants']['B'];before=self.scalar(f"select concat((select count(*) from user_account where tenant_id={a['id']}),':',(select count(*) from asset_account where tenant_id={a['id']}),':',(select count(*) from backend_login where tenant_id={a['id']}))")
        self.domain(a,'a2.localhost');assert before==self.scalar(f"select concat((select count(*) from user_account where tenant_id={a['id']}),':',(select count(*) from asset_account where tenant_id={a['id']}),':',(select count(*) from backend_login where tenant_id={a['id']}))")
        self.request('a.localhost','/api/auth/login','POST',{'account':a['user']['email'],'password':a['user']['password']},allowed=(403,))
        self.request('a.localhost','/api/auth/heartbeat','POST',{},a['user']['token'],allowed=(403,))
        self.login_user(a);self.login_owner(a);self.login_user(b)
        assert self.scalar(f"select count(*) from tenant_domain_binding where tenant_id={a['id']} and status='ACTIVE'")=='1'
    def browser_credentials(self):
        tenants=[]
        for label in ['default','A','B']:
            t=self.state['tenants'][label];self.login_owner(t);self.login_user(t)
            tenants.append({'id':t['id'],'host':t['host'],'label':label,'owner':{k:t['owner'][k] for k in ['account','password']},'user':{k:t['user'][k] for k in ['email','password']}})
        save(self.private/'browser-identities.json',{'control':{'account':self.ident['account'],'password':self.ident['password'],'totpSecret':self.ident['totpSecret']},'tenants':tenants})
    def run(self,focus=None):
        if focus=='fresh-agent':
            self.scope='focused-fresh-agent'
            self.check('真实 A 负责人账号密码登录身份',lambda:self.login_owner(self.state['tenants']['A']))
            self.check('本版全新 subject、负责人开通 AGENT 身份、登录授权及原token撤权',self.fresh_agent)
            save(self.directory/'exit.json',{'exitCode':0});print('Stage1 focused fresh-agent: 2 passed, 0 failed, 0 skipped; '+str(self.directory));return
        self.check('真实总控 MFA 身份',self.login_control)
        for label,code,host in [('default','default','default.localhost'),('A','stage1_a','a.localhost'),('B','stage1_b','b.localhost')]:
            self.check(label+' 安全模板、真实负责人、TLS域名、配置授权及开通',lambda l=label,c=code,h=host:self.setup(l,c,h))
        self.check('A B 同邮箱手机号独立注册、口令及用户隔离',self.users)
        self.check('真实 TLS SMTP 投递、跨租户验证码拒绝及本租户消费',self.email_delivery)
        self.check('受控本地短信CONFIG_TEST真实文件Redis审计、跨租户及重放拒绝',self.sms_sink)
        self.check('负责人创建员工客服代理、全局登录名、范围约束及实际撤权',self.identities_permissions)
        self.check('Host 令牌与共享入口隔离、未知Host及跨租户对象拒绝',self.boundaries)
        self.check('真实multipart私有文件落盘、用户归属与跨租户匿名拒绝',self.private_files)
        self.check('总控监管只读、真实总控业务写审计、多会话及撤销',self.readonly_and_control)
        self.check('一次性票据、浏览器绑定、错租户、重放及真实等待过期',self.tickets)
        self.check('配置锁定、授权关闭、历史退出路径及租户状态生效',self.locks_states)
        self.check('真实公告已读归属与软隐藏保留历史',self.announcement_retention)
        self.check('域名准备验证切换、原域名拒绝及租户数据身份不变',self.domain_switch)
        self.check('四端真实浏览器验收凭据准备',self.browser_credentials)
        save(self.directory/'exit.json',{'exitCode':0});print('Stage1 API acceptance: '+str(len(self.results))+' passed, 0 failed, 0 skipped; '+str(self.directory))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--run-dir',type=Path,required=True);parser.add_argument('--focus',choices=['fresh-agent'])
    args=parser.parse_args();home=args.run_dir.resolve()
    if ROOT/'reports' not in home.parents or not home.name.startswith('stage1-'):raise SystemExit('Explicit workspace stage1 run directory required')
    runner=Acceptance(home)
    try:runner.run(args.focus)
    except Exception as failure:
        runner.write_report();save(runner.directory/'exit.json',{'exitCode':1});print('FAIL '+runner.redact(str(failure))+'; evidence '+str(runner.directory),file=sys.stderr);sys.exit(1)
