# iOS IPv6 Lab

SwiftUI App + `NEPacketTunnelProvider` + C/lwIP 的本地 IPv6 测试工具。MTU 为 **1500**。
通过公共 `packetFlow` 接口读写 IP 包，无私有 utun 文件描述符，无远端 VPN 服务依赖。

## 当前实现

- 隧道仅配置 IPv6 地址 `fd64:64:64:1::2/128`，`ipv4Settings=nil`，不配置隧道 IPv4 地址或 IPv4 路由。
  默认全流量模式保留 `includeAllNetworks=true`、`enforceRoutes=true`、`excludeLocalNetworks=false`，IPv6 TCP/UDP 本地转发。
  不配置 IPv4 本身不能证明没有绕行，需结合下述 AF_INET 真机回归；限定前缀模式仅用于诊断，不阻断物理 IPv4。
- 显式保留 `2000::/3`、`fc00::/7`、`fd64:64:64::/96` 和虚拟 DNS `fd64:64:64:1::53/128` 路由。
  真机实验中仅 IPv6 默认路由不足以让这些测试请求进入预期路径。
- DNS64：虚拟 DNS 的 UDP/TCP 53 → 物理网络上游 DNS，也支持手动指定 IP。
  启动时在安装隧道 DNS 设置前用系统 `res_ninit` / `res_getservers` 读取默认 DNS，优先 IPv4，其次 IPv6。
  读取不到可用上游则启动报错，不回退到公共 DNS；过滤本工具的虚拟 DNS，避免转发环路。
  AAAA 无可用结果、RCODE 错误或传输失败后查询 A，按 CNAME 链合成 AAAA；A 也失败时不伪造成功。
  上游 UDP 响应 TC=1 时重试 TCP；校验事务 ID、问题、响应结构和 CNAME 链，限制 DNS 报文和并发。
  App 的 AAAA 查询可触发 A→AAAA 合成；独立 A 查询仍返回 A，不把 A 问题改成 AAAA 答案。
  保留原生 AAAA；DO+CD 查询不合成。`ipv4only.arpa` 支持前缀发现。
- NAT64 前缀目的地映射到 IPv4 出站；原生 IPv6 目的地用 IPv6 出站。
  TCP 使用现有固定版本 lwIP fork 终止用户态连接，Swift `NWConnection` 转发字节流，支持半关闭和背压。
  UDP 为每个五元组保留独立出站 socket，连续收发时保留映射，支持最大 1452 字节负载。
- 资源上限：64 个 TCP 流、128 个 UDP 流、32 个 DNS 查询；TCP 空闲 120 秒、UDP 空闲 30 秒回收。
- App 提供启动/停止、上游 DNS 配置、回显/HTTPS 自检和扩展诊断。
  日志使用统一日志 subsystem `dev.ipv6lab.ios`、category `PacketTunnel`，包含 DNS 查询、回退、合成、
  连接目标、关闭原因、收发字节数和 IPv4 丢弃计数。DNS 使用 trace 关联 client、upstream、synthesized、downstream，
  区分 `purpose=client` 与 `purpose=dns64-A`；逐条输出完整答案（不再限前 8 个地址），合成记录附 `embeddedIPv4`。
  downstream 显示实际下发的类型、答案数、TC、传输方式与字节数，UDP 截断和 TCP 重试分别记录。
  若收到 IPv4 包，最多记录前 128 个不同流的源/目的 IP、端口、协议和分片偏移，其后汇总计数。
  诊断保留最近 100 条事件，不记录业务报文正文；完整历史需要采集统一日志。

## 使用

下文命令除特别说明外在 `ios/` 目录执行。

1. 用有 Packet Tunnel capability 的团队签名安装，允许系统添加 VPN 配置。
2. 上游 DNS 留空，默认自动读取物理网络 DNS；也可手动填写 IPv4/IPv6 DNS。选择“全流量 IPv6 测试”并启动。
   旧版本的默认 `1.1.1.1` 配置自动迁移为自动选择；升级后再次手动填写该地址会作为显式配置保留。
   日志 `DNS selection source=physical` / `started ... dnsSource=physical` 显示实际选择。
   切换 Wi-Fi/蜂窝网络后需停止再启动测试，以重新读取 DNS；当前不会在运行中重新读取隧道覆盖后的 DNS。
3. 打开待测应用进行测试，通过“读取扩展诊断”查看 DNS/转发结果。
4. 测试结束点击“停止测试”。工具运行期间，使用 IPv4 socket 的应用可能无法通信。

可选的 Mac 回显服务器只用于自检，普通使用不依赖 Mac。
手机和 Mac 需处于可互访局域网；首次局域网访问需允许系统权限。

```bash
python3 ../tools/fixtures/nat64-fixture.py --address YOUR_MAC_LAN_IPV4
```

脚本提供 TCP 18080、UDP 18081、测试 DNS 1053。App 中填入 Mac IPv4 后可运行回显按钮。
测试 DNS 只服务测试域名，不能作为日常递归解析器。

## 域名指定地址

在配置界面的“域名指定地址”中每行填写一条映射，例如：

```text
pull-f5-sg01.tiktokcdn.com:170.187.221.24
another.example.com:192.0.2.10
```

- 支持英文或中文冒号、空行、两侧空格；域名大小写和末尾一个点会归一化。
- 按客户端查询的完整域名匹配，不支持通配符，不匹配子域名，也不沿上游 CNAME 查找映射。中文域名使用 Punycode。
- AAAA 查询直接返回指定 IPv4 按当前 NAT64 前缀合成的地址，优先于上游原生 AAAA；A 查询返回指定 IPv4。命中后不访问上游 DNS。
- 对映射域名的其他普通 IN 查询（包括 HTTPS/SVCB）返回 NOERROR 空答案，避免别名或地址提示改变目的地；AXFR/IXFR 等原有禁止的请求仍被拒绝。
- 映射响应 TTL 为 60 秒；这是显式本地覆盖策略，即使查询带 DO+CD 也使用映射，响应不声称通过 DNSSEC 验证。
- 最多 128 条；重复域名、非法 IPv4、回环/组播等不可转发地址会提示具体错误行。`ipv4only.arpa`、`echo.ipv6lab.test` 保留给工具自检。
- 启动测试时保存配置；运行中不能编辑。停止后修改，再启动生效；删除对应行取消映射，全部清空则关闭覆盖。系统和 App 已缓存的地址、已建立连接可能仍被使用，建议重启待测 App，必要时等待旧缓存过期。
- 仅影响经过本工具虚拟 DNS 的请求；不能覆盖 App 自带 DoH/DoT、硬编码 IP 或绕过 VPN 的查询。URL、Host、TLS SNI 保持原域名，工具不改写业务请求。

默认前缀下，示例 IPv4 合成为 `fd64:64:64::aabb:dd18`。日志搜索 `override name=` 可看到命中的域名、查询类型、指定 IPv4 和合成 IPv6。

## 构建

Xcode 16.1 / iOS SDK 18.1，最低 iOS 16.4。无第三方 Swift 包；C 栈复用仓库已有 lwIP fork。
新工作目录先在仓库根目录执行 `./scripts/bootstrap-native.sh` 获取固定版本源码。

```bash
./scripts/test-core.sh
# 修改生成逻辑或源文件列表后才需要重新生成；保留两个 target 的 Team 和 Bundle ID。
python3 scripts/generate-project.py
./scripts/build-device.sh YOUR_TEAM_ID YOUR_DEVICE_UDID
```

打开 `IPv6Lab.xcodeproj`，选择 `IPv6Lab` scheme。
App 和 PacketTunnel 两个 target 都要设置有效开发团队、Bundle ID、包含设备的 provisioning profile，
扩展 Bundle ID 必须为 App ID 加 `.PacketTunnel`。lwIP 许可证嵌入 App 和扩展的资源。

## 真机回归

Debug App 提供显式自动回归入口，普通启动及 Release 不自动运行。
在 Mac 启动上述 fixture 后，安装、启动并读取报告：

```bash
xcrun devicectl device install app --device YOUR_DEVICE_ID build-device/Build/Products/Debug-iphoneos/IPv6Lab.app
xcrun devicectl device process launch --device YOUR_DEVICE_ID --terminate-existing \
  dev.ipv6lab.ios --probe-echo YOUR_MAC_LAN_IPV4 --resolver YOUR_MAC_LAN_IPV4:1053
xcrun devicectl device copy from --device YOUR_DEVICE_ID \
  --domain-type appDataContainer --domain-identifier dev.ipv6lab.ios \
  --source Documents/device-probe.json --destination build-device/device-probe.json
```

等待 `finished=true` 并逐项检查结果；不能把文件存在或测试跑完当作全部通过。
回归使用明确的 `AF_INET` socket（避免 Network.framework 自动地址转换），先验证 VPN 外 IPv4 TCP/UDP 及绑定 Wi-Fi 的 UDP 可达，再跑两轮 VPN 启停、DNS UDP/TCP、44/1452 字节 UDP、
同会话五次 UDP、系统解析器、32 KiB TCP 半关闭、AAAA REFUSED 后 A 查询及 UDP→TCP 回退、
IPv4 UDP/TCP 阻断和绑定 Wi-Fi 的 IPv4 阻断。
测试会修改本工具配置并替换活动 VPN，完成后停止 VPN；使用 1053 测试解析器后恢复自动选择物理 DNS。

公网检查把启动参数改为 `--probe-echo YOUR_MAC_LAN_IPV4 --system-dns --public-check`。
仍需本地 echo 服务；上游 DNS64 边界检查换成真实 HTTPS 请求。公网端点或底层网络失败需结合基线分析。

## 精度与限制

- 这是应用通信路径模拟，TCP 终止后重新建立连接，**不是完整 RFC 6146 逐包 NAT64 网关**。
  不能据此验证真实链路的 TCP 拥塞控制、PMTU、ICMP 错误或逐包行为。
- 原生 AAAA 必须有可用的底层 IPv6 出口；本地工具无法为 IPv4-only 物理网络凭空提供 IPv6 互联网。
  不实现 CLAT，不把原生 AAAA 自动改走 A。HTTPS/SVCB 的地址提示、应用自带 DNS、DoH/DoT 不保证兼容。
- 当前不支持 IPv6 扩展头、分片、ICMP echo、组播、链路本地发现及入站服务。
  UDP 超过 1452 字节会被丢弃。没有完整 DNSSEC 验证和全部 RFC 6147 特殊处理。
- 无隧道 IPv4 地址模式依赖 iOS 全流量策略限制物理 IPv4 绕行，收到的 IPv4 包仍防御性丢弃。
  iOS 系统服务有例外，单机回归通过不代表所有应用、系统版本或绕行方式均被隔离。
  停止 VPN 或系统终止扩展后，系统可恢复普通网络。不要把此工具当作网络安全隔离边界。
- 尚未完整验证蜂窝切换、锁屏、长时间高负载、第三方 App QUIC 和不同 iOS 版本。
  网络改变时旧连接可能失败，应用需重连；不提供会话迁移。
- Apple [TN3120](https://developer.apple.com/documentation/technotes/tn3120-expected-use-cases-for-network-extension-packet-tunnel-providers)
  将本地代理转发和全系统 DNS 截获列为 Packet Tunnel 不受支持的用途。当前属于开发测试实验；
  真机可运行不等于官方支持或上架保证。
  [路由文档](https://developer.apple.com/documentation/networkextension/routing-your-vpn-network-traffic)
  说明 `includeAllNetworks` 仍有系统例外；
  [TN3134](https://developer.apple.com/documentation/technotes/tn3134-network-extension-provider-deployment)
  说明 iOS per-app VPN 需要受管理设备，此版本没有 Android 式自由应用选择。

## 验证记录

[真机验证记录及原始报告](../docs/validation/ios/README.md)按日期保存在 `docs/validation/ios/`。

## 源码职责

- `App/IPv6LabApp.swift`、`App/LabView.swift`：启动入口和界面。
- `App/VPNModel.swift`：配置持久化、VPN 生命周期和状态。
- `App/Diagnostics/VPNModel+Checks.swift`：界面可用的 DNS、TCP/UDP、HTTPS 自检。
- `App/Debug/DeviceRegression.swift`：仅 Debug 编译的命令行自动真机回归。
- `Shared/PacketCodec.swift`：地址转换和 IPv6 UDP 编解码，供 App、扩展和主机测试使用。
- `Tunnel/`、`Native/`：DNS64、用户态 TCP 栈和转发。
