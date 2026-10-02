"""Fail-closed source registry. This is a change-review gate, NOT a SQL interceptor or proof of isolation.

Every production Java file is fingerprinted so even a detector's blind spot cannot silently enter.
Sensitive occurrences are individually listed for review; private entities/repositories get structural checks.
--propose writes an UNAPPROVED candidate. It never replaces the checked-in reviewed manifest.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
import sys

ROOT=Path(__file__).resolve().parents[2]
SOURCE=Path('exchange-backend/src/main/java')
REGISTRY=ROOT/'scripts/multitenant/source_isolation_registry.json'
RULES={
 'native_sql':r'(?i)JdbcTemplate|prepareStatement\s*\(|createNativeQuery\s*\(|\.executeQuery\s*\(|\b(?:SELECT|INSERT INTO|UPDATE|DELETE FROM)\b',
 'entity_manager_jpql':r'EntityManager|@(?:[\w.]*\.)?Query\(|\.createQuery\(',
 'cache_lock':r'ConcurrentHashMap|Cacheable|CacheEvict|CacheManager|RedisTemplate|StringRedisTemplate|Caffeine|(?:private|protected)\s+(?:static\s+)?(?:final\s+)?(?:java\.util\.)?(?:Map|Set|ConcurrentMap)<',
 'async_job':r'@(?:[\w.]*\.)?Scheduled|@Async|ExecutorService|Executors\.|CompletableFuture|\.submit\(|\.schedule\(|new Thread\(',
 'file_io':r'Files\.(?:write|read|newInput|newOutput|copy|move|delete|createDirectories)|FileSystemResource|MultipartFile|Paths\.get\(|addResourceHandler\(',
 'tenant_entity':r'@Entity\b',
 'repository':r'extends\s+(?:[\w.]*JpaRepository|[\w.]*TenantRepository|[\w.]*Repository<)',
 'identity_boundary':r'TenantContext|TenantHostService|ControlIdentity|SecurityContextHolder|UsernamePasswordAuthenticationToken',
 'startup':r'@PostConstruct|CommandLineRunner|ApplicationRunner|ApplicationReadyEvent'
}

def normalized(text):return text.replace('\r\n','\n')
def digest(text):return hashlib.sha256(normalized(text).encode('utf-8')).hexdigest()
def scan(root=ROOT):
    files={}
    for path in sorted((root/SOURCE).rglob('*.java')):
        source=path.read_text(encoding='utf-8-sig');sites=[]
        for line,text in enumerate(source.splitlines(),1):
            for kind,pattern in RULES.items():
                if re.search(pattern,text):sites.append({'kind':kind,'line':line,'statement_sha256':digest(text.strip())})
        files[path.relative_to(root).as_posix()]={'sha256':digest(source),'surfaces':sites}
    return files

def structural(root,files):
    manifest=json.loads((root/'scripts/multitenant/table_manifest.json').read_text(encoding='utf-8'))
    private=set(manifest['private']);known=private|set(manifest['shared'])|set(manifest['control']);errors=[];private_entities=set()
    for relative in files:
        source=(root/relative).read_text(encoding='utf-8-sig')
        if '@Entity' not in source:continue
        table=re.search(r'@Table\s*\(\s*name\s*=\s*"([a-z0-9_]+)"',source)
        name=re.search(r'public\s+class\s+(\w+)',source)
        if not table or table[1] not in known:errors.append(relative+': unknown/unclassified entity table');continue
        if table[1] in private:
            if not re.search(r'extends\s+(?:[\w.]*\.)?TenantOwnedEntity\b',source):errors.append(relative+': private entity does not inherit tenant ownership')
            if not re.search(r'@(?:[\w.]*\.)?Persister\s*\(\s*impl\s*=\s*(?:[\w.]*\.)?TenantEntityPersister\.class\s*\)',source):errors.append(relative+': private entity missing explicit tenant write persister')
            if name:private_entities.add(name[1])
    for relative in files:
        source=(root/relative).read_text(encoding='utf-8-sig')
        if re.search(r'extends\s+(?:[\w.]*\.)?(?:JpaRepository|CrudRepository|PagingAndSortingRepository)\s*<\s*('+'|'.join(sorted(private_entities))+r')\s*,',source):errors.append(relative+': private repository exposes unscoped CRUD')
        if 'CustomUserIdGenerator' in source and not relative.endswith('/CustomUserIdGenerator.java'):errors.append(relative+': forbidden legacy MAX+1 identity generator reference')
        if re.search(r'extends\s+(?:[\w.]*\.)?TenantRepository\s*<',source):
            for query in re.findall(r'@(?:[\w.]*\.)?Query\s*\(\s*(?:value\s*=\s*)?"((?:\\.|[^"\\])*)"',source):
                if not re.search(r'(?i)\b(?:tenantId|tenant_id)\s*=',query):
                    errors.append(relative+': private repository query lacks a tenant predicate')
    if manifest.get('migration_files'):
        from build_manifest import validate
        errors.extend(validate(root))
    return errors

def check(root=ROOT,registry_path=REGISTRY,release=False):
    actual=scan(root);registry=json.loads(registry_path.read_text(encoding='utf-8'));expected=registry['files'];errors=structural(root,actual)
    if registry.get('approved') is not True:errors.append('Registry is an unapproved candidate')
    if release:
        if registry.get('release_approved') is not True:errors.append('Production release approval is absent')
        errors.extend('Release blocked: '+x['id'] for x in registry.get('release_blockers',[]) if not x.get('resolved',False))
    for name in sorted(set(actual)|set(expected)):
        if name not in expected:errors.append(name+': unregistered source surface');continue
        if name not in actual:errors.append(name+': removed file needs registry review');continue
        record=expected[name]
        if not record.get('review_reason') or record.get('classification') not in ('private_scoped','control_plane','shared_public','boundary','stateless','disabled_legacy'):
            errors.append(name+': missing explicit classification/review reason')
        if record.get('sha256')!=actual[name]['sha256']:errors.append(name+': source changed; review tenant predicates, keys, callbacks and files before updating this fingerprint')
        if record.get('surfaces')!=actual[name]['surfaces']:errors.append(name+': sensitive occurrence inventory changed')
    return errors,actual

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');parser.add_argument('--release',action='store_true');parser.add_argument('--propose',type=Path);parser.add_argument('--registry',type=Path,default=REGISTRY)
    args=parser.parse_args()
    if args.propose:
        if args.propose.resolve()==REGISTRY.resolve():raise ValueError('Candidate cannot replace reviewed registry')
        if args.propose.exists():raise ValueError('Candidate already exists')
        files=scan();candidate={'format':1,'approved':False,'files':{p:{**x,'classification':'UNREVIEWED','review_reason':''} for p,x in files.items()}}
        args.propose.parent.mkdir(parents=True,exist_ok=True);args.propose.write_text(json.dumps(candidate,indent=2)+'\n',encoding='utf-8');print('Unapproved candidate written; no reviewed entry changed.');return
    errors,files=check(registry_path=args.registry,release=args.release)
    if errors:
        print('\n'.join(errors));print('TENANT_SOURCE_GATE_FAIL issues='+str(len(errors)));sys.exit(1)
    counts=collections.Counter(s['kind'] for v in files.values() for s in v['surfaces'])
    print('TENANT_SOURCE_GATE_PASS files='+str(len(files))+' occurrences='+str(sum(counts.values()))+' '+json.dumps(counts,sort_keys=True))

if __name__=='__main__':main()
