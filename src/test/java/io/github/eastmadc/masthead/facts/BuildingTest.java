package io.github.eastmadc.masthead.facts;

import io.github.eastmadc.masthead.config.Settings;
import io.github.eastmadc.masthead.config.SettingsLoader;
import io.github.eastmadc.masthead.edition.FactCheck;
import io.github.eastmadc.masthead.edition.Fallback;
import io.github.eastmadc.masthead.edition.StylePrompt;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The Building Report (features.building-report): one check per rule. Plain main(); exit 1 on any failure. */
public final class BuildingTest {
    static int failures = 0;
    static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    static final LocalTime AT = LocalTime.of(5, 30);
    /** What the server would say is a block (tools, food and buckets are not). */
    static final Set<String> BLOCKS = Set.of("spruce_planks", "spruce_stairs", "stone_bricks", "stone_brick_wall", "glass", "white_stained_glass_pane",
            "netherrack", "sand", "jungle_planks", "cobbled_deepslate", "chest", "diamond_block", "torch", "oak_planks", "dirt", "cobblestone");

    public static void main(String[] args) throws Exception {
        Path dir = args.length > 0 ? Files.createDirectories(Path.of(args[0])) : Files.createTempDirectory("building");
        // yesterday's snapshot, then today's
        Map<String, String> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        // Alice: 900 spruce (planks+stairs), 300 stone brick (+wall), 100 glass (+pane), 410 netherrack, 50 sand, 2 tools/food
        before.put("a", used("spruce_planks", 100, "diamond_pickaxe", 10, "bread", 5));
        after.put("a", used("spruce_planks", 700, "spruce_stairs", 300, "stone_bricks", 250, "stone_brick_wall", 50, "glass", 60,
                "white_stained_glass_pane", 40, "netherrack", 410, "sand", 50, "diamond_pickaxe", 900, "bread", 80, "water_bucket", 30));
        // Bob: exactly 64 blocks, all of a place hint (jungle wood) and a stash hint (chest): listed, nothing named
        before.put("b", used("jungle_planks", 10));
        after.put("b", used("jungle_planks", 70, "chest", 4));
        // Cara: 63 blocks: counts in the total, not listed
        after.put("c", used("oak_planks", 63));
        // Dan: stats reset (fewer than yesterday): never negative; 100 new dirt still counts
        before.put("d", used("cobblestone", 5000));
        after.put("d", used("cobblestone", 10, "dirt", 100));
        // Hidden: placed lots, but privacy.hide-players
        after.put("h", used("cobblestone", 9000));
        // Eve: 5% rule: 1000 spruce, 49 glass (under 5%), 61 torches (over 5%), 40 dirt (under)
        after.put("e", used("spruce_planks", 1000, "glass", 49, "torch", 61, "dirt", 40));
        world(dir, before, after);

        JsonObject on = facts(dir, true, List.of("Hidden_h"));
        JsonObject b = on.getAsJsonObject("building");
        check("report present when the flag is on", b != null);
        String all = FactsBuilder.GSON.toJson(b);
        System.out.println(all);
        check("hidden player is not in the report", !all.contains("Hidden_h"));
        check("server total: every listed and unlisted player, hidden left out, resets never negative",
                b.get("blocks_today").getAsLong() == (900 + 300 + 100 + 410 + 50) + (60 + 4) + 63 + 100 + (1000 + 49 + 61 + 40));
        List<String> names = new ArrayList<>();
        for (JsonElement e : b.getAsJsonArray("builders")) names.add(e.getAsJsonObject().get("name").getAsString());
        check("listed: 64 and over only, most first (Cara with 63 is not listed) " + names, names.equals(List.of("Alice_a", "Eve_e", "Dan_d", "Bob_b")));
        JsonObject alice = b.getAsJsonArray("builders").get(0).getAsJsonObject();
        check("only blocks count: tools, food and buckets do not", alice.get("blocks_today").getAsLong() == 1760);
        check("top 3 materials, families merged, place hints never named",
                alice.get("materials").toString().equals("[{\"what\":\"spruce\",\"blocks\":900},{\"what\":\"stone brick\",\"blocks\":300},{\"what\":\"glass\",\"blocks\":100}]"));
        JsonObject eve = b.getAsJsonArray("builders").get(1).getAsJsonObject();
        check("a material under 5% of a player's blocks is not named",
                eve.get("materials").toString().equals("[{\"what\":\"spruce\",\"blocks\":1000},{\"what\":\"torch\",\"blocks\":61}]"));
        JsonObject bob = b.getAsJsonArray("builders").get(3).getAsJsonObject();
        check("jungle wood and chests are counted but never named", bob.get("blocks_today").getAsLong() == 64 && bob.getAsJsonArray("materials").isEmpty());
        check("dirt after a stats reset: 100, not negative", b.getAsJsonArray("builders").get(2).getAsJsonObject().get("blocks_today").getAsLong() == 100);
        check("leaderboard: top 3 as [name, blocks]", b.get("leaders").toString().equals("[[\"Alice_a\",1760],[\"Eve_e\",1150],[\"Dan_d\",100]]"));
        check("only the known keys (no place, no time)", keys(b, "", new ArrayList<>()).stream().allMatch(k -> k.matches(
                "blocks_today|builders|leaders|builders\\[]\\.(name|blocks_today|materials)|builders\\[]\\.materials\\[]\\.(what|blocks)")));
        List<String> free = SmokeTest.freeText(on, "", new ArrayList<>());
        check("free text only in material words " + free, free.stream().allMatch(f -> f.startsWith("building.builders[].materials[].what=")
                && Building.NAMED.contains(f.substring(f.indexOf('=') + 1))));

        // byte-identical when off: the same facts with the key taken out, and data/DATE.json too
        JsonObject off = facts(dir, false, List.of("Hidden_h"));
        String offDisk = Files.readString(dir.resolve("data").resolve(DAY + ".json"));
        check("flag off: no building key", !off.has("building"));
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix"))
            check("data/ is owner-only from the first facts file",
                    java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("data"))).equals("rwx------"));
        JsonObject stripped = on.deepCopy();
        stripped.remove("building");
        check("flag off: facts byte-identical to the facts without the report", FactsBuilder.GSON.toJson(stripped).equals(FactsBuilder.GSON.toJson(off)));
        check("flag off: data/DATE.json has no report", !offDisk.contains("\"building\"") && offDisk.equals(FactsBuilder.GSON.toJson(FactsBuilder.redacted(off))));
        check("no block list from the server: no report", !new FactsBuilder(dir, settings(true, List.of())).writeTo(null).build(DAY, AT).has("building"));

        // nobody over the line: no report
        Path quiet = Files.createTempDirectory("building-quiet");
        world(quiet, Map.of("a", used("oak_planks", 10)), Map.of("a", used("oak_planks", 73)));
        check("nobody at 64: no report", !facts(quiet, true, List.of()).has("building"));
        // first edition (no snapshot to compare with): no report
        Path first = Files.createTempDirectory("building-first");
        world(first, null, Map.of("a", used("oak_planks", 5000)));
        check("first edition: no report", !facts(first, true, List.of()).has("building"));
        // a renamed player (same UUID, new name) counts what they placed since the last paper, not their lifetime total; a player
        // missing from the last snapshot (name cache evicted, just un-hidden) with more play time than the gap is not new and is
        // skipped; a real newcomer counts in full
        Path renamed = Files.createTempDirectory("building-renamed");
        for (int i = 0; i < 2; i++) {
            Path s = Files.createDirectories(renamed.resolve("stats").resolve(DAY.minusDays(1 - i).toString()));
            Files.writeString(s.resolve("usercache.json"), "[{\"uuid\":\"" + uuid("a") + "\",\"name\":\"" + (i == 0 ? "Alex" : "Alexa") + "\"}"
                    + (i == 0 ? "" : ",{\"uuid\":\"" + uuid("b") + "\",\"name\":\"Back_b\"},{\"uuid\":\"" + uuid("c") + "\",\"name\":\"Newbie_c\"}") + "]");
            Files.writeString(s.resolve(uuid("a") + ".json"), "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":" + 1200 * (60_000 + 120 * i) + "},"
                    + "\"minecraft:used\":{" + used("oak_planks", 5000 + 100 * i) + "}}}");
            Files.writeString(s.resolve(uuid("b") + ".json"), "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":" + 1200 * 50_000 + "},"
                    + "\"minecraft:used\":{" + used("oak_planks", 3000) + "}}}");
            if (i == 1) Files.writeString(s.resolve(uuid("c") + ".json"), "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":" + 1200 * 90 + "},"
                    + "\"minecraft:used\":{" + used("oak_planks", 200) + "}}}");
        }
        JsonObject rb = facts(renamed, true, List.of()).getAsJsonObject("building");
        check("renamed player: 100 today, not the lifetime 5,100; returning player skipped; newcomer 200: " + rb, rb != null && rb.get("blocks_today").getAsLong() == 300
                && rb.get("leaders").toString().equals("[[\"Newbie_c\",200],[\"Alexa\",100]]"));

        // M2: the day's and the week's numbers match the older snapshot by UUID first, then by name. a: Old_Vet is New_Vet today.
        // c: back today, the name had fallen out of yesterday's name cache. d, e: the same name under another UUID.
        Path vet = Files.createTempDirectory("building-vet");
        LocalDate sun = LocalDate.of(2026, 11, 8);   // a Sunday: the weekly rows too
        java.util.function.BiFunction<String, String, String> uc = (u, n) -> "{\"uuid\":\"" + uuid(u) + "\",\"name\":\"" + n + "\"}";
        for (int i = 0; i < 2; i++) {
            Path s = Files.createDirectories(vet.resolve("stats").resolve(sun.minusDays(1 - i).toString()));
            Files.writeString(s.resolve("usercache.json"), "[" + uc.apply("a", i == 0 ? "Old_Vet" : "New_Vet") + "," + uc.apply("b", "Bob_22") + ","
                    + (i == 0 ? uc.apply("d", "Dan_d") : uc.apply("c", "Back_c") + "," + uc.apply("e", "Dan_d")) + "]");
            long[][] rows = {{60_000 + 30 * i, 48_000 + 5 * i, 300}, {600 + 60 * i, 100 + 60 * i, 3 + i}, {50_000 + 10 * i, 7_000 + 2 * i, 90}, {900 + 60 * i, 500 + 20 * i, 9}};
            String[] ids = {"a", "b", "c", i == 0 ? "d" : "e"};
            for (int k = 0; k < 4; k++)
                Files.writeString(s.resolve(uuid(ids[k]) + ".json"), "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":" + 1200 * rows[k][0]
                        + ",\"minecraft:mob_kills\":" + rows[k][1] + ",\"minecraft:deaths\":" + rows[k][2] + "},\"minecraft:killed\":{\"minecraft:chicken\":" + rows[k][1] + "}}}");
        }
        long noon = java.time.LocalDateTime.of(sun.minusDays(1), LocalTime.NOON).toEpochSecond(java.time.ZoneOffset.UTC);
        StringBuilder joins = new StringBuilder();
        for (String n : List.of("New_Vet", "Bob_22", "Back_c", "Dan_d")) joins.append("{\"t\":" + noon + ",\"k\":\"join\",\"p\":\"" + n + "\"}\n");
        Files.writeString(Files.createDirectories(vet.resolve("events")).resolve(sun.minusDays(1) + ".jsonl"), joins.toString());
        Settings v0 = settings(false, List.of());   // P4: the animal watch is in the facts only with its tone bit
        Settings.Tone vt = v0.tone();
        JsonObject vf = new FactsBuilder(vet, new Settings(v0.paper(), new Settings.Tone(vt.voice(), vt.spice(), vt.language(), vt.slang(), vt.audience(), vt.length(),
                List.of("animal-watch"), vt.lexiconAdd(), vt.lexiconRemove()), v0.owner(), v0.privacy(), v0.schedule(), v0.writer(), v0.web(), v0.features(), v0.alerts())).build(sun, AT);
        for (String part : List.of("today", "week")) {
            JsonObject t = vf.getAsJsonObject(part);
            check("renamed player, " + part + ": 5 mob kills in 30 minutes, not the season's 48,005: " + t.get("New_Vet"),
                    t.has("New_Vet") && t.getAsJsonObject("New_Vet").get("mob_kills").getAsLong() == 5 && t.getAsJsonObject("New_Vet").get("play_min").getAsDouble() == 30);
            check("returning player whose name left the name cache, " + part + ": 2 mob kills, not 7,002: " + t.get("Back_c"),
                    t.has("Back_c") && t.getAsJsonObject("Back_c").get("mob_kills").getAsLong() == 2);
            check("same name under another UUID still matches by name, " + part + ": " + t.get("Dan_d"), t.has("Dan_d") && t.getAsJsonObject("Dan_d").get("mob_kills").getAsLong() == 20);
        }
        check("a renamed or returning player is not a new player: " + vf.get("new_players"), vf.getAsJsonArray("new_players").isEmpty());
        check("no award for a season's numbers: " + vf.get("awards"), !vf.get("awards").toString().contains("300") && !vf.get("awards").toString().contains("48"));
        check("the animal watch counts the day, not the season: " + vf.get("chicken_watch"), vf.getAsJsonObject("chicken_watch").getAsJsonObject("today").get("chickens_killed").getAsLong() == 5 + 60 + 2 + 20);

        // family words
        Map<String, String> fam = new LinkedHashMap<>();
        fam.put("stripped_dark_oak_log", "dark oak"); fam.put("oak_fence_gate", "oak"); fam.put("smooth_stone_slab", "stone");
        fam.put("waxed_weathered_cut_copper_stairs", "copper"); fam.put("light_gray_concrete_powder", "concrete"); fam.put("purple_glazed_terracotta", "terracotta");
        fam.put("bricks", "brick"); fam.put("sticky_piston", "piston"); fam.put("hay_block", "hay"); fam.put("chiseled_stone_bricks", "stone brick");
        for (String hidden : List.of("pale_oak_planks", "jungle_log", "cherry_stairs", "bamboo_block", "crimson_planks", "red_sandstone", "red_nether_bricks",
                "blue_ice", "sand", "netherrack", "end_stone_bricks", "purpur_block", "prismarine", "cobbled_deepslate", "polished_blackstone_bricks",
                "mossy_cobblestone", "moss_carpet", "grass_block", "obsidian", "chest", "barrel", "hopper", "shulker_box", "diamond_block", "iron_block",
                "gold_block", "tnt", "soul_torch", "red_mushroom_block", "tinted_glass", "poplar_planks", "oak_sapling", "mud_bricks"))
            fam.put(hidden, null);
        for (Map.Entry<String, String> e : fam.entrySet())
            check("family " + e.getKey() + " -> " + e.getValue(), java.util.Objects.equals(Building.family(e.getKey()), e.getValue()));
        check("every named material is plain words (no digits: FactCheck would learn the number)",
                Building.NAMED.stream().allMatch(w -> w.matches("[a-z ]{2,30}")));

        // prompt: only with the flag, and otherwise unchanged
        Settings son = settings(true, List.of()), soff = settings(false, List.of());
        String pOn = StylePrompt.build(son), pOff = StylePrompt.build(soff);
        check("prompt: flag off has no Building Report", !pOff.contains("Building Report") && !pOff.contains("\"building\""));
        check("prompt: flag on lists the section and its rule", pOn.contains("\"Achievements unlocked\" (whenever \"advancements\" has entries), \"Building Report\" (only if \"building\" is in the data), ")
                && pOn.contains("- \"Building Report\" (only if \"building\" is in the data): one short line, ")
                && pOn.contains("never judge the build itself (nobody has seen it)") && pOn.contains("never a teacher's grade"));
        String undone = pOn.replace("\"Building Report\" (only if \"building\" is in the data), ", "").replaceAll("(?m)^- \"Building Report\".*\n", "");
        check("prompt: flag on is flag off plus the two pieces only", undone.equals(pOff));
        check("prompt: never where or when", pOn.contains("never say or guess what they built, where, or when"));

        // plain edition
        String plainOn = Fallback.edition(son, on), plainOff = Fallback.edition(soff, off);
        check("plain edition: section with total, names, numbers and materials", plainOn.contains("<h3>Building Report</h3><p>Blocks placed on the server: 3,137.</p><ul>"
                + "<li>Alice_a: 1,760 blocks (spruce, stone brick, glass)</li><li>Eve_e: 1,150 blocks (spruce, torch)</li><li>Dan_d: 100 blocks (dirt)</li><li>Bob_b: 64 blocks</li></ul>"));
        check("plain edition: nothing when the flag is off", !plainOff.contains("Building Report"));
        check("plain edition: same as before apart from the section", plainOn.replaceAll("<h3>Building Report</h3>.*?</ul>", "").equals(Fallback.edition(son, off)));

        // fact check: every number the section uses is in the data; an invented one is caught
        String draft = "<h3>Building Report</h3><p>The town put down 3,137 blocks.</p><ul><li>Alice_a: 1,760 blocks of spruce (900), stone brick (300), glass (100)</li>"
                + "<li>Eve_e: 1,150</li></ul>";
        check("fact check: the report's numbers pass", FactCheck.problems(on, draft, DAY, son).isEmpty());
        check("fact check: an invented number is caught", FactCheck.problems(on, draft.replace("1,760", "1,763"), DAY, son).equals(List.of("1763")));

        // EC-3 / WP-1: while construction news prints, it owns the builders: no Building Report (no double list, no private builders).
        // Automated review 18:15: in a practice run or paused, the report lists only players construction news may name.
        Files.createDirectories(dir.resolve("construction"));
        Files.writeString(dir.resolve("construction").resolve("players.json"), "{\"version\":1,\"next_permit\":1,\"players\":{"
                + "\"" + uuid("a") + "\":{\"name\":\"Alice_a\",\"level\":\"public\",\"noticed\":\"2026-10-01\"},"
                + "\"" + uuid("b") + "\":{\"noticed\":\"2026-10-01\"},"
                + "\"" + uuid("e") + "\":{\"name\":\"Eve_e\",\"noticed\":\"2026-10-01\"}}}");
        Settings d0 = Settings.defaults();
        for (boolean print : new boolean[]{true, false}) {
            Settings.Construction c0 = Settings.Construction.defaults();   // default-level private
            Settings both = new Settings(d0.paper(), d0.tone(), d0.owner(), new Settings.Privacy(List.of("Hidden_h"), List.of()), new Settings.Schedule(AT, "UTC"), d0.writer(),
                    d0.web(), new Settings.Features(true, true, false, true, 3, "", true, true), d0.alerts(),
                    new Settings.Construction(c0.title(), print, c0.defaultLevel(), c0.names(), c0.maxEntries(), c0.minBlocks(), c0.crossDemolition(),
                            c0.bigDemolitionBlocks(), c0.bigDemolitionPercent(), c0.forgetAfterDays(), c0.ignoreWorlds(), c0.maxCellsPerMinute(), List.of("bob_b")));
            JsonObject f = new FactsBuilder(dir, both).blockItems(BLOCKS).build(DAY, AT);
            check("construction " + (print ? "printing: no Building Report" : "practice run: Building Report stays"), f.has("building") != print && both.buildingReportPrints() != print);
            if (print) continue;
            String rep = FactsBuilder.GSON.toJson(f.get("building"));
            check("practice run: a public, noticed player is listed", rep.contains("Alice_a"));
            check("practice run: an opted-in player (by name, before the fold applies it) is listed", rep.contains("Bob_b"));
            check("practice run: a private player (default-level) is not listed", !rep.contains("Eve_e"));
            check("practice run: a player who never had the notice is not listed", !rep.contains("Dan_d"));
            check("practice run: the server total still counts everyone", f.getAsJsonObject("building").get("blocks_today").getAsLong() == b.get("blocks_today").getAsLong());
        }

        // config: the key is read, the default is on, and the old 6-value form is off
        check("config: features.building-report false is read", !SettingsLoader.load(YamlConfiguration.loadConfiguration(
                new StringReader("features:\n  building-report: false\n")), m -> { }).features().buildingReport());
        check("config: missing key means on", SettingsLoader.load(new YamlConfiguration(), m -> { }).features().buildingReport());
        check("config: shipped config.yml has it on", YamlConfiguration.loadConfiguration(new StringReader(
                Files.readString(Path.of("src/main/resources/config.yml")))).getBoolean("features.building-report", false));
        check("old 6-value Features: report off (golden facts unchanged)", !new Settings.Features(true, true, true, true, 3, "").buildingReport());

        System.out.println(failures == 0 ? "building: all checks passed" : "building: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static Settings settings(boolean report, List<String> hidden) {
        Settings d = Settings.defaults();
        return new Settings(d.paper(), d.tone(), d.owner(), new Settings.Privacy(hidden, List.of()), new Settings.Schedule(AT, "UTC"), d.writer(), d.web(),
                new Settings.Features(true, true, false, true, 3, "", report), d.alerts());
    }

    static JsonObject facts(Path dir, boolean report, List<String> hidden) throws Exception {
        return new FactsBuilder(dir, settings(report, hidden)).blockItems(BLOCKS).build(DAY, AT);
    }

    /** stats/(DAY-1) from before (null = no snapshot) and stats/DAY from after; uuid suffix u, name "<Name>_u". */
    static void world(Path dir, Map<String, String> before, Map<String, String> after) throws Exception {
        Map<String, String> names = Map.of("a", "Alice_a", "b", "Bob_b", "c", "Cara_c", "d", "Dan_d", "e", "Eve_e", "h", "Hidden_h");
        StringBuilder cache = new StringBuilder("[");
        names.forEach((u, n) -> cache.append(cache.length() > 1 ? "," : "").append("{\"uuid\":\"").append(uuid(u)).append("\",\"name\":\"").append(n).append("\"}"));
        cache.append(']');
        for (int i = 0; i < 2; i++) {
            Map<String, String> m = i == 0 ? before : after;
            if (m == null) continue;
            Path s = Files.createDirectories(dir.resolve("stats").resolve(DAY.minusDays(1 - i).toString()));
            Files.writeString(s.resolve("usercache.json"), cache.toString());
            for (Map.Entry<String, String> e : m.entrySet())
                Files.writeString(s.resolve(uuid(e.getKey()) + ".json"), "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":" + (1200 * (60 + 120 * i)) + "},"
                        + "\"minecraft:used\":{" + e.getValue() + "}}}");
        }
    }

    static String uuid(String u) {
        return "00000000-0000-0000-0000-00000000000" + u;
    }

    /** "k1", n1, "k2", n2 ... -> the inside of a minecraft:used object. */
    static String used(Object... kv) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) b.append(i > 0 ? "," : "").append("\"minecraft:").append(kv[i]).append("\":").append(kv[i + 1]);
        return b.toString();
    }

    static List<String> keys(JsonElement e, String path, List<String> out) {
        if (e.isJsonObject()) for (var en : e.getAsJsonObject().entrySet()) {
            String p = path.isEmpty() ? en.getKey() : path + "." + en.getKey();
            out.add(p);
            keys(en.getValue(), p, out);
        }
        else if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) if (x.isJsonObject()) keys(x, path + "[]", out);
        return out;
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
        if (!ok) failures++;
    }
}
