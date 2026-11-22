package io.github.eastmadc.masthead.collect;

import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;

/**
 * Copies every player's statistics file (and the server's usercache.json) into stats/DATE/ at edition time, so the paper
 * can compare today with the last edition. Works with both world layouts: <world>/stats (1.21) and <world>/players/stats (26.x).
 */
public final class StatsSnapshot {
    private StatsSnapshot() {}

    /** Main thread: ask the server to write current stats of online players to disk. Call a tick before {@link #copy}. */
    public static void saveOnlinePlayers() {
        try { Bukkit.getServer().savePlayers(); } catch (Throwable ignored) { }
    }

    /** Finds the stats folder once (main thread is fine: only file checks). */
    public static Path statsFolder() {
        for (File w = Bukkit.getWorlds().get(0).getWorldFolder().getAbsoluteFile(); w != null; w = w.getParentFile()) {
            for (String sub : new String[]{"players/stats", "stats"}) {
                File c = new File(w, sub);
                if (c.isDirectory()) return c.toPath();
            }
            if (new File(w, "server.properties").isFile()) break;
        }
        return null;
    }

    /**
     * Off the main thread: takes stats/DATE. Copies into a hidden ".DATE.partial-N" folder first and renames it at the end,
     * so a copy that stops halfway never looks like a finished snapshot. Returns how many player files were copied.
     */
    public static int copy(Path statsFolder, Path serverRoot, Path dataFolder, LocalDate date) throws IOException {
        Path stats = dataFolder.resolve("stats"), dst = stats.resolve(date.toString());
        io.github.eastmadc.masthead.pipeline.Housekeeping.createPrivateDirs(stats);
        Path part = stats.resolve("." + date + ".partial-" + System.nanoTime());
        int n;
        try {
            n = copyInto(statsFolder, serverRoot, part);
            Files.move(part, dst, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteTree(part);
            throw e;
        }
        return n;
    }

    /** Copies the stats files and usercache.json into out (created if needed). Returns how many player files were copied. */
    public static int copyInto(Path statsFolder, Path serverRoot, Path out) throws IOException {
        Files.createDirectories(out);
        int n = 0;
        if (statsFolder != null && Files.isDirectory(statsFolder)) {
            try (var files = Files.list(statsFolder)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    String name = f.getFileName().toString();
                    if (!name.matches("[0-9a-f-]{36}\\.json") || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || Files.size(f) > 4 << 20) continue;
                    Files.copy(f, out.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    if (++n >= 2000) break;
                }
            }
        }
        Path uc = serverRoot.resolve("usercache.json");
        if (Files.isRegularFile(uc, LinkOption.NOFOLLOW_LINKS) && Files.size(uc) < 4 << 20)
            Files.copy(uc, out.resolve("usercache.json"), StandardCopyOption.REPLACE_EXISTING);
        return n;
    }

    /** Deletes a folder and what is in it (no links followed). */
    public static void deleteTree(Path dir) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) { Files.deleteIfExists(dir); return; }
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) deleteTree(f);
        }
        Files.deleteIfExists(dir);
    }
}
