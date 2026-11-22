package io.github.eastmadc.masthead.notify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eastmadc.masthead.config.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The fixed safety path for worried letters. It does not depend on the AI, the editor desk or the day's paper.
 *
 * <p>A worried letter is reported once (by its id) the moment the mailroom stores it, and again at edition time for any
 * letter the editor desk flags. Each report goes to every channel that is set up:
 * <ul>
 *   <li>ntfy: the player's name (checked against the Minecraft name rule, else "a player") and one fixed sentence;</li>
 *   <li>Discord: one fixed sentence with no name (an admin sees who with /masthead status);</li>
 *   <li>alerts.helper-pushes-concerns: a file concern/ID.json for the host helper, written next door first and then moved in
 *       whole. The helper reads only its "name" field.</li>
 * </ul>
 * A push never carries the letter's text, the desk's note, a quote or the letter id, and the console never shows the text
 * or the name (the console log is kept forever). A report that fails waits in concern-outbox/ and is tried again.
 */
public final class Concerns {
    public static final String NAMED = "💛 A letter from %s sounded like they might be having a hard time. It was not printed. Maybe check in, or let their parent know.";
    public static final String UNNAMED = "💛 A letter sounded like a player might be having a hard time. It was not printed. An admin can see who with /masthead status.";
    public static final int KEEP_DAYS = 7;
    static final Pattern NAME = Pattern.compile("\\.?[A-Za-z0-9_]{3,16}");
    static final Pattern ID = Pattern.compile("\\d{4}-\\d\\d-\\d\\d-[0-9a-f]{8}");

    /** One worried letter. note and text go only into the helper's file (keep it in owner-only storage). */
    public record Letter(String id, String name, String note, String text) {}

    private final Path data;
    private final Logger log;
    private final Function<Settings.Alerts, Push> pushes;
    private final Object lock = new Object();

    public Concerns(Path dataFolder, Logger log, Function<Settings.Alerts, Push> pushes) {
        this.data = dataFolder;
        this.log = log;
        this.pushes = pushes;
    }

    /**
     * Does /masthead status show worried-letter names in game? Only when Discord is the only channel: its push carries no
     * name, so an admin needs another way to find out who. With ntfy or the host helper the name already went to the
     * owner, and an op in game (perhaps a friend of the writers) should not see it.
     */
    public static boolean namesInGame(Settings.Alerts a) {
        return a.discordOk() && !a.ntfyOk() && !a.helperPushesConcerns();
    }

    /** The name a push may carry. */
    public static String safeName(String name) {
        return name != null && NAME.matcher(name).matches() ? name : "a player";
    }

    // ------------------------------------------------------------ reporting (blocking: call off the main thread)
    public void report(Settings s, Letter l) {
        if (l == null || l.id() == null || !ID.matcher(l.id()).matches()) return;
        synchronized (lock) {
            if (sent().containsKey(l.id())) return;
            Path ob = outbox().resolve(l.id() + ".json");
            if (!Files.exists(ob)) {
                JsonObject o = new JsonObject();
                o.addProperty("id", l.id());
                o.addProperty("name", l.name() == null ? "" : l.name());
                o.addProperty("note", l.note() == null ? "" : l.note());
                o.addProperty("text", l.text() == null ? "" : l.text());
                o.add("done", new JsonArray());
                try { write(ob, o.toString()); }
                catch (IOException e) { log.warning("Could not save a worried letter for its alert (" + e.getClass().getSimpleName() + "); trying now anyway."); }
            }
            deliver(s, l.id(), ob, l);
        }
    }

    /**
     * Tries every waiting report again. One older than 7 days loses its text and note (the alert itself still waits).
     * Also tidies concern-sent.txt, so a name tied to a worried letter goes on time even when no new alert is sent
     * (every published edition calls this).
     */
    public void retry(Settings s) {
        synchronized (lock) {
            try { pruneSent(); } catch (IOException e) { log.warning("Could not tidy concern-sent.txt (" + e.getClass().getSimpleName() + ")."); }
            String cut = LocalDate.now().minusDays(KEEP_DAYS).toString();
            for (Path ob : waitingFiles()) {
                String id = ob.getFileName().toString().replace(".json", "");
                if (id.substring(0, 10).compareTo(cut) < 0) dropWords(ob);
                deliver(s, id, ob, null);
            }
        }
    }

    /**
     * How many reports are waiting to be sent. No lock: /masthead status calls this on the main thread while a retry may
     * be waiting on the network, and the files it reads are only ever replaced whole.
     */
    public int waiting() {
        return waitingFiles().size();
    }

    /** Names of worried letters from the last 7 days (sent or waiting), oldest first. For /masthead status in game only. */
    public List<String> recentNames(LocalDate today) {
        String cut = today.minusDays(KEEP_DAYS).toString();
        List<String> out = new ArrayList<>();   // no lock, as in waiting()
        sent().forEach((id, name) -> { if (id.substring(0, 10).compareTo(cut) >= 0) out.add(safeName(name)); });
        for (Path ob : waitingFiles()) {
            JsonObject o = read(ob);
            String id = ob.getFileName().toString();
            if (o != null && id.substring(0, 10).compareTo(cut) >= 0) out.add(safeName(str(o, "name")));
        }
        return out;
    }

    // ------------------------------------------------------------ inside
    private void deliver(Settings s, String id, Path ob, Letter given) {
        if (sent().containsKey(id)) {   // sent already; only its outbox file was left behind
            try { Files.deleteIfExists(ob); } catch (IOException ignored) { }
            return;
        }
        JsonObject o = read(ob);
        if (o == null && given == null) return;
        if (o == null) {   // the outbox file could not be written: work from memory
            o = new JsonObject();
            o.addProperty("name", given.name());
            o.addProperty("note", given.note());
            o.addProperty("text", given.text());
            o.add("done", new JsonArray());
        }
        List<String> done = new ArrayList<>();
        if (o.get("done") instanceof JsonArray a) for (JsonElement e : a) done.add(e.getAsString());
        Push p = pushes.apply(s.alerts());
        boolean ntfy = p != null && p.hasNtfy(), discord = p != null && p.hasDiscord(), helper = s.alerts().helperPushesConcerns();
        if (!ntfy && !discord && !helper) {
            log.warning("A worried-letter alert is waiting, but no alert channel is set up (see alerts in config.yml).");
            return;
        }
        String name = safeName(str(o, "name")), title = s.paper().name() + ": worried letter";
        if (ntfy && !done.contains("ntfy") && p.sendNtfy(title, String.format(NAMED, name), "high", List.of("yellow_heart"))) done.add("ntfy");
        if (discord && !done.contains("discord") && p.sendDiscord(title, UNNAMED)) done.add("discord");
        if (helper && !done.contains("helper")) {
            try {
                helperFile(id, o);
                done.add("helper");
            } catch (IOException e) {
                log.warning("Could not write a worried-letter file for the host helper (" + e.getClass().getSimpleName() + ").");
            }
        }
        boolean all = (!ntfy || done.contains("ntfy")) && (!discord || done.contains("discord")) && (!helper || done.contains("helper"));
        if (all) {
            try {
                io.github.eastmadc.masthead.pipeline.Housekeeping.createPrivate(sentFile());   // 600 before a name is in it
                Files.writeString(sentFile(), id + " " + (NAME.matcher(str(o, "name")).matches() ? str(o, "name") : "-") + "\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                privateFile(sentFile());
                Files.deleteIfExists(ob);
                pruneSent();
            } catch (IOException e) {
                // the channels that worked are saved, so a retry never sends them again
                saveDone(ob, o, done);
                log.warning("Could not note a sent worried-letter alert (" + e.getClass().getSimpleName() + ").");
            }
            log.info("Sent a worried-letter alert.");
        } else {
            saveDone(ob, o, done);
            log.warning("A worried-letter alert could not be sent yet; it will be tried again (" + waitingFiles().size() + " waiting).");
        }
    }

    private void saveDone(Path ob, JsonObject o, List<String> done) {
        JsonArray d = new JsonArray();
        done.forEach(d::add);
        o.add("done", d);
        try { write(ob, o.toString()); } catch (IOException ignored) { }
    }

    /** An alert still waiting after 7 days keeps only its id, name and done list: the letter's words go. */
    private void dropWords(Path ob) {
        JsonObject o = read(ob);
        if (o == null || (str(o, "text").isEmpty() && str(o, "note").isEmpty())) return;
        o.addProperty("text", "");
        o.addProperty("note", "");
        try { write(ob, o.toString()); } catch (IOException e) { log.warning("Could not clear an old worried-letter alert (" + e.getClass().getSimpleName() + ")."); }
    }

    /** concern/ID.json for the host helper: [{"id","name","note","text"}], the same shape 2.0.0 wrote. */
    private void helperFile(String id, JsonObject o) throws IOException {
        JsonObject x = new JsonObject();
        x.addProperty("id", id);
        x.addProperty("name", str(o, "name"));
        x.addProperty("note", str(o, "note"));
        x.addProperty("text", str(o, "text"));
        JsonArray a = new JsonArray();
        a.add(x);
        Path stage = data.resolve("concern-new"), dir = data.resolve("concern");
        Files.createDirectories(stage);
        Files.createDirectories(dir);
        privateDir(stage);
        privateDir(dir);
        Path tmp = stage.resolve(id + ".json.tmp");
        Files.writeString(tmp, a.toString(), StandardCharsets.UTF_8);
        privateFile(tmp);
        Files.move(tmp, dir.resolve(id + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private Path outbox() { return data.resolve("concern-outbox"); }

    private Path sentFile() { return data.resolve("concern-sent.txt"); }

    private List<Path> waitingFiles() {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(outbox(), LinkOption.NOFOLLOW_LINKS)) return out;
        try (Stream<Path> s = Files.list(outbox())) {
            for (Path p : (Iterable<Path>) s::iterator)
                if (p.getFileName().toString().matches("\\d{4}-\\d\\d-\\d\\d-[0-9a-f]{8}\\.json")) out.add(p);
        } catch (IOException ignored) { }
        out.sort(null);
        return out;
    }

    /** id -> name of alerts already sent (last 7 days). */
    private Map<String, String> sent() {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            if (Files.isRegularFile(sentFile(), LinkOption.NOFOLLOW_LINKS) && Files.size(sentFile()) < 1 << 20)
                for (String l : Files.readAllLines(sentFile(), StandardCharsets.UTF_8)) {
                    String[] p = l.strip().split(" ", 2);
                    if (p.length == 2 && ID.matcher(p[0]).matches()) m.put(p[0], p[1]);
                }
        } catch (IOException ignored) { }
        return m;
    }

    /** Keeps concern-sent.txt to the last 10 days. Never creates it; deletes it when nothing is left. */
    private void pruneSent() throws IOException {
        if (!Files.isRegularFile(sentFile(), LinkOption.NOFOLLOW_LINKS)) return;
        String cut = LocalDate.now().minusDays(KEEP_DAYS + 3).toString();
        StringBuilder b = new StringBuilder();
        sent().forEach((id, name) -> { if (id.substring(0, 10).compareTo(cut) >= 0) b.append(id).append(' ').append(name).append('\n'); });
        if (b.isEmpty()) Files.deleteIfExists(sentFile());
        else if (!b.toString().equals(Files.readString(sentFile(), StandardCharsets.UTF_8))) write(sentFile(), b.toString());
    }

    private void write(Path f, String text) throws IOException {
        Files.createDirectories(f.getParent());
        privateDir(f.getParent().equals(data) ? null : f.getParent());
        io.github.eastmadc.masthead.pipeline.Housekeeping.writePrivate(f, text);   // 600 before any text is in it, then moved in whole
    }

    private static JsonObject read(Path f) {
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || Files.size(f) > 64 * 1024) return null;
            JsonElement e = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    // best effort, like Housekeeping
    private static void privateFile(Path f) {
        try { Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException | IOException | SecurityException ignored) { }
    }

    private static void privateDir(Path d) {
        if (d == null) return;
        try { Files.setPosixFilePermissions(d, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException | IOException | SecurityException ignored) { }
    }
}
