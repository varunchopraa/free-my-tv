package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen's "Continue" tab: what the person is watching in Stremio, read from their Stremio account (the
 * library Stremio syncs to api.strem.io), the way Stremio's own Continue Watching row picks it: titles not removed (or
 * only opened, "temp") with a playback position, the most recently watched first. A card opens the title in Stremio
 * (stremio:///detail/...), at the episode it was on.
 *
 * Each person uses their own Stremio auth key (config.properties "stremio_key", built into the app). It never goes to
 * the log. The API is Stremio's own, not a documented public one: if it changes, the tab is just empty.
 */
final class Stremio {
    static final String PKG = "com.stremio.one";
    private static final String API = "https://api.strem.io/api/datastoreGet";
    /** Backgrounds for IMDb ids (tt...), from the same image service Stremio's Cinemeta uses. */
    private static final String BACKGROUNDS = "https://images.metahub.space/background/medium/";
    /** Coming back to the home screen after watching refetches, but not more often than this. */
    private static final long EVERY = 60_000L;
    private static final int MAX = 5;

    private static Stremio instance;

    static synchronized Stremio get(Context c) {
        if (instance == null) instance = new Stremio(c.getApplicationContext());
        return instance;
    }

    /** A key in config.properties: the tab is there (empty until the first fetch, or with Stremio not installed). */
    static boolean on() {
        return !Config.STREMIO_KEY.trim().isEmpty();
    }

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Recs.Listener> listeners = new ArrayList<>();
    private volatile List<Recs.Pick> items = new ArrayList<>();
    private volatile long fetchedAt;
    private volatile boolean fetching;

    private Stremio(Context c) {
        context = c;
    }

    void addListener(Recs.Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Recs.Listener l) { listeners.remove(l); }

    /** Up to five, the most recently watched first; empty without Stremio on the TV. */
    List<Recs.Pick> now() {
        if (!on() || !installed()) return new ArrayList<>();
        return items;
    }

    void refreshIfStale() {
        if (!on() || fetching || !installed()) return;
        long now = System.currentTimeMillis();
        if (now - fetchedAt < EVERY && now >= fetchedAt) return;
        fetching = true;
        io.execute(() -> {
            try {
                List<Recs.Pick> list = fetch();
                fetchedAt = System.currentTimeMillis();
                if (!same(list, items)) {
                    items = list;
                    main.post(() -> { for (Recs.Listener l : new ArrayList<>(listeners)) l.onRecs(); });
                }
            } catch (Exception e) {
                fetchedAt = System.currentTimeMillis();   // a failure waits like a success: no retry on every key
                AppLog.w("stremio: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                fetching = false;
            }
        });
    }

    private boolean installed() {
        try {
            context.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private List<Recs.Pick> fetch() throws Exception {
        JSONObject body = new JSONObject().put("authKey", Config.STREMIO_KEY.trim()).put("collection", "libraryItem")
                .put("ids", new JSONArray()).put("all", true);
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        JSONObject reply;
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            try (InputStream in = c.getInputStream()) {
                reply = new JSONObject(read(in));
            }
        } finally {
            c.disconnect();
        }
        if (reply.has("error")) {
            JSONObject e = reply.optJSONObject("error");
            throw new Exception(e != null ? e.optString("message", "error") : reply.optString("error"));
        }
        JSONArray all = reply.optJSONArray("result");
        List<JSONObject> watching = new ArrayList<>();
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            JSONObject state = o == null ? null : o.optJSONObject("state");
            if (state == null || "other".equals(o.optString("type"))) continue;
            if (o.optBoolean("removed") && !o.optBoolean("temp")) continue;
            if (state.optLong("timeOffset") <= 0) continue;
            watching.add(o);
        }
        Collections.sort(watching, (a, b) -> Long.compare(watched(b), watched(a)));
        List<Recs.Pick> out = new ArrayList<>();
        for (JSONObject o : watching) {
            if (out.size() == MAX) break;
            out.add(pick(o));
        }
        return out;
    }

    /** When it was last watched (a date, ISO 8601 or milliseconds depending on the client that wrote it). */
    private static long watched(JSONObject o) {
        JSONObject state = o.optJSONObject("state");
        Object at = state == null ? null : state.opt("lastWatched");
        if (at == null || at == JSONObject.NULL) at = o.opt("_mtime");
        if (at instanceof Number) return ((Number) at).longValue();
        if (at instanceof String) {
            try {
                return java.time.Instant.parse((String) at).toEpochMilli();
            } catch (RuntimeException ignored) {
            }
        }
        return 0;
    }

    private static Recs.Pick pick(JSONObject o) {
        JSONObject state = o.optJSONObject("state");
        String id = o.optString("_id"), type = o.optString("type"), video = state.optString("video_id");
        Recs.Pick p = new Recs.Pick();
        p.kind = "stremio";
        p.id = id.hashCode();
        p.title = o.optString("name");
        p.pkg = PKG;
        p.app = "stremio";
        p.detailed = true;
        p.link = "stremio:///detail/" + type + "/" + id + (video.isEmpty() || video.equals(id) ? "" : "/" + video);
        String imdb = id.startsWith("tt") ? id.split(":")[0] : null;
        String poster = o.optString("poster");
        p.backdrop = imdb != null ? BACKGROUNDS + imdb + "/img" : poster.startsWith("http") ? poster : null;
        p.meta = meta(type, id, video, state);
        return p;
    }

    /** "S2 E5 · 23 min left · Stremio" (the episode only for shows, the time left only when Stremio knows the length). */
    private static String meta(String type, String id, String video, JSONObject state) {
        List<String> parts = new ArrayList<>();
        if ("series".equals(type) && video.startsWith(id + ":")) {
            String[] se = video.substring(id.length() + 1).split(":");
            if (se.length == 2) parts.add(Ui.trf("S{season} E{episode}", "season", se[0], "episode", se[1]));
        }
        long left = (state.optLong("duration") - state.optLong("timeOffset")) / 60_000L;
        if (state.optLong("duration") > 0 && left > 0) parts.add(Ui.trf("{n} min left", "n", left));
        parts.add("Stremio");
        return android.text.TextUtils.join(" · ", parts);
    }

    private static boolean same(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Recs.Pick x = a.get(i), y = b.get(i);
            if (!x.link.equals(y.link) || !x.meta.equals(y.meta)) return false;
        }
        return true;
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
