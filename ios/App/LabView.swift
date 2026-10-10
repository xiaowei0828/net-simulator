import SwiftUI

struct LabView: View {
    @StateObject private var model = VPNModel()
    var body: some View {
        NavigationStack {
            Form {
                Section("验证范围") {
                    Text(model.status)
                    Text("隧道仅配置 IPv6 地址，全流量模式限制绕行；IPv6 经本地 TCP/UDP 转发。原生 IPv6 需要物理网络可达。")
                        .font(.footnote)
                }
                Section("网络配置") {
                    Toggle("全流量 IPv6 测试",isOn:$model.fullTunnel).disabled(model.active || model.busy)
                    TextField("上游 DNS IP（留空自动选择）",text:$model.resolver).keyboardType(.asciiCapable).textInputAutocapitalization(.never).autocorrectionDisabled().disabled(model.active || model.busy)
                    Text("默认读取启动前的物理网络 DNS，优先 IPv4。切换 Wi-Fi 或蜂窝网络后，请重启测试以重新读取。")
                        .font(.footnote)
                }
                Section("域名指定地址（可选）") {
                    TextField("pull-f5-sg01.tiktokcdn.com:170.187.221.24",text:$model.dnsOverrides,axis:.vertical)
                        .lineLimit(3...6).keyboardType(.asciiCapable).textInputAutocapitalization(.never)
                        .autocorrectionDisabled().disabled(model.active || model.busy)
                    Text("每行 域名:IPv4，完整匹配。AAAA 返回合成 IPv6，A 返回指定 IPv4；该域名其他查询返回空答案。留空关闭，启动时保存并生效。修改后建议重启待测 App，避免旧 DNS 缓存。")
                        .font(.footnote)
                }
                Section("可选回显测试服务器") {
                    TextField("同一网络 Mac 的 IPv4", text: $model.echoIPv4).keyboardType(.decimalPad).disabled(model.active || model.busy)
                    Text("TCP 18080 / UDP 18081；仅自检需要回显服务器。配置修改后重启生效。")
                        .font(.footnote)
                }
                Section {
                    Button("启动 IPv6 测试") { model.start() }.disabled(model.busy || model.active)
                    Button("检查 DNS64 本地响应") { model.probeDNS() }.disabled(model.busy || !model.active)
                    Button("检查 UDP NAT64 回显") { model.probeUDP() }.disabled(model.busy || !model.active)
                    Button("检查 TCP NAT64 回显") { model.probeTCP() }.disabled(model.busy || !model.active)
                    Button("检查 IPv4-only 网站访问") { model.probeHTTPS(ipv4Only:true) }.disabled(model.busy || !model.active)
                    Button("检查双栈网站访问") { model.probeHTTPS() }.disabled(model.busy || !model.active)
                    Button("读取扩展诊断") { model.snapshot() }
                    Button("停止测试", role: .destructive) { model.stop() }
                }
                Section("结果") { Text(model.report).font(.system(.footnote, design: .monospaced)).textSelection(.enabled) }
            }.navigationTitle("IPv6 Lab · iOS")
        }
    }
}
