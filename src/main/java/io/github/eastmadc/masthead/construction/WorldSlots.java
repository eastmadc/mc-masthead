package io.github.eastmadc.masthead.construction;

import java.util.HashMap;
import java.util.UUID;

/**
 * Each world's UUID -> a small slot number (0-62) for the section keys. Keeps UUIDs only, never a world, so an unloaded world is
 * never held in memory. Main thread only. Slot -1 = ignored or over the cap.
 */
final class WorldSlots {
    static final int NEW = -2, CAP = 63;

    private HashMap<UUID, Integer> slotOf = new HashMap<>();
    private UUID[] slotToWorld = new UUID[CAP];
    boolean[] bedsExplode = new boolean[CAP], anchorsExplode = new boolean[CAP];
    private UUID lastUid;
    private int lastSlot = -1, used;
    long overflow;

    /** Known slot, -1, or NEW for a world never seen. */
    int slotOf(UUID u) {
        if (u == lastUid) return lastSlot;
        Integer s = slotOf.get(u);
        if (s == null) return NEW;
        lastUid = u;
        lastSlot = s;
        return s;
    }

    /** Gives a new world its slot (or -1 if ignored or all 63 are used; the caller warns once when overflow becomes 1). */
    int assign(UUID u, boolean ignored, boolean beds, boolean anchors) {
        int s = -1;
        if (!ignored) {
            if (used < CAP) {
                s = used++;
                slotToWorld[s] = u;
                bedsExplode[s] = beds;
                anchorsExplode[s] = anchors;
            } else overflow++;
        }
        slotOf.put(u, s);
        lastUid = u;
        lastSlot = s;
        return s;
    }

    /** An immutable copy for the IO thread (the arrays are replaced, never changed, by reset()). */
    UUID[] snapshot() { return slotToWorld.clone(); }

    /** On reload, after the open minute was handed off: new arrays, slots start from 0 again. */
    void reset() {
        slotOf = new HashMap<>();
        slotToWorld = new UUID[CAP];
        bedsExplode = new boolean[CAP];
        anchorsExplode = new boolean[CAP];
        lastUid = null;
        lastSlot = -1;
        used = 0;
        overflow = 0;
    }

    /** Section key: world slot (6 bits) | chunk x (22 bits) | section y (8 bits, signed) | chunk z (22 bits). */
    static long sectionKey(int w, int cx, int sy, int cz) {
        return ((long) w << 52) | ((cx & 0x3FFFFFL) << 30) | ((sy & 0xFFL) << 22) | (cz & 0x3FFFFFL);
    }

    static int slot(long key) { return (int) (key >>> 52); }
    static int cx(long key) { return (int) (key << 12 >> 42); }
    static int sy(long key) { return (int) (key << 34 >> 56); }
    static int cz(long key) { return (int) (key << 42 >> 42); }

    /** Sub-cube 0-63 of a block inside its 16x16x16 section. */
    static int sc(int x, int y, int z) {
        return ((x & 15) >> 2) | (((z & 15) >> 2) << 2) | (((y & 15) >> 2) << 4);
    }
}
