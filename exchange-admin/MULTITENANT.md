# 多租户前端构建

业务 API 必须走当前域名的 `/api` 网关；模拟域使用同源 `/demo-api`，不接受跨域 API 基址。PC 与移动端使用租户的唯一前台域名，移动端可部署于同域 `/mobile`；后台和总控使用各自的独立域名。代理必须保留校验过的原始 Host，不能由客户端任意租户头选择租户。

总控是独立 Vite 入口，不加载租户后台 Pinia 身份。“进入后台”在点击时立即请求独立浏览器窗口；浏览器策略仍可能阻止弹窗或改为标签页。后台入口使用总控认证接口返回的 `adminOrigin`，交换页从同源 `GET /api/admin/auth/control-exchange-config` 读取 `platform.admin-origin`、`platform.control-origin`。不再依赖容易漏设的 `VITE_ADMIN_ORIGIN`、`VITE_CONTROL_ORIGIN`，不猜测域名、不使用 URL 参数或 referrer 作为可信来源。

配置接口只公开两个精确来源，`Cache-Control: no-store`，仍经过后台 Host/Origin 边界；只有此 GET 可匿名，不开放其他后台权限。生产入口必须为 HTTPS 精确来源。前后端需一起发布，旧后端没有此接口时不会降级。不得把令牌、交换票据或 MFA 密钥写入 URL、构建变量或日志。

新窗口的总控凭据副本在空白页阶段清除；票据仍绑定目标租户、真实总控身份和浏览器随机值，只可消费一次。父窗口等待新窗口实际交换、导航完成后才判定成功，失败或关闭会清理握手监听和超时，不影响原总控或租户人员会话。

```powershell
npm run build -- --outDir dist-multitenant
npm run build:control
npm run test:entry
npm run test:tenant
node tests/readPolling.test.mjs
node tests/permissionCoverage.test.mjs
```

`dist-multitenant` 是租户后台，`dist-control` 是独立总控，均需对应域名的 SPA 回退。构建成功不代表部署或真实服务验收。后台交换需真实单次票据、严格窗口来源与挑战验证，不能用 mock 的成功响应作为验收。

后台旧 `localStorage.admin_token/admin_user` 不迁移，原用户需要重新登录；新凭据仅保存在当前标签页。总控访问到期或撤销不会回退到普通管理员身份。新建管理员首次登录必须改密码。图片仅通过同源带 Authorization 请求得到短生命周期 Blob URL，旧不带租户归属的图片等待后端迁移，不自动降级到公开直链。

租户公共图标、收款二维码、理财图片需要服务端明确的公开引用授权；不能以允许所有 staff 上传文件的方式解决。默认留存策略不删除，会话清理必须预览版本/范围一致、归档校验成功、输入原因并再次确认。

PC 和移动端从真实同源 `/api/tenant/features` 读取公开功能快照，不附带凭据、不走模拟服务；路由变化、重新可见和手动刷新更新快照，不加永久心跳。核验失败时关闭新增业务入口，历史和资金退出不受前端快照隐藏。真实新增业务仍必须由后端再次校验。
