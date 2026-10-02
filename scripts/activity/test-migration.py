"""Disposable MySQL 5.7 migration test. Never touches the shared application DB."""
import pathlib, subprocess, time, uuid

root=pathlib.Path(__file__).resolve().parents[2]
name='t02-migration-'+uuid.uuid4().hex
password=uuid.uuid4().hex

def run(args,**kwargs):
 result=subprocess.run(args,capture_output=True,**kwargs)
 if result.returncode:raise AssertionError((result.stderr or result.stdout).decode(errors='replace'))
 return result

run(['docker','run','-d','--name',name,'--network','none','--tmpfs','/var/lib/mysql',
     '-e','MYSQL_ROOT_PASSWORD='+password,'-e','MYSQL_DATABASE=activity_test','mysql:5.7',
     '--character-set-server=utf8mb4','--collation-server=utf8mb4_unicode_ci'])
try:
 def sql(statement,check=True):
  result=subprocess.run(['docker','exec','-i','-e','MYSQL_PWD='+password,name,'mysql','-uroot',
                         '--default-character-set=utf8mb4','-NB','activity_test'],
                        input=statement.encode('utf-8'),capture_output=True)
  if check and result.returncode:raise AssertionError(result.stderr.decode(errors='replace'))
  return result.stdout.decode('utf-8').strip()
 for _ in range(90):
  if sql('SELECT 1;',False)=='1':break
  time.sleep(2)
 else:raise AssertionError('MySQL 5.7 unavailable')
 sql("""CREATE TABLE activity_campaign(id BIGINT PRIMARY KEY,name VARCHAR(120),granted DECIMAL(32,16));
 CREATE TABLE trial_account(tenant_id BIGINT NOT NULL,user_id BIGINT NOT NULL,row_version BIGINT NOT NULL DEFAULT 0,
 available DECIMAL(32,16) NOT NULL,frozen DECIMAL(32,16) NOT NULL,granted DECIMAL(32,16) NOT NULL,
 consumed DECIMAL(32,16) NOT NULL,profits DECIMAL(32,16) NOT NULL,PRIMARY KEY(tenant_id,user_id));
 CREATE TABLE contract_order(id BIGINT PRIMARY KEY);CREATE TABLE option_order(id BIGINT PRIMARY KEY);
 INSERT INTO activity_campaign VALUES(1,'历史活动',300);
 INSERT INTO trial_account VALUES(1,7,0,250,50,300,0,0);""")
 directory=root/'exchange-backend/src/main/resources/db/migration'
 for filename in ['V2026092903__activity_delivery_settings.sql','V2026092904__activity_template_design.sql','V2026092906__activity_repeat_send.sql']:
  sql((directory/filename).read_text(encoding='utf-8'))
 sql("""CREATE TABLE activity_selection(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,
 row_version BIGINT NOT NULL DEFAULT 0,campaign_id BIGINT NOT NULL,operation_id VARCHAR(64) NOT NULL,
 filter_hash VARCHAR(64) NOT NULL,created_at DATETIME NOT NULL,selected_count BIGINT NOT NULL DEFAULT 0,
 sent_count BIGINT NOT NULL DEFAULT 0,duplicate_count BIGINT NOT NULL DEFAULT 0,
 ineligible_count BIGINT NOT NULL DEFAULT 0,cursor_id BIGINT NOT NULL DEFAULT 0,done BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE KEY uk_activity_selection_operation(tenant_id,campaign_id,operation_id));""")
 migration=(directory/'V2026093006__moddoc_T02_trial_activity.sql').read_text(encoding='utf-8')
 sql(migration)
 assert sql("SELECT name,granted,auto_send_enabled+0,allow_repeat_send+0,allow_repeat_claim+0,claim_validity_days IS NULL,positions,trigger_conditions FROM activity_campaign WHERE id=1")=='历史活动\t300.0000000000000000\t0\t0\t0\t1\t["AUTH_HOME"]\t[]'
 assert sql("SELECT available,frozen,active+0,request_key FROM trial_grant WHERE tenant_id=1 AND user_id=7")=='250.0000000000000000\t50.0000000000000000\t1\tLEGACY'
 assert sql("SELECT trial_eligible+0,expired,uncovered_loss FROM trial_account WHERE tenant_id=1 AND user_id=7")=='1\t0.0000000000000000\t0.0000000000000000'
 assert sql("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity_selection' AND COLUMN_NAME='send_operation_id'")=='1'
 sql("UPDATE activity_campaign SET layout_json='{\"version\":1,\"title\":\"模板🎁\"}' WHERE id=1;")
 sql(migration)
 assert sql("SELECT COUNT(*) FROM trial_grant WHERE tenant_id=1 AND user_id=7")=='1'
 assert '模板🎁' in sql('SELECT layout_json FROM activity_campaign WHERE id=1')
 assert sql("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='contract_order' AND COLUMN_NAME='funding_source'")=='1'
 assert sql("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='option_order' AND COLUMN_NAME='trial_allocations'")=='1'
 assert sql("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('activity_selection_member','activity_send_receipt')")=='2'
 print('PASS: isolated MySQL 5.7 T02 upgrade + rerun preserve legacy grant, balances, layout and selection metadata')
finally:
 run(['docker','rm','-f',name])
