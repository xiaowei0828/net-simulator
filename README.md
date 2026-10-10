# IPv6 Lab

使用本地 VPN、DNS64 和 TCP/UDP 转发，模拟应用访问 IPv6-only 网络的通信路径。
支持 Android 和 iOS，可配置域名到指定 IPv4 的映射并返回合成 AAAA；无需远端 VPN 服务器。默认 NAT64 前缀 `fd64:64:64::/96`，MTU 1500。

| 平台 | 工程入口 | 实现 | 使用与构建 |
| --- | --- | --- | --- |
| Android 10+ | `android/`，Android Studio / Gradle | Java、VpnService、HevSocks5Tunnel/lwIP | [Android 文档](android/README.md) |
| iOS 16.4+ | `ios/IPv6Lab.xcodeproj`，Xcode | Swift、Packet Tunnel、C/lwIP | [iOS 文档](ios/README.md) |

## 开始构建

在仓库根目录获取固定版本的原生依赖：

```bash
./scripts/bootstrap-native.sh
```

Android（JDK 17、SDK 34、NDK 26.1.10909125）：

```bash
cd android
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`（相对仓库根目录）。
SDK 路径配置在 `android/local.properties` 或 `ANDROID_HOME`。

另一个终端从仓库根目录执行 iOS 协议检查及设备构建：

```bash
./ios/scripts/test-core.sh
./ios/scripts/build-device.sh YOUR_TEAM_ID YOUR_DEVICE_UDID
```

iOS 需要有 Packet Tunnel capability 的开发团队，签名配置见平台文档。

## 目录

```text
android/            Android 工程、Java core、平台测试脚本
  app/              界面、VPN 服务、设备集成测试
  core/             Java DNS64/NAT64 网关及协议测试
ios/                Xcode 工程和 iOS 实现
  App/              界面、VPN 管理、手动自检与 Debug 自动回归
  Tunnel/           Packet Tunnel、DNS64、TCP/UDP 转发
  Shared/           App 与扩展共享的 Swift 报文编解码
  Native/           C/lwIP 桥接与配置
  Tests/            Swift 主机协议检查
  scripts/          工程生成、签名构建、主机测试
third_party/        固定版本的共享原生依赖
scripts/            仓库级依赖引导
tools/fixtures/     可复用的 DNS、TCP、UDP 测试服务
docs/               架构与按平台归档的验证结果
```

`android/core` 是 Java 模块；`ios/Shared` 只在 iOS App 与扩展间共享。
两端当前共享原生依赖和测试服务，各自维护平台网络生命周期。

## 测试与文档

- [架构与模块职责](docs/architecture.md)
- [公共测试服务](tools/fixtures/README.md)
- [验证记录索引](docs/validation/README.md)
- [第三方依赖及许可证](THIRD_PARTY.md)

## 模拟边界

工具在用户态终止 TCP 后建立出站连接，不是完整的逐包 NAT64 网关。
原生 AAAA 需要底层 IPv6 出口；不提供 CLAT，不模拟链路层 RA/DHCPv6，也不等价于真实 IPv6-only 物理接口。
DNSSEC、应用内 DoH/DoT、系统绕行及各平台支持限制见平台文档。
