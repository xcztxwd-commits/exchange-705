# 多租户项目：当前基线与阶段边界

更新：2026-10-02T23:21:06+08:00。原工作区：`C:\workspace\fx\705`。只用本文和[执行命令](C:/workspace/fx/705/docs/multitenant-next-commands.md)接手；旧阶段编号、过期摘要及失败启动记录仅作历史追溯。

## 当前进度

- 阶段1已验收。用户确认阶段2完成，并授权派发阶段3、4。阶段2最新交接、29项矩阵、索引与输入已只读复核，未重新运行业务测试。
- 阶段2最终版本为`equity-read-collect-v2/mobile-market-mode-v3`：后端221项（191同输入复用、30新跑），前端93项，当前浏览器109组/19次；必测失败、错误、跳过、未执行均0。旧209只是历史数量，不继续作为当前验收总数。
- 当前后端664输入/JAR及最终前端输入匹配；新增手机REAL/DEMO行情模式修正与测试保留。UI回滚与BANK、上传、防重复提交、充值取消/状态功能保留；Figma/原型不重新实施到应用。
- 阶段3、4已有主要代码/工具，但还没有本轮完整阶段验收。只补证据明确的缺口，不从零重写。阶段5最终统一验收尚未执行。

## 统一基线

本次本地快照分支：`codex/tenant-stage34-baseline-20261002-232106`。完整提交、逐文件哈希、工作树路径、端口和只读验收记录见：

`C:\workspace\fx\705\reports\tenant-stage34-baseline-20261002-232106\baseline.json`

快照包含当前已修改和未跟踪的源码、构建配置、锁文件、测试、迁移SQL、工具及本次文档；不使用旧HEAD冒充当前代码。原`main`、原索引与未提交业务改动保留，未reset/clean、推送或启动服务。快照专用`.gitattributes`保持验收文件原字节，避免工作树换行转换导致无业务变化的哈希误报。

本地密钥、原始SQL、上传文件、运行数据库、依赖、target/dist、报告和设计预览不纳入源码快照，原位置保留。原工作区仍有活动Figma会话，因此阶段3、4各用独立工作树；不可在原工作区并发开发。

## 接手入口（只读一次）

1. 阶段2最终交接：`C:\workspace\fx\705\reports\stage2-retest-20261002-193957\handoff-phase2-candidate.md`；按需求定位矩阵/索引，不完整重读历史日志。
2. 阶段2报告：`C:\workspace\fx\705\reports\stage2-retest-20261002-193957\phase2-complete-candidate-report.md`；最终矩阵：`C:\workspace\fx\705\reports\stage2-retest-20261002-193957\phase2-same-version-matrix.json`；索引：`C:\workspace\fx\705\reports\stage2-retest-20261002-193957\phase2-evidence-index.json`。
3. 最终资金、恢复、清理与脱敏证明均由该索引定位。私有备份仍在`C:\Users\徐乾妖\AppData\Local\Temp\codex-mt705-stage2-retest\01a0fc66-736d-7b62-89ac-b5c0bb9b19a0`，不要公开原始账本、身份、SQL或凭证。

阶段2资源保持停止；新阶段只从已证明的备份建立自己的隔离副本，不启动/覆盖原REAL、DEMO或恢复实例，不复用旧容器标签当归属证明。

## 并行分工与收口

- 阶段3负责应用运营、客服、在线、留存、缓存/推送/模拟隔离的源码与对应测试；必要应用实体、仓储和应用DDL由阶段3统一负责。
- 阶段4负责`scripts/multitenant`迁移/恢复工具、表清单、工具测试及独立数据演练；不改应用源码、前端或阶段3的DDL。发现应用边界/DDL缺口，写交接交由调度归给阶段3，禁止两边修同一文件。
- 两边数据库、Redis、文件/构建目录、端口和证据目录独立，只读共享已冻结证据。都完成后由调度审核增量并合并；阶段4只对合并后实体/DDL/工具实际变化补复核，才做最终验收。不自动互相合并、执行阶段5或新建会话。

原规模为5/10租户、每租户约200用户/10人在线、平台约100人同时在线。容量测试留阶段5，不提前扩展规模。Java 1.8、schema epoch及实际包防护分别核对；`business_activation_ready=0`和`release_approved=false`不能强置通过。三个原标记`ORM_DIRTY_WRITE_PREDICATE`、`PRODUCTION_LEGACY_ORPHANS`、`RUNTIME_ACCEPTANCE_PENDING`按实际缺口处理，不仅修改批准字段。

原自动任务保持PAUSED。本地阶段通过不等于生产批准；真实外部支付、自动支付回调/已付款退款适配器、生产部署及push不在本轮范围。
