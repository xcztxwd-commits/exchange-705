import pathlib, subprocess, time, uuid
root=pathlib.Path(__file__).resolve().parents[2]
name='activity-migration-'+uuid.uuid4().hex
password=uuid.uuid4().hex
subprocess.run(['docker','run','-d','--name',name,'--network','none','--tmpfs','/var/lib/mysql','-e','MYSQL_ROOT_PASSWORD='+password,'-e','MYSQL_DATABASE=activity_test','mysql:5.7','--character-set-server=utf8mb4','--collation-server=utf8mb4_unicode_ci'],check=True,capture_output=True)
try:
 def sql(s,check=True):
  r=subprocess.run(['docker','exec','-i','-e','MYSQL_PWD='+password,name,'mysql','-uroot','--default-character-set=utf8mb4','-NB','activity_test'],input=s.encode(),capture_output=True)
  if check and r.returncode: raise AssertionError(r.stderr.decode())
  return r
 for _ in range(90):
  if sql('SELECT 1;',False).returncode==0:break
  time.sleep(2)
 else:raise AssertionError('MySQL unavailable')
 sql("CREATE TABLE activity_campaign(id BIGINT PRIMARY KEY,name VARCHAR(120),granted DECIMAL(32,16)); INSERT INTO activity_campaign VALUES(1,'历史活动',300);")
 migrations=[root/'exchange-backend/src/main/resources/db/migration'/f for f in ['V2026092903__activity_delivery_settings.sql','V2026092904__activity_template_design.sql']]
 for _ in range(2):
  for f in migrations:sql(f.read_text(encoding='utf-8'))
 assert sql('SELECT id,name,granted,auto_send_enabled+0,repeat_unread+0,deleted+0,layout_json IS NULL FROM activity_campaign;').stdout.decode().strip()=='1\t历史活动\t300.0000000000000000\t0\t0\t0\t1'
 sql("UPDATE activity_campaign SET layout_json='{\"version\":1,\"title\":\"模板🎁\"}';")
 for f in migrations:sql(f.read_text(encoding='utf-8'))
 assert '模板🎁' in sql('SELECT layout_json FROM activity_campaign;').stdout.decode()
 print('PASS: MySQL 5.7 activity upgrade/rerun preserves historical amounts and template JSON; new automation defaults off.')
finally:subprocess.run(['docker','rm','-f',name],check=True,capture_output=True)