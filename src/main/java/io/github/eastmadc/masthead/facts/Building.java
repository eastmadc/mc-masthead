package io.github.eastmadc.masthead.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Building Report (features.building-report): how many blocks each player placed since the last paper, from the
 * "used" counts in the stats files the plugin already snapshots. No locations, no times: only names, counts and plain
 * material words.
 *
 * Facts shape (key "building", present only when someone placed at least {@link #MIN_BLOCKS}):
 *   {"blocks_today": 2310,                                  every player's placed blocks added up
 *    "builders": [{"name":"Alex","blocks_today":1800,
 *                  "materials":[{"what":"spruce","blocks":900},{"what":"glass","blocks":200}]}, ...],
 *    "leaders": [["Alex",1800],["Sam",410]]}                 the top 3 builders, like "leaders_today"
 *
 * Later, construction news (design SPEC section 5) folds this in as its "busiest builders" line; the keys
 * blocks_today, builders and materials[{what, blocks}] already use that spec's names.
 */
final class Building {
    private Building() {}

    /** A player is listed only from this many blocks placed (a few torches and a door are not news). */
    static final int MIN_BLOCKS = 64;
    static final int MAX_BUILDERS = 8;

    /**
     * The report, or null when nobody placed enough. blockItems = item keys that are blocks (from the server, e.g.
     * "oak_planks"); everything else "used" (tools, food, buckets, eggs) is not counted. Hidden players are already gone
     * from now and prev. Players are matched by UUID, so a new name doesn't make a lifetime total look like today's.
     * gapMin = the most minutes anyone could have played since prev: a player missing from prev (name cache evicted, just
     * un-hidden) who has played longer than that is not new, and is skipped rather than counted from zero.
     */
    static JsonObject report(Map<String, Stats.Row> now, Map<String, Stats.Row> prev, Set<String> blockItems, long gapMin,
                             java.util.function.BiPredicate<String, String> listed) {
        long total = 0;
        List<JsonObject> builders = new ArrayList<>();
        Map<String, Stats.Row> prevById = new HashMap<>();
        prev.values().forEach(r -> { if (r.uuid != null) prevById.put(r.uuid, r); });
        for (Map.Entry<String, Stats.Row> pe : now.entrySet()) {
            Stats.Row b = pe.getValue().uuid != null ? prevById.get(pe.getValue().uuid) : prev.get(pe.getKey());
            if (b == null && pe.getValue().dbl("play_min") > gapMin) continue;
            Map<String, Long> before = b == null ? Map.of() : b.map("used");
            long placed = 0;
            Map<String, Long> fam = new HashMap<>();
            for (Map.Entry<String, Long> e : pe.getValue().map("used").entrySet()) {
                if (!blockItems.contains(e.getKey())) continue;
                long d = Math.max(0, e.getValue() - before.getOrDefault(e.getKey(), 0L));   // a reset stats file is not negative building
                placed += d;
                String f = family(e.getKey());
                if (f != null && d > 0) fam.merge(f, d, Long::sum);
            }
            total += placed;
            if (placed < MIN_BLOCKS || !listed.test(pe.getValue().uuid, pe.getKey())) continue;   // counted, not listed
            JsonObject o = new JsonObject();
            o.addProperty("name", pe.getKey());
            o.addProperty("blocks_today", placed);
            o.add("materials", materials(fam, placed));
            builders.add(o);
        }
        if (builders.isEmpty()) return null;
        builders.sort(Comparator.comparingLong((JsonObject o) -> -o.get("blocks_today").getAsLong())
                .thenComparing(o -> o.get("name").getAsString()));
        JsonObject out = new JsonObject();
        out.addProperty("blocks_today", total);
        JsonArray list = new JsonArray(), leaders = new JsonArray();
        for (JsonObject o : builders.subList(0, Math.min(MAX_BUILDERS, builders.size()))) list.add(o);
        for (JsonObject o : builders.subList(0, Math.min(3, builders.size()))) {
            JsonArray pair = new JsonArray();
            pair.add(o.get("name"));
            pair.add(o.get("blocks_today"));
            leaders.add(pair);
        }
        out.add("builders", list);
        out.add("leaders", leaders);
        return out;
    }

    /** Top 3 named materials, each at least 5% of what the player placed, most first. */
    private static JsonArray materials(Map<String, Long> fam, long placed) {
        List<Map.Entry<String, Long>> l = new ArrayList<>(fam.entrySet());
        l.sort(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        JsonArray a = new JsonArray();
        for (Map.Entry<String, Long> e : l) {
            if (a.size() == 3 || e.getValue() * 20 < placed) break;
            JsonObject m = new JsonObject();
            m.addProperty("what", e.getKey());
            m.addProperty("blocks", e.getValue());
            a.add(m);
        }
        return a;
    }

    /**
     * Materials the paper may name, in plain words. Only these are ever named: anything else (sand, netherrack, deepslate,
     * prismarine, jungle wood, chests, diamond blocks, new blocks nobody checked) still counts in the totals but is never
     * named, because it can hint at a biome, a dimension, a depth, a structure or a stash. Woods are the five that grow
     * almost everywhere.
     */
    static final Set<String> NAMED = Set.of("oak", "spruce", "birch", "acacia", "dark oak", "stone", "cobblestone", "stone brick",
            "brick", "andesite", "diorite", "granite", "concrete", "wool", "carpet", "terracotta", "glass", "quartz", "copper",
            "dirt", "gravel", "torch", "lantern", "ladder", "scaffolding", "bookshelf", "hay", "rail", "iron bars", "piston",
            "observer", "repeater", "comparator", "redstone lamp", "bed");

    private static final Pattern PREFIX = Pattern.compile("^(stripped|polished|smooth|chiseled|cut|waxed|weathered|exposed|oxidized|sticky)_");
    private static final Pattern COLOR = Pattern.compile("^(light_blue|light_gray|white|orange|magenta|yellow|lime|pink|gray|cyan|purple|blue|brown|green|red|black)_");
    private static final Pattern SUFFIX = Pattern.compile("_(stairs|slab|wall|fence_gate|fence|trapdoor|door|pressure_plate|button|hanging_sign|sign|pane|planks|log|wood|block)$");

    /** "spruce_stairs" -> "spruce", "white_stained_glass_pane" -> "glass", "stone_bricks" -> "stone brick"; null = never named. */
    static String family(String key) {
        String k = SUFFIX.matcher(key).replaceFirst("");
        for (String was = null; !k.equals(was); ) {
            was = k;
            k = PREFIX.matcher(k).replaceFirst("");
        }
        k = COLOR.matcher(k).replaceFirst("");
        k = switch (k) {
            case "stained_glass" -> "glass";
            case "glazed_terracotta" -> "terracotta";
            case "concrete_powder" -> "concrete";
            default -> k;
        };
        if (k.endsWith("bricks")) k = k.substring(0, k.length() - 1);
        k = k.replace('_', ' ');
        return NAMED.contains(k) ? k : null;
    }
}
