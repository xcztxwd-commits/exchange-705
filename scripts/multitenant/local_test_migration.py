"""Explicit owner-authorized disposable migration tests, never business approval.

No signer, signing key, production policy change, source restoration or automatic
uncertain-DDL replay. Evidence uses an unsigned SHA-256 chain, not HMAC approval.
"""
import copy
import datetime as dt
import json
import os
from pathlib import Path, PurePosixPath
import re
import controlled_migration as c

core = c.core
ENVIRONMENT = 'LOCAL / NON-PRODUCTION / DISPOSABLE TEST ENVIRONMENT'
JOURNAL = 'LOCAL_OWNER_TEST_EVIDENCE_NOT_SIGNED_APPROVAL'
MIGRATION = 'V2026100403__source_history_input_revision.sql'
HISTORY_MIGRATION = 'V2026100404__history_ordering_and_response_receipts.sql'

def phase():
    # Explicit reviewed phases only. A 0403 namespace may continue to 0404 without rebuilding capacity data.
    reviewed={2026100403:(2026100402,MIGRATION,'403'),
              2026100404:(2026100403,HISTORY_MIGRATION,'(?:403|404)'),
              2026100702:(2026100603,c.CONTROL0702_TAIL[-1],'702')}
    value=reviewed.get(core.EPOCH)
    if (value is None or c.migrations()[-1]['name']!=value[1]
            or (core.EPOCH==2026100702 and tuple(x['name'] for x in c.migrations()[-3:])!=('V2026100603__tenant_entry_frontend_roles.sql',*c.CONTROL0702_TAIL))):
        raise ValueError('Only the exact reviewed local0403, local0404 or local0603-to0702 tails are supported')
    return value

RESTORE_ROLES = {'prerestore', 'restore', 'resume_restore', 'terminal_restore', 'rollback_restore'}
HISTORY_RESTORE_ROLES = {'h4_prerestore', 'h4_restore', 'h4_terminal_restore'}

class LocalPolicy:
    fixture = True
    local = True

    def __init__(self, path, db, restore_db):
        self.path = Path(path).resolve()
        self.value = c.read(self.path)
        self.db, self.restore_db = db, restore_db
        self.guard_targets(db, restore_db)

    def guard_targets(self, db, restore_db):
        value = self.value
        if c.read(self.path) != value or any(k in value for k in ('payload', 'signatures', 'keys', 'journal_key')):
            raise ValueError('Local owner authorization is not a fabricated signed approval')
        if (value.get('environment') != ENVIRONMENT or value.get('scope') != 'LOCAL_SCHEMA_TEST'
                or value.get('owner_authorization') != '当前项目所有者的明确授权即视为本次测试执行授权'):
            raise ValueError('Explicit disposable-test owner authorization required')
        expiry = dt.datetime.fromisoformat(value['expires_at'])
        if expiry.tzinfo is None or expiry <= c.now(): raise ValueError('Local execution authorization expired')
        if value.get('source_sha256') != c.sources() or value.get('migrations') != c.migrations():
            raise ValueError('Local source or reviewed migration bytes changed')
        previous_epoch,_,namespace=phase()
        source, restore = c.target(db), c.target(restore_db)
        if not db.test or not restore_db.test or source != value['source']:
            raise ValueError('Local mode cannot authorize a business or different source target')
        if not re.fullmatch(rf'mt705_probe_joint_local{namespace}_[0-9a-f]{{16}}', source['database']):
            raise ValueError('Only exact owned reviewed local migration namespaces may be used')
        roles = RESTORE_ROLES | (HISTORY_RESTORE_ROLES if core.EPOCH == 2026100404 else set())
        if len(restore['database']) > 64 or restore['database'] not in {source['database']+'_'+role for role in roles}:
            raise ValueError('Restore must use a new namespace belonging to this exact local source')
        expected_restore = copy.deepcopy(value['restore'])
        expected_restore['database'] = restore['database']
        expected_restore['physical']['database'] = restore['database']
        if restore != expected_restore or not independent_datadir(source, restore):
            raise ValueError('Actual independent restore identity or volume changed')
        for actual in (source, restore):
            physical = actual['physical']
            if physical.get('adapter') is not None: raise ValueError('Local mode requires owned Docker fixtures')
            item = json.loads(core.run(['docker', 'inspect', physical['container_id']]).stdout)[0]
            labels = item['Config'].get('Labels', {})
            if (not item['State']['Running'] or labels.get('com.gtcfesk.multitenant.test') != 'true'
                    or labels.get('com.gtcfesk.joint.owner') != value.get('owner')
                    or labels.get('com.gtcfesk.stage1.run') != value.get('run')
                    or not value.get('owner') or not value.get('run')):
                raise ValueError('Actual task ownership or local fixture label differs')
            if (item['HostConfig']['Memory'] != 1073741824 or item['HostConfig']['MemorySwap'] != 1073741824
                    or item['HostConfig']['NanoCpus'] != 1000000000 or item['HostConfig']['PidsLimit'] != 128):
                raise ValueError('Local fixture resource budget differs')
            for bindings in item['NetworkSettings'].get('Ports', {}).values():
                if bindings and any(x.get('HostIp') != '127.0.0.1' for x in bindings):
                    raise ValueError('Local mode requires loopback-only published ports')
        # The restore namespace may not exist yet. Never query it through a database-bound connection.
        for database in (db, restore_db):
            drained = database.sql('SELECT @@global.read_only; '
                                   'SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID(); '
                                   'SELECT COUNT(*) FROM information_schema.INNODB_TRX;', database=False).stdout.decode().strip().splitlines()
            if drained != ['1', '0', '0']:
                raise ValueError('Both owned servers require actual read_only=1 and drained sessions/transactions')
            exists = database.sql('SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='
                                  +core.literal(database.database), database=False).stdout.decode().strip()
            if exists == '0' and database is restore_db: continue
            metadata = database.query('SELECT MAX(version),MAX(minimum_application_epoch),'
                                      'SUM(CAST(business_activation_ready AS UNSIGNED)) FROM tenant_schema_version')
            if metadata not in ([f'{previous_epoch}\t{previous_epoch}\t0'], [f'{core.EPOCH}\t{previous_epoch if core.EPOCH==2026100702 else core.EPOCH}\t0']):
                raise ValueError('Only inactive baseline/current schemas for the reviewed phase are authorized')
        if db.query('SELECT COUNT(*) FROM market_engine_runtime WHERE lease_until>'
                    'CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)') != ['0']:
            raise ValueError('Local source writers still have a valid lease')
        if (db.query("SELECT COUNT(*) FROM market_control_command WHERE state IN ('ACCEPTED','PREPARING','READY')") != ['0']
                or db.query("SELECT COUNT(*) FROM control_chat_archive_job WHERE state IN ('QUEUED','RUNNING')") != ['0']):
            raise ValueError('Local application work must drain without rewriting old jobs')
        return source, restore

    def verify(self, authorization, binding, scope):
        self.guard_targets(self.db, self.restore_db)
        if authorization != self.value or scope != 'isolated-fixture':
            raise ValueError('Local owner authorization cannot substitute for business signatures')
        if (binding.get('source_sha256') != self.value['source_sha256']
                or binding.get('target_sha256') != c.digest(self.value['source'])
                or any(not re.fullmatch(r'[0-9a-f]{64}', str(binding.get(k, '')))
                       for k in ('plan_sha256', 'restore_proof_sha256', 'backup_sha256'))):
            raise ValueError('Local plan, source, backup and target binding is incomplete')

class LocalLedger:
    def __init__(self, directory, policy):
        if not isinstance(policy, LocalPolicy): raise ValueError('Local ledger requires explicit local policy')
        self.directory, self.policy = Path(directory), policy

    def rows(self):
        rows, previous = [], '0'*64
        for number, path in enumerate(sorted(self.directory.glob('*.json'))):
            if path.name != f'{number:06d}.json': raise ValueError('Local evidence sequence is incomplete')
            item = c.read(path); body = item['body']
            if (body.get('journal_kind') != JOURNAL or body.get('sequence') != number
                    or body.get('previous') != previous or item.get('sha256') != c.digest(body)):
                raise ValueError('Local unsigned evidence is reordered or changed; retain maintenance')
            previous = c.digest(item); rows.append(body)
        return rows

    def latest(self):
        rows = self.rows()
        if not rows: raise ValueError('No local migration evidence')
        return rows[-1]

    def append(self, body):
        if any(k in body for k in ('sequence', 'previous', 'at', 'journal_kind')):
            raise ValueError('Caller cannot overwrite local evidence chain fields')
        rows = self.rows()
        previous = c.digest(c.read(self.directory/f'{len(rows)-1:06d}.json')) if rows else '0'*64
        value = {'sequence': len(rows), 'previous': previous, 'at': c.now().isoformat(),
                 'journal_kind': JOURNAL, **body}
        c.publish(self.directory/f'{len(rows):06d}.json', {'body': value, 'sha256': c.digest(value)})

def observe_local_baseline(db, restore_db, reference_state, imported_backup, ledger):
    """Observe a real imported reviewed baseline; do not invent past migration completion."""
    previous_epoch,_,_=phase()
    ledger.policy.guard_targets(db, restore_db)
    c.maintenance(db)
    if ledger.rows(): raise ValueError('Local baseline evidence already exists')
    if core.file_hash(Path(imported_backup['path'])) != imported_backup['sha256']:
        raise ValueError('Immutable imported baseline dump changed')
    if c.state(db) != reference_state or c.state(restore_db) != reference_state:
        raise ValueError('Imported baseline full definitions, counters or all-column rows differ')
    if db.query('SELECT MAX(version),MAX(minimum_application_epoch),SUM(CAST(business_activation_ready AS UNSIGNED)) FROM tenant_schema_version') != [f'{previous_epoch}\t{previous_epoch}\t0']:
        raise ValueError('Exact inactive reviewed baseline required')
    if db.query(f'SELECT COUNT(*) FROM tenant_schema_version WHERE version={core.EPOCH}') != ['0']:
        raise ValueError('Do not infer current-phase completion from an existing database')
    value = {'kind':'LOCAL_BASELINE_OBSERVED', 'target':c.target(db), 'state':reference_state,
             'migrations':c.migrations()[:-2 if core.EPOCH==2026100702 else -1], 'imported_backup':imported_backup,
             'restore':c.target(restore_db), 'source_sha256':c.sources(),
             'authorization_sha256':core.file_hash(ledger.policy.path), 'activation_ready':False,
             'qualification':'Actual imported reviewed baseline full-state observation; not a signed or fabricated past COMPLETE'}
    ledger.append(value)
    return value


def _container_path(value):
    if not isinstance(value,str) or not value.startswith('/') or value.startswith('//') or '\\' in value or '\x00' in value:
        raise ValueError('Unknown container storage path')
    if any(part in ('.','..') for part in value.split('/')):raise ValueError('Ambiguous container storage path')
    return PurePosixPath(value)

def _docker_datadir_volume(value):
    physical=value['physical'];identifier=physical.get('container_id')
    if physical.get('adapter') is not None or not isinstance(identifier,str) or not re.fullmatch(r'[0-9a-f]{64}',identifier):return None
    items=json.loads(core.run(['docker','inspect',identifier]).stdout)
    if len(items)!=1:return None
    item=items[0]
    if (item['Id']!=identifier or item['Name'].lstrip('/')!=physical['container']
            or item['Image']!=physical['image_id'] or item['Mounts']!=physical['mounts']):return None
    actual_fixture=item.get('Config',{}).get('Labels',{}).get('com.gtcfesk.multitenant.test')=='true'
    if physical.get('test_instance') not in (True,False) or physical['test_instance'] is not actual_fixture:return None
    datadir=_container_path(value['datadir'])
    mounted=[(mount,_container_path(mount['Destination'])) for mount in item['Mounts']]
    roots=[(mount,path) for mount,path in mounted if path==datadir or path in datadir.parents]
    # ponytail: qualify exact local volume roots only; ancestor/submount/bind layouts need reviewed storage resolution.
    if len(roots)!=1 or roots[0][1]!=datadir or any(datadir in path.parents for _,path in mounted):return None
    mount=roots[0][0];name=mount.get('Name')
    if (mount.get('Type')!='volume' or mount.get('Driver')!='local' or mount.get('RW') is not True
            or not isinstance(name,str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*',name)):return None
    source=_container_path(mount['Source'])
    volumes=json.loads(core.run(['docker','volume','inspect',name]).stdout)
    if len(volumes)!=1:return None
    volume=volumes[0]
    if (volume.get('Name')!=name or volume.get('Driver')!='local' or volume.get('Scope')!='local'
            or 'Options' not in volume or volume['Options'] not in (None,{})
            or _container_path(volume['Mountpoint'])!=source):return None
    return name,source

def independent_datadir(source,restore):
    """Requalify live physical storage; namespace path text alone proves neither equality nor independence."""
    try:
        uuids=[value['server_uuid'] for value in (source,restore)]
        if any(not isinstance(value,str) or not re.fullmatch(r'[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}',value) for value in uuids):return False
        if uuids[0].lower()==uuids[1].lower():return False
        adapters=[value['physical'].get('adapter') for value in (source,restore)]
        if adapters==['this-run-native-only','this-run-native-only']:
            paths=[Path(value['datadir']) for value in (source,restore)]
            if not all(path.is_absolute() for path in paths):return False
            a,b=[path.resolve(strict=True) for path in paths]
            return (a.is_dir() and b.is_dir() and a!=b and a not in b.parents and b not in a.parents
                    and not os.path.samefile(a,b))
        if adapters!=[None,None] or source['physical'].get('container_id')==restore['physical'].get('container_id'):return False
        a=_docker_datadir_volume(source);b=_docker_datadir_volume(restore)
        return bool(a and b and a[0]!=b[0] and a[1]!=b[1] and a[1] not in b[1].parents and b[1] not in a[1].parents)
    except (AttributeError,KeyError,IndexError,TypeError,ValueError,OSError,RuntimeError):
        return False

