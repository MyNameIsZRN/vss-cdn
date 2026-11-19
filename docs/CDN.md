# HTTP 与 CDN 配置

CDN 的源站是 Velocity bridge 的独立 HTTP 服务。VSS 的请求、更新通知和会话身份继续使用 Minecraft 连接。CDN 命中后直接返回已编码地形字节；未命中时 bridge 主动连接对应后端取对象。

## 域名与回源

| 配置 | 示例 |
| --- | --- |
| 玩家访问域名 | `https://lod.example.net` |
| `cdn.baseUrl` | 同上，必须是根地址，不带路径、查询参数或账号密码 |
| CDN 回源 | `http://origin.example.net:26665` |
| 回源 HOST | `origin.example.net`，按实际源站配置 |
| DNS | 玩家域名 CNAME 指向厂商分配的 CDN CNAME |

HTTPS 需配置真实有效的证书；只有 HTTP 时可以把 `cdn.baseUrl` 配成 `http://lod.example.net`。客户端拒绝重定向，因此声明的地址必须直接提供响应。域名和端口不要指向 Minecraft 游戏监听端口。

## 缓存规则

对 `/lod/v1/` 的成功响应遵循源站：

```http
Content-Type: application/octet-stream
Cache-Control: public, max-age=31536000, immutable, no-transform
ETag: "<发布代UUID>-<递增版本号>"
```

- 以完整路径作为缓存键，保留后端、世界、发布代、维度、坐标和版本。
- 尊重 `no-store`；关闭强制缓存，缺少源站 TTL 时不缓存。
- 错误负缓存关闭。至少检查 `400/404/410/503`；厂商若不能单独配置 410，必须遵守源站的 `no-store`。
- 关闭 Range 回源。当前 bridge 不实现 206；HEAD 仍会完整读取后端对象。
- 关闭智能压缩、内容改写和图片/文本处理，保留 VSS 的现成压缩字节。
- 保留 URL 参数，或在边缘拒绝查询参数。bridge 对任何查询参数返回 `400 + no-store`。
- 不启用会将旧 URL 映射到“当前版本”的重写。当前版本变化必须使用新 URL。

## 华为云已验证配置

选择普通 CDN 的“文件下载加速”，并使用与流量包一致的服务区域及流量计费方式；WSA 另有请求计费。

| 项目 | 已验证取值 |
| --- | --- |
| 缓存规则 | 全文件及目录 `/lod/v1` 均遵循源站、强制缓存关闭、缺省 TTL 0 |
| URL 参数 | 不忽略参数 / `full_url` |
| Range 回源 | 关闭 |
| 智能压缩 | 关闭 |
| 错误码缓存 | 支持配置的错误码 TTL 设为 0；410 依靠 `no-store` |
| 原站协议 | HTTP，端口对应 bridge 独立监听 |

本文不包含任何账号或密钥，也不自动调用厂商管理 API。规则入口和取值应以实际账号界面/API 为准。[华为云全量配置 API](https://support.huaweicloud.com/api-cdn/UpdateDomainFullConfig.html)

## 验证缓存

先让装了 CDN client 的玩家从公网入服，产生实际下载。然后从 CDN 访问日志或测试环境的 HTTP 请求记录中取一个成功对象的完整路径。在可访问源站的机器上，把下面的地址和路径换成真实值，再分别请求源站和 CDN。

```sh
origin='http://origin.example.net:26665'
cdn='https://lod.example.net'
path='/lod/v1/填写从实际请求取得的完整对象路径.bin'

curl -fsS -o origin.bin "$origin$path" &&
curl -fsS -D first.headers -o cdn.bin "$cdn$path" &&
cmp origin.bin cdn.bin &&
curl -fsS -D repeat.headers -o cdn-repeat.bin "$cdn$path" &&
cmp origin.bin cdn-repeat.bin
```

两轮 CDN 请求都应返回 200，且 `cmp` 应通过。再查看 `repeat.headers` 的缓存命中字段。华为云实测 `Via` 中出现 `TCP_HIT`。命中可能发生在中间缓存层，单次请求时延只能反映当次请求。

访问 `/` 返回 400 是 bridge 的正常行为，不表示发布服务不可用。有效但已清理/不存在的对象返回 410；未知后端返回 404；连接池不可用返回 503。错误响应不能代替成功对象的缓存验证。

## 更新与费用边界

世界变化仍由原 VSS 触发请求；新字节取得新版本 URL，无需刷新旧 CDN 对象。旧 URL 保持旧内容，或清理后返回 410。CDN 可能提前淘汰对象，并会在多个节点分别回源，不能承诺“全网只回源一次”。

**生成 LOD、发布到源站、预热 CDN 是三个步骤。** 原生 VSS 缓存有数据后，bridge 仍需要后端完成 HTTP 对象发布。源站已有对象后，可以使用厂商工具预热出生点等热门区域的实际 URL，其余对象按首次访问缓存。仓库没有内置全图发布、OSS 推送或 CDN 预热工具。

共享同一 CDN 域名的多个后端也共享该域名的用量封顶。厂商的流量统计、流量包抵扣和停用都有延迟，不能把日封顶视为精确扣费上限。本项目没有监控流量包余额或自动停用 CDN 的后台任务。[华为云用量封顶说明](https://support.huaweicloud.com/usermanual-cdn/cdn_01_0279.html)
