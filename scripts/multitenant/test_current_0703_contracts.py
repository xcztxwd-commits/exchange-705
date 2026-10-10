"""Offline refusal contracts, not a substitute for physical MySQL/startup evidence."""
import datetime as dt
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import current_0703_activation as a
import current_scope_audit as audit


class CurrentContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='current703-contract-')
        self.directory = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def authorization(self,**changes):
        value = dict(human_instructions=a.INSTRUCTIONS,no_real_users=True,
                     historical_data_policy='PRESERVE_EXCEPT_EXACT_MARKET_MONTHLY',
                     expires_at=(a.c.now()+dt.timedelta(hours=1)).isoformat(),
                     stopped_writers=['owned fixture only'],recovery_owner='fixture owner',activation_output=str(self.directory/'activate.json'),
                     observation_output=str(self.directory/'observed.json'))
        value.update(changes)
        path = self.directory/'owner.json';path.write_text(json.dumps(value),encoding='utf-8')
        return path

    def test_live_test_does_not_infer_missing_human_authority(self):
        for changes in ({'no_real_users':False},{'human_instructions':[]},{'historical_data_policy':'DELETE_ANY'},
                        {'stopped_writers':[]},{'recovery_owner':''},{'keys':{}},
                        {'expires_at':(a.c.now()-dt.timedelta(seconds=1)).isoformat()}):
            with self.assertRaises(ValueError):a.instructions(self.authorization(**changes))

    def test_existing_backup_is_refused_before_any_db_action(self):
        output = self.directory/'observed.json';backup = output.with_suffix('.sql');backup.write_bytes(b'original backup')
        with self.assertRaises(ValueError):a.observe(None,None,self.directory/'owner.json',output)
        self.assertEqual(backup.read_bytes(),b'original backup')

    def test_tested_build_requires_actual_required_success_not_tested_label(self):
        refs=[]
        def write(name,value):
            path=self.directory/name
            path.write_text(value if isinstance(value,str) else json.dumps(value),encoding='utf-8')
            ref={'path':str(path),'sha256':a.c.core.file_hash(path)};refs.append(ref);return ref
        source=write('source.json',{'files':{}})
        artifact=write('artifacts.json',{'artifacts':[{'file':'fixture.jar','sha256':'a'*64}]})
        validations={name:{'result':'PASS','exit_code':0,'failures':0,'errors':0} for name in ('backend_build','related_backend','physical_mysql','orm11')}
        for name,label,command in [('backend_build','backend-build-quoted',['mvn','package']),
                                   ('related_backend','backend-related',['python','-Dtest=FixtureTest','test']),
                                   ('physical_mysql','mysql-related',['python','start_mysql_regression.py'])]:
            log=write(name+'.log','BUILD SUCCESS\n')
            entry={'command':write(name+'.json',{'label':label,'command':command,'exitCode':0,'log':log['path']}),'log':log}
            if name!='backend_build':
                cases=['FixtureTest'] if name=='related_backend' else ['KlineGapBackfillMysqlTest','HistoryRestoreMysqlTest','LiveKlineMysqlTest']
                entry['reports']=[write(case+'.xml','<testsuite name="fixture.'+case+'" tests="1" failures="0" errors="0"><testcase name="owned-unit-fixture"/></testsuite>') for case in cases]
            if name=='physical_mysql':entry['environment']=write('environment.json',{'mysql':'5.7.44','transactionIsolation':'REPEATABLE-READ',
                'junitExitCode':0,'onlyOwnedLocalResources':True,'serverUuid':'fixture-only-not-physical-evidence'})
            validations[name]['raw']=entry
        stdout=write('orm-out.log','synthetic offline unit input');stderr=write('orm-err.log','')
        probe=write('orm-probe.json',{'status':'PASS','checks':[{'status':'PASS','assertions':1} for _ in range(11)],'assertions':11,'privateMappings':[{}]})
        commands=write('orm-commands.json',[{'label':'java8-full-11-groups','exitCode':0,'stdoutSha256':stdout['sha256'],'stderrSha256':stderr['sha256']}])
        verified=write('orm-verified.json',{'result':'PASS_CURRENT_SEALED_JAR_ORIGINAL_11_ORM_GROUPS','verificationExitCode':0,
            'originalCompileExitCode':0,'originalDockerStartExitCode':0,'originalJavaExitCode':0,'jar':{'sha256':'a'*64},'rawProbeReport':probe,
            'commands':commands,'currentSourceArtifactRuntimeMappingsExact':True,'assertions':11,'currentPrivateMappingCount':1,'rawStdout':stdout,'rawStderr':stderr})
        validations['orm11']['raw']={'verification':verified,'probe':probe,'commands':commands,'rawStdout':stdout,'rawStderr':stderr}
        source_hash=a.c.digest({})
        base={'kind':'TESTED_CURRENT_FULL_JAR_BINDING','backend_source_sha256':source_hash,'artifact_sha256':'a'*64,
              'raw_validation_inputs':refs,'sealed_source_manifest':source,'artifact_manifest':artifact,
              'result':'PASS_REQUIRED_PRE_DEPLOYMENT_VALIDATION','blocked':False,'required_validation':validations}
        path=self.directory/'build.json'
        for mutation in ('FAIL','missing','failed_case','exit_error','blocked','unbound','wrong_raw'):
            value=json.loads(json.dumps(base))
            if mutation=='FAIL':value['result']='FAIL'
            elif mutation=='missing':value['required_validation'].pop('orm11')
            elif mutation=='failed_case':value['required_validation']['physical_mysql']['failures']=1
            elif mutation=='exit_error':value['required_validation']['backend_build']['exit_code']=1
            elif mutation=='blocked':value['blocked']=True
            elif mutation=='unbound':value['raw_validation_inputs']=[source,artifact]
            else:value['required_validation']['backend_build']['raw']['command']=source
            path.write_text(json.dumps(value),encoding='utf-8')
            with patch.object(a,'backend_sources',return_value=source_hash),self.assertRaises(ValueError):
                a.build_binding({'tested_build':{'path':str(path),'sha256':a.c.core.file_hash(path)}})
        path.write_text(json.dumps(base),encoding='utf-8')
        with patch.object(a,'backend_sources',return_value=source_hash):
            a.build_binding({'tested_build':{'path':str(path),'sha256':a.c.core.file_hash(path)}})
        physical=validations['physical_mysql']['raw']['reports'];originals=[]
        for ref in physical:
            report=Path(ref['path']);originals.append((ref,report.read_text(encoding='utf-8')))
            report.write_text(originals[-1][1].replace('/>','><skipped/></testcase>'),encoding='utf-8')
            ref['sha256']=a.c.core.file_hash(report)
        physical.append(write('H2OnlyTest.xml','<testsuite name="fixture.H2OnlyTest" tests="1" failures="0" errors="0"><testcase name="runs"/></testsuite>'))
        path.write_text(json.dumps(base),encoding='utf-8')
        with patch.object(a,'backend_sources',return_value=source_hash),self.assertRaises(ValueError):
            a.build_binding({'tested_build':{'path':str(path),'sha256':a.c.core.file_hash(path)}})
        refs.remove(physical.pop())
        for ref,text in originals:
            report=Path(ref['path']);report.write_text(text,encoding='utf-8');ref['sha256']=a.c.core.file_hash(report)
        # Rebind changed raw bytes, while the wrapper still self-declares PASS.
        raw=validations['backend_build']['raw']['command'];raw_path=Path(raw['path'])
        value=json.loads(raw_path.read_text(encoding='utf-8'));value['exitCode']=23
        raw_path.write_text(json.dumps(value),encoding='utf-8');raw['sha256']=a.c.core.file_hash(raw_path)
        path.write_text(json.dumps(base),encoding='utf-8')
        with patch.object(a,'backend_sources',return_value=source_hash),self.assertRaises(ValueError):
            a.build_binding({'tested_build':{'path':str(path),'sha256':a.c.core.file_hash(path)}})

    def test_changed_activation_path_is_refused_before_db_action(self):
        observation=self.directory/'observed.json';observation.write_text(json.dumps({'activation_output':str(self.directory/'once.json')}),encoding='utf-8')
        with self.assertRaises(ValueError):
            a.activate(None,None,observation,None,self.directory/'startup.json',self.directory/'again.json')

    def test_prior_uncertain_intent_blocks_same_attempt(self):
        output = self.directory/'activate.json';output.with_suffix('.intent.json').write_bytes(b'uncertain old attempt')
        observation=self.directory/'observed.json';observation.write_text(json.dumps({'activation_output':str(output.resolve())}),encoding='utf-8')
        with self.assertRaises(ValueError):
            a.activate(None,None,observation,None,self.directory/'startup.json',output)

    def test_symbolic_alias_is_refused_before_resolve_or_database_work(self):
        with patch.object(Path,'is_symlink',return_value=True),patch.object(Path,'resolve') as resolve:
            with self.assertRaises(ValueError):a.new_output(self.directory/'alias.json',('.intent.json','.failure.json'))
            resolve.assert_not_called()

    def test_parent_alias_uses_the_canonical_uncertain_sidecar(self):
        output=self.directory/'activate.json';output.with_suffix('.intent.json').write_bytes(b'original uncertain intent')
        with patch.object(Path,'resolve',return_value=output):
            with self.assertRaises(ValueError):a.new_output(self.directory/'other-name.json',('.intent.json','.failure.json'))

    def test_same_datadir_or_mount_is_not_an_independent_restore(self):
        def target(uuid,container,datadir,mount):
            return {'server_uuid':uuid,'datadir':datadir,'physical':{'container_id':container,'mounts':[{'Destination':datadir,'Source':mount}]}}
        source=target('one','source','/data/source','/owned/source')
        for other in (target('one','restore','/data/restore','/owned/restore'),target('two','restore','/data/source','/owned/restore'),
                      target('two','restore','/data/restore','/owned/source'),target('two','restore','/data/restore','/owned/source/rehearsal')):
            with self.assertRaises(ValueError):a.independent(source,other)
        a.independent(source,target('two','restore','/data/restore','/owned/restore'))

    def stopped_startup(self, change=None):
        raw=b'actual log bytes in an offline refusal fixture'
        log_hash=a.hashlib.sha256(raw).hexdigest()
        network={'NetworkID':'e'*64,'IPAddress':'','IPAMConfig':{'IPv4Address':'10.235.70.231'}}
        actual={'Id':'c'*64,'Image':'sha256:'+'d'*64,'State':{'Running':False,'StartedAt':'2026-10-10T01:02:03Z'},
                'NetworkSettings':{'Networks':{'fixture-network':network}}}
        if change:change(actual)
        value={'kind':'CURRENT_0703_ACTUAL_READ_ONLY_STARTUP','target':{'database':'1090'},'source_sha256':'b'*64,
               'artifact_sha256':'a'*64,'read_only':True,'observed_at':(a.c.now()-dt.timedelta(minutes=1)).isoformat(),
               'actual_non_super_users':['fixture_readonly'],'actual_target_connections':[['fixture_readonly','10.235.70.231','1090']],
               'application':{'container_id':'c'*64,'image_id':'sha256:'+'d'*64,'started_at':'2026-10-10T01:02:03Z',
                              'artifact_path':'/app/app.jar','networks':{'fixture-network':{'network_id':'e'*64,'address':'10.235.70.231'}}},
               'logs':{'path':str(self.directory/'startup.log'),'sha256':log_hash}}
        class DB:
            database='1090'
            def query(self,sql):return ['0']
        with patch.object(a.c,'target',return_value={'database':'1090'}),patch.object(a.c,'sources',return_value='b'*64), \
                patch.object(a.c,'package_epoch',return_value=a.EPOCH), \
                patch.object(a.c.core,'file_hash',side_effect=lambda path:log_hash if Path(path).suffix=='.log' else 'a'*64), \
                patch.object(a.c.core,'run',return_value=SimpleNamespace(stdout=raw,stderr=b'')), \
                patch.object(a.owner,'inspect_application',return_value=actual), \
                patch.object(a.owner,'container_artifact_hash',return_value='a'*64):
            a.verify_startup(DB(),self.directory/'artifact.jar',value)

    def test_stopped_canary_uses_its_retained_static_ip_and_original_network(self):
        # Actual Docker clears IPAddress on stop; its configured IPAM address remains.
        self.stopped_startup()

    def test_stopped_canary_network_reconfiguration_is_refused(self):
        def changed_id(actual):actual['NetworkSettings']['Networks']['fixture-network']['NetworkID']='f'*64
        def changed_address(actual):actual['NetworkSettings']['Networks']['fixture-network']['IPAMConfig']['IPv4Address']='10.235.70.230'
        def removed_static(actual):actual['NetworkSettings']['Networks']['fixture-network']['IPAMConfig']=None
        def running(actual):actual['State']['Running']=True
        for change in (changed_id,changed_address,removed_static,running):
            with self.subTest(change=change.__name__),self.assertRaises(ValueError):self.stopped_startup(change)

    def test_running_canary_requires_explicit_static_address_equal_to_actual(self):
        actual={'NetworkSettings':{'Networks':{'fixture':{'NetworkID':'e'*64,'IPAddress':'10.235.70.231',
                                                          'IPAMConfig':{'IPv4Address':'10.235.70.231'}}}}}
        self.assertEqual(a.application_networks(actual),{'fixture':{'network_id':'e'*64,'address':'10.235.70.231'}})
        for configured in (None,{}, {'IPv4Address':'10.235.70.230'}):
            actual['NetworkSettings']['Networks']['fixture']['IPAMConfig']=configured
            with self.assertRaises(ValueError):a.application_networks(actual)

    def test_all_fields_keep_tokens_nulls_versions_and_decimal_digits(self):
        class DB:
            def columns(self):return {'deposit_record':[['current_token'],['source'],['order_no'],['account_type'],['amount']],
                                      'tenant_schema_version':[['version'],['applied_at'],['minimum_application_epoch'],['business_activation_ready']]}
        captured=[]
        def fingerprint(db,queries,columns):captured.extend(queries);return {}
        with patch.object(a.c,'schema',return_value={}),patch.object(a.c.core,'row_fingerprints',side_effect=fingerprint):
            a.protected_state(DB())
        sql='\n'.join(captured)
        self.assertIn('HEX(CAST(`current_token` AS BINARY))',sql)
        self.assertIn('HEX(CAST(`amount` AS BINARY))',sql)
        self.assertIn('HEX(CAST(`applied_at` AS BINARY))',sql)
        self.assertEqual(sql.count('CASE WHEN version=2026100703 THEN NULL'),1)
        self.assertNotIn('COALESCE',sql)
        self.assertNotIn('LEGACY-',sql)

    def test_missing_scoped_control_tenant_cannot_fall_back_to_global_id(self):
        class DB:
            database='fixture'
            def columns(self):return {'backend_login':[['user_id','bigint','YES']],
                'user_account':[['id','bigint','NO'],['tenant_id','bigint','NO']]}
            def query(self,sql):return []
        with patch.object(audit.core,'MANIFEST',{'private':['user_account'],'relations':[['backend_login','user_id','user_account','id']]}):
            _,checks,missing=audit.build(DB())
        self.assertTrue(any(x.get('reason')=='scoped tenant column absent' for x in missing))
        self.assertFalse(any('relation:backend_login' in label for item in checks for label in item['labels']))

    def test_nullable_control_owner_is_not_silently_filtered_out(self):
        class DB:
            database='fixture'
            def columns(self):return {'backend_login':[['tenant_id','bigint','YES'],['user_id','bigint','YES']],
                'user_account':[['id','bigint','NO'],['tenant_id','bigint','NO']]}
            def query(self,sql):return []
        with patch.object(audit.core,'MANIFEST',{'private':['user_account'],'relations':[['backend_login','user_id','user_account','id']]}):
            sql,checks,missing=audit.build(DB())
        self.assertFalse(missing)
        relation=next(x for x in checks if any(label.startswith('relation:backend_login') for label in x['labels']))
        self.assertEqual(relation['nonNullFields'],('user_id',))
        self.assertIn('p.`tenant_id`=r.`tenant_id`',sql)


if __name__=='__main__':unittest.main()
