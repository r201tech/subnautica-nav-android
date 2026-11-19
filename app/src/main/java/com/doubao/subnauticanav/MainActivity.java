package com.doubao.subnauticanav;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/**
 * 深海导航 —— 用 Shizuku 授予的 shell 权限读取 Subnautica 手游存档，
 * 解析玩家坐标并叠加到中文全图上。点一次「更新地图」= 重新读一次存档。
 */
public class MainActivity extends Activity implements Shizuku.OnRequestPermissionResultListener {

    private static final int SHIZUKU_REQ = 100;
    private static final String REPO_URL = "https://github.com/r201tech/subnautica-nav-android";

    private static final String SAVE_DIR_A =
            "/sdcard/Android/data/com.unknownworlds.subnautica/files/SavedGames";
    private static final String SAVE_DIR_B =
            "/sdcard/Android/data/com.unknownworlds.subnautica/files/SNAppData/SavedGames";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Pattern GAME_TIME = Pattern.compile("\"gameTime\"\\s*:\\s*(\\d+)");

    private MapView mapView;
    private TextView statusText;
    private TextView szState;
    private TextView tipText;
    private Button authBtn;
    private Button refreshBtn;
    private Button slotBtn;

    private Bitmap mapBitmap;
    private String baseDir = SAVE_DIR_A;
    private String slotName = "";
    private String chosenSlot = "";        // 为空 = 自动选最新
    private boolean busy = false;

    private SharedPreferences prefs;
    private float heading = Float.NaN;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getPreferences(MODE_PRIVATE);
        chosenSlot = prefs.getString("slot", "");
        heading = prefs.getFloat("heading", Float.NaN);
        setContentView(buildUi());
        Shizuku.addRequestPermissionResultListener(this);
        refreshState();
        ui.postDelayed(this::refreshState, 2500);
        loadMapAsync();
        ui.postDelayed(this::onRefreshClicked, 800);
    }

    /** 把 Shizuku 服务/授权状态直接写在界面上，方便一眼定位卡在哪 */
    private void refreshState() {
        if (szState == null) return;
        String s;
        boolean granted = false;
        try {
            if (!Shizuku.pingBinder()) {
                s = "Shizuku 服务：未连接 → 先打开 Shizuku（首页要显示“服务运行中”）";
            } else {
                granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                s = granted
                        ? "Shizuku 服务：已连接 · 已授权（uid " + Shizuku.getUid() + "）"
                        : "Shizuku 服务：已连接 · 未授权 → 点右边「授权」";
            }
        } catch (Throwable t) {
            s = "Shizuku 状态异常：" + t.getClass().getSimpleName() + " " + t.getMessage();
        }
        szState.setText(s);
        szState.setTextColor(granted ? 0xFF7FE9FF : 0xFFFF7A7A);
        if (authBtn != null) authBtn.setVisibility(granted ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(this);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF060A10);

        mapView = new MapView(this);
        mapView.setOnPickDirectionListener(deg -> {
            heading = deg;
            prefs.edit().putFloat("heading", deg).apply();
            updateTip();
        });
        root.addView(mapView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF00C131C);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0x33FFFFFF);
        panel.setBackground(bg);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("深海导航 · 存档定位");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.ImageButton gh = new android.widget.ImageButton(this);
        gh.setImageResource(getResources().getIdentifier("ic_github", "drawable", getPackageName()));
        gh.setBackgroundColor(Color.TRANSPARENT);
        gh.setPadding(dp(6), dp(6), dp(6), dp(6));
        gh.setContentDescription("打开 GitHub 仓库");
        gh.setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(REPO_URL)));
            } catch (Throwable t) {
                status("没有可用的浏览器");
            }
        });
        titleRow.addView(gh, new LinearLayout.LayoutParams(dp(34), dp(34)));
        panel.addView(titleRow);

        statusText = new TextView(this);
        statusText.setTextColor(0xFF8FE9FF);
        statusText.setTextSize(12);
        statusText.setPadding(0, dp(6), 0, 0);
        statusText.setText("正在读取存档…");
        panel.addView(statusText);

        szState = new TextView(this);
        szState.setTextColor(0xFFFF7A7A);
        szState.setTextSize(10);
        szState.setPadding(0, dp(3), 0, 0);
        szState.setText("Shizuku 服务：检测中…");
        panel.addView(szState);

        tipText = new TextView(this);
        tipText.setTextColor(0x88FFFFFF);
        tipText.setTextSize(10);
        tipText.setPadding(0, dp(4), 0, dp(4));
        panel.addView(tipText);

        TextView saveHint = new TextView(this);
        saveHint.setText("提示：每次更新地图前，先在游戏「设置」里手动保存一次存档，位置才会变");
        saveHint.setTextColor(0xFFFFC45B);
        saveHint.setTextSize(10);
        saveHint.setPadding(0, 0, 0, dp(10));
        panel.addView(saveHint);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        authBtn = new Button(this);
        authBtn.setText("授权");
        authBtn.setTextSize(12);
        authBtn.setAllCaps(false);
        authBtn.setTextColor(0xFFFFFFFF);
        GradientDrawable authBg = new GradientDrawable();
        authBg.setColor(0xFFE0533D);
        authBg.setCornerRadius(dp(14));
        authBtn.setBackground(authBg);
        authBtn.setOnClickListener(v -> requestShizuku());
        LinearLayout.LayoutParams lp0 = new LinearLayout.LayoutParams(dp(72), dp(48));
        lp0.setMargins(0, 0, dp(10), 0);
        row.addView(authBtn, lp0);

        slotBtn = new Button(this);
        slotBtn.setText("选择存档");
        slotBtn.setTextSize(14);
        slotBtn.setAllCaps(false);
        slotBtn.setTextColor(0xFFFFFFFF);
        GradientDrawable slotBg = new GradientDrawable();
        slotBg.setColor(0xFF22303D);
        slotBg.setCornerRadius(dp(14));
        slotBtn.setBackground(slotBg);
        slotBtn.setOnClickListener(v -> showSlotPicker());
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp1.setMargins(0, 0, dp(10), 0);
        row.addView(slotBtn, lp1);

        refreshBtn = new Button(this);
        refreshBtn.setText("更新地图");
        refreshBtn.setTextSize(16);
        refreshBtn.setAllCaps(false);
        refreshBtn.setTextColor(0xFF06121A);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xFF52D8FF);
        btnBg.setCornerRadius(dp(14));
        refreshBtn.setBackground(btnBg);
        refreshBtn.setOnClickListener(v -> onRefreshClicked());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(48), 1.6f);
        row.addView(refreshBtn, lp2);

        panel.addView(row);

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.gravity = Gravity.BOTTOM;
        pp.setMargins(dp(12), 0, dp(12), dp(22));
        root.addView(panel, pp);

        updateTip();
        return root;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void status(String s) {
        statusText.setText(s);
    }

    private void updateTip() {
        if (tipText == null) return;
        StringBuilder sb = new StringBuilder("存档 ");
        sb.append(slotName.isEmpty() ? "（自动）" : slotName);
        if (!Float.isNaN(heading)) {
            sb.append(" · 朝向 ").append(Math.round(heading)).append("°").append(dirName(heading));
        }
        sb.append("\n双指缩放 / 拖动 · 长按地图设定朝向 · 双击回到玩家");
        tipText.setText(sb.toString());
    }

    private static String dirName(float deg) {
        String[] n = {"正北", "东北", "正东", "东南", "正南", "西南", "正西", "西北"};
        int i = (int) Math.round(deg / 45f) % 8;
        return " " + n[i];
    }

    // ------------------------------------------------------- 地图与刷新

    private void loadMapAsync() {
        new Thread(() -> {
            try {
                Bitmap bmp = loadExternalMap();
                if (bmp == null) {
                    AssetManager am = getAssets();
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    InputStream in = am.open("subnautica_map.png");
                    bmp = BitmapFactory.decodeStream(in, null, o);
                    in.close();
                }
                final Bitmap result = bmp;
                ui.post(() -> {
                    mapBitmap = result;
                    mapView.setMap(result);
                    if (!Float.isNaN(heading)) mapView.setHeading(heading);
                });
            } catch (Throwable t) {
                ui.post(() -> status("底图加载失败：" + t.getMessage()));
            }
        }, "map-loader").start();
    }

    /** 优先用用户自己放在手机里的地图：/storage/emulated/0/DeepSeaNav/map.png（或中文目录） */
    private Bitmap loadExternalMap() {
        String[] paths = {
                "/storage/emulated/0/DeepSeaNav/map.png",
                "/storage/emulated/0/深海导航/map.png"
        };
        for (String p : paths) {
            try {
                byte[] data = shell("cat '" + p + "' 2>/dev/null");
                if (data.length > 50_000) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    return BitmapFactory.decodeByteArray(data, 0, data.length, o);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 「授权」按钮：显式发起一次 Shizuku 授权申请 */
    private void requestShizuku() {
        refreshState();
        try {
            if (!Shizuku.pingBinder()) {
                status("Shizuku 服务未连接：先把 Shizuku 打开（首页显示“服务运行中”）再点授权");
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                status("已经授权过了");
                refreshState();
                return;
            }
            status("正在向 Shizuku 申请授权…");
            int ver = Shizuku.getVersion();
            try {
                Shizuku.requestPermission(SHIZUKU_REQ);
            } catch (Throwable t) {
                status("申请失败（Shizuku v" + ver + "）：" + t.getMessage()
                        + " → 到 Shizuku「授权应用」里手动打开本应用");
            }
        } catch (Throwable t) {
            status("Shizuku 调用异常：" + t.getMessage());
        }
    }

    private void onRefreshClicked() {
        if (busy) return;
        if (!Shizuku.pingBinder()) {
            status("Shizuku 没运行 —— 先打开 Shizuku 再点我");
            return;
        }
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                status("等待 Shizuku 授权…");
                Shizuku.requestPermission(SHIZUKU_REQ);
                return;
            }
        } catch (Throwable t) {
            status("Shizuku 状态异常：" + t.getMessage());
            return;
        }
        refreshState();
        busy = true;
        refreshBtn.setEnabled(false);
        status("正在读取存档…");
        new Thread(this::doRefresh, "save-reader").start();
    }

    @Override
    public void onRequestPermissionResult(int requestCode, int grantResult) {
        if (requestCode != SHIZUKU_REQ) return;
        ui.post(this::refreshState);
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            ui.post(this::onRefreshClicked);
        } else {
            ui.post(() -> status("Shizuku 授权被拒绝，无法读取存档"));
        }
    }

    private void doRefresh() {
        try {
            String slot = resolveSlot();
            if (slot == null) {
                fail("没找到存档目录，确认游戏运行过并存过档");
                return;
            }
            slotName = slot;

            byte[] raw = shell("cat " + baseDir + "/" + slot + "/scene-objects.bin");
            SaveParser.Pos pos = SaveParser.parsePlayer(raw);
            if (pos == null) {
                fail("存档解析失败（scene-objects.bin 结构不符合预期）");
                return;
            }

            String gameTime = "";
            byte[] info = shell("cat " + baseDir + "/" + slot + "/gameinfo.json");
            if (info.length > 0) {
                Matcher m = GAME_TIME.matcher(new String(info));
                if (m.find()) {
                    long sec = Long.parseLong(m.group(1));
                    gameTime = String.format(" · 游戏 %dh%02dm", sec / 3600, (sec % 3600) / 60);
                }
            }

            final SaveParser.Pos p = pos;
            final String gt = gameTime;
            ui.post(() -> {
                if (mapBitmap != null) {
                    float bx2 = (p.x + 2000f) / 4000f * mapBitmap.getWidth();
                    float by2 = (2000f - p.z) / 4000f * mapBitmap.getHeight();
                    mapView.setMarker(bx2, by2);
                    mapView.setHeading(heading);
                }
                status(String.format("X %.1f   Z %.1f   深度 %d m%s",
                        p.x, p.z, Math.round(p.depth()), gt));
                updateTip();
                busy = false;
                refreshBtn.setEnabled(true);
            });
        } catch (Throwable t) {
            fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** 选定或默认最新的存档槽 */
    private String resolveSlot() throws IOException {
        for (String dir : new String[]{SAVE_DIR_A, SAVE_DIR_B}) {
            byte[] out = shell("ls " + dir + " 2>/dev/null");
            if (out.length == 0) continue;
            baseDir = dir;
            List<String> slots = parseSlots(out);
            if (slots.isEmpty()) continue;
            if (!chosenSlot.isEmpty() && slots.contains(chosenSlot)) return chosenSlot;
            // 默认取最新修改的那个
            String newest = null;
            long newestTs = -1;
            for (String s : slots) {
                long ts = mtime(dir + "/" + s + "/scene-objects.bin");
                if (ts > newestTs) { newestTs = ts; newest = s; }
            }
            return newest != null ? newest : slots.get(0);
        }
        return null;
    }

    private List<String> parseSlots(byte[] out) {
        List<String> r = new ArrayList<>();
        for (String line : new String(out).split("\n")) {
            String s = line.trim();
            if (s.startsWith("slot")) r.add(s);
        }
        return r;
    }

    private long mtime(String path) throws IOException {
        byte[] out = shell("stat -c %Y " + path + " 2>/dev/null");
        try {
            return Long.parseLong(new String(out).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------- 存档选择

    private void showSlotPicker() {
        if (!Shizuku.pingBinder()) {
            status("Shizuku 没运行 —— 先打开 Shizuku 再点我");
            return;
        }
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                status("等待 Shizuku 授权…");
                Shizuku.requestPermission(SHIZUKU_REQ);
                return;
            }
        } catch (Throwable t) {
            status("Shizuku 状态异常：" + t.getMessage());
            return;
        }
        status("正在读取存档列表…");
        new Thread(() -> {
            List<String> labels = new ArrayList<>();
            List<String> values = new ArrayList<>();
            labels.add("自动 · 用最新的存档");
            values.add("");
            try {
                for (String dir : new String[]{SAVE_DIR_A, SAVE_DIR_B}) {
                    byte[] out = shell("ls " + dir + " 2>/dev/null");
                    if (out.length == 0) continue;
                    for (String slot : parseSlots(out)) {
                        String detail = slotDetail(dir + "/" + slot);
                        labels.add(detail);
                        values.add(slot);
                        if (values.size() > 12) break;
                    }
                    if (values.size() > 1) break;
                }
            } catch (Throwable ignored) {
            }
            final String[] items = labels.toArray(new String[0]);
            ui.post(() -> {
                status("点「更新地图」读取选中的存档");
                new AlertDialog.Builder(this)
                        .setTitle("选择存档")
                        .setItems(items, (d, which) -> {
                            chosenSlot = values.get(which);
                            prefs.edit().putString("slot", chosenSlot).apply();
                            slotName = chosenSlot.isEmpty() ? "（自动）" : chosenSlot;
                            updateTip();
                            onRefreshClicked();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }, "slot-lister").start();
    }

    private String slotDetail(String path) throws IOException {
        byte[] info = shell("cat " + path + "/gameinfo.json 2>/dev/null");
        String gt = "";
        Matcher m = GAME_TIME.matcher(new String(info));
        if (m.find()) {
            long sec = Long.parseLong(m.group(1));
            gt = String.format("  %dh%02dm", sec / 3600, (sec % 3600) / 60);
        }
        long ts = mtime(path + "/scene-objects.bin");
        String when = "";
        if (ts > 0) {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm");
            when = "  " + f.format(new java.util.Date(ts * 1000L));
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        return last + gt + when;
    }

    private void fail(String msg) {
        ui.post(() -> {
            status(msg);
            busy = false;
            refreshBtn.setEnabled(true);
        });
    }

    // ------------------------------------------------------- Shizuku 调用

    /** 通过 Shizuku 以 shell 身份跑一条命令，返回 stdout 原始字节 */
    private byte[] shell(String cmd) throws IOException {
        Process p = newShizukuProcess(new String[]{"sh", "-c", cmd});
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        try { p.waitFor(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        p.destroy();
        return bos.toByteArray();
    }

    /** Shizuku.newProcess 是私有方法，用反射拿（同 App 内的类，不受隐藏 API 限制） */
    private static java.lang.reflect.Method newProcessMethod;

    private Process newShizukuProcess(String[] cmd) throws IOException {
        try {
            if (newProcessMethod == null) {
                newProcessMethod = Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                newProcessMethod.setAccessible(true);
            }
            return (Process) newProcessMethod.invoke(null, cmd, null, null);
        } catch (Throwable t) {
            throw new IOException("调用 Shizuku.newProcess 失败: " + t, t);
        }
    }
}
