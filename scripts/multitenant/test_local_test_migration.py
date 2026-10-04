"""Offline local owner-mode guard checks. Docker, MySQL and source transport are mocked.

These checks prove Python guard behavior only, never native DDL/restore, production
approval, or application acceptance. Run against the actual local migration modules.
"""
import copy
import datetime as dt
import hashlib
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import controlled_migration as c
import local_test_migration as local

HOME=Path(tempfile.gettempdir()).resolve()
SOURCE='a'*64
MIGRATIONS=[{'name':'V2026100402__joint_s4_history_projection.sql','sha256':'b'*64},
            {'name':local.MIGRATION,'sha256':'c'*64}]

class FakeDb:
    test=True
    def __init__(self,identity):
        self.identity=identity;self.database=identity['database'];self.read_only='1';self.sessions='0'
        self.ready='0';self.minimum_epoch='2026100402';self.epoch='2026100402';self.new_receipts='0'
        self.queue='0';self.archive_queue='0';self.live_leases='0';self.sql_seen=[];self.count_state=0;self.transactions='0';self.exists='1'
    def sql(self,sql,database=True,check=True):
        self.sql_seen.append((sql,database))
        if sql.startswith('SELECT @@global.read_only; '):
            if database is not False:raise AssertionError('Drain checks must use unbound transport')
            values=[self.read_only,self.sessions,self.transactions]
            return SimpleNamespace(stdout=('\n'.join(values)+'\n').encode(),returncode=0)
        if sql.startswith('SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='):
            if database is not False:raise AssertionError('Existence checks must not connect nonexistent schemas')
            return SimpleNamespace(stdout=(self.exists+'\n').encode(),returncode=0)
        if sql=='OFFLINE-TOY-RESTORE-NOT-SQL':return SimpleNamespace(stdout=b'',returncode=0)
        raise AssertionError('Unexpected offline SQL transport: '+sql)
    def query(self,sql):
        self.sql_seen.append(sql)
        if sql=='SELECT @@global.read_only':return [self.read_only]
        if 'information_schema.PROCESSLIST' in sql:return [self.sessions]
        if 'COUNT(*)' in sql and 'tenant_schema_version' in sql and 'version=2026100403' in sql:return [self.new_receipts]
        if 'MAX(version)' in sql and 'tenant_schema_version' in sql:
            values=[self.epoch]
            if 'minimum_application_epoch' in sql:values.append(self.minimum_epoch)
            values.append(self.ready)
            return ['\t'.join(values)]
        if 'tenant_schema_version' in sql and 'business_activation_ready' in sql:return [self.ready]
        if 'market_engine_runtime' in sql:return [self.live_leases]
        if 'control_chat_archive_job' in sql:return [self.archive_queue]
        if 'COUNT(*)' in sql:return [self.queue]
        raise AssertionError('Unexpected offline SQL query: '+sql)

class LocalModeReviewTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='review-unit-',dir=HOME)
        self.root=Path(self.tmp.name).resolve();self.assertTrue(self.root.is_relative_to(HOME.resolve()))
        self.items={};self.volumes={}
        self.source=self.target('1'*64,'11111111-1111-1111-1111-111111111111','local-source','fixture-source',
            'mt705_probe_joint_local403_'+'a'*16,True)
        self.restore=self.target('2'*64,'22222222-2222-2222-2222-222222222222','local-restore','fixture-restore',
            self.source['database']+'_prerestore',False)
        self.db=FakeDb(self.source);self.other=FakeDb(self.restore)
        self.authorization={'environment':local.ENVIRONMENT,'scope':'LOCAL_SCHEMA_TEST',
            'owner_authorization':'当前项目所有者的明确授权即视为本次测试执行授权',
            'expires_at':(c.now()+dt.timedelta(minutes=5)).isoformat(),
            'source_sha256':SOURCE,'migrations':copy.deepcopy(MIGRATIONS),
            'source':copy.deepcopy(self.source),'restore':copy.deepcopy(self.restore),
            'owner':'review-owner','run':'review-run'}
        self.path=self.root/'owner.json';self.write_auth()
        self.patchers=[patch.object(c.core,'EPOCH',2026100403),patch.object(c,'sources',return_value=SOURCE),
            patch.object(c,'migrations',return_value=copy.deepcopy(MIGRATIONS)),
            patch.object(c,'target',side_effect=lambda db:copy.deepcopy(db.identity)),
            patch.object(c.core,'run',side_effect=self.transport)]
        for p in self.patchers:p.start()
    def tearDown(self):
        for p in reversed(self.patchers):p.stop()
        self.assertTrue(self.root.is_relative_to(HOME.resolve()));self.tmp.cleanup()
    def target(self,identifier,uuid,name,volume,database,published):
        mount={'Type':'volume','Name':volume,'Source':'/var/lib/docker/volumes/'+volume+'/_data',
               'Destination':'/var/lib/mysql','Driver':'local','RW':True}
        item={'Id':identifier,'Name':'/'+name,'Image':'sha256:fixture-image',
              'Mounts':[mount],'State':{'Running':True},
              'Config':{'Labels':{'com.gtcfesk.multitenant.test':'true','com.gtcfesk.joint.owner':'review-owner',
                                 'com.gtcfesk.stage1.run':'review-run'}},
              'HostConfig':{'Memory':1073741824,'MemorySwap':1073741824,'NanoCpus':1000000000,'PidsLimit':128},
              'NetworkSettings':{'Ports':{'3306/tcp':[{'HostIp':'127.0.0.1','HostPort':'33318'}]} if published else {}}}
        self.items[identifier]=item
        self.volumes[volume]={'Name':volume,'Driver':'local','Scope':'local','Options':None,'Mountpoint':mount['Source']}
        return {'physical':{'container_id':identifier,'container':name,'image_id':item['Image'],
                            'database':database,'test_instance':True,'mounts':copy.deepcopy(item['Mounts'])},
                'server_uuid':uuid,'datadir':'/var/lib/mysql/','port':3306,'version':'5.7.44','database':database}
    def transport(self,args,*unused,**kwargs):
        if args[:2]==['docker','inspect'] and len(args)==3:value=[self.items[args[2]]]
        elif args[:3]==['docker','volume','inspect'] and len(args)==4:value=[self.volumes[args[3]]]
        else:raise AssertionError('No actual transport is permitted: '+repr(args))
        return SimpleNamespace(stdout=json.dumps(value).encode(),stderr=b'',returncode=0)
    def write_auth(self):self.path.write_text(json.dumps(self.authorization,ensure_ascii=False),encoding='utf-8')
    def policy(self):return local.LocalPolicy(self.path,self.db,self.other)
    def binding(self):
        return {'source_sha256':SOURCE,'target_sha256':c.digest(self.source),
                'plan_sha256':'d'*64,'restore_proof_sha256':'e'*64,'backup_sha256':'f'*64}
    def test_local_authorization_valid_on_independent_volumes_even_when_datadir_text_matches(self):
        self.assertEqual(self.source['datadir'],self.restore['datadir'])
        policy=self.policy();policy.verify(policy.value,self.binding(),'isolated-fixture')
        self.assertTrue(local.independent_datadir(self.source,self.restore))
    def test_policy_does_not_have_a_signing_key_interface(self):self.assertFalse(hasattr(self.policy(),'key'))
    def test_missing_authorization_path_is_not_a_default_policy(self):
        with self.assertRaises(ValueError):local.LocalPolicy(self.root/'missing.json',self.db,self.other)
    def test_source_change_invalidates_existing_policy(self):
        policy=self.policy()
        with patch.object(c,'sources',return_value='0'*64),self.assertRaises(ValueError):policy.guard_targets(self.db,self.other)
    def test_migration_change_invalidates_existing_policy(self):
        policy=self.policy();changed=copy.deepcopy(MIGRATIONS);changed[-1]['sha256']='0'*64
        with patch.object(c,'migrations',return_value=changed),self.assertRaises(ValueError):policy.guard_targets(self.db,self.other)
    def test_authorization_file_changed_after_policy_load_is_rejected(self):
        policy=self.policy();self.authorization['expires_at']=(c.now()+dt.timedelta(hours=1)).isoformat();self.write_auth()
        with self.assertRaises(ValueError):policy.guard_targets(self.db,self.other)
    def test_business_scope_cannot_use_owner_test_authorization(self):
        policy=self.policy()
        with self.assertRaises(ValueError):policy.verify(policy.value,self.binding(),'business')
    def test_policy_refuses_foreign_binding_fields(self):
        policy=self.policy()
        for field in self.binding():
            with self.subTest(field=field):
                value=self.binding();value[field]='bad'
                with self.assertRaises(ValueError):policy.verify(policy.value,value,'isolated-fixture')
    def test_unsigned_ledger_detects_missing_or_reordered_record(self):
        ledger=local.LocalLedger(self.root/'ledger',self.policy())
        for kind in ['BEGIN','INTENT','PHASE_COMPLETE']:ledger.append({'kind':kind})
        self.assertEqual('PHASE_COMPLETE',ledger.latest()['kind'])
        (self.root/'ledger/000001.json').unlink()
        with self.assertRaises(ValueError):ledger.rows()
    def test_unsigned_ledger_detects_body_tamper(self):
        ledger=local.LocalLedger(self.root/'ledger',self.policy());ledger.append({'kind':'INTENT'})
        path=self.root/'ledger/000000.json';value=json.loads(path.read_text());value['body']['kind']='COMPLETE'
        path.write_text(json.dumps(value))
        with self.assertRaises(ValueError):ledger.latest()
    def test_unsigned_ledger_refuses_hmac_format_and_reserved_field_override(self):
        ledger=local.LocalLedger(self.root/'ledger',self.policy())
        for key in ['sequence','previous','at','journal_kind']:
            with self.subTest(key=key),self.assertRaises(ValueError):ledger.append({'kind':'BEGIN',key:None})
        c.publish(self.root/'ledger/000000.json',{'body':{'sequence':0,'previous':'0'*64,'kind':'BEGIN'},'sha256':'a'*64})
        with self.assertRaises(ValueError):ledger.rows()
    def test_local_ledger_without_explicit_policy_is_rejected(self):
        with self.assertRaises(ValueError):local.LocalLedger(self.root/'ledger',object())
    def baseline(self,alter_state=False):
        policy=self.policy();ledger=local.LocalLedger(self.root/'ledger',policy)
        backup=self.root/'imported0402.sql';backup.write_bytes(b'OFFLINE-TOY-SNAPSHOT-NOT-MYSQL-DUMP')
        imported={'path':str(backup),'sha256':hashlib.sha256(backup.read_bytes()).hexdigest()}
        state={'schema':{'objects':{'table:toy':'a'*64},'sha256':'b'*64},'data':{'columns':{},'tables':{}}}
        def actual(db):return {'changed':True} if alter_state and db is self.other else copy.deepcopy(state)
        with patch.object(c,'state',side_effect=actual):
            return local.observe_local_baseline(self.db,self.other,state,imported,ledger),ledger
    def test_baseline_is_observation_never_past_complete(self):
        value,ledger=self.baseline();self.assertEqual('LOCAL_BASELINE_OBSERVED',value['kind'])
        self.assertEqual('LOCAL_BASELINE_OBSERVED',ledger.latest()['kind']);self.assertFalse(value['activation_ready'])
    def test_changed_restored_state_cannot_become_local_baseline(self):
        with self.assertRaises(ValueError):self.baseline(alter_state=True)
    def test_active_metadata_cannot_become_local_baseline(self):
        self.db.ready='1'
        with self.assertRaises(ValueError):self.baseline()
    def test_existing0403_cannot_be_inferred_as_baseline(self):
        self.db.new_receipts='1'
        with self.assertRaises(ValueError):self.baseline()
    def test_wrong_minimum_application_epoch_cannot_become_baseline(self):
        self.db.minimum_epoch='2026100403'
        with self.assertRaises(ValueError):self.baseline()
    def test_local_policy_refuses_global_read_write_source(self):
        self.db.read_only='0'
        with self.assertRaises(ValueError):self.policy()
    def test_local_policy_refuses_undrained_sessions(self):
        self.db.sessions='1'
        with self.assertRaises(ValueError):self.policy()
    def test_local_policy_refuses_activated_schema(self):
        self.db.ready='1'
        with self.assertRaises(ValueError):self.policy()
    def test_local_policy_refuses_ready_business_queue(self):
        self.db.queue='1'
        with self.assertRaises(ValueError):self.policy()
    def test_local_policy_refuses_live_market_lease(self):
        self.db.live_leases='1'
        with self.assertRaises(ValueError):self.policy()
    def test_local_policy_refuses_active_innodb_transaction(self):
        self.db.transactions='1'
        with self.assertRaises(ValueError):self.policy()
    def test_local_policy_refuses_global_read_write_restore_server(self):
        self.other.read_only='0'
        with self.assertRaises(ValueError):self.policy()
    def test_local_policy_refuses_undrained_restore_sessions(self):
        self.other.sessions='1'
        with self.assertRaises(ValueError):self.policy()
    def test_local_policy_refuses_active_restore_transaction(self):
        self.other.transactions='1'
        with self.assertRaises(ValueError):self.policy()
    def test_local_policy_refuses_activated_restore_schema(self):
        self.other.ready='1'
        with self.assertRaises(ValueError):self.policy()
    def test_new_restore_namespace_is_checked_unbound_without_database_metadata_connection(self):
        self.other.exists='0';self.other.ready='1';self.other.epoch='0'
        self.policy()
        self.assertTrue(self.other.sql_seen)
        self.assertTrue(all(isinstance(row,tuple) and row[1] is False for row in self.other.sql_seen))
    def test_local_policy_refuses_queued_archive_work(self):
        self.db.archive_queue='1'
        with self.assertRaises(ValueError):self.policy()

def authorization_case(name,mutate):
    def check(self):
        mutate(self);self.write_auth()
        with self.assertRaises((ValueError,KeyError,TypeError)):self.policy()
    check.__name__='test_reject_'+name
    setattr(LocalModeReviewTests,check.__name__,check)

for name,key in [('fabricated_signatures','signatures'),('fabricated_keys','keys'),('fabricated_journal_key','journal_key'),('signed_payload','payload')]:
    authorization_case(name,lambda self,key=key:self.authorization.update({key:[]}))
authorization_case('wrong_environment',lambda self:self.authorization.update(environment='PRODUCTION'))
authorization_case('wrong_scope',lambda self:self.authorization.update(scope='business'))
authorization_case('missing_owner_grant',lambda self:self.authorization.pop('owner_authorization'))
authorization_case('expired_grant',lambda self:self.authorization.update(expires_at=(c.now()-dt.timedelta(seconds=1)).isoformat()))
authorization_case('naive_expiry',lambda self:self.authorization.update(expires_at=dt.datetime.now().isoformat()))
authorization_case('wildcard_source_database',lambda self:self.authorization['source'].update(database='*'))
authorization_case('non_fixture_business_db',lambda self:setattr(self.db,'test',False))
authorization_case('unlabelled_source',lambda self:self.items['1'*64]['Config']['Labels'].pop('com.gtcfesk.multitenant.test'))
authorization_case('foreign_owner_label',lambda self:self.items['1'*64]['Config']['Labels'].update({'com.gtcfesk.joint.owner':'other-owner'}))
authorization_case('foreign_run_label',lambda self:self.items['1'*64]['Config']['Labels'].update({'com.gtcfesk.stage1.run':'other-run'}))
authorization_case('non_loopback_port',lambda self:self.items['1'*64]['NetworkSettings']['Ports']['3306/tcp'][0].update(HostIp='0.0.0.0'))
authorization_case('different_full_container_id',lambda self:self.items['1'*64].update(Id='3'*64))
authorization_case('native_only_adapter',lambda self:self.source['physical'].update(adapter='this-run-native-only'))
authorization_case('same_uuid',lambda self:self.restore.update(server_uuid=self.source['server_uuid']))
authorization_case('same_container',lambda self:self.restore['physical'].update(container_id='1'*64))
authorization_case('wrong_restore_namespace',lambda self:self.restore.update(database='unowned_restore'))
authorization_case('budget_growth',lambda self:self.items['1'*64]['HostConfig'].update(Memory=2147483648))
authorization_case('bind_datadir_mount',lambda self:self.items['1'*64]['Mounts'][0].update(Type='bind'))
authorization_case('non_local_volume_driver',lambda self:self.volumes['fixture-source'].update(Driver='nfs'))
authorization_case('volume_has_options',lambda self:self.volumes['fixture-source'].update(Options={'device':'shared-path'}))

if __name__=='__main__':unittest.main()
