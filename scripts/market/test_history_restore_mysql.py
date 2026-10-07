"""Owned local MySQL 5.7 acceptance; never uses a production endpoint or database."""
import json, os, re, secrets, socket, subprocess, time
from pathlib import Path

root = Path(__file__).resolve().parents[2]
password = secrets.token_hex(24)
owner = secrets.token_hex(12)
name = 'history-restore-test-' + owner
def docker(*args, data=None):
    result = subprocess.run(['docker', *args], input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode: raise RuntimeError(result.stderr.decode(errors='replace')[-600:])
    return result.stdout.decode().strip()
with socket.socket() as free:
    free.bind(('127.0.0.1', 0)); port = free.getsockname()[1]
container = docker('run', '-d', '--name', name, '--label', 'history.restore.owner=' + owner,
                   '-p', f'127.0.0.1:{port}:3306', '-e', 'MYSQL_ROOT_PASSWORD=' + password,
                   '--memory', '768m', 'mysql:5.7', '--innodb-use-native-aio=0')
try:
    def sql(text):
        return docker('exec', '-i', '-e', 'MYSQL_PWD=' + password, container, 'mysql', '-h127.0.0.1', '-uroot', '--batch', '--skip-column-names', data=text.encode())
    deadline = time.monotonic() + 100
    while True:
        try: sql('SELECT 1;'); break
        except RuntimeError:
            if time.monotonic() > deadline: raise
            time.sleep(1)
    schema = (root / 'exchange-backend/src/test/resources/multitenant-market-test.sql').read_text(encoding='utf-8')
    schema = schema.replace('history_restore_revision BIGINT NOT NULL DEFAULT 0,', '')
    schema = re.sub(r'CREATE TABLE market_history_restore_(job|minute) \(.*?\);', '', schema, flags=re.S)
    prefix = "CREATE DATABASE history_restore; USE history_restore; CREATE TABLE tenant(id BIGINT PRIMARY KEY); INSERT INTO tenant VALUES(1),(2); CREATE TABLE tenant_schema_version(version BIGINT,applied_at DATETIME(6),minimum_application_epoch BIGINT,business_activation_ready INT);"
    sql(prefix + schema + "ALTER TABLE trading_symbol ADD UNIQUE KEY tenant_symbol(tenant_id,id), ADD symbol VARCHAR(32) DEFAULT 'JPY=X', ADD alltick_symbol VARCHAR(64), ADD market_source VARCHAR(16) DEFAULT 'yahoo', ADD source_category VARCHAR(32) DEFAULT 'Forex', ADD random_market_enabled BOOLEAN DEFAULT FALSE; INSERT INTO trading_symbol(id,tenant_id) VALUES(1,1);")
    migration = root / 'exchange-backend/src/main/resources/db/migration'
    fences = (migration / 'V2026100304__market_engine_runtime.sql').read_text(encoding='utf-8').split('DELIMITER $$', 1)[1]
    sql('USE history_restore;\nDELIMITER $$\n' + fences)
    sql('USE history_restore;\n' + (migration / 'V2026100703__history_source_restore.sql').read_text(encoding='utf-8'))
    # ControlHistoryStore validates projection metadata on physical MySQL even when this feature never uses it.
    sql('USE history_restore; CREATE TABLE s4_history_projection_progress(input_revision BIGINT);')
    env = dict(os.environ, HISTORY_RESTORE_MYSQL_URL=f'jdbc:mysql://127.0.0.1:{port}/history_restore?useSSL=false&serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8', HISTORY_RESTORE_MYSQL_PASSWORD=password)
    log = Path(os.environ.get('HISTORY_RESTORE_QA', 'C:/workspace/fx/new/history-restore-qa')) / 'mysql-test.log'
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open('w', encoding='utf-8') as output:
        result = subprocess.run(['C:/Environment/Maven/3.9.9/bin/mvn.cmd', '-q', '-Dtest=HistoryRestoreMysqlTest', 'test'], cwd=Path.cwd() / 'exchange-backend', env=env, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode: raise RuntimeError('Native MySQL check failed; inspect ' + str(log))
    print(json.dumps({'mysql': '5.7', 'migration': 2026100703, 'nativeWriter': True, 'immutableSnapshots': True, 'tenantForeignKeys': True, 'sourceInsertOnly': True}))
finally:
    identity = json.loads(docker('inspect', container))[0]
    if identity['Id'] != container or identity['Config']['Labels'].get('history.restore.owner') != owner: raise RuntimeError('Owned container identity changed; refuse cleanup')
    docker('rm', '-f', container)
