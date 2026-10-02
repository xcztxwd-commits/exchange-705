"""Source-only explicit tenant predicates for the audited wallet/history native SQL paths.
This is a reviewable one-time migration, not a runtime SQL interceptor.
"""
import importlib.util, re
from pathlib import Path
sp=importlib.util.spec_from_file_location('source',Path(__file__).with_name('tenant-source-convert.py'))
m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m)
T='com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()'
files=['user/AssetHistoryService.java','user/AssetEquityHistoryService.java','user/AssetEquityStore.java',
       'user/AssetEquityJobs.java','user/EquityValuationService.java','user/ManualOrderHistory.java','user/AssetHistoryCache.java',
       'trade/ManualOrderService.java']
for name in files:
    path=m.SRC/name; text=path.read_text(encoding='utf8')
    # All these audited queries are single-table or single-table subqueries, no ambiguous tenant alias.
    text=re.sub(r'(?i)\bwhere ',lambda x:x[0]+'tenant_id="+'+T+'+" and ',text)
    if name.endswith('EquityValuationService.java'):
        text=text.replace('String users=" user_id', 'String users=" tenant_id="+'+T+'+" and user_id')
    # INSERT columns and VALUE rows are explicit in each source call; no hidden runtime transformation.
    text=re.sub(r'(?i)(insert(?: ignore)? into [a-z0-9_]+\s*\()', r'\1tenant_id,',text)
    text=re.sub(r'(?i)((?<!\.)\bvalues\s*\()',lambda x:x[0]+'"+'+T+'+",',text)
    # VALUES(column) in ON DUPLICATE KEY is a function, not a new VALUES row.
    text=re.sub(r'(?i)values\("\+'+re.escape(T)+r'\+",([a-z_]+)\)',r'values(\1)',text)
    text=text.replace('"(user_id,basis_version,bucket_start,', '"(tenant_id,user_id,basis_version,bucket_start,')
    text=text.replace('"select user_id,basis_version,observed_at,', '"select tenant_id,user_id,basis_version,observed_at,')
    if name.endswith('AssetHistoryService.java'):
        text=text.replace('select user_id,?,', 'select tenant_id,user_id,?,').replace('group by user_id','group by tenant_id,user_id')
        text=text.replace('@Transactional\n    public void capture()', 'public void capture()')
        text=text.replace('    // Same accounting boundary', '    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.tenant.TenantJobRunner tenantJobs;\n    // Same accounting boundary')
        text=text.replace('        jdbc.update("insert into asset_snapshot', '        tenantJobs.each(tenant -> captureTenant());\n    }\n    private void captureTenant() {\n        jdbc.update("insert into asset_snapshot')
    if name.endswith('AssetEquityStore.java'):
        text=text.replace('String name="equity_v1_"+task', 'String name="tenant_"+'+T+'+"_equity_v1_"+task')
    if name.endswith('AssetEquityJobs.java'):
        text=text.replace('    private final AssetEquityStore store;', '    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.tenant.TenantJobRunner tenantJobs;\n    private final AssetEquityStore store;')
        text=text.replace('try{work.run();}', 'try{tenantJobs.each(tenant -> work.run());}')
        text=text.replace("select is_free_lock('equity_v1_capture')", "select is_free_lock('tenant_\"+"+T+"+\"_equity_v1_capture')")
    if name.endswith('AssetHistoryCache.java'):
        text=text.replace('namespace+','namespace+":tenant:"+'+T+'+')
    if name.endswith('ManualOrderService.java'):
        text=text.replace('String hash=hash(r,operator);','String hash=hash(r,operator);')
        text=text.replace('previews.get(', 'previews.get(') # Preview ownership is separately validated below.
        text=text.replace('    private void lock(JdbcTemplate db,List<String> locks,String name) {', '    private void lock(JdbcTemplate db,List<String> locks,String name) {\n        name="tenant_"+'+T+'+"_"+name;')
    m.write(path,text)
print('Scoped',len(files),'native SQL source files; review all generated predicates before deployment.')
