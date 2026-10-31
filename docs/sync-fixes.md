# 0.1.2 同步问题修复

用户反馈：已经登录、官方页面能看到作业列表；首页长时间“正在刷新”后又回到要求刷新，没有错误。

## 本次修复

1. **超时被吞掉。** 原 `withTimeout` 超时抛出 `TimeoutCancellationException`，继承 `CancellationException`，被同步与 ViewModel 的取消分支直接重新抛出，因此最后只关闭了加载动画，没有错误。
   - 加载、解析、JS 执行使用 `pageOperation` 包裹各自期限，把自身超时转换为 `IOException`；父任务主动取消仍保持取消。
   - 根页读取失败也显示检查页数、发现课程数量和失败阶段。
2. **文档视口被缩成 1dp。** 上版登录隔离修改把同步文档也缩小，可能影响响应式布局和懒加载。
   - 新增 `SyncWebViewHost`：容器保持微小、透明且不可交互，内部文档按实际窗口大小测量布局，不覆盖登录浏览器。
   - 当前进程中的同步 UA 跟随成功登录或手动读取所用浏览器。DOM 结果连续稳定后才采用。
3. **官方旧链接丢失。** 旧解析脚本直接丢弃 HTTP 地址，课程和 iframe 入口可能没有进入队列。
   - 观察到的官方 HTTP 字面链接升级 HTTPS；读取 href/data-url/data-href/onclick 字面地址、同源 iframe 和更多作业行字段。
   - 不猜测私有 API，不执行任意 onclick，不把作业详情链接误当课程入口。
4. **部分同步无法更新已有缓存。** 旧逻辑在有缓存且覆盖不完整时一律放弃新数据。
   - 可信部分结果按稳定 ID 在 Room 事务中合并，保留其他课程和本页缺失的字段。覆盖标记为 PARTIAL，不能显示“全部完成”。
   - 完整快照仍可原子替换；账号隔离保持不变。
5. **返回首页反复抓取。** 自动抓取改为每次应用会话每个账号一次；主动刷新或重新登录仍可再次触发。

## 手机上的使用步骤

1. 安装 `dist/HomeworkAssistant-0.1.2-debug.apk` 覆盖旧版。versionCode 3，签名与前版相同。
2. 登录后先等一次自动同步。顶部会显示“检查第 N 页 / 已发现课程、作业数”；失败后给出原因。
3. 若自动入口仍无法识别，点击右上角官方页面入口，在官方网页中进入**某门课程的作业列表**。
4. 点击网页上方的 **同步当前页**。成功会显示读取条数；返回首页即可查看同一数据源更新的列表和总览。
5. 如果提示存在内嵌页面，点击 **打开内嵌页面**，优先选择“作业页面”，加载后再次同步。
6. 切换网页的未完成/已完成、下一页或其他课程后，可以继续点击同步当前页；其他已读记录会保留。手动读取永远标记为部分覆盖，不冒充账号全量数据。

若页面显示“未读取到作业”，反馈它给出的候选作业数、缺少标识数、内嵌页数与视口大小即可。无需提供 Cookie、密码、Key 或完整 URL。本项目仍未获得真实账号 DOM，因此不能承诺覆盖所有学校页面与仅通过复杂 JavaScript 导航的模板。

## 验证

- 42 项 JVM 单元测试通过；新增自身超时、父取消、外层期限，以及部分合并保留其他课程/已知字段/账号隔离测试。
- JS 回归覆盖 HTTP 课程和框架入口、当前页面字段和行属性、classId/clazzId 继承、缺少 ID 诊断、图标 title 状态、AJAX 空容器与明确空态，以及分页禁用。
- 新增正常文档视口和 Room 部分合并的设备测试，已编译，未执行：ADB 没有连接设备。
- 构建、签名和 Lint 结果见 [verification.md](verification.md)。

参考：[协程局部超时与取消语义](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout-or-null.html)、[WebView 当前文档执行 JavaScript](https://developer.android.com/reference/android/webkit/WebView#evaluateJavascript(java.lang.String,%20android.webkit.ValueCallback%3Cjava.lang.String%3E))。
