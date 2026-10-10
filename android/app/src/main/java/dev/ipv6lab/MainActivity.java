package dev.ipv6lab;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.ipv6lab.core.LocalGateway;
import dev.ipv6lab.core.Nat64Prefix;
import dev.ipv6lab.core.Telemetry;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Section;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(22, 43, 36), GREEN = Color.rgb(17, 108, 89);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService diagnostics = Executors.newSingleThreadExecutor();
    private final Set<String> selected = new HashSet<>();
    private TextView phase, detail, network, metrics, logs;
    private EditText prefix, resolver, dnsOverrides;
    private Button toggle, scope, probe;
    private boolean pending;
    private boolean probing;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 1000); }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LabConfig config = LabConfig.load(this); selected.addAll(config.packages);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(244, 246, 242));
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(24), dp(28), dp(24), dp(32));
        scroll.addView(page); setContentView(scroll);
        TextView eyebrow = text("NETWORK TESTING / ANDROID", 11, GREEN); eyebrow.setLetterSpacing(.12f); page.addView(eyebrow);
        TextView title = text("IPv6 Lab", 36, INK); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); add(page, title, 8);
        add(page, text("在手机上复现 IPv6 应用通信路径", 15, INK), 4);

        LinearLayout status = card(); add(page, status, 24);
        phase = text("已停止", 24, GREEN); phase.setTypeface(Typeface.DEFAULT, Typeface.BOLD); status.addView(phase);
        detail = text("", 14, INK); add(status, detail, 8);
        network = text("", 12, Color.DKGRAY); add(status, network, 12);
        toggle = button("启动测试环境", true); add(status, toggle, 18);
        toggle.setOnClickListener(v -> {
            if (LabState.active) startService(new Intent(this, LabVpnService.class).setAction(LabVpnService.STOP));
            else prepare();
        });

        LinearLayout settings = card(); add(page, settings, 16);
        settings.addView(text("测试配置", 18, INK));
        add(settings, text("NAT64 前缀 · /96", 12, Color.DKGRAY), 16);
        prefix = input(config.prefix, "fd64:64:64::/96"); settings.addView(prefix);
        add(settings, text("上游 DNS · 留空使用底层网络的 DNS", 12, Color.DKGRAY), 12);
        resolver = input(config.resolver, "自动 / 数字 IPv4 或 IPv6 地址"); settings.addView(resolver);
        add(settings, text("域名指定地址 · 每行 域名:IPv4", 12, Color.DKGRAY), 12);
        dnsOverrides = input(config.dnsOverrides.text, "pull-f5-sg01.tiktokcdn.com:170.187.221.24");
        dnsOverrides.setSingleLine(false);
        dnsOverrides.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        dnsOverrides.setMinLines(3); dnsOverrides.setMaxLines(6); settings.addView(dnsOverrides);
        add(settings, text("完整匹配。AAAA 返回合成 IPv6，A 返回指定 IPv4；该域名其他查询返回空答案。留空关闭，启动时保存并生效。修改后建议重启待测 App，避免旧 DNS 缓存。", 12, Color.DKGRAY), 8);
        scope = button("", false); add(settings, scope, 12); scope.setOnClickListener(v -> chooseApps());
        add(settings, text("只有 IPv6 地址与路由；不提供 CLAT。原生 AAAA 保持不变，访问它们需要底层 IPv6 出口。", 12, Color.DKGRAY), 12);

        LinearLayout counters = card(); add(page, counters, 16); counters.addView(text("连接诊断", 18, INK));
        metrics = text("", 14, INK); metrics.setTypeface(Typeface.MONOSPACE); add(counters, metrics, 12);
        probe = button("检查 DNS64 与 IPv4 阻断", false); add(counters, probe, 12);
        probe.setOnClickListener(v -> runProbe());
        add(counters, text("IPv4 在系统层阻断，无法统计被阻断的数据包数。这里只显示转发层计数。", 12, Color.DKGRAY), 10);

        LinearLayout events = card(); add(page, events, 16); events.addView(text("最近事件", 18, INK));
        logs = text("", 11, Color.DKGRAY); logs.setTypeface(Typeface.MONOSPACE); logs.setTextIsSelectable(true); add(events, logs, 12);
        add(page, text("适用于开发自测。应用自带 DoH/DoT 不会经过本地 DNS64；测试前建议关闭 Android 私人 DNS。启动将替换当前 VPN，停止或进程退出后恢复原网络。", 12, Color.DKGRAY), 18);
        render();
    }

    private void prepare() {
        try { new LabConfig(prefix.getText().toString(), resolver.getText().toString(), selected, dnsOverrides.getText().toString()).save(this); }
        catch (IllegalArgumentException e) { new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("确定", null).show(); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        Intent consent = VpnService.prepare(this);
        if (consent != null) { pending = true; startActivityForResult(consent, 1); }
        else launch();
    }
    private void launch() {
        pending = true; toggle.setEnabled(false);
        startForegroundService(new Intent(this, LabVpnService.class));
        handler.postDelayed(() -> { pending = false; render(); }, 1500);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1) { pending = false; if (result == RESULT_OK) launch(); else LabState.log("未授予 VPN 权限"); }
    }

    private void chooseApps() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> candidates = getPackageManager().queryIntentActivities(launcher, 0);
        candidates.sort((a, b) -> a.loadLabel(getPackageManager()).toString().compareToIgnoreCase(b.loadLabel(getPackageManager()).toString()));
        List<String> names = new ArrayList<>(), packages = new ArrayList<>();
        for (ResolveInfo info : candidates) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || packages.contains(pkg)) continue;
            packages.add(pkg); names.add(info.loadLabel(getPackageManager()) + "\n" + pkg);
        }
        Set<String> draft = new HashSet<>(selected); boolean[] checked = new boolean[packages.size()];
        for (int i = 0; i < checked.length; i++) checked[i] = draft.contains(packages.get(i));
        new AlertDialog.Builder(this).setTitle("选择被测应用（未选择 = 全设备）")
                .setMultiChoiceItems(names.toArray(new String[0]), checked, (d, i, yes) -> {
                    if (yes) draft.add(packages.get(i)); else draft.remove(packages.get(i));
                }).setPositiveButton("应用", (d, w) -> { selected.clear(); selected.addAll(draft); render(); })
                .setNeutralButton("全设备", (d, w) -> { selected.clear(); render(); }).setNegativeButton("取消", null).show();
    }

    private void runProbe() {
        if (probing) return;
        probing = true;
        probe.setEnabled(false);
        diagnostics.execute(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.connect(new InetSocketAddress(Nat64Prefix.numeric(LocalGateway.DNS_ADDRESS), 53)); socket.setSoTimeout(5000);
                Message query = Message.newQuery(Record.newRecord(Name.fromString("ipv4only.arpa."), Type.AAAA, DClass.IN));
                byte[] bytes = query.toWire(); socket.send(new DatagramPacket(bytes, bytes.length));
                DatagramPacket packet = new DatagramPacket(new byte[2048], 2048); socket.receive(packet);
                Message response = new Message(Arrays.copyOf(packet.getData(), packet.getLength()));
                LabState.log("自检 DNS64：" + response.getSection(Section.ANSWER));
            } catch (Exception e) { LabState.log("自检 DNS64 失败：" + e.getMessage()); }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("1.1.1.1", 443), 3000);
                LabState.log("自检异常：直接 IPv4 连接成功，请检查 VPN 测试范围！");
            } catch (Exception expected) { LabState.log("自检：直接 IPv4 未连接（符合阻断预期；单次失败不是无泄漏证明）"); }
            handler.post(() -> { probing = false; if (!isDestroyed()) render(); });
        });
    }

    private void render() {
        phase.setText(LabState.phase); detail.setText(LabState.detail); network.setText(LabState.network);
        toggle.setText(LabState.active ? "停止测试环境" : "启动测试环境"); toggle.setEnabled(!pending);
        prefix.setEnabled(!LabState.active); resolver.setEnabled(!LabState.active); dnsOverrides.setEnabled(!LabState.active); scope.setEnabled(!LabState.active);
        scope.setText(selected.isEmpty() ? "测试范围：全设备  ›" : "测试范围：" + selected.size() + " 个应用  ›");
        probe.setEnabled(LabState.active && !probing);
        Telemetry t = LabState.telemetry;
        metrics.setText(String.format(Locale.ROOT, "DNS 查询  %d    合成 AAAA  %d\nNAT64 连接  %d    原生 IPv6  %d\n上行  %.1f KB    下行  %.1f KB\n转发错误  %d", t.dnsQueries.get(), t.synthesized.get(),
                t.translated.get(), t.nativeIpv6.get(), t.sent.get() / 1024.0, t.received.get() / 1024.0, t.errors.get()));
        String events = LabState.logs(); logs.setText(events.isEmpty() ? "尚无事件。启动环境后，这里会显示 DNS 合成与连接记录。" : events);
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); diagnostics.shutdownNow(); super.onDestroy(); }
    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setLineSpacing(dp(3), 1); return v;
    }
    private EditText input(String value, String hint) {
        EditText v = new EditText(this); v.setText(value); v.setHint(hint); v.setSingleLine(true); v.setTextSize(14);
        v.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); return v;
    }
    private Button button(String label, boolean primary) {
        Button v = new Button(this); v.setText(label); v.setAllCaps(false); v.setTextSize(15);
        v.setTextColor(primary ? Color.WHITE : GREEN);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(primary ? GREEN : Color.rgb(231, 241, 235)); bg.setCornerRadius(dp(12));
        v.setBackground(bg); v.setMinHeight(dp(48)); return v;
    }
    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(20), dp(20), dp(20), dp(20));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(20)); v.setBackground(bg); return v;
    }
    private void add(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(margin); parent.addView(child, p);
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
