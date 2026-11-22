package io.github.eastmadc.masthead.edition;

import io.github.eastmadc.masthead.config.Settings;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Golden-test driver: produces the same artifacts as the Python commands so they can be diffed (settings = the test paper's).
 * <pre>
 *   style                                  -> StylePrompt.build
 *   prompt  DATA.json                      -> full writer prompt (style + DATA)
 *   desk    DATA.json                      -> editor desk prompt
 *   check   DATA.json FRAG DATE            -> fact-check line
 *   mail    DATA.json DESK.json DATE       -> mailbag json, then letters and tips
 *   render  STATE DATE FRAG OUTDIR [SINCE] -> STATE/editions/DATE.html written; OUTDIR/{index,players,owner}.html, game.json, mailbag.json
 *   kinds   CARDS.json                     -> the kinds desk's exact prompt (CARDS = a JSON array of cards; any "id" is replaced)
 *   site-cards CONSTRUCTION_DIR DATE [WORLD_UUID=SEED ...]
 *                                          -> the prompt the plugin would send for DATE from a server's construction folder (folded
 *                                             on a copy; the folder is never changed); "A = build 9" lines and notes on stderr
 * </pre>
 * Try the kinds prompt before a deploy: {@code GoldenTool kinds src/test/golden/kinds/cards.json | claude -p}
 */
public final class GoldenTool {
    public static void main(String[] a) throws Exception {
        Settings s = TestSettings.coop();
        switch (a[0]) {
            case "style" -> {
                if (a.length > 1) {   // style SPICE LANGUAGE LENGTH bit,bit,...  (the animal watch is always on, as in production)
                    java.util.List<String> bits = new java.util.ArrayList<>();
                    for (String b : a[4].split(",")) if (!b.isEmpty()) bits.add(b);
                    bits.add("animal-watch");
                    s = TestSettings.with(new Settings.Tone(a[1], a[2], a[3], bits));
                }
                System.out.print(StylePrompt.build(s));
            }
            case "prompt" -> System.out.println(StylePrompt.prompt(s, load(a[1])));
            case "kinds" -> {
                java.util.List<JsonObject> cards = new java.util.ArrayList<>();
                for (com.google.gson.JsonElement e : com.google.gson.JsonParser.parseString(Files.readString(Path.of(a[1]), StandardCharsets.UTF_8)).getAsJsonArray()) {
                    JsonObject c = e.getAsJsonObject().deepCopy();
                    c.remove("id");
                    cards.add(c);
                }
                System.out.println(io.github.eastmadc.masthead.construction.KindDesk.prompt(cards));
            }
            case "site-cards" -> {
                var r = io.github.eastmadc.masthead.construction.SiteCards.run(Path.of(a[1]), LocalDate.parse(a[2]),
                        io.github.eastmadc.masthead.construction.SiteCards.seeds(a, 3));
                for (String l : r.lines()) System.err.println(l);
                System.out.println(r.prompt());
            }
            case "desk" -> {
                String p = MailDesk.prompt(s, load(a[1]));
                if (p != null) System.out.println(p);
            }
            case "check" -> System.out.println(FactCheck.line(FactCheck.problems(load(a[1]), Files.readString(Path.of(a[2])), LocalDate.parse(a[3]), s)));
            case "mail" -> {
                MailDesk.Result r = MailDesk.validate(load(a[1]), MailDesk.readText(Path.of(a[2])), true, "pg13", LocalDate.parse(a[3]));
                System.out.println(PyJson.dumps(r.toJson(), false));
                for (String l : r.letters) System.out.println("LETTER " + l);
                for (String t : r.tips) System.out.println("TIP " + t);
            }
            case "render" -> {
                Path st = Path.of(a[1]);
                LocalDate d = LocalDate.parse(a[2]);
                Path out = Path.of(a[4]);
                Files.createDirectories(out);
                LocalDate since = a.length > 5 ? LocalDate.parse(a[5]) : null;
                JsonObject data = load(st.resolve("data").resolve(d + ".json").toString());
                String desk = MailDesk.readText(st.resolve("in").resolve("mailbag-" + d + ".json"));
                Render.Rendered r = Render.render(s, data, d, MailDesk.readText(Path.of(a[3])), desk,
                        new Render.Inputs(st.resolve("editions"), st.resolve("stats").resolve(d.toString()), st.resolve("mailbag-printed.jsonl"), since),
                        LocalDate.now());
                Files.createDirectories(st.resolve("editions"));   // a published render no longer writes the archive; EditionJob does
                w(st.resolve("editions").resolve(d + ".html"), r.archiveHtml());
                w(out.resolve("index.html"), r.indexHtml());
                if (r.playersHtml() != null) w(out.resolve("players.html"), r.playersHtml());
                if (r.ownerHtml() != null) w(out.resolve("owner.html"), r.ownerHtml());
                w(out.resolve("game.json"), PyJson.dumps(r.game(), false));
                w(out.resolve("mailbag.json"), PyJson.dumps(r.mail().toJson(), false));
                for (String x : r.warnings()) System.err.println("WARN: " + x);
                System.out.println(r.summary());
            }
            case "batch" -> {   // batch CASES.json DATA.json: [[clean, cleanReply, check, bad(edition), bad(mail), namesIn], ...]
                JsonObject data = load(a[2]);
                java.util.List<String> names = new java.util.ArrayList<>(data.getAsJsonObject("season_totals").keySet());
                com.google.gson.JsonArray out = new com.google.gson.JsonArray();
                for (com.google.gson.JsonElement e : PyJson.loads(Files.readString(Path.of(a[1]))).getAsJsonArray()) {
                    String x = e.getAsString();
                    com.google.gson.JsonArray r = new com.google.gson.JsonArray();
                    r.add(HtmlClean.clean(x));
                    r.add(HtmlClean.cleanReply(x));
                    r.add(FactCheck.line(FactCheck.problems(data, x, LocalDate.parse("2026-11-05"), s)));
                    r.add(String.join(",", WordGate.bad(x, names, false, "pg13")));
                    r.add(String.join(",", WordGate.bad(x, names, true, "clean")));
                    r.add(String.join(",", WordGate.namesIn(x, names)));
                    r.add(String.valueOf(MailDesk.quoteOk(x, "nimbit is not a cheater, he just mines a lot")));
                    r.add(GameEdition.txt(x));
                    out.add(r);
                }
                System.out.print(PyJson.dumps(out, true));
            }
            default -> throw new IllegalArgumentException(a[0]);
        }
    }

    static JsonObject load(String f) throws Exception {
        return PyJson.loads(Files.readString(Path.of(f), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    static void w(Path p, String s) throws Exception {
        Files.writeString(p, s, StandardCharsets.UTF_8);
    }
}
