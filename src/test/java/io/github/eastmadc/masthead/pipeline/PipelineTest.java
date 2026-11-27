package io.github.eastmadc.masthead.pipeline;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eastmadc.masthead.config.Settings;
import io.github.eastmadc.masthead.config.SettingsLoader;
import io.github.eastmadc.masthead.edition.MailDesk;
import io.github.eastmadc.masthead.game.Mailroom;
import io.github.eastmadc.masthead.notify.Concerns;
import io.github.eastmadc.masthead.notify.Push;
import io.github.eastmadc.masthead.writer.Writer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The edition run without a server (tier 0 of release 2.0.1):
 * T4 preview writes only under preview/ (every published file keeps its hash); T3 retries, the plain fallback and a
 * broken AI library; T6 worried letters (canary test: the letter's words reach no push and no console line, desk up and
 * desk down); T7 private files, retention, no letters on disk; T2 exchange clean-up; T14/T24/T25 checks. Plain main().
 */
public final class PipelineTest {
    static final String CANARY_WB = "CANARYWB11", CANARY_TEXT = "CANARYTXT66", CANARY_NOTE = "CANARYNOTE55";
    static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    static int checks = 0;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mh-pipeline");
        preview(root.resolve("a"));
        canary(root.resolve("b"), true);
        canary(root.resolve("c"), false);
        storeTimeAndRetry(root.resolve("d"));
        retries(root.resolve("e"));
        housekeeping(root.resolve("f"));
        settingsAndRelease(root.resolve("g"));
        reviewRound1(root.resolve("h"));
        reviewRound2(root.resolve("i"));
        reviewRound3(root.resolve("j"));
        hiddenEverywhere(root.resolve("k"));
        hiddenRename(root.resolve("q"));
        stateInMemory(root.resolve("l"));
        slowBatchDesk(root.resolve("m"));
        styleOnly(root.resolve("n"));
        publicationFailure(root.resolve("o"));
        kindsGate();
        surveyPage(root.resolve("p"));
        System.out.println("PipelineTest PASS (" + checks + " checks)");
    }

    // ------------------------------------------------------------ T4
    static void preview(Path root) throws Exception {
        World w = new World(root, true);
        Map<String, String> before = hashes(w.data, w.web);
        Rec push = new Rec(true, true);
        Logs logs = new Logs();
        EditionJob job = w.job(s -> new Fake(false, false), push, logs);
        stamp = "preview draft";
        String r = job.run(DAY, false);
        check(r.startsWith("AI preview written"), "preview ran with the AI: " + r);
        // C7: one console line when a run starts with an AI writer (the owner sees nothing else for minutes); none for the plain edition
        check(logs.lines.stream().filter(l -> l.equals("INFO Writing today's edition (writer command); this can take a few minutes.")).count() == 1,
                "one console line when the AI writer starts: " + logs.lines);
        Logs plainLogs = new Logs();
        String plain = new World(root.resolve("plain"), true).job(s -> new Fake(false, false), new Rec(true, true), plainLogs).run(DAY, false, "a test of the plain edition");
        check(plain.startsWith("PLAIN") && plainLogs.lines.stream().noneMatch(l -> l.contains("Writing today's edition")), "no such line for the plain edition: " + plain);
        check(Files.isRegularFile(w.data.resolve("preview/index.html")), "preview/index.html written");
        check(Files.isRegularFile(w.data.resolve("preview/editions/" + DAY + ".html")), "preview has its own editions/");
        check(PosixFilePermissions.toString(Files.getPosixFilePermissions(w.data.resolve("preview"))).equals("rwx------")
                && PosixFilePermissions.toString(Files.getPosixFilePermissions(w.data.resolve("preview/editions"))).equals("rwx------")
                && PosixFilePermissions.toString(Files.getPosixFilePermissions(w.data.resolve("preview/editions/" + DAY + ".html"))).equals("rw-------"),
                "the preview folders and the preview edition are private from the start");
        // the preview folder holds what its front page asks for: the fonts and the Player Cards page
        for (String f : io.github.eastmadc.masthead.edition.Pages.FONT_FILES)
            check(Files.isRegularFile(w.data.resolve("preview").resolve(f)), "preview font or its license: " + f);
        check(Files.isRegularFile(w.data.resolve("preview/players.html")), "preview/players.html written");
        Map<String, String> after = hashes(w.data, w.web);
        check(after.equals(before), "preview changed no published file (before stats snapshot): " + diff(before, after));
        check(push.all.isEmpty(), "preview pushes nothing");
        // publish, then preview again: now stats/DATE, desk/DATE.json, editions/DATE.html, today.json, web pages all exist
        stamp = null;
        String pub = job.run(DAY, true);
        check(pub.startsWith("AI"), "publish ran: " + pub);
        for (String f : List.of("editions/" + DAY + ".html", "data/" + DAY + ".json", "stats/" + DAY, "desk/" + DAY + ".json", "today.json", "last-edition.json", "mailbag.json"))
            check(Files.exists(w.data.resolve(f)), "published " + f);
        check(Files.isRegularFile(w.web.resolve("index.html")), "web page published");
        for (String f : io.github.eastmadc.masthead.edition.Pages.FONT_FILES)
            check(Files.isRegularFile(w.web.resolve(f)), "font or its license next to the pages: " + f);
        check(Files.readString(w.web.resolve("index.html")).contains("url(\"fonts/pixelify-sans/"), "page links the self-hosted fonts");
        Map<String, String> before2 = hashes(w.data, w.web);
        push.all.clear();
        stamp = "preview draft";
        String r2 = job.run(DAY, false);
        stamp = null;
        check(r2.contains("preview"), "second preview ran");
        Map<String, String> after2 = hashes(w.data, w.web);
        check(after2.equals(before2), "preview after publishing changed nothing: " + diff(before2, after2));
        check(push.all.isEmpty(), "second preview pushes nothing");
        // the preview's front page shows today's preview, the published archive keeps the published text
        check(Files.readString(w.data.resolve("editions/" + DAY + ".html")).contains("published draft")
                && Files.readString(w.data.resolve("preview/index.html")).contains("preview draft"), "archive keeps the published text");
        // the owner page: a preview writes it when there is an owner, and takes an old one away when there is none
        Settings d = w.settings(true, true, true);
        Settings owned = new Settings(d.paper(), d.tone(), new Settings.Owner("Alice_1", "The Owner's Desk", List.of(), true), d.privacy(), d.schedule(), d.writer(), d.web(),
                d.features(), d.alerts());
        new EditionJob(w.data, () -> owned, logs.logger, x -> new Fake(false, false), a -> push, new Concerns(w.data, logs.logger, a -> push), w.live, w.root).run(DAY, false);
        Path ownerPage = w.data.resolve("preview").resolve(io.github.eastmadc.masthead.edition.Pages.OWNER_FILE);
        check(Files.isRegularFile(ownerPage) && Files.readString(ownerPage).contains("Alice_1"), "preview/owner.html written for a server with an owner");
        job.run(DAY, false);
        check(!Files.exists(ownerPage), "a preview with no owner removes an old preview/owner.html");
    }

    // alpha.21 review: survey.html is drawn again on every published paper from the register kept in players.json (by
    // UUID), with today's name rules, construction news on or off. A preview never touches it.
    static void surveyPage(Path root) throws Exception {
        World w = new World(root, false);
        String a = "00000000-0000-0000-0000-00000000000a", b = "00000000-0000-0000-0000-00000000000b";
        Path cons = Files.createDirectories(w.data.resolve("construction"));
        String players = "{\"version\":1,\"next_permit\":3,\"opened\":\"2026-11-02\",\"survey_permits\":{\"S1\":1},\"players\":{"
                + "\"" + a + "\":{\"name\":\"Alice_1\",\"level\":\"%s\",\"noticed\":\"2026-10-01\"},"
                + "\"" + b + "\":{\"name\":\"Bob_22\",\"level\":\"%s\",\"noticed\":\"2026-10-01\"}},"
                + "\"register\":[{\"permit\":1,\"builders\":[\"" + a + "\"],\"kind\":\"library\",\"features\":[{\"id\":\"bookshelves\",\"n\":64}],\"blocks_total\":364,\"existing\":true},"
                + "{\"permit\":2,\"builders\":[\"" + b + "\"],\"kind\":\"house\",\"blocks_total\":200}]}";
        Path page = w.web.resolve("survey.html");
        EditionJob job = w.job(x -> new Fake(false, false), new Rec(false, false), new Logs());
        Files.writeString(cons.resolve("players.json"), players.formatted("private", "private"));
        job.run(DAY, true);
        check(!Files.exists(page), "an empty register on a fresh server writes no survey page");
        Files.writeString(cons.resolve("players.json"), players.formatted("public", "public"));
        job.run(DAY, true);
        String p = Files.readString(page);
        check(p.contains("Builders: Alice_1") && p.contains("Builders: Bob_22") && p.contains("Inside: 64 bookshelves") && p.contains("2 builds on the register"),
                "survey page from the register: " + p);
        Files.writeString(cons.resolve("players.json"), players.formatted("public", "private"));
        job.run(DAY, true);
        p = Files.readString(page);
        check(p.contains("Builders: Alice_1") && !p.contains("Bob_22") && p.contains("1 build on the register"), "a player who went private leaves the page: " + p);
        Files.writeString(cons.resolve("players.json"), players.formatted("private", "private"));
        String before = Files.readString(page);
        job.run(DAY, false);
        check(Files.readString(page).equals(before) && !Files.exists(w.data.resolve("preview").resolve("survey.html")), "a preview leaves the survey page alone");
        job.run(DAY, true);
        p = Files.readString(page);
        check(!p.contains("Alice_1") && !p.contains("Bob_22") && p.contains("The register is empty."), "nobody left: the page says the register is empty: " + p);
    }

    // A blocked late output must leave the previous paper intact, including same-day reissues.
    static void publicationFailure(Path root) throws Exception {
        for (boolean reissue : List.of(false, true)) {
            World w = new World(root.resolve(reissue ? "reissue" : "new-day"), false);
            Files.writeString(w.web.resolve("index.html"), "OLD INDEX");
            Files.writeString(w.web.resolve("owner.html"), "OLD OWNER");
            Files.createDirectory(w.web.resolve("players.html"));
            Path archive = w.data.resolve("editions/" + DAY + ".html");
            if (reissue) Files.writeString(archive, "OLD SAME-DAY ARCHIVE");
            String oldGame = Files.readString(w.data.resolve("today.json"));
            Rec push = new Rec(true, false);
            EditionJob job = w.job(x -> new Fake(false, false), push, new Logs());
            int[] marked = {0}; job.onPublished(d -> marked[0]++);
            try { job.run(DAY, true); check(false, "a directory cannot be replaced by a player page"); }
            catch (IOException expected) { }
            check(Files.readString(w.web.resolve("index.html")).equals("OLD INDEX"), "failed preparation keeps the web edition");
            check(Files.readString(w.web.resolve("owner.html")).equals("OLD OWNER"), "failed preparation keeps the owner page");
            check(Files.readString(w.data.resolve("today.json")).equals(oldGame), "failed preparation keeps the game edition");
            check(reissue ? Files.readString(archive).equals("OLD SAME-DAY ARCHIVE") : !Files.exists(archive), "failed preparation keeps the archive");
            check(marked[0] == 0 && push.all.isEmpty() && !Files.exists(w.data.resolve("mailbag-done.txt")), "failed preparation neither marks, notifies nor consumes mail");
            Files.delete(w.web.resolve("players.html"));
            job.run(DAY, true);
            check(marked[0] == 1 && Files.readString(archive).contains("published draft")
                    && Files.readString(w.web.resolve("index.html")).contains("published draft"), "retry publishes the coherent new edition once");
            check(!Files.exists(w.web.resolve("owner.html")), "successful publish removes the disabled owner page");
        }
        // Real filesystem failure AFTER replacement, creation and deletion. No fake move implementation:
        // losing the final prepared file forces its rename to fail and exercises all three rollback cases.
        World w = new World(root.resolve("commit"), false);
        EditionJob job = w.job(x -> new Fake(false, false), new Rec(false, false), new Logs());
        Path a = w.web.resolve("index.html"), b = w.web.resolve("owner.html"), c = w.web.resolve("players.html"), d = w.data.resolve("today.json");
        Files.writeString(a, "OLD INDEX"); Files.writeString(b, "OLD OWNER");
        String oldGame = Files.readString(d);
        List<EditionJob.Publication> prepared = List.of(job.preparePublication(a, "NEW INDEX", false),
                job.preparePublication(c, "NEW PLAYERS", false), job.preparePublication(b, null, false), job.preparePublication(d, "NEW GAME", true));
        Files.delete(prepared.get(3).next());
        try { job.commitPublication(prepared); check(false, "the missing prepared game file must fail during commit"); }
        catch (IOException expected) { }
        check(Files.readString(a).equals("OLD INDEX") && Files.readString(b).equals("OLD OWNER") && Files.readString(d).equals(oldGame)
                && !Files.exists(c), "mid-commit failure restores replacements and deletions and removes new outputs");
        for (Path folder : List.of(w.web, w.data)) {
            try (Stream<Path> files = Files.list(folder)) {
                check(files.noneMatch(p -> p.getFileName().toString().startsWith(".masthead-")), "ordinary rollback cleans its temporary files");
            }
        }
    }

    // ------------------------------------------------------------ T6 canary
    static void canary(Path root, boolean deskUp) throws Exception {
        World w = new World(root, true);
        Rec push = new Rec(true, true);
        Logs logs = new Logs();
        EditionJob job = w.job(s -> new Fake(!deskUp, false), push, logs);
        String r = job.run(DAY, true);
        check(r.startsWith("AI"), "edition ran (desk " + (deskUp ? "up" : "down") + "): " + r);
        List<String> named = push.ntfyBodies();
        check(named.stream().anyMatch(b -> b.equals(String.format(Concerns.NAMED, "Alice_1"))), "worried letter pushed by name (ntfy): " + named);
        if (deskUp) check(named.stream().anyMatch(b -> b.equals(String.format(Concerns.NAMED, "Bob_22"))), "desk-flagged letter pushed: " + named);
        else check(named.stream().noneMatch(b -> b.contains("💛") && b.contains("Bob_22")), "desk down: only the worried-word letter");
        check(push.discordBodies().stream().filter(b -> b.contains("💛")).allMatch(b -> b.equals(Concerns.UNNAMED)), "Discord gets no name");
        check(push.all.stream().noneMatch(m -> m.contains("Alice_1") && m.contains("is out") && m.contains("💛")), "the 'is out' push has no worried line");
        // the canary: nowhere in any push (title or body) and no console line
        for (String c : List.of(CANARY_WB, CANARY_TEXT, CANARY_NOTE)) {
            check(push.all.stream().noneMatch(m -> m.contains(c)), "canary " + c + " in no push");
            check(logs.lines.stream().noneMatch(l -> l.contains(c)), "canary " + c + " in no console line");
            for (String f : List.of("mailbag.json", "data/" + DAY + ".json", "last-edition.json", "today.json"))
                check(!Files.readString(w.data.resolve(f)).contains(c), "canary " + c + " not in " + f);
        }
        // the host helper's files: one per letter, the 2.0.0 shape (a list of {id, name, note, text}); it reads only "name"
        try (Stream<Path> s = Files.list(w.data.resolve("concern"))) {
            List<Path> files = s.toList();
            check(files.size() == (deskUp ? 2 : 1), "one helper file per worried letter: " + files);
            for (Path f : files) {
                check(f.getFileName().toString().matches("\\d{4}-\\d\\d-\\d\\d-[0-9a-f]{8,}\\.json"), "helper file name " + f.getFileName());
                var a = JsonParser.parseString(Files.readString(f)).getAsJsonArray();
                check(a.size() == 1 && a.get(0).getAsJsonObject().has("name") && a.get(0).getAsJsonObject().has("text"), "helper file shape");
            }
        }
        check(!Files.exists(w.data.resolve("concern-new")) || isEmpty(w.data.resolve("concern-new")), "no half-written helper file left");
        // a re-run does not push the same letters again
        int n = push.all.size();
        push.all.clear();
        job.run(DAY, true);
        check(push.all.stream().noneMatch(m -> m.contains("💛")), "no second worried-letter push on a re-run (" + n + " before)");
        if (!deskUp) check(Files.readString(w.data.resolve("mailbag-carry.txt")).contains(w.normalId), "letters the desk could not read are kept (carry list)");
    }

    // ------------------------------------------------------------ C3: a slow batch draft never costs the editor desk its turn
    static void slowBatchDesk(Path root) throws Exception {
        World w = new World(root, true);
        Rec push = new Rec(true, true);
        Logs logs = new Logs();
        long[] now = {0};
        java.util.function.LongSupplier real = EditionJob.nanoTime;
        EditionJob.nanoTime = () -> now[0];
        try {
            Fake f = new Fake(false, false);
            Writer slow = new Writer() {
                @Override public String name() { return "slow batch"; }
                @Override public String write(String system, String user, Duration timeout) throws Exception { return f.write(system, user, timeout); }
                @Override public String writePaper(String system, String user, Duration timeout) throws Exception {
                    now[0] += Duration.ofSeconds(700).toNanos();   // a batch that took over 11 minutes
                    return f.write(system, user, timeout);
                }
            };
            Settings d = w.settings(true, true, true);   // timeout-seconds 900, as for the host batch
            Settings s = new Settings(d.paper(), d.tone(), d.owner(), d.privacy(), d.schedule(),
                    new Settings.WriterCfg("command", d.writer().model(), "medium", "", List.of("x"), 900, 60), d.web(), d.features(), d.alerts());
            EditionJob job = new EditionJob(w.data, () -> s, logs.logger, x -> slow, a -> push, new Concerns(w.data, logs.logger, a -> push), w.live, w.root);
            String r = job.run(DAY, true);
            check(r.startsWith("AI") && Files.isRegularFile(w.data.resolve("desk/" + DAY + ".json")) && !r.contains("no time left"),
                    "C3: the editor desk still runs after a slow batch draft: " + r);
            check(push.ntfyBodies().contains(String.format(Concerns.NAMED, "Bob_22")), "C3: the desk's worried letter reaches the owner the same day");
        } finally {
            EditionJob.nanoTime = real;
        }
    }

    // ------------------------------------------------------------ T6 at store time, failed pushes, Discord-only
    static void storeTimeAndRetry(Path root) throws Exception {
        World w = new World(root, false);
        Logs logs = new Logs();
        Rec down = new Rec(true, false);
        down.ntfyWorks = false;
        Settings s = w.settings(false, true, false);
        Concerns c = new Concerns(w.data, logs.logger, a -> down);
        LocalDate today = LocalDate.now();   // not DAY: concern-sent.txt forgets an id 10 days old, and then it would be sent twice
        String id = today + "-0000c0de";
        c.report(s, new Concerns.Letter(id, "Alice_1", "worried words", CANARY_WB + " nobody would care"));
        check(c.waiting() == 1, "failed push waits in the outbox");
        check(logs.lines.stream().anyMatch(l -> l.contains("tried again")) && logs.lines.stream().noneMatch(l -> l.contains(CANARY_WB) || l.contains("Alice_1")),
                "warning without text or name: " + logs.lines);
        check(c.recentNames(today).equals(List.of("Alice_1")), "status sees the waiting name");
        down.ntfyWorks = true;
        c.retry(s);
        check(c.waiting() == 0 && down.ntfyBodies().contains(String.format(Concerns.NAMED, "Alice_1")), "retry sends it");
        int sent = down.all.size();
        c.report(s, new Concerns.Letter(id, "Alice_1", "", "again"));
        check(down.all.size() == sent, "the same letter is reported once");
        // Discord only: no name; a bad name becomes "a player"
        Rec disc = new Rec(false, true);
        Concerns c2 = new Concerns(root.resolve("d2"), logs.logger, a -> disc);
        c2.report(w.settings(false, false, true), new Concerns.Letter(DAY + "-0000c0df", "Bad Name!", "", CANARY_WB));
        check(disc.all.size() == 1 && disc.discordBodies().get(0).equals(Concerns.UNNAMED) && !disc.all.get(0).contains(CANARY_WB), "Discord-only push has no name");
        check(Concerns.safeName("x y").equals("a player") && Concerns.safeName(".Bed_rock1").equals(".Bed_rock1"), "name check");
        // the mailroom line marks the worried letter; the desk side treats it as concern even with no desk
        String line = Mailroom.line(id, 1, "Alice_1", "letter", "hi", true);
        check(line.contains("\"wb\":true") && !Mailroom.line(id, 1, "Alice_1", "letter", "hi", false).contains("wb"), "wb mark");
        for (String l : logs.lines) check(!l.contains(CANARY_WB), "canary not logged");
    }

    // ------------------------------------------------------------ T3 + T2
    static void retries(Path root) throws Exception {
        // decisions
        check(Attempts.next("2026-11-10", "2026-11-10", "", 0, 0, 0) == Attempts.Next.DONE, "done");
        check(Attempts.next("2026-11-10", "2026-11-09", "2026-11-09", 3, 0, 0) == Attempts.Next.RUN, "new day");
        check(Attempts.next("2026-11-10", "2026-11-09", "2026-11-10", 1, 1000, 1300) == Attempts.Next.WAIT, "wait 10 minutes");
        check(Attempts.next("2026-11-10", "2026-11-09", "2026-11-10", 2, 1000, 1600) == Attempts.Next.RUN, "retry after 10 minutes");
        check(Attempts.next("2026-11-10", "2026-11-09", "2026-11-10", 3, 1000, 1001) == Attempts.Next.PLAIN, "plain at once after 3");
        check(Attempts.next("2026-11-10", "2026-11-09", "2026-11-10", 4, 1000, 99999) == Attempts.Next.GAVE_UP, "then stop");
        check(Attempts.reason(new java.io.IOException("a\nb")).equals("IOException: a b"), "one-line reason");
        // a day published but not saved to state.yml still counts as done (no second paper); a cut-off plain try is told once
        check(Attempts.next("2026-11-10", Attempts.lastEdition("2026-11-09", "2026-11-10", "2026-11-10"), "2026-11-10", 1, 1000, 99999) == Attempts.Next.DONE,
                "published in memory: done");
        check(Attempts.lastEdition("2026-11-09", "2026-11-09", "2026-11-10").equals("2026-11-09"), "yesterday's memory is not today");
        check(Attempts.tellGaveUp("2026-11-10", "", "") && !Attempts.tellGaveUp("2026-11-10", "2026-11-10", "")
                && !Attempts.tellGaveUp("2026-11-10", "2026-11-09", "2026-11-10") && Attempts.tellGaveUp("2026-11-10", "2026-11-09", "2026-11-09"), "told once a day");
        // a broken AI library (LinkageError) gives the plain edition, not no paper
        World w = new World(root, true);
        Rec push = new Rec(true, false);
        Logs logs = new Logs();
        EditionJob job = w.job(s -> new Fake(false, true), push, logs);
        String r = job.run(DAY, true);
        check(r.startsWith("PLAIN") && r.contains("NoClassDefFoundError"), "LinkageError -> plain edition: " + r);
        check(Files.isRegularFile(w.web.resolve("index.html")), "plain edition published");
        // the plain fallback after 3 failures: no writer, the reason in the owner's notes
        String why = "today's paper fell back to the plain edition after 3 failed tries (IOException: disk)";
        String p = job.run(DAY, true, why);
        JsonObject le = JsonParser.parseString(Files.readString(w.data.resolve("last-edition.json"))).getAsJsonObject();
        check(p.startsWith("PLAIN") && !le.get("ai").getAsBoolean() && le.get("notes").toString().contains("fell back"), "plain fallback noted: " + le);
        check(push.ntfyBodies().stream().anyMatch(b -> b.contains("fell back")), "owner pushed about the fallback");
        // X6: an answer with no heading (a refusal or chat filler in plain prose) is not a paper: plain edition, and a warning
        for (String prose : List.of("I am sorry, but writing this newspaper is not something I am able to help with. Is there something else you would like instead?",
                "Sure! Happy to help. Could you tell me a little more about what kind of newspaper you have in mind and who the readers are?")) {
            Writer chat = new Writer() {
                @Override public String name() { return "chat"; }
                @Override public String write(String system, String user, Duration timeout) {
                    return system.contains("Players mailed the letters") ? "{\"printed\": [], \"held\": [], \"concern\": []}" : prose;
                }
            };
            logs.lines.clear();
            String pr = w.job(s -> chat, push, logs).run(DAY, true);
            check(pr.startsWith("PLAIN") && !Files.readString(w.web.resolve("index.html")).contains(prose.substring(0, 20))
                    && !Files.readString(w.data.resolve("editions/" + DAY + ".html")).contains(prose.substring(0, 20))
                    && logs.lines.stream().anyMatch(l -> l.contains("the AI's answer was not a paper; using the plain edition")), "X6 prose is not published as the paper: " + pr);
        }
        // a run that fails (web folder is a file) throws, and the exchange folders are still cleaned
        Files.createDirectories(w.data.resolve("ai/requests"));
        Files.createDirectories(w.data.resolve("ai/responses"));
        Files.writeString(w.data.resolve("ai/requests/2026-11-09-t360-0123456789abcdef.txt"), "old");
        Files.writeString(w.data.resolve("ai/responses/2026-11-09-t150-0123456789abcdee.txt"), "late answer");
        StatsSnapshotHelper.deleteTree(w.web);
        Files.writeString(w.web, "not a folder");
        boolean threw = false;
        try { job.run(DAY, true); } catch (IOException e) { threw = true; }
        check(threw, "a failing run throws (Masthead counts it as a failed try)");
        check(isEmpty(w.data.resolve("ai/requests")) && isEmpty(w.data.resolve("ai/responses")), "stale exchange files deleted after the run");
        // every try failed: last-edition.json says so, and the owner is told
        push.all.clear();
        job.failed(DAY, "IOException: disk full");
        le = JsonParser.parseString(Files.readString(w.data.resolve("last-edition.json"))).getAsJsonObject();
        check(le.get("failed").getAsBoolean() && le.get("date").getAsString().equals(DAY.toString()) && le.has("summary") && le.has("notes"), "failed record");
        check(push.all.stream().anyMatch(m -> m.contains("today's paper failed")), "owner told the paper failed");
    }

    // ------------------------------------------------------------ T7
    static void housekeeping(Path root) throws Exception {
        World w = new World(root, true);
        // things 2.0.0 left behind
        Files.createDirectories(w.data.resolve("data"));
        Files.writeString(w.data.resolve("data/2026-09-01.json"), "{\"date\": \"2026-09-01\"}");
        Files.writeString(w.data.resolve("data/2026-11-05.json"), "{\"date\": \"2026-11-05\", \"mailbag_candidates\": [{\"text\": \"" + CANARY_TEXT + "\"}], \"circulation\": {\"readers\": 1, \"who\": [\"Bob_22\"]}}");
        Files.createDirectories(w.data.resolve("stats/2026-09-01"));
        Files.writeString(w.data.resolve("mailbag.json"), "{\"in\": 1, \"concern\": [{\"id\": \"2026-11-05-00000001\", \"name\": \"Alice_1\", \"note\": \"sad\", \"text\": \"" + CANARY_TEXT + "\"}]}");
        check(Housekeeping.scrubOld(w.data) == 2, "old facts file and mailbag.json cleaned");
        check(!Files.readString(w.data.resolve("data/2026-11-05.json")).contains(CANARY_TEXT) && !Files.readString(w.data.resolve("data/2026-11-05.json")).contains("Bob_22")
                && !Files.readString(w.data.resolve("mailbag.json")).contains(CANARY_TEXT) && Files.readString(w.data.resolve("mailbag.json")).contains("Alice_1"), "cleaned contents");
        check(perms(w.data.resolve("mailbag.json")).equals("rw-------"), "the cleaned mailbag.json is private at once: " + perms(w.data.resolve("mailbag.json")));
        check(Housekeeping.scrubOld(w.data) == 0, "the clean-up changes nothing the second time");
        Files.setPosixFilePermissions(w.web, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.createDirectories(w.data.resolve("editions"));
        Files.setPosixFilePermissions(w.data.resolve("editions"), PosixFilePermissions.fromString("rwxr-xr-x"));
        // W1: day files of the collectors go by their date, also with the Blotter turned off
        for (String f : List.of("blotter/" + DAY.minusDays(15), "blotter/" + DAY.minusDays(14), "events/" + DAY.minusDays(41), "events/" + DAY.minusDays(40),
                "health/" + DAY.minusDays(41), "health/" + DAY.minusDays(40))) {
            Files.createDirectories(w.data.resolve(f).getParent());
            Files.writeString(w.data.resolve(f + ".jsonl"), "{}\n");
        }
        Settings on = w.settings(true, true, true);
        Settings noBlotter = new Settings(on.paper(), on.tone(), on.owner(), on.privacy(), on.schedule(), on.writer(), on.web(), new Settings.Features(true, false, false, true, 3, ""), on.alerts());
        Rec push = new Rec(false, false);
        Logs logs = new Logs();
        EditionJob job = new EditionJob(w.data, () -> noBlotter, logs.logger, s -> new Fake(false, false), a -> push, new Concerns(w.data, logs.logger, a -> push), w.live, w.root);
        job.run(DAY, true);
        for (String f : List.of("blotter/" + DAY.minusDays(15), "events/" + DAY.minusDays(41), "health/" + DAY.minusDays(41)))
            check(!Files.exists(w.data.resolve(f + ".jsonl")), "past its keep time: deleted " + f);
        for (String f : List.of("blotter/" + DAY.minusDays(14), "events/" + DAY.minusDays(40), "health/" + DAY.minusDays(40), "events/" + DAY.minusDays(1)))
            check(Files.exists(w.data.resolve(f + ".jsonl")), "inside its keep time: kept " + f);
        check(!Files.exists(w.data.resolve("data/2026-09-01.json")) && !Files.exists(w.data.resolve("stats/2026-09-01")), "older than 40 days: deleted");
        check(Files.exists(w.data.resolve("data/2026-11-05.json")) && Files.exists(w.data.resolve("stats/" + DAY.minusDays(1))), "recent kept");
        for (String d : List.of("data", "stats", "mailroom", "events", "desk", "stats/" + DAY))
            check(perms(w.data.resolve(d)).equals("rwx------"), d + "/ is 700: " + perms(w.data.resolve(d)));
        for (String f : List.of("data/" + DAY + ".json", "mailroom/" + DAY + ".jsonl", "mailbag.json", "state.yml", "today.json", "last-edition.json"))
            check(perms(w.data.resolve(f)).equals("rw-------"), f + " is 600: " + perms(w.data.resolve(f)));
        check(perms(w.data.resolve("editions")).equals("rwxr-xr-x") && perms(w.web).equals("rwxr-xr-x"), "editions/ and the web folder keep their modes");
        check(!perms(w.data.resolve("editions/" + DAY + ".html")).equals("rw-------") && !perms(w.web.resolve("index.html")).equals("rw-------"),
                "edition and web files not made private");
    }

    // ------------------------------------------------------------ settings, T14, T24, T25
    static void settingsAndRelease(Path root) throws Exception {
        String base = "features:\n  mailroom: true\nwriter:\n  mode: exchange\n";
        check(!load(base).features().mailroom(), "no alert channel: mailroom off");
        check(!load(base).features().mailroom(), "exchange mode alone is not a channel");
        // no working AI writer, no mailroom (even with a channel); command and exchange count as a writer
        String ch = "features:\n  mailroom: true\nalerts:\n  ntfy-url: \"https://ntfy.example/abc\"\n";
        check(!load(ch).features().mailroom(), "writer.mode none (the default): mailroom off");
        check(!load(ch + "writer:\n  mode: none\n").features().mailroom(), "writer.mode none: mailroom off");
        check(!load(ch + "writer:\n  mode: anthropic\n").features().mailroom(), "anthropic, no key: mailroom off");
        check(load(ch + "writer:\n  mode: anthropic\n  anthropic:\n    api-key: \"k\"\n").features().mailroom(), "anthropic with a key: mailroom on");
        check(load(ch + "writer:\n  mode: anthropic\n  anthropic:\n    api-key-file: /srv/minecraft/masthead.key\n").features().mailroom(), "anthropic with a key file: mailroom on");
        List<String> warned = new ArrayList<>();
        Settings old = SettingsLoader.load(YamlConfiguration.loadConfiguration(new StringReader("writer:\n  mode: anthropic\n  api-key: \"k\"\n")), warned::add, null);
        check(old.writer().apiKey().isEmpty() && warned.stream().anyMatch(x -> x.contains("writer.api-key has moved to writer.anthropic.api-key")), "the old flat key is ignored, with a warning: " + warned);
        // writer: per-mode sections, budget, openai-compatible
        Settings.WriterCfg dw = load("").writer();
        check(dw.batch() && dw.monthlyBudgetUsd() == 10 && dw.model().equals("claude-opus-5-5") && dw.effort().equals("medium"), "writer defaults: batch on, $10 budget");
        Settings.WriterCfg oa = load(ch + "writer:\n  mode: openai-compatible\n  monthly-budget-usd: 2.5\n  anthropic:\n    batch: false\n    effort: xhigh\n"
                + "  openai-compatible:\n    base-url: https://openrouter.ai/api/v1\n    model: \"~anthropic/claude-sonnet-latest\"\n    price-per-million-input: 1.5\n"
                + "    extra-body:\n      provider: { data_collection: deny, zdr: true }\n      tags: [a, b]\n").writer();
        check(oa.monthlyBudgetUsd() == 2.5 && !oa.batch() && oa.effort().equals("xhigh"), "budget, batch and effort read");
        check(oa.openai().baseUrl().equals("https://openrouter.ai/api/v1") && oa.openai().model().equals("~anthropic/claude-sonnet-latest") && oa.openai().pricePerMillionInput() == 1.5,
                "openai-compatible read: " + oa.openai());
        check(oa.openai().extraBody().equals("{\"provider\":{\"data_collection\":\"deny\",\"zdr\":true},\"tags\":[\"a\",\"b\"]}"), "extra-body as JSON: " + oa.openai().extraBody());
        check(SettingsLoader.writerLine(oa, null).equals("Writer: openai-compatible (Beta)") && load(ch + "writer:\n  mode: openai-compatible\n").features().mailroom() == false,
                "openai-compatible line; no base-url means no writer");
        check(SettingsLoader.writerLine(load("writer:\n  mode: openai-compatible\n").writer(), null).equals("Writer: openai-compatible (no base-url or model set: plain edition)"), "openai-compatible, not set up");
        check(load("writer:\n  monthly-budget-usd: -1\n").writer().monthlyBudgetUsd() == 10 && load("writer:\n  monthly-budget-usd: 0\n").writer().monthlyBudgetUsd() == 0, "budget range");
        check(SettingsLoader.load(YamlConfiguration.loadConfiguration(new StringReader(ch + "writer:\n  mode: anthropic\n")), m -> { }, "env-key").features().mailroom(),
                "anthropic with ANTHROPIC_API_KEY: mailroom on");
        check(load(ch + "writer:\n  mode: command\n").features().mailroom() && load(ch + "writer:\n  mode: exchange\n").features().mailroom(), "command and exchange count as a writer");
        check(SettingsLoader.writerLine(load(ch + "writer:\n  mode: anthropic\n").writer(), null).equals("Writer: anthropic (no API key set: plain edition)"), "the exact writer line, anthropic with no key");
        check(SettingsLoader.writerLine(load(ch).writer(), null).equals("Writer: none (plain edition)") && SettingsLoader.writerLine(load(base).writer(), null).equals("Writer: exchange"), "writer line, none and exchange");
        check(SettingsLoader.MAILROOM_NO_WRITER_LINE.startsWith("Mailroom is off: it needs an AI writer"), "the AI-writer console line");
        // /masthead status share says "API key: set" for a key from any source, of the writer in use only
        String anth = "writer:\n  mode: anthropic\n", open = "writer:\n  mode: openai-compatible\n  openai-compatible:\n    base-url: \"http://127.0.0.1:11434/v1\"\n";
        check(SettingsLoader.apiKeySet(load(anth + "  anthropic:\n    api-key-file: /srv/minecraft/masthead.key\n").writer(), null, null)
                && SettingsLoader.apiKeySet(load(anth + "  anthropic:\n    api-key: \"k\"\n").writer(), null, null)
                && SettingsLoader.apiKeySet(load(anth).writer(), "k", null), "status share: anthropic key file, key and ANTHROPIC_API_KEY count");
        check(SettingsLoader.apiKeySet(load(open + "    api-key-file: /srv/minecraft/masthead.key\n").writer(), null, null)
                && SettingsLoader.apiKeySet(load(open + "    api-key: \"k\"\n").writer(), null, null)
                && SettingsLoader.apiKeySet(load(open).writer(), null, "k"), "status share: openai-compatible key file, key and MASTHEAD_AI_KEY count");
        check(!SettingsLoader.apiKeySet(load(anth).writer(), null, "k") && !SettingsLoader.apiKeySet(load(open).writer(), "k", null)
                && !SettingsLoader.apiKeySet(load(open + "  anthropic:\n    api-key: \"k\"\n").writer(), null, null), "status share: another writer's key does not count");
        // a key file that is named but missing or empty is no key (the writer never falls back to another source)
        Path keyFile = Files.createTempFile("mh-key", ".txt");
        String withFile = "  anthropic:\n    api-key: \"k\"\n    api-key-file: \"" + keyFile + "\"\n";
        check(SettingsLoader.apiKeyStatus(load(anth + withFile).writer(), "k", null).equals("not set (api-key-file is empty)"), "status share: an empty key file is not a key");
        Files.writeString(keyFile, "k\n");
        check(SettingsLoader.apiKeyStatus(load(anth + withFile).writer(), null, null).equals("set"), "status share: a key file with a key");
        Files.delete(keyFile);
        check(SettingsLoader.apiKeyStatus(load(anth + withFile).writer(), "k", null).equals("not set (api-key-file can't be read)"), "status share: a missing key file is not a key");
        check(SettingsLoader.apiKeyStatus(load(anth).writer(), "k", null).equals("set") && SettingsLoader.apiKeyStatus(load(anth).writer(), null, null).equals("not set")
                && SettingsLoader.apiKeyStatus(load(open + "    api-key-file: \"" + keyFile + "\"\n").writer(), null, "k").startsWith("not set ("), "status share: no key file, and the other writer's key file");
        check(load(base + "alerts:\n  helper-pushes-concerns: true\n").features().mailroom(), "helper channel: mailroom on");
        check(load(base + "alerts:\n  ntfy-url: \"https://ntfy.example/abc\"\n").features().mailroom(), "ntfy: mailroom on");
        check(load(base + "alerts:\n  discord-webhook: \"https://discord.example/x\"\n").features().mailroom(), "Discord: mailroom on");
        check(!load(base + "alerts:\n  ntfy-url: \"not a url\"\n").features().mailroom(), "a broken URL is not a channel");
        check(SettingsLoader.MAILROOM_OFF_LINE.equals("Mailroom is off: it needs an alert channel (alerts.ntfy-url or a private alerts.discord-webhook) so worried letters reach a person."),
                "the exact console line");
        check(Mailroom.CLOSED.equals("The mailroom is closed on this server."), "the exact /letter reply");
        check(load("privacy:\n  retention-days: 12\n").privacy().retentionDays() == 12 && load("").privacy().retentionDays() == 40, "retention-days");
        // construction news: off and opt-in by default; bad values fall back
        check(!load("").features().construction() && load("").construction().equals(Settings.Construction.defaults())
                && load("").construction().defaultLevel().equals("private"), "construction off, private by default");
        var cons = load("features:\n  construction: true\nconstruction:\n  default-level: loud\n  forget-after-days: 400\n  max-entries: 2\n  opted-in: [Steve_Builder, \"bad name!\"]\n");
        check(cons.features().construction() && cons.construction().defaultLevel().equals("private") && cons.construction().forgetAfterDays() == 30
                && cons.construction().maxEntries() == 2 && cons.construction().optedIn().equals(List.of("Steve_Builder")), "construction values checked");
        var sb = SettingsLoader.load(YamlConfiguration.loadConfiguration(Path.of("examples/config-teens-classic.yml").toFile()), m -> { }, null);
        check(sb.features().construction() && !sb.construction().print() && sb.construction().defaultLevel().equals("private")
                && sb.construction().forgetAfterDays() == 90 && sb.construction().optedIn().size() >= 9, "example preset: practice run, opt-in list, 90 days");
        // shipped files (build/classes holds the resources)
        String py = new String(PipelineTest.class.getResourceAsStream("/plugin.yml").readAllBytes(), StandardCharsets.UTF_8);
        check(py.matches("(?s).*version: '1\\.0\\.2(-(alpha|beta|rc)\\.\\d+)?'.*"), "plugin.yml version is 1.0.2, 1.0.2-alpha.N, 1.0.2-beta.N or 1.0.2-rc.N (R14)");
        check(py.contains("authors: [eastmadc]"), "plugin.yml author is the handle, not a real name (R5)");
        String page = io.github.eastmadc.masthead.edition.Pages.index(load(""), new com.google.gson.JsonObject(), "", List.of(), java.time.LocalDate.of(2026, 11, 5), true);
        check(!page.contains("googleapis") && !page.contains("gstatic") && page.contains("Written by an AI"), "the page loads nothing from other websites and says it is AI-written (R7, R11)");
        String plainPage = io.github.eastmadc.masthead.edition.Pages.index(load(""), new com.google.gson.JsonObject(), "", List.of(), java.time.LocalDate.of(2026, 11, 5), false);
        check(!plainPage.contains("Written by an AI") && plainPage.contains("<p class=\"foot\">Written from this server's game stats. Updated Nov 5, 2026.</p>"), "F7: a plain day's page has the plain footer");
        check(page.contains("<link rel=\"icon\" type=\"image/png\" href=\"data:image/png;base64,iVBOR"), "the tab icon is inlined");
        check(!py.contains("aliases"), "plugin.yml has no fixed aliases (T24)");
        String cy = new String(PipelineTest.class.getResourceAsStream("/config.yml").readAllBytes(), StandardCharsets.UTF_8);
        YamlConfiguration def = YamlConfiguration.loadConfiguration(new StringReader(cy));
        check(!def.getBoolean("alerts.helper-pushes-concerns", true) && def.getInt("privacy.retention-days") == 40, "config.yml defaults");
        for (String c : List.of("news", "letter", "awards", "stats", "masthead")) check(def.getStringList("commands." + c).isEmpty(), "commands." + c + " empty by default");
        check(cy.contains("Set true only if your exchange helper pushes the files in the concern folder to a grown-up. The helper in tools/")
                && cy.contains("private, staff-only channel") && cy.contains("nobody can guess"), "alert comments");
        // T14: the self-test commands are gone from the source (a fixed check, not a review)
        try (Stream<Path> s = Files.walk(Path.of("src/main"))) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                String t = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);   // the fonts are binary
                check(!t.contains("mailtest") && !t.contains("blottertest"), "no mailtest/blottertest in " + f);
            }
        }
        // T25: files 2.0.1 writes stay readable the way 2.0.0 reads them
        Path d = Files.createDirectories(root);
        State.set(d, "qr-map-id", 7);
        State.set(d, "last-edition", "2026-11-10");
        State.setAll(d, Map.of("edition-attempt-date", "2026-11-10", "edition-attempts", 2));
        YamlConfiguration st = YamlConfiguration.loadConfiguration(d.resolve("state.yml").toFile());   // what 2.0.0 does
        check(st.getString("last-edition", "").equals("2026-11-10") && st.getInt("qr-map-id") == 7, "state.yml readable by 2.0.0");
        Files.createDirectories(d.resolve("mailroom"));
        Files.writeString(d.resolve("mailroom/2026-11-10.jsonl"), Mailroom.line("2026-11-10-0000abcd", 1, "Alice_1", "letter", "hello there", true));
        var cands = MailDesk.candidates(d.resolve("mailroom"), d.resolve("none.txt"), LocalDate.parse("2026-11-10"), List.of("Alice_1"));
        check(cands.size() == 1 && cands.get(0).getAsJsonObject().get("text").getAsString().equals("hello there"), "a 2.0.1 mailroom line reads like a 2.0.0 one");
        // the request name carries the time limit (T2) and fits the helpers' pattern
        // (checked in CommandExchangeWriterTest too)
    }

    // ------------------------------------------------------------ review round 1
    static void reviewRound1(Path root) throws Exception {
        Logs logs = new Logs();
        // CSP-5 / CC6: an alert that went out but whose outbox file could not be removed is not sent again
        Path d1 = Files.createDirectories(root.resolve("sent-once"));
        Rec r1 = new Rec(true, false);
        r1.ntfyWorks = false;
        World w0 = new World(root.resolve("w0"), false);
        Settings ntfyOnly = w0.settings(true, false, false);
        Concerns c1 = new Concerns(d1, logs.logger, a -> r1);
        c1.report(ntfyOnly, new Concerns.Letter(DAY + "-0000aa01", "Bob_1", "worried words", "x"));
        Files.setPosixFilePermissions(d1.resolve("concern-outbox"), PosixFilePermissions.fromString("r-x------"));
        r1.ntfyWorks = true;
        for (int i = 0; i < 4; i++) c1.retry(ntfyOnly);
        Files.setPosixFilePermissions(d1.resolve("concern-outbox"), PosixFilePermissions.fromString("rwx------"));
        check(r1.ntfyBodies().size() == 2, "sent once after the outbox could not be cleared (1 failed try + 1 push): " + r1.all);
        c1.retry(ntfyOnly);
        check(c1.waiting() == 0 && r1.ntfyBodies().size() == 2, "the left-over outbox file is cleared without a push");
        // CSP-4: an alert still waiting after 7 days loses the letter's words, keeps the name and still waits
        Path d2 = Files.createDirectories(root.resolve("old-outbox/concern-outbox"));
        String oldId = "2020-01-01-0000aa02", newId = LocalDate.now() + "-0000aa03";
        for (String id : List.of(oldId, newId))
            Files.writeString(d2.resolve(id + ".json"), "{\"id\":\"" + id + "\",\"name\":\"Cat_3\",\"note\":\"" + CANARY_NOTE + "\",\"text\":\"" + CANARY_TEXT + "\",\"done\":[]}");
        Concerns c2 = new Concerns(d2.getParent(), logs.logger, a -> null);
        c2.retry(w0.settings(false, false, false));   // no channel: nothing can go out
        JsonObject old = JsonParser.parseString(Files.readString(d2.resolve(oldId + ".json"))).getAsJsonObject();
        check(!old.toString().contains(CANARY_TEXT) && !old.toString().contains(CANARY_NOTE) && old.get("name").getAsString().equals("Cat_3"),
                "old waiting alert keeps no words: " + old);
        check(Files.readString(d2.resolve(newId + ".json")).contains(CANARY_TEXT) && c2.waiting() == 2, "a recent one keeps them and both still wait");
        // CC1: /masthead status (waiting, recentNames) does not wait for a retry stuck on the network
        Path d3 = Files.createDirectories(root.resolve("slow"));
        java.util.concurrent.CountDownLatch sending = new java.util.concurrent.CountDownLatch(1);
        Push slow = new Push() {
            @Override public boolean hasNtfy() { return true; }
            @Override public boolean hasDiscord() { return false; }
            @Override public boolean sendNtfy(String t, String b, String pr, List<String> tags) {
                sending.countDown();
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
                return false;
            }
            @Override public boolean sendDiscord(String t, String b) { return false; }
        };
        Rec down = new Rec(true, false);
        down.ntfyWorks = false;
        new Concerns(d3, logs.logger, a -> down).report(ntfyOnly, new Concerns.Letter(DAY + "-0000aa04", "Dee_4", "", "x"));
        Concerns c3 = new Concerns(d3, logs.logger, a -> slow);
        Thread t = new Thread(() -> c3.retry(ntfyOnly));
        t.start();
        check(sending.await(5, java.util.concurrent.TimeUnit.SECONDS), "the retry is sending");
        long t0 = System.nanoTime();
        int waiting = c3.waiting();
        List<String> names = c3.recentNames(DAY);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        t.join();
        check(ms < 1000 && waiting == 1 && names.equals(List.of("Dee_4")), "status is not held up by a retry (" + ms + " ms)");

        // CC2: a failure after the paper is published does not fail the run (that would publish it again)
        World w = new World(root.resolve("cc2"), true);
        Rec push = new Rec(true, true);
        EditionJob job = w.job(s -> new Fake(false, false), push, logs);
        Path done = w.data.resolve("mailbag-done.txt");
        Files.writeString(done, "");
        Files.setPosixFilePermissions(done, PosixFilePermissions.fromString("---------"));
        String r;
        try { r = job.run(DAY, true); }
        finally { Files.setPosixFilePermissions(done, PosixFilePermissions.fromString("rw-------")); }
        JsonObject le = JsonParser.parseString(Files.readString(w.data.resolve("last-edition.json"))).getAsJsonObject();
        check(r.startsWith("AI") && le.get("date").getAsString().equals(DAY.toString()) && le.get("notes").toString().contains("bookkeeping failed"),
                "bookkeeping failure after publishing is noted, not a failed try: " + r);
        check(push.all.stream().anyMatch(m -> m.contains("is out")), "the 'is out' push still goes");

        // CSP-2: a letter the desk flags reaches the owner even when publishing then fails
        World w2 = new World(root.resolve("csp2"), true);
        Rec push2 = new Rec(true, true);
        EditionJob job2 = w2.job(s -> new Fake(false, false), push2, logs);
        StatsSnapshotHelper.deleteTree(w2.web);
        Files.writeString(w2.web, "not a folder");
        boolean threw = false;
        try { job2.run(DAY, true); } catch (IOException e) { threw = true; }
        check(threw, "publishing failed");
        check(push2.ntfyBodies().contains(String.format(Concerns.NAMED, "Bob_22")), "desk-flagged letter pushed before publishing: " + push2.ntfyBodies());

        // CSP-3 + CC7: a preview keeps neither the desk's answer nor its stats copy
        World w3 = new World(root.resolve("prev"), true);
        EditionJob job3 = w3.job(s -> new Fake(false, false), new Rec(true, true), logs);
        Files.createDirectories(w3.data.resolve("preview/desk"));
        Files.writeString(w3.data.resolve("preview/desk/2026-11-01.json"), CANARY_NOTE);   // left by 2.0.1 before this fix
        String pr = job3.run(DAY, false);
        check(pr.contains("preview"), "preview ran: " + pr);
        check(!Files.exists(w3.data.resolve("preview/desk")), "preview keeps no desk answer (it quotes letters)");
        check(!Files.exists(w3.data.resolve("preview/stats")), "preview's stats copy is thrown away");
    }

    // ------------------------------------------------------------ review round 2
    static void reviewRound2(Path root) throws Exception {
        Logs logs = new Logs();
        // r2cc-1: the day is marked done the moment the paper is published, before the alerts and clean-up (a restart
        // in between must not print a second paper)
        World w = new World(root.resolve("done"), true);
        Rec push = new Rec(true, true);
        EditionJob job = w.job(s -> new Fake(false, false), push, logs);
        List<String> seen = new ArrayList<>();
        job.onPublished(d -> {
            boolean out = push.all.stream().anyMatch(m -> m.contains(" is out"));
            boolean live = Files.exists(w.web.resolve("index.html")) && Files.exists(w.data.resolve("today.json"));
            seen.add(d + " out-push-sent=" + out + " published=" + live + " done-file=" + Files.exists(w.data.resolve("mailbag-done.txt")));
        });
        job.run(DAY, true);
        check(seen.equals(List.of(DAY + " out-push-sent=false published=true done-file=false")),
                "day marked done right after publishing, before bookkeeping and alerts: " + seen);
        check(push.all.stream().anyMatch(m -> m.contains(" is out")), "the 'is out' push still goes");
        String r = new World(root.resolve("prev"), true).job(s -> new Fake(false, false), new Rec(true, true), logs).run(DAY, false);
        check(r.contains("preview"), "preview ran: " + r);
        List<String> previewSeen = new ArrayList<>();
        EditionJob pj = new World(root.resolve("prev2"), true).job(s -> new Fake(false, false), new Rec(true, true), logs);
        pj.onPublished(d -> previewSeen.add(d.toString()));
        pj.run(DAY, false);
        check(previewSeen.isEmpty(), "a preview never marks the day done");
        // r2-csp-2: the daily edition deletes mailroom files older than 7 days, even if nobody wrote a letter since
        World m = new World(root.resolve("mail"), false);
        Path old = m.data.resolve("mailroom/" + DAY.minusDays(8) + ".jsonl"), keep = m.data.resolve("mailroom/" + DAY.minusDays(7) + ".jsonl");
        Files.writeString(old, Mailroom.line(DAY.minusDays(8) + "-0000c0de", 1, "Bob_22", "letter", CANARY_TEXT + " i want to die", true));
        Files.writeString(keep, Mailroom.line(DAY.minusDays(7) + "-0000c0df", 1, "Bob_22", "letter", "the farm is cool", false));
        m.job(s -> new Fake(false, false), new Rec(true, true), logs).run(DAY, true);
        check(!Files.exists(old), "a mailroom file older than 7 days is deleted by the daily edition");
        check(Files.exists(keep), "a 7-day-old mailroom file is kept");
    }

    // ------------------------------------------------------------ the test world
    // ------------------------------------------------------------ review round 3
    static void reviewRound3(Path root) throws Exception {
        Logs logs = new Logs();
        // CSP-R3-2: concern-sent.txt (a player's name tied to a worried letter) is tidied by every retry, not only when a
        // new alert goes out; emptied, it is deleted; a retry never creates it
        Path d = Files.createDirectories(root.resolve("sent"));
        Settings helperOnly = new World(root.resolve("w"), false).settings(false, true, false);
        Concerns c = new Concerns(d, logs.logger, a -> null);
        Path sent = d.resolve("concern-sent.txt");
        LocalDate today = LocalDate.now();
        String old = today.minusDays(30) + "-0000abcd", recent = today.minusDays(2) + "-0000abce";
        Files.writeString(sent, old + " Kid_1\n" + recent + " Kid_2\n");
        c.retry(helperOnly);
        check(Files.readString(sent).equals(recent + " Kid_2\n"), "an old line goes at the next retry: " + Files.readString(sent));
        Files.writeString(sent, old + " Kid_1\n");
        c.retry(helperOnly);
        check(!Files.exists(sent), "nothing left: the file is deleted");
        c.retry(helperOnly);
        check(!Files.exists(sent), "a retry never creates it");
        // CSP-R3-1: names in game only when Discord is the only channel (its push carries no name)
        check(!Concerns.namesInGame(helperOnly.alerts()), "host helper: no names in game");
        check(!Concerns.namesInGame(new Settings.Alerts("https://ntfy.example/t", "", false)), "ntfy: no names in game");
        check(!Concerns.namesInGame(new Settings.Alerts("", "https://discord.example/h", true)), "Discord and the helper: no names in game");
        check(Concerns.namesInGame(new Settings.Alerts("", "https://discord.example/h", false)), "Discord only: names in game");
        // held-worried-letter-replaced: a worried letter held back by the per-player limit, then replaced by an ordinary
        // letter from the same player that day, is still reported at the edition (and the desk still never sees it)
        World w = new World(root.resolve("held"), false);
        String heldId = DAY + "-0000d0d0", laterId = DAY + "-0000d0d1";
        Files.writeString(w.data.resolve("mailroom").resolve(DAY + ".jsonl"),
                Mailroom.line(heldId, 1, "Bob_22", "letter", CANARY_WB + " i want to die", true)
                        + Mailroom.line(laterId, 2, "Bob_22", "letter", "nvm wrong chat, the farm is cool", false));
        Rec push = new Rec(true, false);
        w.job(s -> new Fake(false, false), push, logs).run(DAY, true);
        check(push.ntfyBodies().contains(String.format(Concerns.NAMED, "Bob_22")), "held worried letter reported at the edition: " + push.ntfyBodies());
        check(push.all.stream().noneMatch(m -> m.contains(CANARY_WB)), "its words in no push");
        try (Stream<Path> f = Files.list(w.data.resolve("concern"))) {
            check(f.anyMatch(p -> p.getFileName().toString().equals(heldId + ".json")), "helper file for the held letter");
        }
        push.all.clear();
        w.job(s -> new Fake(false, false), push, logs).run(DAY, true);
        check(push.all.stream().noneMatch(m -> m.contains("💛")), "reported once, not again on a re-run");
    }

    // ------------------------------------------------------------ 1A.3 (T10) hide-players everywhere; P10 owner.hours-ok (owner.name in another case)
    static void hiddenEverywhere(Path root) throws Exception {
        World w = new World(root, true);
        // an older paper named the player who is hidden now
        Files.writeString(w.data.resolve("editions").resolve(DAY.minusDays(2) + ".html"), "<h2>Old</h2><p>Bob_22 won the egg race.</p>");
        // the web log: one visit older than the 60-day limit, one from yesterday
        String visit = " 09:00:00][INFO] 203.0.113.9 \"GET /index.html HTTP/1.1\" 200 OK\n";
        Files.writeString(w.data.resolve("web-access.log"), "[" + DAY.minusDays(61) + visit + "[" + DAY.minusDays(1) + visit);
        // X1: another player's letter and tip name the hidden player; the AI must not read them
        String namedLetter = DAY + "-0000a11e", namedTip = DAY + "-0000a11d";
        Files.writeString(w.data.resolve("mailroom").resolve(DAY + ".jsonl"), Mailroom.line(namedLetter, 3, "Alice_1", "letter", "why does Bob_22 never come to my base anymore", false)
                + Mailroom.line(namedTip, 4, "Alice_1", "tip", "Bob_22 took my diamonds", false), java.nio.file.StandardOpenOption.APPEND);
        Settings d = w.settings(true, true, true);
        Settings s = new Settings(d.paper(), d.tone(), new Settings.Owner("alice_1", "The Owner's Desk", List.of(), false), new Settings.Privacy(List.of("bob_22"), List.of(), 40),
                d.schedule(), d.writer(), d.web(), d.features(), d.alerts());
        List<String> prompts = new ArrayList<>();
        int[] drafts = {0};
        Writer writer = new Writer() {
            @Override public String name() { return "capture"; }
            @Override public String write(String system, String user, Duration timeout) {
                prompts.add(system + user);
                if (system.contains("Players mailed the letters")) return "{\"printed\": [], \"held\": [], \"concern\": []}";
                // the first draft names the hidden player (it is not in the facts, so only a slip could do it): the check catches it
                return "<h2>The Test Post</h2><h3>Front page</h3><p>Alice_1 logged in and did a few things today, and the cows are nervous about it all.</p>"
                        + (drafts[0]++ == 0 ? "<p>BOB_22 waved.</p>" : "");
            }
        };
        Rec push = new Rec(true, false);
        Logs logs = new Logs();
        EditionJob job = new EditionJob(w.data, () -> s, logs.logger, x -> writer, a -> push, new Concerns(w.data, logs.logger, a -> push), w.live, w.root);
        String r = job.run(DAY, true);
        check(r.startsWith("AI") && r.contains("hidden player"), "a draft naming a hidden player is rewritten: " + r);
        String bag = Files.readString(w.data.resolve("mailbag.json")), doneIds = Files.readString(w.data.resolve("mailbag-done.txt"));
        check(bag.replace("check: named a hidden player", "").length() == bag.length() - 2 * "check: named a hidden player".length()
                && doneIds.contains(namedLetter) && doneIds.contains(namedTip), "X1 mail naming a hidden player is held and done, desk or no desk: " + bag);
        // a rewrite that is still off gets one more try; after three bad drafts the bad lines are cut
        for (int bad : new int[] {2, 3}) {
            int[] n = {0};
            Writer flaky = new Writer() {
                @Override public String name() { return "flaky"; }
                @Override public String write(String system, String user, Duration timeout) {
                    if (system.contains("Players mailed the letters")) return "{\"printed\": [], \"held\": [], \"concern\": []}";
                    return "<h2>The Test Post</h2><h3>Front page</h3><p>Alice_1 logged in and did a few things today, and the cows are nervous about it all.</p>"
                            + (n[0]++ < bad ? "<p>BOB_22 waved.</p>" : "");
                }
            };
            String r2 = new EditionJob(w.data, () -> s, logs.logger, x -> flaky, a -> push, new Concerns(w.data, logs.logger, a -> push), w.live, w.root).run(DAY, true);
            // three bad drafts: the one bad line is cut (Salvage) and the rest passes the whole check
            check(n[0] == 3 && r2.startsWith("AI") && (bad == 2 || r2.contains("dropped the lines with problems")) && !r2.toLowerCase().contains("bob_22"),
                    "two rewrites at most (" + bad + " bad drafts): " + r2 + " after " + n[0] + " drafts");
        }
        for (String p : prompts) { int i = p.toLowerCase().indexOf("bob_22"); check(i < 0, "no prompt names the hidden player: " + (i < 0 ? "" : p.substring(Math.max(0, i - 200), Math.min(p.length(), i + 60)))); }
        for (String f : List.of("editions/" + DAY + ".html", "data/" + DAY + ".json", "today.json", "mailbag.json"))
            check(!Files.readString(w.data.resolve(f)).toLowerCase().contains("bob_22"), "hidden player not in " + f);
        String players = Files.readString(w.web.resolve("players.html")), owner = Files.readString(w.web.resolve("owner.html"));
        check(!players.toLowerCase().contains("bob_22") && !owner.toLowerCase().contains("bob_22"), "hidden player on no page");
        String front = Files.readString(w.web.resolve("index.html"));
        check(!front.toLowerCase().contains("bob_22") && front.contains("a player won the egg race."), "hidden player not in the back issues on the front page");
        check(Files.readString(w.data.resolve("editions").resolve(DAY.minusDays(2) + ".html")).contains("Bob_22"), "the old paper's file is not rewritten");
        check(Files.readString(w.data.resolve("web-access.log")).equals("[" + DAY.minusDays(1) + visit), "the daily tidy drops web log lines older than 60 days");
        // the one line that turns the web log off with readership (EditionJob.start needs a server, so its text is checked)
        String startSrc = Files.readString(Path.of("src/main/java/io/github/eastmadc/masthead/pipeline/EditionJob.java"));
        startSrc = startSrc.substring(startSrc.indexOf("public void start()"), startSrc.indexOf("public void stop()"));
        check(startSrc.contains("Path accessLog = WebServer.accessLog(data, s.features().readership(), ") && startSrc.contains("s.web().port(), accessLog, zone(s),")
                && !startSrc.contains("web-access.log") && !startSrc.contains("BUILTIN_ACCESS_LOG"), "the web server only gets a log file when features.readership is on");
        // M5: the job hands the prompt its list of owner notes, so "N quiet players left out of the AI's data" goes out with the rest
        // (a pipeline run would need a 60,000-character data set, so the text is checked)
        String jobSrc = Files.readString(Path.of("src/main/java/io/github/eastmadc/masthead/pipeline/EditionJob.java"));
        check(jobSrc.contains("String full = StylePrompt.prompt(s, facts, notes),") && !jobSrc.contains("StylePrompt.prompt(s, facts)"), "the prompt's owner note reaches the job's notes");
        // owner.hours-ok: false: the owner's hours are on no page and not in the facts
        check(!owner.contains("hours") && !players.contains("hours played</dt>"), "owner's hours kept off the pages");
        JsonObject facts = JsonParser.parseString(Files.readString(w.data.resolve("data/" + DAY + ".json"))).getAsJsonObject();
        check(!facts.getAsJsonObject("season_totals").getAsJsonObject("Alice_1").has("play_min")
                && !facts.getAsJsonObject("owner").getAsJsonObject("season").has("play_min"), "owner's minutes not in the facts");
        for (String p : prompts) check(!p.contains("\"play_min\":180"), "owner's minutes not in any prompt");
        // owner.name "alice_1" is spelled as in game everywhere the writer reads it
        for (String p : prompts) check(!p.contains("alice_1"), "the owner spelled as in game in every prompt");
        check(prompts.stream().anyMatch(p -> p.contains("The owner, Alice_1")), "the prompt names the owner as in game");
    }

    // M3: privacy.hide-players survives a name change. state.yml remembers the UUID behind a hidden name; the list in effect
    // (Masthead.effective) then holds every name that UUID has had, for as long as one of them is in config.yml.
    static void hiddenRename(Path root) throws Exception {
        World w = new World(root, false);   // its state.yml is as alpha.34 wrote it: no hidden-uuids yet
        String b = "00000000-0000-0000-0000-00000000000b", c = "00000000-0000-0000-0000-00000000000c";
        String cache = Files.readString(w.root.resolve("usercache.json")).replace("]", ",{\"uuid\":\"" + c + "\",\"name\":\"Juno_B\"}]");
        Path prev = w.data.resolve("stats").resolve(DAY.minusDays(1).toString());
        Files.writeString(prev.resolve("usercache.json"), cache);
        Files.writeString(prev.resolve(c + ".json"), World.stats(1200 * 60, 1));
        Files.writeString(w.live.resolve(c + ".json"), World.stats(1200 * 180, 3));
        // the day of the name change: the hidden Bob_22 (same UUID, same stats file) joined as Bob_23
        Files.writeString(w.root.resolve("usercache.json"), cache.replace("Bob_22", "Bob_23"));
        long noon = LocalDateTime.of(DAY.minusDays(1), LocalTime.NOON).toEpochSecond(ZoneOffset.UTC);
        Files.writeString(w.data.resolve("events").resolve(DAY.minusDays(1) + ".jsonl"), "{\"t\":" + noon + ",\"k\":\"join\",\"p\":\"Alice_1\"}\n"
                + "{\"t\":" + noon + ",\"k\":\"join\",\"p\":\"Bob_23\"}\n{\"t\":" + noon + ",\"k\":\"join\",\"p\":\"Juno_B\"}\n"
                + "{\"t\":" + (noon + 9) + ",\"k\":\"death\",\"p\":\"Bob_23\",\"msg\":\"Bob_23 fell from a high place\",\"killer\":\"\",\"v\":2}\n");
        Settings d = w.settings(true, false, false);
        Settings[] cfg = {new Settings(d.paper(), d.tone(), d.owner(), new Settings.Privacy(List.of("bob_22", "Juno_B"), List.of(), 40), d.schedule(), d.writer(), d.web(),
                d.features(), d.alerts())};
        List<String> prompts = new ArrayList<>();
        Writer writer = new Writer() {
            @Override public String name() { return "capture"; }
            @Override public String write(String system, String user, Duration timeout) {
                prompts.add(system + user);
                return "<h2>The Test Post</h2><h3>Front page</h3><p>Alice_1 logged in and did a few things today, and the cows are nervous about it all.</p>";
            }
        };
        Rec push = new Rec(true, false);
        Logs logs = new Logs();
        // the plugin's settings: config.yml with state.yml on top
        EditionJob job = new EditionJob(w.data, () -> io.github.eastmadc.masthead.Masthead.effective(cfg[0], w.data), logs.logger, x -> writer, a -> push,
                new Concerns(w.data, logs.logger, a -> push), w.live, w.root);
        java.util.function.BiFunction<LocalDate, String, String> named = (day, name) -> {
            StringBuilder where = new StringBuilder();
            try {
                for (String p : prompts) if (p.toLowerCase().contains(name)) where.append(" prompt");
                for (Path f : List.of(w.data.resolve("editions/" + day + ".html"), w.data.resolve("data/" + day + ".json"), w.data.resolve("today.json"),
                        w.web.resolve("players.html"), w.web.resolve("index.html")))
                    if (Files.readString(f).toLowerCase().contains(name)) where.append(" ").append(f.getFileName());
            } catch (IOException e) { where.append(" ").append(e); }
            return where.toString();
        };
        String line = "WARNING hidden player Bob_22 is now called Bob_23; still hidden. Add Bob_23 to privacy.hide-players.";
        check(job.run(DAY, true).startsWith("AI") && !prompts.isEmpty(), "the paper ran on the day of the name change");
        for (String n : List.of("bob_22", "bob_23", "juno_b"))
            check(named.apply(DAY, n).isEmpty(), "the day of the name change: " + n + " is named nowhere:" + named.apply(DAY, n));
        check(logs.lines.stream().filter(line::equals).count() == 1, "the console says it once: " + logs.lines);
        YamlConfiguration st = State.load(w.data);
        check(st.getStringList("hidden-uuids." + b).equals(List.of("Bob_22", "Bob_23")) && st.getStringList("hidden-uuids." + c).equals(List.of("Juno_B"))
                && st.getConfigurationSection("hidden-uuids").getKeys(false).size() == 2 && st.getInt("qr-map-id") == 7,
                "state.yml remembers the hidden players' UUIDs only, and keeps what it had: " + st.saveToString());
        // the day after: the last snapshot knows only the new name. The second hidden player changes name today.
        Files.writeString(w.root.resolve("usercache.json"), Files.readString(w.root.resolve("usercache.json")).replace("Juno_B", "Juno_C"));
        for (String u : List.of("a", "b", "c")) Files.writeString(w.live.resolve("00000000-0000-0000-0000-00000000000" + u + ".json"), World.stats(1200 * 240, 4));
        Files.writeString(w.data.resolve("events").resolve(DAY + ".jsonl"), "{\"t\":" + (noon + 86400) + ",\"k\":\"join\",\"p\":\"Alice_1\"}\n"
                + "{\"t\":" + (noon + 86400) + ",\"k\":\"join\",\"p\":\"Bob_23\"}\n{\"t\":" + (noon + 86400) + ",\"k\":\"join\",\"p\":\"Juno_C\"}\n");
        prompts.clear();
        LocalDate next = DAY.plusDays(1);
        check(job.run(next, true).startsWith("AI") && !prompts.isEmpty(), "the paper ran on the day after");
        for (String n : List.of("bob_22", "bob_23", "juno_b", "juno_c"))
            check(named.apply(next, n).isEmpty(), "the day after: " + n + " is named nowhere:" + named.apply(next, n));
        check(logs.lines.stream().filter(line::equals).count() == 1
                && logs.lines.contains("WARNING hidden player Juno_B is now called Juno_C; still hidden. Add Juno_C to privacy.hide-players."), "one console line for each name change: " + logs.lines);
        // in game: a join is compared by UUID too (a third name), and a player who is not hidden is not remembered
        Settings now = io.github.eastmadc.masthead.Masthead.effective(cfg[0], w.data);
        check(now.privacy().hidePlayers().equals(List.of("bob_22", "Juno_B", "Bob_23", "Juno_C")), "one list for every name check: " + now.privacy().hidePlayers());
        Settings joined = io.github.eastmadc.masthead.Masthead.follow(now, w.data, List.of(Map.of(b, "Bob_24", "00000000-0000-0000-0000-00000000000a", "Alice_1")), true, logs.logger);
        check(joined.privacy().hidePlayers().contains("Bob_24") && !State.load(w.data).contains("hidden-uuids.00000000-0000-0000-0000-00000000000a"),
                "a hidden player who joins under a new name is hidden at once: " + joined.privacy().hidePlayers());
        check(io.github.eastmadc.masthead.Masthead.follow(joined, w.data, List.of(Map.of(b, "Bob_24")), true, logs.logger) == joined, "nothing new: the same settings");
        // taking the name out of config.yml un-hides the player, as before (and shows these checks can see a name)
        cfg[0] = new Settings(d.paper(), d.tone(), d.owner(), new Settings.Privacy(List.of("Juno_B"), List.of(), 40), d.schedule(), d.writer(), d.web(), d.features(), d.alerts());
        check(io.github.eastmadc.masthead.Masthead.effective(cfg[0], w.data).privacy().hidePlayers().equals(List.of("Juno_B", "Juno_C")), "removed from config.yml: no longer hidden");
        job.run(next, true);
        check(named.apply(next, "bob_23").contains("players.html") && named.apply(next, "juno_c").isEmpty(), "the un-hidden player has a card again:" + named.apply(next, "bob_23"));
        // a preview keeps nothing, and still hides the new name
        World pw = new World(root.resolve("preview"), false);
        Files.writeString(pw.root.resolve("usercache.json"), Files.readString(pw.root.resolve("usercache.json")).replace("Bob_22", "Bob_23"));
        Settings ps = new Settings(d.paper(), d.tone(), d.owner(), new Settings.Privacy(List.of("Bob_22"), List.of(), 40), d.schedule(), d.writer(),
                new Settings.Web("folder", "0.0.0.0", 8150, pw.web.toString(), "http://example.test/news/", ""), d.features(), d.alerts());
        String stateBefore = Files.readString(pw.data.resolve("state.yml"));
        new EditionJob(pw.data, () -> ps, logs.logger, x -> writer, a -> push, new Concerns(pw.data, logs.logger, a -> push), pw.live, pw.root).run(DAY, false);
        check(Files.readString(pw.data.resolve("state.yml")).equals(stateBefore) && !State.load(pw.data).contains("hidden-uuids")
                && !Files.readString(pw.data.resolve("preview/players.html")).contains("Bob_23") && Files.readString(pw.data.resolve("preview/players.html")).contains("Alice_1"),
                "a preview hides the new name and writes nothing to state.yml");
        // the name changed before the last stats copy was taken (state.yml lost, or a change from before 1.0): an older copy still shows it
        World ow = new World(root.resolve("older"), false);
        Path older = Files.createDirectories(ow.data.resolve("stats").resolve(DAY.minusDays(2).toString())), last = ow.data.resolve("stats").resolve(DAY.minusDays(1).toString());
        Files.copy(last.resolve("usercache.json"), older.resolve("usercache.json"));
        for (Path f : List.of(last.resolve("usercache.json"), ow.root.resolve("usercache.json"))) Files.writeString(f, Files.readString(f).replace("Bob_22", "Bob_23"));
        Settings os = new Settings(d.paper(), d.tone(), d.owner(), new Settings.Privacy(List.of("Bob_22"), List.of(), 40), d.schedule(), d.writer(),
                new Settings.Web("folder", "0.0.0.0", 8150, ow.web.toString(), "http://example.test/news/", ""), d.features(), d.alerts());
        prompts.clear();
        new EditionJob(ow.data, () -> os, logs.logger, x -> writer, a -> push, new Concerns(ow.data, logs.logger, a -> push), ow.live, ow.root).run(DAY, true);
        check(!prompts.isEmpty() && prompts.stream().noneMatch(p -> p.contains("Bob_23")) && !Files.readString(ow.web.resolve("players.html")).contains("Bob_23")
                && Files.readString(ow.web.resolve("players.html")).contains("Alice_1") && State.load(ow.data).getStringList("hidden-uuids." + b).equals(List.of("Bob_22", "Bob_23")),
                "a name change two stats copies back is followed: " + State.load(ow.data).saveToString());
        // in game the calls need a server, so their text is checked: a join (before any other plugin's handler), a reload with
        // the player online, and the paper just written each reach follow(); the building recorder gets the new list
        String mh = Files.readString(Path.of("src/main/java/io/github/eastmadc/masthead/Masthead.java"));
        String join = mh.substring(mh.indexOf("@org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)"), mh.indexOf("job.onPublished("));
        check(join.contains("onJoin(org.bukkit.event.player.PlayerJoinEvent e) { rehide(Map.of(e.getPlayer().getUniqueId().toString(), e.getPlayer().getName())); }")
                && mh.indexOf("registerEvents(new org.bukkit.event.Listener() {") < mh.indexOf(join) && mh.indexOf("registerEvents(new org.bukkit.event.Listener() {") > mh.indexOf("private void start()"),
                "a join is compared by UUID, first of all handlers");
        String ls = mh.substring(mh.indexOf("private boolean loadSettings()"), mh.indexOf("private void upgradeConfig()"));
        check(ls.contains("for (Player p : Bukkit.getOnlinePlayers())") && ls.contains("settings = follow(settings, getDataFolder().toPath(), List.of(Map.of(p.getUniqueId().toString(), p.getName())), true, getLogger());"),
                "a reload looks at the players who are online");
        String rh = mh.substring(mh.indexOf("private void rehide("), mh.indexOf("private boolean mailroomNeedsChannel()"));
        check(rh.contains("follow(settings, getDataFolder().toPath(), List.of(seen), true, getLogger())") && rh.contains("settings = s;") && rh.contains("construction.apply(s);"),
                "rehide puts the new list in use, for the building recorder too");
        String re = mh.substring(mh.indexOf("private void runEdition("), mh.indexOf("private void admin("));
        check(re.contains("if (ok) try { Bukkit.getScheduler().runTask(this, () -> rehide(Map.of())); }"), "after a paper the names its snapshot showed are in use in game");
    }

    // ------------------------------------------------------------ style is guidance, never a gate
    static void styleOnly(Path root) throws Exception {
        World w = new World(root, true);
        int[] drafts = {0};
        List<String> users = new ArrayList<>();
        Writer writer = new Writer() {
            @Override public String name() { return "style"; }
            @Override public String write(String system, String user, Duration timeout) {
                if (system.contains("Players mailed the letters")) return "{\"printed\": [], \"held\": [], \"concern\": []}";
                drafts[0]++;
                users.add(user);
                // the "not a X, that's a Y" shape twice: a style problem, and nothing else wrong
                return "<h2>The Test Post</h2><h3>Front page</h3><p>Alice_1 logged in today. That's not a visit, that's a statement.</p>"
                        + "<p>Bob_22 logged in too. That's not a cameo, that's a residency.</p>";
            }
        };
        Logs logs = new Logs();
        String r = w.job(x -> writer, new Rec(true, false), logs).run(DAY, true);
        JsonObject le = JsonParser.parseString(Files.readString(w.data.resolve("last-edition.json"))).getAsJsonObject();
        String notes = le.get("notes").toString();
        check(r.startsWith("AI") && drafts[0] == 1, "a style-only problem publishes the first draft, no rewrite: " + r + " after " + drafts[0] + " drafts");
        check(notes.contains("style: \\\"not a X, that's a Y\\\" (more than once)") && !notes.contains("draft needed fixing"), "the style problem is noted as style: " + notes);
        check(users.stream().noneMatch(u -> u.contains("PREVIOUS DRAFT")), "no rewrite note was sent");
        check(Files.readString(w.data.resolve("editions/" + DAY + ".html")).contains("that's a residency"), "the first draft is the paper");
    }

    // ------------------------------------------------------------ 1A.6 (T13) state.yml: read once, kept in memory, saved safely
    static void stateInMemory(Path root) throws Exception {
        Path d = Files.createDirectories(root);
        Files.writeString(d.resolve("state.yml"), "qr-map-id: 7\n");
        check(State.load(d).getInt("qr-map-id") == 7, "read from disk the first time");
        Files.writeString(d.resolve("state.yml"), "qr-map-id: 99\n");
        check(State.load(d).getInt("qr-map-id") == 7, "then from memory (no re-read every 30 s)");
        State.load(d).set("qr-map-id", 1);
        check(State.load(d).getInt("qr-map-id") == 7, "a loaded copy cannot change the state");
        check(State.set(d, "last-edition", "2026-11-10"), "saved");
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(d.resolve("state.yml").toFile());
        check(disk.getInt("qr-map-id") == 7 && disk.getString("last-edition").equals("2026-11-10") && !Files.exists(d.resolve("state.yml.new")),
                "the whole state is written in one step");
        // a save that fails still counts for this run (the folder cannot be written)
        Files.setPosixFilePermissions(d, PosixFilePermissions.fromString("r-x------"));
        try {
            check(!State.set(d, "last-edition", "2026-11-11"), "save fails");
            check(State.load(d).getString("last-edition").equals("2026-11-11"), "but the value is kept in memory");
        } finally {
            Files.setPosixFilePermissions(d, PosixFilePermissions.fromString("rwx------"));
        }
        // threads: many writers at once never lose a key
        Thread[] ts = new Thread[8];
        for (int i = 0; i < ts.length; i++) {
            int n = i;
            ts[i] = new Thread(() -> { for (int k = 0; k < 50; k++) State.set(d, "k" + n, k); });
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        YamlConfiguration after = YamlConfiguration.loadConfiguration(d.resolve("state.yml").toFile());
        for (int i = 0; i < ts.length; i++) check(after.getInt("k" + i) == 49, "key k" + i + " kept");
        check(after.getInt("qr-map-id") == 7, "the QR map id survives every write");
        // CC-8: /masthead reload names the settings that need a restart instead of quietly ignoring them
        Settings a = Settings.defaults(), b2 = new Settings(a.paper(), a.tone(), a.owner(), a.privacy(), a.schedule(), a.writer(), a.web(),
                new Settings.Features(true, false, false, true, 3, ""), a.alerts());
        check(io.github.eastmadc.masthead.Masthead.restartNeeded(a, b2, Map.of("news", List.of()), Map.of("news", List.of("paper")))
                .equals(List.of("features.blotter", "features.readership", "commands")), "restart needed for blotter, readership, commands");
        check(io.github.eastmadc.masthead.Masthead.restartNeeded(a, a, Map.of(), Map.of()).isEmpty(), "nothing to restart for");
        // I1: /masthead reload says that a player hidden just now is still in the paper that is already out
        Settings h1 = new Settings(a.paper(), a.tone(), a.owner(), new Settings.Privacy(List.of("Bob_22"), List.of(), 40), a.schedule(), a.writer(), a.web(), a.features(), a.alerts()),
                h2 = new Settings(a.paper(), a.tone(), a.owner(), new Settings.Privacy(List.of("bob_22", "Kai_Ore"), List.of(), 40), a.schedule(), a.writer(), a.web(), a.features(), a.alerts());
        String hid = "Newly hidden players stay in today's paper and Player Cards until the next paper. Run /masthead now to take them off now.";
        check(io.github.eastmadc.masthead.Masthead.newlyHidden(a, h1).equals(hid) && io.github.eastmadc.masthead.Masthead.newlyHidden(h1, h2).equals(hid),
                "reload: hide-players gained a name: the line is shown");
        check(io.github.eastmadc.masthead.Masthead.newlyHidden(h1, h1).isEmpty() && io.github.eastmadc.masthead.Masthead.newlyHidden(h2, h1).isEmpty()
                && io.github.eastmadc.masthead.Masthead.newlyHidden(h1, a).isEmpty(), "reload: the same list, another spelling or a shorter list: no line");
        String web = io.github.eastmadc.masthead.Masthead.webStatus(a, false, "");
        check(web.equals(a.web().mode()), "status with no web address has no stray space: '" + web + "'");
        // C3: a word /masthead does not know gets the usage line, never the status (the command needs a server, so its text is checked)
        String mhSrc = Files.readString(Path.of("src/main/java/io/github/eastmadc/masthead/Masthead.java"));
        String adminSrc = mhSrc.substring(mhSrc.indexOf("private void admin("), mhSrc.indexOf("private void construction("));
        String tabSrc = Files.readString(Path.of("src/main/java/io/github/eastmadc/masthead/game/InGame.java"));
        tabSrc = tabSrc.substring(tabSrc.indexOf("Stream.of(\"reload\""), tabSrc.indexOf(")", tabSrc.indexOf("Stream.of(\"reload\"")));
        java.util.regex.Matcher sub = java.util.regex.Pattern.compile("\"([a-z]+)\"").matcher(tabSrc);
        int subs = 0;
        while (sub.find()) {
            subs++;
            check(adminSrc.contains("case \"" + sub.group(1) + "\" ->") && io.github.eastmadc.masthead.Masthead.USAGE.contains(" " + sub.group(1)),
                    "/masthead " + sub.group(1) + " has its own case and is in the usage line");
        }
        check(subs == 6 && adminSrc.contains("default -> sender.sendMessage(Component.text(USAGE,") && !adminSrc.contains("default -> status("),
                "an unknown /masthead word prints the usage line, not the status");
        // C4: /masthead reload also shows the writer line that status shows, so "Settings OK" is never the whole answer
        String reloadSrc = adminSrc.substring(adminSrc.indexOf("case \"reload\" ->"), adminSrc.indexOf("case \"now\" ->"));
        check(reloadSrc.indexOf("\"Reloaded. \"") > 0 && reloadSrc.indexOf("sender.sendMessage(Component.text(writerLine(),") > reloadSrc.indexOf("\"Reloaded. \"")
                && SettingsLoader.writerLine(load("writer:\n  mode: anthropic\n").writer(), null).equals("Writer: anthropic (no API key set: plain edition)"),
                "reload shows the writer line (no API key: plain edition)");
        // I1: the reload compares the list from before with the new one and shows the line; not on the first good reload
        // after a broken start, when no paper was running under the old list
        check(reloadSrc.indexOf("Settings before = job == null ? null : settings;") > 0 && reloadSrc.indexOf("Settings before = job == null ? null : settings;") < reloadSrc.indexOf("loadSettings()")
                && reloadSrc.indexOf("String hid = before == null ? \"\" : newlyHidden(before, settings);") > reloadSrc.indexOf("\"Reloaded. \"")
                && reloadSrc.contains("if (!hid.isEmpty()) {") && reloadSrc.indexOf("sender.sendMessage(Component.text(hid,") > reloadSrc.indexOf("newlyHidden(before, settings)"),
                "reload shows the newly-hidden line");
        // C6: status share names the server software that runs (Purpur is not "Paper")
        String statusSrc = mhSrc.substring(mhSrc.indexOf("private void status("), mhSrc.indexOf("YamlConfiguration state()"));
        check(statusSrc.contains("getPluginMeta().getVersion() + \", \" + Bukkit.getName() + \" \" + Bukkit.getVersion()") && !statusSrc.contains("\", Paper \""),
                "status share takes the server's name from the server");
        // W2: status shows a locked spend ledger (Spend.locked() has its own test), for the paid writers and not while a paper is being written
        check(statusSrc.contains("String ledger = running.get() || !List.of(\"anthropic\", \"openai-compatible\").contains(s.writer().mode()) ? null")
                && statusSrc.contains("resolve(\"data\").resolve(\"spend.json\"), s.writer().monthlyBudgetUsd(), null).locked();")
                && statusSrc.contains("if (ledger != null) sender.sendMessage(Component.text(ledger, NamedTextColor.RED));"), "status shows a locked spend ledger");
        // CC-9: private files are 600 from the moment they exist, and written in one step
        Path priv = d.resolve("ip-players.txt");
        Housekeeping.writePrivate(priv, "1.2.3.4 Kid_1\n");
        Housekeeping.writePrivate(priv, "# 2026-11-10\n1.2.3.4 Kid_1\n");
        check(PosixFilePermissions.toString(Files.getPosixFilePermissions(priv)).equals("rw-------") && Files.readString(priv).startsWith("# 2026")
                && !Files.exists(d.resolve("ip-players.txt.new")), "written 600, in one step");
        // a new install: a data folder is owner-only from the moment it is made; one that is already there keeps its mode
        Path fresh = root.resolve("fresh"), open = Files.createDirectories(root.resolve("open"));
        Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxr-xr-x"));
        Housekeeping.createPrivateDirs(fresh.resolve("events"));
        Housekeeping.createPrivateDirs(open);
        check(perms(fresh).equals("rwx------") && perms(fresh.resolve("events")).equals("rwx------") && perms(open).equals("rwxr-xr-x"),
                "new folders are made 700, an old one is left alone: " + perms(fresh.resolve("events")) + " " + perms(open));
        new io.github.eastmadc.masthead.writer.Spend(open.resolve("data").resolve("spend.json"), 1, m -> { }).add(0.01);
        check(perms(open.resolve("data")).equals("rwx------"), "data/ made by the spend ledger is 700: " + perms(open.resolve("data")));
        // the stats snapshot: a copy that fails halfway leaves no stats/DATE, so the next run takes it again
        Path live = Files.createDirectories(root.resolve("live")), data = Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("usercache.json"), "[]");
        Files.writeString(live.resolve("00000000-0000-0000-0000-00000000000a.json"), World.stats(100, 1));
        Path bad = live.resolve("00000000-0000-0000-0000-00000000000b.json");
        Files.writeString(bad, World.stats(100, 1));
        Files.setPosixFilePermissions(bad, PosixFilePermissions.fromString("---------"));
        try {
            io.github.eastmadc.masthead.collect.StatsSnapshot.copy(live, root, data, DAY);
            check(false, "the copy should fail on an unreadable file");
        } catch (IOException expected) {
            check(!Files.exists(data.resolve("stats").resolve(DAY.toString())), "no half snapshot under stats/DATE");
            try (Stream<Path> left = Files.list(data.resolve("stats"))) { check(left.findAny().isEmpty(), "the half copy is removed"); }
        }
        Files.setPosixFilePermissions(bad, PosixFilePermissions.fromString("rw-------"));
        check(io.github.eastmadc.masthead.collect.StatsSnapshot.copy(live, root, data, DAY) == 2
                && Files.isRegularFile(data.resolve("stats").resolve(DAY.toString()).resolve(bad.getFileName())), "the next run takes the whole snapshot");
        check(perms(data.resolve("stats")).equals("rwx------"), "stats/ made by the first snapshot is 700: " + perms(data.resolve("stats")));
    }

    static final class World {
        final Path root, data, live, web;
        String normalId;

        World(Path root, boolean letters) throws IOException {
            this.root = Files.createDirectories(root);
            this.data = Files.createDirectories(root.resolve("plugins/Masthead"));
            this.live = Files.createDirectories(root.resolve("world/stats"));
            this.web = Files.createDirectories(root.resolve("web-out"));
            String cache = "[{\"uuid\":\"00000000-0000-0000-0000-00000000000a\",\"name\":\"Alice_1\"},{\"uuid\":\"00000000-0000-0000-0000-00000000000b\",\"name\":\"Bob_22\"}]";
            Files.writeString(root.resolve("usercache.json"), cache);
            Path prev = Files.createDirectories(data.resolve("stats").resolve(DAY.minusDays(1).toString()));
            Files.writeString(prev.resolve("usercache.json"), cache);
            for (String u : List.of("a", "b")) {
                Files.writeString(prev.resolve("00000000-0000-0000-0000-00000000000" + u + ".json"), stats(1200 * 60, 1));
                Files.writeString(live.resolve("00000000-0000-0000-0000-00000000000" + u + ".json"), stats(1200 * 180, 3));
            }
            Files.createDirectories(data.resolve("editions"));
            Files.writeString(data.resolve("editions").resolve(DAY.minusDays(1) + ".html"), "<span class=\"eyebrow\">Daily edition</span><h2>Yesterday</h2><p>old news here</p>");
            Files.writeString(data.resolve("today.json"), "{\"date\": \"" + DAY.minusDays(1) + "\"}");
            Files.writeString(data.resolve("state.yml"), "last-edition: '" + DAY.minusDays(1) + "'\nqr-map-id: 7\nmailroom-since: '2026-11-01'\n");
            long noon = LocalDateTime.of(DAY.minusDays(1), LocalTime.NOON).toEpochSecond(ZoneOffset.UTC);
            Files.createDirectories(data.resolve("events"));
            Files.writeString(data.resolve("events").resolve(DAY.minusDays(1) + ".jsonl"),
                    "{\"t\":" + noon + ",\"k\":\"join\",\"p\":\"Alice_1\"}\n{\"t\":" + noon + ",\"k\":\"join\",\"p\":\"Bob_22\"}\n");
            Files.createDirectories(data.resolve("mailroom"));
            normalId = DAY + "-0000b0b0";
            if (letters)
                Files.writeString(data.resolve("mailroom").resolve(DAY + ".jsonl"),
                        Mailroom.line(DAY + "-0000a11c", 1, "Alice_1", "letter", CANARY_WB + " i want to die for real", true)
                                + Mailroom.line(normalId, 2, "Bob_22", "letter", CANARY_TEXT + " everything is bad lately", false));
        }

        static String stats(int play, int deaths) {
            return "{\"stats\":{\"minecraft:custom\":{\"minecraft:play_time\":" + play + ",\"minecraft:deaths\":" + deaths + "},\"minecraft:killed\":{\"minecraft:cow\":2}}}";
        }

        Settings settings(boolean ntfy, boolean helper, boolean discord) {
            Settings d = Settings.defaults();
            return new Settings(new Settings.Paper("The Test Post", "Testland", "", "a small-town paper", "friends", "Play of the Day", "Fumble of the Day", "chicken"),
                    d.tone(), d.owner(), new Settings.Privacy(List.of(), List.of(), 40), new Settings.Schedule(LocalTime.of(5, 30), "UTC"),
                    new Settings.WriterCfg("command", d.writer().model(), "medium", "", List.of("x"), 60, 60),
                    new Settings.Web("folder", "0.0.0.0", 8150, web.toString(), "http://example.test/news/", ""),
                    new Settings.Features(true, true, false, true, 3, ""),
                    new Settings.Alerts(ntfy ? "https://ntfy.example/topic" : "", discord ? "https://discord.example/hook" : "", helper));
        }

        EditionJob job(Function<Settings, Writer> writers, Push push, Logs logs) {
            Settings s = settings(true, true, true);
            Concerns c = new Concerns(data, logs.logger, a -> push);
            return new EditionJob(data, () -> s, logs.logger, writers, a -> push, c, live, root);
        }
    }

    /** A writer that answers the edition and the editor desk. deskDown: the desk call fails. broken: a LinkageError. */
    static final class Fake implements Writer {
        final boolean deskDown, broken;
        int calls;

        Fake(boolean deskDown, boolean broken) { this.deskDown = deskDown; this.broken = broken; }

        @Override public String name() { return "fake"; }

        @Override public String write(String system, String user, Duration timeout) throws Exception {
            calls++;
            if (broken) throw new NoClassDefFoundError("com/anthropic/client/Missing");
            if (system.contains("Players mailed the letters")) {
                if (deskDown) throw new IOException("the helper did not answer");
                check(!user.contains(CANARY_WB), "the desk never sees a worried letter");
                return "{\"printed\": [], \"held\": [], \"concern\": [{\"id\": \"" + DAY + "-0000b0b0\", \"note\": \"" + CANARY_NOTE + " sounds sad\"}]}";
            }
            return "<h2>The Test Post</h2><h3>Front page</h3><p>Alice_1 and Bob_22 logged in and did a few things. Nobody won anything big, and the cows are nervous."
                    + "</p><p>" + (stamp == null ? "published draft" : stamp) + "</p>";
        }
    }

    static String stamp = null;

    /** Records every push: "channel|title|body". */
    static final class Rec implements Push {
        final boolean ntfy, discord;
        boolean ntfyWorks = true;
        final List<String> all = new ArrayList<>();

        Rec(boolean ntfy, boolean discord) { this.ntfy = ntfy; this.discord = discord; }

        @Override public boolean hasNtfy() { return ntfy; }
        @Override public boolean hasDiscord() { return discord; }
        @Override public boolean sendNtfy(String title, String body, String priority, List<String> tags) {
            all.add("ntfy|" + title + "|" + body);
            return ntfyWorks;
        }
        @Override public boolean sendDiscord(String title, String body) {
            all.add("discord|" + title + "|" + body);
            return true;
        }

        List<String> ntfyBodies() { return all.stream().filter(m -> m.startsWith("ntfy|")).map(m -> m.substring(m.indexOf('|', 5) + 1)).toList(); }
        List<String> discordBodies() { return all.stream().filter(m -> m.startsWith("discord|")).map(m -> m.substring(m.indexOf('|', 8) + 1)).toList(); }
    }

    /** Console lines. */
    static final class Logs {
        final List<String> lines = new ArrayList<>();
        final Logger logger = Logger.getAnonymousLogger();

        Logs() {
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override public void publish(LogRecord r) { synchronized (lines) { lines.add(r.getLevel() + " " + r.getMessage()); } }
                @Override public void flush() { }
                @Override public void close() { }
            });
        }
    }

    // ------------------------------------------------------------ helpers
    /** Review fix 1: when the kinds desk may call the writer (EditionJob.kindsWriter). */
    static void kindsGate() {
        Settings ex = load("features:\n  construction: true\nwriter:\n  mode: exchange\n"), paid = load("features:\n  construction: true\nwriter:\n  mode: anthropic\n  api-key: \"k\"\n");
        Writer w = new Fake(false, false);
        int[] asked = {0};
        java.util.function.BooleanSupplier low = () -> { asked[0]++; return true; }, room = () -> false;
        List<String> notes = new ArrayList<>();
        check(EditionJob.kindsWriter(ex, w, true, true, room, notes) == w && notes.isEmpty(), "kinds: a published paper asks the writer");
        check(EditionJob.kindsWriter(ex, w, false, true, room, notes) == null, "kinds: a preview never asks");
        check(EditionJob.kindsWriter(ex, new io.github.eastmadc.masthead.writer.NoWriter(), true, true, room, notes) == null, "kinds: the plain retry (NoWriter) never asks");
        check(EditionJob.kindsWriter(ex, w, true, false, room, notes) == null && EditionJob.kindsWriter(load("writer:\n  mode: exchange\n"), w, true, true, room, notes) == null,
                "kinds: no construction, no call");
        check(EditionJob.kindsWriter(ex, w, true, true, low, notes) == w && asked[0] == 0, "kinds: an unpaid writer never reads the budget");
        check(EditionJob.kindsWriter(paid, w, true, true, low, notes) == null && notes.equals(List.of("build kinds: AI budget low; rules used")), "kinds: paid, budget low: skipped with a note");
        check(EditionJob.kindsWriter(paid, w, true, true, room, notes) == w, "kinds: paid, budget left: asks");
    }

    static Settings load(String yaml) {
        return SettingsLoader.load(YamlConfiguration.loadConfiguration(new StringReader(yaml)), m -> { }, null);
    }

    /** sha-256 of every file under data (except preview/) and the web folder, by path. */
    static Map<String, String> hashes(Path data, Path web) throws Exception {
        Map<String, String> out = new TreeMap<>();
        for (Path base : List.of(data, web)) {
            if (!Files.isDirectory(base)) continue;
            try (Stream<Path> s = Files.walk(base)) {
                for (Path p : s.toList()) {
                    String rel = base.getFileName() + "/" + base.relativize(p);
                    if (base.equals(data) && base.relativize(p).startsWith("preview")) continue;
                    out.put(rel, Files.isDirectory(p) ? "dir" : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
                }
            }
        }
        return out;
    }

    static String diff(Map<String, String> a, Map<String, String> b) {
        List<String> d = new ArrayList<>();
        for (String k : a.keySet()) if (!a.get(k).equals(b.get(k))) d.add("changed/removed " + k);
        for (String k : b.keySet()) if (!a.containsKey(k)) d.add("new " + k);
        return d.toString();
    }

    static String perms(Path p) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
    }

    static boolean isEmpty(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) { return s.findAny().isEmpty(); }
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    /** StatsSnapshot.deleteTree without loading Bukkit-facing code paths. */
    static final class StatsSnapshotHelper {
        static void deleteTree(Path p) throws IOException { io.github.eastmadc.masthead.collect.StatsSnapshot.deleteTree(p); }
    }
}
