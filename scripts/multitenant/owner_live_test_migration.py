"""Explicit owner-authorized live-test migration, not independent signed approval.

The default signed production route is unchanged. This exceptional route requires
the owner's exact no-real-users instruction, bound backup/target/source receipts,
actual maintenance and an unsigned append-only evidence chain. Activation stays off.
"""
import datetime as dt
from pathlib import Path
import controlled_migration as c

INSTRUCTION = '跳过批准环节，目前线上环节无真实用户，允许直接迁移'
KIND = 'OWNER_AUTHORIZED_LIVE_TEST_MIGRATION_NOT_SIGNED_APPROVAL'

class OwnerLiveTestPolicy:
    fixture = False
    owner_live_test = True

    def __init__(self, path, db, restore):
        self.path = Path(path).resolve()
        self.value = c.read(self.path)
        self.db, self.restore = db, restore
        self.guard()

    def guard(self):
        value = self.value
        if c.read(self.path) != value or any(k in value for k in ('signatures', 'keys', 'payload', 'journal_key')):
            raise ValueError('Owner instruction must not impersonate signed approval')
        if value.get('kind') != KIND or value.get('owner_asserts_no_real_users') is not True:
            raise ValueError('Explicit owner-authorized live-test scope required')
        receipt = Path(value['instruction_receipt'])
        if c.core.file_hash(receipt) != value['instruction_sha256'] or c.read(receipt).get('human_instruction') != INSTRUCTION:
            raise ValueError('Exact current owner instruction receipt required')
        expiry = dt.datetime.fromisoformat(value['expires_at'])
        if expiry.tzinfo is None or expiry <= c.now():
            raise ValueError('Owner live-test authorization expired')
        if self.db.test or not self.restore.test or c.target(self.db) != value['source'] or c.target(self.restore) != value['restore']:
            raise ValueError('Exact live-test source and isolated restore target required')
        if c.sources() != value.get('source_sha256') or c.migrations() != value.get('migrations'):
            raise ValueError('Owner-authorized source or reviewed SQL changed')
        if c.isolation_gate.check()[0]:
            raise ValueError('Reviewed source isolation checks remain mandatory')
        if not value.get('stopped_writers') or not value.get('recovery_owner'):
            raise ValueError('Actual writer inventory and recovery responsibility required')
        c.maintenance(self.db)

    def verify(self, authorization, binding, scope):
        self.guard()
        if authorization != self.value or scope != 'business' or binding != self.value.get('binding'):
            raise ValueError('Owner authorization differs from exact plan/backup/source/target binding')

class OwnerLiveTestLedger:
    def __init__(self, directory, policy):
        if not isinstance(policy, OwnerLiveTestPolicy):
            raise ValueError('Explicit owner live-test policy required')
        self.directory, self.policy = Path(directory), policy

    def rows(self):
        rows, previous = [], '0'*64
        for number, path in enumerate(sorted(self.directory.glob('*.json'))):
            if path.name != f'{number:06d}.json':
                raise ValueError('Owner evidence sequence incomplete')
            item = c.read(path); body = item['body']
            if (body.get('journal_kind') != KIND or body.get('sequence') != number
                    or body.get('previous') != previous or item.get('sha256') != c.digest(body)):
                raise ValueError('Owner unsigned evidence reordered or changed')
            previous = c.digest(item); rows.append(body)
        return rows

    def latest(self):
        rows = self.rows()
        if not rows: raise ValueError('No owner live-test migration evidence')
        return rows[-1]

    def append(self, body):
        if any(k in body for k in ('sequence', 'previous', 'at', 'journal_kind')):
            raise ValueError('Caller cannot replace evidence chain fields')
        rows = self.rows()
        previous = c.digest(c.read(self.directory/f'{len(rows)-1:06d}.json')) if rows else '0'*64
        value = {'sequence':len(rows), 'previous':previous, 'at':c.now().isoformat(), 'journal_kind':KIND, **body}
        c.publish(self.directory/f'{len(rows):06d}.json', {'body':value, 'sha256':c.digest(value)})
