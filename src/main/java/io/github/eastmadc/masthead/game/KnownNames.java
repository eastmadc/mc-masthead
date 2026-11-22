package io.github.eastmadc.masthead.game;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The names of everyone who has played here, read in the background. On Paper 26.x each OfflinePlayer.getName() reads
 * and unpacks that player's file, so reading them all on the main thread (on every keystroke of a tab complete, say)
 * would stall the server. The list is at most 10 minutes old; add the online players for anyone newer.
 */
public final class KnownNames {
    private KnownNames() {}

    private static final long MAX_AGE_MS = 10 * 60_000L;
    private static volatile List<String> names = List.of();
    private static volatile long readAt;
    private static final AtomicBoolean reading = new AtomicBoolean();

    /** The last list read; starts a fresh read in the background when it is old. Never reads a file itself. */
    public static List<String> get(Plugin plugin) {
        if (System.currentTimeMillis() - readAt > MAX_AGE_MS) refresh(plugin);
        return names;
    }

    /** Reads every player's name off the main thread (at start-up, and when the list is old). */
    public static void refresh(Plugin plugin) {
        if (!reading.compareAndSet(false, true)) return;
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    List<String> out = new ArrayList<>();
                    for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                        String n = op.getName();   // once: each call reads the file again
                        if (n != null) out.add(n);
                    }
                    names = List.copyOf(out);
                    readAt = System.currentTimeMillis();
                } catch (RuntimeException | LinkageError e) {
                    plugin.getLogger().warning("could not read the player names: " + e);
                } finally {
                    reading.set(false);
                }
            });
        } catch (RuntimeException e) {   // the plugin is being turned off
            reading.set(false);
        }
    }
}
