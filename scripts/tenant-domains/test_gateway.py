"""Exercise the checked-in Nginx config in an owned disposable container.
The upstream is a deterministic fixture, NOT production or an application/TLS acceptance proof.
Actual Java filter + database mapping is tested by TenantEntryRequestTest/TenantDomainLifecycleTest.
"""
import http.client, http.server, json, subprocess, tempfile, threading, time, uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
DENIED='{"success":false,"code":"TENANT_BOUNDARY_REJECTED","message":"入口或租户上下文无效"}'.encode()
def main():
    token=uuid.uuid4().hex;name='tenant-entry-gateway-'+token[:10];network=name+'-net';target={'enabled':True,'frontend':'different.forex-exchange.cc'};seen=[]
    class Upstream(http.server.BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def handle_request(self):
            host=self.headers.get('Host','');seen.append({'path':self.path,'method':self.command,'host':host,'forwarded':self.headers.get('X-Forwarded-Host'),'authorization':self.headers.get('Authorization')})
            status=403;body=DENIED;location=None
            if self.path=='/internal/tenant-gateway':
                assert self.headers.get('X-Tenant-Gateway')=='1';assert self.headers.get('X-Forwarded-Host')==host
                status=401 if host.endswith('.forex-exchange.net') else 204 if host in ['different.forex-exchange.cc','newfront.forex-exchange.cc'] else 403;body=b'' if status!=403 else DENIED
            elif self.path.startswith('/api/tenant-routing-check?challenge='):
                status=200;body=b'{"tenantId":17,"role":"ENTRY","challenge":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}'
            elif host=='entry.forex-exchange.net' and target['enabled'] and self.command in ['GET','HEAD'] and not self.headers.get('Upgrade') and self.path in ['/','/mobile/trade?symbol=EURUSD','/login']:
                status=302;location='https://'+target['frontend']+self.path;body=b''
            self.send_response(status);self.send_header('Cache-Control','no-store');self.send_header('Content-Type','application/json');
            if location:self.send_header('Location',location)
            self.end_headers()
            if self.command!='HEAD':self.wfile.write(body)
        do_GET=handle_request;do_HEAD=handle_request;do_POST=handle_request;do_OPTIONS=handle_request
    server=http.server.ThreadingHTTPServer(('0.0.0.0',0),Upstream);threading.Thread(target=server.serve_forever,daemon=True).start()
    def run(cmd,check=True):
        p=subprocess.run(cmd,capture_output=True,timeout=90)
        if check and p.returncode:raise RuntimeError(p.stderr.decode('utf-8',errors='replace')[:1400])
        return p
    created=False;net=False;checks=0
    try:
        with tempfile.TemporaryDirectory(prefix='entry-gateway-',dir=ROOT/'rollback') as folder:
            folder=Path(folder);html=folder/'html';html.mkdir();(html/'index.html').write_text('SPA_FIXTURE_DO_NOT_SERVE_ON_ENTRY',encoding='utf-8');(html/'file.js').write_text('asset fixture',encoding='utf-8')
            config=(ROOT/'docker/nginx.conf').read_text(encoding='utf-8').replace('backend:8080','backend:'+str(server.server_port));(folder/'nginx.conf').write_text(config,encoding='utf-8')
            run(['docker','network','create','--label','com.gtcfesk.tenant-entry.test='+token,network]);net=True
            run(['docker','run','-d','--name',name,'--label','com.gtcfesk.tenant-entry.test='+token,'--network',network,'--network-alias','mobile','--add-host','backend:host-gateway','-p','127.0.0.1::80','--mount','type=bind,src='+str(folder/'nginx.conf')+',dst=/etc/nginx/conf.d/default.conf,readonly','--mount','type=bind,src='+str(html)+',dst=/usr/share/nginx/html,readonly','nginx:1.27-alpine']);created=True
            run(['docker','exec',name,'nginx','-t']);port=int(run(['docker','port',name,'80/tcp']).stdout.decode().strip().split(':')[-1])
            def fetch(host,path='/',method='GET',headers=None):
                nonlocal checks
                c=http.client.HTTPConnection('127.0.0.1',port,timeout=8);c.request(method,path,headers={'Host':host,'Accept':'text/html',**(headers or {})});r=c.getresponse();body=r.read();result=(r.status,dict(r.getheaders()),body);c.close();checks+=1;return result
            def unavailable(host,path='/',method='GET'):
                status,headers,body=fetch(host,path,method);assert status==403,(status,path,body);assert 'no-store' in headers.get('Cache-Control','');assert 'Location' not in headers;assert b'SPA_FIXTURE' not in body;assert b'different.forex' not in body
            for method in ['GET','HEAD']:
                status,headers,body=fetch('entry.forex-exchange.net','/mobile/trade?symbol=EURUSD',method);assert status==302,(status,body);assert headers['Location']=='https://different.forex-exchange.cc/mobile/trade?symbol=EURUSD';assert 'no-store' in headers['Cache-Control'];assert b'SPA_FIXTURE' not in body
            target['frontend']='newfront.forex-exchange.cc';status,headers,_=fetch('entry.forex-exchange.net');assert status==302;assert headers['Location']=='https://newfront.forex-exchange.cc/'
            target['enabled']=False;unavailable('entry.forex-exchange.net');unavailable('unknown.forex-exchange.net');unavailable('unknown.forex-exchange.cc');unavailable('entry.forex-exchange.net','/file.js');unavailable('entry.forex-exchange.net','/mobile/login');target['enabled']=True
            for path in ['/api/auth/login','/api/admin/auth/login','/api/ws/market','/uploads/file.png','/payment/callback']:
                unavailable('entry.forex-exchange.net',path);unavailable('entry.forex-exchange.net',path,'POST')
            for path in ['/','/mobile/trade','/file.js']:
                status,headers,body=fetch('different.forex-exchange.cc',path);assert status==200,(status,path,body);assert b'SPA_FIXTURE' in body or b'asset fixture' in body
            status,headers,body=fetch('entry.forex-exchange.net','/api/tenant-routing-check?challenge='+'a'*32);assert status==200;assert 'no-store' in headers['Cache-Control'];assert 'Location' not in headers;assert b'challenge' in body
            status,headers,body=fetch('entry.forex-exchange.net','/',headers={'Upgrade':'websocket','Connection':'Upgrade'});assert status==403;assert 'Location' not in headers;assert 'no-store' in headers['Cache-Control'];assert b'SPA_FIXTURE' not in body
            unavailable('entry.forex-exchange.net','/_tenant_gateway')
            c=http.client.HTTPConnection('127.0.0.1',port,timeout=8);c.putrequest('GET','/',skip_host=True);c.putheader('Host','entry.forex-exchange.net');c.putheader('Host','different.forex-exchange.cc');c.endheaders();r=c.getresponse();body=r.read();headers=dict(r.getheaders());checks+=1;assert r.status==403;assert 'no-store' in headers['Cache-Control'];assert 'Location' not in headers;assert b'SPA_FIXTURE' not in body;c.close()
            assert all(s['host']==s['forwarded'] for s in seen if s['path']=='/internal/tenant-gateway')
            print(json.dumps({'result':'PASS','checks':checks,'nginx_config':'docker/nginx.conf','scope':'owned local Nginx + deterministic upstream fixture','entry_before_spa':True,'original_mobile_uri_preserved':True,'no_cached_redirect_or_rejection':True,'db_projection_change_without_nginx_reload':True,'frontend_unaffected':True,'challenge_direct':True,'production_or_TLS_verified':False}))
    finally:
        server.shutdown();server.server_close()
        if created:
            label=run(['docker','inspect','--format','{{index .Config.Labels "com.gtcfesk.tenant-entry.test"}}',name]).stdout.decode().strip();assert label==token;run(['docker','rm','-f',name])
        if net:
            label=run(['docker','network','inspect','--format','{{index .Labels "com.gtcfesk.tenant-entry.test"}}',network]).stdout.decode().strip();assert label==token;run(['docker','network','rm',network])
if __name__=='__main__':main()
