"""K-line acceptance on an owned MySQL 5.7 container; never an existing business DB."""
import datetime, json, os, pathlib, re, secrets, shutil, socket, subprocess, sys, time

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
repo = pathlib.Path(__file__).resolve().parents[2]
qa = pathlib.Path(os.environ['KLINE_GAP_QA']).resolve()
backend = pathlib.Path(os.environ.get('KLINE_GAP_BACKEND', repo / 'exchange-backend'))
owner = secrets.token_hex(12)
password = secrets.token_hex(24)
name = 'kline-gap-test-' + owner
commands = []
def docker(*args, data=None):
    # Passwords only enter the child's environment, never report files or printed commands.
    command = ['docker', *args]
    env = dict(os.environ, MYSQL_ROOT_PASSWORD=password, MYSQL_PWD=password)
    result = subprocess.run(command, input=data, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    commands.append(dict(command=command, exitCode=result.returncode, at=datetime.datetime.now(datetime.timezone.utc).isoformat()))
    if result.returncode: raise RuntimeError(result.stderr.decode(errors='replace')[-1200:])
    return result.stdout.decode(errors='replace').strip()

with socket.socket() as free:
    free.bind(('127.0.0.1', 0)); port = free.getsockname()[1]
container = docker('run', '-d', '--name', name, '--label', 'kline.gap.owner=' + owner,
    '-p', f'127.0.0.1:{port}:3306', '-e', 'MYSQL_ROOT_PASSWORD', '--memory', '768m', 'mysql:5.7', '--innodb-use-native-aio=0')
try:
    def sql(statement):
        return docker('exec', '-i', '-e', 'MYSQL_PWD', container, 'mysql', '-h127.0.0.1', '-uroot', '--batch', '--skip-column-names', data=statement.encode('utf-8'))
    deadline = time.monotonic() + 100
    while True:
        try: sql('SELECT 1;'); break
        except RuntimeError:
            if time.monotonic() > deadline: raise
            time.sleep(1)
    schema = (repo / 'exchange-backend/src/test/resources/multitenant-market-test.sql').read_text(encoding='utf-8')
    schema = schema.replace('history_restore_revision BIGINT NOT NULL DEFAULT 0,', '')
    schema = re.sub(r'CREATE TABLE market_history_restore_(job|minute) \(.*?\);', '', schema, flags=re.S)
    migration = repo / 'exchange-backend/src/main/resources/db/migration'
    fences = (migration / 'V2026100304__market_engine_runtime.sql').read_text(encoding='utf-8').split('DELIMITER $$', 1)[1]
    restore = (migration / 'V2026100703__history_source_restore.sql').read_text(encoding='utf-8')
    s4 = (repo / 'exchange-backend/src/test/resources/s4-history-projection.sql').read_text(encoding='utf-8')
    gap_db, restore_db = 'kline_gap_' + owner, 'history_restore_' + owner
    for database, source, category, symbol in [(gap_db, 'binance', 'Crypto', 'BTCUSDT'), (restore_db, 'yahoo', 'Forex', 'JPY=X')]:
        sql(f"CREATE DATABASE {database}; USE {database}; CREATE TABLE tenant(id BIGINT PRIMARY KEY); INSERT INTO tenant VALUES(1),(2); CREATE TABLE tenant_schema_version(version BIGINT,applied_at DATETIME(6),minimum_application_epoch BIGINT,business_activation_ready INT);" + schema +
            f"ALTER TABLE trading_symbol ADD UNIQUE KEY tenant_symbol(tenant_id,id), ADD symbol VARCHAR(32) DEFAULT '{symbol}', ADD alltick_symbol VARCHAR(64), ADD market_source VARCHAR(16) DEFAULT '{source}', ADD source_category VARCHAR(32) DEFAULT '{category}', ADD row_version BIGINT DEFAULT 0, ADD random_market_enabled BOOLEAN DEFAULT FALSE, ADD random_market_started_at BIGINT, ADD control_enabled BOOLEAN DEFAULT FALSE; INSERT INTO trading_symbol(id,tenant_id) VALUES(1,1);")
        sql(f'USE {database};\nDELIMITER $$\n' + fences)
        sql(f'USE {database};\n' + restore)
        sql(f'USE {database};\n' + s4)
    env = dict(os.environ, KLINE_GAP_MYSQL_URL=f'jdbc:mysql://127.0.0.1:{port}/{gap_db}?useSSL=false&serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8', KLINE_GAP_MYSQL_PASSWORD=password,
        HISTORY_RESTORE_MYSQL_URL=f'jdbc:mysql://127.0.0.1:{port}/{restore_db}?useSSL=false&serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8', HISTORY_RESTORE_MYSQL_PASSWORD=password)
    # This ASCII cwd can be a junction to the authorized checkout, avoiding Windows protoc Unicode limitations.
    command = [os.environ.get('KLINE_GAP_MAVEN', 'C:/Environment/Maven/3.9.9/bin/mvn.cmd'), '-q', '-Dtest=KlineGapBackfillMysqlTest,HistoryRestoreMysqlTest', 'test']
    with (qa / 'mysql-maven.log').open('w', encoding='utf-8') as output:
        result = subprocess.run(command, cwd=backend, env=env, stdout=output, stderr=subprocess.STDOUT)
    commands.append(dict(command=command, cwd=str(backend), exitCode=result.returncode))
    report_dir = qa / 'mysql-junit'; report_dir.mkdir(exist_ok=True)
    for test in ['KlineGapBackfillMysqlTest', 'HistoryRestoreMysqlTest']:
        for report in (backend / 'target/surefire-reports').glob('*' + test + '*'): shutil.copy2(report, report_dir / report.name)
    tables = ['market_source_candle', 'market_engine_runtime', 'market_control_task', 'market_control_sample', 'market_control_hold', 'market_control_publication', 'market_history_restore_job', 'market_history_restore_minute']
    counts = {database: {table: int(sql(f'USE {database}; SELECT COUNT(*) FROM {table};')) for table in tables} for database in [gap_db, restore_db]}
    proof = dict(mysql=sql('SELECT VERSION();'), container=container, database=gap_db, restoreDatabase=restore_db, port=port,
        onlyOwnedLocalResources=True, junitExitCode=result.returncode, counts=counts)
    (qa / 'mysql-results.json').write_text(json.dumps(proof, indent=2), encoding='utf-8')
    if result.returncode: raise RuntimeError('MySQL acceptance failed; inspect mysql-maven.log and mysql-junit')
    print(json.dumps(proof))
finally:
    identity = json.loads(docker('inspect', container))[0]
    if identity['Id'] != container or identity['Config']['Labels'].get('kline.gap.owner') != owner: raise RuntimeError('Owned container identity changed; refuse cleanup')
    docker('rm', '-f', '-v', container)
    (qa / 'mysql-commands.json').write_text(json.dumps(commands, indent=2), encoding='utf-8')
    (qa / 'mysql-cleanup.json').write_text(json.dumps(dict(container=container, removed=True, labelChecked=True, removeAnonymousVolumes=True)), encoding='utf-8')
