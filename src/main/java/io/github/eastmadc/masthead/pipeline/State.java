package io.github.eastmadc.masthead.pipeline;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * The plugin's small state file, state.yml (not the owner's config). Same keys and format as 2.0.0 (last-edition,
 * qr-map-id, mailroom-since), so going back to 2.0.0 reads it fine; 2.0.1 adds the
 * edition-attempt-* keys, which 2.0.0 ignores. One lock for every reader and writer (main thread and the edition thread),
 * and saved through a temporary file so a crash never leaves it half-written. It is read from disk once and then kept in
 * memory (the schedule asks every 30 seconds); a value that could not be saved still counts for this run.
 */
public final class State {
    private State() {}

    public static final Object LOCK = new Object();
    private static final Map<Path, String> MEMORY = new java.util.HashMap<>();   // data folder -> state.yml text; under LOCK

    /** A copy of the state: changing it changes nothing (use set / setAll). */
    public static YamlConfiguration load(Path dataFolder) {
        synchronized (LOCK) {
            YamlConfiguration y = new YamlConfiguration();
            try {
                y.loadFromString(text(dataFolder));
            } catch (org.bukkit.configuration.InvalidConfigurationException e) {
                // unreadable: empty, as YamlConfiguration.loadConfiguration would give
            }
            return y;
        }
    }

    private static String text(Path dataFolder) {
        return MEMORY.computeIfAbsent(dataFolder.toAbsolutePath().normalize(),
                k -> YamlConfiguration.loadConfiguration(k.resolve("state.yml").toFile()).saveToString());
    }

    /** Sets one key (null removes it). Returns false if saving failed. */
    public static boolean set(Path dataFolder, String key, Object value) {
        return setAll(dataFolder, java.util.Collections.singletonMap(key, value));
    }

    /** Sets several keys in one save (a null value removes the key). Returns false if saving failed. */
    public static boolean setAll(Path dataFolder, Map<String, Object> values) {
        synchronized (LOCK) {
            File f = dataFolder.resolve("state.yml").toFile();
            YamlConfiguration st = load(dataFolder);
            values.forEach(st::set);
            String text = st.saveToString();
            MEMORY.put(dataFolder.toAbsolutePath().normalize(), text);
            try {
                Files.createDirectories(dataFolder);
                Path tmp = dataFolder.resolve("state.yml.new");
                Files.writeString(tmp, text, StandardCharsets.UTF_8);
                Housekeeping.privateFile(tmp);
                Files.move(tmp, f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }
}
