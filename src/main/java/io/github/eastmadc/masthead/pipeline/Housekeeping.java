package io.github.eastmadc.masthead.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eastmadc.masthead.collect.StatsSnapshot;
import io.github.eastmadc.masthead.facts.FactsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Keeping the plugin folder tidy and private:
 * <ul>
 *   <li>private files: the plugin's own data files are mode 600 and its data folders 700, best effort (skipped where the
 *       disk has no Unix permissions, e.g. Windows). Never applied to editions/, the web/ folder or the web.folder output,
 *       which another web server may need to read.</li>
 *   <li>retention: the day's facts (data/) and stats snapshots (stats/) older than privacy.retention-days are deleted. The
 *       editions/ archive is kept: it is the paper.</li>
 *   <li>a one-time clean-up of files written by 2.0.0: letters and reader names out of data/DATE.json, letter text out of
 *       mailbag.json.</li>
 * </ul>
 */
public final class Housekeeping {
    private Housekeeping() {}

    static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");
    static final Set<PosixFilePermission> DIR = PosixFilePermissions.fromString("rwx------");

    /**
     * Folders that hold player data (everything inside them is made private). Not ai/: the public exchange helper runs as
     * its own user and must be able to read the requests and write the answers.
     */
    static final List<String> DIRS = List.of("data", "stats", "mailroom", "events", "blotter", "health", "desk", "concern", "concern-new",
            "concern-outbox", "preview");
    /** Files in the plugin folder itself. */
    static final List<String> FILES = List.of("config.yml", "state.yml", "mailbag.json", "mailbag-done.txt", "mailbag-printed.jsonl",
            "mailbag-carry.txt", "notified.txt", "ip-players.txt", "today.json", "last-edition.json", "web-access.log", "writer-stderr.log",
            "concern-sent.txt");

    /**
     * Writes a private file safely: the text goes into f.new, made mode 600 before any text is in it (where the disk has
     * POSIX modes), which is then renamed over f in one step. So the file is never readable by others, not even for a
     * moment, and never half-written.
     */
    public static void writePrivate(Path f, String text) throws IOException {
        Path tmp = f.resolveSibling(f.getFileName() + ".new");
        Files.deleteIfExists(tmp);
        try {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(FILE));
        } catch (UnsupportedOperationException e) {
            Files.createFile(tmp);
        }
        Files.writeString(tmp, text, java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE);
        Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Makes f as an empty private (600) file if it is not there yet, so text written or appended next is never readable
     * by others. Where the disk has no POSIX modes this does nothing: the write that follows makes the file.
     */
    public static void createPrivate(Path f) throws IOException {
        try {
            Files.createFile(f, PosixFilePermissions.asFileAttribute(FILE));
        } catch (java.nio.file.FileAlreadyExistsException | UnsupportedOperationException ignored) { }
    }

    /**
     * Makes folder d, and any missing folder above it, owner-only (700) from the start. A folder that is already there
     * keeps its mode. Where the disk has no POSIX modes the folder is made the plain way.
     */
    public static void createPrivateDirs(Path d) throws IOException {
        try {
            Files.createDirectories(d, PosixFilePermissions.asFileAttribute(DIR));
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(d);
        }
    }

    /** Mode 600 on a file, best effort. */
    public static void privateFile(Path f) {
        try {
            if (Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) Files.setPosixFilePermissions(f, FILE);
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) { }
    }

    /** Mode 700 on a folder, best effort. */
    public static void privateDir(Path d) {
        try {
            if (Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) Files.setPosixFilePermissions(d, DIR);
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) { }
    }

    /**
     * Makes the plugin's data private (see the class comment). webFolder: the configured web.folder output (may be null);
     * it and editions/ and web/ are never touched, even if a listed folder contains them.
     */
    public static void tighten(Path data, Path webFolder) {
        List<Path> keepOpen = new java.util.ArrayList<>(List.of(data.resolve("editions"), data.resolve("web")));
        if (webFolder != null) keepOpen.add(webFolder.toAbsolutePath().normalize());
        for (String d : DIRS) tightenTree(data.resolve(d), keepOpen, 0);
        for (String f : FILES) privateFile(data.resolve(f));
    }

    private static void tightenTree(Path p, List<Path> keepOpen, int depth) {
        Path abs = p.toAbsolutePath().normalize();
        for (Path k : keepOpen) if (abs.startsWith(k.toAbsolutePath().normalize())) return;
        if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) { privateFile(p); return; }
        if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) || depth > 3) return;
        privateDir(p);
        try (Stream<Path> s = Files.list(p)) {
            for (Path c : (Iterable<Path>) s::iterator) tightenTree(c, keepOpen, depth + 1);
        } catch (IOException ignored) { }
    }

    /** Deletes data/DATE.json and stats/DATE folders older than retentionDays (and stats copies left half-done). */
    public static int prune(Path data, LocalDate today, int retentionDays) {
        String cut = today.minusDays(retentionDays).toString();
        int n = 0;
        Path facts = data.resolve("data");
        if (Files.isDirectory(facts, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> s = Files.list(facts)) {
                for (Path f : (Iterable<Path>) s::iterator) {
                    String name = f.getFileName().toString();
                    if (name.matches("\\d{4}-\\d\\d-\\d\\d\\.json") && name.substring(0, 10).compareTo(cut) < 0 && Files.deleteIfExists(f)) n++;
                }
            } catch (IOException ignored) { }
        }
        Path stats = data.resolve("stats");
        if (Files.isDirectory(stats, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> s = Files.list(stats)) {
                for (Path f : (Iterable<Path>) s::iterator) {
                    String name = f.getFileName().toString();
                    boolean old = name.matches("\\d{4}-\\d\\d-\\d\\d") && name.compareTo(cut) < 0;
                    boolean partial = name.matches("\\.\\d{4}-\\d\\d-\\d\\d\\.partial-\\d+") && name.substring(1, 11).compareTo(today.toString()) < 0;
                    if (old || partial) { StatsSnapshot.deleteTree(f); n++; }
                }
            } catch (IOException ignored) { }
        }
        return n;
    }

    /**
     * One-time clean-up of what 2.0.0 left behind: data/DATE.json without letters and reader names, mailbag.json without
     * the worried letters' text and notes. Safe to run every start-up (it changes nothing the second time). Returns how
     * many files changed.
     */
    public static int scrubOld(Path data) {
        int n = 0;
        Path facts = data.resolve("data");
        if (Files.isDirectory(facts, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> s = Files.list(facts)) {
                for (Path f : (Iterable<Path>) s::iterator)
                    if (f.getFileName().toString().matches("\\d{4}-\\d\\d-\\d\\d\\.json") && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)
                            && FactsBuilder.rewriteRedacted(f)) n++;
            } catch (IOException ignored) { }
        }
        Path mb = data.resolve("mailbag.json");
        try {
            if (Files.isRegularFile(mb, LinkOption.NOFOLLOW_LINKS) && Files.size(mb) < 4 << 20) {
                JsonElement el = JsonParser.parseString(Files.readString(mb, StandardCharsets.UTF_8));
                if (el.isJsonObject() && el.getAsJsonObject().get("concern") instanceof JsonArray c) {
                    boolean changed = false;
                    JsonArray slim = new JsonArray();
                    for (JsonElement e : c) {
                        if (!e.isJsonObject()) { changed = true; continue; }
                        JsonObject o = e.getAsJsonObject(), x = new JsonObject();
                        if (o.has("id")) x.add("id", o.get("id"));
                        if (o.has("name")) x.add("name", o.get("name"));
                        if (o.size() != x.size()) changed = true;
                        slim.add(x);
                    }
                    if (changed) {
                        el.getAsJsonObject().add("concern", slim);
                        writePrivate(mb, io.github.eastmadc.masthead.edition.PyJson.dumps(el.getAsJsonObject(), false));
                        n++;
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) { }
        return n;
    }
}
