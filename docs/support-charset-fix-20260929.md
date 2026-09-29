# 线上客服创建会话失败：字符集修复

## 根因

2026-09-29 09:14、09:27 UTC 的生产日志中，`UserSupportController.start` 调用 `SupportService.start`，在 `append` 写入中文欢迎语时出现 MySQL `Incorrect string value`，失败字段为 `support_message.text`。事务回滚后，前端显示通用错误。

生产 `1090` 数据库默认字符集及五张客服/站内信表均为 `latin1`。JDBC 传入 UTF-8 并不能让 latin1 字段保存中文。

## 已执行

- 目标：`trade.forex-exchange.cc` 对应生产 `exchange-705-mysql-1`。
- 用 `mysqldump --no-tablespaces --single-transaction --hex-blob` 备份五张表。
- 执行 `exchange-backend/src/main/resources/db/manual/705-support-utf8mb4.sql`，将客服会话、消息、附件、在线状态、站内信表转为 `utf8mb4_unicode_ci`。
- 未部署当前工作区未发布的多租户代码，未重启服务，未改账户、余额、订单或数据库默认字符集。
- SQL 已保存到生产当前发布目录同一路径。新建环境需使用显式 utf8mb4 建表；恢复旧备份后应复核字符集。

## 验证

- 五张表字符集全部为 `utf8mb4_unicode_ci`。
- 从真实消息字段和站内信字段创建临时表，中文、日文及四字节表情写入/读回的十六进制逐字节比较均为 1。
- 会话、消息、站内信数量修复前后均为 0；没有向业务表写入测试消息。
- 后端健康检查 healthy，线上公开时区接口 HTTP 200。
- 未使用真实客户身份创建会话，真实登录后的按钮流程仍需用户复验。

## 备份及回退边界

服务器备份：`/opt/exchange-705-backups/support-charset-20260929/support-before-verified.sql`。

SHA-256：`88056a545fce7f7863b701f2460ef86c737095d66dcb7ddb17af3d3699d97da7`。

保留 utf8mb4 与旧应用兼容，不应为了回退应用而恢复 latin1。若已有新聊天记录，禁止直接导入旧备份或降回 latin1，以免丢失消息；需要恢复时先停写、重新备份并单独核对新增数据。
