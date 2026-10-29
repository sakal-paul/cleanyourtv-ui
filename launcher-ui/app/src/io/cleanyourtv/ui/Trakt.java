package io.cleanyourtv.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Trakt account (trakt.tv): sign-in with a code typed on the phone (OAuth device flow), and what the home screen
 * shows from it: Up Next (the next episode of every show being watched), the Watchlist, this week's new episodes, and
 * per title whether it's watched and its rating. Titles carry their TMDB ids, so the cards get their pictures and
 * details from TMDB as every other card. Fetched again every half hour; the last lists are kept for power-on.
 *
 * The app's Client ID comes from config.json ("trakt_client_id"), with "trakt_client_secret" only for an older app
 * that has one.
 */
final class Trakt {
    private static final String API = "https://api.trakt.tv";
    private static final long EVERY = 30 * 60_000L;
    private static final int UP_NEXT_MAX = 15;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static List<Recs.Pick> upNext = new ArrayList<>(), watchlist = new ArrayList<>(), releases = new ArrayList<>();
    private static final int RELEASES_MAX = 30;
    private static final Set<String> watched = new HashSet<>(), listed = new HashSet<>();
    private static final Map<String, Integer> ratings = new HashMap<>();
    private static final Map<String, String> airing = new HashMap<>(), progress = new HashMap<>();
    private static long fetchedAt;
    private static boolean loaded, fetching;

    private Trakt() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("trakt", Context.MODE_PRIVATE);
    }

    /** A Client ID is set. Trakt's newer (PKCE) apps have no secret, and the device sign-in works without one. */
    static boolean configured() {
        return !Prefs.traktId().isEmpty();
    }

    /** The app's credentials for a token request: the secret only when the app has one. */
    private static JSONObject client(JSONObject body) throws Exception {
        body.put("client_id", Prefs.traktId());
        if (!Prefs.traktSecret().isEmpty()) body.put("client_secret", Prefs.traktSecret());
        return body;
    }

    static boolean signedIn(Context c) {
        return configured() && !sp(c).getString("access", "").isEmpty();
    }

    static String userName(Context c) {
        return sp(c).getString("user", "");
    }

    // MARK: - Sign-in (device flow: a code on the TV, typed at trakt.tv/activate on the phone)

    /** {device_code, user_code, verification_url, expires_in, interval}. */
    static JSONObject deviceCode() throws Exception {
        return new JSONObject(call(null, "POST", "/oauth/device/code", new JSONObject().put("client_id", Prefs.traktId()).toString()));
    }

    /** 1: signed in; 0: not approved yet (ask again after the interval); -1: the code expired or was refused. */
    static int poll(Context c, String deviceCode) throws Exception {
        JSONObject body = client(new JSONObject().put("code", deviceCode));
        HttpURLConnection h = open("POST", "/oauth/device/token", null);
        send(h, body.toString());
        int code = h.getResponseCode();
        String text = read(h);
        if (code == 200) {
            keep(c, new JSONObject(text));
            try {
                JSONObject me = new JSONObject(call(c, "GET", "/users/me", null));
                sp(c).edit().putString("user", me.optString("name").isEmpty() ? me.optString("username") : me.optString("name")).apply();
            } catch (Exception ignored) {
            }
            fetchedAt = 0;
            return 1;
        }
        if (code == 400 || code == 429) return 0;   // pending, or polling a little too fast
        return -1;                                  // 404 invalid, 409 used, 410 expired, 418 denied
    }

    private static void keep(Context c, JSONObject t) {
        long expires = (t.optLong("created_at", System.currentTimeMillis() / 1000) + t.optLong("expires_in", 7776000)) * 1000;
        sp(c).edit().putString("access", t.optString("access_token")).putString("refresh", t.optString("refresh_token"))
                .putLong("expires", expires).apply();
    }

    static void signOut(Context c) {
        String token = sp(c).getString("access", "");
        sp(c).edit().clear().apply();
        synchronized (Trakt.class) {
            upNext = new ArrayList<>();
            watchlist = new ArrayList<>();
            watched.clear();
            listed.clear();
            ratings.clear();
            airing.clear();
            progress.clear();
            fetchedAt = 0;
        }
        IO.execute(() -> {
            try {
                call(null, "POST", "/oauth/revoke", client(new JSONObject().put("token", token)).toString());
            } catch (Exception ignored) {
            }
        });
    }

    /** The access token, renewed first when it ends within a day. */
    private static String token(Context c) throws Exception {
        SharedPreferences p = sp(c);
        if (System.currentTimeMillis() > p.getLong("expires", 0) - 24 * 3600_000L && !p.getString("refresh", "").isEmpty()) {
            JSONObject body = client(new JSONObject().put("refresh_token", p.getString("refresh", ""))
                    .put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob").put("grant_type", "refresh_token"));
            keep(c, new JSONObject(call(null, "POST", "/oauth/token", body.toString())));
        }
        return p.getString("access", "");
    }

    // MARK: - HTTP

    private static HttpURLConnection open(String method, String path, String token) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(API + path).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(12000);
        h.setRequestMethod(method);
        h.setRequestProperty("Content-Type", "application/json");
        h.setRequestProperty("trakt-api-version", "2");
        h.setRequestProperty("trakt-api-key", Prefs.traktId());
        h.setRequestProperty("User-Agent", "CleanYourTV-UI");
        if (token != null && !token.isEmpty()) h.setRequestProperty("Authorization", "Bearer " + token);
        return h;
    }

    private static void send(HttpURLConnection h, String body) throws IOException {
        h.setDoOutput(true);
        try (OutputStream out = h.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
    }

    private static String read(HttpURLConnection h) throws IOException {
        int code = h.getResponseCode();
        InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream();
        String text = in == null ? "" : Tmdb.readAll(in);
        h.disconnect();
        return text;
    }

    /** A call with the account's token when `c` is given; throws on an error answer. */
    private static String call(Context c, String method, String path, String body) throws Exception {
        HttpURLConnection h = open(method, path, c == null ? null : token(c));
        if (body != null) send(h, body);
        int code = h.getResponseCode();
        String text = read(h);
        if (code == 401 && c != null) sp(c).edit().remove("access").apply();   // revoked on trakt.tv: signed out
        if (code >= 400) throw new IOException("Trakt " + code);
        return text;
    }

    // MARK: - What the home screen shows

    /** The Up Next tab: shows being watched, the most recent first, each with its next episode in `note`. */
    static synchronized List<Recs.Pick> upNext(Context c) {
        load(c);
        return new ArrayList<>(upNext);
    }

    /** The Releases tab (no account needed): Trakt's new releases, as on trakt.tv's Discover → Releases. */
    static synchronized List<Recs.Pick> releases(Context c) {
        load(c);
        return new ArrayList<>(releases);
    }

    static synchronized List<Recs.Pick> watchlist(Context c) {
        load(c);
        return new ArrayList<>(watchlist);
    }

    /** A small tag for the card's details: "New episode Thu", "Next: S2 E4", "✓ Watched"; null for none. */
    static synchronized String tag(String kind, int tmdbId) {
        String key = kind + ":" + tmdbId;
        if (airing.containsKey(key)) return airing.get(key);
        if (progress.containsKey(key)) return progress.get(key);
        return watched.contains(key) ? Ui.tr("✓ Visto", "✓ Watched") : null;
    }

    /** Your rating on Trakt (1 to 10), or 0. */
    static synchronized int rating(String kind, int tmdbId) {
        Integer r = ratings.get(kind + ":" + tmdbId);
        return r == null ? 0 : r;
    }

    static synchronized boolean onWatchlist(String kind, int tmdbId) {
        return listed.contains(kind + ":" + tmdbId);
    }

    /** The Trakt episode id of this show's next episode (Up Next), or 0. */
    static synchronized long nextEpisode(int tmdbId) {
        for (Recs.Pick p : upNext) if (p.id == tmdbId) return p.traktEpisode;
        return 0;
    }

    /** Fetches everything again in the background when it's half an hour old (or `force`); `done` runs on the main thread. */
    static void refresh(Context c, boolean force, Runnable done) {
        if (!configured()) return;
        synchronized (Trakt.class) {
            load(c);
            if (fetching || (!force && System.currentTimeMillis() - fetchedAt < EVERY)) return;
            fetching = true;
        }
        Context app = c.getApplicationContext();
        IO.execute(() -> {
            try {
                fetch(app);
            } catch (Exception e) {
                AppLog.w("trakt: " + e.getMessage());
            } finally {
                synchronized (Trakt.class) {
                    fetching = false;
                    fetchedAt = System.currentTimeMillis();   // one try per half hour, even if it failed
                }
            }
            if (done != null) MAIN.post(done);
        });
    }

    private static void fetch(Context c) throws Exception {
        List<Recs.Pick> rel = fetchReleases();
        synchronized (Trakt.class) { releases = rel; }
        if (!signedIn(c)) {
            save(c);
            return;
        }
        Set<String> w = new HashSet<>(), l = new HashSet<>();
        Map<String, Integer> r = new HashMap<>();
        Map<String, String> air = new HashMap<>(), prog = new HashMap<>();
        List<Recs.Pick> next = new ArrayList<>(), list = new ArrayList<>();

        JSONArray movies = new JSONArray(call(c, "GET", "/sync/watched/movies", null));
        for (int i = 0; i < movies.length(); i++) {
            int id = tmdb(movies.getJSONObject(i).optJSONObject("movie"));
            if (id > 0) w.add("movie:" + id);
        }

        // Shows, most recently watched first: the next episode of each (one progress request per show).
        JSONArray shows = new JSONArray(call(c, "GET", "/sync/watched/shows?extended=noseasons", null));
        List<JSONObject> order = new ArrayList<>();
        for (int i = 0; i < shows.length(); i++) order.add(shows.getJSONObject(i));
        order.sort((a, b) -> b.optString("last_watched_at").compareTo(a.optString("last_watched_at")));
        int asked = 0;
        for (JSONObject s : order) {
            JSONObject show = s.optJSONObject("show");
            int id = tmdb(show);
            if (id <= 0) continue;
            if (asked >= UP_NEXT_MAX) break;
            asked++;
            long traktId = show.optJSONObject("ids").optLong("trakt");
            JSONObject pr = new JSONObject(call(c, "GET", "/shows/" + traktId + "/progress/watched?hidden=false&specials=false", null));
            int aired = pr.optInt("aired"), done = pr.optInt("completed");
            JSONObject ep = pr.optJSONObject("next_episode");
            if (ep == null || done >= aired) {
                w.add("tv:" + id);
                continue;
            }
            String label = String.format(Locale.US, "S%d E%d", ep.optInt("season"), ep.optInt("number"));
            prog.put("tv:" + id, Ui.tr("A seguir: ", "Next: ") + label + "  ·  " + done + "/" + aired);
            Recs.Pick p = pick("tv", id, show.optString("title"));
            p.note = Ui.tr("A seguir ", "Next ") + label;
            p.traktEpisode = ep.optJSONObject("ids") == null ? 0 : ep.optJSONObject("ids").optLong("trakt");
            next.add(p);
        }

        JSONArray wl = new JSONArray(call(c, "GET", "/sync/watchlist", null));
        for (int i = 0; i < wl.length(); i++) {
            JSONObject it = wl.getJSONObject(i);
            String type = it.optString("type");
            String kind = type.equals("movie") ? "movie" : type.equals("show") ? "tv" : null;
            if (kind == null) continue;
            JSONObject o = it.optJSONObject(type);
            int id = tmdb(o);
            if (id <= 0) continue;
            l.add(kind + ":" + id);
            list.add(pick(kind, id, o.optString("title")));
        }

        JSONArray rated = new JSONArray(call(c, "GET", "/sync/ratings", null));
        for (int i = 0; i < rated.length(); i++) {
            JSONObject it = rated.getJSONObject(i);
            String type = it.optString("type");
            String kind = type.equals("movie") ? "movie" : type.equals("show") ? "tv" : null;
            int id = kind == null ? 0 : tmdb(it.optJSONObject(type));
            if (id > 0) r.put(kind + ":" + id, it.optInt("rating"));
        }

        // This week's new episodes of your shows: "New episode Thu" (in the TV's own time).
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        JSONArray cal = new JSONArray(call(c, "GET", "/calendars/my/shows/" + day.format(new Date()) + "/7", null));
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat weekday = new SimpleDateFormat("EEE", Locale.US);
        for (int i = 0; i < cal.length(); i++) {
            JSONObject it = cal.getJSONObject(i);
            int id = tmdb(it.optJSONObject("show"));
            if (id <= 0 || air.containsKey("tv:" + id)) continue;
            String when = "";
            try {
                Date d = iso.parse(it.optString("first_aired").replaceAll("\\.\\d+Z$", "").replace("Z", ""));
                if (d != null) when = d.getTime() - System.currentTimeMillis() < 24 * 3600_000L ? Ui.tr("hoje", "today") : weekday.format(d);
            } catch (Exception ignored) {
            }
            air.put("tv:" + id, Ui.tr("Episódio novo ", "New episode ") + when);
        }

        synchronized (Trakt.class) {
            upNext = next;
            watchlist = list;
            watched.clear();
            watched.addAll(w);
            listed.clear();
            listed.addAll(l);
            ratings.clear();
            ratings.putAll(r);
            airing.clear();
            airing.putAll(air);
            progress.clear();
            progress.putAll(prog);
        }
        save(c);
    }

    /**
     * The last two weeks and the next one: films out in cinemas, films and shows arriving on streaming, and new shows,
     * the most popular on Trakt first (by votes), each with "In cinemas Oct 23", "Streaming Oct 30" or "New show Oct 20".
     */
    private static List<Recs.Pick> fetchReleases() throws Exception {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.DAY_OF_YEAR, -14);
        String from = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
        String[][] feeds = {
            {"/calendars/all/movies/" + from + "/21?extended=full", "movie", Ui.tr("Nos cinemas ", "In cinemas ")},
            {"/calendars/all/streaming/" + from + "/21?extended=full", "movie", Ui.tr("No streaming ", "Streaming ")},
            {"/calendars/all/shows/new/" + from + "/21?extended=full", "tv", Ui.tr("Série nova ", "New show ")},
        };
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US), label = new SimpleDateFormat("MMM d", Locale.US);
        Map<String, Object[]> best = new HashMap<>();   // "kind:id" → {votes, pick}
        for (String[] f : feeds) {
            JSONArray a;
            try {
                a = new JSONArray(call(null, "GET", f[0], null));
            } catch (Exception e) {
                AppLog.w("trakt releases: " + e.getMessage());
                continue;
            }
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.getJSONObject(i);
                JSONObject o = it.optJSONObject(f[1].equals("movie") ? "movie" : "show");
                int id = tmdb(o);
                if (id <= 0) continue;
                String date = it.optString(f[1].equals("movie") ? "released" : "first_aired");
                String when = "";
                try {
                    Date d = day.parse(date.length() >= 10 ? date.substring(0, 10) : date);
                    if (d != null) when = label.format(d);
                } catch (Exception ignored) {
                }
                int votes = o.optInt("votes");
                String key = f[1] + ":" + id;
                Object[] had = best.get(key);
                if (had != null && (int) had[0] >= votes) continue;
                Recs.Pick p = pick(f[1], id, o.optString("title"));
                p.note = f[2] + when;
                best.put(key, new Object[] {votes, p});
            }
        }
        List<Object[]> all = new ArrayList<>(best.values());
        all.sort((x, y) -> Integer.compare((int) y[0], (int) x[0]));
        List<Recs.Pick> out = new ArrayList<>();
        for (int i = 0; i < all.size() && out.size() < RELEASES_MAX; i++) out.add((Recs.Pick) all.get(i)[1]);
        return out;
    }

    private static int tmdb(JSONObject o) {
        JSONObject ids = o == null ? null : o.optJSONObject("ids");
        return ids == null ? 0 : ids.optInt("tmdb");
    }

    private static Recs.Pick pick(String kind, int id, String title) {
        Recs.Pick p = new Recs.Pick();
        p.kind = kind;
        p.id = id;
        p.title = title;
        return p;
    }

    // MARK: - Kept for power-on

    private static void save(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("upNext", picks(upNext)).put("watchlist", picks(watchlist)).put("releases", picks(releases));
            o.put("watched", new JSONArray(watched)).put("listed", new JSONArray(listed));
            o.put("ratings", new JSONObject(ratings)).put("airing", new JSONObject(airing)).put("progress", new JSONObject(progress));
            sp(c).edit().putString("data", o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static JSONArray picks(List<Recs.Pick> list) throws Exception {
        JSONArray a = new JSONArray();
        for (Recs.Pick p : list) {
            a.put(new JSONObject().put("kind", p.kind).put("id", p.id).put("title", p.title).put("note", p.note == null ? "" : p.note)
                    .put("ep", p.traktEpisode));
        }
        return a;
    }

    private static void load(Context c) {
        if (loaded) return;
        loaded = true;
        try {
            JSONObject o = new JSONObject(sp(c).getString("data", "{}"));
            upNext = unpack(o.optJSONArray("upNext"));
            watchlist = unpack(o.optJSONArray("watchlist"));
            releases = unpack(o.optJSONArray("releases"));
            JSONArray a = o.optJSONArray("watched");
            for (int i = 0; a != null && i < a.length(); i++) watched.add(a.getString(i));
            a = o.optJSONArray("listed");
            for (int i = 0; a != null && i < a.length(); i++) listed.add(a.getString(i));
            JSONObject m = o.optJSONObject("ratings");
            if (m != null) for (java.util.Iterator<String> it = m.keys(); it.hasNext(); ) { String k = it.next(); ratings.put(k, m.getInt(k)); }
            for (String name : new String[] {"airing", "progress"}) {
                m = o.optJSONObject(name);
                Map<String, String> into = name.equals("airing") ? airing : progress;
                if (m != null) for (java.util.Iterator<String> it = m.keys(); it.hasNext(); ) { String k = it.next(); into.put(k, m.getString(k)); }
            }
        } catch (Exception ignored) {
        }
    }

    private static List<Recs.Pick> unpack(JSONArray a) throws Exception {
        List<Recs.Pick> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Recs.Pick p = pick(o.getString("kind"), o.getInt("id"), o.optString("title"));
            p.note = o.optString("note").isEmpty() ? null : o.optString("note");
            p.traktEpisode = o.optLong("ep");
            out.add(p);
        }
        return out;
    }

    // MARK: - Actions (long-press OK on a card)

    private static String items(String kind, int tmdbId, Integer rating) throws Exception {
        JSONObject item = new JSONObject().put("ids", new JSONObject().put("tmdb", tmdbId));
        if (rating != null) item.put("rating", rating);
        return new JSONObject().put(kind.equals("movie") ? "movies" : "shows", new JSONArray().put(item)).toString();
    }

    /** Runs a change on Trakt, then fetches everything again; `done(ok)` on the main thread. */
    private static void change(Context c, String path, String body, java.util.function.Consumer<Boolean> done) {
        Context app = c.getApplicationContext();
        IO.execute(() -> {
            boolean ok;
            try {
                call(app, "POST", path, body);
                ok = true;
            } catch (Exception e) {
                AppLog.w("trakt " + path + ": " + e.getMessage());
                ok = false;
            }
            boolean result = ok;
            synchronized (Trakt.class) { fetchedAt = 0; }
            MAIN.post(() -> done.accept(result));
        });
    }

    static void setWatchlist(Context c, String kind, int tmdbId, boolean add, java.util.function.Consumer<Boolean> done) {
        try {
            change(c, add ? "/sync/watchlist" : "/sync/watchlist/remove", items(kind, tmdbId, null), done);
        } catch (Exception e) {
            done.accept(false);
        }
    }

    /** A movie or a whole show as watched now. */
    static void markWatched(Context c, String kind, int tmdbId, java.util.function.Consumer<Boolean> done) {
        try {
            change(c, "/sync/history", items(kind, tmdbId, null), done);
        } catch (Exception e) {
            done.accept(false);
        }
    }

    /** One episode (by its Trakt id) as watched now. */
    static void markEpisode(Context c, long traktEpisode, java.util.function.Consumer<Boolean> done) {
        try {
            String body = new JSONObject().put("episodes", new JSONArray().put(new JSONObject().put("ids",
                    new JSONObject().put("trakt", traktEpisode)))).toString();
            change(c, "/sync/history", body, done);
        } catch (Exception e) {
            done.accept(false);
        }
    }

    static void rate(Context c, String kind, int tmdbId, int rating, java.util.function.Consumer<Boolean> done) {
        try {
            change(c, "/sync/ratings", items(kind, tmdbId, rating), done);
        } catch (Exception e) {
            done.accept(false);
        }
    }
}
