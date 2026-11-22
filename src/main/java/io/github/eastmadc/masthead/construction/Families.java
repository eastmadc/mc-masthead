package io.github.eastmadc.masthead.construction;

import io.github.eastmadc.masthead.edition.ConstructionWords;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Which materials the paper may name, and how. Valuables, storage, portal blocks and anything that hints at a place (a
 * biome, a structure, a dimension, a wood that only grows in one biome) are never named, in any dimension.
 */
public final class Families {
    private Families() {}

    private static final Set<String> KNOWN_WOODS = Set.of("oak", "spruce", "birch", "acacia", "dark_oak");
    private static final List<String> SUFFIXES = List.of("planks", "log", "wood", "hyphae", "leaves", "stairs", "slab", "fence_gate", "fence",
            "trapdoor", "door", "wall_hanging_sign", "hanging_sign", "wall_sign", "sign", "button", "pressure_plate", "wall");
    private static final Set<String> WOOD_SUFFIXES = Set.of("planks", "log", "wood", "hyphae", "leaves");
    /** Place hints: biome, structure, dimension and portal blocks. */
    private static final Pattern HINT = Pattern.compile(String.join("|",
            "sand|red_sand|.*sandstone.*|snow_block|snow|ice|packed_ice|blue_ice|mud|packed_mud|mud_brick.*|mangrove_.*|cherry_.*",
            "bamboo_.*|pale_oak_.*|jungle_.*|moss_block|moss_carpet|.*coral.*|sponge|wet_sponge|.*mushroom.*|mycelium|sea_pickle",
            "cave_vines.*|pointed_dripstone|dripstone_block|spore_blossom|cactus|dead_bush|podzol",
            "prismarine.*|dark_prismarine.*|sea_lantern|sculk.*|reinforced_deepslate",
            "netherrack|.*nether.*|blackstone.*|polished_blackstone.*|.*basalt.*|crimson_.*|warped_.*|soul_sand|soul_soil",
            "warped_wart_block|shroomlight|magma_block|end_stone.*|purpur.*|chorus.*",
            "obsidian|crying_obsidian"));
    /**
     * Everything else the paper may name. An allowlist, because a denylist can't keep up: pale moss, glowstone, kelp,
     * deepslate, tuff and every new biome block are hidden until someone checks they say nothing about a place.
     */
    private static final Set<String> BASES = Set.of("stone", "cobblestone", "stone brick", "brick", "andesite", "diorite", "granite",
            "copper", "dirt", "gravel", "torch", "lantern", "ladder", "bookshelf", "hay block", "rail", "iron bars", "piston", "observer",
            "repeater", "comparator", "redstone lamp", "bed");
    private static final Pattern COLOR = Pattern.compile("^(?:light_blue|light_gray|white|orange|magenta|yellow|lime|pink|gray|cyan|purple"
            + "|blue|brown|green|red|black)_");
    private static final Pattern SAFE_PREFIX = Pattern.compile("^(?:polished|smooth|chiseled|cut|waxed|weathered|exposed|oxidized|sticky) ");
    /** Odd materials the Commission "cannot explain", in key form -> display. */
    static final Map<String, String> ODD = new LinkedHashMap<>();
    static {
        for (String k : List.of("hay_block", "slime_block", "honey_block", "pumpkin", "carved_pumpkin", "jack_o_lantern", "melon", "bookshelf",
                "bone_block", "dried_kelp_block", "target"))
            ODD.put(k, k.equals("hay_block") ? "hay block" : k.replace('_', ' '));
    }
    private static final Pattern DISPLAY = Pattern.compile("[a-z ]{2,30}");
    private static final Pattern WORDS = ConstructionWords.regex(allWords(), false);
    private static final Pattern CASE_WORDS = ConstructionWords.regex(ConstructionWords.CASE_SENSITIVE, true);

    private static List<String> allWords() {
        List<String> w = new ArrayList<>(ConstructionWords.gateWords());
        w.addAll(ConstructionWords.SECTION_ONLY);
        w.addAll(ConstructionWords.GATE_C);
        return w;
    }

    /** A display string the facts may carry: plain lower-case words and no construction gate word. */
    public static boolean allowed(String display) {
        return display != null && DISPLAY.matcher(display).matches() && !WORDS.matcher(display).find() && !CASE_WORDS.matcher(display).find();
    }

    /** The family a material key belongs to ("spruce_stairs" -> "spruce"), or null if it is never named. */
    public static String family(String key, int info, Predicate<String> keyExists) {
        if (key.contains("amethyst")) return "amethyst";
        if ((info & KeyRules.NEVER_NAME) != 0 || HINT.matcher(key).matches()) return null;
        if (key.endsWith("_concrete") || key.endsWith("_concrete_powder")) return "concrete";
        if (key.endsWith("_wool") || key.endsWith("_carpet") && COLOR.matcher(key).find()) return "wool";   // not pale_moss_carpet
        if (key.contains("terracotta")) return "terracotta";
        if (key.contains("glass")) return "glass";
        String k = key.startsWith("stripped_") ? key.substring(9) : key, base = k, suffix = "";
        for (String s : SUFFIXES) if (k.endsWith("_" + s)) { base = k.substring(0, k.length() - s.length() - 1); suffix = s; break; }
        if (KNOWN_WOODS.contains(base)) return base.replace('_', ' ');
        if (WOOD_SUFFIXES.contains(suffix) || keyExists.test(base + "_planks") || keyExists.test(base + "_log")) return null;   // a wood we can't place
        if (base.endsWith("_bricks")) base = base.substring(0, base.length() - 1);
        if (base.equals("bricks")) base = "brick";
        String d = COLOR.matcher(base).replaceFirst("").replace('_', ' ');
        for (String was = null; !d.equals(was); ) { was = d; d = SAFE_PREFIX.matcher(d).replaceFirst(""); }
        if (d.equals("copper block")) d = "copper";
        return BASES.contains(d) && allowed(d) ? d : null;
    }

    /** One named material and its block count. */
    public record Mat(String what, int blocks) {}

    /** The top 3 families with at least 5% of the build, and one odd material (32+ blocks, not in the top 3), or null. */
    public record Pick(List<Mat> materials, Mat odd) {}

    public static Pick pick(Map<String, Integer> mats, int total, Map<String, Integer> keyInfo) {
        LinkedHashMap<String, Integer> fam = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : mats.entrySet()) {
            int n = Math.max(0, e.getValue());   // a key that changed with no event can go below 0
            if (n == 0) continue;
            String f = family(e.getKey(), keyInfo.getOrDefault(e.getKey(), (int) KeyRules.BUILT), keyInfo::containsKey);
            if (f != null) fam.merge(f, n, Integer::sum);
        }
        List<Map.Entry<String, Integer>> l = new ArrayList<>(fam.entrySet());
        l.sort((a, b) -> b.getValue().equals(a.getValue()) ? a.getKey().compareTo(b.getKey()) : Integer.compare(b.getValue(), a.getValue()));
        List<Mat> top = new ArrayList<>();
        for (Map.Entry<String, Integer> e : l) {
            if (top.size() == 3) break;
            if (e.getValue() * 20L >= total && total > 0) top.add(new Mat(e.getKey(), e.getValue()));
        }
        Mat odd = null;
        for (Map.Entry<String, String> o : ODD.entrySet()) {
            int n = Math.max(0, mats.getOrDefault(o.getKey(), 0));
            if (n < 32 || top.stream().anyMatch(m -> m.what().equals(o.getValue()))) continue;
            if (odd == null || n > odd.blocks()) odd = new Mat(o.getValue(), n);
        }
        return new Pick(top, odd);
    }

    /** How many named families have at least 5% of the build (for the suppression rules). */
    public static int count(Map<String, Integer> mats, int total, Map<String, Integer> keyInfo) {
        LinkedHashMap<String, Integer> fam = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : mats.entrySet()) {
            int n = Math.max(0, e.getValue());
            if (n > 0) fam.merge(rawFamily(e.getKey()), n, Integer::sum);
        }
        int c = 0;
        for (int n : fam.values()) if (n * 20L >= total && total > 0) c++;
        return c;
    }

    /** The family for counting only (every key, named or not). */
    private static String rawFamily(String key) {
        String k = key.startsWith("stripped_") ? key.substring(9) : key;
        for (String s : SUFFIXES) if (k.endsWith("_" + s)) return k.substring(0, k.length() - s.length() - 1);
        return k.endsWith("s") ? k.substring(0, k.length() - 1) : k;
    }
}
