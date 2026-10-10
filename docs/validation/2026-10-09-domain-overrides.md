# 域名映射：两端安装与真机验证

2026-10-09 20:46–20:48（Asia/Shanghai）。

## 安装与配置

- iPhone 11 Pro：安装签名 Debug App 和 Packet Tunnel 扩展成功。
- Android 23116PN5BC（设备 fa9adfc）：安装主 APK 成功；测试 APK 首次被系统取消，用户确认解锁后重试成功。
- 两端保留映射 `pull-f5-sg01.tiktokcdn.com:170.187.221.24`；默认前缀 `fd64:64:64::/96`。
- 验证结束两端停止 VPN，重新打开主 App。Android 通过读取本工具 SharedPreferences 确认映射已持久化；iOS 启动经过 saveToPreferences / loadFromPreferences，扩展诊断确认读取到映射。

## 实测结果

两端都通过实际 VPN 虚拟 DNS，分别验证 UDP 和 TCP 下的三种查询，共每端 6 种组合：

| 查询 | 结果 |
|---|---|
| A | 唯一地址 170.187.221.24，TTL 60 |
| AAAA | 唯一地址 fd64:64:64::aabb:dd18，TTL 60 |
| HTTPS（type 65） | NOERROR 空答案 |

同时校验 DNS 事务 ID、问题、响应；命中映射不查询该域名的上游。
iOS 另有启动与停止检查，8 项均 PASS；Android 单个集成测试内部覆盖以上六种组合、配置持久化与停止检查，JUnit 返回 OK (1 test)。
这是域名覆盖功能的真机 DNS 路径验证，未执行指定 CDN 的播放器拉流或证明该 CDN 节点业务可用。

## 原始结果

- iOS runID：`5DC7704D-5B7C-4E6E-9C6B-B2457518A7DA`，结束时间 `2026-10-09T12:46:48Z`。
- [iOS 逐项报告及扩展诊断](ios/2026-10-09-override-device.json)
- [Android instrumentation 结果](android/2026-10-09-override-device.txt)

## 构建 SHA-256

- `android/app/build/outputs/apk/debug/app-debug.apk`：`e4ae99424c1fe9c9bde66cead061abeb21d5a263b89af22f7ca5fa4d733a0dbf`
- `ios/build-domain-overrides/Build/Products/Debug-iphoneos/IPv6Lab.app/IPv6Lab.debug.dylib`：`b58d1b1df41d1bf6d9b82911b66e0148b1518f105e10f5cbb1191edb04f704d5`
- `ios/build-domain-overrides/Build/Products/Debug-iphoneos/IPv6Lab.app/PlugIns/PacketTunnel.appex/PacketTunnel.debug.dylib`：`2465178e20e5ef1fcbd8ecbee0c8aff97cdd918259d3fa6dfaa179913ad92b21`
