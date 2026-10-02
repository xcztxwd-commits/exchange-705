"""Mutation checks in a temporary synthetic source tree; no production source is edited."""
import json
from pathlib import Path
import tempfile
from isolation_gate import SOURCE, check, scan


def main():
    checks = []
    with tempfile.TemporaryDirectory(prefix='tenant-gate-') as directory:
        root = Path(directory)
        source = root / SOURCE
        source.mkdir(parents=True)
        config = root / 'scripts/multitenant'
        config.mkdir(parents=True)
        (config / 'table_manifest.json').write_text(json.dumps({'private':['orders'],'shared':[],'control':[]}), encoding='utf-8')
        entity = source / 'Order.java'
        entity.write_text('@Persister(impl=TenantEntityPersister.class)\n@Entity\n@Table(name="orders")\npublic class Order extends TenantOwnedEntity {}', encoding='utf-8')
        repo = source / 'OrderRepository.java'
        repo.write_text('public interface OrderRepository extends TenantRepository<Order,Long> {}', encoding='utf-8')
        registry = config / 'registry.json'

        def register():
            registry.write_text(json.dumps({'approved':True, 'files':{p:{**v,'classification':'private_scoped','review_reason':'Synthetic mutation fixture'} for p,v in scan(root).items()}}), encoding='utf-8')

        register()
        assert not check(root, registry)[0]; checks.append('reviewed_baseline_passes')
        assert any('release approval' in e for e in check(root, registry,release=True)[0]); checks.append('source_baseline_is_not_release_approval')
        unknown = source / 'Unknown.java'
        unknown.write_text('class Unknown {}', encoding='utf-8')
        assert any('unregistered source' in e for e in check(root, registry)[0]); checks.append('unknown_even_stateless_file_fails')
        unknown.unlink()
        repo.write_text('public interface OrderRepository extends TenantRepository<Order,Long> { /* removed WHERE tenant_id */ }', encoding='utf-8')
        assert any('source changed' in e for e in check(root, registry)[0]); checks.append('any_existing_source_change_fails')
        repo.write_text('public interface OrderRepository extends JpaRepository<Order,Long> {}', encoding='utf-8')
        register()
        assert any('unscoped CRUD' in e for e in check(root, registry)[0]); checks.append('private_unscoped_repository_fails_despite_new_hash')
        repo.write_text('public interface OrderRepository extends TenantRepository<Order,Long> { @Query("select count(o) from Order o") long total(); }', encoding='utf-8')
        register()
        assert any('lacks a tenant predicate' in e for e in check(root, registry)[0]); checks.append('private_projection_without_tenant_fails_despite_new_hash')
        repo.write_text('public interface OrderRepository extends TenantRepository<Order,Long> {}', encoding='utf-8')
        entity.write_text('@Entity\n@Table(name="orders")\npublic class Order extends TenantOwnedEntity {}', encoding='utf-8')
        register()
        assert any('write persister' in e for e in check(root, registry)[0]); checks.append('private_entity_without_write_persister_fails')
        entity.write_text('@Entity\n@Table(name="unregistered")\npublic class Order extends TenantOwnedEntity {}', encoding='utf-8')
        register()
        assert any('unclassified entity' in e for e in check(root, registry)[0]); checks.append('unknown_table_fails_despite_new_hash')
        entity.write_text('@Entity\n@Table(name="orders")\npublic class Order {}', encoding='utf-8')
        register()
        assert any('does not inherit' in e for e in check(root, registry)[0]); checks.append('private_entity_without_tenant_fails')
        data = json.loads(registry.read_text(encoding='utf-8')); data['approved'] = False
        registry.write_text(json.dumps(data), encoding='utf-8')
        assert any('unapproved' in e for e in check(root, registry)[0]); checks.append('candidate_not_accepted')
        data['approved'] = True
        first = next(iter(data['files'])); data['files'][first]['review_reason'] = ''
        registry.write_text(json.dumps(data), encoding='utf-8')
        assert any('classification/review reason' in e for e in check(root, registry)[0]); checks.append('missing_review_reason_fails')
        unknown.write_text('class BadIdentity { CustomUserIdGenerator old; }', encoding='utf-8')
        register()
        assert any('MAX+1' in e for e in check(root, registry)[0]); checks.append('legacy_generator_cannot_be_reactivated')
    print('TENANT_SOURCE_GATE_MUTATIONS_PASS checks=' + str(len(checks)))
    return checks


if __name__ == '__main__':
    main()
