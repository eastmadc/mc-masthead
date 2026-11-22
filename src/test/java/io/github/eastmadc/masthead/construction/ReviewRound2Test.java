package io.github.eastmadc.masthead.construction;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.github.eastmadc.masthead.construction.ConstructionTest.*;

/** Review round 2: place-hint materials, build names, and a merge chain inside one fold. */
public final class ReviewRound2Test {
    static int ok = 0;

    static void t(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
        ok++;
    }

    public static void main(String[] args) throws Exception {
        families();
        names();
        mergeChain();
        System.out.println("ReviewRound2Test: " + ok + " checks passed");
    }

    /** PRIV-R2-1: only checked materials are named; every game key is run through the filter. */
    static void families() {
        // Written out here, not read from Families: widening the list must be a deliberate change to this test too.
        Set<String> allowed = Set.of("oak", "spruce", "birch", "acacia", "dark oak", "concrete", "wool", "terracotta", "glass", "amethyst",
                "stone", "cobblestone", "stone brick", "brick", "andesite", "diorite", "granite", "copper", "dirt", "gravel", "torch", "lantern",
                "ladder", "bookshelf", "hay block", "rail", "iron bars", "piston", "observer", "repeater", "comparator", "redstone lamp", "bed");
        Set<String> keys = new TreeSet<>();
        for (Material m : Material.values()) if (!m.isLegacy()) keys.add(m.getKey().getKey().toLowerCase(Locale.ROOT));
        t(keys.size() > 1000, "every game key: " + keys.size());
        int named = 0;
        for (String k : keys) {
            String f = Families.family(k, KeyRules.info(k, Set.of()), keys::contains);
            t(f == null || allowed.contains(f), "PRIV-R2-1: " + k + " -> " + f + " is not on the checked list");
            if (f != null) named++;
        }
        t(named > 100, "PRIV-R2-1: the usual building blocks are still named (" + named + ")");
        for (String trap : List.of("pale_moss_block", "pale_moss_carpet", "creaking_heart", "potted_pale_oak_sapling", "glowstone", "soul_lantern",
                "end_rod", "dragon_head", "dragon_egg", "ochre_froglight", "kelp", "lily_pad", "big_dripleaf", "azalea", "hanging_roots",
                "cobbled_deepslate", "polished_deepslate", "deepslate_bricks", "polished_tuff", "chiseled_tuff", "calcite", "resin_bricks",
                "mossy_cobblestone", "cracked_stone_bricks", "infested_stone", "smooth_quartz", "sea_lantern"))
            t(Families.family(trap, KeyRules.info(trap, Set.of()), keys::contains) == null, "PRIV-R2-1 trap named: " + trap);
        Map<String, String> want = Map.of("oak_planks", "oak", "polished_andesite_slab", "andesite", "red_bed", "bed", "white_carpet", "wool",
                "chiseled_stone_bricks", "stone brick", "waxed_weathered_cut_copper_stairs", "copper", "copper_block", "copper", "sticky_piston", "piston",
                "lime_concrete_powder", "concrete", "hay_block", "hay block");
        want.forEach((k, f) -> t(f.equals(Families.family(k, KeyRules.info(k, Set.of()), keys::contains)), "PRIV-R2-1: " + k + " -> " + f));
        Map<String, Integer> info = new HashMap<>();
        for (String k : List.of("pale_moss_block", "oak_planks")) info.put(k, KeyRules.info(k, Set.of()));
        Families.Pick p = Families.pick(Map.of("pale_moss_block", 300, "oak_planks", 200), 500, info);
        t(p.materials().size() == 1 && p.materials().get(0).what().equals("oak"), "PRIV-R2-1: pale moss is not printed: " + p);
    }

    /** PRIV-R2-2: no self-harm phrase, no put-down aimed at someone, no biome name. */
    static void names() {
        List<String> known = List.of("StevePlayer");
        for (String bad : List.of("Kill Yourself", "Hang Yourself", "Go Die", "Jake Is Gay", "Emmas Crush", "Sam Smells", "Hate Robin",
                "Kill Myself", "Pale Garden", "Deep Dark", "Ice Spikes", "Igloo", "Plains Hut", "Dark Forest Inn"))
            t(NameRules.check(bad, known, "clean") == null, "PRIV-R2-2 accepts " + bad);
        for (String good : List.of("The Great Wall", "Spruce Hotel", "Rae's Bakery", "Clear Glass Studio", "Copper Works", "Brick Mill"))
            t(good.equals(NameRules.check(good, known, "clean")), "PRIV-R2-2 refuses " + good);
    }

    /** R2C-1: two merges in one fold (3 into 2, then 2 into 1): no cube is left on a merged-away build. */
    static void mergeChain() throws Exception {
        int chains = 0;
        for (int mirror = 0; mirror < 2; mirror++) for (int shift = 0; shift < 12; shift++) {
            Sc s = new Sc().notice(STEVE);
            int bx = X + shift * 4;
            int left = bx, mid0 = bx + 20, mid1 = bx + 120, right = bx + 140;
            int e1 = mirror == 0 ? left : right, e2 = mirror == 0 ? right : left;
            s.box(STEVE, "oak_planks", e1, Y, Z, e1 + 7, Y + 1, Z + 7); s.fold();
            s.box(STEVE, "stone_bricks", e2, Y, Z, e2 + 7, Y + 1, Z + 7); s.fold();
            s.box(STEVE, "oak_planks", mid0, Y, Z, mid1, Y + 1, Z + 3); s.fold();
            int before = s.idx.sites.values().stream().filter(x -> x.aliasOf == null).mapToInt(x -> x.net).sum();
            s.box(STEVE, "oak_planks", left + 8, Y, Z, mid0 - 1, Y, Z);   // one day, a bridge at each end of the long build
            s.box(STEVE, "oak_planks", mid1 + 1, Y, Z, right - 1, Y, Z);
            s.fold();
            int bad = 0;
            for (var m : s.idx.cubes) for (var c : m.values()) if (c.site > 0 && s.idx.sites.get(c.site).aliasOf != null) bad++;
            t(bad == 0, "R2C-1 (mirror " + mirror + ", shift " + shift + "): " + bad + " cubes point at a merged-away build");
            List<SiteIndex.Site> live = s.idx.sites.values().stream().filter(x -> x.aliasOf == null).toList();
            t(live.size() == 1 && live.get(0).net >= before, "R2C-1: one build, net " + (live.isEmpty() ? -1 : live.get(0).net) + " not below " + before);
            if (s.idx.sites.values().stream().filter(x -> x.aliasOf != null).count() >= 2) chains++;
        }
        t(chains > 0, "R2C-1: the layout produced two merges in one fold");
    }
}
