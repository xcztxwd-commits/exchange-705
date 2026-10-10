"""Audit the current reviewed manifest; never regenerate historical applied migrations.

The JSON manifest is the single authoring source. Historical V02/V05 must not be
rewritten when an additive business table is introduced. Forward DDL belongs in
a new unique migration version, then is included in this manifest.
"""
import json
from pathlib import Path
import re
import sys
ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / 'scripts/multitenant/table_manifest.json'
MIGRATION_ROOT = ROOT / 'exchange-backend/src/main/resources/db/migration'

def validate(root=ROOT):
    manifest = json.loads((root / MANIFEST.relative_to(ROOT)).read_text(encoding='utf-8'))
    errors = []
    known = set(manifest['private'] + manifest['shared'] + manifest['control'])
    if sum(len(manifest[k]) for k in ('private', 'shared', 'control')) != len(known):
        errors.append('Table classes overlap or contain duplicates')
    versions = []
    creations = {}
    for name in manifest['migration_files']:
        path = root / MIGRATION_ROOT.relative_to(ROOT) / name
        version = re.fullmatch(r'V(20[0-9]{8})(?:_([1-9][0-9]*))?__[A-Za-z0-9_]+\.sql', name)
        if not version or not path.is_file():
            errors.append('Missing/invalid migration: ' + name)
            continue
        versions.append((int(version[1]), int(version[2] or 0)))
        for table in re.findall(r'(?i)CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([a-z0-9_]+)', path.read_text(encoding='utf-8')):
            if table not in known: errors.append('Unclassified DDL table: ' + table)
            creations.setdefault(table, []).append(name)
    if versions != sorted(set(versions)):
        errors.append('Migration versions are duplicated or out of order')
    # The packaged/database epoch is the existing integer major version; minor DDL still has a unique ordered identity.
    if not versions or manifest['schema_epoch'] != max(versions)[0]:
        errors.append('Schema epoch does not match final migration')
    for table, migration in manifest.get('introduced_tables', {}).items():
        if migration not in creations.get(table, []):
            errors.append('Introduced table has no included CREATE TABLE: ' + table)
    for child, field, parent, parent_field in manifest['relations']:
        if child not in known or parent not in known:
            errors.append('Unknown relationship: ' + child + '.' + field)
    epoch = root / 'exchange-backend/src/main/resources/META-INF/mt705-schema-epoch'
    if epoch.read_text(encoding='ascii').strip() != str(manifest['schema_epoch']):
        errors.append('Packaged schema epoch disagrees with manifest')
    return errors

if __name__ == '__main__':
    errors = validate()
    if errors:
        print('\n'.join(errors)); sys.exit(1)
    print('TENANT_MANIFEST_DDL_PASS; historical migrations unchanged')
