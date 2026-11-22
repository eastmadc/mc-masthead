package io.github.eastmadc.masthead.writer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/** Plain-main test for CommandWriter, ExchangeWriter and NoWriter. Needs /bin/sh, cat, sleep. */
public final class CommandExchangeWriterTest {
    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("cp-writer-test");
        Path log = tmp.resolve("logs/writer-stderr.log");

        // --- CommandWriter
        CommandWriter cat = new CommandWriter(List.of("cat"), log);
        check(cat.write("SYS", "USER ✓", Duration.ofSeconds(10)).equals("SYS\n\nUSER ✓"), "cat echoes system+user");
        String mid = "x".repeat(90_000);   // 180 KB through the pipe both ways, under the 200 KB cap
        check(cat.write(mid, mid, Duration.ofSeconds(10)).length() == 180_002, "large round trip");
        String big = "y".repeat(150_000);
        System.out.println("ok command: stdin->stdout");
        // big stdin and stdout over the cap (300 KB) -> throws, no deadlock
        try {
            cat.write(big, big, Duration.ofSeconds(10));
            throw new AssertionError("over-cap output should throw");
        } catch (IOException e) {
            check(e.getMessage().contains("more than"), "cap message: " + e.getMessage());
        }
        System.out.println("ok command: stdout cap");

        CommandWriter fail = new CommandWriter(List.of("sh", "-c", "echo oops >&2; exit 3"), log);
        try {
            fail.write("a", "b", Duration.ofSeconds(10));
            throw new AssertionError("exit 3 should throw");
        } catch (IOException e) {
            check(e.getMessage().contains("code 3"), "exit code message");
        }
        check(Files.readString(log).contains("oops") && Files.readString(log).contains("exit=3"), "stderr logged");
        if (POSIX) check(mode(log).equals("rw-------"), "the stderr log is private from its first line: " + mode(log));
        System.out.println("ok command: non-zero exit throws, stderr logged");

        // timeout kills the whole tree: sh spawns a sleep grandchild that writes a marker if it survives
        Path marker = tmp.resolve("survived");
        CommandWriter slow = new CommandWriter(List.of("sh", "-c", "(sleep 3; touch '" + marker + "') & sleep 30"), log);
        long t0 = System.nanoTime();
        try {
            slow.write("a", "b", Duration.ofSeconds(1));
            throw new AssertionError("timeout should throw");
        } catch (TimeoutException e) {
            check((System.nanoTime() - t0) / 1_000_000 < 9_000, "returned promptly");
        }
        Thread.sleep(4000);
        check(!Files.exists(marker), "grandchild was killed");
        System.out.println("ok command: timeout kills process tree");

        CommandWriter empty = new CommandWriter(List.of("true"), log);
        try {
            empty.write("a", "b", Duration.ofSeconds(5));
            throw new AssertionError("empty output should throw");
        } catch (IOException e) {
            check(e.getMessage().contains("nothing"), "empty message");
        }
        System.out.println("ok command: empty output throws");

        // C5: the two folders exist before the first paper: made at server start and on reload when writer.mode is exchange
        // (EditionJob.start needs a server, so its text is checked)
        Path early = tmp.resolve("early-ai");
        ExchangeWriter.makeFolders(early);
        ExchangeWriter.makeFolders(early);   // a second time (reload) is fine
        String startSrc = Files.readString(Path.of("src/main/java/io/github/eastmadc/masthead/pipeline/EditionJob.java"));
        startSrc = startSrc.substring(startSrc.indexOf("public void start()"), startSrc.indexOf("public void stop()"));
        check(Files.isDirectory(early.resolve("requests")) && Files.isDirectory(early.resolve("responses"))
                && startSrc.contains("if (s.writer().mode().equals(\"exchange\")) {") && startSrc.contains("ExchangeWriter.makeFolders(data.resolve(\"ai\"));"),
                "exchange folders are made at server start and reload");
        System.out.println("ok exchange: folders before the first paper");

        // --- ExchangeWriter, with a thread playing the host helper
        Path ai = tmp.resolve("ai");
        ExchangeWriter ex = new ExchangeWriter(ai, Duration.ofMillis(50));
        java.util.Set<String> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.Set<String> requestModes = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Thread helper = Thread.ofPlatform().daemon().start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Path req = ai.resolve("requests");
                    if (Files.isDirectory(req)) try (Stream<Path> s = Files.list(req)) {
                        for (Path p : s.toList()) {
                            String name = p.getFileName().toString();
                            if (!name.matches("[a-z0-9-]{8,64}\\.txt")) continue;
                            seen.add(name);
                            // act like tools/masthead-exchange.sh: skip a request already answered (.txt or .err),
                            // write answers atomically, and expect the plugin to delete the request at any moment
                            Path resp = ai.resolve("responses").resolve(name);
                            Path err = ai.resolve("responses").resolve(name.replace(".txt", ".err"));
                            if (Files.exists(resp) || Files.exists(err)) continue;
                            String in;
                            try {
                                in = Files.readString(p);
                                if (POSIX) requestModes.add(mode(p) + " group=" + group(p));
                            } catch (java.nio.file.NoSuchFileException gone) {
                                continue;
                            }
                            Path t = ai.resolve("responses").resolve("." + name + ".tmp");
                            if (in.contains("PLEASE FAIL")) {
                                Files.writeString(t, "claude exited 1");
                                Files.move(t, err, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                            } else if (!in.contains("IGNORE ME")) {
                                Files.writeString(t, "DRAFT for: " + in.lines().reduce((a, b) -> b).orElse(""), StandardCharsets.UTF_8);
                                Files.move(t, resp, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                            }
                        }
                    }
                    Thread.sleep(30);
                }
            } catch (InterruptedException ignored) {
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        // review round 3: the host's path unit watches requests/, so the only file that may appear there is the finished
        // <id>.txt (a half-written temp file there would start the helper too early)
        Files.createDirectories(ai.resolve("requests"));
        java.nio.file.WatchService watch = ai.getFileSystem().newWatchService();
        ai.resolve("requests").register(watch, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
        check(ex.write("STYLE", "facts today", Duration.ofSeconds(10)).equals("DRAFT for: facts today"), "exchange round trip");
        java.util.List<String> created = new java.util.ArrayList<>();
        for (java.nio.file.WatchKey k; (k = watch.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)) != null; k.reset())
            for (var e : k.pollEvents()) created.add(String.valueOf(e.context()));
        watch.close();
        check(!created.isEmpty() && created.stream().allMatch(n -> n.matches("[a-z0-9-]{8,64}\\.txt")), "only the finished request appears in requests/: " + created);
        check(empty(ai.resolve("requests")) && !Files.list(ai).anyMatch(p -> p.getFileName().toString().endsWith(".tmp")), "no temp file left next to requests/");
        check(empty(ai.resolve("requests")) && empty(ai.resolve("responses")), "files cleaned up after success");
        System.out.println("ok exchange: round trip, files deleted");
        if (POSIX) privateRequests(ai, ex, requestModes);

        try {
            ex.write("STYLE", "PLEASE FAIL", Duration.ofSeconds(10));
            throw new AssertionError(".err should throw");
        } catch (IOException e) {
            check(e.getMessage().contains("claude exited 1"), "err message");
        }
        check(empty(ai.resolve("requests")) && empty(ai.resolve("responses")), "files cleaned up after .err");
        System.out.println("ok exchange: helper failure -> throws");

        try {
            ex.write("STYLE", "IGNORE ME", Duration.ofMillis(400));
            throw new AssertionError("no response should time out");
        } catch (TimeoutException e) {
            check(e.getMessage().contains("masthead-exchange.sh"), "timeout message names the helper");
        }
        check(empty(ai.resolve("requests")), "request deleted after timeout");
        System.out.println("ok exchange: timeout, request deleted");
        helper.interrupt();

        // --- NoWriter
        try {
            new NoWriter().write("a", "b", Duration.ofSeconds(1));
            throw new AssertionError("NoWriter should throw");
        } catch (UnsupportedOperationException expected) {
            System.out.println("ok none: throws UnsupportedOperationException");
        }
        check(ExchangeWriter.ID.matcher("2026-11-05-0123456789abcdef").matches(), "id format");
        // T2: the request name carries this request's own time limit: <date>-t<seconds>-<16 hex>.txt
        check(seen.stream().anyMatch(n -> n.matches("\\d{4}-\\d\\d-\\d\\d-t10-[0-9a-f]{16}\\.txt")), "request names carry -t10-: " + seen);
        String id = ExchangeWriter.requestId(java.time.LocalDate.of(2026, 11, 5), Duration.ofSeconds(360));
        check(id.matches("2026-11-05-t360-[0-9a-f]{16}") && id.length() == 32 && (id + ".txt").matches("[a-z0-9-]{8,64}\\.txt"), "request id " + id);
        check(ExchangeWriter.requestId(java.time.LocalDate.of(2026, 11, 5), Duration.ofMillis(400)).contains("-t1-"), "short limits round up");
        // stale files (a late answer, a request from before a restart) are deleted at start-up and after each run
        Files.writeString(ai.resolve("requests").resolve("2026-11-04-t360-0123456789abcdef.txt"), "old");
        Files.writeString(ai.resolve("responses").resolve("2026-11-04-t150-0123456789abcdee.txt"), "late");
        Files.writeString(ai.resolve("responses").resolve(".lock"), "");          // the helper's own lock: left alone
        Files.writeString(ai.resolve(".2026-11-04-t360-0123456789abcdef.tmp"), "half-written");   // the plugin's own temp file
        check(ExchangeWriter.clearStale(ai) == 3 && !Files.exists(ai.resolve(".2026-11-04-t360-0123456789abcdef.tmp")) && empty(ai.resolve("requests")) && Files.exists(ai.resolve("responses").resolve(".lock")), "clearStale");
        Files.delete(ai.resolve("responses").resolve(".lock"));
        check(ExchangeWriter.clearStale(tmp.resolve("nothing-here")) == 0, "clearStale with no folder");
        System.out.println("CommandExchangeWriterTest PASS");
    }

    /**
     * A request holds players' letters: it is never readable by everyone, not as the temp file in ai/ and not in requests/.
     * The helper reads it through the group of requests/ (here a second group of the user running the test, if there is one).
     */
    static void privateRequests(Path ai, ExchangeWriter ex, java.util.Set<String> requestModes) throws Exception {
        String own = group(ai.resolve("requests"));
        check(requestModes.equals(java.util.Set.of("rw-r----- group=" + own)), "a request is rw-r----- with the group of requests/: " + requestModes);
        // the temp file, looked at again and again while a big request is being written
        java.util.Set<String> tmpModes = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Thread watcher = Thread.ofPlatform().daemon().start(() -> {
            while (!Thread.currentThread().isInterrupted())
                try (Stream<Path> s = Files.list(ai)) {
                    for (Path p : s.toList()) if (p.getFileName().toString().endsWith(".tmp")) tmpModes.add(mode(p));
                } catch (IOException | java.io.UncheckedIOException gone) { }   // it was renamed while we looked
        });
        check(ex.write("STYLE", "x".repeat(24 << 20) + "\nbig", Duration.ofSeconds(60)).equals("DRAFT for: big"), "big round trip");
        watcher.interrupt();
        watcher.join();
        check(tmpModes.stream().allMatch(m -> m.equals("rw-------") || m.equals("rw-r-----")), "the temp request is never readable by everyone: " + tmpModes);
        System.out.println("ok exchange: requests are private (temp file seen as " + tmpModes + ")");
        // requests/ belongs to a shared group: the request takes that group, so a helper in it can read
        String other = null;
        try {
            Process id = new ProcessBuilder("id", "-Gn").redirectErrorStream(true).start();
            for (String g : new String(id.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip().split(" ")) if (!g.isBlank() && !g.equals(own)) other = g;
        } catch (IOException noIdCommand) { }
        if (other == null) { System.out.println("skipped exchange: shared group (this user has only one group)"); return; }
        var group = ai.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByGroupName(other);
        Files.getFileAttributeView(ai.resolve("requests"), java.nio.file.attribute.PosixFileAttributeView.class).setGroup(group);
        requestModes.clear();
        check(ex.write("STYLE", "facts today", Duration.ofSeconds(10)).equals("DRAFT for: facts today"), "round trip with a shared group");
        check(requestModes.equals(java.util.Set.of("rw-r----- group=" + other)), "a request takes the group of requests/: " + requestModes);
        System.out.println("ok exchange: a request takes the group of requests/ (" + other + ")");
    }

    static final boolean POSIX = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    static String group(Path p) throws IOException {
        return Files.readAttributes(p, java.nio.file.attribute.PosixFileAttributes.class).group().getName();
    }

    static String mode(Path p) throws IOException {
        return java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
    }

    static boolean empty(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }

    static void check(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }
}
