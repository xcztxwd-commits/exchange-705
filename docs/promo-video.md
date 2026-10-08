# 宣传视频

后台「系统设置 → 宣传视频」按用户端支持的 19 种语言上传 MP4 / WebM，单个文件最多 100MB。选择默认回退语言，然后点击「保存配置」。用户当前语言没有视频时，播放默认语言的视频；已有视频时必须给默认语言上传视频，避免回退失效。移除全部语言的视频并保存可停用播放。

语言绑定与回退语言原子保存在租户配置 `home.video.settings`：

```json
{"defaultLocale":"en","videos":{"en":"/api/uploads/videos/42/staff/1/00000000-0000-0000-0000-000000000001.mp4"}}
```

未设置新配置时兼容原 `home.video.url`。经典版首页「视频简介」与桌面版左侧入口使用同一播放器；切换语言会重新选择视频，关闭会停止播放。模拟账户也读取真实租户的公开宣传视频。

上传接口 `/api/admin/videos/upload` 要求 `settings:save`，配置保存继续使用现有权限和总控策略。文件以 `videos/{tenant}/staff/{admin}/{uuid}.{mp4|webm}` 存入私有 MinIO 桶；跨租户引用会被拒绝。只有已保存发布的视频可匿名访问，上传者可在后台认证预览未发布视频。播放接口支持 GET、HEAD 和单段 Range，按需读取 MinIO，便于播放与拖动进度。

## MinIO 连接

已有 MinIO 时，在后端环境中配置：

```dotenv
MINIO_ENDPOINT=http://minio:9000
MINIO_ACCESS_KEY=your-access-key
MINIO_SECRET_KEY=your-secret-key
MINIO_BUCKET=exchange-media
MINIO_PREFIX=real
FILE_STORAGE=minio
```

桶须预先创建并保持私有；应用账号需要对应运行环境对象的 PutObject、GetObject 及分段上传权限。凭据只保存在服务端。未配置或存储不可用时明确报错，视频不回退到本地目录。图片、音频、实名认证、头像和签名也统一使用 MinIO，详见[私有素材存储](private-media-storage.md)。

本地与线上使用各自的独立账号和私有桶，不对浏览器开放 MinIO 端口。`compose.yaml` 提供服务和上述环境变量。替换、移除只改变发布引用，旧对象保留；按需要配置 MinIO 保留或清理策略。

服务端请求上限 101MB、单文件 100MB；部署时必须同步更新网关配置。外部反向代理也需允许相同请求大小。SDK 使用 [MinIO Java 8.6.0](https://github.com/minio/minio-java/releases/tag/8.6.0)。

## 本次验证

- 三端生产构建通过；23 项后端测试通过，包含上传与配置校验、租户与权限边界、Range/HEAD，以及隔离 MinIO 私有桶实际上传、完整读取和分段读取。MinIO 集成测试仅在 `VIDEO_TEST_MINIO_ENDPOINT` 指向本机测试端口时启用。
- Chrome 实测后台上传、回退语言保存、预览、移除校验和锁定限制；手机端与桌面端视频播放、语言匹配/回退、拖动和关闭正常。页面使用模拟 API，覆盖 390px、1280px、1440px；存储实测单独连接隔离 MinIO。现有后台菜单的 Element Plus 指令警告、外部 `ipwho.is` 429 与主动模拟的 503 错误状态均已区分，未作为无错误全站验收。
- 本次素材存储与视频相关源码已登记；租户源码检查仍有 37 项存量问题，与修改前 `c950a98` 完全一致。本地真实 API 和完整候选包已经验证实名认证、头像的 MinIO 上传、用户与后台读取、权限边界和不落盘回退；实际部署记录另存私有发布目录。
