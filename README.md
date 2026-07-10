
## 平安回家

本软件是广州市白云区蓝牙门禁的离线版本，只需要门禁的mac地址以及加密key即可开门，无需网络。

1. 提取MAC地址及加密密钥 ,Mac地址及 加密key [获取方式](extract.md)
2. 点击软件右上角编辑按钮，将Mac地址及Key填进去，保存
3. 开门

## 构建环境

- JDK 17
- Gradle 8.4（使用仓库内的 `./gradlew`）

## CI/CD

- 推送到 `main` 或向 `main` 提交 Pull Request 时，执行 lint、单元测试并构建 Debug APK。
- 推送 `v*` 标签时，在通过 CI 后构建、校验签名并将 signed Release APK 发布到 GitHub Releases。
- Release 签名从 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 四个 GitHub Actions Secrets 注入；仓库不保存签名密钥。
