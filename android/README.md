# IPv6 Lab for Android

Android 本地 IPv6 / DNS64 / NAT64 兼容性测试工具。无需 root、无需远端 VPN 服务器。

第一版支持 Android 10+（API 29），提供 arm64-v8a、armeabi-v7a、x86_64 APK。
使用 Java 原生界面与 VpnService，复用 HevSocks5Tunnel / lwIP 处理用户态 TCP/IP。
这是开发测试版本，targetSdk 34；公开上架前需要更新目标 SDK 并验证对应系统行为。

## 工作方式

```text
被测 App
  └─ Android IPv6-only VPN（IPv4 地址族在系统层阻断）
      └─ HevSocks5Tunnel / lwIP
          └─ 经过认证的本地 SOCKS5 网关
              ├─ 虚拟 DNS → DNS64 → 受保护的上游 DNS socket
              ├─ NAT64 前缀 → 提取 IPv4 → 受保护的 IPv4 TCP/UDP socket
              └─ 原生 IPv6 → 受保护的 IPv6 TCP/UDP socket
```

- VPN 只配置 IPv6 地址、`::/0` 路由及 IPv6 DNS；不调用 `allowFamily(AF_INET)` 或 `allowBypass()`。
- 出站 socket 必须成功 `VpnService.protect()`，并绑定非 VPN 的底层网络，防止转发环路。
- DNS64 保留成功返回的 AAAA；AAAA 为空、返回错误（包括 REFUSED/SERVFAIL/NXDOMAIN）、超时或响应无效时，继续查询 A；A 响应有效且包含可用地址时合成 AAAA，保留 CNAME 链。
- `ipv4only.arpa` 返回本工具的前缀，供 RFC 7050 发现使用。
- DNS 支持 TCP、UDP、EDNS 大小限制及上游 UDP 截断后的 TCP 回退。合成记录不带 AD/RRSIG。
- TCP/UDP 通过同一条路径转发，UDP 可承载 QUIC；不解密 TLS，不安装证书。
- 可选择全设备或指定可启动应用；选择指定应用时，工具自身也纳入 VPN 以便执行自检。
- 运行中显示 DNS、合成记录、NAT64/原生 IPv6 连接尝试、流量及错误计数。日志同步写入 Logcat，界面在内存保留最近 150 条。

默认使用网络专用 ULA 前缀 `fd64:64:64::/96`，适合同时测试公网和私网 IPv4。
虚拟接口为 `fd64:64:64:1::2/64`，虚拟 DNS 为 `fd64:64:64:1::53`，MTU 为 1500。
目前只支持 /96 前缀。若手动使用 `64:ff9b::/96`，应仅用于全球可路由 IPv4 地址。

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

以下命令在 `android/` 目录执行。

需要 JDK 17、Android SDK Platform 34 / Build Tools 34.0.0、NDK 26.1.10909125。

```bash
# 获取锁定版本的原生源代码及其锁定的子模块
../scripts/bootstrap-native.sh

# local.properties 中设置 sdk.dir，或者设置 ANDROID_HOME
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。

可直接用 Android Studio 打开 `android/` 目录。Gradle Wrapper 固定为 8.2，AGP 固定为 8.2.0。
`../third_party/hev-socks5-tunnel` 为可重新下载的依赖目录，不纳入项目源码跟踪。

## 使用

1. 安装 APK，连接 Wi-Fi 或移动网络。
2. 在 Android 系统设置关闭“私人 DNS”，并关闭被测 App 自带的安全 DNS/DoH，以测试本地 DNS64。
3. 保持默认前缀；上游 DNS 留空时采用底层网络的 DNS，也可填入可达的数字 IP。
4. 选择全设备或指定 App，点击“启动测试环境”，接受系统 VPN 授权。
5. 使用“检查 DNS64 与 IPv4 阻断”检查路径，并重启被测 App，减少旧连接和 DNS 缓存影响。
6. 在 App 或常驻通知中停止，恢复原网络。

此工具占用 Android 当前用户的 VPN 槽位，不能同时运行另一个普通 VPN。
选择单个 App 时，未选择的 App 仍使用原网络，不应拿它们判断测试是否生效。
IPv4 在 Android 内核策略处被拒绝，不进入用户态，所以界面不声称统计所有被阻断的 IPv4 包。

## Logcat 诊断

安装最新 APK 后，在复现前开始抓取（Android Studio Logcat 可用 `tag:IPv6Lab` 过滤）：

```bash
adb logcat -v threadtime 'IPv6Lab:I' '*:S' > ipv6lab.log
# 仅导出当前缓冲区中的工具日志
adb logcat -d -v threadtime 'IPv6Lab:I' '*:S' > ipv6lab-snapshot.log
```

普通事件为 Info，转发/解析异常为 Error。日志包含 VPN 启停、测试应用范围、
底层网络编号和 DNS、NAT64/原生 IPv6 连接，以及 DNS 查询的域名、类型、事务 ID、
RCODE、TC、A/AAAA/CNAME 摘要、合成或跳过原因、耗时及 UDP → TCP 回退。
同一次客户端 DNS 请求用 `trace=` 串联，合成时追加的 A 查询也使用同一 trace；
传输日志用 `id=` 对应上游查询，`selectedPhysicalNetwork` 是记录时所选底层网络。
响应摘要反映实际发送的 UDP 截断结果，最多展示 6 条地址或别名记录。

业务 UDP 日志用进程内唯一的 `UDP flow=` 串联，包含虚拟 IPv6 目的地址和实际出口目的地址：

- `event=open`：开始创建映射；`event=first-send`：首个数据报已交给出口 socket，包含负载字节数及本地地址，不能证明远端收到。
- `event=first-receive`：出口 socket 收到首个远端数据报；`event=first-relay`：首个回包已交给本地 SOCKS relay，不能证明应用已经收到。
- `event=progress`：有收发活动时至多每秒一条累计统计；`event=close`：映射关闭时的最终统计和原因（控制连接关闭、VPN 停止、30 秒无回包或错误）。
- `txPackets/txBytes` 为成功交给出口 socket 的包数/负载字节数，`rxPackets/rxBytes` 为出口收到的包数/负载字节数，`relayPackets/relayBytes` 为成功交给本地 relay 的回包数/负载字节数。
- `event=error` 包含 `phase=setup/send/receive/relay`、异常类型和当时累计值；`elapsedMs` 从映射创建起按单调时钟计时。没有回包时会明确显示 `rxPackets=0`。

这些统计不包含 IP/UDP/SOCKS 头，不记录业务负载；虚拟 DNS 请求仍使用上面的 DNS trace 日志。
若只抓取很短一段日志，建议复现结束后停止测试 VPN，以获取所有仍存活 UDP 映射的关闭汇总。

`ipv4only.arpa` 自检由本地直接回答，不访问上游；自检通过不能证明业务域名的上游解析正常。
日志包含域名及 IP，但不记录业务报文、SOCKS 密码或任意 TXT 内容，不主动上传。
Logcat 保留时间由系统缓冲区决定；持续抓取可保留复现全过程。

## 测试

主机协议测试：

```bash
./gradlew :core:test
# 报告：core/build/reports/tests/test/index.html
```

模拟器集成测试使用主机本地 TCP 18080 / UDP 18081 回显服务，不依赖公网可用性：

```bash
# 先启动一个专用 Android 14 模拟器。此脚本仅接受 emulator-* 序列号。
./scripts/test-emulator.sh emulator-5580
```

脚本构建并安装主 APK 和测试 APK，在指定模拟器上授权 VPN 后运行测试。
它会覆盖该模拟器的工具配置、替换当前 VPN；请使用专用测试模拟器。
覆盖 DNS64 的 TCP/UDP TUN 路径、Android 系统解析器、TCP/UDP NAT64 到同一主机服务、
直接 IPv4 无法访问同一服务、连续停止与重启。结果写入 `.test-device/instrumentation.txt`。

真机回归还需覆盖：Wi-Fi/蜂窝切换、锁屏、长连接、撤销 VPN 权限、多厂商系统、
真实 AAAA-only 服务、HTTP/3、私人 DNS 及真实 IPv6-only Wi-Fi 对照。

本次构建与模拟器测试结果见 [验证记录](../docs/validation/android/2026-09-30.md)。

## 精度与已知边界

- **这是应用通信路径模拟。** NAT64 部分通过用户态 TCP 终止与 socket 转发实现，
  不是 RFC 6146 的逐包转换器；TCP 拥塞控制、ICMP 错误、扩展头、分片及 PMTU 不能视为真实 NAT64 网关的等价实现。
- **保留原生 AAAA。** 底层只有 IPv4 时，纯本地工具不能提供原生 IPv6 出口。
  这类目的地可能失败，界面会显示底层 IPv6 路由提示；有默认路由也不等于公网 IPv6 可达。
- 不实现 CLAT/464XLAT，不自动修复 App 的 IPv4-only socket，也不自动把原生 AAAA 改走 A。
- 仅虚拟 DNS 地址上的 TCP/UDP 53 请求经过 DNS64。应用直连其他 DNS、DoH/DoT、DNSSEC 本地验证不保证兼容。
  DO+CD 查询仍不合成；工具自身不是 DNSSEC 验证器。AAAA 错误后尝试 A 合成是本工具的兼容策略。
  A 查询也失败或没有可用地址时不合成；A 响应错误/截断/无地址时保留原 AAAA 响应，A 传输或校验失败时返回 SERVFAIL。
  当前没有 DNS 缓存、DNSSEC 验证和完整 RFC 6147 的所有特殊场景。
- 不模拟链路层 RA/DHCPv6、运营商 CLAT、IPv6-only 硬件接口枚举状态。不支持入站服务、组播和链路本地发现。
- ICMP echo 模拟关闭，避免把未访问远端的 ping 显示成成功。
- 工具存活而原生网络栈退出时保留 TUN 并显示错误，避免静默回退；
  用户主动停止、系统撤销 VPN 或整个进程被杀时，系统可恢复底层网络。
  本版本未提供 always-on/lockdown 保证，不能作为强制网络隔离工具。
- 网络切换后新连接使用新底层网络，已有连接可能失败并需要 App 重试，不支持会话迁移。
- 当前资源上限：原生 64 个会话、Java 网关 192 个工作线程、每个 UDP association 32 个目的映射；
  TCP 120 秒空闲超时，UDP 30 秒无入站响应过期。这些上限可能影响压力测试和低频长连接。
- 本地事件日志包含 DNS 域名及目的 IP/端口，不记录报文正文，不上传日志。DNS 查询发给所配置的上游解析器。

## 源码

- `app/`：界面、VPN 服务、底层网络选择与受保护 socket、Android 集成测试。
- `core/`：NAT64 /96 映射、DNS64、上游 DNS 传输、本地 SOCKS5 网关和协议测试。
- `scripts/`：Android 模拟器测试；共享依赖引导位于 `../scripts/`。
- [THIRD_PARTY.md](../THIRD_PARTY.md)：固定版本与许可证。

## 设计依据

- [Android VpnService](https://developer.android.com/develop/connectivity/vpn)
- [Android 地址族默认阻断](https://developer.android.com/reference/android/net/VpnService.Builder#allowFamily(int))
- [VPN 的 systemExempted 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types#system-exempted)
- [DNS64 RFC 6147](https://www.rfc-editor.org/rfc/rfc6147.html)
- [NAT64 前缀发现 RFC 7050](https://www.rfc-editor.org/rfc/rfc7050.html)
- [HevSocks5Tunnel](https://github.com/heiher/hev-socks5-tunnel)

AGP 8.2 的 Lint 对 `systemExempted` 只检查闹钟类权限，未识别已授权 VPN 的独立资格，
因此仅对该服务标注局部抑制及原因，没有添加无关闹钟权限或关闭全局检查。

