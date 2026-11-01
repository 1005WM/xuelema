# main 分支合并核验（0.3.12）

日期：2026-11-01（Asia/Shanghai）。

本次合并的其他分支：

- `fix/multi-blank-answer-fill`：`7a0de53`，包含多空题逐空生成/回填和构建流程修复。
- `fix/startup-refresh-and-ai-scroll`：`2cdc6b1`，包含启动/手动刷新、AI 答案滚动、填空题解题步骤、解析换行和既有签名证书核验。

第二个分支已经包含第一个分支的全部提交。`main` 从原始 0.3.9 提交 `d438642` 快进到 `2cdc6b1`，保留所有分支提交及最新应用代码，没有冲突或强制覆盖。原分支保留。补充的文档提交仅修正 README 的版本和读取触发说明。

`app/build.gradle.kts` 与实际构建 APK Manifest 一致：`versionName = "0.3.12"`，`versionCode = 27`，`applicationId = "com.homeworkassistant"`。没有额外提升版本号。

## 本地验证

- 完整 JavaScript 回归通过（9 个脚本），包括新增 18 项多空回填用例。
- 232 项 JVM 单元测试通过，0 失败。
- Debug APK 和 AndroidTest APK 构建通过。
- Lint 通过，0 错误 / 5 条提示。
- Android 15（API 35）独立模拟器上 7 项设备回归通过：`HomeworkStatusUpdateTest` 4 项，`AiAnswerScrollTest` 3 项。
- `git branch -r --no-merged main` 无输出，确认抓取的所有远端分支均已被包含。

设备测试使用本地测试数据，没有进行真实学校账号、真实提交或 AI 服务调用。此次设备构建使用本地测试签名，未用于判断能否覆盖用户手机上既有的 CI 安装包；签名核对机制详见 [沿用原签名与覆盖安装](existing-signature.md)。

当前最新分支的读取策略为每个账号每次应用会话自动尝试一次，其余读取由手动操作触发；提交后的列表、总数和分类通过手动刷新更新。详情页、网页加载完成和返回首页不再自动发起状态核对。该行为来自本次保留的最新分支，详见 [启动刷新与 AI 答案滚动修复](startup-refresh-and-ai-scroll.md)。
