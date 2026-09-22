"""Exercise the reset transaction only in the disposable isolation MySQL container."""
import importlib.util, pathlib, subprocess
root=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('reset',root/'docker/reset-trading-catalog.py');reset=importlib.util.module_from_spec(spec);spec.loader.exec_module(reset)
base=['docker','compose','-f',str(root/'docker/compose.isolation.yaml'),'exec','-T','mysql-test','sh','-c','MYSQL_PWD=isolation-only mysql -B -uroot']
def sql(query,fail=False):
 r=subprocess.run(base,input=query.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 if fail: assert r.returncode!=0, 'guard must reject insufficient reserves'
 else: assert r.returncode==0,r.stderr.decode()
 return r.stdout.decode()
schema='DROP DATABASE IF EXISTS catalog_reset_test; CREATE DATABASE catalog_reset_test; USE catalog_reset_test;'
schema+='CREATE TABLE asset_account(id INT PRIMARY KEY,user_id INT,coin VARCHAR(32),available DECIMAL(32,16),frozen DECIMAL(32,16),row_version BIGINT,updated_at DATETIME);'
schema+='CREATE TABLE contract_order(id INT PRIMARY KEY,user_id INT,status VARCHAR(20),margin DECIMAL(32,16),fee DECIMAL(32,16));'
schema+='CREATE TABLE option_order(id INT PRIMARY KEY,user_id INT,status VARCHAR(20),amount DECIMAL(32,16));'
for table in reset.TABLES:
 if table not in ['contract_order','option_order']:schema+='CREATE TABLE '+table+'(id INT PRIMARY KEY); INSERT INTO '+table+' VALUES(1);'
schema+="INSERT INTO asset_account VALUES(1,1,'CONTRACT',900,1,0,NOW()),(2,1,'OPTION',80,20,0,NOW()),(3,1,'FUND',1000,8,0,NOW()); INSERT INTO contract_order VALUES(1,1,'OPEN',100,2); INSERT INTO option_order VALUES(1,1,'TRADING',20); CREATE TABLE deposit_record(id INT); INSERT INTO deposit_record VALUES(1);"
sql(schema)
sql('USE catalog_reset_test;'+reset.transaction(),fail=True)
assert sql('USE catalog_reset_test;SELECT COUNT(*) FROM contract_order;').strip().endswith('1')
assert sql('USE catalog_reset_test;SELECT available FROM asset_account WHERE id=1;').strip().endswith('900.0000000000000000')
sql('USE catalog_reset_test;UPDATE asset_account SET frozen=150 WHERE id=1;'+reset.transaction())
for table in reset.TABLES:assert sql('USE catalog_reset_test;SELECT COUNT(*) FROM '+table+';').strip().endswith('0'),table
assert '1002.0000000000000000\t48.0000000000000000' in sql('USE catalog_reset_test;SELECT available,frozen FROM asset_account WHERE id=1;')
assert '100.0000000000000000\t0.0000000000000000' in sql('USE catalog_reset_test;SELECT available,frozen FROM asset_account WHERE id=2;')
assert '1000.0000000000000000\t8.0000000000000000' in sql('USE catalog_reset_test;SELECT available,frozen FROM asset_account WHERE id=3;')
assert sql('USE catalog_reset_test;SELECT COUNT(*) FROM deposit_record;').strip().endswith('1')
print('PASS: physical deletion, atomic rollback, exact reserve release, per-account totals, unrelated funds/deposits preserved')
