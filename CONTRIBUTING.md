# 开发与贡献

当前兼容目标是 Minecraft 1.21.1、VSS NeoForge 0.22.0 / 协议 20。VSS 调用通过反射和 Mixin 接入；升级上游时需要核对 ABI，并运行真实客户端测试。

## 构建

安装 JDK 21，克隆本仓库后运行：

```sh
./gradlew --no-daemon build distZip
```

Windows 使用 `gradlew.bat`。首次构建需要访问 Gradle、Maven Central、NeoForge 和 PaperMC 仓库；构建不需要安装 VSS 或 Voxy。Gradle Wrapper 固定在 9.2.1，并记录官方分发包 SHA-256。

默认从 Maven 获取 Velocity API。只有明确需要离线 SDK 时才传 `-PvelocityApiDir=/path/to/sdk-jars`；该目录须包含 Velocity API 及其编译依赖，不应提交到仓库。CI 使用默认 Maven 路径。

## 模块

| 路径 | 负责内容 |
| --- | --- |
| `common` | 对象、会话、持久化版本、TCP、HTTP 与集成检查 |
| `neoforge-shared` | VSS 反射接口和可选 payload，编入两个 NeoForge JAR |
| `vss-cdn-cilent` | 客户端传输协商、下载、取消与原 VSS 回注 |
| `vss-cdn-server` | 编码后接管、后端身份、发布与 TCP 服务 |
| `vss-cdn-bridge` | Velocity 配置下发、后端路由和 HTTP 回源 |

## 从源码追踪一列地形

1. `VssSendMixin` 在 VSS 编码后的发送入口调用 `ServerMod.intercept`。`SnapshotStore` 保存对象，维护版本、容量与保留期。
2. `VssCdnBridge` 发送 CDN 配置。`BridgeHttpServer` 处理回源，`BackendPool` 主动连接各后端的 `BackendTcpServer` 并读取对象。
3. `CdnClient` 管理协商、异步下载和回退。`DownloadLedger` 合并在途请求。`LssAccess` 与 `ReceiptChecks` 复用原 VSS 的收据、解码和入库流程。

数据流示意和协议字段见 [工作原理](docs/HOW-IT-WORKS.md) 与 [协议说明](docs/PROTOCOL.md)。

## 验证

`build` 会运行 `:common:integrationTest`。断言数以测试输出为准。覆盖版本持久化、双后端路由、鉴权、真实 TCP/HTTP、缓存保留、尺寸限制、会话取消、LAN 选择、同 URL 去重和 4,096 项批量响应查找。

客户端补丁只负责传输。禁止改写原 VSS 缓存键、Voxy 世界标识、存储目录或渲染生命周期。切服只能取消本组件的 HTTP 工作，不能重建 VSS 管理器或主动重新握手。发布检查会核对客户端混入点及已删除的世界管理类。

真实服务的探测见 [OPERATIONS.md](docs/OPERATIONS.md)。涉及客户端状态或 Voxy 的改动，还需验证入服、下载中切服、同名维度、世界变更、HTTP 故障回退以及画面；不要用“交回 VSS”的日志代替渲染完成证明。

## 提交与发布

- 修改前说明触发条件和最终行为，保持当前协议兼容边界。
- 提交配置示例时使用示例域名，不包含运行环境的密钥、世界或玩家数据。
- 发布流程见 [RELEASING.md](docs/RELEASING.md)。安装包由 Gradle 从三个当前 JAR 明确组装，不从本机 `dist/` 目录打包历史文件。
- Gitea 工作流位于 `.gitea/workflows/build.yml`。管理员需要另行启用 Actions 并配置 `ubuntu-latest` 运行器；有工作流文件不代表 CI 已运行。
