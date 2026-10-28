package io.cleanyourtv.ui;

import android.app.Activity;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A streaming app's own shelf over the top of the home screen: while the focus rests on Netflix, Prime Video, Apple TV,
 * ZEE5, JioHotstar, SonyLIV or hoichoi in the app row, the top turns into that service's hero (genre, rating, title,
 * year · age · length, story, cast, Watch now) and its Latest, Popular and Top rated rows, from TMDB. Up from the app
 * row goes in; Down from the cards or Back comes out. Nothing is fetched until a service tile is focused, and TMDB's
 * answers are cached on disk like the rest of the app.
 */
final class ServicePanel extends FrameLayout {
    /** Package → {name, TMDB provider id, region, brand colour}. India's catalogue for the services TMDB has only there. */
    private static final Map<String, String[]> SERVICES = new HashMap<>();
    static {
        SERVICES.put("com.netflix.ninja", new String[] {"Netflix", "8", "AE", "E50914"});
        SERVICES.put("com.amazon.amazonvideo.livingroom", new String[] {"Prime Video", "119", "AE", "1A98FF"});
        SERVICES.put("com.apple.atve.androidtv.appletv", new String[] {"Apple TV", "350", "AE", "F5F5F7"});
        SERVICES.put("com.graymatrix.did", new String[] {"ZEE5", "232", "AE", "8230C6"});
        SERVICES.put("in.startv.hotstar", new String[] {"JioHotstar", "2336", "IN", "1F80E0"});
        SERVICES.put("com.sonyliv", new String[] {"Sony LIV", "237", "IN", "F04E23"});
        SERVICES.put("com.viewlift.hoichoi", new String[] {"hoichoi", "315", "IN", "E3262E"});
    }
    private static final String CARD_IMAGE = "https://image.tmdb.org/t/p/w780";
    private static final int ROW_MAX = 20;
    private static final int WATCH = 0, TABS = 1, CARDS = 2;
    static final float HEIGHT = 800;

    interface Host {
        /** The panel gave the focus back to the app row. */
        void onPanelExit();
    }

    private final Activity activity;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float k;
    private final Map<String, String> genreNames = new HashMap<>();

    private String pkg;                 // the service shown (or loading), null when hidden
    private String[] service;
    private boolean active;             // the panel has the remote
    private int section = CARDS, tab, cardIndex;
    private final List<List<Tmdb.Title>> rows = new ArrayList<>();
    private Tmdb.Title hero;
    private int generation;             // bumped per service: late answers for an old one are dropped

    private final ImageView backdrop;
    private final TextView genreChip, ratingChip, titleView, metaView, overviewView, castView, watch;
    private final LinearLayout faces, tabsRow, cardsRow;
    private final HorizontalScrollView scroller;
    private final TextView[] tabs = new TextView[3];
    private final TextView status;

    ServicePanel(Activity a, Host host) {
        super(a);
        activity = a;
        this.host = host;
        k = TvUi.k(a);
        setBackgroundColor(TvUi.BG);
        setAlpha(0);
        setVisibility(GONE);
        setClipChildren(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);

        backdrop = new ImageView(a);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(backdrop, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View sideScrim = new View(a);
        sideScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xF20B0D12, 0xB30B0D12, 0x000B0D12}));
        addView(sideScrim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View bottomScrim = new View(a);
        bottomScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x000B0D12, 0x000B0D12, 0xE60B0D12, 0xFF0B0D12}));
        addView(bottomScrim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(px(80), px(52), px(80), 0);
        addView(column, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout chips = new LinearLayout(a);
        genreChip = chip(TvUi.INK);
        ratingChip = chip(0xFFF5C518);
        chips.addView(genreChip);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = px(16);
        chips.addView(ratingChip, gap);
        column.addView(chips);

        titleView = TvUi.text(a, k, 76, Ui.displaySemibold, TvUi.INK);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setPadding(0, px(22), px(500), 0);
        column.addView(titleView);

        metaView = TvUi.text(a, k, 28, Ui.medium, TvUi.INK);
        metaView.setPadding(0, px(18), 0, 0);
        column.addView(metaView);

        overviewView = TvUi.text(a, k, 28, Ui.regular, 0xE6F5F6F8);
        overviewView.setMaxLines(2);
        overviewView.setEllipsize(TextUtils.TruncateAt.END);
        overviewView.setLineSpacing(px(8), 1);
        overviewView.setPadding(0, px(16), px(700), 0);
        column.addView(overviewView);

        LinearLayout castRow = new LinearLayout(a);
        castRow.setGravity(Gravity.CENTER_VERTICAL);
        castRow.setClipChildren(false);
        castRow.setPadding(0, px(20), 0, 0);
        faces = new LinearLayout(a);
        castRow.addView(faces);
        castView = TvUi.text(a, k, 24, Ui.regular, 0xCCF5F6F8);
        castView.setMaxLines(2);
        castView.setPadding(px(20), 0, px(40), 0);
        castRow.addView(castView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        watch = TvUi.text(a, k, 30, Ui.semibold, 0xFFFFFFFF);
        watch.setText(Ui.tr("Assistir agora  ▶", "Watch now  ▶"));
        watch.setGravity(Gravity.CENTER);
        watch.setPadding(px(44), px(18), px(44), px(18));
        watch.setFocusable(true);
        watch.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.08f : 1).scaleY(has ? 1.08f : 1).setDuration(150).start());
        castRow.addView(watch);
        column.addView(castRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(84)));

        tabsRow = new LinearLayout(a);
        tabsRow.setGravity(Gravity.CENTER_VERTICAL);
        tabsRow.setPadding(0, px(30), 0, 0);
        String[] names = {Ui.tr("Lançamentos", "Latest"), Ui.tr("Populares", "Popular"), Ui.tr("Mais bem avaliados", "Top rated")};
        for (int i = 0; i < 3; i++) {
            TextView t = TvUi.text(a, k, 32, Ui.semibold, TvUi.DIM);
            t.setText(names[i]);
            t.setPadding(px(22), px(8), px(22), px(8));
            t.setFocusable(true);
            tabs[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i == 0) lp.leftMargin = -px(22);
            lp.rightMargin = px(10);
            tabsRow.addView(t, lp);
            rows.add(null);
        }
        status = TvUi.text(a, k, 24, Ui.regular, TvUi.FAINT);
        status.setPadding(px(20), 0, 0, 0);
        tabsRow.addView(status);
        column.addView(tabsRow);

        scroller = new HorizontalScrollView(a);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setSmoothScrollingEnabled(true);
        cardsRow = new LinearLayout(a);
        cardsRow.setClipChildren(false);
        cardsRow.setPadding(px(80), px(22), px(80), px(22));
        scroller.addView(cardsRow);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(236));
        sp.leftMargin = -px(80);
        sp.rightMargin = -px(80);
        column.addView(scroller, sp);
        markTabs();
    }

    static boolean isService(String pkg) {
        return pkg != null && SERVICES.containsKey(pkg);
    }

    boolean isShowing() { return pkg != null; }

    boolean isActive() { return active; }

    String service() { return pkg; }

    /** Shows this service (fades in), or hides the panel for null / an app that isn't a service. */
    void show(String p) {
        if (!isService(p)) p = null;
        if (p == null) {
            if (pkg == null) return;
            pkg = null;
            active = false;
            setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            animate().alpha(0).setDuration(180).withEndAction(() -> { if (pkg == null) setVisibility(GONE); }).start();
            return;
        }
        if (!p.equals(pkg)) load(p);
        setVisibility(VISIBLE);
        animate().alpha(1).setDuration(220).withEndAction(null).start();
    }

    /** Up from the app row: the panel takes the remote, on the cards of the open tab. */
    boolean enter() {
        if (pkg == null || rows.get(tab) == null || rows.get(tab).isEmpty()) return false;
        active = true;
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        section = CARDS;
        focusCard(Math.min(cardIndex, cardsRow.getChildCount() - 1));
        return true;
    }

    private void exit() {
        active = false;
        View f = findFocus();
        if (f != null) f.clearFocus();
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        markTabs();
        host.onPanelExit();
    }

    /** Keys while the panel has the remote; true when used. */
    boolean onKey(KeyEvent e) {
        if (!active) return false;
        int code = e.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean arrow = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (!ok && !arrow && code != KeyEvent.KEYCODE_BACK) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (code == KeyEvent.KEYCODE_BACK) {
            exit();
            return true;
        }
        switch (section) {
            case CARDS:
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) focusCard(cardIndex - 1);
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) focusCard(cardIndex + 1);
                else if (code == KeyEvent.KEYCODE_DPAD_UP) focusTabs();
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN) exit();
                else if (ok) play(currentRow().get(cardIndex));
                break;
            case TABS:
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && tab > 0) selectTab(tab - 1);
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && tab < 2) selectTab(tab + 1);
                else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    section = WATCH;
                    watch.requestFocus();
                    markTabs();
                } else if ((code == KeyEvent.KEYCODE_DPAD_DOWN || ok) && cardsRow.getChildCount() > 0) {
                    section = CARDS;
                    focusCard(Math.min(cardIndex, cardsRow.getChildCount() - 1));
                }
                break;
            default:
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) focusTabs();
                else if (ok && hero != null) play(hero);
                break;
        }
        return true;
    }

    // MARK: - Data

    private void load(String p) {
        pkg = p;
        service = SERVICES.get(p);
        int gen = ++generation;
        hero = null;
        tab = 0;
        cardIndex = 0;
        for (int i = 0; i < 3; i++) rows.set(i, null);
        cardsRow.removeAllViews();
        int brand = 0xFF000000 | Integer.parseInt(service[3], 16);
        watch.setBackground(TvUi.round(brand, px(14)));
        watch.setTextColor(brand == 0xFFF5F5F7 ? 0xFF111111 : 0xFFFFFFFF);
        backdrop.setImageDrawable(null);
        genreChip.setVisibility(GONE);
        ratingChip.setVisibility(GONE);
        titleView.setText(service[0]);
        metaView.setText("");
        overviewView.setText(Ui.tr("Carregando o que tem no ", "Loading what's on ") + service[0] + "…");
        castView.setText("");
        faces.removeAllViews();
        status.setText("");
        markTabs();
        new Thread(() -> {
            try {
                loadGenres();
                List<Tmdb.Title> first = fetchRow(0);
                main.post(() -> {
                    if (gen != generation) return;
                    rows.set(0, first);
                    if (tab == 0) showRow();
                    if (!first.isEmpty()) setHero(first.get(0));
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (gen != generation) return;
                    overviewView.setText(Ui.tr("Não deu para buscar: ", "Couldn't load: ") + e.getMessage());
                });
            }
        }, "service-panel").start();
    }

    private void loadGenres() throws Exception {
        if (!genreNames.isEmpty()) return;
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray g = Tmdb.get("/genre/" + kind + "/list").optJSONArray("genres");
            for (int i = 0; g != null && i < g.length(); i++) {
                JSONObject o = g.getJSONObject(i);
                genreNames.put(String.valueOf(o.getInt("id")), o.getString("name"));
            }
        }
    }

    /** One tab's titles: movies and shows from this service, alternated, only the ones with a picture. */
    private List<Tmdb.Title> fetchRow(int which) throws Exception {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String where = "&watch_region=" + service[2] + "&with_watch_providers=" + service[1] + "&include_adult=false";
        String movie, tv;
        if (which == 0) {
            movie = "sort_by=primary_release_date.desc&primary_release_date.lte=" + today + "&vote_count.gte=10";
            tv = "sort_by=first_air_date.desc&first_air_date.lte=" + today + "&vote_count.gte=10";
        } else if (which == 1) {
            movie = tv = "sort_by=popularity.desc";
        } else {
            movie = "sort_by=vote_average.desc&vote_count.gte=300";
            tv = "sort_by=vote_average.desc&vote_count.gte=150";
        }
        List<Tmdb.Title> m = Tmdb.titles(Tmdb.get("/discover/movie?" + movie + where), "movie");
        List<Tmdb.Title> s = Tmdb.titles(Tmdb.get("/discover/tv?" + tv + where), "tv");
        List<Tmdb.Title> out = new ArrayList<>();
        for (int i = 0; out.size() < ROW_MAX && (i < m.size() || i < s.size()); i++) {
            if (i < s.size() && hasBackdrop(s.get(i))) out.add(s.get(i));
            if (out.size() < ROW_MAX && i < m.size() && hasBackdrop(m.get(i))) out.add(m.get(i));
        }
        return out;
    }

    private static boolean hasBackdrop(Tmdb.Title t) {
        return t.backdrop != null && !t.backdrop.isEmpty() && !"null".equals(t.backdrop);
    }

    private List<Tmdb.Title> currentRow() {
        List<Tmdb.Title> r = rows.get(tab);
        return r == null ? new ArrayList<>() : r;
    }

    private void selectTab(int t) {
        tab = t;
        cardIndex = 0;
        markTabs();
        tabs[t].requestFocus();
        if (rows.get(t) != null) {
            showRow();
            return;
        }
        cardsRow.removeAllViews();
        status.setText(Ui.tr("Carregando…", "Loading…"));
        int gen = generation;
        new Thread(() -> {
            try {
                List<Tmdb.Title> got = fetchRow(t);
                main.post(() -> {
                    if (gen != generation) return;
                    rows.set(t, got);
                    if (tab == t) showRow();
                });
            } catch (Exception e) {
                main.post(() -> { if (gen == generation && tab == t) status.setText(e.getMessage()); });
            }
        }, "service-row").start();
    }

    private void showRow() {
        cardsRow.removeAllViews();
        List<Tmdb.Title> r = currentRow();
        status.setText(r.isEmpty() ? Ui.tr("Nada aqui por enquanto", "Nothing here yet") : "");
        for (Tmdb.Title t : r) cardsRow.addView(card(t));
        scroller.scrollTo(0, 0);
    }

    // MARK: - Hero

    private void setHero(Tmdb.Title t) {
        hero = t;
        Images.load(backdrop, Tmdb.BACKDROP + t.backdrop);
        titleView.setText(t.name);
        String g = t.genres.length > 0 ? genreNames.get(String.valueOf(t.genres[0])) : null;
        genreChip.setText(g == null ? "" : g);
        genreChip.setVisibility(g == null ? GONE : VISIBLE);
        ratingChip.setText(String.format(Locale.US, "%.1f ★", t.vote));
        ratingChip.setVisibility(t.vote > 0 ? VISIBLE : GONE);
        metaView.setText(t.year);
        overviewView.setText(t.overview);
        castView.setText("");
        faces.removeAllViews();
        int gen = generation;
        String key = t.key();
        new Thread(() -> {
            try {
                boolean movie = t.kind.equals("movie");
                JSONObject o = Tmdb.get("/" + t.kind + "/" + t.id + "?append_to_response="
                        + (movie ? "release_dates,credits" : "content_ratings,aggregate_credits"));
                String meta = meta(o, t, movie);
                List<String[]> cast = cast(o, movie);
                main.post(() -> {
                    if (gen != generation || hero == null || !hero.key().equals(key)) return;
                    metaView.setText(meta);
                    showCast(cast);
                });
            } catch (Exception ignored) {
            }
        }, "service-hero").start();
    }

    private String meta(JSONObject o, Tmdb.Title t, boolean movie) {
        List<String> parts = new ArrayList<>();
        if (!t.year.isEmpty()) parts.add(t.year);
        String cert = certification(o, movie);
        if (!cert.isEmpty()) parts.add(cert);
        if (movie) {
            int min = o.optInt("runtime");
            if (min > 0) parts.add(min >= 60 ? (min / 60) + " h " + (min % 60) + " m" : min + " m");
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0) parts.add(seasons + " " + (seasons == 1 ? Ui.tr("temporada", "season") : Ui.tr("temporadas", "seasons")));
        }
        return TextUtils.join("  |  ", parts);
    }

    /** The age rating for the service's region (as the detail page reads it), then the US one. */
    private String certification(JSONObject o, boolean movie) {
        for (String region : new String[] {service[2], "US"}) {
            try {
                JSONArray results = o.getJSONObject(movie ? "release_dates" : "content_ratings").getJSONArray("results");
                for (int i = 0; i < results.length(); i++) {
                    JSONObject r = results.getJSONObject(i);
                    if (!region.equals(r.optString("iso_3166_1"))) continue;
                    if (!movie) return r.optString("rating");
                    JSONArray dates = r.getJSONArray("release_dates");
                    for (int j = 0; j < dates.length(); j++) {
                        String c = dates.getJSONObject(j).optString("certification");
                        if (!c.isEmpty()) return c;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static List<String[]> cast(JSONObject o, boolean movie) {
        List<String[]> out = new ArrayList<>();
        JSONObject credits = o.optJSONObject(movie ? "credits" : "aggregate_credits");
        JSONArray c = credits == null ? null : credits.optJSONArray("cast");
        for (int i = 0; c != null && i < c.length() && out.size() < 3; i++) {
            JSONObject p = c.optJSONObject(i);
            if (p == null) continue;
            String face = p.optString("profile_path", "");
            out.add(new String[] {p.optString("name"), face.isEmpty() || "null".equals(face) ? "" : Tmdb.PROFILE + face});
        }
        return out;
    }

    private void showCast(List<String[]> cast) {
        faces.removeAllViews();
        List<String> names = new ArrayList<>();
        int size = px(64);
        for (int i = 0; i < cast.size(); i++) {
            names.add(cast.get(i)[0]);
            ImageView face = new ImageView(activity);
            face.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(TvUi.CARD);
            ring.setStroke(px(3), 0xFF0B0D12);
            face.setBackground(ring);
            face.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline out) { out.setOval(0, 0, v.getWidth(), v.getHeight()); }
            });
            face.setClipToOutline(true);
            if (!cast.get(i)[1].isEmpty()) Images.load(face, cast.get(i)[1]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) lp.leftMargin = -px(14);
            faces.addView(face, lp);
        }
        castView.setText(names.isEmpty() ? "" : TextUtils.join(", ", names) + (names.size() == 3 ? Ui.tr(" e outros", " and others") : ""));
    }

    // MARK: - Cards

    private View card(Tmdb.Title t) {
        float r = px(18);
        FrameLayout card = new FrameLayout(activity);
        card.setBackground(TvUi.round(TvUi.CARD, r));
        card.setClipToOutline(true);
        ImageView img = new ImageView(activity);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Images.load(img, CARD_IMAGE + t.backdrop);
        card.addView(img, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(activity);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x55000000, 0x00000000, 0xD9000000}));
        card.addView(shade, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        String g = t.genres.length > 0 ? genreNames.get(String.valueOf(t.genres[0])) : null;
        if (g != null) {
            TextView gc = smallChip(g, TvUi.INK);
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            lp.setMargins(px(14), px(14), 0, 0);
            card.addView(gc, lp);
        }
        if (t.vote > 0) {
            TextView rc = smallChip(String.format(Locale.US, "%.1f ★", t.vote), 0xFFF5C518);
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            lp.setMargins(0, px(14), px(14), 0);
            card.addView(rc, lp);
        }
        TextView name = TvUi.text(activity, k, 28, Ui.semibold, 0xFFFFFFFF);
        name.setText(t.name);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams np = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        np.setMargins(px(20), 0, px(20), px(18));
        card.addView(name, np);
        TvUi.focusable(card, k, r);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(340), px(192));
        lp.rightMargin = px(26);
        card.setLayoutParams(lp);
        return card;
    }

    private void focusCard(int i) {
        int n = cardsRow.getChildCount();
        if (n == 0) return;
        i = Math.max(0, Math.min(n - 1, i));
        cardIndex = i;
        section = CARDS;
        View v = cardsRow.getChildAt(i);
        v.requestFocus();
        // Keep a neighbour in view on the left too (requestFocus alone scrolls only as far as the card).
        int left = Math.max(0, v.getLeft() - px(80) - (i > 0 ? px(366) : 0));
        if (v.getLeft() - scroller.getScrollX() < px(366) && i > 0) scroller.smoothScrollTo(left, 0);
        markTabs();
        List<Tmdb.Title> r = currentRow();
        if (i < r.size()) {
            Tmdb.Title t = r.get(i);
            main.removeCallbacks(heroLater);
            pendingHero = t;
            main.postDelayed(heroLater, 280);   // resting on a card, not sliding past it
        }
    }

    private Tmdb.Title pendingHero;
    private final Runnable heroLater = () -> { if (pendingHero != null && pkg != null) setHero(pendingHero); };

    private void focusTabs() {
        section = TABS;
        tabs[tab].requestFocus();
        markTabs();
    }

    private void markTabs() {
        for (int i = 0; i < 3; i++) {
            boolean selected = i == tab, focused = active && section == TABS && selected;
            tabs[i].setTextColor(focused ? 0xFF0B0D12 : selected ? TvUi.INK : TvUi.DIM);
            tabs[i].setBackground(focused ? TvUi.round(0xFFFFFFFF, px(30)) : null);
        }
    }

    // MARK: - Watching

    /** The title in its app when Wikidata knows its link there; otherwise the title's page (with Watch on …). */
    private void play(Tmdb.Title t) {
        String p = pkg;
        new Thread(() -> {
            String url = DeepLinks.find(activity, t.kind, t.id, t.name).get(p);
            main.post(() -> { if (!DeepLinks.open(activity, p, url)) DetailActivity.open(activity, t.kind, t.id); });
        }, "service-watch").start();
    }

    // MARK: - Small views

    private TextView chip(int color) {
        TextView t = TvUi.text(activity, k, 28, Ui.medium, color);
        t.setPadding(px(18), px(8), px(18), px(8));
        t.setBackground(TvUi.round(0x40000000 | 0x2A2D35, px(10)));
        return t;
    }

    private TextView smallChip(String text, int color) {
        TextView t = TvUi.text(activity, k, 20, Ui.medium, color);
        t.setText(text);
        t.setPadding(px(12), px(5), px(12), px(5));
        t.setBackground(TvUi.round(0x99000000, px(8)));
        return t;
    }

    private int px(float v) {
        return Math.round(v * k);
    }
}
