# 0701/0702 正式迁移包装与本地证据（2026-10-07）

## 范围与结论

本轮在既有 `C:/Users/徐乾妖/.codex/worktrees/3d6b/705` 整合控盘恢复版本的正式结构契约。没有修改旧迁移、删除触发器、清空行情、手改任务状态，未修改生产数据库或签名审批。

正式 DDL 仅涉及三张表：`market_control_command` 两个 retry 列、`market_control_flow` 三个历史最终化列，以及 `tenant_schema_version` 的一条新 inactive 收据。没有对所有业务数据库做备份/迁移。本轮 113 表保全和恢复检查仅发生在新建、独占、无网络的本地一个 schema 及其独立恢复 schema，不是生产全实例或其他业务库的数据备份。

**已完成：双尾登记、打包 epoch、严格阶段合同、离线守卫测试，以及完整无数据 schema/实际新列字典/checksum。** 原生 MySQL 的最终状态另见下文。**未完成：生产审批、受控业务库 apply/resume、生产备份恢复、应用完整上线验收。** 本文件不是发布批准，不解除现有来源审定或运行配置漂移阻断。

## 实际最小代码改动

- `table_manifest.json` 唯一迁移序列由 28 条扩展为 30 条，末尾精确为 0701、0702，epoch=2026100702。
- `META-INF/mt705-schema-epoch` 同步 0702；没有改写已发布的 0603 或更早 SQL/checksum。
- 未发布的 0702 末尾增加唯一收据：version=0702，minimum_application_epoch=0603，business_activation_ready=0，applied_at 为实际执行时间。新增列保留 additive 应用回滚兼容；不伪造已批准业务激活。
- `controlled_migration.py` 只允许已验证 0603 前缀之后的精确 `[0701,0702]` 双尾。缺一条、倒序、其他文件名、额外未来尾、错误前驱都拒绝。0701 完成时要求 0702 收据仍不存在；0702 完成时要求准确的 inactive/minimum=0603 收据。resume 在真实恢复状态相等之后再次核对已完成阶段的收据条件。
- 原字段保全仍覆盖租户、取消凭证、任务、会话、资金和所有旧版本回执；只排除这一条计划内新增的 0702 收据。没有放宽未知 DDL、原始状态变化或不确定阶段的恢复门禁。
- `local_test_migration.py` 仅增加严格的本地 local702 namespace 与双尾 baseline 切分；保留真正 Docker owner、独立卷、资源额度、read_only、排空队列及来源指纹约束。该 unsigned 本地路径不能代替业务库独立签名。
- 新增 `test_control0702_migration_contract.py` 与 `verify_control_recovery_package.py`。后者固定既有 0603 无数据结构 SHA，专属实例、仅原生 SQL、独立单 schema 恢复、完整 ID/name/owner 清理；不得用于业务库。

## 实际验证结果

### 离线合同与公开结构

- `scripts/multitenant/test_*.py`：**198/198 PASS**，包含新增 7 个 0702 合同测试；不是 MySQL 或生产验收。
- `build_manifest.py`：PASS，版本唯一有序、manifest/packaged epoch 一致、历史迁移字节未改。
- `check_snapshot.py --self-test`：4 项 PASS。
- `check_snapshot.py`：PASS，实际 113 表、1338 列、452 索引、188 外键、161 触发器、1 过程；没有视图/事件。
- 原登记但无审定 CREATE 的 `tenant_migration_history` 仍明确列为缺失，不伪造第 114 张实际表。

证据：`reports/control0702-offline-20261007-final.log`、`reports/control0702-schema-20261007-03/` 及最终原生检查目录。公开结构无行数据、迁移收据、源账户 DEFINER 或 fixture 身份。

### 原生 MySQL 5.7：保留失败，不冒充一次全绿

1. `control0702-schema-20261007-01`：逐对象 Docker 运输耗时过高，明确中断为 `INTERRUPTED_NOT_PASS`。阶段备份保留，仅清理本轮完整 ID/owner 对应容器；不是成功验收。
2. `control0702-schema-20261007-02`：0701 后独立完整单 schema 恢复通过，但真实 MySQL 重启后的严格原始状态相等检查失败。没有修改受控恢复门禁来掩盖失败。
3. `control0702-schema-20261007-03`：保存重启前后完整定义/全列行指纹，确认空的 `user_account` 在 MySQL 5.7 重启时重算 AUTO_INCREMENT；数据、旧版本行、触发器/过程及其余定义未变。该轮**严格 raw-state 恢复仍明确 BLOCKED**，不能称为受控 resume 成功。只完成独立稳定结构捕获、实际新列字典、113 表零行导入和规范化 DDL 往返。
4. 最终独占夹具使用合法的合成 tenant 和 user_account(id=7000000) 锚点，遵守正式列/FK/trigger，用真实最大 ID 保留审定 7000001 floor，不 ALTER 计数器，不忽略差异。最终原生结果将在本文件末尾单独登记。

空表反例属于原 MySQL 5.7 计数器行为，不是忽略差异的授权。真正 apply/resume 仍要求最新原始 schema/data 与已签名 ledger 相等；如果变化必须保全并重新审定前向方案，不能通过“规范化”绕过门禁。

## 可复用命令

在同一工作树执行；证据目录必须全新。原 0603 无数据结构 SHA-256 固定为 `3d4fae2f121413c73ad809a0a23654db99121a961a67f1242c3b0ec0ddf1506b`。本轮备份路径属于忽略的私有目录，不上传 GitHub。

```powershell
$root = 'C:/Users/徐乾妖/.codex/worktrees/3d6b/705'
python -B -m unittest discover -s "$root/scripts/multitenant" -p 'test_*.py'
python -B "$root/scripts/multitenant/build_manifest.py"
python -B "$root/scripts/database/check_snapshot.py" --self-test
python -B "$root/scripts/database/check_snapshot.py"
python -B "$root/scripts/database/verify_control_recovery_package.py" `
  --baseline "$root/rollback/merge-20261007/release-contract-baseline/docs/database/schema.sql" `
  --output "$root/reports/control0702-package-new-run"
```

默认只输出本轮专属证据。`--write-snapshot` 仅允许公开字典仍处于 0603 且原生验证全部通过的第一次同步；已有 0702 字典会拒绝重复追加。复跑用于证明实际结构，不覆盖既有证据或公开文件。

## 未实施与硬阻断

- 未生成或伪造任何生产 operator/approver 签名；未更新 source_isolation_registry 的 approved/release_approved 指纹。
- 未运行正式生产 `plan/verify-backup/apply/resume/package-check` 完整批准链。离线对恢复条件的覆盖、原生阶段备份或实际 restart 都不能替代该签名/ledger 链。
- 没有生产 full AppContext/鉴权/JPA、真实生产单库备份恢复、持续负载 p99、健康容器切换或回滚演练验收。当前 runtime/Compose 候选漂移须由合并发布负责人协调，不能直接覆盖现有候选。
- 最低应用 epoch=0603 仅表示结构 additive 兼容。旧 worker 不理解新版 RESTORE 队列；应用回滚前须通过授权 API 排空或取消新协议 pending，保留 tombstone/历史/新列，不能强行回切或 DROP COLUMN。
- 用户限定仅比较变化的数据库。后续正式迁移必须绑定实际有变化的那一个目标库/三张表的差异与对应恢复证据；不得自动扫描、备份或迁移所有数据库。

## 最终原生结果

最终证据：`C:/Users/徐乾妖/.codex/worktrees/3d6b/705/reports/control0702-schema-20261007-04/result.json`，实际 exitCode=0、result=PASS。两个独占实例均按完整 ID/name/owner 核验后清理。

- 合法合成 floor 锚点成立；重启前后**原始** schema 对象 SHA、全部原列与新列行指纹严格相等，`strictRawStateRestart=PASS`。没有忽略 AUTO_INCREMENT 或改写受控门禁。
- 0701 完整单 schema 备份在另一独立实例实际恢复，原始 schema/data 完全相等；此备份只有本轮合成数据，不是生产备份。
- 0701/0702 只改变两张目标表定义；原 161 个触发器、过程、其余表定义及全部原列事实、0603 收据保持，0702 收据 inactive 且 minimum=0603。
- 两次源无数据导出一致；另一专属实例的新空库实际导入，113 张表逐表零行；规范化 DDL 逐字节往返相等。normalized SHA-256=`9174d3fe12bf1b7f8e7e8f839ebfb75c8d1397f1e6797576f47ce7b833e2f69c`，与已生成公开快照一致。
- 0701 原始 SQL SHA-256=`3b91805f8750ba5f310a192e6c36d6b51dd04c7cd131cb38fa5de208e07c23f4`；0702=`ad96d9a17ac8aaac7fd6995c1ef35bb32b3e97f579c56e1b96039e28d81fa087`。
- `productionApproval=false`、`productionOrApplicationAcceptance=false`。**严格重启状态检查不是签名受控 apply/resume 已完成**，也不改变 02/03 空表反例的 FAIL/BLOCKED 记录。

最终离线合同再跑 198/198 PASS，公开快照检查再次 PASS。代码/迁移资源已冻结并交给合并负责人提交与打包；本任务没有运行 Maven、提交 Git 或发布生产。
