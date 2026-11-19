# 构建与发布

## 本地验证

```sh
./gradlew --no-daemon build distZip
```

构建默认使用公共 Maven 依赖。`dist/` 中会产生三个当前版本 JAR，以及按 client/server/velocity 分目录的安装压缩包。压缩包只包含本项目 JAR、许可证、文档、配置示例和 Youer 兼容准备脚本，不包含运行配置、密钥、缓存、世界或第三方 VSS/Voxy JAR。

修改文档后需要重新运行 `distZip`，让安装包内的说明与源码一致。

## 生成可核验的发布文件

先检查差异和凭据，将发布内容提交到 Git，再运行：

```sh
python3 tools/package_release.py
```

该命令要求工作区干净，验证三个 JAR 中的版本和组件身份，并检查安装压缩包包含相同字节的 JAR。随后使用 `git archive HEAD` 生成仅含已提交文件的源码包，并为这五个文件生成 `dist/SHA256SUMS`：

```text
vss-cdn-cilent-neoforge-1.21.1-0.1.0-alpha.7.jar
vss-cdn-server-neoforge-1.21.1-0.1.0-alpha.7.jar
vss-cdn-bridge-0.1.0-alpha.7.jar
vss-cdn-0.1.0-alpha.7-install.zip
vss-cdn-0.1.0-alpha.7-source.zip
```

校验下载：Linux 使用 `sha256sum -c SHA256SUMS`；macOS 使用 `shasum -a 256 -c SHA256SUMS`。Windows 可用 PowerShell `Get-FileHash -Algorithm SHA256 <文件>` 比对。

## Gitea Release

1. 将提交推送至仓库，确认远端提交一致。禁止强推覆盖现有用户提交。
2. 创建并推送对应版本标签，例如 `v0.1.0-alpha.7`。
3. 创建标记为 **Pre-release** 的 Release，说明兼容版本、组件升级方式、已完成测试和渲染限制。
4. 上传上述五个文件和 `SHA256SUMS`，再下载核对大小及摘要。
5. 保持仓库已有可见性。不要把本机客户端整合包、服务器 `.properties`、世界身份或原始账号日志作为发布附件。

版本升级需要同时修改根 `build.gradle`、两个 NeoForge TOML、Velocity JSON 和 `@Plugin` 中的版本，重新构建并测试。发布工具会检查 JAR 元数据，但不能代替运行行为验证。

## CI

`.gitea/workflows/build.yml` 在启用 Actions、配置 `ubuntu-latest` 运行器后执行相同的构建和集成检查。工作流不会自动创建 Release 或读取部署密钥。当前实例没有启用 Actions/运行器，因此本次 Release 的构建结果来自本地验证，远端 CI 尚未运行。
