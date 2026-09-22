# Binance 加密货币永续分类

管理后台的「项目分类与源分类绑定」及新增交易对目录现在提供：

- `Crypto`：加密货币（现货），使用 Binance Spot 目录、报价、K 线和 WebSocket。
- `CryptoPerpetual`：加密货币（永续合约），使用 Binance USDⓈ-M Futures 目录、报价、K 线和 WebSocket。
- `Metal`：保留原有黄金、白银永续分类。

永续目录只显示状态为 `TRADING`、合约类型为 `PERPETUAL`、标的类型为 `COIN` 的加密资产，并排除 XAU/XAG。此入口不包括币本位合约或交割合约。

永续品种内部代码使用 `_PERP` 后缀，例如 `BTCUSDT_PERP`；发送给 Binance 的源代码仍为 `BTCUSDT`。内部唯一代码、源身份、报价缓存、K 线缓存及实时订阅按市场区分，可与现货同时存在。已有品种的来源不随项目分类绑定改变；新增永续品种时选择永续源分类。

验证：Docker JDK 8 构建通过，122 项测试中 118 项通过、4 项环境条件测试跳过。新增测试覆盖目录过滤、现货共存、重复识别、分类保存、HTTP 报价与 K 线路由、独立缓存订阅及永续 WebSocket 元数据。宿主 JDK 21 与项目现有 Lombok 不兼容，未修改依赖，使用项目 Docker 工具链验证。

2026-09-22：从运行容器请求 `https://fapi.binance.com/fapi/v1/exchangeInfo` 15 秒超时。真实永续目录与行情的网络可用性尚未确认；不会自动回退为现货或其他交易所。

接口依据：[Binance USDⓈ-M 市场数据文档](https://developers.binance.info/en/docs/catalog/core-trading-derivatives-trading-usd-s-m-futures/api/rest-api/market-data)、[Binance WebSocket 升级公告](https://www.binance.com/en-NG/support/announcement/detail/ebf9b0aa9eca4ff3804eef6fb09ba32a)。
