package io.cleanyourtv.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
 * A streaming app's own page in the home screen's design: while the focus rests on Netflix, Prime Video, Apple TV,
 * ZEE5, JioHotstar, SonyLIV or hoichoi in the app row, the service's name takes the place of "For You", its tabs
 * (Latest, Popular, Top rated) the place of Explore, Movies and Shows, and its titles the three cards (one wide, two
 * tall), from TMDB. The panel is see-through: the home screen's background shows, and it follows light and dark.
 * Up from the app row goes to the cards; Down from the cards or Back comes out.
 */
final class ServicePanel extends FrameLayout {
    /** Package → {name, TMDB provider id, region}. India's catalogue for the services TMDB has only there. */
    private static final Map<String, String[]> SERVICES = new HashMap<>();
    static {
        SERVICES.put("com.netflix.ninja", new String[] {"Netflix", "8", "AE"});
        SERVICES.put("com.amazon.amazonvideo.livingroom", new String[] {"Prime Video", "119", "AE"});
        SERVICES.put("com.apple.atve.androidtv.appletv", new String[] {"Apple TV", "350", "AE"});
        SERVICES.put("com.graymatrix.did", new String[] {"ZEE5", "232", "AE"});
        SERVICES.put("in.startv.hotstar", new String[] {"JioHotstar", "2336", "IN"});
        SERVICES.put("com.sonyliv", new String[] {"Sony LIV", "237", "IN"});
        SERVICES.put("com.viewlift.hoichoi", new String[] {"hoichoi", "315", "IN"});
    }
    private static final String WIDE_IMAGE = "https://image.tmdb.org/t/p/w1280";
    private static final int ROW_MAX = 21;     // seven pages of three
    private static final int TABS = 1, CARDS = 2;
    // The home screen's light design (HomeView): margins, title, tab bar and cards, in 1080p pixels.
    private static final float MARGIN = 80, CONTENT_R = 1840, TITLE_BASE = 130, TABBAR_TOP = 158, TABBAR_H = 68;
    private static final float CARDS_TOP = 262, CARD_H = 442, TALL_W = 320, CARD_GAP = 40;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, WHITE = 0xFFF5F5F7, N_DIM = 0xFF98989D;
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
    private int section = CARDS, tab, page, cardIndex;
    private final List<List<Tmdb.Title>> rows = new ArrayList<>();
    private int generation;             // bumped per service: late answers for an old one are dropped
    private final long[] rowsAt = new long[3];   // when each tab was fetched: refetched after half an hour
    private long lastKeyAt;
    private static final long REFRESH_MS = 30 * 60_000L, TURN_MS = 15_000L;

    /** Every 15 s while shown: a stale tab is fetched again, and the cards move on to the next page when idle. */
    private final Runnable autoTick = new Runnable() {
        @Override public void run() {
            if (pkg == null) return;
            long now = System.currentTimeMillis();
            if (rows.get(tab) != null && now - rowsAt[tab] > REFRESH_MS) {
                refetch(tab);
            } else if (!(active && section == CARDS) && android.os.SystemClock.uptimeMillis() - lastKeyAt > TURN_MS) {
                int pages = (currentRow().size() + 2) / 3;
                if (pages > 1) {
                    page = (page + 1) % pages;
                    slide();
                }
            }
            main.postDelayed(this, TURN_MS);
        }
    };
    private float night;

    private final TextView titleView, status;
    private final TextView[] tabs = new TextView[3];
    private final FrameLayout[] cards = new FrameLayout[3];
    private final ImageView[] images = new ImageView[3];
    private final TextView[] names = new TextView[3];
    private final TextView[] metas = new TextView[3];
    private final InfoView[] infos = new InfoView[3];
    /** The large card (0 to 2): a tall one the focus rests on for GROW_MS grows into it, the others turn tall. */
    private int wideSlot;
    private static final long GROW_MS = 1000;
    private final float[] lefts = new float[3], widths = new float[3];
    private ValueAnimator growing;
    private final Runnable grow = () -> {
        if (active && section == CARDS && cardIndex != wideSlot && cardIndex < pageSize()) setWide(cardIndex, true);
    };

    ServicePanel(Activity a, Host host) {
        super(a);
        activity = a;
        this.host = host;
        k = TvUi.k(a);
        setAlpha(0);
        setVisibility(GONE);
        setClipChildren(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);

        titleView = TvUi.text(a, k, 76, Ui.displaySemibold, L_INK);
        titleView.setSingleLine(true);
        put(titleView, MARGIN, TITLE_BASE - 76 * 0.94f, CONTENT_R - MARGIN, -2);

        String[] labels = {Ui.tr("Lançamentos", "Latest"), Ui.tr("Populares", "Popular"), Ui.tr("Mais bem avaliados", "Top rated")};
        float x = MARGIN - 26;
        for (int i = 0; i < 3; i++) {
            TextView t = TvUi.text(a, k, 30, Ui.medium, L_DIM);
            t.setText(labels[i]);
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(26), 0, px(26), 0);
            t.setFocusable(true);
            t.measure(0, 0);
            tabs[i] = t;
            put(t, x, TABBAR_TOP, -2, TABBAR_H);
            x += t.getMeasuredWidth() / k + 4;
            rows.add(null);
        }
        status = TvUi.text(a, k, 25, Ui.medium, L_DIM);
        status.setGravity(Gravity.CENTER_VERTICAL);
        put(status, x + 20, TABBAR_TOP, -2, TABBAR_H);

        slots(0, lefts, widths);
        for (int i = 0; i < 3; i++) {
            FrameLayout c = new FrameLayout(a);
            c.setBackground(TvUi.round(0xFFDADADF, px(18)));
            c.setClipToOutline(true);
            ImageView img = new ImageView(a);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            c.addView(img, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            InfoView info = new InfoView();
            info.full = i == 0 ? 1 : 0;
            info.setAlpha(0);
            c.addView(info, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            infos[i] = info;
            TvUi.focusable(c, k, px(18));
            cards[i] = c;
            images[i] = img;
            put(c, lefts[i], CARDS_TOP, widths[i], CARD_H);
            TextView n = TvUi.text(a, k, 31, Ui.semibold, L_INK);
            n.setLetterSpacing(0.012f);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.END);
            names[i] = n;
            put(n, lefts[i], CARDS_TOP + CARD_H + 46 - 31 * 0.94f, widths[i], -2);
        }
        for (int i = 0; i < 3; i++) {
            TextView m = TvUi.text(a, k, 24, Ui.poppins, L_DIM);
            m.setSingleLine(true);
            m.setEllipsize(TextUtils.TruncateAt.END);
            metas[i] = m;
            put(m, lefts[i], CARDS_TOP + CARD_H + 80 - 25 * 0.94f, widths[i], -2);
        }
        setNight(0);
    }

    /** The cards' lefts and widths (1080p) with card `wide` the large one. */
    private static void slots(int wide, float[] l, float[] w) {
        float big = (CONTENT_R - MARGIN) - 2 * TALL_W - 2 * CARD_GAP, x = MARGIN;
        for (int i = 0; i < 3; i++) {
            l[i] = x;
            w[i] = i == wide ? big : TALL_W;
            x += w[i] + CARD_GAP;
        }
    }

    /** Moves each card (and with `lines`, the lines under it) to these lefts and widths (1080p). */
    private void place(float[] l, float[] w, boolean lines) {
        for (int i = 0; i < 3; i++) {
            for (View v : lines ? new View[] {cards[i], names[i], metas[i]} : new View[] {cards[i]}) {
                LayoutParams lp = (LayoutParams) v.getLayoutParams();
                lp.leftMargin = px(l[i]);
                lp.width = px(w[i]);
                v.setLayoutParams(lp);
            }
        }
    }

    private int growGeneration;

    /**
     * Card `slot` becomes the large one: gliding there (animate), or at once for a new page. The lines under the cards
     * fade out first, and come back with their new text once the cards have settled, so nothing rewraps mid-move; the
     * details over the focused card cross-fade between their tall and wide versions.
     */
    private void setWide(int slot, boolean animate) {
        if (growing != null) growing.cancel();
        main.removeCallbacks(grow);
        float[] fromL = lefts.clone(), fromW = widths.clone();
        float[] fromFull = new float[3];
        for (int i = 0; i < 3; i++) fromFull[i] = infos[i].full;
        wideSlot = slot;
        slots(slot, lefts, widths);
        int gen = ++growGeneration;
        if (!animate) {
            place(lefts, widths, true);
            for (int i = 0; i < 3; i++) {
                infos[i].full = i == slot ? 1 : 0;
                infos[i].invalidate();
            }
            showPage();
            return;
        }
        for (int i = 0; i < 3; i++) {
            for (View v : new View[] {names[i], metas[i]}) {
                v.animate().cancel();
                v.animate().alpha(0).setStartDelay(0).setDuration(110).start();
            }
        }
        float[] l = new float[3], w = new float[3];
        growing = ValueAnimator.ofFloat(0, 1).setDuration(460);
        growing.setInterpolator(TvUi.EASE);
        growing.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            for (int i = 0; i < 3; i++) {
                l[i] = fromL[i] + (lefts[i] - fromL[i]) * t;
                w[i] = fromW[i] + (widths[i] - fromW[i]) * t;
                infos[i].full = fromFull[i] + ((i == slot ? 1 : 0) - fromFull[i]) * t;
                infos[i].invalidate();
            }
            place(l, w, false);
        });
        growing.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (gen != growGeneration) return;
                place(lefts, widths, true);
                showPage();
                for (int i = 0; i < 3; i++) {
                    for (View v : new View[] {names[i], metas[i]}) {
                        v.animate().cancel();
                        v.animate().alpha(1).setStartDelay(40).setDuration(240).setInterpolator(TvUi.EASE).start();
                    }
                }
            }
        });
        growing.start();
    }

    /** A new page, tab or service: the first card large again. */
    private void resetWide() {
        if (wideSlot != 0) setWide(0, false);
    }

    /** The details over the focused card only, while the panel has the remote. */
    private void showInfo() {
        List<Tmdb.Title> r = currentRow();
        for (int i = 0; i < 3; i++) {
            int at = page * 3 + i;
            Tmdb.Title t = at < r.size() ? r.get(at) : null;
            infos[i].title = t;
            boolean on = t != null && active && section == CARDS && i == cardIndex;
            infos[i].animate().cancel();
            if (on && infos[i].getAlpha() < 0.05f) infos[i].setTranslationY(px(18));
            infos[i].animate().alpha(on ? 1 : 0).translationY(0).setInterpolator(TvUi.EASE).setDuration(on ? 360 : 160).start();
            infos[i].invalidate();
        }
    }

    /** Draws CardInfo over a card's picture (fetching it the first time). */
    private final class InfoView extends View {
        Tmdb.Title title;
        float full;   // 1: the wide card's version of the details, 0: the tall one's (cross-faded while growing)
        private final RectF box = new RectF();

        InfoView() {
            super(activity);
        }

        @Override protected void onDraw(Canvas c) {
            // Drawn even while faded out: fading in only changes the view's alpha and doesn't draw it again.
            if (title == null) return;
            CardInfo info = CardInfo.get(title.kind, title.id, this::invalidate);
            if (info == null) return;
            box.set(0, 0, getWidth(), getHeight());
            info.draw(c, box, px(18), title.name, 1, k, full, px((CONTENT_R - MARGIN) - 2 * TALL_W - 2 * CARD_GAP), px(TALL_W));
        }
    }

    /** Places a child at 1080p coordinates (-2: its own size). */
    private void put(View v, float left, float top, float width, float height) {
        LayoutParams lp = new LayoutParams(width == -2 ? ViewGroup.LayoutParams.WRAP_CONTENT : px(width),
                height == -2 ? ViewGroup.LayoutParams.WRAP_CONTENT : px(height));
        lp.leftMargin = px(left);
        lp.topMargin = px(top);
        addView(v, lp);
    }

    static boolean isService(String pkg) {
        return pkg != null && SERVICES.containsKey(pkg);
    }

    boolean isShowing() { return pkg != null; }

    boolean isActive() { return active; }

    String service() { return pkg; }

    /** The home screen's day ⇄ night value: dark text by day, white at night, as on the home screen. */
    void setNight(float n) {
        night = n;
        int ink = blend(L_INK, WHITE, n), dim = blend(L_DIM, N_DIM, n);
        titleView.setTextColor(ink);
        for (TextView t : names) t.setTextColor(ink);
        for (TextView m : metas) m.setTextColor(dim);
        status.setTextColor(dim);
        for (FrameLayout c : cards) c.setBackground(TvUi.round(blend(0xFFDADADF, 0xFF2C2C2E, n), px(18)));
        markTabs();
    }

    /** Shows this service (fades in), or hides the panel for null / an app that isn't a service. */
    void show(String p) {
        if (!isService(p)) p = null;
        if (p == null) {
            if (pkg == null) return;
            pkg = null;
            active = false;
            main.removeCallbacks(autoTick);
            setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            animate().alpha(0).setDuration(160).withEndAction(() -> { if (pkg == null) setVisibility(GONE); }).start();
            return;
        }
        if (!p.equals(pkg)) load(p);
        else if (rows.get(tab) != null && System.currentTimeMillis() - rowsAt[tab] > REFRESH_MS) refetch(tab);
        main.removeCallbacks(autoTick);
        main.postDelayed(autoTick, TURN_MS);
        setVisibility(VISIBLE);
        animate().alpha(1).setDuration(220).withEndAction(null).start();
    }

    /** The TV was turned on: every tab is fetched again the next time a service's page shows. */
    void powerOn() {
        for (int i = 0; i < rowsAt.length; i++) rowsAt[i] = 0;
    }

    /** Up from the app row: the panel takes the remote, on the first card in view. */
    boolean enter() {
        if (pkg == null || pageSize() == 0) return false;
        active = true;
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        focusCard(Math.min(cardIndex, pageSize() - 1));
        return true;
    }

    private void exit() {
        active = false;
        main.removeCallbacks(grow);
        if (wideSlot != 0) setWide(0, true);
        View f = findFocus();
        if (f != null) f.clearFocus();
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        markTabs();
        host.onPanelExit();
    }

    /** Keys while the panel has the remote; true when used. */
    boolean onKey(KeyEvent e) {
        if (!active) return false;
        lastKeyAt = android.os.SystemClock.uptimeMillis();
        int code = e.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean arrow = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (!ok && !arrow && code != KeyEvent.KEYCODE_BACK) return false;
        if (ok && section == CARDS) return okOnCard(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (code == KeyEvent.KEYCODE_BACK) {
            exit();
            return true;
        }
        if (section == CARDS) {
            int n = pageSize();
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (cardIndex < n - 1) focusCard(cardIndex + 1);
                else if ((page + 1) * 3 < currentRow().size()) turnPage(1, 0);
            } else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (cardIndex > 0) focusCard(cardIndex - 1);
                else if (page > 0) turnPage(-1, 2);
            } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                section = TABS;
                tabs[tab].requestFocus();
                markTabs();
            } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                exit();
            }
        } else {
            // Tabs, as on the home screen: focusing one selects it.
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && tab > 0) selectTab(tab - 1);
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && tab < 2) selectTab(tab + 1);
            else if ((code == KeyEvent.KEYCODE_DPAD_DOWN || ok) && pageSize() > 0) focusCard(0);
        }
        return true;
    }

    // OK on a card, as on the home screen: a short press plays the title (or opens its page); held a second and let
    // go, the Trakt menu; held 2.5 s, the title's page opens once, while still held. The remote's repeats while OK is
    // held do nothing: each used to open the page again, stacking copies that Back had to close one by one.
    private long okAt;
    private boolean okDone;
    private final Runnable okDetails = this::okDetailsNow;

    private void okDetailsNow() {
        Tmdb.Title t = focusedTitle();
        if (okAt == 0 || t == null) return;
        okDone = true;
        DetailActivity.open(activity, t.kind, t.id);
    }

    private Tmdb.Title focusedTitle() {
        int i = page * 3 + cardIndex;
        return i < currentRow().size() ? currentRow().get(i) : null;
    }

    private boolean okOnCard(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                okAt = android.os.SystemClock.uptimeMillis();
                okDone = false;
                main.postDelayed(okDetails, TraktMenu.DETAILS_MS);
            }
            return true;
        }
        main.removeCallbacks(okDetails);
        long held = okAt == 0 ? 0 : android.os.SystemClock.uptimeMillis() - okAt;
        boolean pressedHere = okAt != 0;
        okAt = 0;
        Tmdb.Title t = focusedTitle();
        if (!pressedHere || okDone || e.isCanceled() || t == null) return true;
        if (held >= TraktMenu.MENU_MS && Trakt.signedIn(activity)) {
            View card = cards[cardIndex];
            int[] at = new int[2];
            card.getLocationInWindow(at);
            android.graphics.Rect anchor = new android.graphics.Rect(at[0], at[1], at[0] + card.getWidth(), at[1] + card.getHeight());
            TraktMenu.show(activity, this, anchor, t.kind, t.id, t.name, null, night < 0.5f,
                    msg -> android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show(), null);
        } else {
            play(t);
        }
        return true;
    }

    // MARK: - Data

    private void load(String p) {
        pkg = p;
        service = SERVICES.get(p);
        int gen = ++generation;
        tab = 0;
        page = 0;
        cardIndex = 0;
        resetWide();
        for (int i = 0; i < 3; i++) rows.set(i, null);
        titleView.setText(service[0]);
        status.setText(Ui.tr("Carregando…", "Loading…"));
        showPage();
        markTabs();
        new Thread(() -> {
            try {
                loadGenres();
                List<Tmdb.Title> first = fetchRow(0);
                main.post(() -> {
                    if (gen != generation) return;
                    rows.set(0, first);
                    rowsAt[0] = System.currentTimeMillis();
                    if (tab == 0) showPage();
                });
            } catch (Exception e) {
                main.post(() -> { if (gen == generation) status.setText(Ui.tr("Não deu para buscar", "Couldn't load") + ": " + e.getMessage()); });
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

    /** One tab's titles: movies and shows from this service, alternated, only the ones with both pictures. */
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

    private int pageSize() {
        return Math.max(0, Math.min(3, currentRow().size() - page * 3));
    }

    private void selectTab(int t) {
        tab = t;
        page = 0;
        cardIndex = 0;
        resetWide();
        markTabs();
        tabs[t].requestFocus();
        showPage();
        if (rows.get(t) != null) return;
        status.setText(Ui.tr("Carregando…", "Loading…"));
        int gen = generation;
        new Thread(() -> {
            try {
                List<Tmdb.Title> got = fetchRow(t);
                main.post(() -> {
                    if (gen != generation) return;
                    rows.set(t, got);
                    rowsAt[t] = System.currentTimeMillis();
                    if (tab == t) showPage();
                });
            } catch (Exception e) {
                main.post(() -> { if (gen == generation && tab == t) status.setText(e.getMessage()); });
            }
        }, "service-row").start();
    }

    /** A tab's titles fetched again in the background; the cards stay until the new ones arrive. */
    private void refetch(int t) {
        int gen = generation;
        rowsAt[t] = System.currentTimeMillis();   // one try per half hour, even if it fails
        new Thread(() -> {
            try {
                List<Tmdb.Title> got = fetchRow(t);
                main.post(() -> {
                    if (gen != generation || got.isEmpty()) return;
                    rows.set(t, got);
                    if (tab == t) {
                        page = 0;
                        resetWide();
                        if (!(active && section == CARDS)) slide(); else showPage();
                    }
                });
            } catch (Exception ignored) {
            }
        }, "service-refresh").start();
    }

    /** The next page slides in from the right, as the cards turning on their own. */
    private void slide() {
        float d = px(60);
        for (int i = 0; i < 3; i++) {
            View[] vs = {cards[i], names[i], metas[i]};
            for (View v : vs) {
                v.animate().cancel();
                v.animate().alpha(0).translationX(-d).setDuration(180).start();
            }
        }
        main.postDelayed(() -> {
            resetWide();
            showPage();
            for (int i = 0; i < 3; i++) {
                View[] vs = {cards[i], names[i], metas[i]};
                for (View v : vs) {
                    v.setTranslationX(d);
                    v.animate().alpha(1).translationX(0).setStartDelay(i * 50L).setDuration(260).start();
                }
            }
        }, 190);
    }

    private void turnPage(int dir, int focus) {
        page += dir;
        resetWide();
        showPage();
        focusCard(Math.min(focus, pageSize() - 1));
    }

    /** The three cards for the current tab and page: the wide one with its details, the tall ones with the poster. */
    private void showPage() {
        List<Tmdb.Title> r = currentRow();
        if (rows.get(tab) != null) {
            int pages = (r.size() + 2) / 3;
            status.setText(r.isEmpty() ? Ui.tr("Nada aqui por enquanto", "Nothing here yet")
                    : pages > 1 ? (page + 1) + " / " + pages : "");
        }
        for (int i = 0; i < 3; i++) {
            int at = page * 3 + i;
            Tmdb.Title t = at < r.size() ? r.get(at) : null;
            cards[i].setVisibility(t == null && rows.get(tab) != null ? INVISIBLE : VISIBLE);
            names[i].setText(t == null ? "" : t.name);
            metas[i].setText(t == null ? "" : i == wideSlot ? details(t) : shortDetails(t));
            if (t != null) CardInfo.get(t.kind, t.id, null);   // ready by the time the card has the focus
            // The same wide photo at every size (centre-cropped on a tall card), as on the home screen: nothing is
            // swapped while a card grows or shrinks.
            String url = t == null ? null : WIDE_IMAGE + t.backdrop;
            Images.load(images[i], url);
        }
        showInfo();
    }

    /** "★ 7.3 · Series · Comedy · 2026", like the home screen's line under each card (rating first: never cut off). */
    private String details(Tmdb.Title t) {
        List<String> parts = new ArrayList<>();
        if (t.vote > 0) parts.add(String.format(Locale.US, "★ %.1f", t.vote));
        parts.add(t.kind.equals("tv") ? Ui.tr("Série", "Series") : Ui.tr("Filme", "Movie"));
        String g = t.genres.length > 0 ? genreNames.get(String.valueOf(t.genres[0])) : null;
        if (g != null) parts.add(g);
        if (!t.year.isEmpty()) parts.add(t.year);
        return TextUtils.join(" · ", parts);
    }

    /** The tall cards' line: "★ 7.1 · Drama". */
    private String shortDetails(Tmdb.Title t) {
        List<String> parts = new ArrayList<>();
        if (t.vote > 0) parts.add(String.format(Locale.US, "★ %.1f", t.vote));
        String g = t.genres.length > 0 ? genreNames.get(String.valueOf(t.genres[0])) : null;
        if (g != null) parts.add(g);
        return TextUtils.join(" · ", parts);
    }

    private void focusCard(int i) {
        int n = pageSize();
        if (n == 0) return;
        cardIndex = Math.max(0, Math.min(n - 1, i));
        section = CARDS;
        cards[cardIndex].requestFocus();
        markTabs();
        main.removeCallbacks(grow);
        if (cardIndex != wideSlot) main.postDelayed(grow, GROW_MS);
    }

    private void markTabs() {
        boolean focused = active && section == TABS;
        int ink = blend(L_INK, WHITE, night), dim = blend(L_DIM, N_DIM, night);
        for (int i = 0; i < 3; i++) {
            boolean selected = i == tab;
            tabs[i].setTypeface(selected ? Ui.semibold : Ui.medium);
            tabs[i].setTextColor(selected && focused ? L_INK : selected ? ink : dim);
            tabs[i].setBackground(selected && focused ? TvUi.round(0xFFFFFFFF, px(TABBAR_H / 2)) : null);
            tabs[i].setElevation(selected && focused ? px(10) : 0);
        }
        if (infos[0] != null) showInfo();
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

    private static int blend(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }

    private int px(float v) {
        return Math.round(v * k);
    }
}
