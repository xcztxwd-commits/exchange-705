"""One-time, reviewed source migration; never touches a database.
Backs up each original once and writes atomically. Keep for migration evidence.
"""
from pathlib import Path
import re, shutil, json

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'exchange-backend/src/main/java/com/gtcfesk/exchange'
BACKUP = ROOT / 'rollback/multitenant-20260929/source'
CONTEXT = 'com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()'
SHARED = {'AdminMenu', 'MenuAction'}
def write(path, text):
    if path.exists() and path.read_text(encoding='utf-8-sig') == text: return
    backup = BACKUP / path.relative_to(ROOT)
    if path.exists() and not backup.exists():
        backup.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(path, backup)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + '.tenant-tmp')
    temp.write_text(text, encoding='utf-8', newline='\n'); temp.replace(path)

def run():
    repos, changed, entities = {}, {}, []
    original_files = {p: p.read_text(encoding='utf-8-sig') for p in SRC.rglob('*.java') if '/tenant/' not in p.as_posix() and '/control/' not in p.as_posix()}
    for p, text in original_files.items():
        if '@Entity' in text and re.search(r'public class (\w+)', text):
            entity = re.search(r'public class (\w+)', text)[1]
            if entity in SHARED: continue
            entities.append(entity)
            text = re.sub(r'(public class '+entity+r')\s*\{', r'\1 extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {', text)
            # Versioned MySQL migrations own the composite unique constraints.
            text = re.sub(r'\bunique\s*=\s*true\s*,?\s*', '', text)
            text = re.sub(r',\s*\)', ')', text)
            text = re.sub(r'columnNames\s*=\s*\{', 'columnNames={"tenant_id", ', text)
            text = re.sub(r'columnNames\s*=\s*"([^"\n]+)"', r'columnNames={"tenant_id", "\1"}', text)
            if entity == 'UserAccount':
                text = re.sub(r'@GeneratedValue\(generator = "custom-user-id-generator"\)\s*@org.hibernate.annotations.GenericGenerator\([\s\S]*?\)\s*', '@GeneratedValue(strategy = GenerationType.IDENTITY)\n    ', text)
            changed[p] = text
        match = re.search(r'public interface (\w+) extends JpaRepository<\s*(\w+),\s*(\w+)\s*>', text)
        if not match or match[2] in SHARED: continue
        name, entity, id_type = match.groups()
        methods = {}
        # Annotated queries are handled explicitly below, never relying on ORM filters.
        query_spans = []
        for q in re.finditer(r'@(?:org\.springframework\.data\.jpa\.repository\.)?Query\(((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)\)', text):
            tail = text[q.end():]
            method = re.search(r'\b(\w+)\s*\(', tail)
            if method: query_spans.append(method[1])
        for method in re.finditer(r'\b((?:find\w*?|count|exists|delete)By\w+)\s*\(([^;{}]*)\)\s*;', text):
            old, params = method.groups()
            if old in query_spans: continue
            prefix, rest = old.split('By', 1)
            new = prefix + 'ByTenantId' + (('And' + rest) if rest and not rest.startswith('OrderBy') else rest)
            methods[old] = new
        for old, new in methods.items():
            text = re.sub(r'\b'+old+r'\(([^;{}]*)\)\s*;', lambda m: new+'(Long tenantId'+(', '+m[1] if m[1].strip() else '')+');', text)
            text = re.sub(r'(?<=return )'+old+r'\(', new+'('+CONTEXT+', ', text)
        def scope_query(m):
            query = ''.join(re.findall(r'"((?:[^"\\]|\\.)*)"', m[1]))
            alias = re.search(r'\b(?:from|update)\s+\w+\s+(\w+)', query, re.I)
            if not alias: raise ValueError((p, query))
            alias = alias[1]
            parameter = (':#{' if re.search(r':\w', query) else '?#{') + 'T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()}'
            boundary = re.search(r'\b(order\s+by|group\s+by)\b', query, re.I)
            end = boundary.start() if boundary else len(query)
            head, order = query[:end].rstrip(), query[end:]
            where = re.search(r'\bwhere\b', head, re.I)
            if where:
                head = head[:where.start()] + 'WHERE '+alias+'.tenantId = '+parameter+' AND ('+head[where.end():].strip()+')'
            else: head += ' WHERE '+alias+'.tenantId = '+parameter
            return '@org.springframework.data.jpa.repository.Query("'+head+(' '+order if order else '')+'")'
        text = re.sub(r'@(?:org\.springframework\.data\.jpa\.repository\.)?Query\(((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)\)', scope_query, text)
        text = re.sub(r'extends JpaRepository<\s*'+entity+r',\s*'+id_type+r'\s*>(?:\s*,\s*(?:org\.springframework\.data\.jpa\.repository\.)?JpaSpecificationExecutor<'+entity+r'>)?', 'extends com.gtcfesk.exchange.tenant.TenantRepository<'+entity+', '+id_type+'>', text)
        repos[name] = methods
        changed[p] = text
    basic = {'findAll':'findAllByTenantId', 'findById':'findByTenantIdAndId', 'existsById':'existsByTenantIdAndId', 'count':'countByTenantId', 'findAllById':'findAllByTenantIdAndIdIn', 'deleteById':'deleteByTenantIdAndId', 'deleteAllById':'deleteAllByTenantIdAndIdIn', 'deleteAll':'deleteAllByTenantId'}
    for p, original in original_files.items():
        if p.name in {r+'.java' for r in repos}: continue
        text = changed.get(p, original)
        variables = {}
        for repo, methods in repos.items():
            for match in re.finditer(r'\b'+repo+r'\s+(\w+)', text): variables[match[1]] = dict(basic, **methods)
        for variable, methods in variables.items():
            for old, new in methods.items():
                pattern = r'\b'+variable+r'\s*\.\s*'+old+r'\s*\(\s*'
                text = re.sub(pattern, lambda m: variable+'.'+new+'('+CONTEXT+('' if text[m.end():].startswith(')') else ', '), text)
        if text != original: changed[p] = text
    for p, text in changed.items(): write(p, text)
    evidence = {'entities':sorted(entities), 'repositories':repos, 'changedFiles':[str(p.relative_to(ROOT)) for p in changed]}
    write(ROOT/'reports/multitenant/source-migration.json', json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    print('Migrated',len(entities),'entities,',len(repos),'repositories,',len(changed),'files; database unchanged.')
if __name__ == '__main__': run()
