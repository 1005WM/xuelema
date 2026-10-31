# MCP 原生读取流程（0.2.0 接入，0.2.2 课程状态过滤）

日期：2026-10-28（Asia/Shanghai）。0.2.2 先核对课程状态，过滤已结课课程；考试汇总关联限制见 [课程范围修复](course-scope-fixes.md)。0.2.1 的逐课程发布、限时补充和签名复用见 [加载优化](detail-speed-fixes.md)。

## 接入方式

来源：[DanMo661/xuexitong-cli](https://github.com/DanMo661/xuexitong-cli)。检查的上游 HEAD 为 `74b843ad4a3439a6ee5ba33f6a3288d9be6ed990`；参考 `xuexitong_mcp/client.py` 文件 SHA-256 为 `4a11761d8faebe93e65c9b7f6bbc78ae020b7d36286be452d9650bd6924b78da`。上游 MIT 许可及版权声明已收入 App“关于与开源许可”。

上游 MCP Server 是 Python 本地 stdio 服务，面向支持启动子进程的桌面客户端。该仓库没有供 Android 直接连接的公共 HTTPS MCP 地址。此次采用 **Kotlin 原生移植其数据读取层**，没有在手机中运行 Python，也没有声称调用过远程 MCP `tools/call`。用户不需要配置电脑、服务器或输入学习通明文密码。

## 替换后的实际链路

1. 仍由官方 WebView 登录；CookieManager 保留原登录态，OkHttp 通过已有 CookieJar 共享 Cookie。
2. 按 `fetch_courses` 流程读取官方 `/base` 的学校 `fid`，请求 `courselistdata`，取得课程/班级标识和卡片状态。先跳过结课、未开放和状态未知的课程；不照搬上游固定默认学校编号，缺少学校标识时报错保留缓存。
3. 对已确认未结课课程，按 `fetch_homework` 流程加载 `stucoursemiddle`，从隐藏字段读取 **workEnc**、openc、页面 enc。`workEnc` 作为作业列表 `enc`；页面 enc 作为 `stuenc`，不能互换。参数交给 HttpUrl 编码。
4. 请求 `/mooc2/work/list`，读取 `goTask(this)` 行的 `data` 地址、`p.overHidden2` 标题和 `p.status` 状态。整个地址被编码时只整体解码一次，已经正常的签名查询参数不额外解码。不会执行 onclick。
5. 按上游 `_DONE_MARKS` 分类：**已完成、已提交、待批阅、已批阅、已批改** 视为已提交；非空且不含完成标记的状态归入待处理；退回优先。空白状态仍为 UNKNOWN，避免漏读状态后无依据断言已做或未做。标题和考试安排状态不参与作业完成判断。
6. 详情地址来自该作业行，按 `get_homework_detail` 流程只读补充截止时间、满分及得分。仅解析带明确年份的截止时间；上游推断 MM-DD 年份的方法未照搬，历史课程需要避免误判逾期。
7. 每读完一门课程就将已读作业写入部分 Room 快照，读取结束再确认完整性；原来的 `BuildHomeContentUseCase → OverviewSummary` 与首页列表继续由同一个 Flow 更新。完整读取时替换旧作业，部分读取时保留其他缓存并标注覆盖不全。
8. 取得课程状态后，按 `fetch_exams / parse_exam_rows` 流程读取官方账号汇总 `examlist` 的 `tr.dataTr` 表格，保留唯一关联到未结课课程的考试。所属课程不明时显示提示并引导到官方页面。分别保留考试名称、时间、时长、安排状态、答卷状态、得分、方式；考试“已结束”不会自动归类为“已完成”或课程“已结课”。没有未结课课程时不请求该汇总入口。

首页刷新和官方页面里的“同步作业与考试”都走上述原生路径。主界面不再创建隐藏同步 WebView，浏览器不默认注册旧的作业 DOM 桥。旧 DOM 适配器代码和回归测试留作历史兼容资料，不在主同步路径运行。

## 文件

- `data/mcp/McpReadTransport.kt`：只读 GET、官方 HTTPS 来源、手动逐跳校验重定向、Cookie 共享、单并发和限流、重试、大小限制、请求取消。
- `data/mcp/McpChaoxingParser.kt`：学校 / 课程解析、动态签名 URL、逐行状态、详情、考试字段映射。
- `data/mcp/McpChaoxingReader.kt`：课程和作业分页编排，App 内后台最多 4 项限时详情补充；解析在 Default 调度器上执行。
- `data/sync/HomeworkSyncCoordinator.kt`：账号代次、阶段超时、两个数据源分别保留缓存、发布结果。
- `data/mcp/CourseScanScope.kt`：课程状态范围、课程/班级与考试的唯一关联判定。
- `data/local/ExamStorage.kt`：考试课程关联、账号隔离、Room 1 → 2 → 3 无损迁移。
- `ui/exams/ExamsScreen.kt`：考试入口和列表。考试官方页面不显示 AI 作答按钮，也不自动打开考试或交卷。

## 完整性与限制

- 上游本身使用官方站点内部网页入口，并非平台公开稳定 API；动态签名、HTML 和风控仍可能变化，不能保证任意学校始终可用。
- 缺少 workEnc 视为该课程读取失败，不能当成无作业。最多显示前三项课程失败原因，不输出 Cookie、签名地址或登录票据。
- 明确空态或进度数量 / 末页标记才可确认作业列表完整。未识别的 HTML、异常分页、重复 ID、状态冲突不会发布可信空快照。
- 部分成功合并旧缓存；只要还有课程未覆盖就不宣称“全部完成”。状态为空的作业仍保留并计入状态待识别。
- 官方请求全局单并发，开始时间至少间隔 1 秒；单次网络调用 25 秒；重定向最多 9 跳；429/502/503/504 最多重试两次。
- 作业阶段最多 160 次逻辑请求、4 分钟；前台同步不等待可选详情；App 内后台补充最多 4 项、单项 2.5 秒、总共 6 秒；考试阶段最多 20 页、20 秒。超过边界会保留原缓存并提示，详情缺失不会改变作业提交状态。
- 只访问官方 HTTPS 站点，外部认证跳转交回官方浏览器由用户完成；不会把 Cookie 发给 GitHub、上游作者、AI 服务或中转。
- 离开 App 取消读取；退出登录 / 清理缓存会清理考试缓存；数据库升级不删除现有作业。

## 使用

1. 覆盖安装 `dist/HomeworkAssistant-0.2.3-debug.apk`，无需卸载旧版。
2. 保持官方账号登录，回首页点击刷新；无需先进入某门课的作业页面。
3. 未完成 / 已完成作业与顶部总览同步更新。首页右上角“考试”进入考试列表。
4. 读取结果以浮层显示 3 秒；如遇失败，可在官方网页完成验证后重试。不要发送 Cookie、密码、Key 或带参数的登录网址。

构建与测试结果见 [verification.md](verification.md)。没有连接真实手机、未使用真实账号；测试数据是按上游结构制作的合成页面，不冒充真实账号验证。
