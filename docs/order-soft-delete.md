# 订单软删除：需求与执行方案

范围：订单管理中的合约、期货订单，包括人工历史订单。充值、理财不在本次范围。

1. 两张订单表新增 nullable deleted_at、deleted_by；时间为空代表未删除。保留原 status、盈亏、资金流水和订单主键，不执行物理删除。
2. 后台默认显示全部，删除订单显示“已删除”并置灰；独立删除状态筛选支持全部/未删除/已删除，与原交易状态、用户、代理条件组合，数据库先筛选再分页。
3. 用户端共享订单列表接口在数据库过滤 deleted_at，手机与 PC 同时生效；不改余额、资产曲线或财务报表。
4. 删除与恢复由有订单菜单及 delete_order / restore_order 操作权限的管理员执行，代理不可执行。复用现有菜单鉴权。旧 abnormal-delete 接口改为同样软删除，杜绝旧入口物理删除及退款。
5. 安全边界：仅已平仓 CLOSED、已取消 CANCELLED 可删除。活动订单须先走原平仓/撤单流程，避免隐藏仍有资金风险的持仓。恢复只恢复展示，不重新开仓或重复入账。事务及现有 @Version 防并发覆盖，重复操作幂等。
6. 执行：备份涉及文件；修改实体、查询接口和后台；运行自动回归及前端构建；迁移先于后端发布。当前 ddl-auto:update 会补充 nullable 字段，也提供显式 SQL。

回滚：rollback/order-soft-delete-20260929 保存本次修改前文件。恢复代码前，应先决定是否恢复已删除订单的用户可见性；保留新增字段即可，无需删表或删数据。未自动操作任何真实订单。

## 验证结果

- 后端定向回归 5 项通过：两类订单删除/恢复、数据库保留、用户查询隐藏、后台过滤分页、活动订单保护、余额/冻结资金与平仓时间不变、并发版本冲突、代理范围与删除恢复拒绝。
- 后台首次 TypeScript 检查和生产构建通过；最新全量构建被同时修改的 src/utils/access.ts:41 的 TS2345 阻塞（string | undefined 传入 string）。订单组件脚本回归仍通过，覆盖删除、恢复、重载、筛选重置与活动订单保护。
- 工作区同时有权限模块改动，曾导致其他测试 DepositOrderAccessTest 的 BackendAccess 构造参数不匹配。未修改该无关测试；定向测试使用已编译生产类及独立 javac + surefire:test 验证，不代表全量测试通过。
- 未发布容器、未修改运行中数据库、未删除真实订单，避免把其他进行中的改动一起发布。

复验命令：
```powershell
node exchange-admin/tests/orderSoftDelete.test.mjs
npm --prefix exchange-admin run build
mvn -f exchange-backend/pom.xml -Dtest=OrderSoftDeleteTest test
```
完整 Maven 测试需先完成同时进行中的权限测试适配。上线时执行 SQL（仅首次），再发布后端与后台；用户端无需改包。先备份数据库并在隔离环境验证迁移。
