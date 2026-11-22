package io.github.eastmadc.masthead.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Python sniffs(): the two daily awards. Winners are picked here by fixed rules from real data; the writer only writes the blurb.
 * "coolest" becomes golden_egg, "most_sus" becomes headless_chicken.
 */
final class Awards {
    private Awards() {}

    static final Map<String, Integer> COOL_ADV = new LinkedHashMap<>();
    static final Map<String, String> GOOFY = new LinkedHashMap<>();   // death text contains key -> how they died (first match wins)
    static final Map<String, Integer> SUS_KILLS = new LinkedHashMap<>();

    static {
        Object[] adv = {"Hero of the Village", 100, "Free the End", 120, "The End?", 70, "The End", 70, "Sky's the Limit", 90,
                "Cover Me in Debris", 100, "Hidden in the Depths", 60, "Withering Heights", 110, "Postmortal", 80, "Sniper Duel", 50,
                "Arbalistic", 60, "How Did We Get Here?", 150, "Adventuring Time", 120, "A Complete Catalogue", 90, "Diamonds!", 35,
                "Cover Me with Diamonds", 50, "Into Fire", 30, "A Terrible Fortress", 30, "Return to Sender", 70, "Uneasy Alliance", 80,
                "Monsters Hunted", 90, "Two by Two", 60, "The Next Generation", 130, "Beaconator", 120, "Bring Home the Beacon", 70,
                "Total Beelocation", 40, "Smithing with Style", 60, "Over-Overkill", 70, "Who Needs Rockets?", 50};
        for (int i = 0; i < adv.length; i += 2) COOL_ADV.put((String) adv[i], (Integer) adv[i + 1]);
        String[] goofy = {"fell", "fall damage", "hit the ground", "fall damage", "drowned", "drowned", "tried to swim in lava", "swam in lava",
                "burned", "burned", "went up in flames", "went up in flames", "walked into", "walked into something pointy", "pricked", "cactus",
                "poked to death", "berry bush", "suffocated", "stuck in a wall", "squashed", "squashed", "experienced kinetic energy",
                "flew into a wall", "froze", "froze", "starved", "starved", "blew up", "blew up", "discovered the floor was lava", "floor was lava"};
        for (int i = 0; i < goofy.length; i += 2) GOOFY.put(goofy[i], goofy[i + 1]);
        Object[] sus = {"villager", 15, "iron_golem", 20, "cat", 25, "wolf", 25, "parrot", 25, "axolotl", 20, "horse", 15, "allay", 30,
                "fox", 10, "bee", 5, "wandering_trader", 10, "snow_golem", 10};
        for (int i = 0; i < sus.length; i += 2) SUS_KILLS.put((String) sus[i], (Integer) sus[i + 1]);
    }

    private record Reason(double score, String name, String text) {}

    /** An award reason from elsewhere (construction news): award is golden_egg or headless_chicken. */
    record Extra(String award, String name, double score, String text) {}

    static JsonObject pick(Map<String, Stats.Row> today, Events ev, Set<String> players, List<BlotterFacts.Entry> blotter) {
        return pick(today, ev, players, blotter, List.of());
    }

    /** today = per-player deltas, players = real player names (mobs never win). */
    static JsonObject pick(Map<String, Stats.Row> today, Events ev, Set<String> players, List<BlotterFacts.Entry> blotter, List<Extra> extra) {
        List<Reason> cool = new ArrayList<>(), sus = new ArrayList<>();
        for (Extra x : extra) {   // a reason names exactly one player: one that names a second is dropped
            if (!players.contains(x.name()) || players.stream().anyMatch(p -> !p.equals(x.name()) && namesIn(x.text(), p))) continue;
            (x.award().equals("golden_egg") ? cool : sus).add(new Reason(x.score(), x.name(), x.text()));
        }
        for (String[] a : ev.advancements) {
            if (players.contains(a[0])) cool.add(new Reason(COOL_ADV.getOrDefault(a[1], 10), a[0], "unlocked the achievement [" + a[1] + "]"));
        }
        LinkedHashMap<String, Integer> fights = new LinkedHashMap<>();   // "killer\tvictim" -> times
        for (String[] p : ev.pvp) {
            String victim = p[0], killer = p[1];
            if (players.contains(victim) && players.contains(killer) && !victim.equals(killer)) fights.merge(killer + "\t" + victim, 1, Integer::sum);
        }
        fights.forEach((key, times) -> {   // a pair scores once, however many kills: repeat kills of one player win nothing
            String killer = key.split("\t")[0], victim = key.split("\t")[1], x = times > 1 ? " " + times + " times" : "";
            cool.add(new Reason(50, killer, "took down " + victim + " in PvP" + x));
            sus.add(new Reason(20, victim, "got taken out by their own friend " + killer + x));
        });
        for (Map.Entry<String, Stats.Row> e : today.entrySet()) {
            String n = e.getKey();
            Stats.Row r = e.getValue();
            long dia = r.num("mined_diamond_ore") + r.num("mined_deepslate_diamond_ore");
            if (dia >= 5) cool.add(new Reason(dia * 3, n, "mined " + dia + " diamond ore"));
            long debris = r.num("mined_ancient_debris");
            if (debris >= 1) cool.add(new Reason(debris * 8, n, "dug up " + debris + " ancient debris"));
            if (r.dbl("mob_kills") >= 50) cool.add(new Reason(r.dbl("mob_kills") / 10, n, "killed " + (long) r.dbl("mob_kills") + " mobs"));
            if (r.dbl("blocks_mined") >= 2000) cool.add(new Reason(r.dbl("blocks_mined") / 200, n, "broke " + Py.commas((long) r.dbl("blocks_mined")) + " blocks"));
            if (r.dbl("trades") >= 20) cool.add(new Reason(r.dbl("trades"), n, "made " + (long) r.dbl("trades") + " villager trades"));
            if (r.dbl("elytra_m") >= 1000) cool.add(new Reason(r.dbl("elytra_m") / 200, n, "flew " + Py.commas((long) r.dbl("elytra_m")) + " m with an elytra"));
            if (r.dbl("deaths") >= 2) sus.add(new Reason(r.dbl("deaths") * 15, n, "died " + (long) r.dbl("deaths") + " times"));
            if (r.dbl("eggs") >= 100) sus.add(new Reason(r.dbl("eggs") / 20, n, "threw " + Py.commas((long) r.dbl("eggs")) + " eggs"));
            if (r.dbl("jumps") >= 5000) sus.add(new Reason(r.dbl("jumps") / 500, n, "jumped " + Py.commas((long) r.dbl("jumps")) + " times"));
            if (r.dbl("cake") >= 3) sus.add(new Reason(r.dbl("cake") * 10, n, "ate " + (long) r.dbl("cake") + " slices of cake"));
            for (Map.Entry<String, Integer> m : SUS_KILLS.entrySet()) {
                long kk = r.map("killed").getOrDefault(m.getKey(), 0L);
                if (kk != 0) sus.add(new Reason(kk * m.getValue(), n, "killed " + kk + " " + m.getKey().replace('_', ' ') + (kk > 1 ? "s" : "")));
            }
        }
        for (String d : ev.deaths) {
            String who = d.split(" ", 2)[0];
            if (!players.contains(who)) continue;
            for (Map.Entry<String, String> g : GOOFY.entrySet()) {
                if (d.contains(g.getKey())) {
                    sus.add(new Reason(25, who, "died in a goofy way (" + g.getValue() + "): \"" + d + "\""));
                    break;
                }
            }
        }
        for (BlotterFacts.Entry e : blotter) {   // what they did to each other
            String a = e.by, b = e.to, k = e.what, x = e.x;
            long items = e.itemTotal(), n = e.items == null ? e.n : 0;
            switch (k) {
                case "killed_pet" -> sus.add(new Reason(45, a, "killed " + b + "'s " + (x.isEmpty() ? "pet" : x)));
                case "broke_bed" -> sus.add(new Reason(40, a, "broke " + b + "'s bed"));
                case "slept_in_bed" -> { }   // a spawn point, never an award reason ("slept in X's bed" read as innuendo)
                case "broke_container" -> sus.add(new Reason(30, a, "broke " + b + "'s " + (x.isEmpty() ? "chest" : x).replace('_', ' ')));
                case "took_from" -> {
                    if (items >= 4) sus.add(new Reason(15 + Math.min(items, 64) / 8.0, a, "took " + items + " items from " + b + "'s chest"));
                }
                case "left_in" -> {
                    if (items >= 8) cool.add(new Reason(15 + Math.min(items, 64) / 8.0, a, "left " + items + " items for " + b));
                }
                case "rode_horse" -> sus.add(new Reason(15, a, "took " + b + "'s " + (x.isEmpty() ? "horse" : x) + " for a ride"));
                case "egged" -> {
                    if (n >= 10) sus.add(new Reason(10 + Math.min(n, 100) / 10.0, a, "egged " + b + " " + n + " times"));
                }
                case "reeled_in" -> {
                    if (n >= 3) sus.add(new Reason(12, a, "reeled " + b + " in with a fishing rod " + n + " times"));
                }
                default -> { }
            }
        }
        JsonObject out = new JsonObject();
        add(out, "golden_egg", cool);
        add(out, "headless_chicken", sus);
        return out;
    }

    private static boolean namesIn(String text, String name) {
        return java.util.regex.Pattern.compile("(?i)(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(name) + "(?![A-Za-z0-9_])").matcher(text).find();
    }

    private static void add(JsonObject out, String label, List<Reason> xs) {
        if (xs.isEmpty()) return;
        xs.sort((p, q) -> p.score < q.score ? 1 : p.score > q.score ? -1 : 0);   // stable, highest first
        String w = xs.get(0).name;
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        JsonArray runners = new JsonArray();
        for (Reason r : xs) {
            if (r.name.equals(w)) reasons.add(r.text);
            else if (runners.size() < 3) {
                JsonArray pair = new JsonArray();
                pair.add(r.name);
                pair.add(r.text);
                runners.add(pair);
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("winner", w);
        JsonArray ra = new JsonArray();
        reasons.stream().limit(3).forEach(ra::add);
        o.add("reasons", ra);
        o.add("runners_up", runners);
        out.add(label, o);
    }
}
