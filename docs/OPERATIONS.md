# 运维与排错

## 成功链路的证据

1. 后端日志出现 `CDN backend ... listening at ...`。
2. Velocity 日志出现 `VSS CDN HTTP origin listening ... connecting outbound to N backends`。
3. 客户端出现 `CDN session active ... (configured by Velocity bridge)`。
4. 客户端出现 `CDN delivered ... HTTP bytes to VSS`。
5. 实际成功对象可经 CDN 返回 200，重复请求能命中缓存，字节与源站一致。

第 4 步仅证明数据交回 VSS；Voxy 入库和渲染、地图显示及视觉隔离需要另外验收。`CDN delivered` 为交付次数，可包含同坐标重试，不是唯一地图区块统计。

## 局域网直连的证据

bridge 开启 `lan.direct.enabled=true` 后，按 Velocity 看到的玩家来源 `192.168.*.*` 下发直连选择。即使用公网域名入服，只要来源仍是此网段，就应记录 `LAN direct session active ... (original VSS; selected by Velocity bridge)`，此会话不应出现 `CDN delivered`，仍保留后端/世界身份。LOD 由原生游戏连接发送，受原生 VSS 限速影响。

若仍走 CDN，先查 Velocity 登录日志看到的来源，不要看客户端连接的目标地址。源地址被 NAT/隧道改写成 Docker 网关或公网 IP 时，不能从入服域名还原。不要把共享 Docker 网关当作所有 LAN 玩家，否则会误分流公网连接。

## 无玩家、只读探测

构建环境可运行已有的 `PlatformSmokeProbe`：

```sh
./gradlew :common:smokeProbe \
  -PserverConfig=/server/config/vss-cdn/server.properties \
  -PstateFile=/server/world/vss-cdn-publications/publication-state.bin \
  -PhttpBase=http://127.0.0.1:26665 \
  -PtcpHost=172.17.0.1
```

`tcpHost` 默认 127.0.0.1，其他三个参数必填。应使用有权限读取后端配置及状态文件的账号运行；探测器不输出密钥。它验证真实 TCP 身份、鉴权及 HTTP → 后端路由，使用随机世界的缺失对象，预期为 `410 + no-store`，不会生成或修改地图。它不验证成功对象、商用 CDN 命中率或客户端画面。

## 常见故障

| 现象 | 排查重点 |
| --- | --- |
| 后端启动报 zstd 模块重名 | 核对 Youer 内置 zstd，使用安装文档的去重工具 |
| bridge 未启动 | `enabled`、CDN 根地址、占位密钥、重复后端 UUID、端口占用 |
| 根路径返回 400 | 正常；应测试完整 `/lod/v1/...bin` 地址 |
| 对象返回 400 | 路径不完整、不规范或带查询参数 |
| 对象返回 404 | bridge 没有该后端 UUID 的路由 |
| 对象返回 410 | 旧对象已清理、发布代变化或 URL 不存在；应由客户端重新请求当前 VSS 数据 |
| 对象返回 503 | bridge 到后端地址/端口、Docker 映射、宿主防火墙、密钥和连接池 |
| 客户端 5 秒后回退 | 未同时收到 bridge CONFIG 与后端 SESSION；核对代理通道与三组件版本 |
| 下载中回退 | HTTP 超时、503 连续三次或其他非 200、身份或 CRC 错误、尺寸/预算上限、对象过期 |
| 下载正常但没有远景 | Voxy ABI、GPU/OpenGL、渲染开关及实际消费者；检查日志而非仅看模组加载清单 |
| 原生 VSS 限速与 HTTP 实际流量不同 | 本补丁的发送预算主要计小型引用，不是 HTTP 字节；需要在 HTTP/网络侧单独限制 |

默认 Docker bridge 上，容器访问宿主发布端口可能经 docker-proxy 进入宿主 INPUT 链。仅确认 Docker FORWARD 放行不足以证明可达；从 Velocity 容器实际探测后端。后端 TCP 端口仅允许 bridge 所在内网访问。

## 原生缓存排查

alpha.5 日志中的 `Column cache key` 应采用原生地址与世界种子规则，不再追加 `.cdn-<UUID>`。同一入口下切换 CDN/直连不创建新的原生缓存身份；不同地址是否复用由 VSS 的原有规则决定。

旧 `cache-identities.properties` 与 scoped 地形/收据不再读取，也不自动合并。升级后的首次重传是恢复默认键时的预期行为。确认收据与真实地形库配套；不能以 HTTP 交付日志代替入库或画面验证。

## 磁盘占用与重启

| 数据 | 用途 | 常见位置 |
| --- | --- | --- |
| Minecraft 世界存档 | 地形、实体、玩家与模组数据 | 世界目录中的 `region/`、各维度和其他存档目录 |
| VSS 原生 LOD 缓存 | 保存 VSS 可复用的远景数据 | 世界目录中 VSS 管理的 SQLite store |
| CDN 源站发布对象 | 保存按版本供 HTTP 下载的已编码副本 | 默认 `<世界>/vss-cdn-publications/`，由 `snapshot.directory` 配置 |

统计整个世界目录时，这三部分会加在一起。比较存档备份体积或估算 CDN 发布所需空间时，分别量各目录。客户端的收据与 Voxy 地形数据另存在客户端。

- `config/vss-cdn/server.properties`：后端 UUID、密钥和发布参数。
- `config/vss-cdn/worlds.properties`：各维度世界 UUID，首次使用时生成。
- `<世界>/vss-cdn-publications/publication-state.bin`：后端身份、发布代、预留版本计数。
- 发布副本默认磁盘上限 64 GiB、内存热缓存 64 MiB。服务端定期在锁外扫描超出 30 天保留期的对象，扫描间隔 10 分钟，删除前再次检查保留时间。后端再次发布相同内容时，最多每小时刷新一次对象的保留时间，URL 保持相同。

正常重启应保留这套身份与发布数据。磁盘上限或写入失败时，对应区块回退原 VSS；不要通过复制旧版本号来恢复空间。发布目录丢失后会建立新的发布代。

发布缓存的估算用量触及上限时，会在锁外复核磁盘上的实际用量。一次复核仍显示超额时，常规复核间隔至少 30 秒。若已缓存的发布文件被发现损坏或丢失，可提前复核并签发新 revision。

克隆服、换地图或世界回滚应使用新的世界 UUID 和发布目录，避免旧 URL 指向新内容。备份和恢复时一并处理身份与发布目录；原世界和数据库仍需按自身流程管理。

## 回退

正常停止受影响的后端/代理，移出本项目 JAR，恢复需要回退的配置，再启动原 VSS 服务。客户端缺少任一 CDN 组件时，兼容组合继续原 VSS 发送。不要直接删除世界、原 VSS 缓存或发布目录作为“回退”。如果还回退原版 VSS 的版本，应恢复与该版本匹配的客户端和配置。

完整验收清单及真实测试范围见 [VERIFICATION.md](../VERIFICATION.md)。
