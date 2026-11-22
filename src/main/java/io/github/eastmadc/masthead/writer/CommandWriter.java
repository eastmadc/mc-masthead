package io.github.eastmadc.masthead.writer;

import io.github.eastmadc.masthead.pipeline.Housekeeping;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * writer.mode: command. Runs an argv (no shell; e.g. {@code claude -p --tools "" ...}), writes {@link Writer#join system+user}
 * to its stdin, and returns its stdout. stderr is appended to a log file. On timeout the whole process tree is killed.
 * Non-zero exit, empty output, or output over {@link #MAX_STDOUT} bytes throw.
 */
public final class CommandWriter implements Writer {
    public static final int MAX_STDOUT = 200 * 1024;
    static final int MAX_STDERR_PER_RUN = 64 * 1024;
    static final long MAX_LOG_BYTES = 1024 * 1024;

    private final List<String> command;
    private final Path stderrLog;

    /** @param command argv, first element the program; @param stderrLog file stderr is appended to (rotated to .1 at 1 MB). */
    public CommandWriter(List<String> command, Path stderrLog) {
        if (command == null || command.isEmpty() || command.get(0).isBlank())
            throw new IllegalArgumentException("writer.command is empty");
        this.command = List.copyOf(command);
        this.stderrLog = stderrLog;
    }

    @Override
    public String name() {
        return "command (" + Path.of(command.get(0)).getFileName() + ")";
    }

    @Override
    public String write(String system, String user, Duration timeout) throws Exception {
        byte[] input = Writer.join(system, user).getBytes(StandardCharsets.UTF_8);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        Capture out = new Capture(p.getInputStream(), MAX_STDOUT);
        Capture err = new Capture(p.getErrorStream(), MAX_STDERR_PER_RUN);
        Thread tOut = Thread.ofPlatform().daemon().name("masthead-cmd-out").start(out);
        Thread tErr = Thread.ofPlatform().daemon().name("masthead-cmd-err").start(err);
        Thread tIn = Thread.ofPlatform().daemon().name("masthead-cmd-in").start(() -> {
            try (OutputStream os = p.getOutputStream()) {
                os.write(input);
            } catch (IOException ignored) {
                // the program closed stdin early; its exit code tells the story
            }
        });
        boolean done;
        try {
            done = p.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            killTree(p);
            throw e;
        }
        if (!done) killTree(p);
        tIn.join(2000);
        tOut.join(5000);
        tErr.join(5000);
        logStderr(err, done ? p.exitValue() : -1);
        if (!done) throw new java.util.concurrent.TimeoutException("writer command timed out after " + timeout.toSeconds() + "s (killed)");
        int code = p.exitValue();
        if (code != 0) throw new IOException("writer command exited with code " + code + " (stderr in " + stderrLog + ")");
        if (out.overflow.get()) throw new IOException("writer command printed more than " + MAX_STDOUT + " bytes");
        String text = out.buf.toString(StandardCharsets.UTF_8);
        if (text.isBlank()) throw new IOException("writer command printed nothing");
        return text;
    }

    static void killTree(Process p) {
        List<ProcessHandle> kids = p.descendants().toList();
        p.destroyForcibly();
        for (ProcessHandle h : kids) h.destroyForcibly();
        try {
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void logStderr(Capture err, int code) {
        if (stderrLog == null) return;
        try {
            Files.createDirectories(stderrLog.toAbsolutePath().getParent());
            if (Files.exists(stderrLog) && Files.size(stderrLog) > MAX_LOG_BYTES)
                Files.move(stderrLog, stderrLog.resolveSibling(stderrLog.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            String head = "=== " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + " " + command.get(0)
                    + " exit=" + code + (err.overflow.get() ? " (stderr truncated)" : "") + "\n";
            Housekeeping.createPrivate(stderrLog);   // the AI program's errors can repeat parts of the prompt
            Files.write(stderrLog, (head + err.buf.toString(StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // logging stderr is best effort
        }
    }

    /** Reads a stream to the end, keeping at most {@code cap} bytes (keeps draining so the child never blocks). */
    static final class Capture implements Runnable {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final AtomicBoolean overflow = new AtomicBoolean();
        private final InputStream in;
        private final int cap;

        Capture(InputStream in, int cap) {
            this.in = in;
            this.cap = cap;
        }

        @Override
        public void run() {
            byte[] b = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(b)) > 0) {
                    int room = cap - buf.size();
                    if (room > 0) buf.write(b, 0, Math.min(room, n));
                    if (n > room) overflow.set(true);
                }
            } catch (IOException ignored) {
                // stream closed when the process was killed
            }
        }
    }
}
