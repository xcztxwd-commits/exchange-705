# 本机净权益历史发布与回退

工作目录固定 `C:/workspace/fx/705`。仅 backend/mobile；不操作其他应用服务。

## 已确认估值口径

- FUND/CONTRACT/OPTION 可用及冻结余额，加 OPEN 合约浮盈亏、已生成且未发放的 PENDING 理财收益，减实际放款未偿本金、已产生未付利息、记录的已生效未付 overdueFee、新 OPEN 合约冻结未扣手续费。
- 期限订单按用户确认的**本金成本**估值；本金已在 frozen 中，新增浮盈亏为真实 0。这不是实时公允价值；不使用方向型 payout 或预设盈亏。
- 利息复用原提前还款的整天、freeDays、日利率与舍入规则，不提前确认全期限利息。未放款 PENDING/SIGNED/REJECTED 不扣本金；状态异常标记 INCOMPLETE。COMPLETED 且有清偿时间时解除债务；没有部分还款字段，不推造部分还款。
- overdueFee 仅使用持久化的已确认未付金额；NULL 表示没有记录，不新增计费规则。原 earlyRepayment 只收本金和利息，COMPLETED 不能单独证明罚息已付：实际 repaymentAmount 等于本金加已付利息时，仍保留已记录罚息；实际还款额也覆盖该罚息时才解除。证据不匹配则标记 INCOMPLETE，不能重复扣已付费用或抹掉未付费用。
- 已生成 PENDING 理财收益没有拒绝审批路径，发放仅转入现金；计入应收。理财本金、合约保证金、提现冻结不重复加减。已付收益不再重复计入。
- 行情使用已有服务端执行价快照（并非凭空命名为 mark price），沿用控制价格与时效；非 USD/USDT 使用订单币种/来源对应有效换汇快照。未知/过期/未来报价、数据异常以 NULL 净权益及原因保留。

## 数据及开关

迁移 `V001__net_equity_history` 创建八张表：`asset_history_1m/1h/4h/1d`、`asset_history_baseline`、`asset_history_job_state`、`asset_history_quote_batch`、`asset_history_migration`。DECIMAL(32,16)，口径 `net_equity_v1`，UTC 固定桶。旧 `asset_snapshot` 保持 wallet_balance_v1，不复制为净权益；新旧基准隔离。原始数据均不自动删除。

`asset.history.equity.read-enabled` / 环境变量 `ASSET_HISTORY_EQUITY_READ_ENABLED`：默认 false，false 为明确标记的旧余额兼容接口，true 为 schemaVersion=2 净权益接口。

`asset.history.equity.collect-enabled` / `ASSET_HISTORY_EQUITY_COLLECT_ENABLED`：默认 false。true 同时禁用旧快照生产者，开启所有现存用户分钟采集及归集。读取开关可以独立回退，不删除新记录。

分钟 UTC `5 * * * * *`；小时 `15 2 * * * *`；4 小时 `30 4 0/4 * * *`；日 `45 8 0 * * *`；恢复 `0 */5 * * * *`。分钟提交后独立归集通道更新当前 DRAFT。三级封闭检查下级水位，事务提交游标和结果。每批 100 用户；单轮封闭最多 48 批，积压在后续恢复任务继续。不补造停机分钟。不缓存用户净权益，因此无跨版本缓存拼接。

## 验证及启用

```powershell
Set-Location -LiteralPath 'C:/workspace/fx/705'
& ./scripts/asset-equity/Test-MySql.ps1
node ./exchange-frontend/tests/assetPixelWindow.test.mjs
node ./exchange-frontend/tests/assetPixelScrub.test.mjs
node ./exchange-frontend/tests/assetEquityHistory.test.mjs
docker compose -f ./compose.yaml build backend mobile
if ($LASTEXITCODE) { throw 'Build failed' }
& ./scripts/asset-equity/Migrate.ps1 -Mode Verify
& ./scripts/asset-equity/Migrate.ps1 -Mode Apply -BackupPath 'C:/workspace/fx/705/rollback/asset-equity-20260927-184151'
& ./scripts/asset-equity/Migrate.ps1 -Mode Status
# 仅全部发布门槛通过后执行：
docker compose -f ./compose.yaml -f ./scripts/asset-equity/enable.local.yaml up -d --no-deps backend mobile
if ($LASTEXITCODE) { throw 'Start failed' }
& ./scripts/asset-equity/Smoke.ps1 -SessionFile 'C:/workspace/fx/705/rollback/asset-equity-20260927-184151/smoke-sessions.clixml'
```

后续本机启动应继续包含 enable.local.yaml，否则回到默认关闭。Smoke 仅用经授权账户正常注册/登录取得的会话，不签发伪造令牌；会话文件使用当前 Windows 用户 DPAPI 加密。脚本不新建账户、不下单、不调账。

MySQL 测试使用独立随机命名、loopback 端口、tmpfs 数据的 mysql:5.7 容器；完成后只清理该测试容器。不在现有数据库注入测试资金或订单。Docker 构建中该测试按环境条件跳过，必须单独运行脚本，不能以跳过代替通过。

## 非破坏回退

```powershell
docker compose -f ./compose.yaml -f ./scripts/asset-equity/rollback.local.yaml up -d --no-deps backend
if ($LASTEXITCODE) { throw 'Feature rollback failed' }
```

以上保留当前无 prune 的镜像，关闭新采集/新读取，恢复旧余额兼容读取/采集；不会删除新表、新分钟或旧历史。也可以只关闭读取、保持新采集。数据库不回删、不导入资金表。旧镜像标签 `equity-before-20260927-184151` 仅作存档，它包含旧 370 天 prune，**不得直接启动**。

源码备份、原镜像标识、旧快照 SQL 与 SHA256 位于 `C:/workspace/fx/705/rollback/asset-equity-20260927-184151`。恢复源码前逐文件比较当前内容，不能覆盖期间新增的他人修改；不要运行 reset/clean/stash。旧 SQL 是保全证据，不是自动整库恢复入口。

本目录说明的是实际实现和操作入口，是否迁移/部署成功以本次运行报告为准。
