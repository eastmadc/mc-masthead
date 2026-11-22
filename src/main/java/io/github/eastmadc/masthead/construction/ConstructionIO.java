package io.github.eastmadc.masthead.construction;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eastmadc.masthead.config.Settings;
import io.github.eastmadc.masthead.pipeline.Housekeeping;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every construction file read and write, on one private daemon thread ("Masthead-construction"), so files never race and
 * a minute flushed before a fold is always written before the fold reads. The executor lives as long as the plugin. No
 * server types: the methods are plain and tested directly; the plugin runs them through {@link #submit}.
 */
final class ConstructionIO {
    static final int RUNNING = 0, COMMITTING = 1, CANCELLED = 2;
    private static final Pattern RAW = Pattern.compile("(\\d{4}-\\d\\d-\\d\\d)\\.jsonl");
    private static final Pattern DATED = Pattern.compile("(\\d{4}-\\d\\d-\\d\\d)\\.json");

    /** A fold that stopped on purpose; the message (fixed words, never a position) goes in the owner's notes. */
    static final class Skip extends Exception {
        Skip(String why) { super(why, null, false, false); }
    }

    /**
     * One fold: what it needs, and its hand-off state (RUNNING, COMMITTING or CANCELLED). ai = the kinds desk's answers.
     * cards != null: a practice fold for the kinds desk, which only fills cards and writes nothing.
     */
    record Job(LocalDate date, boolean publish, Settings.Construction c, Set<String> hidden, Map<String, Integer> keyInfo, LocalDate today,
               AtomicInteger state, Map<String, Long> slimeSeeds, Map<Integer, KindDesk.Ask> ai, List<KindDesk.Card> cards) {
        Job(LocalDate date, boolean publish, Settings.Construction c, Set<String> hidden, Map<String, Integer> keyInfo, LocalDate today, AtomicInteger state) {
            this(date, publish, c, hidden, keyInfo, today, state, Map.of(), Map.of(), null);
        }
    }

    final Path dir;
    private final Logger log;
    final ExecutorService exec;
    long waitMs = 60_000, commitWaitMs = 30_000;   // analyze(): how long an edition waits for the fold, then for its commit
    volatile long testSleepBeforeCommitMs, testSleepInCommitMs;   // test hooks (F-38, F-38b)
    volatile AtomicInteger running;                 // the fold now running, or null
    volatile Map<UUID, Production.Watch> watch = Map.of();   // chunks where mob deaths count, from the last saved index
    volatile Map<String, String> dims = Map.of();             // world UUID -> overworld | nether | end (for survey.json)
    // figures for /masthead construction status (written here, read on the main thread)
    volatile Map<String, List<String>> status = Map.of();
    volatile Map<String, String> levels = Map.of();
    volatile int rawFiles, sites, lastEntries, lastAlerts;
    volatile long rawBytes, lastFoldMs = -1;

    ConstructionIO(Path dir, Logger log) {
        this.dir = dir;
        this.log = log;
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Masthead-construction");
            t.setDaemon(true);
            return t;
        });
    }

    interface Task { void run() throws Exception; }

    private final Set<String> failing = new HashSet<>();   // IO thread only

    /**
     * Runs a task on the IO thread; a failure is logged by its class name only (a message could hold a position), once
     * until that task works again (a full disk would otherwise log every minute).
     */
    void submit(String what, Task t) {
        try {
            exec.execute(() -> {
                try {
                    t.run();
                    if (failing.remove(what)) log.info("construction: " + what + " working again");
                } catch (Exception e) {
                    if (failing.add(what)) log.warning("construction: " + what + " failed (" + e.getClass().getSimpleName() + ")");
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) { /* shutting down */ }
    }

    private Path raw() { return dir.resolve("raw"); }
    private Path f(String name) { return dir.resolve(name); }

    /**
     * At shutdown: waits firstMs for the queue; a fold already writing gets commitMs more, one still reading is cancelled.
     * False when the open minute may not be saved.
     */
    boolean drain(long firstMs, long commitMs) throws InterruptedException {
        exec.shutdown();
        if (exec.awaitTermination(firstMs, TimeUnit.MILLISECONDS)) return true;
        AtomicInteger st = running;
        boolean done = false;   // a cancelled fold still leaves the final minute queued behind it
        if (st != null && !st.compareAndSet(RUNNING, CANCELLED) && st.get() == COMMITTING) done = exec.awaitTermination(commitMs, TimeUnit.MILLISECONDS);
        return st == null || done;
    }

    // ------------------------------------------------------------ appends
    void appendRaw(String text, LocalDate edition) throws IOException {
        if (text.isEmpty()) return;
        mkdirs(raw());
        Path f = raw().resolve(edition + ".jsonl");
        boolean fresh = !Files.exists(f);
        if (!fresh && !endsWithNewline(f)) text = "\n" + text;   // a crash left half a line: only that line is lost
        Files.writeString(f, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (fresh) Housekeeping.privateFile(f);
        rawStats();
    }

    private static boolean endsWithNewline(Path f) throws IOException {
        try (var ch = Files.newByteChannel(f, StandardOpenOption.READ)) {
            if (ch.size() == 0) return true;
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(1);
            ch.position(ch.size() - 1).read(b);
            return b.get(0) == '\n';
        }
    }

    void appendRequest(String line) throws IOException {
        mkdirs(dir);
        Path f = f("requests.jsonl");
        boolean fresh = !Files.exists(f);
        if (!fresh && !endsWithNewline(f)) line = "\n" + line;
        Files.writeString(f, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (fresh) Housekeeping.privateFile(f);
    }

    private void mkdirs(Path d) throws IOException {
        if (Files.isDirectory(d)) return;
        Files.createDirectories(d);
        Housekeeping.privateDir(dir);
        Housekeeping.privateDir(d);
    }

    // ------------------------------------------------------------ the daily fold
    /**
     * Waits for a fold on the IO thread: up to waitMs, then (if it is already writing) up to commitWaitMs more. A fold that
     * was cancelled in time writes nothing, so the next edition folds the same lines again. null = no section today.
     */
    Path analyze(Job j, List<String> notes) {
        Future<Path> fut;
        try {
            fut = exec.submit(() -> {
                running = j.state();
                try { return fold(j); } finally { running = null; }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) { notes.add("construction: skipped (stopping)"); return null; }
        try {
            try { return fut.get(waitMs, TimeUnit.MILLISECONDS); }
            catch (TimeoutException e) {
                if (j.state().compareAndSet(RUNNING, CANCELLED)) { notes.add("construction: skipped (timeout)"); return null; }
                try { return fut.get(commitWaitMs, TimeUnit.MILLISECONDS); }
                catch (TimeoutException e2) { notes.add("construction: skipped (slow commit)"); return null; }
            }
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Skip) notes.add("construction: skipped (" + c.getMessage() + ")");
            else {
                log.warning("construction: fold failed (" + c.getClass().getSimpleName() + ")");
                notes.add("construction: skipped (" + c.getClass().getSimpleName() + ")");
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notes.add("construction: skipped (stopping)");
            return null;
        }
    }

    /** Spec §3.1: age sweep first; then a new date (from sites.json) or a re-run of the same date (from the .prev files). */
    Path fold(Job j) throws Exception {
        long t0 = System.nanoTime();
        try { sweepAged(j.today(), j.c().forgetAfterDays()); }
        catch (IOException | RuntimeException e) { log.warning("construction: age sweep failed (" + e.getClass().getSimpleName() + ")"); }
        String ds = j.date().toString();
        String sj = read(f("sites.json")), pj = read(f("players.json"));
        checkVersion(sj, "sites.json", SiteIndex.VERSION);
        checkVersion(pj, "players.json", Prefs.VERSION);
        SiteIndex idx = sj == null ? new SiteIndex() : SiteIndex.read(sj);
        boolean rerun = ds.equals(idx.lastEdition);
        Map<String, Long> prevFolded = new TreeMap<>(idx.folded);   // what sites.prev.json will hold after a new-date fold
        Prefs prefs;
        List<RawLine.Request> reqs = new ArrayList<>();
        Map<Integer, KindDesk.Ask> ai = j.ai();
        if (rerun) {
            ai = KindDesk.today(idx, ds, j.ai(), j.cards() != null);   // the .prev files predate today's AI answers: reuse what today's paper saved
            String sp = read(f("sites.prev.json")), pp = read(f("players.prev.json"));
            checkVersion(sp, "sites.prev.json", SiteIndex.VERSION);
            checkVersion(pp, "players.prev.json", Prefs.VERSION);
            idx = sp == null ? new SiteIndex() : SiteIndex.read(sp);
            prefs = pp == null ? new Prefs() : Prefs.read(pp);
            requests(f("requests.prev.jsonl"), reqs);
        } else prefs = pj == null ? new Prefs() : Prefs.read(pj);
        requests(f("requests.jsonl"), reqs);
        // replay every raw file up to this date, each from where the last fold stopped, line by line into the fold (L-8)
        Fold.Cfg cfg = new Fold.Cfg(j.c(), j.hidden(), j.date(), j.keyInfo(), j.slimeSeeds(), ai);
        Fold.Replay replay = new Fold.Replay(idx, prefs, reqs, cfg);
        TreeMap<String, Long> folded = new TreeMap<>(idx.folded);
        for (Map.Entry<String, Path> e : rawFiles().entrySet()) {
            if (e.getKey().compareTo(ds) > 0) continue;
            if (j.state().get() != RUNNING || Thread.interrupted()) throw new Skip("timeout");
            replay.file(e.getKey());
            folded.put(e.getKey(), readLines(e.getValue(), folded.getOrDefault(e.getKey(), 0L), replay::add));
        }
        folded.keySet().removeIf(d -> !Files.exists(raw().resolve(d + ".jsonl")));
        Fold.Result fr = replay.finish();
        List<KindDesk.Card> cards = j.cards() == null ? null : KindDesk.cards(idx, prefs, fr, cfg);
        // the owner's survey of older builds: read only while the opening paper (the first one that prints) is still to come
        List<String> surveyNotes = new ArrayList<>();
        List<Survey.Build> survey = List.of();
        if (cards == null && prefs.opened == null) {
            Path sf = f("survey.json");
            try {
                if (Files.isRegularFile(sf, LinkOption.NOFOLLOW_LINKS) && Files.size(sf) > Survey.MAX_BYTES) surveyNotes.add("survey: survey.json is over 1 MB; no survey");
                else survey = Survey.parse(read(sf), surveyNotes);
            } catch (IOException e) { surveyNotes.add("survey: survey.json is not readable; no survey"); }   // not UTF-8, say
        } else if (cards == null && prefs.register != null && prefs.register.stream().anyMatch(x -> prefs.surveyPermits.containsValue(x.permit))) {
            // after the opening, survey rows still leave the page when a secret is typed inside them; that needs the file
            List<String> sn = new ArrayList<>();
            List<Survey.Build> sv = null;
            try {
                Path sf = f("survey.json");
                String text = Files.isRegularFile(sf, LinkOption.NOFOLLOW_LINKS) && Files.size(sf) <= Survey.MAX_BYTES ? read(sf) : null;
                if (text != null) sv = Survey.parse(text, sn);
                if (sn.stream().anyMatch(n -> n.endsWith("no survey"))) sv = null;
            } catch (IOException e) { sv = null; }
            if (sv != null) { Projector.dropSecretSurveys(idx, prefs, sv, dims); prefs.surveyLost = null; }
            else if (prefs.surveyLost == null) {
                prefs.surveyLost = ds;
                surveyNotes.add("survey: survey.json is gone or unreadable, so Town Survey rows can no longer follow secrets typed inside them; put the file back to fix this");
            }
        }
        Projector.Result res = cards != null ? null : Projector.project(idx, prefs, fr, cfg, j.date().getDayOfWeek() == DayOfWeek.SUNDAY, j.c().print(),
                survey, dims, surveyNotes);
        idx.lastEdition = ds;
        idx.folded = folded;

        // the hand-off: from here on everything is written, to the end (a practice fold hands over its cards only if the
        // edition is still waiting for them)
        if (testSleepBeforeCommitMs > 0) Thread.sleep(testSleepBeforeCommitMs);
        if (!j.state().compareAndSet(RUNNING, COMMITTING)) throw new Skip("timeout");
        if (cards != null) { j.cards().addAll(cards); return null; }
        Path outDir = f("out"), alertsDir = f("alerts");
        mkdirs(outDir);
        if (!j.publish()) {
            Path p = outDir.resolve("preview.json");
            write(p, res.out().toString());
            for (String n : surveyNotes) log.info("preview " + n);   // a preview keeps no alerts file; the owner still sees why a row dropped
            return p;
        }
        if (!rerun) {
            copyOrDelete(f("sites.json"), f("sites.prev.json"));
            copyOrDelete(f("players.json"), f("players.prev.json"));
        }
        write(f("sites.json"), idx.write());
        write(f("players.json"), prefs.write());
        if (testSleepInCommitMs > 0) Thread.sleep(testSleepInCommitMs);
        Path rq = f("requests.jsonl"), rqPrev = f("requests.prev.jsonl");
        if (!rerun) {
            Files.deleteIfExists(rqPrev);
            if (Files.exists(rq)) Files.move(rq, rqPrev, StandardCopyOption.ATOMIC_MOVE);
            pruneFolded(j.today(), idx.folded, prevFolded);
        } else if (Files.exists(rq)) {
            Files.write(rqPrev, Files.readAllBytes(rq), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Housekeeping.privateFile(rqPrev);
            Files.delete(rq);
        }
        // only once the save is whole; a failure keeps yesterday's watch (its build ids are still valid) and the paper goes on
        try { watch = Production.watch(idx); }
        catch (RuntimeException e) { log.warning("construction: production watch not rebuilt (" + e.getClass().getSimpleName() + ")"); }
        Path out = outDir.resolve(ds + ".json");
        write(out, res.out().toString());
        Path al = alertsDir.resolve(ds + ".json");
        if (res.alerts().size() > 0) { mkdirs(alertsDir); write(al, res.alerts().toString()); }
        else Files.deleteIfExists(al);
        status = res.status();
        levels = levels(prefs);
        sites = idx.sites.size();
        JsonObject cons = res.out().getAsJsonObject("construction");
        lastEntries = cons == null || !cons.has("entries") ? 0 : cons.getAsJsonArray("entries").size();
        lastAlerts = res.alerts().has("alerts") ? res.alerts().getAsJsonArray("alerts").size() : 0;
        lastFoldMs = (System.nanoTime() - t0) / 1_000_000;
        rawStats();
        return out;
    }

    private void checkVersion(String json, String name, int want) throws Skip {
        if (json == null) return;
        int v = SiteIndex.version(json);
        if (v != -1 && v != want) {
            log.warning("construction: " + name + " version " + v + " not supported by this jar; section skipped");
            throw new Skip("version");
        }
    }

    /** Players' own choices only; everyone else follows default-level and construction.opted-in as they are now. */
    private static Map<String, String> levels(Prefs prefs) {
        Map<String, String> m = new HashMap<>();
        for (Map.Entry<String, Prefs.P> e : prefs.players.entrySet()) if (e.getValue().level != null) m.put(e.getKey(), e.getValue().level);
        return Map.copyOf(m);
    }

    /** Reads whole lines from byte offset off; returns the offset after the last full line (a half-written line waits). */
    static long readLines(Path f, long off, Consumer<RawLine.Line> out) throws IOException {
        if (Files.size(f) <= off) return off;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(f))) {
            in.skipNBytes(off);
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            long pos = off;
            int b;
            while ((b = in.read()) != -1) {
                pos++;
                if (b != '\n') { line.write(b); continue; }
                RawLine.Line l = RawLine.parse(line.toString(StandardCharsets.UTF_8));
                if (l != null) out.accept(l);
                line.reset();
                off = pos;
            }
        }
        return off;
    }

    private static void requests(Path f, List<RawLine.Request> out) throws IOException {
        if (!Files.isRegularFile(f)) return;
        for (String s : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            RawLine.Request r = RawLine.parseRequest(s);
            if (r != null) out.add(r);
        }
    }

    /** Raw files by date, oldest first (other file names are left alone). */
    private TreeMap<String, Path> rawFiles() throws IOException {
        TreeMap<String, Path> m = new TreeMap<>();
        if (!Files.isDirectory(raw(), LinkOption.NOFOLLOW_LINKS)) return m;
        try (var s = Files.list(raw())) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Matcher x = RAW.matcher(p.getFileName().toString());
                if (x.matches() && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) m.put(x.group(1), p);
            }
        }
        return m;
    }

    /** Raw files fully folded in both sites.json and sites.prev.json, and 3+ days old, are no longer needed. */
    private void pruneFolded(LocalDate today, Map<String, Long> now, Map<String, Long> prev) throws IOException {
        String cut = today.minusDays(3).toString();
        for (Map.Entry<String, Path> e : rawFiles().entrySet()) {
            if (e.getKey().compareTo(cut) > 0) continue;
            long size = Files.size(e.getValue());
            if (now.getOrDefault(e.getKey(), -1L) == size && prev.getOrDefault(e.getKey(), -1L) == size) Files.delete(e.getValue());
        }
    }

    // ------------------------------------------------------------ age sweep (feature on or off; never creates the folder)
    void sweepAged(LocalDate today, int forgetAfterDays) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return;
        int raws = 0, files = 0;
        String cut7 = today.minusDays(7).toString();
        for (Map.Entry<String, Path> e : rawFiles().entrySet())
            if (e.getKey().compareTo(cut7) <= 0) { Files.delete(e.getValue()); raws++; }
        FileTime old = FileTime.from(Instant.now().minus(forgetAfterDays, ChronoUnit.DAYS));
        for (String n : List.of("sites.json", "sites.prev.json", "requests.jsonl", "requests.prev.jsonl")) {
            Path p = f(n);
            if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && Files.getLastModifiedTime(p).compareTo(old) <= 0) {
                Files.delete(p);
                files++;
                if (n.equals("sites.json")) watch = Map.of();   // build ids start again from 1
            }
        }
        files += pruneDated(f("out"), today.minusDays(14)) + pruneDated(f("alerts"), today.minusDays(7));
        Path preview = f("out").resolve("preview.json");
        if (Files.isRegularFile(preview) && Files.getLastModifiedTime(preview).compareTo(FileTime.from(Instant.now().minus(14, ChronoUnit.DAYS))) <= 0) {
            Files.delete(preview);
            files++;
        }
        if (raws + files > 0) log.info("construction: age sweep removed " + raws + " raw file(s) and " + files + " other file(s)");
        rawStats();
    }

    private static int pruneDated(Path d, LocalDate keepFrom) throws IOException {
        if (!Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) return 0;
        int n = 0;
        try (var s = Files.list(d)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Matcher x = DATED.matcher(p.getFileName().toString());
                if (x.matches() && x.group(1).compareTo(keepFrom.toString()) < 0) { Files.delete(p); n++; }
            }
        }
        return n;
    }

    void rawStats() {
        int n = 0;
        long bytes = 0;
        try {
            for (Path p : rawFiles().values()) { n++; bytes += Files.size(p); }
        } catch (IOException ignored) { }
        rawFiles = n;
        rawBytes = bytes;
    }

    // ------------------------------------------------------------ the one-time notice, levels (read once at start)
    /** Everyone already sent the notice: players.json plus noticed lines not folded yet. Also loads each player's level. */
    Set<UUID> loadNoticed() {
        Set<UUID> out = new HashSet<>();
        try {
            String pj = read(f("players.json"));
            if (pj != null && SiteIndex.version(pj) == Prefs.VERSION) {
                Prefs p = Prefs.read(pj);
                for (Map.Entry<String, Prefs.P> e : p.players.entrySet()) if (e.getValue().noticed != null) out.add(UUID.fromString(e.getKey()));
                levels = levels(p);
            }
        } catch (IOException | RuntimeException e) { log.warning("construction: players.json unreadable (" + e.getClass().getSimpleName() + ")"); }
        for (String n : List.of("requests.prev.jsonl", "requests.jsonl")) {
            List<RawLine.Request> r = new ArrayList<>();
            try { requests(f(n), r); } catch (IOException ignored) { }
            for (RawLine.Request q : r) if (q.op().equals("noticed")) out.add(q.player());
        }
        return out;
    }

    /** At start: the watch from the saved index (none if it is missing, unreadable or another version's). */
    void loadWatch() throws IOException {
        String sj = read(f("sites.json"));
        watch = sj == null || SiteIndex.version(sj) != SiteIndex.VERSION ? Map.of() : Production.watch(SiteIndex.read(sj));
    }

    // ------------------------------------------------------------ /news build forget, reset, clear-name
    /**
     * Erases one player's building records: their raw and request lines are blanked in place (same length, so fold offsets
     * stay right; "noticed" lines are kept), their TNT is no longer theirs, and sites.json, players.json and both .prev
     * copies drop them. Then a forget line, so a re-run drops anything of theirs still on disk.
     */
    void forget(UUID player) throws IOException {
        String u = player.toString();
        for (Path p : rawFiles().values()) blank(p, u, false);
        for (String n : List.of("requests.jsonl", "requests.prev.jsonl")) blank(f(n), u, true);
        forgetIn("sites.json", "players.json", u);
        forgetIn("sites.prev.json", "players.prev.json", u);
        appendRequest(RawLine.request(player, "forget", null, null, null, null));
        Map<String, List<String>> st = new HashMap<>(status);
        st.remove(u);
        status = Map.copyOf(st);
        rawStats();
    }

    private void forgetIn(String sitesName, String playersName, String u) throws IOException {
        String sj = read(f(sitesName)), pj = read(f(playersName));
        if ((sj != null && SiteIndex.version(sj) != SiteIndex.VERSION) || (pj != null && SiteIndex.version(pj) != Prefs.VERSION)) return;
        SiteIndex idx = sj == null ? new SiteIndex() : SiteIndex.read(sj);
        Prefs prefs = pj == null ? new Prefs() : Prefs.read(pj);
        Fold.forget(idx, prefs, u);
        if (sj != null) write(f(sitesName), idx.write());
        if (pj != null) write(f(playersName), prefs.write());
    }

    /** Overwrites a player's lines with spaces of the same length; a loss line caused by their TNT becomes "by":null. */
    private static void blank(Path f, String uuid, boolean requests) throws IOException {
        if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) return;
        String text = Files.readString(f, StandardCharsets.UTF_8);
        if (!text.contains(uuid)) return;
        String byField = "\"by\":\"" + uuid + "\"";
        StringBuilder b = new StringBuilder(text.length());
        int start = 0;
        while (start < text.length()) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? text.length() : nl;
            String line = text.substring(start, end);
            if (line.contains(uuid)) line = blankLine(line, uuid, byField, requests);
            b.append(line);
            if (nl >= 0) b.append('\n');
            start = end + 1;
        }
        write(f, b.toString());
    }

    private static String blankLine(String line, String uuid, String byField, boolean requests) {
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            String p = o.has("p") && o.get("p").isJsonPrimitive() ? o.get("p").getAsString() : null;
            if (requests) return uuid.equals(p) && !"noticed".equals(str(o, "op")) ? " ".repeat(line.length()) : line;
            if ("p".equals(str(o, "k")) && uuid.equals(p)) return " ".repeat(line.length());
            if ("x".equals(str(o, "k")) && line.contains(byField))
                return line.replace(byField, "\"by\":null" + " ".repeat(byField.length() - "\"by\":null".length()));
        } catch (RuntimeException broken) { return " ".repeat(line.length()); }   // a broken line that names them goes too
        return line;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** World rollback: the building records go (players.json stays, so permit numbers are never reused). */
    void reset() throws IOException {
        for (Path p : rawFiles().values()) Files.delete(p);
        for (String n : List.of("sites.json", "sites.prev.json", "requests.jsonl", "requests.prev.jsonl")) Files.deleteIfExists(f(n));
        watch = Map.of();   // build ids start again from 1: nothing may be credited to the old ones
        status = Map.of();
        sites = 0;
        rawStats();
    }

    /** Takes a build name off, printed or held (any case), and drops name requests for it not folded yet. Returns how many. */
    int clearName(String name) throws IOException {
        int n = 0;
        for (String s : List.of("sites.json", "sites.prev.json")) {
            String sj = read(f(s));
            if (sj == null || SiteIndex.version(sj) != SiteIndex.VERSION) continue;
            SiteIndex idx = SiteIndex.read(sj);
            int here = 0;
            for (SiteIndex.Site x : idx.sites.values())
                if (x.name != null && x.name.equalsIgnoreCase(name)) { x.name = null; x.nameBy = null; x.nameFrom = null; here++; }
            if (here > 0) write(f(s), idx.write());
            if (s.equals("sites.json")) n += here;
        }
        for (String r : List.of("requests.jsonl", "requests.prev.jsonl")) {
            Path p = f(r);
            if (!Files.isRegularFile(p)) continue;
            List<String> out = new ArrayList<>();
            boolean changed = false;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                RawLine.Request q = RawLine.parseRequest(line);
                if (q != null && (q.op().equals("name") || q.op().equals("sign")) && q.value() != null && q.value().strip().replaceAll("\\s+", " ").equalsIgnoreCase(name)) {
                    out.add(" ".repeat(line.length()));
                    changed = true;
                    n++;
                } else out.add(line);
            }
            if (changed) write(p, String.join("\n", out) + "\n");
        }
        return n;
    }

    // ------------------------------------------------------------ files
    private static String read(Path p) throws IOException {
        return Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) ? Files.readString(p, StandardCharsets.UTF_8) : null;
    }

    /** Written whole through a temporary file, private (600). */
    static void write(Path p, String text) throws IOException {
        Path tmp = p.resolveSibling(p.getFileName() + ".new");
        Files.writeString(tmp, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Housekeeping.privateFile(tmp);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void copyOrDelete(Path from, Path to) throws IOException {
        if (Files.isRegularFile(from)) {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            Housekeeping.privateFile(to);
        } else Files.deleteIfExists(to);
    }
}
