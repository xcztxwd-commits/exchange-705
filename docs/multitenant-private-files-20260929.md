# 历史上传文件归属盘点与隔离演练

## 范围与限制

`scripts/multitenant/private_files.py` 默认只读。它按数据库实际字段引用归属，不按文件名、用户名、目录数字或当前登录人猜归属。不下载远程 URL，不发布未引用文件，不改客服历史证据，不删除或搬走源文件。

当前 `--apply` **只允许独立标记的 MySQL 测试实例和受保护的合成文件目录**。正式库文件迁移尚未开放，不得将本次演练当作生产数据已经迁移。所有原数据库和实际 `uploads` 保持不变。

## 已登记引用

| 数据字段 | 类别 | 权威归属 |
| --- | --- | --- |
| `kyc_record.id_front_image/id_back_image` | KYC | 记录 `tenant_id + user_id`，同时确认用户父行存在 |
| `loan_personal_info.id_front_image/id_back_image/handheld_image` | 借贷身份材料 | 记录 `tenant_id + user_id` |
| `loan_record.signature_image` | 借贷签名 | 记录 `tenant_id + user_id` |
| `deposit_record.proof_image` | 入款凭证 | 记录 `tenant_id + user_id` |
| `financial_product.image_url` | 公开产品图 | 只能确认租户，历史上传主体未知；人工复核 |
| `trading_symbol.icon_url/flag_url` | 公开交易品种图 | 只能确认租户，不能伪造 staff 上传人 |
| `deposit_setting.qr_code` | 已登录付款渠道图 | 租户资产，不自动当作公开 logo |
| `system_config` 中本地上传引用 | 品牌 logo/banner 或其他配置 | 记录配置行号、租户和内容摘要；JSON/HTML 不自动改写 |
| `support_attachment.content` | 客服证据 BLOB | 关联 message/conversation/user，记录字节数与 SHA-256；不提取、不改写 |
| `support_message.text` 中历史图片引用 | 客服历史证据 | 只记摘要；需要独立保全方案，不能破坏消息哈希链 |

旧库没有 `tenant_id` 时，只有操作员明确提供 `--legacy-tenant 1` 才按历史默认租户记账。未提供则无法确认归属。一个文件被不同租户、不同用户或公开/私有类别混用时，整项阻断，不复制给任意一方。相同主体的多字段引用可共同迁移。

## 实际只读盘点

盘点时实际挂载为 `C:\workspace\fx\705\uploads`，容器路径 `/app/uploads`。只对 `exchange-705-mysql-1 / 1090` 发出只读查询和本地源文件读取：

```powershell
python scripts/multitenant/private_files.py `
  --container exchange-705-mysql-1 --database 1090 --legacy-tenant 1 `
  --storage-root C:/workspace/fx/705/uploads `
  --output rollback/multitenant-20260929/files/production-readonly-inventory.json
```

此证据路径已存在，重跑须换新文件名，工具拒绝覆盖。结果：42 个本地引用文件；29 个可确认私有主体，10 个缺失或链接路径，3 个租户资产上传主体未知；44 个未引用文件不发布；另有 4 条配置需复核、14 条 URL 需复核；客服 BLOB 计数为 0。明细含敏感路径，只放受限 `rollback`，不复制到公共报告。数字是当次快照，不是持续监控。

外部主机默认不接受；如确有历史绝对 URL，操作员可逐个传 `--legacy-host`，只用于匹配其本地路径，不会发网络请求。带 query/fragment、路径穿越、未知媒体类型、软链接或 junction 均不自动迁移。模拟上传目录单列待审，不混进真实业务目录。

## 可续跑演练

```powershell
python scripts/multitenant/test_private_files.py
```

此命令创建独立命名的合成数据库和微型 PNG，在保护目录写目标标记，先 dry-run 再显式调用 `--apply`，不会接触实际上传源。成功输出 `PRIVATE_FILES_MYSQL_PASS checks=18`。证据：`reports/multitenant/private-files-rehearsal.json`，内含对应受保护目录、备份 SHA-256 和逐项检查。

手工续跑只能使用演练已产生的同一 inventory、destination 和 backup：

```powershell
python scripts/multitenant/private_files.py `
  --container mt705-20260929-test-mysql --database mt705_REVIEWED_FIXTURE `
  --storage-root REVIEWED_SYNTHETIC_SOURCE --output REVIEWED_INVENTORY_JSON `
  --apply --destination REVIEWED_MARKED_TARGET --backup REVIEWED_BACKUP_DIRECTORY
```

工具先核对 Docker 标签、完整容器 ID、数据库名、目标标记和保护目录范围。应用前备份数据库、原计划及源文件；源/备份/目标 SHA-256 一致才改引用。目标格式为 `images/{tenant}/user/{user}/{sha256}.{ext}`，输出 URL 与实际受鉴权图片接口一致。

目标通过临时文件加原子“不覆盖创建”落盘。数据库按每个源文件分事务：锁定全部引用，以精确旧 URL/租户/用户/行号作条件；任一引用已变动则整组回滚。每个已完成文件记收据，重跑核对哈希并接受已完成新 URL，不重复修改。原文件始终保留；失败可能留下尚未被引用的已验证目标副本，不会公开它。

18 项真实 MySQL 检查覆盖：只读无 DB 变更、多引用同主体、跨主体歧义、未知公共上传者、缺失文件、未引用不发布、客服 BLOB 不改、4 类危险 URL、第二引用并发改变时整组回滚、幂等续跑、真实 CLI apply、源/备份/目标哈希、引用一致、全部原件保留和源变更拒绝。

## 生产前剩余事项

生产库历史孤儿必须先有证据地处理。公共品牌/产品图片需要确认历史实际发布引用及发布规则，不能虚构管理员或把未引用素材开放。模拟目录、远程历史 URL、客服文本图片和不透明配置均须单独复核。之后才可设计受控生产 apply：停写、审批、校验、批次恢复和访问授权回归；本工具当前故意不提供生产写开关。
