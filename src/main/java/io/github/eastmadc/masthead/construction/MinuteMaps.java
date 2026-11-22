package io.github.eastmadc.masthead.construction;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The open minute: what each player placed and broke per chunk section, and what explosions, fire and golem builds took,
 * counted per 4-block cube. Main thread only, no locks; swap() hands the whole minute to the IO thread. No server types,
 * so it is tested without a server.
 */
final class MinuteMaps {
    /** Class index inside a cube: BUILT, LOG, PLANT. */
    static int ci(byte cls) { return cls == KeyRules.BUILT ? 0 : cls == KeyRules.LOG ? 1 : 2; }

    /** One player in one section for one minute. */
    static final class Acc {
        final UUID player;
        final String name;
        int placed, broken;
        byte yMin = 15, yMax = 0;
        short[] sub;                       // net per (class, sub-cube): 3 x 64
        short[] gnd;                       // natural ground broken per sub-cube (never netted against placements)
        HashMap<Integer, int[]> mats;      // net per material ordinal
        HashMap<Integer, int[]> gp;        // ground placed this minute, not yet broken, per (ordinal << 6 | sub-cube)
        short overAir, water, ag;          // BUILT over air or fluid; water poured; BUILT placed against a crafted block in another cube
        long[] agc;                        // up to 4 distinct against-cubes: section key << 6 | sub-cube
        byte agn;

        Acc(UUID player, String name) { this.player = player; this.name = name; }

        void add(byte cls, int sc, int delta, int ordinal) {
            if (sub == null) sub = new short[3 * 64];
            sub[ci(cls) * 64 + sc] += (short) delta;
            if (mats == null) mats = new HashMap<>();
            mats.computeIfAbsent(ordinal, k -> new int[1])[0] += delta;
        }

        /** A ground material (KeyRules.groundBreak) placed: a break of it in this sub-cube this minute nets against it. */
        void groundPlaced(int sc, int ordinal) {
            if (gp == null) gp = new HashMap<>();
            gp.computeIfAbsent(ordinal << 6 | sc, k -> new int[1])[0]++;
        }

        void noteY(int ly) {
            if (ly < yMin) yMin = (byte) ly;
            if (ly > yMax) yMax = (byte) ly;
        }

        void noteAgainst(long ref) {
            if (agc == null) agc = new long[4];
            for (int i = 0; i < agn; i++) if (agc[i] == ref) return;
            if (agn < 4) agc[agn++] = ref;
        }
    }

    /** What one cause (and TNT source, or none) took from one section in one minute. */
    static final class Loss {
        final String cause;   // creeper | tnt | fire | explosion | wither | summon
        final UUID by;        // the TNT's player, or null
        short[] sub = new short[3 * 64];
        HashMap<Integer, int[]> mats = new HashMap<>();

        Loss(String cause, UUID by) { this.cause = cause; this.by = by; }

        void add(byte cls, int sc, int ordinal) {
            sub[ci(cls) * 64 + sc]--;
            mats.computeIfAbsent(ordinal, k -> new int[1])[0]--;
        }
    }

    record LossKey(String cause, UUID by, long section) {}

    /** One finished minute, handed to the IO thread with the world slots it was recorded under. */
    record Minute(HashMap<Long, Acc[]> current, HashMap<LossKey, Loss> losses, UUID[] worlds, int cells) {}

    private HashMap<Long, Acc[]> current = new HashMap<>();
    private HashMap<LossKey, Loss> currentLoss = new HashMap<>();
    private long lastKey;
    private UUID lastPlayer;
    private Acc lastAcc;
    int cells, maxCells = 20000, minute;
    long overflow;

    /** The player's counter for this section, or null when the cell cap is reached (counted in overflow). */
    Acc acc(UUID player, String name, long key) {
        if (lastAcc != null && key == lastKey && player.equals(lastPlayer)) return lastAcc;
        Acc[] row = current.get(key);
        Acc a = null;
        if (row != null) for (Acc x : row) if (x.player.equals(player)) { a = x; break; }
        if (a == null) {
            if (cells >= maxCells) { overflow++; return null; }
            a = new Acc(player, name);
            Acc[] grown = row == null ? new Acc[1] : java.util.Arrays.copyOf(row, row.length + 1);
            grown[grown.length - 1] = a;
            current.put(key, grown);
            cells++;
        }
        lastKey = key;
        lastPlayer = player;
        lastAcc = a;
        return a;
    }

    /** The loss counter for (cause, source, section), or null when the cell cap is reached. */
    Loss loss(String cause, UUID by, long key) {
        LossKey k = new LossKey(cause, by, key);
        Loss l = currentLoss.get(k);
        if (l == null) {
            if (cells >= maxCells) { overflow++; return null; }
            l = new Loss(cause, by);
            currentLoss.put(k, l);
            cells++;
        }
        return l;
    }

    /** One block placed (cls already BUILT, LOG or PLANT). The block position stays in the caller; only its cube is kept. */
    Acc place(UUID player, String name, int w, int x, int y, int z, byte cls, int ordinal) {
        Acc a = acc(player, name, WorldSlots.sectionKey(w, x >> 4, y >> 4, z >> 4));
        if (a == null) return null;
        a.placed++;
        a.add(cls, WorldSlots.sc(x, y, z), 1, ordinal);
        a.noteY(y & 15);
        return a;
    }

    /** The existing-build signal: a BUILT block placed against a crafted block in another 4-block cube. */
    static void against(Acc a, int w, int x, int y, int z, int ax, int ay, int az) {
        if ((ax >> 2) == (x >> 2) && (ay >> 2) == (y >> 2) && (az >> 2) == (z >> 2)) return;
        a.ag++;
        a.noteAgainst(WorldSlots.sectionKey(w, ax >> 4, ay >> 4, az >> 4) << 6 | WorldSlots.sc(ax, ay, az));
    }

    /** A block the player placed was used up by a golem or wither they built: as if never placed (net 0 within the minute). */
    void unplace(UUID player, String name, int w, int x, int y, int z, byte cls, int ordinal) {
        Acc a = acc(player, name, WorldSlots.sectionKey(w, x >> 4, y >> 4, z >> 4));
        if (a == null) return;
        a.placed--;
        a.add(cls, WorldSlots.sc(x, y, z), -1, ordinal);
    }

    /** One block broken by a player. */
    void breakBlock(UUID player, String name, int w, int x, int y, int z, byte cls, int ordinal) {
        Acc a = acc(player, name, WorldSlots.sectionKey(w, x >> 4, y >> 4, z >> 4));
        if (a == null) return;
        a.broken++;
        a.add(cls, WorldSlots.sc(x, y, z), -1, ordinal);
    }

    /**
     * Ground broken by a player (KeyRules.groundBreak): counted apart, so digging grass and laying planks in the same minute
     * still counts the planks. The fold applies it only to blocks already tracked. A break of a material placed in this
     * sub-cube this minute nets against that placement first, so cycling dirt never adds up to a build.
     */
    void breakGround(UUID player, String name, int w, int x, int y, int z, int ordinal) {
        Acc a = acc(player, name, WorldSlots.sectionKey(w, x >> 4, y >> 4, z >> 4));
        if (a == null) return;
        a.broken++;
        int sc = WorldSlots.sc(x, y, z);
        int[] placed = a.gp == null ? null : a.gp.get(ordinal << 6 | sc);
        if (placed != null && placed[0] > 0) { placed[0]--; a.add(KeyRules.BUILT, sc, -1, ordinal); return; }
        if (a.gnd == null) a.gnd = new short[64];
        a.gnd[sc]++;
        if (a.mats == null) a.mats = new HashMap<>();
        a.mats.computeIfAbsent(ordinal, k -> new int[1])[0]--;
    }

    /** One block taken by an explosion, fire or a golem build. */
    void lose(String cause, UUID by, int w, int x, int y, int z, byte cls, int ordinal) {
        Loss l = loss(cause, by, WorldSlots.sectionKey(w, x >> 4, y >> 4, z >> 4));
        if (l != null) l.add(cls, WorldSlots.sc(x, y, z), ordinal);
    }

    /** Hands off the minute: new maps, empty cache, next minute. O(1). */
    Minute swap(UUID[] worlds) {
        Minute m = new Minute(current, currentLoss, worlds, cells);
        current = new HashMap<>();
        currentLoss = new HashMap<>();
        lastAcc = null;
        lastPlayer = null;
        cells = 0;
        minute++;
        return m;
    }

    /** /news build forget: the player's open-minute counters go. */
    void dropPlayer(UUID player) {
        for (Iterator<Map.Entry<Long, Acc[]>> it = current.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Acc[]> e = it.next();
            Acc[] row = java.util.Arrays.stream(e.getValue()).filter(a -> !a.player.equals(player)).toArray(Acc[]::new);
            cells -= e.getValue().length - row.length;
            if (row.length == 0) it.remove(); else e.setValue(row);
        }
        int before = currentLoss.size();
        currentLoss.keySet().removeIf(k -> player.equals(k.by()));
        cells -= before - currentLoss.size();
        if (player.equals(lastPlayer)) { lastAcc = null; lastPlayer = null; }
    }

    /** A player left: forget the cache if it points at them. */
    void quit(UUID player) {
        if (player.equals(lastPlayer)) { lastAcc = null; lastPlayer = null; }
    }
}
