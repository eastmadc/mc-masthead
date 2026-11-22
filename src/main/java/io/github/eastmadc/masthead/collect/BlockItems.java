package io.github.eastmadc.masthead.collect;

import org.bukkit.Material;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Which items are placeable blocks, asked from the running server (so new blocks in a new game version count without a
 * plugin update). The Building Report counts only these from the "used" statistics: tools, food and buckets are not
 * building. Main thread.
 */
public final class BlockItems {
    private BlockItems() {}

    /** Item keys like "oak_planks". Empty if the server could not be asked (then there is no Building Report). */
    public static Set<String> fromServer() {
        Set<String> out = new HashSet<>();
        try {
            for (Material m : Material.values()) {
                if (!m.isLegacy() && !m.isAir() && m.isItem() && m.isBlock()) out.add(m.getKey().getKey().toLowerCase(Locale.ROOT));
            }
        } catch (RuntimeException | LinkageError e) {
            return Set.of();
        }
        return Set.copyOf(out);
    }
}
