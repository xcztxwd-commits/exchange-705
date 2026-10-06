# 三会话主线合并与线上测试环境发布记录

## 授权与边界

用户明确要求合并当前及两个指定会话的代码、合入主线并部署。后续明确声明线上没有真实用户、没有真实资金，均为测试数据，同时要求尽量保全数据。此事实允许显式 owner-authorized live-test 路线，不等同于独立双签生产批准。

只处理实际变化的数据库 `1090`（`exchange-705-mysql-1`，UUID `8787a95c-b994-11f1-8f07-768e28e78a27`）。main-api/admin/control 共用此库；demo 使用不同物理 MySQL 和 `exchange_demo`，不备份或迁移它，不做全实例 dump。mobile、pc、Redis、gateway 保持原运行版本。

## 已完成代码和主线

三个会话的应用代码已合并并提交于 `33c759a`，经 PR #1 合入 GitHub main，合并提交为 `9595e8c6ad9aff4ca10628abc1d6b9a84db91f3e`。原两个来源工作树及未提交改动未被重置或覆盖。

本次后续修改仅包括明确的测试 fixture 非空字段、17 个独立审查过的源码登记项，以及现有迁移工具的 owner live-test 收据、当前真实基线观察、单库全恢复 proof 复用和严格单行 0702 激活路径。旧 signed production 路线、固定批准策略、release_approved=false 及三个 release blockers 均保持不变。owner 路线不能用 fixture 标签授权业务库，不能把当前观察伪称历史 COMPLETE。

## 真实测试结果

- 已合并的控盘后端测试：176 项，175 通过、1 跳过；跳过的是缺少百万级配对快照的负载项。
- 真实独占 MySQL 5.7 应用/JDBC 控盘测试：42/42，覆盖租约过期、实际连接/查询中断、COMMIT 响应丢失、HTTP socket 响应丢失、未知请求键取消、晚到请求、重启、多租户隔离及断源。使用 fixture provider/repository/audit，不是完整生产鉴权/JPA/长时负载验收。
- 合并前端 Node 测试：admin 45/45、账户访问 6/6、PC 4/4；既有客户端 5/5 与 PC 场景重叠，不重复累计。Chrome UI 场景通过，API 为显式合成拦截。
- 本轮多租户迁移工具离线测试：218/218，其中 owner 路线 28/28；包含真实物理历史 witness 的严格文件、SQL、对象、来源和拒绝路径。
- 本轮真实独占 MySQL native02 契约：完整单库备份及独立恢复、0603 基线、精确 0701/0702 增量、实际源增量拒绝、已完成 full restore proof 复用（禁止第二次 dump/restore）、所有旧字段和 161 triggers 保全、单行 activation SQL 与重放拒绝均通过。startup 和 source review 是明确 mock，JAR 为 fixture epoch 资源，不能冒充真实应用或 `1090` 上线验收。
- `MinimalFixRegressionTest` 默认运行 46/48：两个严格 rowVersion 断言与既有异步推广消费者同时更新冲突。实际 SQL 证明第三方更新只清除 promotion_pending，资金/终态不变；相关生产代码与合并前基线相同。用既有 `activity.order-events.initial-delay-ms=86400000` 隔离该消费者后 48/48，未放宽断言或修改生产代码。H2 fixture 缺归档表的后台日志仍保留；不称推广消费者或完整生产验收。

本轮测试 fixture 修改前后，生产源码 549 个文件、target/classes 769 个文件及两个 JAR 的 SHA 完全一致；正式不可变候选包未被测试覆盖。

## 可复用本地命令

在原 `3d6b/705` 工作树运行；先阅读具体测试脚本的参数及 fixture 范围。

```powershell
python -m unittest discover -s scripts/multitenant -p 'test_*.py'
python scripts/market/run_control_recovery_mysql.py --help
Set-Location exchange-backend
mvn surefire:test -Dtest=MinimalFixRegressionTest -Dactivity.order-events.initial-delay-ms=86400000
```

后端其它定向 Maven、真实 MySQL 和 UI 的实际命令保存在 `reports/three-chat-20261007-control-01/`、`reports/combined-three-chat-20261007-frontend/` 及 `rollback/mainline-release-20261007-acceptance-plan/`，应复用原 fixture owner/full-container-ID 门禁，不要对生产直接执行 fixture 脚本。

## 镜像与部署准备

候选不可变镜像已在本地实际重建检查六次，并上传线上且核验完整 archive SHA `012b0691ad37385b81a53d60bc3111b89c2a5cef7a56171a122a6e3341eb1ed9`。线上只加载镜像，不等于发布容器。

- backend：`sha256:7b0b82e2199fdece525c60b4b7634098096de2446273f89296e8428b30e0b6c6`；完整 JAR SHA `6ca2cb3dab67993e15b72d7ed156d1700dbdd921e284e04265cc65374b40f286`。
- admin：`sha256:dfb9d1fc03d2a3d0522d207dfe114555e13b308e198ae63cb74deb82081ba2d8`。
- control：`sha256:56b2f12c98b6e9b28d33aad259d2b3450a9a07e8851cdf70301b583a19e78db4`。
- 完整热修回滚 backend：`sha256:715dab04585eea446092dc412a885332044928d137fe0c6a90dfefa76105a39d`，JAR `8907422fc03d85ab7a69b7b9816564466aa853e8fa974c1804e95fb40d1b321b`。不能直接重建原 f30 镜像，当前单类热修会丢失。

原线上 Compose 候选与实际镜像/healthcheck 有差异；已另存按实际环境、挂载、网络、entrypoint、command、restart 核对的独立三服务 release 和完整热修 rollback Compose，不覆盖原候选。只更换 main-api/admin/control，使用 `--no-deps`，不使用 remove-orphans。新静态候选没有 version.json，健康检查明确改查 index.html，不以 SPA fallback 伪装版本文件。

## 结构差异解决与待执行

首次只读结构 preflight 对固定公共 0603 fixture 快照发现 32 个对象差异（30 张表及 trigger/routine SQL_MODE），诚实记录 BLOCKED。未停业务容器，未迁移、未激活，未备份其它库。六个额外索引有现有项目脚本来源，必须保留；不能为了对齐 fixture 快照删除索引、隔离触发器或改变现有字符集、默认值、SQL_MODE。

进一步发现此前同一物理 `1090` 的真实冻结证据和独立全恢复 proof，可用于审查实际 legacy 结构；它不能被公共 fixture 快照替代。已逐项证明：原 112 张表中 108 张原定义一致，四张表仅有原 0602/0603 增量，新增一张表严格对应原 0601；全部 161 triggers、routine、参数、事件、库默认值原定义保持。三份同物理执行收据及原完整备份/恢复 proof 均严格 hash 绑定。工具新增精确 native_witness 分支，独立参考库明确仅含原 112 CREATE 和已执行的三个增量，不伪称含 stored objects；默认公共 fixture 和双签路线不变。只允许当前全部 raw state 与完整独立恢复严格相等；只忽略结构参考中最终 table-option 的活动 AUTO_INCREMENT 计数，不忽略实际数据计数。

在上述事实闭环后，才执行：只停 main-api、实际 drain 全部 MySQL sessions、全局 readonly、仅 `1090` 一致 full dump 和不同 UUID/datadir 的独立全恢复、受控 0701/0702、SELECT-only 真实候选稳定启动取证、再次 stop/drain、0702 单行激活、恢复正常配置并切换三个服务、真实只读 smoke 和制品核验。当前文档不声称这些待执行步骤已经上线通过。

完整执行/回滚参数和私有证据位于 `rollback/mainline-release-20261007-current-audit/`；不得把私有配置、密码、token 或备份提交 Git。回滚须使用完整热修镜像并保留 additive DDL、已提交历史及取消凭证，不能把旧全库备份灌回覆盖新提交数据。真正包含真实用户/资金的生产发布仍需原独立双签和正式 release 门禁。
