# VSS CDN 验证记录

各小节记录对应版本的测试环境与验收范围，包括本机隔离回归、双后端部署及商用 CDN 下载。

## 0.1.0-alpha.7（2026-11-18）

- JDK 21 离线构建及 114 条集成断言通过。分别模拟发布文件丢失和截断，服务端在运行中校准配额并用新 revision 重发。实际超额仍触发原生回退。
- 使用 550 次原子发布复测目录扫描，4,248 轮并发扫描均完成。跟踪测试确认清理旧 revision 与不活跃坐标时，热门列的缓存字节和原 URL 都保留。
- 使用 alpha.7 的三个 JAR，在本地 HTTP 代理、Velocity 和隔离双 Youer 环境通过 A → B → A 回归。三个阶段均绘制远景，切服取消 4 个待下载列，返回 A 恢复原生缓存键并载入 40 条旧收据。
- 短暂 503 在第二次请求成功。持续 503 触发一次原生批量回退，覆盖 7 个坐标（含探针加入的 6 个），服务端恢复原生 VSS 发包，客户端正常退出。
- 同 URL 的两条在途引用共用一次 GET，并按最新引用入库。4,096 条批量响应均正确匹配。原生批量重试探针保留两条已有收据。

## 0.1.0-alpha.6（2026-11-18）

- JDK 21 离线构建通过，106 条集成断言通过。覆盖热缓存文件截断、大小及时间戳相同的内容损坏、重启后重新发布、原 URL 文件保留，以及同一回源连接读取新 revision。
- 清理竞争测试确认目录扫描在发布锁外运行。扫描选中的旧对象在等候删除时续期，删除前复查将其保留。
- alpha.6 的客户端、后端和 Bridge 在隔离的双 Youer、Velocity、VSS 0.22.0 和 NeoForge 环境完成 A → B → A。三次均绘制远景，切服取消了 3 个待下载列，返回 A 时恢复原生缓存键并载入 40 条旧收据。
- 临时 503 重试成功。持续 503 触发批量回退，一次 VSS 重扫覆盖 7 个坐标，其中 6 个由测试探针加入，服务端恢复原生发包。两个原生收据在独立的批量重扫探针中保留时间戳。客户端正常退出。
- 回归使用本地 HTTP 代理和隔离世界副本，渲染由 Mac 测试版 Voxy 提供。

## 0.1.0-alpha.5：仅 CDN 传输（2026-11-18）

- 删除客户端自定义世界标识、缓存键/跨地址映射、Voxy ABI 访问、渲染器刷新及额外 VSS 握手。安装包检查确认客户端仅有 level 通知、VSS 接收与传输预算三个 Mixin，没有退休的世界管理类或直接 Voxy 类引用。
- 三组件构建及 82 条现有传输断言通过。alpha.4 跨地址映射的 10 条断言随功能移除；原生收据只读去重、HTTP/TCP、版本与路由检查保留。
- 隔离 Youer 双后端、Velocity 和 VSS0.22.0、NeoForge 开发客户端实测 A → B → A。同名 `minecraft:overworld` 使用不同原生种子，原生 `Column cache key` 按 A → B → A 恢复，无 `.cdn-UUID` 后缀。每次入服只收到一次原 VSS 会话配置。
- JDK HTTP 故障注入器延迟 4 秒，在两次切服时分别确认取消 3 个和 2 个 pending HTTP 列；两个后端均完成 HTTP 交付和实际远景绘制，返回 A 后再次绘制。
- 返回 A 后注入明确的 HTTP503，客户端记录回退，之后原生 VSS 继续入库/绘制；未额外重新握手或重建渲染器。客户端完成验收驱动并正常退出。此前 Python 注入器出现自身连接中断，其运行不作为稳定 HTTP 的证明。
- 测试使用 Apple M3 Pro 的 Metal 兼容 Voxy，仅作为可绘制的测试环境；CDN 补丁没有引入该分支的专用接口。
- 2026-11-18 03:57–03:58 补充完整 HMCL 整合包验收：NeoForge21.1.252、Sodium0.8.13、VSS0.22.0、已发布 alpha.5 客户端，main → survive → main 成功。三次均走 LAN 原生 VSS；默认地址/种子缓存键 A → B → A，HTTP 交付与 CDN 回退均为 0；三个阶段均有实际远景绘制。最终渲染器有 1,964 个常驻区块，客户端以退出码 0 正常结束；临时验收模组已移除，启动设置恢复。此为短时验收，不代表长期压力测试。

证据文件与完整账号/世界日志保留在本机忽略目录，不进入发布包。alpha.2–alpha.4 的历史说明如下；其中“跨地址收据即可代表共享地形”的结论已被后续评审纠正，alpha.5 不保留这项行为。

### P1 传输修复的隔离验收（本地工作树，2026-11-18）

- JDK 21 离线构建通过，82 条现有传输断言全部通过。
- 在双 Youer 后端、Velocity、原版 VSS 0.22.0 和 NeoForge 客户端上跑通 A → B → A。首次切服取消 10 个待处理 HTTP 列，回 A 恢复原来的 VSS 缓存键并读取 44 条已有列收据。三个阶段都绘制了远景，客户端正常退出。
- 本地 HTTP 代理返回 200 及部分 LOD 正文后暂停传输。客户端约 10.04 秒触发超时并回退，服务端随后确认原生 VSS 发包。探针对真实 VSS 请求管理器重试一列已有地形，确认原时间戳保留、该列被标记为待重扫。
- 单独启动缺少 zstd 本地库的测试客户端。服务端确认发送 25 个原生未压缩列，客户端绘制了远景，HTTP 代理未收到对象请求。
- 验收在本地回环网络进行，包含本地缓存代理及 VSS 收据复用。生产公网 CDN 未纳入本次验收。

### P2 传输修复的隔离验收（本地工作树，2026-11-18）

- JDK 21 离线构建及 94 条集成断言通过。将两个对象老化 31 天，重新发布其中一个并执行 30 天清理。热门对象跨清理与重启复用原 URL 和内容，未重发的对象过期。
- 双 Youer 和 Velocity 的实机客户端收到一次 503 后，对同一 URL 的第二次尝试成功并继续走 CDN。持续 503 时，同一 URL 最多尝试三次，之后服务端恢复 VSS 原生列发送。
- 在真实客户端重放同一 URL 的在途引用，HTTP 代理只收到一次 GET，原生 VSS 收到了更新后的时间戳。客户端探针处理了 4,096 项混合批量响应，探针单次测量约 2.15 毫秒。
- A → B → A 三个阶段均绘制远景。切服取消了 6 个待处理 HTTP 列，回到 A 后恢复原 VSS 缓存键并载入 39 条已有收据，客户端正常退出。
- 额外回归首次在 Mac 测试版 Voxy 的模型烘焙线程遇到一次 `ConcurrentModificationException`，崩溃日志已保留。同一构建复测完成 A → B → A、约 10.03 秒的正文超时回退、旧收据保留和 26 个原生未压缩列的传输检查。

### 回源容错与批量回退复核（本地工作树，2026-11-18）

- JDK 21 离线构建及 97 条集成断言通过。单连接池中的坏对象返回 `503 + no-store`，之后正常对象通过同一连接立即返回 `200`，响应内容一致。
- 隔离 Youer 将 `snapshot.retentionDays` 设为 0。CDN 初始化拒绝该配置，CDN TCP 端口关闭，游戏端口正常监听。
- 隔离双服 A → B → A 三个阶段均绘制远景，两次切服分别取消 4 个和 1 个待下载列。返回 A 后恢复原 VSS 缓存键并载入 39 条旧收据，客户端正常退出。
- 持续 503 触发原生回退后，一次 VSS 重扫调用覆盖 7 个待处理坐标，其中 6 个由测试探针加入。服务端恢复原生 VSS 发包。探针另对两条已有收据执行批量重扫，两个时间戳均保留。


## 0.1.0-alpha.4：共享收据与跨通道去重

92 项集成断言，新增 23 项覆盖持久化缓存身份、不同入服地址共用、重启后复用、后端/世界/发布代隔离、已有 scoped 旧桶识别、损坏映射拒绝、有效/脏/未校验/加载中/失败收据判定、旧数据判定及待下载版本替换/取消。共享的是原生 VSS 收据和 Voxy 作用域，没有另加一套 HTTP 地形文件缓存。

另启动隔离 AsyncYouer + Velocity alpha.2 + NeoForge 开发客户端（VSS0.22.0、Xaero World Map 消费者），确认新的 ModifyArgs 缓存拦截点实际加载，生成持久化身份索引、完成 CDN 会话和真实 HTTP 数据交付，没有 Mixin 注入失败或 CDN 回退。最后正常断开测试玩家并关闭测试进程。此项验证新客户端与旧 server/bridge 的接口兼容，不是完整的 LAN↔CDN 往返数据复用验收；测试环境没有 Voxy，不可证明 Voxy 入库或画面。旧 scoped 桶发现及 I/O 降级补充逻辑由后续集成检查覆盖。

原生链路已经发出的重复字节无法由客户端撤回，本版本保证的范围是有效同版本收据抑制 HTTP、待下载合并、统一缓存身份及防止已知旧版本覆盖。缓存清理、无效/失败收据及世界变化仍可能要求重新传输。

## 0.1.0-alpha.3：Velocity 选择局域网原生 VSS

bridge 新增按玩家来源实际 IP 选择传输的规则，`192.168.0.0/16` 使用原生 VSS，客户端保留身份协商和缓存隔离，不再根据入服目标地址判断。新增 26 项断言，总计 69 项：包括网段首尾、相邻网段、其他内网/回环/IPv6、公网、IPv4 映射、主机名不影响来源分类、未解析/缺失地址，以及 DIRECT_CONFIG 帧、公网旧格式一致、LAN 配置/身份两种顺序、旧会话拒绝和切服时传输选择复位。构建与规则检查通过不代表已完成新版本真实游戏内 LAN 下载验收。

另外以不登录游戏的真实 LAN TCP 连接检查当前 Docker 网络，Velocity 容器内能看到 `::ffff:192.168.*.*` 来源，未统一改写为 Docker 网关。该检查只证明当前测试路径的来源可见，不代表所有 NAT/公网域名回流路径，也不是游戏内 LOD 交付验收。

以下真实客户端传输和切服数据均来自 alpha.2，不能作为 alpha.3 局域网规则的游戏内验收证据。

## 双后端部署与本机整合包实测

安装组合：

| 位置 | 版本 |
| --- | --- |
| 后端 A（main） | AsyncYouer 1.21.1-3f16f9b7 / NeoForge 21.1.264 |
| 后端 B（survive） | Youer 1.21.1-b0e77ed9 / NeoForge 21.1.265，保留既有 C2ME |
| 原版 VSS | NeoForge 0.22.0；服务端使用已验证的 zstd 去重副本 |
| Velocity | 4.2.0 / JDK 25，bridge alpha.2 |
| 本机客户端 | 既有 HMCL 整合包，NeoForge 21.1.252 / JDK 21.0.10，官方 VSS 0.22.0 + CDN client alpha.2 |
| Voxy | 既有 NeoForge 0.2.15-beta |
| CDN | 华为云普通文件下载加速；本次玩家到 CDN 和回源均为 HTTP |

已实际完成：

1. 正常保存世界、部署和重启；三个进程最终均 healthy。新增后端只开放到 Docker 内网，并持久化容器映射及必要的宿主防火墙规则。
2. TCP 鉴权、backend UUID、ping、缺失对象读取正常；两服分别通过源站和 CDN 返回 `410 + no-store`，重复错误请求仍为 MISS。
3. 使用启动器已有微软账号，通过 Velocity 真实登录后端 A → 后端 B → 后端 A。
4. 三次会话均取得 bridge 下发的地址及正确后端/世界/发布代，切回 A 恢复原身份。两个后端使用不同的客户端 VSS 缓存键。
5. 客户端实际交付日志的最后一个千列采样点如下：

| 阶段 | 日志确认的交付次数 | 累计 HTTP 响应体字节 |
| --- | ---: | ---: |
| 首次后端 A | 6,000 | 19,305,287 |
| 后端 B | 8,000 | 53,758,374 |
| 返回后端 A | 4,000 | 8,681,186 |

计数是日志采样下界，可包含同坐标重复交付，不等于唯一地图区块数、无缓存下载量或已完成渲染数量。测试未出现 `CDN paused`、`VSS retry failed` 日志。

6. 两服各取一个刚发布的真实对象，源站与 CDN 的响应体逐字节一致；重复 CDN 请求均出现 `Via: ... TCP_HIT ...`，成功响应保留一年缓存头。样本响应体分别为 424 和 7219 字节。单次缓存请求约 36–38ms，仅记录本次测点，不能推断其他地区性能。
7. 本机 Voxy `WorldIdentifier` 构造方法及公开字段符合隔离适配接口；客户端启动后 VSS 配置迁移为 YAML。测试结束退出账号、关闭本次进程并恢复临时快速入服参数。

**画面验收未完成。** 本机 Apple M3 Pro 报告 OpenGL 4.1，Voxy 明确记录 `Voxy is unsupported on your system` 和禁用渲染。以上测试证明真实客户端下载、回源、缓存命中及会话/缓存键切换，没有证明 Voxy 最终入库成功、画面无洞或视觉上不串世界。仍需在满足 Voxy 要求的 GPU 上验收。

## 仓库构建与发布整理

本次整理不修改三个组件的运行时实现。离线 Velocity SDK 改为显式选项；默认构建使用 Maven。新增分目录安装包、源码/校验清单生成工具及可传入 TCP 地址的只读平台探测任务。具体构建命令及重新验证结果以本次 Release 说明为准。

Gitea 服务当前未启用 Actions，也未配置运行器；仓库提供工作流文件，但没有声称远端 CI 已通过。

## 0.1.0-alpha.2：bridge 直接下发 CDN 配置

本版本把 `cdn.baseUrl` 移到 `plugins/vss-cdn-bridge/bridge.properties`。Velocity 监听客户端 HELLO，直接向该客户端发送 CONFIG，原 HELLO 仍转发到后端；后端的 SESSION 只提供身份，不包含地址。客户端等 CONFIG 和 SESSION 到齐后发 READY，后端收到确认才启用接管。三个组件须同时升级，游戏控制协议为 v2；既有 HTTP/TCP v1 格式和 URL 未改变。

`./gradlew build dist` 成功，**43 项集成检查通过**。在原有 27 项上，新增检查配置与身份的两种到达顺序、缺少任一方时不启用、上一会话配置拒绝、退出后迟到消息不能重新启用、Velocity/NeoForge 控制帧格式、畸形包与非法 CDN 根地址拒绝。

实际使用 alpha.2 的 NeoForge 客户端 → Velocity 4.2.0 → Youer 1.21.1 路径验证：

- bridge 配置地址为 `http://127.0.0.1:25590`；后端故意残留的旧字段为不可用地址 `http://127.0.0.1:1`。
- 后端启动后自动移除了旧字段，身份和发布缓存继续沿用。
- 客户端实际日志确认地址来源为 Velocity bridge，并完成真实 LOD 下载：

```text
CDN session active: ... cdn=http://127.0.0.1:25590 (configured by Velocity bridge)
CDN delivered 1 columns / 7789 HTTP bytes to VSS (last codec=1, raw=43261)
CDN delivered 9000 columns / 56131397 HTTP bytes to VSS
```

再直接连接后端而不经过 Velocity，客户端未收到 bridge CONFIG，5 秒后记录协商超时并恢复原 VSS，游戏连接保持。未出现 CDN 会话启用或 HTTP 下载日志。日志为 `verification/alpha2-client-no-bridge.log` 与 `alpha2-youer-no-bridge.log`。

经 bridge 的成功日志为 `verification/alpha2-client-bridge-config.log` 与 `alpha2-youer.log`。以下保留 alpha.1 的原始链路与故障回退验证，Voxy 渲染及双后端实机切服的未验收范围仍然适用。

## 0.1.0-alpha.1 基线

## 构建与传输测试

JDK 21.0.10，Gradle 9.2.1，NeoForge ModDev 2.0.148。客户端和服务端编译目标 NeoForge 21.1.266 / Minecraft 1.21.1，Velocity 编译 API 3.4.0-SNAPSHOT。

```text
./gradlew build dist
PASS 27 integration assertions
BUILD SUCCESSFUL
```

集成检查实际创建两个 TCP 后端、一个 HTTP 源站和一个模拟 CDN 缓存代理，覆盖：

- 相同字节复用版本，新字节递增版本，旧 URL 内容不可变。
- 重启恢复发布代、计数器和版本；过期后重新发布不会复用旧 URL。
- 相同坐标在两个后端独立路由；响应身份不符、CRC 损坏均被拒绝。
- 主动 TCP 连接和错误密钥拒绝。
- HTTP 成功缓存头、缺失版本 `410/no-store`。
- 两次玩家请求同一个对象，缓存代理仅向源站获取一次。
- 64 KiB codec=1 帧的字节和原始大小经 TCP/HTTP 保持一致；此项是传输测试，随机帧本身不作为有效 zstd 进行解压。
- 超出大小上限的 HTTP 响应取消接收。
- 新版本替换旧下载，切服清理会话后拒绝迟到响应。
- 脏坐标取消使用 VSS 的 x 在高 32 位、z 在低 32 位的打包格式。
- 磁盘限额和过期文件回收。

## 实际平台测试

| 部件 | 实测版本 / 结果 |
|---|---|
| VSS | 官方 Modrinth NeoForge 0.22.0，协议 20 |
| 普通服务端 | NeoForge 21.1.266，成功启动 VSS 与 server 模组，开启独立 TCP 25591 |
| 混合服务端 | AsyncYouer 1.21.1-a58128a6，内置 NeoForge 21.1.249，JDK 25.0.3，成功启动打包后的 server 模组 |
| Velocity | 4.2.0，JDK 25.0.3，成功加载 bridge 插件，独立 HTTP 25590 |
| 客户端 | NeoForge 21.1.266、VSS 0.22.0、client 模组，成功加载、入服、协商身份与 CDN 地址 |
| 实际数据消费者 | Xaero's World Map 1.42.0，VSS 检测并注册地图消费者 |

直接加入 Youer，以及通过 Velocity 的游戏入口加入 Youer，均取得 CDN 会话。通过 Velocity 时，客户端使用带后端/世界/发布代 UUID 的 VSS 缓存桶。

HTTP 数据链路实测记录：

```text
CDN delivered 1 columns / 7789 HTTP bytes to VSS (last codec=1, raw=43261)
CDN delivered 1000 columns / 6236704 HTTP bytes to VSS
CDN delivered 5000 columns / 31318009 HTTP bytes to VSS
```

链路是：真实 VSS 读取/生成及编码 → server 拦截 → 小型引用经 Velocity 转发 → client HTTP → Velocity bridge → bridge 主动 TCP 读取 Youer → client 交回真实 VSS 接收流程。该测试源站未接商用 CDN，不能据此推断商业 CDN 的命中率或实际公网性能；也不能把上述交回 VSS 的计数当作 Voxy 渲染完成计数。

另运行 `PlatformSmokeProbe`，直接连接真实 Youer TCP 端口与真实 Velocity HTTP 端口，验证后端身份、连接鉴权及缺失对象的 `410/no-store` 回源结果。

原始有限日志：`verification/client-direct.log`、`client-velocity.log`、`youer-velocity.log`。

## HTTP 故障回退

把隔离后端的 CDN 地址改为本机临时 HTTP 503 服务，继续通过 Velocity 入服：

```text
CDN paused for this backend session; original VSS will retry: java.io.IOException: HTTP 503
```

未出现 `VSS retry failed`。保持游戏连接后，真实 VSS 的 `/vsslod diag` 输出：

```text
Throughput: sent=5898 (207.9 MB)
Bandwidth: ... 207.9 MB total, 34.6 MB wire, cols zstd=5897 raw=0
```

这验证了 HTTP 故障后会停用接管并恢复原 VSS 地形发送。传输取消调用 VSS 的取消接口，没有调用其解码失败计数接口。日志为 `verification/client-http503.log` 和 `youer-http503.log`。测试结束后停止所有临时服务并恢复测试配置中的源站地址。

## Youer 安装兼容

原版 VSS 0.22.0 内嵌 zstd-jni 1.5.7-3，与本次 Youer 内置的 1.5.7-8 发生 Java 模块重名冲突。`tools/prepare_youer_vss.py` 在新副本中移除重复嵌套依赖及其 JarJar 记录后启动成功。

逐项比较证实 **1120 个 VSS class 条目与官方原件完全一致**。工具不覆盖输入文件，不修改 Youer 自带库；需保留其指定的 1.5.7-8。原件与处理后的 VSS 副本不能同时安装。

## 仍需在目标环境验收

早期隔离测试未安装 Voxy。后续已在现有整合包中运行 Voxy，结果见顶部硬件限制。隔离逻辑对照 `xiaoancute/voxy-neoforge` 的 `neoforge-1.21.1` 分支，提交 `77a8c5384f97628eae745d260cdbe1d3a22d1d54`，并核对本机 JAR 的构造方法及字段；**尚未完成 Voxy 实机画面验证。**

在目标整合包上仍应验证：

1. 两个同维度名、相同种子的 Youer 后端使用不同身份，通过同一个 Velocity 来回切服，确认 Voxy 地形和磁盘缓存互不串用。
2. 下载期间切服、传送维度，确认旧画面和迟到下载不会进入新世界；返回原世界可复用缓存。
3. 改方块并保存，等待原 VSS 脏区块广播，确认新 URL 生效、旧 URL 仍只提供旧数据。
4. 实际 CDN 第二次请求命中缓存；错误响应不缓存；停掉回源后已缓存对象仍可用。
5. 目标 Youer 构建、代理握手组件、Voxy 移植版、其他模组及反作弊共同运行。已完成上述两个真实后端的登录/切服和传输测试；更多核心、反作弊组合及同种子世界的视觉隔离仍需验证。
6. 大地图、多人下载、长期磁盘与文件数量、清理耗时、带宽及计费。当前不是生产压测结果。

因此本版交付为可构建且核心链路已运行的 alpha；应先在测试网络完成上述验收，再决定上线。
