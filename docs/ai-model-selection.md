# AI 模型列表与 DeepSeek 404 修复（0.2.4）

日期：2026-10-28。版本：0.2.4 / versionCode 10。

## 使用

1. 在设置页填写服务的 API 地址与 Key。DeepSeek 官方 Chat Completions 的 Base URL 为 `https://api.deepseek.com`，接口文档见 [官方接入说明](https://api-docs.deepseek.com/quick_start/agent_integrations/)。第三方提供的 DeepSeek 服务应使用该服务自己的地址和 Key。
2. 点击“保存并获取模型”，先加密保存 Key 和配置，再请求服务的 `GET /models`。这一步不需要预先知道模型名称，也不发送题目或聊天生成请求。
3. 点击“模型（下拉选择或手动填写）”，选择服务实际返回的模型标识，选择后自动保存。列表会去重并排序。修改 URL 或 Key 会清空旧列表，并取消旧请求；旧请求的返回结果不能覆盖新配置的模型列表。
4. 不提供 `/models` 的服务可手动填写模型，再点击“保存设置”或“保存并测试”。列表获取不会自动退回生成请求。“保存并测试”只在非 DeepSeek 官方服务的列表接口返回 404/405 时，才用所填模型发送一次最小聊天测试（最多 64 输出 Token，可能计费）。

模型列表的协议及 `data[].id` 格式依据 [DeepSeek 官方 Models API](https://api-docs.deepseek.com/api/list-models/)。模型标识取自实际返回值，不在 App 内硬编码版本。列表或鉴权成功仅确认该接口可访问，不表示余额充足、模型可生成或所有参数均兼容。

## 404 与保存问题修复

- 原实现始终在填写地址后追加 `chat/completions`；填写完整接口地址可能得到重复路径并返回 404。现在 Base URL、完整 `/chat/completions` 地址和 `/models` 地址统一转换为 API 根路径，生成和模型查询共用解析规则。例如 `https://api.deepseek.com/v1/chat/completions` 分别解析为 `/v1/chat/completions` 和 `/v1/models`。
- 404 仍可能来自服务端路径、模型名称或网关配置，不能据状态码断言用户 Key 有误。新提示会区分 401 鉴权、402 余额、404 路径/模型、429 限流等情况；错误分类参考 [DeepSeek 官方错误说明](https://api-docs.deepseek.com/quick_start/error_codes/)。不展示服务端原始正文，防止反射 Key 等敏感信息。
- “保存并获取模型”“保存并测试”均先保存当前输入，串行执行设置写入，再读取加密保存的 Key；避免旧版“当前 URL + 旧 Key”组合测试。
- Key 前后空白和重复填写的 `Bearer ` 前缀统一处理；Key 内空白或非法字符给出明确提示。旧密钥无法解密时，可以编辑新 Key 后重新保存。
- 限制 HTTPS、拒绝 URL 中的账号密码/查询参数/片段，AI 客户端不携带学习通 Cookie，不自动跟随重定向。模型列表最多读取 1 MB、30 秒；外部取消继续传播并关闭响应体。

## 验证

- 新增 14 项 JVM 测试：地址解析、非法地址拒绝、Key 规范化、带鉴权的 GET 列表、模型排序去重、模型未知、缺少 Key 不发请求、HTTP 分类与不泄露响应正文、重定向拒绝、异常/空列表、响应大小限制、兼容服务最小测试退路、DeepSeek 404 不静默退路、生成复用地址规则、DNS 错误提示。全部 124 项 JVM 测试通过。
- 新增 2 项 Compose 设备测试：选择模型后保存准确 ID，以及旧 Key 解密失败后可重新输入。测试已编译，未连接设备执行。
- 测试使用 OkHttp 拦截器与合成 JSON/SSE，没有使用真实 Key、没有请求真实模型服务或产生调用费用。尚未完成真实手机及 DeepSeek 账号验证。

安装包：`dist/HomeworkAssistant-0.2.4-debug.apk`。可覆盖前版，无需卸载。
