# 第1步交接

日期：2026-09-18。结论：第1步本地核验通过，等待调度器接收。未执行第2步或第3步。

## 路径与提交

- 原工程：`C:\workspace\fx\705`，main，起始与最终源码HEAD均为 `61efea03af83b00f7530e8f14dd0bc4fb39d07ff`，工作区干净。
- 实际集成：`C:\Users\徐乾妖\.codex\worktrees\c90e\705`，从同一HEAD的detached工作树新建 `codex/restore-2024-integration`。
- origin（两个工作树共享）：`https://github.com/xcztxwd-commits/exchange-705.git`。
- 交付提交标题：`docs: lock restore-2024 scope and integration baseline`。提交完成后的精确哈希在任务最终报告；也可执行 `git log -1 --format=%H -- dispatch/step-01-handoff.md` 获取包含本交接的提交。文档不自引用尚未生成的提交哈希。
- 未另建 `C:\workspace\fx\restore-2024\integration`，未切换或重置原目录，没有推送。

## 材料检查与第2步准备

以下材料均在原工程存在，均无Git跟踪记录，均被原根.gitignore忽略；本客户端工作树均未复制。仅检查元数据与忽略状态，未打印SQL、env或凭据内容。

| 材料 | 实测 | 第2步处理 |
|---|---|---|
| 1090.sql | 152872字节，忽略规则第8行 `*.sql` | 原件只读；计算哈希、检查结构与数据、审阅副作用，制作脱敏或合成种子；不得原样提交 |
| .env | 244字节，第1行 `.env` | 不整份复用；提取配置键需求，独立生成测试密钥、账户及端口 |
| uploads/ | 目录存在，第9行 | 盘点必要资源并审查敏感数据，建立独立上传目录，禁止挂载原目录读写 |
| reports/ | 目录存在，第11行 | 历史报告只作参考，当前提交重新运行并保存新报告 |
| rollback/ | 目录存在，第10行 | 只读检查哈希、归档条目及年代，不能认定是2024源码 |

rollback内存在 backend-before.zip（259007字节）、frontend-before.zip（1011679字节）、compose.before-isolation.yaml（248字节）；本步没有解包或恢复。

缺失材料本身不阻塞本步骤，但新工作树不能直接视为完整运行环境。第2步仍需独立DB/Redis卷、合成种子、uploads、端口、网络、JWT、邮件/验证码/回调与调度器隔离；邮件须检查DB system_config配置链。Docker引擎状态、依赖下载、四端构建、行情测试和全栈健康均NOT_RUN。宿主与容器版本差异沿用既有记录，不冒称本轮复测。

## 规则与文件

检查了两个目录祖先路径及仓库AGENTS文件：仅集成目录上级 `C:\Users\徐乾妖\.codex\AGENTS.md` 存在，要求caveman full与ponytail full。两技能已读。原仓库没有AGENTS.md；新增根规则来自AGENTS-merge并补充最新调度覆盖项。旧文档中的子代理、另建integration、仅方案编制和执行者更新共享STATE要求，由当前任务授权覆盖。

原.gitignore内容保留，追加私有证据、credentials.clixml、HAR和会话状态规则；exchange-frontend与exchange-pc的子级.gitignore未改。Git info/exclude只有默认注释，core.excludesfile未设置。根.dockerignore现有内容未改；第2步如构建上下文可能纳入私有目录，须在自身环境方案中排除或采用窄构建上下文，不能将私有材料复制到镜像。

明确交付文件仅5个：`.gitignore`、`AGENTS.md`、`docs/restore-2024/STATE.md`、`dispatch/tasks.md`、`dispatch/step-01-handoff.md`。没有复制new目录、凭据、SQL、env或原始证据。

## 命令记录

本次必要验证及实际退出码：

- Get-Content读取指定方案及两技能、祖先规则：PowerShell调用0。首次合并输出截断，后续分批补读了缺失内容。
- 两目录 `git rev-parse HEAD`、`status --short --branch`、`remote -v`、`worktree list`、`ls-files '*AGENTS.md' '*gitignore'`：调用0；状态与HEAD见上文。
- `git show-ref --verify --quiet refs/heads/codex/restore-2024-integration`：1，表示不存在，随后才创建。
- `git switch -c codex/restore-2024-integration`：0。
- 原目录五项 `git check-ignore -v`：各0；`git ls-files -- 1090.sql .env uploads reports rollback`：0、无输出。
- 集成目录SQL/env忽略检查：各0；不存在目录用无尾斜杠检查时各1，随后以 uploads/、reports/、rollback/ 检查：0，确认目录规则仍有效。五项Test-Path均不存在。
- `git config --get core.excludesfile`：1（未配置）。info/exclude读取成功，仅注释。
- 原目录 `git status --porcelain=v1 --untracked-files=all`：0、无输出。
- 加密凭据文件Test-Path：True；未解密。材料Get-Item与文档写入调用：0。

最终 `git diff --check`、`git diff --cached --check`、五项私有忽略探针与显式 `git add` 均退出0；暂存清单正好为上述5个文件。提交及提交后工作区检查结果随最终报告交付。功能/业务/视觉测试均NOT_RUN，此步骤只有文档与规则变更，不以构建通过代替阶段验收。

## 调度接手

先验收本提交与原工作区保护，再按 `dispatch/tasks.md` 派发两项独立任务。两张卡有输入、输出、写集合、唯一写入者与验收门禁；实际执行者ID和专属绝对工作树必须由调度器派发时绑定，尚未创建不虚构记录。

外部总账 `C:\workspace\fx\new\DISPATCHER-STATE.md` 全程只读。总调度器为 `01a0b270-cdb9-76c0-98eb-5a0c30e293b1`。没有本步阻塞；后续环境与证据缺口见STATE和任务卡。停止于第1步。
