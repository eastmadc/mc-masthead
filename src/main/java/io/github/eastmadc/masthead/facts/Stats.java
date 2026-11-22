package io.github.eastmadc.masthead.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Player stats snapshots: Python load_stats(), delta(), leaders(). */
final class Stats {
    private Stats() {}

    /** (key, stats category, stats key, multiplier). Multiplier 1 keeps the value an integer, as in Python. */
    private record Stat(String key, String cat, String name, double mul) {}

    private static final List<Stat> STATS = List.of(
            new Stat("play_min", "custom", "play_time", 1.0 / 1200), new Stat("deaths", "custom", "deaths", 1),
            new Stat("mob_kills", "custom", "mob_kills", 1), new Stat("player_kills", "custom", "player_kills", 1),
            new Stat("jumps", "custom", "jump", 1), new Stat("walk_m", "custom", "walk_one_cm", 0.01),
            new Stat("sprint_m", "custom", "sprint_one_cm", 0.01), new Stat("horse_m", "custom", "horse_one_cm", 0.01),
            new Stat("boat_m", "custom", "boat_one_cm", 0.01), new Stat("elytra_m", "custom", "aviate_one_cm", 0.01),
            new Stat("trades", "custom", "traded_with_villager", 1), new Stat("bred", "custom", "animals_bred", 1),
            new Stat("fish", "custom", "fish_caught", 1), new Stat("cake", "custom", "eat_cake_slice", 1),
            new Stat("damage_taken", "custom", "damage_taken", 0.1),
            new Stat("nautilus_m", "custom", "nautilus_one_cm", 0.01), new Stat("happy_ghast_m", "custom", "happy_ghast_one_cm", 0.01),
            new Stat("swim_m", "custom", "swim_one_cm", 0.01), new Stat("minecart_m", "custom", "minecart_one_cm", 0.01),
            new Stat("strider_m", "custom", "strider_one_cm", 0.01), new Stat("raids_won", "custom", "raid_win", 1),
            new Stat("raids_started", "custom", "raid_trigger", 1), new Stat("enchanted", "custom", "enchant_item", 1),
            new Stat("bells_rung", "custom", "bell_ring", 1),
            new Stat("discs_played", "custom", "play_record", 1), new Stat("villager_talks", "custom", "talked_to_villager", 1));
    private static final List<String> ORES = List.of("diamond_ore", "deepslate_diamond_ore", "ancient_debris", "emerald_ore", "deepslate_emerald_ore");
    static final List<String> MAPS = List.of("killed", "mined", "used", "crafted", "broken");   // broken: tools and armor worn out

    /** One player's numbers. nums values are Long or Double (Python int or float); maps are item -> count. */
    static final class Row {
        final LinkedHashMap<String, Number> nums = new LinkedHashMap<>();
        final LinkedHashMap<String, LinkedHashMap<String, Long>> maps = new LinkedHashMap<>();
        /** The stats file's UUID (set by {@link #load}); not part of the facts. A name can change, this can't. */
        String uuid;

        long num(String k) {
            Number n = nums.get(k);
            return n == null ? 0 : n.longValue();
        }

        double dbl(String k) {
            Number n = nums.get(k);
            return n == null ? 0 : n.doubleValue();
        }

        Map<String, Long> map(String k) {
            Map<String, Long> m = maps.get(k);
            return m == null ? Map.of() : m;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            nums.forEach((k, v) -> o.add(k, Py.json(v)));
            maps.forEach((k, m) -> o.add(k, mapJson(m)));
            return o;
        }
    }

    static JsonObject mapJson(Map<String, ? extends Number> m) {
        JsonObject o = new JsonObject();
        m.forEach((k, v) -> o.add(k, Py.json(v)));
        return o;
    }

    /** Python load_stats(dir). Players come in directory order (same as Python's glob), which decides ties. */
    static LinkedHashMap<String, Row> load(Path dir) {
        return load(dir, Map.of());
    }

    /**
     * An older snapshot, read for today's rows: a stats file whose UUID is in today goes under the name it has today, so a
     * player who changed name, or whose name fell out of the name cache, is compared with their own old numbers and a
     * lifetime total never reads as one day's. A UUID that is not in today keeps the name it had then (the match by name).
     */
    static LinkedHashMap<String, Row> load(Path dir, Map<String, Row> today) {
        Map<String, String> nameToday = new HashMap<>();
        today.forEach((n, r) -> nameToday.put(r.uuid, n));
        Set<String> byUuid = new java.util.HashSet<>();
        Map<String, String> names = new HashMap<>();
        JsonElement cache = Py.parse(orEmpty(Py.readCapped(dir.resolve("usercache.json"), 4 << 20)));
        if (cache != null && cache.isJsonArray()) {
            for (JsonElement e : cache.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                String uuid = str(e.getAsJsonObject().get("uuid")), name = str(e.getAsJsonObject().get("name"));
                if (uuid != null && Py.isName(name)) names.put(uuid, name);
            }
        }
        LinkedHashMap<String, Row> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path f : ds) {
                String fn = f.getFileName().toString();
                if (fn.startsWith(".") || !fn.matches(".*-.*-.*-.*-.*\\.json")) continue;
                String id = fn.substring(0, fn.length() - 5), name = nameToday.getOrDefault(id, names.get(id));
                if (name == null) continue;
                if (!nameToday.containsKey(id) && byUuid.contains(name)) continue;   // the UUID match wins over another file's old name
                JsonElement root = Py.parse(orEmpty(Py.readCapped(f, 8 << 20)));
                if (root == null || !root.isJsonObject()) continue;
                JsonElement s = root.getAsJsonObject().get("stats");
                Row r = row(s != null && s.isJsonObject() ? s.getAsJsonObject() : new JsonObject());
                r.uuid = id;
                if (nameToday.containsKey(id)) byUuid.add(name);
                out.put(name, r);
            }
        } catch (IOException e) {
            // unreadable folder: treat as no stats
        }
        return out;
    }

    private static Row row(JsonObject s) {
        Row r = new Row();
        for (Stat st : STATS) {
            Number v = get(s, st.cat, st.name);
            r.nums.put(st.key, st.mul == 1 ? v : (Number) (v.doubleValue() * st.mul));
        }
        long mined = 0;
        for (Map.Entry<String, Long> e : category(s, "mined").entrySet()) mined += e.getValue();
        r.nums.put("blocks_mined", mined);
        r.nums.put("eggs", get(s, "used", "egg"));
        for (String o : ORES) r.nums.put("mined_" + o, get(s, "mined", o));
        for (String m : MAPS) {
            LinkedHashMap<String, Long> short_ = new LinkedHashMap<>();
            category(s, m).forEach((k, v) -> short_.put(k.substring(k.lastIndexOf(':') + 1), v));
            r.maps.put(m, short_);
        }
        return r;
    }

    /** Integer entries of minecraft:CAT, full keys. */
    private static LinkedHashMap<String, Long> category(JsonObject s, String cat) {
        LinkedHashMap<String, Long> out = new LinkedHashMap<>();
        JsonElement c = s.get("minecraft:" + cat);
        if (c == null || !c.isJsonObject()) return out;
        for (Map.Entry<String, JsonElement> e : c.getAsJsonObject().entrySet()) {
            if (Py.isNumber(e.getValue()) && Py.number(e.getValue().getAsJsonPrimitive()) instanceof Long l) out.put(e.getKey(), l);
        }
        return out;
    }

    private static Number get(JsonObject s, String cat, String key) {
        JsonElement c = s.get("minecraft:" + cat);
        if (c == null || !c.isJsonObject()) return 0L;
        JsonElement v = c.getAsJsonObject().get("minecraft:" + key);
        return Py.isNumber(v) ? Py.number(v.getAsJsonPrimitive()) : (Number) 0L;
    }

    /** Python delta(now, before): per-player change, only players with at least one minute played. */
    static LinkedHashMap<String, Row> delta(Map<String, Row> now, Map<String, Row> before) {
        LinkedHashMap<String, Row> res = new LinkedHashMap<>();
        for (Map.Entry<String, Row> pe : now.entrySet()) {
            Row r = pe.getValue(), b = before.get(pe.getKey()), d = new Row();
            for (Map.Entry<String, Number> e : r.nums.entrySet()) {
                Number v = e.getValue(), bv = b == null ? null : b.nums.get(e.getKey());
                if (bv == null) bv = 0L;
                if (v instanceof Long lv && bv instanceof Long lb) d.nums.put(e.getKey(), lv - lb);
                else d.nums.put(e.getKey(), Py.round1(v.doubleValue() - bv.doubleValue()));
            }
            for (Map.Entry<String, LinkedHashMap<String, Long>> e : r.maps.entrySet()) {
                Map<String, Long> bd = b == null ? Map.of() : b.map(e.getKey());
                List<Map.Entry<String, Long>> top = new ArrayList<>();
                e.getValue().forEach((k, v) -> top.add(Map.entry(k, v - bd.getOrDefault(k, 0L))));
                top.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));   // stable, like Python sorted()
                LinkedHashMap<String, Long> m = new LinkedHashMap<>();
                for (Map.Entry<String, Long> t : top.subList(0, Math.min(15, top.size()))) if (t.getValue() > 0) m.put(t.getKey(), t.getValue());
                d.maps.put(e.getKey(), m);
            }
            if (d.dbl("play_min") >= 1) res.put(pe.getKey(), d);
        }
        return res;
    }

    /** Python leaders(rows): top 3 per numeric stat, rounded. */
    static JsonObject leaders(Map<String, Row> rows) {
        Set<String> keys = new TreeSet<>();
        rows.values().forEach(r -> keys.addAll(r.nums.keySet()));
        JsonObject out = new JsonObject();
        for (String k : keys) {
            List<Map.Entry<String, Number>> top = new ArrayList<>();
            rows.forEach((n, r) -> {
                Number v = r.nums.get(k);
                if (v != null && v.doubleValue() > 0) top.add(Map.entry(n, v));
            });
            if (top.isEmpty()) continue;
            top.sort((x, y) -> Double.compare(y.getValue().doubleValue(), x.getValue().doubleValue()));
            JsonArray a = new JsonArray();
            for (Map.Entry<String, Number> t : top.subList(0, Math.min(3, top.size()))) {
                JsonArray pair = new JsonArray();
                pair.add(t.getKey());
                pair.add(Py.round(t.getValue()));
                a.add(pair);
            }
            out.add(k, a);
        }
        return out;
    }

    /** A row's numbers rounded to whole numbers (season_totals, owner season). */
    static JsonObject rounded(Row r) {
        JsonObject o = new JsonObject();
        r.nums.forEach((k, v) -> o.addProperty(k, Py.round(v)));
        return o;
    }

    static JsonObject rows(Map<String, Row> rows) {
        JsonObject o = new JsonObject();
        rows.forEach((n, r) -> o.add(n, r.toJson()));
        return o;
    }

    private static String str(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
