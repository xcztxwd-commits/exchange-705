# Docker 本地部署

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

PC 入口会按视口宽度自动切换：≤768px 进入同域名 `/mobile/`，放大后返回 PC 页面。首次打开和调整窗口大小均生效，两端共享登录存储。独立移动端端口仍固定显示移动页面。切换会重新加载页面，未提交的表单不会保留。

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
