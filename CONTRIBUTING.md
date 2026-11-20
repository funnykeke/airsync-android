# 参与贡献

欢迎通过 GitHub Issue 或 Pull Request 反馈问题与改进建议。

## 提交规范

- 请保持代码简洁，避免引入高耗电的后台常驻任务或不必要的第三方依赖。
- 切勿在代码中提交任何个人服务器 IP、域名、密钥、自签证书或签名 keystore。
- 新增用户可见文本请同时补充 `app/src/main/res/values/strings.xml` 与 `app/src/main/res/values-zh/strings.xml`。
- 提交前请运行：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

## 协议说明

所有贡献将遵循本仓库 [LICENSE](LICENSE) 中的 MPL-2.0 协议及附加条款。
