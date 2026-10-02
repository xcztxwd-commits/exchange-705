# 账户实名与贷款资料分离

实施日期：2026-09-28 至 2026-09-29。

## 业务约定

1. `kyc_record` 是基础身份来源。手机端 `/verification` 与 PC 个人中心实名认证统一使用 `/api/kyc/*`，由后台「实名审核」处理。
2. 账户实名通过后，用户补充电话、地址及手持证件照，由后台「贷款资料审核」处理。后台菜单路由及权限代码保持不变：`/loan-personal-info-review`、`loan_personal_info_review`。
3. 贷款资料的姓名、证件号与正反面照片由服务端从已通过的 KYC 复制，前端只读；`loan_personal_info` 中原有身份列保留为审核快照，不再接受客户端改写。
4. 申请贷款必须同时满足基础实名通过、贷款资料通过、身份一致、补充资料完整。贷款申请只需金额与贷款配置 ID，服务端读取已审核资料。旧客户端多传的身份字段不会被采用。
5. 贷款签约后的放款审核再次检查资料与身份。已签约但身份不匹配的旧申请不允许放款，须重新申请。已放款、还款记录及余额不做迁移或重写。

## 接口变化

- `/api/kyc/status` 的 `latestRecord` 补齐当前用户的证件号、正反面照片；PC 不再使用贷款审核状态作为 KYC 状态。
- `/api/loan/personal-info/submit` 接收电话、地址及手持照片（URL 或文件）；身份信息、正反面照片只取服务端 KYC。
- `/api/loan/personal-info/status` 返回 `kycVerified`、`verified`、`status`、`reviewRemark`、资料及审核时间。待审核禁止编辑；拒绝和需要更新时允许重新提交。
- 后台贷款资料列表补齐手持照片，审批时核验基础实名与资料一致性。
- 手机端与 PC 均去掉重复正反面上传、身份编辑和虚假电话地址占位值。保留原路由兼容，旧贷款填写页面改为只读确认。

## 历史资料处理

无需数据库结构迁移。不自动生成 KYC、不自动批准旧资料、不删除历史记录。

- 缺少已通过 KYC 的用户必须先完成基础实名。
- 姓名或证件号不一致、电话地址或手持照不完整的旧资料返回 `NEEDS_UPDATE`；补全提交后重新进入 `PENDING`，清空旧审核人和时间。
- 已通过且与当前实名一致、资料完整的贷款资料继续有效。
- 手机端 KYC 审核中不再显示可重复提交的表单。

## 验证

- 新增 `IdentityLoanFlowTest`：13 项，包括缺少/未通过 KYC、单独 KYC 不等于贷款通过、拒绝重提、占位联系方式、历史身份不一致、客户端伪造身份被忽略、申请门槛和放款校验。
- 既有 `MinimalFixRegressionTest`：45 项通过。
- 手机端、PC、管理后台生产构建通过；只有既有打包体积提示。
- `identityLoanFlow.browser.cjs` 使用模拟 API，不提交真实身份或触发真实贷款；覆盖两端身份只读、补充资料提交、待审核锁定、缺少 KYC 引导、PC 状态分离及真实 KYC 提交接口。浏览器无未捕获运行错误。
- 浏览器截图与结果保存在 `C:/workspace/fx/705/reports/identity-unification-20260928/`。

复验命令（项目根目录）：

```powershell
mvn -q -f exchange-backend/pom.xml '-Dtest=IdentityLoanFlowTest,MinimalFixRegressionTest' test
npm run build --prefix exchange-frontend
npm run build --prefix exchange-pc
npm run build --prefix exchange-admin
```

浏览器测试需分别启动手机端 Vite 端口 5293、PC Vite 端口 5295，再运行：

```powershell
node exchange-frontend/tests/identityLoanFlow.browser.cjs
```

## 发布与回滚

本次仅修改工作区代码并验证，未重新部署 Docker、未修改运行中数据库。

上线需协调发布后端、手机端、PC 和后台，避免新旧接口同时运行。旧用户可能需要补做基础实名或重新提交贷款资料，不能用批量自动通过代替审核。

修改前原文件备份：`C:/workspace/fx/705/rollback/identity-unification-20260928/`。`manifest.json` 列出原文件与哈希，`changes.patch` 只包含本次修改相对备份的差异。备份保留了本次开始前已有的未提交修改。

回滚前先比对后续改动，仅恢复 manifest 中对应文件；不要整库 reset。新增测试和报告可保留。本次没有数据库迁移，无需数据库回滚。
