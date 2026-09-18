# Docker 本地部署

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

后台账户见项目原有的 `后台密码.txt`。

## 数据和配置

MySQL 和 Redis 使用项目专属 Docker 命名卷。`1090.sql` 仅在空数据库卷首次启动时导入。上传文件保存在宿主机 `uploads` 目录，并挂载到后端容器。三个站点通过 Nginx 代理本地 API、上传文件和 WebSocket；后端、MySQL、Redis 不发布宿主机端口。

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
