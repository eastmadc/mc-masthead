package io.github.eastmadc.masthead.construction;

import io.github.eastmadc.masthead.config.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiPredicate;

/** Who construction news may name, for the other parts of the paper that list builders (the Building Report). No server types. */
public final class Levels {
    private Levels() {}

    /** A player's level: their own choice; else public if construction.opted-in names them; else default-level. */
    static String level(String own, String name, Settings.Construction c) {
        if (own != null) return own;
        if (name != null) for (String o : c.optedIn()) if (o.equalsIgnoreCase(name)) return "public";
        return c.defaultLevel();
    }

    /**
     * (uuid, name) -> may be named: public and has had the one-time notice, from construction/players.json. A missing or
     * unreadable file names nobody. /news build forget makes a player private there at once.
     */
    public static BiPredicate<String, String> nameable(Path dataFolder, Settings.Construction c) {
        Prefs p;
        try {
            Path f = dataFolder.resolve("construction").resolve("players.json");
            p = Prefs.read(Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return (u, n) -> false;
        }
        return (u, n) -> {
            Prefs.P pp = u == null ? null : p.players.get(u);
            return pp != null && pp.noticed != null && "public".equals(level(pp.level, n, c));
        };
    }
}
