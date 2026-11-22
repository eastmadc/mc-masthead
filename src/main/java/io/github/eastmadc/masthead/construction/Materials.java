package io.github.eastmadc.masthead.construction;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * What every block of this server's Paper version counts as, in arrays by block number, built once on the main thread the
 * first time construction news is switched on. Tags are looked up by name (a tag a newer Paper dropped is treated as
 * empty, with one warning), and the four golem and wither spawn reasons by name, so a jar built against an older Paper
 * never reads a field that is gone.
 */
final class Materials {
    byte[] cls, grownAs, body, summonHeight, summonBody;
    boolean[] stacking, crafted, bed, twoPart, head, replaceable, ground;
    int[] keyAs;
    int anchorOrd = -1;
    String[] keyByOrdinal;
    String[] entityNames;   // production counts: every entity type's name, by ordinal
    Map<String, Integer> keyInfo;

    static Materials build(Logger log) {
        Map<String, Tag<Material>> tags = new LinkedHashMap<>();
        for (String t : KeyRules.TAGS) {
            Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, NamespacedKey.minecraft(t), Material.class);
            if (tag == null) log.warning("construction: block tag minecraft:" + t + " not found; treated as empty");
            else tags.put(t, tag);
        }
        Material[] all = Material.values();
        int n = all.length;
        Materials m = new Materials();
        m.cls = new byte[n]; m.grownAs = new byte[n]; m.body = new byte[n];
        m.stacking = new boolean[n]; m.crafted = new boolean[n]; m.bed = new boolean[n]; m.twoPart = new boolean[n]; m.head = new boolean[n];
        m.replaceable = new boolean[n]; m.ground = new boolean[n];
        m.keyAs = new int[n];
        m.keyByOrdinal = new String[n];
        Map<String, Integer> info = new HashMap<>(), ordOf = new HashMap<>();
        for (Material x : all) {
            int o = x.ordinal();
            m.keyAs[o] = o;
            if (x.isLegacy() || !x.isBlock()) continue;   // cls stays IGNORE
            String k = x.getKey().getKey();
            Set<String> names = new HashSet<>();
            for (Map.Entry<String, Tag<Material>> e : tags.entrySet()) if (e.getValue().isTagged(x)) names.add(e.getKey());
            int i = KeyRules.info(k, names);
            info.put(k, i);
            ordOf.put(k, o);
            m.keyByOrdinal[o] = k;
            byte c = KeyRules.cls(i);
            m.cls[o] = c;
            if (c == KeyRules.GROWN) m.grownAs[o] = KeyRules.grownAs(k);
            m.stacking[o] = KeyRules.stacking(k);
            m.crafted[o] = c == KeyRules.BUILT && (i & KeyRules.GROUND) == 0;
            m.bed[o] = (i & KeyRules.BED) != 0;
            m.twoPart[o] = KeyRules.twoPart(k, names);
            m.head[o] = KeyRules.head(k);
            m.replaceable[o] = KeyRules.replaceable(k, names);
            m.ground[o] = KeyRules.groundBreak(k, i);
            m.body[o] = KeyRules.body(k);
            if (k.equals("respawn_anchor")) m.anchorOrd = o;
        }
        for (Map.Entry<String, Integer> e : ordOf.entrySet()) {
            Integer to = ordOf.get(KeyRules.keyAs(e.getKey()));
            if (to != null) m.keyAs[e.getValue()] = to;
        }
        m.keyInfo = Map.copyOf(info);
        org.bukkit.entity.EntityType[] types = org.bukkit.entity.EntityType.values();
        m.entityNames = new String[types.length];
        for (org.bukkit.entity.EntityType t : types) m.entityNames[t.ordinal()] = t.name().toLowerCase(java.util.Locale.ROOT);
        int reasons = CreatureSpawnEvent.SpawnReason.values().length;
        m.summonHeight = new byte[reasons];
        m.summonBody = new byte[reasons];
        summon(m, "BUILD_SNOWMAN", 3, 1);
        summon(m, "BUILD_IRONGOLEM", 3, 2);
        summon(m, "BUILD_WITHER", 3, 3);
        summon(m, "BUILD_COPPERGOLEM", 1, 0);   // only on newer Paper
        return m;
    }

    private static void summon(Materials m, String reason, int height, int body) {
        try {
            int r = CreatureSpawnEvent.SpawnReason.valueOf(reason).ordinal();
            m.summonHeight[r] = (byte) height;
            m.summonBody[r] = (byte) body;
        } catch (IllegalArgumentException missing) { /* not in this Paper version */ }
    }
}
