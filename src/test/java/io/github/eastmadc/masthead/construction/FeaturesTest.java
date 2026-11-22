package io.github.eastmadc.masthead.construction;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.eastmadc.masthead.construction.ConstructionTest.STEVE;
import static io.github.eastmadc.masthead.construction.ConstructionTest.X;
import static io.github.eastmadc.masthead.construction.ConstructionTest.Y;
import static io.github.eastmadc.masthead.construction.ConstructionTest.Z;

/**
 * alpha.21: what is inside a build (Features) from real-looking block counts, the kinds desk's evidence vetoes per kind
 * family with the second and parent fallbacks, the card's notable_blocks, and the kinds list's words. Plain main().
 */
public final class FeaturesTest {
    static int ok = 0;

    static void t(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
        ok++;
    }

    public static void main(String[] args) throws Exception {
        features();
        vetoes();
        notableBlocks();
        kinds();
        detail();
        System.out.println("FeaturesTest: " + ok + " checks passed");
    }

    static Map<String, Integer> m(Object... kv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], (Integer) kv[i + 1]);
        return out;
    }

    static String of(Map<String, Integer> blocks) { return of(blocks, Map.of(), Map.of()); }

    static String of(Map<String, Integer> blocks, Map<String, Integer> villagers, Map<String, Integer> spawners) {
        return Features.of(blocks, villagers, spawners).toString();
    }

    static String list(String... idN) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < idN.length; i += 2) b.append(i == 0 ? "" : ", ").append("F[id=").append(idN[i]).append(", n=").append(idN[i + 1]).append(']');
        return b.append(']').toString();
    }

    // ------------------------------------------------------------ the kinds of build alpha.20 got wrong
    static void features() {
        Map<String, Integer> filler = m("oak_planks", 400, "cobblestone", 120, "dirt_path", 60);
        Map<String, Integer> b = new HashMap<>(filler);
        b.putAll(m("lectern", 17, "barrel", 5, "cartography_table", 1));
        t(of(b).equals(list("librarian_trading", "17")), "17 lecterns + 5 barrels: librarian trading, barrels are not proof of fishermen: " + of(b));
        t(of(b, m("librarian", 4, "fisherman", 2, "nitwit", 1), Map.of()).equals(list("librarian_trading", "17", "fisherman_trading", "5", "librarian_villagers", "4",
                "fisherman_villagers", "2", "villagers", "1")), "the survey's villagers prove the barrels: " + of(b, m("librarian", 4, "fisherman", 2, "nitwit", 1), Map.of()));
        t(of(m("blast_furnace", 13, "carrots", 30)).equals(list("armorer_trading", "13", "carrots", "30")), "13 blast furnaces: armorer: " + of(m("blast_furnace", 13, "carrots", 30)));
        Map<String, Integer> melon = m("melon_stem", 110, "pumpkin_stem", 24, "observer", 36, "piston", 36, "hopper", 4);
        t(of(melon).equals(list("melons", "110", "pumpkins", "24", "machine_parts", "72")), "melon farm: " + of(melon));
        Map<String, Integer> smelt = m("furnace", 34, "smoker", 11, "hopper", 38, "chest", 10);
        t(of(smelt).equals(list("furnaces", "45")), "34 furnaces + 11 smokers + 38 hoppers: a smelter, smokers are not butchers: " + of(smelt));
        t(of(m("cactus", 120, "sand", 0)).equals(list("cactus", "120")), "120 cactus");
        // the spider farm is proved by its deaths (KindDesk), the survey adds the spawner
        t(of(m("mossy_cobblestone", -25), Map.of(), m("spider", 1)).equals(list("spider_spawners", "1")), "a spider spawner, from the survey");
        t(of(Map.of(), Map.of(), m("cave_spider", 2, "zombie", 1, "trial", 1)).equals(list("zombie_spawners", "1", "cave_spider_spawners", "2", "spawners", "1")), "spawners by mob");
        // thresholds
        t(of(m("lectern", 2, "bookshelf", 3, "red_bed", 2, "obsidian", 9, "wheat", 7, "observer", 7)).equals("[]"), "below every threshold: nothing");
        t(of(m("lectern", 3, "bookshelf", 30, "chiseled_bookshelf", 4, "enchanting_table", 1, "brewing_stand", 2, "red_bed", 2, "white_bed", 1, "beacon", 1,
                "bell", 1, "obsidian", 18, "wheat", 8, "chest", 10, "trapped_chest", 2, "barrel", 4)).equals(list("librarian_trading", "3", "bookshelves", "34",
                "enchanting_tables", "1", "brewing_stands", "2", "storage", "16", "beds", "3", "beacons", "1", "bells", "1", "nether_portal", "18", "wheat", "8")),
                "rooms and things: " + of(m("lectern", 3, "bookshelf", 30)));
        t(of(m("furnace", 3, "blast_furnace", 9)).equals("[]"), "9 blast furnaces and 3 furnaces, no villagers seen: cooking, not trading: " + of(m("furnace", 3, "blast_furnace", 9)));
        t(of(m("furnace", 3, "blast_furnace", 9), m("armorer", 2), Map.of()).equals(list("armorer_trading", "9", "armorer_villagers", "2")), "armorers seen: trading");
        t(of(m("smoker", 3, "bookshelf", 6)).equals(list("bookshelves", "6")), "a house's 3 smokers are a kitchen; 6 bookshelves show: " + of(m("smoker", 3, "bookshelf", 6)));
        t(of(m("water_cauldron", 2, "cauldron", 2), m("leatherworker", 1), Map.of()).equals(list("leatherworker_trading", "4", "leatherworker_villagers", "1")), "cauldrons of any fill");
        t(of(m("lectern", -5, "chest", -40)).equals("[]"), "negative counts are none");
        // the entry field: at most MAX, ids and counts only
        Map<String, Integer> lots = new HashMap<>();
        for (String c : Features.CROPS.keySet()) for (String k : Features.CROPS.get(c)) lots.put(k, 9);
        t(Features.of(lots, Map.of(), Map.of()).size() == 14 && Features.json(Features.of(lots, Map.of(), Map.of())).size() == Features.MAX
                && Features.json(Features.of(m("cactus", 120), Map.of(), Map.of())).toString().equals("[{\"feature\":\"cactus\",\"n\":120}]"), "json: capped, ids and counts");
        for (Map.Entry<String, String[]> w : Features.WORDS.entrySet())
            t(w.getKey().matches("[a-z_]+") && w.getValue()[0].matches("[a-z ]+") && (w.getValue()[1] == null || w.getValue()[1].matches("[a-z ]+")), "plain words: " + w.getKey());

        // through the fold: the entry carries them
        ConstructionTest.Sc s = new ConstructionTest.Sc().notice(STEVE);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 7, Y + 1, Z + 7);
        s.box(STEVE, "melon_stem", X, Y + 2, Z, X + 7, Y + 2, Z + 1);
        s.fold();
        t(s.entry(0).getAsJsonArray("features").toString().equals("[{\"feature\":\"melons\",\"n\":16}]"), "the paper's entry: " + s.entry(0));
    }

    // ------------------------------------------------------------ vetoes: a specific kind the blocks don't show
    static final Map<String, Integer> KI = new HashMap<>();
    static {
        for (String k : List.of("wheat", "carrots", "potatoes", "melon_stem", "pumpkin_stem")) KI.put(k, KeyRules.info(k, Set.of("crops")));
        for (String k : List.of("poppy", "dandelion")) KI.put(k, KeyRules.info(k, Set.of("flowers")));
        for (String k : List.of("cactus", "sugar_cane", "glass", "glass_pane", "hopper", "observer", "piston", "rail", "powered_rail")) KI.put(k, KeyRules.info(k, Set.of()));
    }

    static int site = 0;

    /** A card with these materials (all shown), an optional top death, 1 builder, 12 high. */
    static KindDesk.Card card(Map<String, Integer> mats, String death, int deaths) {
        return card(mats, death == null ? null : "{\"" + death + "\":" + deaths + ",\"zombie\":3}");
    }

    /** died = the card's "mobs_died_here_7d" as JSON, biggest first; null for none. */
    static KindDesk.Card card(Map<String, Integer> mats, String died) {
        JsonObject j = new JsonObject();
        j.addProperty("net_blocks", 500);
        j.addProperty("height_blocks", 12);
        j.addProperty("builders", 1);
        JsonObject mj = new JsonObject();
        mats.forEach(mj::addProperty);
        j.add("materials_net", mj);
        if (died != null) j.add("mobs_died_here_7d", JsonParser.parseString(died));
        return KindDesk.card(site++, j, KI);
    }

    /** What the desk keeps for an answer {kind, second}, or "-". */
    static String pick(KindDesk.Card c, String kind, String second) {
        String ans = "{\"A\":{\"kind\":\"" + kind + "\"" + (second == null ? "" : ",\"second\":\"" + second + "\"") + ",\"confidence\":\"high\"}}";
        var got = KindDesk.read(ans, List.of(c));
        return got.containsKey(c.site()) ? got.get(c.site()).kind() + "/" + got.get(c.site()).confidence() : "-";
    }

    static void yes(String kind, KindDesk.Card c) { t(KindDesk.fits(kind, c), kind + " fits " + c.blocks() + " " + c.prod()); }

    static void no(String kind, KindDesk.Card c) { t(!KindDesk.fits(kind, c), kind + " vetoed for " + c.blocks() + " " + c.prod()); }

    static void vetoes() {
        // crops: 8+ of that crop's block
        yes("melon_farm", card(m("melon_stem", 110, "pumpkin_stem", 24, "observer", 36, "piston", 36), null, 0));
        no("melon_farm", card(m("pumpkin_stem", 40, "melon_stem", 7), null, 0));
        yes("pumpkin_farm", card(m("pumpkin_stem", 8), null, 0));
        yes("cactus_farm", card(m("cactus", 120), null, 0));
        no("cactus_farm", card(m("sugar_cane", 144), null, 0));
        yes("sugar_cane_farm", card(m("sugar_cane", 144), null, 0));
        yes("wheat_farm", card(m("wheat", 40), null, 0));
        no("carrot_farm", card(m("wheat", 40), null, 0));
        yes("bamboo_farm", card(m("bamboo_sapling", 20), null, 0));
        yes("kelp_farm", card(m("kelp", 30), null, 0));
        no("mushroom_farm", card(m("oak_planks", 300), null, 0));
        yes("nether_wart_farm", card(m("nether_wart", 60), null, 0));
        no("tree_farm", card(m("oak_log", 41, "dirt", 28), null, 0));   // the recorder never sees saplings: only the survey proves one
        yes("tree_farm", card(m("oak_sapling", 12), null, 0));
        yes("flower_farm", card(m("poppy", 10, "dandelion", 10), null, 0));
        no("flower_farm", card(m("poppy", 5), null, 0));
        yes("bonemeal_farm", card(m("composter", 4, "hopper", 2), null, 0));
        no("bonemeal_farm", card(m("composter", 4), null, 0));
        // mobs: the top death, 16+ in 7 days
        KindDesk.Card spiders = card(m("cobblestone", 200), "spider", 190);
        yes("spider_farm", spiders);
        no("zombie_farm", spiders);
        no("spider_farm", card(m("cobblestone", 200), "spider", 15));
        yes("spider_farm", card(m("cobblestone", 200), "cave_spider", 41));
        yes("zombie_farm", card(m("cobblestone", 200), "drowned", 40));
        yes("skeleton_farm", card(m("cobblestone", 200), "stray", 40));
        yes("gold_farm", card(m("magma_block", 200), "zombified_piglin", 900));
        yes("hoglin_farm", card(m("stone_bricks", 200), "hoglin", 2400));
        no("hoglin_farm", card(m("stone_bricks", 200), "piglin", 96));
        yes("raid_farm", card(m("cobblestone", 200), "pillager", 90));
        no("blaze_farm", card(m("cobblestone", 200), null, 0));
        yes("mob_farm", card(m("cobblestone", 200), null, 0));   // the general kind is never vetoed
        // no one mob clearly most (the kind's mobs under twice the next mob): the general kind, whatever the top death is today
        KindDesk.Card tie = card(m("cobblestone", 200), "{\"zombie\":320,\"skeleton\":300,\"creeper\":280}");
        no("zombie_farm", tie);
        no("skeleton_farm", tie);
        yes("zombie_farm", card(m("cobblestone", 200), "{\"zombie\":320,\"husk\":300,\"skeleton\":300}"));   // zombies and husks together: 620
        yes("skeleton_farm", card(m("cobblestone", 200), "{\"skeleton\":600,\"zombie\":300}"));
        t(pick(tie, "zombie_farm", "decoration").equals("mob_farm/low") && pick(tie, "zombie_farm", null).equals("mob_farm/low"),
                "a near tie is a mob farm, before \"second\": " + pick(tie, "zombie_farm", "decoration"));
        t(pick(tie, "creeper_farm", "decoration").equals("decoration/low"), "a mob kind that is not the top death still gives way to \"second\"");
        // rooms and works
        yes("library", card(m("bookshelf", 20, "chiseled_bookshelf", 4), null, 0));
        no("library", card(m("bookshelf", 13, "enchanting_table", 1), null, 0));
        yes("enchanting_room", card(m("bookshelf", 13, "enchanting_table", 1), null, 0));
        no("brewing_room", card(m("bookshelf", 13), null, 0));
        yes("smelter", card(m("furnace", 30, "smoker", 10, "hopper", 43), null, 0));
        no("smelter", card(m("furnace", 7), null, 0));
        no("smelter", card(m("blast_furnace", 13, "carrots", 30), null, 0));   // armorers' job blocks, as the features say
        yes("smelter", card(m("blast_furnace", 13, "hopper", 28), null, 0));
        yes("greenhouse", card(m("glass", 23, "glass_pane", 7, "potatoes", 16), null, 0));
        no("greenhouse", card(m("glass", 200), null, 0));
        yes("nether_portal", card(m("obsidian", 10), null, 0));
        no("nether_portal", card(m("obsidian", 4), null, 0));
        yes("rail_line", card(m("rail", 27, "powered_rail", 5), null, 0));
        no("rail_line", card(m("rail", 31), null, 0));
        // never vetoed: villagers and animals are invisible to the data
        for (String k : List.of("trading_hall", "villager_breeder", "iron_farm", "chicken_farm", "cow_farm", "wool_farm", "fishing_farm", "animal_pen", "slime_farm"))
            yes(k, card(m("oak_planks", 10), null, 0));

        // the chain: first, then second, then the first's parent, then the rules
        KindDesk.Card melonish = card(m("pumpkin_stem", 43, "observer", 8, "hopper", 2), null, 0);
        t(pick(melonish, "melon_farm", null).equals("auto_farm/low"), "melon farm with no melon stems: its parent, at low");
        t(pick(melonish, "melon_farm", "pumpkin_farm").equals("pumpkin_farm/low"), "second before parent");
        t(pick(melonish, "pumpkin_farm", "melon_farm").equals("pumpkin_farm/high"), "a fitting first keeps its confidence");
        KindDesk.Card plain = card(m("cobblestone", 300), "zombie", 21);
        t(pick(plain, "spider_farm", null).equals("mob_farm/low"), "spider farm where zombies die: mob farm");
        t(pick(plain, "library", "smelter").equals("house/low"), "library and smelter vetoed: a library's parent is a house");
        t(pick(card(m("cobblestone", 300), null, 0), "wheat_farm", null).equals("-"), "parent vetoed too (farm, no crops): the rules decide");
        t(pick(card(m("bookshelf", 40), null, 0), "library", "house").equals("library/high"), "a real library");
    }

    // ------------------------------------------------------------ the card shows the telling blocks outside the top 25
    static void notableBlocks() {
        SiteIndex.Site s = new SiteIndex.Site();
        for (int i = 0; i < 30; i++) s.mats.put(String.format("filler_%02d", i), 100 + i);
        s.mats.put("lectern", 17);
        s.mats.put("melon_stem", 9);
        s.mats.put("oak_planks", 2);
        s.mats.put("barrel", -3);
        s.crew.add("x");
        Fold.Out o = new Fold.Out();
        o.m = new Kinds.M(5000, 5000, 10, 10, 10, 160, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        JsonObject j = KindDesk.shown(s, o, KI);
        t(j.getAsJsonObject("materials_net").size() == 25 && !j.getAsJsonObject("materials_net").has("lectern"), "the top 25 are fillers");
        t(j.getAsJsonObject("notable_blocks").toString().equals("{\"lectern\":17,\"melon_stem\":9}"), "notable_blocks: telling keys past the top 25, positive only: " + j.get("notable_blocks"));
        KindDesk.Card c = KindDesk.card(1, j, KI);
        t(c.blocks().get("lectern") == 17 && c.crops() == 9 && KindDesk.fits("melon_farm", c), "the vetoes read notable_blocks");
        t(KindDesk.RULES.contains("\"notable_blocks\""), "the prompt explains notable_blocks");
        for (String bad : List.of("filler", "oak_planks")) t(!j.get("notable_blocks").toString().contains(bad), "plain blocks stay out of notable_blocks");
    }

    // ------------------------------------------------------------ the kinds list
    static void kinds() {
        t(Kinds.ALL.size() == 59 && Set.copyOf(Kinds.ALL).size() == 59, "59 kinds, no repeats");
        for (String old : List.of("house", "base", "storage", "tower", "farm", "auto_farm", "slime_farm", "mob_farm", "iron_farm", "trading_hall", "villager_breeder",
                "animal_pen", "bee_farm", "garden", "redstone", "bridge", "road", "wall", "item_collector", "decoration"))
            t(Kinds.ALL.contains(old), "an alpha.20 kind stays valid: " + old);
        for (String k : Kinds.ALL) {
            String d = Kinds.display(k);
            t(!d.contains("_") && d.matches("[a-z ]+") && KindDesk.RULES.contains("\n- " + k + ": "), "words and a definition for " + k + " -> " + d);
            String p = Kinds.PARENT.get(k);
            t(p == null || Kinds.ALL.contains(p) && !Kinds.PARENT.containsKey(p), "a parent is a general kind: " + k + " -> " + p);
        }
        for (String k : Kinds.ALL) if (k.endsWith("_farm") && !k.equals("auto_farm") && !k.equals("mob_farm")) t(Kinds.PARENT.containsKey(k), "every specific farm has a parent: " + k);
        t(Kinds.display("redstone").equals("redstone machine") && Kinds.display("sugar_cane_farm").equals("sugar cane farm"), "display words");
        t(Kinds.WORKS.contains("melon_farm") && Kinds.WORKS.contains("farm") && !Kinds.WORKS.contains("library") && Kinds.FLAT.contains("rail_line"), "works and flat kinds");
    }

    // ------------------------------------------------------------ construction.detail and singular words
    static void detail() {
        t(Kinds.shown("melon_farm", "standard").equals("melon_farm") && Kinds.shown("melon_farm", "basic").equals("auto_farm"), "melon farm: specific at standard");
        t(Kinds.shown("cactus_farm", "full").equals("cactus_farm") && Kinds.shown("cactus_farm", "standard").equals("auto_farm")
                && Kinds.shown("cactus_farm", "basic").equals("auto_farm"), "cactus farm hints at a biome: its parent at standard");
        t(Kinds.shown("nether_wart_farm", "standard").equals("farm") && Kinds.shown("spider_farm", "basic").equals("mob_farm")
                && Kinds.shown("library", "basic").equals("house") && Kinds.shown("house", "basic").equals("house"), "basic: the general kind");
        for (String k : Kinds.ALL) for (String d : List.of("basic", "standard", "full")) t(Kinds.ALL.contains(Kinds.shown(k, d)), "a real kind: " + k + " " + d);
        List<Features.F> all = Features.of(m("chest", 35, "beacon", 1, "enchanting_table", 1, "cactus", 9, "melon_stem", 9),
                Map.of(), Map.of("spider", 1, "pig", 2));
        t(all.toString().equals(list("spider_spawners", "1", "spawners", "2", "enchanting_tables", "1", "storage", "35", "beacons", "1", "melons", "9", "cactus", "9")),
                "all features: " + all);
        t(Features.shown(all, "full").equals(all), "full: everything");
        t(Features.shown(all, "basic").isEmpty(), "basic: nothing inside");
        t(Features.shown(all, "standard").toString().equals(list("enchanting_tables", "1", "melons", "9")), "standard: no loot or place hints: " + Features.shown(all, "standard"));
        t(Features.counted("spider_spawners", 1).equals("spider spawner") && Features.counted("enchanting_tables", 1).equals("enchanting table")
                && Features.counted("beacons", 1).equals("beacon") && Features.counted("beacons", 2).equals("beacons")
                && Features.counted("librarian_villagers", 1).equals("librarian villager"), "one of a feature reads singular");
    }
}
