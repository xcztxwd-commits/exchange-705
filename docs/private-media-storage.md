# 私有素材存储

图片、GIF、实名认证正反面、用户头像、贷款签名、凭证、活动与分享素材、音频及宣传视频统一存入私有 MinIO。现有数据库中的应用 URL 保持不变；读取仍经过应用的租户、用户、代理范围、发布引用和后台菜单权限检查。MinIO 地址与凭据不返回浏览器。

对象路径使用运行环境前缀：`real/images/{tenant}/user/{user}/{uuid}.png`、`demo/images/{tenant}/staff/{staff}/{uuid}.gif`，音频与视频使用对应的 `audio`、`videos` 目录。正式与演示环境分开存放。图片和音频仍限制 5MB；图片继续解码检查并保留 GIF 动画，签名共用同一上传路径。

```dotenv
FILE_STORAGE=minio
MINIO_ENDPOINT=http://exchange-705-private-minio:9000
MINIO_ACCESS_KEY=server-only-media-account
MINIO_SECRET_KEY=server-only-secret
MINIO_BUCKET=exchange-media
MINIO_PREFIX=real
```

本机直接运行 Java 时，使用本机 MinIO 的回环地址；Docker 内使用服务名并加入素材服务所在网络。`compose.yaml` 提供 MinIO 服务和持久卷，所需 root 凭据通过私有环境文件注入。新实例需先创建私有桶与应用账号。应用账号只需要目标桶的 GetBucketLocation、ListBucket、ListBucketMultipartUploads，以及运行环境对象的 GetObject、PutObject、AbortMultipartUpload、ListMultipartUploadParts，不授予 DeleteObject。

应用默认使用 MinIO。仅显式设置 `FILE_STORAGE=local` 时使用原目录模式；MinIO 模式遇到连接或读写错误不会自动落盘。MinIO 不公开桶，不对公网映射端口；控制台关闭，凭据只存服务端私有配置。视频替换和移除只改变发布引用，旧对象保留。

## 迁移与发布验证

迁移先复制旧上传目录，再逐个比较源文件和 MinIO 读取内容的 SHA-256，原目录保留。切换前停旧后端补做最后一轮增量复制，避免迁移期间的上传遗漏。应用接口与数据库引用无需改写；回退可恢复原镜像及目录模式。

本地验收使用真实回环 HTTP、JWT 与租户过滤、真实配置和菜单权限、隔离数据库以及私有 MinIO。实际提交实名认证正反面与用户头像，确认数据库引用、用户读取、后台用户详情与实名认证审核读取、无权限拒绝、跨用户与跨租户拒绝、匿名 MinIO 返回 403，且无本地文件回退。该验收也对保留线上其他类与依赖的完整候选包执行。

`MediaMinioAcceptanceTest#identityUploadsReadForUserAndCorrectBackendPermissions` 仅接受回环 `VIDEO_TEST_MINIO_ENDPOINT`。上传与读取使用应用账号；清理测试对象可另行提供 `VIDEO_TEST_MINIO_ROOT_ACCESS_KEY`、`VIDEO_TEST_MINIO_ROOT_SECRET_KEY`。测试仅清理自身独立前缀。另有 MinIO 实际 PNG/GIF/音频/签名上传、Range、视频完整与分段读取测试。

部署时后端单文件上限 100MB、请求上限 101MB，应用 Nginx 和入口网关需同步允许 101MB 请求。发布包按现有线上镜像叠加相关类与新依赖，逐条校验其他 JAR 内容和前端旧静态资源，保留现有运行配置及回退包。实际发布、迁移数量及线上复测记录保存于独立私有发布目录。
