# 0.1.1 登录问题修复

用户反馈：启动空白、网页登录后未进入首页、提示“此登录跳转需要外部服务，当前未授权该来源”，域名为 i.chaoxing.com。

## 已确认的代码缺陷与处理

1. 原版把非允许导航全部显示为“外部来源未授权”，无法区分官方 HTTP 链接、about:blank、真正的学校 SSO 与外部应用。
   - 官方 HTTP GET 链接升级为 HTTPS，保留已经转义的 ticket/refer/query/fragment；不放开明文流量。
   - 官方 HTTPS 地址直接继续，学校 HTTPS 站点展示准确来源和“本次允许/取消”对话框；批准只针对当前窗口和准确来源。
   - HTTPS 验证码 iframe 保持浏览器同源限制，不错误地当成顶层登录跳转。
2. 原版临时新窗口未插入界面、未启用完整网页设置，并在首次导航后立即销毁；about:blank 会被错误拦截。
   - 新窗口作为真正的可见 WebView 加入容器，保留 opener、表单和关闭语义，允许关闭窗口返回原页面。
3. 原版要求页面出现“退出登录”控件才确认登录，并且某些手动验证分支没有反馈。
   - 同时检查官方个人页、可见登录表单、账号 ID、原生 CookieManager 的会话 Cookie，兼容 HttpOnly 和同源 iframe。
   - 个人主页不再依赖退出按钮；空白文档、未完成加载、过期提示、登录表单及伪造域名均不能确认登录。
   - 手动验证有明确状态。在 passport 页面点击验证，会打开官方主页检查会话。
   - 持久化账号后发出登录完成事件，明确返回首页并触发作业同步；同一账号重新登录也能触发。
4. 原版启动时同时创建全屏透明同步 WebView，且缺少页面加载状态和恢复入口。
   - 登录期间不创建同步 WebView；首页的同步视图缩为不可交互的 1dp 工作宿主。
   - 增加来源栏、进度、超时提示、空文档检测、重试、返回官方首页，以及渲染进程退出恢复。

未连接用户手机，因此不能声称已在该设备复现唯一白屏原因；以上是源码中已修正的缺陷与可能造成空白的路径。

## 安装与手机验收

1. 安装 `dist/HomeworkAssistant-0.1.1-debug.apk` 覆盖旧版。版本号 0.1.1，versionCode 2；签名与上版一致。
2. 打开登录页，顶部应有来源栏和刷新按钮；加载中显示进度，不再只剩空白。
3. 完成网页登录。通常会自动进入首页；未进入时点击“我已完成登录，验证登录状态”，底部应显示验证结果或下一步。
4. 使用学校统一认证时，遇到外部 HTTPS 站点应显示“允许学校登录站点？”；确认来源后选择“本次允许”。
5. 若提示表单认证需重试，返回上一页重新点击学校登录按钮。系统通知设置与此授权无关。
6. 页面加载异常时选择“重试”或“返回官方首页”；移动版页面要求外部 App 时也可切换“电脑网页”继续网页登录。
7. 登录失效后重新登录，同一账号应返回首页并重新同步。退出应用重进时仍使用平台允许保留的 Cookie。

学校真实认证链、作业 DOM 和真机行为仍需设备验收。若仍失败，反馈当前步骤、来源栏中的域名和页面错误文字；不要提供密码、Cookie、Key 或带票据参数的完整 URL。

## 回归验证

- 新增 7 项导航策略测试、6 项登录状态测试；连同原有测试共 32 项。
- JS 测试新增个人页无退出按钮、隐藏登录表单、隐藏 iframe、过期登录提示、空页和伪造域名。
- 新增授权对话框设备测试：显示准确来源、未点击前不授权、取消不授权。已编译，未在设备执行。
- 完整构建和静态检查结果见 [verification.md](verification.md)。

实现依据：[WebViewClient 导航及 POST 回调边界](https://developer.android.com/reference/android/webkit/WebViewClient#shouldOverrideUrlLoading(android.webkit.WebView,%20android.webkit.WebResourceRequest))、[WebChromeClient 新窗口要求](https://developer.android.com/reference/android/webkit/WebChromeClient#onCreateWindow(android.webkit.WebView,%20boolean,%20boolean,%20android.os.Message))。
