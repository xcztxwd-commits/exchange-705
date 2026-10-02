# 未知错误统一英文

本轮修改 PC、手机端公共请求层及后端统一异常兜底，不修改已识别业务错误的现有本地化、不修改正常内容和后台管理界面。

- 后端未处理异常及 SafeErrors 未识别异常统一返回：Unable to confirm the result. Check the relevant history or status before submitting again.
- 双端所有语言的未知错误使用同一英文提示，不展示未识别原始异常文本；HTTP 通用错误保留状态码。
- 无响应读请求显示 A network error occurred. Please try again later.；无响应写请求提示先核对履历，避免将超时视为确定失败。
- 正常响应 message 不盲目替换；success=false 的 message 使用错误兜底。
- 已知业务错误仍按原语言规则处理。未知前缀原因也按未知错误兜底，不产生半日语半中文的异常提示。
- 后端全局异常处理器保留原有日志。前端不打印原始异常，避免泄露数据库信息等内部内容。

测试：30 项请求分支断言通过；结构检查通过，增加三种语言下未知错误与已知日语业务错误断言；后端 Maven compile 通过。未进行生产部署或真实交易测试。

备份：C:/workspace/fx/705/rollback/unknown-errors-english/。

边界：本轮统一的是公共请求层和后端异常兜底，不是将所有已知业务提示、商品说明或审核备注改为英文。没有把任意未知异常逐字翻译，而是使用安全、固定的英文提示。
