# 第一阶段本地运行与验收

本入口只执行租户基座、租户开通、身份、域名与总控访问验证，不部署、不推送，不自动执行资金阶段或完整项目回归。

## 前提

- 在 `C:\workspace\fx\705` 运行。使用当前工作区代码，保留现有未提交改动。
- 已有 Java/Maven、Node/npm、PowerShell 7、Python（cryptography）、Docker，以及当前项目依赖。
- 本机端口 443、465、33318、33630、33631、33632、33405、19405 必须空闲；端口冲突时拒绝启动，不停止其他服务。
- 默认使用 Windows 已安装的 Chrome 和 Playwright；非默认位置通过 `CHROME_PATH`、`PLAYWRIGHT_PATH` 指定。浏览器正常校验证书；需要信任本轮 CA 时，须明确选择下面的临时信任或人工确认方式。

## 新建本轮独立环境

```powershell
Set-Location C:\workspace\fx\705
$run = 'C:\workspace\fx\705\reports\stage1-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
pwsh -NoProfile -ExecutionPolicy Bypass -File C:\workspace\fx\705\scripts\stage1\Start-Stage1.ps1 -RunDir $run
```

每轮生成独立数据库、Redis 卷、文件目录、TLS CA/证书和凭据。Docker 网络根据实际已有子网选择，不删除旧网络。所有公开映射只绑定 `127.0.0.1`；备份恢复另用独立 MySQL 服务器，不发布端口。

初始化先读取当前迁移清单，使用合成旧结构数据演练当前 DDL，验证原始字段不变及独立恢复。再创建独立 IT 克隆，禁止用真实业务库验收。原始结构文件只是迁移参考，绝不恢复旧应用代码。

默认、A、B 的负责人、配置、功能授权、域名验证和启用都由真实接口完成。域名使用 `*.localhost` 和本轮 CA；邮件通过认证 TLS SMTP 真实投递。短信只启用已有受限 `CONFIG_TEST` 本地文件 sink，不宣称真实供应商验证成功。出站例外仅显式列出的回环 Host/端口，默认策略和 TLS 验证保持有效。

`private` 目录受当前用户 ACL 保护，保存随机口令、令牌、邮件与短信，不将其内容复制到报告或终端。浏览器必须正常验证证书链、有效期与 Host；只通过正常证书库信任本轮 CA，不使用证书错误忽略参数或自动继续证书警告。不修改操作系统 DNS 或 hosts。

## 执行必测

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File C:\workspace\fx\705\scripts\stage1\Test-Stage1.ps1 -RunDir $run -Browser Windows -TrustFixtureCertificate
```

`-TrustFixtureCertificate` 是显式选择临时信任，不是关闭 TLS 校验。只检查并安装这个运行目录的 CA 到 `CurrentUser Root`，保留证书链、有效期、Host 校验；入口结束时仅移除本轮新导入的 CA。Windows 要求人工确认时必须由用户确认，不自动绕过。

若自动导入等待人工确认超过时限，可先运行 `Fixture-Certificate.ps1 -RunDir $run -Action Prepare` 记录精确证书的原有状态，再使用下面命令从同一 CA 导出 DER 文件并打开正常证书窗口，核对本轮名称、指纹和有效期，安装到当前用户的受信任根证书库。PEM 文件不能直接用 Windows 证书查看器可靠打开；导出格式不改变证书内容或信任策略。随后使用同一测试命令核验已安装证书与文件逐字节一致；结束时仍只清理本轮证书。默认不加该开关时，入口不会修改证书库；未建立正常信任就停止浏览器验收。不关闭防病毒、不关闭 TLS、不修改其他证书。

人工确认使用同一证书，不生成新 CA：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File C:\workspace\fx\705\scripts\stage1\Fixture-Certificate.ps1 -RunDir $run -Action Prepare
$ca = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new((Join-Path $run 'private/ca.crt'))
try { [IO.File]::WriteAllBytes((Join-Path $run 'private/ca.cer'), $ca.RawData) } finally { $ca.Dispose() }
Start-Process -FilePath (Join-Path $run 'private/ca.cer')
```

`-Browser Linux` 为可选独立浏览器路径，安装依赖时仍保持 HTTPS、APT 签名与下载完整性校验，正常信任只在容器内建立。当前阶段验收使用 Windows 路径；Linux 构建失败和下载记录保留，不作为 Windows 真实浏览器通过的替代证据。

入口执行不导入证书的原生 Prepare/缺席证书清理检查、当前源码隔离门禁及反例测试、真实 MySQL 约束检查、阶段相关后端测试、前端权限/会话/注册单元测试、实际 API 开通与四端真实浏览器冒烟。`SchemaPackageGuardMySqlIT`、`Stage1TenantRepositoryMySqlIT` 明确列入 Maven `-Dtest`，不能依赖默认 `*Test` 筛选。资金及长历史压力测试不属于本阶段。

每次验收保留新时间戳日志、真实 `.exit` 和本次选中 Surefire XML；失败立即停止，修复根因后另开记录重测。API 的续跑状态保存在本轮私有目录，已有身份必须重新真实登录。缺少阶段必测、跳过、浏览器白屏或模拟后端不能标记完成。

## 停止本轮资源

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File C:\workspace\fx\705\scripts\stage1\Stop-Stage1.ps1 -RunDir $run
```

停止前检查 PID 命令行、创建时间和容器本轮标签；归属不匹配就拒绝。只停止，不删除数据库、卷、文件或测试证据。不能直接停止现有业务服务，也不要将旧版无租户应用接入本轮多租户数据库。

若 Windows 在正常移除本轮 CA 时要求人工确认，停止入口会保留非零退出码；本轮主服务已停止不等于证书已经删除。打开 `certmgr.msc` 的“证书 - 当前用户”，在“受信任的根证书颁发机构／证书”中，仅删除所有权记录 `fixture-certificate-trust.json` 指定的本轮名称与指纹。不能删除其他证书、更改根证书保护策略或关闭防病毒。删除后再次执行同一 `Stop-Stage1.ps1 -RunDir $run`，该入口识别已归档的服务记录，不重复终止旧 PID，并核验本轮 CA 缺席；正常清理成功才返回 0。

上述执行策略参数只作用于本次本地 PowerShell 子进程，不更改系统执行策略；只执行本项目已审查的本地脚本。

若需要在同一本轮环境重启，先执行上述停止入口，再运行 `environment.py start --run-dir $run` 和 `services.py --run-dir $run`；不会重新初始化数据库，已有总控不会重复引导创建。修改后端代码后先重新构建当前 jar，不能继续用旧进程验证新源码。
