# 沿用原签名与覆盖安装

Android 覆盖安装要求 applicationId 一致、签名密钥相同，并满足版本更新要求。当前 applicationId 保持 com.homeworkassistant，0.3.12 的 versionCode 为 27；原签名私钥仍是必要条件。

在仓库 Settings → Secrets and variables → Actions 配置：
- ANDROID_KEYSTORE_BASE64：原 .jks / .keystore 文件的 Base64 内容
- ANDROID_KEYSTORE_PASSWORD：原 keystore 密码
- ANDROID_KEY_ALIAS：原密钥别名
- ANDROID_KEY_PASSWORD：原密钥密码

构建流程在 Runner 临时目录解码密钥，限制文件权限，使用该配置签名 Debug / Release；不将私钥写入源码、构建产物或缓存。配置不完整则失败。使用同一组 Secrets 的后续构建会沿用同一个签名。

未配置原密钥时保留 CI Debug 构建，明确输出不能宣称覆盖现有安装的提示。安装包附带 apksigner 验证结果与证书指纹，可用于核对旧包；证书指纹本身不能还原私钥。以前已经生成且未保存密钥的临时 CI Debug 包，其原私钥无法从 APK 还原。只有配置与手机现有安装一致的原密钥后，才能确认支持覆盖。

禁止把 keystore、密码或 Base64 私钥提交到公开仓库。该配置不会绕过 Android 签名检查。
