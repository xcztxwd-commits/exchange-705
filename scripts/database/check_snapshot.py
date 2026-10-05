"""Verify the public, data-free database snapshot without connecting to MySQL."""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
FOLDER = ROOT / 'docs/database'


def check_sql(sql, manifest):
    tables = re.findall(r'^CREATE TABLE `([A-Za-z0-9_]+)` \(', sql, re.M)
    assert len(tables) == len(set(tables)), 'Duplicate CREATE TABLE'
    assert sorted(tables) == manifest['tables'], 'Snapshot table inventory changed'
    blocks = re.findall(r'^CREATE TABLE `\w+` \(\n(.*?)^\) ENGINE=[^\n]+;', sql, re.M | re.S)
    counts = {
        'tables': len(tables),
        'columns': sum(len(re.findall(r'^  `\w+` ', b, re.M)) for b in blocks),
        'indexes': sum(len(re.findall(r'^  (?:PRIMARY KEY|(?:UNIQUE |FULLTEXT |SPATIAL )?KEY)\b', b, re.M)) for b in blocks),
        'foreign_keys': sum(len(re.findall(r'^  CONSTRAINT .* FOREIGN KEY ', b, re.M)) for b in blocks),
        'triggers': len(re.findall(r'/\*!50003 TRIGGER `?[A-Za-z0-9_]+`?(?=\s)', sql)),
        'routines': len(re.findall(r'^CREATE DEFINER=CURRENT_USER (?:PROCEDURE|FUNCTION) `\w+`', sql, re.M)),
    }
    assert counts == {k: manifest['counts'][k] for k in counts}, 'Snapshot object counts changed'
    assert not re.search(r'DEFINER\s*=(?!CURRENT_USER\b)', sql), 'Source account DEFINER leaked'
    delimiter = ';'
    for line in sql.splitlines():
        if line.startswith('DELIMITER '):
            delimiter = line.split()[1]
        elif delimiter == ';':
            plain = re.sub(r'/\*!\d+\s*(.*?)\s*\*/', r'\1', line).strip()
            assert not re.match(r'(?i)(INSERT|REPLACE|UPDATE|DELETE|DROP|TRUNCATE|GRANT|USE)\b', plain), 'Top-level data/destructive SQL'
    assert delimiter == ';', 'Unclosed stored-object delimiter'
    return counts


def check():
    manifest = json.loads((FOLDER / 'schema-manifest.json').read_text(encoding='utf-8'))
    inventory = json.loads((ROOT / 'scripts/multitenant/table_manifest.json').read_text(encoding='utf-8'))
    assert manifest['schema_epoch'] == inventory['schema_epoch'], 'Snapshot is behind the schema epoch'
    expected = set(inventory['private'] + inventory['shared'] + inventory['control'])
    assert set(manifest['tables']) | set(manifest['registered_without_ddl']) == expected
    assert set(manifest['tables']).isdisjoint(manifest['registered_without_ddl'])
    assert [x['name'] for x in manifest['migrations']] == inventory['migration_files'], 'Migration order changed'
    required = {'docs/database/schema.sql', 'docs/database/data-dictionary.md',
                'scripts/multitenant/table_manifest.json', 'scripts/multitenant/legacy-schema.sql',
                'scripts/multitenant/legacy-feature-tables.sql'}
    required.update('exchange-backend/src/main/resources/db/migration/' + n for n in inventory['migration_files'])
    assert set(manifest['sha256']) == required, 'Checksum inventory is incomplete'
    for relative, expected_hash in manifest['sha256'].items():
        path = (ROOT / relative).resolve()
        assert path.is_relative_to(ROOT), 'Checksum path escapes the repository'
        assert hashlib.sha256(path.read_bytes()).hexdigest() == expected_hash, 'Checksum mismatch: ' + relative
    counts = check_sql((FOLDER / 'schema.sql').read_text(encoding='utf-8'), manifest)
    print('DATABASE_SNAPSHOT_PASS ' + json.dumps(counts, sort_keys=True))


def self_test():
    schema = 'CREATE TABLE `example` (\n  `id` bigint NOT NULL,\n  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;\n'
    manifest = {'tables': ['example'], 'counts': {'tables': 1, 'columns': 1, 'indexes': 1,
                'foreign_keys': 0, 'triggers': 0, 'routines': 0}}
    check_sql(schema, manifest)
    for mutation in [schema + 'INSERT INTO example VALUES (1);\n',
                     schema + '/*!50017 DEFINER=`private_user`@`private_host`*/\n',
                     schema.replace('`example`', '`other`')]:
        try:
            check_sql(mutation, manifest)
        except AssertionError:
            continue
        raise AssertionError('Unsafe snapshot accepted')
    print('DATABASE_SNAPSHOT_SELF_TEST_PASS checks=4')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    self_test() if args.self_test else check()
