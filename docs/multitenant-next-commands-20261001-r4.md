# 多租户后续命令：2026-10-01，r4限定验收

配套进度：`C:\workspace\fx\705\docs\multitenant-next-progress-20261001-r4.md`。本页不授权生产操作、不复用旧浏览器收据、不重跑覆盖本轮候选或日志。缓存方法C12已经在冻结r3源上真实通过，但需求矩阵C12整条迁移发布链没有通过。

## 1. 只读核验本轮输出

```powershell
$ErrorActionPreference='Stop'
$Run='C:\workspace\fx\new\mt705-next-20261001-3d15deb89671'
$Python='C:\Users\徐乾妖\AppData\Local\Programs\Python\Python314\python.exe'
$env:PYTHONIOENCODING='utf-8'
$Expected=@{
 (Join-Path $Run 'baseline-source-manifest.json')='cb52c76029b357c0b2fceddc28e1da38b1b5167fe6553f49311cc4c005cb08ed'
 (Join-Path $Run 'candidate-r3c\exchange-backend\target\exchange-backend-0.0.1-SNAPSHOT.jar')='c6bfcb1c73c74d5686887b504c35d4e222292e264747e5d96f489572a6ff5aed'
 'C:\workspace\fx\705\docs\multitenant-requirement-matrix.json'='89e0b9af48cb64f0895c54ba1eaa33c168453a2c4a3d3466b9ddbddd5b050c6f'
}
foreach ($Entry in $Expected.GetEnumerator()) {
 if ((Get-FileHash -LiteralPath $Entry.Key -Algorithm SHA256).Hash -ne $Entry.Value) { throw "Evidence changed: $($Entry.Key)" }
}
Get-Content -LiteralPath (Join-Path $Run 'checkpoint-r4.json') -Raw -Encoding utf8
Get-Content -LiteralPath (Join-Path $Run 'checkpoint-r4-full-skip-dispositions.json') -Raw -Encoding utf8
Get-Content -LiteralPath (Join-Path $Run 'final-resource-observation.json') -Raw -Encoding utf8
git -C 'C:\workspace\fx\705' rev-parse HEAD
git -C 'C:\workspace\fx\705' status --short
Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -in @(3306,33315,33417,33418,33419) } | Select-Object LocalAddress,LocalPort,OwningProcess
```

UTF-8清单一律显式读取，不能用本机cp936默认编码得出中文路径“缺失”。现有candidate-r3c只供只读复核，不在其target重建。

## 2. 最小Python增量复测（另建日志，不覆盖本轮）

```powershell
$Next='C:\workspace\fx\new\mt705-guard-check-'+[Guid]::NewGuid().ToString('N')
New-Item -ItemType Directory -Path $Next -ErrorAction Stop | Out-Null
Push-Location 'C:\workspace\fx\705\scripts\multitenant'
try {
 & $Python -B -m unittest -v test_retention_archive test_controlled_migration *> (Join-Path $Next 'python-unit.log')
 $Code=$LASTEXITCODE
 Get-Content -LiteralPath (Join-Path $Next 'python-unit.log') -Tail 10
 if ($Code -ne 0) { throw "Portable tests failed: $Code" }
} finally { Pop-Location }
```

这是便携单元检查，不能自动获得MySQL迁移、共享source gate、完整Java或发布验收。进一步运行夹具前应对新证据目录设置当前用户受限ACL并先保存身份/恢复证明；这里不复制认证值。

## 3. 下一实现顺序

1. 逐条读取余24个真实case；先处理BalancedControlPlan及AssetEquity的当前tenant schema和DedicatedMysqlFixture绑定，再处理MarketIsolation/ContractClose的真实HTTP/任务scope与资金不变量。ReceivedIndex/SourceCandles需要新受限性能库、明确数据量及D03预算，真实外部Catalog/Exchange条件独立记录。不能猜旧root口令或只打开环境开关。
2. 补正式孤儿/文件批准范围、旧凭据隔离和不确定DDL人工核对的前向处置；先审查现有所有caller及边界。旧DDL、未知提交状态、当前增量不得重放/覆盖。
3. 审查共享工作区并发代码和本轮两份Python改动，保存原字节/CAS原子写。registry只在真实逐项审查后更新；禁止通过重算hash或清除blocker消除门禁。
4. 新建唯一候选、target、XML/日志与loopback夹具。所有写库前取得最新全量独立恢复证明，并验证实际UUID/datadir/PID/端口。旧datadir和旧证明保留，不初始化已有目录，不复用3306/33315。
5. 新代码如影响缓存/鉴权/前端，先准备CUA及真实Vue/Auth/MySQL/Redis链，再启动九类必测。动态API与challenge、收据路径重新绑定；十项在同一六分钟活跃窗口完成。不得复制这次PASS到新服务，也不能允许CDP截断却声明完整抓包。
6. 全套verify、未闭环真实业务链、D03四档混合容量和长稳、运营/四端验收逐项留证。每次独立保留失败及skip；限定通过不等于项目完成。

## 4. 当前资源与证据不可直接重跑

本轮MySQL、Redis与Vite已停止，原datadir/全部恢复副本/容器/volume保留。不要直接执行 `C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\prepare_this_run.py`、`prepare_fixtures.py`、任何 `backup_*`、`verify_latest_and_stop_*` 或 `run-mandatory-r3c.ps1`：它们绑定旧PID/路径/输出，部分入口会创建库或改变维护状态，重跑不是只读验证。

`C:\workspace\fx\new\mt705-next-20261001-3d15deb89671\wire_read_adapter.py`只用于本轮owned夹具读取。它避免原生CLI不完整stdout，但根因仍未确认，不是生产连接器补丁。新会话须重新绑定新物理身份，不能删校验或重用旧PID。

当前33315由其他写者监听，3306原进程未改动。端口盘点不能作为kill许可。Docker daemon、用户标签页、生产、真实付款/短信/邮件、留存删除均不操作。

当前:多租户后续命令 / 本轮C12已通过，尚余24项未执行和整链缺口 / 先核验r4证据，再改造当前租户夹具与审查新候选
