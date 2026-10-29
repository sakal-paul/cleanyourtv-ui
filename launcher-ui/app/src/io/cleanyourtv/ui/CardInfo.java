package io.cleanyourtv.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the focused card shows over its picture: the genre and rating chips, the title, year · age rating · length,
 * the story and the first three of the cast with their faces. The wide card gets all of it on a dark wash from the
 * left; a tall card a short version (chips, title, year line) at the bottom. From TMDB (the title's page with its
 * credits and age ratings), kept half an hour like every other answer.
 */
final class CardInfo {
    private static final String FACES = "https://image.tmdb.org/t/p/w185";
    private static final long FRESH_MS = 30 * 60_000L;
    private static final int YELLOW = 0xFFFFC83D, WHITE = 0xFFFFFFFF, SOFT = 0xE6FFFFFF, TRAKT = 0xFFFF6B6B;
    private static final LruCache<String, CardInfo> CACHE = new LruCache<>(48);
    /** Titles being fetched, with who to tell when they arrive. */
    private static final java.util.Map<String, List<Runnable>> LOADING = new java.util.HashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    String genre = "", rating = "", year = "", cert = "", length = "", overview = "", cast = "";
    private String kind;
    private int id;
    final Bitmap[] faces = new Bitmap[3];
    private final BitmapShader[] faceShaders = new BitmapShader[3];
    private long at;

    // Text laid out once per width (drawn every frame while focused).
    private StaticLayout storyLayout, castLayout, titleLayout;
    private float storyW, castW, titleW;
    private String titleText;

    /** The info for this title if it is in memory (and starts fetching it when it isn't, or is old); null meanwhile. */
    static CardInfo get(String kind, int id, Runnable ready) {
        String key = kind + ":" + id;
        CardInfo hit;
        synchronized (CACHE) {
            hit = CACHE.get(key);
            if (hit != null && System.currentTimeMillis() - hit.at < FRESH_MS) return hit;
            List<Runnable> waiting = LOADING.get(key);
            if (waiting != null) {
                if (ready != null && !waiting.contains(ready)) waiting.add(ready);
                return hit;
            }
            waiting = new ArrayList<>();
            if (ready != null) waiting.add(ready);
            LOADING.put(key, waiting);
        }
        POOL.execute(() -> {
            CardInfo info = null;
            try {
                info = fetch(kind, id);
            } catch (Exception ignored) {
            }
            List<Runnable> tell;
            synchronized (CACHE) {
                tell = LOADING.remove(key);
                if (info != null) CACHE.put(key, info);
            }
            if (info != null && tell != null) for (Runnable r : tell) MAIN.post(r);
        });
        return hit;
    }

    private static CardInfo fetch(String kind, int id) throws Exception {
        boolean movie = kind.equals("movie");
        JSONObject o = Tmdb.get("/" + kind + "/" + id + "?append_to_response=credits," + (movie ? "release_dates" : "content_ratings"));
        CardInfo c = new CardInfo();
        c.kind = kind;
        c.id = id;
        JSONArray genres = o.optJSONArray("genres");
        if (genres != null && genres.length() > 0) c.genre = genres.getJSONObject(0).optString("name");
        double vote = o.optDouble("vote_average", 0);
        if (vote > 0) c.rating = String.format(Locale.US, "%.1f", vote);
        String date = o.optString(movie ? "release_date" : "first_air_date");
        if (date.length() >= 4) c.year = date.substring(0, 4);
        c.cert = movie ? movieCert(o.optJSONObject("release_dates")) : tvCert(o.optJSONObject("content_ratings"));
        if (movie) {
            int m = o.optInt("runtime");
            if (m > 0) c.length = m >= 60 ? (m / 60) + " h " + (m % 60) + " m" : m + " m";
        } else {
            int s = o.optInt("number_of_seasons");
            if (s > 0) c.length = s == 1 ? Ui.tr("1 temporada", "1 season") : s + Ui.tr(" temporadas", " seasons");
        }
        c.overview = o.optString("overview").trim();
        JSONObject credits = o.optJSONObject("credits");
        JSONArray cast = credits == null ? null : credits.optJSONArray("cast");
        List<String> names = new ArrayList<>();
        for (int i = 0; cast != null && i < cast.length() && names.size() < 3; i++) {
            JSONObject p = cast.getJSONObject(i);
            String face = p.optString("profile_path");
            if (face.isEmpty() || "null".equals(face)) continue;   // a face for every name shown
            Bitmap b = Images.fetch(FACES + face);
            if (b == null) continue;
            c.faces[names.size()] = b;
            names.add(p.optString("name"));
        }
        if (!names.isEmpty()) {
            boolean more = cast.length() > names.size();
            c.cast = TextUtils.join(", ", names) + (more ? Ui.tr(" e outros", " and others") : "");
        }
        c.at = System.currentTimeMillis();
        return c;
    }

    /** The TV's region first, then the US, then any: the first age rating given. */
    private static String movieCert(JSONObject rd) {
        JSONArray r = rd == null ? null : rd.optJSONArray("results");
        for (String want : new String[] {Tmdb.region(), "US", null}) {
            for (int i = 0; r != null && i < r.length(); i++) {
                JSONObject e = r.optJSONObject(i);
                if (e == null || (want != null && !want.equals(e.optString("iso_3166_1")))) continue;
                JSONArray dates = e.optJSONArray("release_dates");
                for (int j = 0; dates != null && j < dates.length(); j++) {
                    String c = dates.optJSONObject(j) == null ? "" : dates.optJSONObject(j).optString("certification").trim();
                    if (!c.isEmpty()) return c;
                }
            }
        }
        return "";
    }

    private static String tvCert(JSONObject cr) {
        JSONArray r = cr == null ? null : cr.optJSONArray("results");
        for (String want : new String[] {Tmdb.region(), "US", null}) {
            for (int i = 0; r != null && i < r.length(); i++) {
                JSONObject e = r.optJSONObject(i);
                if (e == null || (want != null && !want.equals(e.optString("iso_3166_1")))) continue;
                String c = e.optString("rating").trim();
                if (!c.isEmpty()) return c;
            }
        }
        return "";
    }

    // MARK: - Drawing

    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG), LINE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final int SMOOTH = Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG;
    private static final TextPaint TEXT = new TextPaint(SMOOTH);
    private static final Path CLIP = new Path();
    private static final RectF R = new RectF();
    private static final Matrix M = new Matrix();

    /**
     * Over the card's picture at `card` (rounded `corner`), faded by `alpha`; `k` is the screen scale (1 at 1080p).
     * `full` (0 to 1) is how much of the wide card's version shows: while a card grows or shrinks the two versions
     * cross-fade. Each is laid out once at its own final width (`fullW`, `shortW`) and only clipped to the card, so no
     * word moves or rewraps while the card changes size.
     */
    void draw(Canvas c, RectF card, float corner, String title, float alpha, float k, float full, float fullW, float shortW) {
        if (alpha <= 0.01f) return;
        float a = Math.min(1, alpha);
        int layer = c.saveLayerAlpha(card, Math.round(255 * a));
        CLIP.reset();
        CLIP.addRoundRect(card, corner, corner, Path.Direction.CW);
        c.clipPath(CLIP);
        c.translate(card.left, card.top);
        // The words rise into place as they fade in (eased: fast, then settling).
        float e = 1 - (1 - a) * (1 - a) * (1 - a), rise = (1 - e) * 18 * k;
        full = Math.max(0, Math.min(1, full));
        if (full < 0.99f) part(c, false, shortW, card.height(), title, k, 1 - full, rise);
        if (full > 0.01f) part(c, true, fullW, card.height(), title, k, full, rise);
        c.restoreToCount(layer);
    }

    /** One version (wide or tall) at its final size, faded by `a`. */
    private void part(Canvas c, boolean full, float w, float h, String title, float k, float a, float rise) {
        int layer = a < 0.99f ? c.saveLayerAlpha(0, 0, w, h, Math.round(255 * a)) : -1;
        LOCAL.set(0, 0, w, h);
        wash(c, LOCAL, full);
        c.save();
        c.translate(0, rise);
        if (full) drawFull(c, LOCAL, title, k);
        else drawShort(c, LOCAL, title, k);
        c.restore();
        if (layer >= 0) c.restoreToCount(layer);
    }

    private static final RectF LOCAL = new RectF();
    private static LinearGradient washSide, washFoot, washShort;
    private static float sideW, sideH, shortH;

    /** The dark wash under the words: from the left on the wide card, from the bottom on a tall one. */
    private static void wash(Canvas c, RectF r, boolean full) {
        if (full && (washSide == null || sideW != r.width() || sideH != r.height())) {
            sideW = r.width();
            sideH = r.height();
            washSide = new LinearGradient(0, 0, r.width() * 0.75f, 0, new int[] {0xF2000000, 0xB3000000, 0x00000000},
                    new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
            washFoot = new LinearGradient(0, r.height() * 0.6f, 0, r.height(), 0x00000000, 0x99000000, Shader.TileMode.CLAMP);
        }
        if (!full && (washShort == null || shortH != r.height())) {
            shortH = r.height();
            washShort = new LinearGradient(0, r.height() * 0.3f, 0, r.height(), new int[] {0x00000000, 0xB3000000, 0xF2000000},
                    new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
        }
        if (full) {
            FILL.setShader(washSide);
            c.drawRect(r, FILL);
            FILL.setShader(washFoot);
        } else {
            FILL.setShader(washShort);
        }
        c.drawRect(r, FILL);
        FILL.setShader(null);
    }

    private void drawFull(Canvas c, RectF card, String title, float k) {
        float x = card.left + 40 * k, y = card.top + 34 * k, col = Math.min(card.width() * 0.58f, 640 * k);
        float chipBottom = chips(c, x, y, card.right - 40 * k, 22 * k, k);

        TEXT.setTypeface(Ui.displaySemibold);
        TEXT.setTextSize(54 * k);
        TEXT.setColor(WHITE);
        TEXT.setLetterSpacing(0.01f);
        String t = TextUtils.ellipsize(title, TEXT, card.width() - 80 * k, TextUtils.TruncateAt.END).toString();
        float base = chipBottom + 70 * k;
        c.drawText(t, x, base, TEXT);
        TEXT.setLetterSpacing(0);

        float lineMid = base + 44 * k;
        yearLine(c, x, lineMid, 23 * k, k);

        float storyTop = lineMid + 26 * k;
        if (!overview.isEmpty()) {
            if (storyLayout == null || storyW != col) {
                TextPaint p = new TextPaint(SMOOTH);
                p.setTypeface(Ui.poppins);
                p.setTextSize(22 * k);
                p.setColor(SOFT);
                storyLayout = StaticLayout.Builder.obtain(overview, 0, overview.length(), p, Math.round(col))
                        .setLineSpacing(0, 1.18f).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build();
                storyW = col;
            }
            c.save();
            c.translate(x, storyTop);
            storyLayout.draw(c);
            c.restore();
        }

        if (!cast.isEmpty()) {
            float d = 56 * k, cy = card.bottom - 34 * k - d / 2, fx = x;
            int n = 0;
            for (Bitmap b : faces) if (b != null) n++;
            for (int i = 0; i < n; i++) {
                face(c, i, fx + d / 2, cy, d / 2, k);
                fx += d - 12 * k;
            }
            float nx = fx + 12 * k + 18 * k, w = Math.min(card.right - 40 * k - nx, 340 * k);
            if (w > 80 * k) {
                if (castLayout == null || castW != w) {
                    TextPaint p = new TextPaint(SMOOTH);
                    p.setTypeface(Ui.poppins);
                    p.setTextSize(18 * k);
                    p.setColor(SOFT);
                    castLayout = StaticLayout.Builder.obtain(cast, 0, cast.length(), p, Math.round(w))
                            .setLineSpacing(0, 1.15f).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build();
                    castW = w;
                }
                c.save();
                c.translate(nx, cy - castLayout.getHeight() / 2f);
                castLayout.draw(c);
                c.restore();
            }
        }
    }

    private void drawShort(Canvas c, RectF card, String title, float k) {        float x = card.left + 22 * k, w = card.width() - 44 * k;
        float lineMid = card.bottom - 30 * k;
        float lineTop = lineMid - 14 * k;
        if (titleLayout == null || titleW != w || !title.equals(titleText)) {
            TextPaint p = new TextPaint(SMOOTH);
            p.setTypeface(Ui.displaySemibold);
            p.setTextSize(32 * k);
            p.setColor(WHITE);
            titleLayout = StaticLayout.Builder.obtain(title, 0, title.length(), p, Math.round(w))
                    .setLineSpacing(0, 1.0f).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
            titleW = w;
            titleText = title;
        }
        float titleTop = lineTop - 12 * k - titleLayout.getHeight();
        c.save();
        c.translate(x, titleTop);
        titleLayout.draw(c);
        c.restore();
        yearLine(c, x, lineMid, 20 * k, k);
        chips(c, x, titleTop - 12 * k - 34 * k, x + w, 19 * k, k);
    }

    /** "Action" and "7.8 ★" in small dark pills, left to right while they fit; returns their bottom. */
    private float chips(Canvas c, float x, float top, float maxRight, float size, float k) {
        TEXT.setTypeface(Ui.medium);
        TEXT.setTextSize(size);
        float h = size * 1.8f, pad = size * 0.7f, gap = 14 * k;
        // Then from Trakt, when connected: "Next: S2 E4 · 6/10", "New episode Thu" or "✓ Watched", and your rating.
        String tag = Trakt.tag(kind, id);
        int mine = Trakt.rating(kind, id);
        String[] texts = {genre, rating.isEmpty() ? "" : rating + " ★", tag == null ? "" : tag,
                mine > 0 ? Ui.tr("Você ", "You ") + mine + " ♥" : ""};
        int[] colors = {WHITE, YELLOW, TRAKT, TRAKT};
        for (int i = 0; i < texts.length; i++) {
            if (texts[i].isEmpty()) continue;
            float tw = TEXT.measureText(texts[i]), right = x + tw + 2 * pad;
            if (right > maxRight) continue;
            R.set(x, top, right, top + h);
            FILL.setColor(0x8C1C1C1E);
            c.drawRoundRect(R, 8 * k, 8 * k, FILL);
            LINE.setStyle(Paint.Style.STROKE);
            LINE.setStrokeWidth(1.5f * k);
            LINE.setColor(0x33FFFFFF);
            c.drawRoundRect(R, 8 * k, 8 * k, LINE);
            TEXT.setColor(colors[i]);
            c.drawText(texts[i], x + pad, R.centerY() + Ui.halfCap(TEXT), TEXT);
            x = right + gap;
        }
        return top + h;
    }

    /** "2023 | 16+ | 2 h 49 m": thin bars between, the age rating in an outlined box. */
    private void yearLine(Canvas c, float x, float mid, float size, float k) {
        TEXT.setTypeface(Ui.medium);
        TEXT.setTextSize(size);
        TEXT.setColor(SOFT);
        LINE.setStyle(Paint.Style.STROKE);
        LINE.setStrokeWidth(1.5f * k);
        LINE.setColor(0xB3FFFFFF);
        String[] parts = {year, cert, length};
        boolean first = true;
        float half = Ui.halfCap(TEXT), gap = size * 0.6f;
        for (int i = 0; i < 3; i++) {
            if (parts[i].isEmpty()) continue;
            if (!first) {
                c.drawLine(x, mid - size * 0.6f, x, mid + size * 0.6f, LINE);
                x += gap;
            }
            first = false;
            float tw = TEXT.measureText(parts[i]);
            if (i == 1) {
                float pad = size * 0.4f;
                R.set(x, mid - size * 0.72f, x + tw + 2 * pad, mid + size * 0.72f);
                c.drawRoundRect(R, 6 * k, 6 * k, LINE);
                c.drawText(parts[i], x + pad, mid + half, TEXT);
                x = R.right + gap;
            } else {
                c.drawText(parts[i], x, mid + half, TEXT);
                x += tw + gap;
            }
        }
    }

    private void face(Canvas c, int i, float cx, float cy, float r, float k) {
        Bitmap b = faces[i];
        FILL.setColor(0xFF2C2C2E);
        c.drawCircle(cx, cy, r + 2.5f * k, FILL);
        if (faceShaders[i] == null) faceShaders[i] = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        BitmapShader s = faceShaders[i];
        float scale = Math.max(2 * r / b.getWidth(), 2 * r / b.getHeight());
        M.setScale(scale, scale);
        M.postTranslate(cx - b.getWidth() * scale / 2, cy - r);   // faces sit at the top of the portrait
        s.setLocalMatrix(M);
        FILL.setShader(s);
        c.drawCircle(cx, cy, r, FILL);
        FILL.setShader(null);
    }
}
