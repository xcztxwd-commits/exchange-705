# MT02 最后定向独立复验

2026-09-29，Asia/Singapore；实际测试结束约05:59。

## 结论

**MT02 本轮定向独立复验通过。** 不是沿用修复负责人结果：新建源码快照、独立构建、随机隔离MySQL、真实本机HTTP和浏览器重新执行。

- 原失败 `ManualHashRecheckTest` 文件逐字节不变，1/1通过；另运行共享 `ManualOrderHashTest` 7/7通过，Maven退出0。
- 自有HTTP夹具加入实际参数改变、第二名超级管理员、预览时效和旧持久摘要兼容检查，1/1通过，Maven退出0。
- 真实浏览器五目标generate后不编辑、不重新preview直接create，HTTP200、orderId=2，浏览器退出0。
- 最终隔离SQL：orders=2、audit=2、available=1000.0000000000000000、frozen=0；两单均为MANUAL_TEST/BASE_ASSET/BTC。包括先前直接HTTP正例一单、浏览器一单。
- 未修改共享业务源码或公共测试；未部署、未执行Git命令、未接触业务库。未重跑700项大回归。

## 源码、交付指纹和漂移

先读取 `C:/workspace/fx/705/docs/session-monitor/tests/manual-tolerance-mt02-fix-20260929.md`。

本轮证据目录 R：`C:/workspace/fx/new/manual-mt02-independent-20260929-055552`；实际构建目录 `R/source`。

三个当前源码哈希与交付匹配：
- ManualOrderService.java：faef65323e4c716b43f1bb00abe31678b27990715e8fdba2671c80ca9d82f8f6
- ManualOrderMySqlIT.java：659519c086d95c48752cfed070f222a99f28d16f45771689511f008ef890891d
- ManualOrderHashTest.java：8b6d8476544b3b91fa2d5a0440d9fe29b099bdc7c1a10b58f949100b77c75d19

`R/delivery-hashes.json`、`R/source-before.json`、`R/copy-verification.json`、`R/source-after.json`：669个原文件，复制前后匹配；测试结束共享源漂移0、快照原文件漂移0。独立新增夹具指纹在 `R/fixture-hashes.json`。没有读取Git状态或HEAD，本轮以逐文件指纹标识版本。

原失败夹具从 `C:/workspace/fx/new/manual-recheck-20260929-045631/source/exchange-backend/src/test/java/com/gtcfesk/exchange/trade/ManualHashRecheckTest.java` 原样复制；新旧SHA-256均为 `1d1f91c4716b3e5bd06ab9b4794c474f6c9e302273cd4c9266175a0d3b349d5d`，见 `R/original-hash-test-equality.json`。

## 隔离措施

- 复制时排除全部 `.env*`、node_modules、target、dist和.git；源清单内.env文件0。只将现有前端依赖建只读使用的junction，不安装依赖。
- Vite `envDir:false`，固定客户端API基址 `/api`；代理仅指向本轮随机后端 `127.0.0.1:63348`。页面服务 `127.0.0.1:18398`。
- **导航和登录之前**安装BrowserContext请求拦截，非127.0.0.1请求abort；禁用service worker。登录使用页面fetch且redirect:error，同样受路由拦截，而非不受拦截的APIRequestContext。`R/blocked-requests.json`为 `[]`，记录的业务API地址全部loopback。
- JWT密钥每次运行用RandomNumberGenerator产生64随机字节，仅进程环境传入，runner finally清除；未输出令牌或密钥，无业务凭据。
- Maven `-o`离线构建；Docker `--pull=never`，现有MySQL5.7镜像、随机名称、tmpfs、2CPU/2GB、随机loopback数据库端口。真实服务仅连接该合成库，外部行情/Redis/邮件等依赖用测试替身；流和邮件连接关闭/指向本机。
- 自建Vite已通过工具session停止；自建容器已按标签验证删除。`R/cleanup.json`：ownedViteProcessExists=false、remainingContainers=0。
- 启动后台Vite的一条复杂工具命令被执行策略拒绝，未产生服务；改用工具管理的前台Vite session，无权限提升。所有测试断言保留。

以上是应用/浏览器层隔离证据，没有进行全机抓包，不把它表述成全机网络取证结论。

## 逐项实测

### 1. 原数值等价失败反例

原断言比较 `input=0.0100000000000000` / `0.01`、`leverage=10.00` / `10` 的实际服务hash。以前不同，本轮相同：
`bba80ab60afc319c7e3b27d0c8fe764f4fc3596b786409fe4605340ef3164909`。

共享7项补充覆盖科学计数、正负零、浏览器数值转字符串往返、每个实际参数及操作者绑定、不经double损失精度、大指数不展开。全部通过。此组不需要数据库。

### 2. 真实HTTP负例和身份/时效

真实Spring Boot随机端口、真实密码验证/JWT登录、权限和实际服务/数据库；没有mock业务API。

- readonly调用generate403，超级管理员正常调用。
- 保留MT01原HTTP负例：0.5杠杆拒绝；上限5、目标10拒绝；preview0.5拒绝；preview10.4成功，数据库不改rowVersion仅把上限降5，create400；恢复上限20后create200，幂等重放不增加单据。
- 新预览后更改input为0.011，create400；仅改变walletEnabled也400。
- 真实登录第二名同为super_admin的管理员，提交第一人预览，create400；不是只测低权限角色。
- 用反射仅将本测试内存预览expires设置为已过期，再以数值等价的input `1E-2`、leverage `1.0400E1` 提交，create400。没有修改系统时钟、业务校验或生产源码。
- 以上拒绝后SQL仍orders=1、audit=1、钱包1000/frozen0，没有副作用。

### 3. 旧持久幂等兼容

独立夹具读取本测试合法10.4订单审计的request和operator_id，按修复前算法重建排序JSON的SHA-256。确认旧摘要不等于新摘要，再仅在隔离审计行写入旧摘要，清空预览缓存。

旧摘要：`ea4f087542de17ced39db948cc6741f202ef00f9eba748b462831448ca9a7d33`。

- 使用原幂等键、input `1.000E-2`、leverage `1.0400E1`、targetNet `9.9700E-2`，真实HTTP重试200，返回同一orderId=1；无需仍存活的预览缓存。
- 更换操作者400；改变真实input为0.011则400。
- 将隔离审计evidence临时改为 `{}`，等价请求也400，证据缺失不放行；随后恢复原evidence。
- SQL仍orders=1、audit=1、钱包不变。没有靠写入任意假摘要后直接放行证明兼容。

限制：此项模拟旧算法落库格式和缓存丢失，没有真的先启动旧版本进程创建订单再跨版本重启；浏览器与本轮HTTP钱包开关均关闭，未额外测入账一次。

### 4. 浏览器原失败路径

Browser plugin/专属browser skill未提供，使用已安装Playwright和独立headless Chrome，无安装。独立HTML壳挂载真实ManualContractOrder组件；业务API全部真实本机请求。不是完整后台登录页导航。

填写五目标：杠杆10、BTC数量0.01、仓位0.01003%、净收益0.0997、平仓价110，方向做多，钱包/历史关闭。

- 登录200、context200、generate200，原输入保留、五项偏差0%，BTC单位及0.0003手续费正确。
- 立即点击确认，不编辑表单，**中间没有 `/preview` 请求**，create200、orderId=2；`R/browser-api.json`和`R/browser-wire.jsonl`可核对完整顺序。
- JSON往返后的input `"0.01"`和leverage `"10"`被正确接受，未恢复尾零、未绕开摘要。
- 服务端随后实际SQL断言orders=2、audit=2、钱包不变及两单手动标签/单位均通过。
- pageerror为空、Vite错误覆盖层不存在。console有Chromium对本机HMR WebSocket的网络安全阻断提示，Vite进程有旧API弃用警告；没有关闭安全检查，不宣称零控制台警告。
- 已查看 `R/browser-created.png`。截图处在弹窗关闭动画附近，不能单凭画面判创建成功；结论依赖真实200响应和SQL。

## 命令及证据

Java21、Maven3.9.9；MAVEN_OPTS=-Xmx256m，测试JVM=-Xmx768m。

1. `mvn.cmd -o -B -f R/source/exchange-backend/pom.xml -Dtest=ManualHashRecheckTest,ManualOrderHashTest -DargLine=-Xmx768m test`：8/8，退出0；`R/hash.log`、`R/hash-exit.txt`。
2. `R/run-http.ps1 -Root R`：内部离线Maven选择ManualRecheckHttpTest，1/1，Maven退出0；`R/http.log`、`R/http-exit.txt`。同一集成用例内包含上述多个独立断言，不按HTTP请求数虚增测试数。
3. 设置QA_HTTP_PORT=63348后，从R/source/exchange-admin执行 `node.exe node_modules/vite/bin/vite.js --config qa.vite.config.ts`；工具session24011，测试后主动Ctrl-C终止，非业务测试失败。
4. 设置QA_EVIDENCE=R，执行 `node.exe R/source/exchange-admin/tests/recheck-real.browser.cjs`：退出0；`R/browser.log`、`R/browser-exit.txt`、`R/browser-done.txt`。
5. HTTP关键SQL证据：`R/http.log`中的NEGATIVE SQL PASS、LEGACY SQL PASS、FINAL SQL；浏览器wire文件不记录登录密钥/令牌。

新增验收代码：`R/source/exchange-backend/src/test/java/com/gtcfesk/exchange/security/ManualRecheckHttpTest.java`。修复前夹具基线另存 `R/http-fixture-before.java`、`R/browser-fixture-before.cjs`。原hash反例保持原样。

## 未验证范围及交付判断

未重跑全仓/700项、完整管理后台导航、多浏览器/移动端、生产配置、外部真实行情、跨进程旧版本升级、JWT自然过期/签名攻击、集群预览缓存。本轮时效为服务端预览过期测试，不冒充JWT过期测试。

在本轮指定范围内，原MT02失败已真实转绿，参数/身份/预览时效及旧摘要兼容未因修复失守，可关闭MT02。本报告不等于生产部署批准。