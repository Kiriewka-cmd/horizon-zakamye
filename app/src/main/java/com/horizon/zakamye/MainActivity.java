package com.horizon.zakamye;

import android.app.Activity;
import android.content.SharedPreferences;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.AnimatorSet;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.animation.LinearInterpolator;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.webkit.WebViewAssetLoader;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    // OWNER/REPO подставляется автоматически при сборке в GitHub Actions
    static final String UPDATE_BASE = "https://raw.githubusercontent.com/OWNER/REPO/main/game/";
    static final String MUSIC_API = "https://api.github.com/repos/OWNER/REPO/contents/game/music";
    static final int BUNDLED_VERSION = 19;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private File gameDir, musicDir;
    private WebView web;
    private LinearLayout launcher;
    private TextView status, verChip;
    private GlassProgress bar;
    private Button play, check;
    private int cPrimary, cTertiary, cBg;
    private View launcherRootView;
    private boolean gameLoaded = false, updating = false, inGame = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        gameDir = new File(getFilesDir(), "game");
        gameDir.mkdirs();
        musicDir = new File(gameDir, "music");
        musicDir.mkdirs();

        initColors();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(cBg);
        setContentView(root);

        web = buildWebView();
        web.setVisibility(View.GONE);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        // --- экран лаунчера: фон + светящиеся пятна + стеклянная карточка ---
        FrameLayout launcherRoot = new FrameLayout(this);
        launcherRoot.setBackgroundColor(cBg);
        launcherRoot.addView(orb(cPrimary, 420, Gravity.TOP | Gravity.START, -120, -120, 7000, 40, 30));
        launcherRoot.addView(orb(cTertiary, 380, Gravity.BOTTOM | Gravity.END, 0, 0, 9000, -50, -30));
        launcherRoot.addView(orb(mix(cPrimary, cTertiary), 300, Gravity.CENTER, 160, -60, 11000, 30, 50));
        launcher = new LinearLayout(this);
        launcher.setOrientation(LinearLayout.VERTICAL);
        launcher.setGravity(Gravity.CENTER);
        launcherRoot.addView(launcher, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(28), dp(24), dp(28), dp(24));
        card.setBackground(glass(dp(32), 0x40, 0x12, 0x66));
        if (Build.VERSION.SDK_INT >= 21) card.setElevation(dp(12));

        TextView title = text("HORIZON", 34, Color.WHITE, true);
        title.setLetterSpacing(0.18f);
        TextView sub = text("ZAKAMYE", 13, cPrimary, true);
        sub.setLetterSpacing(0.5f);
        verChip = text("", 12, Color.WHITE, false);
        verChip.setPadding(dp(14), dp(5), dp(14), dp(5));
        verChip.setBackground(glass(dp(20), 0x30, 0x10, 0x55));
        status = text("Проверка обновлений…", 14, 0xFFE6E1E9, false);
        bar = new GlassProgress(this);

        play = pill("ИГРАТЬ", true);
        play.setOnClickListener(v -> startGame());
        check = pill("Проверить версию", false);
        check.setOnClickListener(v -> checkUpdate());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(54), 1f);
        rp.rightMargin = dp(6);
        LinearLayout.LayoutParams rp2 = new LinearLayout.LayoutParams(0, dp(54), 1f);
        rp2.leftMargin = dp(6);
        row.addView(check, rp);
        row.addView(play, rp2);

        card.addView(title);
        card.addView(sub, lp(0, 0));
        card.addView(verChip, lp(0, 16));
        card.addView(status, lp(0, 22));
        card.addView(bar, lp2(300, 8, 14));
        card.addView(row, lp2(400, 54, 24));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(460), -2);
        launcher.addView(card, cp);
        root.addView(launcherRoot, new FrameLayout.LayoutParams(-1, -1));
        launcher = (LinearLayout) launcherRoot.getChildAt(3);
        launcherRootView = launcherRoot;
        refreshVersionLabel();

        checkUpdate();
    }

    private int curVersion() { return prefs.getInt("ver", BUNDLED_VERSION); }
    private boolean hasLocal() { return curVersion() > BUNDLED_VERSION && new File(gameDir, "index.html").exists(); }

    private void refreshVersionLabel() { verChip.setText("Версия игры  v" + (hasLocal() ? curVersion() : BUNDLED_VERSION)); }

    private void checkUpdate() {
        if (updating) return;
        updating = true;
        play.setEnabled(false);
        check.setEnabled(false);
        status.setText("Проверка обновлений…");
        bar.setIndeterminate(true);
        new Thread(() -> {
            try {
                String json = httpString(UPDATE_BASE + "version.json?t=" + System.currentTimeMillis());
                int remote = Integer.parseInt(json.replaceAll("[^0-9]", ""));
                int have = hasLocal() ? curVersion() : BUNDLED_VERSION;
                if (remote > have) {
                    ui.post(() -> { status.setText("Скачивание обновления v" + remote + "…"); bar.setIndeterminate(false); bar.setProgress(0); });
                    File tmp = new File(gameDir, "index.tmp");
                    download(UPDATE_BASE + "index.html?t=" + System.currentTimeMillis(), tmp);
                    File dst = new File(gameDir, "index.html");
                    if (dst.exists()) dst.delete();
                    if (!tmp.renameTo(dst)) throw new IOException("rename");
                    prefs.edit().putInt("ver", remote).apply();
                    ui.post(() -> { gameLoaded = false; status.setText("Обновлено до v" + remote + " ✓"); });
                } else {
                    ui.post(() -> status.setText("У вас последняя версия ✓"));
                }
            } catch (Exception e) {
                ui.post(() -> status.setText("Нет связи с сервером обновлений — запуск сохранённой версии"));
            }
            ui.post(() -> { updating = false; bar.setIndeterminate(false); bar.setProgress(100); play.setEnabled(true); refreshVersionLabel(); });
            syncMusic();
            ui.post(() -> check.setEnabled(true));
        }).start();
    }

    /** Скачивает музыку из game/music репозитория в память приложения (только недостающие файлы). Вызывать из фонового потока. */
    private void syncMusic() {
        try {
            HttpURLConnection c = open(MUSIC_API);
            c.setRequestProperty("User-Agent", "HorizonLauncher");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            String json;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                json = o.toString("UTF-8");
            } finally { c.disconnect(); }
            JSONArray arr = new JSONArray(json);
            List<String[]> need = new ArrayList<>();
            int total = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!"file".equals(o.optString("type"))) continue;
                String name = o.getString("name"), low = name.toLowerCase();
                if (!(low.endsWith(".mp3") || low.endsWith(".ogg") || low.endsWith(".m4a") || low.endsWith(".wav") || low.endsWith(".aac"))) continue;
                total++;
                File f = new File(musicDir, name);
                if (!(f.exists() && f.length() == o.optLong("size", -1))) need.add(new String[]{name, o.getString("download_url")});
            }
            if (total == 0) return;
            final int tot = total;
            if (need.isEmpty()) { ui.post(() -> status.setText("Музыка: " + tot + " треков ✓")); return; }
            int ok = 0;
            for (int i = 0; i < need.size(); i++) {
                final int idx = i + 1, cnt = need.size();
                ui.post(() -> { status.setText("Скачивание музыки " + idx + "/" + cnt + "…"); bar.setIndeterminate(false); bar.setProgress(0); });
                try {
                    File tmp = new File(musicDir, need.get(i)[0] + ".part");
                    download(need.get(i)[1], tmp);
                    File dst = new File(musicDir, need.get(i)[0]);
                    if (dst.exists()) dst.delete();
                    if (tmp.renameTo(dst)) ok++;
                } catch (Exception ignored) { }
            }
            final int okf = ok, cnt = need.size();
            ui.post(() -> { bar.setProgress(100); status.setText(okf == cnt ? "Музыка загружена ✓ (" + tot + " треков)" : "Музыка: скачано " + okf + " из " + cnt + " — повторите проверку"); });
        } catch (Exception e) {
            // нет интернета или нет папки game/music — тихо пропускаем
        }
    }

    private String httpString(String u) throws IOException {
        HttpURLConnection c = open(u);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private void download(String u, File out) throws IOException {
        HttpURLConnection c = open(u);
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); FileOutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[32768]; int n; long got = 0; int last = -1;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n); got += n;
                if (total > 0) { final int p = (int) (got * 100 / total); if (p != last) { last = p; ui.post(() -> bar.setProgress(p)); } }
            }
        } finally { c.disconnect(); }
        if (out.length() < 1000) throw new IOException("too small");
    }

    private HttpURLConnection open(String u) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(6000); c.setReadTimeout(20000);
        c.setUseCaches(false);
        return c;
    }

    private WebView buildWebView() {
        WebView w = new WebView(this);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/music/", new WebViewAssetLoader.InternalStoragePathHandler(this, musicDir))
                .addPathHandler("/game/music/", new WebViewAssetLoader.InternalStoragePathHandler(this, musicDir))
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/game/", new WebViewAssetLoader.InternalStoragePathHandler(this, gameDir))
                .build();
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setBackgroundColor(0xFF101827);
        w.setWebChromeClient(new WebChromeClient());
        w.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
        });
        return w;
    }

    private void startGame() {
        if (updating) return;
        if (!gameLoaded) {
            web.loadUrl(hasLocal() ? "https://appassets.androidplatform.net/game/index.html"
                                   : "https://appassets.androidplatform.net/assets/index.html");
            gameLoaded = true;
        }
        web.setVisibility(View.VISIBLE);
        launcherRootView.setVisibility(View.GONE);
        web.onResume();
        inGame = true;
    }

    private void showLauncher() {
        web.onPause();
        web.setVisibility(View.GONE);
        launcherRootView.setVisibility(View.VISIBLE);
        inGame = false;
    }


    // ---------- дизайн: Material You + liquid glass ----------
    private void initColors() {
        if (Build.VERSION.SDK_INT >= 31) {
            cPrimary = getColor(android.R.color.system_accent1_200);
            cTertiary = getColor(android.R.color.system_accent3_200);
            cBg = getColor(android.R.color.system_neutral1_900);
        } else {
            cPrimary = 0xFFD0BCFF; cTertiary = 0xFFEFB8C8; cBg = 0xFF141218;
        }
    }
    private int mix(int a, int b) {
        return Color.rgb((Color.red(a) + Color.red(b)) / 2, (Color.green(a) + Color.green(b)) / 2, (Color.blue(a) + Color.blue(b)) / 2);
    }
    private int alpha(int c, int a) { return (c & 0x00FFFFFF) | (a << 24); }

    /** стекло: полупрозрачный градиент (светлее сверху) + светлая рамка-блик */
    private GradientDrawable glass(int radius, int aTop, int aBottom, int aStroke) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{alpha(Color.WHITE, aTop), alpha(Color.WHITE, aBottom)});
        g.setCornerRadius(radius);
        g.setStroke(Math.max(1, dp(1)), alpha(Color.WHITE, aStroke));
        return g;
    }

    private View orb(int color, int sizeDp, int gravity, int mx, int my, long dur, float dx, float dy) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(sizeDp) / 2f);
        g.setColors(new int[]{alpha(color, 0xAA), alpha(color, 0x00)});
        v.setBackground(g);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), gravity);
        lp.leftMargin = dp(mx); lp.topMargin = dp(my);
        v.setLayoutParams(lp);
        ObjectAnimator ax = ObjectAnimator.ofFloat(v, "translationX", 0, dp((int) dx));
        ObjectAnimator ay = ObjectAnimator.ofFloat(v, "translationY", 0, dp((int) dy));
        for (ObjectAnimator o : new ObjectAnimator[]{ax, ay}) { o.setDuration(dur); o.setRepeatMode(ValueAnimator.REVERSE); o.setRepeatCount(ValueAnimator.INFINITE); }
        AnimatorSet set = new AnimatorSet(); set.playTogether(ax, ay); set.start();
        return v;
    }

    private Button pill(String t, boolean filled) {
        Button b = new Button(this);
        b.setText(t); b.setAllCaps(false); b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setPadding(dp(12), 0, dp(12), 0);
        if (filled) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{cPrimary, mix(cPrimary, cTertiary)});
            g.setCornerRadius(dp(27));
            b.setBackground(g);
            b.setTextColor(0xFF1B1B1F);
        } else {
            b.setBackground(glass(dp(27), 0x38, 0x14, 0x77));
            b.setTextColor(Color.WHITE);
        }
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) v.animate().scaleX(.95f).scaleY(.95f).setDuration(90).start();
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            return false;
        });
        return b;
    }

    /** тонкий стеклянный прогресс-бар (определённый и «бегущий» режим) */
    private class GlassProgress extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int progress = 0; private boolean indet = true; private float phase = 0f;
        GlassProgress(android.content.Context c) {
            super(c);
            track.setColor(alpha(Color.WHITE, 0x2A));
            fill.setColor(cPrimary);
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(1300); a.setRepeatCount(ValueAnimator.INFINITE); a.setInterpolator(new LinearInterpolator());
            a.addUpdateListener(x -> { phase = (float) x.getAnimatedValue(); if (indet) invalidate(); });
            a.start();
        }
        void setProgress(int p) { progress = p; invalidate(); }
        void setIndeterminate(boolean i) { indet = i; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = h / 2f;
            r.set(0, 0, w, h); c.drawRoundRect(r, rad, rad, track);
            if (indet) {
                float len = w * 0.35f, x = -len + (w + len) * phase;
                r.set(Math.max(0, x), 0, Math.min(w, x + len), h);
            } else r.set(0, 0, w * progress / 100f, h);
            if (r.width() > 0) c.drawRoundRect(r, rad, rad, fill);
        }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private TextView text(String t, int sp, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(t); v.setTextSize(sp); v.setTextColor(color); v.setGravity(Gravity.CENTER);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD); return v;
    }
    private LinearLayout.LayoutParams lp(int l, int topDp) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.topMargin = dp(topDp); return p; }
    private LinearLayout.LayoutParams lp2(int w, int h, int topDp) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(w), dp(h)); p.topMargin = dp(topDp); return p; }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }
    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); if (inGame) web.onResume(); immersive(); }
    @Override protected void onDestroy() { web.destroy(); super.onDestroy(); }
    @Override public void onBackPressed() { if (inGame) showLauncher(); else super.onBackPressed(); }
}
