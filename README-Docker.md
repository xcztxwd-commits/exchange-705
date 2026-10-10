# Docker 本地部署

## 最新数据库结构文档（2026-10-05）

最新结构快照、逐表字段/索引/外键字典、迁移顺序和校验和见 [docs/database/README.md](docs/database/README.md)。结构版本为 `2026100404`，实际包含 112 张表；机器清单另有 1 个仅登记、尚无审定 DDL 的名称，差异已明确记录。

公开快照不含业务数据、凭据、初始化账户或迁移/批准回执；仅供全新空库导入和结构参考。默认 Docker 仍引用本地 `1090.sql`，本次不替换挂载、不启动服务、不授予业务激活。

## 当前源码与本轮隔离运行（2026-10-02）

当前源码需要 Java 17 构建/运行、已迁移的多租户 schema、Hibernate `validate` 和平台鉴权配置。下文原始默认启动命令不能替代这些前置条件，也不能直接升级未迁移的历史业务库。

本轮按用户选择创建全新本机隔离环境，8 个组件健康、65 项启动检查通过；原业务数据库和上传文件未操作。业务仍为维护状态，全量测试和正式发布门禁尚未通过。本地打包显式跳过测试，不构成生产发布批准。

报告、独立恢复证明和可复跑启动/停止脚本：
`C:\workspace\fx\705\reports\docker-runtime-20261002-288b06b6\README.md`

```powershell
& 'C:\workspace\fx\705\reports\docker-runtime-20261002-288b06b6\Start.ps1'
# 只停止本轮容器，保留数据库卷和文件
& 'C:\workspace\fx\705\reports\docker-runtime-20261002-288b06b6\Stop.ps1'
```


## 发布前数据库兼容迁移（2026-09-29）

旧库升级必须在**备份已验证、后端停止写入的维护窗口**，并在启动新版后端前执行
`exchange-backend/src/main/resources/db/migration/widen_admin_menu_code.sql`。
JPA `ddl-auto=update` 不能代替此迁移。新库无 `admin_menu` 时脚本跳过，JPA 创建的实体列为 150。
脚本仅把短于 150 的 `menu_code` 扩为 `VARCHAR(150) NOT NULL`，保留字符集、排序规则、注释和唯一索引；重复执行安全，已更宽的列不缩短。
发现非预期列定义会报错停止，不能带 `mysql --force` 忽略错误继续发布。MySQL DDL 隐式提交，失败时检查实际结构再重试；不要尝试缩回 50。
需 `ALTER`、`CREATE ROUTINE`、`ALTER ROUTINE`、`EXECUTE` 权限。元数据锁等待最多 10 秒；扩列可能重建表，应先在备份恢复库验证耗时和磁盘余量。

发布人员在选定环境中显式执行（以下是 Bash 模板，数据库名必须核实；本修复不会自动操作运行中的发布）：

```bash
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 "$MYSQL_DATABASE"' \
  < exchange-backend/src/main/resources/db/migration/widen_admin_menu_code.sql
# 上一步非零退出即停止发布；成功后核对 SHOW CREATE TABLE admin_menu，再启动新版后端。
```

demo 独立旧库需对 `demo-mysql` 同样执行。`compose.demo.yaml` 为全新 demo 库显式设置
`utf8mb4/utf8mb4_unicode_ci`，使不带字符集声明的中文初始化 SQL 可导入。
**修改 MySQL 服务启动参数不会转换已有 latin1 数据库或表，也不能覆盖 SQL 内显式 latin1 定义。**
已有此类库须先备份、核查编码与索引长度，再制定单独转换方案；不要删除卷重建来“修复”历史数据。

隔离回归（仅新建一次性 MySQL 容器，无宿主端口、无生产卷）：

```powershell
python scripts/admin-permissions/test-startup-migration.py
```

该检查覆盖新库中文和长权限码写入、旧 50 字符列升级、重复执行、已有数据/字符集/注释/唯一性保留、拒绝缩列和非预期结构。
它验证 MySQL 兼容路径，不替代完整应用启动或生产备份验收。

所有 Java、Maven、Node.js、npm 依赖均在 Docker 镜像内安装和编译，无需在宿主机安装运行环境。宿主机已有的 node_modules、dist、target 不参与构建。

## 启动

首次部署需要在项目根目录创建 `.env`，分别设置随机的 `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`JWT_SECRET`。当前工作目录已生成该文件，不要提交到版本库。

```powershell
docker compose up -d --build
docker compose ps
./docker/smoke.ps1
```

- PC 端：http://127.0.0.1:17050
- 管理后台：http://127.0.0.1:17051
- 移动端：http://127.0.0.1:17052

PC 入口会按视口宽度自动切换：≤768px 进入同域名 `/mobile/`，放大后返回 PC 页面。屏幕短边≤768px 的触控手机固定使用移动页面，视频全屏和横竖屏切换不会触发端类型跳转。首次打开和调整窗口大小均生效，两端共享登录存储。独立移动端端口仍固定显示移动页面。切换会重新加载页面，未提交的表单不会保留。

后台账户见项目原有的 `后台密码.txt`。

## 数据和配置

MySQL 和 Redis 使用项目专属 Docker 命名卷。`1090.sql` 仅在空数据库卷首次启动时导入。上传文件保存在宿主机 `uploads` 目录，并挂载到后端容器。三个站点通过 Nginx 代理本地 API、上传文件和 WebSocket；后端、MySQL、Redis 不发布宿主机端口。

本机默认开启手动模拟订单（`MANUAL_ORDERS_ENABLED=true`），仅超级管理员可用；生产叠加配置强制关闭。需在本机临时关闭时，在 `.env` 设置 `MANUAL_ORDERS_ENABLED=false` 并重建后端容器。

本配置用于本机部署。行情需要外部服务网络和有效配置。邮件实际读取数据库 `system_config` 中的 `mail.*` 配置；导入的数据已配置 Gmail SMTP（`smtp.gmail.com:465`），凭据有效性与实际投递尚未验证，不使用 application.yml 中的示例邮箱配置。

本次验证：三个站点页面、后台登录与仪表盘、数据库查询、上传图片和 WebSocket 代理可访问。当前容器访问 `api.bitget.com` 出现连接拒绝/超时，实时行情和 K 线暂不可用；这部分需要恢复到外部行情服务的网络连通性。PC 端生产构建的 WebSocket 已改为使用当前站点代理。

## 日常操作

```powershell
# 查看日志
docker compose logs --tail 100 backend

# 暂停 / 恢复
docker compose stop
docker compose start

# 移除容器，保留数据库卷和上传文件
docker compose down
```

不要使用 `docker compose down -v`，除非明确要删除数据库和 Redis 数据。
