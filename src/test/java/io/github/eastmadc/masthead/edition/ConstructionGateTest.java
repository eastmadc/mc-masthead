package io.github.eastmadc.masthead.edition;

import io.github.eastmadc.masthead.config.Settings;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** The construction prompt text and draft gates (spec §6-§8, G-01...G-22, T-PROMPT-*). Plain main(). Names are synthetic. */
public final class ConstructionGateTest {
    static int ok = 0;

    static void t(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
        ok++;
    }

    static Settings on(Settings d, boolean feature, boolean print) {
        var f = d.features();
        var c = d.construction();
        return new Settings(d.paper(), d.tone(), d.owner(), d.privacy(), d.schedule(), d.writer(), d.web(),
                new Settings.Features(f.mailroom(), f.blotter(), f.readership(), f.joinMessage(), f.joinDelaySeconds(), f.healthExtraFile(), f.buildingReport(), feature), d.alerts(),
                new Settings.Construction(c.title(), print, c.defaultLevel(), c.names(), c.maxEntries(), c.minBlocks(), c.crossDemolition(), c.bigDemolitionBlocks(),
                        c.bigDemolitionPercent(), c.forgetAfterDays(), c.ignoreWorlds(), c.maxCellsPerMinute(), c.optedIn()));
    }

    static Settings detail(Settings d, String level) {
        var c = d.construction();
        return new Settings(d.paper(), d.tone(), d.owner(), d.privacy(), d.schedule(), d.writer(), d.web(), d.features(), d.alerts(),
                new Settings.Construction(c.title(), c.print(), c.defaultLevel(), c.names(), c.maxEntries(), c.minBlocks(), c.crossDemolition(), c.bigDemolitionBlocks(),
                        c.bigDemolitionPercent(), c.forgetAfterDays(), c.ignoreWorlds(), c.maxCellsPerMinute(), c.optedIn(), level));
    }

    /** Today's facts with every allowed phrase in them: three entries' kinds and features. */
    static final JsonObject MANY = JsonParser.parseString("{\"season_totals\":{\"Steve_Builder\":{}},\"construction\":{\"opening\":false,\"entries\":["
            + "{\"tag\":\"permit\",\"permit\":3,\"builders\":[\"Steve_Builder\"],\"kind\":\"nether portal\",\"features\":[{\"n\":18,\"of\":\"obsidian blocks\",\"for\":\"nether portal\"}]},"
            + "{\"tag\":\"permit\",\"permit\":4,\"builders\":[\"Steve_Builder\"],\"kind\":\"mushroom farm\",\"features\":[{\"n\":1,\"of\":\"cave spider spawner\"},{\"n\":12,\"of\":\"nether wart\"}]},"
            + "{\"tag\":\"permit\",\"permit\":5,\"builders\":[\"Steve_Builder\"],\"kind\":\"nether wart farm\"}]}}").getAsJsonObject();

    static JsonObject data(String kind, String... builders) {
        StringBuilder b = new StringBuilder();
        for (String x : builders) b.append(b.length() == 0 ? "" : ",").append('"').append(x).append('"');
        return JsonParser.parseString("{\"season_totals\":{\"Steve_Builder\":{},\"Alex_Miner\":{},\"Sam_Redstone\":{},\"North_Star\":{},\"x420\":{},\"Z1000\":{},\"Y2006\":{}},"
                + "\"advancements\":[[\"Alex_Miner\",\"Hero of the Village\"]],"
                + "\"construction\":{\"opening\":false,\"entries\":[{\"tag\":\"permit\",\"permit\":3,\"name\":null,\"builders\":[" + b + "],\"kind\":\"" + kind + "\"}]}}")
                .getAsJsonObject();
    }

    static String draft(String section, String rest) {
        return "<h2>Big day</h2><p><em>Deck.</em></p><h3>Front page</h3><p>Plenty happened.</p><h3>🏗️ Planning &amp; Zoning</h3><p>" + section + "</p>"
                + "<h3>Sports</h3><p>" + rest + "</p><h3>Weather</h3><p>Clear skies.</p>";
    }

    public static void main(String[] args) throws Exception {
        Settings chick = on(TestSettings.coop(), true, true), plain = on(TestSettings.other(), true, true);
        // T-PROMPT-OFF: off, or a practice run, is today's prompt byte for byte
        String base = StylePrompt.build(TestSettings.coop());
        t(base.equals(StylePrompt.build(on(TestSettings.coop(), false, true))) && base.equals(StylePrompt.build(on(TestSettings.coop(), true, false))),
                "T-PROMPT-OFF: prompt unchanged when off or practising");
        // T21 A1: "masthead construction pause" is print: false byte for byte, and a restart (a fresh read of state.yml) keeps it
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("pause");
            io.github.eastmadc.masthead.pipeline.State.set(dir, io.github.eastmadc.masthead.construction.Construction.PAUSE_KEY, true);
            Settings paused = io.github.eastmadc.masthead.Masthead.effective(chick, dir);
            JsonObject d = data("house", "Steve_Builder");
            t(StylePrompt.build(paused).equals(base) && StylePrompt.prompt(paused, d).equals(StylePrompt.prompt(on(TestSettings.coop(), true, false), d))
                    && !ConstructionGate.on(paused), "pause: the prompt is the print: false prompt, byte for byte");
            t(!io.github.eastmadc.masthead.Masthead.effective(chick, dir).construction().print(), "pause: still paused after a restart");
            io.github.eastmadc.masthead.pipeline.State.set(dir, io.github.eastmadc.masthead.construction.Construction.PAUSE_KEY, null);
            t(StylePrompt.build(io.github.eastmadc.masthead.Masthead.effective(chick, dir)).equals(StylePrompt.build(chick)), "resume: back to printing");
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        String p = StylePrompt.build(chick);
        // T-PROMPT-WORDS: every gate word is in the prompt; the section rules trip none
        List<String> all = new ArrayList<>(ConstructionWords.gateWords());
        all.addAll(ConstructionWords.SECTION_ONLY);
        all.addAll(ConstructionWords.GATE_C);
        for (String w : all) t(p.contains(w), "T-PROMPT-WORDS: prompt names " + w);
        t(p.contains("the End (the dimension)"), "the End is named");
        Pattern any = ConstructionWords.regex(all, false), caseW = ConstructionWords.regex(ConstructionWords.CASE_SENSITIVE, true);
        String rules = StylePrompt.constructionRules(chick) + StylePrompt.constructionRules(plain) + StylePrompt.CONSTRUCTION_DATA_NOTE;
        var m = any.matcher(rules);
        t(!m.find() && !caseW.matcher(rules).find(), "T-PROMPT-WORDS: the section rules trip no gate word" + (m.find(0) ? " (" + m.group() + ")" : ""));
        t(p.contains("Never call a build a \"structure\"") && p.contains("it is already in words (\"auto farm\"") && !p.contains("auto_farm"),
                "the writer: never \"structure\", and the kind arrives as display words");
        t(p.contains("the Coop Planning Commission") && StylePrompt.build(plain).contains("the Town Planning Commission"), "the Commission's name follows the theme");
        t(p.contains("\"Planning & Zoning\" (only if \"construction\" is in the data)") && p.contains("broke_build: a broke blocks of b's build")
                && p.contains("Builds are never compared or ranked, at any spice."), "FORMAT, BLOTTER MEANINGS and compare lines");
        // T-PROMPT-GATEC
        t(p.contains("never join three numbers with slashes") && base.contains("never join three numbers with slashes") && ConstructionGate.REWRITE_NOTE.contains("A \"coordinates\" problem with no quoted word"),
                "T-PROMPT-GATEC: the number shapes are named exactly when Gate C runs");

        // G-01...G-08: in the section
        String[][] g = {{"a tower at -6512, 3104", "coordinates"}, {"x 45 z -80", "coordinates"}, {"a cabin next to Steve_Builder's base", "next to"},
                {"perched on a cliff overlooking the ocean", "cliff"}, {"the tallest tower on the server", "tallest"}, {"an abandoned shack", "abandoned"},
                {"an ugly dirt hut", "ugly"}, {"a Nether hideout", "nether"}};
        for (String[] x : g) {
            String pr = String.join(" | ", FactCheck.problems(data("house", "Steve_Builder"), draft(x[0], "A quiet day."), java.time.LocalDate.of(2026, 11, 3), chick));
            t(pr.contains(x[1]), "G-0x: " + x[0] + " -> " + pr);
        }
        // G-09: advancements and other paragraphs keep their words; the section does not
        t(ConstructionGate.problems(data("house", "Steve_Builder"), draft("A fine permit.", "The village mourns a chicken."), chick).isEmpty(), "G-09: village outside construction copy");
        t(ConstructionGate.problems(data("house", "Steve_Builder"), draft("A house by the village.", "x"), chick).toString().contains("village"), "G-09: village in the section");
        // PS3-1: biome and terrain words (forest, plains, snow...) are place words too
        t(ConstructionGate.problems(data("house", "Alex"), draft("Alex filed for a spruce house out in the forest, on the edge of the plains by the snow.", "x"), chick)
                .toString().contains("construction section (\"forest\", \"plains\", \"snow\")"), "PS3-1: forest, plains, snow in the section");
        t(!ConstructionGate.problems(data("house", "Alex"), draft("A fine permit.", "Alex built a house deep in the forest."), chick).isEmpty(), "PS3-1: forest in a front-page build line");
        // G-10: Gate C, attack and benign strings, with names masked first
        for (String a : List.of("-6512, 3104", "chunk -407,194", "at 12, -40", "(120, -40)", "-6512,3104", "base:-300,400", "x-300, 400", "300/64/-200", "-6512 3104",
                "x45 z-80", "X 300, Z 400", "12 64 -40", "120, 70, -40", "x: 45", "Y=-40", "Y 64"))
            t(!ConstructionGate.coordinates(a).isEmpty(), "G-10 attack: " + a);
        for (String b : List.of("Steve went 4-1, 3 kills", "won 3-2, 4-1 in the rematch", "went 10-0, 13 kills", "the 3-2, 4-1, 5-0 week", "Paper build 26.3-242, 26.3-243",
                "Player placed 412 blocks", "won 3-2", "scored 5 - 3", "the 1-2 punch", "11/05", "1/2 of the wall", "Day 12, 5,210 blocks", "lost 41 blocks to a creeper, 6 removed"))
            t(ConstructionGate.coordinates(b).isEmpty(), "G-10 benign: " + b);
        List<String> odd = List.of("x420", "Z1000", "Y2006");
        for (String b : List.of("x420 won the duel by 3 kills.", "Aries (Z1000): the stars smile.", "Y2006 placed 412 blocks.")) {
            t(ConstructionGate.coordinates(ConstructionGate.mask(b, odd)).isEmpty(), "G-10: a name is not a coordinate: " + b);
            t(!ConstructionGate.coordinates(b).isEmpty(), "G-10 mutation: without the mask it would be: " + b);
        }
        for (String a : List.of("x420 lives at -6512, 3104", "x420, z400", "Y2006 at Y-40", "Z1000 (120, -40)"))
            t(!ConstructionGate.coordinates(ConstructionGate.mask(a, odd)).isEmpty(), "G-10: masked names still catch " + a);
        t(!ConstructionGate.coordinates("come see my tower at 12 -40").isEmpty(), "G-11: a letter with a position is caught");
        for (String a : List.of("x ninety z forty", "X: minus four hundred", "z negative twelve",
                "x -ninety z -forty", "x is ninety and z is forty", "xninety zforty", "x ninetyfive", "x fourty", "x equals minus forty"))
            t(ConstructionGate.coordinates(a).equals(List.of("coordinates")), "G-10 attack in words: " + a);
        for (String b : List.of("Steve won ninety blocks of xp", "the Zone held forty", "a tent for two",
                "the zones held ninety", "Xylo won seven"))
            t(ConstructionGate.coordinates(b).isEmpty(), "G-10 benign in words: " + b);
        JsonObject mail = JsonParser.parseString("{\"season_totals\":{\"Steve_Builder\":{},\"x42\":{}},\"mailbag_candidates\":["
                + "{\"id\":\"2026-11-05-0000000a\",\"from\":\"Steve_Builder\",\"kind\":\"letter\",\"text\":\"come see my tower at 12 -40\"},"
                + "{\"id\":\"2026-11-05-0000000b\",\"from\":\"x42\",\"kind\":\"letter\",\"text\":\"x42 loves this paper\"}]}").getAsJsonObject();
        String desk = "{\"printed\":[{\"id\":\"2026-11-05-0000000a\",\"quote\":\"come see my tower at 12 -40\",\"reply_html\":\"<p>Lovely.</p>\"},"
                + "{\"id\":\"2026-11-05-0000000b\",\"quote\":\"x42 loves this paper\",\"reply_html\":\"<p>Thanks, x42.</p>\"}],\"held\":[],\"concern\":[]}";
        MailDesk.Result mr = MailDesk.validate(mail, desk, true, "pg13", java.time.LocalDate.of(2026, 11, 5), List.of());
        t(mr.held.size() == 1 && mr.held.get(0).get("reason").equals("check: location") && mr.printed.size() == 1, "G-11: the letter is held, check: location; x42 is a name " + mr.held + mr.printed);
        // G-14: labels never carry digits
        t(ConstructionGate.coordinates("A creeper took a chunk out of Permit #3").equals(List.of("coordinates (\"chunk\")")), "G-14: chunk label");
        t(ConstructionGate.coordinates("a tower at -6512, 3104").equals(List.of("coordinates")), "G-14: numeric label");
        // G-20: gaming shorthand anywhere is a bare "coordinates"; the rewrites pass
        for (String a : List.of("Creeper explosions x3", "combo x2", "deaths: x4", "K/D 4/1/0", "(41, 980)", "Permit #3 now stands at 980, 41 of them lost to a creeper.",
                "Steve_Builder finished at 14, 2 kills ahead of Alex_Miner.", "Blocks this week (3,100) were a record."))
            t(ConstructionGate.coordinates(a).equals(List.of("coordinates")), "G-20: " + a);
        for (String b : List.of("three creeper blasts", "4 kills, 1 death", "at 980 blocks, 41 of them lost", "finished with 14 kills, 2 ahead", "Blocks this week: 3,100, a record."))
            t(ConstructionGate.coordinates(b).isEmpty(), "G-20 rewrite passes: " + b);
        // G-15: schedule jabs in construction copy; fine in Sports with no builder and build
        for (String j : List.of("still not done after 12 days", "taking forever", "half-built", "halfbuilt", "a work in progress", "slow going", "boring",
                "still isn't finished after 12 days", "still isn’t finished", "construction delays continue", "Day 47. Completion date: unknown",
                "the project remains behind schedule", "finally finished")) {
            t(!ConstructionGate.problems(data("house", "Steve_Builder"), draft(j, "x"), chick).isEmpty(), "G-15 section: " + j);
            if (!j.contains(". "))   // scope (b) is judged per sentence
                t(!ConstructionGate.problems(data("house", "Steve_Builder"), draft("A fine permit.", "Steve_Builder and the house: " + j + "."), chick).isEmpty(), "G-15 mention: " + j);
            t(ConstructionGate.problems(data("house", "Steve_Builder"), draft("A fine permit.", "The Sports desk says: " + j + "."), chick).isEmpty(), "G-15 Sports: " + j);
        }
        // G-16: "small" only in the section; a player called North_Star never trips "north"
        t(ConstructionGate.problems(data("house", "Steve_Builder"), draft("a small house", "x"), chick).contains("construction section (\"small\")"), "G-16: small in the section");
        t(ConstructionGate.problems(data("house", "Steve_Builder"), draft("A fine permit.", "a small upset"), chick).isEmpty(), "G-16: small upset in Sports");
        t(ConstructionGate.problems(data("house", "North_Star"), draft("North_Star filed a permit.", "x"), chick).isEmpty(), "G-16: North_Star is a name");
        // G-17: chicken theme, a farm entry: the paper's farm framing is not construction copy
        JsonObject farm = data("farm", "Alex_Miner", "Steve_Builder");
        for (String fine : List.of("Today\'s crew: Sam_Redstone, Steve_Builder, Alex_Miner are out on the farm again. The paper says hello, farm hands.",
                "Aries (Alex_Miner): The stars say the end of your losing streak is near.",
                "Steve_Builder ran the farm like a hub of chaos, posting the worst K/D",
                "Alex_Miner went 0-3 against Steve_Builder, who looked slow off the line but still not beaten. A rough day on the farm."))
            t(ConstructionGate.problems(farm, draft("A fine permit.", fine), chick).isEmpty(), "G-17 not flagged: " + fine);
        JsonObject tower = data("tower", "Steve_Builder");
        List<String> pr = ConstructionGate.problems(tower, draft("A fine permit.", "Steve_Builder's new tower sits near spawn."), chick);
        t(pr.equals(List.of("construction mention in \"sports\" (\"near\", \"spawn\")")), "G-17 flagged with its heading: " + pr);
        pr = ConstructionGate.problems(tower, draft("A fine permit.", "Steve_Builder is still building; the project is near the End."), chick);
        t(pr.size() == 1 && pr.get(0).contains("still building") && pr.get(0).contains("near") && pr.get(0).contains("the End"), "G-17 both sentences: " + pr);
        // G-21: the placed/blocks trigger is per sentence naming the builder
        JsonObject build = data("base", "Steve_Builder");
        t(!ConstructionGate.problems(build, draft("A fine permit.", "Steve_Builder placed 400 blocks near spawn."), chick).isEmpty(), "G-21: placed + near spawn");
        t(ConstructionGate.problems(build, draft("A fine permit.", "Steve_Builder placed 412 blocks today."), chick).isEmpty(), "G-21: checked, clean");
        t(ConstructionGate.problems(build, draft("A fine permit.", "Steve_Builder won a duel. Placed blocks were near spawn."), chick).isEmpty(), "G-21: other sentence not checked");
        // review fixes 4 and 5: no kind id in the section; "base" is no trigger word ("back to base")
        String idSec = "<h3>Front page</h3><p>x</p><h3>Planning &amp; Zoning</h3><p>Permit #3, an auto_farm of smooth_stone_slab.</p><h3>Sports</h3><p>y</p>";
        t(ConstructionGate.problems(data("house", "Steve_Builder"), idSec, chick).equals(List.of("construction section (\"auto_farm\", \"smooth_stone_slab\")")),
                "fix 4: an underscore word in the section: " + ConstructionGate.problems(data("house", "Steve_Builder"), idSec, chick));
        t(ConstructionGate.problems(data("base", "Steve_Builder"), draft("A fine permit.", "Steve_Builder ran back to base, near spawn, after the duel."), chick).isEmpty(), "fix 5: back to base");
        t(ConstructionGate.kindWord("base", false) == null && ConstructionGate.kindWord("auto farm", false).equals("farm") && ConstructionGate.kindWord("auto_farm", true) == null,
                "fix 5: base is no trigger; display words work");
        // G-18: heading forms
        for (String h : List.of("🏗️ Planning &amp; Zoning", "PLANNING AND ZONING", "Planning &amp; Zoning:")) {
            String d = "<h3>Front page</h3><p>x</p><h3>" + h + "</h3><p>A permit downtown.</p><h3>Sports</h3><p>y</p>";
            t(ConstructionGate.problems(data("house", "Steve_Builder"), d, chick).contains("construction section (\"downtown\")"), "G-18: " + h);
            t(ConstructionGate.dropSection(d, "Planning & Zoning").equals("<h3>Front page</h3><p>x</p><h3>Sports</h3><p>y</p>"), "G-18 drop: " + h);
        }
        for (String h : List.of("Zoning Planning", "Planning"))
            t(ConstructionGate.dropSection("<h3>" + h + "</h3><p>x</p>", "Planning & Zoning").equals("<h3>" + h + "</h3><p>x</p>"), "G-18: not the section: " + h);
        // G-19: the End, case-sensitive
        t(ConstructionGate.problems(data("house", "Steve_Builder"), draft("the end of your losing streak, by the end of the week", "x"), chick).isEmpty(), "G-19: the end of");
        t(!ConstructionGate.problems(data("house", "Steve_Builder"), draft("a trip to the End", "x"), chick).isEmpty()
                && !ConstructionGate.problems(data("house", "Steve_Builder"), draft("an end dimension trip", "x"), chick).isEmpty(), "G-19: the End");
        t(ConstructionGate.problems(data("house", "Steve_Builder"), draft("The End is calling.", "x"), chick).isEmpty(), "G-19: accepted miss at a sentence start");
        // G-22: the Sunday table and critic stay inside the section even with their own h3
        for (String tr : List.of("Permit tracker", "📋 PERMIT TRACKER")) {
            String d = "<h3>Front page</h3><p>x</p><h3>Planning &amp; Zoning</h3><p>A permit.</p><h3>" + tr + "</h3><table><tr><td>7</td></tr></table>"
                    + "<h3>The Architecture Critic</h3><p>A skyline of small wonder.</p><h3>Sports</h3><p>Sports text.</p>";
            pr = ConstructionGate.problems(data("house", "Steve_Builder"), d, chick);
            t(pr.equals(List.of("construction section (\"skyline\", \"small\")")), "G-22: " + pr);
            t(ConstructionGate.dropSection(d, "Planning & Zoning").equals("<h3>Front page</h3><p>x</p><h3>Sports</h3><p>Sports text.</p>"), "G-22: dropped together");
        }
        // G-13: section-only recovery
        String d = draft("A permit downtown.", "x");
        t(ConstructionGate.recover(List.of("construction section (\"downtown\")"), d, "Planning & Zoning").equals(ConstructionGate.dropSection(d, "Planning & Zoning")),
                "G-13: section-only problems drop the section");
        t(ConstructionGate.recover(List.of("construction section (\"downtown\")", "coordinates"), d, "Planning & Zoning") == null, "G-13: Gate C still gives the plain edition");
        t(ConstructionGate.recover(List.of("construction mention in \"sports\" (\"near\")"), d, "Planning & Zoning") == null, "G-13: a mention gives the plain edition");

        // G-12 / T-PLAIN: the plain edition's section passes both gates, with every tag
        JsonObject all8 = JsonParser.parseString("""
                {"season_totals":{"Steve_Builder":{},"Alex_Miner":{},"Sam_Redstone":{},"x420":{}},
                 "construction":{"opening":true,"entries":[
                  {"tag":"permit","permit":44,"name":null,"builders":["Steve_Builder"],"kind":"base","blocks_total":412,"materials":[{"what":"spruce","blocks":300}]},
                  {"tag":"permit","permit":46,"name":null,"builders":["Sam_Redstone"],"kind":"house","blocks_total":412,"existing":true},
                  {"tag":"new_resident","permit":45,"name":null,"builders":["Alex_Miner"],"kind":"house","blocks_total":412},
                  {"tag":"rebuild","permit":9,"name":null,"builders":["Steve_Builder"],"kind":"base","blocks_total":340},
                  {"tag":"progress","permit":7,"name":"The Great Wall","builders":["Sam_Redstone","Steve_Builder"],"kind":"wall","day":9,"blocks_total":5210,"milestone_blocks":5000},
                  {"tag":"progress","permit":46,"name":null,"builders":["x420"],"kind":"base","day":9,"blocks_total":5210,"milestone_blocks":5000,"existing":true},
                  {"tag":"ribbon","permit":7,"name":"The Great Wall","builders":["Sam_Redstone","Steve_Builder"],"kind":"wall","blocks_total":6204},
                  {"tag":"incident","permit":3,"name":null,"builders":["Steve_Builder"],"kind":"farm","blocks_total":980,"incident":{"cause":"creeper","blocks":41,"self":false}},
                  {"tag":"public_works","permit":42,"name":null,"builders":["Sam_Redstone"],"kind":"redstone","blocks_total":200,"parts":{"observers":19,"pistons":6}},
                  {"tag":"demolition","permit":9,"name":null,"builders":["Alex_Miner","Steve_Builder"],"kind":"base","blocks_total":0},
                  {"tag":"renovation","permit":4,"name":null,"builders":["Alex_Miner"],"kind":"house","blocks_total":800}]}}""").getAsJsonObject();
        String sec = Fallback.construction(chick, all8.getAsJsonObject("construction"));
        t(ConstructionGate.problems(all8, sec, chick).isEmpty(), "G-12: plain edition passes the gates: " + ConstructionGate.problems(all8, sec, chick));
        for (String line : List.of("PERMIT FILED: Permit #44, Steve_Builder. What the Commission believes is a base: 412 blocks, mostly spruce.",
                "PERMIT FILED (ADDITION): Permit #46, Sam_Redstone: 412 blocks added.", "PERMIT FILED (NEW RESIDENT): Permit #45, Alex_Miner broke ground on a first home: 412 blocks.",
                "PERMIT RE-FILED: Permit #9 (Steve_Builder) is back under construction: 340 blocks.",
                "DAY 9: Permit #7 &quot;The Great Wall&quot; (Sam_Redstone, Steve_Builder) passed 5,000 blocks: 5,210 in all.",
                "DAY 9: Permit #46 (x420) has added 5,000 blocks: 5,210 added in all.", "RIBBON CUTTING: Permit #7 &quot;The Great Wall&quot; (Sam_Redstone, Steve_Builder) is finished: 6,204 blocks.",
                "INCIDENT REPORT: a creeper removed 41 blocks from Permit #3 (Steve_Builder).", "PUBLIC WORKS: Permit #42 (Sam_Redstone): 19 observers, 6 pistons.",
                "DEMOLITION NOTICE: Permit #9 (Alex_Miner, Steve_Builder) was taken down by its builders.", "RENOVATION: Permit #4 (Alex_Miner) was renovated.",
                "The Coop Planning Commission is now open."))
            t(sec.contains(line), "§8 line: " + line + " in " + sec);
        t(StylePrompt.constructionRules(chick).contains("one line that the Commission is now open;") && !sec.contains("office"),
                "P1: the opening line never says \"office\" (the checker rejects that word)");
        t(!Fallback.edition(TestSettings.coop(), JsonParser.parseString("{}").getAsJsonObject()).contains("Planning"), "no construction data, no section");
        // the problems join the fact check only when on and printing
        String bad = draft("A permit downtown at -6512, 3104.", "x");
        List<String> offP = FactCheck.problems(data("house", "Steve_Builder"), bad, java.time.LocalDate.of(2026, 11, 3), TestSettings.coop());
        t(offP.stream().noneMatch(x -> x.startsWith("construction")) && offP.contains("coordinates"),
                "off: no construction problems (Gate C still runs on every paper)");
        t(FactCheck.problems(data("house", "Steve_Builder"), bad, java.time.LocalDate.of(2026, 11, 3), chick).contains("construction section (\"downtown\")"), "on: they join the list");
        // EC-8 / WP-4: Gate C runs once (global), so its problems and its prompt lines appear once
        List<String> dup = FactCheck.problems(data("house", "Steve_Builder"), draft("A fine permit.", "K/D 4/1/0 and found a chunk of iron."),
                java.time.LocalDate.of(2026, 11, 3), chick);
        t(java.util.Collections.frequency(dup, "coordinates") == 1 && java.util.Collections.frequency(dup, "coordinates (\"chunk\")") == 1, "EC-8: once each: " + dup);
        t(p.split("Never use the words chunk", -1).length == 2, "WP-4: the Gate C words are in the prompt once");
        t(p.split("never join three numbers with slashes", -1).length == 2, "WP-4: the number shapes are in the prompt once");
        // WP-2: the headline is checked too
        String head = draft("A fine permit.", "x").replace("<h2>Big day</h2>", "<h2>Permit #3 Finally Finished Near Spawn</h2>");
        t(ConstructionGate.problems(data("house", "Steve_Builder"), head, chick).stream().anyMatch(x -> x.startsWith("construction mention")),
                "WP-2: a headline jab is caught: " + ConstructionGate.problems(data("house", "Steve_Builder"), head, chick));
        t(ConstructionGate.problems(data("house", "Steve_Builder"), draft("A fine permit.", "x"), chick).isEmpty(), "WP-2: ordinary headings pass");
        // WP-3: a title the gate would reject (or one with no letters) falls back to the default, with a warning
        for (String badTitle : List.of("Village Permits", "Small Builds Desk", "Hub Building News", "\"🏗️\"")) {
            List<String> warns = new ArrayList<>();
            Settings ls = io.github.eastmadc.masthead.config.SettingsLoader.load(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.StringReader("construction:\n  title: " + badTitle + "\n")), warns::add);
            t(ls.construction().title().equals("Planning & Zoning") && warns.stream().anyMatch(w -> w.startsWith("construction.title")), "WP-3: " + badTitle + " -> " + ls.construction().title());
        }
        t(io.github.eastmadc.masthead.config.SettingsLoader.load(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new java.io.StringReader("construction:\n  title: Building Permits\n")), w -> { }).construction().title().equals("Building Permits"), "WP-3: a fine title is kept");
        // WP-5: tracker only (the plain edition prints no tracker): no empty heading
        t(Fallback.construction(chick, JsonParser.parseString("{\"opening\":false,\"entries\":[],\"tracker\":[{\"permit\":3}]}").getAsJsonObject()).isEmpty(),
                "WP-5: no empty Planning & Zoning heading");
        kindLists(chick);
        opening(chick);
        allowedReview(chick);
        System.out.println("ConstructionGateTest: " + ok + " checks passed");
    }

    /**
     * Review of alpha.21: an allowed phrase is masked only as the thing itself (after a, an, for or a count), only when
     * today's entries or survey rows carry it, and only at a detail level that prints it. Used as a landmark it is caught.
     */
    static void allowedReview(Settings chick) {
        Settings full = detail(chick, "full");
        JsonObject portal = data("nether_portal", "Steve_Builder");
        for (String place : List.of("that shares a wall with the nether portal", "at the nether portal", "built into the old nether portal", "near the nether portal",
                "at a nether portal", "by a nether portal", "next to a nether portal", "beside the nether portal", "over 2 cave spider spawners"))
            t(!ConstructionGate.problems(MANY, draft("Permit #3: Steve_Builder filed a house " + place + ".", "x"), full).isEmpty(), "a landmark phrase is caught: " + place);
        t(!ConstructionGate.problems(portal, draft("A fine permit.", "Steve_Builder filed a permit for a house at the nether portal."), full).isEmpty(),
                "a landmark phrase is caught outside the section too");
        String things = "Permit #3, a nether portal with 18 obsidian blocks for nether portal. Permit #4, a mushroom farm with 1 cave spider spawner and 12 nether wart. "
                + "Permit #5, a nether wart farm.";
        t(ConstructionGate.problems(MANY, draft(things, "x"), full).isEmpty(), "the phrases as the things themselves pass: " + ConstructionGate.problems(MANY, draft(things, "x"), full));
        t(ConstructionGate.problems(MANY, draft(things, "x"), chick).equals(List.of("construction section (\"mushroom\", \"cave\", \"nether\")")),
                "standard: place hints are never masked: " + ConstructionGate.problems(MANY, draft(things, "x"), chick));
        JsonObject house = data("house", "Steve_Builder");
        for (String p : List.of("a nether portal", "a mushroom farm", "a nether wart farm", "1 cave spider spawner", "12 nether wart"))
            t(!ConstructionGate.problems(house, draft("Permit #3, a house. Also " + p + ".", "x"), full).isEmpty(), "not in today's facts: not masked: " + p);
        JsonObject survey = JsonParser.parseString("{\"season_totals\":{\"Steve_Builder\":{}},\"construction\":{\"opening\":true,\"entries\":[],"
                + "\"survey\":[{\"permit\":1,\"builders\":[\"Steve_Builder\"],\"kind\":\"nether portal\",\"blocks_total\":34}]}}").getAsJsonObject();
        t(ConstructionGate.problems(survey, draft("The register lists a nether portal.", "x"), chick).isEmpty(), "a survey row's kind counts as today's");
        t(!ConstructionGate.problems(survey, draft("The register lists a nether portal.", "x"), detail(chick, "basic")).isEmpty(), "basic: never masked (it prints decoration)");
        // detail standard: mob farms that point at a structure, biome or dimension print as "mob farm"; cocoa is a place hint
        for (String k : List.of("guardian_farm", "blaze_farm", "witch_farm", "enderman_farm", "gold_farm", "hoglin_farm", "spider_farm", "zombie_farm", "skeleton_farm", "raid_farm"))
            t("mob_farm".equals(io.github.eastmadc.masthead.construction.Kinds.shown(k, "standard")) && k.equals(io.github.eastmadc.masthead.construction.Kinds.shown(k, "full")),
                    "standard prints " + k + " as mob farm");
        t(io.github.eastmadc.masthead.construction.Features.shown(List.of(new io.github.eastmadc.masthead.construction.Features.F("cocoa", 40)), "standard").isEmpty(),
                "standard leaves cocoa out");
        String rules = StylePrompt.constructionRules(chick);
        t(!rules.contains("spider farm") && !rules.contains("spawner") && rules.contains("a librarian trading hall with 17 lecterns"), "the prompt's examples suggest nothing hidden at standard");
        t(StylePrompt.constructionHardLines().contains("right after a, an, for or their count"), "the writer is told how the phrases may be used");
    }

    /** K-LIST (R5, R12): every kind passes every list and gate; no kind, and nothing printed for one, says build or structure. */
    /** The opening paper: the Town Survey page, the plain edition's lines and the renderer's link (made-up builds). */
    static void opening(Settings chick) throws Exception {
        JsonObject data = JsonParser.parseString("""
                {"season_totals":{"Steve_Builder":{},"Alex_Miner":{},"Sam_Redstone":{}},
                 "construction":{"opening":true,"survey_builds":3,"entries":[
                  {"tag":"survey","permit":1,"builders":["Steve_Builder"],"kind":"library","confidence":"high","blocks_total":364,
                   "features":[{"n":64,"of":"bookshelves"}]}],
                 "survey":[
                  {"permit":1,"builders":["Steve_Builder"],"kind":"library","blocks_total":364,"features":[{"n":64,"of":"bookshelves"}],"existing":true},
                  {"permit":2,"builders":["<b>Alex</b>"],"kind":"spider farm","blocks_total":120,"features":[{"n":2,"of":"spider spawners"}]},
                  {"permit":3,"builders":["Sam_Redstone"],"kind":"trading hall","blocks_total":800,"features":[{"n":17,"of":"lecterns","for":"librarian trading"}]}]}}""")
                .getAsJsonObject();
        JsonObject cons = data.getAsJsonObject("construction");
        String page = Pages.survey(chick, cons, java.time.LocalDate.of(2026, 11, 2));
        for (String want : List.of("<title>Town Survey · ", "<h1>Town Survey</h1>", "3 builds on the register", "<h2>Rooms &amp; workshops</h2>",
                "<h2>Mob farms</h2>", "<h2>Villagers &amp; trading</h2>", "Permit #3 — trading hall", "Inside: 17 lecterns for librarian trading",
                "Inside: 2 spider spawners<", "Builders: &lt;b&gt;Alex&lt;/b&gt;", "800 blocks"))
            t(page.contains(want), "survey page: " + want);
        t(page.contains("<meta name=\"robots\" content=\"noindex, noarchive\">"), "survey page: noindex");
        t(!page.contains("<b>Alex") && !page.contains("<h2>Farms</h2>") && page.indexOf("Villagers") < page.indexOf("Rooms &amp; workshops")
                && page.indexOf("Mob farms") < page.indexOf("Villagers"), "survey page: escaped, empty groups left out, groups in page order");
        String text = HtmlClean.checkText(page.substring(page.indexOf("<body>")));
        t(ConstructionGate.coordinates(text).isEmpty(), "survey page: nothing shaped like a position: " + ConstructionGate.coordinates(text));
        t(!ConstructionGate.mask(text, List.of("Steve_Builder", "Sam_Redstone")).contains("_"), "survey page: no ids with underscores");
        JsonObject seen = FactCheck.facts(data).getAsJsonObject("construction");
        t(!seen.has("survey") && seen.get("survey_builds").getAsInt() == 3 && cons.has("survey"), "the writer sees how many, not the rows (the page keeps them)");
        // the plain edition: the survey story and the opening line
        String sec = Fallback.construction(chick, cons);
        t(sec.contains("ON THE REGISTER: Permit #1, Steve_Builder: a library of 364 blocks, standing before the Commission opened. Inside: 64 bookshelves.")
                && sec.contains("first full survey of the town registered 3 builds, all listed on the Town Survey page."), "plain opening: " + sec);
        t(ConstructionGate.problems(data, sec, chick).isEmpty(), "plain opening passes the gates: " + ConstructionGate.problems(data, sec, chick));
        // a survey build name (construction.survey-names): printed like a player's build name, in the story and on the page, escaped
        JsonObject named = cons.deepCopy();
        named.getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("name", "Pixel's House");
        named.getAsJsonArray("survey").get(0).getAsJsonObject().addProperty("name", "Pixel's House");
        t(Fallback.construction(chick, named).contains("ON THE REGISTER: Permit #1 &quot;Pixel&#x27;s House&quot;, Steve_Builder: a library"), "survey name in the story: " + Fallback.construction(chick, named));
        String namedPage = Pages.survey(chick, named, java.time.LocalDate.of(2026, 11, 2));
        t(namedPage.contains("Permit #1 &quot;Pixel&#x27;s House&quot; — library") && namedPage.contains("Permit #3 — trading hall"), "survey name on the page, escaped; unnamed rows as before");
        // the renderer: the page, and its link at the end of the section on the web page only
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("render");
        Render.Rendered r = Render.render(chick, data, java.time.LocalDate.of(2026, 11, 2), null, null,
                new Render.Inputs(tmp.resolve("editions"), tmp.resolve("stats"), tmp.resolve("printed.jsonl"), null), java.time.LocalDate.of(2026, 11, 2));
        t(r.summary().contains("Planning & Zoning") && !r.summary().contains("&amp;"), "render: the owner's summary shows & as a character, not as an HTML code: " + r.summary());
        var cp = chick.paper();   // a paper name with an apostrophe
        Settings toms = new Settings(new Settings.Paper("Tom's Post", cp.serverName(), cp.tagline(), cp.theme(), cp.readers(), cp.goodAward(), cp.badAward(), cp.watchAnimal(), cp.language()),
                chick.tone(), chick.owner(), chick.privacy(), chick.schedule(), chick.writer(), chick.web(), chick.features(), chick.alerts(), chick.construction());
        String sum = Render.render(toms, data, java.time.LocalDate.of(2026, 11, 2), null, null,
                new Render.Inputs(tmp.resolve("editions"), tmp.resolve("stats"), tmp.resolve("printed.jsonl"), null), java.time.LocalDate.of(2026, 11, 2)).summary();
        t(sum.contains("Tom's Post") && !sum.contains("&#x27;"), "render: the summary shows an apostrophe as a character too: " + sum);
        t(r.surveyHtml() != null && r.indexHtml().contains(Render.SURVEY_LINK) && r.archiveHtml().contains(Render.SURVEY_LINK), "render: survey page and link");
        int[] s = ConstructionGate.section(r.fragment(), chick.construction().title());
        t(s != null && r.fragment().substring(s[0], s[1]).endsWith(Render.SURVEY_LINK), "render: the link closes the section");
        t(r.game() == null || !r.game().toString().contains("survey.html"), "render: no web link in the in-game edition");
        cons.addProperty("opening", false);
        t(Render.render(chick, data, java.time.LocalDate.of(2026, 11, 3), null, null,
                new Render.Inputs(tmp.resolve("editions"), tmp.resolve("stats"), tmp.resolve("printed.jsonl"), null), java.time.LocalDate.of(2026, 11, 3)).surveyHtml() == null,
                "render: no page after the opening day");
        // back issues on the front page: no old build stories (the builder may have gone private, been forgotten, or had the
        // name cleared since), and a player hidden since reads "a player". Tags are never touched.
        Settings hid = new Settings(chick.paper(), chick.tone(), chick.owner(), new Settings.Privacy(List.of("kai_ore", "span", "eyebrow", "amp"), List.of()),
                chick.schedule(), chick.writer(), chick.web(), chick.features(), chick.alerts(), chick.construction());
        String old = r.archiveHtml() + "<h3>Sports</h3><p>KAI_ORE won the egg race.</p><ul><li>Sol_Dig: 12 days of building</li><li>Kai_Ore's boat sank.</li></ul>";
        t(old.contains("Permit #1") && old.contains(chick.construction().title().replace("&", "&amp;")), "the old paper has a build story: " + old);
        String scrubbed = Render.backIssue(old, hid);
        t(scrubbed.startsWith("<span class=\"eyebrow\">") && scrubbed.contains("<p>a player won the egg race.</p>") && scrubbed.contains("<li>a player's boat sank.</li>")
                && !scrubbed.toLowerCase().contains("kai_ore"), "back issue: a hidden name is masked in text, tags stay whole: " + scrubbed);
        t(!scrubbed.contains("Permit #") && !scrubbed.contains("days of building") && !scrubbed.contains(Render.SURVEY_LINK), "back issue: no build stories: " + scrubbed);
        t(Render.backIssue("<p>Kai_Ore_2 and Kai_Ore</p>", hid).equals("<p>Kai_Ore_2 and a player</p>"), "back issue: whole names only");
        t(Render.backIssue("<p>Hens &amp; Sons beat amp</p>", hid).equals("<p>Hens &amp; Sons beat a player</p>"), "back issue: an HTML entity is not a name");
        t(Render.backIssue(old, chick).contains("KAI_ORE won"), "back issue: nobody hidden, names stay");
        java.nio.file.Files.createDirectories(tmp.resolve("editions"));
        java.nio.file.Files.writeString(tmp.resolve("editions").resolve("2026-11-02.html"), old);
        String front = Render.render(hid, data, java.time.LocalDate.of(2026, 11, 3), null, null,
                new Render.Inputs(tmp.resolve("editions"), tmp.resolve("stats"), tmp.resolve("printed.jsonl"), null), java.time.LocalDate.of(2026, 11, 3)).indexHtml();
        String back = front.substring(front.indexOf("Back issues"));
        t(back.contains("a player won the egg race.") && back.contains("<summary>") && !back.contains("Permit #") && !back.contains("days of building")
                && !back.toLowerCase().contains("kai_ore"), "front page: back issues carry no build story and no hidden name: " + back);
        t(front.substring(0, front.indexOf("Back issues")).contains("Permit #"), "front page: today's own build story stays");
        // a stored paper dated after this run's (the clock went back) is an old paper too
        java.nio.file.Path tmp2 = java.nio.file.Files.createTempDirectory("render");
        java.nio.file.Files.createDirectories(tmp2.resolve("editions"));
        java.nio.file.Files.writeString(tmp2.resolve("editions").resolve("2031-03-10.html"), old);
        String late = Render.render(hid, data, java.time.LocalDate.of(2031, 3, 9), null, null,
                new Render.Inputs(tmp2.resolve("editions"), tmp2.resolve("stats"), tmp2.resolve("printed.jsonl"), null), java.time.LocalDate.of(2031, 3, 9)).indexHtml();
        String top = late.substring(0, late.indexOf("Back issues"));
        t(top.contains("a player won the egg race.") && !top.contains("Permit #") && !top.toLowerCase().contains("kai_ore"), "front page: a stored paper in the top slot is cleaned too: " + top);
        t(java.nio.file.Files.readString(tmp.resolve("editions").resolve("2026-11-02.html")).equals(old), "the files in editions/ are not rewritten");
    }

    static void kindLists(Settings chick) {
        Pattern never = Pattern.compile("(?i)\\b(?:build|structure|unclassified|classif\\w*)\\b");
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("kinds");
            for (String k : io.github.eastmadc.masthead.construction.Kinds.ALL) {
                String d = io.github.eastmadc.masthead.construction.Kinds.display(k);
                boolean phrase = ConstructionGate.ALLOWED.contains(d);   // a gate word inside, printed only as the whole phrase
                t((phrase || io.github.eastmadc.masthead.construction.Families.allowed(d)) && !never.matcher(d).find() && !d.contains("_"), "K-LIST: display " + k + " -> " + d);
                t((k.equals("base") || k.equals("nether_portal") || ConstructionGate.kindWord(k, false) != null) && (phrase || ConstructionWords.titleOk(d)),
                        "K-LIST: trigger word and title words for " + k);
                t(java.util.Objects.equals(ConstructionGate.kindWord(k, false), ConstructionGate.kindWord(d, false)), "K-LIST: id and display words trigger alike: " + k);
                String json = "{\"season_totals\":{\"Steve_Builder\":{},\"Alex_Miner\":{}},\"construction\":{\"opening\":false,\"entries\":["
                        + "{\"tag\":\"permit\",\"permit\":3,\"name\":null,\"builders\":[\"Steve_Builder\"],\"kind\":\"" + k + "\",\"confidence\":\"low\",\"blocks_total\":412}]},"
                        + "\"blotter\":[{\"what\":\"broke_build\",\"by\":\"Alex_Miner\",\"to\":\"Steve_Builder\",\"n\":30,\"x\":\"" + d + "\"}]}";
                java.nio.file.Path f = dir.resolve(k + ".json");
                java.nio.file.Files.writeString(f, json);
                var r = io.github.eastmadc.masthead.facts.ConstructionFacts.read(f, java.util.Set.of("Steve_Builder", "Alex_Miner"), java.util.Set.of(), false, 3);
                t(r.dropped().isEmpty() && r.construction().getAsJsonArray("entries").size() == 1 && r.blotter().size() == 1, "K-LIST: the facts keep " + k + ": " + r.dropped());
                t(r.construction().getAsJsonArray("entries").get(0).getAsJsonObject().get("kind").getAsString().equals(d), "fix 4: the writer sees the display words for " + k);
                JsonObject data = JsonParser.parseString(json).getAsJsonObject();
                String sec = Fallback.construction(chick, data.getAsJsonObject("construction"));
                t(sec.contains(" " + d + ":") && !never.matcher(sec).find() && ConstructionGate.problems(data, sec, detail(chick, "full")).isEmpty(),
                        "K-LIST: the plain edition prints " + k + " and passes the gates: " + sec);
            }
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        t(io.github.eastmadc.masthead.construction.Kinds.ALL.stream().noneMatch(k -> never.matcher(k.replace('_', ' ')).find()), "K-LIST: no kind id is build or structure");
        // alpha.21: kind and feature phrases with a gate word inside pass as whole phrases; the word alone is still caught
        t(ConstructionGate.ALLOWED.equals(List.of("nether portal", "mushroom farm", "nether wart farm", "cave spider spawners", "cave spider spawner", "nether wart")), "the allowed phrases: " + ConstructionGate.ALLOWED);
        t(ConstructionGate.problems(MANY, draft("Permit #3, a nether portal with 18 obsidian blocks; a mushroom farm; a nether wart farm.", "x"), detail(chick, "full")).isEmpty(),
                "allowed phrases pass the section gate");
        t(ConstructionGate.problems(data("nether_portal", "Steve_Builder"), draft("Permit #3, a nether portal near the nether.", "x"), chick)
                .equals(List.of("construction section (\"near\", \"nether\")")), "the word alone is still caught");
        t(ConstructionGate.problems(data("nether_portal", "Steve_Builder"), draft("A fine permit.", "Steve_Builder jumped through a nether portal near spawn."), chick).isEmpty(),
                "nether portal is no trigger word (deaths and advancements say it)");
        t(ConstructionGate.kindWord("chicken farm", true) == null && "chicken farm".equals(ConstructionGate.kindWord("chicken_farm", false))
                && "library".equals(ConstructionGate.kindWord("library", true)), "chicken farm is no trigger on a chicken paper; library is");
        t(!ConstructionGate.problems(data("library", "Steve_Builder"), draft("A fine permit.", "Steve_Builder read in the library near spawn."), chick).isEmpty(),
                "a builder's library sentence is checked");
        for (String[] w : io.github.eastmadc.masthead.construction.Features.WORDS.values())
            t(!w[0].contains("_") && (w[1] == null || !w[1].contains("_")) && (ConstructionGate.ALLOWED.contains(w[0]) || io.github.eastmadc.masthead.construction.Families.allowed(w[0]))
                    && (w[1] == null || ConstructionGate.ALLOWED.contains(w[1]) || io.github.eastmadc.masthead.construction.Families.allowed(w[1])), "feature words: " + w[0]);
        JsonObject feat = JsonParser.parseString("{\"opening\":false,\"entries\":[{\"tag\":\"permit\",\"permit\":3,\"name\":null,"
                + "\"builders\":[\"Steve_Builder\"],\"kind\":\"trading hall\",\"blocks_total\":412,\"features\":[{\"n\":17,\"of\":\"lecterns\",\"for\":\"librarian trading\"},"
                + "{\"n\":18,\"of\":\"obsidian blocks\",\"for\":\"nether portal\"},{\"n\":1200,\"of\":\"beds\"}]}]}").getAsJsonObject();
        String plain = Fallback.construction(chick, feat);
        JsonObject featData = data("trading_hall", "Steve_Builder");
        featData.add("construction", feat);
        t(plain.contains(" Inside: 17 lecterns for librarian trading, 18 obsidian blocks for nether portal, 1,200 beds.</li>")
                && ConstructionGate.problems(featData, plain, chick).isEmpty(), "the plain edition prints the features in words: " + plain);
        // R12: the old "declined to classify" wording is caught inside the section
        for (String w : List.of("a structure", "unclassified", "declined to classify", "classified"))
            t(!ConstructionGate.problems(data("house", "Steve_Builder"), draft("Permit #3 is " + w + ".", "x"), chick).isEmpty(), "R12: section gate catches " + w);
        t(!StylePrompt.constructionRules(chick).contains("\"build\"") && !StylePrompt.constructionRules(chick).contains("declined"), "R12: the prompt no longer offers the build kind");
        t(StylePrompt.constructionRules(chick).contains("\"features\" is what the build has inside") && StylePrompt.constructionRules(chick).contains("with 17 lecterns")
                && StylePrompt.constructionHardLines().contains("even with a word above inside: nether portal, mushroom farm"), "the writer is told about features and the allowed phrases");
    }
}
