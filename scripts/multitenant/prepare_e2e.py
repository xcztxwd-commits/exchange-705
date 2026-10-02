"""Prepare synthetic localhost-only E2E fixtures. Never accepts a business database."""
import base64, datetime as dt, json, os, secrets, sys
from pathlib import Path
from urllib.parse import urlparse
import pymysql
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa

ROOT=Path(__file__).resolve().parents[2]
HOME=ROOT/'rollback/multitenant-20260929/schema/e2e'
c=json.loads((HOME/'connection.json').read_text())
u=urlparse(c['url'][5:])
if u.hostname!='127.0.0.1' or u.port!=64029 or not u.path.startswith('/mt705_probe_'):
    raise SystemExit('Only the dedicated synthetic MySQL fixture is accepted')
connection=pymysql.connect(host=u.hostname,port=u.port,user=c['username'],password=c['password'],database=c['database'],autocommit=True)
with connection.cursor() as sql:
    for tenant,host in [(1,'default.localhost'),(2,'a.localhost'),(3,'b.localhost')]:
        sql.execute('UPDATE tenant SET frontend_host=%s, domain_verified=1,config_ready=1,status=%s WHERE id=%s',(host,'ACTIVE',tenant))
    # This fixture bypasses public DNS verification only through test seed data, not application policy.
    sql.execute('UPDATE trading_symbol SET is_enabled=0,control_enabled=0')
connection.close()
state=HOME/'identities.json'
if state.exists():
    identity=json.loads(state.read_text())
else:
    identity={'account':'e2e_control','password':secrets.token_urlsafe(24),'totpSecret':base64.b32encode(os.urandom(20)).decode(),
              'mfaKey':base64.b64encode(os.urandom(32)).decode(),'dataKey':base64.b64encode(os.urandom(32)).decode(),'jwtKey':base64.b64encode(os.urandom(48)).decode()}
    state.write_text(json.dumps(identity),encoding='utf8')
props={
 'server.address':'127.0.0.1','server.port':64031,'spring.datasource.url':c['url'],'spring.datasource.username':c['username'],'spring.datasource.password':c['password'],
 'spring.jpa.hibernate.ddl-auto':'validate','spring.jpa.show-sql':'false','spring.jpa.open-in-view':'false',
 'spring.redis.host':'127.0.0.1','spring.redis.port':64030,'spring.redis.database':0,
 'platform.base-domain':'localhost','platform.admin-origin':'https://admin.localhost','platform.control-origin':'https://control.localhost',
 'platform.mfa-encryption-key':identity['mfaKey'],'tenant.secrets.key':identity['dataKey'],'jwt.secret':identity['jwtKey'],
 'platform.bootstrap.enabled':'true','platform.bootstrap.account':identity['account'],'platform.bootstrap.password':identity['password'],'platform.bootstrap.totp-secret':identity['totpSecret'],
 'market.exchange.stream-enabled':'false','market.yahoo.mode':'http_only','market.quote.yahoo-url':'http://127.0.0.1:64032/unavailable',
 'market.quote.alltick-url':'http://127.0.0.1:64032/unavailable','market.exchange.spot-url':'http://127.0.0.1:64032/unavailable',
 'market.exchange.futures-url':'http://127.0.0.1:64032/unavailable','market.exchange.okx-url':'http://127.0.0.1:64032/unavailable',
 'file.upload-dir':str(HOME/'uploads').replace('\\','/'),'control.retention.archive-directory':str(HOME/'archives').replace('\\','/')}
(HOME/'application.properties').write_text('\n'.join(str(k)+'='+str(v) for k,v in props.items()),encoding='utf8')
if not (HOME/'server.key').exists():
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'705 Synthetic E2E localhost only')])
    cert=(x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key()).serial_number(x509.random_serial_number())
          .not_valid_before(dt.datetime.now(dt.timezone.utc)-dt.timedelta(minutes=5)).not_valid_after(dt.datetime.now(dt.timezone.utc)+dt.timedelta(days=2))
          .add_extension(x509.SubjectAlternativeName([x509.DNSName(h) for h in ['control.localhost','admin.localhost','a.localhost','b.localhost','default.localhost']]),critical=False)
          .sign(key,hashes.SHA256()))
    (HOME/'server.key').write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    (HOME/'server.crt').write_bytes(cert.public_bytes(serialization.Encoding.PEM))
print('Prepared isolated E2E properties/certificate. Credentials remain in restricted directory; no OS trust or hosts changes.')
