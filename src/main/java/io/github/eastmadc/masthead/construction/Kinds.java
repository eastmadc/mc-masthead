package io.github.eastmadc.masthead.construction;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What a build is (a closed list), whether it is news at all, and how sure the Commission is. Pure rules. */
public final class Kinds {
    private Kinds() {}

    /** The kinds list version: 2 = the 56 kinds of alpha.21, 3 = hoglin_farm (alpha.32). An AI answer from an older list is asked again once. */
    public static final int LIST = 3;

    /** Every kind that may print, be filed (/news build kind) or come from the AI. Never "build" or "structure". */
    public static final List<String> ALL = List.of("house", "base", "storage", "tower", "library", "greenhouse", "enchanting_room", "brewing_room",
            "smelter", "trading_hall", "villager_breeder", "nether_portal", "bridge", "road", "rail_line", "wall", "garden", "decoration", "redstone",
            "item_collector", "farm", "auto_farm", "wheat_farm", "carrot_farm", "potato_farm", "melon_farm", "pumpkin_farm", "sugar_cane_farm",
            "cactus_farm", "bamboo_farm", "kelp_farm", "tree_farm", "mushroom_farm", "flower_farm", "berry_farm", "nether_wart_farm", "bonemeal_farm",
            "slime_farm", "mob_farm", "spider_farm", "zombie_farm", "skeleton_farm", "creeper_farm", "witch_farm", "enderman_farm", "guardian_farm",
            "blaze_farm", "gold_farm", "hoglin_farm", "raid_farm", "iron_farm", "chicken_farm", "cow_farm", "wool_farm", "animal_pen", "bee_farm", "fishing_farm",
            "lighthouse", "ship");
    /** A specific kind's general kind: what the kinds desk falls back to when the blocks don't prove the specific one. */
    public static final Map<String, String> PARENT = parents();
    /** Kinds that get a public works entry and their parts: every farm, the redstone kinds and the villager breeder. */
    public static final Set<String> WORKS = works();
    /** Kinds that never get a height word. */
    public static final Set<String> FLAT = Set.of("bridge", "road", "rail_line", "wall");

    /**
     * A build's numbers. n = net blocks; h = height in blocks; l, w = long and short sides in blocks; a = occupied columns x 16;
     * families = material families with 5%+; placements = BUILT blocks ever placed; plants = non-crop plant blocks.
     */
    record M(int n, int built, int h, int l, int w, int a, int families, int ut, int home, int dr, int bed, int ss, int fn, int crop,
             int rs, int mc, int hp, int gl, int rail, int sk, int air, int wb, int placements, int plants, int clamped, int slimePct) {}

    /** Why a build is not news, or null. */
    static String suppressed(M m, int minBlocks) {
        if (m.ut() * 100L >= 85L * m.n() && m.home() == 0 && m.families() <= 2) return "utility";
        if (m.a() <= 16 && m.h() >= 8 && m.families() <= 2 && m.n() < 200) return "pillar";
        if (m.clamped() >= 64 && m.clamped() * 4L >= m.n() && m.sk() >= 16) return "machine";
        if (m.n() < minBlocks) return "tiny";
        return null;
    }

    static String kind(M m) { return kind(m, 1); }

    /**
     * First match wins; "build" means no rule matched and never leaves this class's callers (see {@link #fallback}).
     * f = 1.5 asks whether the deciding numbers are met by half again (high confidence).
     */
    static String kind(M m, double f) {
        boolean crops = m.crop() >= 32 * f || (m.wb() >= 1 && m.crop() >= 16 * f);
        if (crops && m.mc() >= 4 * f) return "auto_farm";
        if (crops) return "farm";
        // most of it in slime chunks (1 chunk in 10 is one), with the slabs farms use to stop other spawns
        if (m.slimePct() >= Math.min(90, 40 * f) && m.n() >= 100 && m.ss() >= 0.15 * m.n()) return "slime_farm";
        if (m.fn() >= 0.6 * f * m.built() && m.fn() > 0 && m.crop() < 8 && m.h() <= 4) return "animal_pen";
        if (m.rs() >= 20 * f || m.rs() >= 0.15 * f * m.n()) return "redstone";
        if (m.crop() < 16 && m.hp() >= 6 * f && m.mc() >= 8 * f) return "auto_farm";
        if (m.h() >= 20 * f && m.h() >= 2.5 * f * m.l()) return "tower";
        if (m.l() >= 24 * f && m.w() <= 8 && m.h() <= 10 && m.air() >= 0.3 * f * Math.max(1, m.placements())) return "bridge";
        if (m.l() >= 24 * f && m.w() <= 4 && m.h() >= 4 && m.built() >= 0.9 * m.n()) return "wall";
        if (m.l() >= 24 * f && m.w() <= 8 && m.h() <= 4 && (m.ss() + m.rail()) >= 0.5 * f * m.n()) return "road";
        if (m.a() >= 25 && m.a() <= 900 && m.h() >= 3 && m.h() <= 32 && m.home() >= 2 * f && m.dr() >= 1) return "house";
        if (m.plants() >= 0.5 * f * m.n() && m.h() <= 4) return "garden";
        return "build";
    }

    /** No builder label, no AI kind and no rule: the shape's best guess, always a real kind. */
    static String fallback(M m) {
        if (m.plants() * 2 >= m.n()) return "garden";
        if (m.h() >= 20 && m.h() >= 2 * m.l()) return "tower";
        if (m.l() >= 16 && m.w() <= 8 && m.h() <= 10) return m.air() * 3 >= Math.max(1, m.placements()) ? "bridge" : m.h() <= 3 ? "road" : "wall";
        return m.n() < 150 ? "decoration" : "house";
    }

    /**
     * high: the same kind 2+ folds and met by the rules at 1.5x (a house also needs stairs/slabs or glass, a bed and no
     * hoppers). low: changed today, the AI's kind (ai, may be null) when the AI itself said low (aiConf), or a shape
     * guess that neither the rules nor the AI gave.
     */
    static String confidence(M m, String kind, String ai, String aiConf, int streak, boolean changedToday) {
        if (changedToday || (kind.equals(ai) ? "low".equals(aiConf) : !kind.equals(kind(m)))) return "low";
        boolean strong = kind(m, 1.5).equals(kind);
        if (kind.equals("house")) strong &= (m.ss() > 0 || m.gl() > 0) && m.bed() >= 1 && m.hp() == 0;
        return streak >= 2 && strong ? "high" : "medium";
    }

    /** Java Edition's slime chunk test for an Overworld chunk. */
    static boolean slimeChunk(long seed, int cx, int cz) {
        return new java.util.Random(seed + (long) (cx * cx * 0x4c1906) + (long) (cx * 0x5ac0db) + (long) (cz * cz) * 0x4307a7L
                + (long) (cz * 0x5f24f) ^ 0x3ad8025fL).nextInt(10) == 0;
    }

    static String size(int n) {
        return n < 150 ? "compact" : n < 1000 ? "medium" : n < 5000 ? "large" : "huge";
    }

    static String height(int h) {
        return h < 8 ? "low" : h < 32 ? "tall" : h < 96 ? "very_tall" : "towering";
    }

    private static Map<String, String> parents() {
        Map<String, String> p = new HashMap<>();
        for (String k : List.of("library", "enchanting_room", "brewing_room")) p.put(k, "house");
        p.put("smelter", "storage");
        p.put("greenhouse", "farm");
        p.put("nether_portal", "decoration");
        p.put("lighthouse", "tower");
        p.put("ship", "decoration");
        p.put("rail_line", "road");
        for (String k : List.of("wheat_farm", "carrot_farm", "potato_farm", "tree_farm", "mushroom_farm", "berry_farm", "nether_wart_farm", "bee_farm"))
            p.put(k, "farm");
        // crops that are almost always grown by machines
        for (String k : List.of("melon_farm", "pumpkin_farm", "sugar_cane_farm", "cactus_farm", "bamboo_farm", "kelp_farm", "bonemeal_farm", "fishing_farm"))
            p.put(k, "auto_farm");
        p.put("flower_farm", "garden");
        for (String k : List.of("slime_farm", "spider_farm", "zombie_farm", "skeleton_farm", "creeper_farm", "witch_farm", "enderman_farm", "guardian_farm",
                "blaze_farm", "gold_farm", "hoglin_farm", "raid_farm", "iron_farm")) p.put(k, "mob_farm");
        for (String k : List.of("chicken_farm", "cow_farm", "wool_farm")) p.put(k, "animal_pen");
        return Map.copyOf(p);
    }

    private static Set<String> works() {
        Set<String> w = new HashSet<>(Set.of("farm", "redstone", "villager_breeder", "item_collector"));
        for (String k : ALL) if (k.endsWith("_farm")) w.add(k);
        return Set.copyOf(w);
    }

    /** Specific kinds that hint at a biome, a structure or a dimension: construction.detail standard prints their parent. */
    static final Set<String> PLACE_HINTS = Set.of("cactus_farm", "bamboo_farm", "mushroom_farm", "nether_wart_farm", "kelp_farm",
            "guardian_farm", "blaze_farm", "witch_farm", "enderman_farm", "gold_farm", "hoglin_farm", "spider_farm",
            "zombie_farm", "skeleton_farm", "raid_farm");   // spider, zombie, skeleton: dungeon spawners; raid: a pillager outpost

    /** The kind the paper may print at a construction.detail level: basic = the top parent, standard = no biome hints, full = as is. */
    public static String shown(String kind, String detail) {
        if (kind == null || detail.equals("full") || detail.equals("standard") && !PLACE_HINTS.contains(kind)) return kind;
        if (detail.equals("standard")) return PARENT.get(kind);
        while (PARENT.containsKey(kind)) kind = PARENT.get(kind);
        return kind;
    }

    /** The words award text, the blotter and the plain edition use for a kind ("auto farm"). */
    public static String display(String kind) {
        return kind.equals("redstone") ? "redstone machine" : kind.replace('_', ' ');
    }
}
