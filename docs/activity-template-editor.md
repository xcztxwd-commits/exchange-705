# 活动模板编辑器

## 需求与原有问题
原实现把活动文案保存在 translations，页面结构、背景、礼盒动画及部分标题、按钮、账户说明则写在 ActivityCenter.vue 和 utils/activity.ts 中。客户语言选择与活动文案回退分别执行，因此可能出现日文系统文字与英文正文并存。

新设计把封面 gift、详情 detail、成功 success 及自定义页面作为完整模板保存。每个语言版本保存独立页面树，缺失语言整页回退 defaultLocale。所有业务展示文字均来自树中的可编辑组件，不再插入旧版固定文案。关闭安全控件、接口错误及后端领取校验不属于营销模板。

## 开源选型
- GrapesJS 0.23.6，BSD-3-Clause：采用开源核心的组件拖拽、富文本入口、样式管理、设备预览与撤销重做。未使用商业 Studio SDK。
- Puck：MIT，但面向 React，与现有 Vue 技术栈不一致，未引入。
- 文档：https://grapesjs.com/docs/modules/Components.html 、https://grapesjs.com/docs/modules/Pages.html 、https://grapesjs.com/docs/modules/Assets.html 、https://grapesjs.com/docs/modules/Storage.html
- 项目：https://github.com/GrapesJS/grapesjs 、https://github.com/puckeditor/puck

## 使用
活动或模板编辑窗口选择语言，打开“可视化模板编辑器”。拖入容器、文字、图片/GIF、按钮或金额组件。双击文字或在组件文字栏编辑；右侧画笔调整尺寸、Flex 图文比例、字体、颜色、背景、边框、圆角、间距和定位。上传背景时先选择容器。GIF 保留动画，单文件最大 5MB。顶部可设置弹窗宽度、圆角、遮罩色与模糊。

首屏保留为入口，详情与成功页可删除，可编辑全部组件；可新增并命名其他页面。按钮可跳转设计页、领取、关闭或跳转站内路径。成功页只有真实领取成功后显示，不允许通过普通跳转伪造到账。应用设计仅回填编辑窗口，仍需保存活动/模板才持久化。已有活动不自动转换，避免覆盖线上设计。

## 数据与安全
layout_json 保存版本化结构树，不保存原始可执行 HTML 或 JavaScript。Vue 使用文本节点和固定组件渲染；不使用 v-html。模板脚本、任意事件属性、CSS URL、不受支持的图片路径、外部页面跳转不受支持。本次非租户版本的图片与背景使用原有图片接口读取，blob 仅作会话预览，不写入模板。后端校验默认语言、页面引用、组件种类、样式、层级和总量（每种语言最多12页/500组件，JSON最多2MB）。

显示金额支持 {amount}，登录天数支持 {days}。金额、预算、领取次数、资格和到账结果仍由后端计算。后台不能通过样式改写资金状态。自定义动态效果使用 GIF 或内置的固定动画标识；任意动画脚本、视频/Lottie、HTML源码导入不在本次范围。行内粗体、斜体、下划线和样式保存为安全文本片段；不保留任意HTML。

## 发布与回滚
先备份数据库，依次执行 V2026092903__activity_delivery_settings.sql 和 V2026092904__activity_template_design.sql，再发布后端与三个前端。新字段为空时保持旧版展示。源码备份位于 rollback/activity-designer-20260929。回滚源码时保留新列及设计数据，不做破坏性删列。

## 素材、动作与重复发送（2026-09-29）

- 左侧素材库可保存图片/GIF 或安全组件树，按名称搜索并分页；移除只做软删除。复用时复制组件，不引用其他活动的跳转。动画与布局仅接受预设样式，不执行任意 CSS/JS。
- 按钮可配置最多 8 步固定动作：标记已读、领取体验金、上一页/下一页/指定页、站内跳转、关闭。副作用按顺序等待；失败即停止，领取由后端幂等校验。纯预览使用模拟回调，不发真实请求。
- 活动可显式开启再次发送。同一活动对同一收件人再次投递只重置未读状态，保留领取与资金记录；默认为关闭。
- 本非租户版本的素材库为单库共享，使用既有公告管理权限；不引入租户标识、控制台或私有图片路径。
- 发布前备份数据库，顺序执行 `V2026092905__activity_material_library.sql`、`V2026092906__activity_repeat_send.sql`，然后同步发布后端及三端资源。两份迁移为加法式，回退源码时保留新增表/列与已投递数据。
- 回归：`node scripts/test-activity-actions.cjs`、`node scripts/test-activity-popup.cjs`、`node exchange-frontend/tests/homeAllCategory.test.mjs`；浏览器环境可运行 `node scripts/test-activity-editor.cjs`。
