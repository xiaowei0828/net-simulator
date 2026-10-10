# 架构与模块职责

两端实现同一测试目标：应用使用 IPv6 到达虚拟网络，DNS64 在需要时合成 /96 地址，
工具提取对应 IPv4 并在底层网络建立出站连接。原生 IPv6 目的地址直接使用 IPv6 出站。
MTU 为 1500，保持 AAAA 错误后尝试 A 的兼容策略。

## Android

`android/app` 负责界面、VpnService 生命周期、应用选择、底层网络和受保护 socket。
`android/core` 是 Java 库，负责 DNS64、上游 DNS、地址映射、本地 SOCKS 网关及遥测，不是跨平台模块。

```text
App → Android TUN → HevSocks5Tunnel/lwIP → 本地 SOCKS 网关
                                            ├─ DNS64 → 上游 DNS
                                            ├─ 合成 IPv6 → IPv4 socket
                                            └─ 原生 IPv6 → IPv6 socket
```

IPv4 地址族由 VpnService 策略阻断；出站 socket protect 后绑定物理网络，避免进入自身隧道。

## iOS

`ios/App` 保存 VPN 配置、启动与停止、呈现状态；手动诊断和 Debug 自动回归分别放在 `Diagnostics/`、`Debug/`。
`ios/Tunnel` 管理 Packet Tunnel 和 Swift 转发；`ios/Native` 提供串行队列调用的 C/lwIP 桥接。
`ios/Shared` 是 App/扩展内部共享的 Swift 编解码，不含 Android 代码。

```text
App → packetFlow
        ├─ IPv4 → 丢弃
        ├─ IPv6 DNS → DNS64Server → 上游 DNS
        ├─ IPv6 TCP → LabStack/lwIP → NWConnection
        └─ IPv6 UDP → 五元组会话 → NWConnection
```

全流量模式配置 IPv4/IPv6 默认路由和明确的 IPv6 前缀路由，系统服务例外仍由 iOS 控制。
TCP 半关闭、背压和缓冲读取在 provider 串行队列中协调。

## 共享内容及维护边界

- `third_party/` 保留固定版本及上游目录结构。Android 使用 HevSocks5Tunnel，iOS 直接编译其中的 lwIP fork。
- `scripts/bootstrap-native.sh` 是唯一的仓库级依赖引导入口。
- `tools/fixtures/` 放网络端点测试服务，平台脚本保留在各自目录。
- `docs/validation/` 放可审阅的历史报告；构建输出和机器配置仍放平台目录并忽略跟踪。
- 修改 DNS64、NAT64、MTU 或 TCP/UDP 会话语义时，应检查两端对应实现与测试；当前不强制共用业务核心。

两端均为 TCP/UDP 代理式通信路径模拟，不提供完整逐包 NAT64、CLAT 或真实 IPv6-only 链路层。
具体支持范围和限制以 [Android](../android/README.md)、[iOS](../ios/README.md) 文档为准。
