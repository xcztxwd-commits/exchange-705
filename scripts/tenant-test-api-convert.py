"""Migrate legacy test repository calls to the explicit tenant API; does not weaken assertions."""
import importlib.util, json, re
from pathlib import Path
sp=importlib.util.spec_from_file_location('source',Path(__file__).with_name('tenant-source-convert.py'))
m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m)
mapping=json.loads((m.ROOT/'reports/multitenant/source-migration.json').read_text(encoding='utf8'))['repositories']
basic={'findAll':'findAllByTenantId','findById':'findByTenantIdAndId','existsById':'existsByTenantIdAndId','count':'countByTenantId','findAllById':'findAllByTenantIdAndIdIn','deleteById':'deleteByTenantIdAndId','deleteAllById':'deleteAllByTenantIdAndIdIn','deleteAll':'deleteAllByTenantId'}
for p in (m.ROOT/'exchange-backend/src/test/java').rglob('*.java'):
    if '/tenant/' in p.as_posix() or '/control/' in p.as_posix():continue
    original=p.read_text(encoding='utf-8-sig');s=original
    for repo,methods in mapping.items():
        variables=set(re.findall(r'\b'+repo+r'\s+(\w+)',s))
        for variable in variables:
            for old,new in dict(basic,**methods).items():
                pattern=r'\b'+variable+r'(\s*\))?\s*\.\s*'+old+r'\s*\(\s*'
                def replace(match):
                    tail=s[match.end():];depth=1;i=0
                    while i<len(tail) and depth:
                        depth+=(tail[i]=='(')-(tail[i]==')');i+=1
                    args=tail[:i-1]
                    tenant='org.mockito.ArgumentMatchers.eq(1L)' if re.search(r'\b(?:eq|any|anyLong|anyString|anyList|isNull|argThat|same)\s*\(',args) else '1L'
                    return variable+(match[1] or '')+'.'+new+'('+tenant+('' if not args.strip() else ', ')
                s=re.sub(pattern,replace,s)
    if s!=original:m.write(p,s)
print('Updated legacy test API calls. Runtime expectations still require tenant fixtures; no assertions removed.')
