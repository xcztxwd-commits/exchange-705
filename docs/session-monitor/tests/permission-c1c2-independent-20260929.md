# 权限 C1/C2 独立动态验收

日期：2026-09-29，Asia/Singapore。

## 结论

**本轮限定范围通过。** 独立源码快照内执行 19 个测试方法，0 失败、0 错误、0 跳过，Maven 退出码 **0**。

- 5 个本轮独立编写的验收方法，仅保存于独立快照。
- 7 个修复交付方法，重新执行，而非只引用负责人日志。
- 7 个继承的基础权限方法，仅执行一次。

C1 原反例（字符串/小数/缺字段误清空）与 C2 原反例（旧停用授权阻塞撤除）均有动态通过证据。委派上限、停用权限不得重新授予、代理两张授权表的失败回滚亦通过。**不代表旧运行后台、真实 MySQL 迁移或并发场景已验收。**

## 隔离与操作边界

先读 [修复说明](C:/workspace/fx/705/docs/permission-grant-repair-20260929.md)、交付摘要/指纹，以及 [原归因表](C:/workspace/fx/705/docs/session-monitor/tests/permission-version-triage-20260929.md)。

独立快照：`C:/workspace/fx/new/permission-c1c2-independent-20260929`。

使用文件复制建立新目录，只复制后端 `pom.xml` 和 `src`，没有复制共享 target，也没有复用负责人的构建产物。共享业务源码和公共测试只读。本轮额外测试写入新目录，不修改公共测试。

继承的测试配置使用：

```text
spring.datasource.url=jdbc:h2:mem:admin_permission;MODE=MySQL;DB_CLOSE_DELAY=-1
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.hibernate.ddl-auto=create-drop
```

真实 Spring MVC、JWT 过滤器、权限入口、JPA repository 和 H2 数据持久化；市场、Redis、邮件等沿用基础测试替身。MockMvc 不向旧后台发送 HTTP 请求。数据为合成角色、代理和授权，内存库随测试进程结束销毁。

未访问旧运行后台和业务库，未创建运行环境账号，未部署、未启动浏览器、未执行 Git 操作。未接续任何客服验收。

## 实际命令与退出码

快照创建采用以下实际操作（目标存在时中止，不覆盖旧副本）：

```powershell
$dest='C:/workspace/fx/new/permission-c1c2-independent-20260929'
if(Test-Path $dest){throw 'Snapshot already exists'}
New-Item -ItemType Directory -Path $dest
Copy-Item -LiteralPath 'C:/workspace/fx/705/exchange-backend/pom.xml' -Destination $dest
Copy-Item -LiteralPath 'C:/workspace/fx/705/exchange-backend/src' -Destination $dest -Recurse
```

创建及指纹核对命令退出码 0；6 个交付源码/测试指纹全部吻合，之后才进行验收。

唯一 Maven 测试命令：

```powershell
mvn -q -f C:/workspace/fx/new/permission-c1c2-independent-20260929/pom.xml '-Dtest=PermissionGrantRepairTest,PermissionC1C2IndependentTest#independent*' test
```

命令实际以 `*> C:/workspace/fx/705/reports/permission-c1c2-independent-20260929/test.log` 保存输出，并将 `$LASTEXITCODE` 写入 `exit-code.txt`。退出码 **0**；没有失败后修改业务源码重试。

| JUnit 类 | 实际执行数 | 失败 | 错误 | 跳过 | XML 用时 |
| --- | ---: | ---: | ---: | ---: | ---: |
| PermissionC1C2IndependentTest | 5 | 0 | 0 | 0 | 8.974 秒 |
| PermissionGrantRepairTest | 14 | 0 | 0 | 0 | 1.793 秒 |
| 合计 | 19 | 0 | 0 | 0 | 10.767 秒（不含 Maven 编译等耗时） |

`#independent*` 只选择独立类中的 5 个新方法，避免其继承方法重复执行。上述 19 个是测试方法数，不是所有循环断言数量，也不能与原交付的 81 次执行直接加总为独立覆盖数。

## 逐项结果

| 验收项 | 本轮动态动作与数据断言 | 结果 |
| --- | --- | --- |
| 原 C1 字符串 ID | 两个入口提交 `menuIds:["not-a-menu"]`，均 HTTP 400；角色授权、代理菜单和动作与之前的精确集合相同 | 通过 |
| 小数与指数 | 两入口验证 N.5、N.0、1e0；交付用例还涵盖其他畸形类型。均拒绝而非截断，旧授权不变 | 通过 |
| 整数边界 | 超出 long 上限拒绝；long 最大值作为合法整数但不存在目录中的 ID 被语义校验拒绝，原授权不变 | 通过 |
| 混合有效/无效 | `[有效ID,"bad"]` 拒绝，未部分写入 | 通过 |
| 缺字段/null 与显式空分离 | `{}`、`menuIds:null`、代理仅传 actions 均 400 且保留授权；`menuIds:[]` 成功清空 | 通过 |
| 去重 | 重复合法菜单 ID 保存后只有一个绑定 | 通过 |
| 省略动作语义 | 代理保留合法 menuIds、不传 actions，菜单保留，旧动作清空；显式空菜单与空动作最终两表为空 | 通过 |
| 动作结构校验 | 重跑交付方法：actions 为 null/数组、非法键、非法动作值及混合未知动作均失败，两个表原集合保留 | 通过 |
| 原 C2 停用菜单撤除 | 独立方法将已绑定 durations 父菜单设 disabled，以 super 提交空集合，成功撤除原菜单及按钮绑定 | 通过 |
| 停用按钮撤除 | 交付方法将 create 按钮停用，替换为只含父菜单成功，旧按钮移除 | 通过 |
| 禁止重新授予 | 清理后 super 不能重新授予停用父菜单；委派管理员也不能授予停用项；交付方法确认代理停用按钮不可重新分配 | 通过 |
| 角色管理操作 | 重跑交付方法：有停用旧授权的角色可改名；无账号绑定后可删除；基础测试继续覆盖敏感角色边界 | 通过 |
| 委派管理范围 | 独立方法：委派者不能清空比自身更强的有效角色；旧权限停用后可清理，但不能趁机增加 settings 权限；失败保留旧绑定 | 通过 |
| 委派代理动作 | 交付方法：将合法 modify_remark 与未授权 modify_balance 混合提交，403，菜单/动作均保持原集合 | 通过 |
| 两表写入后回滚 | 独立 Spy 先调用真实动作写入，再 flush 菜单/动作，确认事务内已变为替换菜单及 create 动作；随后注入异常。HTTP 400，事务外重新查询两表均恢复精确旧集合，且确认故障注入确实执行 | 通过 |
| 原有权限基线 | 同轮执行继承的 7 个基础方法，无失败 | 通过 |
| 旧 MySQL 数据迁移、部署后接口/浏览器 | 本轮禁止操作，不以 H2 结果代替 | 未验证 |
| 并发授权/停用竞态、真实数据库特有约束 | 本轮为串行合成 H2，不覆盖这些差异 | 未验证 |
| 全角色/全模块业务正向流、客服模块 | 超出 C1/C2 范围，未执行 | 未验证 |

### 回滚证据为何不是“校验失败前未写入”

独立方法 `independentFailureAfterRealFlushRestoresMenusAndActions` 使用真实 `AgentActionService.assignActions`，写入并 flush 后读取到新菜单和新动作，再抛出 `IllegalArgumentException`。`AtomicBoolean` 标记由上述读取断言之后设置；HTTP 调用后必须为 true。随后在请求事务外重新读两张表，比较原菜单/原动作精确集合。因此该结果证实的是**实际替换之后的事务回滚**，不是把预校验拒绝误记为回滚成功。

## 源码 SHA-256

以下 6 项均满足：负责人交付指纹 = 本轮共享源读取指纹 = 独立快照指纹。测试结束又核对共享源，6 项均未变化。

| 文件 | SHA-256 |
| --- | --- |
| AdminRoleController.java | `f8acefbc3cf58150d3a94c98ebd079bf440a4ff767fe7281e5b7b5f90ebf03a0` |
| AdminRoleService.java | `136ebb6fa57872ab836c38659d963ee7c23aad87466045c58686c65afe2ed417` |
| AdminUserController.java | `7e9539d9bb2dd13efbd256c361d493d287b8965b319b67af01601820ff868a74` |
| AdminPermissionService.java | `103cf70edf7b791fb9f4ffd35ef2e8902ee532649f1008ec4ff1b518c782779f` |
| PermissionGrantInput.java | `40ed66d61346b2fa5aa69ad0309762951079f19e35c98fa46797b3dfc862f6f2` |
| PermissionGrantRepairTest.java | `6d03da2a31cb718066ada35e0f59e897c69ce5801e00e2c6e9b1233c2db4399e` |

独立新增测试 SHA-256：`e609675f9be5130f143100e890e15ecd232d117ec119462c7c8c48cc1210e444`。

快照 pom.xml SHA-256：`11230d601912aba33a9b6b84b8360b2eb574b430e2d50699ac96d381bd383ece`。

完整快照 src 文件指纹另存，不仅记录修改过的 6 个文件。

## 可核验产物

- [独立测试源码（仅快照）](C:/workspace/fx/new/permission-c1c2-independent-20260929/src/test/java/com/gtcfesk/exchange/security/PermissionC1C2IndependentTest.java)
- [真实命令](C:/workspace/fx/705/reports/permission-c1c2-independent-20260929/command.txt)
- [退出码](C:/workspace/fx/705/reports/permission-c1c2-independent-20260929/exit-code.txt)
- [运行日志](C:/workspace/fx/705/reports/permission-c1c2-independent-20260929/test.log)
- [从本轮 XML 提取的摘要](C:/workspace/fx/705/reports/permission-c1c2-independent-20260929/summary.json)
- [独立 5 方法 JUnit XML](C:/workspace/fx/705/reports/permission-c1c2-independent-20260929/TEST-com.gtcfesk.exchange.security.PermissionC1C2IndependentTest.xml)
- [修复与基础 14 方法 JUnit XML](C:/workspace/fx/705/reports/permission-c1c2-independent-20260929/TEST-com.gtcfesk.exchange.security.PermissionGrantRepairTest.xml)
- [交付/共享/快照六文件比对](C:/workspace/fx/705/reports/permission-c1c2-independent-20260929/source-sha256.json)
- [共享源结束核对](C:/workspace/fx/705/reports/permission-c1c2-independent-20260929/shared-source-postcheck.json)
- [完整快照源码指纹](C:/workspace/fx/705/reports/permission-c1c2-independent-20260929/snapshot-src-sha256.json)
- [独立测试与 pom 指纹](C:/workspace/fx/705/reports/permission-c1c2-independent-20260929/independent-test-sha256.json)

验收意见：C1/C2 可以标为“该指纹版本在独立 H2 动态验收通过”；原版本归因表的两项静态待修结论已有本轮后续证据，但不要据此关闭部署、旧库迁移和其他权限正向流程待验证项。
