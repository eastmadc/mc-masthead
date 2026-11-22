package io.github.eastmadc.masthead.writer;

import io.github.eastmadc.masthead.pipeline.Housekeeping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * writer.mode: exchange. For servers where the plugin can't run the AI itself (e.g. the AI CLI is logged in as another
 * user). Writes {@code <aiDir>/requests/<id>.txt} atomically, then waits for a host-side helper
 * ({@code tools/masthead-exchange.sh}) to write {@code <aiDir>/responses/<id>.txt} (or {@code <id>.err} on failure).
 * Both files are deleted afterwards, also on timeout. Request ids match {@code ^[a-z0-9-]{8,64}$} and look like
 * {@code 2026-11-05-t360-0123456789abcdef}: the date, then "t" and this request's own time limit in seconds (so the helper
 * can stop Claude in time and skip a request that is already too old), then 16 random hex characters.
 * A request holds players' letters, so it is never readable by everyone: only by the server's user and the group of
 * requests/ (see {@link #shareWithHelper}).
 */
public final class ExchangeWriter implements Writer {
    public static final Pattern ID = Pattern.compile("[a-z0-9-]{8,64}");
    static final long MAX_RESPONSE = 200 * 1024;
    private static final SecureRandom RNG = new SecureRandom();

    private final Path aiDir;
    private final Path requests;
    private final Path responses;
    private final Duration poll;

    public ExchangeWriter(Path aiDir) {
        this(aiDir, Duration.ofSeconds(2));
    }

    ExchangeWriter(Path aiDir, Duration poll) {
        this.aiDir = aiDir;
        this.requests = aiDir.resolve("requests");
        this.responses = aiDir.resolve("responses");
        this.poll = poll;
    }

    @Override
    public String name() {
        return "exchange";
    }

    @Override
    public String write(String system, String user, Duration timeout) throws Exception {
        makeFolders(aiDir);
        String id = requestId(LocalDate.now(), timeout);
        Path req = requests.resolve(id + ".txt");
        Path resp = responses.resolve(id + ".txt");
        Path err = responses.resolve(id + ".err");
        // Written next to requests/, not in it: the host's path unit watches requests/, and a half-written file there
        // would start the helper before the real request exists (it could then miss it).
        Path tmp = aiDir.resolve("." + id + ".tmp");
        try {
            Housekeeping.createPrivate(tmp);   // owner-only before any text is in it: other users can list ai/
            Files.writeString(tmp, Writer.join(system, user), StandardCharsets.UTF_8);
            shareWithHelper(tmp);
            try {
                Files.move(tmp, req, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, req);
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                if (Files.isRegularFile(err, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("exchange helper reported a failure: " + readCapped(err, 500).strip());
                if (Files.isRegularFile(resp, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.size(resp) > MAX_RESPONSE) throw new IOException("exchange response larger than " + MAX_RESPONSE + " bytes");
                    String text = Files.readString(resp, StandardCharsets.UTF_8);
                    if (text.isBlank()) throw new IOException("exchange response was empty");
                    return text;
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new TimeoutException("no exchange response after " + timeout.toSeconds()
                        + "s - is the host helper (tools/masthead-exchange.sh) running?");
                Thread.sleep(Math.min(poll.toMillis(), Math.max(1, left / 1_000_000)));
            }
        } finally {
            for (Path p : new Path[]{tmp, req, resp, err}) Files.deleteIfExists(p);
        }
    }

    /**
     * requests/ and responses/. Also made as soon as writer.mode is exchange (server start and /masthead reload): the
     * helper stops without them, and the owner sets their group and mode before the first paper.
     */
    public static void makeFolders(Path aiDir) throws IOException {
        Files.createDirectories(aiDir.resolve("requests"));
        Files.createDirectories(aiDir.resolve("responses"));
    }

    /**
     * Lets the host helper read the finished request without making it readable by everyone: the file takes the group of
     * requests/ and mode 640. (A file renamed into a folder keeps its own group, even when the folder is setgid.) A helper
     * that runs as another user must share that group with the server's user; root and the server's own user always can.
     */
    private void shareWithHelper(Path tmp) throws IOException {
        PosixFileAttributeView v = Files.getFileAttributeView(tmp, PosixFileAttributeView.class);
        if (v == null) return;   // no POSIX modes on this disk
        try {
            v.setGroup(Files.readAttributes(requests, PosixFileAttributes.class).group());
        } catch (IOException ignored) {
            // the server's user is not in that group: the file keeps the server's own group
        }
        v.setPermissions(PosixFilePermissions.fromString("rw-r-----"));
    }

    /** "2026-11-05-t360-0123456789abcdef": the date, the time limit in whole seconds (at least 1), 16 random hex. */
    static String requestId(LocalDate day, Duration timeout) {
        long secs = Math.max(1, (timeout.toMillis() + 999) / 1000);
        return day + "-t" + secs + "-" + HexFormat.of().formatHex(randomBytes());
    }

    /**
     * Deletes leftover request and response files (a request whose run ended, a late answer nobody will read, the plugin's
     * own half-written request). The helper's own files (its lock, its temporary files) are left alone. Call it only when
     * no edition is running: at start-up and after each run. Returns how many files were deleted.
     */
    public static int clearStale(Path aiDir) {
        int n = 0;
        if (Files.isDirectory(aiDir, LinkOption.NOFOLLOW_LINKS)) try (var files = Files.list(aiDir)) {
            for (Path f : (Iterable<Path>) files::iterator)
                if (f.getFileName().toString().matches("\\.[a-z0-9-]{8,64}\\.tmp") && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) && Files.deleteIfExists(f)) n++;
        } catch (IOException ignored) { }
        for (String sub : new String[]{"requests", "responses"}) {
            Path d = aiDir.resolve(sub);
            if (!Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var files = Files.list(d)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    String name = f.getFileName().toString();
                    boolean ours = name.matches("[a-z0-9-]{8,64}\\.(txt|err)") || (sub.equals("requests") && name.matches("\\.[a-z0-9-]{8,64}\\.tmp"));
                    if (ours && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) && Files.deleteIfExists(f)) n++;
                }
            } catch (IOException ignored) { }
        }
        return n;
    }

    private static byte[] randomBytes() {
        byte[] b = new byte[8];
        RNG.nextBytes(b);
        return b;
    }

    private static String readCapped(Path p, int max) throws IOException {
        try (var in = Files.newInputStream(p)) {
            return new String(in.readNBytes(max), StandardCharsets.UTF_8);
        }
    }
}
