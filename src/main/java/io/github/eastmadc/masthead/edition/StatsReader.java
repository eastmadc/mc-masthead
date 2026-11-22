package io.github.eastmadc.masthead.edition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads one stats snapshot folder (vanilla world/stats/*.json + usercache.json) into per-player season rows
 * (port of load_stats). Used by the player-cards and owner pages; pure file reading, no server needed.
 */
public final class StatsReader {
    private StatsReader() {}

    /** One player's season numbers. Scalars are Long (counts) or Double (scaled: minutes, meters, damage). */
    public static final class Row {
        public final Map<String, Number> v = new LinkedHashMap<>();
        public final Map<String, Long> killed = new LinkedHashMap<>(), mined = new LinkedHashMap<>(), used = new LinkedHashMap<>(),
                crafted = new LinkedHashMap<>();

        public double d(String k) {
            Number n = v.get(k);
            return n == null ? 0 : n.doubleValue();
        }

        public boolean isFloat(String k) {
            return v.get(k) instanceof Double;
        }
    }

    /** key, category, stat, multiplier (null = 1, an int stays an int). */
    private static final Object[][] STATS = {
            {"play_min", "custom", "play_time", 1.0 / 1200}, {"deaths", "custom", "deaths", null}, {"mob_kills", "custom", "mob_kills", null},
            {"player_kills", "custom", "player_kills", null}, {"jumps", "custom", "jump", null},
            {"walk_m", "custom", "walk_one_cm", 0.01}, {"sprint_m", "custom", "sprint_one_cm", 0.01},
            {"horse_m", "custom", "horse_one_cm", 0.01}, {"boat_m", "custom", "boat_one_cm", 0.01},
            {"elytra_m", "custom", "aviate_one_cm", 0.01}, {"trades", "custom", "traded_with_villager", null},
            {"bred", "custom", "animals_bred", null}, {"fish", "custom", "fish_caught", null},
            {"cake", "custom", "eat_cake_slice", null}, {"damage_taken", "custom", "damage_taken", 0.1},
            {"nautilus_m", "custom", "nautilus_one_cm", 0.01}, {"happy_ghast_m", "custom", "happy_ghast_one_cm", 0.01},
            {"swim_m", "custom", "swim_one_cm", 0.01}, {"minecart_m", "custom", "minecart_one_cm", 0.01},
            {"strider_m", "custom", "strider_one_cm", 0.01}, {"raids_won", "custom", "raid_win", null},
            {"raids_started", "custom", "raid_trigger", null}, {"enchanted", "custom", "enchant_item", null},
            {"bells_rung", "custom", "bell_ring", null},
            {"discs_played", "custom", "play_record", null}, {"villager_talks", "custom", "talked_to_villager", null}};
    static final String[] ORES = {"diamond_ore", "deepslate_diamond_ore", "ancient_debris", "emerald_ore", "deepslate_emerald_ore"};

    /** As load(dir), without the players in privacy.hide-players (any case): they are on no page and in no ranking. */
    public static Map<String, Row> load(Path dir, java.util.Collection<String> hidden) {
        Map<String, Row> out = load(dir);
        java.util.Set<String> h = new java.util.HashSet<>();
        for (String n : hidden) h.add(n.toLowerCase(java.util.Locale.ROOT));
        out.keySet().removeIf(n -> h.contains(n.toLowerCase(java.util.Locale.ROOT)));
        return out;
    }

    /** The snapshot's name cache (usercache.json): UUID -> the name the player had when it was taken. */
    public static Map<String, String> names(Path dir) {
        Map<String, String> names = new LinkedHashMap<>();
        try {
            String t = MailDesk.readText(dir.resolve("usercache.json"));
            if (t != null) {
                for (JsonElement u : PyJson.loads(t).getAsJsonArray()) {
                    if (!u.isJsonObject()) continue;
                    JsonObject o = u.getAsJsonObject();
                    String n = PyJson.strOr(o.get("name"), "");
                    if (Py.isName(n) && PyJson.isStr(o.get("uuid"))) names.put(o.get("uuid").getAsString(), n);
                }
            }
        } catch (RuntimeException e) {
            // like Python: no names, no rows
        }
        return names;
    }

    public static Map<String, Row> load(Path dir) {
        Map<String, String> names = names(dir);
        Map<String, Row> out = new LinkedHashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*-*-*-*-*.json")) {
            for (Path f : ds) {
                String fn = f.getFileName().toString();
                String n = names.get(fn.substring(0, fn.length() - 5));
                if (n == null) continue;
                JsonObject s;
                try {
                    String t = MailDesk.readText(f);
                    if (t == null) continue;
                    s = PyJson.obj(PyJson.loads(t).getAsJsonObject().get("stats"));
                } catch (RuntimeException e) {
                    continue;
                }
                out.put(n, row(s));
            }
        } catch (IOException e) {
            // no folder: no rows
        }
        return out;
    }

    private static JsonPrimitive get(JsonObject s, String c, String k) {
        JsonElement cat = s.get("minecraft:" + c);
        if (cat == null || !cat.isJsonObject()) return null;
        JsonElement v = cat.getAsJsonObject().get("minecraft:" + k);
        return PyJson.isNum(v) ? v.getAsJsonPrimitive() : null;
    }

    private static Number num(JsonPrimitive p) {
        if (p == null) return 0L;
        return PyJson.isFloat(p) ? (Number) p.getAsDouble() : (Number) p.getAsLong();
    }

    static Row row(JsonObject s) {
        Row r = new Row();
        for (Object[] st : STATS) {
            Number v = num(get(s, (String) st[1], (String) st[2]));
            if (st[3] != null) r.v.put((String) st[0], v.doubleValue() * (Double) st[3]);
            else r.v.put((String) st[0], v);
        }
        long blocks = 0;
        JsonElement mined = s.get("minecraft:mined");
        if (mined != null && mined.isJsonObject())
            for (Map.Entry<String, JsonElement> e : mined.getAsJsonObject().entrySet())
                if (PyJson.isNum(e.getValue()) && !PyJson.isFloat(e.getValue().getAsJsonPrimitive())) blocks += e.getValue().getAsLong();
        r.v.put("blocks_mined", blocks);
        r.v.put("eggs", num(get(s, "used", "egg")));
        for (String o : ORES) r.v.put("mined_" + o, num(get(s, "mined", o)));
        dict(s, "killed", r.killed);
        dict(s, "mined", r.mined);
        dict(s, "used", r.used);
        dict(s, "crafted", r.crafted);
        return r;
    }

    private static void dict(JsonObject s, String cat, Map<String, Long> into) {
        JsonElement e = s.get("minecraft:" + cat);
        if (e == null || !e.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
            if (!PyJson.isNum(x.getValue()) || PyJson.isFloat(x.getValue().getAsJsonPrimitive())) continue;
            String k = x.getKey();
            into.put(k.substring(k.lastIndexOf(':') + 1), x.getValue().getAsLong());
        }
    }
}
