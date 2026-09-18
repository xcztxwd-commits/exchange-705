# 2024 还原工程状态

核验日期：2026-09-18（Asia/Singapore）。阶段：STEP1_VERIFIED，等待调度器验收；不是 READY_FOR_S3，也不是工程 COMPLETE。

## 范围与基线

目标是恢复2024 PC与管理端可维护源码、界面、交互及可观察业务行为。先调查可恢复源文件，再复用2026 Vue/Vite/TypeScript与Java工程。移动端仅验证共享接口回归，没有旧版证据不重建其界面。

- 原工程：`C:\workspace\fx\705`，分支 `main`。
- 实际集成工作树：`C:\Users\徐乾妖\.codex\worktrees\c90e\705`。
- 集成分支：`codex/restore-2024-integration`，本步骤从 detached HEAD 新建，无既有同名分支。
- 两目录起始 HEAD：`61efea03af83b00f7530e8f14dd0bc4fb39d07ff`。
- 两目录起始工作区均干净（不包括被忽略的本地材料）。原目录未修改；分支创建只新增共享 Git 元数据。
- origin：`https://github.com/xcztxwd-commits/exchange-705.git`，fetch/push 相同。本步未查询远端，不声称远端当前 HEAD 已核验。
- 本步骤交付由提交 `docs: lock restore-2024 scope and integration baseline` 标识；精确哈希见任务最终报告或 `git log -1 --format=%H -- dispatch/step-01-handoff.md`。

## 输入与所有权

只读方案目录：`C:\workspace\fx\new`。已读 DISPATCHER-STATE.md、PROJECT-MEMORY.md、03-命令调度执行方案.md、04-逐步执行指令.md、AGENTS-merge.md。

加密凭据仅引用：`C:\workspace\fx\new\.restore-private\legacy-2024.credentials.clixml`；已核实文件存在，未读取或解密。原始敏感证据统一放采集任务专属工作树的 `.restore-private/step-03/`，公共输出必须脱敏。

实际生效上级规则：`C:\Users\徐乾妖\.codex\AGENTS.md`。原工程及本工作树起始无根或嵌套 AGENTS.md；已检查两目录祖先路径。新增根 AGENTS.md 合并还原约束，保留默认技能要求。

调度器 `01a0b270-cdb9-76c0-98eb-5a0c30e293b1` 独占外部 DISPATCHER-STATE.md。第1步结束后共享 STATE、任务清单、汇总 inventory/feature-diff/api-map 仅由调度器或其唯一指定集成维护者串行写入。步骤2和3不得修改这些文件，也不得修改集成工作树。两任务各用客户端专属工作树；派发时调度器记录实际绝对路径和验收后的基线提交，未绑定则不得执行。

最多三个独立执行任务，执行者不派生任务。第3步是唯一浏览器控制者，PC与后台串行。第2步独占自身环境配置；最多两个重构建进程、一套集成数据库；合并与共享文件修改串行。

## 本步骤验收

已完成目录、基线、忽略材料核对、规则合并、总账与两张任务卡。详细命令结果及材料清单见 `dispatch/step-01-handoff.md`。未复制私有数据、未导入SQL、未启动全栈、未执行构建/业务测试、未采集浏览器、未实现UI。

前轮两端登录有效仅为既有记录，本步未复核；第3步按会话现状续接。当前工具未设置或修改客户端权限；本步本地读写已成功，其余能力由对应任务实测。

下一轮：调度器验收本步骤后派发第2步和第3步。任务定义见 `dispatch/tasks.md`，目前尚未派发。步骤2的运行环境和步骤3证据未完成；步骤4合同及 READY_FOR_S3 门禁未通过。
