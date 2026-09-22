"""One-time catalog reset. Dry-run by default; --execute stops the backend and creates a full backup first.
Deletes only trading orders, instrument definitions, instrument durations and market/control history.
Order reserves return to available balance; unexplained reserves and other business records remain.
"""
import argparse, datetime, json, pathlib, subprocess, tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
TABLES = ['contract_order','option_order','symbol_duration','market_control_publication','market_control_resume','market_control_hold','market_control_sample','market_control_task','market_mixed_minute','market_source_candle','market_source_event','market_source_quote','market_source_tick','trading_symbol']
COMPOSE = ['docker','compose','-f',str(ROOT/'compose.yaml')]
MYSQL = COMPOSE + ['exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -B -uroot "$MYSQL_DATABASE"']

def run(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, **kwargs)
def sql(query):
    return run(MYSQL, input=query.encode(), stdout=subprocess.PIPE).stdout.decode('utf-8')
def counts():
    return sql(' UNION ALL '.join("SELECT '%s' AS table_name, COUNT(*) AS row_count FROM %s" % (t,t) for t in TABLES)+';')

def transaction():
    # Duplicate key on guard is a hard SQL error. Batch mysql stops and connection closure rolls back.
    statements = ['START TRANSACTION;', 'CREATE TEMPORARY TABLE reset_guard (id INT PRIMARY KEY); INSERT INTO reset_guard VALUES (1);',
        'CREATE TEMPORARY TABLE reset_assets AS SELECT id, available+frozen AS total FROM asset_account;',
        "CREATE TEMPORARY TABLE reset_reserves AS SELECT user_id, 'CONTRACT' AS coin, SUM(COALESCE(margin,0)+COALESCE(fee,0)) AS amount FROM contract_order WHERE status IN ('OPEN','PENDING') GROUP BY user_id UNION ALL SELECT user_id, 'OPTION',SUM(amount) FROM option_order WHERE status='TRADING' GROUP BY user_id;",
        'INSERT INTO reset_guard SELECT 1 FROM reset_reserves r LEFT JOIN asset_account a ON a.user_id=r.user_id AND a.coin=r.coin WHERE a.id IS NULL OR a.frozen<r.amount OR r.amount<0 LIMIT 1;',
        'UPDATE asset_account a JOIN reset_reserves r ON a.user_id=r.user_id AND a.coin=r.coin SET a.available=a.available+r.amount,a.frozen=a.frozen-r.amount,a.row_version=a.row_version+1,a.updated_at=NOW();',
        'SELECT coin,SUM(amount) AS released_order_reserves FROM reset_reserves GROUP BY coin;']
    statements += ['DELETE FROM '+table+';' for table in TABLES]
    statements += ['INSERT INTO reset_guard SELECT 1 FROM asset_account a JOIN reset_assets b ON a.id=b.id WHERE a.available+a.frozen<>b.total LIMIT 1;']
    statements += ['INSERT INTO reset_guard SELECT 1 FROM '+table+' LIMIT 1;' for table in TABLES]
    statements += ['COMMIT;']
    return '\n'.join(statements)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--execute',action='store_true');args=parser.parse_args()
    if not args.execute: print(counts());raise SystemExit(0)
    backup=pathlib.Path(tempfile.gettempdir())/('705-before-catalog-reset-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'));backup.mkdir()
    run(COMPOSE+['stop','backend'])
    # On failure, leave the writer stopped for inspection. Never restart against a partially migrated schema.
    with (backup/'database.sql').open('wb') as output:
        run(COMPOSE+['exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --triggers "$MYSQL_DATABASE"'],stdout=output)
    if (backup/'database.sql').stat().st_size<1000: raise RuntimeError('Backup unexpectedly small')
    run(COMPOSE+['cp','backend:/app/app.jar',str(backup/'app.jar')])
    (backup/'before.tsv').write_text(counts(),encoding='utf-8')
    (backup/'reset.sql').write_text(transaction(),encoding='utf-8')
    (backup/'result.tsv').write_text(sql(transaction()),encoding='utf-8')
    # Exact namespace deletion; do not flush Redis accounts/sessions or unrelated data.
    run(COMPOSE+['exec','-T','redis','sh','-c','redis-cli --scan --pattern "market:*" | while IFS= read -r key; do redis-cli UNLINK "$key" >/dev/null; done'])
    (backup/'after.tsv').write_text(counts(),encoding='utf-8')
    print(json.dumps({'backup':str(backup),'before':(backup/'before.tsv').read_text(),'result':(backup/'result.tsv').read_text(),'after':(backup/'after.tsv').read_text()},ensure_ascii=False,indent=2))
