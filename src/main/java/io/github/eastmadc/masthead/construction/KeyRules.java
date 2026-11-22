package io.github.eastmadc.masthead.construction;

import java.util.Set;

/**
 * What a block counts as, from its key ("spruce_planks") and the names of the block tags it is in ("planks", "logs").
 * Pure: the server side only looks the tags up. Rules use keys and tags, never compiled block constants, so blocks newer
 * than the API we build against are classed too.
 */
public final class KeyRules {
    private KeyRules() {}

    // classes (low 4 bits of info)
    public static final byte IGNORE = 0, LOG = 1, PLANT = 2, GROWN = 3, EARTH = 4, BUILT = 5;
    // flags (bit masks above the class)
    public static final int UTILITY = 1 << 4, GROUND = 1 << 5, REDSTONE = 1 << 6, STICKY = 1 << 7, MACHINE = 1 << 8, HOPPER = 1 << 9,
            BED = 1 << 10, HOME = 1 << 11, DOOR = 1 << 12, STAIRSLAB = 1 << 13, FENCE = 1 << 14, CROP = 1 << 15, GLASS = 1 << 16,
            RAIL = 1 << 17, NEVER_NAME = 1 << 18;

    /** The block tags these rules read, by name. */
    public static final Set<String> TAGS = Set.of("saplings", "logs", "crops", "flowers", "leaves", "base_stone_overworld",
            "base_stone_nether", "dirt", "nylium", "terracotta", "beds", "doors", "stairs", "slabs", "fences", "fence_gates", "rails",
            "shulker_boxes", "replaceable");

    private static final Set<String> IGNORE_KEYS = Set.of("air", "cave_air", "void_air", "water", "lava", "bubble_column", "fire",
            "soul_fire", "scaffolding", "ladder", "snow", "cobweb", "tripwire", "sand", "red_sand", "gravel", "tnt");
    private static final Set<String> GROWN_BUILT = Set.of("melon", "pumpkin", "kelp", "kelp_plant", "brown_mushroom", "red_mushroom",
            "amethyst_cluster", "small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud");
    private static final Set<String> GROWN_PLANT = Set.of("twisting_vines", "twisting_vines_plant", "weeping_vines", "weeping_vines_plant",
            "cave_vines", "cave_vines_plant", "chorus_plant", "chorus_flower", "sugar_cane", "bamboo", "cactus");
    private static final Set<String> STACKING = Set.of("sugar_cane", "bamboo", "cactus");
    private static final Set<String> PLANT_KEYS = Set.of("cocoa", "nether_wart", "sweet_berry_bush", "bamboo_sapling", "short_grass",
            "tall_grass", "fern", "large_fern", "vine", "melon_stem", "pumpkin_stem", "attached_melon_stem", "attached_pumpkin_stem");
    private static final Set<String> EARTH_KEYS = Set.of("clay", "ice", "end_stone", "soul_soil");
    private static final Set<String> UTILITY_KEYS = Set.of("dirt", "coarse_dirt", "rooted_dirt", "mud", "cobblestone", "cobbled_deepslate",
            "netherrack", "cobblestone_slab", "cobblestone_stairs");
    private static final Set<String> GROUND_KEYS = Set.of("farmland", "dirt_path", "terracotta", "sandstone", "red_sandstone", "snow_block",
            "packed_ice", "blue_ice", "calcite", "smooth_basalt", "dripstone_block", "magma_block", "obsidian", "bedrock", "amethyst_block",
            "budding_amethyst", "brown_mushroom_block", "red_mushroom_block", "mushroom_stem", "prismarine", "sculk");
    private static final Set<String> REDSTONE_KEYS = Set.of("redstone_wire", "repeater", "comparator", "observer", "piston", "sticky_piston",
            "dispenser", "dropper", "redstone_torch", "redstone_wall_torch", "crafter", "sculk_sensor", "calibrated_sculk_sensor");
    private static final Set<String> MACHINE_KEYS = Set.of("hopper", "observer", "piston", "sticky_piston", "dispenser", "composter", "crafter");
    private static final Set<String> HOME_KEYS = Set.of("crafting_table", "furnace", "smoker", "blast_furnace", "chest", "barrel");
    private static final Set<String> CROP_KEYS = Set.of("nether_wart", "sugar_cane", "bamboo", "bamboo_sapling", "cactus", "cocoa",
            "sweet_berry_bush", "kelp");
    private static final Set<String> NEVER_KEYS = Set.of("diamond_block", "emerald_block", "gold_block", "iron_block", "netherite_block",
            "lapis_block", "redstone_block", "beacon", "ancient_debris", "chest", "trapped_chest", "barrel", "ender_chest", "hopper", "vault",
            "decorated_pot", "obsidian", "crying_obsidian");
    private static final Set<String> TWO_PART_KEYS = Set.of("sunflower", "lilac", "rose_bush", "peony", "tall_grass", "large_fern",
            "pitcher_plant", "small_dripleaf", "pitcher_crop");
    private static final Set<String> HEADS = Set.of("carved_pumpkin", "jack_o_lantern", "wither_skeleton_skull", "wither_skeleton_wall_skull");

    /** Class in the low 4 bits, flags above. */
    public static int info(String key, Set<String> tags) {
        return cls(key, tags) | flags(key, tags);
    }

    public static byte cls(int info) { return (byte) (info & 15); }

    /** Natural ground players almost never place (grass, podzol, terracotta, sandstone): its breaks are kept apart (raw "g"). */
    public static boolean naturalGround(int info) { return cls(info) == BUILT && (info & GROUND) != 0 && (info & UTILITY) == 0; }

    /** Utility blocks that are also the natural ground (dirt, mud): breaks go to raw "g" too, but cobblestone never. */
    private static final Set<String> NATURAL_UTILITY = Set.of("dirt", "coarse_dirt", "rooted_dirt", "mud");

    /** Ground whose breaks are kept apart (raw "g"): natural ground, and the utility blocks the world is made of. */
    public static boolean groundBreak(String key, int info) { return naturalGround(info) || cls(info) == BUILT && NATURAL_UTILITY.contains(key); }

    static byte cls(String k, Set<String> tags) {
        boolean torch = k.endsWith("torch") && !k.startsWith("redstone_");
        if (IGNORE_KEYS.contains(k) || torch || tags.contains("saplings") || k.endsWith("_concrete_powder")) return IGNORE;
        if (tags.contains("logs") || k.endsWith("_log") || k.endsWith("_wood") || k.endsWith("_hyphae")) return LOG;
        if (GROWN_BUILT.contains(k) || GROWN_PLANT.contains(k)) return GROWN;
        if (tags.contains("crops") || tags.contains("flowers") || tags.contains("leaves") || PLANT_KEYS.contains(k)) return PLANT;
        if (tags.contains("base_stone_overworld") || tags.contains("base_stone_nether") || k.endsWith("_ore") || EARTH_KEYS.contains(k)) return EARTH;
        return BUILT;
    }

    static int flags(String k, Set<String> tags) {
        int f = 0;
        if (UTILITY_KEYS.contains(k)) f |= UTILITY | GROUND;
        if (tags.contains("dirt") || tags.contains("nylium") || tags.contains("terracotta") || GROUND_KEYS.contains(k)) f |= GROUND;
        if (REDSTONE_KEYS.contains(k)) f |= REDSTONE;
        if (k.equals("slime_block") || k.equals("honey_block")) f |= STICKY;
        if (MACHINE_KEYS.contains(k)) f |= MACHINE;
        if (k.equals("hopper")) f |= HOPPER;
        if (tags.contains("beds")) f |= BED;
        if (tags.contains("doors")) f |= DOOR | HOME;
        if (HOME_KEYS.contains(k)) f |= HOME;
        if (tags.contains("stairs") || tags.contains("slabs")) f |= STAIRSLAB;
        if (tags.contains("fences") || tags.contains("fence_gates")) f |= FENCE;
        if (tags.contains("crops") || CROP_KEYS.contains(k)) f |= CROP;
        if (k.contains("glass")) f |= GLASS;
        if (tags.contains("rails")) f |= RAIL;
        if (NEVER_KEYS.contains(k) || tags.contains("shulker_boxes") || (k.startsWith("raw_") && k.endsWith("_block"))) f |= NEVER_NAME;
        return f;
    }

    /** What a placed GROWN block counts as: BUILT (melon, pumpkin, kelp, mushrooms, amethyst) or PLANT. */
    public static byte grownAs(String k) { return GROWN_BUILT.contains(k) ? BUILT : PLANT; }

    /** Cane, bamboo, cactus: a break counts only at the base. */
    public static boolean stacking(String k) { return STACKING.contains(k); }

    /** Beds, doors and two-block plants: one click places two blocks, one break removes both. Counted once. */
    public static boolean twoPart(String k, Set<String> tags) { return tags.contains("beds") || tags.contains("doors") || TWO_PART_KEYS.contains(k); }

    public static boolean head(String k) { return HEADS.contains(k); }

    private static final Set<String> REPLACEABLE_KEYS = Set.of("air", "cave_air", "void_air", "water", "lava", "short_grass", "tall_grass",
            "fern", "large_fern", "snow", "vine", "dead_bush", "seagrass", "tall_seagrass", "fire", "soul_fire", "glow_lichen", "light");

    /** Blocks a placed block simply takes the place of (air, water, short grass, a snow layer). */
    public static boolean replaceable(String k, Set<String> tags) { return tags.contains("replaceable") || REPLACEABLE_KEYS.contains(k); }

    /** Golem and wither body blocks: 1 snow block, 2 iron block, 3 soul sand or soul soil, 0 anything else. */
    public static byte body(String k) {
        return switch (k) {
            case "snow_block" -> 1;
            case "iron_block" -> 2;
            case "soul_sand", "soul_soil" -> 3;
            default -> 0;
        };
    }

    /** Blocks that turn into another key with no event are recorded under the key they were placed as. */
    public static String keyAs(String k) {
        return switch (k) {
            case "attached_melon_stem" -> "melon_stem";
            case "attached_pumpkin_stem" -> "pumpkin_stem";
            case "bamboo" -> "bamboo_sapling";
            default -> k;
        };
    }
}
