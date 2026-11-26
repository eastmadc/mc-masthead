package io.github.eastmadc.masthead.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.eastmadc.masthead.collect.BlockItems;
import io.github.eastmadc.masthead.collect.StatsSnapshot;
import io.github.eastmadc.masthead.config.Settings;
import io.github.eastmadc.masthead.config.SettingsLoader;
import io.github.eastmadc.masthead.construction.Construction;
import io.github.eastmadc.masthead.edition.ConstructionGate;
import io.github.eastmadc.masthead.edition.FactCheck;
import io.github.eastmadc.masthead.edition.MailDesk;
import io.github.eastmadc.masthead.edition.Outputs;
import io.github.eastmadc.masthead.edition.Pages;
import io.github.eastmadc.masthead.edition.PyJson;
import io.github.eastmadc.masthead.edition.Render;
import io.github.eastmadc.masthead.edition.Salvage;
import io.github.eastmadc.masthead.edition.StylePrompt;
import io.github.eastmadc.masthead.facts.FactsBuilder;
import io.github.eastmadc.masthead.game.InGame;
import io.github.eastmadc.masthead.notify.Concerns;
import io.github.eastmadc.masthead.notify.Notifier;
import io.github.eastmadc.masthead.notify.Push;
import io.github.eastmadc.masthead.qr.QrBitmap;
import io.github.eastmadc.masthead.web.WebServer;
import io.github.eastmadc.masthead.writer.AnthropicWriter;
import io.github.eastmadc.masthead.writer.CommandWriter;
import io.github.eastmadc.masthead.writer.ExchangeWriter;
import io.github.eastmadc.masthead.writer.NoWriter;
import io.github.eastmadc.masthead.writer.OpenAiCompatibleWriter;
import io.github.eastmadc.masthead.writer.RefusalException;
import io.github.eastmadc.masthead.writer.Spend;
import io.github.eastmadc.masthead.writer.Writer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The daily edition, in the same order as the reference shell pipeline it replaces:
 * snapshot stats (once a day) -> facts -> writer -> fact check (one rewrite) -> editor desk -> render -> publish -> bookkeeping -> alerts.
 * Runs off the main thread except {@link #prepareOnMainThread} and {@link #start}. Every writer failure falls back to the plain
 * edition. A preview ({@code publish=false}) writes only under preview/ (see {@link Outputs}).
 */
public final class EditionJob {
    private final JavaPlugin plugin;            // null in tests
    private final Supplier<Settings> settings;
    private final InGame game;                  // null in tests
    private final Path data;
    private final Logger log;
    private final Runnable afterPublish;
    private final Function<Settings, Writer> writers;
    private final Function<Settings.Alerts, Push> pushes;
    private final Concerns concerns;
    private volatile Path statsFolder;
    private volatile Path serverRoot;
    private volatile String paperBuild = "";
    private volatile java.util.Set<String> blockItems = java.util.Set.of();
    private volatile java.util.function.Consumer<LocalDate> onPublished = d -> { };
    private WebServer web;
    private volatile Construction construction;   // null = no construction news (and in tests)

    public EditionJob(JavaPlugin plugin, Supplier<Settings> settings, InGame game, Concerns concerns) {
        this.plugin = plugin;
        this.settings = settings;
        this.game = game;
        this.data = plugin.getDataFolder().toPath();
        this.log = plugin.getLogger();
        this.afterPublish = () -> Bukkit.getScheduler().runTask(plugin, game::forceReload);
        this.writers = null;
        this.pushes = a -> new Notifier(a, plugin.getLogger());
        this.concerns = concerns;
    }

    /** For tests: no server. statsFolder = the "live" stats folder; serverRoot holds usercache.json. */
    EditionJob(Path data, Supplier<Settings> settings, Logger log, Function<Settings, Writer> writers, Function<Settings.Alerts, Push> pushes,
               Concerns concerns, Path statsFolder, Path serverRoot) {
        this.plugin = null;
        this.game = null;
        this.data = data;
        this.settings = settings;
        this.log = log;
        this.afterPublish = () -> { };
        this.writers = writers;
        this.pushes = pushes;
        this.concerns = concerns;
        this.statsFolder = statsFolder;
        this.serverRoot = serverRoot;
    }

    // ------------------------------------------------------------ web server, public URL, QR (main thread; again on reload)
    public void start() {
        Settings s = settings.get();
        stopWeb();
        if (s.writer().mode().equals("exchange")) {
            try { ExchangeWriter.makeFolders(data.resolve("ai")); }
            catch (IOException e) { plugin.getLogger().warning("Could not make the folders ai/requests and ai/responses (" + e.getClass().getSimpleName() + ")"); }
        }
        String url = s.web().publicUrl();
        Path accessLog = WebServer.accessLog(data, s.features().readership(), plugin.getLogger());   // readership off: no log, old ones deleted
        if (s.web().mode().equals("builtin")) {
            try {
                Files.createDirectories(data.resolve("web"));
                web = new WebServer(data.resolve("web"), s.web().bind(), s.web().port(), accessLog, zone(s), plugin.getLogger());
                web.start();
                if (url.isEmpty()) plugin.getLogger().warning("web.public-url is empty: players get no [read it] link or QR code. Set it to http://<your server's address>:" + s.web().port() + "/");
            } catch (Exception e) {
                plugin.getLogger().severe(e instanceof java.net.BindException ? SettingsLoader.webBindLine(s.web().bind(), s.web().port(), e.getMessage())
                        : "Could not start the web server on port " + s.web().port() + ": " + e);
                web = null;
            }
        }
        if (s.web().mode().equals("off")) url = "";
        game.publicUrl = url;
        if (!url.isEmpty()) {
            try { game.refreshQr(QrBitmap.render(url)); }
            catch (Exception e) { plugin.getLogger().warning("QR code: " + e.getMessage()); }
        }
    }

    public void stop() { stopWeb(); }

    /** Construction news: the fold runs inside each edition. */
    public void construction(Construction c) { construction = c; }

    /**
     * Called the moment a day's paper is published (before the alerts and clean-up), so the day is marked done even if
     * the server stops right after. Review round 2: a restart in that gap printed a second paper without the letters.
     */
    public void onPublished(java.util.function.Consumer<LocalDate> c) { onPublished = c == null ? d -> { } : c; }

    private void stopWeb() {
        if (web != null) { try { web.stop(); } catch (Exception ignored) { } web = null; }
    }

    private static ZoneId zone(Settings s) {
        return s.schedule().zone().isEmpty() ? ZoneId.systemDefault() : ZoneId.of(s.schedule().zone());
    }

    /** Main thread: write online players' stats to disk and remember where the stats folder is. */
    public void prepareOnMainThread(LocalDate date) {
        StatsSnapshot.saveOnlinePlayers();
        if (construction != null) construction.flushNow();   // this minute's building goes in before the fold
        statsFolder = StatsSnapshot.statsFolder();
        serverRoot = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        paperBuild = Bukkit.getVersion();
        if (blockItems.isEmpty()) {
            blockItems = BlockItems.fromServer();
            if (blockItems.isEmpty() && settings.get().features().buildingReport()) log.warning("Building Report: could not get the list of blocks from the server; no report today");
        }
    }

    // ------------------------------------------------------------ the run (async)
    public String run(LocalDate date, boolean publish) throws Exception {
        return run(date, publish, null);
    }

    /**
     * One edition run. plainBecause != null: the plain edition only (no writer, no desk), published after the earlier tries
     * failed; the reason goes in the owner's notes.
     */
    /** The clock for the editor desk's time budget (tests use a fake one). */
    static java.util.function.LongSupplier nanoTime = System::nanoTime;

    public String run(LocalDate date, boolean publish, String plainBecause) throws Exception {
        long t0 = nanoTime.getAsLong();
        Settings s = settings.get();
        // a preview shows construction news as it would print (the opening paper too, while still practising); its fold keeps nothing
        if (!publish) s = s.constructionPrint(true);
        Outputs out = publish ? Outputs.publish(data) : Outputs.preview(data);
        if (publish && concerns != null) concerns.retry(s);   // worried-letter alerts still waiting from earlier
        Writer w = null;
        try {
            if (!publish) Housekeeping.createPrivateDirs(out.root());   // preview/ names players: private before anything is written into it
            // 1. stats snapshot: only the first published run of the day takes it, so a re-run never moves tomorrow's
            //    comparison point. A preview reads a throwaway copy under preview/ instead.
            Path snap = data.resolve("stats").resolve(date.toString()), todayStats = snap;
            if (!Files.isDirectory(snap)) {
                if (publish) StatsSnapshot.copy(statsFolder, serverRoot, data, date);
                else {
                    todayStats = out.root().resolve("stats").resolve(date.toString());
                    StatsSnapshot.deleteTree(out.root().resolve("stats"));
                    StatsSnapshot.copyInto(statsFolder, serverRoot, todayStats);
                }
            }
            if (!publish) {
                StatsSnapshot.deleteTree(out.editionsDir());   // the preview's archive starts from the published one
                StatsSnapshot.deleteTree(out.deskDir());       // an older version kept the desk's answer here
            }
            // 1a. privacy.hide-players follows a name change: an older snapshot shows the UUID behind a hidden name, today's
            //     the name it has now. Every snapshot still kept is read, oldest first, so a lost state.yml or a change
            //     from before 1.0 is followed too. From here on s hides both.
            String ds = date.toString();
            List<Map<String, String>> shown = new ArrayList<>();
            try (Stream<Path> days = Files.list(data.resolve("stats"))) {
                for (String n : days.map(p -> p.getFileName().toString()).filter(n -> n.matches("\\d{4}-\\d\\d-\\d\\d") && n.compareTo(ds) < 0).sorted().toList())
                    shown.add(io.github.eastmadc.masthead.edition.StatsReader.names(data.resolve("stats").resolve(n)));
            } catch (IOException e) { /* no snapshots yet */ }
            shown.add(io.github.eastmadc.masthead.edition.StatsReader.names(todayStats));
            s = io.github.eastmadc.masthead.Masthead.follow(s, data, shown, publish, log);
            List<String> notes = new ArrayList<>();
            if (plainBecause != null) notes.add(plainBecause);
            // 1b. construction news: the day's building folded into builds (no positions leave it). The writer comes first,
            //     because the kinds desk asks it to name the builds before the fold.
            w = plainBecause != null ? new NoWriter() : writer(s);
            if (!(w instanceof NoWriter)) log.info("Writing today's edition (writer " + s.writer().mode() + "); this can take a few minutes.");
            Construction cn = construction;
            Settings fs = s;
            Writer kw = kindsWriter(s, w, publish, construction != null, () -> spend(fs).low(), notes);
            long k0 = kw == null ? 0 : nanoTime.getAsLong();
            Path cons = cn == null ? null : cn.analyze(s, date, publish, notes, kw);
            long kindsWait = kw == null ? 0 : Math.max(0, nanoTime.getAsLong() - k0);   // the kinds call and its folds: never held against the editor desk
            // 2. facts
            FactsBuilder fb = new FactsBuilder(data, s).paperBuild(paperBuild).blockItems(blockItems).todayStats(todayStats).writeTo(out.factsDir()).construction(cons);
            JsonObject facts = fb.build(date, s.schedule().time());
            s = s.ownerNamed(fb.owner());
            if (!fb.constructionDropped().isEmpty()) log.warning("construction: left out of the facts: " + String.join(", ", fb.constructionDropped()));
            String review = io.github.eastmadc.masthead.edition.Lexicon.reviewNote(date);   // once a week, on Mondays
            if (!review.isEmpty() && date.getDayOfWeek() == java.time.DayOfWeek.MONDAY) log.info(review);
            // 3. writer (+ up to two rewrites if the fact check finds problems)
            String draft = null;
            long batchWait = 0;   // the first draft's wait (a batch can take 15 minutes); never held against the editor desk
            if (!(w instanceof NoWriter)) {
                String full = StylePrompt.prompt(s, facts, notes), system = StylePrompt.build(s), user = full.substring(Math.min(system.length(), full.length()));
                Duration timeout = Duration.ofSeconds(s.writer().timeoutSeconds());
                try {
                    long p0 = nanoTime.getAsLong();
                    try { draft = w.writePaper(system, user, timeout); }
                    finally { batchWait = Math.max(0, nanoTime.getAsLong() - p0); }
                    List<String> problems = FactCheck.problems(facts, draft, date, s);
                    if (!problems.isEmpty()) {
                        notes.add("draft needed fixing (" + FactCheck.line(problems) + ")");
                        String fix = rewriteNote(s, FactCheck.line(problems));
                        // Up to two rewrites: a rewrite that fixes one problem can trip another check, so the second one hears about both.
                        String second = null; List<String> again = problems; String seen = FactCheck.line(problems);
                        // A rewrite that fails (timeout, refusal, cut off) still leaves the first draft for salvage.
                        try {
                            for (int t = 0; t < 2 && !again.isEmpty(); t++) {
                                String note = t == 0 ? fix : fix.replace(FactCheck.line(problems), seen);
                                second = w.write(system, user + note, timeout);
                                again = FactCheck.problems(facts, second, date, s);
                                if (!again.isEmpty()) seen = seen + "; " + FactCheck.line(again);
                            }
                        } catch (Throwable e) {
                            notes.add("rewrite failed (" + e.getClass().getSimpleName() + ": " + cap(String.valueOf(e.getMessage()), 160) + ")");
                            second = null; again = problems;
                        }
                        String kept = again.isEmpty() || second == null ? null : ConstructionGate.recover(again, second, s.construction().title());   // only the section is off: drop it
                        if (again.isEmpty()) draft = second;
                        else if (kept != null) { draft = kept; notes.add("construction section dropped (" + ConstructionGate.words(again) + ")"); }
                        else {   // last try: cut the sentences that fail on their own, if the rest then passes the whole check
                            String saved = second == null ? null : Salvage.drop(facts, second, date, s);
                            if (saved == null) saved = Salvage.drop(facts, draft, date, s);
                            if (saved != null) { draft = saved; notes.add("rewrites still off (" + FactCheck.line(again) + "); dropped the lines with problems"); }
                            else { notes.add("rewrites still off (" + FactCheck.line(again) + "); plain edition"); draft = null; }
                        }
                    }
                    if (draft != null) {   // style is guidance, never a gate: noted for the owner, the draft stands
                        List<String> style = FactCheck.style(facts, draft, s);
                        if (!style.isEmpty()) notes.add("style: " + FactCheck.line(style));
                    }
                } catch (RefusalException e) {
                    notes.add("writer declined (" + e.category() + "); plain edition"); draft = null;
                } catch (Throwable e) {   // also LinkageError: a broken AI library means the plain edition, not no paper
                    notes.add("writer unavailable (" + e.getClass().getSimpleName() + ": " + cap(String.valueOf(e.getMessage()), 160) + "); plain edition");
                    draft = null;
                }
            }
            // 4. editor desk: runs even on plain-edition days; cached so re-runs agree. A preview reuses the published
            //    decisions if there are any, and otherwise asks the desk but keeps nothing (its answer quotes letters).
            String deskRaw = null;
            String deskPrompt = s.features().mailroom() ? MailDesk.prompt(s, facts) : null;
            Path deskCache = data.resolve("desk").resolve(date + ".json");
            if (deskPrompt != null) {
                if (Files.isRegularFile(deskCache)) deskRaw = Files.readString(deskCache);
                else if (plainBecause != null) notes.add("mailroom: plain fallback, letters wait");
                else if (w instanceof NoWriter) notes.add("mailroom: no AI writer, letters wait");
                else if (Duration.ofNanos(nanoTime.getAsLong() - t0 - batchWait - kindsWait).toSeconds() > 600) notes.add("mailroom: no time left for the editor desk; letters wait");
                else {
                    String rules = MailDesk.rules(s);
                    try {
                        deskRaw = w.write(rules, deskPrompt.substring(Math.min(rules.length(), deskPrompt.length())), Duration.ofSeconds(s.writer().deskTimeoutSeconds()));
                        if (publish && deskRaw != null && !deskRaw.isBlank()) {
                            Files.createDirectories(out.deskDir());
                            atomicWrite(out.deskDir().resolve(date + ".json"), deskRaw);
                        }
                    } catch (Throwable e) { notes.add("editor desk unavailable (" + e.getClass().getSimpleName() + ")"); deskRaw = null; }
                }
            }
            // Worried letters (marked, or flagged by the desk) go to the owner now, before anything below can fail.
            // Concerns sends each one once, so bookkeeping reporting them again does nothing.
            if (publish && s.features().mailroom() && concerns != null) {
                try {
                    for (Map<String, String> c : MailDesk.validate(facts, deskRaw, true, s, date).concern)
                        concerns.report(s, new Concerns.Letter(c.get("id"), c.get("name"), c.get("note"), c.get("text")));
                } catch (RuntimeException e) { log.warning("worried letters: " + e.getClass().getSimpleName()); }
            }
            // 5. render
            LocalDate since = s.features().mailroom() ? mailroomSince(date, publish) : null;
            Render.Rendered r = Render.render(s, facts, date, draft, deskRaw,
                    new Render.Inputs(data.resolve("editions"), todayStats, data.resolve("mailbag-printed.jsonl"), since), out, date);
            r.warnings().forEach(x -> log.warning("edition: " + x));
            if (!publish) {
                Files.createDirectories(out.root());
                atomicWrite(out.root().resolve("index.html"), r.indexHtml());
                // the fonts and the pages the front page links, so the preview looks and clicks like the real page
                Pages.writeFonts(out.root());
                if (r.playersHtml() != null) atomicWrite(out.root().resolve(Pages.PLAYERS_FILE), r.playersHtml());
                if (r.ownerHtml() != null) atomicWrite(out.root().resolve(Pages.OWNER_FILE), r.ownerHtml());
                else Files.deleteIfExists(out.root().resolve(Pages.OWNER_FILE));
                if (r.surveyHtml() != null) atomicWrite(out.root().resolve(Pages.SURVEY_FILE), r.surveyHtml());
                return (r.usedAi() ? "AI" : "PLAIN") + " preview written to plugins/Masthead/preview/index.html" + (notes.isEmpty() ? "" : " | " + String.join("; ", notes));
            }
            // 6. publish
            publishEdition(s, date, r, surveyPage(s, date, facts, r.surveyHtml()));
            // The paper is out: mark the day done now. Nothing below may count the day as failed (that would publish it
            // again): a failure is logged and noted for the owner.
            try { onPublished.accept(date); }
            catch (RuntimeException e) { log.warning("could not mark the day done: " + Attempts.reason(e)); }
            try { afterPublish.run(); }
            catch (RuntimeException e) { log.warning("in-game refresh failed: " + Attempts.reason(e)); }
            try { bookkeeping(s, date, facts, r); }
            catch (IOException | RuntimeException e) {
                log.warning("mailbag bookkeeping failed: " + Attempts.reason(e));
                notes.add("mailbag bookkeeping failed (" + Attempts.reason(e) + ")");
            }
            // 7. summary for the owner (alerts + a file a host script can push from)
            try {
                JsonObject sum = summary(s, date, facts, r, notes);
                atomicWrite(data.resolve("last-edition.json"), PyJson.dumps(sum, false));
                alert(s, sum);
            } catch (IOException | RuntimeException e) { log.warning("owner summary failed: " + Attempts.reason(e)); }
            return (r.usedAi() ? "AI" : "PLAIN") + " | " + r.summary() + (notes.isEmpty() ? "" : " | " + String.join("; ", notes));
        } finally {
            if (w != null) try { w.close(); } catch (Exception ignored) { }
            if (!publish) try { StatsSnapshot.deleteTree(out.root().resolve("stats")); } catch (IOException ignored) { }   // the throwaway stats copy
            ExchangeWriter.clearStale(data.resolve("ai"));
            if (publish) tidy(s, date);
        }
    }

    /** The note sent with a rewrite: the hard-gate problems and how to read them. Style problems never trigger one. */
    static String rewriteNote(Settings s, String problemLine) {
        return "\n\nYOUR PREVIOUS DRAFT HAD THESE PROBLEMS: " + problemLine
                + "\n(Bare numbers are not in the data. Quoted words are banned phrases, times of day or weekday names. \"time of day\" means a clock time:"
                + " remove it. \"hidden player\" means you named a player who is never named: name only players in the data. \"never-mention\" problems quote what to remove. \"coordinates\" problems that show a word in brackets"
                + " quote the word to remove. A \"coordinates\" problem with no quoted word means a number written in a shape that could be a position"
                + " (a number right after x, y or z, three numbers joined by slashes, the word at followed by two numbers, numbers in parentheses or with"
                + " minus signs): rewrite every number next to its word. \"claim\" problems quote a word that says a play happened when the data doesn't"
                + " show it: remove it. \"floor\" and \"family\" problems quote a word the paper never prints: remove it."
                + " \"gender\" problems quote a he/she word next to a player's name, or a word like man or guy: use the username or they/them. \"removed\" problems quote"
                + " a word this paper may not use: remove it.)"
                + (ConstructionGate.on(s) ? "\n" + ConstructionGate.REWRITE_NOTE : "")
                + "\nRewrite the whole edition: use only numbers that appear in the data, and none of those phrases. Fix only the lines with problems: keep every"
                + " section, every horoscope, the jokes and the full length.\n";
    }

    /**
     * Every try failed, the plain one too: no paper today. Writes last-edition.json (failed: true) so the host helper tells
     * the owner, and pushes it on the plugin's own alert channels. Does not mark the day done.
     */
    public void failed(LocalDate date, String reason) {
        Settings s = settings.get();
        JsonObject o = new JsonObject();
        o.addProperty("date", date.toString());
        o.addProperty("ai", false);
        o.addProperty("failed", true);
        o.addProperty("summary", "No paper today: every try failed (" + cap(reason, 200) + ")");
        o.addProperty("url", s.web().publicUrl());
        JsonArray n = new JsonArray();
        n.add("today's paper failed: " + cap(reason, 200) + ". Players still see the last paper. Try /masthead now, or check the server console.");
        o.add("notes", n);
        o.add("concern", new JsonArray());
        try { atomicWrite(data.resolve("last-edition.json"), PyJson.dumps(o, false)); }
        catch (IOException e) { log.warning("last-edition.json: " + e.getClass().getSimpleName()); }
        Push p = pushes.apply(s.alerts());
        if (p != null && (p.hasNtfy() || p.hasDiscord()))
            p.send(s.paper().name() + ": today's paper failed", o.get("summary").getAsString() + "\n" + n.get(0).getAsString(), "high", List.of("warning"));
    }

    private Writer writer(Settings s) {
        if (writers != null) return writers.apply(s);
        try {
            return switch (s.writer().mode()) {
                case "anthropic" -> SettingsLoader.anthropicRefused() ? new NoWriter() : new AnthropicWriter(s.writer(), log, spend(s));   // T16
                case "openai-compatible" -> new OpenAiCompatibleWriter(s.writer().openai(), log, spend(s));
                case "command" -> new CommandWriter(s.writer().command(), data.resolve("writer-stderr.log"));
                case "exchange" -> new ExchangeWriter(data.resolve("ai"));
                default -> new NoWriter();
            };
        } catch (Throwable e) {   // e.g. the Anthropic SDK download failed at startup (LinkageError)
            log.severe("Writer '" + s.writer().mode() + "' could not start (" + e + "); plain edition today");
            return new NoWriter();
        }
    }

    /**
     * The writer for the kinds desk, or null: a published paper with construction news on (printing or not) and an AI
     * writer; a paid writer only while no request is unsettled and 10% or more of the month's budget is left.
     */
    static Writer kindsWriter(Settings s, Writer w, boolean publish, boolean construction, java.util.function.BooleanSupplier budgetLow, List<String> notes) {
        if (!publish || w instanceof NoWriter || !construction || !s.features().construction()) return null;
        if (Set.of("anthropic", "openai-compatible").contains(s.writer().mode()) && budgetLow.getAsBoolean()) {
            notes.add("build kinds: AI budget low; rules used");
            return null;
        }
        return w;
    }

    /** writer.monthly-budget-usd: the ledger for the paid writers. Reaching it is logged and pushed to the owner once a month. */
    private Spend spend(Settings s) {
        return new Spend(data.resolve("data").resolve("spend.json"), s.writer().monthlyBudgetUsd(), msg -> {
            log.warning(msg);
            Push p = pushes.apply(s.alerts());
            if (p != null && (p.hasNtfy() || p.hasDiscord())) p.send(s.paper().name() + ": AI budget reached", msg, "high", List.of("moneybag"));
        });
    }

    // ------------------------------------------------------------ publish + bookkeeping
    private Path webDir(Settings s) {
        return switch (s.web().mode()) {
            case "builtin" -> data.resolve("web");
            case "folder" -> s.web().folder().isEmpty() ? null : resolve(s.web().folder());
            default -> null;
        };
    }

    /**
     * survey.html for a published paper: drawn again every day from the opening's register (construction/players.json), so a
     * builder who went private, was hidden or forgotten leaves it, whether construction news is on or not. rendered = the
     * renderer's page (null: none); used only when there is no register. Null: no page to write.
     */
    private String surveyPage(Settings s, LocalDate date, JsonObject facts, String rendered) {
        JsonObject reg = Construction.register(data, s.construction(), s.privacy().hidePlayers(), date);
        if (reg == null) return rendered;
        JsonObject cons = io.github.eastmadc.masthead.facts.ConstructionFacts.filter(reg, PyJson.objOf(facts, "season_totals").keySet(),
                Set.copyOf(s.privacy().hidePlayers()), false, 0, new ArrayList<>()).construction();
        // an empty register makes no new page, but an existing one is still overwritten so no old names stay up
        Path dir = webDir(s);
        if (PyJson.arr(cons.get("survey")).isEmpty() && (dir == null || !Files.exists(dir.resolve(Pages.SURVEY_FILE)))) return null;
        return Pages.survey(s, cons, LocalDate.parse(reg.get("opened").getAsString()));
    }

    private void publishEdition(Settings s, LocalDate date, Render.Rendered r, String surveyHtml) throws IOException {
        if (r.game() == null) throw new IOException("No in-game edition was rendered");
        Path dir = webDir(s);
        if (dir != null) {
            Files.createDirectories(dir);
            Pages.writeFonts(dir);   // static resources first; no edition file is exposed yet
        }
        List<Publication> files = new ArrayList<>();
        try {
            files.add(preparePublication(data.resolve("editions").resolve(date + ".html"), r.archiveHtml(), false));
            if (dir != null) {
                if (r.playersHtml() != null) files.add(preparePublication(dir.resolve("players.html"), r.playersHtml(), false));
                files.add(preparePublication(dir.resolve("owner.html"), r.ownerHtml(), false));   // null removes an old owner page
                if (surveyHtml != null) files.add(preparePublication(dir.resolve(Pages.SURVEY_FILE), surveyHtml, false));   // drawn again each day
            }
            files.add(preparePublication(data.resolve("today.json"), PyJson.dumps(r.game(), false), true));
            if (dir != null) files.add(preparePublication(dir.resolve("index.html"), r.indexHtml(), false));   // front page last
        } catch (IOException | RuntimeException e) {
            discardPublication(files, false);
            throw e;
        }
        commitPublication(files);
    }

    /** Each replacement and rollback copy is prepared on its destination filesystem before the first rename. */
    record Publication(Path target, Path next, Path previous) {}

    Publication preparePublication(Path target, String text, boolean privateFile) throws IOException {
        Files.createDirectories(target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Publication target is not a regular file: " + target);
        Path next = null, previous = null;
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                previous = Files.createTempFile(target.getParent(), ".masthead-", ".old");
                Files.copy(target, previous, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            if (text != null) {
                next = Files.createTempFile(target.getParent(), ".masthead-", ".new");
                try { Files.setPosixFilePermissions(next, privateFile ? Housekeeping.FILE : PosixFilePermissions.fromString("rw-r--r--")); }
                catch (UnsupportedOperationException ignored) { }
                Files.writeString(next, text, StandardCharsets.UTF_8);
            }
            return new Publication(target, next, previous);
        } catch (IOException | RuntimeException e) {
            discardPublication(List.of(new Publication(target, next, previous)), false);
            throw e;
        }
    }

    // ponytail: per-file rollback handles ordinary I/O failures, not a process crash or concurrent readers.
    // A durable recovery journal / generation switch is needed before claiming crash-atomic publication.
    void commitPublication(List<Publication> files) throws IOException {
        int committed = 0;
        boolean keepBackups = false;
        try {
            for (Publication f : files) {
                if (f.next() == null) Files.deleteIfExists(f.target());
                else Files.move(f.next(), f.target(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                committed++;
            }
        } catch (IOException | RuntimeException e) {
            for (int i = committed - 1; i >= 0; i--) {
                Publication f = files.get(i);
                try {
                    if (f.previous() == null) Files.deleteIfExists(f.target());
                    else Files.move(f.previous(), f.target(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException rollback) {
                    keepBackups = true;
                    e.addSuppressed(rollback);
                    log.severe("Publication rollback failed for " + f.target() + "; saved copy: " + f.previous());
                }
            }
            throw e;
        } finally {
            discardPublication(files, keepBackups);
        }
    }

    private void discardPublication(List<Publication> files, boolean keepBackups) {
        for (Publication f : files) {
            for (Path tmp : new Path[] {f.next(), keepBackups ? null : f.previous()}) {
                if (tmp == null) continue;
                try { Files.deleteIfExists(tmp); }
                catch (IOException e) { log.warning("Could not remove publication temporary file " + tmp + ": " + e.getClass().getSimpleName()); }
            }
        }
    }

    /** Relative paths in the config are relative to the plugin's folder. */
    private Path resolve(String p) {
        Path x = Path.of(p);
        return (x.isAbsolute() ? x : data.resolve(x)).normalize();
    }

    private void bookkeeping(Settings s, LocalDate date, JsonObject facts, Render.Rendered r) throws IOException {
        if (r.mail() == null) return;
        // worried letters: to the owner (each once; the ones marked at store time went out then)
        if (concerns != null)
            for (Map<String, String> c : r.mail().concern)
                concerns.report(s, new Concerns.Letter(c.get("id"), c.get("name"), c.get("note"), c.get("text")));
        JsonObject mail = r.mail().toJson();   // concern entries: id and name only
        atomicWrite(data.resolve("mailbag.json"), PyJson.dumps(mail, false));
        // decided letters are done (kept 10 days)
        TreeSet<String> done = new TreeSet<>();
        Path doneFile = data.resolve("mailbag-done.txt");
        if (Files.isRegularFile(doneFile)) for (String x : Files.readString(doneFile).split("\\s+")) if (!x.isBlank()) done.add(x);
        if (mail.get("done_ids") instanceof JsonArray a) for (JsonElement e : a) {
            String id = e.getAsString();
            if (id.matches("\\d{4}-\\d\\d-\\d\\d-[0-9a-f]{8}")) done.add(id);
        }
        String cut = date.minusDays(10).toString();
        done.removeIf(x -> x.length() < 10 || x.substring(0, 10).compareTo(cut) < 0);
        atomicWrite(doneFile, String.join("\n", done) + "\n");
        // letters the desk could not read (it did not run, or its picks could not be printed) wait up to 7 days, not 3
        TreeSet<String> carry = new TreeSet<>();
        Path carryFile = data.resolve("mailbag-carry.txt");
        if (Files.isRegularFile(carryFile)) for (String x : Files.readString(carryFile).split("\\s+")) if (!x.isBlank()) carry.add(x);
        if (!r.mail().ran || !r.mail().usedAi)
            for (JsonElement e : PyJson.arr(facts.get("mailbag_candidates")))
                if (e.isJsonObject() && e.getAsJsonObject().get("id") != null) carry.add(e.getAsJsonObject().get("id").getAsString());
        String cut7 = date.minusDays(7).toString();
        carry.removeIf(x -> !x.matches("\\d{4}-\\d\\d-\\d\\d-[0-9a-f]{8}") || done.contains(x) || x.substring(0, 10).compareTo(cut7) < 0);
        if (carry.isEmpty()) Files.deleteIfExists(carryFile);
        else atomicWrite(carryFile, String.join("\n", carry) + "\n");
        // printed log (for "your letter made the paper"), last 8 days, no duplicates
        Path log = data.resolve("mailbag-printed.jsonl");
        TreeSet<String> lines = new TreeSet<>();
        if (Files.isRegularFile(log)) for (String l : Files.readAllLines(log)) if (l.startsWith("{\"date\":\"")) lines.add(l);
        if (mail.get("printed") instanceof JsonArray pr) for (JsonElement e : pr) {
            JsonObject o = e.getAsJsonObject(), x = new JsonObject();
            x.addProperty("date", date.toString()); x.add("name", o.get("name")); x.add("kind", o.get("kind"));
            lines.add(PyJson.dumpsCompact(x));
        }
        String cut8 = date.minusDays(8).toString();
        lines.removeIf(l -> l.length() < 20 || l.substring(9, 19).compareTo(cut8) < 0);
        atomicWrite(log, lines.isEmpty() ? "" : String.join("\n", lines) + "\n");
    }

    /** After a published run: old files out, private modes on (best effort). */
    private void tidy(Settings s, LocalDate date) {
        try {
            pruneOlder(data.resolve("concern"), date.minusDays(7));
            pruneOlder(data.resolve("desk"), date.minusDays(7));
            // the collectors delete old day files only when they write a new line; a quiet or turned-off one never would
            pruneOlder(data.resolve("blotter"), date.minusDays(io.github.eastmadc.masthead.collect.Blotter.KEEP_DAYS));
            for (String dir : List.of("events", "health")) pruneOlder(data.resolve(dir), date.minusDays(io.github.eastmadc.masthead.collect.Recorder.KEEP_DAYS));
            io.github.eastmadc.masthead.game.Mailroom.prune(data.resolve("mailroom").toFile(), date, log);   // letters: 7 days
            StatsSnapshot.deleteTree(data.resolve("preview").resolve("desk"));   // an older version kept preview desk answers
            WebServer.pruneLog(data.resolve(FactsBuilder.BUILTIN_ACCESS_LOG), date.minusDays(WebServer.LOG_KEEP_DAYS));
        } catch (IOException e) { log.warning("clean-up: " + e.getClass().getSimpleName()); }
        Housekeeping.prune(data, date, s.privacy().retentionDays());
        Housekeeping.tighten(data, s.web().mode().equals("folder") && !s.web().folder().isEmpty() ? resolve(s.web().folder()) : null);
    }

    /** The day the mailroom opened. A preview only reads it. */
    private LocalDate mailroomSince(LocalDate date, boolean save) {
        synchronized (State.LOCK) {
            YamlConfiguration st = State.load(data);
            String v = st.getString("mailroom-since", "");
            if (v.matches("\\d{4}-\\d\\d-\\d\\d")) return LocalDate.parse(v);
            if (save) State.set(data, "mailroom-since", date.toString());
            return date;
        }
    }

    // ------------------------------------------------------------ owner summary + alerts
    private JsonObject summary(Settings s, LocalDate date, JsonObject facts, Render.Rendered r, List<String> notes) {
        JsonObject o = new JsonObject();
        o.addProperty("date", date.toString());
        o.addProperty("ai", r.usedAi());
        o.addProperty("summary", cap(r.summary(), 400));
        o.addProperty("url", s.web().publicUrl());
        if (r.gate() != null && !r.gate().isEmpty()) o.addProperty("word_gate", r.gate());
        JsonArray n = new JsonArray(); notes.forEach(n::add); o.add("notes", n);
        // construction news for the owner only (big damage, build names): pushed, never printed
        JsonArray building = new JsonArray();
        Construction.ownerLines(data, date).forEach(building::add);
        if (!building.isEmpty()) o.add("building", building);
        if (facts.get("circulation") instanceof JsonObject c) {
            String who = c.get("who") instanceof JsonArray wa && !wa.isEmpty()
                    ? " (" + String.join(", ", wa.asList().stream().map(JsonElement::getAsString).toList()) + ")" : "";
            o.addProperty("readers", "Read yesterday: " + c.get("readers").getAsInt() + " reader(s), " + c.get("page_views").getAsInt() + " page views" + who);
        }
        if (r.mail() != null) {
            JsonObject m = r.mail().toJson();
            int in = m.has("in") ? m.get("in").getAsInt() : 0;
            if (in > 0) {
                int printed = m.getAsJsonArray("printed").size(), held = m.getAsJsonArray("held").size();
                TreeSet<String> reasons = new TreeSet<>();
                for (JsonElement h : m.getAsJsonArray("held")) reasons.add(h.getAsJsonObject().get("reason").getAsString());
                o.addProperty("mailroom", "Mailroom: " + in + " letter(s), " + printed + " printed, " + held + " held" + (held > 0 ? " (" + String.join(", ", reasons) + ")" : "")
                        + (m.get("ran").getAsBoolean() ? "" : " - editor desk unavailable, " + m.get("waiting").getAsInt() + " waiting"));
            }
            // names only (an older host helper pushes these; the worried-letter alerts themselves go through Concerns)
            JsonArray concern = new JsonArray();
            if (m.get("concern") instanceof JsonArray ca) for (JsonElement e : ca) {
                String name = e.getAsJsonObject().get("name").getAsString();
                if (name.matches("\\.?[A-Za-z0-9_]{3,16}")) concern.add(name);
            }
            o.add("concern", concern);
        }
        return o;
    }

    /** "The paper is out" push. Worried letters are not in it: they have their own push (Concerns). */
    private void alert(Settings s, JsonObject sum) {
        Push nf = pushes.apply(s.alerts());
        if (nf == null || !(nf.hasNtfy() || nf.hasDiscord())) return;
        StringBuilder b = new StringBuilder();
        b.append(sum.get("summary").getAsString()).append('\n');
        if (sum.get("building") instanceof JsonArray bl) for (JsonElement e : bl) b.append(e.getAsString()).append('\n');
        for (String k : List.of("readers", "mailroom", "url")) if (sum.has(k) && !sum.get(k).getAsString().isEmpty()) b.append(sum.get(k).getAsString()).append('\n');
        if (sum.get("notes") instanceof JsonArray n) for (JsonElement e : n) b.append("(").append(e.getAsString()).append(")\n");
        nf.send(s.paper().name() + " is out", b.toString(), "default", List.of("newspaper"));
    }

    // ------------------------------------------------------------ files
    /** The plugin's own files: private (600) from the start, written in one step. */
    static void atomicWrite(Path p, String text) throws IOException {
        Housekeeping.writePrivate(p, text);
    }

    private static void pruneOlder(Path dir, LocalDate keepFrom) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String n = f.getFileName().toString();
                if (n.matches("\\d{4}-\\d\\d-\\d\\d(-[0-9a-f]{8,})?\\.jsonl?") && n.substring(0, 10).compareTo(keepFrom.toString()) < 0) Files.deleteIfExists(f);
            }
        }
    }

    private static String cap(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }
}
