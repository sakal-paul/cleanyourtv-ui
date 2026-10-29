package io.cleanyourtv.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Material Symbols Rounded icons (Apache 2.0), drawn from a small icon font (see GLYPHS), PNGs as the fallback. Drawn
 * like text: `pointSize` is the size of the font next to the icon, and the icon is centered on that font's cap height.
 */
final class Symbols {
    private Symbols() {}

    /**
     * PNG pixels per em of `pointSize` (a 96 pt × 1.5 px/pt reference scale). tools/make_symbols.py renders a 172 px
     * em, so an icon's em is drawn at ~1.2 × `pointSize`.
     */
    private static final float PX_PER_POINT = 96 * 1.5f;
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF RECT = new RectF();

    /**
     * The icons as glyphs of Material Symbols Rounded (fonts.google.com/icons), drawn as vector text so they stay sharp at
     * any size: assets/fonts/MaterialSymbolsRounded-icons.ttf holds just these, pinned to optical size 24, weight 500,
     * with the FILL axis kept (art/make_icon_font.py). Name → {codepoint, fill}. Any other name falls back to its PNG.
     */
    private static final Map<String, int[]> GLYPHS = new HashMap<>();
    static {
        GLYPHS.put("apps", new int[] {0xe5c3, 1});
        GLYPHS.put("bedtime", new int[] {0xf159, 1});
        GLYPHS.put("bell", new int[] {0xe7f5, 1});
        GLYPHS.put("bitcoin", new int[] {0xebc5, 1});
        GLYPHS.put("check", new int[] {0xe668, 1});
        GLYPHS.put("chevron_down", new int[] {0xe313, 1});
        GLYPHS.put("chevron_right", new int[] {0xe5cc, 1});
        GLYPHS.put("clear_night", new int[] {0xf159, 1});
        GLYPHS.put("cloud", new int[] {0xf15c, 1});
        GLYPHS.put("foggy", new int[] {0xe818, 1});
        GLYPHS.put("gamepad", new int[] {0xf135, 1});
        GLYPHS.put("hdmi", new int[] {0xe8c2, 1});
        GLYPHS.put("home", new int[] {0xe9b2, 1});
        GLYPHS.put("live_tv", new int[] {0xe63a, 1});
        GLYPHS.put("partly_cloudy_day", new int[] {0xf172, 1});
        GLYPHS.put("partly_cloudy_night", new int[] {0xf174, 1});
        GLYPHS.put("play", new int[] {0xe037, 1});
        GLYPHS.put("rainy", new int[] {0xf176, 1});
        GLYPHS.put("search", new int[] {0xef7a, 1});
        GLYPHS.put("settings", new int[] {0xe8b8, 1});
        GLYPHS.put("snowflake", new int[] {0xeb3b, 1});
        GLYPHS.put("sparkles", new int[] {0xe65f, 1});
        GLYPHS.put("speaker", new int[] {0xe32d, 1});
        GLYPHS.put("speed", new int[] {0xe9e4, 1});
        GLYPHS.put("dashboard", new int[] {0xe871, 1});
        GLYPHS.put("sunny", new int[] {0xe81a, 1});
        GLYPHS.put("sunrise", new int[] {0xe1c6, 1});
        GLYPHS.put("sunset", new int[] {0xe1c6, 1});
        GLYPHS.put("sync", new int[] {0xe627, 1});
        GLYPHS.put("thunderstorm", new int[] {0xebdb, 1});
        GLYPHS.put("tune", new int[] {0xe429, 1});
        GLYPHS.put("tv", new int[] {0xe63b, 0});
        GLYPHS.put("tv_settings", new int[] {0xe1de, 1});
        GLYPHS.put("wifi", new int[] {0xe63e, 1});
    }
    /** The PNGs' em was 172 px at PX_PER_POINT: the glyph's em is drawn at the same size, so layouts don't move. */
    private static final float EM = 172f / PX_PER_POINT;
    private static final float REF = 100;
    private static Typeface filled, outlined;
    private static final Map<String, RectF> BOUNDS = new HashMap<>();
    private static final Paint GLYPH = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private static final Rect TMP = new Rect();

    private static Typeface face(Context c, int fill) {
        if (filled == null) {
            try {
                filled = new Typeface.Builder(c.getAssets(), "fonts/MaterialSymbolsRounded-icons.ttf").setFontVariationSettings("'FILL' 1").build();
                outlined = new Typeface.Builder(c.getAssets(), "fonts/MaterialSymbolsRounded-icons.ttf").setFontVariationSettings("'FILL' 0").build();
            } catch (RuntimeException e) {
                return null;
            }
        }
        return fill == 1 ? filled : outlined;
    }

    /** The glyph's ink bounds at REF px, around its drawing origin (null: no glyph for that name). */
    private static RectF bounds(Context c, String name) {
        int[] g = GLYPHS.get(name);
        if (g == null) return null;
        RectF b = BOUNDS.get(name);
        if (b != null) return b;
        Typeface t = face(c, g[1]);
        if (t == null) return null;
        GLYPH.setTypeface(t);
        GLYPH.setTextSize(REF);
        String s = new String(Character.toChars(g[0]));
        GLYPH.getTextBounds(s, 0, s.length(), TMP);
        b = new RectF(TMP);
        BOUNDS.put(name, b);
        return b;
    }

    static Bitmap get(Context c, String name) {
        synchronized (CACHE) {
            if (CACHE.containsKey(name)) return CACHE.get(name);
            Bitmap b = null;
            try (InputStream in = c.getAssets().open("symbols/" + name + ".png")) {
                b = BitmapFactory.decodeStream(in);
                if (b != null) {
                    b = b.extractAlpha();
                    b.setHasMipMap(true);
                }
            } catch (IOException ignored) {
            }
            CACHE.put(name, b);
            return b;
        }
    }

    static float width(Context c, String name, float pointSize) {
        synchronized (PAINT) {
            RectF g = bounds(c, name);
            if (g != null) return g.width() * EM * pointSize / REF;
        }
        Bitmap b = get(c, name);
        return b == null ? 0 : b.getWidth() * pointSize / PX_PER_POINT;
    }

    /** Draws centered at (cx, cy) in the given color. */
    static void draw(Context c, Canvas canvas, String name, float cx, float cy, float pointSize, int color) {
        synchronized (PAINT) {   // card loading (another thread) also draws symbols
            RectF g = bounds(c, name);
            if (g != null) {
                int[] glyph = GLYPHS.get(name);
                float size = EM * pointSize, scale = size / REF;
                GLYPH.setTypeface(face(c, glyph[1]));
                GLYPH.setTextSize(size);
                GLYPH.setColor(color);
                String s = new String(Character.toChars(glyph[0]));
                canvas.drawText(s, cx - g.centerX() * scale, cy - g.centerY() * scale, GLYPH);
                return;
            }
        }
        Bitmap b = get(c, name);
        if (b == null) return;
        synchronized (PAINT) {
            drawLocked(canvas, b, cx, cy, pointSize, color);
        }
    }

    private static void drawLocked(Canvas canvas, Bitmap b, float cx, float cy, float pointSize, int color) {
        float w = b.getWidth() * pointSize / PX_PER_POINT, h = b.getHeight() * pointSize / PX_PER_POINT;
        PAINT.setColor(color);
        RECT.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        canvas.drawBitmap(b, null, RECT, PAINT);
    }

    /** Weather symbol for an Open-Meteo WMO weather code. */
    static String weather(int code, boolean day) {
        if (code <= 1) return day ? "sunny" : "clear_night";
        if (code == 2) return day ? "partly_cloudy_day" : "partly_cloudy_night";
        if (code == 3) return "cloud";
        if (code == 45 || code == 48) return "foggy";
        if (code >= 51 && code <= 57) return "rainy";
        if (code == 65 || code == 67 || code == 82) return "rainy";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "snowflake";
        if (code >= 95) return "thunderstorm";
        return "rainy";
    }
}
