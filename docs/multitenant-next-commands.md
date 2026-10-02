# 阶段5收口后的接手边界

当前工作树：`C:\Users\徐乾妖\.codex\worktrees\tenant-stage34-integration-20261003\705`。阶段5本地验收完成；生产发布未批准。**当前应停止，不自动进入下一阶段。**

## 只读接手

1. 先核对分支 `codex/tenant-stage34-integration-20261003`、HEAD及工作区归属；本次最终提交见 `C:\Users\徐乾妖\.codex\worktrees\tenant-stage34-integration-20261003\705\reports\stage5-closeout\evidence.json` 的 `finalCommit`。
2. 只读 `C:\Users\徐乾妖\.codex\worktrees\tenant-stage34-integration-20261003\705\reports\stage5-closeout\handoff.md`、`C:\Users\徐乾妖\.codex\worktrees\tenant-stage34-integration-20261003\705\reports\stage5-closeout\evidence.json`。按具体缺口定位原报告；不要重新完整阅读历史或无理由重跑全部阶段。
3. 本地 ORM 结论详见 `C:\Users\徐乾妖\.codex\worktrees\tenant-stage34-integration-20261003\705\reports\stage5-closeout\orm-coverage.json`；需求逐项复核详见 `C:\Users\徐乾妖\.codex\worktrees\tenant-stage34-integration-20261003\705\reports\stage5-closeout\requirements-review.json`。复用与补测分别列明，结构检查未替代缺失运行时证明。

## 仅在后续明确指令下开展

- 对真正变化的代码、schema或运行契约，只补受影响检查；新增改动先确认归属，不覆盖、不自动合并。
- 生产相关事项必须另行明确范围并取得真实材料与审批；本轮没有批准这些操作。
- 保持 `release_approved=false`、`business_activation_ready=0`、`PRODUCTION_LEGACY_ORPHANS` 及现有生产门禁原值。不要仅为门禁通过改变 `resolved`。
- 不启动旧阶段服务，不覆盖源库/原工作区/其他会话；保留卷、备份、失败记录与原JUnit跳过/替代证明。

复现增量探针：`C:\Users\徐乾妖\.codex\worktrees\tenant-stage34-integration-20261003\705\scripts\stage5\OrmCloseoutProbe.java`。必须使用新建且经完整ID/标签/UUID核验的本地MySQL克隆、相同验收JAR及表清单；连接文件仅置受限目录。不能拿生产或其他会话的连接文件运行。
