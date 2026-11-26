package io.github.eastmadc.masthead.edition;

import io.github.eastmadc.masthead.config.Settings;
import io.github.eastmadc.masthead.config.SettingsLoader;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The voice system: public defaults, the audience cap, the shipped lexicon and its fact gates, the floor, the claim
 * gate, and prompt snapshots. Snapshots live in src/test/golden/voice; run with UPDATE_GOLDEN=1 to rewrite them after a
 * deliberate prompt change, then read the diff.
 */
public final class VoiceTest {
    static int fails = 0;

    static void t(boolean ok, String what) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
        if (!ok) fails++;
    }

    static final Path GOLD = Path.of("src/test/golden/voice");
    static final LocalDate D = LocalDate.parse("2026-06-12");

    public static void main(String[] a) throws Exception {
        defaults();
        audience();
        lexiconFile();
        palette();
        claims();
        floor();
        ownerWords();
        prompts();
        review1();
        review2();
        review3();
        classic();
        review4();
        if (fails > 0) throw new AssertionError("voice: " + fails + " FAILED");
        System.out.println("voice: all passed");
    }

    static JsonObject facts() throws Exception {
        return PyJson.loads(Files.readString(GOLD.resolve("facts.json"))).getAsJsonObject();
    }

    static Settings tone(Settings.Tone tn) {
        Settings d = Settings.defaults();
        return new Settings(new Settings.Paper("The Daily Cluck", "Henhouse", "Out before the rooster wakes up.",
                "a small-town paper for a town that takes its chickens far too seriously", "a group of friends who play on this server",
                "🥚 Egg of the Day", "🐔 Flapping Fowl Award", "chicken"), tn,
                new Settings.Owner("Rex_Owner", "From the Coop Office", List.of("The Torch Trail: Rex_Owner lit every cave under spawn with one long torch trail "
                        + "and walked out with a stack of diamonds. Nobody needs x-ray with that many torches."), true, "he"),
                d.privacy(), d.schedule(), d.writer(), d.web(), d.features(), d.alerts());
    }

    static Settings.Tone tn(String voice, String spice, String lang, String slang, String aud) {
        return new Settings.Tone(voice, spice, lang, slang, aud, "normal", List.of("obituaries", "sports", "weather", "horoscopes", "animal-watch"), List.of(), List.of());
    }

    static Settings teens() { return tone(tn("sportscast", "spicy", "pg13", "heavy", "teens")); }

    static Settings load(String yaml, List<String> warn) {
        return SettingsLoader.load(YamlConfiguration.loadConfiguration(new StringReader(yaml)), warn::add, null);
    }

    // ------------------------------------------------------------ R10 defaults
    static void defaults() throws Exception {
        Settings.Tone d = Settings.defaults().tone();
        t(d.voice().isEmpty() && d.spice().equals("medium") && d.language().equals("clean") && d.slang().equals("light") && d.audience().equals("all-ages"),
                "R10: public tone defaults medium / clean / light / all-ages");
        t(Settings.defaults().paper().readers().equals("players of all ages"), "R10: readers are players of all ages");
        String shipped;
        try (var in = VoiceTest.class.getResourceAsStream("/config.yml")) { shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        List<String> w = new ArrayList<>();
        Settings s = load(shipped, w);
        t(s.tone().equals(d) && s.paper().readers().equals("players of all ages"), "the shipped config.yml loads to the same defaults: " + s.tone());
        t(w.stream().noneMatch(x -> x.startsWith("tone.")), "the shipped config.yml loads without a tone warning: " + w);
        t(s.tone().effectiveVoice().equals("newsroom"), "public default voice is the newsroom");
        // the example teens preset: the classic voice
        List<String> dw = new ArrayList<>();
        Settings sb = SettingsLoader.load(YamlConfiguration.loadConfiguration(Path.of("examples/config-teens-classic.yml").toFile()), dw::add, null);
        t(sb.tone().audience().equals("teens") && sb.tone().effectiveVoice().equals("classic") && sb.tone().spice().equals("spicy")
                && sb.tone().language().equals("pg13") && sb.tone().slang().equals("heavy"), "deploy preset: teens, classic, spicy, pg13, slang heavy");
        t(!sb.paper().readers().contains("dad") && dw.stream().noneMatch(x -> x.startsWith("tone.")), "deploy preset: readers line without the parent, no tone warning " + dw);
        t(StylePrompt.LANGUAGE_RULE.get("pg13").contains("no WTF") && !StylePrompt.SPICE_VOICE.get("spicy").contains("suspicious"),
                "letters desk: WTF out of pg13, no \"suspicious chest activity\"");
    }

    // ------------------------------------------------------------ the audience cap
    static void audience() {
        Settings.Tone x = tn("", "savage", "strong", "heavy", "all-ages");
        t(x.spice().equals("medium") && x.language().equals("clean") && x.slang().equals("light"), "all-ages caps savage/strong/heavy at medium/clean/light");
        Settings.Tone y = tn("", "savage", "strong", "heavy", "teens");
        t(y.spice().equals("savage") && y.language().equals("pg13") && y.slang().equals("heavy"), "teens caps strong at pg13 only");
        Settings.Tone z = tn("", "savage", "strong", "heavy", "adults");
        t(z.spice().equals("savage") && z.language().equals("strong"), "adults caps nothing");
        t(tn("", "spicy", "pg13", "light", "nonsense").audience().equals("all-ages") && tn("", "spicy", "pg13", "light", "nonsense").spice().equals("medium"),
                "an unknown audience is all-ages, never more");
        t(Settings.Tone.capNotes("savage", "pg13", "light", "all-ages").equals(List.of(
                "tone.spice is savage, but audience all-ages caps it at medium. The paper uses medium.",
                "tone.language is pg13, but audience all-ages caps it at clean. The paper uses clean.")), "cap notes are plain lines");
        t(new Settings.Tone("spicy", "pg13", "normal", List.of()).audience().equals("teens") && new Settings.Tone("mild", "strong", "normal", List.of()).audience().equals("adults")
                && new Settings.Tone("medium", "clean", "normal", List.of()).audience().equals("all-ages"), "the old four-dial Tone keeps its paper (lowest audience that allows it)");
        // the loader: an old config with no audience keeps its paper and says so; a set audience lowers and says so
        List<String> w = new ArrayList<>();
        Settings old = load("tone:\n  spice: spicy\n  language: pg13\n", w);
        t(old.tone().audience().equals("teens") && old.tone().spice().equals("spicy") && old.tone().language().equals("pg13")
                && w.contains("tone.audience is not set; using teens, the lowest audience that allows your spice and language."), "upgrade path: no audience -> teens " + w);
        List<String> w2 = new ArrayList<>();
        Settings capped = load("tone:\n  audience: all-ages\n  spice: savage\n  language: strong\n  slang: heavy\n", w2);
        t(capped.tone().spice().equals("medium") && capped.tone().language().equals("clean") && capped.tone().slang().equals("light")
                && w2.contains("tone.spice is savage, but audience all-ages caps it at medium. The paper uses medium."), "loader caps and says so " + w2);
        // the config upgrader writes the audience that keeps an old paper, never the shipped all-ages
        YamlConfiguration user = YamlConfiguration.loadConfiguration(new StringReader("tone:\n  spice: spicy\n  language: pg13\n"));
        YamlConfiguration def = YamlConfiguration.loadConfiguration(new StringReader("tone:\n  audience: all-ages\n  spice: medium\n  language: clean\n  slang: light\n"));
        SettingsLoader.addMissing(user, def);
        t(user.getString("tone.audience").equals("teens") && user.getString("tone.slang").equals("light"), "upgrade writes audience teens for a pg13 paper");
        YamlConfiguration user2 = YamlConfiguration.loadConfiguration(new StringReader("tone:\n  spice: medium\n  language: clean\n"));
        SettingsLoader.addMissing(user2, def);
        t(user2.getString("tone.audience").equals("all-ages"), "upgrade writes all-ages for a clean paper");
        t(Settings.audienceFor("SPICY", " PG13 ", "light").equals("teens"), "audienceFor ignores case and spaces");
    }

    // ------------------------------------------------------------ the shipped lexicon
    static void lexiconFile() {
        t(Lexicon.SHIPPED.size() >= 40, "lexicon loads (" + Lexicon.SHIPPED.size() + " terms)");
        for (Lexicon.Term x : Lexicon.SHIPPED) {
            t(WordGate.floor(x.term() + " " + String.join(" ", x.forms()) + " " + x.means(), List.of()).isEmpty()
                    && WordGate.bad(x.term() + " " + x.means(), List.of(), false, "clean").isEmpty(), "shipped term passes the floor and the clean word list: " + x.term());
            t(!x.never() || x.claim(), "a never term is a claim word (so the gate stops it): " + x.term());
        }
        t(Lexicon.reviewNote(Lexicon.REVIEWED).isEmpty() && Lexicon.reviewNote(Lexicon.REVIEWED.plusDays(121)).contains("last checked on " + Lexicon.REVIEWED),
                "review date and reminder");
        Lexicon.Term mid = Lexicon.SHIPPED.stream().filter(x -> x.term().equals("mid")).findFirst().orElseThrow();
        t(Lexicon.freshness(mid, D).equals("current") && Lexicon.freshness(mid, LocalDate.parse("2027-03-01")).equals("fading")
                && Lexicon.freshness(mid, LocalDate.parse("2028-01-02")).equals("expired"), "freshness: current, fading, expired");
        boolean threw = false;
        try { Lexicon.parse(Lexicon.load("terms:\n  - {term: x, kind: slang, tier: kids, means: y}\n")); } catch (IllegalArgumentException e) { threw = true; }
        t(threw, "a bad tier in a lexicon file is refused");
        threw = false;
        try { Lexicon.parse(Lexicon.load("terms:\n  - {term: x, kind: slang, tier: teens, means: y, needs: {mood: 1}}\n")); } catch (IllegalArgumentException e) { threw = true; }
        t(threw, "an unknown needs key is refused");
    }

    static String all(Lexicon.Palette p) {
        return String.join("\n", p.game()) + "\n" + String.join("\n", p.reads()) + "\n" + String.join("\n", p.slang()) + "\nAVOID " + p.avoid();
    }

    // ------------------------------------------------------------ today's palette and its fact gates
    static void palette() throws Exception {
        JsonObject f = facts();
        Lexicon.Palette p = Lexicon.palette(teens(), f);
        String s = all(p);
        for (String g : List.of("- skelly:", "- zombo:", "- deepslate diamonds:", "- redstone:", "- palette:", "- TPS:", "- the grind:"))
            t(p.game().stream().anyMatch(x -> x.startsWith(g)), "game word backed by the facts: " + g);
        for (String g : List.of("creepered", "mace smash", "popped a totem", "pearled", "bodied", "bucket clutch", "chicken jockey", "K board"))
            t(!s.contains("- " + g), "game word the facts don't back is not offered: " + g);
        t(p.reads().contains("- librarian reroll (lecterns broken plus villager trades): Kai_Ore: lectern broken 118, trades 11"), "read with its player and numbers: " + p.reads());
        t(Lexicon.SHIPPED.stream().filter(x -> x.kind().equals("read") && !Lexicon.backers(x, f).isEmpty()).count() > 3, "the test day backs more reads than the cap");
        t(p.reads().stream().anyMatch(x -> x.startsWith("- spawn-proofing")) && p.reads().size() == 3, "reads capped at 3");
        t(p.slang().stream().anyMatch(x -> x.startsWith("- rage bait:")), "rage bait backed by took_from + left_in");
        t(!s.contains("- cooked:") && p.avoid().contains("\"cooked\""), "cooldown: a slang word in a recent paper rests and is named to avoid");
        t(p.slangMax() == 3 && p.slang().size() <= 10, "heavy: 3 in the paper, up to 10 offered");
        // tier and level
        Lexicon.Palette pub = Lexicon.palette(tone(tn("", "medium", "clean", "light", "all-ages")), f);
        t(!all(pub).contains("- mid:") && !all(pub).contains("- glazing:") && !all(pub).contains("- larping:"), "all-ages: no teens words");
        t(pub.slangMax() == 2 && pub.slang().size() <= 6, "light: 2 in the paper, up to 6 offered");
        Lexicon.Palette off = Lexicon.palette(tone(tn("sportscast", "spicy", "pg13", "off", "teens")), f);
        t(off.slang().isEmpty() && off.slangMax() == 0 && !off.game().isEmpty(), "slang off: game words stay, slang goes");
        Lexicon.Palette news = Lexicon.palette(tone(tn("newsroom", "spicy", "pg13", "heavy", "teens")), f);
        t(news.slangMax() == 2, "the newsroom keeps slang at light (a stiff voice saying \"cooked\" is cringe)");
        // freshness against the paper's date
        JsonObject later = facts();
        later.addProperty("date", "2027-03-01");
        List<String> others = Lexicon.SHIPPED.stream().filter(x -> x.kind().equals("slang") && !x.term().equals("mid")).map(Lexicon.Term::term).toList();
        Settings onlyMidHeavy = tone(new Settings.Tone("sportscast", "spicy", "pg13", "heavy", "teens", "normal", List.of(), List.of(), others));
        Settings onlyMidLight = tone(new Settings.Tone("sportscast", "spicy", "pg13", "light", "teens", "normal", List.of(), List.of(), others));
        t(all(Lexicon.palette(onlyMidHeavy, f)).contains("- mid:") && all(Lexicon.palette(onlyMidLight, f)).contains("- mid:"), "a current word at light and heavy");
        t(all(Lexicon.palette(onlyMidHeavy, later)).contains("- mid:") && !all(Lexicon.palette(onlyMidLight, later)).contains("- mid:"), "fading words only at heavy");
        later.addProperty("date", "2028-01-02");
        t(!all(Lexicon.palette(onlyMidHeavy, later)).contains("- mid:"), "expired words never");
        // gates read the facts: a skeleton death backs skelly, a PvP kill backs bodied
        JsonObject g = facts();
        g.add("deaths", PyJson.loads("[\"Sol_Dig was blown up by Creeper\", \"Juno_B was slain by Sol_Dig\"]"));
        g.add("pvp", PyJson.loads("[[\"Sol_Dig\", \"Juno_B\"]]"));
        String gs = all(Lexicon.palette(teens(), g));
        t(gs.contains("- creepered:") && gs.contains("- bodied:"), "death messages and pvp gate their words");
        t(!all(Lexicon.palette(tone(tn("sportscast", "medium", "clean", "light", "all-ages")), g)).contains("- bodied:"), "bodied is teens only");
    }

    // ------------------------------------------------------------ the claim gate (slang never invents a play)
    static void claims() throws Exception {
        JsonObject f = facts();
        Settings s = teens();
        t(FactCheck.problems(f, "<p>Sol_Dig pulled off a bucket clutch.</p>", D, s).contains("claim (\"bucket clutch\")"), "bucket clutch is always a claim");
        t(FactCheck.problems(f, "<p>Sol_Dig went full M L G.</p>", D, s).contains("claim (\"mlg\")"), "spaced out MLG is caught");
        t(FactCheck.problems(f, "<p>Kai_Ore pearled out of there.</p>", D, s).contains("claim (\"pearled\")"), "pearled without an ender pearl used");
        t(FactCheck.problems(f, "<p>Sol_Dig got creepered.</p>", D, s).contains("claim (\"creepered\")"), "creepered without a Creeper death");
        JsonObject p = facts();
        p.getAsJsonObject("today").getAsJsonObject("Kai_Ore").getAsJsonObject("used").addProperty("ender_pearl", 4);
        t(FactCheck.problems(p, "<p>Kai_Ore pearled out of there.</p>", D, s).stream().noneMatch(x -> x.startsWith("claim")), "pearled with ender pearls used is fine");
        Settings removed = tone(new Settings.Tone("sportscast", "spicy", "pg13", "heavy", "teens", "normal", List.of(), List.of(), List.of("bucket clutch", "pearled")));
        t(FactCheck.problems(f, "<p>A bucket clutch and Kai_Ore pearled.</p>", D, removed).containsAll(List.of("claim (\"bucket clutch\")", "claim (\"pearled\")")),
                "tone.lexicon.remove never lets a claim word through");
        t(FactCheck.problems(f, "<p>Kai_Ore threw 4 ender pearls and broke a pearl necklace.</p>", D, s).stream().noneMatch(x -> x.startsWith("claim")), "plain words are not claims");
        // the gate counts the season's PvP (a first edition's Cold Case kill line), the offer only today's
        t(FactCheck.problems(f, "<p>Juno_B got bodied the hard way.</p>", D, s).contains("claim (\"bodied\")") && !all(Lexicon.palette(s, f)).contains("- bodied:"),
                "bodied: a season K board alone never backs it on a daily paper, and it is offered only on a PvP day");
        JsonObject noPvp = facts();
        noPvp.remove("leaders");
        t(FactCheck.problems(noPvp, "<p>Juno_B got bodied.</p>", D, s).contains("claim (\"bodied\")"), "bodied with no PvP anywhere in the facts");
        t(FactCheck.problems(f, "<p>The circle of life, speedran.</p>", D, s).stream().noneMatch(x -> x.startsWith("claim")), "a figure of speech is not a claim");
    }

    // ------------------------------------------------------------ the floor
    static void floor() throws Exception {
        JsonObject f = facts();
        Settings s = teens();
        for (String bad : List.of("gyatt", "GYATTT", "gy4tt", "g y a t t", "Rizz", "rizzler", "down bad", "mogging", "looksmaxxing", "sigma", "unc",
                "copium", "geeked", "clanker", "touch grass", "no homo", "ez", "kms", "sybau", "ts", "pmo", "noob", "dirt hut", "stole", "hacker", "dupe"))
            t(FactCheck.problems(f, "<p>Juno_B, " + bad + ", 29 golden carrots.</p>", D, s).stream().anyMatch(x -> x.startsWith("floor (") || x.startsWith("family (")), "floor catches: " + bad);
        t(FactCheck.problems(f, "<p>Sol_Dig has gyatt energy.</p>", D, s).contains("floor (\"gyatt\")"), "an excluded slang word in a draft triggers the gate, by name");
        t(FactCheck.problems(f, "<p>Sol_Dig's dad would be proud.</p>", D, s).contains("family (\"dad\")"), "family words are labelled family");
        t(FactCheck.problems(f, "<p>Juno_B used x-ray to find it.</p>", D, s).contains("floor (\"x ray\")"),
                "the owner's lore says x-ray, but that never lifts it for another player");
        t(FactCheck.problems(f, "<p>Rex_Owner's legend: Nobody needs x-ray with that many torches.</p>", D, s).stream().noneMatch(x -> x.startsWith("floor")),
                "the owner's legend may be retold as written");
        Settings noLore = tone(tn("sportscast", "spicy", "pg13", "heavy", "teens"));
        noLore = new Settings(noLore.paper(), noLore.tone(), new Settings.Owner("Rex_Owner", "Desk", List.of(), true, "he"), noLore.privacy(), noLore.schedule(),
                noLore.writer(), noLore.web(), noLore.features(), noLore.alerts());
        t(FactCheck.problems(f, "<p>Juno_B used x-ray to find it.</p>", D, noLore).contains("floor (\"x ray\")"), "without that lore, x-ray is a cheating accusation");
        t(WordGate.floor("gyatt is fine", List.of(), List.of("gyatt is fine"), "").contains("gyatt"), "lore exempts only the cheating words");
        // the other way: game text, item names, death messages and names that contain floor letters pass
        List<String> clean = List.of("Sol_Dig was slain by Zombie", "Sol_Dig fell from a high place", "Sol_Dig was blown up by Creeper",
                "Juno_B experienced kinetic energy", "Kai_Ore was killed by [Intentional Game Design]", "Wren_5 tried to swim in lava", "Sol_Dig went off with a bang",
                "Kai_Ore chopped 66 oak logs and baked 12 potatoes", "76 blocks, 420 stone, 7,5 and 55 torches", "the Alpha Hall and the Beta Bridge",
                "a pack of wolves", "Sigma_Builder placed 40 blocks", "Stoneman_9 mined 900 stone", "the wasted pickaxe", "a smash with the mace",
                "ancient debris, deepslate diamond ore, coal ore, glow lichen, sugar cane, carved pumpkin, honey bottle, ender pearl, gold nugget",
                "the town hall", "Postmortal", "Diamonds!", "Suit Up", "Isn't It Iron Pick", "Hot Topic", "Monster Hunter", "The End?", "Ice Bucket Challenge");
        for (String c : clean)
            t(WordGate.floor(c, List.of("Sigma_Builder", "Stoneman_9", "Sol_Dig", "Juno_B", "Kai_Ore", "Wren_5")).isEmpty(), "floor passes: " + c);
        // acronyms: wtf/af/stfu only at strong; the floor ones never
        t(!WordGate.bad("wtf was that", List.of(), false, "pg13").isEmpty() && WordGate.bad("wtf was that", List.of(), false, "strong").isEmpty(), "WTF is strong only");
        t(!WordGate.floor("sybau", List.of()).isEmpty(), "sybau at every setting");
        // build names: the floor holds there too
        t(io.github.eastmadc.masthead.construction.NameRules.check("Gyatt Tower", List.of(), "pg13") == null
                && "Alpha Hall".equals(io.github.eastmadc.masthead.construction.NameRules.check("Alpha Hall", List.of(), "pg13")), "build names: the floor holds, Alpha Hall passes");
    }

    // ------------------------------------------------------------ the owner's own words
    static void ownerWords() throws Exception {
        List<String> w = new ArrayList<>();
        Settings s = load("tone:\n  audience: teens\n  spice: spicy\n  language: pg13\n  slang: heavy\n  lexicon:\n    add:\n"
                + "      - {term: the vault, means: the shared storage room, kind: game}\n"
                + "      - {term: gyatt, means: nope, kind: slang}\n"
                + "      - {term: big W, means: a win}\n"
                + "    remove: [rage bait]\n", w);
        t(w.contains("tone.lexicon.add: 'gyatt' uses a word the paper never prints; ignored"), "a floor word can never be added " + w);
        t(s.tone().lexiconAdd().size() == 2 && s.tone().lexiconRemove().equals(List.of("rage bait")), "add and remove load");
        Lexicon.Palette p = Lexicon.palette(new Settings(teens().paper(), s.tone(), teens().owner(), teens().privacy(), teens().schedule(), teens().writer(),
                teens().web(), teens().features(), teens().alerts()), facts());
        t(p.game().contains("- the vault: the shared storage room"), "an owner game word is offered");
        t(all(p).contains("- big W: a win") || p.slang().size() == 10, "an owner slang word joins the slang list");
        t(!all(p).contains("- rage bait:"), "a removed word is not offered");
        Settings forced = tone(new Settings.Tone("sportscast", "spicy", "pg13", "heavy", "teens", "normal", List.of(),
                List.of(new Settings.LexTerm("rizz", "charm", "slang", "teens")), List.of()));
        t(Lexicon.terms(forced).stream().noneMatch(x -> x.term().equals("rizz")), "a floor word built into Settings directly is still dropped");
    }

    // ------------------------------------------------------------ prompt structure and snapshots
    static void prompts() throws Exception {
        JsonObject f = facts();
        Settings pub = tone(Settings.defaults().tone()), hot = teens();
        for (Settings s : List.of(pub, hot)) {
            String sys = StylePrompt.build(s), full = StylePrompt.prompt(s, f), user = full.substring(sys.length());
            t(full.startsWith(sys), "the system part is a prefix (it caches)");
            t(sys.indexOf("HARD LINES") < sys.indexOf("DATA NOTES") && sys.indexOf("SECTION RULES") < sys.indexOf("BLOTTER MEANINGS")
                    && sys.indexOf("BLOTTER MEANINGS") < sys.indexOf("\nVOICE (") && sys.trim().endsWith(".") , "order: hard lines ... section rules, blotter, voice last");
            t(user.indexOf("WORDS FOR TODAY") < user.indexOf("EXAMPLES") && user.indexOf("EXAMPLES") < user.indexOf("DATA (JSON):")
                    && user.trim().startsWith("WORDS FOR TODAY") && user.trim().endsWith("every hard line kept."), "user part: words, examples, data, recap");
            t(!sys.contains("2026") && !sys.contains("Kai_Ore"), "the system part has nothing from the day");
            for (String tic : List.of("No arrests were made", "Officers are looking into", "mock-solemn", "classified-intel", "slept in b's bed",
                    "cannot confirm\" whether", "suspicious chest activity", "'WTF'"))
                t(!sys.contains(tic) || sys.contains("\"" + tic + "\""), "no template tic left in the instructions: " + tic);
            t(!sys.contains("one dad"), "no parent priming");
            t(sys.contains("golden_egg and headless_chicken are data keys; call the awards only by their headings."), "P2: the award keys are not award names");
        }
        String p = StylePrompt.prompt(pub, f), h = StylePrompt.prompt(hot, f);
        t(p.contains("- Spice: MEDIUM.") && p.contains("- Language: CLEAN.") && p.contains("Slang (max 2") && p.endsWith("Now write it: the town paper, MEDIUM, CLEAN (no swearing), "
                + "the best story leads, slang at most 2 from WORDS FOR TODAY and never as a tag, game words wherever the facts back them, no police voice, every hard line kept.\n"),
                "public: medium, clean, light, newsroom recap");
        t(!p.contains("hell") && !p.contains("damn"), "public prompt carries no swear, not even in an example");
        t(h.contains("You are the server's caster") && h.contains("merciless on the blunders") && h.contains("roasted for every play")
                && h.contains("\"he\", \"him\" and \"his\" only ever mean Rex_Owner.") && h.contains("Slang (max 3"), "teens caster: identity, owner, pronoun rule, slang 3");
        Settings sheep = TestSettings.other();
        String sp = StylePrompt.build(sheep) + StylePrompt.daily(sheep, f);
        t(!sp.contains("coop") && !sp.contains("spokeshen") && !sp.contains("egg-cellent"), "no chicken words for a sheep paper");
        Settings gc = tone(tn("group-chat", "medium", "clean", "light", "all-ages"));
        t(StylePrompt.examples(gc, D).stream().noneMatch(x -> x.contains("hell")) && StylePrompt.examples(tone(tn("group-chat", "spicy", "pg13", "light", "teens")), D).size() == 3,
                "examples: 3 a day, none with a swear the language forbids");
        t(!StylePrompt.examples(hot, D).equals(StylePrompt.examples(hot, D.plusDays(1))), "examples rotate by date");
        snap("public.txt", p);
        snap("teens-caster.txt", h);
        // M5: a big server's data is never cut in the middle; quiet players are left out whole, and the owner is told
        List<String> none = new ArrayList<>();
        t(StylePrompt.prompt(pub, f, none).equals(p) && none.isEmpty(), "M5: a small server gets the same prompt and no note");
        JsonObject big = f.deepCopy(), row = big.getAsJsonObject("today").getAsJsonObject("Kai_Ore");
        for (int i = 0; i < 400; i++) {
            JsonObject r = row.deepCopy();
            r.addProperty("play_min", i + 1);
            if (i < 80) big.getAsJsonObject("today").add("Crowd_" + i, r);
            big.getAsJsonObject("season_totals").add("Crowd_" + i, r);
        }
        String whole = PyJson.dumpsCompact(big);
        for (Settings s : List.of(pub, tone(tn("classic", "spicy", "pg13", "heavy", "teens")))) {
            List<String> notes = new ArrayList<>();
            String bp = StylePrompt.prompt(s, big, notes);
            int at = bp.indexOf("DATA (JSON):\n") + 13;
            String sent = bp.substring(at, bp.indexOf("\n\n", at));
            JsonObject got = PyJson.loads(sent).getAsJsonObject(), td = got.getAsJsonObject("today"), st = got.getAsJsonObject("season_totals");
            t(sent.length() > 40000 && sent.length() <= 60000 && got.has("awards") && got.has("police_blotter") && got.has("server"), "M5: the data is whole JSON and fits (" + sent.length() + ")");
            t(td.has("Crowd_79") && st.has("Crowd_79") && td.has("Kai_Ore") && !td.has("Crowd_0") && !st.has("Crowd_0"), "M5: the least play time goes first, today row and season row");
            t(st.has("Moss_T") && st.has("Rex_Owner") && !st.has("Crowd_399"), "M5: season rows stay for leaders and the owner, not for players who were not on");
            t(notes.size() == 1 && notes.get(0).matches("3[2-9]\\d quiet players left out of the AI's data"), "M5: the owner is told how many: " + notes);
            t(PyJson.dumpsCompact(big).equals(whole), "M5: the full facts keep every row (the fact check reads them)");
        }
    }

    // ------------------------------------------------------------ voice review, round 1 (each a finding id)
    static Settings withOwner(Settings b, String owner, List<String> lore) {
        return new Settings(b.paper(), b.tone(), new Settings.Owner(owner, "Desk", lore, true, "he"), b.privacy(), b.schedule(), b.writer(), b.web(),
                b.features(), b.alerts());
    }

    static Settings withTone(Settings b, Settings.Tone tn) {
        return new Settings(b.paper(), tn, b.owner(), b.privacy(), b.schedule(), b.writer(), b.web(), b.features(), b.alerts());
    }

    static boolean has(List<String> problems, String prefix) { return problems.stream().anyMatch(x -> x.startsWith(prefix)); }

    static void review1() throws Exception {
        JsonObject f = facts();
        Settings s = teens();
        List<String> names = List.of("Sol_Dig", "Juno_B", "Kai_Ore", "Wren_5", "Rex_Owner");
        // CS1: compound, stretched, starred and spaced swears
        for (String w : List.of("bullshit", "Motherfucking", "fuckin", "shitshow", "dumbass", "badass", "goddamn", "dammit", "pissed", "daaamn", "shiiit",
                "fuuuck", "f*ck", "sh*t", "d*mn", "h*ll", "f u c k", "s e x", "these mfs", "BS", "gtfo", "omfg", "ffs"))
            t(!WordGate.bad("That trade was " + w + ".", names, false, "clean").isEmpty(), "CS1 clean catches: " + w);
        for (String w : List.of("bullshit", "Motherfucking", "fuckin", "shitshow", "pissed", "shiiit", "fuuuck", "f*ck", "sh*t", "f u c k", "s e x", "these mfs", "BS"))
            t(!WordGate.bad("That trade was " + w + ".", names, false, "pg13").isEmpty(), "CS1 pg13 catches: " + w);
        t(WordGate.bad("daaamn, that hurt like hell", names, false, "pg13").isEmpty(), "CS1 pg13 still allows the mild swears");
        t(!WordGate.bad("bitchin", names, false, "strong").isEmpty() && WordGate.bad("fuckin", names, false, "strong").isEmpty(), "CS1 strong: f-word stems yes, b-word never");
        for (String ok : List.of("heel", "shell", "netherite scrap", "glass", "compass", "assess", "passed", "hello", "Niger", "as", "class", "5* trade", "goner", "soon", "bass"))
            t(WordGate.bad(ok, names, false, "clean").isEmpty() && WordGate.floor(ok, names).isEmpty(), "CS1/CS7 innocent word passes: " + ok);
        // CS7: stretched spellings still caught, shorter real words not
        t(!WordGate.floor("gyattt", List.of()).isEmpty() && !WordGate.floor("gooner", List.of()).isEmpty() && WordGate.floor("he's a goner", List.of()).isEmpty(),
                "CS7 gyattt and gooner caught, goner passes");
        // CS2: family words in the paper
        for (String x : List.of("Sol_Dig, Kai_Ore's father, mined 40.", "Sol_Dig's son Kai_Ore mined 40.", "Sol_Dig, the server's adult, mined 40.",
                "Kai_Ore, the kid, mined 40.", "Sol_Dig's daddy mined 40.", "Pops Sol_Dig mined 40.", "The grown-up mined 40.", "The old guy mined 40.",
                "Every child on the server mined 40."))
            t(has(FactCheck.problems(f, "<p>" + x + "</p>", D, s), "family ("), "CS2 family: " + x);
        t(!has(FactCheck.problems(f, "<p>Kai_Ore pops a fresh torch. Soon, Sol_Dig.</p>", D, s), "family ("), "CS2 'pops a' and 'soon' are not family");
        // CS3 / FH1: the lore exemption is for a retelling about the owner only
        for (String x : List.of("Wren_5 found 25 diamonds. Totally x-ray.", "Wren_5 broke 25 deepslate diamond ore in 51.3 minutes. That is x-ray luck.",
                "Wren_5: Nobody needs x-ray with that many torches.", "Wren_5 x-rayed every one."))
            t(has(FactCheck.problems(f, "<p>" + x + "</p>", D, s), "floor (\"x ray"), "CS3/FH1 lore never covers another player: " + x);
        t(!has(FactCheck.problems(f, "<p>" + s.owner().lore().get(0) + "</p>", D, s), "floor"), "CS3/FH1 the lore retold as written passes");
        t(!has(FactCheck.problems(f, "<p>Rex_Owner counted 618 \"torches.\" Nobody needs x-ray with that many torches.</p>", D, s), "floor"),
                "CS3/FH1 the lore's own sentence after a quoted number passes (r3 first edition)");
        // FH2
        for (String x : List.of("Wren_5 x-rayed those 25.", "Wren_5 was x-raying.", "Wren_5 and the x-rays."))
            t(!WordGate.floor(x, names).isEmpty(), "FH2 floor catches: " + x);
        // CS4: no bed entries for the writer
        t(!StylePrompt.BLOTTER_MEANINGS.containsKey("slept_in_bed") && Lexicon.SHIPPED.stream().noneMatch(x -> x.term().equals("set spawn")), "CS4 no bed kind, no set spawn word");
        for (String x : List.of("Sol_Dig slept in Rex_Owner's bed.", "in bed with Rex_Owner", "the bed swap", "the spawn swap", "they share a bed"))
            t(!WordGate.floor(x, names).isEmpty(), "CS4 floor catches: " + x);
        t(!StylePrompt.build(s).contains("set spawn at X's bed"), "CS4 the voice block no longer tells bed facts");
        // CS5: slurs, sexual, drug words; emoji; look-alike letters; the owner's words
        for (String w : List.of("tranny", "dyke", "kike", "chink", "spic", "homo", "tard", "schizo", "autist", "tism", "midget", "cripple", "thot", "milf",
                "freaky", "edging", "baddie", "zaddy", "vape", "weed", "bullshit", "fuckin", "that's gay", "sipping lean", "goon cave", "got blazed")) {
            List<String> w2 = new ArrayList<>();
            Settings o = load("tone:\n  audience: all-ages\n  lexicon:\n    add:\n      - {term: \"" + w + "\", means: a minecart line, kind: game, tier: all-ages}\n", w2);
            t(o.tone().lexiconAdd().isEmpty() && w2.stream().anyMatch(x -> x.contains("never prints")), "CS5 owner word refused: " + w);
        }
        for (String e : List.of("🍆", "🍑", "💦", "👅", "🥵", "🍃", "🍺"))
            t(!WordGate.floor("Sol_Dig " + e, names).isEmpty(), "CS5 emoji caught: " + e);
        t(!WordGate.floor("gуatt", List.of()).isEmpty() && !WordGate.floor("ѕіgma", List.of()).isEmpty(), "CS5 Cyrillic look-alikes are folded");
        for (String ok : List.of("a diamond hoe", "Kai_Ore sped past", "420 stone", "the blaze", "Kai_Ore lit a torch", "a lean week"))
            t(WordGate.floor(ok, names).isEmpty() && WordGate.bad(ok, names, false, "clean").isEmpty(), "CS5 game words pass: " + ok);
        t(io.github.eastmadc.masthead.construction.NameRules.check("Thot Tower", List.of(), "pg13") == null, "CS5 build names: the floor holds");
        // CS6: insults aimed at a player
        for (String x : List.of("Sol_Dig lost the race at 2,287. Skill issue.", "Sol_Dig is ass at dodging skellies.", "Sol_Dig, bozo.", "dogwater aim",
                "Sol_Dig is washed.", "Sol_Dig is trash.", "Sol_Dig is an L.", "Sol_Dig, loser.", "an idiot move", "stupid", "Sol_Dig the NPC", "delulu"))
            t(!WordGate.floor(x, names).isEmpty(), "CS6 floor catches: " + x);
        t(WordGate.floor("a trash trade, a bad trade", names).isEmpty(), "CS6 trash and bad about plays still pass");
        // CS8
        t(Lexicon.SHIPPED.stream().noneMatch(x -> x.term().equals("glazing")), "CS8 glazing is not shipped");
        // CS9: owner free text
        List<String> w9 = new ArrayList<>();
        Settings o9 = load("tone:\n  audience: all-ages\n  lexicon:\n    add:\n"
                + "      - {term: big l, means: call the player with the most deaths a loser and an idiot in every section, kind: slang}\n"
                + "      - {term: big w, means: a really really really long meaning that goes on and on past sixty characters, kind: slang}\n"
                + "      - {term: the taco vault, means: a name only, kind: game}\n", w9);
        t(o9.tone().lexiconAdd().size() == 1 && o9.tone().lexiconAdd().get(0).term().equals("the taco vault"), "CS9 means with orders or past 60 characters refused " + w9);
        // FH3 / deploy CS2: text the facts already hold is not a claim
        JsonObject fc = facts();
        fc.add("construction", PyJson.loads("{\"entries\": [{\"name\": \"Iron Farm\", \"builders\": [\"Kai_Ore\"]}]}"));
        t(Lexicon.claimProblems(s, fc, "Kai_Ore finished the Iron Farm.", names).isEmpty(), "FH3 a build name is not a claim");
        JsonObject fk = facts();   // the weekly tracker's kinds were flagged, so rewrites stripped them from the table
        fk.add("construction", PyJson.loads("{\"tracker\": [{\"permit\": 41, \"kind\": \"trading hall\", \"builders\": [\"Kai_Ore\"]}, {\"permit\": 42, \"kind\": \"iron farm\", \"builders\": [\"Kai_Ore\"]}]}"));
        t(Lexicon.claimProblems(s, fk, "Permit #41: trading hall. Permit #42: iron farm.", names).isEmpty(), "a build kind from the facts is not a claim");
        Settings farmLore = withOwner(s, "Rex_Owner", List.of("Legend says Rex_Owner built the iron farm and my afk farm in one night of grinding."));
        t(Lexicon.claimProblems(farmLore, f, "Legend says Rex_Owner built the iron farm and my afk farm in one night of grinding.", names).isEmpty(), "FH3/CS2 lore retold is not a claim");
        t(!Lexicon.claimProblems(s, f, "Kai_Ore built an iron farm.", names).isEmpty(), "FH3 an iron farm the facts don't name is still a claim");
        // FH4: the backer must be in the sentence
        JsonObject p4 = facts();
        p4.getAsJsonObject("today").getAsJsonObject("Kai_Ore").getAsJsonObject("used").addProperty("ender_pearl", 1);
        p4.getAsJsonObject("today").getAsJsonObject("Kai_Ore").getAsJsonObject("used").addProperty("totem_of_undying", 1);
        t(!Lexicon.claimProblems(s, p4, "Sol_Dig pearled away from 9 zombies.", names).isEmpty(), "FH4 pearled pinned on a player who threw none");
        t(!Lexicon.claimProblems(s, p4, "Sol_Dig popped a totem.", names).isEmpty(), "FH4 totem pinned on the wrong player");
        t(Lexicon.claimProblems(s, p4, "Kai_Ore pearled away. Kai_Ore popped a totem.", names).isEmpty(), "FH4 the backer's own sentence is fine");
        JsonObject c4 = facts();
        c4.add("deaths", PyJson.loads("[\"Sol_Dig was blown up by Creeper\"]"));
        t(!Lexicon.claimProblems(s, c4, "Juno_B got creepered.", names).isEmpty() && Lexicon.claimProblems(s, c4, "Sol_Dig got creepered.", names).isEmpty(),
                "FH4 creepered needs the death line's victim");
        // FH5: season board backs bodied only on a first edition
        JsonObject first = facts();
        first.addProperty("first_edition", true);
        t(Lexicon.claimProblems(s, first, "Moss_T bodied 7 players this season.", List.of("Moss_T", "Juno_B")).isEmpty(), "FH5 first edition: the season board backs bodied");
        // FH6: rage bait needs the same two players
        JsonObject r6 = facts();
        r6.add("police_blotter", PyJson.loads("[{\"what\": \"took_from\", \"by\": \"Juno_B\", \"to\": \"Rex_Owner\", \"items\": {\"bread\": 3}},"
                + "{\"what\": \"left_in\", \"by\": \"Wren_5\", \"to\": \"Kai_Ore\", \"items\": {\"glass\": 1}}]"));
        t(!all(Lexicon.palette(s, r6)).contains("- rage bait:"), "FH6 rage bait needs one player taking and leaving in the same chest owner's");
        Lexicon.Term rb = Lexicon.SHIPPED.stream().filter(x -> x.term().equals("rage bait")).findFirst().orElseThrow();
        t(!rb.means().contains("back") && rb.means().contains("no order"), "FH6 rage bait means: both counts, no order, no motive");
        // FH7 / QC2: examples model only backed facts, and carry no lexicon slang
        List<String> slangForms = new ArrayList<>();
        for (Lexicon.Term x : Lexicon.SHIPPED) if (x.kind().equals("slang")) slangForms.addAll(x.forms());
        for (var e : StylePrompt.EXAMPLES.entrySet())
            for (String ex : e.getValue()) {
                String low = ex.toLowerCase(java.util.Locale.ROOT);
                t(List.of("last in", "in one day", "day three", "all season", "first ever", "solo", "exactly", "rage bait").stream().noneMatch(low::contains)
                        && FactCheck.textProblems(ex, s, List.of()).isEmpty(), "FH7 example keeps the data rules: " + ex);
                List<String> tk = WordGate.tokens(ex, List.of());
                t(slangForms.stream().noneMatch(sf -> WordGate.hasPhrase(tk, sf)), "QC2 example has no lexicon slang: " + ex);
            }
        t(!StylePrompt.SPICE_PAPER.get("savage").contains("what the stat was for") && !StylePrompt.build(s).contains("last in diamonds"), "FH7 savage and the shape line");
        // FH8: lower-is-better boards never make a fumble or a race
        JsonObject d8 = facts();
        d8.add("leaders", PyJson.loads("{\"deaths\": [[\"Sol_Dig\", 26]]}"));
        d8.add("leaders_today", PyJson.loads("{\"deaths\": [[\"Juno_B\", 3], [\"Sol_Dig\", 2]], \"damage_taken\": [[\"Juno_B\", 30], [\"Sol_Dig\", 2]]}"));
        t(!Lexicon.day("fumble", true, d8, false) && !Lexicon.day("race", true, d8, false), "FH8 deaths and damage boards are not races");
        // FH9
        Lexicon.Term grind = Lexicon.SHIPPED.stream().filter(x -> x.term().equals("the grind")).findFirst().orElseThrow();
        Lexicon.Term gg = Lexicon.SHIPPED.stream().filter(x -> x.term().equals("GG")).findFirst().orElseThrow();
        t(!grind.means().contains("tuff") && !gg.means().contains("feud") && StylePrompt.daily(s, ggDay(s)).contains("after the line that settles a race"), "FH9 grind and GG means");
        // deploy CS3: an owner word that is a claim form or a shipped word is refused
        List<String> w3 = new ArrayList<>();
        Settings o3 = load("tone:\n  audience: teens\n  lexicon:\n    add:\n      - {term: xp farm, means: our farm, kind: game}\n"
                + "      - {term: iron farm, means: our farm, kind: game}\n      - {term: crits, means: hits, kind: slang}\n      - {term: cooked, means: done, kind: slang}\n", w3);
        t(o3.tone().lexiconAdd().isEmpty() && w3.stream().filter(x -> x.contains("can't check")).count() == 4, "deploy CS3 claim forms and shipped words refused " + w3);
        // deploy CS4: remove + add overrides a shipped word
        List<String> w4 = new ArrayList<>();
        Settings o4 = load("tone:\n  audience: teens\n  lexicon:\n    add:\n      - {term: lowkey, means: OUR meaning, kind: slang}\n    remove: [lowkey]\n", w4);
        t(Lexicon.terms(withTone(s, o4.tone())).stream().anyMatch(x -> x.term().equals("lowkey") && x.means().equals("OUR meaning")), "deploy CS4 override kept " + w4);
        // deploy CS5: the owner's game words come first
        JsonObject busy = facts();
        busy.getAsJsonObject("today").getAsJsonObject("Kai_Ore").getAsJsonObject("used").addProperty("ender_pearl", 1);
        busy.getAsJsonObject("today").getAsJsonObject("Kai_Ore").getAsJsonObject("used").addProperty("totem_of_undying", 1);
        busy.add("deaths", PyJson.loads("[\"Sol_Dig was blown up by Creeper\", \"Sol_Dig was shot by Skeleton\", \"Sol_Dig was slain by Zombie\"]"));
        Settings vault = withTone(s, new Settings.Tone("sportscast", "spicy", "pg13", "heavy", "teens", "normal", List.of(),
                List.of(new Settings.LexTerm("the vault", "a name only", "game", "all-ages")), List.of()));
        t(Lexicon.palette(vault, busy).game().contains("- the vault: a name only"), "deploy CS5 the owner's word survives a busy day");
        // deploy CS6: config mistakes are reported
        List<String> w6 = new ArrayList<>();
        StringBuilder many = new StringBuilder("tone:\n  audience: all-ages\n  lexicon:\n    add:\n");
        for (int i = 0; i < 45; i++) many.append("      - {term: word ").append((char) ('a' + i % 26)).append((char) ('a' + i / 26)).append(", means: a name, kind: game}\n");
        many.append("      - {term: The Taco Vault, means: a name, kind: game, tier: teens}\n      - just a string\n    remove: [lowky]\n");
        Settings o6 = load(many.toString(), w6);
        t(w6.stream().anyMatch(x -> x.contains("only the first 40")) && w6.stream().anyMatch(x -> x.contains("'lowky'"))
                && w6.stream().anyMatch(x -> x.contains("not a {term, means")), "deploy CS6 cap, typo and plain-string warnings " + w6);
        List<String> w6b = new ArrayList<>();
        Settings o6b = load("tone:\n  audience: all-ages\n  lexicon:\n    add:\n      - {term: The Taco Vault, means: a name, kind: game, tier: teens}\n"
                + "      - {term: la bóveda, means: a name, kind: game}\n", w6b);
        t(o6b.tone().lexiconAdd().size() == 1 && o6b.tone().lexiconAdd().get(0).term().equals("The Taco Vault") && w6b.stream().anyMatch(x -> x.contains("above tone.audience"))
                && w6b.stream().anyMatch(x -> x.contains("English letters")), "deploy CS6 case kept, tier note, clear letters message " + w6b);
        // deploy CS7 / CS8: docs
        String readme = Files.readString(Path.of("README.md")), cfg = Files.readString(Path.of("src/main/resources/config.yml"));
        t(!readme.contains("keeps its paper as it was") && !cfg.contains("raids") && !readme.contains("raids"), "deploy CS7/CS8 docs");
        // QC1: the cooldown reads whole papers, game nicknames and swears included
        String longPaper = "x ".repeat(2000) + "That was a mining diff. The skelly won. Rough as hell.";
        List<String> used = Lexicon.usedWords(s, longPaper, names);
        t(used.containsAll(List.of("diff", "skelly", "as hell")), "QC1 words late in a paper are found: " + used);
        JsonObject q1 = facts();
        JsonArray uw = new JsonArray();
        used.forEach(uw::add);
        q1.add("recent_words", uw);
        Lexicon.Palette pq = Lexicon.palette(s, q1);
        t(!all(pq).contains("- diff:") && !all(pq).contains("- skelly:") && pq.avoid().containsAll(List.of("\"diff\"", "\"skelly\"", "\"as hell\"")), "QC1 they rest " + pq.avoid());
        t(!FactCheck.facts(q1).has("recent_words"), "QC1 the writer never sees the word list as data");
        // QC3: no swear quota
        t(!StylePrompt.LANGUAGE_PAPER.get("pg13").contains("last words") && !StylePrompt.LANGUAGE_PAPER.get("strong").contains("last words")
                && !StylePrompt.recap(s).contains("end of a roast") && StylePrompt.LANGUAGE_PAPER.get("pg13").contains("never a sentence on its own"), "QC3 swears are not a quota");
        // QC5: dated slang
        t(FactCheck.problems(f, "<p>Sol_Dig, no cap, bestie.</p>", D, s).containsAll(List.of("dated (\"no cap\")", "dated (\"bestie\")")), "QC5 dated slang triggers a rewrite");
        t(FactCheck.problems(f, "<p>Sol_Dig lit a torch and ate 3 steak.</p>", D, s).stream().noneMatch(x -> x.startsWith("dated")), "QC5 lit and ate are plain English");
        t(StylePrompt.daily(s, f).contains("bestie") && StylePrompt.daily(s, f).contains("never use"), "QC5 dated words are always named");
        // QC6
        t(Lexicon.SHIPPED.stream().noneMatch(x -> x.term().equals("lava chicken")) && Lexicon.SHIPPED.stream().noneMatch(x -> x.kind().equals("game") && x.freshUntil() != null),
                "QC6 no dated meme as a game word");
        // QC8
        t(!StylePrompt.SPICE_PAPER.get("medium").contains("in the same item") && StylePrompt.SPICE_PAPER.get("medium").contains("credit where due"), "QC8 medium credit rule");
        t(has(FactCheck.problems(f, "<p>Kai_Ore made Enchanter look like homework.</p>", D, s), "\"homework\""), "QC8 school words");
        // QC9
        t(StylePrompt.build(s).contains("\"home turf\"") && StylePrompt.build(s).contains("\"farm hands\""), "QC9 banned in the prompt");
        t(has(FactCheck.problems(f, "<p>Kai_Ore on his own server. Sol_Dig on his own server.</p>", D, s), "\"own server\"")
                && !has(FactCheck.problems(f, "<p>Rex_Owner lost on his own server.</p>", D, s), "\"own server\""), "QC9 own server at most once");
    }

    // ------------------------------------------------------------ voice review, round 2 (each a finding id)
    static boolean caught(String x, String lang) {
        return !WordGate.bad(x, List.of(), false, lang).isEmpty() || !WordGate.floor(x, List.of()).isEmpty();
    }

    static JsonObject withDeaths(String... lines) throws Exception {
        JsonObject f = facts();
        JsonArray a = new JsonArray();
        for (String l : lines) a.add(l);
        f.add("deaths", a);
        return f;
    }

    static void review2() throws Exception {
        JsonObject f = facts();
        Settings s = teens();
        List<String> names = List.of("Sol_Dig", "Juno_B", "Kai_Ore", "Wren_5", "Rex_Owner");
        // CS2-1: '!', '|', the dotless i, Cyrillic ү, the script g and accents spell words past the filter
        for (String x : List.of("nimbit is a n!gga.", "nimbit is a b!tch.", "nimbit is a b|tch.", "nimbit is a bıtch.", "shıt trade.", "What the sh!t.",
                "nimbit is a s!mp.", "nimbit r!zz.", "nimbit is a gүatt.", "nimbit is a ɡyatt.", "nimbit is a bïtch.", "b!!tch"))
            t(caught(x, "pg13") && caught(x, "clean"), "CS2-1 caught at pg13 and clean: " + x);
        for (String ok : List.of("What a run!", "GG!", "Kai_Ore, 40 ore!!!", "75!", "That was hell!", "|| 1,874 blocks ||", "a café trade"))
            t(!caught(ok, "pg13"), "CS2-1 pg13 passes: " + ok);
        t(WordGate.mixedScript("nimbit is a gүatt") && WordGate.mixedScript("bıtch") && !WordGate.mixedScript("a café trade, 40 ore!")
                && !WordGate.mixedScript("гуатт") , "CS2-1 a letter mixing look-alike letters into English words is held");
        // CS2-2: an article before a spaced word never hides it
        for (String x : List.of("nimbit is a n i g g a.", "nimbit is a s l u t.", "nimbit is a w h o r e.", "nimbit is a g y a t t.", "nimbit is a s i m p.",
                "I s i m p for it"))
            t(caught(x, "pg13"), "CS2-2 caught: " + x);
        t(!caught("a W, a L and a W", "clean") && !caught("x y z 1 2 3", "clean"), "CS2-2 single letters that spell nothing pass");
        // CS2-3: the cheating floor
        for (String x : List.of("25 diamonds in 51.3 minutes? Sus.", "nimbit cheats.", "nimbit is a cheat.", "Did nimbit hack?", "nimbit runs kill aura",
                "Quill_7 robbed TownFounder of 30 melons", "The great melon heist: Quill_7 swiped 30", "nimbit griefs everyone",
                "That count is suspicious.", "suspiciously lucky", "nimbit got banned", "nimbit uses ESP", "Baritone mined it", "an autoclicker",
                "nimbit steals", "exploiting a bug", "nimbit looted the chest", "thieving again", "sussy numbers", "hax", "a robbery"))
            t(!WordGate.floor(x, List.of("Quill_7", "TownFounder")).isEmpty(), "CS2-3 floor catches: " + x);
        Settings cheatLore = withOwner(s, "Rex_Owner", List.of("Rex_Owner cheats at cobble: he silk-touches it twice."));
        t(!has(FactCheck.problems(f, "<p>Rex_Owner cheats at cobble: he silk-touches it twice.</p>", D, cheatLore), "floor"),
                "CS2-3 the owner's lore, retold about the owner, still passes");
        t(has(FactCheck.problems(f, "<p>Juno_B cheats at cobble: he silk-touches it twice.</p>", D, cheatLore), "floor"), "CS2-3 never about another player");
        // CS2-4: ages set against each other
        for (String x : List.of("Quartz_Pim outplayed the teenagers again", "Quartz_Pim vs. the teens", "Quartz_Pim schooled the youngsters",
                "The youth got cooked by Quartz_Pim", "The zoomers could not keep up", "Quartz_Pim is the only grown man on the server",
                "the server's oldhead", "the old head", "the old-timer", "the elder statesman", "Uncle Quartz_Pim", "Quartz_Pim and his family",
                "Quartz_Pim's wife", "Quartz_Pim's household", "since before Kai_Ore was born", "Quartz_Pim dadded", "a 9 year old", "62 years old"))
            t(has(FactCheck.problems(f, "<p>" + x + ".</p>", D, s), "family ("), "CS2-4 family: " + x);
        t(!has(FactCheck.problems(f, "<p>Kai_Ore killed an elder guardian.</p>", D, s), "family ("), "CS2-4 elder guardian is a mob");
        // a mob's or an animal's family is a joke about the mob, in a quote or right after it; a player's family never passes
        for (String x : List.of("\"Welcome to the family,\" hissed a silverfish.", "Cave spiders, the spider's smaller cousin, took the rest.",
                "A zombie and its cousin stormed the gate.", "The spiders' cousins moved in.", "\"We are all cousins here,\" said a librarian."))
            t(!has(FactCheck.problems(f, "<p>" + x + "</p>", D, s), "family ("), "mob family passes: " + x);
        for (String x : List.of("Kai_Ore's cousin logged on.", "Juno_B and Kai_Ore are cousins.", "Kai_Ore's family watched the creeper.",
                "The Ore family fought the zombies.", "The whole family logged on.", "The family business is spiders now.", "The zombie ate their cousin.",
                "The owner's family fought the spiders.", "Survived by family and the creeper that did it.", "Kai_Ore fought a spider and its whole family.",
                "\"Kai_Ore's family logged on,\" said a villager.", "Kai_Ore's dad logged on.", "Kai_Ore's mom would be proud.", "Kai_Ore's little brother logged on.",
                "A zombie chased the family all night.", "A spider bit a cousin.", "The 6\" spider and the miner's cousin met.",
                "Zombies chased family all night.", "A spider bit cousins at the farm.", "A zombie chased the f a m i l y.",
                "\"Their cousin logged on,\" said a villager."))
            t(has(FactCheck.problems(f, "<p>" + x + "</p>", D, s), "family ("), "a player's family is held: " + x);
        t(!FactCheck.mobKin("The Cat family moved in.", List.of("Cat_3")).contains("family") && FactCheck.mobKin("The cat family moved in.", List.of("Kai_Ore")).contains("family"),
                "a mob word in a player's name is the player's");
        t(!has(FactCheck.problems(f, "<p>A zombie and its cousin got me.</p>", D, s), "family (")
                && has(FactCheck.textProblems("A zombie and its cousin got me.", s, List.of("Kai_Ore")), "family ("), "a letter's cousin is a player's: the lift is for the paper only");
        String sbReaders = Files.readString(Path.of("examples/config-teens-classic.yml"));
        t(!sbReaders.matches("(?s).*readers:[^\\n]*\\d.*"), "CS2-4 the example readers line names no age");
        // CS2-5: floor words with their endings
        for (String x : List.of("nimbit simped for the villagers", "Alex_Fan simps for TownFounder", "Alex_Fan gooned all day in the mine", "nimbit is the mogger",
                "nimbit looksmaxxed his armor", "nimbit touched grass", "Alex_Fan has no life", "nimbit goon", "nimbit hackin", "nimbit griefed", "nimbit cheatin"))
            t(!WordGate.floor(x, List.of()).isEmpty(), "CS2-5 inflections caught: " + x);
        for (String ok : List.of("a simple trade", "suspense", "Kai_Ore's stealth", "grieving", "mushroom stew", "the lagoon", "an esper", "sustained",
                "the espresso", "hacksaw", "the critter", "Kai_Ore sped past", "Free the End", "a mogul"))
            t(WordGate.floor(ok, names).isEmpty(), "CS2-5 innocent word passes: " + ok);
        // CS2-6: a name is a label
        t(StylePrompt.build(s).contains("Never joke about, explain or play on what a player's name means; a name is only a label."), "CS2-6 hard line");
        for (String x : List.of("Captain_Clumsy, living up to the name.", "Captain_Clumsy: name checks out.", "As the name suggests, a lot of eggs."))
            t(!WordGate.floor(x, List.of("Captain_Clumsy")).isEmpty(), "CS2-6 floor catches: " + x);
        // CS2-7: clean language
        for (String x : List.of("lmao nimbit folded", "lmaooo", "hella fast", "A helluva run", "got his asses handed to him", "That trade sucks", "an a-hole move"))
            t(!WordGate.bad(x, List.of(), false, "clean").isEmpty(), "CS2-7 clean catches: " + x);
        for (String x : List.of("fk that creeper", "fck that creeper", "fkn creeper", "effing creeper", "f-ing creeper", "fcking creeper"))
            t(!WordGate.bad(x, List.of(), false, "pg13").isEmpty() && WordGate.bad(x, List.of(), false, "strong").isEmpty(), "CS2-7 below strong catches: " + x);
        t(WordGate.bad("That trade sucks, lmao", List.of(), false, "pg13").isEmpty(), "CS2-7 pg13 allows sucks and lmao");
        for (String ok : List.of("a hole in the wall", "the shell", "hello", "a mob farm"))
            t(WordGate.bad(ok, List.of(), false, "clean").isEmpty(), "CS2-7 clean passes: " + ok);
        // CS2-8: emoji
        for (String e : List.of("🫦", "😏", "🍒", "🍻", "🍷", "🥂", "🥃", "🍾", "🍸", "🍹", "🍶", "💊", "💉", "🚬", "🌿"))
            t(!WordGate.floor("nimbit " + e, List.of()).isEmpty(), "CS2-8 emoji caught: " + e);
        t(WordGate.floor("nimbit 🍄", List.of()).isEmpty(), "CS2-8 the mushroom is in the game");
        // CS2-9: the tier is checked in the draft
        Settings allAgesGc = tone(tn("group-chat", "medium", "clean", "light", "all-ages"));
        t(FactCheck.problems(f, "<p>Kai_Ore's 1,874 blocks? That run was mid.</p>", D, allAgesGc).contains("tier (\"mid\")"), "CS2-9 all-ages paper with mid");
        t(!has(FactCheck.problems(f, "<p>Kai_Ore's 1,874 blocks? That run was mid.</p>", D, s), "tier ("), "CS2-9 a teens paper may say mid");
        t(!has(FactCheck.problems(f, "<p>Kai_Ore jumped in mid-air.</p>", D, allAgesGc), "tier ("), "CS2-9 mid-air is plain English");
        // FH2-1: claim words in any form
        for (String x : List.of("Sol_Dig clutched with a water bucket.", "Sol_Dig bucket clutched the drop.", "Sol_Dig pearl clutched out.", "Sol_Dig popped his totem.",
                "Sol_Dig popped two totems.", "Sol_Dig mace smashed a zombie.", "Sol_Dig got mace-smashed.", "Sol_Dig built two iron farms.",
                "Kai_Ore runs the trading halls.", "Sol_Dig got creeper'd.", "Sol_Dig one-shot a skelly.", "Sol_Dig one-shotted a skelly.",
                "Sol_Dig dodged two chicken jockeys.", "84 water buckets, every one a clutch.", "Kai_Ore third-partied the fight."))
            t(!Lexicon.claimProblems(s, f, x, names).isEmpty(), "FH2-1 claim caught: " + x);
        for (String ok : List.of("The Architecture Critic loved it.", "Kai_Ore found a critter.", "Kai_Ore broke 900 stone."))
            t(Lexicon.claimProblems(s, f, ok, names).isEmpty(), "FH2-1 not a claim: " + ok);
        // FH2-2: bodied says who won
        JsonObject pv = withDeaths("Kai_Ore was slain by Juno_B");
        pv.add("pvp", PyJson.loads("[[\"Kai_Ore\", \"Juno_B\"]]"));
        for (String ok : List.of("Juno_B bodied Kai_Ore in the duel.", "Kai_Ore got bodied by Juno_B.", "Kai_Ore got bodied."))
            t(Lexicon.claimProblems(s, pv, ok, names).isEmpty(), "FH2-2 the right way round: " + ok);
        for (String x : List.of("Kai_Ore bodied Juno_B in the duel.", "Juno_B got bodied.", "Juno_B got bodied by Kai_Ore.", "Juno_B bodied Sol_Dig."))
            t(!Lexicon.claimProblems(s, pv, x, names).isEmpty(), "FH2-2 reversed or wrong pair: " + x);
        t(StylePrompt.build(s).contains("\"pvp\" pairs are [who died, who killed them]"), "FH2-2 the Sports rule says the pair order");
        // FH2-3: a first edition says this season
        JsonObject fe = facts();
        fe.addProperty("first_edition", true);
        fe.getAsJsonObject("season_totals").add("Rex_Owner", PyJson.loads("{\"mined_deepslate_diamond_ore\": 611, \"blocks_mined\": 41275, \"trades\": 188}"));
        Lexicon.Palette pf = Lexicon.palette(s, fe);
        String pg = String.join("\n", pf.game());
        t(pg.contains("deepslate diamond ore broken this season") && pg.contains("1,000+ blocks broken this season") && pg.contains("traded this season")
                && !pg.contains("broken today") && !pg.contains("traded today"), "FH2-3 first edition: " + pf.game());
        t(Lexicon.SHIPPED.stream().anyMatch(x -> x.term().equals("redstone") && x.means().contains("say which one")), "FH2-3 redstone means");
        // FH2-4: names match whole
        JsonObject st = facts();
        st.getAsJsonObject("today").add("Steve", PyJson.loads("{\"used\": {\"ender_pearl\": 3}}"));
        st.getAsJsonObject("today").add("Steven", PyJson.loads("{\"used\": {}}"));
        st.getAsJsonObject("today").add("Pearl", PyJson.loads("{\"used\": {\"ender_pearl\": 1}}"));
        List<String> more = new ArrayList<>(names);
        more.addAll(List.of("Steve", "Steven", "Pearl"));
        t(!Lexicon.claimProblems(s, st, "Steven pearled across the ravine.", more).isEmpty(), "FH2-4 Steven is not Steve");
        t(!Lexicon.claimProblems(s, st, "Sol_Dig pearled out of the cave.", more).isEmpty(), "FH2-4 a player called Pearl backs no one else");
        t(Lexicon.claimProblems(s, st, "Steve pearled across the ravine.", more).isEmpty(), "FH2-4 Steve did");
        // FH2-5: slang that states a fact
        t(!Lexicon.claimProblems(s, f, "Wren_5 fumbled the mining lead today.", names).isEmpty(), "FH2-5 fumbled pinned on a non-leader");
        t(Lexicon.claimProblems(s, f, "Rex_Owner fumbled the mining lead today.", names).isEmpty(), "FH2-5 the real fumble");
        t(!Lexicon.claimProblems(s, f, "Sol_Dig got 35 free kills.", names).isEmpty(), "FH2-5 free with 3 deaths");
        t(Lexicon.claimProblems(s, f, "Kai_Ore got 48 free kills.", names).isEmpty(), "FH2-5 free with 0 deaths");
        t(!Lexicon.claimProblems(s, f, "Wren_5 with the rage bait.", names).isEmpty() && Lexicon.claimProblems(s, f, "Juno_B with the rage bait.", names).isEmpty(),
                "FH2-5 rage bait names the player who took and left");
        t(Lexicon.claimProblems(s, f, "Juno_B took 29 golden carrots and left 3. 29 out and 3 back is rage bait.", names).isEmpty()
                && !Lexicon.claimProblems(s, f, "Juno_B took 29 golden carrots. Wren_5 with the rage bait.", names).isEmpty()
                && !Lexicon.claimProblems(s, f, "Wren_5 took 4 bread. 29 out and 3 back is rage bait.", names).isEmpty(), "FH2-5 the verdict may follow the sentence that names the player");
        Settings fumbleAward = new Settings(Settings.defaults().paper(), s.tone(), s.owner(), s.privacy(), s.schedule(), s.writer(), s.web(), s.features(), s.alerts());
        t(Settings.defaults().paper().badAward().contains("Fumble") && !has(FactCheck.problems(f, "<h3>🤡 Fumble of the Day</h3><p>Sol_Dig died 3 times.</p>", D, fumbleAward), "claim")
                && !has(FactCheck.problems(f, "<h3>🤡 Fumble of the Day</h3><p>Sol_Dig died 3 times.</p>", D, fumbleAward), "slang"), "FH2-5 the award title is not a fumble claim");
        // FH2-6: only a mob is a mob
        for (String d : List.of("Sol_Dig was struck by lightning", "Sol_Dig was squashed by a falling anvil", "Sol_Dig was poked to death by a sweet berry bush",
                "Sol_Dig was killed by [Intentional Game Design]", "Sol_Dig was slain by a player"))
            t(!Lexicon.day("mob-death", true, withDeaths(d), false), "FH2-6 not a mob: " + d);
        t(Lexicon.day("mob-death", true, withDeaths("Sol_Dig was shot by Skeleton"), false), "FH2-6 a Skeleton is a mob");
        t(!Lexicon.day("death", List.of("Skeleton"), withDeaths("Sol_Dig was shot by Wither Skeleton"), false)
                && !Lexicon.day("death", List.of("Zombie"), withDeaths("Sol_Dig was slain by Zombie Villager"), false)
                && Lexicon.day("death", List.of("Skeleton"), withDeaths("Sol_Dig was shot by Skeleton"), false)
                && Lexicon.day("death", List.of("Zombie"), withDeaths("Sol_Dig was slain by Zombie using [Iron Sword]"), false), "FH2-6 the whole killer");
        // FH2-7: two Blotter lines are two facts
        t(StylePrompt.build(s).contains("Two Blotter lines are two separate facts"), "FH2-7 the rule");
        t(FactCheck.problems(f, "<p>Juno_B opened Rex_Owner's chest and closed that same chest.</p>", D, s).contains("\"that same\""), "FH2-7 'that same' caught");
        // cfg-r2-1: dated owner words and lore
        List<String> w1 = new ArrayList<>();
        Settings o1 = load("tone:\n  audience: teens\n  lexicon:\n    add:\n      - {term: goated, means: the best, kind: slang, tier: teens}\n"
                + "      - {term: \"the big 26\", means: this year, kind: game}\n      - {term: fam, means: the crew, kind: game}\n"
                + "owner:\n  name: Rex_Owner\n  lore:\n    - \"Rex_Owner is goated at cobble.\"\n", w1);
        t(o1.tone().lexiconAdd().isEmpty() && w1.stream().filter(x -> x.contains("dated-slang list")).count() == 4, "cfg-r2-1 dated owner words and lore warned " + w1);
        Settings forcedDated = tone(new Settings.Tone("sportscast", "spicy", "pg13", "heavy", "teens", "normal", List.of(),
                List.of(new Settings.LexTerm("fam", "the crew", "game", "all-ages")), List.of()));
        t(Lexicon.palette(forcedDated, f).game().stream().noneMatch(x -> x.startsWith("- fam:")), "cfg-r2-1 a dated word built in code is never offered");
        // cfg-r2-2: owner words against tone.language
        List<String> w2 = new ArrayList<>();
        Settings o2 = load("tone:\n  audience: teens\n  spice: spicy\n  language: clean\n  lexicon:\n    add:\n"
                + "      - {term: hell run, means: a trip through the nether, kind: game, tier: teens}\n", w2);
        t(w2.contains("tone.lexicon.add: 'hell run' has a swear but tone.language is clean, so it is never offered"), "cfg-r2-2 warned " + w2);
        t(Lexicon.palette(withTone(s, o2.tone()), f).game().stream().noneMatch(x -> x.contains("hell run")), "cfg-r2-2 never offered");
        List<String> w2b = new ArrayList<>();
        Settings o2b = load("tone:\n  audience: adults\n  spice: savage\n  language: strong\n  lexicon:\n    add:\n"
                + "      - {term: shit storm, means: a big mob fight, kind: game, tier: adults}\n", w2b);
        t(o2b.tone().lexiconAdd().size() == 1 && w2b.stream().noneMatch(x -> x.contains("shit storm")), "cfg-r2-2 an adults word on a strong paper " + w2b);
        List<String> w2c = new ArrayList<>();
        load("tone:\n  audience: teens\n  lexicon:\n    add:\n      - {term: shit storm, means: a big mob fight, kind: game, tier: teens}\n", w2c);
        t(w2c.stream().anyMatch(x -> x.contains("tier teens never prints (teens allows pg13 at most)")), "cfg-r2-2 the message names the cap " + w2c);
        // cfg-r2-3: heavy under the newsroom
        List<String> w3 = new ArrayList<>();
        load("tone:\n  audience: teens\n  spice: medium\n  language: pg13\n  slang: heavy\n", w3);
        t(w3.stream().anyMatch(x -> x.contains("newsroom voice")), "cfg-r2-3 warned " + w3);
        String readme = Files.readString(Path.of("README.md")), cfg = Files.readString(Path.of("src/main/resources/config.yml"));
        t(readme.contains("| group-chat or sportscast |") && cfg.contains("newsroom keeps it at 2"), "cfg-r2-3 docs");
        // cfg-r2-4: the mixes carry a readers line
        t(cfg.contains("readers: \"a group of friends who play on this server\"") && cfg.contains("readers: \"longtime players")
                && readme.contains("| a group of friends who play on this server |"), "cfg-r2-4 mixes name their readers");
        // cfg-r2-5: a term needs letters
        List<String> w5 = new ArrayList<>();
        Settings o5 = load("tone:\n  audience: teens\n  lexicon:\n    add:\n      - {term: \"--\", means: dash, kind: game}\n      - {term: \"' '\", means: x, kind: game}\n", w5);
        t(o5.tone().lexiconAdd().isEmpty() && w5.size() >= 2, "cfg-r2-5 punctuation-only terms refused " + w5);
        // QC7-1: reads rest, and the shaped-number frame rests
        t(Lexicon.SHIPPED.stream().filter(x -> x.kind().equals("read")).allMatch(Lexicon.Term::rests), "QC7-1 reads rest");
        List<String> u1 = Lexicon.usedWords(s, "Kai_Ore broke 118 lecterns, which is a very librarian-reroll-shaped number.", names);
        t(u1.containsAll(List.of("librarian reroll", "shaped number")), "QC7-1 used: " + u1);
        JsonObject q1 = facts();
        q1.add("recent_words", PyJson.loads("[\"librarian reroll\", \"shaped number\"]"));
        Lexicon.Palette pq = Lexicon.palette(allAgesGc, q1);
        t(pq.reads().stream().noneMatch(x -> x.startsWith("- librarian reroll")) && pq.avoid().containsAll(List.of("\"librarian reroll\"", "\"shaped number\"")),
                "QC7-1 they rest, at clean too " + pq.avoid());
        t(!StylePrompt.build(s).contains("farm-shaped") && StylePrompt.build(s).contains("never the word \"shaped\""), "QC7-1 no shaped example");
        // QC7-2: palette, K board and TPS rest; the season board only on a PvP day
        for (String g : List.of("palette", "K board", "TPS"))
            t(Lexicon.SHIPPED.stream().anyMatch(x -> x.term().equals(g) && x.rests()), "QC7-2 rests: " + g);
        t(Lexicon.palette(s, f).game().stream().noneMatch(x -> x.startsWith("- K board")) && Lexicon.palette(s, pv).game().stream().anyMatch(x -> x.startsWith("- K board")),
                "QC7-2 K board only on a PvP day");
        t(StylePrompt.build(s).contains("On a day with no PvP, skip the season board"), "QC7-2 the Sports rule");
        // QC7-3: the slang cap is counted, as style (noted, never a rewrite)
        String four = "<h2>H</h2><h3>A</h3><p>Sol_Dig folded twice.</p><h3>B</h3><p>Rex_Owner fumbled the mining lead.</p>"
                + "<h3>C</h3><p>That mining diff was real.</p><h3>D</h3><p>Kai_Ore broke 1,874. GG.</p>";
        t(has(FactCheck.style(f, four, s), "slang (4 over 3"), "QC7-3 four is over the heavy cap (noted as style): " + FactCheck.style(f, four, s));
        String five = four + "<h3>E</h3><p>Sol_Dig took the L to a zombie, 3 deaths.</p>";
        t(FactCheck.style(f, five, s).contains("slang (5 over 3: folded, fumbled, diff, gg, w / l)"), "QC7-3 over the cap: " + FactCheck.style(f, five, s));
        t(!has(FactCheck.problems(f, five, D, s), "slang ("), "QC7-3 over the cap is style, not a gate: " + FactCheck.problems(f, five, D, s));
        String two = "<h3>Sports</h3><p>The sprint diff went to Sol_Dig, 2,564 to 2,287. GG.</p>";
        t(FactCheck.style(f, two, s).contains("slang (2 in one section: diff, gg)"), "QC7-3 two in one section");
        t(!has(FactCheck.style(f, "<h3>A</h3><p>Sol_Dig folded twice.</p><h3>B</h3><p>Kai_Ore ate 13 cooked chicken.</p>", s), "slang ("), "QC7-3 within the cap");
        // QC7-4: the cooldown scan leaves player names out
        t(!Lexicon.usedWords(s, "Cooked_Kid broke 2,400 blocks. Tuff_Miner mined 300 stone.", List.of("Cooked_Kid", "Tuff_Miner")).contains("cooked"), "QC7-4 usedWords");
        Path dir = Files.createTempDirectory("voice-recent");
        LocalDate day = LocalDate.parse("2026-11-06");
        Path st0 = Files.createDirectories(dir.resolve("stats").resolve(day.toString()));
        Files.writeString(st0.resolve("usercache.json"), "[{\"uuid\":\"00000000-0000-0000-0000-00000000000a\",\"name\":\"Cooked_Kid\"}]");
        Files.writeString(st0.resolve("00000000-0000-0000-0000-00000000000a.json"), "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":1200}}}");
        Files.createDirectories(dir.resolve("editions"));
        Files.writeString(dir.resolve("editions").resolve(day.minusDays(1) + ".html"), "<p>Cooked_Kid broke 2,400 blocks.</p>");
        JsonObject built = new io.github.eastmadc.masthead.facts.FactsBuilder(dir, s).writeTo(null).build(day, java.time.LocalTime.of(5, 30));
        t(!built.has("recent_words") || !built.get("recent_words").toString().contains("cooked"), "QC7-4 a player called Cooked_Kid never rests cooked: " + built.get("recent_words"));
        // QC7-5: the theme line pushes no slang
        t(!StylePrompt.build(s).contains("\"cooked\" when"), "QC7-5 no cooked in the theme");
        // QC7-6: every example start comes round
        for (String v : List.of("newsroom", "group-chat", "sportscast")) {
            Settings vs = tone(tn(v, "spicy", "pg13", "heavy", "teens"));
            int n = (int) StylePrompt.EXAMPLES.get(v).stream().filter(e -> WordGate.bad(e, List.of(), false, "pg13").isEmpty()).count();
            java.util.Set<String> starts = new java.util.HashSet<>();
            for (int i = 0; i < n; i++) starts.add(StylePrompt.examples(vs, D.plusDays(i)).get(0));
            t(starts.size() == n, "QC7-6 " + v + ": " + n + " days, " + starts.size() + " different starts");
        }
        // QC7-7: no lore slang; tuff the block is not tuff the slang
        t(Lexicon.SHIPPED.stream().noneMatch(x -> x.term().equals("lore")), "QC7-7 lore is not shipped slang");
        List<String> u7 = Lexicon.usedWords(s, "Kai_Ore mined 386 tuff. Lore desk: the Torch Trail.", names);
        t(!u7.contains("tuff") && !u7.contains("lore") && Lexicon.usedWords(s, "That run is so tuff.", names).contains("tuff"), "QC7-7 used: " + u7);
        // QC7-8: crash out only after a mob death
        Lexicon.Term co = Lexicon.SHIPPED.stream().filter(x -> x.term().equals("crash out")).findFirst().orElseThrow();
        t(co.means().contains("never a player or the server") && co.needs().stream().anyMatch(c -> c.containsKey("mob-death")), "QC7-8 crash out");
    }

    // ------------------------------------------------------------ voice review, round 3 (each a finding id)
    static Settings removing(Settings.Tone b, List<Settings.LexTerm> add, List<String> remove) {
        return tone(new Settings.Tone(b.voice(), b.spice(), b.language(), b.slang(), b.audience(), b.length(), b.bits(), add, remove));
    }

    /** The test facts on the first date whose offer has GG (the slang offer rotates by date). */
    static JsonObject ggDay(Settings s) throws Exception {
        JsonObject f = facts();
        for (int i = 0; i < 60; i++) {
            f.addProperty("date", D.plusDays(i).toString());
            if (Lexicon.palette(s, f).slang().stream().anyMatch(x -> x.startsWith("- GG:"))) return f;
        }
        throw new AssertionError("GG is never offered");
    }

    static void review3() throws Exception {
        JsonObject f = facts();
        Settings s = teens();
        List<String> names = List.of("Sol_Dig", "Juno_B", "Kai_Ore", "Wren_5", "Rex_Owner", "Quartz_Pim");
        // cs-r3-1: an inline tag inside a word is drawn with no gap, so the gates read it with none
        for (String x : List.of("<p>Quartz_Pim is the d<em></em>ad.</p>", "<p>Quartz_Pim is the <em>d</em>ad.</p>", "<p>Kai_Ore is <strong>gy</strong>att.</p>",
                "<p>Kai_Ore is gy<em></em>att.</p>", "<p>Kai_Ore is gy<b>att</b>.</p>", "<p>Kai_Ore is gy<span>att</span>.</p>"))
            t(has(FactCheck.problems(f, x, D, s), "floor") || has(FactCheck.problems(f, x, D, s), "family"), "cs-r3-1 the fact check sees the word: " + x);
        String split = HtmlClean.clean("<p>nimbit made a <em>sh</em>itty <em>f</em>ucking play.</p>");
        t(!WordGate.bad(HtmlClean.checkText(split), names, false, "pg13").isEmpty(), "cs-r3-1 the last swear check sees the word: " + HtmlClean.checkText(split));
        t(HtmlClean.text(HtmlClean.cleanReply("<p>Quartz_Pim is the d<em></em>ad, <strong>gy</strong>att.</p>")).equals("Quartz_Pim is the dad, gyatt."),
                "cs-r3-1 a desk reply's text: " + HtmlClean.text(HtmlClean.cleanReply("<p>Quartz_Pim is the d<em></em>ad, <strong>gy</strong>att.</p>")));
        t(HtmlClean.text("<h3>Sports</h3><p><strong>Kai_Ore</strong> broke 1,874.</p>").equals("Sports Kai_Ore broke 1,874."), "cs-r3-1 block tags still a gap");
        // cs-r3-2: invisible format characters
        for (String cf : List.of("​", "­", "‌", "‍", "⁠", "﻿")) {
            String x = "Quartz_Pim is the d" + cf + "ad. nimbit is gy" + cf + "att, a sh" + cf + "itty play, n" + cf + "igga.";
            List<String> fl = WordGate.floor(x, names);
            t(fl.contains("dad") && fl.contains("gyatt") && !WordGate.bad(x, names, false, "pg13").isEmpty(), "cs-r3-2 caught with U+" + Integer.toHexString(cf.charAt(0)) + ": " + fl);
            t(!HtmlClean.clean("<p>gy" + cf + "att</p>").contains(cf), "cs-r3-2 the page drops U+" + Integer.toHexString(cf.charAt(0)));
            t(has(FactCheck.problems(f, "<p>Kai_Ore did home" + cf + "work.</p>", D, s), "\"homework\""), "cs-r3-2 banned phrases too, U+" + Integer.toHexString(cf.charAt(0)));
        }
        t(HtmlClean.clean("<p>👨‍🌾 farmer</p>").contains("‍"), "cs-r3-2 an emoji ZWJ sequence keeps its joiner");
        // cs-r3-3: person-insult swears are on the floor
        for (String x : List.of("Quartz_Pim died 4 times today, what a fatass.", "nimbit played like a total dumbass.", "tin_kettle is a jackass for that.",
                "Robin is an asshole about the K board.", "You bastard, nimbit.", "Quartz_Pim is fat and ugly.", "nimbit is so ugly.", "nimbit, you smartass.",
                "what an asshat", "an asswipe move", "nimbit is an ahole", "nimbit is a fucker", "those fuckers", "nimbit the motherfucker"))
            t(!WordGate.floor(x, List.of("Quartz_Pim", "nimbit")).isEmpty(), "cs-r3-3 floor: " + x);
        for (String ok : List.of("That run was damn fast.", "a hell of a tour", "That trade sucks.", "a badass run", "the fat chicken count went up", "the bass guitar"))
            t(WordGate.floor(ok, List.of()).isEmpty(), "cs-r3-3 still fine: " + ok);
        // cs-r3-4 / CFG-R3-1: removing a word never loosens the gates
        Settings.Tone fam = tn("group-chat", "medium", "clean", "light", "all-ages");
        Settings famRm = removing(fam, List.of(), List.of("mid", "crash out", "larping", "bodied"));
        List<String> tp = Lexicon.tierProblems(famRm, "nimbit's mining was mid. The Zombie was larping. The Skeleton crashed out.", names);
        t(tp.containsAll(List.of("tier (\"mid\")", "tier (\"larping\")", "tier (\"crash out\")")), "cs-r3-4 removed words keep their tier: " + tp);
        Settings famRe = removing(fam, List.of(new Settings.LexTerm("mid", "an average number", "slang", "all-ages")), List.of("mid"));
        t(Lexicon.tierProblems(famRe, "nimbit's mining was mid.", names).contains("tier (\"mid\")"), "cs-r3-4 remove + add never lowers the tier");
        Settings lightRm = removing(tn("sportscast", "spicy", "pg13", "light", "teens"), List.of(), List.of("cooked"));
        String three = "<h3>A</h3><p>Sol_Dig folded twice.</p><h3>B</h3><p>Sol_Dig got cooked by a Zombie.</p><h3>C</h3><p>Sol_Dig won 2,564 to 2,287, GG to the field.</p>";
        t(has(Lexicon.slangProblems(lightRm, three, names), "slang (3 over 2"), "CFG-R3-1 a removed word still counts toward the cap: " + Lexicon.slangProblems(lightRm, three, names));
        t(Lexicon.usedWords(lightRm, "Sol_Dig got cooked.", names).contains("cooked"), "CFG-R3-1 a removed word still rests");
        t(FactCheck.problems(f, "<p>Kai_Ore's 1,874 blocks? Sol_Dig got cooked by a Zombie.</p>", D, lightRm).contains("removed (\"cooked\")"),
                "CFG-R3-1 a removed slang word in a draft goes back");
        t(!has(FactCheck.problems(f, "<p>Rex_Owner crafted 47 redstone.</p>", D, removing(s.tone(), List.of(), List.of("redstone"))), "removed"),
                "CFG-R3-1 a removed game word is still the item's name");
        // FH-R3-1: bodied with a made-up victim
        JsonObject k1 = facts();
        k1.getAsJsonObject("today").getAsJsonObject("Juno_B").addProperty("player_kills", 1);
        for (String x : List.of("Juno_B bodied Kai_Ore.", "Kai_Ore got bodied by Juno_B.", "Wren_5 got bodied by Juno_B."))
            t(!Lexicon.claimProblems(s, k1, x, names).isEmpty(), "FH-R3-1 a kill count names no victim: " + x);
        t(Lexicon.claimProblems(s, k1, "Juno_B bodied someone today.", names).isEmpty(), "FH-R3-1 the killer alone is fine");
        JsonObject first = facts();
        first.addProperty("first_edition", true);
        List<String> fn = List.of("Moss_T", "Juno_B", "Sol_Dig");
        t(!Lexicon.claimProblems(s, first, "Moss_T bodied Sol_Dig.", fn).isEmpty(), "FH-R3-1 first edition: the board names no victim");
        t(Lexicon.claimProblems(s, first, "Moss_T bodied 7 players this season.", fn).isEmpty(), "FH-R3-1 first edition: the board backs the killer");
        JsonObject hid = facts();
        hid.getAsJsonObject("today").getAsJsonObject("Juno_B").addProperty("player_kills", 2);
        hid.add("pvp", PyJson.loads("[[\"Kai_Ore\", \"Juno_B\"]]"));   // the second kill's victim is hidden, so it has no pair
        t(Lexicon.claimProblems(s, hid, "Juno_B bodied Kai_Ore.", names).isEmpty() && !Lexicon.claimProblems(s, hid, "Juno_B bodied Wren_5.", names).isEmpty(),
                "FH-R3-1 a hidden victim's kill backs no other victim");
        // FH-R3-2: a mace kill says who won
        JsonObject m2 = withDeaths("Kai_Ore was smashed by Juno_B");
        m2.add("pvp", PyJson.loads("[[\"Kai_Ore\", \"Juno_B\"]]"));
        for (String x : List.of("Kai_Ore mace smashed Juno_B.", "Juno_B got mace smashed by Kai_Ore."))
            t(!Lexicon.claimProblems(s, m2, x, names).isEmpty(), "FH-R3-2 reversed: " + x);
        for (String ok : List.of("Juno_B mace smashed Kai_Ore.", "Kai_Ore got mace smashed by Juno_B."))
            t(Lexicon.claimProblems(s, m2, ok, names).isEmpty(), "FH-R3-2 the right way round: " + ok);
        // FH-R3-3: ordinary English is not a claim
        for (String ok : List.of("Sol_Dig threw 947 eggs, a whole clutch of eggs gone.", "Sol_Dig had one shot at the blocks board.", "Two clutches of eggs."))
            t(Lexicon.claimProblems(s, f, ok, names).isEmpty(), "FH-R3-3 idiom: " + ok);
        for (String x : List.of("Sol_Dig clutched the drop.", "Sol_Dig one-shot a skelly.", "What a clutch."))
            t(!Lexicon.claimProblems(s, f, x, names).isEmpty(), "FH-R3-3 still a claim: " + x);
        // FH-R3-4: backed claim words are never cut
        List<Settings.LexTerm> twelve = new ArrayList<>();
        for (String w : List.of("spleef", "parkour", "nether hub", "ice road", "rail line", "sky bridge", "end portal", "mob tower", "wheat field", "kelp line", "beacon", "sugar cane"))
            twelve.add(new Settings.LexTerm(w, "a thing on the server", "game", "all-ages"));
        Settings busy = removing(s.tone(), twelve, List.of());
        t(Lexicon.palette(busy, hid).game().stream().anyMatch(x -> x.startsWith("- bodied:")), "FH-R3-4 bodied offered on a busy day: " + Lexicon.palette(busy, hid).game());
        t(!(StylePrompt.build(s) + StylePrompt.daily(s, f)).contains("the play didn't happen"), "FH-R3-4 the prompt never says a play didn't happen");
        // FH-R3-5: a guessed gender
        t(has(FactCheck.problems(f, "<p>Rex_Owner got out-dug by Sol_Dig herself.</p>", D, s), "gender"), "FH-R3-5 herself about Sol_Dig");
        t(has(FactCheck.problems(f, "<p>Kai_Ore broke 1,874 blocks with his pickaxe.</p>", D, s), "gender"), "FH-R3-5 his about Kai_Ore");
        t(!has(FactCheck.problems(f, "<p>Rex_Owner mined his own stone, 1,800 of it.</p>", D, s), "gender"), "FH-R3-5 the owner's own pronoun");
        t(!has(FactCheck.problems(f, "<p>The Zombie lost his head.</p>", D, s), "gender"), "FH-R3-5 no player named");
        t(!has(FactCheck.problems(f, "<p>Juno_B opened Rex_Owner's barrels 4 times and a chest of his twice.</p>", D, s), "gender")
                && !has(FactCheck.problems(f, "<p>Rex_Owner crafted 22 rails. Juno_B broke 355 blocks to his 2,287.</p>", D, s), "gender"),
                "FH-R3-5 the owner's pronoun where the owner is named");
        t(has(FactCheck.problems(f, "<p>Juno_B opened Rex_Owner's barrels and her own chest.</p>", D, s), "gender (\"her\")"), "FH-R3-5 any other pronoun is a guess");
        t(has(FactCheck.problems(f, "<p>Juno_B broke 355 blocks with his shovel.</p>", D, s), "gender"), "FH-R3-5 a non-owner alone");
        // a man, a guy: about the owner too (owner.pronoun is for pronouns only); mob, item and idiom compounds pass
        t(has(FactCheck.problems(f, "<p>Rex_Owner dug 640 gravel, big numbers for a man of the coop.</p>", D, s), "gender (\"man\")")
                && has(FactCheck.problems(f, "<p>Sol_Dig traded with a guy at the market.</p>", D, s), "gender (\"guy\")"), "person nouns: a man, a guy");
        for (String ok : List.of("Kai_Ore beat 4 Enderman and 2 Endermen.", "Sol_Dig built a snowman.", "Juno_B crossed no man's land.", "Kai_Ore ate like Pac-Man.",
                "Sol_Dig dug a man-made lake.", "Rex_Owner went full Iron Man.", "Juno_B, the madman, beat the middleman.", "Creeper, aw man."))
            t(!has(FactCheck.problems(f, "<p>" + ok + "</p>", D, s), "gender"), "person nouns: passes: " + ok);
        JsonObject held = PyJson.loads("{\"deaths\": [\"Kai_Ore was slain by Sol_Dig using [Old Man Sword]\"]}").getAsJsonObject();
        t(FactCheck.personProblems(s, held, "Kai_Ore swung the Old Man Sword.", names).isEmpty()
                && FactCheck.personProblems(s, held, "Kai_Ore is a girl.", names).equals(List.of("gender (\"girl\")")), "person nouns: a word the facts hold passes");
        // CFG-R3-2: YAML 1.1 booleans in the lexicon
        List<String> wb = new ArrayList<>();
        Settings ob = load("tone:\n  audience: teens\n  lexicon:\n    add:\n      - {term: yes, means: agreement, kind: slang, tier: teens}\n"
                + "      - {term: grind day, means: on, kind: game}\n    remove: [no, mid]\n", wb);
        t(ob.tone().lexiconAdd().isEmpty() && !ob.tone().lexiconRemove().contains("false") && ob.tone().lexiconRemove().contains("mid")
                && wb.stream().filter(x -> x.contains("put the term in quotes")).count() == 3, "CFG-R3-2 yes/no/on warned " + wb + " " + ob.tone().lexiconRemove());
        // CFG-R3-3: 'what we call' is a meaning
        List<String> wc = new ArrayList<>();
        Settings oc = load("tone:\n  audience: teens\n  lexicon:\n    add:\n      - {term: chunk banger, means: \"what we call a big build day\", kind: slang, tier: teens}\n"
                + "      - {term: spleef king, means: \"call them the champ\", kind: slang, tier: teens}\n", wc);
        t(oc.tone().lexiconAdd().size() == 1 && oc.tone().lexiconAdd().get(0).term().equals("chunk banger"), "CFG-R3-3 'what we call' kept " + wc);
        t(wc.stream().anyMatch(x -> x.contains("spleef king") && x.contains("'call them'")), "CFG-R3-3 the warning names the word " + wc);
        // CFG-R3-4: removing a form removes its word
        List<String> wf = new ArrayList<>();
        load("tone:\n  audience: teens\n  lexicon:\n    remove: [hoppers, mid]\n", wf);
        t(wf.contains("tone.lexicon.remove: 'hoppers' is part of 'redstone'; the whole word is removed") && wf.stream().noneMatch(x -> x.contains("'mid' is part")),
                "CFG-R3-4 warned " + wf);
        t(Lexicon.shippedTermFor("l").equals("W / L") && Lexicon.shippedTermFor("mid").equals("mid") && Lexicon.shippedTermFor("nope") == null, "CFG-R3-4 shippedTermFor");
        // QC-R3-1, QC-R3-2, QC-R3-6: frames that rest
        t(!StylePrompt.BUILDING_RULE.contains("cobble"), "QC-R3-1 the Building rule names no block");
        List<String> u = Lexicon.usedWords(s, "That's 810 cobble, so grey is the whole plan, a full commitment. It looks a lot like a storage day. That's the paper. Go feed your chickens.", names);
        t(u.containsAll(List.of("grey", "commitment", "looks a lot like", "that's the paper", "feed your")), "QC-R3 frames rest: " + u);
        JsonObject rf = facts();
        rf.add("recent_words", PyJson.loads("[\"gray\", \"that's the news\", \"look a lot like\"]"));
        t(Lexicon.palette(s, rf).avoid().containsAll(List.of("\"gray\"", "\"that's the news\"", "\"look a lot like\"")), "QC-R3 they go on AVOID");
        String sp = StylePrompt.build(s);
        t(sp.contains("a one-line sign-off that calls back to today's lead story (never an order to the readers)"), "QC-R3-2 the sign-off spec");
        t(!sp.contains("how the number looks") && !StylePrompt.daily(s, f).contains("how the number looks"), "QC-R3-6 the reads rule gives no fixed frame");
        // QC-R3-3: the 'not X, that's Y' frame
        t(StylePrompt.EXAMPLES.values().stream().flatMap(List::stream).noneMatch(x -> x.contains("Not a race, a lap")), "QC-R3-3 the example is gone");
        t(Lexicon.usedWords(s, "That's not a trade, that's a statement.", names).contains(Lexicon.CONTRAST), "QC-R3-3 it rests");
        String twice = "<p>Juno_B took 29. That's not a trade, that's a statement.</p><p>Wren_5 left 3. That is not a refund. That is a receipt.</p>";
        t(FactCheck.style(f, twice, s).contains("\"" + Lexicon.CONTRAST + "\" (more than once)"), "QC-R3-3 twice in a paper: " + FactCheck.style(f, twice, s));
        t(!has(FactCheck.problems(f, twice, D, s), "\"" + Lexicon.CONTRAST), "QC-R3-3 twice is style, not a gate");
        t(!has(FactCheck.style(f, "<p>Juno_B took 29. That's not a trade, that's a statement.</p>", s), "\"" + Lexicon.CONTRAST), "QC-R3-3 once is fine");
        t(!Lexicon.usedWords(s, "Not a single death, and 40 ore.", names).contains(Lexicon.CONTRAST), "QC-R3-3 a plain 'not a' is not the frame");
        // QC-R3-4: the closing GG follows the offer
        t(!sp.contains("closing \"GG.\""), "QC-R3-4 not in the cached VOICE block");
        JsonObject ggf = ggDay(s);
        t(StylePrompt.daily(s, ggf).contains("closing \"GG.\""), "QC-R3-4 offered today: in the daily part");
        JsonObject gg = ggDay(s);
        gg.add("recent_words", PyJson.loads("[\"gg\"]"));
        t(!StylePrompt.daily(s, gg).contains("closing \"GG.\""), "QC-R3-4 resting: no GG invitation");
        t(!StylePrompt.daily(removing(s.tone(), List.of(), List.of("GG")), ggf).contains("closing \"GG.\""), "QC-R3-4 removed: no GG invitation");
        t(FactCheck.style(gg, "<p>Sol_Dig won 2,564 to 2,287. GG.</p>", s).contains("slang (resting: gg)")
                && !has(FactCheck.problems(gg, "<p>Sol_Dig won 2,564 to 2,287. GG.</p>", D, s), "resting"), "QC-R3-4 a resting word in a draft is noted as style");
        t(FactCheck.problems(f, "<p>Sol_Dig won 2,564 to 2,287. GG.</p>", D, removing(s.tone(), List.of(), List.of("GG"))).contains("removed (\"GG\")"),
                "QC-R3-4 a removed word in a draft goes back");
        // QC-R3-5: a slang word left alone as a sentence
        String tag = "<h3>Horoscopes</h3><p>Kai_Ore, 177 torches crafted: Mars says the mobs are spawning somewhere else now. Let them cook.</p>";
        t(FactCheck.style(f, tag, s).contains("slang tag (\"let them cook\")") && !has(FactCheck.problems(f, tag, D, s), "slang tag"), "QC-R3-5 a tag is style: " + FactCheck.style(f, tag, s));
        t(!has(FactCheck.style(ggf, "<p>Sol_Dig won 2,564 to 2,287. GG.</p>", s), "slang tag"), "QC-R3-5 one offered closing GG is fine");
        t(has(FactCheck.style(ggf, "<p>Sol_Dig won 2,564 to 2,287. GG.</p><p>Kai_Ore broke 1,874. GG.</p>", s), "slang tag (\"gg\")"), "QC-R3-5 only one");
        t(!has(FactCheck.style(f, "<p>Sol_Dig folded twice.</p>", s), "slang tag"), "QC-R3-5 a sentence with a player in it is not a tag");
        t(Lexicon.SHIPPED.stream().anyMatch(x -> x.term().equals("let them cook") && x.means().contains("never about mobs or items")), "QC-R3-5 let them cook means");
        // QC-R3-8: grown-up life
        for (String x : List.of("resume", "résumé", "payroll", "office", "bureau", "lawyer", "court", "commute", "retirement", "committee", "town council"))
            t(has(FactCheck.problems(f, "<p>The list looks like a " + x + " of the game.</p>", D, s), "\"" + WordGate.norm(x) + "\""), "QC-R3-8 caught: " + x);
        t(has(FactCheck.problems(f, "<p>Also on the record: 4 deaths.</p>", D, s), "\"on the record\"") && has(FactCheck.problems(f, "<p>For the record, 4.</p>", D, s), "\"for the record\""),
                "QC-R3-8 on/for the record");
        t(!has(FactCheck.problems(f, "<p>Kai_Ore resumed mining. The courtyard got 40 more blocks.</p>", D, s), "\"resume\"")
                && !has(FactCheck.problems(f, "<p>Kai_Ore resumed mining. The courtyard got 40 more blocks.</p>", D, s), "\"court\""), "QC-R3-8 longer words pass");
    }

    static void snap(String name, String text) throws Exception {
        Path g = GOLD.resolve(name);
        if (System.getenv("UPDATE_GOLDEN") != null) { Files.writeString(g, text); t(true, "snapshot written: " + g); return; }
        String want = Files.isRegularFile(g) ? Files.readString(g) : "";
        boolean same = want.equals(text);
        if (!same) {
            String[] a = want.split("\n", -1), b = text.split("\n", -1);
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                String x = i < a.length ? a[i] : "<none>", y = i < b.length ? b[i] : "<none>";
                if (!x.equals(y)) { System.out.println("  first difference at line " + (i + 1) + ":\n    want: " + x + "\n    got:  " + y); break; }
            }
        }
        t(same, "prompt snapshot matches " + g + " (UPDATE_GOLDEN=1 to rewrite after a deliberate change)");
    }
    // ------------------------------------------------------------ the classic voice (the short prompt)
    static void classic() throws Exception {
        Settings s = tone(tn("classic", "spicy", "pg13", "heavy", "teens"));
        List<String> warn = new ArrayList<>();
        t(load("tone:\n  voice: classic\n", warn).tone().effectiveVoice().equals("classic") && warn.stream().noneMatch(x -> x.contains("voice")), "classic: tone.voice classic loads " + warn);
        JsonObject f = facts();
        String sys = StylePrompt.build(s), full = StylePrompt.prompt(s, f);
        t(sys.contains("funniest person in the group chat") && sys.contains("In-world quotes are allowed") && sys.contains("deadpan police-blotter voice")
                && sys.contains("classified-intel"), "classic: the first papers' voice (group-chat editor, hen quotes, blotter voice, classified desk)");
        // a line handed over as an example came back in nearly every paper, so the instructions hand over none
        // (the weather's "TPS = conditions" became the same opening every day)
        for (String line : List.of("A resident reported", "Officers are looking into", "No arrests were made", "asked not to be named", "a gift", "the barn is insured", "clear skies", "is in your",
                "TPS = conditions"))
            t(!sys.contains(line), "classic: no ready-made line in the instructions: " + line);
        t(!full.contains("WORDS FOR TODAY") && !full.contains("EXAMPLES") && !full.contains("Slang: at most") && !full.contains("2-3 mild swears") && !full.contains("(max "),
                "classic: no examples and no slang or swear quotas");
        // "own server" twice is a tic gate for the other voices (their prompt names it); the classic prompt does not, so it is no gate here
        String twice = "<p>Rex_Owner lost on his own server.</p><p>Rex_Owner won on his own server.</p>";
        t(!has(FactCheck.problems(f, twice, D, s), "\"own server\"") && has(FactCheck.problems(f, twice, D, teens()), "\"own server\""), "classic: own server twice is not a gate");
        // tone.slang picks the line ("heavy" used to do nothing)
        String off = StylePrompt.build(tone(tn("classic", "spicy", "pg13", "off", "teens"))), light = StylePrompt.build(tone(tn("classic", "spicy", "pg13", "light", "teens")));
        t(off.contains("- Slang: none.") && !off.contains("slang: a little") && !off.contains("HEAVY"), "classic: slang off says none");
        t(light.contains("- Teen slang: a little") && !light.contains("HEAVY") && !light.contains("Slang: none"), "classic: slang light is the first papers' line");
        t(StylePrompt.build(tone(tn("classic", "mild", "clean", "light", "all-ages"))).contains("- Kid-friendly gamer slang: a little"), "classic: slang light follows the audience");
        t(sys.contains("- Slang: HEAVY, and lean all the way in. It should read like the funniest kid in the server's group chat") && sys.contains("ngl") && sys.contains("bold but never the facts") && !sys.contains("bro thought") && !sys.contains(" bro,") && sys.contains("respawn, speedrun") && sys.contains("lowkey, highkey") && sys.contains("Never end a sentence on a slang tag")
                && sys.contains("spawn camping") && sys.contains("chicken jockey") && sys.contains("not even \"ironically\"") && sys.contains("skill issue") && sys.contains("mad lad")
                && !sys.contains("sweaty") && !sys.contains("Teen slang: a little") && sys.contains("screenshotted into the group chat") && sys.contains("skipped every earlier issue") && !sys.contains("DO continue"),
                "classic: slang heavy talks like the readers (their slang, no tags), keeps the never-plays, the slang floor, the insults and the AI tics");
        t(StylePrompt.build(tone(tn("classic", "savage", "strong", "heavy", "adults"))).contains("the funniest player in the server's group chat"), "classic: slang heavy follows the audience");
        t(!light.contains("screenshotted") && light.contains("sweaty"), "classic: slang light keeps the dated words and the old comedy line");
        // heavy: the slang words pass the gates; light and the AI tics still don't
        String speak = "<h2>Rex_Owner Mines</h2><h3>Front page</h3><p>Rex_Owner stayed locked in, and the creeper was lowkey cooked fr.</p>";
        List<String> hp = FactCheck.problems(f, speak, D, s), lp = FactCheck.problems(f, speak, D, tone(tn("classic", "spicy", "pg13", "light", "teens")));
        t(!has(hp, "dated") && !has(hp, "locked in"), "classic: slang heavy lets fr and locked in through: " + hp);
        t(has(lp, "dated (\"fr\")") && has(lp, "\"locked in\""), "classic: slang light still refuses them: " + lp);
        t(has(FactCheck.problems(f, "<h2>A</h2><p>Totally unhinged, a mad lad.</p>", D, s), "\"mad lad\"") && !has(FactCheck.problems(f, "<h2>A</h2><p>Totally unhinged.</p>", D, s), "\"unhinged\""),
                "classic: slang heavy allows unhinged, still refuses the other AI tics");
        t(has(FactCheck.problems(f, "<h2>A</h2><p>Totally unhinged.</p>", D, tone(tn("classic", "spicy", "pg13", "light", "teens"))), "\"unhinged\""), "classic: slang light still refuses unhinged");
        // the floor scales with the tone: trash talk at savage for teens/adults, sus at savage for adults, drinking for adults
        Settings kids = tone(tn("classic", "savage", "clean", "light", "all-ages")), teenSpicy = tone(tn("classic", "spicy", "pg13", "heavy", "teens")),
                teenSavage = tone(tn("classic", "savage", "pg13", "heavy", "teens")), adultSavage = tone(tn("classic", "savage", "strong", "heavy", "adults")),
                adultMild = tone(tn("classic", "mild", "clean", "light", "adults"));
        String trash = "<h2>A</h2><p>Sol_Dig fell off a ledge again, skill issue, washed.</p>", sus = "<h2>A</h2><p>Sol_Dig died 40 times. Sus.</p>", drink = "<h2>A</h2><p>The hen got drunk 🍺.</p>";
        java.util.function.BiFunction<String, Settings, Boolean> floored = (x, st) -> FactCheck.problems(f, x, D, st).stream().anyMatch(p -> p.startsWith("floor"));
        t(floored.apply(trash, kids) && floored.apply(trash, teenSpicy) && !floored.apply(trash, teenSavage) && !floored.apply(trash, adultSavage) && floored.apply(trash, adultMild),
                "floor scales: trash talk only at savage, never all-ages");
        t(floored.apply(sus, kids) && floored.apply(sus, teenSavage) && !floored.apply(sus, adultSavage), "floor scales: sus only at savage for adults");
        for (String ore : List.of("Sol_Dig mined 40 diamonds. Sus.", "Sol_Dig's luck is suspiciously good after 40 deaths.", "Sol_Dig is sus."))
            t(floored.apply("<h2>A</h2><p>" + ore + "</p>", adultSavage), "floor scales: sus never on ore, loot or luck, and only on a number: " + ore);
        t(floored.apply(drink, teenSavage) && !floored.apply(drink, adultMild) && !floored.apply(drink, adultSavage), "floor scales: drinking only for adults");
        for (String fixed : List.of("Sol_Dig is a hacker.", "Sol_Dig x-rayed it.", "Sol_Dig got high.", "Sol_Dig has rizz.", "Sol_Dig should touch grass.", "Sol_Dig is a bozo.", "What a dirt hut.", "Sol_Dig is so ugly."))
            t(floored.apply("<h2>A</h2><p>" + fixed + "</p>", adultSavage), "floor holds at the loosest setting: " + fixed);
        String tp = StylePrompt.build(teenSavage), sp = StylePrompt.build(teenSpicy), ap = StylePrompt.build(adultSavage);
        t(tp.contains("Gamer trash talk (skill issue") && !tp.contains("Never: ez, noob") && tp.contains("suspicious, stolen") && tp.contains("no drug or drinking jokes"),
                "prompt scales: teens savage gets trash talk, keeps sus and drinking out");
        t(!sp.contains("Gamer trash talk") && sp.contains("Never: ez, noob, skill issue"), "prompt scales: teens spicy keeps trash talk out");
        t(ap.contains("A \"sus\" joke is fine when it rides on a real number, never on ore") && ap.contains("no drug jokes") && !ap.contains("no drug or drinking jokes"), "prompt scales: adults savage gets sus and drinking");
        // the tone reaches every AI call: the other voices and the editor desk
        Settings caster = tone(tn("sportscast", "savage", "strong", "heavy", "adults")), casterTeen = tone(tn("sportscast", "savage", "pg13", "heavy", "teens"));
        String cp = StylePrompt.build(caster), ctp = StylePrompt.build(casterTeen);
        t(cp.contains("- Audience: ADULTS.") && cp.contains("Gamer trash talk") && cp.contains("no drug slang") && cp.contains("A \"sus\" joke is fine")
                && !ctp.contains("Audience: ADULTS") && ctp.contains("Gamer trash talk") && ctp.contains("no drug or drinking slang") && !ctp.contains("\"sus\" joke"),
                "other voices: adults line, trash talk, drinking and sus follow the tone");
        String dk = MailDesk.rules(adultSavage), dt = MailDesk.rules(teenSavage), dl = MailDesk.rules(tone(tn("classic", "spicy", "pg13", "light", "teens")));
        t(dk.contains("Audience: ADULTS.") && dk.contains("readers' own words") && dk.contains("Gamer trash talk") && dk.contains("\"sus\" joke"),
                "editor desk: adult replies, heavy slang, trash talk and sus");
        t(!dt.contains("Audience: ADULTS") && dt.contains("readers' own words") && !dt.contains("\"sus\" joke") && dl.contains("not slang") && !dl.contains("readers' own words"),
                "editor desk: teens get heavy slang without the adult extras; light keeps \"not slang\"");
        // the heavy slang list follows tone.lexicon
        List<String> lw = new ArrayList<>();
        Settings lex = load("tone:\n  voice: classic\n  audience: teens\n  spice: savage\n  language: pg13\n  slang: heavy\n  lexicon:\n    remove: [lowkey]\n    add:\n"
                + "      - {term: \"skrrt\", means: \"a fast getaway\", kind: slang, tier: teens}\n      - {term: \"bar tab\", means: \"what you owe\", kind: slang, tier: adults}\n", lw);
        String lexp = StylePrompt.build(lex);
        t(lexp.contains("highkey") && !lexp.contains("(lowkey") && !lexp.contains(" lowkey,") && lexp.contains("skrrt") && !lexp.contains("bar tab"), "heavy slang list: remove takes out, add puts in (by tier): " + lw);
        t(has(FactCheck.problems(f, "<h2>A</h2><p>Sol_Dig was lowkey cooked.</p>", D, lex), "removed (\"lowkey\")") && !has(FactCheck.problems(f, "<h2>A</h2><p>Sol_Dig was highkey cooked.</p>", D, lex), "removed"),
                "heavy slang: a removed word goes back");
        t(ap.contains("- Language: STRONG. Use 3-4 swears") && ap.contains("- Audience: ADULTS.") && !tp.contains("Audience: ADULTS") && !tp.contains("3-4 swears")
                && StylePrompt.build(adultMild).contains("- Audience: ADULTS.") && !StylePrompt.build(adultMild).contains("3-4 swears"),
                "classic: adults get the adult line, strong gets a swear target, teens get neither");
        t(has(FactCheck.problems(f, "<p>Sol_Dig, no cap.</p>", D, tone(tn("caster", "spicy", "pg13", "heavy", "teens"))), "dated"), "slang heavy in another voice keeps the dated gate");
        t(full.contains("Now write today's paper.") && !full.contains("NEVER USE"), "classic: slang heavy adds no word list");
        Settings rm = tone(new Settings.Tone("classic", "spicy", "pg13", "heavy", "teens", "normal", List.of("obituaries"), List.of(), List.of("mid")));
        t(StylePrompt.prompt(rm, f).contains("NEVER USE (this server turned them off): \"mid\""), "classic: words the server turned off are named (the gate refuses them)");
        String lots = "<h2>A</h2><h3>Front page</h3><p>Kai_Ore folded 3 times, cooked, then took the L. GG.</p>";
        t(FactCheck.style(f, lots, s).stream().noneMatch(x -> x.matches("slang \\(\\d.*")) && FactCheck.style(f, lots, teens()).stream().anyMatch(x -> x.matches("slang \\(\\d.*")),
                "classic: no slang count to note (the other voices keep theirs)");
        t(sys.length() < 16000, "classic: the instructions stay short (" + sys.length() + " chars)");
        t(sys.contains("Never mention times of day") && sys.contains("who is anyone's parent") && sys.contains("they/them"), "classic: the hard lines stay");
        t(sys.contains("golden_egg and headless_chicken are data keys; call the awards only by their headings."), "P2 classic: the award keys are not award names");

        JsonObject d = PyJson.loads("""
                {"today":{"Rex_Owner":{"play_min":187.3,"walk_m":10.6},"Player_1":{"play_min":812.6,"blocks_mined":5811}},
                 "week":{"Rex_Owner":{"play_min":900}},"season_totals":{"Rex_Owner":{"play_min":6150},"Player_1":{"play_min":1945}},
                 "leaders":{"play_min":[["Player_1",1945]]},"leaders_today":{"play_min":[["Player_1",812.6]]},
                 "owner":{"name":"Rex_Owner","today":{"play_min":187.3,"damage_taken":0.0},"week":{"play_min":900}},
                 "server":{"tps_min":19.5}}""").getAsJsonObject();
        JsonObject tr = ClassicPrompt.trim(s, d);
        String j = PyJson.dumpsCompact(tr);
        t(!tr.getAsJsonObject("today").getAsJsonObject("Player_1").has("play_min") && !tr.getAsJsonObject("season_totals").getAsJsonObject("Player_1").has("play_min")
                && !tr.getAsJsonObject("leaders").has("play_min") && !tr.getAsJsonObject("leaders_today").has("play_min"),
                "classic: nobody's play time but the owner's reaches the writer: " + j);
        t(tr.getAsJsonObject("today").getAsJsonObject("Rex_Owner").get("play_min").getAsString().equals("187")
                && !tr.getAsJsonObject("week").getAsJsonObject("Rex_Owner").has("play_min") && !tr.getAsJsonObject("owner").getAsJsonObject("week").has("play_min"),
                "classic: the owner's minutes are whole, and a week over 720 goes: " + j);
        t(j.contains("\"walk_m\":11") && j.contains("\"damage_taken\":0}") && j.contains("\"tps_min\":19.5"), "classic: decimals rounded, the server line untouched: " + j);
        t(d.getAsJsonObject("today").getAsJsonObject("Player_1").has("play_min"), "classic: trim leaves the input alone");

        String html = "<h2>Rex_Owner Mines</h2><h3>Front page</h3><p>The bureau has opened a file, and the town council has questions.</p>";
        t(!has(FactCheck.problems(f, html, D, s), "\"bureau\"") && !has(FactCheck.problems(f, html, D, s), "\"town council\""), "classic: the officialese is allowed");
        t(has(FactCheck.problems(f, html, D, teens()), "\"bureau\""), "classic: the other voices still ban it");
        t(has(FactCheck.problems(f, "<h2>A</h2><h3>Police Blotter</h3><p>That same chest was opened.</p>", D, s), "\"that same\""), "classic: the Blotter order ban stays");
        // false alarms loosened; the real hits stay
        t(!has(FactCheck.problems(f, "<h2>A</h2><h3>Obituaries</h3><p>Sol_Dig fell, with 19 gold ingots crafted that same shift.</p><h3>Police Blotter</h3><p>Sol_Dig opened a chest.</p>", D, s), "\"that same\""),
                "classic: \"that same\" outside the Blotter is fine");
        t(FactCheck.problems(f, "<h2>A</h2><p>A hedge has started sleeping with one eye open. The Commission stood up and clapped. Sol_Dig won, edging Rex_Owner 31 to 30, edging out the field.</p>", D, s).stream().noneMatch(x -> x.startsWith("floor")),
                "floor: idioms and a close win pass: " + FactCheck.problems(f, "<p>A hedge has started sleeping with one eye open. The Commission stood up and clapped. Sol_Dig won, edging Rex_Owner 31 to 30.</p>", D, s));
        for (String bad : List.of("Sol_Dig slept with the lights on.", "Sol_Dig was edging all day.", "Sol_Dig got clapped.", "Sol_Dig is sleeping with Rex_Owner."))
            t(FactCheck.problems(f, "<h2>A</h2><p>" + bad + "</p>", D, s).stream().anyMatch(x -> x.startsWith("floor")), "floor still catches: " + bad);
        t(!has(FactCheck.problems(f, "<h2>A</h2><p>Sol_Dig won, and a local hen handed over her own egg.</p>", D, s), "gender"), "gender: a pronoun after an animal is the animal's");
        t(has(FactCheck.problems(f, "<h2>A</h2><p>A local hen watched Sol_Dig win her match.</p>", D, s), "gender"), "gender: a pronoun after a player still counts");
    }

    // ------------------------------------------------------------ review round 4: the gates hold what the settings promise
    static void review4() throws Exception {
        JsonObject f = facts();
        Settings s = tone(tn("classic", "spicy", "pg13", "heavy", "teens")), caster = teens();
        Settings teenSavage = tone(tn("classic", "savage", "pg13", "heavy", "teens")), adultSavage = tone(tn("classic", "savage", "strong", "heavy", "adults")),
                adultMild = tone(tn("classic", "mild", "clean", "light", "adults"));
        // he/she: a mob word earlier in the clause never excuses a pronoun about the player
        for (String g : List.of("Sol_Dig walked into a creeper and lost all his levels.", "Sol_Dig traded with a villager and spent all his emeralds.",
                "Sol_Dig was shot by a skeleton while he was mining."))
            for (Settings st : List.of(s, caster))
                t(has(FactCheck.problems(f, "<h2>A</h2><p>" + g + "</p>", D, st), "gender"), "gender: a mob word in the player's clause is no excuse: " + g);
        t(!has(FactCheck.problems(f, "<h2>A</h2><p>Sol_Dig won; a cow chewed her cud.</p>", D, s), "gender"), "gender: an animal's own clause keeps its pronoun");

        // a player's own letter gets the whole floor at every tone; the desk's reply keeps the lift
        JsonObject mail = new JsonObject();
        mail.add("season_totals", f.get("season_totals"));
        mail.add("mailbag_candidates", PyJson.loads("""
                [{"id": "2026-11-06-000000d1", "from": "Kai_Ore", "kind": "letter", "text": "Sol_Dig is a washed noob, ez"},
                 {"id": "2026-11-06-000000d2", "from": "Kai_Ore", "kind": "letter", "text": "Sol_Dig finding that much is very suspicious"},
                 {"id": "2026-11-06-000000d3", "from": "Kai_Ore", "kind": "letter", "text": "Sol_Dig plays drunk"},
                 {"id": "2026-11-06-000000d4", "from": "Kai_Ore", "kind": "letter", "text": "the paper is good"}]"""));
        for (Settings st : List.of(teenSavage, adultSavage, adultMild))
            for (String[] l : new String[][]{{"d1", "Sol_Dig is a washed noob, ez"}, {"d2", "Sol_Dig finding that much is very suspicious"}, {"d3", "Sol_Dig plays drunk"}}) {
                MailDesk.Result r = MailDesk.validate(mail, desk(l[0], l[1], "Noted."), true, st, D);
                t(r.printed.isEmpty() && r.held.size() == 1 && r.held.get(0).get("reason").equals("check: word filter"),
                        "letters: a lifted word in a player's letter is held at " + st.tone().audience() + "/" + st.tone().spice() + ": " + l[1] + " " + r.held);
            }
        MailDesk.Result kept = MailDesk.validate(mail, desk("d4", "the paper is good", "Sol_Dig fell twice, skill issue."), true, teenSavage, D);
        t(kept.printed.size() == 1, "letters: the desk's own reply keeps the trash-talk lift: " + kept.held);

        // classic: the Blotter-order check finds the section by "blotter" or "cold case", and a hit there is cut, not the whole paper
        for (String h : List.of("The Blotter", "Cold Cases"))
            t(has(FactCheck.problems(f, "<h2>A</h2><h3>" + h + "</h3><p>That same chest was opened.</p>", D, s), "\"that same\""), "classic: Blotter order checked under \"" + h + "\"");
        String page = "<h2>Sol_Dig Wins</h2><h3>Front page</h3><p>Sol_Dig won the egg race and the hens are still talking about it in the yard this week.</p>"
                + "<p>Kai_Ore rode a long way on a borrowed horse and came back with nothing to show for the trip at all.</p>"
                + "<h3>Police Blotter</h3><ul><li>A resident reported that Juno_B opened a barrel.</li><li>Juno_B took an egg from that same barrel.</li></ul>";
        t(FactCheck.problems(f, page, D, s).equals(List.of("\"that same\"")), "classic salvage: the only problem is in the Blotter: " + FactCheck.problems(f, page, D, s));
        String saved = Salvage.drop(f, page, D, s);
        t(saved != null && !saved.contains("that same") && saved.contains("opened a barrel") && saved.contains("Police Blotter"), "classic salvage: the Blotter line is cut and the paper kept: " + saved);
        t(Salvage.drop(f, page + "<div>Kai_Ore took that same egg.</div>", D, s) == null, "classic salvage: a problem outside a paragraph or list item cannot be cut, so the page is not saved");

        // a removed heavy-slang word is matched as words: a curly apostrophe is no way past, and it is named once
        Settings rm = tone(new Settings.Tone("classic", "spicy", "pg13", "heavy", "teens", "normal", List.of("obituaries"), List.of(), List.of("it's giving", "lowkey")));
        t(has(FactCheck.problems(f, "<h2>A</h2><p>It\u2019s giving gravity.</p>", D, rm), "removed (\"it's giving\")"), "heavy slang: a removed word with a curly apostrophe goes back");
        t(FactCheck.problems(f, "<h2>A</h2><p>Sol_Dig was lowkey fast.</p>", D, rm).stream().filter(x -> x.equals("removed (\"lowkey\")")).count() == 1
                && ClassicPrompt.daily(rm, f).split("\"lowkey\"", -1).length == 2, "heavy slang: a word on both lists is named once");

        // British swears (paper.language en-GB asks for British words)
        List<String> names = List.of("Sol_Dig");
        for (String w : List.of("wanker", "twat", "bellend", "tosser", "bollocks", "arsehole"))
            t(!WordGate.bad("Sol_Dig, you " + w + ".", names, false, "strong").isEmpty() && !WordGate.bad("you " + w, names, true, "pg13").isEmpty(), "British swear never prints: " + w);
        t(!WordGate.bad("a right arse of it", names, false, "clean").isEmpty() && !WordGate.bad("oh bugger", names, false, "clean").isEmpty()
                && WordGate.bad("a right arse of it, oh bugger", names, false, "pg13").isEmpty() && !WordGate.bad("a right arse", names, true, "pg13").isEmpty(),
                "British mild swears: refused at clean and in letters, fine in a pg13 paper");
        t(WordGate.bad("Sol_Dig tossed the arsenal and parsed the twatter", names, false, "clean").isEmpty(), "British swears: look-alike words pass");

        // the prompt names what the gates refuse, so a good draft is not sent back for it
        String heavy = StylePrompt.build(s), adults = StylePrompt.build(adultSavage);
        t(heavy.contains("fr fr, no cap fr, \"is an L\""), "prompt: heavy slang names fr fr, no cap fr and \"is an L\" as never");
        t(adults.contains("Never the words adult, grown-up or kid.") && !heavy.contains("Never the words adult"), "prompt: the adults line names the family words the gate refuses");
        t(adults.contains("never about the person or a build.") && StylePrompt.build(teenSavage).contains("never about the person or a build."), "prompt: trash talk is never about a build");
    }

    /** A desk answer that prints one letter. */
    static String desk(String id, String quote, String reply) {
        return "{\"printed\": [{\"id\": \"2026-11-06-000000" + id + "\", \"quote\": \"" + quote + "\", \"reply_html\": \"<p>" + reply + "</p>\"}], \"held\": [], \"concern\": []}";
    }

}
