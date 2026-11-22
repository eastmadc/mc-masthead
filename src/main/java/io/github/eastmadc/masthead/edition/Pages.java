package io.github.eastmadc.masthead.edition;

import io.github.eastmadc.masthead.config.Settings;
import io.github.eastmadc.masthead.construction.Kinds;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The published HTML pages (port of PAGE / render's index part, players_page + card_rows, owner_full). Same look as
 * production (Pixelify Sans / Atkinson Hyperlegible / JetBrains Mono, light and dark tokens); every name comes from
 * Settings. Pages are returned as strings; the caller writes them (index.html, players.html, owner.html).
 */
public final class Pages {
    private Pages() {}

    /** Shown on the web page and the last book page. Pages load nothing from other websites (R7). */
    public static final String AI_FOOTER = "Written by an AI from this server's game stats. Numbers are checked; jokes are jokes.";
    public static final String PLAIN_FOOTER = "Written from this server's game stats.";
    public static final String INDEX_FILE = "index.html", PLAYERS_FILE = "players.html", OWNER_FILE = "owner.html";


    /** The index page's CSS (production PAGE style block). */
    static final String PAGE_CSS = """

            :root{--bg:#e4e8ee;--surface:#f5f7fa;--fg:#1a1e26;--muted:#566070;--line:#c7cdd6;--diamond:#0d6e7d;--gold:#855c00;--redstone:#b3261e;
            --display:"Masthead Digits","Pixelify Sans","Courier New",monospace;--body:"Atkinson Hyperlegible",system-ui,sans-serif;--data:"JetBrains Mono",ui-monospace,monospace}
            @media (prefers-color-scheme: dark){:root{--bg:#14171c;--surface:#1d2128;--fg:#e8ebf0;--muted:#9aa4b3;--line:#2e343e;--diamond:#52d7e6;--gold:#f2c14e;--redstone:#ff6b5e;color-scheme:dark}}
            body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.6 var(--body)}
            .wrap{max-width:680px;margin:0 auto;padding:28px 16px 64px;display:grid;gap:18px}
            h1,h2,h3{font-family:var(--display);font-weight:600;line-height:1.2;margin:0;text-wrap:balance}
            h1{font-size:clamp(28px,6vw,42px)} h2{font-size:24px;color:var(--diamond)} h3{font-size:18px;margin-top:14px}
            h1,h2,h3,a,li,p,blockquote,summary,.eyebrow{overflow-wrap:anywhere} a{color:var(--diamond)} code{white-space:nowrap}
            .eyebrow{display:block;line-height:1.5;font-family:var(--data);font-size:12px;letter-spacing:.12em;text-transform:uppercase;color:var(--muted)}
            article{background:var(--surface);border:2px solid var(--line);padding:20px;display:grid;gap:10px;min-width:0}
            article p{margin:0} article ul,article ol{margin:0;padding-left:1.2em}
            table{border-collapse:collapse;width:100%;font-size:15px;display:block;overflow-x:auto}
            th,td{text-align:left;padding:6px 10px;border-bottom:1px solid var(--line)} th{font-family:var(--data);font-size:12px;color:var(--muted);text-transform:uppercase}
            blockquote{margin:0;border-left:4px solid var(--gold);padding-left:12px;font-family:var(--display)}
            details{background:var(--surface);border:2px solid var(--line);padding:0 16px;min-width:0} summary{cursor:pointer;font-family:var(--display);padding:10px 0}
            summary .hl{color:var(--muted);font-family:var(--body);font-size:14px}
            details>article{padding:0;border:0;background:none;margin-bottom:12px}
            .foot{color:var(--muted);font-size:14px;text-align:center}
            .owner{border-color:var(--gold)} .owner dl{display:grid;grid-template-columns:repeat(auto-fill,minmax(170px,1fr));gap:8px 16px;margin:0}
            .owner dl div{display:flex;justify-content:space-between;gap:8px;border-bottom:1px dashed var(--line);padding-bottom:3px} .owner dt{color:var(--muted);font-size:14px} .owner dd{margin:0;font-family:var(--data)}
            .stamp{display:inline-block;font-family:var(--display);color:var(--redstone);border:3px solid var(--redstone);padding:0 8px;transform:rotate(-3deg);font-size:14px;justify-self:start}
            """;

    static final String OWNER_CSS = """
            /* Layout: one centered reading column of "case file" cards; tables scroll inside their own box. */
            :root{
              --bg:#e4e8ee; --surface:#f5f7fa; --fg:#1a1e26; --muted:#566070; --line:#c7cdd6;
              --diamond:#0d6e7d; --redstone:#b3261e; --gold:#855c00; --grass:#3b7a2a;
              --display:"Masthead Digits", "Pixelify Sans", "Courier New", monospace; --body:"Atkinson Hyperlegible", system-ui, sans-serif; --data:"JetBrains Mono", ui-monospace, monospace;
            }
            @media (prefers-color-scheme: dark){:root:not([data-theme="light"]){
              --bg:#14171c; --surface:#1d2128; --fg:#e8ebf0; --muted:#9aa4b3; --line:#2e343e;
              --diamond:#52d7e6; --redstone:#ff6b5e; --gold:#f2c14e; --grass:#86d362; color-scheme:dark}}
            :root[data-theme="dark"]{
              --bg:#14171c; --surface:#1d2128; --fg:#e8ebf0; --muted:#9aa4b3; --line:#2e343e;
              --diamond:#52d7e6; --redstone:#ff6b5e; --gold:#f2c14e; --grass:#86d362; color-scheme:dark}
            body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.6 var(--body)}
            .wrap{max-width:860px;margin:0 auto;padding-block:32px 64px;padding-left:max(16px,env(safe-area-inset-left,0px));padding-right:max(16px,env(safe-area-inset-right,0px));display:grid;gap:20px}
            h1,h2,h3{font-family:var(--display);font-weight:600;text-wrap:balance;margin:0;line-height:1.2}
            h1{font-size:clamp(28px,6vw,44px)} h2{font-size:22px;display:flex;gap:10px;align-items:baseline} h3{font-size:18px}
            h1,a,li,p,.eyebrow{overflow-wrap:anywhere}
            h2 .no{font-family:var(--data);font-size:14px;color:var(--muted)}
            p{margin:0;max-width:68ch} ul,ol{margin:0;padding-left:1.2em;display:grid;gap:4px}
            .card{background:var(--surface);border:2px solid var(--line);padding:20px;display:grid;gap:12px;min-width:0}
            .eyebrow{font-family:var(--data);font-size:12px;letter-spacing:.12em;text-transform:uppercase;color:var(--muted)}
            .stamp{display:inline-block;font-family:var(--display);color:var(--redstone);border:3px solid var(--redstone);padding:2px 10px;transform:rotate(-3deg);letter-spacing:.08em}
            .tbl{overflow-x:auto} table{border-collapse:collapse;width:100%;font-size:15px}
            th,td{text-align:left;padding:7px 10px;border-bottom:1px solid var(--line);vertical-align:top}
            th{font-family:var(--data);font-size:12px;letter-spacing:.06em;text-transform:uppercase;color:var(--muted)}
            td.n,.n{font-family:var(--data);font-variant-numeric:tabular-nums;white-space:nowrap}
            .big{font-family:var(--display);font-size:clamp(34px,8vw,56px);color:var(--diamond);line-height:1}
            .quote{border-left:4px solid var(--gold);padding-left:14px;font-family:var(--display);font-size:19px}
            .dia{color:var(--diamond)} .red{color:var(--redstone)} .gold{color:var(--gold)} .grass{color:var(--grass)}
            code{font-family:var(--data);font-size:.92em;background:var(--bg);padding:1px 5px}
            .foot{color:var(--muted);font-size:14px;text-align:center}
            :focus-visible{outline:3px solid var(--diamond);outline-offset:2px}
            """;

    /**
     * The three fonts (SIL OFL 1.1), unmodified from the google/fonts repository, shipped in the jar under fonts/ and
     * copied next to the pages by {@link #writeFonts}. Each family's OFL.txt goes with them, at the same path.
     */
    public static final List<String> FONT_FILES = List.of(
            "fonts/pixelify-sans/PixelifySans[wght].ttf", "fonts/pixelify-sans/OFL.txt",
            "fonts/atkinson-hyperlegible/AtkinsonHyperlegible-Regular.ttf", "fonts/atkinson-hyperlegible/AtkinsonHyperlegible-Bold.ttf",
            "fonts/atkinson-hyperlegible/AtkinsonHyperlegible-Italic.ttf", "fonts/atkinson-hyperlegible/AtkinsonHyperlegible-BoldItalic.ttf",
            "fonts/atkinson-hyperlegible/OFL.txt",
            "fonts/jetbrains-mono/JetBrainsMono[wght].ttf", "fonts/jetbrains-mono/OFL.txt");

    /** Relative URLs only: the pages load nothing from other websites. [ ] are percent-encoded (upstream file names). */
    static final String FONT_CSS = """

            @font-face{font-family:"Pixelify Sans";src:url("fonts/pixelify-sans/PixelifySans%5Bwght%5D.ttf") format("truetype");font-weight:400 700;font-display:swap}
            @font-face{font-family:"Atkinson Hyperlegible";src:url("fonts/atkinson-hyperlegible/AtkinsonHyperlegible-Regular.ttf") format("truetype");font-weight:400;font-display:swap}
            @font-face{font-family:"Atkinson Hyperlegible";src:url("fonts/atkinson-hyperlegible/AtkinsonHyperlegible-Bold.ttf") format("truetype");font-weight:700;font-display:swap}
            @font-face{font-family:"Atkinson Hyperlegible";src:url("fonts/atkinson-hyperlegible/AtkinsonHyperlegible-Italic.ttf") format("truetype");font-weight:400;font-style:italic;font-display:swap}
            @font-face{font-family:"Masthead Digits";src:url("fonts/atkinson-hyperlegible/AtkinsonHyperlegible-Bold.ttf") format("truetype");font-weight:400 700;unicode-range:U+2C,U+2E,U+30-39;font-display:swap}
            @font-face{font-family:"Atkinson Hyperlegible";src:url("fonts/atkinson-hyperlegible/AtkinsonHyperlegible-BoldItalic.ttf") format("truetype");font-weight:700;font-style:italic;font-display:swap}
            @font-face{font-family:"JetBrains Mono";src:url("fonts/jetbrains-mono/JetBrainsMono%5Bwght%5D.ttf") format("truetype");font-weight:100 800;font-display:swap}""";

    /** The tab icon, inlined so the pages still load nothing from other websites. */
    static final String ICON = icon();
    private static String icon() {
        try (InputStream in = Pages.class.getClassLoader().getResourceAsStream("icon-64.png")) {
            return in == null ? "" : "<link rel=\"icon\" type=\"image/png\" href=\"data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(in.readAllBytes()) + "\">\n";
        } catch (IOException e) { return ""; }
    }

    static String head(String title, String css) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><meta name=\"robots\" content=\"noindex, noarchive\">\n"
                + ICON + "<title>" + Py.escape(title) + "</title>\n<style>" + FONT_CSS + css;
    }

    /** Copies the fonts and their licenses from the jar into dir/fonts/..., rewriting a file only when it differs. */
    public static void writeFonts(Path dir) throws IOException {
        for (String f : FONT_FILES) {
            byte[] want;
            try (InputStream in = Pages.class.getClassLoader().getResourceAsStream(f)) {
                if (in == null) throw new IOException("missing from the jar: " + f);
                want = in.readAllBytes();
            }
            Path p = dir.resolve(f);
            if (Files.isRegularFile(p) && Arrays.equals(Files.readAllBytes(p), want)) continue;
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".new");
            Files.write(tmp, want);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /** "Henhouse server" / "our Minecraft server" */
    static String serverLabel(Settings s) {
        String n = s.paper().serverName() == null ? "" : s.paper().serverName().trim();
        if (n.isEmpty()) return "Minecraft server";
        return n.toLowerCase(java.util.Locale.ROOT).contains("server") ? n : n + " server";
    }

    // ------------------------------------------------------------------------------------------------ index

    /** The owner card on the front page ("" when there is no owner or no owner stats yet). */
    static String ownerCard(Settings s, JsonObject data) {
        JsonObject o = PyJson.objOf(PyJson.objOf(data, "owner"), "season");
        if (o.isEmpty() || !StylePrompt.hasOwner(s)) return "";
        StringBuilder b = new StringBuilder("<article class=\"owner\"><span class=\"stamp\">CLASSIFIED</span><h2>Owner&#39;s File: ")
                .append(Py.escape(StylePrompt.owner(s))).append("</h2><p>Server owner and admin. Live season stats, updated every morning from the game.</p><dl>");
        String[][] rows = {{"Deaths", "deaths"}, {"Mob kills", "mob_kills"}, {"Player kills", "player_kills"}, {"Jumps", "jumps"},
                {"Eggs thrown", "eggs"}, {"Villager trades", "trades"}, {"Animals bred", "bred"}, {"Diamond ore \"mined\"", null},
                {"Ancient debris", "mined_ancient_debris"}, {"Horse (m)", "horse_m"}, {"Boat (m)", "boat_m"}};
        for (String[] r : rows) {
            String v = r[1] != null ? F(o.get(r[1]))
                    : Py.comma((long) (PyJson.dbl(o.get("mined_diamond_ore")) + PyJson.dbl(o.get("mined_deepslate_diamond_ore"))));
            b.append("<div><dt>").append(r[0]).append("</dt><dd>").append(v).append("</dd></div>");
        }
        b.append("</dl><p><strong>Top kills:</strong> ").append(fav(o, "top_killed")).append("<br><strong>Most used:</strong> ").append(fav(o, "top_used")).append("</p>")
                .append("<p><a href=\"").append(OWNER_FILE).append("\" style=\"font-family:var(--display);font-size:18px\">Read the full classified file&nbsp;→</a></p></article>");
        return b.toString();
    }

    /** f"{int(round(o.get(k, 0) or 0)):,}" */
    private static String F(JsonElement e) {
        if (!PyJson.isNum(e)) return "0";
        if (PyJson.isFloat(e.getAsJsonPrimitive())) return Py.commaRound(e.getAsDouble());
        return Py.comma(PyJson.bigInt(e.getAsJsonPrimitive()));
    }

    private static String fav(JsonObject o, String key) {
        List<String> xs = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : PyJson.objOf(o, key).entrySet()) {
            if (xs.size() >= 3) break;
            JsonElement v = e.getValue();
            String vs = PyJson.isNum(v) && !PyJson.isFloat(v.getAsJsonPrimitive()) ? Py.comma(PyJson.bigInt(v.getAsJsonPrimitive()))
                    : PyJson.isNum(v) ? Py.commaRepr(v.getAsDouble()) : PyJson.pyStr(v);
            xs.add(e.getKey().replace('_', ' ') + " " + vs);
        }
        return Py.escape(xs.isEmpty() ? "-" : String.join(", ", xs));
    }

    /**
     * The front page. latest = the newest edition file's content (eyebrow + fragment); archive = older edition contents,
     * newest first, as {date, content} (the caller passes at most 29). usedAi picks the AI or the plain footer.
     */
    public static String index(Settings s, JsonObject data, String latest, List<Map.Entry<String, String>> archive, LocalDate updated,
                               boolean usedAi) {
        StringBuilder arch = new StringBuilder();
        for (Map.Entry<String, String> e : archive) {
            String hl = headline(e.getValue());
            arch.append("<details><summary>").append(Py.escape(archiveLabel(e.getKey(), s.paper().british())))
                    .append(hl.isEmpty() ? "" : " <span class=\"hl\">" + Py.escape(hl) + "</span>").append("</summary><article>").append(e.getValue()).append("</article></details>");
        }
        String archive2 = arch.length() == 0 ? "<p class=\"foot\">None yet.</p>" : arch.toString();
        List<String> eyebrow = new ArrayList<>();
        eyebrow.add(Py.escape(serverLabel(s)));
        if (StylePrompt.hasOwner(s)) eyebrow.add("owner: " + Py.escape(StylePrompt.owner(s)));
        if (!StylePrompt.taglineClause(s).isEmpty()) eyebrow.add(Py.escape(StylePrompt.taglineClause(s)));
        eyebrow.add("<span style=\"white-space:nowrap\">in-game facts only</span>");
        String paper = Py.escape(s.paper().name());
        return head(s.paper().name(), PAGE_CSS) + "</style></head><body><main class=\"wrap\">\n"
                + "<header><span class=\"eyebrow\">" + String.join(" · ", eyebrow) + "</span><h1>" + paper + "</h1><p><a href=\"" + PLAYERS_FILE
                + "\" style=\"font-family:var(--display);font-size:18px\">Player Cards: everyone's season stats&nbsp;→</a></p></header>\n"
                + "<article>" + latest + "</article>\n"
                + ownerCard(s, data) + "\n"
                + "<h2 style=\"color:var(--fg);font-size:20px\">Back issues</h2>\n"
                + archive2 + "\n"
                + "<p class=\"foot\">" + (usedAi ? AI_FOOTER : PLAIN_FOOTER) + " Updated " + Py.monthDay(updated, s.paper().british()) + ".</p>\n"
                + "</main></body></html>";
    }

    /** A back issue's headline: the text of its first h2, at most 80 characters, or "". */
    static String headline(String content) {
        for (java.util.regex.Matcher m = GameEdition.BLOCK.matcher(content); m.find(); )
            if (m.group(1).equals("h2")) { String h = GameEdition.txt(m.group(2)); if (Py.len(h) <= 80) return h; String c = Py.head(h, 79); int sp = c.lastIndexOf(' '); return (sp > 40 ? c.substring(0, sp) : c) + "…"; }
        return "";
    }

    /** "2026-11-15" -> "Sun Nov 15, 2026"; any other file name is shown as it is. */
    static String archiveLabel(String key, boolean uk) {
        try { return Py.dayLabel(LocalDate.parse(key), uk); } catch (java.time.DateTimeException e) { return key; }
    }

    // ------------------------------------------------------------------------------------------------ player cards

    /** (key, label, title if #1 per hour played). The animal row follows paper.watchAnimal. */
    static List<String[]> cardStats(Settings s) {
        List<String[]> l = new ArrayList<>(List.of(
                new String[]{"play_h", "hours played", "No-Life Champion"}, new String[]{"deaths", "deaths", "Professional Respawner"},
                new String[]{"mob_kills", "mob kills", "Mob Exterminator"}, new String[]{"player_kills", "player kills", "PvP Menace"},
                new String[]{"blocks_mined", "blocks mined", "Human Tunnel Bore"}, new String[]{"diamond_ore", "diamond ore mined", "Diamond Magnet"},
                new String[]{"debris", "ancient debris", "Netherite Hunter"}, new String[]{"jumps", "jumps", "Bunny Hop Champion"},
                new String[]{"foot_km", "km on foot", "Marathon Runner"}, new String[]{"horse_km", "km by horse", "Certified Horse Enjoyer"},
                new String[]{"boat_km", "km by boat", "Captain of the Seas"}, new String[]{"elytra_km", "km flown", "Sky Menace"},
                new String[]{"trades", "villager trades", "Villager CEO"}, new String[]{"bred", "animals bred", "Professional Matchmaker"},
                new String[]{"fish", "fish caught", "Fish Whisperer"}, new String[]{"eggs", "eggs thrown", "Egg Lord"},
                new String[]{"cake", "cake slices", "Cake Goblin"}));
        String a = StylePrompt.animals(s);
        if (!a.isEmpty()) l.add(new String[]{"chickens", a + " killed", "Wanted by the " + Character.toUpperCase(a.charAt(0)) + a.substring(1)});
        return l;
    }

    private static final java.util.Set<String> FLOAT_KEYS = java.util.Set.of("play_h", "foot_km", "horse_km", "boat_km", "elytra_km");

    /** One card row: stat values, season ranks and per-hour title ranks. */
    static final class Card {
        final String name;
        final Map<String, Double> v = new LinkedHashMap<>();
        final Map<String, Integer> rank = new LinkedHashMap<>(), trank = new LinkedHashMap<>();
        final StatsReader.Row raw;

        Card(String name, StatsReader.Row raw) {
            this.name = name;
            this.raw = raw;
        }

        double get(String k) {
            return v.getOrDefault(k, 0.0);
        }
    }

    /** card_rows: players with 10+ minutes; ranks among all (ties share, zero never ranked); titles per hour played (2+ hours). */
    static List<Card> cardRows(Settings s, Map<String, StatsReader.Row> stats) {
        List<Card> rows = new ArrayList<>();
        String animal = StylePrompt.animal(s);
        for (Map.Entry<String, StatsReader.Row> e : stats.entrySet()) {
            StatsReader.Row r = e.getValue();
            if (r.d("play_min") < 10) continue;
            Card c = new Card(e.getKey(), r);
            c.v.put("play_h", r.d("play_min") / 60);
            for (String k : new String[]{"deaths", "mob_kills", "player_kills", "blocks_mined"}) c.v.put(k, r.d(k));
            c.v.put("diamond_ore", r.d("mined_diamond_ore") + r.d("mined_deepslate_diamond_ore"));
            c.v.put("debris", r.d("mined_ancient_debris"));
            c.v.put("jumps", r.d("jumps"));
            c.v.put("foot_km", (r.d("walk_m") + r.d("sprint_m")) / 1000);
            c.v.put("horse_km", r.d("horse_m") / 1000);
            c.v.put("boat_km", r.d("boat_m") / 1000);
            c.v.put("elytra_km", r.d("elytra_m") / 1000);
            for (String k : new String[]{"trades", "bred", "fish", "eggs", "cake"}) c.v.put(k, r.d(k));
            c.v.put("chickens", animal.isEmpty() ? 0.0 : (double) r.killed.getOrDefault(animal, 0L));
            rows.add(c);
        }
        for (String[] st : cardStats(s)) {
            String key = st[0];
            boolean hrs = key.equals("play_h");
            TreeSet<Double> vals = new TreeSet<>(Comparator.reverseOrder());
            for (Card c : rows) if (c.get(key) > 0 && !(hrs && hoursHidden(s, c))) vals.add(Py.round(c.get(key), 3));
            for (Card c : rows) {
                double v = Py.round(c.get(key), 3);
                c.rank.put(key, v > 0 && !(hrs && hoursHidden(s, c)) ? vals.headSet(v).size() + 1 : null);
            }
            // owner.hours-ok: false: the owner is in no hours rank and no per-hour rank (a rate next to a total gives the hours away)
            TreeSet<Double> rv = new TreeSet<>(Comparator.reverseOrder());
            for (Card c : rows) if (c.get("play_h") >= 2 && c.get(key) > 0 && !hoursHidden(s, c)) rv.add(Py.round(rate(c, key), 6));
            for (Card c : rows) {
                if (c.get("play_h") >= 2 && c.get(key) > 0 && !hoursHidden(s, c)) c.trank.put(key, rv.headSet(Py.round(rate(c, key), 6)).size() + 1);
                else c.trank.put(key, null);
            }
        }
        return rows;
    }

    private static boolean hoursHidden(Settings s, Card c) {
        String owner = StylePrompt.owner(s);
        return !owner.isEmpty() && !s.owner().hoursOk() && c.name.equalsIgnoreCase(owner);
    }

    private static double rate(Card c, String key) {
        return key.equals("play_h") ? c.get(key) : c.get(key) / c.get("play_h");
    }

    private static String num(String k, double v) {
        if (FLOAT_KEYS.contains(k) && v < 100) return Py.comma1(v);
        return Py.commaRound(v);
    }

    private static String top(Map<String, Long> d) {
        List<Map.Entry<String, Long>> l = new ArrayList<>(d.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(5, l.size()); i++) out.add(Py.escape(l.get(i).getKey().replace('_', ' ')) + " " + Py.comma(l.get(i).getValue()));
        return out.isEmpty() ? "-" : String.join(", ", out);
    }

    /** The player cards inside players.html (one article per player), as production renders them. */
    static String cards(Settings s, List<Card> rows) {
        List<String[]> stats = cardStats(s);
        List<Card> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing((Card c) -> !hoursHidden(s, c)).thenComparing(c -> -c.get("play_h")));   // hidden hours: the owner's card goes first, not in hours order
        String owner = StylePrompt.owner(s);
        StringBuilder out = new StringBuilder();
        for (Card x : sorted) {
            boolean isOwner = !owner.isEmpty() && x.name.equalsIgnoreCase(owner);
            boolean noHours = isOwner && !s.owner().hoursOk();   // owner.hours-ok: false: no hours played on the owner's card
            List<String> won = new ArrayList<>();
            for (String[] st : stats) if (Integer.valueOf(1).equals(x.trank.get(st[0])) && !(noHours && st[0].equals("play_h"))) won.add(st[2]);
            StringBuilder badge = new StringBuilder();
            if (!won.isEmpty()) {
                for (String t : won) badge.append("<span class=\"badge\">").append(Py.escape(t)).append("</span>");   // every title, no "+N more"
            } else {
                List<Object[]> podium = new ArrayList<>(), any = new ArrayList<>();
                for (String[] st : stats) {
                    Integer r = x.trank.get(st[0]);
                    if (r == null || (noHours && st[0].equals("play_h"))) continue;
                    any.add(new Object[]{r, st[1]});
                    if (r <= 3) podium.add(new Object[]{r, st[1]});
                }
                Comparator<Object[]> cmp = Comparator.comparing((Object[] o) -> (Integer) o[0]).thenComparing(o -> (String) o[1]);
                podium.sort(cmp);
                any.sort(cmp);
                List<Object[]> best = !podium.isEmpty() ? podium : any.subList(0, Math.min(2, any.size()));
                for (Object[] b : best) badge.append("<span class=\"badge quiet\">#").append(b[0]).append(" in ").append(Py.escape((String) b[1])).append(" per hour</span>");
                if (best.isEmpty() && !noHours) badge.append("<span class=\"badge quiet\">").append(StylePrompt.chickenTheme(s) ? "Fresh hatchling" : "Fresh spawn").append("</span>");
            }
            StringBuilder cells = new StringBuilder();
            for (String[] st : stats) {
                String k = st[0];
                if (!(x.get(k) > 0 || k.equals("deaths") || k.equals("mob_kills") || k.equals("play_h")) || (noHours && k.equals("play_h"))) continue;
                Integer r = x.rank.get(k);
                cells.append("<div><dt>").append(Py.escape(st[1])).append("</dt><dd>").append(num(k, x.get(k)));
                if (r != null) cells.append(" <span class=\"rk").append(r == 1 ? " one" : "").append("\">#").append(r).append("</span>");
                cells.append("</dd></div>");
            }
            String extra = isOwner ? "<p><a href=\"" + OWNER_FILE + "\">Read the full classified owner file&nbsp;→</a></p>" : "";
            out.append("<article class=\"pc\" id=\"").append(Py.escape(x.name)).append("\"><h2>").append(Py.escape(x.name))
                    .append(isOwner ? " <span class=\"own\">owner</span>" : "").append("</h2><div class=\"badges\">").append(badge).append("</div>")
                    .append("<dl>").append(cells).append("</dl><p><strong>Top kills:</strong> ").append(top(x.raw.killed))
                    .append("<br><strong>Most mined:</strong> ").append(top(x.raw.mined)).append("</p>").append(extra).append("</article>");
        }
        return out.toString();
    }

    /** players.html; always written, since the front page always links it (no cards until someone has played 10+ minutes). */
    public static String players(Settings s, Map<String, StatsReader.Row> stats, LocalDate updated) {
        List<Card> rows = cardRows(s, stats);
        List<Card> byName = new ArrayList<>(rows);
        byName.sort(Comparator.comparing(c -> Py.lower(c.name)));
        StringBuilder nav = new StringBuilder();
        for (Card c : byName) nav.append("<a href=\"#").append(Py.escape(c.name)).append("\">").append(Py.escape(c.name)).append("</a>");
        String paper = Py.escape(s.paper().name());
        return head("Player Cards · " + s.paper().name(), PAGE_CSS) + """

                .pc dl{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:8px 16px;margin:0}
                .pc dl div{display:flex;justify-content:space-between;gap:8px;border-bottom:1px dashed var(--line);padding-bottom:3px;min-width:0}
                .pc dt{color:var(--muted);font-size:14px} .pc dd{margin:0;font-family:var(--data);white-space:nowrap}
                .rk{font-size:12px;color:var(--muted)} .rk.one{color:var(--gold);font-weight:600}
                .badges{display:flex;flex-wrap:wrap;gap:6px} .badge{font-family:var(--display);font-size:14px;border:2px solid var(--gold);color:var(--gold);padding:1px 8px}
                .badge.quiet{border-color:var(--line);color:var(--muted)} .own{font-family:var(--data);font-size:12px;color:var(--muted);text-transform:uppercase;letter-spacing:.1em}
                nav.jump{display:flex;flex-wrap:wrap;gap:0 14px;font-family:var(--data);font-size:14px} nav.jump a{padding:8px 0}
                .pc{scroll-margin-top:12px}
                </style></head><body><main class="wrap">
                """
                + "<p><a href=\"./\">←&nbsp;back to " + paper + "</a></p>\n"
                + "<header style=\"display:grid;gap:8px\"><span class=\"eyebrow\">" + paper + " · whole-season stats · updated every morning · <span style=\"white-space:nowrap\">in-game facts only</span></span>\n"
                + "<h1>Player Cards</h1><p>" + (rows.isEmpty() ? "No cards yet: a player gets one after 10 minutes on the server."
                : "Every player's season so far. The small <b>#</b> next to each number is that player's place on the server out of "
                + (rows.size() == 1 ? "1 player" : rows.size() + " players") + " (season totals). Gold titles go to whoever does the most of something <em>per hour played</em> (2+ hours), so newer players can win too.")
                + " In game, type <code>/stats</code> for your live numbers.</p></header>\n"
                + "<nav class=\"jump\">" + nav + "</nav>\n"
                + cards(s, rows) + "\n"
                + "<p class=\"foot\">Built from the server's own stats files. Updated " + Py.monthDay(updated, s.paper().british()) + ".</p>\n"
                + "</main></body></html>";
    }

    // ------------------------------------------------------------------------------------------------ town survey

    public static final String SURVEY_FILE = "survey.html";

    /** The Town Survey's groups, in page order; a kind in none of them is a landmark. */
    static final List<Map.Entry<String, List<String>>> SURVEY_GROUPS = List.of(
            Map.entry("Homes & bases", List.of("house", "base", "tower")),
            Map.entry("Farms", List.of("farm", "auto_farm", "greenhouse", "wheat_farm", "carrot_farm", "potato_farm", "melon_farm", "pumpkin_farm",
                    "sugar_cane_farm", "cactus_farm", "bamboo_farm", "kelp_farm", "tree_farm", "mushroom_farm", "flower_farm", "berry_farm",
                    "nether_wart_farm", "bonemeal_farm", "chicken_farm", "cow_farm", "wool_farm", "animal_pen", "bee_farm", "fishing_farm")),
            Map.entry("Mob farms", List.of("mob_farm", "slime_farm", "spider_farm", "zombie_farm", "skeleton_farm", "creeper_farm", "witch_farm",
                    "enderman_farm", "guardian_farm", "blaze_farm", "gold_farm", "hoglin_farm", "raid_farm", "iron_farm")),
            Map.entry("Villagers & trading", List.of("trading_hall", "villager_breeder")),
            Map.entry("Rooms & workshops", List.of("library", "enchanting_room", "brewing_room", "smelter", "storage", "redstone", "item_collector")),
            Map.entry("Public works", List.of("bridge", "road", "rail_line", "wall", "nether_portal")),
            Map.entry("Decoration & landmarks", List.of("decoration", "garden", "lighthouse", "ship")));

    /**
     * survey.html: the opening day's register from the facts' construction.survey rows (kinds already in words, features as
     * n/of/for), grouped by kind. Fixed text and escaped fields only: no positions, nothing from the AI; a build name only from the owner's survey.
     */
    public static String survey(Settings s, JsonObject cons, LocalDate date) {
        Map<String, String> group = new HashMap<>();
        for (Map.Entry<String, List<String>> g : SURVEY_GROUPS) for (String k : g.getValue()) group.put(Kinds.display(k), g.getKey());
        Map<String, StringBuilder> rows = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> g : SURVEY_GROUPS) rows.put(g.getKey(), new StringBuilder());
        int n = 0;
        for (JsonElement el : PyJson.arr(cons.get("survey"))) {
            JsonObject r = PyJson.obj(el);
            String kind = PyJson.pyStr(r.get("kind"));
            List<String> bs = new ArrayList<>(), inside = new ArrayList<>();
            for (JsonElement b : PyJson.arr(r.get("builders"))) bs.add(PyJson.pyStr(b));
            for (JsonElement f : PyJson.arr(r.get("features"))) {
                JsonObject o = PyJson.obj(f);
                inside.add(Py.comma(Fallback.truncL(o.get("n"))) + " " + PyJson.pyStr(o.get("of")) + (o.has("for") ? " for " + PyJson.pyStr(o.get("for")) : ""));
            }
            rows.get(group.getOrDefault(kind, "Decoration & landmarks")).append("<li><strong>")
                    .append(Py.escape("Permit #" + Fallback.truncL(r.get("permit")) + (r.get("name") instanceof com.google.gson.JsonPrimitive nm ? " \"" + nm.getAsString() + "\"" : "")
                            + " — " + kind)).append("</strong><br>")
                    .append(Py.escape("Builders: " + String.join(", ", bs))).append("<br>")
                    .append(inside.isEmpty() ? "" : Py.escape("Inside: " + String.join(", ", inside)) + "<br>")
                    .append(Py.escape(Py.comma(Fallback.truncL(r.get("blocks_total"))) + " blocks")).append("</li>\n");
            n++;
        }
        String paper = Py.escape(s.paper().name()), cm = StylePrompt.commission(s);
        StringBuilder b = new StringBuilder(head("Town Survey · " + s.paper().name(), PAGE_CSS))
                .append("\narticle li{margin-bottom:8px}\n</style></head><body><main class=\"wrap\">\n")
                .append("<p><a href=\"./\">←&nbsp;back to ").append(paper).append("</a></p>\n")
                .append("<header style=\"display:grid;gap:8px\"><span class=\"eyebrow\">").append(paper).append(" · ")
                .append(Py.escape(s.construction().title())).append(" · ").append(Py.escape(Py.dayLabel(date, s.paper().british()))).append("</span>\n<h1>Town Survey</h1><p>")
                .append(Py.escape(n == 0 ? "The register is empty." : Character.toUpperCase(cm.charAt(0)) + cm.substring(1) + "'s first full survey of the town: " + n
                        + (n == 1 ? " build" : " builds") + " on the register, by kind. Never where they are."))
                .append("</p></header>\n");
        for (Map.Entry<String, StringBuilder> g : rows.entrySet())
            if (g.getValue().length() > 0) b.append("<article><h2>").append(Py.escape(g.getKey())).append("</h2><ul>\n").append(g.getValue()).append("</ul></article>\n");
        return b.append("<p class=\"foot\">The register as it stood on the opening day.</p>\n</main></body></html>").toString();
    }

    // ------------------------------------------------------------------------------------------------ owner file

    /** The owner's classified file (owner.html), or null when there is no owner or no stats for them. */
    public static String owner(Settings s, Map<String, StatsReader.Row> stats, LocalDate updated) {
        if (!StylePrompt.hasOwner(s)) return null;
        String ownerName = StylePrompt.owner(s);
        Settings.Owner o = s.owner();
        StatsReader.Row raw = null;
        for (Map.Entry<String, StatsReader.Row> e : stats.entrySet()) if (e.getKey().equalsIgnoreCase(ownerName)) { raw = e.getValue(); ownerName = e.getKey(); }
        if (raw == null) return null;
        Map<String, Long> u = raw.used, k = raw.killed, c = raw.crafted;
        double deaths = raw.d("deaths"), pk = raw.d("player_kills"), eggs = raw.d("eggs");
        double dia = raw.d("mined_diamond_ore") + raw.d("mined_deepslate_diamond_ore");
        String hours = Py.fixed0(raw.d("play_min") / 60);
        String inHours = o.hoursOk() ? " in " + hours + " hours" : "";   // owner.hours-ok: false keeps the hours off the page
        String O = Py.escape(ownerName), paper = Py.escape(s.paper().name());
        String animal = StylePrompt.animal(s), animals = StylePrompt.animals(s);
        boolean fr = StylePrompt.slangMax(s) == 3;   // heavy slang only, as the AI gets it (newsroom caps it at light); else plain wording
        StringBuilder b = new StringBuilder();
        b.append(head(ownerName + " Debrief · " + s.paper().name(), OWNER_CSS)).append("""

                .kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px} .kpi{background:var(--surface);border:2px solid var(--line);padding:14px;display:grid;gap:4px;min-width:0}
                .kpi .v{font-family:var(--display);font-size:clamp(22px,7vw,30px);color:var(--diamond)} .kpi .l{font-size:14px;color:var(--muted)} a{color:var(--diamond)}
                </style></head><body><main class="wrap">
                """);
        b.append("<p><a href=\"./\">←&nbsp;back to ").append(paper).append("</a></p>\n");
        b.append("<header style=\"display:grid;gap:10px\"><span class=\"eyebrow\">").append(Py.escape(s.paper().serverName())).append(" · Server owner file · Clearance: everyone with this link</span>\n");
        b.append("<h1>Operative Debrief: ").append(O).append("</h1><div><span class=\"stamp\">CLASSIFIED</span></div>\n");
        b.append(fr ? "<p><em>chat is this real?</em> yes. Every number below is recalculated from the server every morning. No cap. Last updated "
                : "<p><em>Is this real?</em> Yes. Every number below is recalculated from the server every morning. We checked. Last updated ").append(Py.dayLabel(updated, s.paper().british())).append(".</p></header>\n");
        b.append("<section class=\"kpis\"><div class=\"kpi\"><span class=\"v\">").append(n(deaths)).append("</span><span class=\"l\">deaths").append(inHours).append("</span></div>\n");
        b.append("<div class=\"kpi\"><span class=\"v\">").append(n(u.get("diamond_pickaxe"))).append("</span><span class=\"l\">diamond pickaxe swings</span></div>\n");
        b.append("<div class=\"kpi\"><span class=\"v\">").append(n(eggs)).append("</span><span class=\"l\">eggs thrown</span></div>\n");
        b.append("<div class=\"kpi\"><span class=\"v\">").append(n(raw.d("trades"))).append("</span><span class=\"l\">villager trades</span></div></section>\n\n");
        int sec = 0;
        b.append(card(++sec, "Character sheet (" + o.subjIs() + (fr ? " literally " + o.obj() : " the boss") + ")")).append("<div class=\"tbl\"><table><tbody>\n");
        b.append("<tr><th>Rank</th><td>Server owner and Operator. Built the server, runs the server. ").append(fr ? "Main character energy." : "The star of every story.").append("</td></tr>\n");
        long diamondUse = 0;
        for (String t : new String[]{"diamond_pickaxe", "diamond_axe", "diamond_shovel", "diamond_sword", "diamond_hoe"}) diamondUse += u.getOrDefault(t, 0L);
        if (diamondUse > 0) b.append("<tr><th>Signature tools</th><td>Diamond everything. Nobody else stands a chance").append(fr ? " fr." : ".").append("</td></tr>\n");
        b.append("<tr><th>Deaths</th><td><b>").append(n(deaths)).append(inHours).append(".</b> ")
                .append(deaths <= 5 ? ("Simply does NOT die. " + (fr ? "+10,000 aura." : "A legend."))
                        : (fr ? "The aura took some hits. Comeback arc loading." : "The legend took some hits. A comeback is expected.")).append("</td></tr>\n");
        b.append("<tr><th>Player kills</th><td class=\"n\">").append(n(pk)).append("</td></tr></tbody></table></div></section>\n\n");

        // Weapon stats and kill feed: a row or joke only when its number is above zero, as in Economy below.
        String tools = rowIf("Diamond pickaxe", u.get("diamond_pickaxe")) + rowIf("Diamond axe", u.get("diamond_axe")) + rowIf("Diamond shovel", u.get("diamond_shovel"))
                + rowIf("Diamond sword", u.get("diamond_sword")) + rowIf("Diamond hoe (a <em>diamond hoe</em>? " + (fr ? "diabolical flex)" : "pure showing off)"), u.get("diamond_hoe"))
                + rowIf("Bow", u.get("bow")) + (eggs > 0 ? row("<b>Eggs thrown 🥚</b>", "<b>" + n(eggs) + "</b>") : "");
        if (!tools.isEmpty()) {
            b.append(card(++sec, fr ? "Weapon stats (" + o.subjIs() + " cooking)" : "Weapon stats (tools of the trade)")).append('\n');
            if (u.getOrDefault("diamond_pickaxe", 0L) > 0)
                b.append("<p>The diamond pickaxe has been swung <b>").append(n(u.get("diamond_pickaxe"))).append(" times</b>. That pickaxe has seen things. It deserves therapy.</p>\n");
            b.append("<div class=\"tbl\"><table><thead><tr><th>Tool</th><th>Uses</th></tr></thead><tbody>\n").append(tools).append("</tbody></table></div>\n");
            if (eggs >= 8 && k.getOrDefault("chicken", 0L) > 0)   // 8 eggs: at least one chick to joke about
                b.append("<p>Each egg has a 1-in-8 chance to hatch, so roughly ").append(n(eggs / 8)).append(" baby chickens entered this world because of " + o.obj() + "… and <b>")
                        .append(n(k.get("chicken"))).append(" chickens were later killed</b>. " + cap(o.subj()) + " created them. " + cap(o.subj()) + " deleted them. " + (fr ? "It's giving villain origin story." : "Every villain has an origin story.") + "</p>");
            b.append("</section>\n\n");
        }

        String kills = rowIf("Silverfish (a farm, or it&#39;s personal)", k.get("silverfish")) + rowIf(fr ? "Chickens (cooked from birth, literally)" : "Chickens (doomed from birth)", k.get("chicken"))
                + rowIf("Zombies", k.get("zombie")) + rowIf("Skeletons", k.get("skeleton")) + rowIf("Spiders", k.get("spider")) + rowIf("Pillagers", k.get("pillager"))
                + rowIf("Creepers", k.get("creeper")) + (pk > 0 ? row("<b>Real players</b>", "<b>" + n(pk) + "</b>") : "");
        if (!kills.isEmpty())
            b.append(card(++sec, "Kill feed 💀")).append("<div class=\"tbl\"><table><thead><tr><th>Target</th><th>Confirmed</th></tr></thead><tbody>\n").append(kills).append("</tbody></table></div></section>\n\n");

        // Economy: a line only when its number is above zero, so a new owner's page makes no false jokes ("there's always a gold farm").
        StringBuilder eco = new StringBuilder();
        Object[][] econ = {{raw.d("trades"), " villager trades.</b> The villagers know " + o.obj() + ". They're scared of " + o.obj() + "."},
                {(double) c.getOrDefault("gold_nugget", 0L), " gold nuggets crafted.</b> There's a gold farm. There's always a gold farm."},
                {(double) c.getOrDefault("golden_carrot", 0L), " golden carrots.</b> " + (fr ? "Eating good." : "Eating well. Very, very well.")},
                {(double) c.getOrDefault("iron_ingot", 0L), " iron ingots.</b> Industrial era."},
                {(double) c.getOrDefault("spruce_planks", 0L), " spruce planks.</b> Something BIG got built."},
                {raw.d("mined_ancient_debris"), " ancient debris.</b> " + (fr ? "Netherite arc loading…" : "Netherite upgrade coming soon…")}};
        for (Object[] e : econ) if ((double) e[0] > 0) eco.append("<li><b>").append(n((double) e[0])).append(e[1]).append("</li>\n");
        if (eco.length() > 0) b.append(card(++sec, fr ? "Economy (CEO mindset)" : "Economy (big business)")).append("<ul>\n").append(eco).append("</ul></section>\n\n");

        List<String> lore = new ArrayList<>();
        if (s.owner().lore() != null) for (String l : s.owner().lore()) if (l != null && !l.isBlank()) lore.add(l.trim());
        if (!lore.isEmpty()) {
            b.append(card(++sec, "Server legends")).append("<ul>");
            for (String l : lore) b.append("<li>").append(Py.escape(l)).append("</li>");
            b.append("</ul></section>\n\n");
        }

        b.append(card(++sec, "Travel log 🐴")).append("<div class=\"tbl\"><table><tbody>\n");
        b.append("<tr><th>Horse</th><td class=\"n\">").append(Py.fixed0(raw.d("horse_m") / 1000)).append(" km</td><td>")
                .append(raw.d("horse_m") >= 42195 ? "That horse has done " + Py.fixed0(raw.d("horse_m") / 42195) + " marathons. Give it a raise." : "").append("</td></tr>\n");
        b.append("<tr><th>Boat</th><td class=\"n\">").append(Py.fixed0(raw.d("boat_m") / 1000)).append(" km</td><td></td></tr>\n");
        b.append("<tr><th>Elytra</th><td class=\"n\">").append(Py.fixed0(raw.d("elytra_m") / 1000)).append(" km</td><td>")
                .append(raw.d("elytra_m") < 100 ? "No wings yet. The End is waiting." : cap(o.subj()) + " " + o.has() + " wings now. Nobody is safe.").append("</td></tr></tbody></table></div></section>\n\n");

        b.append(card(++sec, "Threat assessment")).append("<div class=\"tbl\"><table><tbody>\n");
        b.append("<tr><th>Cheating</th><td class=\"grass\"><b>None.</b> ").append(fr ? "Plays it legit." : "Plays fair.").append("</td></tr>\n");
        b.append("<tr><th>Griefing</th><td class=\"grass\"><b>None.</b> ").append(fr ? "Not an opp." : "A friend to all.").append("</td></tr>\n");
        boolean animalKills = !animal.isEmpty() && k.getOrDefault(animal, 0L) > 0;   // no "EXTREME (0 and counting)"
        if (animalKills)
            b.append("<tr><th>Danger to ").append(Py.escape(animals)).append("</th><td class=\"red\"><b>EXTREME</b> ").append(StylePrompt.chicken(s) ? "🐔 " : "").append("(").append(n(k.get(animal))).append(" and counting)</td></tr>\n");
        if (k.getOrDefault("silverfish", 0L) > 0)
            b.append("<tr><th>Danger to silverfish</th><td class=\"red\"><b>Extinction-level</b> (").append(n(k.get("silverfish"))).append(")</td></tr>");
        b.append("</tbody></table></div></section>\n\n");

        b.append(card(++sec, "Final verdict")).append("<ol>\n");
        b.append("<li>✅ <b>Cleared.</b> ").append(fr ? "Plays legit and the aura is real." : "Plays fair, and the legend is real.").append("</li>\n");
        if (animalKills) b.append("<li>").append(StylePrompt.chicken(s) ? "🐔 " : "").append("Someone check on the ").append(Py.escape(animals)).append(".</li>");
        b.append("</ol></section>\n");
        String from = serverLabel(s);
        if (!from.matches("(?i)(our|the|a|an|my) .*")) from = "the " + from;
        b.append("<p class=\"foot\">This file rebuilds itself every morning from ").append(Py.escape(from)).append(fr ? ". Go outside sometimes. GG.</p>\n" : ". Go outside sometimes. Good game.</p>\n");
        b.append("</main></body></html>");
        return b.toString();
    }

    private static String card(int no, String title) {
        return String.format(java.util.Locale.ROOT, "<section class=\"card\"><h2><span class=\"no\">%02d</span>%s</h2>", no, title);
    }

    private static String row(String a, String b) {
        return "<tr><td>" + a + "</td><td class=\"n\">" + b + "</td></tr>";
    }

    /** The row, or "" when the number is missing or zero. */
    private static String rowIf(String a, Long v) {
        return v == null || v <= 0 ? "" : row(a, n(v));
    }

    private static String n(Long v) {
        return Py.commaRound(v == null ? 0 : v);
    }

    private static String n(double v) {
        return Py.commaRound(v);
    }

    private static String cap(String w) { return w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1); }
}
