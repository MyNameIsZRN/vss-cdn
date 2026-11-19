# VSS CDN · Minecraft 1.21.1

VSS CDN 是 VoxyServerSide（VSS）的传输补丁。玩家仍通过原有 Velocity 入口入服。VSS 照常生成、编码和管理远景地形，公网玩家可以通过 CDN 下载大块地形数据，让多个玩家共享同一份 CDN 缓存。

补丁继续使用 **VSS/Voxy 默认的世界识别、地形缓存和入库流程**。局域网玩家可以走原生 VSS 游戏连接，CDN 下载失败时也会回退到这条链路。

## 三个组件装在哪里

| 组件 | 安装位置 | 负责什么 |
| --- | --- | --- |
| `vss-cdn-cilent` | 玩家 NeoForge 的 `mods/` | 用 CDN 地址下载远景数据，交给原版 VSS 解码和入库 |
| `vss-cdn-server` | 每个 NeoForge 或兼容 Youer 后端的 `mods/` | 声明后端身份，把 VSS 已编码的数据发布成可下载对象，并开放独立 TCP 回源端口 |
| `vss-cdn-bridge` | Velocity 的 `plugins/` | 向客户端下发 CDN 地址，提供独立 HTTP 回源端口，主动连接各后端读取对象 |

客户端文件名沿用 `cilent` 的拼写，模组 ID 为 `vss_cdn_client`。CDN 域名只配在 Velocity 的 `bridge.properties` 中。多个后端可以共用一个 Velocity 玩家入口和一个 CDN 域名，各后端用自己的 UUID 路由。

## 从哪里开始

| 我想了解 | 去哪里看 |
| --- | --- |
| 第一次了解项目 | [工作原理](docs/HOW-IT-WORKS.md) · 用一列远景地形讲清首次回源、缓存命中、更新、切服和回退 |
| 下载和安装 | [Gitea Releases](/IamZRN/vss-cdn/releases) · [安装与升级](docs/INSTALL.md) · [配置示例](examples/) |
| 配置 CDN | [HTTP 与 CDN](docs/CDN.md) · 域名、回源端口、缓存规则、预热和费用 |
| 运行和排错 | [运维与排错](docs/OPERATIONS.md) · 日志、HTTP 状态码、磁盘和连接 |
| 开发和发布 | [开发与贡献](CONTRIBUTING.md) · [协议](docs/PROTOCOL.md) · [构建与发布](docs/RELEASING.md) |
| 查版本与测试 | [更新记录](CHANGELOG.md) · [实测记录](VERIFICATION.md) |

## 当前版本

**0.1.0-alpha.7** 针对 **Minecraft 1.21.1、VSS NeoForge 0.22.0（协议 20）**。客户端仍需安装官方 VSS 及兼容的 Voxy NeoForge 移植版。后端仍需安装 NeoForge 版 VSS。代理实测版本为 Velocity 4.2.0。

alpha.7 在隔离双 Youer 环境回归了切服、原生回退和缓存复用。远景绘制验收使用兼容的测试版 Voxy，实际客户端仍要满足所用 Voxy 的渲染要求。[验证记录](VERIFICATION.md)

仓库采用 [MIT](LICENSE) 许可证。VSS、Voxy 等上游组件另行安装，遵循各自许可证。[VSS 项目](https://modrinth.com/plugin/voxy-server-side)
