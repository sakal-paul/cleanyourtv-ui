package io.cleanyourtv.ui;

import android.app.Activity;
import android.app.ActivityManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * The gauge button on the home screen: free storage, free memory and the internet speed. The speed test runs only
 * while this screen is open (downloads from Cloudflare's speed test for 8 seconds), never in the background.
 */
public class SystemInfoActivity extends Activity {
    private static final String TEST_URL = "https://speed.cloudflare.com/__down?bytes=";
    private static final long TEST_MS = 8000, CHUNK = 25_000_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopped;
    private float k;
    private TextView speedValue, speedDetail;
    private View again;
    private Thread test;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        k = TvUi.k(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(TvUi.BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(120), px(90), px(120), px(80));
        page.setClipChildren(false);   // the focused button grows past its row
        page.setClipToPadding(false);
        root.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView h = TvUi.text(this, k, 56, Ui.displaySemibold, TvUi.INK);
        h.setText(Ui.tr("Sistema", "System info"));
        page.addView(h);
        TextView sub = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        sub.setText(Ui.tr("Espaço livre, memória livre e a velocidade da internet agora.",
                "Free space, free memory and your internet speed right now."));
        sub.setPadding(0, px(12), 0, px(50));
        page.addView(sub);

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(cards, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(380)));

        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long freeDisk = fs.getAvailableBytes(), totalDisk = fs.getTotalBytes();
        cards.addView(card(Ui.tr("Armazenamento", "Storage"), size(freeDisk) + " " + Ui.tr("livres", "free"),
                Ui.tr("de ", "of ") + size(totalDisk), totalDisk - freeDisk, totalDisk), weight(true));

        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mem);
        cards.addView(card(Ui.tr("Memória (RAM)", "Memory (RAM)"), size(mem.availMem) + " " + Ui.tr("livres", "free"),
                Ui.tr("de ", "of ") + size(mem.totalMem), mem.totalMem - mem.availMem, mem.totalMem), weight(true));

        LinearLayout net = box();
        net.addView(label(Ui.tr("Internet", "Internet speed")));
        speedValue = TvUi.text(this, k, 64, Ui.displaySemibold, TvUi.INK);
        speedValue.setPadding(0, px(26), 0, 0);
        net.addView(speedValue);
        speedDetail = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        speedDetail.setPadding(0, px(14), 0, 0);
        net.addView(speedDetail);
        cards.addView(net, weight(false));

        TextView button = TvUi.text(this, k, 28, Ui.semibold, TvUi.INK);
        button.setText(Ui.tr("Testar de novo", "Test again"));
        button.setGravity(Gravity.CENTER);
        button.setPadding(px(44), px(20), px(44), px(20));
        button.setBackground(TvUi.round(TvUi.CARD, px(40)));
        TvUi.focusable(button, k, px(40));
        button.setOnClickListener(v -> startTest());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = px(50);
        page.addView(button, bp);
        again = button;

        setContentView(root);
        button.requestFocus();
        startTest();
    }

    @Override protected void onDestroy() {
        stopped = true;
        super.onDestroy();
    }

    private void startTest() {
        if (test != null && test.isAlive()) return;
        speedValue.setText(Ui.tr("Testando…", "Testing…"));
        speedDetail.setText(Ui.tr("Baixando por alguns segundos", "Downloading for a few seconds"));
        again.setAlpha(0.5f);
        test = new Thread(this::runTest, "speed-test");
        test.start();
    }

    /** Ping: time to the first byte of an empty answer. Speed: bytes read in TEST_MS, in megabits per second. */
    private void runTest() {
        try {
            long t0 = SystemClock.elapsedRealtime();
            fetch(TEST_URL + "0", 0);
            long ping = SystemClock.elapsedRealtime() - t0;
            // Cloudflare refuses more than 50 MB per request (403), so read 25 MB pieces until the time is up.
            long start = SystemClock.elapsedRealtime(), end = start + TEST_MS, got = 0;
            while (!stopped && SystemClock.elapsedRealtime() < end) {
                got += fetch(TEST_URL + CHUNK, end - SystemClock.elapsedRealtime());
            }
            float secs = Math.max(0.001f, (SystemClock.elapsedRealtime() - start) / 1000f);
            float mbps = got * 8f / secs / 1_000_000f;
            String value = String.format(Locale.US, mbps < 10 ? "%.1f Mbps" : "%.0f Mbps", mbps);
            String detail = Ui.tr("Ping ", "Ping ") + ping + " ms · " + size(got) + Ui.tr(" em ", " in ")
                    + String.format(Locale.US, "%.1f s", secs);
            post(value, detail);
        } catch (Exception e) {
            post(Ui.tr("Sem conexão", "No connection"), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Reads the URL until it ends, the screen closes or `limitMs` passes (0: no limit); returns the bytes read. */
    private long fetch(String url, long limitMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setUseCaches(false);
        long total = 0, end = limitMs > 0 ? SystemClock.elapsedRealtime() + limitMs : Long.MAX_VALUE;
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while (!stopped && (n = in.read(buf)) > 0) {
                total += n;
                if (SystemClock.elapsedRealtime() >= end) break;
            }
        } finally {
            c.disconnect();
        }
        return total;
    }

    private void post(String value, String detail) {
        main.post(() -> {
            if (stopped) return;
            speedValue.setText(value);
            speedDetail.setText(detail);
            again.setAlpha(1f);
        });
    }

    // MARK: - Views

    private LinearLayout card(String title, String value, String detail, long used, long total) {
        LinearLayout b = box();
        b.addView(label(title));
        TextView v = TvUi.text(this, k, 64, Ui.displaySemibold, TvUi.INK);
        v.setText(value);
        v.setPadding(0, px(26), 0, 0);
        b.addView(v);
        TextView d = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        d.setText(detail);
        d.setPadding(0, px(14), 0, px(36));
        b.addView(d);
        // A bar: the used part in the accent colour on a faint track.
        LinearLayout bar = new LinearLayout(this);
        bar.setBackground(TvUi.round(0x26FFFFFF, px(8)));
        View fill = new View(this);
        fill.setBackground(TvUi.round(TvUi.ACCENT, px(8)));
        float part = total > 0 ? Math.max(0.02f, Math.min(1f, used / (float) total)) : 0;
        bar.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, part));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1 - part));
        b.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(16)));
        TextView pct = TvUi.text(this, k, 22, Ui.regular, TvUi.FAINT);
        pct.setText(Math.round(part * 100) + Ui.tr("% em uso", "% used"));
        pct.setPadding(0, px(12), 0, 0);
        b.addView(pct);
        return b;
    }

    private LinearLayout box() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(px(44), px(40), px(44), px(40));
        b.setBackground(TvUi.round(TvUi.CARD, px(28)));
        return b;
    }

    private TextView label(String text) {
        TextView t = TvUi.text(this, k, 26, Ui.semibold, TvUi.DIM);
        t.setText(text.toUpperCase(Locale.getDefault()));
        t.setLetterSpacing(0.08f);
        return t;
    }

    private LinearLayout.LayoutParams weight(boolean gapAfter) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        if (gapAfter) p.rightMargin = px(30);
        return p;
    }

    private String size(long bytes) {
        return Formatter.formatShortFileSize(this, bytes);
    }

    private int px(float v) {
        return Math.round(v * k);
    }
}
