# iOS 验证记录


2026-10-08，iPhone 11 Pro / iOS 18.5，开发签名与 Packet Tunnel 权限有效。
早期限定 DNS/UDP 探针结果保存在 `2026-10-08-first-run.json` 和 `2026-10-08-repeat-run.json`。
完整模式初次路由失败及修正后发现的 TCP 关闭竞态，也保留原始报告，不覆盖失败证据。
最终构建 Debug/Release 均签名编译成功，本机协议检查 **18/18** 通过。

受控服务两轮回归 **28/28** 检查通过（含 2 个 IPv4 基线，不计诊断快照）：
[原始报告](2026-10-08-full-final.json)。覆盖 AAAA REFUSED → A/CNAME → 合成 AAAA、上游 UDP 截断后 TCP 回退，及 TCP/UDP/IPv4 阻断。

公网两轮回归 **31/31** 检查通过（含 3 个无 VPN 基线，不计诊断快照）：
[原始报告](2026-10-08-public-final.json)。
`ipv4.icanhazip.com` 通过 DNS64 合成地址转发到 IPv4，两轮 HTTP 200；
`example.com` 保留上游原生 AAAA，通过 `2606:4700:10::/…` 的 IPv6 TCP 出站，两轮 HTTP 200。
TCP 32 KiB 半关闭、连续 UDP、IPv4 TCP/UDP 及绑定 Wi-Fi 的阻断、停止重启也通过。
这证明当前设备与网络上的这些路径可用，不替代长期稳定性和其他 App 的专项验证。

修复了两类真机发现的问题：
1. 只设置 IPv6 默认路由时，ULA/NAT64 及公网原生 IPv6 请求没有进入预期路径；显式路由后通过。
2. `NWConnection` 终止状态可能早于缓冲数据/EOF 交付。已就绪连接改为先完成 receive，避免提前销毁 TCP 流导致丢尾包。
   修正后四次受控 32 KiB 半关闭均记录 `txBytes=32768 rxBytes=32768`，公网复测也通过。

最终设备保留已安装的 Debug 0.2.0 构建；回归结束 `vpnStatus=VPN 未连接`，临时 Mac fixture 已停止。

平台使用说明见 [iOS README](../../../ios/README.md)。以上报告属于对应时间的构建；后续目录迁移检查单独记录。

## 目录迁移后复测

2026-10-08 晚间重新编译、安装与运行：主机 18/18、受控真机 28/28、公网真机 31/31 全部通过。
[本次报告](2026-10-08-layout-device.md)包含新 runID 的原始数据与构建哈希。

## 自动选择物理 DNS

[修改与验证记录](2026-10-08-system-dns.md)：主机 25/25 通过、签名编译及安装成功；
设备提示开发者描述文件需要验证，本次真机运行尚未通过。

## 无隧道 IPv4 地址

[2026-10-08 21:07 真机验证](2026-10-08-no-ipv4.md)：删除隧道 IPv4 地址，主机 31/31、公网真机两轮 32/32 通过；物理 DNS 10.1.5.1。
明确使用 AF_INET 的 TCP/UDP 及绑定 Wi-Fi UDP 在 VPN 内均返回 ENETUNREACH，VPN 外基线均可达。新版本已安装，验证结束 VPN 已断开。
