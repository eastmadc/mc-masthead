package io.github.eastmadc.masthead.construction;

import io.github.eastmadc.masthead.config.Settings;
import io.github.eastmadc.masthead.edition.ConstructionWords;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Construction news, the pure core (spec §13.1 and the pure parts of §13.2). Plain main(), no JUnit. Builds are written
 * through the real minute maps and raw-line writer, then folded and projected. Fixture blocks sit at x=-7341, y=97,
 * z=2963 so a leaked position is easy to grep. Players are synthetic.
 */
public final class ConstructionTest {
    static int ok = 0;

    static void t(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
        ok++;
    }

    static final int X = -7341, Y = 97, Z = 2963;
    static final String STEVE = "Steve_Builder", ALEX = "Alex_Miner", SAM = "Sam_Redstone";

    static final List<String> KEYS = List.of("oak_planks", "spruce_planks", "spruce_log", "oak_log", "stone_bricks", "stone_brick_stairs", "glass",
            "glass_pane", "oak_door", "red_bed", "crafting_table", "chest", "spruce_stairs", "dirt", "cobblestone", "torch", "stone", "wheat",
            "repeater", "piston", "observer", "hopper", "rail", "prismarine", "netherrack", "nether_bricks", "obsidian", "diamond_block",
            "slime_block", "melon", "melon_stem", "attached_melon_stem", "poplar_planks", "oak_fence", "hay_block", "snow_block", "carved_pumpkin",
            "iron_block", "tnt", "sugar_cane", "oak_sapling", "grass_block", "jungle_planks", "sea_pickle", "small_amethyst_bud", "red_mushroom",
            "bamboo", "bamboo_sapling", "comparator", "redstone_wire", "dispenser", "composter", "white_concrete", "cherry_planks", "end_stone_bricks");

    static Set<String> tags(String k) {
        Set<String> t = new HashSet<>();
        if (k.endsWith("_door")) t.add("doors");
        if (k.endsWith("_bed")) t.add("beds");
        if (k.endsWith("_stairs")) t.add("stairs");
        if (k.endsWith("_slab")) t.add("slabs");
        if (k.endsWith("_fence")) t.add("fences");
        if (k.endsWith("_log")) t.add("logs");
        if (k.endsWith("_sapling")) t.add("saplings");
        if (Set.of("wheat", "melon_stem", "pumpkin_stem").contains(k)) t.add("crops");
        if (Set.of("stone", "deepslate", "andesite").contains(k)) t.add("base_stone_overworld");
        if (k.equals("netherrack")) t.add("base_stone_nether");
        if (Set.of("grass_block", "dirt").contains(k)) t.add("dirt");
        if (k.equals("rail")) t.add("rails");
        return t;
    }

    static final Map<String, Integer> KEY_INFO = new HashMap<>();
    static final Map<String, Integer> ORD = new HashMap<>();
    static final String[] BY_ORD = KEYS.toArray(new String[0]);
    static {
        for (int i = 0; i < KEYS.size(); i++) { KEY_INFO.put(KEYS.get(i), KeyRules.info(KEYS.get(i), tags(KEYS.get(i)))); ORD.put(KEYS.get(i), i); }
    }

    /** A synthetic server: build through the real recorder path, fold, project. */
    static final class Sc {
        static final UUID WORLD = UUID.fromString("c0ffee00-0000-0000-0000-000000000001");
        final MinuteMaps mm = new MinuteMaps();
        final WorldSlots ws = new WorldSlots();
        final int w = ws.assign(WORLD, false, false, false);
        final StringBuilder raw = new StringBuilder(), allRaw = new StringBuilder();
        final List<String> reqs = new ArrayList<>();
        SiteIndex idx = new SiteIndex();
        Prefs prefs = new Prefs();
        { prefs.opened = "2026-10-01"; }   // the daily papers; OpeningTest covers the opening paper
        List<Survey.Build> survey = List.of();
        final List<String> surveyNotes = new ArrayList<>();
        Settings.Construction c = new Settings.Construction("Planning & Zoning", true, "public", true, 3, 64, "blotter", 100, 15, 30, List.of(), 20000, List.of());
        Set<String> hidden = Set.of();
        LocalDate day = LocalDate.of(2026, 11, 2);   // a Monday
        Fold.Result fr;
        Projector.Result res;
        final List<String> outputs = new ArrayList<>();

        UUID id(String n) { return UUID.nameUUIDFromBytes(n.getBytes()); }

        Sc notice(String... names) {
            for (String n : names) { Prefs.P p = prefs.get(id(n).toString()); p.name = n; p.noticed = "2026-10-01"; }
            return this;
        }

        byte cls(String key) {
            byte k = KeyRules.cls(KEY_INFO.get(key));
            return k == KeyRules.GROWN ? KeyRules.grownAs(key) : k;
        }

        MinuteMaps.Acc place(String n, String key, int x, int y, int z) {
            byte k = cls(key);
            if (k == KeyRules.IGNORE || k == KeyRules.EARTH) return null;
            MinuteMaps.Acc a = mm.place(id(n), n, w, x, y, z, k, ORD.get(KeyRules.keyAs(key)));
            if (a != null && k == KeyRules.BUILT && KeyRules.groundBreak(key, KEY_INFO.get(key))) a.groundPlaced(WorldSlots.sc(x, y, z), ORD.get(KeyRules.keyAs(key)));   // as Collector does
            return a;
        }

        void brk(String n, String key, int x, int y, int z) {
            byte k = KeyRules.cls(KEY_INFO.get(key));
            if (k == KeyRules.IGNORE || k == KeyRules.EARTH || k == KeyRules.GROWN) return;
            if (KeyRules.groundBreak(key, KEY_INFO.get(key))) mm.breakGround(id(n), n, w, x, y, z, ORD.get(KeyRules.keyAs(key)));   // as Collector does
            else mm.breakBlock(id(n), n, w, x, y, z, k, ORD.get(KeyRules.keyAs(key)));
        }

        void lose(String cause, String by, String key, int x, int y, int z) {
            mm.lose(cause, by == null ? null : id(by), w, x, y, z, cls(key), ORD.get(key));
        }

        /** Places a solid box, corners included. Returns how many. */
        int box(String n, String key, int x0, int y0, int z0, int x1, int y1, int z1) {
            int c = 0;
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) { place(n, key, x, y, z); c++; }
            flush();
            return c;
        }

        int boxBreak(String n, String key, int x0, int y0, int z0, int x1, int y1, int z1, int max) {
            int c = 0;
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
                if (c >= max) break;
                brk(n, key, x, y, z);
                c++;
            }
            flush();
            return c;
        }

        void flush() {
            String s = RawLine.write(mm.swap(ws.snapshot()), BY_ORD);
            raw.append(s);
            allRaw.append(s);
        }

        Map<String, Long> seeds = Map.of();
        Map<Integer, KindDesk.Ask> ai = Map.of();   // the kinds desk's answers for the next fold

        void req(String n, String op, String v) { reqs.add(RawLine.request(id(n), op, null, null, v, null)); }

        void reqAt(String n, String op, int x, int y, int z) {
            reqs.add(RawLine.request(id(n), op, WORLD, SiteIndex.cubeKey(x >> 2, y >> 2, z >> 2), null, null));
        }

        Projector.Result fold() {
            flush();
            List<RawLine.Line> lines = new ArrayList<>();
            for (String l : raw.toString().split("\n")) { RawLine.Line p = RawLine.parse(l); if (p != null) lines.add(p); }
            List<RawLine.Request> rq = new ArrayList<>();
            for (String r : reqs) rq.add(RawLine.parseRequest(r));
            Fold.Cfg cfg = new Fold.Cfg(c, hidden, day, KEY_INFO, seeds, ai);
            fr = Fold.run(idx, prefs, lines, rq, cfg);
            surveyNotes.clear();
            res = Projector.project(idx, prefs, fr, cfg, day.getDayOfWeek() == java.time.DayOfWeek.SUNDAY, c.print(), survey,
                    java.util.Map.of(WORLD.toString(), "overworld"), surveyNotes);
            outputs.add(res.out().toString());
            outputs.add(res.alerts().toString());
            raw.setLength(0);
            reqs.clear();
            day = day.plusDays(1);
            return res;
        }

        JsonArray entries() { return res.out().getAsJsonObject("construction").getAsJsonArray("entries"); }

        JsonObject entry(int i) { return entries().get(i).getAsJsonObject(); }

        List<String> alerts() { return strings(res.alerts().getAsJsonArray("alerts")); }

        List<String> names() { return strings(res.alerts().getAsJsonArray("names")); }

        JsonArray blotter() { return res.out().getAsJsonArray("blotter"); }

        JsonArray awards() { return res.out().getAsJsonArray("award_reasons"); }
    }

    static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (JsonElement e : a) out.add(e.getAsString());
        return out;
    }

    /** A 5x5 dirt hut, 4 high, with a door and a bed: about 80 blocks. */
    static void hut(Sc s, String n, int x, int y, int z, String wall) {
        for (int dx = 0; dx < 5; dx++) for (int dz = 0; dz < 5; dz++) {
            s.place(n, wall, x + dx, y, z + dz);
            s.place(n, wall, x + dx, y + 3, z + dz);
            boolean edge = dx == 0 || dx == 4 || dz == 0 || dz == 4;
            if (edge && !(dx == 2 && dz == 0)) { s.place(n, wall, x + dx, y + 1, z + dz); s.place(n, wall, x + dx, y + 2, z + dz); }
        }
        s.place(n, "oak_door", x + 2, y + 1, z);
        s.place(n, "red_bed", x + 2, y + 1, z + 2);
        s.flush();
    }

    /** An 11x9 plank house with glass, a door, a bed, a crafting table and a stair roof edge. */
    static void house(Sc s, String n, int x, int y, int z) {
        for (int dx = 0; dx < 11; dx++) for (int dz = 0; dz < 9; dz++) {
            s.place(n, "oak_planks", x + dx, y, z + dz);
            boolean edge = dx == 0 || dx == 10 || dz == 0 || dz == 8;
            if (edge) for (int dy = 1; dy <= 3; dy++) s.place(n, dy == 2 && dx % 3 == 1 ? "glass" : "oak_planks", x + dx, y + dy, z + dz);
            s.place(n, edge ? "spruce_stairs" : "oak_planks", x + dx, y + 4, z + dz);
        }
        s.place(n, "oak_door", x + 5, y + 1, z);
        s.place(n, "red_bed", x + 3, y + 1, z + 3);
        s.place(n, "crafting_table", x + 6, y + 1, z + 4);
        s.flush();
    }

    public static void main(String[] args) throws Exception {
        pureParts();
        kindsAndShapes();
        crewAndSelection();
        namesAndRibbon();
        demolition();
        privacy();
        existingAndSummon();
        reviewFixes();
        leakChecks();
        round3();
        reviewRound1();
        slimeFarms();
        System.out.println("ConstructionTest: " + ok + " checks passed");
    }

    // ------------------------------------------------------------ small pure pieces
    static void pureParts() throws Exception {
        // T-WORLD: 63 slots, then ignored
        WorldSlots ws = new WorldSlots();
        Set<Long> keys = new HashSet<>();
        for (int i = 0; i < 70; i++) {
            UUID u = new UUID(7, i);
            int s = ws.assign(u, false, false, false);
            t(i < 63 ? s == i : s == -1, "slot " + i);
            if (s >= 0) keys.add(WorldSlots.sectionKey(s, -459, 6, 185));
        }
        t(ws.overflow == 7 && keys.size() == 63, "T-WORLD: 63 distinct section keys, 7 over the cap");
        long k = WorldSlots.sectionKey(62, -459, -4, 185);
        t(WorldSlots.slot(k) == 62 && WorldSlots.cx(k) == -459 && WorldSlots.sy(k) == -4 && WorldSlots.cz(k) == 185, "section key round trip");
        long ck = SiteIndex.cubeKey(-1836, -16, 740);
        t(SiteIndex.sx(ck) == -1836 && SiteIndex.sy(ck) == -16 && SiteIndex.sz(ck) == 740, "cube key round trip");
        for (String f : List.of("WorldSlots", "MinuteMaps"))   // no server types in the hot-path data structures
            t(!Files.readString(Path.of("src/main/java/io/github/eastmadc/masthead/construction/" + f + ".java")).matches("(?s).*(import org\\.bukkit|\\bWorld\\b|\\bMaterial\\b|\\bBlock\\b|\\bPlayer\\b).*"),
                    "T-WORLD: no Bukkit type in " + f);
        // T-SWAP
        MinuteMaps mm = new MinuteMaps();
        UUID p = new UUID(1, 1);
        mm.place(p, "P", 0, X, Y, Z, KeyRules.BUILT, 0);
        MinuteMaps.Minute m1 = mm.swap(new UUID[63]);
        mm.place(p, "P", 0, X, Y, Z, KeyRules.BUILT, 0);
        MinuteMaps.Acc old = m1.current().values().iterator().next()[0];
        t(old.placed == 1, "T-SWAP: the handed-off minute is unchanged");
        t(mm.swap(new UUID[63]).current().values().iterator().next()[0].placed == 1, "T-SWAP: the new count landed in the new map");
        // the cell cap
        mm.maxCells = 2;
        for (int i = 0; i < 5; i++) mm.place(new UUID(2, i), "P", 0, X, Y, Z, KeyRules.BUILT, 0);
        t(mm.overflow == 3 && mm.cells == 2, "cell cap: overflow counted");
        // EditionWindow
        ZoneId zd = ZoneId.of("+02:00");
        t(EditionWindow.dateFor(Instant.parse("2026-11-05T04:00:00Z"), LocalTime.of(5, 30), zd).equals(LocalDate.of(2026, 11, 6)), "after the edition time: tomorrow's paper");
        t(EditionWindow.dateFor(Instant.parse("2026-11-05T03:00:00Z"), LocalTime.of(5, 30), zd).equals(LocalDate.of(2026, 11, 5)), "before: today's paper");
        // beds: on Paper 26.x isBedWorks() is false in the Overworld by day; Overworld beds must still count
        t(!Collector.bedsExplode(false, org.bukkit.World.Environment.NORMAL) && !Collector.bedsExplode(true, org.bukkit.World.Environment.NORMAL),
                "an Overworld bed counts by day and by night");
        t(Collector.bedsExplode(false, org.bukkit.World.Environment.NETHER) && Collector.bedsExplode(false, org.bukkit.World.Environment.THE_END),
                "a Nether or End bed is never counted");
        // a hoe, shovel or axe only changes a block's form: only air, water, short grass and the like make a placement new
        t(KeyRules.replaceable("air", Set.of()) && KeyRules.replaceable("water", Set.of()) && KeyRules.replaceable("snow", Set.of())
                && KeyRules.replaceable("warped_roots", Set.of("replaceable")), "air, water, a snow layer and tagged plants are replaceable");
        t(!KeyRules.replaceable("grass_block", Set.of("dirt")) && !KeyRules.replaceable("oak_log", Set.of("logs")) && !KeyRules.replaceable("copper_block", Set.of()),
                "grass, logs and copper are not: tilling, stripping and waxing are not new blocks");
        t(KeyRules.TAGS.contains("replaceable"), "the replaceable block tag is read");
        // KeyRules
        t(KeyRules.cls(KEY_INFO.get("tnt")) == KeyRules.IGNORE && KeyRules.cls(KEY_INFO.get("torch")) == KeyRules.IGNORE
                && KeyRules.cls(KeyRules.info("redstone_torch", Set.of())) == KeyRules.BUILT, "tnt and torches ignored, redstone torch built");
        t(KeyRules.cls(KEY_INFO.get("melon")) == KeyRules.GROWN && KeyRules.grownAs("melon") == KeyRules.BUILT && KeyRules.grownAs("sugar_cane") == KeyRules.PLANT, "grown blocks");
        t(KeyRules.cls(KEY_INFO.get("poplar_planks")) == KeyRules.BUILT, "F-18: a wood newer than the API is BUILT");
        t(KeyRules.cls(KEY_INFO.get("stone")) == KeyRules.EARTH && (KEY_INFO.get("dirt") & KeyRules.UTILITY) != 0, "stone is earth, placed dirt is utility");
        t(KeyRules.keyAs("attached_melon_stem").equals("melon_stem") && KeyRules.keyAs("bamboo").equals("bamboo_sapling"), "key aliases");
        t(KeyRules.twoPart("red_bed", Set.of("beds")) && KeyRules.twoPart("pitcher_crop", Set.of()) && !KeyRules.twoPart("oak_stairs", Set.of("stairs")), "two-part blocks");
        // RawLine: T-RAW
        Sc s = new Sc();
        s.notice(STEVE);
        s.box(STEVE, "stone_bricks", X, Y, Z, X + 5, Y + 3, Z + 5);
        s.lose("creeper", null, "stone_bricks", X, Y, Z);
        s.flush();
        String raw = s.raw.toString();
        t(!raw.contains("\"t\"") && !raw.contains("7341") && !raw.contains("2963") && !raw.contains("\"x\":") && !raw.contains("\"y\":") && !raw.contains("\"z\":"),
                "T-RAW: no time stamps and no block position in raw lines");
        for (String l : raw.split("\n")) {
            RawLine.Line pl = RawLine.parse(l);
            t(pl != null, "every line parses");
            for (int i = 0; i < 192; i++) if (pl.d()[i] != 0) t(i % 64 < 64, "sub-cube 0-63");
        }
        t(RawLine.parse(raw.substring(0, 30)) == null && RawLine.parse("   ") == null, "a partial or blank line is skipped");
        // index round trip
        s.fold();
        String a = s.idx.write();
        t(SiteIndex.read(a).write().equals(a) && SiteIndex.version(a) == 1, "sites.json round trip");
        t(Prefs.read(s.prefs.write()).write().equals(s.prefs.write()), "players.json round trip");
        boolean threw = false;
        try { SiteIndex.read(a.replace("\"version\":1", "\"version\":2")); } catch (java.io.IOException e) { threw = true; }
        t(threw && SiteIndex.version(a.replace("\"version\":1", "\"version\":2")) == 2, "F-37: an unknown version is refused, never rewritten");
        // F-15: the same fold from the same state gives the same paper
        Sc a1 = new Sc(), a2 = new Sc();
        for (Sc x : List.of(a1, a2)) { x.notice(STEVE, ALEX); house(x, STEVE, X, Y, Z); hut(x, ALEX, X + 40, Y, Z, "dirt"); }
        t(a1.fold().out().toString().equals(a2.fold().out().toString()) && a1.idx.write().equals(a2.idx.write()), "F-15: same input, same out and index");
    }

    // ------------------------------------------------------------ kinds, sizes, false positives
    static void kindsAndShapes() throws Exception {
        Sc s = new Sc().notice(STEVE);
        s.box(STEVE, "spruce_planks", X, Y, Z, X + 4, Y + 39, Z + 4);
        s.fold();
        JsonObject e = s.entry(0);
        t(e.get("kind").getAsString().equals("tower") && e.get("height").getAsString().equals("very_tall") && !e.has("size"), "F-01: tower, very tall, no size on day 1");

        s = new Sc().notice(STEVE);
        for (int dx = 0; dx < 32; dx++) for (int dz = 0; dz < 3; dz++) { MinuteMaps.Acc a = s.place(STEVE, "oak_planks", X + dx, Y, Z + dz); if (dx % 5 < 3) a.overAir++; }
        s.fold();
        t(s.entry(0).get("kind").getAsString().equals("bridge") && !s.entry(0).has("height"), "F-02: bridge, no height");

        s = new Sc().notice(STEVE);
        house(s, STEVE, X, Y, Z);
        s.fold();
        t(s.entry(0).get("kind").getAsString().equals("house") && s.entry(0).get("confidence").getAsString().equals("medium"), "F-03: house, medium on day 1");
        s.place(STEVE, "oak_planks", X + 5, Y + 5, Z + 4);
        s.fold();
        Fold.Out o = s.fr.out.firstEntry().getValue();
        t(o.confidence.equals("high"), "F-03: high on day 2 (" + o.confidence + ")");

        s = new Sc().notice(ALEX);
        hut(s, ALEX, X, Y, Z, "dirt");
        s.fold();
        e = s.entry(0);
        t(e.get("tag").getAsString().equals("new_resident") && !e.has("size") && e.get("day").getAsInt() == 1, "F-04: dirt hut is a new resident, no size on day 1");
        t(s.awards().toString().contains("built a first home") && !s.res.out().toString().contains("small"), "F-04: first home award, no 'small'");

        // F-05 strip mine, F-06 trees, F-07 terraforming
        s = new Sc().notice(STEVE);
        for (int i = 0; i < 150; i++) { s.brk(STEVE, "stone", X + i, Y, Z); s.brk(STEVE, "stone", X + i, Y + 1, Z); s.place(STEVE, "torch", X + i, Y, Z); s.place(STEVE, "cobblestone", X + i, Y + 1, Z); s.place(STEVE, "cobblestone", X + i, Y, Z); }
        for (int i = 0; i < 50; i++) { s.brk(STEVE, "oak_log", X + 3 * i, Y + 10, Z + 20); s.place(STEVE, "oak_sapling", X + 3 * i, Y + 9, Z + 20); }
        for (int i = 0; i < 200; i++) s.brk(STEVE, "grass_block", X + i % 20, Y + 30, Z + i / 20);
        s.fold();
        t(s.entries().size() == 0 && s.fr.alerts.isEmpty() && s.blotter().size() == 0, "F-05/06/07: mining, trees and terraforming are not news");

        s = new Sc().notice(STEVE);
        s.box(STEVE, "cobblestone", X, Y, Z, X, Y + 63, Z);
        s.fold();
        t(s.entries().size() == 0 && "utility".equals(s.fr.out.firstEntry().getValue().suppressed), "F-08: a cobble pillar is suppressed");

        // F-09 wheat farm, harvested and replanted
        s = new Sc().notice(STEVE);
        for (int dx = 0; dx < 9; dx++) for (int dz = 0; dz < 9; dz++) if (dx != 4 || dz != 4) s.place(STEVE, "wheat", X + dx, Y, Z + dz);
        s.mm.acc(s.id(STEVE), STEVE, WorldSlots.sectionKey(s.w, X >> 4, Y >> 4, Z >> 4)).water++;
        s.fold();
        t(s.entries().size() == 1 && s.entry(0).get("kind").getAsString().equals("farm"), "F-09: one farm " + s.res.out() + s.fr.out.firstEntry().getValue().m);
        for (int d = 0; d < 4; d++) {
            for (int dx = 0; dx < 9; dx++) for (int dz = 0; dz < 9; dz++) if (dx != 4 || dz != 4) s.brk(STEVE, "wheat", X + dx, Y, Z + dz);
            s.flush();
            for (int dx = 0; dx < 9; dx++) for (int dz = 0; dz < 9; dz++) if (dx != 4 || dz != 4) s.place(STEVE, "wheat", X + dx, Y, Z + dz);
            s.fold();
            t(s.entries().size() == 0, "F-09: no entry from a harvest, day " + d);
        }

        // F-10 redstone by a first-time builder
        s = new Sc().notice(SAM);
        s.box(SAM, "stone_bricks", X, Y, Z, X + 9, Y, Z + 3);
        for (int i = 0; i < 30; i++) s.place(SAM, "repeater", X + i % 10, Y + 1, Z + i / 10);
        for (int i = 0; i < 10; i++) s.place(SAM, "piston", X + i, Y + 2, Z);
        for (int i = 0; i < 8; i++) s.place(SAM, "observer", X + i, Y + 2, Z + 1);
        for (int i = 0; i < 12; i++) s.place(SAM, "hopper", X + i % 10, Y + 2, Z + 2 + i / 10);
        s.fold();
        e = s.entry(0);
        t(e.get("kind").getAsString().equals("redstone") && e.get("tag").getAsString().equals("public_works"), "F-10: redstone, public works (" + e + ")");
        t(!s.awards().toString().contains("first home") && !e.getAsJsonObject("parts").toString().contains("hopper"), "F-10: never a first home; no hoppers in parts");

        // F-10b(1): a 3-villager iron farm with no door
        s = new Sc().notice(STEVE);
        s.box(STEVE, "glass", X, Y, Z, X + 6, Y, Z + 6);
        s.box(STEVE, "glass", X, Y + 1, Z, X + 6, Y + 2, Z);
        for (int i = 0; i < 3; i++) s.place(STEVE, "red_bed", X + 1 + 2 * i, Y + 1, Z + 3);
        s.place(STEVE, "hopper", X + 3, Y + 1, Z + 5); s.place(STEVE, "hopper", X + 4, Y + 1, Z + 5);
        s.place(STEVE, "chest", X + 3, Y + 1, Z + 6); s.place(STEVE, "chest", X + 4, Y + 1, Z + 6);
        s.fold();
        t(!s.entry(0).get("kind").getAsString().equals("house") && !s.entry(0).get("tag").getAsString().equals("new_resident"), "F-10b: an iron farm is never a first home");

        // F-18 / L-04 / L-05 / L-06 / L-07: what may be named
        s = new Sc().notice(STEVE);
        s.box(STEVE, "poplar_planks", X, Y, Z, X + 7, Y + 1, Z + 7);
        s.fold();
        t(s.entry(0).getAsJsonArray("materials").size() == 0, "F-18: an unknown wood is never named");
        s = new Sc().notice(STEVE);
        s.box(STEVE, "nether_bricks", X, Y, Z, X + 7, Y + 1, Z + 7);
        s.box(STEVE, "obsidian", X, Y + 2, Z, X + 4, Y + 3, Z + 3);
        s.fold();
        // alpha.21: a portal frame may show as the closed "nether_portal" feature (the owner's list), never as a material
        t(s.entry(0).getAsJsonArray("materials").size() == 0 && !s.res.out().toString().replace("\"nether_portal\"", "").contains("nether")
                && !s.res.out().toString().contains("obsidian") && s.entry(0).getAsJsonArray("features").toString().equals("[{\"feature\":\"nether_portal\",\"n\":40}]"),
                "L-04/L-05: no dimension or portal material named");
        s = new Sc().notice(STEVE);
        s.box(STEVE, "chest", X, Y, Z, X + 4, Y, Z + 7);
        s.box(STEVE, "diamond_block", X, Y + 1, Z, X + 3, Y + 1, Z + 2);
        s.box(STEVE, "stone_bricks", X + 5, Y, Z, X + 14, Y + 2, Z + 9);
        s.fold();
        t(s.entry(0).getAsJsonArray("materials").toString().equals("[{\"what\":\"stone brick\",\"blocks\":300}]"), "L-06: storage and valuables never named");
        s = new Sc().notice(STEVE);
        s.box(STEVE, "prismarine", X, Y, Z, X + 9, Y + 3, Z + 9);
        s.box(STEVE, "oak_planks", X, Y + 4, Z, X + 9, Y + 4, Z + 9);
        s.fold();
        t(s.entry(0).getAsJsonArray("materials").toString().equals("[{\"what\":\"oak\",\"blocks\":100}]"), "L-07: prismarine hidden, oak named");
        // L-03: rails make a road, no height
        s = new Sc().notice(STEVE);
        for (int i = 0; i < 100; i++) { s.place(STEVE, "rail", X + i, Y + 1, Z); s.place(STEVE, "stone_brick_stairs", X + i, Y, Z); }
        s.fold();
        t(s.entry(0).get("kind").getAsString().equals("road") && !s.entry(0).has("height"), "L-03: road, no height");

        // F-29: a flying-machine launch pad never piles up
        s = new Sc().notice(STEVE);
        for (int r = 0; r < 20; r++) {
            for (int i = 0; i < 30; i++) s.place(STEVE, i % 2 == 0 ? "slime_block" : "observer", X + i % 8, Y + (i / 8) % 2, Z);
            s.flush();
        }
        s.fold();
        boolean capped = true;
        for (SiteIndex.Cube cb : s.idx.cubes.get(0).values()) capped &= cb.n() <= 64;
        t(capped && s.entries().size() == 0 && s.idx.sites.values().stream().allMatch(x -> x.milestones.isEmpty()), "F-29: cubes cap at 64, no entry, no milestone");

        // F-22: a printed house's kind waits two folds before changing
        s = new Sc().notice(STEVE);
        house(s, STEVE, X, Y, Z);
        s.fold();
        s.box(STEVE, "oak_planks", X + 11, Y, Z, X + 11, Y + 60, Z);
        s.fold();
        t(s.idx.sites.firstEntry().getValue().kind.equals("house"), "F-22: still a house after one tall day");

        // a build printed in the last paper that turns out to be a farm keeps its milestone story
        s = new Sc().notice(STEVE);
        s.box(STEVE, "stone_bricks", X, Y, Z, X + 19, Y + 9, Z + 9);
        s.fold();
        s.box(STEVE, "stone_bricks", X, Y + 10, Z, X + 19, Y + 19, Z + 9);
        for (int i = 0; i < 30; i++) s.place(STEVE, "piston", X + i % 10, Y + 20, Z + i / 10);
        SiteIndex.Site farm = s.idx.sites.firstEntry().getValue();
        s.ai = Map.of(farm.id, new KindDesk.Ask("hoglin_farm", "high", 4030, KindDesk.top5(farm.mats), "hoglin"));
        s.fold();
        t(s.entries().size() == 1 && s.entry(0).get("tag").getAsString().equals("progress") && s.entry(0).get("kind").getAsString().equals("mob_farm"),   // detail standard prints a hoglin farm as a mob farm
                "a farm seen yesterday still prints its milestone: " + s.entries());

        // F-13 growth over 3 days, milestones and megaproject
        s = new Sc().notice(STEVE);
        s.box(STEVE, "stone_bricks", X, Y, Z, X + 19, Y + 9, Z + 9);
        s.fold();
        s.box(STEVE, "stone_bricks", X, Y + 10, Z, X + 19, Y + 19, Z + 9);
        java.util.Locale was = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.GERMANY);   // /news build on a German server: still "4,000 blocks", never "4.000 blocks"
        try { s.fold(); } finally { java.util.Locale.setDefault(was); }
        t(s.res.status().get(s.id(STEVE).toString()).get(0).contains(", 4,000 blocks"), "/news build numbers with a German default language: " + s.res.status());
        t(s.entry(0).get("tag").getAsString().equals("progress") && s.entry(0).get("milestone_blocks").getAsInt() == 2500, "F-13: day 2 passes 2,500");
        s.box(STEVE, "stone_bricks", X, Y + 20, Z, X + 19, Y + 25, Z + 9);
        s.fold();
        e = s.entry(0);
        t(e.get("milestone_blocks").getAsInt() == 5000 && e.get("megaproject").getAsBoolean() && e.get("size").getAsString().equals("huge"), "F-13: 5,000 and megaproject (" + e + ")");
        t(s.awards().toString().contains("passed 5000 blocks on one build"), "F-13: good award reason at 5,000");
        s.box(STEVE, "stone_bricks", X + 30, Y, Z, X + 31, Y, Z);
        s.fold();
        t(s.entries().size() == 0, "F-13: no second 5,000 milestone");
    }

    // ------------------------------------------------------------ crews, selection, the weekly tracker
    static void crewAndSelection() throws Exception {
        Sc s = new Sc().notice(STEVE, ALEX);
        house(s, STEVE, X, Y, Z);
        house(s, ALEX, X + 17, Y, Z);
        s.fold();
        t(s.entries().size() == 2 && s.idx.sites.size() == 2, "F-11: two houses 6 blocks apart are two builds");

        s = new Sc().notice(STEVE, ALEX);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
        s.box(ALEX, "oak_planks", X, Y + 6, Z, X + 9, Y + 9, Z + 9);
        s.fold();
        t(s.entry(0).getAsJsonArray("builders").toString().equals("[\"Alex_Miner\",\"Steve_Builder\"]") && !s.entry(0).toString().contains("share"),
                "F-12: both builders, alphabetical, no shares");

        // F-14: two builds joined by a wall merge under the lower id
        s = new Sc().notice(STEVE);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 7, Y + 1, Z + 7);
        s.box(STEVE, "stone_bricks", X + 20, Y, Z, X + 27, Y + 1, Z + 7);
        s.fold();
        t(s.idx.sites.size() == 2, "F-14: two builds first");
        s.box(STEVE, "oak_planks", X + 8, Y, Z, X + 19, Y + 1, Z);
        s.fold();
        long live = s.idx.sites.values().stream().filter(x -> x.aliasOf == null).count();
        SiteIndex.Site low = s.idx.sites.firstEntry().getValue();
        t(live == 1 && low.id == 1 && low.net == 128 + 128 + 24, "F-14: merged under the lower id, totals summed (" + low.net + ")");

        // F-20: 6 builds, 3 by one player -> at most 3 entries, that player once
        s = new Sc().notice(STEVE, ALEX, SAM, "Pat_Pine", "Kim_Kelp");
        String[] who = {STEVE, STEVE, STEVE, ALEX, SAM, "Pat_Pine"};
        for (int i = 0; i < 6; i++) s.box(who[i], "oak_planks", X + 30 * i, Y, Z, X + 30 * i + 7, Y + 1, Z + 7);
        s.fold();
        int steve = 0;
        for (JsonElement x : s.entries()) if (x.toString().contains(STEVE)) steve++;
        t(s.entries().size() == 3 && steve <= 1, "F-20: three entries, Steve in at most one");

        // a build with 7 crew is never picked: the facts check drops more than 6 builders, which emptied the section
        String[] seven = {STEVE, ALEX, SAM, "Pat_Pine", "Kim_Kelp", "Lou_Loam", "Dee_Dune"};
        s = new Sc().notice(seven).notice("Bo_Birch");
        for (int i = 0; i < 7; i++) s.box(seven[i], "oak_planks", X, Y + 2 * i, Z, X + 9, Y + 2 * i + 1, Z + 9);
        s.box("Bo_Birch", "oak_planks", X + 60, Y, Z, X + 67, Y + 1, Z + 7);
        s.fold();
        boolean big = s.idx.sites.values().stream().anyMatch(x -> x.crew.size() == 7), over6 = false, bo = false;
        for (JsonElement x : s.entries()) {
            over6 |= x.getAsJsonObject().getAsJsonArray("builders").size() > 6;
            bo |= x.toString().contains("Bo_Birch");
        }
        t(big && !over6 && bo, "a 7-crew build is skipped and the next build gets the story: " + s.entries());

        // F-26: a private build never takes a permit number
        s = new Sc().notice(STEVE, ALEX);
        s.prefs.get(s.id(ALEX).toString()).level = "private";
        s.box(ALEX, "oak_planks", X, Y, Z, X + 7, Y + 1, Z + 7);
        s.fold();
        s.box(STEVE, "oak_planks", X + 40, Y, Z, X + 47, Y + 1, Z + 7);
        s.fold();
        t(s.entries().size() == 1 && s.entry(0).get("permit").getAsInt() == 1, "F-26: permit numbers have no gap");

        // F-21: Sunday tracker, by permit, one critic
        s = new Sc().notice(STEVE, ALEX);
        s.day = LocalDate.of(2026, 11, 6);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 7, Y + 1, Z + 7);
        s.box(ALEX, "stone_bricks", X + 40, Y, Z, X + 47, Y + 1, Z + 7);
        s.fold();
        s.fold();
        s.box(STEVE, "oak_planks", X, Y + 2, Z, X + 7, Y + 2, Z + 7);
        s.box(ALEX, "stone_bricks", X + 40, Y + 2, Z, X + 47, Y + 2, Z + 7);
        s.fold();   // Sunday 2026-11-08
        JsonArray tr = s.res.out().getAsJsonObject("construction").getAsJsonArray("tracker");
        int critics = 0;
        for (JsonElement r : tr) if (r.getAsJsonObject().has("critic")) critics++;
        t(tr.size() == 2 && tr.get(0).getAsJsonObject().get("permit").getAsInt() < tr.get(1).getAsJsonObject().get("permit").getAsInt() && critics == 1,
                "F-21: tracker ordered by permit, one critic (" + tr + ")");
        for (JsonElement r : tr) {
            JsonObject row = r.getAsJsonObject();
            t(row.has("critic") == row.has("materials") && row.has("critic") == row.has("blocks_week"), "T-CRITIC: only the critic row has the extra fields");
        }
    }

    // ------------------------------------------------------------ names and ribbons
    static void namesAndRibbon() throws Exception {
        Sc s = new Sc().notice(STEVE, ALEX);
        house(s, STEVE, X, Y, Z);
        s.box(ALEX, "oak_planks", X + 11, Y, Z, X + 11, Y + 3, Z + 8);
        s.fold();
        s.req(STEVE, "name", "Spruce Hotel");
        s.req(ALEX, "name", "Noob Box");
        s.box(STEVE, "oak_planks", X, Y + 5, Z, X + 3, Y + 5, Z + 3);
        s.fold();
        t(s.idx.sites.firstEntry().getValue().name.equals("Spruce Hotel"), "F-24: a good name is stored");
        t(s.names().size() == 1 && s.names().get(0).contains("\"Spruce Hotel\", filed by Steve_Builder (Permit #1"), "F-24: the owner hears the name first: " + s.names());
        t(!s.res.out().toString().contains("Spruce Hotel"), "F-24: not printed in the paper that accepts it");
        Map<String, List<String>> st = s.res.status();
        t(st.get(s.id(STEVE).toString()).get(0).contains("(in the paper from the next one)"), "held name shown in /news build");
        s.req(STEVE, "done", null);
        s.fold();
        JsonObject e = s.entry(0);
        t(e.get("tag").getAsString().equals("ribbon") && e.get("name").getAsString().equals("Spruce Hotel"), "F-23/F-24: ribbon, name printed the next paper");
        t(s.awards().toString().contains("finished their") && s.awards().toString().contains("days of building"), "F-23: good award reason");
        t(s.res.status().get(s.id(ALEX).toString()) == null || s.res.status().get(s.id(ALEX).toString()).get(0).contains("Ribbon cutting filed by Steve_Builder"),
                "F-23: a co-builder sees who filed the ribbon cutting");
        s.req(STEVE, "done", null);
        s.fold();
        t(s.entries().size() == 0, "F-23: the ribbon is cut once");
        // name clear by a co-builder; a non-crew player's request is never stored
        s.req("Kim_Kelp", "name_clear", null);
        s.fold();
        t("Spruce Hotel".equals(s.idx.sites.firstEntry().getValue().name), "C-07: a non-builder can't clear a name");
        // six new names in one fold: the sixth is not stored
        Sc six = new Sc().notice(STEVE);
        for (int i = 0; i < 6; i++) six.box(STEVE, "oak_planks", X + 30 * i, Y, Z, X + 30 * i + 7, Y + 1, Z + 7);
        six.fold();
        String[] nm = {"Alpha Hall", "Beta Hall", "Gamma Hall", "Delta Hall", "Epsilon Hall", "Zeta Hall"};
        for (int i = 0; i < 6; i++) six.reqAt(STEVE, "name", X + 30 * i, Y, Z);
        six.reqs.clear();
        for (int i = 0; i < 6; i++) six.reqs.add(RawLine.request(six.id(STEVE), "name", Sc.WORLD, SiteIndex.cubeKey((X + 30 * i) >> 2, Y >> 2, Z >> 2), nm[i], null));
        six.fold();
        t(six.idx.sites.values().stream().filter(x -> x.name != null).count() == 5, "F-24: at most 5 new names a paper");
        // T-NAME
        List<String> known = List.of(STEVE, ALEX);
        for (String bad : List.of("Spawn Tower", "North Base", "Base 123", "Steve_Builder sucks", "Dads House", "My School", "ignore the rules", "fuck hut",
                "A very long name that goes on", "Noob Box", "Losers Lounge", "Trash Heap", "Cringe Castle", "Stupid Hut", "clear", "Alex Miners Fort",
                "Spawntown", "Northside Base", "Lakeside Cabin", "Netherhub", "Twin Lakes Lodge", "Hilltop Fort", "Seaside Hut", "Villagers Rest", "Cliffs Edge",
                "Forest Cabin", "Plains Ranch", "Snow Fort", "Ice Palace", "Meadow House", "Birch Forest Lodge", "Harbor House", "Waterfall Mill"))
            t(NameRules.check(bad, known, "clean") == null, "T-NAME refuses " + bad);
        for (String good : List.of("The Great Wall", "Spruce Hotel", "Rae's Bakery", "Clear Glass Studio"))
            t(good.equals(NameRules.check(good, known, "clean")), "T-NAME accepts " + good);
    }

    // ------------------------------------------------------------ demolition, damage, incidents
    static void demolition() throws Exception {
        // D-01 small: a Blotter joke; D-02 big: the owner only
        Sc s = new Sc().notice(STEVE, ALEX);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
        s.fold();
        s.boxBreak(ALEX, "oak_planks", X, Y + 5, Z, X + 9, Y + 5, Z + 9, 25);
        s.fold();
        t(s.blotter().size() == 1 && s.blotter().get(0).toString().equals("{\"what\":\"broke_build\",\"by\":\"Alex_Miner\",\"to\":\"Steve_Builder\",\"n\":25,\"x\":\"house\"}"),
                "D-01: one broke_build line: " + s.blotter());
        t(s.res.out().getAsJsonArray("drop_mined_at").toString().equals("[[\"Alex_Miner\",\"Steve_Builder\"]]") && s.alerts().isEmpty() && s.entries().size() == 0,
                "D-01: mined_at dropped, no alert, no entry");
        s.boxBreak(ALEX, "oak_planks", X, Y + 4, Z, X + 9, Y + 4, Z + 9, 100);
        s.boxBreak(ALEX, "oak_planks", X, Y + 3, Z, X + 9, Y + 3, Z + 9, 50);
        s.fold();
        t(s.blotter().size() == 0 && s.alerts().size() == 1 && s.alerts().get(0).startsWith("Building check: Alex_Miner broke about 150 blocks of a build Steve_Builder made. Alex_Miner is not listed as a builder there")
                && !s.alerts().get(0).contains("have a word"), "D-02/D-11: big damage goes to the owner only: " + s.alerts());
        t(s.awards().size() == 0, "T-AWD: damage is never an award reason");

        // D-04 creeper: an incident, never an award; D-05 own TNT on a solo build: self, the bad award
        s = new Sc().notice(STEVE);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
        s.fold();
        for (int i = 0; i < 41; i++) s.lose("creeper", null, "oak_planks", X + i % 10, Y + 5, Z + i / 10);
        s.fold();
        JsonObject e = s.entry(0);
        t(e.get("tag").getAsString().equals("incident") && e.getAsJsonObject("incident").toString().equals("{\"cause\":\"creeper\",\"blocks\":41,\"self\":false}")
                && s.awards().size() == 0, "D-04: creeper incident, no award: " + e);
        for (int i = 0; i < 30; i++) s.lose("tnt", STEVE, "oak_planks", X + i % 10, Y + 4, Z + i / 10);
        s.fold();
        t(s.entry(0).getAsJsonObject("incident").get("self").getAsBoolean() && s.awards().toString().contains("blew up part of their own")
                && s.awards().toString().contains(STEVE), "D-05: own TNT on a solo build");

        // D-06 / F-27: a teardown by its builder gets a notice one paper later, then a rebuild keeps the permit
        s = new Sc().notice(STEVE);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
        s.fold();
        s.boxBreak(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9, 500);
        s.fold();
        t(s.entries().size() == 0 && s.idx.sites.firstEntry().getValue().retired, "D-06: retired, nothing the same paper");
        s.fold();
        t(s.entries().size() == 1 && s.entry(0).get("tag").getAsString().equals("demolition") && s.entry(0).get("permit").getAsInt() == 1, "D-06: one DEMOLITION NOTICE the next paper");
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 7, Z + 9);
        s.fold();
        t(s.entries().size() == 1 && s.entry(0).get("tag").getAsString().equals("rebuild") && s.entry(0).get("permit").getAsInt() == 1, "F-27: the rebuild keeps Permit #1");

        // F-27 variant: the rebuild starts the very next fold -> no notice
        s = new Sc().notice(STEVE);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
        s.fold();
        s.boxBreak(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9, 500);
        s.fold();
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 7, Z + 9);
        s.fold();
        t(s.entries().size() == 1 && s.entry(0).get("tag").getAsString().equals("rebuild"), "F-27: no notice when the rebuild starts at once");

        // D-08 fire, D-16 wither: big unexplained damage goes to the owner and the build sits out
        s = new Sc().notice(STEVE);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 9, Z + 9);
        s.fold();
        for (int i = 0; i < 300; i++) s.lose("wither", null, "oak_planks", X + i % 10, Y + 9 - i / 100, Z + (i / 10) % 10);
        s.fold();
        t(s.entries().size() == 0 && s.alerts().size() == 1 && s.alerts().get(0).equals("Building check: about 300 blocks of Steve_Builder's build were burned or blown up, cause unknown. It was not printed.")
                && s.idx.sites.firstEntry().getValue().harmed, "D-16: wither damage to the owner, harmed: " + s.alerts());
        for (int i = 0; i < 30; i++) s.lose("fire", null, "oak_planks", X + i % 10, Y + 6, Z + i / 10);
        s.fold();
        t(s.entries().size() == 1 && s.entry(0).getAsJsonObject("incident").get("cause").getAsString().equals("fire") && s.alerts().isEmpty(), "D-16: a small fire is an incident");

        // D-09: a friend who breaks and places as much is helping
        s = new Sc().notice(STEVE, ALEX);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
        s.fold();
        s.boxBreak(ALEX, "oak_planks", X, Y + 5, Z, X + 9, Y + 5, Z + 9, 30);
        s.box(ALEX, "glass", X, Y + 5, Z, X + 6, Y + 5, Z + 4);
        s.fold();
        t(s.blotter().size() == 0 && s.alerts().isEmpty() && s.res.out().getAsJsonArray("drop_mined_at").size() == 1, "D-09: helping is not news");

        // D-12: a co-builder tearing down a lot of the other's part
        s = new Sc().notice(STEVE, ALEX);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 19, Y + 9, Z + 14);
        s.fold();
        s.box(ALEX, "spruce_planks", X + 20, Y, Z, X + 21, Y + 9, Z + 9);
        s.boxBreak(ALEX, "oak_planks", X, Y, Z, X + 19, Y + 9, Z + 14, 1000);
        s.fold();
        t(s.alerts().size() == 1 && s.alerts().get(0).contains("co-builder Steve_Builder") && s.alerts().get(0).contains("Could be a redesign")
                && s.entries().size() == 0 && s.blotter().size() == 0, "D-12: co-builders: an alert, nothing printed: " + s.alerts());
    }

    // ------------------------------------------------------------ early adversarial review
    static Settings.Construction forgetAfter(int days) {
        return new Settings.Construction("Planning & Zoning", true, "public", true, 3, 64, "blotter", 100, 15, days, List.of(), 20000, List.of());
    }

    static void reviewFixes() throws Exception {
        // LP-2 / CSF-3 / EC-6: a Blotter line about a shared build needs every crew member public and noticed
        for (int v = 0; v < 3; v++) {
            Sc s = new Sc().notice(STEVE, ALEX, SAM);
            s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
            s.box(ALEX, "oak_planks", X, Y + 6, Z, X + 9, Y + 9, Z + 9);
            s.fold();
            t(s.idx.sites.size() == 1 && s.idx.sites.firstEntry().getValue().crew.size() == 2, "shared build, two crew");
            if (v == 0) s.prefs.get(s.id(ALEX).toString()).level = "private";
            if (v == 1) s.prefs.get(s.id(ALEX).toString()).noticed = null;
            s.boxBreak(SAM, "oak_planks", X, Y, Z, X + 9, Y, Z + 9, 25);
            s.fold();
            t(s.res.out().getAsJsonArray("drop_mined_at").size() == 1, "LP-2: the mined_at row still goes (" + v + ")");
            t(s.blotter().size() == (v == 2 ? 1 : 0), "LP-2: Blotter line only when every crew member is public and noticed (" + v + "): " + s.blotter());
        }

        // CSF-1: a helper taking down their own blocks on a friend's build is not breaking the friend's build
        Sc s = new Sc().notice(STEVE, ALEX);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
        s.fold();
        s.box(ALEX, "cobblestone", X, Y + 6, Z, X + 9, Y + 6, Z + 2);   // 30, in cubes Steve filled first
        s.boxBreak(ALEX, "cobblestone", X, Y + 6, Z, X + 9, Y + 6, Z + 2, 30);
        s.fold();
        t(s.blotter().size() == 0 && s.alerts().isEmpty(), "CSF-1 S1: own scaffold, same paper: " + s.blotter());
        s.box(ALEX, "spruce_planks", X, Y + 6, Z, X + 9, Y + 6, Z + 3);   // 40
        s.fold();
        s.boxBreak(ALEX, "spruce_planks", X, Y + 6, Z, X + 9, Y + 6, Z + 3, 30);
        s.fold();
        t(s.blotter().size() == 0 && s.alerts().isEmpty(), "CSF-1 S1b: own blocks, a paper later: " + s.blotter());
        t(SiteIndex.read(s.idx.write()).write().equals(s.idx.write()), "CSF-1: sites.json round trip with other players' blocks");
        s.boxBreak(ALEX, "oak_planks", X, Y, Z, X + 9, Y, Z + 9, 25);
        s.fold();
        t(s.blotter().size() == 1, "CSF-1: breaking the friend's own blocks still makes the Blotter: " + s.blotter());
        s = new Sc().notice(STEVE, ALEX);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
        s.fold();
        s.box(ALEX, "cobblestone", X - 3, Y + 6, Z, X + 11, Y + 6, Z + 9);   // 150
        s.boxBreak(ALEX, "cobblestone", X - 3, Y + 6, Z, X + 11, Y + 6, Z + 9, 150);
        s.fold();
        t(s.alerts().isEmpty() && s.blotter().size() == 0, "CSF-1 S1c: no griefing alert for removing your own 150: " + s.alerts());

        // CSF-4: a visitor's TNT on their own blocks does not give the builder the bad award
        s = new Sc().notice(STEVE, ALEX);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
        s.box(ALEX, "oak_planks", X + 13, Y, Z, X + 15, Y + 3, Z + 3);   // 48 in cubes of his own, joined to the build
        s.fold();
        t(s.idx.sites.size() == 1 && s.idx.sites.firstEntry().getValue().crew.equals(List.of(s.id(STEVE).toString())), "CSF-4: one build, Steve the only crew");
        for (int i = 0; i < 40; i++) s.lose("tnt", ALEX, "oak_planks", X + 13 + i % 3, Y + (i / 3) % 4, Z + i / 12);
        s.lose("tnt", STEVE, "oak_planks", X, Y + 5, Z);
        s.fold();
        String out = s.res.out().toString();
        t(out.contains("\"cause\":\"tnt\"") && !out.contains("\"self\":true") && !s.awards().toString().contains("headless_chicken"),
                "CSF-4: not self, no award: " + out);

        // EC-1: a merge keeps the newer last growth, and an alias goes with its build
        s = new Sc().notice(STEVE);
        s.c = forgetAfter(30);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
        s.fold();
        s.day = LocalDate.of(2026, 11, 27);
        s.box(STEVE, "oak_planks", X + 30, Y, Z, X + 39, Y + 5, Z + 9);
        s.fold();
        t(s.idx.sites.size() == 2, "EC-1: two builds");
        for (int k = -1831; k <= -1829; k++) s.place(STEVE, "oak_planks", k * 4, Y, Z);
        s.fold();
        t(s.idx.sites.values().stream().filter(x -> x.aliasOf == null).count() == 1, "EC-1: the walkway joins them");
        s.day = LocalDate.of(2026, 12, 3);
        s.fold();
        t(s.idx.sites.values().stream().anyMatch(x -> x.aliasOf == null && "2026-11-27".equals(x.lastGrowth)), "EC-1: the newer build is not forgotten");
        s.day = LocalDate.of(2026, 12, 29);
        s.fold();
        t(s.idx.sites.isEmpty(), "EC-1: forgotten together, no alias left behind: " + s.idx.sites.keySet());

        // EC-2: a live build that touches a retired one is not merged into it
        s = new Sc().notice(STEVE);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
        s.fold();
        s.boxBreak(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9, 600);
        s.fold();
        s.fold();
        s.box(STEVE, "oak_planks", X + 30, Y, Z, X + 35, Y + 2, Z + 5);   // version 2
        s.fold();
        t(s.entries().size() == 1 && s.entry(0).get("permit").getAsInt() == 2, "EC-2: version 2 is Permit #2: " + s.entries());
        s.place(STEVE, "oak_planks", -1831 * 4, Y, Z);
        s.place(STEVE, "oak_planks", -1830 * 4, Y, Z);
        s.fold();
        SiteIndex.Site v2 = s.idx.sites.get(2);
        t(v2 != null && v2.aliasOf == null && !v2.retired && s.res.status().get(s.id(STEVE).toString()) != null, "EC-2: Permit #2 stays live and listed");
        s.box(STEVE, "oak_planks", X + 30, Y + 3, Z, X + 35, Y + 3, Z + 3);
        s.fold();
        t(s.entries().size() == 0 && s.idx.sites.get(2).aliasOf == null && s.idx.sites.get(2).permit == 2 && !s.idx.sites.get(2).rebuild,
                "EC-2: it grows as Permit #2, not a rebuild of #1: " + s.entries());
        for (int i = 0; i < 4; i++) s.fold();
        t(!s.idx.sites.get(2).retired, "EC-2: the retired build's old peak does not retire version 2");

        // EC-4: a farm harvested and replanted every day is never forgotten and filed again
        s = new Sc().notice(STEVE);
        s.c = forgetAfter(7);
        for (int dx = 0; dx < 10; dx++) for (int dz = 0; dz < 10; dz++) s.place(STEVE, "wheat", X + dx, Y, Z + dz);
        for (int i = 0; i < 24; i++) s.place(STEVE, "oak_fence", X + i % 12, Y, Z - 1 - i / 12);
        s.fold();
        t(s.entries().size() == 1, "EC-4: the farm prints on day 1: " + s.entries());
        for (int d = 2; d <= 10; d++) {
            for (int dx = 0; dx < 10; dx++) for (int dz = 0; dz < 10; dz++) s.brk(STEVE, "wheat", X + dx, Y, Z + dz);
            s.flush();
            for (int dx = 0; dx < 10; dx++) for (int dz = 0; dz < 10; dz++) s.place(STEVE, "wheat", X + dx, Y, Z + dz);
            s.fold();
            t(s.entries().size() == 0 && s.idx.sites.size() == 1, "EC-4: nothing new on day " + d + ": " + s.entries());
        }

        // LP-3: a secret typed on a build still too small to track hides that build when it grows, and only that build
        s = new Sc().notice(STEVE);
        house(s, STEVE, X + 60, Y, Z);
        s.fold();
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y, Z);
        s.reqAt(STEVE, "secret", X + 2, Y + 1, Z);
        s.fold();
        t(s.idx.sites.size() == 1 && s.idx.sites.firstEntry().getValue().secretBy.isEmpty(), "LP-3: the older build is not made secret instead");
        house(s, STEVE, X, Y, Z);
        s.fold();
        t(s.entries().size() == 0 && s.idx.sites.size() == 2, "LP-3: the surprise build stays out: " + s.entries());
        t(SiteIndex.read(s.idx.write()).write().equals(s.idx.write()), "LP-3: sites.json round trip");
        s = new Sc().notice(STEVE);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y, Z);
        s.reqAt(STEVE, "secret", X + 2, Y + 1, Z);
        s.fold();
        s.reqAt(STEVE, "unsecret", X + 2, Y + 1, Z);
        s.fold();
        house(s, STEVE, X, Y, Z);
        s.fold();
        t(s.entries().size() == 1, "LP-3: unsecret before it grows takes the secret back");

        // EC-7: grass dug and planks laid in the same minute (a sunken floor) is still a build
        s = new Sc().notice(STEVE);
        for (int dx = 0; dx < 12; dx++) for (int dz = 0; dz < 12; dz++) { s.brk(STEVE, "grass_block", X + dx, Y, Z + dz); s.place(STEVE, "oak_planks", X + dx, Y, Z + dz); }
        s.fold();
        t(s.idx.sites.size() == 1 && s.idx.sites.firstEntry().getValue().net == 144, "EC-7: same-minute floor counted: " + s.idx.sites.size());
        s.brk(STEVE, "grass_block", X + 20, Y, Z);
        s.fold();
        t(s.idx.sites.firstEntry().getValue().net == 144, "EC-7: digging untracked ground still changes nothing");

        // LP-4 / CSF-2 / EC-5 / PO-2: the notice and /news build follow the player's real level
        Settings.Construction priv = new Settings.Construction("Planning & Zoning", true, "private", true, 3, 64, "blotter", 100, 15, 90, List.of(), 20000, List.of(STEVE));
        UUID su = UUID.randomUUID();
        t(Construction.effectiveLevel(Map.of(), su, "steve_builder", priv).equals("public"), "opted in, not folded yet: public");
        t(Construction.effectiveLevel(Map.of(), su, ALEX, priv).equals("private"), "not opted in: default-level");
        t(Construction.effectiveLevel(Map.of(su.toString(), "private"), su, STEVE, priv).equals("private"), "the player's own choice wins");
        t(Construction.effectiveLevel(Map.of(), su, ALEX, forgetAfter(30)).equals("public"), "default-level public");
        t(Construction.noticeText("The Daily Cluck", "public").startsWith("The Daily Cluck may now write about what you build"), "public: the may-write notice");
        String pn = Construction.noticeText("The Daily Cluck", "private");
        t(!pn.contains("may now write") && pn.contains("unless you type /news build public") && pn.contains("Never where"), "private: nothing printed unless public: " + pn);

        // LP-5: a hidden player's /news build stores nothing; a creative or vanished player's request carries no position
        t(BuildCommands.neverRecorded(Set.of("staffalt"), "StaffAlt") && !BuildCommands.neverRecorded(Set.of("staffalt"), STEVE), "LP-5: hidden players get the fixed reply");
        t(BuildCommands.positionOk(org.bukkit.GameMode.SURVIVAL, false) && BuildCommands.positionOk(org.bukkit.GameMode.ADVENTURE, false)
                && !BuildCommands.positionOk(org.bukkit.GameMode.CREATIVE, false) && !BuildCommands.positionOk(org.bukkit.GameMode.SPECTATOR, false)
                && !BuildCommands.positionOk(org.bukkit.GameMode.SURVIVAL, true), "LP-5: only players the recorder records carry a cube");

        // PO-6: the offline-player list is read at most every 10 minutes, not on every /news build name
        BuildCommands bc = new BuildCommands(null);
        int[] reads = {0};
        bc.offline = () -> { reads[0]++; return List.of("Old_Timer"); };
        t(bc.offlineNames(0).contains("Old_Timer") && bc.offlineNames(5_000).contains("Old_Timer") && reads[0] == 1, "PO-6: cached");
        bc.offlineNames(700_000);
        t(reads[0] == 2, "PO-6: refreshed after 10 minutes");

        // G5: after /news build private the status no longer repeats the last paper's "in the paper" lines
        Construction live = ProductionTest.bare(new ConstructionIO(Files.createTempDirectory("g5"), ConstructionIOTest.LOG));
        Settings d = Settings.defaults();
        ProductionTest.set(live, "settings", new Settings(d.paper(), d.tone(), d.owner(), d.privacy(), d.schedule(), d.writer(), d.web(),
                new Settings.Features(false, true, false, true, 3, "", false, true), d.alerts()));
        UUID ju = UUID.randomUUID();
        ProductionTest.set(live, "sentThisRun", new HashSet<>(Set.of(ju)));
        live.io.levels = Map.of(ju.toString(), "public");
        live.io.status = Map.of(ju.toString(), List.of("No permit yet \"Plank Hall\" (in the paper from the next one): a house, day 1, 90 blocks"));
        List<String> told = new ArrayList<>();
        org.bukkit.entity.Player juno = (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(ConstructionTest.class.getClassLoader(),
                new Class<?>[] {org.bukkit.entity.Player.class}, (proxy, method, values) -> switch (method.getName()) {
                    case "getName" -> "Juno_B";
                    case "getUniqueId" -> ju;
                    case "sendMessage" -> { told.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize((net.kyori.adventure.text.Component) values[0])); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        BuildCommands live2 = new BuildCommands(live);
        java.lang.reflect.Field cool = BuildCommands.class.getDeclaredField("cooldown");
        cool.setAccessible(true);
        for (String typed : new String[] {"", "private", ""}) {
            ((Map<?, ?>) cool.get(live2)).clear();   // no 3 second wait between the commands
            live2.command(juno, typed.isEmpty() ? new String[0] : new String[] {typed});
        }
        t(told.size() == 7 && told.get(0).equals("Your builds: public.") && told.get(1).contains("Plank Hall") && told.get(4).equals("Your builds: private.")
                && told.get(5).equals("Nothing about your builds is in line for the paper."), "G5: status after /news build private: " + told);

        // PO-8: the not-counted figure is a total since start, and says so
        t(Construction.countsLine(3, 12, 5234, 0).contains("5234 not counted since start"), "PO-8: " + Construction.countsLine(3, 12, 5234, 0));
    }

    // ------------------------------------------------------------ review round 3
    static void round3() throws Exception {
        // PS3-2: construction.opted-in is read live; taking a name off makes the player private again
        Settings.Construction in = new Settings.Construction("Planning & Zoning", true, "private", true, 3, 64, "blotter", 100, 15, 30, List.of(), 20000, List.of(STEVE));
        Settings.Construction out = new Settings.Construction("Planning & Zoning", true, "private", true, 3, 64, "blotter", 100, 15, 30, List.of(), 20000, List.of());
        Sc s = new Sc().notice(STEVE, ALEX);
        s.c = in;
        house(s, STEVE, X, Y, Z);
        house(s, ALEX, X + 40, Y, Z);
        s.fold();
        t(s.entries().size() == 1 && s.entry(0).toString().contains(STEVE), "PS3-2: the opted-in player is printed");
        t(s.prefs.players.get(s.id(STEVE).toString()).level == null, "PS3-2: opted-in is not written as the player's own choice");
        java.nio.file.Path dir = Files.createTempDirectory("ps32");
        Files.createDirectories(dir.resolve("construction"));
        Files.writeString(dir.resolve("construction").resolve("players.json"), s.prefs.write());
        t(Levels.nameable(dir, in).test(s.id(STEVE).toString(), STEVE), "PS3-2: nameable while listed");
        t(!Levels.nameable(dir, out).test(s.id(STEVE).toString(), STEVE), "PS3-2: not nameable once taken off the list");
        s.c = out;
        s.box(STEVE, "oak_planks", X, Y + 6, Z, X + 3, Y + 6, Z + 3);
        s.fold();
        t(s.entries().size() == 0 && "private".equals(s.prefs.level(s.id(STEVE).toString(), out)), "PS3-2: off the list, not printed: " + s.entries());
        s.req(ALEX, "public", null);
        s.fold();
        t("public".equals(s.prefs.level(s.id(ALEX).toString(), out)), "PS3-2: a player who typed public stays public");
        // an older jar's players.json (opted-in written into level) is undone on read
        Prefs old = Prefs.read("{\"version\":1,\"players\":{\"u1\":{\"name\":\"Kid\",\"level\":\"public\",\"noticed\":\"2026-11-01\"}},\"opted_applied\":[\"kid\"]}");
        t("private".equals(old.level("u1", out)) && "public".equals(old.level("u1", new Settings.Construction("Planning & Zoning", true, "private", true, 3, 64,
                "blotter", 100, 15, 30, List.of(), 20000, List.of("Kid")))), "PS3-2: an old file's opted-in public follows the live list");

        // R3C-1: digging natural dirt or grass in the cubes of someone's build is not breaking the build
        for (String ground : List.of("dirt", "grass_block")) {
            s = new Sc().notice(STEVE, ALEX);
            s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
            s.fold();
            s.boxBreak(ALEX, ground, X, Y - 1, Z, X + 9, Y - 1, Z + 2, 30);
            s.fold();
            t(s.blotter().size() == 0 && s.alerts().isEmpty() && s.res.out().getAsJsonArray("drop_mined_at").size() == 0
                    && s.idx.sites.firstEntry().getValue().net == 600, "R3C-1: digging " + ground + " under a build: " + s.blotter() + " net " + s.idx.sites.firstEntry().getValue().net);
        }
        // cycling dirt in one minute builds nothing; dig-then-floor still counts (EC-7)
        s = new Sc().notice(STEVE);
        s.brk(STEVE, "dirt", X + 1, Y, Z);   // one natural block first, so a per-material total would not catch the cycle
        for (int i = 0; i < 64; i++) for (int dx = 0; dx < 4; dx++) { s.place(STEVE, "dirt", X + dx, Y, Z); s.brk(STEVE, "dirt", X + dx, Y, Z); }
        s.fold();
        t(s.idx.sites.isEmpty() && s.idx.cubes.get(0).values().stream().mapToInt(SiteIndex.Cube::n).sum() == 0, "same minute: 256 dirt place/break cycles build nothing");
        s = new Sc().notice(STEVE);
        for (int dx = 0; dx < 12; dx++) for (int dz = 0; dz < 12; dz++) { s.brk(STEVE, "dirt", X + dx, Y, Z + dz); s.place(STEVE, "oak_planks", X + dx, Y, Z + dz); }
        s.fold();
        t(s.idx.sites.size() == 1 && s.idx.sites.firstEntry().getValue().net == 144, "same minute: dirt dug, floor laid: floor counted");

        // R3C-2: a co-builder's forget leaves the other builder's build as it is now, not a decline or a rebuild
        s = new Sc().notice(STEVE, ALEX);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
        s.box(ALEX, "stone_bricks", X + 10, Y, Z, X + 39, Y + 9, Z + 9);
        s.fold();
        s.fold();
        s.req(ALEX, "forget", null);
        s.fold();
        for (int i = 0; i < 3; i++) s.fold();
        SiteIndex.Site site = s.idx.sites.firstEntry().getValue();
        t(!site.retired && !site.mats.containsKey("stone_bricks"), "R3C-2: not retired, no forgotten materials: retired=" + site.retired + " " + site.mats);
        s.box(STEVE, "oak_planks", X, Y + 6, Z, X + 9, Y + 6, Z + 9);
        s.fold();
        site = s.idx.sites.firstEntry().getValue();
        t(!site.rebuild && !s.entries().toString().contains("rebuild") && !s.entries().toString().contains("stone brick"), "R3C-2: progress, not a rebuild: " + s.entries());

        // PT-R3-1 / PT-R3-2: the fold stays fast with many loose builds placed against old blocks, and many builds expiring at once
        Map<String, Integer> ki = Map.of("cobblestone", (int) KeyRules.BUILT);
        UUID wu = Sc.WORLD;
        List<RawLine.Line> lines = new ArrayList<>();
        for (int i = 0; i < 20000; i++) {
            int cx = (i % 1000) * 4, cz = (i / 1000) * 4;
            int[] d = new int[192];
            d[0] = 1;
            UUID p = UUID.nameUUIDFromBytes(("p" + (i % 50)).getBytes());
            lines.add(new RawLine.Line('p', p, "p" + (i % 50), wu, cx, 4, cz, 1, 0, 0, 0, 0, 0, 1, List.of(new int[]{cx, 4, cz, 1}), d, Map.of("cobblestone", 1), null, null, new int[64], null));
        }
        Settings.Construction dc = Settings.Construction.defaults();
        long t0 = System.nanoTime();
        Fold.run(new SiteIndex(), new Prefs(), lines, List.of(), new Fold.Cfg(dc, Set.of(), LocalDate.of(2026, 11, 2), ki));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        t(ms < 5000, "PT-R3-1: 20,000 loose placements with against-refs fold in " + ms + " ms");
        SiteIndex idx = new SiteIndex();
        idx.world(wu.toString());
        for (int id = 1; id <= 20000; id++) {
            SiteIndex.Site st = new SiteIndex.Site();
            st.id = id; st.world = 0; st.created = "2026-01-01"; st.lastGrowth = "2026-01-01";
            idx.sites.put(id, st);
            for (int k = 0; k < 20; k++) { SiteIndex.Cube cb = new SiteIndex.Cube(); cb.site = id; cb.b = 10; cb.t = "2026-01-01"; idx.cubes.get(0).put(SiteIndex.cubeKey(id * 10, k, 0), cb); }
        }
        idx.nextSite = 20001;
        t0 = System.nanoTime();
        Fold.run(idx, new Prefs(), List.of(), List.of(), new Fold.Cfg(dc, Set.of(), LocalDate.of(2026, 11, 2), ki));
        ms = (System.nanoTime() - t0) / 1_000_000;
        t(ms < 5000 && idx.sites.isEmpty() && idx.cubes.get(0).isEmpty(), "PT-R3-2: 20,000 builds expire at once in " + ms + " ms");
    }

    // ------------------------------------------------------------ who may be printed
    static void privacy() throws Exception {
        Sc s = new Sc().notice(STEVE);
        house(s, ALEX, X, Y, Z);
        s.fold();
        t(s.entries().size() == 0, "C-01: no notice yet, not printed");
        s.prefs.get(s.id(ALEX).toString()).noticed = s.day.minusDays(1).toString();
        s.fold();
        t(s.entries().size() == 0, "C-01: noticed today, one paper of grace");
        s.fold();
        t(s.entries().size() == 1, "C-01: printed after the grace");

        s = new Sc().notice(STEVE, ALEX);
        s.prefs.get(s.id(ALEX).toString()).level = "private";
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
        s.box(ALEX, "oak_planks", X, Y + 6, Z, X + 9, Y + 9, Z + 9);
        s.fold();
        t(s.entries().size() == 0, "C-02: a private co-builder keeps the build out");

        s = new Sc().notice(STEVE, ALEX);
        s.prefs.get(s.id(ALEX).toString()).level = "private";
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
        s.box(ALEX, "oak_planks", X + 10, Y, Z, X + 10, Y + 2, Z + 9);
        s.fold();
        t(s.entries().size() == 1 && !s.entry(0).toString().contains(ALEX) && s.entry(0).get("blocks_total").getAsInt() == 600, "C-03: a private helper is not named or counted");

        // F2: a public helper who is not crew is not in "builders", so blocks_today leaves their blocks out too
        s = new Sc().notice(STEVE, ALEX);
        s.box(STEVE, "oak_planks", X, Y, Z, X + 9, Y + 5, Z + 9);
        s.box(ALEX, "oak_planks", X + 10, Y, Z, X + 10, Y + 2, Z + 9);
        s.fold();
        t(s.entries().size() == 1 && s.entry(0).get("builders").toString().equals("[\"" + STEVE + "\"]") && s.entry(0).get("blocks_today").getAsInt() == 600,
                "F2: blocks_today counts the named builders only: " + s.entries());

        s = new Sc().notice(STEVE, ALEX);
        house(s, STEVE, X, Y, Z);
        s.reqAt(STEVE, "secret", X + 2, Y + 1, Z + 2);
        s.fold();
        t(s.entries().size() == 0, "C-04: secret by a builder: not printed");

        s = new Sc().notice(STEVE, ALEX);
        house(s, STEVE, X, Y, Z);
        s.reqAt(ALEX, "secret", X + 2, Y + 1, Z + 2);
        s.fold();
        t(s.entries().size() == 1 && s.idx.sites.firstEntry().getValue().secretBy.isEmpty(), "C-07: a non-builder's secret is never stored");

        // default-level private: nobody is printed until they say public; opted-in names are public once
        s = new Sc().notice(STEVE, ALEX);
        s.c = new Settings.Construction("Planning & Zoning", true, "private", true, 3, 64, "blotter", 100, 15, 30, List.of(), 20000, List.of(STEVE));
        house(s, STEVE, X, Y, Z);
        house(s, ALEX, X + 40, Y, Z);
        s.fold();
        t(s.entries().size() == 1 && s.entry(0).toString().contains(STEVE), "opt-in: only the opted-in player is printed");
        s.req(STEVE, "private", null);
        s.box(STEVE, "oak_planks", X, Y + 6, Z, X + 3, Y + 6, Z + 3);
        s.fold();
        t("private".equals(s.prefs.players.get(s.id(STEVE).toString()).level), "opt-in: the player's own choice wins");

        // C-05 hidden players are never recorded; C-06-ish forget
        s = new Sc().notice(STEVE);
        s.hidden = Set.of("steve_builder");
        house(s, STEVE, X, Y, Z);
        s.fold();
        t(s.idx.sites.isEmpty() && s.entries().size() == 0, "C-05: hidden player's lines dropped");
        s = new Sc().notice(STEVE);
        house(s, STEVE, X, Y, Z);
        s.fold();
        s.req(STEVE, "forget", null);
        s.fold();
        t(s.idx.sites.isEmpty() && "private".equals(s.prefs.players.get(s.id(STEVE).toString()).level) && s.prefs.players.get(s.id(STEVE).toString()).noticed != null,
                "forget: cubes and builds gone, private, notice kept");
        // C-09: noticed keeps the earliest date
        s.reqs.add(RawLine.request(s.id(ALEX), "noticed", null, null, null, "2026-11-04"));
        s.reqs.add(RawLine.request(s.id(ALEX), "noticed", null, null, null, "2026-11-06"));
        s.fold();
        t("2026-11-04".equals(s.prefs.players.get(s.id(ALEX).toString()).noticed), "C-09: the earliest notice date wins");
        // print: false is a practice run: worked out, but nothing remembered as printed
        s = new Sc().notice(STEVE);
        s.c = new Settings.Construction("Planning & Zoning", false, "public", true, 3, 64, "blotter", 100, 15, 30, List.of(), 20000, List.of());
        house(s, STEVE, X, Y, Z);
        s.fold();
        t(s.entries().size() == 1 && s.idx.sites.firstEntry().getValue().permit == null && s.prefs.nextPermit == 1 && "2026-10-01".equals(s.prefs.opened),
                "practice run: entries worked out, no permit kept");
    }

    // ------------------------------------------------------------ older builds, golems, odd keys
    static void existingAndSummon() throws Exception {
        // F-32: planks added against an old, untracked stone wall
        Sc s = new Sc().notice(STEVE);
        for (int i = 0; i < 120; i++) {
            int x = X + i % 12, y = Y + i / 12 % 5, z = Z + 1 + i / 60;
            MinuteMaps.Acc a = s.place(STEVE, "spruce_planks", x, y, z);
            MinuteMaps.against(a, s.w, x, y, z, x, y, Z - 4);
        }
        s.place(STEVE, "oak_door", X + 3, Y, Z + 3); s.place(STEVE, "red_bed", X + 4, Y, Z + 3); s.place(STEVE, "crafting_table", X + 5, Y, Z + 3);
        s.fold();
        JsonObject e = s.entry(0);
        t(e.get("existing").getAsBoolean() && e.get("tag").getAsString().equals("permit") && !e.has("size") && !e.has("height") && !e.has("megaproject"),
                "F-32: an addition to an older build: " + e);
        t(!s.awards().toString().contains("first home"), "F-32: never a first home");
        // T-ANGLE (1): printed again as a ribbon, an existing build's angle is never size
        s.req(STEVE, "done", null);
        s.fold();
        t(s.entry(0).get("tag").getAsString().equals("ribbon") && !s.entry(0).get("angle").getAsString().equals("size")
                && !s.entry(0).get("angle").getAsString().equals("materials"), "T-ANGLE: " + s.entry(0).get("angle"));
        // F-33(a): a house on the ground is not "existing"
        s = new Sc().notice(STEVE);
        house(s, STEVE, X, Y, Z);
        s.fold();
        t(!s.entry(0).has("existing") && s.entry(0).get("tag").getAsString().equals("new_resident"), "F-33: a new house is new");

        // F-40: snow golems leave nothing behind
        s = new Sc().notice(STEVE);
        for (int i = 0; i < 30; i++) {
            s.place(STEVE, "snow_block", X, Y, Z); s.place(STEVE, "snow_block", X, Y + 1, Z); s.place(STEVE, "carved_pumpkin", X, Y + 2, Z);
            s.flush();
            s.lose("summon", null, "snow_block", X, Y, Z); s.lose("summon", null, "snow_block", X, Y + 1, Z); s.lose("summon", null, "carved_pumpkin", X, Y + 2, Z);
            s.flush();
        }
        s.fold();
        t(s.idx.sites.isEmpty() && s.entries().size() == 0 && s.fr.alerts.isEmpty(), "F-40: golems leave no build");
        // golems built inside a new house the same day: "grew today" is never more than the whole build
        s = new Sc().notice(STEVE);
        house(s, STEVE, X, Y, Z);
        s.flush();
        for (int i = 0; i < 30; i++) {
            s.place(STEVE, "snow_block", X + 2, Y + 1, Z + 2); s.place(STEVE, "snow_block", X + 2, Y + 2, Z + 2); s.place(STEVE, "carved_pumpkin", X + 2, Y + 3, Z + 2);
            s.flush();
            s.lose("summon", null, "snow_block", X + 2, Y + 1, Z + 2); s.lose("summon", null, "snow_block", X + 2, Y + 2, Z + 2); s.lose("summon", null, "carved_pumpkin", X + 2, Y + 3, Z + 2);
            s.flush();
        }
        s.fold();
        t(s.entries().size() == 1 && s.entry(0).get("blocks_today").getAsInt() <= s.entry(0).get("blocks_total").getAsInt(),
                "golems: blocks_today " + s.entry(0).get("blocks_today") + " is not more than blocks_total " + s.entry(0).get("blocks_total"));
        // F-40c: the summon -1 is written after the +1s of the same minute
        s = new Sc();
        s.place(STEVE, "snow_block", X, Y, Z);
        s.lose("summon", null, "snow_block", X, Y, Z);
        s.flush();
        String[] lines = s.raw.toString().split("\n");
        t(lines[0].contains("\"k\":\"p\"") && lines[1].contains("\"k\":\"x\""), "F-40c: player lines before loss lines");
        // F-41: a key below 0 never pulls a family down
        Map<String, Integer> mats = new HashMap<>(Map.of("oak_planks", 300, "stone_bricks", 40, "melon_stem", 20, "attached_melon_stem", -20));
        Families.Pick a = Families.pick(mats, 340, KEY_INFO);
        mats.remove("attached_melon_stem");
        t(a.equals(Families.pick(mats, 360, KEY_INFO)) || a.materials().equals(Families.pick(mats, 340, KEY_INFO).materials()), "F-41: a negative key is clamped");
        // T-FAM: every name the filter allows is plain and has no gate word
        for (String k : List.of("oak_planks", "dark_oak_stairs", "stone_brick_stairs", "jungle_planks", "red_mushroom_block", "sea_pickle", "small_amethyst_bud",
                "cherry_planks", "end_stone_bricks", "white_concrete", "glass_pane", "hay_block", "cobblestone", "moss_block", "mangrove_roots", "snow_block",
                "nether_wart", "cactus", "prismarine_bricks", "bamboo_mosaic", "pale_oak_planks", "polished_andesite_slab", "crimson_planks", "warped_stem")) {
            String f = Families.family(k, KeyRules.info(k, tags(k)), KEY_INFO::containsKey);
            t(f == null || (f.matches("[a-z ]{2,30}") && Families.allowed(f)), "T-FAM: " + k + " -> " + f);
        }
        for (String trap : List.of("jungle_planks", "red_mushroom_block", "sea_pickle", "cherry_planks", "end_stone_bricks", "nether_wart", "moss_block"))
            t(Families.family(trap, 0, KEY_INFO::containsKey) == null, "T-FAM trap hidden: " + trap);
        t("amethyst".equals(Families.family("small_amethyst_bud", 0, k -> false)), "small amethyst bud -> amethyst");
        for (String k : Kinds.ALL) {   // alpha.21: the few kinds with a gate word inside are printed as whole phrases only (ConstructionGate.ALLOWED)
            boolean phrase = io.github.eastmadc.masthead.edition.ConstructionGate.ALLOWED.contains(Kinds.display(k));
            t(phrase || Families.allowed(Kinds.display(k)) && Families.allowed(k.replace('_', ' ')), "T-FAM: kind label " + k);
        }
        for (String w : List.of("compact", "medium", "large", "huge", "low", "tall", "very tall", "towering")) t(Families.allowed(w), "T-FAM: size word " + w);
    }

    // ------------------------------------------------------------ review round 1
    static void reviewRound1() throws Exception {
        // PRIV-2 (notice by level) is covered by main's LP-4 tests

        // PRIV-3: a private helper's blocks never cross a milestone or swell blocks_week
        Sc s = new Sc().notice(STEVE, ALEX);
        s.prefs.get(s.id(ALEX).toString()).level = "private";
        s.box(STEVE, "stone_bricks", X, Y, Z, X + 19, Y + 14, Z + 9);   // 3000
        s.fold();
        s.box(ALEX, "cobblestone", X, Y + 15, Z, X + 19, Y + 25, Z + 9);   // 20 x 11 x 10 by a private helper
        s.fold();
        t(!s.res.out().toString().contains("milestone_blocks") && !s.awards().toString().contains("5000"), "PRIV-3: no milestone from a private helper's blocks: " + s.res.out());
        while (s.day.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) { s.box(STEVE, "stone_bricks", X + 25, Y, Z, X + 25, Y + 3, Z + 4); s.fold(); }
        s.box(STEVE, "stone_bricks", X + 26, Y, Z, X + 26, Y + 3, Z + 4);
        s.fold();
        JsonArray tr = s.res.out().getAsJsonObject("construction").getAsJsonArray("tracker");
        boolean critic = false;
        for (JsonElement e : tr) {
            JsonObject r = e.getAsJsonObject();
            if (!r.has("blocks_week")) continue;
            critic = true;
            t(r.get("blocks_week").getAsInt() <= r.get("blocks_total").getAsInt(), "PRIV-3: blocks_week is never more than blocks_total: " + r);
        }
        t(critic, "PRIV-3: the Sunday critic row is there: " + tr);

        // C1: taking back your own blocks from someone else's cube is not breaking their build
        s = new Sc().notice(STEVE, ALEX);
        house(s, STEVE, X, Y, Z);
        s.fold();
        s.box(ALEX, "stone_bricks", X, Y + 5, Z, X + 4, Y + 5, Z + 4);   // 25 on Steve's roof, in cubes Steve filled first
        s.fold();
        s.boxBreak(ALEX, "stone_bricks", X, Y + 5, Z, X + 4, Y + 5, Z + 4, 25);   // and his own 25 back
        s.fold();
        t(s.blotter().isEmpty() && s.alerts().isEmpty(), "C1: no Blotter row or alert for taking your own blocks back: " + s.blotter() + " " + s.alerts());
        s = new Sc().notice(STEVE, ALEX);
        house(s, STEVE, X, Y, Z);
        s.fold();
        s.box(ALEX, "stone_bricks", X, Y + 5, Z, X + 10, Y + 6, Z + 8);   // a co-builder's parapet
        s.fold();
        s.boxBreak(ALEX, "stone_bricks", X, Y + 6, Z, X + 10, Y + 6, Z + 8, 90);   // takes 90 of his own back out
        s.fold();
        SiteIndex.Site site = s.idx.sites.firstEntry().getValue();
        t(s.alerts().isEmpty() && !site.harmed && !s.fr.out.firstEntry().getValue().leftOut, "C1: a co-builder taking his own blocks back is no falling-out: " + s.alerts());
        // ... but breaking the blocks someone else placed still is
        s.boxBreak(ALEX, "oak_planks", X, Y, Z, X + 10, Y, Z + 8, 90);
        s.fold();
        t(!s.alerts().isEmpty() || !s.blotter().isEmpty(), "C1: breaking Steve's own floor is still reported: " + s.alerts() + " " + s.blotter());
        // the per-player counts survive a save and load
        s = new Sc().notice(STEVE, ALEX);
        house(s, STEVE, X, Y, Z);
        s.fold();
        s.box(ALEX, "stone_bricks", X, Y + 5, Z, X + 4, Y + 5, Z + 4);
        s.fold();
        s.idx = SiteIndex.read(s.idx.write());
        s.boxBreak(ALEX, "stone_bricks", X, Y + 5, Z, X + 4, Y + 5, Z + 4, 25);
        s.fold();
        t(s.blotter().isEmpty() && s.alerts().isEmpty(), "C1: per-player counts kept in the index file: " + s.blotter());

        // C2: golems built on someone else's build are taken off the golem builder's own counts
        s = new Sc().notice(STEVE, ALEX);
        house(s, STEVE, X, Y, Z);
        s.fold();
        for (int i = 0; i < 12; i++) {
            s.place(ALEX, "snow_block", X + 2, Y + 1, Z + 2); s.place(ALEX, "snow_block", X + 2, Y + 2, Z + 2); s.place(ALEX, "carved_pumpkin", X + 2, Y + 3, Z + 2);
            unplace(s, ALEX, "snow_block", X + 2, Y + 1, Z + 2); unplace(s, ALEX, "snow_block", X + 2, Y + 2, Z + 2); unplace(s, ALEX, "carved_pumpkin", X + 2, Y + 3, Z + 2);
            s.flush();
        }
        s.req(STEVE, "done", null);
        s.fold();
        site = s.idx.sites.firstEntry().getValue();
        t(!site.crew.contains(s.id(ALEX).toString()) && s.fr.out.firstEntry().getValue().newCrew.isEmpty() && !s.res.out().toString().contains(ALEX),
                "C2: the golem builder is not crew: " + site.crew + " " + s.res.out());
        t(!s.day.minusDays(1).toString().equals(site.lastGrowth), "C2: golems are not growth: " + site.lastGrowth);
        // the pattern match: same tick and world, head within one block sideways and up to two above the spawn block
        t(Collector.sameSummon(100, 0, 5, 66, 5, 100, 0, 5, 64, 5) && Collector.sameSummon(100, 0, 6, 66, 5, 100, 0, 5, 64, 5), "C2: head over the spawn block matches");
        t(!Collector.sameSummon(99, 0, 5, 66, 5, 100, 0, 5, 64, 5) && !Collector.sameSummon(100, 1, 5, 66, 5, 100, 0, 5, 64, 5)
                && !Collector.sameSummon(100, 0, 5, 70, 5, 100, 0, 5, 64, 5) && !Collector.sameSummon(-1, 0, 5, 66, 5, -1, 0, 5, 66, 5), "C2: anything else does not");

        // PT-3 + PT-2: no file reads for every player who ever joined on the main thread (command and tab complete)
        String bc = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/io/github/eastmadc/masthead/construction/BuildCommands.java"));
        String ig = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/io/github/eastmadc/masthead/game/InGame.java"));
        String tab = ig.substring(ig.indexOf("public List<String> onTabComplete"));
        t(!bc.contains("getOfflinePlayers") && !tab.substring(0, tab.indexOf("\n    }\n")).contains("getOfflinePlayers"), "PT-2/PT-3: /stats tab complete and /news build name use the name list read in the background");
    }

    static void unplace(Sc s, String n, String key, int x, int y, int z) {
        s.mm.unplace(s.id(n), n, s.w, x, y, z, s.cls(key), ORD.get(KeyRules.keyAs(key)));
    }

    // ------------------------------------------------------------ leaks
    static void leakChecks() throws Exception {
        // T-COORD over every output these tests wrote, and a random property run
        Sc s = new Sc().notice(STEVE, ALEX);
        house(s, STEVE, X, Y, Z);
        hut(s, ALEX, X + 40, Y, Z, "dirt");
        s.fold();
        s.boxBreak(ALEX, "oak_planks", X, Y, Z, X + 10, Y, Z + 8, 30);
        s.fold();
        for (String out : s.outputs) for (String bad : List.of("7341", "2963", "-459", "185", ":6:", "1836", "740"))
            t(!out.contains(bad), "T-COORD: output holds " + bad);
        java.util.Random rnd = new java.util.Random(42);
        Sc r = new Sc();
        List<Integer> pos = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String n = "P_" + (i % 7);
            r.notice(n);
            int x = rnd.nextInt(60_000_000) - 30_000_000, y = rnd.nextInt(300) - 60, z = rnd.nextInt(60_000_000) - 30_000_000;
            pos.add(x); pos.add(z);
            r.box(n, "oak_planks", x, y, z, x + 4, y + 3, z + 4);
        }
        r.fold();
        String all = r.res.out().toString() + r.res.alerts() + r.res.status();
        for (int p : pos) if (Math.abs(p) >= 1000) t(!all.contains(String.valueOf(Math.abs(p))), "T-COORD property: position " + p + " leaked");
        // T-REF: only construction/ touches the position files
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                if (f.toString().contains("/construction/")) continue;
                String txt = Files.readString(f);
                for (String w : List.of("SiteIndex", "RawLine", "Fold.", "sites.json", "requests.jsonl", "construction/raw"))
                    t(!txt.contains(w), "T-REF: " + f + " mentions " + w);
            }
        }
        // L-09: nothing in construction/ reads player-written text, except Collector.onSign, which only runs with
        // construction.sign-names on and only files the text as a "sign" request for NameRules (SignNamesTest)
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/io/github/eastmadc/masthead/construction"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String txt = Files.readString(f);
                if (f.getFileName().toString().equals("Collector.java")) {
                    int a = txt.indexOf("public void onSign("), b = txt.indexOf("@EventHandler", a);
                    String h = txt.substring(a, b);
                    t(h.contains("construction().signNames()") && h.contains("RawLine.request(p.getUniqueId(), \"sign\""), "L-09: the one sign reader is behind the setting");
                    txt = txt.replace(h, "").replace("import org.bukkit.event.block.SignChangeEvent;", "");
                }
                for (String w : List.of("Sign", "getLines", "BookMeta", "displayName", "customName", "getSide")) t(!txt.contains(w), "L-09: " + f + " uses " + w);
            }
        }
        t(ConstructionWords.promptList().contains("the End (the dimension)"), "words: the End printed with its note");
    }
    // ------------------------------------------------------------ slime farms and a builder's own kind
    static void slimeFarms() throws Exception {
        // reference chunks from the same formula checked against the live world's known slime chunk
        t(Kinds.slimeChunk(12345, -5, 2) && Kinds.slimeChunk(12345, -4, 0) && Kinds.slimeChunk(12345, -2, -4) && Kinds.slimeChunk(-4242424242L, -6, -6)
                && Kinds.slimeChunk(-4242424242L, -5, 3), "slime chunks match the reference");
        t(!Kinds.slimeChunk(12345, -5, 1) && !Kinds.slimeChunk(12345, -5, 3) && !Kinds.slimeChunk(12345, -4, -1) && !Kinds.slimeChunk(-4242424242L, -6, -5),
                "chunks that are not slime chunks say so");
        // seed 99: the chunks at X and X+16 are slime chunks, the one at X+48 is not
        for (int[] at : new int[][] {{X, 1}, {X + 48, 0}}) {
            for (long seed : at[1] == 1 ? new long[] {99, -1} : new long[] {99}) {
                Sc s = new Sc().notice(STEVE);
                if (seed != -1) s.seeds = Map.of(Sc.WORLD.toString(), seed);
                s.box(STEVE, "stone_bricks", at[0], Y, Z, at[0] + 12, Y, Z + 12);
                s.box(STEVE, "stone_brick_stairs", at[0], Y + 3, Z, at[0] + 12, Y + 3, Z + 4);
                s.fold();
                boolean slime = s.entry(0).get("kind").getAsString().equals("slime_farm");
                t(slime == (at[1] == 1 && seed != -1), "slime farm only in slime chunks with the seed known (" + at[0] + ", seed " + seed + "): " + s.entry(0));
            }
        }
        // a builder files the kind: it wins, at high confidence, and clear goes back to the guess
        Sc s = new Sc().notice(STEVE);
        house(s, STEVE, X, Y, Z);
        s.fold();
        s.req(STEVE, "kind", "slime_farm");
        s.place(STEVE, "oak_planks", X + 5, Y + 5, Z + 4);
        s.fold();
        Fold.Out o = s.fr.out.firstEntry().getValue();
        t(s.idx.sites.firstEntry().getValue().kind.equals("slime_farm") && o.confidence.equals("high"), "a filed kind wins at high confidence: " + o.confidence);
        s.req(STEVE, "kind", "nonsense");
        s.req(ALEX, "kind", "tower");   // not crew: ignored
        s.place(STEVE, "oak_planks", X + 5, Y + 6, Z + 4);
        s.fold();
        t(s.idx.sites.firstEntry().getValue().kind.equals("slime_farm"), "an unknown kind and a non-builder change nothing");
        s.req(STEVE, "kind", "clear");
        s.place(STEVE, "oak_planks", X + 5, Y + 7, Z + 4);
        s.fold();
        s.place(STEVE, "oak_planks", X + 5, Y + 8, Z + 4);
        s.fold();
        t(s.idx.sites.firstEntry().getValue().kind.equals("house"), "clear: back to the Commission's guess: " + s.idx.sites.firstEntry().getValue().kind);
    }

}
