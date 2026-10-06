"""Apply the actual additive migration only to a new, task-owned disposable MySQL container.
No host database, existing container, business data, credentials file or release approval is used.
"""
import argparse, json, os, secrets, subprocess, time, uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
MIGRATIONS=ROOT/'exchange-backend/src/main/resources/db/migration'
MIGRATION='V2026100603__tenant_entry_frontend_roles.sql'
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--docker',action='store_true',required=True);parser.add_argument('--image',default='mysql:5.7');args=parser.parse_args()
    token=uuid.uuid4().hex;name='tenant-entry-migration-'+token[:12];env={**os.environ,'MYSQL_ROOT_PASSWORD':secrets.token_urlsafe(32)};env['MYSQL_PWD']=env['MYSQL_ROOT_PASSWORD']
    def run(cmd,input=None,check=True):
        p=subprocess.run(cmd,input=input,capture_output=True,env=env,timeout=120)
        if check and p.returncode:raise RuntimeError(p.stderr.decode('utf-8',errors='replace')[:1200])
        return p
    def sql(text,check=True):return run(['docker','exec','-i','-e','MYSQL_PWD',name,'mysql','-uroot','--batch','--skip-column-names','--default-character-set=utf8mb4','fixture'],text.encode(),check)
    def value(text):return sql(text).stdout.decode().strip()
    def rejected(text):
        p=sql(text,False);assert p.returncode!=0,'SQL constraint accepted invalid row';assert b'1062' in p.stderr,p.stderr.decode();return 1
    created=False
    try:
        run(['docker','run','-d','--name',name,'--label','com.gtcfesk.tenant-entry.test='+token,'--network','none','-e','MYSQL_ROOT_PASSWORD','-e','MYSQL_DATABASE=fixture',args.image,'--innodb-use-native-aio=0']);created=True
        for _ in range(100):
            if sql('SELECT 1;',False).returncode==0:break
            time.sleep(1)
        else:raise RuntimeError('Disposable MySQL did not become ready')
        version=value('SELECT VERSION();')
        sql((MIGRATIONS/'V2026092901__multitenant_control.sql').read_text(encoding='utf-8'))
        sql("UPDATE tenant SET frontend_host='existing.forex-exchange.cc',domain_verified=1,config_ready=1,status='ACTIVE',session_version=7 WHERE id=1; INSERT INTO tenant(id,code,name,frontend_host,status,created_at) VALUES(2,'closed','Closed','draft.forex-exchange.cc','DRAFT',UTC_TIMESTAMP(6)); INSERT INTO tenant_domain_history(tenant_id,hostname,retired_at) VALUES(1,'retired.forex-exchange.cc',UTC_TIMESTAMP(6));")
        sql((MIGRATIONS/'V2026093004__tenant_domain_candidates.sql').read_text(encoding='utf-8'))
        sql((MIGRATIONS/'V2026100601__control_policy_definitions.sql').read_text(encoding='utf-8'))
        # Apply the already-reviewed avatar predecessor; never skip its inactive receipt.
        sql("CREATE TABLE user_account(id BIGINT PRIMARY KEY); INSERT INTO user_account(id) VALUES(1);")
        sql((MIGRATIONS/'V2026100602__user_avatar.sql').read_text(encoding='utf-8'))
        assert value((ROOT/'scripts/tenant-domains/preflight.sql').read_text(encoding='utf-8'))=='2026100602\t2026100602','Preflight must show only the exact baseline epoch and no ownership/slot/root conflicts'
        before=value('SELECT id,frontend_host,domain_verified+0,config_ready+0,status,session_version FROM tenant ORDER BY id;')
        source=(MIGRATIONS/MIGRATION).read_text(encoding='utf-8');sql(source)
        assert before==value('SELECT id,frontend_host,domain_verified+0,config_ready+0,status,session_version FROM tenant ORDER BY id;')
        assert value('SELECT COUNT(*) FROM tenant WHERE entry_host IS NOT NULL OR entry_enabled<>0 OR entry_verified<>0 OR domain_version<>0;')=='0'
        assert value("SELECT COUNT(*) FROM tenant_domain_binding WHERE domain_role<>'FRONTEND';")=='0'
        assert value("SELECT COUNT(*) FROM tenant_domain_history WHERE domain_role<>'FRONTEND';")=='0'
        assert value('SELECT MAX(minimum_application_epoch) FROM tenant_schema_version;')=='2026100603'
        sql("INSERT INTO tenant_domain_binding(hostname,tenant_id,status,domain_role) VALUES('entrance.forex-exchange.net',1,'ACTIVE','ENTRY'),('newfront.forex-exchange.cc',1,'PENDING','FRONTEND'),('newentry.forex-exchange.net',1,'PENDING','ENTRY');")
        checks=0
        checks+=rejected("INSERT INTO tenant_domain_binding(hostname,tenant_id,status,domain_role) VALUES('another.forex-exchange.net',1,'ACTIVE','ENTRY');")
        checks+=rejected("INSERT INTO tenant_domain_binding(hostname,tenant_id,status,domain_role) VALUES('another.forex-exchange.cc',1,'ACTIVE','FRONTEND');")
        checks+=rejected("INSERT INTO tenant_domain_binding(hostname,tenant_id,status,domain_role) VALUES('secondpending.forex-exchange.net',1,'VERIFIED','ENTRY');")
        checks+=rejected("INSERT INTO tenant_domain_binding(hostname,tenant_id,status,domain_role) VALUES('secondpending.forex-exchange.cc',1,'PENDING','FRONTEND');")
        checks+=rejected("INSERT INTO tenant_domain_binding(hostname,tenant_id,status,domain_role) VALUES('retired.forex-exchange.cc',2,'PENDING','FRONTEND');")
        checks+=rejected("INSERT INTO tenant_domain_binding(hostname,tenant_id,status,domain_role) VALUES('newentry.forex-exchange.net',2,'PENDING','ENTRY');")
        sql("UPDATE tenant SET entry_host='entrance.forex-exchange.net' WHERE id=1;")
        checks+=rejected("UPDATE tenant SET entry_host='entrance.forex-exchange.net' WHERE id=2;")
        assert sql(source,False).returncode!=0,'Raw replay must not silently reapply DDL'
        assert value('SELECT COUNT(*) FROM tenant_schema_version WHERE version=2026100603;')=='1'
        assert value("SELECT tenant_id FROM tenant_domain_binding WHERE hostname='retired.forex-exchange.cc';")=='1'
        print(json.dumps({'result':'PASS','mysql':version,'migration':MIGRATION,'read_only_preflight_passed':True,'legacy_frontend_and_readiness_flags_preserved':True,'entry_default_closed_and_unconfigured':True,'dual_role_slots':True,'rejected_constraints':checks,'retired_owner_retained':True,'replay_rejected':True,'old_application_epoch_fenced':True,'target':'new disposable local container'},ensure_ascii=False))
    finally:
        if created:
            label=run(['docker','inspect','--format','{{index .Config.Labels "com.gtcfesk.tenant-entry.test"}}',name]).stdout.decode().strip()
            assert label==token,'Refuse to remove an unowned container'
            run(['docker','rm','-fv',name])
if __name__=='__main__':main()
