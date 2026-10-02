# 多租户数据库隔离清单

日期：2026-09-29。分类源：当前源码、实际运行 MySQL 5.7 只读元数据。实际业务库未修改。

## 完整表分类

下列 70 张表是租户私有表。迁移为每张表加入无默认值的 `tenant_id NOT NULL`、租户外键和不可修改租户的触发器；适用的唯一索引与自然主键加入租户维度。编号型历史主键保留。

- `activity_campaign`
- `activity_delivery`
- `admin_role`
- `admin_role_menu`
- `admin_table_preference`
- `admin_user`
- `announcement`
- `asset_account`
- `asset_history_1d`
- `asset_history_1h`
- `asset_history_1m`
- `asset_history_4h`
- `asset_history_baseline`
- `asset_history_job_state`
- `asset_history_quote_batch`
- `asset_history_revision`
- `asset_snapshot`
- `balance_adjustment`
- `contract_order`
- `demo_account`
- `demo_ledger`
- `demo_order`
- `deposit_credit_record`
- `deposit_record`
- `deposit_setting`
- `financial_order`
- `financial_product`
- `financial_yield_record`
- `inbox_letter`
- `kyc_record`
- `loan_personal_info`
- `loan_record`
- `loan_setting`
- `manual_order_record`
- `market_control_flow`
- `market_control_hold`
- `market_control_plan`
- `market_control_publication`
- `market_control_resume`
- `market_control_sample`
- `market_control_task`
- `market_legacy_minute_snapshot`
- `market_mixed_minute`
- `market_simulation_source_candle`
- `market_source_candle`
- `market_source_event`
- `market_source_quote`
- `market_source_tick`
- `operation_log`
- `option_duration`
- `option_order`
- `simulation_seed`
- `support_attachment`
- `support_conversation`
- `support_message`
- `support_presence`
- `symbol_duration`
- `system_config`
- `t_ai_model`
- `trading_symbol`
- `transfer_record`
- `trial_account`
- `trial_ledger`
- `user_account`
- `user_action`
- `user_bank_card`
- `user_digital_address`
- `user_menu`
- `verify_code`
- `withdraw_record`

受控共享表：`admin_menu`, `menu_action`, `asset_history_migration`。共享仅表示服务端目录/迁移登记，普通租户无权修改全局目录。

控制面表：`tenant`, `tenant_policy`, `control_admin`, `control_access_session`, `control_audit_log`, `backend_login`, `tenant_domain_history`, `tenant_schema_version`。`tenant_schema_version` 是受控部署门禁，不是业务表。

## 必须注意的例外

- `market_source_*` 当前用租户私有 `trading_symbol.id` 作 `symbol_id`；本次也隔离，未假装已有公共 instrument 目录。将来可以共享原始公共源，但必须先引入独立公共标识。
- `asset_history_migration` 仅记录 DDL 版本；资产估值、任务水位、报价证据、版本缓存全部私有。
- `support_attachment` 的主键是 `message_id`，`support_presence` 是 `admin_id`，`admin_table_preference` 是字符串 `id`，多张原生表使用复合自然主键。迁移不假定所有表都有数字 `id`。
- `deposit_record.order_no` 保留全局唯一；外部订单身份不能因为租户改造而重复入账。请求幂等键使用租户复合唯一。
- `backend_login.normalized_account` 全局唯一，ADMIN/AGENT 关系由真正关联列与 MySQL 5.7 触发器验证，不依赖该版本不生效的 CHECK。
- `operation_log.admin_id` 是历史多态操作者，`support_message.sender_id` 是用户/员工/总控多态身份。不得给它们机械添加仅指向管理员的错误外键；业务服务负责真实身份校验，总控另留审计。
- `simulation_seed` 关联本模拟数据库内的用户。跨真实/模拟实例仍需已验证的 tenant + user 映射，不能只传裸用户 ID。
- 未知表、现有孤儿、规范化账号冲突、未登记触发器会使预检失败，不忽略、不删行、不猜归属。

## 关键关联

完整机器可读清单：`scripts/multitenant/table_manifest.json`。目前登记 67 条租户复合外键关系，另有控制面 actor/tenant 外键。

- `activity_delivery.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `asset_account.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `asset_snapshot.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `balance_adjustment.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `contract_order.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `deposit_credit_record.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `deposit_record.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `financial_order.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `financial_yield_record.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `kyc_record.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `loan_personal_info.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `loan_record.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `option_order.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `transfer_record.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `user_action.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `user_bank_card.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `user_digital_address.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `user_menu.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `withdraw_record.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `demo_account.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `demo_order.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `demo_ledger.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `trial_account.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `trial_ledger.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `simulation_seed.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `inbox_letter.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `support_conversation.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `asset_history_1m.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `asset_history_1h.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `asset_history_4h.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `asset_history_1d.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `asset_history_baseline.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `asset_history_revision.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `manual_order_record.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `user_account.parent_user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `admin_user.role_id` 关联 `admin_role.id`，同时约束 `tenant_id`。
- `admin_role_menu.role_id` 关联 `admin_role.id`，同时约束 `tenant_id`。
- `financial_order.product_id` 关联 `financial_product.id`，同时约束 `tenant_id`。
- `financial_yield_record.product_id` 关联 `financial_product.id`，同时约束 `tenant_id`。
- `financial_yield_record.order_id` 关联 `financial_order.id`，同时约束 `tenant_id`。
- `deposit_credit_record.deposit_record_id` 关联 `deposit_record.id`，同时约束 `tenant_id`。
- `activity_delivery.campaign_id` 关联 `activity_campaign.id`，同时约束 `tenant_id`。
- `support_conversation.active_user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `support_conversation.admin_id` 关联 `admin_user.id`，同时约束 `tenant_id`。
- `support_message.conversation_id` 关联 `support_conversation.id`，同时约束 `tenant_id`。
- `support_presence.admin_id` 关联 `admin_user.id`，同时约束 `tenant_id`。
- `support_attachment.message_id` 关联 `support_message.id`，同时约束 `tenant_id`。
- `inbox_letter.admin_id` 关联 `admin_user.id`，同时约束 `tenant_id`。
- `backend_login.user_id` 关联 `user_account.id`，同时约束 `tenant_id`。
- `backend_login.admin_user_id` 关联 `admin_user.id`，同时约束 `tenant_id`。
- `demo_ledger.order_id` 关联 `demo_order.id`，同时约束 `tenant_id`。
- `manual_order_record.order_id` 关联 `contract_order.id`，同时约束 `tenant_id`。
- `market_control_flow.task_id` 关联 `market_control_task.id`，同时约束 `tenant_id`。
- `market_control_hold.task_id` 关联 `market_control_task.id`，同时约束 `tenant_id`。
- `market_control_plan.task_id` 关联 `market_control_task.id`，同时约束 `tenant_id`。
- `market_control_publication.task_id` 关联 `market_control_task.id`，同时约束 `tenant_id`。
- `market_control_resume.task_id` 关联 `market_control_task.id`，同时约束 `tenant_id`。
- `market_control_sample.task_id` 关联 `market_control_task.id`，同时约束 `tenant_id`。
- `market_control_task.symbol_id` 关联 `trading_symbol.id`，同时约束 `tenant_id`。
- `market_legacy_minute_snapshot.symbol_id` 关联 `trading_symbol.id`，同时约束 `tenant_id`。
- `market_mixed_minute.symbol_id` 关联 `trading_symbol.id`，同时约束 `tenant_id`。
- `market_simulation_source_candle.symbol_id` 关联 `trading_symbol.id`，同时约束 `tenant_id`。
- `market_source_candle.symbol_id` 关联 `trading_symbol.id`，同时约束 `tenant_id`。
- `market_source_event.symbol_id` 关联 `trading_symbol.id`，同时约束 `tenant_id`。
- `market_source_quote.symbol_id` 关联 `trading_symbol.id`，同时约束 `tenant_id`。
- `market_source_tick.symbol_id` 关联 `trading_symbol.id`，同时约束 `tenant_id`。
- `asset_history_1m.quote_batch_id` 关联 `asset_history_quote_batch.batch_id`，同时约束 `tenant_id`。

## 真实环境发现（阻止上线迁移）

`exchange-705-mysql-1 / 1090` 只读预检：用户父行缺失的记录分别为 `asset_account` 6、`asset_snapshot` 2942、`financial_yield_record` 2、`transfer_record` 38、`user_digital_address` 2、`user_menu` 10。规范化账号冲突和未知表均为 0。这些是历史资金/资产/授权记录，未删除、未补造用户。必须从有依据的历史备份恢复缺失主体，或形成经确认的完整隔离/归档方案，然后重新预检；不允许关外键强行迁移。

证据：`reports/multitenant/production-readonly-inventory.json`。运行库在盘点时仍在线，最终迁移前必须停写后重新盘点，数字不是持续监控承诺。
