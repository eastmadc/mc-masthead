package io.github.eastmadc.masthead.construction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * construction/survey.json: the owner's one-time list of builds made before the plugin started (from a scan of a copy of
 * the world). Read only for the opening edition. This class checks the file's shape; the projector checks who may be
 * named. A bad row is dropped with a note for the owner (never its chunks). A missing file means no survey.
 */
final class Survey {
    private Survey() {}

    static final int MAX_BYTES = 1 << 20, MAX_BUILDS = 300, MAX_CHUNKS = 1024, MAX_KEYS = 200, MAX_COUNT = 1_000_000;
    static final Set<String> DIMS = Set.of("overworld", "nether", "end");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,16}"), KEY = Pattern.compile("[a-z0-9_]{1,40}");

    /** One build: chunks as packed (cx, cz) keys, y in blocks, builders as UUID strings; name as the owner typed it (null: none; the projector checks it). */
    record Build(String id, String dim, Set<Long> chunks, int yMin, int yMax, List<String> builders, String kind,
                 Map<String, Integer> blocks, Map<String, Integer> villagers, Map<String, Integer> spawners, String name) {
        int total() { return Features.n(blocks, blocks.keySet().toArray(new String[0])); }
    }

    static long chunk(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    /** The file's builds; notes get one owner line per dropped row. null text = no file. */
    static List<Build> parse(String text, List<String> notes) {
        List<Build> out = new ArrayList<>();
        if (text == null) return out;
        JsonObject root;
        try { root = JsonParser.parseString(text).getAsJsonObject(); }
        catch (RuntimeException e) { notes.add("survey: survey.json is not readable JSON; no survey"); return out; }
        if (!(root.get("version") instanceof JsonPrimitive v) || !v.isNumber() || v.getAsDouble() != 1 || !(root.get("builds") instanceof JsonArray a)) {
            notes.add("survey: survey.json is not version 1 with a builds list; no survey");
            return out;
        }
        Set<String> ids = new HashSet<>();
        int row = 0;
        for (JsonElement e : a) {
            row++;
            if (out.size() >= MAX_BUILDS) { notes.add("survey: more than " + MAX_BUILDS + " builds; the rest dropped"); break; }
            String why = null, id = null;
            Build b = null;
            if (!(e instanceof JsonObject o)) why = "not an object";
            else {
                id = str(o.get("id"));
                if (id == null || !ID.matcher(id).matches()) { id = null; why = "bad id"; }
                else if (!ids.add(id)) why = "id used twice";
                else {
                    String dim = str(o.get("world")), kind = str(o.get("kind"));
                    Set<Long> chunks = chunks(o.get("chunks"));
                    int[] y = ys(o.get("y"));
                    List<String> builders = builders(o.get("builders"));
                    Map<String, Integer> blocks = counts(o.get("blocks"), false), vil = counts(o.get("villagers"), true), sp = counts(o.get("spawners"), true);
                    if (dim == null || !DIMS.contains(dim)) why = "bad world";
                    else if (chunks == null) why = "bad chunks";
                    else if (y == null) why = "bad y";
                    else if (builders == null) why = "bad builders";
                    else if (kind == null || !Kinds.ALL.contains(kind)) why = "unknown kind";
                    else if (blocks == null || vil == null || sp == null) why = "bad counts";
                    else {
                        String name = str(o.get("name"));
                        if (name == null && o.get("name") != null && !o.get("name").isJsonNull()) notes.add("survey: " + id + " name not used");
                        b = new Build(id, dim, chunks, y[0], y[1], builders, kind, blocks, vil, sp, name);
                    }
                }
            }
            if (b != null) out.add(b);
            else notes.add("survey: " + (id == null ? "row " + row : id) + " dropped (" + why + ")");
        }
        return out;
    }

    private static Set<Long> chunks(JsonElement v) {
        if (!(v instanceof JsonArray a) || a.isEmpty() || a.size() > MAX_CHUNKS) return null;
        Set<Long> out = new HashSet<>();
        for (JsonElement c : a) {
            if (!(c instanceof JsonArray p) || p.size() != 2) return null;
            Integer x = num(p.get(0), -2_000_000, 2_000_000), z = num(p.get(1), -2_000_000, 2_000_000);
            if (x == null || z == null) return null;
            out.add(chunk(x, z));
        }
        return out;
    }

    private static int[] ys(JsonElement v) {
        if (!(v instanceof JsonArray a) || a.size() != 2) return null;
        Integer lo = num(a.get(0), -2048, 2048), hi = num(a.get(1), -2048, 2048);
        return lo == null || hi == null || lo > hi ? null : new int[] {lo, hi};
    }

    private static List<String> builders(JsonElement v) {
        if (!(v instanceof JsonArray a) || a.isEmpty() || a.size() > 6) return null;
        List<String> out = new ArrayList<>();
        for (JsonElement e : a) {
            String s = str(e);
            try { if (s == null || !UUID.fromString(s).toString().equals(s)) return null; }
            catch (IllegalArgumentException bad) { return null; }
            if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    /** key -> positive whole count; optional (missing = empty) unless blocks, which must have at least one. */
    private static Map<String, Integer> counts(JsonElement v, boolean optional) {
        if (v == null || v.isJsonNull()) return optional ? Map.of() : null;
        if (!(v instanceof JsonObject o) || o.size() > MAX_KEYS || (!optional && o.isEmpty())) return null;
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            Integer n = num(e.getValue(), 1, MAX_COUNT);
            if (!KEY.matcher(e.getKey()).matches() || n == null) return null;
            out.put(e.getKey(), n);
        }
        return out;
    }

    private static Integer num(JsonElement v, int lo, int hi) {
        if (!(v instanceof JsonPrimitive p) || !p.isNumber()) return null;
        double d = p.getAsDouble();
        return d == Math.rint(d) && d >= lo && d <= hi ? (int) d : null;
    }

    private static String str(JsonElement v) { return v instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null; }
}
