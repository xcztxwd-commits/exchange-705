# 注册与网站安全

## 入口与规则

- 后台「网站安全」：`/website-security`，仅超级管理员可读写，修改记录进入操作日志。
- PC 实际注册入口是交易首页的注册弹窗；手机是 `/mobile/register`。旧独立注册页亦已接入。
- Hutool `hutool-captcha:5.8.40` 的 `AbstractCaptcha` 自定义合成：每张 4 位，至少一个字母和一个数字；排除易混淆字符 0、1、I、O；不区分大小写。
- 每张独立随机：3–9 条线段、1–5 个圆圈、0–1 层轻度正弦位移。这里的扭曲是位移层数，不是 Hutool ShearCaptcha 的干扰线宽。
- 有效期 120 秒。邀请码可选。刷新没有“验证码已更新”提示。

## 存储、校验、并发

1. 页面以 `crypto.getRandomValues` 生成 128 位随机 `captchaSession`，保存在当前标签页 sessionStorage；无法使用存储时退回页面内存。它只是挑战命名空间，不是登录身份，不能作为唯一防刷依据。
2. `POST /api/auth/captcha`：请求 `{captchaSession}`，返回 `{captchaId,image,expiresIn:120}`。`image` 是 PNG Data URL；响应不包含答案，禁止缓存。
3. Redis `security:{registration}:challenge:<captchaSession>` 保存 `captchaId|answer`，TTL 120 秒，不入业务数据库。
4. 刷新通过限流后，先覆盖为新 ID 的 pending 标记，使旧图立即失效，再生成图片。Lua 比较并发布，防止旧生成请求覆盖新挑战。生成失败也不会恢复旧答案。
5. `POST /api/auth/register` 增加 `captchaSession`、`captchaId`、`captchaCode` 三个必填字段。
6. 校验在用户创建、资产初始化之前执行。Lua 对匹配 ID 读取并删除，随后比较答案：正确、错误均一次性消费，并发最多一个成功；旧 ID 不会删除新挑战。过期、伪造、重放均失败。
7. 格式校验失败或限流拒绝的请求不会消费挑战，也不会执行注册。前端提交后立即清除可提交 ID，不自动重试注册。

## 限流配置

配置复用 MySQL `system_config`，键 `security.registration.v1`，JSON 单行原子保存，无须新增表。缺少配置行使用安全默认值；读取失败或配置损坏返回 503，不放行。保存后下一次请求生效，不清空已有计数。

| 维度 | 默认次数/60秒 | 后台可设范围 |
| --- | ---: | ---: |
| 图片单 IP | 30 | 1–300 |
| 图片单页面会话 | 10 | 1–60 |
| 图片全站 | 600 | 1–10000 |
| 注册单 IP | 10 | 1–100 |
| 注册单页面会话 | 5 | 1–30 |
| 注册全站 | 120 | 1–3000 |

Redis Lua 原子计数，多实例共享。每个键从第一次允许请求起计时 60 秒，不是滑动窗口；被限流拒绝不续期。通过网络限流后，无论业务成功失败都占配额。网络全站/IP 限流在请求体校验前执行；会话限流在参数合法后执行。标签页 ID 可被客户端轮换，因此 IP 和全站限制始终同时启用。不能设置 0 关闭防护。

后台接口：`GET/PUT /api/admin/website-security`。PUT 校验整数上下限、null、未知字段；通用配置写入同一键也必须通过校验。普通管理员、代理、普通用户不得修改。

## 前端与故障策略

- 获取、刷新中禁止重复点击；刷新清空旧图/答案；AbortController 与版本号阻止卸载后更新及过时响应回填。
- 只有图片有效、未过期且输入 4 位字母数字时，注册按钮才可用。前端状态不能代替后端校验。
- 注册失败保留邮箱、密码、邀请码；通常自动获取新图，但不自动重试注册。
- 400 `CAPTCHA_INVALID`：错误/过期/重放；409 `CAPTCHA_REPLACED`：并发刷新被较新请求替代。
- 429 `SECURITY_RATE_LIMITED`：附 `Retry-After`，前端倒计时并禁止提前刷新。
- 503 `SECURITY_UNAVAILABLE`：Redis、配置读取、生成异常时拒绝注册；显示暂不可用，允许稍后手动刷新。不使用本地验证码或跳过校验降级。
- Redis 命令和连接超时均 3 秒。日志脱敏 captcha 字段；不要记录验证码答案或完整注册密码。
- API 网络限流不是 DDoS 防护；需在公网入口继续配合边缘连接数/带宽防护。共用出口 IP 的用户共享 IP 配额，应按真实流量调整。

## 代理与部署

仅当直接连接方位于 `security.trusted-proxies` / `SECURITY_TRUSTED_PROXIES` 时使用 `X-Real-IP`。当前 Compose 信任内部子网 `10.235.70.0/24` 和回环；Nginx 用 `$remote_addr` 覆盖该头。后端不得直接暴露公网。若前方再接 CDN/代理，应先按实际可信地址配置 Nginx real_ip，禁止无条件接受客户端 X-Forwarded-For。

本地部署保留现有资产权益覆盖文件：

```powershell
docker compose -p exchange-705 -f compose.yaml -f scripts/asset-equity/enable.local.yaml build backend pc mobile admin
docker compose -p exchange-705 -f compose.yaml -f scripts/asset-equity/enable.local.yaml up -d --no-deps backend pc mobile admin
```

首次无需初始化数据；管理员保存时创建配置行。升级必须同时发布后端与两端页面，旧缓存客户端会因缺失验证码字段被拒绝。

## 测试

```powershell
# 仅使用独立 Redis；禁止指定业务 Redis
docker run -d --rm --name exchange-705-captcha-test -p 127.0.0.1:16379:6379 redis:7-alpine redis-server --save '' --appendonly no
mvn -q -f exchange-backend/pom.xml '-Dsecurity.test.redis.port=16379' test
docker stop exchange-705-captcha-test
```

- `RegistrationSecurityTest`：图形范围/混合字符、配置边界、失败关闭、IP 信任、尾斜杠防绕过、真实 Redis 刷新/重放/并发/过期/限流/动态修改。
- `RealRegistrationFlowTest`：真实 Spring MVC、H2 和独立 Redis，测试旧图、错码、消费、成功建用户和资产、重放、缺参；不创建生产用户。
- `MinimalFixRegressionTest`：超级管理员权限、配置持久化及非法配置拒绝；其中旧业务注册用例模拟安全服务，真实安全链由上述专用集成测试覆盖。
- `scripts/check-registration-security.browser.cjs`：Vite 端口 5227/5228/5229；API 模拟，无真实注册。覆盖 PC 实际弹窗、手机布局、刷新、错误保留字段、冷却、过期、后台保存重载。截图验证码是模拟夹具，生产干扰范围由 Java 测试验证。
- 测试报告在 `reports/registration-security` 与 `reports/captcha-java-full-tests.log`。其他原有外部集成测试仍按项目条件跳过，不代表全部外部系统已验证。

2026-09-27 本机验证：Java 共 222 项，211 通过、11 项条件跳过、0 失败；PC/手机/后台浏览器场景通过；四个 Docker 镜像构建成功。已更新本地 705 容器，后端 healthy，保留资产权益开关。实际接口验证 PNG 240×104、无答案、no-store，缺验证码注册 400，匿名后台安全接口 401；未在业务库创建测试账户。实际页面截图为 `reports/registration-security/live-pc.png` 和 `live-mobile.png`。

## 回退

源文件备份位于 `rollback/registration-security-20260927-215219`。不要批量覆盖其他未提交工作。回退前记录 `git diff`；按本次变更恢复对应文件，重新构建前后端三端。数据库配置行可保留，旧代码不读取；不要清空 Redis 或删除业务数据。

原运行容器引用的旧镜像层在本机 Docker 中已缺失，无法重新打标签或 commit；已用 `docker cp` 备份正在运行的旧后端 JAR、PC/手机/后台静态文件。紧急内容回退可在上述 Compose 命令再加 `-f rollback/registration-security-20260927-215219/restore-content.yaml` 后执行 `up -d --no-deps backend pc mobile admin`。该覆盖只适用于本机绝对路径，不包含业务数据库/上传文件；解除覆盖并重新创建容器才恢复新版本。回退会同时撤销新增验证码防护。
