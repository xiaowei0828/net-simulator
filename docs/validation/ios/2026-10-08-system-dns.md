# 2026-10-08 iOS 自动选择物理 DNS

## 修改

- 默认上游改为启动隧道前读取的系统默认 DNS，使用公开的 `res_ninit` / `res_getservers`。
- 优先选择物理 IPv4 DNS，否则使用 IPv6 DNS；保留 IPv6 链路本地地址的 scope。
- 无可用 DNS 时启动失败并说明原因，不使用固定公共 DNS。排除本工具虚拟 DNS/NAT64 地址，避免循环转发。
- 空配置表示自动；旧配置中内置默认 `1.1.1.1` 迁移为自动。自定义配置保留；新版本显式输入 `1.1.1.1` 仍可使用。
- 诊断包含 `dnsSource`、`physicalDNS`、`resolver`、`resolverPort`；查询日志包含传输协议和目标地址族。
- 每次 VPN 启动重新读取；物理网络切换后需手动停止、重启测试。尚未实现运行期间的 DNS 更新，也不复刻系统按域名选择 resolver 的所有策略。

## 已完成验证

- 主机协议和 DNS 选择/迁移检查：25/25 通过。
- iOS Debug 真机签名编译：`BUILD SUCCEEDED`，Team `ZL7YRX7GX9`。
- App 与 PacketTunnel 的 `codesign --verify --deep --strict` 均通过。
- 安装到 iPhone 11 Pro 成功。

## 真机运行待验证

20:18–20:20 两次尝试启动均被 iOS 拒绝，自动回归未启动。
设备日志显示 `SBAppProfileNeedsValidationAlertItem`，以及
`validation failed because of missing trust and/or authorization (0xe8008026)`。
新旧版本使用相同 provisioning profile、Team 和 Packet Tunnel entitlement；描述文件未过期。
需要在设备端恢复开发者描述文件验证后才能验证物理 DNS 的实际读取与请求链路。
设备已有的 `device-probe.json` 属于之前的构建，不能作为本次通过证据。

## 构建追溯

输出：`ios/build-system-dns/Build/Products/Debug-iphoneos/IPv6Lab.app`。
构建日志：`ios/build-system-dns.log`；启动失败日志：`ios/build-system-dns/launch-syslog.log`。

- `IPv6Lab.debug.dylib` SHA-256：`74128f9cf5a581b5a2cf230fcd776012f862ba729f8646ae576c4cf77efe7b77`
- `PacketTunnel.debug.dylib` SHA-256：`9adfc1ace2e741cf30b51c9d92c9c23e9438b2205997e4160f6b9750bafc863d`
