package io.github.eastmadc.masthead.edition;

import io.github.eastmadc.masthead.config.Settings;
import io.github.eastmadc.masthead.pipeline.Housekeeping;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The publish step (port of render()): sanitize the draft, whole-edition word gate, plain fallback, mailroom checks and
 * splice, then build the archive fragment, front page, player cards, owner file and in-game edition.
 * Published files are written together by EditionJob; only previews write their own archive here.
 */
public final class Render {
    private Render() {}

    /**
     * Files the render step reads.
     *
     * @param editionsDir   editions/ (the published archive: the newest 30 are read back for the front page)
     * @param statsDir      today's stats snapshot (stats/DATE: uuid.json + usercache.json), for player cards and the owner file
     * @param printedLog    mailbag-printed.jsonl (mail printed on earlier days), may not exist
     * @param mailroomSince the day the mailroom opened (for the "now takes letters" plug); null = no plug (mailroom off)
     */
    static final String SURVEY_LINK = "<p><a href=\"" + Pages.SURVEY_FILE + "\">The Town Survey: every build on the register</a></p>";

    public record Inputs(Path editionsDir, Path statsDir, Path printedLog, LocalDate mailroomSince) {}

    /**
     * What one render produced. Write: index.html / players.html / owner.html (null = don't publish / remove), survey.html
     * (the opening day's page, for a preview; a published paper draws it from the register, EditionJob.surveyPage),
     * today.json = PyJson.dumps(game, false), mailbag.json = mail.toJson(). usedAi false = the plain edition ran.
     */
    public record Rendered(String fragment, String label, String indexHtml, String playersHtml, String ownerHtml, JsonObject game,
                           MailDesk.Result mail, boolean usedAi, String gate, String summary, List<String> warnings, String surveyHtml) {
        public String archiveHtml() { return "<span class=\"eyebrow\">" + label + "</span>" + fragment; }
    }

    /**
     * @param data     the full facts for the date (data/DATE.json, with mailbag_candidates)
     * @param draft    the writer's raw output (null/empty = writer unavailable: plain edition)
     * @param deskRaw  the editor desk's raw output (null = no desk run)
     * @param updated  the "Updated ..." date printed on the pages (today)
     */
    public static Rendered render(Settings s, JsonObject data, LocalDate date, String draft, String deskRaw, Inputs in, LocalDate updated)
            throws IOException {
        return render(s, data, date, draft, deskRaw, in, new Outputs(true, in.editionsDir().toAbsolutePath().getParent()), updated);
    }

    /** As above; a preview writes DATE.html only in its own folder. A published render changes no archive file. */
    public static Rendered render(Settings s, JsonObject data, LocalDate date, String draft, String deskRaw, Inputs in, Outputs out, LocalDate updated)
            throws IOException {
        List<String> warnings = new ArrayList<>();
        String raw = MailDesk.unfence(Py.strip(draft == null ? "" : draft));
        String frag = HtmlClean.clean(Py.head(raw, 60000));
        boolean usedAi = Py.len(Py.strip(Py.stripTags(frag, ""))) > 80;
        if (usedAi && !frag.contains("<h2") && !frag.contains("<h3")) {   // every voice is told to write an h2 and h3 sections: prose alone is a refusal or chat
            usedAi = false;
            warnings.add("the AI's answer was not a paper; using the plain edition");
        }
        List<String> names = new ArrayList<>();
        for (String n : PyJson.objOf(data, "season_totals").keySet()) if (Py.isName(n)) names.add(n);
        String lang = StylePrompt.language(s);
        String gate = "";
        if (usedAi) {   // last line of defence: a listed word anywhere in the AI edition -> plain edition
            List<String> bw = WordGate.bad(HtmlClean.checkText(frag), names, false, lang);
            if (!bw.isEmpty()) {
                gate = bw.get(0);
                usedAi = false;
                warnings.add("the word filter stopped the AI edition (" + String.join(", ", bw) + "); using the plain edition");
            }
        }
        if (!usedAi) {
            frag = Fallback.edition(s, data);
            List<String> bw = WordGate.bad(HtmlClean.checkText(frag), names, false, lang);
            if (!bw.isEmpty()) warnings.add("the plain edition has a listed word (" + String.join(", ", bw) + "); check the paper's settings");
        }
        MailDesk.Result mres = MailDesk.validate(data, deskRaw, usedAi, s, date);
        mres.gate = gate;
        if (usedAi)
            frag = Splice.apply(s, frag, mres.letters, mres.tips, mres.letters.isEmpty() && mres.tips.isEmpty() ? Splice.mailPlug(s, in.mailroomSince(), date) : "");
        String label = ("weekly".equals(PyJson.strOr(data.get("edition"), "")) ? "Weekly edition" : "Daily edition") + " · " + Py.dayLabel(date, s.paper().british());
        String current = date + ".html";
        // the opening day: the Town Survey page, linked by the renderer at the end of the construction section (web pages only)
        JsonObject cons = PyJson.objOf(data, "construction");
        String survey = null, web = frag;
        if (PyJson.truthy(cons.get("opening")) && !PyJson.arr(cons.get("survey")).isEmpty()) {
            survey = Pages.survey(s, cons, date);
            int[] sec = ConstructionGate.section(frag, s.construction().title());
            if (sec != null) web = frag.substring(0, sec[1]) + SURVEY_LINK + frag.substring(sec[1]);
        }
        String edition = "<span class=\"eyebrow\">" + label + "</span>" + web;
        if (!out.publish()) {   // a preview is unpublished text that names players: private from the start
            Housekeeping.createPrivateDirs(out.editionsDir());
            Housekeeping.writePrivate(out.editionsDir().resolve(current), edition);
        }

        // the archive: the published editions, with this run's own file in place of the same day's
        Map<String, Path> byName = new java.util.TreeMap<>(Collections.reverseOrder());
        for (Path dir : new LinkedHashSet<>(List.of(in.editionsDir().toAbsolutePath().normalize(), out.editionsDir().toAbsolutePath().normalize()))) {
            if (!Files.isDirectory(dir)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.html")) {
                for (Path p : ds) byName.put(p.getFileName().toString(), p);
            }
        }
        byName.put(current, out.editionsDir().resolve(current));   // overlay this run in memory, including same-day reissues
        List<Path> eds = new ArrayList<>(byName.values());
        String latest = eds.get(0).getFileName().toString().equals(current) ? edition : backIssue(Files.readString(eds.get(0), StandardCharsets.UTF_8), s);
        List<Map.Entry<String, String>> archive = new ArrayList<>();
        for (Path p : eds.subList(1, Math.min(30, eds.size()))) {
            String fn = p.getFileName().toString();
            archive.add(new AbstractMap.SimpleEntry<>(fn.substring(0, fn.length() - 5), fn.equals(current) ? edition : backIssue(MailDesk.readText(p), s)));
        }
        String index = Pages.index(s, data, latest, archive, updated, usedAi);

        Map<String, StatsReader.Row> stats = StatsReader.load(in.statsDir(), s.privacy().hidePlayers());
        String owner = null, players = null;
        try {
            owner = Pages.owner(s, stats, updated);
        } catch (RuntimeException e) {
            warnings.add("owner file failed: " + e);
        }
        try {
            players = Pages.players(s, stats, updated);
        } catch (RuntimeException e) {
            warnings.add("players page failed: " + e);
        }
        JsonObject game = null;
        try {
            JsonArray printed = MailDesk.printedHistory(in.printedLog(), date, mres.printed);
            game = GameEdition.build(s, date, data, frag, label, usedAi, printed);
        } catch (RuntimeException e) {
            warnings.add("game edition failed: " + e);
        }
        String txt = Py.strip(Py.collapse(Py.unescape(Py.stripTags(frag, " "))));   // plain text for the console and the owner's push: & not &amp;
        String summary = (usedAi ? "AI" : "FALLBACK") + " | " + Py.head(txt, 300);
        return new Rendered(web, label, index, players, owner, game, mres, usedAi, gate, summary, warnings, survey);
    }

    /**
     * An older paper as the front page may show it today: no build stories (ConstructionGate.withoutBuilds), and a player
     * hidden since it ran reads "a player". Only text is changed, never the inside of a tag. The file in editions/ stays as it is.
     */
    static String backIssue(String html, Settings s) {
        if (html == null) return null;
        html = ConstructionGate.withoutBuilds(html, s.construction().title());
        List<String> hide = new ArrayList<>();
        for (String h : s.privacy().hidePlayers()) hide.add(Pattern.quote(h));
        if (hide.isEmpty()) return html;
        // shortcut: whole names only, so a look-alike spelling ("Bob22" for Bob_22) stays, as in FactsBuilder.recent
        // tags and entities (&amp;) are kept as they are: "span" and "amp" are valid player names
        return Pattern.compile("<[^>]*>|&#?[A-Za-z0-9]+;|(?<![A-Za-z0-9_])(?:" + String.join("|", hide) + ")(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE)
                .matcher(html).replaceAll(m -> m.group().startsWith("<") || m.group().startsWith("&") ? Matcher.quoteReplacement(m.group()) : "a player");
    }
}
