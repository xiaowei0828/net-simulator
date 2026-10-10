# 2026-10-08 目录迁移后重新编译与真机回归

本次于北京时间 19:41–19:44，使用迁移后的代码重新编译，安装并运行于连接的 iPhone 11 Pro / iOS 18.5。
构建为 Debug 0.2.0，App ID `dev.ipv6lab.ios`，MTU 1500。未修改业务逻辑。

| 检查 | 结果 |
| --- | --- |
| 新构建目录中的 iOS 签名编译 | BUILD SUCCEEDED |
| 安装到 iPhone 并启动自动回归 | 成功 |
| 主机协议检查 | 18/18 通过 |
| 两轮受控服务回归 | 28/28 通过，另含两份扩展诊断 |
| 两轮公网回归 | 31/31 通过，另含两份扩展诊断 |
| 无自动回归参数的普通启动 | 成功 |

## 验证内容

- DNS UDP/TCP、系统解析器、AAAA REFUSED 后查询 A/CNAME 合成、上游 UDP 截断后 TCP 回退。
- 44/1452 字节 UDP、同一 UDP 会话五次回显；TCP 32 KiB 半关闭回显。
- VPN 外 IPv4 TCP/UDP 可达基线；VPN 内 IPv4 TCP/UDP 阻断，以及显式绑定 Wi-Fi 的 IPv4 阻断。
- 停止与重新启动 VPN，两轮均通过。
- `ipv4.icanhazip.com` 经 DNS64/NAT64、`example.com` 经原生 IPv6，各两轮 HTTPS HTTP 200。
  原生 IPv6 出站证据见公网第二轮扩展诊断中的 `2606:4700:10::ac42:93f3:443`。

原始报告保留新的 runID 和时间戳，不覆盖迁移前的结果：

- [受控服务报告](2026-10-08-layout-device-controlled.json)
- [公网报告](2026-10-08-layout-device-public.json)

测试结束时两份报告均为 `finished=true`、`vpnStatus=VPN 未连接`。
临时 Mac fixture 已停止，新 App 已无测试参数重新启动，供手动使用。
当前没有连接 Android 设备，本次没有重新运行 Android；此前 Android 构建验证见 [目录迁移报告](../2026-10-08-layout.md)。
这些结果只覆盖上述检查，不代表长期高负载、网络切换或所有第三方应用都已验证。

## 构建追溯

本机输出：`ios/build-recheck/Build/Products/Debug-iphoneos/IPv6Lab.app`。
构建日志：`ios/build-recheck.log`。下面为运行代码的 SHA-256：

- `IPv6Lab.debug.dylib`：`f09e56cfa1f9610f8b9c6734d3d1e81e9c71ef07408ea7edb27060547c2023d4`
- `PlugIns/PacketTunnel.appex/PacketTunnel.debug.dylib`：`53427dff19d1e191993366f0d8b505f984e4b084869d2219184ba58245dd677b`
