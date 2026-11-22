package io.github.eastmadc.masthead.construction;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One line of raw/DATE.jsonl (a player's minute in one section, or a loss) and one line of requests.jsonl. No time stamps;
 * the finest position is a 4-block cube (section + sub-cube 0-63).
 */
final class RawLine {
    private RawLine() {}

    static final String[] CLASS = {"B", "L", "P"};
    private static final Gson GSON = new Gson(), NULLS = new com.google.gson.GsonBuilder().serializeNulls().create();

    /**
     * A parsed raw line. d = net per (class, sub-cube), 3 x 64. g = natural ground broken per sub-cube (64). agc =
     * against-cubes as [cx, sy, cz, sub-cube].
     */
    record Line(char k, UUID player, String name, UUID world, int cx, int sy, int cz, int pl, int br, int y0, int y1, int air, int wb,
                int ag, List<int[]> agc, int[] d, Map<String, Integer> m, String cause, UUID by, int[] g, List<Prod> f) {}

    /** A production line's counts for one build (k "f"; see Production): mob deaths by type. */
    record Prod(int site, Map<String, Integer> died) {}

    /** A parsed request line. c = packed cube key (SiteIndex.cubeKey), or null. */
    record Request(UUID player, String op, UUID world, Long cube, String value, String date) {}

    /** The minute's lines: every player line before any loss line, so a golem's -1 always follows its +1s. */
    static String write(MinuteMaps.Minute min, String[] keyByOrdinal) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Long, MinuteMaps.Acc[]> e : min.current().entrySet()) {
            long key = e.getKey();
            UUID w = min.worlds()[WorldSlots.slot(key)];
            if (w == null) continue;
            for (MinuteMaps.Acc a : e.getValue()) {
                JsonObject o = new JsonObject();
                o.addProperty("k", "p");
                o.addProperty("p", a.player.toString());
                o.addProperty("n", a.name);
                o.addProperty("w", w.toString());
                o.add("s", section(key));
                o.addProperty("pl", a.placed);
                o.addProperty("br", a.broken);
                if (a.yMin <= a.yMax) { o.addProperty("y0", a.yMin); o.addProperty("y1", a.yMax); }
                if (a.overAir > 0) o.addProperty("air", a.overAir);
                if (a.water > 0) o.addProperty("wb", a.water);
                if (a.ag > 0) {
                    o.addProperty("ag", a.ag);
                    JsonArray refs = new JsonArray();
                    for (int i = 0; i < a.agn; i++) {
                        JsonArray r = section(a.agc[i] >>> 6);
                        r.add((int) (a.agc[i] & 63));
                        refs.add(r);
                    }
                    o.add("agc", refs);
                }
                o.add("d", sub(a.sub));
                if (a.gnd != null) {
                    JsonObject g = new JsonObject();
                    for (int i = 0; i < 64; i++) if (a.gnd[i] != 0) g.addProperty(String.valueOf(i), a.gnd[i]);
                    o.add("g", g);
                }
                o.add("m", mats(a.mats, keyByOrdinal));
                b.append(GSON.toJson(o)).append('\n');
            }
        }
        for (Map.Entry<MinuteMaps.LossKey, MinuteMaps.Loss> e : min.losses().entrySet()) {
            long key = e.getKey().section();
            UUID w = min.worlds()[WorldSlots.slot(key)];
            if (w == null) continue;
            MinuteMaps.Loss l = e.getValue();
            JsonObject o = new JsonObject();
            o.addProperty("k", "x");
            o.addProperty("cause", l.cause);
            o.addProperty("by", l.by == null ? null : l.by.toString());
            o.addProperty("w", w.toString());
            o.add("s", section(key));
            o.add("d", sub(l.sub));
            o.add("m", mats(l.mats, keyByOrdinal));
            b.append(NULLS.toJson(o)).append('\n');
        }
        return b.toString();
    }

    private static JsonArray section(long key) {
        JsonArray s = new JsonArray();
        s.add(WorldSlots.cx(key));
        s.add(WorldSlots.sy(key));
        s.add(WorldSlots.cz(key));
        return s;
    }

    private static JsonObject sub(short[] sub) {
        JsonObject d = new JsonObject();
        for (int c = 0; c < 3; c++) {
            JsonObject one = new JsonObject();
            if (sub != null) for (int i = 0; i < 64; i++) if (sub[c * 64 + i] != 0) one.addProperty(String.valueOf(i), sub[c * 64 + i]);
            if (one.size() > 0) d.add(CLASS[c], one);
        }
        return d;
    }

    private static JsonObject mats(Map<Integer, int[]> mats, String[] keyByOrdinal) {
        JsonObject m = new JsonObject();
        if (mats != null) for (Map.Entry<Integer, int[]> e : mats.entrySet()) {
            int ord = e.getKey();
            if (e.getValue()[0] == 0 || ord < 0 || ord >= keyByOrdinal.length || keyByOrdinal[ord] == null) continue;
            String k = keyByOrdinal[ord];
            m.addProperty(k, (m.has(k) ? m.get(k).getAsInt() : 0) + e.getValue()[0]);
        }
        return m;
    }

    /** A raw line, or null for a blank, partial or broken one. */
    static Line parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            JsonObject o = JsonParser.parseString(s).getAsJsonObject();
            String k = o.get("k").getAsString();
            if (k.equals("f")) {   // no world, no position: build ids and counts only
                List<Prod> f = new ArrayList<>();
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("f").entrySet()) {
                    JsonObject one = e.getValue().getAsJsonObject();
                    f.add(new Prod(Integer.parseInt(e.getKey()), counts(one.getAsJsonObject("d"))));   // "i" (pickups, alpha.20 drafts): ignored
                }
                return new Line('f', null, null, null, 0, 0, 0, 0, 0, -1, -1, 0, 0, 0, List.of(), new int[3 * 64], Map.of(), null, null, new int[64], f);
            }
            JsonArray sec = o.getAsJsonArray("s");
            int[] d = new int[3 * 64];
            JsonObject dd = o.getAsJsonObject("d");
            for (int c = 0; c < 3; c++) {
                if (dd == null || !dd.has(CLASS[c])) continue;
                for (Map.Entry<String, JsonElement> e : dd.getAsJsonObject(CLASS[c]).entrySet()) {
                    int i = Integer.parseInt(e.getKey());
                    if (i < 0 || i > 63) return null;
                    d[c * 64 + i] = e.getValue().getAsInt();
                }
            }
            int[] g = new int[64];
            if (o.has("g")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("g").entrySet()) {
                int i = Integer.parseInt(e.getKey());
                if (i < 0 || i > 63) return null;
                g[i] = Math.max(0, e.getValue().getAsInt());
            }
            Map<String, Integer> m = new LinkedHashMap<>();
            if (o.has("m")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("m").entrySet()) {
                if (!e.getKey().matches("[a-z0-9_]{1,64}")) continue;
                m.put(e.getKey(), e.getValue().getAsInt());
            }
            UUID w = UUID.fromString(o.get("w").getAsString());
            int cx = sec.get(0).getAsInt(), sy = sec.get(1).getAsInt(), cz = sec.get(2).getAsInt();
            if (k.equals("p")) {
                List<int[]> agc = new ArrayList<>();
                if (o.has("agc")) for (JsonElement r : o.getAsJsonArray("agc")) {
                    JsonArray a = r.getAsJsonArray();
                    agc.add(new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt() & 63});
                }
                return new Line('p', UUID.fromString(o.get("p").getAsString()), o.get("n").getAsString(), w, cx, sy, cz, num(o, "pl"), num(o, "br"),
                        o.has("y0") ? num(o, "y0") : -1, o.has("y1") ? num(o, "y1") : -1, num(o, "air"), num(o, "wb"), num(o, "ag"), agc, d, m, null, null, g, null);
            }
            if (k.equals("x")) {
                JsonElement by = o.get("by");
                String cause = o.get("cause").getAsString();
                return new Line('x', null, null, w, cx, sy, cz, 0, 0, -1, -1, 0, 0, 0, List.of(), d, m, cause,
                        by == null || by.isJsonNull() ? null : UUID.fromString(by.getAsString()), g, null);
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Type -> positive count; keys must look like registry keys. */
    private static Map<String, Integer> counts(JsonObject o) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (o != null) for (Map.Entry<String, JsonElement> e : o.entrySet())
            if (e.getKey().matches("[a-z0-9_]{1,64}") && e.getValue().getAsInt() > 0) m.put(e.getKey(), e.getValue().getAsInt());
        return m;
    }

    private static int num(JsonObject o, String k) {
        return o.has(k) ? o.get(k).getAsInt() : 0;
    }

    static String request(UUID player, String op, UUID world, Long cube, String value, String date) {
        JsonObject o = new JsonObject();
        o.addProperty("p", player.toString());
        o.addProperty("op", op);
        if (world != null) o.addProperty("w", world.toString());
        if (cube != null) o.addProperty("c", cube);
        if (value != null) o.addProperty("v", value);
        if (date != null) o.addProperty("d", date);
        return GSON.toJson(o) + "\n";
    }

    static Request parseRequest(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            JsonObject o = JsonParser.parseString(s).getAsJsonObject();
            return new Request(UUID.fromString(o.get("p").getAsString()), o.get("op").getAsString(),
                    o.has("w") ? UUID.fromString(o.get("w").getAsString()) : null, o.has("c") ? o.get("c").getAsLong() : null,
                    o.has("v") ? o.get("v").getAsString() : null, o.has("d") ? o.get("d").getAsString() : null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
