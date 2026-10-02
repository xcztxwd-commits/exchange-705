# 旧测试数据孤儿精确隔离与恢复

## 执行边界

用户已明确授权修改/删除这些测试记录。本轮仍遵照实施顺序：先完整备份及独立恢复、逐行归档、金额/数量核对、隔离实例演练，**实际 `exchange-705-mysql-1 / 1090` 尚未删除或修改任何记录**。不全库重置，不补造用户，不关闭外键。

`scripts/multitenant/orphan_quarantine.py` 默认只读；`--apply` 目前只接受 `mt705-20260929-test-mysql` 标签实例中的 `mt705_*` 库。正式目标写入口故意未开放，必须等整体发布门禁、停写和 root 确认后再处理。此工具只适用于迁租户之前的旧 schema，发现已有 tenant_id 即拒绝。

## 唯一允许处理的六类

`asset_account`、`asset_snapshot`、`financial_yield_record`、`transfer_record`、`user_digital_address`、`user_menu`。

条件统一为：行 `user_id IS NOT NULL` 且 `NOT EXISTS (SELECT 1 FROM user_account WHERE id=user_id)`。表名硬编码白名单；其它关联孤儿不扩大处理。用户父行恢复、行内容变化或归档与目标不符即拒绝旧计划。NULL user_id 不顺带删除。

每行归档包括主键、父用户键、所有列类型、每列原始二进制表示（HEX）和整行 SHA-256；不会把 DECIMAL 转成浮点。金额按 available/frozen、快照 total、日收益/累计收益和 transfer amount 分别记录；已有币种列才分币种，否则明确 `UNSPECIFIED_UNIT`。快照跨时间求和、累计收益求和只是归档校验数，不是当前钱包余额或可支付金额，不把未知单位换算成美元。

## 只读盘点

```powershell
python scripts/multitenant/orphan_quarantine.py `
  --container exchange-705-mysql-1 --database 1090 `
  --inventory rollback/multitenant-20260929/schema/orphans-production-readonly-with-amounts-20260929.json
```

已生成该文件，重跑须新文件名，不覆盖证据。最新只读盘点数量为 `6 / 3134 / 2 / 38 / 2 / 10`；快照此前是 2942，后为 3124，说明旧系统仍在写入。**最终清理必须停写后重盘点，不能把这些数量或旧计划当作持续有效。** 逐行明细仅存受限 rollback，不进入公共报告。

## 隔离演练与事务保护

```powershell
python scripts/multitenant/test_orphan_quarantine.py
```

演练使用旧 schema + 当前兼容列、合成合法用户/订单/客服证据，再各造一条六类孤儿。不导入生产数据。步骤：

1. 读取计划，核对目标完整容器 ID、schema 和所有孤儿内容。
2. 完整数据库 `mysqldump`，保存 SHA-256；恢复到另一个新建的独立库。
3. 对所有业务表、全部原列（包括只比较哈希的凭据列）逐行摘要一致性验证；失败不删除。
4. 保存逐行 `archive.json` 和可恢复的 `restore-rows.sql`；INSERT 不覆盖已有主键。
5. 一个事务中锁定父用户键范围及每张源表的精确主键；每行复核原内容 SHA-256 和父不存在条件。
6. 按已归档主键和原内容精确删除。任意后续表出现变化，整个事务回滚，不能只留下前几张表已删除。
7. 对比预期剩余全库指纹，确认其它行、金额、订单、消息及附件字节未改变；写结果收据。相同计划续跑只有最终数据库指纹一致才返回原结果。

测试还故意在归档后、事务前为第三张表恢复一个合成父主体，验证前两张表的删除也回滚；随后只移除这个测试自己创建的临时父主体，恢复夹具。最后执行逐行恢复 SQL，证明恢复后整个原数据库指纹完全相同。

证据：`reports/multitenant/orphan-quarantine-rehearsal.json`，包含合成库、完整备份哈希、逐行恢复 SQL 哈希和受保护证据目录。未发生实际库清理。

## 恢复与后续正式步骤

- 计划未应用：保留只读计划，不需要数据回滚。
- 应用失败：当前事务回滚；备份和逐行归档仍保留。中断证据目录不覆盖，先核实数据库，再用新目录重新演练。
- 已完成隔离夹具删除：恢复到新库时首选完整已验证备份；恢复精确孤儿行可在隔离原 schema 执行 `restore-rows.sql`，既有主键碰撞会失败，不做 UPDATE/REPLACE。
- 已执行多租户迁移或有新业务写入：禁止直接将旧孤儿 SQL 灌进新库或用旧全库备份覆盖新数据；需要保留增量并前向修复。
- root 放行实际清理前，必须停旧应用、定时任务及其它写者，重新做计划/备份/恢复。正式清理后重跑完整迁移 preflight；只有六类孤儿清理不能解除 ORM、域名、配置及端到端验收阻塞。
