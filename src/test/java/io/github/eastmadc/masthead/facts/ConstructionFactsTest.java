package io.github.eastmadc.masthead.facts;

import io.github.eastmadc.masthead.config.Settings;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/** Gate 1 (the construction facts allow-list), the Blotter and Awards merge, and the facts hand-off. Plain main(). */
public final class ConstructionFactsTest {
    static int ok = 0;

    static void t(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
        ok++;
    }

    static final Set<String> PLAYERS = Set.of("Steve_Builder", "Alex_Miner", "Sam_Redstone", "Hidden_x");
    static final Set<String> HIDDEN = Set.of("hidden_x");

    static final String GOOD = """
            {"version":1,"construction":{"opening":false,"entries":[
              {"tag":"progress","permit":7,"name":"The Great Wall","builders":["Sam_Redstone","Steve_Builder"],"new_builders":["Sam_Redstone"],
               "kind":"wall","confidence":"high","megaproject":true,"day":9,"blocks_today":1818,"blocks_total":5210,"size":"huge","height":"tall",
               "materials":[{"what":"stone brick","blocks":4100},{"what":"spruce","blocks":600}],"milestone_blocks":5000,"angle":"crew","times_in_paper":2}],
             "tracker":[{"permit":7,"name":null,"builders":["Steve_Builder"],"kind":"wall","day":12,"blocks_total":6940,"critic":true,"confidence":"high",
               "size":"huge","blocks_week":3100,"materials":[{"what":"stone brick","blocks":5400}]},
              {"permit":9,"name":null,"builders":["Alex_Miner"],"kind":"storage","day":16,"blocks_total":610,"materials":[{"what":"oak","blocks":1}],"blocks_week":5}]},
             "blotter":[{"what":"broke_build","by":"Alex_Miner","to":"Steve_Builder","n":37,"x":"auto farm"}],
             "drop_mined_at":[["Alex_Miner","Steve_Builder"]],
             "award_reasons":[{"award":"golden_egg","name":"Steve_Builder","score":20,"text":"built a first home"}],
             "ops":{"sites":12}}""";

    public static void main(String[] args) throws Exception {
        ConstructionFacts.Result r = ConstructionFacts.filter(GOOD, PLAYERS, HIDDEN, true, 3);
        JsonObject e = r.construction().getAsJsonArray("entries").get(0).getAsJsonObject();
        t(e.has("milestone_blocks") && e.has("materials") && !e.has("height"), "a wall keeps its fields, loses its height: " + e);
        JsonObject row0 = r.construction().getAsJsonArray("tracker").get(0).getAsJsonObject(), row1 = r.construction().getAsJsonArray("tracker").get(1).getAsJsonObject();
        t(row0.has("materials") && row0.has("blocks_week") && !row1.has("materials") && !row1.has("blocks_week"), "T-CRITIC: extras only on the critic row");
        t(r.blotter().size() == 1 && r.blotter().get(0).x.equals("auto farm") && r.dropMinedAt().contains("Alex_Miner\tSteve_Builder")
                && r.awardReasons().size() == 1, "blotter, drop list and award reasons read");
        t(!ConstructionFacts.filter(GOOD, PLAYERS, HIDDEN, false, 3).construction().has("tracker"), "tracker only on Sundays");

        // T-SCHEMA (mutation): planted position keys and values are dropped; unsorted builders drop the entry
        String planted = GOOD.replace("\"tag\":\"progress\",", "\"tag\":\"progress\",\"x\":-7341,\"dimension\":\"nether\",\"cube\":12,")
                .replace("{\"what\":\"spruce\",\"blocks\":600}", "{\"what\":\"2963 stone\",\"blocks\":600}");
        ConstructionFacts.Result p = ConstructionFacts.filter(planted, PLAYERS, HIDDEN, true, 3);
        String txt = p.construction().toString();
        t(!txt.contains("7341") && !txt.contains("2963") && !txt.contains("nether") && !txt.contains("cube") && p.dropped().containsAll(List.of("x", "dimension", "cube", "materials")),
                "T-SCHEMA: planted keys dropped " + p.dropped());
        t(planted.contains("7341") && planted.contains("2963"), "T-SCHEMA mutation: without Gate 1 the position would reach the facts");
        String unsorted = GOOD.replace("[\"Sam_Redstone\",\"Steve_Builder\"]", "[\"Steve_Builder\",\"Sam_Redstone\"]");
        t(ConstructionFacts.filter(unsorted, PLAYERS, HIDDEN, true, 3).construction().getAsJsonArray("entries").isEmpty(), "unsorted builders: entry dropped");
        t(ConstructionFacts.filter(GOOD.replace("\"Sam_Redstone\",\"Steve_Builder\"]", "\"Hidden_x\",\"Steve_Builder\"]"), PLAYERS, HIDDEN, true, 3)
                .construction().getAsJsonArray("entries").isEmpty(), "a hidden builder drops the entry");
        t(ConstructionFacts.filter(GOOD.replace("\"kind\":\"wall\",\"confidence\"", "\"kind\":\"castle\",\"confidence\""), PLAYERS, HIDDEN, true, 3)
                .construction().getAsJsonArray("entries").isEmpty(), "a kind not on the list drops the entry");
        // existing: no size, height or megaproject; never a new resident; day 1: no size
        String ex = GOOD.replace("\"tag\":\"progress\",", "\"tag\":\"progress\",\"existing\":true,").replace("\"kind\":\"wall\",\"confidence\"", "\"kind\":\"house\",\"confidence\"");
        JsonObject exe = ConstructionFacts.filter(ex, PLAYERS, HIDDEN, true, 3).construction().getAsJsonArray("entries").get(0).getAsJsonObject();
        t(!exe.has("size") && !exe.has("height") && !exe.has("megaproject") && exe.get("existing").getAsBoolean(), "F-34: existing drops size, height, megaproject");
        t(ConstructionFacts.filter(ex.replace("\"tag\":\"progress\"", "\"tag\":\"new_resident\""), PLAYERS, HIDDEN, true, 3).construction() .getAsJsonArray("entries").isEmpty(),
                "existing + new_resident: dropped");
        t(!ConstructionFacts.filter(GOOD.replace("\"day\":9", "\"day\":1"), PLAYERS, HIDDEN, true, 3).construction().getAsJsonArray("entries").get(0)
                .getAsJsonObject().has("size"), "day 1: no size");
        // T-ANGLE (2): an angle naming a field the entry no longer carries becomes "day"
        String sizeAngle = GOOD.replace("\"angle\":\"crew\"", "\"angle\":\"size\"");
        t(ConstructionFacts.filter(sizeAngle.replace("\"day\":9", "\"day\":1"), PLAYERS, HIDDEN, true, 3).construction().getAsJsonArray("entries").get(0)
                .getAsJsonObject().get("angle").getAsString().equals("day"), "T-ANGLE: size angle on a day-1 entry -> day");
        t(ConstructionFacts.filter(sizeAngle.replace("\"tag\":\"progress\",", "\"tag\":\"progress\",\"existing\":true,"), PLAYERS, HIDDEN, true, 3).construction()
                .getAsJsonArray("entries").get(0).getAsJsonObject().get("angle").getAsString().equals("day"), "T-ANGLE: size angle on an existing entry -> day");
        t(ConstructionFacts.filter(sizeAngle, PLAYERS, HIDDEN, true, 3).construction().getAsJsonArray("entries").get(0)
                .getAsJsonObject().get("angle").getAsString().equals("size"), "T-ANGLE: a carried size angle stays");
        // one story per entry: the size words only with the size angle; the materials only as the angle, or when nothing else says what is inside
        String withParts = GOOD.replace("\"milestone_blocks\":5000,", "\"milestone_blocks\":5000,\"parts\":{\"pistons\":4},");
        JsonObject sized = ConstructionFacts.filter(sizeAngle, PLAYERS, HIDDEN, true, 3).construction().getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject parts = ConstructionFacts.filter(withParts, PLAYERS, HIDDEN, true, 3).construction().getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject mats = ConstructionFacts.filter(withParts.replace("\"angle\":\"crew\"", "\"angle\":\"materials\""), PLAYERS, HIDDEN, true, 3).construction()
                .getAsJsonArray("entries").get(0).getAsJsonObject();
        t(!e.has("size") && e.has("materials") && sized.has("size"), "one story: size only with the size angle: " + e);
        t(!parts.has("materials") && parts.has("parts") && mats.has("materials"), "one story: materials only as the angle, or with no parts or features: " + parts);
        // a wall loses its height by an older rule, so the height is checked on a house
        String house = GOOD.replace("\"kind\":\"wall\",\"confidence\"", "\"kind\":\"house\",\"confidence\"");
        JsonObject crewHouse = ConstructionFacts.filter(house, PLAYERS, HIDDEN, true, 3).construction().getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject sizeHouse = ConstructionFacts.filter(house.replace("\"angle\":\"crew\"", "\"angle\":\"size\""), PLAYERS, HIDDEN, true, 3).construction()
                .getAsJsonArray("entries").get(0).getAsJsonObject();
        t(!crewHouse.has("height") && sizeHouse.has("height"), "one story: height only with the size angle: " + crewHouse);
        t(ConstructionFacts.filter(GOOD.replace("\"The Great Wall\"", "\"Spawn Tower\""), PLAYERS, HIDDEN, true, 3).construction().getAsJsonArray("entries")
                .get(0).getAsJsonObject().get("name") == null, "a name failing the name rules is dropped");
        t(ConstructionFacts.filter(GOOD.replace("\"what\":\"spruce\"", "\"what\":\"jungle\""), PLAYERS, HIDDEN, true, 3).dropped().contains("materials"), "a gate word in materials is dropped");
        t(ConstructionFacts.filter(GOOD.replace("\"x\":\"auto farm\"", "\"x\":\"base at spawn\""), PLAYERS, HIDDEN, true, 3).blotter().isEmpty(), "blotter x must be a kind label");
        t(ConstructionFacts.filter(GOOD.replace("built a first home", "built a home: 12"), PLAYERS, HIDDEN, true, 3).awardReasons().isEmpty()
                && ConstructionFacts.filter(GOOD.replace("built a first home", "built a home (spawn)"), PLAYERS, HIDDEN, true, 3).awardReasons().isEmpty(),
                "award text: plain characters only");
        t(ConstructionFacts.filter("{\"construction\":{\"opening\":false,\"entries\":[]}}", PLAYERS, HIDDEN, false, 3).construction() == null, "nothing to print: no block");
        t(ConstructionFacts.read(Path.of("no/such/file.json"), PLAYERS, HIDDEN, false, 3).construction() == null, "a missing file is nothing");

        // Blotter merge: the judged pair loses its mined_at row; Awards: a reason naming a second player is dropped
        BlotterFacts.Entry mined = new BlotterFacts.Entry("mined_at", "Alex_Miner", "Steve_Builder", "", false);
        mined.n = 40; mined.weight = 18;
        BlotterFacts.Entry other = new BlotterFacts.Entry("mined_at", "Sam_Redstone", "Steve_Builder", "", false);
        other.n = 40; other.weight = 18;
        List<BlotterFacts.Entry> m = BlotterFacts.merge(List.of(mined, other), r.blotter(), r.dropMinedAt());
        t(m.size() == 2 && m.stream().anyMatch(x -> x.what.equals("broke_build")) && m.stream().noneMatch(x -> x.by.equals("Alex_Miner") && x.what.equals("mined_at")),
                "D-01: mined_at(Alex, Steve) replaced by broke_build");
        // old blotter files hold guessed-owner lines ("near b's spawn"). They are read without error and never printed.
        Path bd = Files.createDirectories(Files.createTempDirectory("blot").resolve("blotter"));
        StringBuilder old = new StringBuilder();
        for (String k : List.of("took_from_at", "left_in_at", "opened_container_at", "mined_at", "built_at", "slept_in_bed", "took_from"))
            old.append("{\"t\":100,\"k\":\"").append(k).append("\",\"a\":\"Alex_Miner\",\"b\":\"Steve_Builder\",\"x\":\"chest\",\"n\":40}\n");
        Files.writeString(bd.resolve("2026-11-05.jsonl"), old.toString());
        List<BlotterFacts.Entry> rd = BlotterFacts.read(bd.getParent(), java.time.LocalDate.of(2026, 11, 5), 0, 1000, PLAYERS);
        t(rd.size() == 1 && rd.get(0).what.equals("took_from"), "old guessed-owner lines and bed spawns are skipped, a stamped one still reads: "
                + rd.stream().map(x -> x.what).toList());
        // took logs and put the same number back -> not news; only what was really kept stays.
        String net = "{\"t\":100,\"k\":\"took_from\",\"a\":\"Alex_Miner\",\"b\":\"Steve_Builder\",\"x\":\"birch_log\",\"n\":37}\n"
                + "{\"t\":100,\"k\":\"took_from\",\"a\":\"Alex_Miner\",\"b\":\"Steve_Builder\",\"x\":\"carrot\",\"n\":9}\n"
                + "{\"t\":101,\"k\":\"left_in\",\"a\":\"Alex_Miner\",\"b\":\"Steve_Builder\",\"x\":\"birch_log\",\"n\":37}\n";
        Files.writeString(bd.resolve("2026-11-05.jsonl"), net);
        List<BlotterFacts.Entry> nt = BlotterFacts.read(bd.getParent(), java.time.LocalDate.of(2026, 11, 5), 0, 1000, PLAYERS);
        t(nt.size() == 1 && nt.get(0).what.equals("took_from") && nt.get(0).items.equals(java.util.Map.of("carrot", 9L)),
                "took and put back cancel out, kept items stay: " + nt.stream().map(x -> x.what + x.items).toList());
        JsonObject aw = Awards.pick(java.util.Map.of(), emptyEvents(), PLAYERS, List.of(),
                List.of(new Awards.Extra("golden_egg", "Steve_Builder", 20, "built a first home"), new Awards.Extra("golden_egg", "Alex_Miner", 40, "helped Steve_Builder")));
        t(aw.getAsJsonObject("golden_egg").get("winner").getAsString().equals("Steve_Builder"), "T-AWD: a reason naming a second player is dropped");
        // F3: six kills of the same player score like one; the reason still gives the count
        Events six = emptyEvents();
        for (int i = 0; i < 6; i++) six.pvp.add(new String[]{"Alex_Miner", "Steve_Builder"});
        JsonObject pv = Awards.pick(java.util.Map.of(), six, PLAYERS, List.of(), List.of(new Awards.Extra("golden_egg", "Sam_Redstone", 60, "built a first home"),
                new Awards.Extra("headless_chicken", "Sam_Redstone", 25, "knocked down their own wall")));
        t(pv.getAsJsonObject("golden_egg").get("winner").getAsString().equals("Sam_Redstone")
                && pv.getAsJsonObject("headless_chicken").get("winner").getAsString().equals("Sam_Redstone") && pv.toString().contains("in PvP 6 times"),
                "F3: a killer/victim pair scores once: " + pv);

        // P18 / the hand-off: off, or on in a practice run, the facts are byte-identical; on and printing, the block is there
        Path dir = Files.createTempDirectory("cons-facts");
        LocalDate day = LocalDate.of(2026, 11, 3);
        for (int i = 0; i < 2; i++) {
            Path s = Files.createDirectories(dir.resolve("stats").resolve(day.minusDays(1 - i).toString()));
            Files.writeString(s.resolve("usercache.json"), "[{\"uuid\":\"00000000-0000-0000-0000-00000000000a\",\"name\":\"Steve_Builder\"},"
                    + "{\"uuid\":\"00000000-0000-0000-0000-00000000000b\",\"name\":\"Alex_Miner\"}]");
            for (String u : List.of("a", "b"))
                Files.writeString(s.resolve("00000000-0000-0000-0000-00000000000" + u + ".json"), "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":" + (1200 * (60 + 60 * i)) + "}}}");
        }
        Path out = dir.resolve("out.json");
        Files.writeString(out, GOOD.replace("\"Sam_Redstone\",\"Steve_Builder\"]", "\"Steve_Builder\"]").replace("[\"Sam_Redstone\"]", "[]"));
        Settings d = Settings.defaults();
        Settings off = d;
        Settings dry = with(d, true, false), on = with(d, true, true);
        String a = FactsBuilder.GSON.toJson(new FactsBuilder(dir, off).writeTo(null).construction(out).build(day, LocalTime.of(5, 30)));
        String b = FactsBuilder.GSON.toJson(new FactsBuilder(dir, dry).writeTo(null).construction(out).build(day, LocalTime.of(5, 30)));
        JsonObject c = new FactsBuilder(dir, on).writeTo(null).construction(out).build(day, LocalTime.of(5, 30));
        t(a.equals(b) && !a.contains("construction"), "P18: off and practice run give the same facts");
        t(c.has("construction") && FactsBuilder.constructionOwnsBuilders(c) && !FactsBuilder.constructionOwnsBuilders(JsonParser.parseString(a).getAsJsonObject()),
                "on and printing: data.construction present (and the busiest-builders seam says so)");
        t(c.getAsJsonArray("police_blotter").toString().contains("broke_build") && c.getAsJsonObject("awards").toString().contains("built a first home"),
                "blotter row and award reason merged");

        // PS3-3: yesterday's build stories don't come back through recent_editions (the builder may have gone private since)
        Files.createDirectories(dir.resolve("editions"));
        Files.writeString(dir.resolve("editions").resolve(day.minusDays(1) + ".html"), "<h2>Big day</h2><h3>Front page</h3><p>A creeper ate the bakery.</p>"
                + "<p>Steve_Builder filed Permit #3 for Cozy Nook.</p><h3>Egg of the Day</h3><ul><li>Steve_Builder finished their house after 6 days of building.</li></ul>"
                + "<h3>🏗️ Planning &amp; Zoning</h3><p>Cozy Nook keeps growing.</p><h3>Sports</h3><p>Alex_Miner won a duel.</p>");
        // the cooldown reads the whole paper, not the first 250 characters the writer sees
        Files.writeString(dir.resolve("editions").resolve(day.minusDays(2) + ".html"), "<p>" + "Filler line. ".repeat(250) + "The skelly won again.</p>");
        JsonObject built = new FactsBuilder(dir, on).writeTo(null).construction(out).build(day, LocalTime.of(5, 30));
        t(built.has("recent_words") && built.getAsJsonArray("recent_words").toString().contains("skelly"), "QC1: recent_words from whole papers: " + built.get("recent_words"));
        // the writer sees only the start of each earlier paper (headline and deck): front-page wording kept coming back
        t(built.getAsJsonArray("recent_editions").get(0).getAsString().length() == 250, "recent_editions: 250 characters of a long paper: " + built.getAsJsonArray("recent_editions").get(0).getAsString().length());
        String rec = new FactsBuilder(dir, on).writeTo(null).construction(out).build(day, LocalTime.of(5, 30)).getAsJsonArray("recent_editions").toString();
        t(!rec.contains("Permit") && !rec.contains("Cozy Nook") && !rec.contains("days of building") && rec.contains("creeper ate the bakery") && rec.contains("won a duel"),
                "PS3-3: no build stories in recent_editions: " + rec);
        features();
        System.out.println("ConstructionFactsTest: " + ok + " checks passed");
    }

    /** alpha.21: "features" pass only as closed ids with positive counts, and reach the writer as plain words. */
    static void features() {
        String good = "[{\"feature\":\"librarian_trading\",\"n\":17},{\"feature\":\"nether_portal\",\"n\":18},{\"feature\":\"beds\",\"n\":3}]";
        JsonObject e = feat(good);
        t(e.get("features").toString().equals("[{\"n\":17,\"of\":\"lecterns\",\"for\":\"librarian trading\"},{\"n\":18,\"of\":\"obsidian blocks\",\"for\":\"nether portal\"},"
                + "{\"n\":3,\"of\":\"beds\"}]"), "features in words: " + e.get("features"));
        for (String bad : List.of("[{\"feature\":\"diamond_blocks\",\"n\":4}]", "[{\"feature\":\"beds\",\"n\":0}]", "[{\"feature\":\"beds\",\"n\":-3}]",
                "[{\"feature\":\"beds\",\"n\":2.5}]", "[{\"feature\":\"beds\",\"n\":\"3\"}]", "[{\"feature\":\"beds\",\"n\":3,\"x\":-7341}]", "[{\"feature\":\"beds\"}]",
                "{\"beds\":3}", "[]", "[{\"feature\":\"Beds\",\"n\":3}]", "[{\"feature\":\"lecterns\",\"n\":3}]",
                "[" + "{\"feature\":\"beds\",\"n\":3},".repeat(8) + "{\"feature\":\"beds\",\"n\":3}]")) {
            JsonObject x = feat(bad);
            t(x != null && !x.has("features") && !x.toString().contains("7341"), "bad features dropped, entry kept: " + bad);
        }
    }

    private static JsonObject feat(String features) {
        String json = "{\"construction\":{\"opening\":false,\"entries\":[{\"tag\":\"permit\",\"permit\":3,\"name\":null,\"builders\":[\"Steve_Builder\"],"
                + "\"kind\":\"trading_hall\",\"blocks_total\":412,\"features\":" + features + "}]}}";
        ConstructionFacts.Result r = ConstructionFacts.filter(json, PLAYERS, HIDDEN, false, 3);
        return r.construction() == null || r.construction().getAsJsonArray("entries").isEmpty() ? null : r.construction().getAsJsonArray("entries").get(0).getAsJsonObject();
    }

    static Settings with(Settings d, boolean feature, boolean print) {
        var f = d.features();
        var c = d.construction();
        return new Settings(d.paper(), d.tone(), d.owner(), d.privacy(), d.schedule(), d.writer(), d.web(),
                new Settings.Features(f.mailroom(), f.blotter(), f.readership(), f.joinMessage(), f.joinDelaySeconds(), f.healthExtraFile(), f.buildingReport(), feature), d.alerts(),
                new Settings.Construction(c.title(), print, c.defaultLevel(), c.names(), c.maxEntries(), c.minBlocks(), c.crossDemolition(), c.bigDemolitionBlocks(),
                        c.bigDemolitionPercent(), c.forgetAfterDays(), c.ignoreWorlds(), c.maxCellsPerMinute(), c.optedIn()));
    }

    static Events emptyEvents() throws Exception {
        return Events.read(Files.createTempDirectory("ev"), LocalDate.of(2026, 11, 3), 0, 1, Set.of(), Set.of());
    }
}
