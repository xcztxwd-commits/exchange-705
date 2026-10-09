"""One local command: gap repair, affected-module regressions and isolated MySQL/browser acceptance.

Requires the existing frontend dependencies, Maven, Node 24+, Chrome, Playwright and Docker.
Creates only owned localhost fixtures; never starts the business application or uses its database.
"""
import argparse
import datetime
import json
import os
import pathlib
import shutil
import socket
import stat
import subprocess
import sys
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET

REPO = pathlib.Path(__file__).resolve().parents[2]
BACKFILL = ['KlineGapBackfillTest', 'SourceHistoryGapRepairTest', 'HistorySourceRestoreTest', 'HistoryOrderingTest',
    'S2CanonicalKlineMergerTest', 'SourceHistoryDirtyRevisionJointTest', 'SourceHistoryProjectorJointTest',
    'SourceHistoryWindowTest', 'SimulationControlPathTest', 'SimulationControlTenantTest']
REGRESSION = ['AdminPermissionIntegrationTest', 'LatestSourceLookupTest', 'ManualOrderChartDatabaseTest',
    'MarketKlinePushTest', 'MarketPushTest', 'HomeSparklineCacheTest', 'DepthPushTest', 'TenantMarketIsolationTest',
    'KlineSchedulingTest', 'KlineRetentionRegressionTest#differentialFieldsOrderingNullsDuplicatesAndLimits+cachedTailDoesNotRetainParentList',
    'ExecutionQuoteTest', 'FundingQuoteAuthorityJointTest',
    'FundingConversionsJointTest', 'ControlFlowMarketIntegrationTest', 'SimpleManualOrderGenerationDatabaseTest',
    'ManualOrderCalculationTest', 'ManualOrderPricesLoadingTest', 'ManualOrderChartPageTest',
    'MoneyLockOrderRegressionTest', 'MoneyWriteCheckpointRegressionTest',
    'SourceCandlesBatchRegressionTest#identicalRowsDuplicatesTimestampUnitsAndConfirmationTime+emptyAndBatchBoundaries+failureAfterFirstChunkRollsBackEverything+concurrentIdenticalRetryKeepsOneRowPerKey']
FRONTEND_TESTS = {
    'exchange-pc': ['chartData', 'chartSync', 'chartWindow', 'chartPreferences', 'marketTransport', 'marketSimulation', 'quoteChange', 'controlRecovery'],
    'exchange-frontend': ['marketAccountMode', 'homeSparklineCache'],
    'exchange-admin': ['historyRestore', 'tenantSession', 'controlAccounts', 'controlPolicies'],
}

def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def save(qa, name, value):
    (qa / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')

def find_tool(variable, name, fallback):
    return os.environ.get(variable) or shutil.which(name) or (fallback if pathlib.Path(fallback).is_file() else None)

def main():
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    parser = argparse.ArgumentParser(description=__doc__)
    stamp = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).strftime('%Y%m%d-%H%M%S')
    parser.add_argument('--output', default=f'C:/workspace/fx/new/kline-local-qa-{stamp}-{uuid.uuid4().hex[:6]}', help='New ASCII report directory; never reuse old reports.')
    args = parser.parse_args()
    qa = pathlib.Path(args.output).absolute()
    if not qa.as_posix().isascii(): parser.error('--output must use an ASCII path for Windows protoc')
    qa.mkdir(parents=True, exist_ok=False)
    results, services, handles = [], [], []
    backend = REPO / 'exchange-backend'
    link = qa / 'backend'
    env = dict(os.environ)
    for key in list(env):
        if key.startswith(('KLINE_GAP_MYSQL_', 'HISTORY_RESTORE_MYSQL_', 'S2_FIXTURE_', 'MT705_TEST_REDIS_',
                           'SPRING_DATASOURCE_', 'SPRING_REDIS_', 'SPRING_CONFIG_', 'SPRING_PROFILES_')):
            env.pop(key)
    env.update(KLINE_GAP_QA=str(qa), HISTORY_RESTORE_QA=str(qa), HISTORY_GAP_ARTIFACT_DIR=str(qa))
    maven = find_tool('KLINE_GAP_MAVEN', 'mvn', 'C:/Environment/Maven/3.9.9/bin/mvn.cmd')
    node = find_tool('KLINE_GAP_NODE', 'node', 'C:/Environment/Node.js/24.18.0/node.exe')
    npm = find_tool('KLINE_GAP_NPM', 'npm', 'C:/Environment/Node.js/24.18.0/npm.cmd')
    docker = shutil.which('docker')
    playwright = pathlib.Path(env.get('PLAYWRIGHT_PATH', pathlib.Path.home() / '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'))
    env['PLAYWRIGHT_PATH'] = str(playwright)
    started = now()

    def run(name, command, cwd=REPO, check=None):
        result = dict(name=name, command=[str(arg) for arg in command], cwd=str(cwd), started=now())
        try:
            with (qa / (name + '.log')).open('w', encoding='utf-8') as output:
                process = subprocess.run(result['command'], cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT)
            result.update(exitCode=process.returncode, status='PASS' if process.returncode == 0 else 'FAIL')
            if check: result['evidence'] = check()
        except FileNotFoundError as failure:
            result.update(status='FAIL' if result.get('exitCode') is not None else 'NOT_RUN', reason=str(failure))
        except Exception as failure:
            result.update(status='FAIL', reason=str(failure))
        result['finished'] = now(); results.append(result)
        save(qa, name + '.command.json', result)
        print(f"[{result['status']}] {name}", flush=True)
        return result

    def unavailable(name, reason):
        record = dict(name=name, status='NOT_RUN', reason=reason, exitCode=None, started=now(), finished=now())
        results.append(record); save(qa, name + '.command.json', record)
        (qa / (name + '.log')).write_text(reason + '\n', encoding='utf-8')
        print(f'[NOT_RUN] {name}: {reason}', flush=True)

    def junit(names, directory, destination):
        destination.mkdir(exist_ok=True)
        checked = []
        for name in names:
            name = name.split('#')[0]
            files = list(directory.glob('TEST-*.' + name + '.xml'))
            if len(files) != 1: raise AssertionError('Missing current JUnit report: ' + name)
            xml = files[0]
            if xml.stat().st_mtime < command_start - 2: raise AssertionError('Stale JUnit report: ' + name)
            root = ET.parse(xml).getroot()
            count = {field: int(root.get(field)) for field in ['tests', 'failures', 'errors', 'skipped']}
            count['name'] = name
            shutil.copy2(xml, destination / xml.name)
            if count['tests'] < 1 or any(count[field] for field in ['failures', 'errors', 'skipped']):
                raise AssertionError('JUnit failure or skip: ' + str(count))
            checked.append(count)
        return dict(tests=sum(row['tests'] for row in checked), classes=checked, skipped=0)

    def mysql_proof():
        report = json.loads((qa / 'mysql-results.json').read_text(encoding='utf-8'))
        if not report['mysql'].startswith('5.7.'): raise AssertionError('Requires physical MySQL 5.7')
        evidence = junit(['KlineGapBackfillMysqlTest', 'HistoryRestoreMysqlTest'], qa / 'mysql-junit', qa / 'physical-junit')
        before = (qa / 'mysql-protected-before.json').read_bytes()
        if before != (qa / 'mysql-protected-after.json').read_bytes(): raise AssertionError('Protected facts or authority changed')
        fixed = json.loads(before)
        for field in ['protectedBars', 'publishedBars']:
            if set(fixed[field]) != {'1m','5m','15m','30m','1h','1w','1M'} or not all(fixed[field].values()):
                raise AssertionError('Missing protected OHLCV: ' + field)
        if not any(task['status'] == 'RUNNING' for task in fixed['facts']['market_control_task']):
            raise AssertionError('Control task was stopped for comparison')
        if not fixed['facts']['market_control_publication']: raise AssertionError('Empty publication comparison')
        evidence.update(mysql=report['mysql'], protectedBytesEqual=True)
        cleanup = json.loads((qa / 'mysql-cleanup.json').read_text(encoding='utf-8'))
        if not cleanup['removed'] or not cleanup['labelChecked']: raise AssertionError('Owned MySQL cleanup incomplete')
        return evidence

    def browser_report(name, count):
        rows = json.loads((qa / name).read_text(encoding='utf-8'))
        if len(rows) != count or any(row.get('errors') for row in rows): raise AssertionError('Incomplete browser acceptance: ' + name)
        return dict(cases=len(rows), report=name)

    def start_vite(app):
        root = REPO / app
        with socket.socket() as free:
            free.bind(('127.0.0.1', 0)); port = free.getsockname()[1]
        config = qa / (app + '-vite.mjs')
        config.write_text("import { createRequire } from 'node:module';\nconst require=createRequire(" + json.dumps((root / 'package.json').as_uri()) + ");\nconst vue=require('@vitejs/plugin-vue');\nexport default {root:" + json.dumps(root.as_posix()) +
            ",plugins:[(vue.default||vue)()],resolve:{dedupe:['vue','vue-router','pinia'],alias:{'@':" + json.dumps((root / 'src').as_posix()) + ",'@img':" + json.dumps((root / 'public/img').as_posix()) +
            "}},server:{host:'127.0.0.1',port:" + str(port) + ",strictPort:true,proxy:{'/api':{target:'http://127.0.0.1:1',ws:true},'/demo-api':{target:'http://127.0.0.1:1',ws:true},'/uploads':{target:'http://127.0.0.1:1'}}}};", encoding='utf-8')
        output = (qa / (app + '-vite.log')).open('w', encoding='utf-8'); handles.append(output)
        command = [node, str(root / 'node_modules/vite/bin/vite.js'), '--config', str(config)]
        process = subprocess.Popen(command, cwd=root, env=env, stdout=output, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        services.append(dict(app=app, port=port, pid=process.pid, command=command, process=process))
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            if process.poll() is not None: raise RuntimeError(app + ' Vite exited; inspect its log')
            try:
                with opener.open(f'http://127.0.0.1:{port}/index.html', timeout=1): return port
            except OSError: time.sleep(.2)
        raise RuntimeError(app + ' Vite startup timed out')

    print('本地隔离测试开始；报告：' + str(qa), flush=True)
    try:
        if os.name == 'nt':
            env['KLINE_LOCAL_JUNCTION'] = str(link); env['KLINE_LOCAL_BACKEND'] = str(backend)
            created = run('prepare-ascii-backend', ['powershell.exe', '-NoProfile', '-NonInteractive', '-Command',
                "New-Item -ItemType Junction -Path $env:KLINE_LOCAL_JUNCTION -Target $env:KLINE_LOCAL_BACKEND -ErrorAction Stop | Out-Null"])
            if created['status'] == 'PASS': backend = link
        env['KLINE_GAP_BACKEND'] = str(backend)
        if maven:
            env['KLINE_GAP_MAVEN'] = maven
            command_start = time.time()
            names = BACKFILL + REGRESSION
            run('backend-functional-regression-package', [maven, '-Dtest=' + ','.join(names), 'package'], backend,
                lambda: junit(names, REPO / 'exchange-backend/target/surefire-reports', qa / 'backend-junit'))
            command_start = time.time()
            if docker: run('mysql-isolated-acceptance', [sys.executable, REPO / 'scripts/market/test_kline_gap_mysql.py'], check=mysql_proof)
            else: unavailable('mysql-isolated-acceptance', 'Docker missing')
        else:
            unavailable('backend-functional-regression-package', 'Maven missing; set KLINE_GAP_MAVEN')
            unavailable('mysql-isolated-acceptance', 'Maven missing')
        for app, tests in FRONTEND_TESTS.items():
            installed = (REPO / app / 'node_modules/vite/bin/vite.js').is_file()
            if node and installed:
                run(app + '-unit-regression', [node, '--experimental-strip-types', '--test', *['tests/' + name + '.test.mjs' for name in tests]], REPO / app)
            else: unavailable(app + '-unit-regression', 'Node 24+ or frontend dependencies missing')
            if npm and installed: run(app + '-build', [npm, 'run', 'build'], REPO / app)
            else: unavailable(app + '-build', 'npm missing; dependencies must already be installed')
        browser_tests = [('client-backfill', 'exchange-pc/tests/klineBackfill.browser.cjs', 'client-backfill-results.json', 26),
            ('protected-history', 'exchange-pc/tests/historyRepair.browser.cjs', None, None),
            ('admin-safe-backfill', 'exchange-admin/tests/klineBackfill.browser.cjs', 'admin-backfill-results.json', 4),
            ('admin-existing-history-restore', 'exchange-admin/tests/historyRestore.browser.cjs', 'browser-results.json', 2)]
        if not node or not playwright.is_dir():
            for name, *_ in browser_tests: unavailable(name, 'Node or Playwright missing; set PLAYWRIGHT_PATH')
        else:
            try:
                pc = start_vite('exchange-pc'); mobile = start_vite('exchange-frontend'); admin = start_vite('exchange-admin')
                env.update(PC_QA_URL=f'http://127.0.0.1:{pc}/', MOBILE_QA_URL=f'http://127.0.0.1:{mobile}/#/trade?symbol=BTCUSDT&category=Crypto', ADMIN_QA_URL=f'http://127.0.0.1:{admin}')
                for name, file, report, count in browser_tests:
                    run(name, [node, REPO / file], check=(lambda report=report, count=count: browser_report(report, count)) if report else None)
            except Exception as failure:
                for name, *_ in browser_tests:
                    if not any(row['name'] == name for row in results): unavailable(name, str(failure))
    except BaseException as failure:
        results.append(dict(name='runner-interrupted', status='FAIL', reason=str(failure), started=now(), finished=now(), exitCode=None))
        raise
    finally:
        stopped = []
        for service in services:
            process = service.pop('process')
            if process.poll() is None:
                if os.name == 'nt': subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
                else: process.terminate()
                process.wait(timeout=15)
            stopped.append(service)
        for handle in handles: handle.close()
        closed_ports = []
        for service in stopped:
            with socket.socket() as connection:
                connection.settimeout(.3)
                closed = connection.connect_ex(('127.0.0.1', service['port'])) != 0
            if not closed: results.append(dict(name=service['app'] + '-cleanup', status='FAIL', reason='Owned port still listening', started=now(), finished=now(), exitCode=None))
            closed_ports.append(dict(port=service['port'], closed=closed))
        link_removed = False
        if os.name == 'nt' and (link.exists() or link.is_symlink()):
            expected = REPO / 'exchange-backend'
            if link.parent != qa or not link.lstat().st_file_attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT or not os.path.samefile(link, expected):
                raise RuntimeError('Unexpected junction identity; refuse cleanup')
            os.rmdir(link); link_removed = True
        save(qa, 'local-cleanup.json', dict(ownedServers=stopped, stopped=True, ports=closed_ports, asciiJunctionRemoved=link_removed))
        status = 'PASS' if results and all(row['status'] == 'PASS' for row in results) else 'FAIL' if any(row['status'] == 'FAIL' for row in results) else 'INCOMPLETE'
        save(qa, 'local-summary.json', dict(status=status, workspace=str(REPO), started=started, finished=now(), results=results,
            productionWrites=False, deployed=False, limitations=['浏览器 HTTP/WS 使用隔离夹具，数据库边界使用真实新建 MySQL 5.7。',
                '不执行旧 S2 固定身份 MySQL/Redis 全套和实盘供应商/生产结算；通过只证明列出的回归范围。',
                'SourceCandlesBatchRegressionTest 的 performance MySQL 专用方法未运行，未计通过。',
                'KlineRetentionRegressionTest 仅执行规范化/缓存保留回归，长时间 synthetic soak 不在本功能验收范围。']))
        lines = ['# 本地回填功能和模块回归', '', '最终结果：**' + status + '**。命令、退出码及日志均在此目录。', '',
            '覆盖：缺口检测/安全补采、控盘/发布/恢复/模拟、报价/资金权威、手工订单/资金写入、权限/租户、深度/首页图、PC/移动图表及后台操作。', '',
            '未启动业务应用或访问业务数据库；MySQL 为新建随机身份容器。通过结果不等于所有模块或生产端到端均已验证。', '',
            '| 检查 | 结果 | 日志 |', '|---|---|---|']
        lines += ['| ' + row['name'] + ' | ' + row['status'] + ' | [' + row['name'] + '.log](' + row['name'] + '.log) |' for row in results]
        (qa / 'README.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
        print('本地验收结果：' + status + '；详情：' + str(qa / 'README.md'), flush=True)
    return 0 if status == 'PASS' else 1

if __name__ == '__main__':
    sys.exit(main())
