package io.github.eastmadc.masthead.construction;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * sites.json: every tracked 4-block cube (per world) and the builds they make up. The only class that reads positions
 * after the recorder. Cubes are read and written with a streaming reader, so a big server's index stays small in memory.
 */
final class SiteIndex {
    static final int VERSION = 1;

    String lastEdition;
    int nextSite = 1;
    TreeMap<String, Long> folded = new TreeMap<>();          // raw file date -> bytes already folded
    LinkedHashMap<String, Integer> worlds = new LinkedHashMap<>();   // world UUID -> index
    List<HashMap<Long, Cube>> cubes = new ArrayList<>();      // per world index: cube key -> cube
    TreeMap<Integer, Site> sites = new TreeMap<>();
    List<Pending> pending = new ArrayList<>();               // /news build secret typed where no build is tracked yet

    /** A held secret: applied to the build that player grows at (or next to) this cube, until forget-after-days. */
    static final class Pending {
        String p;          // player UUID
        int w;             // world index
        long c;            // cube key
        String d;          // date asked
    }

    static final class Cube {
        int site;          // 0 = loose (not part of a build yet)
        String own;        // the first player to fill it
        int b, l, p;       // net BUILT, LOG, PLANT (together at most 64)
        String t;          // last date it changed
        int pb, u;         // loose cubes only: BUILT placed, and the share placed against older, untracked builds
        HashMap<String, Integer> o;   // BUILT and LOG blocks other players put here (null when only own did)

        int n() { return b + l + p; }
    }

    static final class Who {
        String name;
        int placed, real, broken, net;
    }

    static final class Hist {
        String d;
        int pl, br, n;
    }

    static final class Mention {
        String d, tag, angle;
    }

    /** One build. Field names are the JSON keys (snake_case). */
    static final class Site {
        int id;
        Integer permit;
        int world;
        String created, lastGrowth;
        int days;
        Integer aliasOf;
        int yMin = Integer.MAX_VALUE, yMax = Integer.MIN_VALUE;
        LinkedHashMap<String, Who> players = new LinkedHashMap<>();
        List<String> crew = new ArrayList<>();
        TreeMap<String, Integer> mats = new TreeMap<>();   // shortcut: every key kept (no top-32 cap); cap it if big servers' indexes grow
        int air, wb;
        int net, peakNet, clamped;
        boolean existing;
        int crewBroke, crewBrokeOwn;
        boolean harmed;
        List<Hist> history = new ArrayList<>();
        String kind, kindPending;
        String kindSet;   // filed by a builder (/news build kind): no guessing
        String aiKind, aiConf, aiAsked;   // the kinds desk's last kept answer, its confidence and date (only a published fold writes them)
        int aiNet;                        // the net blocks and top 5 materials it was given, to know when to ask again
        List<String> aiTop5;
        int aiList;                       // the kinds list it was asked from (Kinds.LIST); older answers are asked again
        String aiProd;                    // the top death it was given ("slime"; "" or null: none; "slime|slime_ball" from alpha.20 drafts)
        // production at the build, last 7 days: date -> type -> count (null when none; older versions ignore them; an
        // "items_collected" field from alpha.20 drafts is dropped on read)
        TreeMap<String, TreeMap<String, Integer>> mobsDied;
        int kindStreak;
        List<Integer> milestones = new ArrayList<>();
        String name, nameBy, nameFrom;
        boolean done;
        String doneBy;
        boolean doneReported;
        List<String> secretBy = new ArrayList<>();
        List<Mention> mentions = new ArrayList<>();
        boolean retired;
        String retiredOn;
        int lowFolds;
        boolean demolitionDue, demolitionReported, rebuild;
    }

    static final Gson GSON = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();

    /** Cube key in one world, from cube coordinates (block >> 2). */
    static long cubeKey(int sx, int sy, int sz) {
        return ((sx & 0xFFFFFFL) << 34) | ((sz & 0xFFFFFFL) << 10) | (sy & 0x3FFL);
    }

    static int sx(long k) { return (int) (k << 6 >> 40); }
    static int sz(long k) { return (int) (k << 30 >> 40); }
    static int sy(long k) { return (int) (k << 54 >> 54); }

    /** The cube key of sub-cube sc in section (cx, sy, cz). */
    static long cubeOf(int cx, int secY, int cz, int sc) {
        return cubeKey(cx * 4 + (sc & 3), secY * 4 + ((sc >> 4) & 3), cz * 4 + ((sc >> 2) & 3));
    }

    int world(String uuid) {
        Integer w = worlds.get(uuid);
        if (w == null) {
            w = worlds.size();
            worlds.put(uuid, w);
            cubes.add(new HashMap<>());
        }
        return w;
    }

    /** The version in a sites.json / players.json text, or -1 when it can't be read. */
    static int version(String json) {
        try (JsonReader r = new JsonReader(new StringReader(json))) {
            r.beginObject();
            while (r.hasNext()) {
                if (r.nextName().equals("version")) return r.nextInt();
                r.skipValue();
            }
        } catch (IOException | RuntimeException e) {
            return -1;
        }
        return -1;
    }

    static SiteIndex read(String json) throws IOException {
        SiteIndex x = new SiteIndex();
        try (JsonReader r = new JsonReader(new StringReader(json))) {
            r.beginObject();
            while (r.hasNext()) {
                switch (r.nextName()) {
                    case "version" -> { if (r.nextInt() != VERSION) throw new IOException("version"); }
                    case "last_edition" -> x.lastEdition = r.peek() == JsonToken.NULL ? nul(r) : r.nextString();
                    case "next_site" -> x.nextSite = r.nextInt();
                    case "folded" -> {
                        r.beginObject();
                        while (r.hasNext()) x.folded.put(r.nextName(), r.nextLong());
                        r.endObject();
                    }
                    case "worlds" -> {
                        r.beginObject();
                        while (r.hasNext()) { String u = r.nextName(); int i = r.nextInt(); x.worlds.put(u, i); }
                        r.endObject();
                        while (x.cubes.size() < x.worlds.size()) x.cubes.add(new HashMap<>());
                    }
                    case "cubes" -> {
                        r.beginObject();
                        while (r.hasNext()) {
                            int w = Integer.parseInt(r.nextName());
                            while (x.cubes.size() <= w) x.cubes.add(new HashMap<>());
                            HashMap<Long, Cube> m = x.cubes.get(w);
                            r.beginObject();
                            while (r.hasNext()) {
                                long key = Long.parseLong(r.nextName());
                                Cube c = new Cube();
                                r.beginObject();
                                while (r.hasNext()) {
                                    switch (r.nextName()) {
                                        case "site" -> c.site = r.nextInt();
                                        case "own" -> c.own = r.peek() == JsonToken.NULL ? nul(r) : r.nextString();
                                        case "n" -> { r.beginArray(); c.b = r.nextInt(); c.l = r.nextInt(); c.p = r.nextInt(); r.endArray(); }
                                        case "t" -> c.t = r.nextString();
                                        case "pb" -> c.pb = r.nextInt();
                                        case "u" -> c.u = r.nextInt();
                                        case "o" -> {
                                            c.o = new HashMap<>();
                                            r.beginObject();
                                            while (r.hasNext()) c.o.put(r.nextName(), r.nextInt());
                                            r.endObject();
                                        }
                                        default -> r.skipValue();
                                    }
                                }
                                r.endObject();
                                m.put(key, c);
                            }
                            r.endObject();
                        }
                        r.endObject();
                    }
                    case "pending" -> {
                        r.beginArray();
                        while (r.hasNext()) x.pending.add(GSON.fromJson(r, Pending.class));
                        r.endArray();
                    }
                    case "sites" -> {
                        r.beginObject();
                        while (r.hasNext()) {
                            r.nextName();
                            Site s = GSON.fromJson(r, Site.class);
                            x.sites.put(s.id, s);
                        }
                        r.endObject();
                    }
                    default -> r.skipValue();
                }
            }
            r.endObject();
        }
        return x;
    }

    private static String nul(JsonReader r) throws IOException { r.nextNull(); return null; }

    String write() throws IOException {
        StringWriter sw = new StringWriter();
        try (JsonWriter w = new JsonWriter(sw)) {
            w.beginObject();
            w.name("version").value(VERSION);
            w.name("last_edition").value(lastEdition);
            w.name("next_site").value(nextSite);
            w.name("folded").beginObject();
            for (Map.Entry<String, Long> e : folded.entrySet()) w.name(e.getKey()).value(e.getValue());
            w.endObject();
            w.name("worlds").beginObject();
            for (Map.Entry<String, Integer> e : worlds.entrySet()) w.name(e.getKey()).value(e.getValue());
            w.endObject();
            w.name("cubes").beginObject();
            for (int i = 0; i < cubes.size(); i++) {
                w.name(String.valueOf(i)).beginObject();
                for (Map.Entry<Long, Cube> e : new TreeMap<>(cubes.get(i)).entrySet()) {
                    Cube c = e.getValue();
                    w.name(String.valueOf(e.getKey())).beginObject();
                    w.name("site").value(c.site);
                    w.name("own").value(c.own);
                    w.name("n").beginArray().value(c.b).value(c.l).value(c.p).endArray();
                    w.name("t").value(c.t);
                    if (c.site == 0 && c.pb != 0) w.name("pb").value(c.pb);
                    if (c.site == 0 && c.u != 0) w.name("u").value(c.u);
                    if (c.o != null && !c.o.isEmpty()) {
                        w.name("o").beginObject();
                        for (Map.Entry<String, Integer> x : new TreeMap<>(c.o).entrySet()) w.name(x.getKey()).value(x.getValue());
                        w.endObject();
                    }
                    w.endObject();
                }
                w.endObject();
            }
            w.endObject();
            if (!pending.isEmpty()) {
                w.name("pending").beginArray();
                for (Pending x : pending) GSON.toJson(x, Pending.class, w);
                w.endArray();
            }
            w.name("sites").beginObject();
            for (Site s : sites.values()) {
                w.name(String.valueOf(s.id));
                GSON.toJson(s, Site.class, w);
            }
            w.endObject();
            w.endObject();
        }
        return sw.toString();
    }

    /** A deep copy (a preview folds into a copy and never saves it). */
    SiteIndex copy() throws IOException {
        return read(write());
    }
}
