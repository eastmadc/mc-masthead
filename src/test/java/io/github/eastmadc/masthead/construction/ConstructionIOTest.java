package io.github.eastmadc.masthead.construction;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eastmadc.masthead.config.Settings;
import io.github.eastmadc.masthead.facts.ConstructionFacts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static io.github.eastmadc.masthead.construction.ConstructionTest.ALEX;
import static io.github.eastmadc.masthead.construction.ConstructionTest.STEVE;
import static io.github.eastmadc.masthead.construction.ConstructionTest.X;
import static io.github.eastmadc.masthead.construction.ConstructionTest.Y;
import static io.github.eastmadc.masthead.construction.ConstructionTest.Z;

/**
 * Construction news, the file side (spec §2.9, §3.1, §10): the fold's byte offsets, new date vs re-run, request rotation,
 * preview, the commit hand-off, the age sweep, forget, reset, clear-name and the notice list. Plain main(), real files in a
 * temporary folder, no server.
 */
public final class ConstructionIOTest {
    static int ok = 0;

    static void t(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
        ok++;
    }

    static final Logger LOG = Logger.getLogger("test");
    static final LocalDate D1 = LocalDate.of(2026, 11, 2), D2 = D1.plusDays(1);

    /** A construction/ folder, plus a synthetic server that writes raw lines through the real recorder path. */
    static final class Rig {
        final Path dir;
        final ConstructionIO io;
        final ConstructionTest.Sc sc = new ConstructionTest.Sc();
        Settings.Construction c = sc.c;
        Set<String> hidden = Set.of();

        Rig() throws Exception { this(Files.createTempDirectory("cons").resolve("construction")); }

        Rig(Path dir) {
            this.dir = dir;
            io = new ConstructionIO(dir, LOG);
        }

        /** Everything built so far goes to raw/DATE.jsonl, as the minute timer would write it. */
        void flush(LocalDate edition) throws Exception {
            sc.flush();
            io.appendRaw(sc.raw.toString(), edition);
            sc.raw.setLength(0);
        }

        void req(String name, String op, String v) throws Exception {
            io.appendRequest(RawLine.request(sc.id(name), op, null, null, v, op.equals("noticed") ? "2026-10-01" : null));
        }

        ConstructionIO.Job job(LocalDate d, boolean publish) {
            return new ConstructionIO.Job(d, publish, c, hidden, ConstructionTest.KEY_INFO, d, new AtomicInteger(ConstructionIO.RUNNING));
        }

        Path fold(LocalDate d, boolean publish) throws Exception { return io.fold(job(d, publish)); }

        String read(String name) throws Exception {
            Path p = dir.resolve(name);
            return Files.exists(p) ? Files.readString(p) : null;
        }

        SiteIndex sites() throws Exception { return SiteIndex.read(read("sites.json")); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("fold-child")) {   // streamedFold's small-heap child JVM
            Rig r = new Rig(Path.of(args[1]));
            r.fold(D1, true);
            return;
        }
        offsetsAndReruns();
        previewAndHandOff();
        sweep();
        forgetResetClear();
        hiddenNames();
        reviewIO();
        thread();
        streamedFold();
        System.out.println("ConstructionIOTest: " + ok + " checks passed");
    }

    // ------------------------------------------------------------ §3.1
    static void offsetsAndReruns() throws Exception {
        Rig r = new Rig();
        r.req(STEVE, "noticed", null);
        r.req(ALEX, "noticed", null);
        ConstructionTest.house(r.sc, STEVE, X, Y, Z);
        r.flush(D1);
        Path out1 = r.fold(D1, true);
        String first = Files.readString(out1), sites1 = r.read("sites.json");
        t(out1.getFileName().toString().equals(D1 + ".json") && first.contains("\"permit\":1"), "publish: out/DATE.json with a permit");
        t(!Files.exists(r.dir.resolve("requests.jsonl")) && r.read("requests.prev.jsonl").contains("noticed"), "publish: requests.jsonl renamed to .prev");
        long size = Files.size(r.dir.resolve("raw").resolve(D1 + ".jsonl"));
        t(r.sites().folded.get(D1.toString()) == size, "the fold records how far it read");
        // F-15c: a same-date re-run (a scheduled retry) gives the same paper and the same permit numbers
        t(Files.readString(r.fold(D1, true)).equals(first), "F-15: a re-run of the same date prints the same");
        t(r.read("sites.json").equals(sites1), "F-15: the re-run's sites.json is the same");
        t(Files.readString(r.fold(D1, true)).equals(first), "F-15: a third run too");
        // a secret filed after the paper: the re-run keeps it (P10), and so does a third run
        r.io.appendRequest(RawLine.request(r.sc.id(STEVE), "secret", ConstructionTest.Sc.WORLD, SiteIndex.cubeKey((X + 2) >> 2, (Y + 1) >> 2, (Z + 2) >> 2), null, null));
        r.fold(D1, true);
        t(!r.sites().sites.firstEntry().getValue().secretBy.isEmpty(), "P10: a re-run applies a new secret");
        t(r.read("requests.prev.jsonl").contains("secret") && !Files.exists(r.dir.resolve("requests.jsonl")), "re-run: requests appended to .prev");
        r.fold(D1, true);
        t(!r.sites().sites.firstEntry().getValue().secretBy.isEmpty(), "P10: a third run still has it");
        // lines written after the paper (an op's early /masthead now) are folded once, by the next date
        r.sc.box(STEVE, "oak_planks", X, Y + 5, Z, X + 3, Y + 5, Z + 3);
        r.flush(D1);
        int before = r.sites().sites.firstEntry().getValue().net;
        r.fold(D2, true);
        int after = r.sites().sites.firstEntry().getValue().net;
        t(after == before + 16, "offsets: late lines in an older raw file are folded by the next date (" + before + " -> " + after + ")");
        r.fold(D2, true);
        t(r.sites().sites.firstEntry().getValue().net == after, "offsets: and only once");
        // a half-written last line waits for its end
        Path raw2 = r.dir.resolve("raw").resolve(D2 + ".jsonl");
        r.sc.box(STEVE, "oak_planks", X, Y + 6, Z, X + 3, Y + 6, Z + 3);
        r.sc.flush();
        String line = r.sc.raw.toString();
        r.sc.raw.setLength(0);
        Files.writeString(raw2, line.substring(0, line.length() / 2));
        List<RawLine.Line> got = new ArrayList<>();
        t(ConstructionIO.readLines(raw2, 0, got::add) == 0 && got.isEmpty(), "a partial line is not read and not counted");
        Files.writeString(raw2, line);
        t(ConstructionIO.readLines(raw2, 0, got::add) == line.getBytes(StandardCharsets.UTF_8).length && got.size() == 1, "and is read whole once finished");
        // version: an unknown sites.json is left byte for byte, and the section is skipped
        String v2 = r.read("sites.json").replaceFirst("\"version\":1", "\"version\":2");
        Files.writeString(r.dir.resolve("sites.json"), v2);
        List<String> notes = new ArrayList<>();
        t(r.io.analyze(r.job(D2.plusDays(1), true), notes) == null && notes.contains("construction: skipped (version)"), "F-37: version 2 skipped: " + notes);
        t(r.read("sites.json").equals(v2), "F-37: sites.json untouched");
    }

    // ------------------------------------------------------------ preview, timeout hand-off (F-38)
    static void previewAndHandOff() throws Exception {
        Rig r = new Rig();
        r.req(STEVE, "noticed", null);
        ConstructionTest.house(r.sc, STEVE, X, Y, Z);
        r.flush(D1);
        Path p = r.fold(D1, false);
        t(p.getFileName().toString().equals("preview.json") && Files.readString(p).contains("\"entries\""), "preview: out/preview.json");
        t(!Files.exists(r.dir.resolve("sites.json")) && !Files.exists(r.dir.resolve("players.json")) && Files.exists(r.dir.resolve("requests.jsonl")),
                "preview: no sites, players or request change");
        // the edition gives up before the fold commits: nothing is written, and the next fold reads the same lines
        r.io.waitMs = 100;
        r.io.testSleepBeforeCommitMs = 600;
        List<String> notes = new ArrayList<>();
        t(r.io.analyze(r.job(D1, true), notes) == null && notes.contains("construction: skipped (timeout)"), "F-38: timeout note: " + notes);
        Thread.sleep(900);
        t(!Files.exists(r.dir.resolve("sites.json")) && !Files.exists(r.dir.resolve("out").resolve(D1 + ".json")), "F-38: a cancelled fold writes nothing");
        r.io.testSleepBeforeCommitMs = 0;
        r.io.waitMs = 60_000;
        notes.clear();
        Path out = r.io.analyze(r.job(D1, true), notes);
        t(out != null && notes.isEmpty() && Files.readString(out).contains("\"permit\":1"), "F-38: the next fold folds the same lines");
        // F-38b: a fold already writing when the wait runs out is waited for (30 s more), and its file is used
        r.sc.box(STEVE, "oak_planks", X, Y + 5, Z, X + 3, Y + 5, Z + 3);
        r.flush(D2);
        r.io.waitMs = 200;
        r.io.testSleepInCommitMs = 400;
        notes.clear();
        Path o2 = r.io.analyze(r.job(D2, true), notes);
        t(o2 != null && notes.isEmpty() && o2.getFileName().toString().equals(D2 + ".json"), "F-38b: a commit in progress is finished and used: " + notes);
        // and if even that runs out, the commit still finishes whole; only this paper goes without the section
        r.sc.box(STEVE, "oak_planks", X, Y + 6, Z, X + 3, Y + 6, Z + 3);
        r.flush(D2.plusDays(1));
        r.io.commitWaitMs = 50;
        r.io.appendRequest(RawLine.request(r.sc.id(STEVE), "public", null, null, null, null));
        notes.clear();
        t(r.io.analyze(r.job(D2.plusDays(1), true), notes) == null && notes.contains("construction: skipped (slow commit)"), "F-38b: slow commit note");
        Thread.sleep(700);
        t(D2.plusDays(1).toString().equals(r.sites().lastEdition) && !Files.exists(r.dir.resolve("requests.jsonl"))
                && Files.exists(r.dir.resolve("out").resolve(D2.plusDays(1) + ".json")), "F-38b: the commit was never cut in half");
        r.io.testSleepInCommitMs = 0;
        r.io.commitWaitMs = 30_000;
        r.io.waitMs = 60_000;
    }

    // ------------------------------------------------------------ §2.9 age sweep
    static void sweep() throws Exception {
        Path base = Files.createTempDirectory("cons");
        ConstructionIO none = new ConstructionIO(base.resolve("construction"), LOG);
        none.sweepAged(D1, 30);
        t(!Files.exists(base.resolve("construction")), "sweep: never creates the folder");
        Rig r = new Rig();
        r.io.appendRaw("{}\n", D1.minusDays(7));
        r.io.appendRaw("{}\n", D1.minusDays(6));
        Files.writeString(r.dir.resolve("raw").resolve("notes.txt"), "keep");
        Files.writeString(r.dir.resolve("sites.json"), "{\"version\":1}");
        Files.writeString(r.dir.resolve("players.json"), "{\"version\":1}");
        FileTime old = FileTime.from(Instant.now().minus(31, ChronoUnit.DAYS));
        Files.setLastModifiedTime(r.dir.resolve("sites.json"), old);
        Files.setLastModifiedTime(r.dir.resolve("players.json"), old);
        Files.createDirectories(r.dir.resolve("out"));
        Files.writeString(r.dir.resolve("out").resolve(D1.minusDays(15) + ".json"), "{}");
        Files.writeString(r.dir.resolve("out").resolve(D1.minusDays(13) + ".json"), "{}");
        r.io.sweepAged(D1, 30);
        t(!Files.exists(r.dir.resolve("raw").resolve(D1.minusDays(7) + ".jsonl")) && Files.exists(r.dir.resolve("raw").resolve(D1.minusDays(6) + ".jsonl")),
                "sweep: raw files 7 days old go");
        t(Files.exists(r.dir.resolve("raw").resolve("notes.txt")), "sweep: other file names are left alone");
        t(!Files.exists(r.dir.resolve("sites.json")) && Files.exists(r.dir.resolve("players.json")), "sweep: an untouched sites.json goes; players.json stays");
        t(!Files.exists(r.dir.resolve("out").resolve(D1.minusDays(15) + ".json")) && Files.exists(r.dir.resolve("out").resolve(D1.minusDays(13) + ".json")),
                "sweep: out/ kept 14 days");
        // a published fold prunes raw files fully folded twice and 3+ days old
        Rig q = new Rig();
        ConstructionTest.house(q.sc, STEVE, X, Y, Z);
        q.flush(D1);
        q.io.fold(new ConstructionIO.Job(D1, true, q.c, Set.of(), ConstructionTest.KEY_INFO, D1.plusDays(4), new AtomicInteger()));
        t(Files.exists(q.dir.resolve("raw").resolve(D1 + ".jsonl")), "prune: not until it is folded in both sites files");
        q.io.fold(new ConstructionIO.Job(D2, true, q.c, Set.of(), ConstructionTest.KEY_INFO, D1.plusDays(4), new AtomicInteger()));
        t(!Files.exists(q.dir.resolve("raw").resolve(D1 + ".jsonl")), "prune: folded in both, and 3 days old");
        q.io.fold(new ConstructionIO.Job(D2.plusDays(1), true, q.c, Set.of(), ConstructionTest.KEY_INFO, D1.plusDays(4), new AtomicInteger()));
        t(!q.sites().folded.containsKey(D1.toString()), "prune: its offset goes at the next fold");
    }

    // ------------------------------------------------------------ §10.1 forget, §3.10 reset, §10.3 clear-name, §10.4 notices
    static void forgetResetClear() throws Exception {
        Rig r = new Rig();
        r.req(STEVE, "noticed", null);
        r.req(ALEX, "noticed", null);
        ConstructionTest.house(r.sc, STEVE, X, Y, Z);
        ConstructionTest.house(r.sc, ALEX, X + 40, Y, Z);
        r.sc.lose("tnt", STEVE, "oak_planks", X + 40, Y, Z);
        r.flush(D1);
        Set<UUID> n = r.io.loadNoticed();
        t(n.contains(r.sc.id(STEVE)) && n.contains(r.sc.id(ALEX)), "C-09: noticed lines not folded yet count");
        Path raw = r.dir.resolve("raw").resolve(D1 + ".jsonl");
        long size = Files.size(raw);
        UUID steve = r.sc.id(STEVE);
        r.io.forget(steve);
        String text = Files.readString(raw);
        t(Files.size(raw) == size, "forget: raw file keeps its length (offsets stay right)");
        t(!text.contains(steve.toString()), "forget: no line names them");
        t(text.contains("\"by\":null"), "forget: their TNT is nobody's");
        for (String l : text.split("\n")) t(l.isBlank() || RawLine.parse(l) != null, "forget: every kept line still parses");
        t(r.read("requests.jsonl").contains("\"op\":\"noticed\"") && r.read("requests.jsonl").contains(steve + "\",\"op\":\"forget\""),
                "forget: the noticed line stays, a forget line is added");
        t(r.io.loadNoticed().contains(steve), "C-09: still noticed after forget");
        Path out = r.fold(D1, true);
        t(!Files.readString(out).contains(STEVE) && Files.readString(r.dir.resolve("players.json")).contains("\"level\":\"private\""), "forget: nothing of theirs prints; private");
        // clear-name: a held name and one not folded yet
        r.io.appendRequest(RawLine.request(r.sc.id(ALEX), "name", ConstructionTest.Sc.WORLD, SiteIndex.cubeKey((X + 42) >> 2, (Y + 1) >> 2, (Z + 2) >> 2), "Oak Hall", null));
        r.fold(D2, true);
        t("Oak Hall".equals(r.sites().sites.lastEntry().getValue().name), "the name was accepted");
        t(r.io.clearName("oak hall") >= 1 && r.sites().sites.lastEntry().getValue().name == null, "clear-name: any case, taken off");
        t(!r.read("requests.prev.jsonl").contains("Oak Hall"), "clear-name: the request line is gone too, so a re-run can't bring it back");
        r.fold(D2, true);
        t(r.sites().sites.lastEntry().getValue().name == null, "clear-name: a re-run keeps it off");
        t(r.io.clearName("No Such Name") == 0, "clear-name: unknown name");
        // reset
        r.io.reset();
        t(!Files.exists(r.dir.resolve("sites.json")) && !Files.exists(raw) && Files.exists(r.dir.resolve("players.json")) && r.io.status.isEmpty(),
                "reset: building records go, players.json stays");
    }

    // ------------------------------------------------------------ review finding: a build name with a hidden player's name
    static void hiddenNames() throws Exception {
        Rig r = new Rig();
        r.hidden = Set.of("staffalt");
        r.req(STEVE, "noticed", null);
        ConstructionTest.house(r.sc, STEVE, X, Y, Z);
        r.flush(D1);
        r.fold(D1, true);
        r.io.appendRequest(RawLine.request(r.sc.id(STEVE), "name", ConstructionTest.Sc.WORLD, SiteIndex.cubeKey((X + 2) >> 2, (Y + 1) >> 2, (Z + 2) >> 2), "StaffAlts Tower", null));
        r.fold(D2, true);
        t(r.sites().sites.firstEntry().getValue().name == null, "hidden name: refused at the fold");
        JsonObject o = JsonParser.parseString("{\"version\":1,\"construction\":{\"opening\":false,\"entries\":[{\"tag\":\"permit\",\"permit\":1,"
                + "\"name\":\"StaffAlts Tower\",\"builders\":[\"Steve_Builder\"],\"kind\":\"house\"}]}}").getAsJsonObject();
        Path f = r.dir.resolve("hidden.json");
        Files.writeString(f, o.toString());
        ConstructionFacts.Result res = ConstructionFacts.read(f, Set.of(STEVE), Set.of("StaffAlt"), false, 3);
        t(res.construction() == null || !res.construction().toString().contains("StaffAlt"), "hidden name: dropped at Gate 1");
        t(NameRules.check("StaffAlts Tower", List.of("staffalt"), "clean") == null, "hidden name: refused at filing");
    }

    // ------------------------------------------------------------ early adversarial review
    static void reviewIO() throws Exception {
        // PO-4: after a crash mid-append, only the torn line is lost
        Rig r = new Rig();
        for (int i = 0; i < 3; i++) r.sc.box(STEVE, "oak_planks", X, Y + i, Z, X + 3, Y + i, Z + 3);   // three minutes, one line each
        String[] lines = r.sc.raw.toString().split("\n");
        r.sc.raw.setLength(0);
        r.io.appendRaw(lines[0] + "\n", D1);
        Path raw = r.dir.resolve("raw").resolve(D1 + ".jsonl");
        Files.writeString(raw, lines[1].substring(0, lines[1].length() / 2), java.nio.file.StandardOpenOption.APPEND);
        r.io.appendRaw(lines[2] + "\n", D1);
        List<RawLine.Line> got = new ArrayList<>();
        ConstructionIO.readLines(raw, 0, got::add);
        t(got.size() == 2, "PO-4: the line after a torn one is kept (" + got.size() + ")");

        // levels: only players' own choices; everyone else follows default-level and the opted-in list live
        r = new Rig();
        r.req(STEVE, "noticed", null);
        r.req(ALEX, "private", null);
        ConstructionTest.house(r.sc, STEVE, X, Y, Z);
        r.flush(D1);
        r.fold(D1, true);
        t(!r.io.levels.containsKey(r.sc.id(STEVE).toString()) && "private".equals(r.io.levels.get(r.sc.id(ALEX).toString())), "levels: own choices only: " + r.io.levels);

        // PO-7: a failing task warns once, then once more when it works again
        List<java.util.logging.LogRecord> logged = new ArrayList<>();
        Logger lg = Logger.getAnonymousLogger();
        lg.setUseParentHandlers(false);
        lg.addHandler(new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord x) { logged.add(x); }
            public void flush() { }
            public void close() { }
        });
        ConstructionIO io = new ConstructionIO(Files.createTempDirectory("cons"), lg);
        for (int i = 0; i < 3; i++) io.submit("raw write", () -> { throw new java.io.IOException("disk full"); });
        io.submit("raw write", () -> { });
        io.submit("raw write", () -> { });
        io.exec.submit(() -> { }).get();
        t(logged.size() == 2 && logged.get(0).getMessage().equals("construction: raw write failed (IOException)")
                && logged.get(1).getMessage().equals("construction: raw write working again"), "PO-7: " + logged.stream().map(java.util.logging.LogRecord::getMessage).toList());

        // PO-5: a fold that finishes its commit during shutdown is not reported as lost
        AtomicInteger st = new AtomicInteger(ConstructionIO.COMMITTING);
        io.exec.submit(() -> { io.running = st; try { Thread.sleep(300); } catch (InterruptedException e) { } io.running = null; });
        io.submit("raw write", () -> { });
        Thread.sleep(50);
        t(io.drain(50, 5_000), "PO-5: commit and final minute done: saved");
        ConstructionIO io2 = new ConstructionIO(Files.createTempDirectory("cons"), lg);
        AtomicInteger st2 = new AtomicInteger(ConstructionIO.RUNNING);
        io2.exec.submit(() -> { io2.running = st2; try { Thread.sleep(300); } catch (InterruptedException e) { } });
        Thread.sleep(50);
        t(!io2.drain(50, 5_000) && st2.get() == ConstructionIO.CANCELLED, "PO-5: a fold still reading is cancelled and reported");
    }

    // ------------------------------------------------------------ §2.1
    static void thread() throws Exception {
        Rig r = new Rig();
        Thread[] th = new Thread[1];
        r.io.exec.submit(() -> th[0] = Thread.currentThread()).get();
        Thread first = th[0];
        r.io.exec.submit(() -> th[0] = Thread.currentThread()).get();
        t(first == th[0] && first.getName().equals("Masthead-construction") && first.isDaemon(), "one daemon IO thread, the same every time");
        r.io.exec.shutdown();
    }

    // ------------------------------------------------------------ L-8: the fold streams the raw files
    /**
     * A day of 60,000 raw lines (12 MB) folds in a 32 MB heap. Held as parsed lines (about 1 KB each, as the fold once did)
     * the day alone needs 60 MB, so the child runs out of memory; streamed, the fold keeps only the cubes it touched.
     */
    static void streamedFold() throws Exception {
        Rig ref = new Rig(), r = new Rig();
        for (Rig x : List.of(ref, r)) {
            x.req(STEVE, "noticed", null);
            ConstructionTest.house(x.sc, STEVE, X, Y, Z);
            x.flush(D1);
        }
        // Steve places and breaks one block, over and over, far from the house: 60,000 lines that change nothing
        String head = "{\"k\":\"p\",\"p\":\"" + r.sc.id(STEVE) + "\",\"n\":\"" + STEVE + "\",\"w\":\"" + ConstructionTest.Sc.WORLD
                + "\",\"s\":[" + ((X >> 4) + 40) + "," + (Y >> 4) + "," + (Z >> 4) + "],";
        String place = head + "\"pl\":1,\"br\":0,\"y0\":1,\"y1\":1,\"d\":{\"B\":{\"0\":1}},\"m\":{\"oak_planks\":1}}\n";
        String brk = head + "\"pl\":0,\"br\":1,\"d\":{\"B\":{\"0\":-1}},\"m\":{\"oak_planks\":-1}}\n";
        Path raw = r.dir.resolve("raw").resolve(D1 + ".jsonl");
        try (var w = Files.newBufferedWriter(raw, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND)) {
            for (int i = 0; i < 30_000; i++) { w.write(place); w.write(brk); }
        }
        t(Files.size(raw) > 11_000_000, "L-8: a 12 MB raw day (" + (Files.size(raw) >> 20) + " MB)");
        String java = ProcessHandle.current().info().command().orElse("java");
        Process pr = new ProcessBuilder(java, "-Xmx32m", "-cp", System.getProperty("java.class.path"), ConstructionIOTest.class.getName(),
                "fold-child", r.dir.toString()).redirectErrorStream(true).start();
        String log = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        t(pr.waitFor() == 0 && !log.contains("OutOfMemoryError"), "L-8: the 12 MB day folds in a 32 MB heap: " + log.lines().limit(3).toList());
        t(r.sites().folded.get(D1.toString()) == Files.size(raw), "L-8: and reads every line");
        ref.fold(D1, true);
        String got = r.read("out/" + D1 + ".json"), want = ref.read("out/" + D1 + ".json");
        t(got.contains("\"lines\":60001") && want.contains("\"lines\":1,"), "L-8: every line went through the fold");
        t(got.replace("\"lines\":60001", "\"lines\":1").equals(want), "L-8: the same paper as without the noise");
        t(r.sites().sites.firstEntry().getValue().net == ref.sites().sites.firstEntry().getValue().net, "L-8: the same build");
    }
}
