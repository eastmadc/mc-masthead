package io.github.eastmadc.masthead.construction;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a build has inside, from its block counts: an ordered list from a closed set of features, each with a real count
 * the fact check can find in the data (librarian trading, 17 lecterns). Pure.
 *
 * <p>Use: {@code of(blocks, villagers, spawners)} gives the list, {@code json(list)} the entry field ({@code [{"feature":
 * "librarian_trading","n":17}]}), and {@code WORDS.get(id)} the plain words for an id: {counted, for} ("lecterns",
 * "librarian trading"); "for" is null when the counted words say it all ("beds"). The tracker can't see villagers or
 * spawners, so daily builds pass empty maps; the world survey passes villagers by profession ("librarian") and spawners
 * by mob ("spider").
 */
public final class Features {
    private Features() {}

    /** One feature: an id from {@link #WORDS} and how many of what it counts. */
    public record F(String id, int n) {}

    /** At most this many features per build reach the paper. */
    public static final int MAX = 8;

    /** Job block -> villager profession, in print order. */
    static final Map<String, String> STATION = new LinkedHashMap<>();
    /** Job blocks with a common use of their own: they count as trading only when the survey saw villagers of that job. */
    static final Set<String> SHARED = Set.of("barrel", "composter", "brewing_stand", "cauldron");
    /** Mobs a spawner can hold that get their own feature; any other spawner is just "spawners". */
    static final List<String> SPAWNER_MOBS = List.of("zombie", "skeleton", "spider", "cave_spider", "blaze", "silverfish", "magma_cube");
    /** Crop feature -> the block keys counted (as placed; attached stems are recorded as stems). */
    private static final Map<String, List<String>> CROP_KEYS = new LinkedHashMap<>();
    public static final Map<String, List<String>> CROPS = Collections.unmodifiableMap(CROP_KEYS);
    /** Every feature id, in print order -> {what is counted, what it is for or null}. Plain words, never an underscore. */
    private static final Map<String, String[]> W = new LinkedHashMap<>();
    public static final Map<String, String[]> WORDS = Collections.unmodifiableMap(W);
    static {
        String[][] st = {{"lectern", "librarian", "lecterns"}, {"blast_furnace", "armorer", "blast furnaces"}, {"smoker", "butcher", "smokers"},
                {"composter", "farmer", "composters"}, {"barrel", "fisherman", "barrels"}, {"cartography_table", "cartographer", "cartography tables"},
                {"fletching_table", "fletcher", "fletching tables"}, {"grindstone", "weaponsmith", "grindstones"}, {"loom", "shepherd", "looms"},
                {"smithing_table", "toolsmith", "smithing tables"}, {"stonecutter", "mason", "stonecutters"}, {"cauldron", "leatherworker", "cauldrons"},
                {"brewing_stand", "cleric", "brewing stands"}};
        for (String[] s : st) { STATION.put(s[0], s[1]); W.put(s[1] + "_trading", new String[] {s[2], s[1] + " trading"}); }
        for (String[] s : st) W.put(s[1] + "_villagers", new String[] {s[1] + " villagers", null});
        W.put("villagers", new String[] {"villagers", null});
        for (String m : SPAWNER_MOBS) W.put(m + "_spawners", new String[] {m.replace('_', ' ') + " spawners", null});
        W.put("spawners", new String[] {"spawners", null});
        W.put("bookshelves", new String[] {"bookshelves", null});
        W.put("enchanting_tables", new String[] {"enchanting tables", null});
        W.put("brewing_stands", new String[] {"brewing stands", null});
        W.put("furnaces", new String[] {"furnaces", "smelting"});
        W.put("storage", new String[] {"chests and barrels", null});
        W.put("beds", new String[] {"beds", null});
        W.put("beacons", new String[] {"beacons", null});
        W.put("bells", new String[] {"bells", null});
        W.put("nether_portal", new String[] {"obsidian blocks", "nether portal"});
        String[][] cr = {{"wheat", "wheat crops", "wheat"}, {"carrots", "carrot crops", "carrots"}, {"potatoes", "potato crops", "potatoes"},
                {"beetroots", "beetroot crops", "beetroots"}, {"melons", "melon stems", "melon_stem attached_melon_stem"},
                {"pumpkins", "pumpkin stems", "pumpkin_stem attached_pumpkin_stem"}, {"sugar_cane", "sugar cane", "sugar_cane"}, {"cactus", "cactus", "cactus"},
                {"bamboo", "bamboo", "bamboo bamboo_sapling"}, {"kelp", "kelp", "kelp kelp_plant"}, {"sweet_berries", "sweet berry bushes", "sweet_berry_bush"},
                {"nether_wart", "nether wart", "nether_wart"}, {"cocoa", "cocoa pods", "cocoa"}, {"mushrooms", "mushrooms", "brown_mushroom red_mushroom"}};
        for (String[] c : cr) { CROP_KEYS.put(c[0], List.of(c[2].split(" "))); W.put(c[0], new String[] {c[1], null}); }
        W.put("machine_parts", new String[] {"observers and pistons", null});
    }

    /** Features that point at loot (storage, beacons) or a place (spawners hint at a natural structure, these crops at a biome). */
    static final Set<String> HINTS = Set.of("storage", "beacons", "cactus", "bamboo", "mushrooms", "nether_wart", "kelp", "cocoa");

    /** What may print at a construction.detail level: basic = nothing, standard = no loot or place hints, full = all. */
    public static List<F> shown(List<F> features, String detail) {
        return switch (detail) {
            case "full" -> features;
            case "basic" -> List.of();
            default -> features.stream().filter(f -> !HINTS.contains(f.id()) && !f.id().endsWith("spawners")).toList();
        };
    }

    /** The counted words for n of a feature, singular for 1 ("1 spider spawner", "1 enchanting table"). */
    public static String counted(String id, int n) {
        String w = WORDS.get(id)[0];
        // only these can count 1 (Features.of); the rest start at 3+ and some have no plain singular ("chests and barrels")
        boolean one = n == 1 && (id.endsWith("villagers") || id.endsWith("spawners") || ONE.contains(id));
        return one ? w.substring(0, w.length() - 1) : w;
    }

    private static final Set<String> ONE = Set.of("enchanting_tables", "brewing_stands", "beacons", "bells");

    /** blocks = net placed counts by key (negatives count as none); villagers and spawners may be empty. */
    public static List<F> of(Map<String, Integer> blocks, Map<String, Integer> villagers, Map<String, Integer> spawners) {
        List<F> out = new ArrayList<>();
        int fam = n(blocks, "furnace", "smoker", "blast_furnace");
        boolean smelter = smelter(blocks);
        boolean barrels = false, stands = false;
        for (Map.Entry<String, String> e : STATION.entrySet()) {
            String k = e.getKey(), job = e.getValue();
            int v = k.equals("cauldron") ? cauldrons(blocks) : n(blocks, k);
            boolean cook = k.equals("smoker") || k.equals("blast_furnace");   // also cooking/smelting blocks: a long row, or that job seen
            boolean ok = v >= 3 && !(smelter && cook) && (!SHARED.contains(k) || villagers.getOrDefault(job, 0) > 0)
                    && (!cook || v >= 12 || villagers.getOrDefault(job, 0) > 0);
            if (!ok) continue;
            out.add(new F(job + "_trading", v));
            barrels |= k.equals("barrel");
            stands |= k.equals("brewing_stand");
        }
        int other = 0;
        for (Map.Entry<String, Integer> e : villagers.entrySet()) if (e.getValue() > 0 && !STATION.containsValue(e.getKey())) other += e.getValue();
        for (String job : STATION.values()) add(out, job + "_villagers", villagers.getOrDefault(job, 0), 1);
        add(out, "villagers", other, 1);
        other = 0;
        for (Map.Entry<String, Integer> e : spawners.entrySet()) if (e.getValue() > 0 && !SPAWNER_MOBS.contains(e.getKey())) other += e.getValue();
        for (String m : SPAWNER_MOBS) add(out, m + "_spawners", spawners.getOrDefault(m, 0), 1);
        add(out, "spawners", other, 1);
        add(out, "bookshelves", n(blocks, "bookshelf", "chiseled_bookshelf"), 4);
        add(out, "enchanting_tables", n(blocks, "enchanting_table"), 1);
        if (!stands) add(out, "brewing_stands", n(blocks, "brewing_stand"), 1);
        if (smelter) out.add(new F("furnaces", fam));
        add(out, "storage", n(blocks, "chest", "trapped_chest") + (barrels ? 0 : n(blocks, "barrel")), 16);
        add(out, "beds", beds(blocks), 3);
        add(out, "beacons", n(blocks, "beacon"), 1);
        add(out, "bells", n(blocks, "bell"), 1);
        add(out, "nether_portal", n(blocks, "obsidian"), 10);   // the smallest portal frame is 10 obsidian
        for (Map.Entry<String, List<String>> c : CROPS.entrySet()) add(out, c.getKey(), n(blocks, c.getValue().toArray(new String[0])), 8);
        add(out, "machine_parts", n(blocks, "observer", "piston", "sticky_piston"), 8);
        return out;
    }

    /** 8+ furnaces of any kind, mostly plain ones or fed by hoppers; else a row of blast furnaces is armorers' job blocks. */
    public static boolean smelter(Map<String, Integer> blocks) {
        int fam = n(blocks, "furnace", "smoker", "blast_furnace");
        return fam >= 8 && (n(blocks, "furnace") >= Math.max(n(blocks, "smoker"), n(blocks, "blast_furnace")) || n(blocks, "hopper") >= fam);
    }

    /** The entry field: the first MAX features as {"feature": id, "n": count}. */
    public static JsonArray json(List<F> features) {
        JsonArray a = new JsonArray();
        for (F f : features.subList(0, Math.min(MAX, features.size()))) {
            JsonObject o = new JsonObject();
            o.addProperty("feature", f.id());
            o.addProperty("n", f.n());
            a.add(o);
        }
        return a;
    }

    /** The positive counts of these keys, added up. */
    public static int n(Map<String, Integer> blocks, String... keys) {
        long t = 0;
        for (String k : keys) t += Math.max(0, blocks.getOrDefault(k, 0));
        return (int) Math.min(Integer.MAX_VALUE, t);
    }

    static int beds(Map<String, Integer> blocks) { return sum(blocks, "_bed"); }

    /** Empty, water, lava and powder snow cauldrons are different block keys. */
    static int cauldrons(Map<String, Integer> blocks) { return sum(blocks, "cauldron"); }

    private static int sum(Map<String, Integer> blocks, String suffix) {
        long t = 0;
        for (Map.Entry<String, Integer> e : blocks.entrySet()) if (e.getKey().endsWith(suffix)) t += Math.max(0, e.getValue());
        return (int) Math.min(Integer.MAX_VALUE, t);
    }

    private static void add(List<F> out, String id, int n, int min) {
        if (n >= min) out.add(new F(id, n));
    }
}
