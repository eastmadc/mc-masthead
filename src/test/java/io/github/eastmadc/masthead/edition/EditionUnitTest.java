package io.github.eastmadc.masthead.edition;

import io.github.eastmadc.masthead.config.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Port of the Python version's unit test (42 assertions) plus a few edition-side extras. Plain main(), no JUnit:
 * java -cp classes:gson.jar io.github.eastmadc.masthead.edition.EditionUnitTest
 */
public final class EditionUnitTest {
    static int ok = 0;

    static Settings sn() { return TestSettings.coop(); }

    static void t(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
        ok++;
    }

    public static void main(String[] args) throws Exception {
        // quote rule
        String o = "nimbit is not a cheater, he just mines a lot";
        t(MailDesk.quoteOk(o, o), "whole");
        t(MailDesk.quoteOk("nimbit is not a cheater…", o), "prefix at word");
        t(!MailDesk.quoteOk("nimbit is… a cheater", o), "middle cut");
        t(!MailDesk.quoteOk("nimbit is not a chea…", o), "mid-word cut");
        t(!MailDesk.quoteOk("Nimbit is not a cheater", o), "changed case = reworded");
        t(!MailDesk.quoteOk("nimbit is a cheater…", o), "dropped not");
        t(MailDesk.quoteOk("nimbit is not a cheater,...", o), "prefix with punctuation");
        t(!MailDesk.quoteOk("", o), "empty");
        // word gate
        List<String> names = List.of("tin_kettle", "Quill_7", "nimbit", "Bob", "Assassin99");
        for (String clean : List.of("tin_kettle was slain by a Skeleton and blew up; Kill feed: 5 deaths, shot, drowned, burned",
                "the assassin hid in a shell; hello Scunthorpe; cock-a-doodle-doo at sunrise", "Assassin99 took 3 dmg",
                "Robin is in the Hall of Fame (hell yeah)"))
            t(WordGate.bad(clean, names, false, "pg13").isEmpty(), "edition false positive: " + clean);
        t(WordGate.bad("my wheat farm is trash and the creeper ai is dumb, what the hell", names, true, "pg13").isEmpty(), "mild self-roast allowed");
        t(WordGate.bad("f u c k this", names, true, "pg13").equals(List.of("fuck")), "spaced");
        t(WordGate.bad("fvck sh1t", names, true, "pg13").equals(List.of("shit")), "leet sh1t (fvck is the desk's job)");
        t(WordGate.bad("ur an 1d10t", names, true, "pg13").equals(List.of("idiot")), "leet idiot");
        t(WordGate.bad("cock-a-doodle", names, true, "pg13").equals(List.of("cock")), "cock is mail-only");
        t(WordGate.bad("ＦＵＣＫ", names, false, "pg13").equals(List.of("fuck")), "fullwidth NFKC");
        // names in tips
        t(WordGate.namesIn("a suspect was seen bobbing in the river", names).isEmpty(), "bob vs bobbing");
        t(WordGate.namesIn("Bob did it", names).equals(List.of("Bob")), "bob");
        t(WordGate.namesIn("tin kettle did it", names).equals(List.of("tin_kettle")), "spaced 5+ name");
        t(WordGate.namesIn("n1mb1t did it", names).equals(List.of("nimbit")), "leet name");
        t(WordGate.namesIn("tinkettle", names).equals(List.of("tin_kettle")), "no underscore");
        // a short name with an "s" stuck on (a possessive typed without the apostrophe)
        for (String x : List.of("i went to kai7s base", "kai7's base", "mia_bs farm", "miabs farm", "bobs base"))
            t(WordGate.namesIn(x, List.of("Kai7", "Mia_B", "Bob")).size() == 1, "short name with an s: " + x + " " + WordGate.namesIn(x, List.of("Kai7", "Mia_B", "Bob")));
        t(WordGate.namesIn("a suspect was seen bobsledding", List.of("Bob")).isEmpty(), "bob vs bobsledding");
        // splice (with the test paper's section names)
        Settings sn = TestSettings.coop();
        String frag = "<h2>T</h2><h3>Front page</h3><p>a</p><h3>🥚 Egg of the Day</h3><p>b</p><h3>🐔 Flapping Fowl Award</h3><p>c</p><h3>Kill feed</h3><p>d</p><h3>Police Blotter</h3><p>e</p><h3>Chicken Watch</h3><p>f</p>";
        String f2 = Splice.apply(sn, frag, List.of("<blockquote><p>q</p></blockquote><p>r</p>"), List.of("<p><strong>From the tip line:</strong> x</p>"), "");
        t(f2.indexOf("Letters to the Editor") > f2.indexOf("Flapping") && f2.indexOf("Letters to the Editor") < f2.indexOf("Kill feed"), "letters after awards");
        t(f2.indexOf("From the tip line") > f2.indexOf("Letters to the Editor") && f2.indexOf("From the tip line") < f2.indexOf("Kill feed"), "tips share the editor's section with letters");
        t(f2.contains("<h3>Police Blotter</h3><p>e</p><h3>Chicken Watch</h3>"), "automatic blotter stays unchanged");
        String f3 = Splice.apply(sn, frag.replace("<h3>Police Blotter</h3><p>e</p>", ""), List.of(), List.of("<p>tip</p>"), "");
        t(f3.contains("<h3>" + Splice.LETTERS_HEADING + "</h3><p>tip</p>") && !f3.contains("Police Blotter"), "tip-only mail creates an editor section, never a blotter");
        String f4 = Splice.apply("<h2>T</h2><h3>Front page</h3><p>news</p><h3>Sign-off</h3><p>bye</p>",
                List.of(), List.of("<p>tip</p>"), "plug", null, null);
        t(f4.contains("<p>news</p><h3>" + Splice.LETTERS_HEADING + "</h3><p>tip</p><h3>Sign-off</h3>") && !f4.contains("plug"),
                "tip-only mail falls back after the front page and suppresses the empty-mail plug");
        JsonArray tipSections = GameEdition.build(sn, LocalDate.of(2026, 11, 12), new JsonObject(), f2, "Daily edition", true, new JsonArray()).getAsJsonArray("sections");
        JsonObject editorSection = null, blotterSection = null;
        for (var section : tipSections) {
            JsonObject row = section.getAsJsonObject();
            if (row.get("h").getAsString().equals("Letters to the Editor")) editorSection = row;
            if (row.get("h").getAsString().equals("Police Blotter")) blotterSection = row;
        }
        t(editorSection != null && editorSection.getAsJsonArray("p").toString().contains("From the tip line: x"), "in-game book keeps tips in the editor section");
        t(blotterSection != null && blotterSection.getAsJsonArray("p").size() == 1 && blotterSection.getAsJsonArray("p").get(0).getAsString().equals("e"), "in-game book keeps the automatic blotter unchanged");
        String editorRules = MailDesk.rules(sn);
        t(editorRules.contains("news lead for the newspaper editor") && editorRules.contains("Never add police officers, investigations")
                && editorRules.contains("Never move tips into the Police Blotter"), "editor prompt preserves the newsroom boundary");
        t(editorRules.contains("guilty\"). Add no number or date the tip does not contain; keep \"today\" as the tip wrote it. Never add police"),
                "X2 the tip rule tells the desk what the number check enforces (tipAdded)");
        t(Splice.apply(sn, frag, List.of(), List.of(), "plug").endsWith("<p><em>plug</em></p>"), "plug at end");
        // mailroom validation against a fake desk output
        JsonObject data = new JsonObject();
        JsonObject st = new JsonObject();
        for (String n : names) {
            JsonObject r = new JsonObject();
            r.addProperty("deaths", 23);
            st.add(n, r);
        }
        data.add("season_totals", st);
        data.add("leaders", PyJson.loads("{\"eggs\": [[\"nimbit\", 8640]]}"));
        data.add("circulation", PyJson.loads("{\"readers\": 1, \"who\": [\"SECRET_READER\"]}"));
        data.add("mailbag_candidates", PyJson.loads("""
                [{"id": "2026-11-05-0000000a", "from": "nimbit", "kind": "letter", "text": "who threw the most eggs?"},
                 {"id": "2026-11-05-0000000b", "from": "Bob", "kind": "letter", "text": "i love this paper so much fr"},
                 {"id": "2026-11-05-0000000c", "from": "tin_kettle", "kind": "tip", "text": "nimbit dyed my sheep pink"},
                 {"id": "2026-11-05-0000000d", "from": "Quill_7", "kind": "letter", "text": "nobody would care if i quit forever"},
                 {"id": "2026-11-05-0000000e", "from": "Quill_7", "kind": "tip", "text": "someone dyed a sheep pink"},
                 {"id": "2026-11-05-0000000f", "from": "tin_kettle", "kind": "letter", "text": "how many diamonds does robin have"}]"""));
        String desk = """
                {"printed": [{"id": "2026-11-05-0000000a", "quote": "who threw the most eggs?", "reply_html": "<p>nimbit, with 8,640 eggs. <h2>x</h2><script>bad()</script></p>"},
                             {"id": "2026-11-05-0000000b", "quote": "i love this paper", "reply_html": "<p>thanks</p>"},
                             {"id": "2026-11-05-0000000c", "quote": "", "reply_html": "<p>A resident reports n1mb1t dyed a sheep.</p>"},
                             {"id": "2026-11-05-0000000e", "quote": "", "reply_html": "<p>A tipster reports that a sheep was dyed pink.</p>"},
                             {"id": "2026-11-05-0000000f", "quote": "how many diamonds does robin have", "reply_html": "<p>Exactly 9,999.</p>"}],
                 "held": [], "concern": [{"id": "2026-11-05-0000000d", "note": "sounds down"}]}""";
        LocalDate d = LocalDate.parse("2026-11-05");
        MailDesk.Result res = MailDesk.validate(data, "```json\n" + desk + "\n```", true, "pg13", d);
        Map<String, String> reasons = new HashMap<>();
        for (Map<String, String> h : res.held) reasons.put(h.get("id").substring(h.get("id").length() - 1), h.get("reason"));
        List<String> printedIds = new ArrayList<>();
        for (Map<String, String> p : res.printed) printedIds.add(p.get("id").substring(p.get("id").length() - 1));
        t(printedIds.equals(List.of("a", "e")), "printed a,e: " + res.printed);
        t(reasons.equals(Map.of("b", "check: quote was changed", "c", "check: tip named a player", "f", "check: number not in the data")), "held reasons " + reasons);
        t(res.concern.get(0).get("name").equals("Quill_7"), "concern");
        String l0 = res.letters.get(0);
        t(!l0.contains("<script>") && !l0.contains("<h2>") && !l0.contains("bad()"), "reply cleaned " + l0);
        t(l0.contains("— nimbit") && l0.contains("<blockquote>"), "signed by account");
        t(res.tips.get(0).startsWith("<p><strong>From the tip line:</strong>"), "tip format");
        Set<String> all = new HashSet<>();
        for (char c : "abcdef".toCharArray()) all.add("2026-11-05-0000000" + c);
        t(new HashSet<>(res.doneIds).equals(all), "all decided -> done");
        t(res.waiting == 0, "none waiting");
        // privacy.hide-players: a reply that names a hidden player is held, not printed
        MailDesk.Result rh = MailDesk.validate(data, "```json\n" + desk + "\n```", true, "pg13", d, List.of("NIMBIT"));
        t(rh.printed.stream().noneMatch(p -> p.get("id").endsWith("a")) && rh.held.stream().anyMatch(h -> h.get("id").endsWith("a")
                && h.get("reason").equals("check: named a hidden player")) && rh.letters.stream().noneMatch(l -> l.toLowerCase().contains("nimbit")), "hidden player held " + rh.held);
        // plain-edition day
        MailDesk.Result res2 = MailDesk.validate(data, "```json\n" + desk + "\n```", false, "pg13", d);
        t(res2.printed.isEmpty() && res2.letters.isEmpty() && res2.tips.isEmpty(), "fallback prints nothing");
        Set<String> bcdf = new HashSet<>();
        for (char c : "bcdf".toCharArray()) bcdf.add("2026-11-05-0000000" + c);
        t(new HashSet<>(res2.doneIds).equals(bcdf) && res2.waiting == 2, "fallback keeps a,e open " + res2.doneIds);
        // broken desk output
        MailDesk.Result res3 = MailDesk.validate(data, "sorry, I cannot help with that", true, "pg13", d);
        // 2.0.1 (T6): the worried letter (d) is a concern letter even when the desk output is garbage; the other 5 wait
        t(!res3.ran && res3.waiting == 5 && res3.doneIds.equals(List.of("2026-11-05-0000000d")), "garbage desk output " + res3.doneIds);
        t(res3.concern.size() == 1 && res3.concern.get(0).get("name").equals("Quill_7"), "worried letter found without the desk");
        // facts() hides readers and letters
        JsonObject fx = FactCheck.facts(data);
        t(!fx.has("mailbag_candidates") && !fx.getAsJsonObject("circulation").has("who"), "facts strips");
        t(data.has("mailbag_candidates") && data.getAsJsonObject("circulation").has("who"), "facts does not mutate");
        // candidates from files
        Path tmp = Files.createTempDirectory("cp-unit");
        Path mr = tmp.resolve("mailroom");
        Files.createDirectories(mr);
        Files.writeString(mr.resolve("2026-11-05.jsonl"), String.join("\n",
                "{\"id\": \"2026-11-05-00000001\", \"t\": 1, \"name\": \"nimbit\", \"kind\": \"letter\", \"text\": \"first\"}",
                "{\"id\": \"2026-11-05-00000002\", \"t\": 2, \"name\": \"nimbit\", \"kind\": \"letter\", \"text\": \"second replaces first\"}",
                "{\"id\": \"2026-11-05-00000003\", \"t\": 3, \"name\": \"Notaplayer\", \"kind\": \"letter\", \"text\": \"not a real player\"}",
                "{\"id\": \"2026-11-04-00000004\", \"t\": 4, \"name\": \"Bob\", \"kind\": \"tip\", \"text\": \"wrong day in id\"}",
                "{\"id\": \"2026-11-05-00000005\", \"t\": 5, \"name\": \"Bob\", \"kind\": \"poll\", \"text\": \"bad kind\"}") + "\n{\"id\": \"2026-11-05-0000000");
        Files.writeString(mr.resolve("2026-11-03.jsonl"), "{\"id\": \"2026-11-03-00000009\", \"name\": \"Bob\", \"kind\": \"letter\", \"text\": \"from three days ago\"}\n");
        Files.writeString(mr.resolve("2026-11-02.jsonl"), "{\"id\": \"2026-11-02-00000008\", \"name\": \"tin_kettle\", \"kind\": \"letter\", \"text\": \"too old\"}\n");
        Path done = tmp.resolve("mailbag-done.txt");
        JsonArray c = MailDesk.candidates(mr, done, d, names);
        t(texts(c).equals(List.of("from three days ago", "second replaces first")), "candidates " + c);
        Files.writeString(done, "2026-11-03-00000009\n");
        t(texts(MailDesk.candidates(mr, done, d, names)).equals(List.of("second replaces first")), "done skipped");
        try (Stream<Path> w = Files.walk(tmp)) {
            w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        System.out.println("unit tests passed: " + ok);
        int base = ok;
        extras();
        System.out.println("edition extras passed: " + (ok - base));
        base = ok;
        selfHosted();
        System.out.println("self-hosted fonts and no-other-websites checks passed: " + (ok - base));
        base = ok;
        paperLanguage();
        System.out.println("paper.language checks passed: " + (ok - base));
    }

    /** paper.language: every prompt names the spelling, and the plugin's own dates follow it. */
    static void paperLanguage() {
        String us = "American English: American spelling", uk = "British English: British spelling";
        Settings classic = TestSettings.with(new Settings.Tone("classic", "spicy", "pg13", "light", "teens", "normal", List.of("obituaries"), List.of(), List.of()));
        for (Settings s : List.of(sn(), TestSettings.other(), classic)) {
            Settings g = british(s);
            String voice = s.tone().effectiveVoice();
            t(s.paper().language().equals("en-US") && g.paper().british(), "en-US by default, en-GB when set");
            t(StylePrompt.build(s).contains("- Spelling: " + us) && !StylePrompt.build(s).contains(uk), voice + " prompt: US line");
            t(StylePrompt.build(g).contains("- Spelling: " + uk) && !StylePrompt.build(g).contains(us), voice + " prompt: UK line");
            t(MailDesk.rules(s).contains("Spelling: " + us) && MailDesk.rules(g).contains("Spelling: " + uk), voice + " editor desk: the same line");
            t(!MailDesk.rules(g).contains("__SPELLING__"), "no placeholder left");
        }
        t(ClassicPrompt.build(british(classic)).contains("colour, favourite, grey, centre"), "classic: UK examples");
        LocalDate d = LocalDate.of(2026, 11, 14);
        t(Py.dayLabel(d, false).equals("Sat Nov 14, 2026"), "US label " + Py.dayLabel(d, false));
        t(Py.dayLabel(d, true).equals("Sat 14 Nov 2026"), "UK label " + Py.dayLabel(d, true));
        t(Py.monthDay(LocalDate.of(2026, 1, 5), false).equals("Jan 5, 2026") && Py.monthDay(LocalDate.of(2026, 1, 5), true).equals("5 Jan 2026"), "page footer dates");
        t(WordGate.FLOOR_FAMILY.containsAll(List.of("mum", "mums")), "mum is a family word too");
    }

    static Settings british(Settings s) {
        Settings.Paper p = s.paper();
        return new Settings(new Settings.Paper(p.name(), p.serverName(), p.tagline(), p.theme(), p.readers(), p.goodAward(), p.badAward(), p.watchAnimal(), "en-GB"),
                s.tone(), s.owner(), s.privacy(), s.schedule(), s.writer(), s.web(), s.features(), s.alerts(), s.construction());
    }

    /** git blob ids of the font files at google/fonts commit 24ddc1f324cac5d9f5aedca126d298f56e3f1640 (ofl/<family>/). */
    static final Map<String, String> UPSTREAM = Map.of(
            "fonts/pixelify-sans/PixelifySans[wght].ttf", "2d7eb388ee499fbcf3e992723ec409c095640104",
            "fonts/pixelify-sans/OFL.txt", "6aa5bfbae9e8654b83e5fff942b9540830403022",
            "fonts/atkinson-hyperlegible/AtkinsonHyperlegible-Regular.ttf", "b3cc4200fcf09d600eac74002ab4ea65b404a536",
            "fonts/atkinson-hyperlegible/AtkinsonHyperlegible-Bold.ttf", "ca3f613847de62b8da83d2dab500ec391c8fe477",
            "fonts/atkinson-hyperlegible/AtkinsonHyperlegible-Italic.ttf", "9ee68183aed5bf84af3bd4eac52e3c723a02d5a5",
            "fonts/atkinson-hyperlegible/AtkinsonHyperlegible-BoldItalic.ttf", "2804fdb8f06c328afb96409d7498c6f5f287b79c",
            "fonts/atkinson-hyperlegible/OFL.txt", "2befc89b0ee81d5a37e6ca6503bb0db099feb70f",
            "fonts/jetbrains-mono/JetBrainsMono[wght].ttf", "aa310be8b717fe3774f9444dd89d5f4101cc6d10",
            "fonts/jetbrains-mono/OFL.txt", "821a3dac22aff15a1f1c9689a1d79c45bb58ca39");

    /** Third-party notice files: exempt from the no-URL check by path, and never edited to make it pass (T22). */
    static final Set<String> NOTICE_PATHS = Set.of("src/main/resources/LICENSE-qrcodegen.txt",
            "src/main/resources/fonts/pixelify-sans/OFL.txt", "src/main/resources/fonts/atkinson-hyperlegible/OFL.txt",
            "src/main/resources/fonts/jetbrains-mono/OFL.txt");

    /** T22: the fonts ship unmodified with their licenses, every page links them by relative URL, and nothing that builds pages names another website. */
    static void selfHosted() throws Exception {
        t(new HashSet<>(Pages.FONT_FILES).equals(UPSTREAM.keySet()), "FONT_FILES = the pinned upstream files " + Pages.FONT_FILES);
        for (Map.Entry<String, String> e : UPSTREAM.entrySet()) {
            byte[] b;
            try (var in = Pages.class.getClassLoader().getResourceAsStream(e.getKey())) {
                t(in != null, "in the jar: " + e.getKey());
                b = in.readAllBytes();
            }
            var sha = java.security.MessageDigest.getInstance("SHA-1");
            sha.update(("blob " + b.length + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            t(java.util.HexFormat.of().formatHex(sha.digest(b)).equals(e.getValue()), "byte-for-byte upstream: " + e.getKey());
        }
        String head = Pages.head("t", "");
        for (String fam : List.of("Pixelify Sans", "Atkinson Hyperlegible", "JetBrains Mono"))
            t(head.contains("@font-face{font-family:\"" + fam + "\""), "@font-face for " + fam);
        var urls = java.util.regex.Pattern.compile("url\\(\"([^\"]*)\"\\)").matcher(head);
        int n = 0;
        while (urls.find()) {
            String u = java.net.URLDecoder.decode(urls.group(1).replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
            t(Pages.FONT_FILES.contains(u) && u.endsWith(".ttf"), "font url points at a shipped font: " + urls.group(1));
            n++;
        }
        t(n == 7, "seven font urls (six faces + the display digits), got " + n);
        // No http:// or https:// in any file that builds pages or is a web resource; notice files exempt by path only.
        String pkg = "src/main/java/io/github/eastmadc/masthead/";
        List<Path> scope = new ArrayList<>();
        for (String dir : List.of(pkg + "edition", pkg + "web", "src/main/resources/fonts")) {
            t(Files.isDirectory(Path.of(dir)), "run from the repo root: " + dir);
            try (Stream<Path> w = Files.walk(Path.of(dir))) { w.filter(Files::isRegularFile).forEach(scope::add); }
        }
        t(scope.stream().anyMatch(p -> p.endsWith("Pages.java")) && scope.stream().anyMatch(p -> p.toString().endsWith(".ttf")), "scope reads Pages.java and the fonts");
        int read = 0;
        for (Path p : scope) {
            if (NOTICE_PATHS.contains(p.toString().replace('\\', '/'))) continue;
            String text = new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.ISO_8859_1);
            t(!text.contains("http://") && !text.contains("https://"), "names another website: " + p);
            read++;
        }
        t(read >= 20, "no-URL check read only " + read + " files");
        // T23: the qrcodegen notice carries the vendored file's license block word for word
        StringBuilder block = new StringBuilder();
        List<String> src = Files.readAllLines(Path.of(pkg + "qr/qrcodegen/QrCode.java"));
        for (String line : src.subList(1, src.indexOf(" */"))) block.append(line.replaceFirst("^ \\* ?", "")).append('\n');
        String notice;
        try (var in = Pages.class.getClassLoader().getResourceAsStream("LICENSE-qrcodegen.txt")) { notice = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
        t(block.toString().contains("Copyright (c) Project Nayuki. (MIT License)") && notice.endsWith(block.toString()), "LICENSE-qrcodegen.txt ends with the vendored header block");
        try (var in = Pages.class.getClassLoader().getResourceAsStream("THIRD-PARTY.txt")) {
            String tp = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String f : Pages.FONT_FILES) if (f.endsWith("OFL.txt")) t(tp.contains(f), "THIRD-PARTY.txt names " + f);
            t(tp.contains("LICENSE-qrcodegen.txt") && tp.contains("anthropic-java 3.5.0: Apache-2.0"), "THIRD-PARTY.txt names qrcodegen and the SDK");
        }
    }

    /** 2.0.1 safety checks: worried letters (T6), mailbag.json (T7), player-typed names and the plain edition (T5). */
    static void safety() {
        LocalDate d = LocalDate.parse("2026-11-05");
        JsonObject data = new JsonObject();
        data.add("season_totals", PyJson.loads("{\"Alice_1\": {\"deaths\": 3}, \"Bob_22\": {\"deaths\": 1}}"));
        data.add("mailbag_candidates", PyJson.loads("""
                [{"id": "2026-11-05-000000a1", "from": "Alice_1", "kind": "letter", "text": "CANARY-7731 i feel low", "wb": true},
                 {"id": "2026-11-05-000000a2", "from": "Bob_22", "kind": "letter", "text": "no one would care if i left"},
                 {"id": "2026-11-05-000000a3", "from": "Bob_22", "kind": "tip", "text": "someone dyed my sheep"}]"""));
        // the desk never sees worried letters (marked, or matching the words)
        String prompt = MailDesk.prompt(TestSettings.coop(), data);
        t(prompt != null && !prompt.contains("CANARY-7731") && !prompt.contains("no one would care") && prompt.contains("someone dyed my sheep")
                && !prompt.contains("\"wb\""), "desk prompt leaves worried letters out");
        // a desk that tries to print a worried letter cannot
        String desk = "{\"printed\": [{\"id\": \"2026-11-05-000000a1\", \"quote\": \"CANARY-7731 i feel low\", \"reply_html\": \"<p>hi</p>\"}], \"held\": [], \"concern\": []}";
        MailDesk.Result r = MailDesk.validate(data, desk, true, "pg13", d);
        t(r.printed.isEmpty() && r.letters.isEmpty() && r.concern.size() == 2, "worried letters are never printed " + r.printed);
        t(r.waiting == 1, "the tip still waits");
        // with no desk at all
        MailDesk.Result r0 = MailDesk.validate(data, null, true, "pg13", d);
        t(r0.concern.size() == 2 && r0.waiting == 1 && r0.doneIds.size() == 2, "no desk: worried letters still flagged");
        // mailbag.json keeps id and name only for concern letters
        String mj = r.toJson().toString();
        t(!mj.contains("CANARY") && !mj.contains("would care") && !mj.contains("\"note\"") && !mj.contains("\"text\"") && mj.contains("Alice_1"),
                "mailbag.json has no concern text: " + mj);
        // the internal record still has the text (for the host helper's root-only copy)
        t(r.concern.get(0).get("text").contains("CANARY"), "concern record keeps the text in memory");

        // T5: numbers in death lines are not "known" (a name tag can carry them)
        JsonObject f = new JsonObject();
        f.add("deaths", PyJson.loads("[\"Alice_1 was slain by base 1200 -340\"]"));
        f.add("today", PyJson.loads("{\"Alice_1\": {\"mob_kills\": 4521}}"));
        List<String> probs = FactCheck.problems(f, "<p>Her base sits at 1200 and -340, with 4,521 kills.</p>", d, TestSettings.coop());
        t(probs.contains("1200") && !probs.contains("4521"), "death-line numbers not whitelisted: " + probs);
        // T5: the plain edition runs the word gate on each death line and achievement
        JsonObject g = new JsonObject();
        g.add("season_totals", PyJson.loads("{\"Alice_1\": {}}"));
        g.add("deaths", PyJson.loads("[\"Alice_1 was slain by Zombie\", \"Alice_1 was slain by fucking slut\"]"));
        g.add("advancements", PyJson.loads("[[\"Alice_1\", \"Diamonds!\"], [\"Alice_1\", \"porn\"]]"));
        String fb = Fallback.edition(TestSettings.coop(), g);
        t(fb.contains("slain by Zombie") && fb.contains("Diamonds!") && !fb.contains("slut") && !fb.contains("porn"), "plain edition word gate: " + fb);
        t(WordGate.bad(Py.unescape(Py.stripTags(fb, " ")), List.of("Alice_1"), false, "pg13").isEmpty(), "plain edition passes the gate as a whole");
    }

    /** 1A.1, 1A.2, 1A.7: Gate C, the mail checks, number words and session phrases. */
    static void gates() {
        LocalDate d = LocalDate.parse("2026-11-05");
        // Gate C (construction spec §7.1, G-10, G-20): attacks caught, benign strings pass
        for (String a : List.of("-6512, 3104", "chunk -407,194", "at 12, -40", "(120, -40)", "-6512,3104", "base:-300,400", "x-300, 400",
                "300/64/-200", "-6512 3104", "x45 z-80", "X 300, Z 400", "12 64 -40", "120, 70, -40", "down at Y-40", "x: 120", "z=-80",
                "x ninety z forty", "x is ninety and z is forty", "xninety zforty", "X = minus four hundred", "x fourty", "z -ninetyfive", "x was ninety", "x at minus forty"))
            t(!GateC.problems(a, List.of()).isEmpty(), "Gate C misses: " + a);
        for (String b : List.of("Lumen208 placed 412 blocks", "won 3-2", "scored 5 - 3", "the 1-2 punch", "11/05", "1/2 of the wall",
                "Day 12, 5,210 blocks", "lost 41 blocks to a creeper, 6 removed", "Steve went 4-1, 3 kills", "won 3-2, 4-1 in the rematch",
                "went 10-0, 13 kills", "the 3-2, 4-1, 5-0 week", "Paper build 26.3-242, 26.3-243", "three creeper blasts", "4 kills, 1 death",
                "stands at 980 blocks, 41 of them lost", "finished with 14 kills, 2 ahead", "Blocks this week: 3,100, a record.",
                "the Zone held forty", "Xylo won seven", "the zones held ninety"))
            t(GateC.problems(b, List.of()).isEmpty(), "Gate C false alarm: " + b + " " + GateC.problems(b, List.of()));
        for (String g : List.of("Creeper explosions x3", "combo x2 for Steve", "deaths: x4", "K/D 4/1/0", "(41, 980)",
                "Permit #3 now stands at 980, 41 of them lost to a creeper.", "Blocks this week (3,100) were a record."))
            t(GateC.problems(g, List.of()).equals(List.of("coordinates")), "numeric label is exactly coordinates: " + g);
        t(GateC.problems("a creeper took a chunk out of the wall", List.of()).equals(List.of("coordinates (\"chunk\")")), "keyword label names the word");
        t(GateC.problems("dug down to y-level eleven", List.of()).equals(List.of("coordinates (\"Y level\")")), "Y level label");
        // known names are masked first (x420, Z1000, Y2006), but a real position next to them is still caught
        List<String> odd = List.of("x420", "Z1000", "Y2006");
        for (String b : List.of("x420 won the duel by 3 kills.", "Aries (Z1000): the stars smile.", "Y2006 placed 412 blocks.")) {
            t(GateC.problems(b, odd).isEmpty(), "masked name passes: " + b);
            t(!GateC.problems(b, List.of()).isEmpty(), "unmasked name is flagged: " + b);
        }
        for (String a : List.of("x420 lives at -6512, 3104", "x420, z400", "Y2006 at Y-40", "Z1000 (120, -40)"))
            t(!GateC.problems(a, odd).isEmpty(), "still caught with names masked: " + a);
        t(!GateC.problems("base at &#45;7341, 2963", List.of()).isEmpty() && !GateC.problems("ｘ４５ ｚ－８０", List.of()).isEmpty(), "entities and fullwidth digits");
        // the writer is told the words and the shapes up front
        String sp = StylePrompt.build(TestSettings.other());
        t(sp.contains("Never use the words chunk, coord, coords, coordinates, Y level anywhere") && sp.contains("never join three numbers with slashes"), "prompt names Gate C");
        // Gate C runs on every edition, through the fact check
        JsonObject f = new JsonObject();
        f.add("season_totals", PyJson.loads("{\"x420\": {\"deaths\": 3}}"));
        f.add("players_on", PyJson.loads("[\"x420\"]"));
        t(FactCheck.problems(f, "<p>x420 is at 120, -40.</p>", d, TestSettings.other()).contains("coordinates"), "edition: coordinates");
        t(FactCheck.problems(f, "<p>x420 won 3 duels.</p>", d, TestSettings.other()).isEmpty(), "edition: masked name");

        // T15: numbers in words are checked like digits
        JsonObject h = new JsonObject();
        h.add("season_totals", PyJson.loads("{\"Steve_Builder\": {\"horse_m\": 7342.3}}"));
        String horse = "<p>Seven thousand three hundred forty-two meters on horseback.</p>";
        t(FactCheck.problems(h, horse, d, TestSettings.other()).isEmpty(), "number words that match the data pass");
        JsonObject h2 = new JsonObject();
        h2.add("season_totals", PyJson.loads("{\"Steve_Builder\": {\"horse_m\": 5000}}"));
        t(FactCheck.problems(h2, horse, d, TestSettings.other()).equals(List.of("7342")), "number words not in the data: " + FactCheck.problems(h2, horse, d, TestSettings.other()));
        t(FactCheck.wordNumbers("a hundred and twelve, hundreds of eggs, the one two punch, twenty-one, five hundred thousand, a thousand")
                .equals(List.of(112L, 1L, 2L, 21L, 500000L, 1000L)), "word numbers " + FactCheck.wordNumbers("a hundred and twelve, hundreds of eggs, the one two punch, twenty-one, five hundred thousand, a thousand"));
        JsonObject e = new JsonObject();
        e.add("today", PyJson.loads("{\"Steve_Builder\": {\"eggs\": 208, \"bred\": 41}}"));
        t(FactCheck.problems(e, "<p>Steve_Builder threw 208 eggs in one session.</p>", d, TestSettings.other()).equals(List.of("\"in one session\"")), "in one session");
        t(FactCheck.problems(e, "<p>Steve_Builder bred 41 animals in a single session.</p>", d, TestSettings.other()).equals(List.of("\"in a single session\"")), "in a single session");
        t(FactCheck.problems(e, "<p>All 208 in one go, in a single sitting.</p>", d, TestSettings.other()).equals(List.of("\"in a single sitting\"", "\"in one go\"")), "sitting, one go");
        // P11: entities are decoded before the number scan
        t(FactCheck.problems(e, "<p>base at &#49;&#50;&#48;&#48;</p>", d, TestSettings.other()).equals(List.of("1200")), "entity digits: " + FactCheck.problems(e, "<p>base at &#49;&#50;&#48;&#48;</p>", d, TestSettings.other()));
        // clock times are times of day
        t(FactCheck.problems(e, "<p>Logged on at 3am.</p>", d, TestSettings.other()).equals(List.of("time of day")), "3am");
        t(FactCheck.problems(e, "<p>At 11:30 pm sharp.</p>", d, TestSettings.other()).equals(List.of("time of day")), "11:30 pm");
        t(FactCheck.problems(e, "<p>won 3-2, 1/2 of the wall, 4 to 1</p>", d, TestSettings.other()).isEmpty(), "no clock false alarm");

        // T9: printed mail gets the edition's checks (and Gate C); each failure is held with its reason
        JsonObject m = new JsonObject();
        m.add("season_totals", PyJson.loads("{\"Steve_Builder\": {\"deaths\": 2}, \"Alex_Miner\": {\"deaths\": 1}, \"x420\": {\"deaths\": 1}}"));
        m.add("mailbag_candidates", PyJson.loads("""
                [{"id": "2026-11-05-000000b1", "from": "Steve_Builder", "kind": "letter", "text": "is my base at 120, -40 safe from creepers"},
                 {"id": "2026-11-05-000000b2", "from": "Alex_Miner", "kind": "letter", "text": "i mined all night and found nothing"},
                 {"id": "2026-11-05-000000b3", "from": "x420", "kind": "letter", "text": "x420 here, my dad says the paper is good"},
                 {"id": "2026-11-05-000000b4", "from": "Steve_Builder", "kind": "tip", "text": "someone moved my chest"},
                 {"id": "2026-11-05-000000b5", "from": "Alex_Miner", "kind": "tip", "text": "the creepers are organised"},
                 {"id": "2026-11-05-000000b6", "from": "x420", "kind": "tip", "text": "x420 saw a skeleton"}]"""));
        String desk = """
                {"printed": [{"id": "2026-11-05-000000b1", "quote": "is my base at 120, -40 safe from creepers", "reply_html": "<p>No base is safe.</p>"},
                             {"id": "2026-11-05-000000b2", "quote": "i mined all night and found nothing", "reply_html": "<p>Keep digging.</p>"},
                             {"id": "2026-11-05-000000b3", "quote": "x420 here, my dad says the paper is good", "reply_html": "<p>Thanks x420.</p>"},
                             {"id": "2026-11-05-000000b4", "quote": "", "reply_html": "<p>A chest was reported moved near chunk 12. No arrests were made.</p>"}],
                 "held": [], "concern": []}""";
        MailDesk.Result r = MailDesk.validate(m, desk, true, TestSettings.other(), d);
        Map<String, String> why = new HashMap<>();
        for (Map<String, String> x : r.held) why.put(x.get("id").substring(x.get("id").length() - 2), x.get("reason"));
        t(why.equals(Map.of("b1", "check: location", "b2", "check: time of day", "b3", "check: family", "b4", "check: location")), "mail held reasons " + why);
        t(r.printed.isEmpty() && r.letters.isEmpty() && r.tips.isEmpty(), "nothing printed");
        String ok2 = """
                {"printed": [{"id": "2026-11-05-000000b5", "quote": "", "reply_html": "<p>A resident reports organised creepers.</p>"},
                             {"id": "2026-11-05-000000b6", "quote": "", "reply_html": "<p>A tipster reports a skeleton sighting. Unconfirmed.</p>"}], "held": [], "concern": []}""";
        t(MailDesk.validate(m, ok2, true, TestSettings.other(), d).printed.size() == 2, "clean tips print");
        // the desk added a verdict ("cowardice") and police framing the tip never made. Held, not printed.
        JsonObject tm = new JsonObject();
        tm.add("season_totals", PyJson.loads("{\"Steve_Builder\": {\"deaths\": 2}, \"Alex_Miner\": {\"deaths\": 1}}"));
        tm.add("mailbag_candidates", PyJson.loads("""
                [{"id": "2026-11-06-000000c1", "from": "Alex_Miner", "kind": "tip", "text": "Steve_Builder spent the whole evening hiding in a boat because he thinks phantoms are after him - ask anyone"},
                 {"id": "2026-11-06-000000c2", "from": "Alex_Miner", "kind": "tip", "text": "someone raided my chest, i want an arrest"}]"""));
        String coward = """
                {"printed": [{"id": "2026-11-06-000000c1", "quote": "", "reply_html": "<p>A resident called the tip line to report that a local, whom the Post will not name, spent the evening in a boat out of fear of phantoms. The Post calls it cowardice. Officers are investigating. No arrests were made.</p>"}],
                 "held": [], "concern": []}""";
        MailDesk.Result tr = MailDesk.validate(tm, coward, true, TestSettings.other(), d);
        t(tr.printed.isEmpty() && tr.tips.isEmpty() && tr.held.size() == 1 && tr.held.get(0).get("reason").startsWith("check: tip added"),
                "embellished tip held: " + tr.held);
        for (String w : List.of("suspect", "arrests", "Officers", "investigating", "cowardly", "a shameless"))
            t(!MailDesk.tipAdded("<p>A tipster reports " + w + " sleep.</p>", "someone slept all night").isEmpty(), "tip added word caught: " + w);
        // more police words, and a police word the tip used still needs "a reader says/asks/..." in its sentence
        for (String w : List.of("The sheriff's deputies are chasing leads and witnesses are asked to come forward.", "The sheriff is on it.",
                "Deputies were sent.", "The cops came.", "A cop came.", "Straight to jail.", "Witnesses saw it.", "The authorities know.",
                "No charges yet.", "He was charged.", "A charge was filed.", "Officials are following leads.", "Are there any leads?"))
            t(!MailDesk.tipAdded(w, "someone slept all night").isEmpty(), "tip added police word caught: " + w);
        t(!MailDesk.tipAdded("Police have been called.", "someone took my stuff, call the police").isEmpty(), "tip's own police word, not attributed, held");
        t(MailDesk.tipAdded("A reader says someone took their stuff and wants the police called.", "someone took my stuff, call the police").isEmpty(),
                "tip's own police word, attributed to the reader, prints");
        t(!MailDesk.tipAdded("A reader says someone took their stuff. Police have been called.", "someone took my stuff, call the police").isEmpty(),
                "attribution only covers its own sentence");
        t(MailDesk.tipAdded("A reader reports that their horse's lead broke, in case of creepers. Copper and scope are fine.",
                "my horse's lead broke, in case of creepers").isEmpty(), "lead, case, copper, scope are not police words");
        t(MailDesk.tipAdded("A reader tells the editor 3 creepers blew up 12 blocks.", "3 creepers blew up 12 blocks").isEmpty(), "numbers from the tip print");
        t(MailDesk.tipAdded("A reader reports 3 creepers, after 41 deaths.", "3 creepers").equals("41"), "a number the tip never said is held");
        String innocent = """
                {"printed": [{"id": "2026-11-06-000000c3", "quote": "", "reply_html": "<p>A reader reports their horse's lead broke.</p>"},
                             {"id": "2026-11-06-000000c4", "quote": "", "reply_html": "<p>A reader stocks torches in case of creepers.</p>"}], "held": [], "concern": []}""";
        JsonObject im = new JsonObject();
        im.add("season_totals", PyJson.loads("{\"Steve_Builder\": {\"deaths\": 2}, \"Alex_Miner\": {\"deaths\": 1}}"));
        im.add("mailbag_candidates", PyJson.loads("""
                [{"id": "2026-11-06-000000c3", "from": "Alex_Miner", "kind": "tip", "text": "my horse's lead broke"},
                 {"id": "2026-11-06-000000c4", "from": "Steve_Builder", "kind": "tip", "text": "i keep torches in case of creepers"},
                 {"id": "2026-11-06-000000c5", "from": "Steve_Builder", "kind": "letter", "text": "hi editor"}]"""));
        MailDesk.Result ir = MailDesk.validate(im, innocent, true, TestSettings.other(), d);
        t(ir.printed.size() == 2 && ir.held.isEmpty() && ir.printed.get(0).get("name").equals("Alex_Miner"), "innocent tips print, tipster still recorded: " + ir.held);
        String sheriff = "{\"printed\": [{\"id\": \"2026-11-06-000000c3\", \"quote\": \"\", \"reply_html\": \"<p>A reader reports a broken lead. The sheriff's deputies are chasing leads.</p>\"}], \"held\": [], \"concern\": []}";
        t(MailDesk.validate(im, sheriff, true, TestSettings.other(), d).held.get(0).get("reason").startsWith("check: tip added"), "validate holds the sheriff tip");
        // the desk never sees who sent a tip (it could name them from their stats); letters keep their writer
        String dp = MailDesk.prompt(TestSettings.other(), im);
        String mail = dp.substring(dp.indexOf("MAIL (JSON):"), dp.indexOf("FACTS (JSON):"));
        t(!mail.contains("Alex_Miner") && mail.contains("my horse's lead broke") && mail.contains("Steve_Builder") && mail.contains("hi editor"),
                "tip prompt has no tipster: " + mail);
        String plain = """
                {"printed": [{"id": "2026-11-06-000000c1", "quote": "", "reply_html": "<p>A tipster reports that a resident slept through the night, scared of getting their house blown up. Unconfirmed.</p>"},
                             {"id": "2026-11-06-000000c2", "quote": "", "reply_html": "<p>A tipster reports a raided chest and wants an arrest.</p>"}], "held": [], "concern": []}""";
        MailDesk.Result tp = MailDesk.validate(tm, plain, true, TestSettings.other(), d);
        t(tp.printed.size() == 2 && tp.held.isEmpty(), "a tip told straight prints, and a word the tip itself used is fine: " + tp.held);
        // X3: real-life details in a letter the desk picked anyway are held by the plugin; game talk still prints
        java.util.function.Function<String, MailDesk.Result> picked = letter -> {
            JsonObject pm = new JsonObject();
            pm.add("season_totals", PyJson.loads("{\"Steve_Builder\": {\"deaths\": 2}, \"Alex_Miner\": {\"deaths\": 1}}"));
            pm.add("mailbag_candidates", PyJson.loads("[{\"id\": \"2026-11-05-000000d1\", \"from\": \"Steve_Builder\", \"kind\": \"letter\", \"text\": \"" + letter + "\"}]"));
            String quote = letter.startsWith("i love this paper so much.") ? "i love this paper so much.…" : letter;   // a clean beginning does not save the letter
            return MailDesk.validate(pm, "{\"printed\": [{\"id\": \"2026-11-05-000000d1\", \"quote\": \"" + quote
                    + "\", \"reply_html\": \"<p>Thanks for writing in, the cows send regards.</p>\"}], \"held\": [], \"concern\": []}", true, TestSettings.other(), d);
        };
        for (String letter : List.of("im 9 and i go to oakdale elementary", "i'm 11", "I am 12, and my sword is better", "im only 9 lol", "i\u2019m 12 btw", "my real name is sam add me on snap samthecow",
                "i live on birch lane next to the park come over", "it is my birthday and i am in 4th grade", "my discord is samthecow if anyone wants to play",
                "school was boring today so i mined", "follow me on tiktok samthecow", "insta: samthecow", "i love this paper so much. btw i am in grade 5",
                "my names sam cowman", "we live in oakdale near the mall", "my b-day is soon",
                "i love this paper so much. my friend is 12 years old")) {   // a clean quote: only the "years old" rule can hold it
            MailDesk.Result pr = picked.apply(letter);
            t(pr.printed.isEmpty() && pr.letters.isEmpty() && pr.held.size() == 1 && pr.held.get(0).get("reason").equals("check: personal"), "X3 personal letter held: " + letter + " " + pr.held);
        }
        for (String letter : List.of("the creeper ai is so dumb", "my base is near spawn", "i am 2 blocks from the end portal", "im 3 diamonds short of a full set",
                "a school of salmon ate my bait", "i built a school for the villagers", "the new snapshot is insta mine heaven", "is the discord down again",
                "the street by the road needs a phone booth", "i am 2nd on the board", "finally im 30", "im 15 minutes from the stronghold", "my name is in the paper")) {
            MailDesk.Result pr = picked.apply(letter);
            t(pr.printed.size() == 1 && pr.held.isEmpty(), "X3 control, game talk still prints: " + letter + " " + pr.held);
        }
        // X3, added part: a tip is checked too, and so is the editor's reply (it was written from the whole text)
        JsonObject xm = new JsonObject();
        xm.add("season_totals", PyJson.loads("{\"Steve_Builder\": {\"deaths\": 2}, \"Alex_Miner\": {\"deaths\": 1}}"));
        xm.add("mailbag_candidates", PyJson.loads("""
                [{"id": "2026-11-05-000000e1", "from": "Alex_Miner", "kind": "tip", "text": "im 9 and i go to oakdale elementary and someone took my diamonds"},
                 {"id": "2026-11-05-000000e2", "from": "Steve_Builder", "kind": "letter", "text": "how many cows are there"}]"""));
        MailDesk.Result xr = MailDesk.validate(xm, """
                {"printed": [{"id": "2026-11-05-000000e1", "quote": "", "reply_html": "<p>A reader tells the editor that someone took their diamonds.</p>"},
                             {"id": "2026-11-05-000000e2", "quote": "how many cows are there", "reply_html": "<p>Plenty. Good luck in 4th grade.</p>"}],
                 "held": [], "concern": []}""", true, TestSettings.other(), d);
        t(xr.printed.isEmpty() && xr.tips.isEmpty() && xr.letters.isEmpty() && xr.held.size() == 2
                && xr.held.get(0).get("reason").equals("check: personal") && xr.held.get(1).get("reason").equals("check: personal"), "X3 personal tip and personal reply held: " + xr.held);
        // X4: playground insults about a player. The floor (paper and mail): gay, lesbian, queer, moron, braindead.
        // Mail only: worthless, pathetic, "nobody likes", "no friends", "like a girl". Not listed: dumb, smells, stinks, hate.
        for (String letter : List.of("Alex_Miner is gay lol", "Alex_Miner is a moron", "nobody likes Alex_Miner", "Alex_Miner plays like a girl", "Alex_Miner is worthless",
                "Alex_Miner has no friends", "Alex_Miner is pathetic", "Alex_Miner is braindead", "all the morons live in the east", "Alex_Miner is queer", "Alex_Miner is a lesbian",
                "no one likes Alex_Miner", "noone likes Alex_Miner", "Alex_Miner is brain dead")) {
            MailDesk.Result pr = picked.apply(letter);
            t(pr.printed.isEmpty() && pr.held.size() == 1 && pr.held.get(0).get("reason").equals("check: word filter"), "X4 mean letter held: " + letter + " " + pr.held);
        }
        for (String letter : List.of("Alex_Miner is so dumb at parkour lol", "the swamp smells and it stinks", "i hate phantoms so much", "nobody told me about the new farm")) {
            MailDesk.Result pr = picked.apply(letter);
            t(pr.printed.size() == 1 && pr.held.isEmpty(), "X4 control, still prints: " + letter + " " + pr.held);
        }
        for (String w : List.of("gay", "lesbian", "queer", "moron", "braindead"))
            t(FactCheck.problems(e, "<p>Alex_Miner is " + w + ".</p>", d, TestSettings.other()).contains("floor (\"" + w + "\")"), "X4 the paper never says: " + w);
        t(WordGate.floor("A pathetic, worthless haul. Nobody likes phantoms, and a zombie has no friends.", List.of()).isEmpty()
                && WordGate.bad("A pathetic, worthless haul. Nobody likes phantoms, and a zombie has no friends.", List.of(), false, "clean").isEmpty(),
                "X4 the mail-only words stay free for the paper itself");
        // X5: a count cut short (90k, 90 thousand, 2 million) is checked against the data like any other big number
        // (the data holds an unrelated 90: a count in thousands is compared with the data's per-1000 forms only)
        JsonObject kd = PyJson.loads("{\"season_totals\": {\"Kai_Ore\": {\"blocks_mined\": 600, \"mob_kills\": 61, \"deaths\": 90}}, \"today\": {\"Kai_Ore\": {\"blocks_mined\": 600, \"mob_kills\": 61}},"
                + " \"owner\": {\"lore\": [\"Kai_Ore once walked 12k blocks for a cookie\"]}}").getAsJsonObject();
        for (String[] x : new String[][] {{"90k", "90k"}, {"90K", "90k"}, {"90 thousand", "90k"}, {"90.0k", "90k"}, {"2 million", "2000000"}, {"2.5 million", "2500000"}})
            t(FactCheck.problems(kd, "<p>Kai_Ore mined " + x[0] + " blocks.</p>", d, TestSettings.other()).equals(List.of(x[1])), "X5 big number not in the data: " + x[0]
                    + " " + FactCheck.problems(kd, "<p>Kai_Ore mined " + x[0] + " blocks.</p>", d, TestSettings.other()));
        for (String ok : List.of("Kai_Ore mined 0.6k blocks.", "Kai_Ore mined 0.6 thousand blocks and 61 mobs.", "Kai_Ore walked 5km with 2kg of loot, y2k style.",
                "Kai_Ore mined 1k blocks and died 90 times.", "Kai_Ore once walked 12k blocks."))
            t(FactCheck.problems(kd, "<p>" + ok + "</p>", d, TestSettings.other()).isEmpty(), "X5 control, a real 600 as 0.6k and units pass: " + ok
                    + " " + FactCheck.problems(kd, "<p>" + ok + "</p>", d, TestSettings.other()));
        // a true count of a million or more may be rounded the same way; a made-up one is still caught
        JsonObject md = PyJson.loads("{\"season_totals\": {\"Kai_Ore\": {\"blocks_mined\": 2512345}}}").getAsJsonObject();
        t(FactCheck.problems(md, "<p>Kai_Ore has mined 2.5 million blocks, nearly 3 million.</p>", d, TestSettings.other()).isEmpty(), "X5 a real 2512345 as 2.5 million passes: "
                + FactCheck.problems(md, "<p>Kai_Ore has mined 2.5 million blocks, nearly 3 million.</p>", d, TestSettings.other()));
        t(FactCheck.problems(md, "<p>Kai_Ore has mined 2.6 million blocks.</p>", d, TestSettings.other()).equals(List.of("2600000")), "X5 a made-up million is still caught");
        // never-mention: a short phrase is checked word for word in the mail and the edition
        Settings nm = new Settings(TestSettings.other().paper(), TestSettings.other().tone(), TestSettings.other().owner(),
                new Settings.Privacy(List.of(), List.of("the old map", "anyone's real name or where they go to school")), TestSettings.other().schedule(),
                TestSettings.other().writer(), TestSettings.other().web(), TestSettings.other().features(), TestSettings.other().alerts());
        String nmDesk = "{\"printed\": [{\"id\": \"2026-11-05-000000b5\", \"quote\": \"\", \"reply_html\": \"<p>Creepers were seen near The Old Map. No arrests.</p>\"}], \"held\": [], \"concern\": []}";
        t(MailDesk.validate(m, nmDesk, true, nm, d).held.get(0).get("reason").equals("check: never-mention"), "mail never-mention");
        t(FactCheck.problems(e, "<p>Back on the old map.</p>", d, nm).equals(List.of("never-mention (\"the old map\")")), "edition never-mention");
        // P10: an owner row without minutes (owner.hours-ok: false) shows "-" in the plain edition, not 0
        JsonObject fo = new JsonObject();
        fo.add("today", PyJson.loads("{\"Steve_Builder\": {\"mob_kills\": 4}}"));
        t(Fallback.edition(TestSettings.other(), fo).contains("<td>Steve_Builder</td><td>-</td>"), "no minutes shown as -");
    }

    static List<String> texts(JsonArray a) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : a) out.add(e.getAsJsonObject().get("text").getAsString());
        return out;
    }

    /** Java-side extras: sanitizer edge cases and settings generalization. */
    static void extras() {
        safety();
        gates();
        t(HtmlClean.clean("<p onclick=\"x()\">hi <b>there</b></p><script>alert(1)</script><img src=x onerror=y>").equals("<p>hi there</p>"), "attrs/script/img gone");
        t(HtmlClean.clean("<h2>A &amp; B</h2><p>1 < 2</p>").equals("<h2>A &amp; B</h2><p>1 &lt; 2</p>"), "text escaped");
        t(HtmlClean.clean("<p>open <strong>bold").equals("<p>open <strong>bold</strong></p>"), "unclosed closed");
        t(HtmlClean.clean("<STYLE>p{}</STYLE><p>x</p><!-- c --><p>y<br/>z</p>").equals("<p>x</p><p>y<br>z</p>"), "style, comment, br/");
        t(HtmlClean.clean("<p>x</p><p class=\"unfinished").equals("<p>x</p>"), "unfinished tag drops the rest");
        t(HtmlClean.clean("<svg><p>in svg</p></svg><p>ok</p>").equals("<p>ok</p>"), "svg content dropped");
        t(HtmlClean.cleanReply("<p>a<h3>b</h3><table><tr><td>c</td></tr></table></p>").equals("<p>abc</p>"), "reply allows only p/strong/em/br");
        Settings other = TestSettings.other();
        String sp = StylePrompt.build(other);
        t(!sp.contains("Chicken") && !sp.contains("chicken\"") && !sp.contains("egg-cellent") && !sp.contains("coop"), "no chicken theme for a sheep paper");
        t(sp.contains("\"Sheep Watch\"") && sp.contains("happened to sheep today"), "sheep watch column");
        t(!sp.contains("owns and runs") && !sp.contains("Desk"), "no owner desk without an owner");
        t(sp.contains("- Never mention: anyone's real name."), "never-mention list");
        t(sp.startsWith("You write \"The Daily Block\", the morning newspaper of our Minecraft server. Readers: friends aged 15+."), "generic first line");
        // F6: InGame.loadToday keeps the first 20 sections, so 25 headings must still end with the AI footer
        StringBuilder many = new StringBuilder("<h2>T</h2>");
        for (int i = 0; i < 25; i++) many.append("<h3>S").append(i).append("</h3><p>x</p>");
        JsonArray gsAi = GameEdition.build(sn(), LocalDate.parse("2026-11-05"), new JsonObject(), many.toString(), "L", true, null).getAsJsonArray("sections");
        JsonArray gsPlain = GameEdition.build(sn(), LocalDate.parse("2026-11-05"), new JsonObject(), many.toString(), "L", false, null).getAsJsonArray("sections");
        t(gsAi.size() == 20 && gsAi.get(19).toString().contains(Pages.AI_FOOTER), "F6: AI footer is section 20 of 20");
        t(gsPlain.size() == 20 && !gsPlain.toString().contains(Pages.AI_FOOTER), "F6: plain edition keeps 20 sections, no AI footer");
        // book: a paragraph longer than the old 600 cut arrives whole (it was cut mid-word);
        // a bold label over a table is left out (the book has no tables); bold text over a table, and a bold line with no table, stay
        String longP = "word ".repeat(150).strip();
        JsonArray gl = GameEdition.build(sn(), LocalDate.parse("2026-11-05"), new JsonObject(), "<h2>T</h2><h3>Long</h3><p>" + longP
                + "</p><h3>Building</h3><p>Intro.</p><p><strong>Permit tracker</strong></p>\n<table><tr><td>x</td></tr></table><p><strong>Bold</strong> start.</p><table><tr><td>y</td></tr></table><p><strong>Big news</strong></p><p>After.</p>", "L", false, null).getAsJsonArray("sections");
        t(longP.length() > 600 && gl.get(0).getAsJsonObject().getAsJsonArray("p").get(0).getAsString().equals(longP), "book: a paragraph longer than the old 600 cut is not cut");
        t(!gl.toString().contains("Permit tracker") && gl.toString().contains("Intro.") && gl.toString().contains("Bold start.") && gl.toString().contains("Big news"),
                "book: a bold label over a table is left out; bold text over a table, and a bold line with no table, stay: " + gl);
        // G6: a section sign in AI text would be a colour code in the book and in chat
        t(GameEdition.txt("Town code \u00a7kSecret, rule &sect;4").equals("Town code kSecret, rule 4")
                && !GameEdition.build(sn(), LocalDate.parse("2026-11-05"), new JsonObject(), "<h2>T \u00a7c</h2><h3>S</h3><p>Town code \u00a7kSecret</p>", "L \u00a7l", true, null).toString().contains("\u00a7"),
                "G6: no section sign in the in-game text: " + GameEdition.txt("Town code \u00a7kSecret, rule &sect;4"));
        // pronouns: a hard line for players; the owner gets owner.pronoun, never a hard-coded "he"
        String rule = "- Refer to players by name or as they/them. Never guess anyone's gender from their name.";
        t(sp.contains(rule + "\n"), "pronoun hard line without an owner");
        Settings sn = TestSettings.coop();
        for (String[] pr : new String[][] {{"she", "she/her", "what she did today", "from her season stats"},
                {"they", "they/them", "what they did today", "from their season stats"}, {"he", "he/him", "what he did today", "from his season stats"}}) {
            String op = StylePrompt.build(new Settings(sn.paper(), sn.tone(), new Settings.Owner("TownFounder", "From the Coop Office", List.of(), true, pr[0]),
                    sn.privacy(), sn.schedule(), sn.writer(), sn.web(), sn.features(), sn.alerts()));
            t(op.contains(rule + " The owner, TownFounder, is " + pr[1] + ".\n") && op.contains(pr[2]) && op.contains(pr[3]), "owner pronoun " + pr[0]);
            if (!pr[0].equals("he")) t(!op.contains(" he ") && !op.contains(" his "), "no stray he/his for owner pronoun " + pr[0]);
        }
        // an owner in privacy.hide-players (any case) is no owner: not on the front page, not in the prompts
        Settings hid = new Settings(sn.paper(), sn.tone(), sn.owner(), new Settings.Privacy(List.of("townfounder"), List.of()),
                sn.schedule(), sn.writer(), sn.web(), sn.features(), sn.alerts());
        JsonObject ownerData = new JsonObject(), season = new JsonObject();
        season.addProperty("deaths", 2);
        ownerData.add("season", season);
        JsonObject hidFacts = new JsonObject();
        hidFacts.add("owner", ownerData);
        t(Pages.index(sn, hidFacts, "", List.of(), LocalDate.of(2026, 11, 5), true).contains("TownFounder"), "hidden-owner test sees the owner when not hidden");
        for (String page : List.of(Pages.index(hid, hidFacts, "", List.of(), LocalDate.of(2026, 11, 5), true), StylePrompt.build(hid), ClassicPrompt.build(hid)))
            t(!page.toLowerCase().contains("townfounder"), "hidden owner named: " + page);
        // owner.name "townfounder", in-game "TownFounder": owner.hours-ok: false still keeps the hours off players.html and owner.html
        StatsReader.Row mr = new StatsReader.Row();
        mr.v.put("play_min", 600);
        mr.v.put("deaths", 3);
        Map<String, StatsReader.Row> ms = Map.of("TownFounder", mr);
        LocalDate upd = LocalDate.of(2026, 11, 5);
        StatsReader.Row rich = new StatsReader.Row();
        rich.v.put("play_min", 600);
        rich.killed.put("silverfish", 5L);
        rich.crafted.put("gold_nugget", 10L);
        String richFile = Pages.owner(sn, Map.of(sn.owner().name(), rich), upd);
        t(richFile.contains("Extinction-level</b> (5)") && richFile.contains("10 gold nuggets crafted") && !richFile.contains("golden carrots"), "owner file prints only non-zero jokes: " + richFile);
        java.util.Locale was = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"));   // a server whose language is Arabic: still "01", not "٠١"
        try { t(Pages.owner(sn, Map.of(sn.owner().name(), rich), upd).equals(richFile), "owner file with an Arabic default language"); }
        finally { java.util.Locale.setDefault(was); }
        t(Pages.archiveLabel("2026-11-15", false).equals("Sun Nov 15, 2026") && Pages.archiveLabel("2026-11-15", true).equals("Sun 15 Nov 2026")
                && Pages.archiveLabel("notes", false).equals("notes"), "archive labels");
        // back issues: date + that day's first h2, escaped and capped; no h2 = date only
        String back = Pages.index(sn, hidFacts, "", List.of(Map.entry("2026-11-15", "<p>x</p><h2>Cats &amp; <b>Dogs</b> &lt;i&gt;</h2><h2>second</h2>"),
                Map.entry("2026-11-14", "<p>no headline</p>"), Map.entry("2026-11-13", "<h2>" + "A".repeat(100) + "</h2>")), upd, true);
        t(back.contains("<summary>Sun Nov 15, 2026 <span class=\"hl\">Cats &amp; Dogs &lt;i&gt;</span></summary>")
                && back.contains("<summary>Sat Nov 14, 2026</summary>") && back.contains("<span class=\"hl\">" + "A".repeat(79) + "…</span>"), "back issue headlines: " + back);
        // owner page slang follows tone.slang: heavy prints the slang lines, off and light print plain ones
        rich.v.put("deaths", 3);
        rich.crafted.put("golden_carrot", 2L);
        rich.v.put("mined_ancient_debris", 1);
        rich.killed.put("chicken", 4L);
        rich.v.put("eggs", 19);
        rich.used.put("diamond_pickaxe", 7L);
        rich.used.put("diamond_hoe", 1L);
        List<String> slangy = List.of("No cap", "aura", "cooking", "It's giving", "chat is this real", "opp", "Main character",
                "literally", "arc loading", "diabolical", "cooked", "CEO mindset", " fr.");
        for (String sl : List.of("off", "light", "heavy")) {
            String f = Pages.owner(TestSettings.with(new Settings.Tone("classic", "spicy", "pg13", sl, "teens", "normal", List.of(), List.of(), List.of())), Map.of(sn.owner().name(), rich), upd);
            for (String w : slangy) t(f.contains(w) == sl.equals("heavy"), "owner page slang " + sl + ", '" + w + "': " + f);
            // no body or looks slang and no "bro" at any level: these are fixed lines, so no word gate ever reads them
            for (String w : List.of("Mogging", "Big back", "Touch grass", "Bro ", "bro ")) t(!f.contains(w), "owner page slang " + sl + " prints '" + w + "': " + f);
            t(java.util.Collections.disjoint(WordGate.floor(Py.stripTags(f, " "), List.of()), List.of("mog", "mogging", "big back", "touch grass")), "owner page floor words, slang " + sl + ": " + f);
        }
        // the owner's own pronoun, not "bro"
        Settings heavy = TestSettings.with(new Settings.Tone("classic", "spicy", "pg13", "heavy", "teens", "normal", List.of(), List.of(), List.of()));
        String hers = Pages.owner(new Settings(heavy.paper(), heavy.tone(), new Settings.Owner("Steve_Builder", "The Owner's Desk", List.of(), true, "she"), heavy.privacy(),
                heavy.schedule(), heavy.writer(), heavy.web(), heavy.features(), heavy.alerts()), Map.of("Steve_Builder", rich), upd);
        t(hers.contains("Weapon stats (she's cooking)") && hers.contains("Simply does NOT die. +10,000 aura.") && hers.contains("Plays legit and the aura is real."), "owner page uses the owner's pronoun: " + hers);
        // a new owner (half an hour, nothing done): no joke or claim about a zero, on any slang level
        StatsReader.Row fresh = new StatsReader.Row();
        fresh.v.put("play_min", 30);
        for (Settings fs : List.of(sn, heavy)) {
            String ff = Pages.owner(fs, Map.of(sn.owner().name(), fresh), upd);
            for (String w : List.of("Diamond everything", "seen things", "baby chickens", "marathons", "Kill feed", "Weapon stats", "Zombies", "diamond hoe"))
                t(!ff.contains(w), "new owner's page prints '" + w + "': " + ff);
            t(ff.contains("Travel log") && ff.contains("No wings yet") && ff.contains("Final verdict"), "new owner's page keeps the plain parts: " + ff);
        }
        // ...and each one comes back with its number
        fresh.used.put("diamond_axe", 2L);
        fresh.killed.put("zombie", 3L);
        fresh.v.put("horse_m", 90000);
        fresh.v.put("eggs", 7);
        fresh.killed.put("chicken", 1L);
        String some = Pages.owner(sn, Map.of(sn.owner().name(), fresh), upd);
        t(some.contains("Diamond everything") && some.contains("<td>Diamond axe</td><td class=\"n\">2</td>") && !some.contains("Diamond pickaxe</td>") && !some.contains("seen things")
                && some.contains("<td>Zombies</td><td class=\"n\">3</td>") && !some.contains("Skeletons") && some.contains("done 2 marathons")
                && some.contains("Eggs thrown") && !some.contains("baby chickens"), "owner page prints a row or joke only for a number above zero: " + some);
        fresh.v.put("eggs", 19);   // the egg joke ends on the chickens killed later, so it needs both numbers
        t(Pages.owner(sn, Map.of(sn.owner().name(), fresh), upd).contains("baby chickens"), "owner page: egg joke with 19 eggs and a chicken kill");
        fresh.killed.remove("chicken");
        t(!Pages.owner(sn, Map.of(sn.owner().name(), fresh), upd).contains("baby chickens"), "owner page: no egg joke when no chicken was killed");
        rich.v.put("deaths", 40);   // the many-deaths line
        String manyD = Pages.owner(TestSettings.with(new Settings.Tone("classic", "spicy", "pg13", "heavy", "teens", "normal", List.of(), List.of(), List.of())), Map.of(sn.owner().name(), rich), upd);
        String calm = Pages.owner(TestSettings.with(new Settings.Tone("classic", "spicy", "pg13", "off", "teens", "normal", List.of(), List.of(), List.of())), Map.of(sn.owner().name(), rich), upd);
        t(manyD.contains("Comeback arc") && !calm.contains("Comeback arc") && calm.contains("Plays fair."), "many deaths: slang only at heavy");
        String nr = Pages.owner(TestSettings.with(new Settings.Tone("newsroom", "spicy", "pg13", "heavy", "teens", "normal", List.of(), List.of(), List.of())), Map.of(sn.owner().name(), rich), upd);
        t(!nr.contains("No cap") && nr.contains("We checked"), "newsroom at heavy prints the plain owner page: " + nr);
        rich.v.put("deaths", 3);
        String none = Pages.players(sn, Map.of(), upd);   // the front page always links players.html, so a fresh server needs one too
        t(none != null && none.contains("No cards yet") && !none.contains("class=\"pc\""), "players page with nobody on it yet: " + none);
        // M4: every page asks search engines not to index or keep a copy of it
        for (String pg : List.of(back, none, richFile))
            t(pg.contains("<meta name=\"robots\" content=\"noindex, noarchive\">"), "noindex in the head: " + Py.head(pg, 200));
        for (boolean ok : new boolean[]{true, false}) {
            Settings lc = new Settings(sn.paper(), sn.tone(), new Settings.Owner("townfounder", sn.owner().deskTitle(), sn.owner().lore(), ok, sn.owner().pronoun()),
                    sn.privacy(), sn.schedule(), sn.writer(), sn.web(), sn.features(), sn.alerts());
            String card = Pages.players(lc, ms, upd), file = Pages.owner(lc, ms, upd);
            t(card.contains("owner</span>") && card.contains("hours played") == ok && card.contains("No-Life Champion") == ok, "owner card, hours-ok " + ok + ": " + card);
            t(file != null && file.contains("Operative Debrief: TownFounder") && file.contains(" in 10 hours") == ok, "owner file, hours-ok " + ok + ": " + file);
            t(!file.contains("gold farm") && !file.contains("Extinction-level") && !file.contains("Economy") && !file.contains("EXTREME") && !file.contains("check on the"), "zero stats print no fixed jokes: " + file);
            t(card.contains("out of 1 player (season"), "one player is singular: " + card);
            JsonObject pf = new JsonObject(), today = new JsonObject(), mp = new JsonObject();
            mp.addProperty("play_min", 30);
            today.add("TownFounder", mp);
            pf.add("today", today);
            t(ClassicPrompt.trim(lc, pf).toString().contains("play_min") == ok, "classic facts keep the owner's minutes only when hours-ok, hours-ok " + ok);
        }
        // owner.hours-ok: false with other players: the card order, the hours ranks and the per-hour badges must not give the hours away
        Map<String, StatsReader.Row> three = new java.util.LinkedHashMap<>();
        for (String[] pl : new String[][] {{"Sol_Dig", "6600"}, {"Steve_Builder", "6000"}, {"Kai_Ore", "5400"}}) {
            StatsReader.Row pr = new StatsReader.Row();
            pr.v.put("play_min", Integer.parseInt(pl[1]));
            pr.v.put("blocks_mined", 500);
            three.put(pl[0], pr);
        }
        for (boolean ok : new boolean[]{true, false}) {
            Settings ho = new Settings(sn.paper(), sn.tone(), new Settings.Owner("Steve_Builder", sn.owner().deskTitle(), List.of(), ok, "they"),
                    sn.privacy(), sn.schedule(), sn.writer(), sn.web(), sn.features(), sn.alerts());
            String pg = Pages.players(ho, three, upd);
            String cards = pg.substring(pg.indexOf("<article class=\"pc\""));
            String own = cards.substring(cards.indexOf("id=\"Steve_Builder\""), cards.indexOf("</article>", cards.indexOf("id=\"Steve_Builder\"")));
            int a = cards.indexOf("id=\"Sol_Dig\""), b = cards.indexOf("id=\"Steve_Builder\""), c = cards.indexOf("id=\"Kai_Ore\"");
            t(ok ? a < b && b < c : b < a && a < c, "owner card place, hours-ok " + ok + ": " + cards);
            t(cards.contains("<dd>110 <span class=\"rk one\">#1</span>") && cards.contains("<dd>90.0 <span class=\"rk\">#" + (ok ? 3 : 2) + "</span>"), "hours ranks have no gap, hours-ok " + ok + ": " + cards);
            t(own.contains("hours played") == ok && own.contains("per hour") == ok && !own.contains("Fresh"), "owner card hours hints, hours-ok " + ok + ": " + own);
            for (Pages.Card cd : Pages.cardRows(ho, three)) {   // per hour: the owner holds no place, and nobody else's place has a gap
                if (cd.name.equals("Sol_Dig")) t(cd.trank.get("blocks_mined") == (ok ? 3 : 2), "per-hour place next to a hidden owner, hours-ok " + ok + ": " + cd.trank);
                if (cd.name.equals("Steve_Builder")) t(cd.trank.values().stream().allMatch(java.util.Objects::isNull) != ok && (cd.rank.get("play_h") == null) != ok, "owner's hours places, hours-ok " + ok + ": " + cd.trank);
            }
        }
        // one long word in a story, a letter or a lore line must not push a page sideways on a phone
        t(Pages.PAGE_CSS.contains("h1,h2,h3,a,li,p,blockquote,summary,.eyebrow{overflow-wrap:anywhere}") && Pages.OWNER_CSS.contains("h1,a,li,p,.eyebrow{overflow-wrap:anywhere}"), "long words wrap");
        // tone: the voice block is last in the system part, the spice and language lines are expectations, the recap ends the prompt
        String hot = StylePrompt.build(sn), voice = hot.substring(hot.indexOf("\nVOICE ("));
        t(hot.indexOf("\nVOICE (") > hot.indexOf("BLOTTER MEANINGS") && voice.contains("- Spice: SPICY. Roast the plays like friends")
                && voice.contains("at least 4 lines in the paper should actually sting")
                && voice.contains("- Language: PG-13. Use 2-3 mild swears") && voice.contains("where they land as the punchline") && voice.contains("never a sentence on its own")
                && !voice.contains("none is fine")
                && voice.contains("No WTF and no acronyms"), "pg13 + spicy voice block");
        t(!hot.contains("LAST CHECK") && !hot.contains("TONE (") && hot.indexOf("Spice:") == hot.lastIndexOf("Spice:"), "tone said once, in VOICE");
        t(StylePrompt.prompt(sn, new JsonObject()).endsWith("Now write it: the caster, SPICY, PG-13 (2-3 mild swears, where they land as the punchline), the best story leads, "
                + "slang at most 2 from WORDS FOR TODAY and never as a tag, game words wherever the facts back them, no police voice, every hard line kept.\n"), "recap last");
        for (String[] tl : new String[][] {{"mild", "clean"}, {"medium", "clean"}}) {
            String p = StylePrompt.build(TestSettings.with(new Settings.Tone(tl[0], tl[1], "normal", List.of("animal-watch"))));
            t(!p.contains("should actually sting") && !p.contains("2-3 mild swears") && p.contains("- Language: CLEAN. No swearing"), "no bite or swear target for " + tl[0] + "/" + tl[1]);
        }
        t(StylePrompt.build(TestSettings.with(new Settings.Tone("savage", "pg13", "normal", List.of()))).contains("- Spice: SAVAGE. Every item is a roast"), "savage line");
        t(!FactCheck.problems(new JsonObject(), "<p>fowl play</p>", LocalDate.parse("2026-11-05"), TestSettings.coop()).isEmpty()
                && FactCheck.problems(new JsonObject(), "<p>fowl play</p>", LocalDate.parse("2026-11-05"), other).isEmpty(), "chicken puns only for chickens");
        t(FactCheck.problems(new JsonObject(), "<p>On Friday 1,234 eggs</p>", LocalDate.parse("2026-11-05"), other).equals(List.of("1234", "\"friday\"")), "numbers + weekday");
        String fb = Fallback.edition(other, new JsonObject());
        JsonObject withBackup = new JsonObject();
        withBackup.add("server", PyJson.loads("{\"tps_avg\": 19.5, \"tps_min\": 18.0, \"backup\": \"newest backup 1 h old\"}"));
        t(!fb.contains("Server health") && !fb.contains("?") && !fb.contains("backup")
                && Fallback.edition(other, withBackup).contains("<p>TPS avg 19.5, lowest 18.0; backup: newest backup 1 h old</p>"), "P3: no Server health section without TPS values; backup line only with a health file: " + fb);
        t(fb.startsWith("<h2>The Daily Block</h2>") && fb.contains("⭐ Star of the Day") && !fb.contains("Debrief"), "fallback generalized");
    }
}
