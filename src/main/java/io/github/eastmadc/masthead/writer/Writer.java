package io.github.eastmadc.masthead.writer;

import java.time.Duration;

/**
 * Something that turns a prompt into raw text (the newspaper draft, or the editor desk's reply).
 *
 * <p>The prompt comes in two parts so the API writer can cache the long, stable part:
 * <ul>
 *   <li>{@code system}: the style guide / standing instructions (same text every day for a given config). AnthropicWriter
 *       sends it as a cached system block.</li>
 *   <li>{@code user}: today's facts and the task. Changes every call.</li>
 * </ul>
 * Writers that only take one text (command, exchange) send {@code system + "\n\n" + user} (see {@link #join}).
 *
 * <p>{@code write} blocks (call it off the main server thread), returns the model's raw text, and throws on any failure:
 * timeout, process error, HTTP error after retries, refusal ({@link RefusalException}), or empty output. The caller then
 * falls back to the plain edition. {@link NoWriter} always throws {@link UnsupportedOperationException}.
 *
 * <p>{@link #close()} releases threads/connections; call it when the plugin disables. Default: nothing to release.
 */
public interface Writer extends AutoCloseable {
    String name();

    String write(String system, String user, Duration timeout) throws Exception;

    /**
     * The day's first draft. Same as {@link #write} unless the writer can do it cheaper and slower (anthropic batch), in
     * which case it may take longer than {@code timeout}.
     */
    default String writePaper(String system, String user, Duration timeout) throws Exception {
        return write(system, user, timeout);
    }

    @Override
    default void close() {}

    /**
     * An API key, first match wins: the key file, then the environment variable, then the inline config value. "" if none.
     * A key file that is set but cannot be read throws (we never fall through to another key). A key with a line break or
     * other control character inside throws too, before it can reach an HTTP header (whose error would quote it). Never
     * logs the key.
     */
    static String apiKey(String file, String envName, String inline, String configPrefix, java.util.logging.Logger log) throws java.io.IOException {
        if (file != null && !file.isBlank()) {
            java.nio.file.Path p = java.nio.file.Path.of(file.strip());
            String k;
            try { k = java.nio.file.Files.readString(p).replaceFirst("^\uFEFF", "").strip(); }   // Windows "UTF-8" adds a BOM
            catch (java.io.IOException | RuntimeException e) { throw new java.io.IOException(configPrefix + "api-key-file can't be read (" + e.getClass().getSimpleName() + ")"); }
            if (k.isEmpty()) throw new java.io.IOException(configPrefix + "api-key-file is empty");
            try {
                var perms = java.nio.file.Files.getPosixFilePermissions(p);
                if (perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ) || perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ))
                    if (log != null) log.warning(configPrefix + "api-key-file can be read by other users; make it readable only by the server's user (chmod 600)");
            } catch (UnsupportedOperationException | java.io.IOException ignored) { }   // not a POSIX disk
            return oneLine(k, configPrefix + "api-key-file");
        }
        String env = System.getenv(envName);
        if (env != null && !env.isBlank()) return oneLine(env.strip(), "the " + envName + " environment variable");
        if (inline != null && !inline.isBlank()) {
            if (log != null) log.warning(configPrefix + "api-key is in config.yml, where anyone with the plugin folder can read it; api-key-file is safer");
            return oneLine(inline.strip(), configPrefix + "api-key");
        }
        return "";
    }

    /**
     * The key, or an error that names where it came from but never shows it. Printable ASCII only: a zero-width space or a
     * BOM would make the HTTP client refuse the header with a message that quotes the whole key.
     */
    private static String oneLine(String key, String where) throws java.io.IOException {
        if (key.chars().anyMatch(ch -> ch < 0x21 || ch > 0x7e)) throw new java.io.IOException(where + " must hold only the key, on one line");
        return key;
    }

    /** The single-text form used by command and exchange writers. */
    static String join(String system, String user) {
        if (system == null || system.isBlank()) return user == null ? "" : user;
        return system + "\n\n" + (user == null ? "" : user);
    }
}
