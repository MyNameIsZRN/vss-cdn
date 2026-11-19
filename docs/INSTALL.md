# 安装与升级

想先了解三个组件如何通信，可以看 [工作原理](HOW-IT-WORKS.md)。

## 支持组合

| 组件 | 当前目标 |
| --- | --- |
| Minecraft | 1.21.1 |
| 加载器 | NeoForge；本项目编译版本 21.1.266 |
| 原版 VSS | NeoForge 0.22.0，mod ID `lss`，协议 20 |
| 客户端渲染器 | 与 VSS 兼容的 Voxy NeoForge 移植版，另行安装 |
| 代理 | Velocity；实测 4.2.0，编译 API 3.4.0 |
| 本项目 | client、server、bridge 当前发布 0.1.0-alpha.7；alpha.3 client/bridge 兼容 alpha.2 server |

实测加载器还包括客户端 21.1.252、AsyncYouer 内置 21.1.264，以及普通 Youer 内置 21.1.265；这不是对所有 NeoForge 补丁版本的兼容承诺。构建使用 JDK 21；代理和具体 Youer 构建可能需要其他 Java 版本。

上游 VSS：[NeoForge 0.22.0 下载页](https://modrinth.com/plugin/voxy-server-side/version/40fV0QFA)。本次验证的官方 JAR SHA-256：

```text
c40447c7873dcf0f951f8762d4ee71458c206ab1fe5db190ba4f6297f3257e53
```

本仓库 Release 仅分发本项目的三个附加组件；VSS、Voxy 和服务端核心需分别获取。`cilent` 是历史安装包/目录拼写，客户端 mod ID 仍为 `vss_cdn_client`。

## 安装顺序

1. 正常停止需要更换模组的后端，将原模组和将修改的配置保存在 `mods/` 外。
2. 每个后端 `mods/` 安装 VSS NeoForge 0.22.0，以及 `vss-cdn-server-neoforge-1.21.1-0.1.0-alpha.7.jar`。
3. Velocity `plugins/` 安装 `vss-cdn-bridge-0.1.0-alpha.7.jar`。不要同时保留多个版本的 bridge。
4. 启动后端，为每个后端生成 `config/vss-cdn/server.properties`。复制各自的 `backend.id`、`bridge.secret`、TCP 地址和端口到 bridge 配置，参考 [bridge.properties](../examples/bridge.properties)。
5. 配置可达的 HTTP 回源端口、CDN 和 `cdn.baseUrl`，将 bridge 的 `enabled` 改为 `true`，再重启 Velocity。CDN 配置见 [CDN.md](CDN.md)。
6. 玩家客户端 `mods/` 安装**官方原版** VSS NeoForge 0.22.0、CDN client JAR 和兼容 Voxy。客户端不安装 server JAR，也不使用下文为 Youer 去掉 zstd 的服务端副本。
7. 玩家重新连接，通过 [OPERATIONS.md](OPERATIONS.md) 中的日志和真实对象检查验收。

安装压缩包按 `client/mods/`、`server/mods/`、`velocity/plugins/` 分目录。只取当前位置对应的文件，不要把整个压缩包中的模组全部放进同一个 `mods/`。

## 三个端口与配置文件

| 连接 | 谁访问 | 到哪里配置 |
| --- | --- | --- |
| Minecraft 游戏端口 | 玩家 → Velocity | 沿用已有 Velocity 游戏入口 |
| Bridge 的 HTTP 端口 | CDN 或反向代理 → Velocity bridge | `plugins/vss-cdn-bridge/bridge.properties` 的 `http.bind`、`http.port`，默认 `127.0.0.1:25590` |
| 后端 TCP 端口 | Velocity bridge → 各个后端 | 后端 `config/vss-cdn/server.properties` 的 `tcp.bind`、`tcp.port`，默认 `127.0.0.1:25591` |

1. 保留每个后端自动生成的 `backend.id` 与 `bridge.secret`。后端的默认值与注释见 [server.properties 示例](../examples/server.properties)。
2. 在 [bridge.properties 示例](../examples/bridge.properties) 中，用 `backend.names` 列出要接入的后端，为每个名称填入对应的 `id`、`secret`、`host`、`port`。这里用的名称是配置标签，实际路由按后端 UUID。每个后端可以使用不同密钥，密钥至少 32 字符。bridge 主动建立持久连接，会定期检查和重连。后端 TCP 使用密钥鉴权，跨公网访问需通过加密隧道。
3. 把 `cdn.baseUrl` 设为玩家访问的 CDN 根地址。启动 bridge 时会检查根地址、路由和密钥配置。改桥接设置后重启 Velocity，再让客户端重新入服。旧版后端配置里的 `cdn.baseUrl` 会在后端启动时移除，已有身份和发布目录会沿用。

客户端无需另填地址。使用 Nginx 接受 CDN 回源时，可参考 [Nginx 示例](../examples/nginx.conf)。后端发布的磁盘目录与上限见 [运维指南](OPERATIONS.md)。

## Youer 的重复 zstd 依赖

已验证的 Youer 内置 `zstd-jni 1.5.7-8`，官方 VSS 0.22.0 内嵌 `1.5.7-3`。模块重名可能导致启动失败。仅在核对这个组合后，生成安装副本：

```sh
python3 tools/prepare_youer_vss.py \
  --vss /downloads/voxy-server-side-neoforge.jar \
  --youer-zstd /server/libraries/com/github/luben/zstd-jni/1.5.7-8/zstd-jni-1.5.7-8.jar \
  --output /staging/voxy-server-side-neoforge-0.22.0-youer.jar
```

脚本拒绝覆盖现有输出，只移除内嵌 zstd 及对应 JarJar 元数据，保留 VSS 类、资源和许可证。普通 NeoForge 客户端/服务端不因此改包。其他 Youer 内置版本需另行验证。

## 多后端与 Docker

每个后端使用独立身份和发布目录。若在同一机器运行多个后端，可以分别使用 TCP 25591、25592；bridge 主动连接这些端口，玩家和 CDN 不直接访问它们。

容器中的 `127.0.0.1` 只表示该容器。后端在容器内通常需要 `tcp.bind=0.0.0.0`；bridge 的 `backend.<name>.host` 则填写它实际可达的地址。

默认 Docker bridge 网络的一种部署方式：

| 位置 | 容器监听 | 宿主映射 | bridge 中的地址 |
| --- | --- | --- | --- |
| 后端 A | `0.0.0.0:25591` | `172.17.0.1:25591:25591/tcp` | `172.17.0.1:25591` |
| 后端 B | `0.0.0.0:25592` | `172.17.0.1:25592:25592/tcp` | `172.17.0.1:25592` |
| Velocity HTTP | `0.0.0.0:26665` | `26665:26665/tcp` | CDN 使用公网源站域名与 26665 |

网桥地址是示例，请以现场网络为准。宿主防火墙还需允许 bridge 所在 Docker 子网访问这两个宿主后端端口。容器内直接互连、通过用户定义网络和其他部署方式也可以，只要地址稳定且实际可达。端口映射应写入原有 Compose/面板/启动脚本，重新创建容器后仍能恢复。

保留现有 Velocity → NeoForge 的认证与模组握手配置。若有通道白名单，允许 `vsscdn:control`、`vsscdn:delivery` 和原有 `lss:*` 通道。不要同时安装 Paper 版和 NeoForge 版 VSS。

## 局域网连接

bridge 的 `lan.direct.enabled=true` 默认开启。Velocity 按玩家来源 IP 判别，来源为 `192.168.*.*` 时通知客户端走原生 VSS 游戏连接，不访问 CDN 或 bridge HTTP 端口；入服用公网域名也适用。后端服务端不自行判断，因为它看到的通常是代理地址。

局域网会话日志应出现 `LAN direct session active`，公网来源仍出现 `CDN session active`。世界与缓存隔离由 VSS/Voxy 默认规则决定。其他内网网段与普通 IPv6 不在本规则内。如果 NAT 或隧道改写了来源，应先确认 Velocity 能看到原始玩家 IP。把共享 Docker 网关纳入直连会误分流公网玩家。

## 原生缓存

从 alpha.5 起，客户端沿用 VSS/Voxy 的世界标识、缓存键和目录。CDN 与直连通过同一个原生入库流程共享当前缓存键的数据；不同入口地址按原 VSS 规则处理，不由补丁合并。

旧 scoped 缓存及 `config/vss-cdn/cache-identities.properties` 原样保留，不自动迁移或读取。首次按默认缓存键连接可能重新获取地形；不要只复制旧收据到新键，否则可能出现收据已有而地形缺失。

## 升级与回退

- alpha.6 → alpha.7：更新三个附加组件即可沿用现有配置和发布目录。控制 v2、HTTP/TCP v1 和 VSS 原生缓存键兼容。
- alpha.5 → alpha.6：更新三个附加组件即可沿用现有配置和发布目录。控制 v2、HTTP/TCP v1 及 VSS 原生缓存键保持兼容。
- alpha.4 → alpha.5：只需更新 client；兼容 alpha.2+ server 与 alpha.3+ bridge，控制 v2 和 HTTP/TCP v1 不变。恢复原生世界/缓存管理，旧补丁缓存不自动迁移。
- alpha.3 → alpha.4：只更新 client 即可；生产后端和 bridge 无需重启，协议不变。身份索引会沿用同 UUID 作用域的已有收据桶。
- alpha.2 → alpha.3：升级 client 和 bridge，Velocity 按玩家来源自动选择局域网直连；后端 alpha.2 server 无需更换或重启。旧 client 在收到 LAN 新消息时走原有 5 秒超时回退；公网 CONFIG 保持兼容。
- alpha.1 → alpha.2：三个组件一起更换。`cdn.baseUrl` 移到 bridge；后端启动会自动移除遗留字段，身份及现有发布对象可继续使用。
- 老的第三方 VSS 0.3.2 → 官方 0.22.0：更换为新原版 VSS，并核对其新配置。旧 `.vcl` 与 0.22.0 的 SQLite LOD store 不是本补丁承诺迁移的格式；保留旧缓存供回退，新 store 按请求建立。
- 原 VSS 0.22.0 的读取、缓存、编解码仍由原版负责；本补丁额外保存已编码字节的 HTTP 发布副本。
- 只回退附加组件时，正常停服后移出对应 JAR 并重启，兼容客户端可继续使用原 VSS 通道。回退原版 VSS 时，同时恢复匹配的客户端、原配置和旧缓存，保留新发布目录供以后恢复。
- 克隆、换世界或回滚世界时，创建新的世界身份与发布目录。不要让两个正在写入的后端共用同一套身份和发布数据。

远景显示需要满足所用 Voxy 版本的渲染要求。早期 macOS OpenGL 4.1 环境只验证了传输，后续使用兼容的测试版 Voxy 验证了绘制。测试环境及结果见 [验证记录](../VERIFICATION.md)。
