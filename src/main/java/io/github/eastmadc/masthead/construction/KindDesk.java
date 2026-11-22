package io.github.eastmadc.masthead.construction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.eastmadc.masthead.writer.Writer;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The kinds desk: once per published paper, the paper's own writer names every printable build that has no builder
 * label and no fresh AI kind, in one call. It sees only a card of numbers and block keys per build, under a stand-in id
 * (never a name, a build name, sign text or a position). Answers outside the list, for unknown ids, or that the numbers
 * rule out are dropped; the fold then applies what is left (see Fold, kind). Any failure is a note: the rules decide.
 */
public final class KindDesk {
    private KindDesk() {}

    static final int MAX_CARDS = 30;

    /** One build as the AI sees it (shown, without its id), with the numbers the vetoes read. */
    record Card(int site, int net, int crew, int h, int crops, int hoppers, int parts, int chests, int hives, List<String> top5, String prod, JsonObject shown,
                Map<String, Integer> blocks, int plants) {}

    /** A checked answer for one build, and the card it was for (the fold applies it only to the same build, R9); list = the kinds list it was asked from. */
    record Ask(String kind, String confidence, int net, List<String> top5, String prod, int list) {
        Ask(String kind, String confidence, int net, List<String> top5, String prod) { this(kind, confidence, net, top5, prod, Kinds.LIST); }
    }

    static final String RULES = """
            You are the classification clerk for a Minecraft server newspaper. For each player build below, decide what it is.

            What the data is: counts of blocks players PLACED at each build (net of removals), the height in blocks, the footprint, \
            and a few counters. The data cannot see blocks that were dug out or that existed before (natural stone, caves, water), \
            living mobs, villagers or spawners. A build's tall visible part may sit beside its working part. \
            "pct_placed_blocks_in_slime_chunks" covers placed blocks only. \
            "notable_blocks" (only when there are any) adds the telling blocks that are not among the 25 materials shown: job blocks, \
            bookshelves, crops, glass, beds, rails, furnaces, chests and machine parts. \
            "mobs_died_here_7d" (only when there were any) counts mob and animal deaths AT this build or right next to it in the \
            last 7 days: it shows what it farms.

            Pick exactly one kind per build from this list (no other words, never "unknown"). Pick the most specific kind the blocks \
            and deaths show (a melon farm, not an auto farm, when melon stems are there; a spider farm when spiders die there); \
            a specific kind they don't show is replaced by its general kind:
            - house: one builder's home, even with workshops, furnaces and chests inside
            - base: a shared base built by 3 or more builders; one builder's home is a house
            - storage: a storage room or item sorter, mostly chests or barrels
            - tower: a tall, narrow build
            - library: a room of bookshelves (24 or more)
            - greenhouse: a glass building (30 or more glass blocks or panes) with crops or plants growing inside
            - enchanting_room: a room built around an enchanting table
            - brewing_room: a room built around brewing stands
            - smelter: a bank of 8 or more furnaces, mostly plain furnaces or fed by hoppers (many blast furnaces or smokers without hoppers are job blocks)
            - trading_hall: villagers in cells with their job blocks (3 or more of one kind: lecterns, blast furnaces, smokers, grindstones...), for trading
            - villager_breeder: beds and food where villagers breed
            - nether_portal: an obsidian portal frame (10 or more obsidian) and what is built around it
            - bridge: a walkway over air or water
            - road: a path on the ground
            - rail_line: a long minecart line (32 or more rails)
            - wall: a long wall or fence line
            - garden: flowers and plants grown for looks
            - decoration: a small decoration or landscaping
            - lighthouse: a tall tower with a light at the top
            - ship: a ship or boat built as a model
            - redstone: a redstone machine or flying machine that is not a farm
            - item_collector: hoppers and water that gather items for another build
            - farm: crops planted and harvested by hand, of several kinds or none clearly most
            - auto_farm: a crop farm that harvests itself (pistons, observers, water, hoppers), of several kinds or none clearly most
            - wheat_farm: a farm mostly of wheat
            - carrot_farm: a farm mostly of carrots
            - potato_farm: a farm mostly of potatoes
            - melon_farm: a farm mostly of melon stems
            - pumpkin_farm: a farm mostly of pumpkin stems
            - sugar_cane_farm: a farm mostly of sugar cane
            - cactus_farm: a farm mostly of cactus
            - bamboo_farm: a farm mostly of bamboo
            - kelp_farm: a farm mostly of kelp
            - tree_farm: saplings grown and cut for wood
            - mushroom_farm: a farm mostly of mushrooms
            - flower_farm: flowers grown in bulk for dye
            - berry_farm: a farm mostly of sweet berry bushes
            - nether_wart_farm: a farm mostly of nether wart
            - bonemeal_farm: composters fed by a farm (hoppers, observers or dispensers), making bone meal
            - slime_farm: a farm for slimes
            - mob_farm: a farm for hostile mobs (spawning floors, a drop or kill area) with no one mob clearly most
            - spider_farm: a farm for spiders or cave spiders
            - zombie_farm: a farm for zombies, husks or drowned
            - skeleton_farm: a farm for skeletons or strays
            - creeper_farm: a farm for creepers
            - witch_farm: a farm for witches
            - enderman_farm: a farm for endermen
            - guardian_farm: a farm for guardians
            - blaze_farm: a farm for blazes
            - gold_farm: a farm for zombified piglins, for gold
            - hoglin_farm: a farm for hoglins
            - raid_farm: a farm for raids (pillagers, vindicators, evokers, ravagers)
            - iron_farm: villagers and a zombie making iron golems
            - chicken_farm: chickens bred, and often cooked, by a machine
            - cow_farm: cows bred for beef and leather
            - wool_farm: sheep kept and sheared for wool
            - animal_pen: fenced farm animals
            - bee_farm: beehives or bee nests with flowers
            - fishing_farm: a spot built for fishing without moving

            Answer with JSON only, one object: {"A": {"kind": "...", "second": "...", "confidence": "high|medium|low"}, "B": ...}
            "second" is your next best kind from the same list.""";

    /** The printable, unlabelled builds whose AI kind is missing or due again (R8), today's news first, at most 30 (R10). */
    static List<Card> cards(SiteIndex idx, Prefs prefs, Fold.Result fr, Fold.Cfg cfg) {
        String graceEnd = cfg.date().minusDays(1).toString();
        List<SiteIndex.Site> due = new ArrayList<>();
        Set<Integer> news = new HashSet<>();
        for (Map.Entry<Integer, Fold.Out> e : fr.out.entrySet()) {
            SiteIndex.Site s = idx.sites.get(e.getKey());
            Fold.Out o = e.getValue();
            if (s == null || s.kindSet != null || s.retired || !Projector.printable(s, o, prefs, cfg.c(), graceEnd) || !due(s, o.m.n(), cfg.date()))
                continue;
            due.add(s);
            if (Projector.tag(s, o, prefs, cfg.c().minBlocks()) != null) news.add(s.id);
        }
        due.sort(Comparator.comparing((SiteIndex.Site s) -> !news.contains(s.id)).thenComparing(s -> String.valueOf(s.lastGrowth), Comparator.reverseOrder())
                .thenComparingInt(s -> s.id));
        List<Card> out = new ArrayList<>();
        for (SiteIndex.Site s : due.subList(0, Math.min(MAX_CARDS, due.size()))) out.add(card(s.id, shown(s, fr.out.get(s.id), cfg.keyInfo()), cfg.keyInfo()));
        return out;
    }

    /**
     * Never asked, grown or shrunk by over 25%, 2+ of the top 5 materials changed, its top production changed (or appeared),
     * or 14 days old (1 day if the answer was low).
     */
    static boolean due(SiteIndex.Site s, int n, LocalDate date) {
        if (s.aiKind == null || s.aiAsked == null || s.aiTop5 == null || s.aiList < Kinds.LIST) return true;
        if (Math.abs(n - s.aiNet) * 4L > s.aiNet || s.aiTop5.size() - overlap(top5(s.mats), s.aiTop5) >= 2) return true;
        // an alpha.20 draft saved "slime|slime_ball": only the death before the bar counts
        if (!prod(top8(s.mobsDied)).equals(s.aiProd == null ? "" : s.aiProd.replaceFirst("\\|.*", ""))) return true;
        return !LocalDate.parse(s.aiAsked).plusDays("low".equals(s.aiConf) ? 1 : 14).isAfter(date);
    }

    /**
     * A same-day re-run or preview folds from the .prev files, which predate today's answers: the answers saved today (in the
     * current index) count again, as the published fold applied them, so they print the same and are not asked again. Fresh
     * answers win. An answer from an older kinds list is left out of the practice fold, so the build is asked again; the real
     * fold keeps it under its own list number until a new answer comes.
     */
    static Map<Integer, Ask> today(SiteIndex now, String date, Map<Integer, Ask> fresh, boolean practice) {
        Map<Integer, Ask> out = new HashMap<>();
        for (SiteIndex.Site s : now.sites.values())
            if (s.aiKind != null && s.aiTop5 != null && date.equals(s.aiAsked) && (!practice || s.aiList >= Kinds.LIST))
                out.put(s.id, new Ask(s.aiKind, s.aiConf == null ? "low" : s.aiConf, s.aiNet, s.aiTop5, s.aiProd == null ? "" : s.aiProd, s.aiList));
        out.putAll(fresh);
        return out;
    }

    /** R9: the answer is for this build as it is now (net within 25%, 3 of its top 5 materials the same). */
    static boolean same(Ask a, int n, Map<String, Integer> mats) {
        return Math.abs(n - a.net()) * 4L <= a.net() && overlap(top5(mats), a.top5()) >= Math.min(3, a.top5().size());
    }

    /**
     * A mob kind whose own mob is the top death, at medium or high confidence: the deaths are the proof, so it does not wait
     * two papers. A low answer, and a kind no death proves (mob_farm, slime_farm, iron_farm), wait like any other change.
     */
    static boolean shownByDeaths(Ask a) {
        return !"low".equals(a.confidence()) && MOB_KIND.getOrDefault(a.kind(), Set.of()).contains(a.prod());
    }

    /** The card: numbers and block keys only. */
    static JsonObject shown(SiteIndex.Site s, Fold.Out o) { return shown(s, o, Map.of()); }

    /** keyInfo marks crops and plants for "notable_blocks" (the other notable keys are known by name). */
    static JsonObject shown(SiteIndex.Site s, Fold.Out o, Map<String, Integer> keyInfo) {
        Kinds.M m = o.m;
        JsonObject j = new JsonObject();
        j.addProperty("net_blocks", m.n());
        if (s.yMin <= s.yMax) j.addProperty("height_blocks", s.yMax - s.yMin + 1);   // a height, never a world Y
        j.add("footprint_blocks", pair(m.l(), m.w()));
        j.addProperty("occupied_4x4_columns", m.a() / 16);
        j.addProperty("dimension", o.seeded ? "overworld" : "nether or end");   // seeded = an Overworld (NORMAL) world, by its environment
        j.addProperty("builders", s.crew.size());
        j.addProperty("building_days", s.days);
        j.addProperty("pct_placed_blocks_in_slime_chunks", m.slimePct());
        j.addProperty("blocks_placed_over_air_or_fluid", s.air);
        j.addProperty("water_buckets_poured", s.wb);
        JsonObject mats = new JsonObject();
        for (Map.Entry<String, Integer> e : sorted(s.mats)) if (mats.size() < 25) mats.addProperty(e.getKey(), e.getValue());
        j.add("materials_net", mats);
        JsonObject notable = new JsonObject();   // what the vetoes read, from the full counts, even past the top 25
        for (Map.Entry<String, Integer> e : sorted(s.mats))
            if (!mats.has(e.getKey()) && notable(e.getKey(), keyInfo.getOrDefault(e.getKey(), 0))) notable.addProperty(e.getKey(), e.getValue());
        if (notable.size() > 0) j.add("notable_blocks", notable);
        JsonObject died = top8(s.mobsDied);   // types and counts only, never where
        if (died.size() > 0) j.add("mobs_died_here_7d", died);
        return j;
    }

    /** The 8 biggest types of a build's last 7 days of production. */
    static JsonObject top8(java.util.TreeMap<String, java.util.TreeMap<String, Integer>> days) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Integer> e : Production.top(days, 8)) o.addProperty(e.getKey(), e.getValue());
        return o;
    }

    /** The top death ("slime"), "" for none; under 16 is not production (a stray kill near a house). */
    static String prod(JsonObject died) {
        for (Map.Entry<String, JsonElement> e : died.entrySet()) return e.getValue().getAsInt() >= 16 ? e.getKey() : "";
        return "";
    }

    /** What the vetoes read, from the card itself, so they judge exactly what the AI saw. */
    static Card card(int site, JsonObject j, Map<String, Integer> keyInfo) {
        int crops = 0, plants = 0;
        List<String> top5 = new ArrayList<>();
        Map<String, Integer> blocks = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("materials_net").entrySet()) {
            if (top5.size() < 5) top5.add(e.getKey());
            blocks.put(e.getKey(), e.getValue().getAsInt());
        }
        if (j.get("notable_blocks") instanceof JsonObject nb) for (Map.Entry<String, JsonElement> e : nb.entrySet()) blocks.putIfAbsent(e.getKey(), e.getValue().getAsInt());
        for (Map.Entry<String, Integer> e : blocks.entrySet()) {
            int info = keyInfo.getOrDefault(e.getKey(), (int) KeyRules.BUILT), v = Math.max(0, e.getValue());
            if ((info & KeyRules.CROP) != 0) crops += v;
            else if (KeyRules.cls(info) == KeyRules.PLANT) plants += v;
        }
        int h = j.has("height_blocks") ? j.get("height_blocks").getAsInt() : 0;
        String prod = j.has("mobs_died_here_7d") ? prod(j.getAsJsonObject("mobs_died_here_7d")) : "";
        return new Card(site, j.get("net_blocks").getAsInt(), j.get("builders").getAsInt(), h, crops, Features.n(blocks, "hopper"),
                Features.n(blocks, "observer", "piston", "sticky_piston"), Features.n(blocks, "chest", "trapped_chest", "barrel"),
                Features.n(blocks, "beehive", "bee_nest"), List.copyOf(top5), prod, j, Map.copyOf(blocks), plants);
    }

    /** Keys the vetoes or the features read, besides crops, plants, beds, glass, rails, cauldrons and saplings. */
    private static final Set<String> NOTABLE = Set.of("lectern", "blast_furnace", "smoker", "furnace", "composter", "barrel", "cartography_table",
            "fletching_table", "grindstone", "loom", "smithing_table", "stonecutter", "brewing_stand", "bookshelf", "chiseled_bookshelf",
            "enchanting_table", "chest", "trapped_chest", "beacon", "bell", "obsidian", "hopper", "observer", "piston", "sticky_piston", "dispenser",
            "beehive", "bee_nest", "brown_mushroom", "red_mushroom", "kelp", "kelp_plant");

    static boolean notable(String k, int info) {
        return NOTABLE.contains(k) || (info & (KeyRules.CROP | KeyRules.BED | KeyRules.GLASS | KeyRules.RAIL)) != 0 || KeyRules.cls(info) == KeyRules.PLANT
                || k.endsWith("_bed") || k.contains("glass") || k.endsWith("rail") || k.endsWith("cauldron") || sapling(k);
    }

    /** Tree farm blocks. Only the survey counts them: the recorder ignores saplings. */
    private static boolean sapling(String k) {
        return k.endsWith("_sapling") && !k.equals("bamboo_sapling") || k.endsWith("_propagule") || k.endsWith("azalea");
    }

    /** One call for every card. Never throws: a failure is a note, and the rules decide today. */
    static Map<Integer, Ask> ask(Writer w, List<Card> cards, Duration timeout, List<String> notes) {
        if (cards.isEmpty()) return Map.of();
        String raw;
        try { raw = w.write(RULES, user(cards.stream().map(Card::shown).toList()), timeout); }
        catch (Throwable e) {
            notes.add("build kinds: AI unavailable (" + e.getClass().getSimpleName() + "); rules used");
            return Map.of();
        }
        Map<Integer, Ask> got = read(raw, cards);
        if (got.size() < cards.size()) notes.add("build kinds: AI named " + got.size() + " of " + cards.size() + " builds; rules used for the rest");
        return got;
    }

    /**
     * Checks the AI's JSON: only listed kinds, only the cards' ids. A vetoed kind gives way to "second", then to its
     * general kind (the general kind first for a mob farm where no one mob is clearly most), then to the rules.
     */
    static Map<Integer, Ask> read(String raw, List<Card> cards) {
        Map<Integer, Ask> out = new HashMap<>();
        int a = raw == null ? -1 : raw.indexOf('{'), b = raw == null ? -1 : raw.lastIndexOf('}');   // also drops a ```json fence
        if (a < 0 || b <= a) return out;
        JsonObject o;
        try { o = JsonParser.parseString(raw.substring(a, b + 1)).getAsJsonObject(); }
        catch (RuntimeException e) { return out; }
        for (int i = 0; i < cards.size(); i++) {
            if (!(o.get(id(i)) instanceof JsonObject x)) continue;
            Card c = cards.get(i);
            String first = str(x, "kind"), conf = str(x, "confidence"), second = str(x, "second"), parent = first == null ? null : Kinds.PARENT.get(first);
            // a vetoed mob kind whose own mob is the top death (no one mob clearly most) is still a mob farm: its general kind comes before "second"
            boolean topDeath = first != null && MOB_KIND.getOrDefault(first, Set.of()).contains(c.prod());
            for (String k : topDeath ? new String[] {first, parent, second} : new String[] {first, second, parent}) {
                if (k == null || !Kinds.ALL.contains(k) || !fits(k, c)) continue;
                out.put(c.site(), new Ask(k, k.equals(first) && ("high".equals(conf) || "medium".equals(conf)) ? conf : "low", c.net(), c.top5(), c.prod()));
                break;
            }
        }
        return out;
    }

    /** Crop kind -> the crop feature whose blocks prove it. */
    private static final Map<String, String> CROP_KIND = Map.ofEntries(Map.entry("wheat_farm", "wheat"), Map.entry("carrot_farm", "carrots"),
            Map.entry("potato_farm", "potatoes"), Map.entry("melon_farm", "melons"), Map.entry("pumpkin_farm", "pumpkins"),
            Map.entry("sugar_cane_farm", "sugar_cane"), Map.entry("cactus_farm", "cactus"), Map.entry("bamboo_farm", "bamboo"), Map.entry("kelp_farm", "kelp"),
            Map.entry("berry_farm", "sweet_berries"), Map.entry("nether_wart_farm", "nether_wart"), Map.entry("mushroom_farm", "mushrooms"));
    /** Mob kind -> the deaths that prove it (as the top death, 16+ in 7 days, and at least twice the next mob). */
    private static final Map<String, Set<String>> MOB_KIND = Map.ofEntries(Map.entry("spider_farm", Set.of("spider", "cave_spider")),
            Map.entry("zombie_farm", Set.of("zombie", "husk", "drowned", "zombie_villager")), Map.entry("skeleton_farm", Set.of("skeleton", "stray", "bogged")),
            Map.entry("creeper_farm", Set.of("creeper")), Map.entry("witch_farm", Set.of("witch")), Map.entry("enderman_farm", Set.of("enderman")),
            Map.entry("guardian_farm", Set.of("guardian")), Map.entry("blaze_farm", Set.of("blaze")), Map.entry("gold_farm", Set.of("zombified_piglin")), Map.entry("hoglin_farm", Set.of("hoglin")),
            Map.entry("raid_farm", Set.of("pillager", "vindicator", "evoker", "ravager")));

    /**
     * R14: what the numbers rule out, from the card's full block counts and its top death. Villagers and animals are
     * invisible to the data, so villager and animal kinds are never vetoed; nor is a kind with no rule below.
     */
    static boolean fits(String kind, Card c) {
        Map<String, Integer> b = c.blocks();
        if (CROP_KIND.containsKey(kind)) return Features.n(b, Features.CROPS.get(CROP_KIND.get(kind)).toArray(new String[0])) >= 8;   // a few rows of it
        if (MOB_KIND.containsKey(kind)) return MOB_KIND.get(kind).contains(c.prod()) && clearlyMost(MOB_KIND.get(kind), c.shown());
        return switch (kind) {
            case "base" -> c.crew() >= 3;
            case "farm" -> c.crops() >= 16;
            // R14 says hoppers with observers/pistons; machine parts with a few crops count too (a pumpkin farm drops by water)
            case "auto_farm" -> c.crops() >= 16 || c.parts() > 0 && (c.hoppers() > 0 || c.crops() > 0);
            case "storage" -> c.chests() >= 16;
            case "tower" -> c.h() >= 20;
            case "lighthouse" -> c.h() >= 12;
            case "bee_farm" -> c.hives() >= 2;
            case "tree_farm" -> sum(b, KindDesk::sapling) >= 8;                                         // a few rows of saplings
            case "flower_farm" -> c.plants() >= 16;                                                    // more than a flower bed
            case "bonemeal_farm" -> Features.n(b, "composter") >= 1 && c.hoppers() + c.parts() + Features.n(b, "dispenser") >= 2;   // fed by a machine
            case "library" -> Features.n(b, "bookshelf", "chiseled_bookshelf") >= 24;               // 15 is an enchanting setup
            case "enchanting_room" -> Features.n(b, "enchanting_table") >= 1;
            case "brewing_room" -> Features.n(b, "brewing_stand") >= 1;
            case "smelter" -> Features.smelter(b);                                                   // the same test as the furnaces feature
            case "greenhouse" -> sum(b, k -> k.contains("glass")) >= 30 && c.crops() + c.plants() >= 16;   // a glass room with rows of plants
            case "nether_portal" -> Features.n(b, "obsidian") >= 10;                                  // the smallest frame is 10
            case "rail_line" -> sum(b, k -> k.endsWith("rail")) >= 32;                                  // longer than a station's siding
            default -> true;
        };
    }

    /** "No one mob clearly most" is a mob farm: the kind's mobs must be twice the next mob, so a near tie cannot swap the kind from paper to paper. */
    private static boolean clearlyMost(Set<String> mobs, JsonObject shown) {
        long in = 0, other = 0;
        if (shown.get("mobs_died_here_7d") instanceof JsonObject d)
            for (Map.Entry<String, JsonElement> e : d.entrySet()) {
                if (mobs.contains(e.getKey())) in += e.getValue().getAsInt();
                else other = Math.max(other, e.getValue().getAsInt());
            }
        return in >= 2 * other;
    }

    private static int sum(Map<String, Integer> b, java.util.function.Predicate<String> key) {
        long t = 0;
        for (Map.Entry<String, Integer> e : b.entrySet()) if (key.test(e.getKey())) t += Math.max(0, e.getValue());
        return (int) Math.min(Integer.MAX_VALUE, t);
    }

    /** The exact prompt for these cards (system and user joined, as the exchange and command writers send it); for GoldenTool. */
    public static String prompt(List<JsonObject> shown) {
        return Writer.join(RULES, user(shown));
    }

    private static String user(List<JsonObject> shown) {
        JsonArray a = new JsonArray();
        for (int i = 0; i < shown.size(); i++) {
            JsonObject j = new JsonObject();
            j.addProperty("id", id(i));
            for (Map.Entry<String, JsonElement> e : shown.get(i).entrySet()) j.add(e.getKey(), e.getValue());
            a.add(j);
        }
        return "BUILDS:\n" + a;
    }

    /** Stand-in ids: A..Z, then AA..AD. */
    static String id(int i) {
        return i < 26 ? String.valueOf((char) ('A' + i)) : "A" + (char) ('A' + i - 26);
    }

    static List<String> top5(Map<String, Integer> mats) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sorted(mats)) if (out.size() < 5) out.add(e.getKey());
        return out;
    }

    /** Positive counts, biggest first, then by key. */
    private static List<Map.Entry<String, Integer>> sorted(Map<String, Integer> mats) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>();
        for (Map.Entry<String, Integer> e : mats.entrySet()) if (e.getValue() > 0) l.add(e);
        l.sort((x, y) -> x.getValue().equals(y.getValue()) ? x.getKey().compareTo(y.getKey()) : Integer.compare(y.getValue(), x.getValue()));
        return l;
    }

    private static int overlap(List<String> a, List<String> b) {
        int n = 0;
        for (String x : a) if (b.contains(x)) n++;
        return n;
    }

    private static JsonArray pair(int a, int b) {
        JsonArray p = new JsonArray();
        p.add(a);
        p.add(b);
        return p;
    }

    private static String str(JsonObject o, String k) {
        return o.get(k) instanceof JsonPrimitive p && p.isString() ? p.getAsString().strip().toLowerCase(Locale.ROOT) : null;
    }
}
