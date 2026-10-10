# 公共网络测试服务

以下命令从仓库根目录执行。脚本只依赖 Python 3 标准库，Ctrl-C 停止。

| 脚本 | 监听 | 用途 |
| --- | --- | --- |
| `stream-echo.py` | 127.0.0.1 TCP 18080 / UDP 18081 | 立即回显每次读取，供 Android 模拟器经主机别名访问 |
| `nat64-fixture.py --address IPv4` | 所有接口 TCP 18080 / UDP 18081，DNS TCP/UDP 1053 | 真机 LAN 回归；TCP 收到 FIN 后回显，DNS AAAA REFUSED、A/CNAME 及 TC 回退 |
| `udp-echo.py` | 所有接口 UDP 18081 | 早期 UDP 探针的最小回显服务，保留用于复现旧实验 |

```bash
python3 tools/fixtures/stream-echo.py
# 或：手机和 Mac 需在可互访的局域网，address 为 Mac 的 IPv4。
python3 tools/fixtures/nat64-fixture.py --address YOUR_MAC_LAN_IPV4
```

这些服务使用相同回显端口，每次只启动一个。
`nat64-fixture.py` 不是递归 DNS：只回答测试后缀，其他域名返回 NXDOMAIN。
两种 TCP 服务的回显时机不同，保留各自协议以确保测试含义不变。

Android 脚本 [test-emulator.sh](../../android/scripts/test-emulator.sh) 自动管理 stream echo 的生命周期。
iOS 真机运行与报告导出命令见 [iOS 文档](../../ios/README.md)。
