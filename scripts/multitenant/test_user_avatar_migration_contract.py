"""Offline integration checks; not evidence of a production migration or restore."""
import json
from pathlib import Path
import unittest

from build_manifest import validate

ROOT = Path(__file__).resolve().parents[2]


class UserAvatarMigrationContractTest(unittest.TestCase):
    def test_unique_ordered_migrations_and_packaged_epoch(self):
        self.assertEqual([], validate(ROOT))
        manifest = json.loads((ROOT / 'scripts/multitenant/table_manifest.json').read_text(encoding='utf-8'))
        self.assertEqual([
            'V2026100601__control_policy_definitions.sql',
            'V2026100602__user_avatar.sql',
            'V2026100603__tenant_entry_frontend_roles.sql',
        ], manifest['migration_files'][-3:])
        self.assertEqual(2026100603, manifest['schema_epoch'])

    def test_avatar_column_is_additive_and_activation_requires_fresh_proof(self):
        sql = (ROOT / 'exchange-backend/src/main/resources/db/migration/V2026100602__user_avatar.sql').read_text(encoding='utf-8')
        statements = [part.strip() for part in '\n'.join(
            line for line in sql.splitlines() if not line.lstrip().startswith('--')
        ).split(';') if part.strip()]
        self.assertEqual(2, len(statements))
        self.assertEqual('ALTER TABLE user_account ADD COLUMN avatar_url VARCHAR(300) NULL', statements[0])
        self.assertRegex(statements[1], r'^INSERT INTO tenant_schema_version\(')
        self.assertIn('VALUES(2026100602,UTC_TIMESTAMP(6),2026100602,0)', statements[1])


if __name__ == '__main__':
    unittest.main()
