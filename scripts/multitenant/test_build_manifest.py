"""Check ordered major/minor migration identities without changing historical DDL."""
import json
from pathlib import Path
import tempfile
import unittest
from build_manifest import validate


class MigrationVersionTest(unittest.TestCase):
    def check_versions(self, names, epoch=2026100703):
        with tempfile.TemporaryDirectory(prefix='manifest-version-') as directory:
            root = Path(directory)
            config = root / 'scripts/multitenant'
            migrations = root / 'exchange-backend/src/main/resources/db/migration'
            resource = root / 'exchange-backend/src/main/resources/META-INF'
            for path in (config, migrations, resource):
                path.mkdir(parents=True)
            manifest = dict(private=[], shared=[], control=[], relations=[], migration_files=names, schema_epoch=epoch)
            (config / 'table_manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
            (resource / 'mt705-schema-epoch').write_text(str(epoch), encoding='ascii')
            for name in names:
                (migrations / name).write_text('-- synthetic version only\n', encoding='utf-8')
            return validate(root)

    def test_minor_versions_are_ordered_numerically_and_keep_integer_epoch(self):
        self.assertEqual([], self.check_versions(['V2026100703__base.sql', 'V2026100703_1__wallet.sql', 'V2026100703_2__next.sql', 'V2026100703_10__last.sql']))

    def test_major_after_minors_advances_epoch(self):
        self.assertEqual([], self.check_versions(['V2026100703__base.sql', 'V2026100703_1__wallet.sql', 'V2026100704__next.sql'], 2026100704))

    def test_same_major_minor_identity_cannot_be_reused(self):
        for names in (['V2026100703__one.sql', 'V2026100703__two.sql'], ['V2026100703_1__one.sql', 'V2026100703_1__two.sql']):
            self.assertIn('Migration versions are duplicated or out of order', self.check_versions(names))

    def test_minor_before_major_and_lexical_sort_are_rejected(self):
        for names in (['V2026100703_1__wallet.sql', 'V2026100703__base.sql'], ['V2026100703_10__last.sql', 'V2026100703_2__next.sql']):
            self.assertIn('Migration versions are duplicated or out of order', self.check_versions(names))

    def test_zero_leading_zero_and_multiple_minor_parts_are_rejected(self):
        for name in ('V2026100703_0__invalid.sql', 'V2026100703_01__invalid.sql', 'V2026100703_1_2__invalid.sql'):
            self.assertIn('Missing/invalid migration: ' + name, self.check_versions([name]))

    def test_epoch_must_equal_final_major(self):
        self.assertIn('Schema epoch does not match final migration', self.check_versions(['V2026100703__base.sql', 'V2026100703_1__wallet.sql'], 2026100702))


if __name__ == '__main__':
    unittest.main()
