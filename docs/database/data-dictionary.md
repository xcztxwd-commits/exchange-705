# Exchange 705 当前数据库数据字典

快照日期：2026-10-05。结构版本：`2026100404`。对应业务源码提交：`50d65fdc59083f9c3d029fa78d42794f5eabf86c`。

本文件由已迁移的隔离 MySQL 5.7 库的 `information_schema` 生成，只包含结构元数据。权威 DDL 见 [schema.sql](schema.sql)，导入边界、版本差异和校验方法见 [README](README.md)。

实测结构：112 张表、1319 个字段、448 个索引、188 个外键、161 个触发器、1 个存储过程；没有视图、事件或存储函数。索引和外键按约束对象计数，复合约束不重复计数。

机器清单登记 113 张表，其中 `tenant_migration_history` 只有登记，没有审定创建语句，也没有出现在此实际库中。因此该名称不被伪造为已存在的第 113 张表；迁移证据仍走现有受控 JSON 回执。

默认值栏 `NULL` 表示元数据未提供非 NULL 默认值；可空字段与 NOT NULL 字段的具体默认行为以 DDL 为准。旧表注释存在历史编码乱码，照录元数据，不推测或改写。

## 表目录

| 表 | 分类 | 字段数 | 引入迁移 |
| --- | --- | ---: | --- |
| [activity_campaign](#activity_campaign) | 租户私有 | 29 | 历史基线或前置结构 |
| [activity_delivery](#activity_delivery) | 租户私有 | 13 | 历史基线或前置结构 |
| [activity_material](#activity_material) | 租户私有 | 5 | V2026092905__activity_material_library.sql |
| [activity_selection](#activity_selection) | 租户私有 | 14 | V2026100102__stage1_current_business_schema.sql |
| [activity_selection_member](#activity_selection_member) | 租户私有 | 4 | V2026100102__stage1_current_business_schema.sql |
| [activity_send_receipt](#activity_send_receipt) | 租户私有 | 6 | V2026100102__stage1_current_business_schema.sql |
| [admin_menu](#admin_menu) | 共享 | 11 | 历史基线或前置结构 |
| [admin_role](#admin_role) | 租户私有 | 9 | 历史基线或前置结构 |
| [admin_role_menu](#admin_role_menu) | 租户私有 | 5 | 历史基线或前置结构 |
| [admin_table_preference](#admin_table_preference) | 租户私有 | 3 | 历史基线或前置结构 |
| [admin_user](#admin_user) | 租户私有 | 13 | 历史基线或前置结构 |
| [announcement](#announcement) | 租户私有 | 11 | 历史基线或前置结构 |
| [announcement_receipt](#announcement_receipt) | 租户私有 | 5 | V2026100102__stage1_current_business_schema.sql |
| [asset_account](#asset_account) | 租户私有 | 9 | 历史基线或前置结构 |
| [asset_history_1d](#asset_history_1d) | 租户私有 | 21 | 历史基线或前置结构 |
| [asset_history_1h](#asset_history_1h) | 租户私有 | 21 | 历史基线或前置结构 |
| [asset_history_1m](#asset_history_1m) | 租户私有 | 24 | 历史基线或前置结构 |
| [asset_history_4h](#asset_history_4h) | 租户私有 | 21 | 历史基线或前置结构 |
| [asset_history_baseline](#asset_history_baseline) | 租户私有 | 6 | 历史基线或前置结构 |
| [asset_history_job_state](#asset_history_job_state) | 租户私有 | 7 | 历史基线或前置结构 |
| [asset_history_migration](#asset_history_migration) | 共享 | 3 | 历史基线或前置结构 |
| [asset_history_quote_batch](#asset_history_quote_batch) | 租户私有 | 4 | 历史基线或前置结构 |
| [asset_history_revision](#asset_history_revision) | 租户私有 | 5 | 历史基线或前置结构 |
| [asset_snapshot](#asset_snapshot) | 租户私有 | 5 | 历史基线或前置结构 |
| [backend_login](#backend_login) | 控制面 | 7 | 历史基线或前置结构 |
| [balance_adjustment](#balance_adjustment) | 租户私有 | 10 | V2026092900__legacy_feature_prerequisites.sql |
| [calendar_audit](#calendar_audit) | 租户私有 | 10 | V2026100301__economic_calendar.sql |
| [calendar_event](#calendar_event) | 租户私有 | 14 | V2026100301__economic_calendar.sql |
| [calendar_reminder](#calendar_reminder) | 租户私有 | 13 | V2026100301__economic_calendar.sql |
| [calendar_source](#calendar_source) | 共享 | 18 | V2026100301__economic_calendar.sql |
| [calendar_source_update](#calendar_source_update) | 共享 | 8 | V2026100301__economic_calendar.sql |
| [contract_order](#contract_order) | 租户私有 | 47 | 历史基线或前置结构 |
| [control_access_session](#control_access_session) | 控制面 | 13 | 历史基线或前置结构 |
| [control_admin](#control_admin) | 控制面 | 8 | 历史基线或前置结构 |
| [control_audit_log](#control_audit_log) | 控制面 | 12 | 历史基线或前置结构 |
| [control_chat_archive_job](#control_chat_archive_job) | 控制面 | 22 | V2026093006__chat_archive_jobs.sql |
| [control_table_preference](#control_table_preference) | 控制面 | 4 | V2026093002__control_table_preferences.sql |
| [demo_account](#demo_account) | 租户私有 | 7 | 历史基线或前置结构 |
| [demo_ledger](#demo_ledger) | 租户私有 | 9 | 历史基线或前置结构 |
| [demo_order](#demo_order) | 租户私有 | 17 | 历史基线或前置结构 |
| [deposit_credit_record](#deposit_credit_record) | 租户私有 | 12 | 历史基线或前置结构 |
| [deposit_record](#deposit_record) | 租户私有 | 33 | 历史基线或前置结构 |
| [deposit_setting](#deposit_setting) | 租户私有 | 12 | 历史基线或前置结构 |
| [financial_order](#financial_order) | 租户私有 | 24 | 历史基线或前置结构 |
| [financial_product](#financial_product) | 租户私有 | 16 | 历史基线或前置结构 |
| [financial_yield_record](#financial_yield_record) | 租户私有 | 14 | 历史基线或前置结构 |
| [inbox_letter](#inbox_letter) | 租户私有 | 10 | 历史基线或前置结构 |
| [kyc_record](#kyc_record) | 租户私有 | 13 | 历史基线或前置结构 |
| [loan_personal_info](#loan_personal_info) | 租户私有 | 16 | 历史基线或前置结构 |
| [loan_record](#loan_record) | 租户私有 | 27 | 历史基线或前置结构 |
| [loan_setting](#loan_setting) | 租户私有 | 11 | 历史基线或前置结构 |
| [manual_order_binding](#manual_order_binding) | 租户私有 | 8 | V2026100102__stage1_current_business_schema.sql |
| [manual_order_record](#manual_order_record) | 租户私有 | 9 | 历史基线或前置结构 |
| [market_control_command](#market_control_command) | 租户私有 | 20 | V2026100304__market_engine_runtime.sql |
| [market_control_flow](#market_control_flow) | 租户私有 | 10 | 历史基线或前置结构 |
| [market_control_hold](#market_control_hold) | 租户私有 | 10 | 历史基线或前置结构 |
| [market_control_plan](#market_control_plan) | 租户私有 | 7 | 历史基线或前置结构 |
| [market_control_publication](#market_control_publication) | 租户私有 | 5 | 历史基线或前置结构 |
| [market_control_resume](#market_control_resume) | 租户私有 | 5 | 历史基线或前置结构 |
| [market_control_sample](#market_control_sample) | 租户私有 | 4 | 历史基线或前置结构 |
| [market_control_task](#market_control_task) | 租户私有 | 21 | 历史基线或前置结构 |
| [market_engine_runtime](#market_engine_runtime) | 租户私有 | 13 | V2026100304__market_engine_runtime.sql |
| [market_engine_tenant](#market_engine_tenant) | 租户私有 | 1 | V2026100304__market_engine_runtime.sql |
| [market_history_ordering](#market_history_ordering) | 租户私有 | 10 | V2026100404__history_ordering_and_response_receipts.sql |
| [market_history_response](#market_history_response) | 租户私有 | 11 | V2026100404__history_ordering_and_response_receipts.sql |
| [market_legacy_minute_snapshot](#market_legacy_minute_snapshot) | 租户私有 | 5 | 历史基线或前置结构 |
| [market_mixed_minute](#market_mixed_minute) | 租户私有 | 5 | 历史基线或前置结构 |
| [market_simulation_source_candle](#market_simulation_source_candle) | 租户私有 | 6 | 历史基线或前置结构 |
| [market_source_candle](#market_source_candle) | 租户私有 | 6 | 历史基线或前置结构 |
| [market_source_event](#market_source_event) | 租户私有 | 7 | 历史基线或前置结构 |
| [market_source_quote](#market_source_quote) | 租户私有 | 4 | 历史基线或前置结构 |
| [market_source_tick](#market_source_tick) | 租户私有 | 5 | 历史基线或前置结构 |
| [menu_action](#menu_action) | 共享 | 7 | 历史基线或前置结构 |
| [news_article](#news_article) | 租户私有 | 15 | V2026100302__external_news.sql |
| [news_audit](#news_audit) | 租户私有 | 11 | V2026100302__external_news.sql |
| [news_feed](#news_feed) | 共享 | 21 | V2026100302__external_news.sql |
| [news_source_setting](#news_source_setting) | 租户私有 | 8 | V2026100302__external_news.sql |
| [operation_log](#operation_log) | 租户私有 | 17 | 历史基线或前置结构 |
| [option_duration](#option_duration) | 租户私有 | 12 | 历史基线或前置结构 |
| [option_order](#option_order) | 租户私有 | 27 | 历史基线或前置结构 |
| [s4_history_projection_minute](#s4_history_projection_minute) | 租户私有 | 8 | V2026100402__joint_s4_history_projection.sql |
| [s4_history_projection_progress](#s4_history_projection_progress) | 租户私有 | 9 | V2026100402__joint_s4_history_projection.sql |
| [simulation_seed](#simulation_seed) | 租户私有 | 4 | 历史基线或前置结构 |
| [support_attachment](#support_attachment) | 租户私有 | 3 | 历史基线或前置结构 |
| [support_conversation](#support_conversation) | 租户私有 | 16 | 历史基线或前置结构 |
| [support_message](#support_message) | 租户私有 | 13 | 历史基线或前置结构 |
| [support_presence](#support_presence) | 租户私有 | 4 | 历史基线或前置结构 |
| [symbol_duration](#symbol_duration) | 租户私有 | 13 | 历史基线或前置结构 |
| [system_config](#system_config) | 租户私有 | 7 | 历史基线或前置结构 |
| [t_ai_model](#t_ai_model) | 租户私有 | 14 | 历史基线或前置结构 |
| [tenant](#tenant) | 控制面 | 12 | 历史基线或前置结构 |
| [tenant_domain_binding](#tenant_domain_binding) | 控制面 | 7 | V2026093004__tenant_domain_candidates.sql |
| [tenant_domain_history](#tenant_domain_history) | 控制面 | 4 | 历史基线或前置结构 |
| [tenant_policy](#tenant_policy) | 控制面 | 6 | 历史基线或前置结构 |
| [tenant_schema_version](#tenant_schema_version) | 控制面 | 4 | 历史基线或前置结构 |
| [trader_audit](#trader_audit) | 租户私有 | 11 | V2026100303__curated_traders.sql |
| [trader_equity](#trader_equity) | 租户私有 | 10 | V2026100303__curated_traders.sql |
| [trader_history](#trader_history) | 租户私有 | 18 | V2026100303__curated_traders.sql |
| [trader_profile](#trader_profile) | 租户私有 | 14 | V2026100303__curated_traders.sql |
| [trading_symbol](#trading_symbol) | 租户私有 | 50 | 历史基线或前置结构 |
| [transfer_record](#transfer_record) | 租户私有 | 8 | 历史基线或前置结构 |
| [trial_account](#trial_account) | 租户私有 | 11 | 历史基线或前置结构 |
| [trial_grant](#trial_grant) | 租户私有 | 14 | V2026100102__stage1_current_business_schema.sql |
| [trial_ledger](#trial_ledger) | 租户私有 | 8 | 历史基线或前置结构 |
| [user_account](#user_account) | 租户私有 | 32 | 历史基线或前置结构 |
| [user_action](#user_action) | 租户私有 | 6 | 历史基线或前置结构 |
| [user_bank_card](#user_bank_card) | 租户私有 | 11 | 历史基线或前置结构 |
| [user_digital_address](#user_digital_address) | 租户私有 | 8 | 历史基线或前置结构 |
| [user_id_sequence](#user_id_sequence) | 控制面 | 2 | V2026093005__user_id_sequence.sql |
| [user_menu](#user_menu) | 租户私有 | 6 | 历史基线或前置结构 |
| [verify_code](#verify_code) | 租户私有 | 8 | 历史基线或前置结构 |
| [withdraw_record](#withdraw_record) | 租户私有 | 27 | 历史基线或前置结构 |

## activity_campaign

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `row_version` | `bigint(20)` | NO | "0" |  | — |  |
| `name` | `varchar(120)` | NO | NULL |  | utf8mb4_general_ci |  |
| `status` | `varchar(16)` | NO | "DRAFT" |  | utf8mb4_general_ci |  |
| `template` | `bit(1)` | NO | "b'0'" |  | — |  |
| `auto_popup` | `bit(1)` | NO | "b'1'" |  | — |  |
| `animation` | `varchar(16)` | NO | "GIFT" |  | utf8mb4_general_ci |  |
| `default_locale` | `varchar(16)` | NO | "zh-CN" |  | utf8mb4_general_ci |  |
| `translations` | `longtext` | NO | NULL |  | utf8mb4_general_ci |  |
| `amount` | `decimal(32,16)` | NO | NULL |  | — |  |
| `recent_login_days` | `int(11)` | NO | "3" |  | — |  |
| `max_claims` | `int(11)` | NO | "1000" |  | — |  |
| `claim_count` | `int(11)` | NO | "0" |  | — |  |
| `budget` | `decimal(32,16)` | NO | NULL |  | — |  |
| `granted` | `decimal(32,16)` | NO | "0.0000000000000000" |  | — |  |
| `starts_at` | `datetime(6)` | YES | NULL |  | — |  |
| `ends_at` | `datetime(6)` | YES | NULL |  | — |  |
| `created_at` | `datetime(6)` | YES | NULL |  | — |  |
| `updated_at` | `datetime(6)` | YES | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `auto_send_enabled` | `bit(1)` | NO | "b'0'" |  | — |  |
| `repeat_unread` | `bit(1)` | NO | "b'0'" |  | — |  |
| `deleted` | `bit(1)` | NO | "b'0'" |  | — |  |
| `layout_json` | `longtext` | YES | NULL |  | utf8mb4_general_ci |  |
| `allow_repeat_send` | `tinyint(1)` | NO | "0" |  | — |  |
| `allow_repeat_claim` | `tinyint(1)` | NO | "0" |  | — |  |
| `claim_validity_days` | `int(11)` | YES | NULL |  | — |  |
| `positions` | `varchar(500)` | NO | "[\"AUTH_HOME\"]" |  | utf8mb4_general_ci |  |
| `trigger_conditions` | `varchar(500)` | NO | "[]" |  | utf8mb4_general_ci |  |

### 索引

- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `mt_tenant_status`：普通索引，BTREE，(`tenant_id`, `status`)。
- `PRIMARY`：主键，BTREE，(`id`)。

### 外键

- `mt_t_activity_campaign`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_activity_campaign`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## activity_delivery

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `row_version` | `bigint(20)` | NO | "0" |  | — |  |
| `campaign_id` | `bigint(20)` | NO | NULL |  | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `sent_at` | `datetime(6)` | YES | NULL |  | — |  |
| `received_at` | `datetime(6)` | YES | NULL |  | — |  |
| `opened_at` | `datetime(6)` | YES | NULL |  | — |  |
| `closed_at` | `datetime(6)` | YES | NULL |  | — |  |
| `claimed_at` | `datetime(6)` | YES | NULL |  | — |  |
| `open_count` | `int(11)` | NO | "0" |  | — |  |
| `close_count` | `int(11)` | NO | "0" |  | — |  |
| `sent_by` | `varchar(120)` | YES | NULL |  | utf8mb4_general_ci |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `ix_activity_user`：普通索引，BTREE，(`user_id`, `id`)。
- `mt_fk_f9e675144f6bbfb5e8d7`：普通索引，BTREE，(`tenant_id`, `user_id`)。
- `mt_old_403fd7ee66e0b7b6`：普通索引，BTREE，(`campaign_id`, `user_id`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_activity_recipient`：唯一索引，BTREE，(`tenant_id`, `campaign_id`, `user_id`)。

### 外键

- `mt_fk_b8d81c4d7a659753b377`：(`tenant_id`, `campaign_id`) 引用 `activity_campaign` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_fk_f9e675144f6bbfb5e8d7`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_activity_delivery`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_activity_delivery`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## activity_material

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_unicode_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `name` | `varchar(80)` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `nodes_json` | `longtext` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `deleted` | `tinyint(1)` | NO | "0" |  | — |  |

### 索引

- `idx_activity_material_tenant`：普通索引，BTREE，(`tenant_id`, `deleted`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_activity_material_owner`：唯一索引，BTREE，(`tenant_id`, `id`)。

### 外键

- `mt_activity_material_tenant`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_activity_material`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## activity_selection

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_unicode_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `row_version` | `bigint(20)` | NO | "0" |  | — |  |
| `campaign_id` | `bigint(20)` | NO | NULL |  | — |  |
| `operation_id` | `varchar(64)` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `filter_hash` | `varchar(64)` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `send_operation_id` | `varchar(64)` | YES | NULL |  | utf8mb4_unicode_ci |  |
| `created_at` | `datetime` | NO | NULL |  | — |  |
| `selected_count` | `bigint(20)` | NO | "0" |  | — |  |
| `sent_count` | `bigint(20)` | NO | "0" |  | — |  |
| `duplicate_count` | `bigint(20)` | NO | "0" |  | — |  |
| `ineligible_count` | `bigint(20)` | NO | "0" |  | — |  |
| `cursor_id` | `bigint(20)` | NO | "0" |  | — |  |
| `done` | `tinyint(1)` | NO | "0" |  | — |  |

### 索引

- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_activity_selection_operation`：唯一索引，BTREE，(`tenant_id`, `campaign_id`, `operation_id`)。

### 外键

- `mt_fk_bbb5482525c1996ff492`：(`tenant_id`, `campaign_id`) 引用 `activity_campaign` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_activity_selection`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_activity_selection`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## activity_selection_member

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_unicode_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `selection_id` | `bigint(20)` | NO | NULL |  | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `ix_activity_selection_cursor`：普通索引，BTREE，(`tenant_id`, `selection_id`, `id`)。
- `mt_fk_5c28fad9c98f685c73db`：普通索引，BTREE，(`tenant_id`, `user_id`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_activity_selection_member`：唯一索引，BTREE，(`tenant_id`, `selection_id`, `user_id`)。

### 外键

- `mt_fk_174160d80e3d9f005d5c`：(`tenant_id`, `selection_id`) 引用 `activity_selection` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_fk_5c28fad9c98f685c73db`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_activity_selection_member`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_activity_selection_member`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## activity_send_receipt

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_unicode_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `campaign_id` | `bigint(20)` | NO | NULL |  | — |  |
| `operation_id` | `varchar(64)` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `payload_hash` | `varchar(64)` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `result_json` | `varchar(1000)` | NO | NULL |  | utf8mb4_unicode_ci |  |

### 索引

- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_activity_send_operation`：唯一索引，BTREE，(`tenant_id`, `campaign_id`, `operation_id`)。

### 外键

- `mt_fk_5ed26ac6d6ccbc4afa73`：(`tenant_id`, `campaign_id`) 引用 `activity_campaign` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_activity_send_receipt`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_activity_send_receipt`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## admin_menu

分类：共享。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `parent_id` | `bigint(20)` | YES | "0" |  | — |  |
| `menu_name` | `varchar(50)` | NO | NULL |  | utf8mb4_general_ci |  |
| `menu_code` | `varchar(150)` | NO | NULL |  | utf8mb4_general_ci |  |
| `menu_type` | `varchar(20)` | NO | NULL |  | utf8mb4_general_ci |  |
| `path` | `varchar(200)` | YES | NULL |  | utf8mb4_general_ci |  |
| `icon` | `varchar(50)` | YES | NULL |  | utf8mb4_general_ci |  |
| `sort_order` | `int(11)` | NO | "0" |  | — |  |
| `status` | `varchar(20)` | NO | "active" |  | utf8mb4_general_ci |  |
| `created_at` | `datetime` | NO | "CURRENT_TIMESTAMP" |  | — |  |
| `updated_at` | `datetime` | NO | "CURRENT_TIMESTAMP" | on update CURRENT_TIMESTAMP | — |  |

### 索引

- `idx_menu_code`：普通索引，BTREE，(`menu_code`)。
- `menu_code`：唯一索引，BTREE，(`menu_code`)。
- `PRIMARY`：主键，BTREE，(`id`)。

## admin_role

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `role_name` | `varchar(50)` | NO | NULL |  | utf8mb4_general_ci |  |
| `role_code` | `varchar(50)` | NO | NULL |  | utf8mb4_general_ci |  |
| `description` | `varchar(200)` | YES | NULL |  | utf8mb4_general_ci |  |
| `status` | `varchar(20)` | NO | "active" |  | utf8mb4_general_ci |  |
| `is_super` | `tinyint(1)` | NO | "0" |  | — |  |
| `created_at` | `datetime` | NO | "CURRENT_TIMESTAMP" |  | — |  |
| `updated_at` | `datetime` | NO | "CURRENT_TIMESTAMP" | on update CURRENT_TIMESTAMP | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `idx_role_code`：普通索引，BTREE，(`role_code`)。
- `mt_old_29df60163fb74096`：普通索引，BTREE，(`role_code`)。
- `mt_old_2fb3eb1546582849`：普通索引，BTREE，(`role_name`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `mt_tenant_status`：普通索引，BTREE，(`tenant_id`, `status`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `role_code`：唯一索引，BTREE，(`tenant_id`, `role_code`)。
- `role_name`：唯一索引，BTREE，(`tenant_id`, `role_name`)。

### 外键

- `mt_t_admin_role`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_admin_role`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## admin_role_menu

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `role_id` | `bigint(20)` | NO | NULL |  | — |  |
| `menu_id` | `bigint(20)` | NO | NULL |  | — |  |
| `created_at` | `datetime` | NO | "CURRENT_TIMESTAMP" |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `mt_old_e65f3be18f0bdc05`：普通索引，BTREE，(`role_id`, `menu_id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_role_menu`：唯一索引，BTREE，(`tenant_id`, `role_id`, `menu_id`)。

### 外键

- `mt_fk_9c50ba4eb3e07fd0c1e0`：(`tenant_id`, `role_id`) 引用 `admin_role` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_admin_role_menu`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_admin_role_menu`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## admin_table_preference

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_unicode_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `varchar(200)` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `columns_json` | `longtext` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。

### 外键

- `mt_t_admin_table_preference`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_admin_table_preference`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## admin_user

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `account` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `created_at` | `datetime(6)` | YES | NULL |  | — |  |
| `email` | `varchar(128)` | NO | NULL |  | utf8mb4_general_ci |  |
| `enabled` | `bit(1)` | NO | NULL |  | — |  |
| `password_hash` | `varchar(128)` | NO | NULL |  | utf8mb4_general_ci |  |
| `role` | `varchar(32)` | NO | NULL |  | utf8mb4_general_ci |  |
| `updated_at` | `datetime(6)` | YES | NULL |  | — |  |
| `role_id` | `bigint(20)` | YES | NULL |  | — | 角色ID |
| `current_token` | `varchar(128)` | YES | NULL |  | utf8mb4_general_ci |  |
| `row_version` | `bigint(20)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `must_change_password` | `bit(1)` | NO | "b'0'" |  | — |  |

### 索引

- `idx_role_id`：普通索引，BTREE，(`role_id`)。
- `mt_fk_e95a17001bc7ce96ff03`：普通索引，BTREE，(`tenant_id`, `role_id`)。
- `mt_old_38909fe62d26ff3b`：普通索引，BTREE，(`email`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `UK_6etwowal6qxvr7xuvqcqmnnk7`：唯一索引，BTREE，(`tenant_id`, `email`)。
- `UK_lhgw84v0nlofhbm3frk55dg23`：唯一索引，BTREE，(`account`)。

### 外键

- `mt_fk_e95a17001bc7ce96ff03`：(`tenant_id`, `role_id`) 引用 `admin_role` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_admin_user`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_admin_user`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## announcement

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：公告表

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `title` | `varchar(200)` | NO | NULL |  | utf8mb4_general_ci | 公告标题 |
| `content` | `text` | NO | NULL |  | utf8mb4_general_ci | 公告内容 |
| `status` | `varchar(20)` | NO | "PUBLISHED" |  | utf8mb4_general_ci | 状态: PUBLISHED-已发布, DRAFT-草稿, HIDDEN-隐藏 |
| `priority` | `int(11)` | NO | "0" |  | — | 优先级，数字越大越优先显示 |
| `created_at` | `datetime` | NO | "CURRENT_TIMESTAMP" |  | — | 创建时间 |
| `updated_at` | `datetime` | NO | "CURRENT_TIMESTAMP" | on update CURRENT_TIMESTAMP | — | 更新时间 |
| `language` | `varchar(10)` | NO | NULL |  | utf8mb4_general_ci |  |
| `countdown_seconds` | `int(11)` | NO | "2" |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `display_at` | `datetime` | YES | NULL |  | — |  |

### 索引

- `idx_created_at`：普通索引，BTREE，(`created_at`)。
- `idx_language_status`：普通索引，BTREE，(`language`, `status`)。
- `idx_status_priority`：普通索引，BTREE，(`status`, `priority`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `mt_tenant_status`：普通索引，BTREE，(`tenant_id`, `status`)。
- `PRIMARY`：主键，BTREE，(`id`)。

### 外键

- `mt_t_announcement`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_announcement`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## announcement_receipt

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `announcement_id` | `bigint(20)` | NO | NULL |  | — |  |
| `read_at` | `datetime` | NO | NULL |  | — |  |

### 索引

- `mt_fk_dc84e06f52118a640e96`：普通索引，BTREE，(`tenant_id`, `announcement_id`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_announcement_receipt`：唯一索引，BTREE，(`tenant_id`, `user_id`, `announcement_id`)。

### 外键

- `mt_fk_48e02e6c67145c4b33fe`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_fk_dc84e06f52118a640e96`：(`tenant_id`, `announcement_id`) 引用 `announcement` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_announcement_receipt`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_announcement_receipt`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## asset_account

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：用户资产账户

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `coin` | `varchar(32)` | NO | NULL |  | utf8mb4_general_ci |  |
| `available` | `decimal(32,16)` | NO | "0.0000000000000000" |  | — |  |
| `frozen` | `decimal(32,16)` | NO | "0.0000000000000000" |  | — |  |
| `created_at` | `datetime` | NO | "CURRENT_TIMESTAMP" |  | — |  |
| `updated_at` | `datetime` | NO | "CURRENT_TIMESTAMP" | on update CURRENT_TIMESTAMP | — |  |
| `row_version` | `bigint(20)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `mt_old_1f918f4c60f0b206`：普通索引，BTREE，(`user_id`, `coin`)。
- `mt_old_b80a8c17543f2ff8`：普通索引，BTREE，(`user_id`, `coin`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_asset_user_coin`：唯一索引，BTREE，(`tenant_id`, `user_id`, `coin`)。
- `uk_user_coin`：唯一索引，BTREE，(`tenant_id`, `user_id`, `coin`)。

### 外键

- `mt_fk_baa188f189f51ddbaf02`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_asset_account`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_asset_account`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## asset_history_1d

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `basis_version` | `varchar(32)` | NO | NULL |  | latin1_swedish_ci |  |
| `bucket_start` | `bigint(20)` | NO | NULL |  | — |  |
| `bucket_end` | `bigint(20)` | NO | NULL |  | — |  |
| `open_value` | `decimal(32,16)` | YES | NULL |  | — |  |
| `high_value` | `decimal(32,16)` | YES | NULL |  | — |  |
| `low_value` | `decimal(32,16)` | YES | NULL |  | — |  |
| `close_value` | `decimal(32,16)` | YES | NULL |  | — |  |
| `open_at` | `bigint(20)` | YES | NULL |  | — |  |
| `high_at` | `bigint(20)` | YES | NULL |  | — |  |
| `low_at` | `bigint(20)` | YES | NULL |  | — |  |
| `close_at` | `bigint(20)` | YES | NULL |  | — |  |
| `source_count` | `bigint(20)` | NO | NULL |  | — |  |
| `valid_sample_count` | `bigint(20)` | NO | NULL |  | — |  |
| `invalid_sample_count` | `bigint(20)` | NO | NULL |  | — |  |
| `expected_sample_count` | `bigint(20)` | NO | NULL |  | — |  |
| `finalized` | `tinyint(1)` | NO | NULL |  | — |  |
| `quality` | `varchar(24)` | NO | NULL |  | latin1_swedish_ci |  |
| `source_through` | `bigint(20)` | NO | NULL |  | — |  |
| `updated_at` | `bigint(20)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `ix_equity_1d_batch`：普通索引，BTREE，(`basis_version`, `bucket_start`, `user_id`)。
- `PRIMARY`：主键，BTREE，(`tenant_id`, `user_id`, `basis_version`, `bucket_start`)。

### 外键

- `mt_fk_6a23f3c6789c7e434d2e`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_asset_history_1d`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `ahc_3_delete`：AFTER DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `ahc_3_insert`：AFTER INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `ahc_3_update`：AFTER UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `mt_immutable_asset_history_1d`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## asset_history_1h

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `basis_version` | `varchar(32)` | NO | NULL |  | latin1_swedish_ci |  |
| `bucket_start` | `bigint(20)` | NO | NULL |  | — |  |
| `bucket_end` | `bigint(20)` | NO | NULL |  | — |  |
| `open_value` | `decimal(32,16)` | YES | NULL |  | — |  |
| `high_value` | `decimal(32,16)` | YES | NULL |  | — |  |
| `low_value` | `decimal(32,16)` | YES | NULL |  | — |  |
| `close_value` | `decimal(32,16)` | YES | NULL |  | — |  |
| `open_at` | `bigint(20)` | YES | NULL |  | — |  |
| `high_at` | `bigint(20)` | YES | NULL |  | — |  |
| `low_at` | `bigint(20)` | YES | NULL |  | — |  |
| `close_at` | `bigint(20)` | YES | NULL |  | — |  |
| `source_count` | `bigint(20)` | NO | NULL |  | — |  |
| `valid_sample_count` | `bigint(20)` | NO | NULL |  | — |  |
| `invalid_sample_count` | `bigint(20)` | NO | NULL |  | — |  |
| `expected_sample_count` | `bigint(20)` | NO | NULL |  | — |  |
| `finalized` | `tinyint(1)` | NO | NULL |  | — |  |
| `quality` | `varchar(24)` | NO | NULL |  | latin1_swedish_ci |  |
| `source_through` | `bigint(20)` | NO | NULL |  | — |  |
| `updated_at` | `bigint(20)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `ix_equity_1h_batch`：普通索引，BTREE，(`basis_version`, `bucket_start`, `user_id`)。
- `PRIMARY`：主键，BTREE，(`tenant_id`, `user_id`, `basis_version`, `bucket_start`)。

### 外键

- `mt_fk_d62ee382bbba2a44e83d`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_asset_history_1h`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `ahc_1_delete`：AFTER DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `ahc_1_insert`：AFTER INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `ahc_1_update`：AFTER UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `mt_immutable_asset_history_1h`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## asset_history_1m

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `basis_version` | `varchar(32)` | NO | NULL |  | latin1_swedish_ci |  |
| `bucket_start` | `bigint(20)` | NO | NULL |  | — |  |
| `observed_at` | `bigint(20)` | YES | NULL |  | — |  |
| `wallet_balance` | `decimal(32,16)` | YES | NULL |  | — |  |
| `contract_unrealized_pnl` | `decimal(32,16)` | YES | NULL |  | — |  |
| `option_unrealized_pnl` | `decimal(32,16)` | YES | NULL |  | — |  |
| `receivables` | `decimal(32,16)` | YES | NULL |  | — |  |
| `loan_principal` | `decimal(32,16)` | YES | NULL |  | — |  |
| `accrued_interest` | `decimal(32,16)` | YES | NULL |  | — |  |
| `overdue_fees` | `decimal(32,16)` | YES | NULL |  | — |  |
| `accrued_trading_fees` | `decimal(32,16)` | YES | NULL |  | — |  |
| `other_liabilities` | `decimal(32,16)` | YES | NULL |  | — |  |
| `liabilities_total` | `decimal(32,16)` | YES | NULL |  | — |  |
| `net_equity` | `decimal(32,16)` | YES | NULL |  | — |  |
| `valuation_status` | `varchar(24)` | NO | NULL |  | latin1_swedish_ci |  |
| `reason_code` | `varchar(1024)` | NO | NULL |  | latin1_swedish_ci |  |
| `quote_batch_id` | `varchar(36)` | YES | NULL |  | latin1_swedish_ci |  |
| `valuation_evidence` | `longtext` | NO | NULL |  | latin1_swedish_ci |  |
| `origin` | `varchar(24)` | NO | "OBSERVED" |  | latin1_swedish_ci |  |
| `created_at` | `bigint(20)` | NO | NULL |  | — |  |
| `manual_adjustment` | `decimal(32,16)` | NO | "0.0000000000000000" |  | — |  |
| `effective_at` | `bigint(20)` | YES | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `ix_equity_minute_batch`：普通索引，BTREE，(`basis_version`, `bucket_start`, `user_id`)。
- `mt_fk_9d49204dd673c933d8a7`：普通索引，BTREE，(`tenant_id`, `quote_batch_id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `PRIMARY`：主键，BTREE，(`tenant_id`, `user_id`, `basis_version`, `bucket_start`)。

### 外键

- `mt_fk_9d49204dd673c933d8a7`：(`tenant_id`, `quote_batch_id`) 引用 `asset_history_quote_batch` (`tenant_id`, `batch_id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_fk_a94d6335a0ddc467ef44`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_asset_history_1m`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_asset_history_1m`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## asset_history_4h

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `basis_version` | `varchar(32)` | NO | NULL |  | latin1_swedish_ci |  |
| `bucket_start` | `bigint(20)` | NO | NULL |  | — |  |
| `bucket_end` | `bigint(20)` | NO | NULL |  | — |  |
| `open_value` | `decimal(32,16)` | YES | NULL |  | — |  |
| `high_value` | `decimal(32,16)` | YES | NULL |  | — |  |
| `low_value` | `decimal(32,16)` | YES | NULL |  | — |  |
| `close_value` | `decimal(32,16)` | YES | NULL |  | — |  |
| `open_at` | `bigint(20)` | YES | NULL |  | — |  |
| `high_at` | `bigint(20)` | YES | NULL |  | — |  |
| `low_at` | `bigint(20)` | YES | NULL |  | — |  |
| `close_at` | `bigint(20)` | YES | NULL |  | — |  |
| `source_count` | `bigint(20)` | NO | NULL |  | — |  |
| `valid_sample_count` | `bigint(20)` | NO | NULL |  | — |  |
| `invalid_sample_count` | `bigint(20)` | NO | NULL |  | — |  |
| `expected_sample_count` | `bigint(20)` | NO | NULL |  | — |  |
| `finalized` | `tinyint(1)` | NO | NULL |  | — |  |
| `quality` | `varchar(24)` | NO | NULL |  | latin1_swedish_ci |  |
| `source_through` | `bigint(20)` | NO | NULL |  | — |  |
| `updated_at` | `bigint(20)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `ix_equity_4h_batch`：普通索引，BTREE，(`basis_version`, `bucket_start`, `user_id`)。
- `PRIMARY`：主键，BTREE，(`tenant_id`, `user_id`, `basis_version`, `bucket_start`)。

### 外键

- `mt_fk_52ac99d8fc3b5c2dd844`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_asset_history_4h`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `ahc_2_delete`：AFTER DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `ahc_2_insert`：AFTER INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `ahc_2_update`：AFTER UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `mt_immutable_asset_history_4h`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## asset_history_baseline

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `basis_version` | `varchar(32)` | NO | NULL |  | latin1_swedish_ci |  |
| `capture_from` | `bigint(20)` | NO | NULL |  | — |  |
| `first_positive` | `decimal(32,16)` | YES | NULL |  | — |  |
| `first_positive_at` | `bigint(20)` | YES | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `user_id`, `basis_version`)。

### 外键

- `mt_fk_459539abd470acd7c35d`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_asset_history_baseline`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_asset_history_baseline`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## asset_history_job_state

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `task_name` | `varchar(32)` | NO | NULL |  | latin1_swedish_ci |  |
| `basis_version` | `varchar(32)` | NO | NULL |  | latin1_swedish_ci |  |
| `watermark` | `bigint(20)` | NO | NULL |  | — |  |
| `user_cursor` | `bigint(20)` | NO | NULL |  | — |  |
| `success_at` | `bigint(20)` | YES | NULL |  | — |  |
| `error` | `varchar(512)` | YES | NULL |  | latin1_swedish_ci |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `task_name`, `basis_version`)。

### 外键

- `mt_t_asset_history_job_state`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_asset_history_job_state`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## asset_history_migration

分类：共享。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `migration_id` | `varchar(64)` | NO | NULL |  | latin1_swedish_ci |  |
| `applied_at` | `bigint(20)` | NO | NULL |  | — |  |
| `basis_version` | `varchar(32)` | NO | NULL |  | latin1_swedish_ci |  |

### 索引

- `PRIMARY`：主键，BTREE，(`migration_id`)。

## asset_history_quote_batch

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `batch_id` | `varchar(36)` | NO | NULL |  | latin1_swedish_ci |  |
| `prepared_at` | `bigint(20)` | NO | NULL |  | — |  |
| `evidence` | `longtext` | NO | NULL |  | latin1_swedish_ci |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `batch_id`)。

### 外键

- `mt_t_asset_history_quote_batch`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_asset_history_quote_batch`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## asset_history_revision

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_unicode_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `basis_version` | `varchar(32)` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `level` | `tinyint(4)` | NO | NULL |  | — |  |
| `revision` | `bigint(20)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `user_id`, `basis_version`, `level`)。

### 外键

- `mt_fk_7a92759d9a1c73cd2cf4`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_asset_history_revision`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_asset_history_revision`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## asset_snapshot

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `captured_at` | `bigint(20)` | NO | NULL |  | — |  |
| `total` | `decimal(32,16)` | NO | NULL |  | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `idx_snapshot_user_time`：普通索引，BTREE，(`user_id`, `captured_at`)。
- `mt_fk_6f9daa439f60d4bcb1c5`：普通索引，BTREE，(`tenant_id`, `user_id`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。

### 外键

- `mt_fk_6f9daa439f60d4bcb1c5`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_asset_snapshot`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_asset_snapshot`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## backend_login

分类：控制面。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `normalized_account` | `varchar(128)` | NO | NULL |  | utf8mb4_general_ci |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `subject_type` | `varchar(16)` | NO | NULL |  | utf8mb4_general_ci |  |
| `admin_user_id` | `bigint(20)` | YES | NULL |  | — |  |
| `user_id` | `bigint(20)` | YES | NULL |  | — |  |
| `enabled` | `bit(1)` | NO | "b'1'" |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_backend_account`：唯一索引，BTREE，(`normalized_account`)。
- `uk_backend_admin`：唯一索引，BTREE，(`tenant_id`, `admin_user_id`)。
- `uk_backend_agent`：唯一索引，BTREE，(`tenant_id`, `user_id`)。

### 外键

- `fk_backend_tenant`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_fk_9e9acbde318b55fc3331`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_fk_e695c4f7a0fb2561aa76`：(`tenant_id`, `admin_user_id`) 引用 `admin_user` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_backend_subject_insert`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `mt_backend_subject_update`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## balance_adjustment

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `request_key` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `request_hash` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `actor_type` | `varchar(16)` | YES | NULL |  | utf8mb4_general_ci |  |
| `actor_id` | `bigint(20)` | YES | NULL |  | — |  |
| `reason` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci |  |
| `changes` | `text` | YES | NULL |  | utf8mb4_general_ci |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `mt_fk_90600745af18a146ba7b`：普通索引，BTREE，(`tenant_id`, `user_id`)。
- `mt_old_70804a4177ee6bb0`：普通索引，BTREE，(`request_key`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_balance_request`：唯一索引，BTREE，(`tenant_id`, `request_key`)。

### 外键

- `mt_fk_90600745af18a146ba7b`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_balance_adjustment`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_balance_adjustment`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## calendar_audit

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `environment` | `varchar(4)` | NO | NULL |  | utf8mb4_general_ci |  |
| `event_id` | `varchar(36)` | NO | NULL |  | utf8mb4_general_ci |  |
| `actor` | `varchar(100)` | NO | NULL |  | utf8mb4_general_ci |  |
| `action` | `varchar(40)` | NO | NULL |  | utf8mb4_general_ci |  |
| `reason` | `varchar(1000)` | NO | NULL |  | utf8mb4_general_ci |  |
| `before_json` | `longtext` | YES | NULL |  | utf8mb4_general_ci |  |
| `after_json` | `longtext` | YES | NULL |  | utf8mb4_general_ci |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |

### 索引

- `calendar_audit_event`：普通索引，BTREE，(`tenant_id`, `environment`, `event_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。

## calendar_event

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `environment` | `varchar(4)` | NO | NULL |  | utf8mb4_general_ci |  |
| `event_id` | `varchar(36)` | NO | NULL |  | utf8mb4_general_ci |  |
| `metric` | `varchar(40)` | NO | NULL |  | utf8mb4_general_ci |  |
| `release_date` | `date` | YES | NULL |  | — |  |
| `release_at` | `datetime(6)` | YES | NULL |  | — |  |
| `status` | `varchar(24)` | NO | NULL |  | utf8mb4_general_ci |  |
| `published` | `bit(1)` | NO | NULL |  | — |  |
| `manual_lock` | `bit(1)` | NO | NULL |  | — |  |
| `data_json` | `longtext` | NO | NULL |  | utf8mb4_general_ci |  |
| `upstream_hash` | `varchar(1024)` | YES | NULL |  | utf8mb4_general_ci |  |
| `updated_at` | `datetime(6)` | NO | NULL |  | — |  |
| `row_version` | `bigint(20)` | NO | "0" |  | — |  |

### 索引

- `calendar_window`：普通索引，BTREE，(`tenant_id`, `environment`, `release_date`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_calendar_event`：唯一索引，BTREE，(`tenant_id`, `environment`, `event_id`)。

## calendar_reminder

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `environment` | `varchar(4)` | NO | NULL |  | utf8mb4_general_ci |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `event_id` | `varchar(36)` | NO | NULL |  | utf8mb4_general_ci |  |
| `lead_minutes` | `int(11)` | NO | NULL |  | — |  |
| `timezone` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `enabled` | `bit(1)` | NO | NULL |  | — |  |
| `delivered_at` | `datetime(6)` | YES | NULL |  | — |  |
| `delivered_release_at` | `datetime(6)` | YES | NULL |  | — |  |
| `letter_id` | `bigint(20)` | YES | NULL |  | — |  |
| `updated_at` | `datetime(6)` | NO | NULL |  | — |  |
| `row_version` | `bigint(20)` | NO | "0" |  | — |  |

### 索引

- `calendar_reminder_pending`：普通索引，BTREE，(`tenant_id`, `environment`, `enabled`, `event_id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_calendar_reminder`：唯一索引，BTREE，(`tenant_id`, `environment`, `user_id`, `event_id`, `lead_minutes`)。

## calendar_source

分类：共享。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `varchar(48)` | NO | NULL |  | utf8mb4_general_ci |  |
| `environment` | `varchar(4)` | NO | NULL |  | utf8mb4_general_ci |  |
| `source_id` | `varchar(24)` | NO | NULL |  | utf8mb4_general_ci |  |
| `status` | `varchar(24)` | NO | NULL |  | utf8mb4_general_ci |  |
| `last_attempt` | `datetime(6)` | YES | NULL |  | — |  |
| `last_success` | `datetime(6)` | YES | NULL |  | — |  |
| `next_attempt` | `datetime(6)` | YES | NULL |  | — |  |
| `lease_until` | `datetime(6)` | YES | NULL |  | — |  |
| `budget_date` | `date` | YES | NULL |  | — |  |
| `requests_today` | `int(11)` | NO | "0" |  | — |  |
| `failures` | `int(11)` | NO | "0" |  | — |  |
| `http_status` | `int(11)` | YES | NULL |  | — |  |
| `last_error` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci |  |
| `etag` | `varchar(300)` | YES | NULL |  | utf8mb4_general_ci |  |
| `last_modified` | `varchar(300)` | YES | NULL |  | utf8mb4_general_ci |  |
| `payload` | `longtext` | YES | NULL |  | utf8mb4_general_ci |  |
| `parsed_json` | `longtext` | YES | NULL |  | utf8mb4_general_ci |  |
| `row_version` | `bigint(20)` | NO | "0" |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`id`)。

## calendar_source_update

分类：共享。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `environment` | `varchar(4)` | NO | NULL |  | utf8mb4_general_ci |  |
| `source_id` | `varchar(24)` | NO | NULL |  | utf8mb4_general_ci |  |
| `status` | `varchar(24)` | NO | NULL |  | utf8mb4_general_ci |  |
| `http_status` | `int(11)` | YES | NULL |  | — |  |
| `response_hash` | `varchar(64)` | YES | NULL |  | utf8mb4_general_ci |  |
| `error` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci |  |
| `captured_at` | `datetime(6)` | NO | NULL |  | — |  |

### 索引

- `calendar_source_history`：普通索引，BTREE，(`environment`, `source_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。

## contract_order

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `close_time` | `datetime(6)` | YES | NULL |  | — |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |
| `current_price` | `decimal(32,16)` | YES | NULL |  | — |  |
| `fee` | `decimal(32,16)` | YES | NULL |  | — |  |
| `margin` | `decimal(32,16)` | YES | NULL |  | — |  |
| `open_price` | `decimal(32,16)` | YES | NULL |  | — |  |
| `open_time` | `datetime(6)` | YES | NULL |  | — |  |
| `price` | `decimal(32,16)` | YES | NULL |  | — |  |
| `profit` | `decimal(32,16)` | YES | NULL |  | — |  |
| `quantity` | `decimal(32,16)` | NO | NULL |  | — |  |
| `side` | `varchar(10)` | NO | NULL |  | utf8mb4_general_ci |  |
| `status` | `varchar(20)` | NO | NULL |  | utf8mb4_general_ci |  |
| `stop_loss` | `decimal(32,16)` | YES | NULL |  | — |  |
| `symbol` | `varchar(32)` | NO | NULL |  | utf8mb4_general_ci |  |
| `take_profit` | `decimal(32,16)` | YES | NULL |  | — |  |
| `type` | `varchar(10)` | NO | NULL |  | utf8mb4_general_ci |  |
| `updated_at` | `datetime(6)` | NO | NULL |  | — |  |
| `user_id` | `bigint(20)` | YES | NULL |  | — |  |
| `close_price` | `decimal(32,16)` | YES | NULL |  | — |  |
| `leverage` | `decimal(10,2)` | YES | NULL |  | — |  |
| `limit_match_enabled` | `bit(1)` | NO | NULL |  | — |  |
| `lot_size` | `decimal(32,16)` | YES | NULL |  | — |  |
| `row_version` | `bigint(20)` | NO | NULL |  | — |  |
| `margin_conversion_rate` | `decimal(32,16)` | YES | NULL |  | — |  |
| `quote_currency` | `varchar(16)` | YES | NULL |  | utf8mb4_general_ci |  |
| `quote_source` | `varchar(16)` | YES | NULL |  | utf8mb4_general_ci |  |
| `settlement_conversion_rate` | `decimal(32,16)` | YES | NULL |  | — |  |
| `order_source` | `varchar(24)` | NO | "USER" |  | utf8mb4_general_ci |  |
| `manual_wallet_enabled` | `tinyint(1)` | NO | "0" |  | — |  |
| `manual_equity_enabled` | `tinyint(1)` | NO | "0" |  | — |  |
| `trial_reserved` | `decimal(32,16)` | YES | NULL |  | — |  |
| `deleted_at` | `datetime(6)` | YES | NULL |  | — |  |
| `deleted_by` | `varchar(64)` | YES | NULL |  | utf8mb4_general_ci |  |
| `fx_base_currency` | `varchar(3)` | YES | NULL |  | utf8mb4_general_ci |  |
| `min_order_notional` | `decimal(32,16)` | YES | NULL |  | — |  |
| `min_order_quantity` | `decimal(32,16)` | YES | NULL |  | — |  |
| `quantity_asset` | `varchar(16)` | YES | NULL |  | utf8mb4_general_ci |  |
| `quantity_step` | `decimal(32,16)` | YES | NULL |  | — |  |
| `quantity_unit_type` | `varchar(16)` | YES | NULL |  | utf8mb4_general_ci |  |
| `spec_version` | `bigint(20)` | YES | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `funding_source` | `varchar(16)` | YES | NULL |  | utf8mb4_general_ci |  |
| `trial_allocations` | `varchar(4000)` | YES | NULL |  | utf8mb4_general_ci |  |
| `request_key` | `varchar(64)` | YES | NULL |  | ascii_bin |  |
| `request_hash` | `varchar(64)` | YES | NULL |  | ascii_bin |  |
| `promotion_pending` | `bit(1)` | NO | "b'0'" |  | — |  |

### 索引

- `ix_contract_promotion`：普通索引，BTREE，(`tenant_id`, `promotion_pending`, `id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `mt_tenant_status`：普通索引，BTREE，(`tenant_id`, `status`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_contract_order_request`：唯一索引，BTREE，(`tenant_id`, `user_id`, `request_key`)。

### 外键

- `mt_fk_d47d5ad36fd99730d76f`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_contract_order`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `manual_unbound_insert`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `manual_unbound_update`：BEFORE UPDATE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `mt_immutable_contract_order`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## control_access_session

分类：控制面。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `actor_id` | `bigint(20)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `ticket_hash` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `browser_binding_hash` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `actor_version` | `bigint(20)` | NO | NULL |  | — |  |
| `expires_at` | `datetime(6)` | NO | NULL |  | — |  |
| `ticket_expires_at` | `datetime(6)` | NO | NULL |  | — |  |
| `last_activity_at` | `datetime(6)` | NO | NULL |  | — |  |
| `consumed` | `bit(1)` | NO | "b'0'" |  | — |  |
| `revoked` | `bit(1)` | NO | "b'0'" |  | — |  |
| `row_version` | `bigint(20)` | NO | "0" |  | — |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |

### 索引

- `fk_control_session_actor`：普通索引，BTREE，(`actor_id`)。
- `ix_control_session_tenant`：普通索引，BTREE，(`tenant_id`, `expires_at`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_control_ticket`：唯一索引，BTREE，(`ticket_hash`)。

### 外键

- `fk_control_session_actor`：(`actor_id`) 引用 `control_admin` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `fk_control_session_tenant`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

## control_admin

分类：控制面。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `account` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `password_hash` | `varchar(128)` | NO | NULL |  | utf8mb4_general_ci |  |
| `enabled` | `bit(1)` | NO | "b'1'" |  | — |  |
| `mfa_secret` | `text` | YES | NULL |  | utf8mb4_general_ci |  |
| `mfa_enabled` | `bit(1)` | NO | "b'0'" |  | — |  |
| `session_version` | `bigint(20)` | NO | "0" |  | — |  |
| `row_version` | `bigint(20)` | NO | "0" |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_control_account`：唯一索引，BTREE，(`account`)。

## control_audit_log

分类：控制面。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `actor_id` | `bigint(20)` | YES | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | YES | NULL |  | — |  |
| `access_session_id` | `varchar(64)` | YES | NULL |  | utf8mb4_general_ci |  |
| `request_id` | `varchar(64)` | YES | NULL |  | utf8mb4_general_ci |  |
| `action` | `varchar(128)` | NO | NULL |  | utf8mb4_general_ci |  |
| `object_ref` | `varchar(255)` | YES | NULL |  | utf8mb4_general_ci |  |
| `outcome` | `varchar(32)` | NO | NULL |  | utf8mb4_general_ci |  |
| `detail` | `text` | YES | NULL |  | utf8mb4_general_ci |  |
| `reason` | `varchar(512)` | YES | NULL |  | utf8mb4_general_ci |  |
| `remote_address` | `varchar(64)` | YES | NULL |  | utf8mb4_general_ci |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |

### 索引

- `ix_control_audit_actor`：普通索引，BTREE，(`actor_id`, `created_at`, `id`)。
- `ix_control_audit_tenant`：普通索引，BTREE，(`tenant_id`, `created_at`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。

### 触发器

- `mt_control_audit_no_delete`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `mt_control_audit_no_update`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## control_chat_archive_job

分类：控制面。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `varchar(36)` | NO | NULL |  | utf8mb4_general_ci |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `conversation_id` | `bigint(20)` | NO | NULL |  | — |  |
| `actor_id` | `bigint(20)` | YES | NULL |  | — |  |
| `owner_key` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `request_key` | `varchar(36)` | NO | NULL |  | utf8mb4_general_ci |  |
| `request_hash` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `reason` | `varchar(512)` | NO | NULL |  | utf8mb4_general_ci |  |
| `state` | `varchar(16)` | NO | NULL |  | utf8mb4_general_ci |  |
| `end_id` | `bigint(20)` | NO | NULL |  | — |  |
| `expected_messages` | `bigint(20)` | NO | NULL |  | — |  |
| `processed_messages` | `bigint(20)` | NO | "0" |  | — |  |
| `cursor_id` | `bigint(20)` | NO | "0" |  | — |  |
| `chunk_sequence` | `int(11)` | NO | "0" |  | — |  |
| `attachment_bytes` | `bigint(20)` | NO | "0" |  | — |  |
| `chain_hash` | `varchar(64)` | NO | "" |  | utf8mb4_general_ci |  |
| `snapshot_sha256` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `expected_last_hash` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `final_manifest_sha256` | `varchar(64)` | YES | NULL |  | utf8mb4_general_ci |  |
| `failure_type` | `varchar(128)` | YES | NULL |  | utf8mb4_general_ci |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |
| `updated_at` | `datetime(6)` | NO | NULL |  | — |  |

### 索引

- `archive_actor`：普通索引，BTREE，(`actor_id`)。
- `archive_owner`：普通索引，BTREE，(`tenant_id`, `actor_id`, `conversation_id`, `created_at`)。
- `archive_pending`：普通索引，BTREE，(`state`, `updated_at`, `id`)。
- `archive_request`：唯一索引，BTREE，(`tenant_id`, `conversation_id`, `owner_key`, `request_key`)。
- `PRIMARY`：主键，BTREE，(`id`)。

### 外键

- `archive_actor`：(`actor_id`) 引用 `control_admin` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `archive_tenant`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

## control_table_preference

分类：控制面。引擎：`InnoDB`。排序规则：`utf8mb4_unicode_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `varchar(160)` | NO | NULL |  | utf8mb4_bin |  |
| `actor_id` | `bigint(20)` | NO | NULL |  | — |  |
| `table_key` | `varchar(100)` | NO | NULL |  | utf8mb4_bin |  |
| `columns_json` | `longtext` | NO | NULL |  | utf8mb4_unicode_ci |  |

### 索引

- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_control_preference`：唯一索引，BTREE，(`actor_id`, `table_key`)。

### 外键

- `mt_control_preference_actor`：(`actor_id`) 引用 `control_admin` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

## demo_account

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `cash` | `decimal(32,8)` | NO | NULL |  | — |  |
| `generation` | `int(11)` | NO | "1" |  | — |  |
| `last_reset_at` | `datetime(6)` | YES | NULL |  | — |  |
| `last_reset_key` | `varchar(255)` | YES | NULL |  | utf8mb4_general_ci |  |
| `version` | `bigint(20)` | YES | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `user_id`)。
- `PRIMARY`：主键，BTREE，(`user_id`)。

### 外键

- `mt_fk_e6544798bb4730d7b306`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_demo_account`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_demo_account`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## demo_ledger

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `varchar(36)` | NO | NULL |  | utf8mb4_general_ci |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `generation` | `int(11)` | NO | NULL |  | — |  |
| `type` | `varchar(16)` | NO | NULL |  | utf8mb4_general_ci |  |
| `order_id` | `varchar(36)` | YES | NULL |  | utf8mb4_general_ci |  |
| `delta` | `decimal(32,8)` | NO | NULL |  | — |  |
| `balance_after` | `decimal(32,8)` | NO | NULL |  | — |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `idx_demo_ledger_owner_time`：普通索引，BTREE，(`user_id`, `created_at`)。
- `mt_fk_21b8187c084d5be03533`：普通索引，BTREE，(`tenant_id`, `user_id`)。
- `mt_fk_f81e633478046eee721c`：普通索引，BTREE，(`tenant_id`, `order_id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。

### 外键

- `mt_fk_21b8187c084d5be03533`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_fk_f81e633478046eee721c`：(`tenant_id`, `order_id`) 引用 `demo_order` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_demo_ledger`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_demo_ledger`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## demo_order

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `varchar(36)` | NO | NULL |  | utf8mb4_general_ci |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `request_key` | `varchar(36)` | NO | NULL |  | utf8mb4_general_ci |  |
| `generation` | `int(11)` | NO | NULL |  | — |  |
| `symbol` | `varchar(32)` | NO | NULL |  | utf8mb4_general_ci |  |
| `market_code` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `status` | `varchar(16)` | NO | NULL |  | utf8mb4_general_ci |  |
| `amount` | `decimal(32,8)` | NO | NULL |  | — |  |
| `quantity` | `decimal(32,16)` | NO | NULL |  | — |  |
| `open_price` | `decimal(32,16)` | NO | NULL |  | — |  |
| `close_price` | `decimal(32,16)` | YES | NULL |  | — |  |
| `open_fee` | `decimal(32,8)` | NO | NULL |  | — |  |
| `close_fee` | `decimal(32,8)` | YES | NULL |  | — |  |
| `realized_pnl` | `decimal(32,8)` | YES | NULL |  | — |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |
| `closed_at` | `datetime(6)` | YES | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `idx_demo_order_owner_status`：普通索引，BTREE，(`user_id`, `status`, `created_at`)。
- `mt_old_1ff2d1f57f38b42a`：普通索引，BTREE，(`user_id`, `request_key`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `mt_tenant_status`：普通索引，BTREE，(`tenant_id`, `status`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_demo_order_request`：唯一索引，BTREE，(`tenant_id`, `user_id`, `request_key`)。

### 外键

- `mt_fk_949ab0b45abb4cf3ecf6`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_demo_order`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_demo_order`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## deposit_credit_record

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `deposit_record_id` | `bigint(20)` | NO | NULL |  | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `account_type` | `varchar(16)` | NO | NULL |  | utf8mb4_general_ci |  |
| `amount_usd` | `decimal(32,16)` | NO | NULL |  | — |  |
| `balance_before` | `decimal(32,16)` | NO | NULL |  | — |  |
| `balance_after` | `decimal(32,16)` | NO | NULL |  | — |  |
| `operator_type` | `varchar(24)` | NO | NULL |  | utf8mb4_general_ci |  |
| `operator_id` | `bigint(20)` | NO | NULL |  | — |  |
| `operator_name` | `varchar(128)` | YES | NULL |  | utf8mb4_general_ci |  |
| `credited_at` | `datetime(6)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `fk_deposit_credit_user`：普通索引，BTREE，(`user_id`)。
- `mt_fk_ffab7bdc031c083df005`：普通索引，BTREE，(`tenant_id`, `user_id`)。
- `mt_old_83a546e1d74c9f4b`：普通索引，BTREE，(`deposit_record_id`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_deposit_credit`：唯一索引，BTREE，(`tenant_id`, `deposit_record_id`)。

### 外键

- `fk_deposit_credit_order`：(`deposit_record_id`) 引用 `deposit_record` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `fk_deposit_credit_user`：(`user_id`) 引用 `user_account` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_fk_26642a321d900586598e`：(`tenant_id`, `deposit_record_id`) 引用 `deposit_record` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_fk_ffab7bdc031c083df005`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_deposit_credit_record`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_deposit_credit_record`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## deposit_record

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `address` | `varchar(200)` | NO | NULL |  | utf8mb4_general_ci |  |
| `amount` | `decimal(32,16)` | NO | NULL |  | — |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |
| `network` | `varchar(50)` | NO | NULL |  | utf8mb4_general_ci |  |
| `proof_image` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci |  |
| `remark` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci |  |
| `status` | `varchar(20)` | NO | NULL |  | utf8mb4_general_ci |  |
| `type` | `varchar(20)` | NO | NULL |  | utf8mb4_general_ci |  |
| `updated_at` | `datetime(6)` | NO | NULL |  | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `row_version` | `bigint(20)` | NO | NULL |  | — |  |
| `currency` | `varchar(3)` | YES | NULL |  | utf8mb4_general_ci |  |
| `exchange_rate` | `decimal(32,16)` | YES | NULL |  | — |  |
| `original_amount` | `decimal(32,16)` | YES | NULL |  | — |  |
| `order_no` | `varchar(64)` | YES | NULL |  | utf8mb4_general_ci |  |
| `source` | `varchar(32)` | YES | NULL |  | utf8mb4_general_ci |  |
| `account_type` | `varchar(16)` | YES | NULL |  | utf8mb4_general_ci |  |
| `manual_purpose` | `varchar(24)` | YES | NULL |  | utf8mb4_general_ci |  |
| `idempotency_key` | `varchar(64)` | YES | NULL |  | utf8mb4_general_ci |  |
| `request_hash` | `varchar(64)` | YES | NULL |  | utf8mb4_general_ci |  |
| `review_remark` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci |  |
| `created_by_type` | `varchar(24)` | YES | NULL |  | utf8mb4_general_ci |  |
| `created_by_id` | `bigint(20)` | YES | NULL |  | — |  |
| `created_by_name` | `varchar(128)` | YES | NULL |  | utf8mb4_general_ci |  |
| `reviewed_by_type` | `varchar(24)` | YES | NULL |  | utf8mb4_general_ci |  |
| `reviewed_by_id` | `bigint(20)` | YES | NULL |  | — |  |
| `reviewed_by_name` | `varchar(128)` | YES | NULL |  | utf8mb4_general_ci |  |
| `fee_rate` | `decimal(32,16)` | YES | NULL |  | — |  |
| `fee_amount` | `decimal(32,16)` | YES | NULL |  | — |  |
| `reviewed_at` | `datetime(6)` | YES | NULL |  | — |  |
| `credited_at` | `datetime(6)` | YES | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `ix_deposit_created`：普通索引，BTREE，(`created_at`, `id`)。
- `ix_deposit_reviewed`：普通索引，BTREE，(`reviewed_at`, `id`)。
- `ix_deposit_source`：普通索引，BTREE，(`source`, `status`, `credited_at`)。
- `ix_deposit_status`：普通索引，BTREE，(`status`, `created_at`, `id`)。
- `ix_deposit_user`：普通索引，BTREE，(`user_id`, `created_at`, `id`)。
- `mt_fk_1b89baafca4b889ed818`：普通索引，BTREE，(`tenant_id`, `user_id`)。
- `mt_old_40a023208e9a4af1`：普通索引，BTREE，(`created_by_type`, `created_by_id`, `idempotency_key`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `mt_tenant_status`：普通索引，BTREE，(`tenant_id`, `status`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_deposit_order_no`：唯一索引，BTREE，(`order_no`)。
- `uk_deposit_request`：唯一索引，BTREE，(`tenant_id`, `created_by_type`, `created_by_id`, `idempotency_key`)。

### 外键

- `mt_fk_1b89baafca4b889ed818`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_deposit_record`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_deposit_record`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## deposit_setting

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：充值设置表

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `type` | `varchar(20)` | NO | "digital" |  | utf8mb4_general_ci | 类型: digital 数字货币, bank 银行卡 |
| `network` | `varchar(50)` | YES | NULL |  | utf8mb4_general_ci | 网络/币种，如 USDC-ERC20, USDT-TRC20（银行卡时可为空） |
| `address` | `varchar(200)` | YES | NULL |  | utf8mb4_general_ci | 充值地址（银行卡时可为空） |
| `bank_name` | `varchar(100)` | YES | NULL |  | utf8mb4_general_ci | 开户银行 |
| `bank_account` | `varchar(50)` | YES | NULL |  | utf8mb4_general_ci | 银行卡号 |
| `account_name` | `varchar(100)` | YES | NULL |  | utf8mb4_general_ci | 户名 |
| `qr_code` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci | 二维码图片URL |
| `enabled` | `tinyint(1)` | NO | "1" |  | — | 是否启用 |
| `created_at` | `datetime` | NO | NULL |  | — |  |
| `updated_at` | `datetime` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `mt_old_21f857c7ae3e9b26`：普通索引，BTREE，(`network`, `type`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_network_type`：唯一索引，BTREE，(`tenant_id`, `network`, `type`)。

### 外键

- `mt_t_deposit_setting`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_deposit_setting`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## financial_order

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：理财订单表

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — | 用户ID |
| `product_id` | `bigint(20)` | NO | NULL |  | — | 产品ID |
| `product_name` | `varchar(100)` | NO | NULL |  | utf8mb4_general_ci | 产品名称 |
| `purchase_amount` | `decimal(32,16)` | NO | NULL |  | — | 申购数量（金额） |
| `currency` | `varchar(10)` | NO | "USD" |  | utf8mb4_general_ci | 货币类型 |
| `daily_yield_rate` | `decimal(8,6)` | NO | NULL |  | — | 日产率 |
| `daily_yield` | `decimal(32,16)` | NO | NULL |  | — | 预计日产（金额） |
| `total_yield` | `decimal(32,16)` | NO | NULL |  | — | 预计总收益 |
| `term_days` | `int(11)` | NO | NULL |  | — | 理财期限（天数） |
| `penalty_rate` | `decimal(8,6)` | YES | "0.300000" |  | — | 违约赎回费率 |
| `penalty_amount` | `decimal(32,16)` | YES | "0.0000000000000000" |  | — | 违约金金额 |
| `status` | `varchar(20)` | NO | "IN_PROGRESS" |  | utf8mb4_general_ci | 状态：IN_PROGRESS进行中, COMPLETED已结束, REDEEMED已赎回 |
| `purchase_time` | `datetime` | NO | NULL |  | — | 申购时间 |
| `end_time` | `datetime` | YES | NULL |  | — | 结束时间 |
| `redeem_time` | `datetime` | YES | NULL |  | — | 赎回时间 |
| `created_at` | `datetime` | NO | NULL |  | — |  |
| `updated_at` | `datetime` | NO | NULL |  | — |  |
| `row_version` | `bigint(20)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `request_key` | `varchar(64)` | YES | NULL |  | ascii_bin |  |
| `request_hash` | `varchar(64)` | YES | NULL |  | ascii_bin |  |
| `last_accrued_date` | `date` | YES | NULL |  | — |  |
| `accrued_yield` | `decimal(32,16)` | YES | NULL |  | — |  |

### 索引

- `idx_product_id`：普通索引，BTREE，(`product_id`)。
- `idx_purchase_time`：普通索引，BTREE，(`purchase_time`)。
- `idx_status`：普通索引，BTREE，(`status`)。
- `idx_user_id`：普通索引，BTREE，(`user_id`)。
- `mt_fk_285bfb9b3be5af402cc8`：普通索引，BTREE，(`tenant_id`, `product_id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `mt_tenant_status`：普通索引，BTREE，(`tenant_id`, `status`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_financial_order_request`：唯一索引，BTREE，(`tenant_id`, `user_id`, `request_key`)。

### 外键

- `mt_fk_285bfb9b3be5af402cc8`：(`tenant_id`, `product_id`) 引用 `financial_product` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_fk_e9329799dd0d7b84068e`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_financial_order`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_financial_order`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## financial_product

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：理财产品表

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `name` | `varchar(100)` | NO | NULL |  | utf8mb4_general_ci | 产品名称（如180MH/S） |
| `image_url` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci | 产品图片 |
| `currency` | `varchar(10)` | NO | "USD" |  | utf8mb4_general_ci | 货币类型 |
| `daily_yield_rate` | `decimal(8,6)` | NO | NULL |  | — | 预计日产率（百分比，如0.3表示0.3%） |
| `rental_fee` | `decimal(32,16)` | NO | NULL |  | — | 矿机租金 |
| `min_purchase` | `decimal(32,16)` | NO | NULL |  | — | 最小申购金额 |
| `max_purchase` | `decimal(32,16)` | NO | NULL |  | — | 最大申购金额 |
| `term_days` | `int(11)` | NO | NULL |  | — | 理财期限（天数） |
| `penalty_rate` | `decimal(8,6)` | YES | "0.300000" |  | — | 违约赎回费率（百分比，如30表示30%） |
| `description` | `text` | YES | NULL |  | utf8mb4_general_ci | 产品介绍 |
| `enabled` | `tinyint(1)` | NO | "1" |  | — | 是否启用 |
| `sort_order` | `int(11)` | YES | "0" |  | — | 排序 |
| `created_at` | `datetime` | NO | NULL |  | — |  |
| `updated_at` | `datetime` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `idx_enabled`：普通索引，BTREE，(`enabled`)。
- `idx_sort_order`：普通索引，BTREE，(`sort_order`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。

### 外键

- `mt_t_financial_product`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_financial_product`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## financial_yield_record

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |
| `cumulative_yield` | `decimal(32,16)` | NO | NULL |  | — |  |
| `daily_yield` | `decimal(32,16)` | NO | NULL |  | — |  |
| `order_id` | `bigint(20)` | NO | NULL |  | — |  |
| `paid_at` | `datetime(6)` | YES | NULL |  | — |  |
| `product_id` | `bigint(20)` | NO | NULL |  | — |  |
| `product_name` | `varchar(100)` | NO | NULL |  | utf8mb4_general_ci |  |
| `status` | `varchar(20)` | NO | NULL |  | utf8mb4_general_ci |  |
| `updated_at` | `datetime(6)` | NO | NULL |  | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `yield_date` | `date` | NO | NULL |  | — |  |
| `row_version` | `bigint(20)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `mt_fk_203e06ffb45190de8b0b`：普通索引，BTREE，(`tenant_id`, `user_id`)。
- `mt_fk_2f2bf34b7500721b0302`：普通索引，BTREE，(`tenant_id`, `product_id`)。
- `mt_old_f051d563ecfeb18a`：普通索引，BTREE，(`order_id`, `yield_date`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `mt_tenant_status`：普通索引，BTREE，(`tenant_id`, `status`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `UK9dll9ic2smexlc46rjowsrvdy`：唯一索引，BTREE，(`tenant_id`, `order_id`, `yield_date`)。

### 外键

- `mt_fk_203e06ffb45190de8b0b`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_fk_2f2bf34b7500721b0302`：(`tenant_id`, `product_id`) 引用 `financial_product` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_fk_98080e1d022123a73687`：(`tenant_id`, `order_id`) 引用 `financial_order` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_financial_yield_record`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_financial_yield_record`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## inbox_letter

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `admin_id` | `bigint(20)` | YES | NULL |  | — |  |
| `request_id` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `title` | `varchar(120)` | NO | NULL |  | utf8mb4_general_ci |  |
| `content` | `varchar(4000)` | NO | NULL |  | utf8mb4_general_ci |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |
| `read_at` | `datetime(6)` | YES | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `control_actor_id` | `bigint(20)` | YES | NULL |  | — |  |

### 索引

- `inbox_recipient`：普通索引，BTREE，(`user_id`, `id`)。
- `mt_fk_b3e30a8534726e1a3b7e`：普通索引，BTREE，(`tenant_id`, `user_id`)。
- `mt_inbox_control_actor`：普通索引，BTREE，(`control_actor_id`)。
- `mt_old_841d0982f7b3d514`：普通索引，BTREE，(`admin_id`, `request_id`, `user_id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_inbox_control_request`：唯一索引，BTREE，(`tenant_id`, `control_actor_id`, `request_id`, `user_id`)。
- `uk_inbox_request`：唯一索引，BTREE，(`tenant_id`, `admin_id`, `request_id`, `user_id`)。

### 外键

- `mt_fk_b3e30a8534726e1a3b7e`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_fk_cd78d780af9736dd2323`：(`tenant_id`, `admin_id`) 引用 `admin_user` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_inbox_control_actor`：(`control_actor_id`) 引用 `control_admin` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_inbox_letter`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_inbox_letter`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## kyc_record

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：实名认证记录表

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — | 用户ID |
| `real_name` | `varchar(100)` | NO | NULL |  | utf8mb4_general_ci | 真实姓名 |
| `id_number` | `varchar(50)` | NO | NULL |  | utf8mb4_general_ci | 证件号 |
| `id_front_image` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci | 证件正面图片 |
| `id_back_image` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci | 证件反面图片 |
| `status` | `varchar(20)` | NO | "PENDING" |  | utf8mb4_general_ci | 状态: PENDING-待审核, APPROVED-已通过, REJECTED-已拒绝 |
| `review_remark` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci | 审核备注 |
| `reviewed_by` | `bigint(20)` | YES | NULL |  | — | 审核人ID |
| `reviewed_at` | `datetime` | YES | NULL |  | — | 审核时间 |
| `created_at` | `datetime` | NO | NULL |  | — | 创建时间 |
| `updated_at` | `datetime` | NO | NULL |  | — | 更新时间 |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `idx_created_at`：普通索引，BTREE，(`created_at`)。
- `idx_status`：普通索引，BTREE，(`status`)。
- `idx_user_id`：普通索引，BTREE，(`user_id`)。
- `mt_fk_c989412821d3c66f68a3`：普通索引，BTREE，(`tenant_id`, `user_id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `mt_tenant_status`：普通索引，BTREE，(`tenant_id`, `status`)。
- `PRIMARY`：主键，BTREE，(`id`)。

### 外键

- `mt_fk_c989412821d3c66f68a3`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_kyc_record`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_kyc_record`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## loan_personal_info

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：贷款个人信息表

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — | 用户ID |
| `real_name` | `varchar(100)` | NO | NULL |  | utf8mb4_general_ci | 真实姓名 |
| `id_number` | `varchar(50)` | NO | NULL |  | utf8mb4_general_ci | 身份证号 |
| `phone` | `varchar(32)` | NO | NULL |  | utf8mb4_general_ci | 电话 |
| `address` | `varchar(500)` | NO | NULL |  | utf8mb4_general_ci | 家庭住址 |
| `status` | `varchar(20)` | NO | "PENDING" |  | utf8mb4_general_ci | 状态：PENDING待审核, APPROVED已通过, REJECTED已拒绝 |
| `review_remark` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci | 审核备注 |
| `reviewed_by` | `bigint(20)` | YES | NULL |  | — | 审核人ID |
| `reviewed_at` | `datetime` | YES | NULL |  | — | 审核时间 |
| `created_at` | `datetime` | NO | NULL |  | — |  |
| `updated_at` | `datetime` | NO | NULL |  | — |  |
| `id_back_image` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci |  |
| `handheld_image` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci | 手持身份证图片 |
| `id_front_image` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `idx_created_at`：普通索引，BTREE，(`created_at`)。
- `idx_status`：普通索引，BTREE，(`status`)。
- `mt_old_360bbc5a40864d35`：普通索引，BTREE，(`user_id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `mt_tenant_status`：普通索引，BTREE，(`tenant_id`, `status`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_user_id`：唯一索引，BTREE，(`tenant_id`, `user_id`)。

### 外键

- `mt_fk_6900f465a9d936413d13`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_loan_personal_info`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_loan_personal_info`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## loan_record

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `actual_repayment_at` | `datetime(6)` | YES | NULL |  | — |  |
| `amount` | `decimal(32,16)` | NO | NULL |  | — |  |
| `approved_at` | `datetime(6)` | YES | NULL |  | — |  |
| `contract_signed` | `bit(1)` | NO | NULL |  | — |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |
| `daily_rate` | `decimal(8,6)` | NO | NULL |  | — |  |
| `days` | `int(11)` | NO | NULL |  | — |  |
| `free_days` | `int(11)` | NO | NULL |  | — |  |
| `overdue_fee` | `decimal(32,16)` | YES | NULL |  | — |  |
| `overdue_rate` | `decimal(8,6)` | YES | NULL |  | — |  |
| `remark` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci |  |
| `repayment_amount` | `decimal(32,16)` | NO | NULL |  | — |  |
| `repayment_date` | `datetime(6)` | YES | NULL |  | — |  |
| `signature_image` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci |  |
| `status` | `varchar(20)` | NO | NULL |  | utf8mb4_general_ci |  |
| `total_interest` | `decimal(32,16)` | NO | NULL |  | — |  |
| `updated_at` | `datetime(6)` | NO | NULL |  | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `address` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci |  |
| `id_number` | `varchar(50)` | YES | NULL |  | utf8mb4_general_ci |  |
| `phone` | `varchar(32)` | YES | NULL |  | utf8mb4_general_ci |  |
| `real_name` | `varchar(100)` | YES | NULL |  | utf8mb4_general_ci |  |
| `row_version` | `bigint(20)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `request_key` | `varchar(64)` | YES | NULL |  | ascii_bin |  |
| `request_hash` | `varchar(64)` | YES | NULL |  | ascii_bin |  |

### 索引

- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `mt_tenant_status`：普通索引，BTREE，(`tenant_id`, `status`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_loan_record_request`：唯一索引，BTREE，(`tenant_id`, `user_id`, `request_key`)。

### 外键

- `mt_fk_7ad261b8f0c18e96fda3`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_loan_record`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_loan_record`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## loan_setting

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |
| `daily_rate` | `decimal(8,6)` | NO | NULL |  | — |  |
| `days` | `int(11)` | NO | NULL |  | — |  |
| `enabled` | `bit(1)` | NO | NULL |  | — |  |
| `free_days` | `int(11)` | NO | NULL |  | — |  |
| `max_amount` | `decimal(32,16)` | YES | NULL |  | — |  |
| `min_amount` | `decimal(32,16)` | YES | NULL |  | — |  |
| `overdue_rate` | `decimal(8,6)` | YES | NULL |  | — |  |
| `updated_at` | `datetime(6)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。

### 外键

- `mt_t_loan_setting`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_loan_setting`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## manual_order_binding

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `order_id` | `bigint(20)` | NO | NULL |  | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `operator_id` | `bigint(20)` | NO | NULL |  | — |  |
| `idempotency_key` | `varchar(64)` | NO | NULL |  | ascii_bin |  |
| `request_hash` | `char(64)` | NO | NULL |  | ascii_general_ci |  |
| `created_at` | `bigint(20)` | NO | NULL |  | — |  |
| `evidence` | `longtext` | NO | NULL |  | utf8mb4_general_ci |  |

### 索引

- `fk_manual_binding_user`：普通索引，BTREE，(`tenant_id`, `user_id`)。
- `PRIMARY`：主键，BTREE，(`tenant_id`, `idempotency_key`)。
- `uk_manual_binding_order`：唯一索引，BTREE，(`tenant_id`, `order_id`)。

### 外键

- `fk_manual_binding_order`：(`tenant_id`, `order_id`) 引用 `contract_order` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `fk_manual_binding_user`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_manual_order_binding`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_manual_order_binding`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## manual_order_record

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `idempotency_key` | `varchar(64)` | NO | NULL |  | ascii_bin |  |
| `request_hash` | `char(64)` | NO | NULL |  | ascii_general_ci |  |
| `order_id` | `bigint(20)` | NO | NULL |  | — |  |
| `operator_id` | `bigint(20)` | NO | NULL |  | — |  |
| `user_id` | `bigint(20)` | YES | NULL |  | — |  |
| `created_at` | `bigint(20)` | NO | NULL |  | — |  |
| `timezone` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `evidence` | `longtext` | NO | NULL |  | utf8mb4_general_ci |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `mt_fk_bcb7c2341e13bdd9a1be`：普通索引，BTREE，(`tenant_id`, `user_id`)。
- `mt_old_19f3dec96fa122ce`：普通索引，BTREE，(`order_id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `PRIMARY`：主键，BTREE，(`tenant_id`, `idempotency_key`)。
- `uk_manual_order`：唯一索引，BTREE，(`tenant_id`, `order_id`)。

### 外键

- `mt_fk_6ad40e17ee136a6c60bf`：(`tenant_id`, `order_id`) 引用 `contract_order` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_fk_bcb7c2341e13bdd9a1be`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_manual_order_record`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_manual_order_record`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_control_command

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_unicode_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `id` | `varchar(36)` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `symbol_id` | `bigint(20)` | NO | NULL |  | — |  |
| `request_key` | `varchar(64)` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `parameter_hash` | `varchar(64)` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `parameters_json` | `text` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `state` | `varchar(16)` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `actor_id` | `bigint(20)` | NO | NULL |  | — |  |
| `session_id` | `varchar(64)` | YES | NULL |  | utf8mb4_unicode_ci |  |
| `config_revision` | `bigint(20)` | NO | NULL |  | — |  |
| `control_revision` | `bigint(20)` | NO | NULL |  | — |  |
| `writer_generation` | `bigint(20)` | YES | NULL |  | — |  |
| `owner_id` | `varchar(36)` | YES | NULL |  | utf8mb4_unicode_ci |  |
| `seed` | `bigint(20)` | NO | NULL |  | — |  |
| `accepted_at` | `bigint(20)` | NO | NULL |  | — |  |
| `expires_at` | `bigint(20)` | NO | NULL |  | — |  |
| `prepared_json` | `mediumtext` | YES | NULL |  | utf8mb4_unicode_ci |  |
| `task_id` | `varchar(36)` | YES | NULL |  | utf8mb4_unicode_ci |  |
| `error_code` | `varchar(64)` | YES | NULL |  | utf8mb4_unicode_ci |  |
| `message` | `varchar(255)` | YES | NULL |  | utf8mb4_unicode_ci |  |

### 索引

- `command_queue`：普通索引，BTREE，(`tenant_id`, `state`, `accepted_at`)。
- `command_request`：唯一索引，BTREE，(`tenant_id`, `symbol_id`, `request_key`)。
- `PRIMARY`：主键，BTREE，(`tenant_id`, `id`)。

### 外键

- `mt_s_market_control_command`：(`tenant_id`, `symbol_id`) 引用 `trading_symbol` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_market_control_command`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_market_control_command`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_control_flow

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `task_id` | `varchar(36)` | NO | NULL |  | latin1_swedish_ci |  |
| `options_json` | `text` | NO | NULL |  | latin1_swedish_ci |  |
| `state` | `varchar(24)` | NO | NULL |  | latin1_swedish_ci |  |
| `recovery_started_at` | `bigint(20)` | YES | NULL |  | — |  |
| `remaining_millis` | `bigint(20)` | YES | NULL |  | — |  |
| `recovery_offset` | `decimal(32,16)` | YES | NULL |  | — |  |
| `last_price` | `decimal(32,16)` | NO | NULL |  | — |  |
| `last_at` | `bigint(20)` | NO | NULL |  | — |  |
| `finished_at` | `bigint(20)` | YES | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `task_id`)。

### 外键

- `mt_fk_6d66d213c682628f995f`：(`tenant_id`, `task_id`) 引用 `market_control_task` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_market_control_flow`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_market_control_flow`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_flow_d`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_flow_i`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_flow_u`：BEFORE UPDATE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_control_hold

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `task_id` | `varchar(36)` | NO | NULL |  | latin1_swedish_ci |  |
| `reference_price` | `decimal(32,16)` | NO | NULL |  | — |  |
| `reference_time` | `bigint(20)` | NO | NULL |  | — |  |
| `offset_price` | `decimal(32,16)` | YES | NULL |  | — |  |
| `activated_at` | `bigint(20)` | YES | NULL |  | — |  |
| `released_at` | `bigint(20)` | YES | NULL |  | — |  |
| `last_price` | `decimal(32,16)` | NO | NULL |  | — |  |
| `generated_at` | `bigint(20)` | NO | NULL |  | — |  |
| `source_time` | `bigint(20)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `task_id`)。

### 外键

- `mt_fk_2401347050c1b068748c`：(`tenant_id`, `task_id`) 引用 `market_control_task` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_market_control_hold`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_market_control_hold`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_hold_d`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_hold_i`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_hold_u`：BEFORE UPDATE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_control_plan

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_unicode_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `task_id` | `varchar(36)` | NO | NULL |  | latin1_swedish_ci |  |
| `seed` | `bigint(20)` | NO | NULL |  | — |  |
| `parameters_json` | `text` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `prices_json` | `mediumtext` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `summary_json` | `text` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `checksum` | `varchar(64)` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `task_id`)。

### 外键

- `mt_fk_2ea06a736cb068d29d6d`：(`tenant_id`, `task_id`) 引用 `market_control_task` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_market_control_plan`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_market_control_plan`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_plan_d`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_plan_i`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_plan_u`：BEFORE UPDATE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_control_publication

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `task_id` | `varchar(36)` | NO | NULL |  | latin1_swedish_ci |  |
| `published_at` | `bigint(20)` | NO | NULL |  | — |  |
| `from_at` | `bigint(20)` | NO | NULL |  | — |  |
| `to_at` | `bigint(20)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `task_id`)。

### 外键

- `mt_fk_bd18bc0098cb40c0a811`：(`tenant_id`, `task_id`) 引用 `market_control_task` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_market_control_publication`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `joint_history_publication_d`：BEFORE DELETE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `joint_history_publication_i`：BEFORE INSERT，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `joint_history_publication_u`：BEFORE UPDATE，同类执行顺序 3。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `mt_immutable_market_control_publication`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_publication_d`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_publication_i`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_publication_u`：BEFORE UPDATE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_control_resume

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `task_id` | `varchar(36)` | NO | NULL |  | latin1_swedish_ci |  |
| `resumed_at` | `bigint(20)` | NO | NULL |  | — |  |
| `source_time` | `bigint(20)` | NO | NULL |  | — |  |
| `price` | `decimal(32,16)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `task_id`)。

### 外键

- `mt_fk_f5e0891528d0e3b11189`：(`tenant_id`, `task_id`) 引用 `market_control_task` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_market_control_resume`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_market_control_resume`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_resume_d`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_resume_i`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_resume_u`：BEFORE UPDATE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_control_sample

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `task_id` | `varchar(36)` | NO | NULL |  | latin1_swedish_ci |  |
| `generated_at` | `bigint(20)` | NO | NULL |  | — |  |
| `price` | `decimal(32,16)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `task_id`, `generated_at`)。

### 外键

- `mt_fk_0dcdcafc9872e551354e`：(`tenant_id`, `task_id`) 引用 `market_control_task` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_market_control_sample`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_market_control_sample`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_sample_d`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_sample_i`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_sample_u`：BEFORE UPDATE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_control_task

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `varchar(36)` | NO | NULL |  | latin1_swedish_ci |  |
| `symbol_id` | `bigint(20)` | NO | NULL |  | — |  |
| `symbol` | `varchar(32)` | NO | NULL |  | latin1_swedish_ci |  |
| `algorithm_version` | `int(11)` | NO | NULL |  | — |  |
| `kind` | `varchar(16)` | NO | NULL |  | latin1_swedish_ci |  |
| `status` | `varchar(16)` | NO | NULL |  | latin1_swedish_ci |  |
| `start_price` | `decimal(32,16)` | NO | NULL |  | — |  |
| `target_price` | `decimal(32,16)` | NO | NULL |  | — |  |
| `duration_seconds` | `int(11)` | NO | NULL |  | — |  |
| `intensity` | `int(11)` | NO | NULL |  | — |  |
| `oscillation` | `tinyint(1)` | NO | NULL |  | — |  |
| `price_precision` | `int(11)` | NO | NULL |  | — |  |
| `start_source` | `varchar(32)` | NO | NULL |  | latin1_swedish_ci |  |
| `source_time` | `bigint(20)` | NO | NULL |  | — |  |
| `started_at` | `bigint(20)` | NO | NULL |  | — |  |
| `planned_end` | `bigint(20)` | NO | NULL |  | — |  |
| `ended_at` | `bigint(20)` | YES | NULL |  | — |  |
| `sampled_until` | `bigint(20)` | NO | NULL |  | — |  |
| `request_key` | `varchar(64)` | YES | NULL |  | latin1_swedish_ci |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `stop_at` | `bigint(20)` | YES | NULL |  | — |  |

### 索引

- `control_request`：唯一索引，BTREE，(`tenant_id`, `symbol_id`, `request_key`)。
- `control_symbol`：普通索引，BTREE，(`symbol_id`, `started_at`)。
- `mt_old_277bcf538fda8ec7`：普通索引，BTREE，(`symbol_id`, `request_key`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `mt_tenant_status`：普通索引，BTREE，(`tenant_id`, `status`)。
- `PRIMARY`：主键，BTREE，(`id`)。

### 外键

- `mt_fk_1d99c3a5c8212fbf6475`：(`tenant_id`, `symbol_id`) 引用 `trading_symbol` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_market_control_task`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_market_control_task`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_task_d`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_task_i`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_control_task_u`：BEFORE UPDATE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_engine_runtime

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_unicode_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `symbol_id` | `bigint(20)` | NO | NULL |  | — |  |
| `writer_generation` | `bigint(20)` | NO | "0" |  | — |  |
| `owner_id` | `varchar(36)` | YES | NULL |  | utf8mb4_unicode_ci |  |
| `lease_until` | `bigint(20)` | NO | "0" |  | — |  |
| `control_revision` | `bigint(20)` | NO | "0" |  | — |  |
| `snapshot_version` | `bigint(20)` | NO | "0" |  | — |  |
| `quote_json` | `mediumtext` | YES | NULL |  | utf8mb4_unicode_ci |  |
| `status_json` | `mediumtext` | YES | NULL |  | utf8mb4_unicode_ci |  |
| `committed_at` | `bigint(20)` | NO | "0" |  | — |  |
| `source_input_revision` | `bigint(20)` | NO | "0" |  | — |  |
| `source_dirty_from` | `bigint(20)` | YES | NULL |  | — |  |
| `source_dirty_to` | `bigint(20)` | YES | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `symbol_id`)。

### 外键

- `mt_s_market_engine_runtime`：(`tenant_id`, `symbol_id`) 引用 `trading_symbol` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_market_engine_runtime`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `joint_source_dirty_delete`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `joint_source_dirty_insert`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `joint_source_dirty_update`：BEFORE UPDATE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `mt_immutable_market_engine_runtime`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_engine_tenant

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_unicode_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`)。

### 外键

- `mt_t_market_engine_tenant`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_market_engine_tenant`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_history_ordering

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_unicode_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `symbol_id` | `bigint(20)` | NO | NULL |  | — |  |
| `ordering_version` | `int(11)` | NO | NULL |  | — |  |
| `from_minute` | `bigint(20)` | NO | NULL |  | — |  |
| `source_sequence` | `bigint(20)` | NO | NULL |  | — |  |
| `responses_json` | `mediumtext` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `scope_sha256` | `char(64)` | NO | NULL |  | ascii_bin |  |
| `evidence_sha256` | `char(64)` | NO | NULL |  | ascii_bin |  |
| `sealed_at` | `bigint(20)` | NO | NULL |  | — |  |
| `writer_generation` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `symbol_id`)。

### 外键

- `mt_s_history_ordering`：(`tenant_id`, `symbol_id`) 引用 `trading_symbol` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_history_ordering`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `joint_history_ordering_d`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `joint_history_ordering_i`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `joint_history_ordering_u`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_history_response

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_unicode_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `symbol_id` | `bigint(20)` | NO | NULL |  | — |  |
| `request_sha256` | `char(64)` | NO | NULL |  | ascii_bin |  |
| `request_json` | `text` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `response_json` | `mediumtext` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `response_sha256` | `char(64)` | NO | NULL |  | ascii_bin |  |
| `artifact_sha256` | `char(64)` | NO | NULL |  | ascii_bin |  |
| `artifact_pointer` | `varchar(255)` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `scope_sha256` | `char(64)` | NO | NULL |  | ascii_bin |  |
| `sealed_at` | `bigint(20)` | NO | NULL |  | — |  |
| `writer_generation` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `symbol_id`, `request_sha256`)。

### 外键

- `mt_p_history_response`：(`tenant_id`, `symbol_id`) 引用 `market_history_ordering` (`tenant_id`, `symbol_id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_s_history_response`：(`tenant_id`, `symbol_id`) 引用 `trading_symbol` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_history_response`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `joint_history_response_d`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `joint_history_response_i`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `joint_history_response_u`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_legacy_minute_snapshot

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `symbol_id` | `bigint(20)` | NO | NULL |  | — |  |
| `minute_at` | `bigint(20)` | NO | NULL |  | — |  |
| `body` | `text` | NO | NULL |  | latin1_swedish_ci |  |
| `last_event` | `bigint(20)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `symbol_id`, `minute_at`)。

### 外键

- `mt_fk_5eab2d3fcc23fbee8321`：(`tenant_id`, `symbol_id`) 引用 `trading_symbol` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_market_legacy_minute_snapshot`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_market_legacy_minute_snapshot`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_legacy_minute_snapshot_d`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_legacy_minute_snapshot_i`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_legacy_minute_snapshot_u`：BEFORE UPDATE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_mixed_minute

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `symbol_id` | `bigint(20)` | NO | NULL |  | — |  |
| `minute_at` | `bigint(20)` | NO | NULL |  | — |  |
| `body` | `text` | NO | NULL |  | latin1_swedish_ci |  |
| `last_event` | `bigint(20)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `symbol_id`, `minute_at`)。

### 外键

- `mt_fk_23085511787f171f5c55`：(`tenant_id`, `symbol_id`) 引用 `trading_symbol` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_market_mixed_minute`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_market_mixed_minute`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_mixed_minute_d`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_mixed_minute_i`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_mixed_minute_u`：BEFORE UPDATE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_simulation_source_candle

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `symbol_id` | `bigint(20)` | NO | NULL |  | — |  |
| `session_at` | `bigint(20)` | NO | NULL |  | — |  |
| `period` | `varchar(8)` | NO | NULL |  | latin1_swedish_ci |  |
| `candle_at` | `bigint(20)` | NO | NULL |  | — |  |
| `body` | `text` | NO | NULL |  | latin1_swedish_ci |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `symbol_id`, `session_at`, `period`, `candle_at`)。

### 外键

- `mt_fk_3575d0af3817d8d18c96`：(`tenant_id`, `symbol_id`) 引用 `trading_symbol` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_market_simulation_source_candle`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_market_simulation_source_candle`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_simulation_source_candle_d`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_simulation_source_candle_i`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_simulation_source_candle_u`：BEFORE UPDATE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_source_candle

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `symbol_id` | `bigint(20)` | NO | NULL |  | — |  |
| `period` | `varchar(8)` | NO | NULL |  | latin1_swedish_ci |  |
| `candle_at` | `bigint(20)` | NO | NULL |  | — |  |
| `body` | `text` | NO | NULL |  | latin1_swedish_ci |  |
| `received_at` | `bigint(20)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `symbol_id`, `period`, `candle_at`)。

### 外键

- `mt_fk_f0220a9e8cc62d740fdf`：(`tenant_id`, `symbol_id`) 引用 `trading_symbol` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_market_source_candle`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_market_source_candle`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_source_candle_d`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_source_candle_i`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_source_candle_u`：BEFORE UPDATE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_source_event

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `event_sequence` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `event_id` | `varchar(64)` | NO | NULL |  | latin1_swedish_ci |  |
| `symbol_id` | `bigint(20)` | NO | NULL |  | — |  |
| `source_time` | `bigint(20)` | NO | NULL |  | — |  |
| `received_at` | `bigint(20)` | NO | NULL |  | — |  |
| `price` | `decimal(32,16)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `mt_old_a784a6d8fcc24fc2`：普通索引，BTREE，(`symbol_id`, `event_id`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `event_sequence`)。
- `PRIMARY`：主键，BTREE，(`event_sequence`)。
- `source_event_identity`：唯一索引，BTREE，(`tenant_id`, `symbol_id`, `event_id`)。
- `source_event_time`：普通索引，BTREE，(`symbol_id`, `source_time`, `received_at`)。

### 外键

- `mt_fk_ece26abe286cbd0ec452`：(`tenant_id`, `symbol_id`) 引用 `trading_symbol` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_market_source_event`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `joint_history_event_d`：BEFORE DELETE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `joint_history_event_i`：AFTER INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `joint_history_event_u`：BEFORE UPDATE，同类执行顺序 3。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `mt_immutable_market_source_event`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_source_event_d`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_source_event_i`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_source_event_u`：BEFORE UPDATE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_source_quote

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `symbol_id` | `bigint(20)` | NO | NULL |  | — |  |
| `price` | `decimal(32,16)` | NO | NULL |  | — |  |
| `source_time` | `bigint(20)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `symbol_id`)。

### 外键

- `mt_fk_2f78669731d93008017a`：(`tenant_id`, `symbol_id`) 引用 `trading_symbol` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_market_source_quote`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_market_source_quote`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_source_quote_d`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_source_quote_i`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_source_quote_u`：BEFORE UPDATE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## market_source_tick

分类：租户私有。引擎：`InnoDB`。排序规则：`latin1_swedish_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `symbol_id` | `bigint(20)` | NO | NULL |  | — |  |
| `source_time` | `bigint(20)` | NO | NULL |  | — |  |
| `received_at` | `bigint(20)` | NO | NULL |  | — |  |
| `price` | `decimal(32,16)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `symbol_id`, `source_time`)。

### 外键

- `mt_fk_e1c6f0d42feb0d070431`：(`tenant_id`, `symbol_id`) 引用 `trading_symbol` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_market_source_tick`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `joint_history_tick_d`：BEFORE DELETE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `joint_history_tick_i`：BEFORE INSERT，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `joint_history_tick_u`：BEFORE UPDATE，同类执行顺序 3。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `mt_immutable_market_source_tick`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_source_tick_d`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_source_tick_i`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `s2_source_tick_u`：BEFORE UPDATE，同类执行顺序 2。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## menu_action

分类：共享。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：菜单操作映射表

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `menu_id` | `bigint(20)` | NO | NULL |  | — | 菜单ID |
| `action_code` | `varchar(100)` | NO | NULL |  | utf8mb4_general_ci | 操作代码（如：reset_password, freeze_user等） |
| `action_name` | `varchar(100)` | NO | NULL |  | utf8mb4_general_ci | 操作名称（如：重置密码、冻结用户等） |
| `sort_order` | `int(11)` | NO | "0" |  | — | 排序 |
| `created_at` | `datetime` | NO | "CURRENT_TIMESTAMP" |  | — |  |
| `updated_at` | `datetime` | NO | "CURRENT_TIMESTAMP" | on update CURRENT_TIMESTAMP | — |  |

### 索引

- `idx_menu_id`：普通索引，BTREE，(`menu_id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_menu_action`：唯一索引，BTREE，(`menu_id`, `action_code`)。

### 外键

- `menu_action_ibfk_1`：(`menu_id`) 引用 `admin_menu` (`id`)；UPDATE RESTRICT；DELETE CASCADE。

## news_article

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `environment` | `varchar(4)` | NO | NULL |  | utf8mb4_general_ci |  |
| `article_id` | `varchar(36)` | NO | NULL |  | utf8mb4_general_ci |  |
| `source_id` | `varchar(24)` | NO | NULL |  | utf8mb4_general_ci |  |
| `category` | `varchar(16)` | NO | NULL |  | utf8mb4_general_ci |  |
| `language` | `varchar(16)` | NO | NULL |  | utf8mb4_general_ci |  |
| `published_at` | `datetime(6)` | YES | NULL |  | — |  |
| `discovered_at` | `datetime(6)` | NO | NULL |  | — |  |
| `updated_at` | `datetime(6)` | NO | NULL |  | — |  |
| `hidden` | `bit(1)` | NO | NULL |  | — |  |
| `sort_order` | `int(11)` | NO | "0" |  | — |  |
| `data_json` | `longtext` | NO | NULL |  | utf8mb4_general_ci |  |
| `upstream_hash` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `row_version` | `bigint(20)` | NO | "0" |  | — |  |

### 索引

- `news_window`：普通索引，BTREE，(`tenant_id`, `environment`, `source_id`, `hidden`, `published_at`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_news_article`：唯一索引，BTREE，(`tenant_id`, `environment`, `article_id`)。

## news_audit

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `environment` | `varchar(4)` | NO | NULL |  | utf8mb4_general_ci |  |
| `article_id` | `varchar(36)` | YES | NULL |  | utf8mb4_general_ci |  |
| `source_id` | `varchar(24)` | NO | NULL |  | utf8mb4_general_ci |  |
| `actor` | `varchar(100)` | NO | NULL |  | utf8mb4_general_ci |  |
| `action` | `varchar(24)` | NO | NULL |  | utf8mb4_general_ci |  |
| `reason` | `varchar(1000)` | NO | NULL |  | utf8mb4_general_ci |  |
| `before_json` | `longtext` | YES | NULL |  | utf8mb4_general_ci |  |
| `after_json` | `longtext` | YES | NULL |  | utf8mb4_general_ci |  |
| `captured_at` | `datetime(6)` | NO | NULL |  | — |  |

### 索引

- `news_audit_scope`：普通索引，BTREE，(`tenant_id`, `environment`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。

## news_feed

分类：共享。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `varchar(40)` | NO | NULL |  | utf8mb4_general_ci |  |
| `environment` | `varchar(4)` | NO | NULL |  | utf8mb4_general_ci |  |
| `source_id` | `varchar(24)` | NO | NULL |  | utf8mb4_general_ci |  |
| `status` | `varchar(24)` | NO | NULL |  | utf8mb4_general_ci |  |
| `last_attempt` | `datetime(6)` | YES | NULL |  | — |  |
| `last_success` | `datetime(6)` | YES | NULL |  | — |  |
| `content_as_of` | `datetime(6)` | YES | NULL |  | — |  |
| `next_attempt` | `datetime(6)` | YES | NULL |  | — |  |
| `lease_until` | `datetime(6)` | YES | NULL |  | — |  |
| `budget_date` | `date` | YES | NULL |  | — |  |
| `requests_today` | `int(11)` | NO | "0" |  | — |  |
| `failures` | `int(11)` | NO | "0" |  | — |  |
| `item_count` | `int(11)` | NO | "0" |  | — |  |
| `skipped_count` | `int(11)` | NO | "0" |  | — |  |
| `http_status` | `int(11)` | YES | NULL |  | — |  |
| `last_error` | `varchar(200)` | YES | NULL |  | utf8mb4_general_ci |  |
| `etag` | `varchar(300)` | YES | NULL |  | utf8mb4_general_ci |  |
| `last_modified` | `varchar(300)` | YES | NULL |  | utf8mb4_general_ci |  |
| `response_hash` | `varchar(64)` | YES | NULL |  | utf8mb4_general_ci |  |
| `parsed_json` | `longtext` | YES | NULL |  | utf8mb4_general_ci |  |
| `row_version` | `bigint(20)` | NO | "0" |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`id`)。

## news_source_setting

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `environment` | `varchar(4)` | NO | NULL |  | utf8mb4_general_ci |  |
| `source_id` | `varchar(24)` | NO | NULL |  | utf8mb4_general_ci |  |
| `enabled` | `bit(1)` | NO | NULL |  | — |  |
| `license_reviewed` | `bit(1)` | NO | NULL |  | — |  |
| `license_evidence` | `varchar(1000)` | YES | NULL |  | utf8mb4_general_ci |  |
| `row_version` | `bigint(20)` | NO | "0" |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_news_source_setting`：唯一索引，BTREE，(`tenant_id`, `environment`, `source_id`)。

## operation_log

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：操作日志表

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `admin_id` | `bigint(20)` | NO | NULL |  | — | 管理员ID |
| `admin_email` | `varchar(100)` | YES | NULL |  | utf8mb4_general_ci | 管理员邮箱 |
| `operation_type` | `varchar(50)` | NO | NULL |  | utf8mb4_general_ci | 操作类型（如：用户管理、订单管理、充值审核等） |
| `operation_action` | `varchar(100)` | NO | NULL |  | utf8mb4_general_ci | 操作动作（如：重置密码、审核通过、删除等） |
| `target_type` | `varchar(50)` | YES | NULL |  | utf8mb4_general_ci | 目标类型（如：用户、订单、充值记录等） |
| `target_id` | `bigint(20)` | YES | NULL |  | — | 目标ID |
| `target_info` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci | 目标信息（如：用户邮箱、订单号等） |
| `request_method` | `varchar(10)` | YES | NULL |  | utf8mb4_general_ci | 请求方法（GET、POST、PUT、DELETE） |
| `request_url` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci | 请求URL |
| `request_params` | `text` | YES | NULL |  | utf8mb4_general_ci | 请求参数（JSON格式） |
| `ip_address` | `varchar(50)` | YES | NULL |  | utf8mb4_general_ci | IP地址 |
| `user_agent` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci | 用户代理 |
| `status` | `varchar(20)` | YES | "SUCCESS" |  | utf8mb4_general_ci | 操作状态（SUCCESS、FAILED） |
| `error_message` | `text` | YES | NULL |  | utf8mb4_general_ci | 错误信息 |
| `created_at` | `datetime` | NO | "CURRENT_TIMESTAMP" |  | — | 创建时间 |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `idx_admin_id`：普通索引，BTREE，(`admin_id`)。
- `idx_created_at`：普通索引，BTREE，(`created_at`)。
- `idx_operation_type`：普通索引，BTREE，(`operation_type`)。
- `idx_target_type_id`：普通索引，BTREE，(`target_type`, `target_id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `mt_tenant_status`：普通索引，BTREE，(`tenant_id`, `status`)。
- `PRIMARY`：主键，BTREE，(`id`)。

### 外键

- `mt_t_operation_log`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_operation_log`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## option_duration

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：期限设置表

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — | 主键 |
| `duration` | `int(11)` | NO | NULL |  | — | 时长（秒） |
| `label` | `varchar(20)` | NO | NULL |  | utf8mb4_general_ci | 显示标签，如 30s, 60s |
| `sort_order` | `int(11)` | NO | "0" |  | — | 排序顺序 |
| `enabled` | `tinyint(1)` | NO | "1" |  | — | 是否启用 |
| `created_at` | `datetime` | NO | "CURRENT_TIMESTAMP" |  | — | 创建时间 |
| `updated_at` | `datetime` | NO | "CURRENT_TIMESTAMP" | on update CURRENT_TIMESTAMP | — | 更新时间 |
| `profit_rate` | `decimal(5,4)` | NO | "0.8000" |  | — | 盈亏比例（如 0.8 表示 80%） |
| `loss_rate` | `decimal(5,4)` | NO | "1.0000" |  | — | 亏损比例（如 1.0 表示 100%，全部亏损） |
| `max_amount` | `decimal(18,2)` | YES | NULL |  | — |  |
| `min_amount` | `decimal(18,2)` | YES | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `idx_enabled`：普通索引，BTREE，(`enabled`)。
- `idx_sort_order`：普通索引，BTREE，(`sort_order`)。
- `mt_old_420e589a4e3a28c9`：普通索引，BTREE，(`duration`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_duration`：唯一索引，BTREE，(`tenant_id`, `duration`)。

### 外键

- `mt_t_option_duration`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_option_duration`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## option_order

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `amount` | `decimal(32,16)` | NO | NULL |  | — |  |
| `close_price` | `decimal(32,16)` | YES | NULL |  | — |  |
| `close_time` | `datetime(6)` | YES | NULL |  | — |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |
| `direction` | `varchar(10)` | NO | NULL |  | utf8mb4_general_ci |  |
| `duration` | `int(11)` | YES | NULL |  | — |  |
| `profit_rate` | `decimal(5,4)` | YES | NULL |  | — | 下单时的盈利比例 |
| `loss_rate` | `decimal(5,4)` | YES | NULL |  | — | 下单时的亏损比例 |
| `open_price` | `decimal(32,16)` | YES | NULL |  | — |  |
| `open_time` | `datetime(6)` | YES | NULL |  | — |  |
| `profit` | `decimal(32,16)` | YES | NULL |  | — |  |
| `status` | `varchar(20)` | NO | NULL |  | utf8mb4_general_ci |  |
| `symbol` | `varchar(32)` | NO | NULL |  | utf8mb4_general_ci |  |
| `updated_at` | `datetime(6)` | NO | NULL |  | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `preset_profit_type` | `varchar(10)` | YES | NULL |  | utf8mb4_general_ci |  |
| `row_version` | `bigint(20)` | NO | NULL |  | — |  |
| `trial_reserved` | `decimal(32,16)` | YES | NULL |  | — |  |
| `deleted_at` | `datetime(6)` | YES | NULL |  | — |  |
| `deleted_by` | `varchar(64)` | YES | NULL |  | utf8mb4_general_ci |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `funding_source` | `varchar(16)` | YES | NULL |  | utf8mb4_general_ci |  |
| `trial_allocations` | `varchar(4000)` | YES | NULL |  | utf8mb4_general_ci |  |
| `request_key` | `varchar(64)` | YES | NULL |  | ascii_bin |  |
| `request_hash` | `varchar(64)` | YES | NULL |  | ascii_bin |  |
| `promotion_pending` | `bit(1)` | NO | "b'0'" |  | — |  |

### 索引

- `ix_option_promotion`：普通索引，BTREE，(`tenant_id`, `promotion_pending`, `id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `mt_tenant_status`：普通索引，BTREE，(`tenant_id`, `status`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_option_order_request`：唯一索引，BTREE，(`tenant_id`, `user_id`, `request_key`)。

### 外键

- `mt_fk_86eaa8ddf560f959a206`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_option_order`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_option_order`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## s4_history_projection_minute

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_unicode_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `symbol_id` | `bigint(20)` | NO | NULL |  | — |  |
| `minute_at` | `bigint(20)` | NO | NULL |  | — |  |
| `generation` | `bigint(20)` | NO | NULL |  | — |  |
| `fact_version` | `bigint(20)` | NO | NULL |  | — |  |
| `body` | `longtext` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `received_cutoff` | `bigint(20)` | NO | NULL |  | — |  |
| `protected_mixed` | `bit(1)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `symbol_id`, `minute_at`)。

### 外键

- `mt_p_s4_minute`：(`tenant_id`, `symbol_id`) 引用 `s4_history_projection_progress` (`tenant_id`, `symbol_id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_s_s4_minute`：(`tenant_id`, `symbol_id`) 引用 `trading_symbol` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_s4_minute`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `joint_s4_minute_delete`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `joint_s4_minute_insert`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `joint_s4_minute_update`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## s4_history_projection_progress

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_unicode_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `symbol_id` | `bigint(20)` | NO | NULL |  | — |  |
| `generation` | `bigint(20)` | NO | NULL |  | — |  |
| `fact_version` | `bigint(20)` | NO | NULL |  | — |  |
| `initial_watermark` | `bigint(20)` | NO | NULL |  | — |  |
| `watermark` | `bigint(20)` | NO | NULL |  | — |  |
| `stop_at` | `bigint(20)` | NO | NULL |  | — |  |
| `last_hash` | `varchar(64)` | YES | NULL |  | ascii_bin |  |
| `input_revision` | `bigint(20)` | NO | "0" |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`tenant_id`, `symbol_id`)。

### 外键

- `mt_s_s4_progress`：(`tenant_id`, `symbol_id`) 引用 `trading_symbol` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_s4_progress`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `joint_s4_progress_delete`：BEFORE DELETE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `joint_s4_progress_insert`：BEFORE INSERT，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。
- `joint_s4_progress_update`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## simulation_seed

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `amount_per_wallet` | `decimal(32,16)` | NO | NULL |  | — |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `user_id`)。
- `PRIMARY`：主键，BTREE，(`user_id`)。

### 外键

- `mt_fk_eac3043ef86b6d837662`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_simulation_seed`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_simulation_seed`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## support_attachment

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `message_id` | `bigint(20)` | NO | NULL |  | — |  |
| `content` | `longblob` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `message_id`)。
- `PRIMARY`：主键，BTREE，(`message_id`)。

### 外键

- `mt_fk_3ef9c109b4c93ad77529`：(`tenant_id`, `message_id`) 引用 `support_message` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_support_attachment`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_support_attachment`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## support_conversation

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `active_user_id` | `bigint(20)` | YES | NULL |  | — |  |
| `admin_id` | `bigint(20)` | YES | NULL |  | — |  |
| `status` | `varchar(16)` | NO | NULL |  | utf8mb4_general_ci |  |
| `client_ip` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |
| `accepted_at` | `datetime(6)` | YES | NULL |  | — |  |
| `closed_at` | `datetime(6)` | YES | NULL |  | — |  |
| `updated_at` | `datetime(6)` | NO | NULL |  | — |  |
| `user_read_id` | `bigint(20)` | NO | "0" |  | — |  |
| `admin_read_id` | `bigint(20)` | NO | "0" |  | — |  |
| `last_hash` | `varchar(64)` | YES | NULL |  | utf8mb4_general_ci |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `legal_hold` | `bit(1)` | NO | "b'0'" |  | — |  |
| `control_actor_id` | `bigint(20)` | YES | NULL |  | — |  |

### 索引

- `ix_support_tenant_created_id`：普通索引，BTREE，(`tenant_id`, `created_at`, `id`)。
- `mt_fk_6505728799905547753a`：普通索引，BTREE，(`tenant_id`, `admin_id`)。
- `mt_fk_6729c78e35f7c7efebec`：普通索引，BTREE，(`tenant_id`, `user_id`)。
- `mt_old_cb7c2c271cc9df33`：普通索引，BTREE，(`active_user_id`)。
- `mt_support_control_actor`：普通索引，BTREE，(`control_actor_id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `mt_tenant_status`：普通索引，BTREE，(`tenant_id`, `status`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `support_owner`：普通索引，BTREE，(`admin_id`, `status`)。
- `support_queue`：普通索引，BTREE，(`status`, `id`)。
- `support_user`：普通索引，BTREE，(`user_id`, `id`)。
- `uk_active_user`：唯一索引，BTREE，(`tenant_id`, `active_user_id`)。

### 外键

- `mt_fk_6505728799905547753a`：(`tenant_id`, `admin_id`) 引用 `admin_user` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_fk_6729c78e35f7c7efebec`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_fk_f3849e0bb7927125705f`：(`tenant_id`, `active_user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_support_control_actor`：(`control_actor_id`) 引用 `control_admin` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_support_conversation`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_support_conversation`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## support_message

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `conversation_id` | `bigint(20)` | NO | NULL |  | — |  |
| `sender` | `varchar(12)` | NO | NULL |  | utf8mb4_general_ci |  |
| `sender_id` | `bigint(20)` | NO | NULL |  | — |  |
| `sender_name` | `varchar(128)` | NO | NULL |  | utf8mb4_general_ci |  |
| `request_id` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `text` | `varchar(4000)` | NO | NULL |  | utf8mb4_general_ci |  |
| `image` | `bit(1)` | NO | NULL |  | — |  |
| `image_hash` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |
| `previous_hash` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `hash` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `mt_old_d16f92fac6282a01`：普通索引，BTREE，(`conversation_id`, `sender`, `sender_id`, `request_id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `support_message_cursor`：普通索引，BTREE，(`conversation_id`, `id`)。
- `uk_support_request`：唯一索引，BTREE，(`tenant_id`, `conversation_id`, `sender`, `sender_id`, `request_id`)。

### 外键

- `mt_fk_d9db291c0d514059a00e`：(`tenant_id`, `conversation_id`) 引用 `support_conversation` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_support_message`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_support_message`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## support_presence

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `admin_id` | `bigint(20)` | NO | NULL |  | — |  |
| `accepting` | `bit(1)` | NO | NULL |  | — |  |
| `heartbeat_at` | `datetime(6)` | YES | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `admin_id`)。
- `PRIMARY`：主键，BTREE，(`admin_id`)。

### 外键

- `mt_fk_76b90f79bce6a4a09e94`：(`tenant_id`, `admin_id`) 引用 `admin_user` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_support_presence`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_support_presence`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## symbol_duration

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：产品期限配置表

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `symbol` | `varchar(32)` | NO | NULL |  | utf8mb4_general_ci | 交易对 |
| `duration` | `int(11)` | NO | NULL |  | — | 时长（秒） |
| `label` | `varchar(20)` | NO | NULL |  | utf8mb4_general_ci | 显示标签 |
| `profit_rate` | `decimal(5,4)` | NO | "0.8000" |  | — | 盈利比例 |
| `loss_rate` | `decimal(5,4)` | NO | "1.0000" |  | — | 亏损比例 |
| `min_amount` | `decimal(18,2)` | YES | "1.00" |  | — | 最低购买金额 |
| `max_amount` | `decimal(18,2)` | YES | "10000.00" |  | — | 最大购买金额 |
| `sort_order` | `int(11)` | NO | "0" |  | — | 排序顺序 |
| `enabled` | `tinyint(1)` | NO | "1" |  | — | 是否启用 |
| `created_at` | `datetime` | NO | "CURRENT_TIMESTAMP" |  | — |  |
| `updated_at` | `datetime` | NO | "CURRENT_TIMESTAMP" | on update CURRENT_TIMESTAMP | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `idx_symbol`：普通索引，BTREE，(`symbol`)。
- `mt_old_2e400eb8cc1a512a`：普通索引，BTREE，(`symbol`, `duration`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_symbol_duration`：唯一索引，BTREE，(`tenant_id`, `symbol`, `duration`)。

### 外键

- `mt_t_symbol_duration`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_symbol_duration`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## system_config

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `config_key` | `varchar(100)` | NO | NULL |  | utf8mb4_general_ci |  |
| `config_value` | `text` | YES | NULL |  | utf8mb4_general_ci |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |
| `description` | `varchar(200)` | YES | NULL |  | utf8mb4_general_ci |  |
| `updated_at` | `datetime(6)` | YES | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `mt_old_4731e0dd2ba01ae8`：普通索引，BTREE，(`config_key`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `UK_npsxm1erd0lbetjn5d3ayrsof`：唯一索引，BTREE，(`tenant_id`, `config_key`)。

### 外键

- `mt_t_system_config`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_system_config`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## tenant

分类：控制面。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `code` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci |  |
| `name` | `varchar(128)` | NO | NULL |  | utf8mb4_general_ci |  |
| `frontend_host` | `varchar(253)` | YES | NULL |  | utf8mb4_general_ci |  |
| `status` | `varchar(32)` | NO | "DRAFT" |  | utf8mb4_general_ci |  |
| `template_version` | `varchar(32)` | NO | "safe-v1" |  | utf8mb4_general_ci |  |
| `policy_version` | `bigint(20)` | NO | "0" |  | — |  |
| `session_version` | `bigint(20)` | NO | "0" |  | — |  |
| `config_ready` | `bit(1)` | NO | "b'0'" |  | — |  |
| `domain_verified` | `bit(1)` | NO | "b'0'" |  | — |  |
| `row_version` | `bigint(20)` | NO | "0" |  | — |  |
| `created_at` | `datetime(6)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_tenant_code`：唯一索引，BTREE，(`code`)。
- `uk_tenant_host`：唯一索引，BTREE，(`frontend_host`)。

## tenant_domain_binding

分类：控制面。引擎：`InnoDB`。排序规则：`utf8mb4_unicode_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `hostname` | `varchar(253)` | NO | NULL |  | utf8mb4_bin |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `status` | `varchar(16)` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `challenge` | `varchar(32)` | YES | NULL |  | utf8mb4_unicode_ci |  |
| `expires_at` | `datetime(6)` | YES | NULL |  | — |  |
| `verified_at` | `datetime(6)` | YES | NULL |  | — |  |
| `version` | `bigint(20)` | NO | "0" |  | — |  |

### 索引

- `ix_domain_candidate`：普通索引，BTREE，(`tenant_id`, `status`)。
- `PRIMARY`：主键，BTREE，(`hostname`)。

### 外键

- `mt_domain_binding_tenant`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

## tenant_domain_history

分类：控制面。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `hostname` | `varchar(253)` | NO | NULL |  | utf8mb4_general_ci |  |
| `retired_at` | `datetime(6)` | YES | NULL |  | — |  |

### 索引

- `fk_domain_history_tenant`：普通索引，BTREE，(`tenant_id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_domain_history`：唯一索引，BTREE，(`hostname`)。

### 外键

- `fk_domain_history_tenant`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

## tenant_policy

分类：控制面。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `policy_key` | `varchar(128)` | NO | NULL |  | utf8mb4_general_ci |  |
| `policy_value` | `text` | YES | NULL |  | utf8mb4_general_ci |  |
| `locked` | `bit(1)` | NO | "b'0'" |  | — |  |
| `version` | `bigint(20)` | NO | "0" |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_tenant_policy`：唯一索引，BTREE，(`tenant_id`, `policy_key`)。

### 外键

- `fk_tenant_policy`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

## tenant_schema_version

分类：控制面。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `version` | `bigint(20)` | NO | NULL |  | — |  |
| `applied_at` | `datetime(6)` | NO | NULL |  | — |  |
| `minimum_application_epoch` | `bigint(20)` | NO | NULL |  | — |  |
| `business_activation_ready` | `bit(1)` | NO | "b'0'" |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`version`)。

## trader_audit

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `environment` | `varchar(4)` | NO | NULL |  | utf8mb4_general_ci |  |
| `trader_id` | `varchar(36)` | NO | NULL |  | utf8mb4_general_ci |  |
| `object_id` | `varchar(36)` | YES | NULL |  | utf8mb4_general_ci |  |
| `actor` | `varchar(100)` | NO | NULL |  | utf8mb4_general_ci |  |
| `action` | `varchar(32)` | NO | NULL |  | utf8mb4_general_ci |  |
| `reason` | `varchar(1000)` | NO | NULL |  | utf8mb4_general_ci |  |
| `before_json` | `longtext` | YES | NULL |  | utf8mb4_general_ci |  |
| `after_json` | `longtext` | YES | NULL |  | utf8mb4_general_ci |  |
| `captured_at` | `datetime(6)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`id`)。
- `trader_audit_scope`：普通索引，BTREE，(`tenant_id`, `environment`, `id`)。

## trader_equity

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `environment` | `varchar(4)` | NO | NULL |  | utf8mb4_general_ci |  |
| `trader_id` | `varchar(36)` | NO | NULL |  | utf8mb4_general_ci |  |
| `point_id` | `varchar(36)` | NO | NULL |  | utf8mb4_general_ci |  |
| `point_at` | `datetime(6)` | NO | NULL |  | — |  |
| `net_asset` | `decimal(38,18)` | NO | NULL |  | — |  |
| `cash_flow` | `decimal(38,18)` | YES | NULL |  | — |  |
| `currency` | `varchar(12)` | NO | NULL |  | utf8mb4_general_ci |  |
| `source_note` | `varchar(1000)` | YES | NULL |  | utf8mb4_general_ci |  |

### 索引

- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_trader_point_id`：唯一索引，BTREE，(`tenant_id`, `environment`, `point_id`)。
- `uk_trader_point_time`：唯一索引，BTREE，(`tenant_id`, `environment`, `trader_id`, `point_at`)。

### 外键

- `fk_trader_equity`：(`tenant_id`, `environment`, `trader_id`) 引用 `trader_profile` (`tenant_id`, `environment`, `trader_id`)；UPDATE RESTRICT；DELETE RESTRICT。

## trader_history

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `environment` | `varchar(4)` | NO | NULL |  | utf8mb4_general_ci |  |
| `trader_id` | `varchar(36)` | NO | NULL |  | utf8mb4_general_ci |  |
| `record_id` | `varchar(36)` | NO | NULL |  | utf8mb4_general_ci |  |
| `record_key` | `varchar(80)` | NO | NULL |  | utf8mb4_general_ci |  |
| `closed_at` | `datetime(6)` | NO | NULL |  | — |  |
| `symbol` | `varchar(40)` | NO | NULL |  | utf8mb4_general_ci |  |
| `direction` | `varchar(8)` | NO | NULL |  | utf8mb4_general_ci |  |
| `leverage` | `decimal(38,18)` | YES | NULL |  | — |  |
| `quantity` | `decimal(38,18)` | YES | NULL |  | — |  |
| `quantity_unit` | `varchar(16)` | YES | NULL |  | utf8mb4_general_ci |  |
| `pnl` | `decimal(38,18)` | YES | NULL |  | — |  |
| `pnl_basis` | `varchar(16)` | NO | NULL |  | utf8mb4_general_ci |  |
| `fees` | `decimal(38,18)` | YES | NULL |  | — |  |
| `currency` | `varchar(12)` | NO | NULL |  | utf8mb4_general_ci |  |
| `source_note` | `varchar(1000)` | YES | NULL |  | utf8mb4_general_ci |  |
| `evidence_note` | `varchar(1000)` | YES | NULL |  | utf8mb4_general_ci |  |

### 索引

- `PRIMARY`：主键，BTREE，(`id`)。
- `trader_closed`：普通索引，BTREE，(`tenant_id`, `environment`, `trader_id`, `closed_at`)。
- `uk_trader_record_id`：唯一索引，BTREE，(`tenant_id`, `environment`, `trader_id`, `record_id`)。
- `uk_trader_record_key`：唯一索引，BTREE，(`tenant_id`, `environment`, `trader_id`, `record_key`)。

### 外键

- `fk_trader_history`：(`tenant_id`, `environment`, `trader_id`) 引用 `trader_profile` (`tenant_id`, `environment`, `trader_id`)；UPDATE RESTRICT；DELETE RESTRICT。

## trader_profile

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `environment` | `varchar(4)` | NO | NULL |  | utf8mb4_general_ci |  |
| `trader_id` | `varchar(36)` | NO | NULL |  | utf8mb4_general_ci |  |
| `source_type` | `varchar(16)` | NO | NULL |  | utf8mb4_general_ci |  |
| `status` | `varchar(16)` | NO | NULL |  | utf8mb4_general_ci |  |
| `name` | `varchar(80)` | NO | NULL |  | utf8mb4_general_ci |  |
| `avatar_url` | `varchar(300)` | YES | NULL |  | utf8mb4_general_ci |  |
| `currency` | `varchar(12)` | NO | NULL |  | utf8mb4_general_ci |  |
| `recommended` | `bit(1)` | NO | "b'0'" |  | — |  |
| `sort_order` | `int(11)` | NO | "0" |  | — |  |
| `updated_at` | `datetime(6)` | NO | NULL |  | — |  |
| `data_json` | `longtext` | NO | NULL |  | utf8mb4_general_ci |  |
| `row_version` | `bigint(20)` | NO | "0" |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`id`)。
- `trader_public`：普通索引，BTREE，(`tenant_id`, `environment`, `status`, `recommended`, `sort_order`)。
- `uk_trader_profile`：唯一索引，BTREE，(`tenant_id`, `environment`, `trader_id`)。

## trading_symbol

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：交易对/币种表

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `symbol` | `varchar(32)` | NO | NULL |  | utf8mb4_general_ci | 交易对符号，如 BTCUSD, XAUUSD |
| `base_currency` | `varchar(16)` | NO | NULL |  | utf8mb4_general_ci | 基础货币，如 BTC, XAU |
| `quote_currency` | `varchar(16)` | NO | "USD" |  | utf8mb4_general_ci | 计价货币，如 USD |
| `name` | `varchar(64)` | NO | NULL |  | utf8mb4_general_ci | 显示名称，如 比特币/美元 |
| `name_en` | `varchar(64)` | YES | NULL |  | utf8mb4_general_ci | 英文名称 |
| `category` | `varchar(32)` | NO | "US" |  | utf8mb4_general_ci | 分类：US, Crypto, Metal, Forex, CFD |
| `icon_url` | `varchar(255)` | YES | NULL |  | utf8mb4_general_ci | 图标URL |
| `flag_url` | `varchar(255)` | YES | NULL |  | utf8mb4_general_ci | 国旗图标URL |
| `is_hot` | `tinyint(4)` | NO | "0" |  | — | 是否热门：0否 1是 |
| `is_enabled` | `tinyint(4)` | NO | "1" |  | — | 是否启用：0否 1是 |
| `sort_order` | `int(11)` | NO | "0" |  | — | 排序权重，数字越大越靠前 |
| `price_precision` | `int(11)` | NO | "2" |  | — | 价格精度（小数位数） |
| `volume_precision` | `int(11)` | NO | "2" |  | — | 数量精度（小数位数） |
| `min_trade_amount` | `decimal(32,16)` | YES | "0.0000000000000000" |  | — | 最小交易数量 |
| `alltick_symbol` | `varchar(64)` | YES | NULL |  | utf8mb4_general_ci | Alltick API中的symbol，用于订阅行情 |
| `current_price` | `decimal(32,16)` | YES | "0.0000000000000000" |  | — | 当前价格（缓存） |
| `price_change_24h` | `decimal(32,16)` | YES | "0.0000000000000000" |  | — | 24小时涨跌额 |
| `price_change_pct_24h` | `decimal(10,4)` | YES | "0.0000" |  | — | 24小时涨跌幅（百分比） |
| `sparkline_data` | `text` | YES | NULL |  | utf8mb4_general_ci | K线数据（JSON数组，用于展示小图） |
| `created_at` | `datetime` | NO | "CURRENT_TIMESTAMP" |  | — |  |
| `updated_at` | `datetime` | NO | "CURRENT_TIMESTAMP" | on update CURRENT_TIMESTAMP | — |  |
| `fee_multiplier` | `decimal(32,16)` | YES | NULL |  | — |  |
| `lot_size` | `decimal(32,16)` | YES | NULL |  | — |  |
| `control_enabled` | `bit(1)` | NO | NULL |  | — |  |
| `control_price_offset` | `decimal(32,16)` | YES | NULL |  | — |  |
| `leverage` | `decimal(10,2)` | YES | NULL |  | — |  |
| `control_completed_at` | `bigint(20)` | YES | NULL |  | — |  |
| `control_duration_seconds` | `int(11)` | YES | NULL |  | — |  |
| `control_intensity` | `int(11)` | YES | NULL |  | — |  |
| `control_random_oscillation` | `bit(1)` | YES | NULL |  | — |  |
| `control_restoring` | `bit(1)` | YES | NULL |  | — |  |
| `control_start_price` | `decimal(32,16)` | YES | NULL |  | — |  |
| `control_started_at` | `bigint(20)` | YES | NULL |  | — |  |
| `control_target_price` | `decimal(32,16)` | YES | NULL |  | — |  |
| `max_leverage` | `decimal(10,2)` | YES | NULL |  | — |  |
| `row_version` | `bigint(20)` | NO | "0" |  | — |  |
| `random_market_base_price` | `decimal(32,16)` | YES | NULL |  | — |  |
| `random_market_enabled` | `bit(1)` | YES | NULL |  | — |  |
| `random_market_started_at` | `bigint(20)` | YES | NULL |  | — |  |
| `random_market_controls` | `longtext` | YES | NULL |  | utf8mb4_general_ci |  |
| `market_instrument_key` | `varchar(128)` | YES | NULL |  | utf8mb4_general_ci |  |
| `market_source` | `varchar(16)` | NO | NULL |  | utf8mb4_general_ci |  |
| `source_category` | `varchar(32)` | NO | NULL |  | utf8mb4_general_ci |  |
| `min_order_notional` | `decimal(32,16)` | YES | NULL |  | — |  |
| `min_order_quantity` | `decimal(32,16)` | YES | NULL |  | — |  |
| `quantity_step` | `decimal(32,16)` | YES | NULL |  | — |  |
| `quantity_unit_type` | `varchar(16)` | YES | NULL |  | utf8mb4_general_ci |  |
| `spec_version` | `bigint(20)` | YES | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `idx_category`：普通索引，BTREE，(`category`)。
- `idx_is_enabled`：普通索引，BTREE，(`is_enabled`)。
- `idx_is_hot`：普通索引，BTREE，(`is_hot`)。
- `idx_sort_order`：普通索引，BTREE，(`sort_order`)。
- `mt_old_507e5c3f28785527`：普通索引，BTREE，(`market_instrument_key`)。
- `mt_old_7927b83f824afdab`：普通索引，BTREE，(`symbol`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `UK_s7aam1xh6nnv6ghru10f6vnuh`：唯一索引，BTREE，(`tenant_id`, `market_instrument_key`)。
- `uk_symbol`：唯一索引，BTREE，(`tenant_id`, `symbol`)。

### 外键

- `mt_t_trading_symbol`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_trading_symbol`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## transfer_record

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：划转记录表

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — | 用户ID |
| `from_account` | `varchar(32)` | NO | NULL |  | utf8mb4_general_ci | 转出账户: FUND-资金账户, CONTRACT-合约账户, OPTION-期权账户 |
| `to_account` | `varchar(32)` | NO | NULL |  | utf8mb4_general_ci | 转入账户: FUND-资金账户, CONTRACT-合约账户, OPTION-期权账户 |
| `amount` | `decimal(32,16)` | NO | NULL |  | — | 划转金额 |
| `created_at` | `datetime` | NO | NULL |  | — | 创建时间 |
| `request_id` | `varchar(64)` | YES | NULL |  | utf8mb4_general_ci |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `idx_created_at`：普通索引，BTREE，(`created_at`)。
- `idx_user_id`：普通索引，BTREE，(`user_id`)。
- `mt_old_625e804fd449f19c`：普通索引，BTREE，(`user_id`, `request_id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_transfer_request`：唯一索引，BTREE，(`tenant_id`, `user_id`, `request_id`)。

### 外键

- `mt_fk_1c701e7a2120e2de41cf`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_transfer_record`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_transfer_record`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## trial_account

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `row_version` | `bigint(20)` | NO | "0" |  | — |  |
| `available` | `decimal(32,16)` | NO | "0.0000000000000000" |  | — |  |
| `frozen` | `decimal(32,16)` | NO | "0.0000000000000000" |  | — |  |
| `granted` | `decimal(32,16)` | NO | "0.0000000000000000" |  | — |  |
| `consumed` | `decimal(32,16)` | NO | "0.0000000000000000" |  | — |  |
| `profits` | `decimal(32,16)` | NO | "0.0000000000000000" |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `expired` | `decimal(32,16)` | NO | "0.0000000000000000" |  | — |  |
| `uncovered_loss` | `decimal(32,16)` | NO | "0.0000000000000000" |  | — |  |
| `trial_eligible` | `tinyint(1)` | NO | "0" |  | — |  |

### 索引

- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `user_id`)。
- `PRIMARY`：主键，BTREE，(`user_id`)。

### 外键

- `mt_fk_21882666b7edaf1be239`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_trial_account`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_trial_account`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## trial_grant

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_unicode_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `row_version` | `bigint(20)` | NO | "0" |  | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `campaign_id` | `bigint(20)` | YES | NULL |  | — |  |
| `delivery_id` | `bigint(20)` | YES | NULL |  | — |  |
| `request_key` | `varchar(80)` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `claimed_at` | `datetime` | NO | NULL |  | — |  |
| `expires_at` | `datetime` | YES | NULL |  | — |  |
| `active` | `tinyint(1)` | NO | "1" |  | — |  |
| `available` | `decimal(32,16)` | NO | "0.0000000000000000" |  | — |  |
| `frozen` | `decimal(32,16)` | NO | "0.0000000000000000" |  | — |  |
| `consumed` | `decimal(32,16)` | NO | "0.0000000000000000" |  | — |  |
| `expired` | `decimal(32,16)` | NO | "0.0000000000000000" |  | — |  |

### 索引

- `ix_trial_grant_campaign`：普通索引，BTREE，(`tenant_id`, `campaign_id`, `user_id`)。
- `ix_trial_grant_user`：普通索引，BTREE，(`tenant_id`, `user_id`, `id`)。
- `mt_fk_3e04cc6256c23dedad69`：普通索引，BTREE，(`tenant_id`, `delivery_id`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_trial_grant_request`：唯一索引，BTREE，(`tenant_id`, `user_id`, `request_key`)。

### 外键

- `mt_fk_061664ca8a3fb6de6312`：(`tenant_id`, `campaign_id`) 引用 `activity_campaign` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_fk_3e04cc6256c23dedad69`：(`tenant_id`, `delivery_id`) 引用 `activity_delivery` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_fk_e6fe59f66115ae0daa18`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_trial_grant`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_trial_grant`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## trial_ledger

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — |  |
| `reason` | `varchar(100)` | NO | NULL |  | utf8mb4_general_ci |  |
| `available` | `decimal(32,16)` | NO | NULL |  | — |  |
| `frozen` | `decimal(32,16)` | NO | NULL |  | — |  |
| `delta` | `decimal(32,16)` | NO | NULL |  | — |  |
| `created_at` | `datetime(6)` | YES | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `ix_trial_ledger_user`：普通索引，BTREE，(`user_id`, `id`)。
- `mt_fk_499fc1d44648354ad966`：普通索引，BTREE，(`tenant_id`, `user_id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。

### 外键

- `mt_fk_499fc1d44648354ad966`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_trial_ledger`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_trial_ledger`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## t_ai_model

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：AI模型配置

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — | 模型ID |
| `model_name` | `varchar(100)` | YES | "" |  | utf8mb4_general_ci | 模型名称 |
| `base_url` | `varchar(500)` | YES | "" |  | utf8mb4_general_ci | API基础URL |
| `model` | `varchar(100)` | YES | "" |  | utf8mb4_general_ci | 模型标识 |
| `api_key` | `varchar(200)` | YES | "" |  | utf8mb4_general_ci | API密钥 |
| `flag` | `int(1)` | YES | "0" |  | — | 当前选中标记 0-未选中 1-已选中 |
| `status` | `int(1)` | YES | "1" |  | — | 状态 0-禁用 1-启用 |
| `sort_order` | `int(4)` | YES | "0" |  | — | 排序 |
| `create_by` | `varchar(64)` | YES | "" |  | utf8mb4_general_ci | 创建者 |
| `create_time` | `datetime` | YES | NULL |  | — | 创建时间 |
| `update_by` | `varchar(64)` | YES | "" |  | utf8mb4_general_ci | 更新者 |
| `update_time` | `datetime` | YES | NULL |  | — | 更新时间 |
| `remark` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci | 备注 |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `mt_tenant_status`：普通索引，BTREE，(`tenant_id`, `status`)。
- `PRIMARY`：主键，BTREE，(`id`)。

### 外键

- `mt_t_t_ai_model`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_t_ai_model`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## user_account

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `email` | `varchar(128)` | NO | NULL |  | utf8mb4_general_ci |  |
| `country_code` | `varchar(32)` | YES | NULL |  | utf8mb4_general_ci |  |
| `phone` | `varchar(32)` | YES | NULL |  | utf8mb4_general_ci |  |
| `password_hash` | `varchar(128)` | NO | NULL |  | utf8mb4_general_ci |  |
| `nickname` | `varchar(50)` | YES | NULL |  | utf8mb4_general_ci |  |
| `invite_code` | `varchar(32)` | YES | NULL |  | utf8mb4_general_ci |  |
| `my_invite_code` | `varchar(32)` | YES | NULL |  | utf8mb4_general_ci | 用户自己的邀请码（用于邀请别人） |
| `parent_user_id` | `bigint(20)` | YES | NULL |  | — | 上级用户ID |
| `status` | `varchar(20)` | NO | "normal" |  | utf8mb4_general_ci |  |
| `user_type` | `varchar(20)` | YES | "normal" |  | utf8mb4_general_ci | 用户类型：normal-普通用户, agent-代理 |
| `kyc_level` | `int(11)` | YES | "0" |  | — |  |
| `created_at` | `datetime` | NO | "CURRENT_TIMESTAMP" |  | — |  |
| `updated_at` | `datetime` | NO | "CURRENT_TIMESTAMP" | on update CURRENT_TIMESTAMP | — |  |
| `kyc_status` | `varchar(20)` | YES | NULL |  | utf8mb4_general_ci |  |
| `last_login_at` | `datetime(6)` | YES | NULL |  | — |  |
| `last_login_ip` | `varchar(64)` | YES | NULL |  | utf8mb4_general_ci |  |
| `last_login_region` | `varchar(128)` | YES | NULL |  | utf8mb4_general_ci |  |
| `last_login_domain` | `varchar(255)` | YES | NULL |  | utf8mb4_general_ci |  |
| `current_token` | `varchar(128)` | YES | NULL |  | utf8mb4_general_ci | 当前有效的登录token标识（用于单设备登录） |
| `remark` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci | 备注（用于代理管理） |
| `last_activity_at` | `datetime(6)` | YES | NULL |  | — |  |
| `row_version` | `bigint(20)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `normalized_email` | `varchar(128)` | YES | NULL | STORED GENERATED | utf8mb4_general_ci |  |
| `normalized_phone` | `varchar(32)` | YES | NULL | STORED GENERATED | utf8mb4_general_ci |  |
| `last_page_code` | `varchar(32)` | YES | NULL |  | utf8mb4_general_ci |  |
| `last_page_seen_at` | `datetime(6)` | YES | NULL |  | — |  |
| `last_page_sequence` | `bigint(20)` | YES | NULL |  | — |  |
| `last_device_type` | `varchar(16)` | YES | NULL |  | utf8mb4_general_ci |  |
| `annual_income` | `decimal(14,2)` | YES | NULL |  | — |  |
| `annual_income_currency` | `varchar(3)` | YES | NULL |  | utf8mb4_general_ci |  |

### 索引

- `idx_parent_user_id`：普通索引，BTREE，(`parent_user_id`)。
- `idx_user_type`：普通索引，BTREE，(`user_type`)。
- `ix_tenant_online`：普通索引，BTREE，(`tenant_id`, `last_activity_at`, `id`)。
- `mt_fk_72d601353dacb02f6447`：普通索引，BTREE，(`tenant_id`, `parent_user_id`)。
- `mt_old_45569da57f4b7bf4`：普通索引，BTREE，(`phone`)。
- `mt_old_471b0683539fe0b1`：普通索引，BTREE，(`my_invite_code`)。
- `mt_old_82244417f956ac7c`：普通索引，BTREE，(`email`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `mt_tenant_status`：普通索引，BTREE，(`tenant_id`, `status`)。
- `my_invite_code`：唯一索引，BTREE，(`tenant_id`, `my_invite_code`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_tenant_normalized_email`：唯一索引，BTREE，(`tenant_id`, `normalized_email`)。
- `uk_tenant_normalized_phone`：唯一索引，BTREE，(`tenant_id`, `normalized_phone`)。

### 外键

- `mt_fk_72d601353dacb02f6447`：(`tenant_id`, `parent_user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_user_account`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_user_account`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## user_action

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：用户操作权限表

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — | 用户ID（代理ID） |
| `menu_id` | `bigint(20)` | NO | NULL |  | — | 菜单ID |
| `action_code` | `varchar(100)` | NO | NULL |  | utf8mb4_general_ci | 操作代码 |
| `created_at` | `datetime` | NO | "CURRENT_TIMESTAMP" |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `idx_menu_id`：普通索引，BTREE，(`menu_id`)。
- `idx_user_id`：普通索引，BTREE，(`user_id`)。
- `mt_old_5e8199ed62f57e99`：普通索引，BTREE，(`user_id`, `menu_id`, `action_code`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_user_menu_action`：唯一索引，BTREE，(`tenant_id`, `user_id`, `menu_id`, `action_code`)。

### 外键

- `mt_fk_e3389fab52e8a4faadae`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_user_action`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `user_action_ibfk_1`：(`user_id`) 引用 `user_account` (`id`)；UPDATE RESTRICT；DELETE CASCADE。
- `user_action_ibfk_2`：(`menu_id`) 引用 `admin_menu` (`id`)；UPDATE RESTRICT；DELETE CASCADE。

### 触发器

- `mt_immutable_user_action`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## user_bank_card

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：用户银行卡绑定表

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — | 用户ID |
| `currency` | `varchar(10)` | NO | NULL |  | utf8mb4_general_ci | 货币代码，如 USD, EUR, GBP |
| `bank_name` | `varchar(100)` | NO | NULL |  | utf8mb4_general_ci | 银行名称 |
| `bank_address` | `varchar(200)` | YES | NULL |  | utf8mb4_general_ci | 银行地址 |
| `swift` | `varchar(50)` | YES | NULL |  | utf8mb4_general_ci | SWIFT代码 |
| `recipient_name` | `varchar(100)` | NO | NULL |  | utf8mb4_general_ci | 收款人姓名 |
| `recipient_account` | `varchar(100)` | NO | NULL |  | utf8mb4_general_ci | 收款人账户 |
| `created_at` | `datetime` | NO | NULL |  | — |  |
| `updated_at` | `datetime` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `idx_user_id`：普通索引，BTREE，(`user_id`)。
- `mt_fk_28c4ef3f1dec09ee4a0a`：普通索引，BTREE，(`tenant_id`, `user_id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。

### 外键

- `mt_fk_28c4ef3f1dec09ee4a0a`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_user_bank_card`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_user_bank_card`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## user_digital_address

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：用户数字货币地址绑定表

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — | 用户ID |
| `currency` | `varchar(20)` | NO | NULL |  | utf8mb4_general_ci | 货币代码，如 BTC, ETH, USDT |
| `network` | `varchar(50)` | NO | NULL |  | utf8mb4_general_ci | 网络，如 BTC, ETH, ERC20, TRC20 |
| `address` | `varchar(200)` | NO | NULL |  | utf8mb4_general_ci | 钱包地址 |
| `created_at` | `datetime` | NO | NULL |  | — |  |
| `updated_at` | `datetime` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `idx_currency_network`：普通索引，BTREE，(`currency`, `network`)。
- `idx_user_id`：普通索引，BTREE，(`user_id`)。
- `mt_fk_6599d5f7c60256bd9f98`：普通索引，BTREE，(`tenant_id`, `user_id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。

### 外键

- `mt_fk_6599d5f7c60256bd9f98`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_user_digital_address`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_user_digital_address`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## user_id_sequence

分类：控制面。引擎：`InnoDB`。排序规则：`utf8mb4_unicode_ci`。

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `sequence_name` | `varchar(255)` | NO | NULL |  | utf8mb4_unicode_ci |  |
| `next_val` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `PRIMARY`：主键，BTREE，(`sequence_name`)。

## user_menu

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：用户菜单关联表（代理用户权限）

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — | 用户ID（user_account表的id） |
| `menu_id` | `bigint(20)` | NO | NULL |  | — | 菜单ID（admin_menu表的id） |
| `created_at` | `datetime` | NO | "CURRENT_TIMESTAMP" |  | — |  |
| `updated_at` | `datetime` | NO | "CURRENT_TIMESTAMP" | on update CURRENT_TIMESTAMP | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `idx_menu_id`：普通索引，BTREE，(`menu_id`)。
- `idx_user_id`：普通索引，BTREE，(`user_id`)。
- `mt_old_2b38be039022650d`：普通索引，BTREE，(`user_id`, `menu_id`)。
- `mt_old_d0307b96a663dd24`：普通索引，BTREE，(`user_id`, `menu_id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `UKr3a3ma3v059ubr7pchn83hcl5`：唯一索引，BTREE，(`tenant_id`, `user_id`, `menu_id`)。
- `uk_user_menu`：唯一索引，BTREE，(`tenant_id`, `user_id`, `menu_id`)。

### 外键

- `mt_fk_1e8ef3f7cc86f5982b54`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_user_menu`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_user_menu`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## verify_code

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：邮箱验证码记录

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `email` | `varchar(128)` | NO | NULL |  | utf8mb4_general_ci |  |
| `scene` | `varchar(32)` | NO | NULL |  | utf8mb4_general_ci | register/login/forget_password |
| `code` | `varchar(16)` | NO | NULL |  | utf8mb4_general_ci |  |
| `expire_at` | `datetime` | NO | NULL |  | — |  |
| `created_at` | `datetime` | NO | "CURRENT_TIMESTAMP" |  | — |  |
| `failed_attempts` | `int(11)` | NO | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |

### 索引

- `idx_email_scene`：普通索引，BTREE，(`email`, `scene`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `PRIMARY`：主键，BTREE，(`id`)。

### 外键

- `mt_t_verify_code`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_verify_code`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## withdraw_record

分类：租户私有。引擎：`InnoDB`。排序规则：`utf8mb4_general_ci`。

原表注释：提现记录表

| 字段 | 类型 | 可空 | 默认值 | 属性 | 排序规则 | 注释 |
| --- | --- | --- | --- | --- | --- | --- |
| `id` | `bigint(20)` | NO | NULL | auto_increment | — |  |
| `user_id` | `bigint(20)` | NO | NULL |  | — | 用户ID |
| `type` | `varchar(20)` | NO | NULL |  | utf8mb4_general_ci | 类型: digital 数字货币, bank 银行卡 |
| `network` | `varchar(50)` | NO | NULL |  | utf8mb4_general_ci | 网络/币种 (如 USDT-TRC20, USD) |
| `amount` | `decimal(32,16)` | NO | NULL |  | — | 提现金额 |
| `actual_amount` | `decimal(32,16)` | YES | NULL |  | — | 实际到账金额（扣除手续费后） |
| `fee` | `decimal(32,16)` | YES | "0.0000000000000000" |  | — | 手续费 |
| `address` | `varchar(200)` | NO | NULL |  | utf8mb4_general_ci | 提币地址/收款账户 |
| `remark` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci | 备注 |
| `country` | `varchar(100)` | YES | NULL |  | utf8mb4_general_ci | 国家 |
| `full_name` | `varchar(100)` | YES | NULL |  | utf8mb4_general_ci | 姓名 |
| `id_number` | `varchar(100)` | YES | NULL |  | utf8mb4_general_ci | 证件号码 |
| `bank_card_number` | `varchar(50)` | YES | NULL |  | utf8mb4_general_ci | 银行卡号 |
| `bank_name` | `varchar(100)` | YES | NULL |  | utf8mb4_general_ci | 银行名称 |
| `swift_code` | `varchar(50)` | YES | NULL |  | utf8mb4_general_ci | SWIFT代码 |
| `status` | `varchar(20)` | NO | "PENDING" |  | utf8mb4_general_ci | 状态: PENDING 待审核, APPROVED 已通过, REJECTED 已驳回, COMPLETED 已完成 |
| `review_remark` | `varchar(500)` | YES | NULL |  | utf8mb4_general_ci | 审核备注 |
| `reviewed_at` | `datetime` | YES | NULL |  | — | 审核时间 |
| `created_at` | `datetime` | NO | NULL |  | — |  |
| `updated_at` | `datetime` | NO | NULL |  | — |  |
| `row_version` | `bigint(20)` | NO | NULL |  | — |  |
| `currency` | `varchar(3)` | YES | NULL |  | utf8mb4_general_ci |  |
| `exchange_rate` | `decimal(32,16)` | YES | NULL |  | — |  |
| `original_amount` | `decimal(32,16)` | YES | NULL |  | — |  |
| `tenant_id` | `bigint(20)` | NO | NULL |  | — |  |
| `request_key` | `varchar(64)` | YES | NULL |  | ascii_bin |  |
| `request_hash` | `varchar(64)` | YES | NULL |  | ascii_bin |  |

### 索引

- `idx_bank_card_number`：普通索引，BTREE，(`bank_card_number`)。
- `idx_country`：普通索引，BTREE，(`country`)。
- `idx_status`：普通索引，BTREE，(`status`)。
- `idx_status_type`：普通索引，BTREE，(`status`, `type`)。
- `idx_user_id`：普通索引，BTREE，(`user_id`)。
- `mt_tenant_created`：普通索引，BTREE，(`tenant_id`, `created_at`)。
- `mt_tenant_identity`：唯一索引，BTREE，(`tenant_id`, `id`)。
- `mt_tenant_status`：普通索引，BTREE，(`tenant_id`, `status`)。
- `PRIMARY`：主键，BTREE，(`id`)。
- `uk_withdraw_record_request`：唯一索引，BTREE，(`tenant_id`, `user_id`, `request_key`)。

### 外键

- `mt_fk_b64ca5123f44eefd042f`：(`tenant_id`, `user_id`) 引用 `user_account` (`tenant_id`, `id`)；UPDATE RESTRICT；DELETE RESTRICT。
- `mt_t_withdraw_record`：(`tenant_id`) 引用 `tenant` (`id`)；UPDATE RESTRICT；DELETE RESTRICT。

### 触发器

- `mt_immutable_withdraw_record`：BEFORE UPDATE，同类执行顺序 1。完整保护条件及 SQL_MODE 在 `schema.sql` 中保留。

## 存储过程

### joint_s4_fence

类型：PROCEDURE。参数：IN `tenant` bigint(20), IN `symbol` bigint(20), IN `generation` bigint(20)。SQL SECURITY：DEFINER。SQL_MODE：`STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION`。

该过程校验 S4 历史投影的租户、交易品种、writer generation 和 control revision fencing；触发器调用它保护写入。不得为导入方便删掉过程或触发器。
