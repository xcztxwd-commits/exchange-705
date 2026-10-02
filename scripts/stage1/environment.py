"""Create this run's disposable loopback-only services and current-schema fixture.

No existing container, database, Redis or uploaded files are reused. Credentials
remain in the run's restricted private directory, never in command output.
"""
import argparse, base64, datetime as dt, hashlib, json, os, secrets, subprocess, sys, time
from pathlib import Path
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/multitenant'))
import mysql_migration as migration

def save(path, value):
    temp = path.with_suffix(path.suffix + '.tmp')
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    temp.replace(path)

def prepare(home):
    private = home / 'private'
    migration.restrict_directory(private)
    state = private / 'identities.json'
    if state.exists():
        raise RuntimeError('Run already exists; use a fresh run directory, never overwrite evidence')
    # Only inventory Docker networks; retain every earlier run and fail if the pool is exhausted.
    import ipaddress
    network_ids = migration.run(['docker', 'network', 'ls', '--quiet']).stdout.decode().split()
    inventory = json.loads(migration.run(['docker', 'network', 'inspect'] + network_ids).stdout) if network_ids else []
    used = [ipaddress.ip_network(config['Subnet'], strict=False)
            for network in inventory for config in (network.get('IPAM', {}).get('Config') or []) if config.get('Subnet')]
    subnet = next((str(candidate) for candidate in ipaddress.ip_network('10.236.0.0/14').subnets(new_prefix=24)
                   if not any(other.version == 4 and candidate.overlaps(other) for other in used)), None)
    if subnet is None:
        raise RuntimeError('No unused dedicated Docker subnet; existing networks/resources remain unchanged')
    identity = {key: secrets.token_urlsafe(24) for key in ['password', 'smtpPassword']}
    identity.update(account='stage1_control', totpSecret=base64.b32encode(os.urandom(20)).decode(),
                    mfaKey=base64.b64encode(os.urandom(32)).decode(),
                    dataKey=base64.b64encode(os.urandom(32)).decode(),
                    jwtKey=base64.b64encode(os.urandom(48)).decode())
    save(state, identity)
    run = home.name.lower()
    containers = {'mysql': 'mt705-' + run + '-mysql', 'redis': 'mt705-' + run + '-redis', 'restore_mysql': 'mt705-' + run + '-restore-mysql',
                  'cache_mysql':'mt705-'+run+'-cache-mysql', 'cache_redis':'moddoc-t05-'+run+'-redis'}
    spec = {'run': run, 'mysqlPort': 33318, 'redisPort': 33630, 'backendPort': 33631,
            'containers': containers, 'database': 'mt705_' + run.replace('-', '_'), 'networkSubnet': subnet}
    save(home / 'environment.json', spec)
    env = private / 'compose.env'
    env.write_text('STAGE1_MYSQL_PASSWORD=' + secrets.token_hex(32) + '\n', encoding='utf-8')
    compose = {'name': 'mt705-' + run, 'services': {
        'mysql': {'image': 'mysql:5.7', 'container_name': containers['mysql'],
            'labels': {'com.gtcfesk.multitenant.test': 'true', 'com.gtcfesk.stage1.run': run},
            'environment': {'MYSQL_ROOT_PASSWORD': '${STAGE1_MYSQL_PASSWORD:?required}'},
            'command': ['--character-set-server=utf8mb4', '--collation-server=utf8mb4_unicode_ci',
                        '--sql-mode=STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION'],
            'ports': ['127.0.0.1:33318:3306'], 'volumes': ['mysql-data:/var/lib/mysql'], 'restart': 'no'},
        'restore_mysql': {'image': 'mysql:5.7', 'container_name': containers['restore_mysql'],
            'labels': {'com.gtcfesk.multitenant.test': 'true', 'com.gtcfesk.stage1.run': run},
            'environment': {'MYSQL_ROOT_PASSWORD': '${STAGE1_MYSQL_PASSWORD:?required}'},
            'command': ['--character-set-server=utf8mb4', '--collation-server=utf8mb4_unicode_ci'],
            'volumes': ['restore-data:/var/lib/mysql'], 'restart': 'no'},
        'redis': {'image': 'redis:6.2-alpine', 'container_name': containers['redis'],
            'labels': {'com.gtcfesk.stage1.run': run}, 'ports': ['127.0.0.1:33630:6379'],
            'command': ['redis-server', '--appendonly', 'yes'], 'volumes': ['redis-data:/data'], 'restart': 'no'}},
        'volumes': {'mysql-data': {}, 'redis-data': {}, 'restore-data': {}},
        'networks': {'default': {'ipam': {'config': [{'subnet': subnet}]}}}}
    compose['services']['cache_mysql']={'image':'mysql:5.7','container_name':containers['cache_mysql'],
        'labels':{'com.gtcfesk.stage1.run':run,'com.gtcfesk.multitenant.test':'true'},
        'environment':{'MYSQL_ROOT_PASSWORD':'${STAGE1_MYSQL_PASSWORD:?required}','MYSQL_DATABASE':'t05_sparkline'},
        'ports':['127.0.0.1:33405:3306'],'volumes':['cache-mysql-data:/var/lib/mysql'],'restart':'no'}
    compose['services']['cache_redis']={'image':'redis:6.2-alpine','container_name':containers['cache_redis'],
        'labels':{'com.gtcfesk.stage1.run':run,'moddoc.task':'T05'},'ports':['127.0.0.1:19405:6379'],
        'command':['redis-server','--appendonly','yes'],'volumes':['cache-redis-data:/data'],'restart':'no'}
    compose['volumes'].update({'cache-mysql-data':{},'cache-redis-data':{}})
    save(home / 'compose.json', compose)
    now = dt.datetime.now(dt.timezone.utc)
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, run + ' local fixture CA')])
    ca = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key())
          .serial_number(x509.random_serial_number()).not_valid_before(now-dt.timedelta(minutes=5))
          .not_valid_after(now+dt.timedelta(days=30)).add_extension(x509.BasicConstraints(ca=True, path_length=0), True)
          .add_extension(x509.KeyUsage(False,False,False,False,False,True,True,False,False), True)
          .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), False)
          .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(key.public_key()), False)
          .sign(key, hashes.SHA256()))
    host_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    names = ['control.localhost', 'admin.localhost', 'default.localhost', 'a.localhost', 'b.localhost', 'a2.localhost', 'smtp.localhost']
    cert = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'a.localhost')]))
            .issuer_name(subject).public_key(host_key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now-dt.timedelta(minutes=5)).not_valid_after(now+dt.timedelta(days=30))
            .add_extension(x509.SubjectAlternativeName([x509.DNSName(name) for name in names]), False)
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), True)
            .add_extension(x509.KeyUsage(True,False,True,False,False,False,False,False,False), True)
            .add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]), False)
            .add_extension(x509.SubjectKeyIdentifier.from_public_key(host_key.public_key()), False)
            .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(key.public_key()), False)
            .sign(key, hashes.SHA256()))
    (private/'ca.crt').write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    (private/'server.crt').write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    (private/'server.key').write_bytes(host_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    spki = host_key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    save(home/'browser-tls.json', {'spki': base64.b64encode(hashlib.sha256(spki).digest()).decode()})
    # A dedicated truststore, not a process-wide trust-all implementation or OS trust change.
    store_password = secrets.token_hex(16)
    store_env = os.environ.copy(); store_env['STAGE1_STORE_PASSWORD'] = store_password
    result = subprocess.run(['keytool', '-importcert', '-noprompt', '-alias', run, '-file', str(private/'ca.crt'),
                             '-keystore', str(private/'truststore.p12'), '-storetype', 'PKCS12', '-storepass:env', 'STAGE1_STORE_PASSWORD'],
                             env=store_env, capture_output=True)
    if result.returncode: raise RuntimeError('Dedicated truststore creation failed')
    identity['truststorePassword'] = store_password
    save(state, identity)
    start(home)

def start(home):
    spec = json.loads((home/'environment.json').read_text())
    result = subprocess.run(['docker', 'compose', '--env-file', str(home/'private/compose.env'), '-f', str(home/'compose.json'), 'up', '-d'], capture_output=True)
    attempt = sum(1 for _ in home.glob('environment-start-*.log')) + 1
    (home/('environment-start-'+str(attempt)+'.log')).write_bytes(result.stdout+result.stderr)
    if result.returncode: raise RuntimeError('Dedicated services startup failed; see environment-start.log')
    db = migration.Database(spec['containers']['mysql'], spec['database'])
    for _ in range(90):
        if db.sql('SELECT 1', database=False, check=False).returncode == 0: break
        time.sleep(1)
    else: raise RuntimeError('Dedicated MySQL not ready')
    print('New loopback MySQL5.7/Redis and dedicated TLS truststore ready')

def initialize(home):
    spec = json.loads((home/'environment.json').read_text())
    private = home/'private'
    attempt = sum(1 for _ in home.glob('initialization-*')) + 1
    evidence = home/('initialization-'+str(attempt)); evidence.mkdir()
    database = spec['database'].split('_retry_')[0] + '_retry_' + str(attempt)
    db = migration.Database(spec['containers']['mysql'], database)
    if not db.test: raise RuntimeError('Disposable test label required')
    db.create_empty()
    # Schema reference only. This run applies current workspace migrations, no frozen application code.
    db.sql((ROOT/'scripts/multitenant/legacy-schema.sql').read_text(encoding='utf-8-sig'))
    original_columns = {table:[row[0] for row in fields if row[0]!='current_token']
                        for table,fields in db.columns().items() if table not in migration.MANIFEST['control']}
    db.sql(migration.MIGRATIONS[0].read_text(encoding='utf-8'))
    migration.seed(db)
    before = migration.fingerprint(db, original_columns)
    save(evidence/'migration-before.json', before)
    backup = db.dump(private/('before-'+str(attempt)+'.sql'))
    restored = migration.Database(spec['containers']['restore_mysql'], database+'_restore')
    for _ in range(90):
        if restored.sql('SELECT 1', database=False, check=False).returncode == 0: break
        time.sleep(1)
    else: raise RuntimeError('Independent restore MySQL not ready')
    restored.create_empty(); restored.sql(Path(backup['path']).read_text(encoding='utf-8'))
    if migration.fingerprint(restored, before['columns']) != before: raise RuntimeError('Current backup restore mismatch before DDL')
    checksums = migration.apply(db)
    after = migration.fingerprint(db, before['columns']); save(evidence/'migration-after.json', after)
    if after != before: raise RuntimeError('Migration changed original synthetic business data')
    if db.query("SELECT order_no,source,account_type FROM deposit_record WHERE id=1") != ['LEGACY-DEP-1\tLEGACY_UNKNOWN\tFUND']:
        raise RuntimeError('New provenance fields not safely initialized')
    fields = 'SELECT @@server_uuid,@@port,@@datadir'
    uuid,port,datadir = db.query(fields)[0].split('\t')
    restore_uuid,restore_port,restore_datadir = restored.query(fields)[0].split('\t')
    if uuid==restore_uuid: raise RuntimeError('Restore must use an independent server')
    proof = {'result':'PASS', 'source':{'test_instance':True,'database':database,'server_uuid':uuid,'port':int(port),'datadir':datadir,
                  'container':db.container,'container_id':db.identity['container_id'],'run_id':spec['run']},
             'backup':backup, 'original_records_unchanged':True,
             'independent_restore_before_ddl':{'result':'PASS','fingerprint_equal':True,'target':{'server_uuid':restore_uuid,
                  'database':restored.database,'container':restored.container,'container_id':restored.identity['container_id'],'run_id':spec['run']}}}
    proof_path=evidence/'fixture-proof.json'; save(proof_path,proof)
    username = 'mt705_' + secrets.token_hex(4); password = secrets.token_hex(24)
    db.sql("CREATE USER '"+username+"'@'%' IDENTIFIED BY '"+password+"'; GRANT ALL PRIVILEGES ON "+migration.ident(db.database)+".* TO '"+username+"'@'%';")
    connection = {'url': 'jdbc:mysql://127.0.0.1:'+str(spec['mysqlPort'])+'/'+db.database+'?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=UTC',
                  'username': username, 'password': password, 'database': db.database, 'container': db.container,
                  'fixtureTransport':'docker','containerName':db.container,'containerId':db.identity['container_id'],
                  'serverUuid':uuid,'runId':spec['run'],'fixtureProof':str(proof_path),'fixtureProofSha256':migration.file_hash(proof_path)}
    save(private/'connection.json', connection)
    save(evidence/'migration-result.json', {'passed': True, 'target': db.identity, 'backup': backup,
        'restoreVerified': True, 'restoreDatabase': restored.database, 'originalRecordsUnchanged': True,
        'migrationChecksums': checksums, 'scopedTableCount': len(migration.MANIFEST['private'])})
    spec['database']=database; save(home/'environment.json',spec)
    identity = json.loads((private/'identities.json').read_text())
    https_port = spec.get('httpsPort',443)
    origin_suffix = '' if https_port == 443 else ':'+str(https_port)
    migration.restrict_directory(private/'sms-api')
    props = {
        'server.address': '127.0.0.1', 'server.port': spec['backendPort'],
        'spring.datasource.url': connection['url'], 'spring.datasource.username': username, 'spring.datasource.password': password,
        'spring.jpa.hibernate.ddl-auto': 'validate', 'spring.jpa.show-sql': 'false', 'spring.jpa.open-in-view': 'false',
        'spring.redis.host': '127.0.0.1', 'spring.redis.port': spec['redisPort'], 'spring.redis.database': 0,
        'platform.base-domain': 'localhost', 'platform.admin-origin': 'https://admin.localhost'+origin_suffix, 'platform.control-origin': 'https://control.localhost'+origin_suffix,
        'platform.mfa-encryption-key': identity['mfaKey'], 'tenant.secrets.key': identity['dataKey'], 'jwt.secret': identity['jwtKey'],
        'platform.bootstrap.enabled': 'true', 'platform.bootstrap.account': identity['account'],
        'platform.bootstrap.password': identity['password'], 'platform.bootstrap.totp-secret': identity['totpSecret'],
        'platform.outbound.local-loopback-enabled': 'true',
        'platform.outbound.local-routing-port': https_port,
        'platform.outbound.local-loopback-endpoints': ','.join(host+':'+str(https_port) for host in ['default.localhost','a.localhost','b.localhost','a2.localhost'])+',smtp.localhost:465',
        'platform.outbound.smtp-endpoints': 'smtp.localhost:465',
        'tenant.sms.local-sink-enabled': 'true', 'tenant.sms.sink-directory': str(private/'sms-api').replace('\\','/'),
        'market.exchange.stream-enabled': 'false', 'market.yahoo.mode': 'http_only',
        'file.upload-dir': str(private/'uploads').replace('\\','/'),
        'control.retention.archive-directory': str(private/'archives').replace('\\','/')}
    for name in ['market.quote.yahoo-url', 'market.quote.alltick-url', 'market.exchange.spot-url', 'market.exchange.futures-url', 'market.exchange.okx-url']:
        props[name] = 'http://127.0.0.1:33632/unavailable'
    (private/'application.properties').write_text('\n'.join(str(k)+'='+str(v) for k,v in props.items())+'\n', encoding='utf-8')
    print('Current migration + independent fresh database restore passed; no readiness flags changed')

def integration_fixture(home):
    spec=json.loads((home/'environment.json').read_text()); private=home/'private'
    if (private/'it-connection.json').exists(): raise RuntimeError('IT fixture already exists; keep original evidence')
    live=migration.Database(spec['containers']['mysql'],spec['database'])
    backup=live.dump(private/'it-source.sql')
    database='mt705_probe_stage1_it_'+secrets.token_hex(4)
    db=migration.Database(live.container,database);db.create_empty();db.sql(Path(backup['path']).read_text(encoding='utf-8'))
    db.sql("INSERT INTO tenant(id,code,name,status,created_at) VALUES(2,'stage1_it_a','Stage1 IT A','MAINTENANCE',UTC_TIMESTAMP(6)),(3,'stage1_it_b','Stage1 IT B','MAINTENANCE',UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id")
    before=migration.fingerprint(db)
    backup=db.dump(private/'it-before.sql')
    restored=migration.Database(spec['containers']['restore_mysql'],database+'_restore');restored.create_empty()
    restored.sql(Path(backup['path']).read_text(encoding='utf-8'))
    if migration.fingerprint(restored,before['columns'])!=before: raise RuntimeError('Independent current IT backup restore differs')
    fields='SELECT @@server_uuid,@@port,@@datadir'
    uuid,port,datadir=db.query(fields)[0].split('\t');restore_uuid=restored.query(fields)[0].split('\t')[0]
    proof={'result':'PASS','source':{'test_instance':True,'database':database,'server_uuid':uuid,'port':int(port),'datadir':datadir,
        'container':db.container,'container_id':db.identity['container_id'],'run_id':spec['run']},'backup':backup,
        'original_records_unchanged':True,'independent_restore_before_ddl':{'result':'PASS','fingerprint_equal':True,
            'target':{'server_uuid':restore_uuid,'database':restored.database,'container':restored.container,
                      'container_id':restored.identity['container_id'],'run_id':spec['run']}}}
    proof_path=home/'it-fixture-proof.json';save(proof_path,proof)
    username='mt705_'+secrets.token_hex(4);password=secrets.token_hex(24)
    db.sql("CREATE USER '"+username+"'@'%' IDENTIFIED BY '"+password+"'; GRANT ALL PRIVILEGES ON "+migration.ident(database)+".* TO '"+username+"'@'%';")
    save(private/'it-connection.json',{'url':'jdbc:mysql://127.0.0.1:'+str(spec['mysqlPort'])+'/'+database+'?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=UTC',
        'username':username,'password':password,'database':database,'container':db.container,'fixtureTransport':'docker',
        'containerName':db.container,'containerId':db.identity['container_id'],'serverUuid':uuid,'runId':spec['run'],
        'fixtureProof':str(proof_path),'fixtureProofSha256':migration.file_hash(proof_path)})
    print('Independent current-version IT clone and restore ready; no onboarding data is changed')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'start', 'initialize', 'integration-fixture']); parser.add_argument('--run-dir', type=Path, required=True)
    args = parser.parse_args(); home = args.run_dir.resolve()
    if ROOT/'reports' not in home.parents or not home.name.startswith('stage1-'): raise SystemExit('Stage1 run directory under this workspace required')
    home.mkdir(parents=True, exist_ok=True)
    {'prepare':prepare, 'start':start, 'initialize':initialize, 'integration-fixture':integration_fixture}[args.action](home)


