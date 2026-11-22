package io.github.eastmadc.masthead.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Police Blotter: Python BLOTTER_KINDS + parse_blotter(), reading blotter/DATE.jsonl (original Blotter format). */
final class BlotterFacts {
    private BlotterFacts() {}

    /**
     * kind -> (how to read it, fun weight). The descriptions are for the writer's prompt. Every kind names a KNOWN owner.
     * Older files also hold guessed-owner kinds (took_from_at, left_in_at, opened_container_at, mined_at, built_at: "near
     * b's spawn point"). They are not listed here, so read() skips those lines: a guess is never printed. slept_in_bed is
     * not listed either: a spawn point is not a feud between two players. The
     * collector still records it (it tells whose bed it is).
     */
    record Kind(String meaning, int weight) {}

    static final Map<String, Kind> KINDS = new LinkedHashMap<>();
    static {
        KINDS.put("killed_pet", new Kind("a killed b's pet (x = the animal)", 100));
        KINDS.put("broke_bed", new Kind("a broke b's bed", 90));
        KINDS.put("broke_container", new Kind("a broke b's container (x = chest/barrel/...)", 70));
        KINDS.put("took_from", new Kind("a took items out of b's own container (items listed)", 70));
        KINDS.put("left_in", new Kind("a left items in b's own container (items listed)", 45));
        KINDS.put("rode_horse", new Kind("a rode b's horse (x = horse/donkey/...)", 50));
        KINDS.put("reeled_in", new Kind("a hooked b with a fishing rod and reeled them in", 40));
        KINDS.put("leashed_pet", new Kind("a put a lead on b's pet (x = the animal)", 35));
        KINDS.put("hit_pet", new Kind("a hit b's pet (x = the animal)", 30));
        KINDS.put("egged", new Kind("a hit b with thrown eggs", 30));
        KINDS.put("snowballed", new Kind("a hit b with snowballs", 20));
        KINDS.put("opened_container", new Kind("a opened b's container (x = type)", 15));
        KINDS.put("bonked", new Kind("a hit b (x = what they hit with; fist = bare hands)", 10));
        KINDS.put("broke_build", new Kind("a broke blocks of b's build (n = about how many, x = what the Commission thinks it was)", 8));
    }

    private static final Set<String> ITEM_KINDS = Set.of("took_from", "left_in");

    /** One aggregated entry (Python dict with what/by/to and either items or n[/x]). */
    static final class Entry {
        final String what, by, to, x;
        final LinkedHashMap<String, Long> items;   // null for count kinds
        long n;
        double weight;

        Entry(String what, String by, String to, String x, boolean itemKind) {
            this.what = what; this.by = by; this.to = to; this.x = x;
            this.items = itemKind ? new LinkedHashMap<>() : null;
        }

        long itemTotal() {
            long s = 0;
            if (items != null) for (long v : items.values()) s += v;
            return s;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("what", what);
            o.addProperty("by", by);
            o.addProperty("to", to);
            if (items != null) {
                o.add("items", Stats.mapJson(items));
            } else {
                o.addProperty("n", n);
                if (!x.isEmpty()) o.addProperty("x", x);
            }
            return o;
        }
    }

    /** Entries in the window [lo, hi), aggregated, most interesting first, at most 25. Both players must be real. */
    static List<Entry> read(Path dataFolder, LocalDate date, long lo, long hi, Set<String> players) {
        LinkedHashMap<String, Entry> agg = new LinkedHashMap<>();
        for (LocalDate day : List.of(date.minusDays(1), date, date.plusDays(1))) {
            for (String line : Py.lines(dataFolder.resolve("blotter").resolve(day + ".jsonl"), 8 << 20, 200000)) {
                JsonElement el = Py.parse(line);
                if (el == null || !el.isJsonObject()) continue;
                JsonObject r = el.getAsJsonObject();
                Double t = FactsBuilder.dbl(r.get("t"));
                String k = FactsBuilder.str(r.get("k")), a = FactsBuilder.str(r.get("a")), b = FactsBuilder.str(r.get("b"));
                String x = FactsBuilder.str(r.get("x"));
                Double nd = FactsBuilder.dbl(r.get("n"));
                if (t == null || k == null || a == null || b == null || nd == null) continue;
                long n = (long) nd.doubleValue();   // Python int() truncates
                if (!(lo <= t && t < hi) || !KINDS.containsKey(k) || a.equals(b) || !players.contains(a) || !players.contains(b) || n <= 0) continue;
                x = x == null ? "" : x.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
                x = Py.head(x, 40);
                if (ITEM_KINDS.contains(k)) {
                    Entry e = agg.computeIfAbsent(k + "\t" + a + "\t" + b + "\t", key -> new Entry(k, a, b, "", true));
                    e.items.merge(x, n, Long::sum);
                } else {
                    String fx = x;
                    Entry e = agg.computeIfAbsent(k + "\t" + a + "\t" + b + "\t" + x, key -> new Entry(k, a, b, fx, false));
                    e.n += n;
                }
            }
        }
        netOut(agg);
        List<Entry> out = new ArrayList<>();
        for (Entry e : agg.values()) {
            if (e.what.equals("bonked") && e.n < 5) continue;
            if (e.items != null && e.items.isEmpty()) continue;
            long size;
            if (e.items != null) {
                List<Map.Entry<String, Long>> l = new ArrayList<>();
                e.items.forEach((k, v) -> l.add(Map.entry(k, v)));
                l.sort((p, q) -> Long.compare(q.getValue(), p.getValue()));
                e.items.clear();
                for (Map.Entry<String, Long> i : l.subList(0, Math.min(6, l.size()))) e.items.put(i.getKey(), i.getValue());
                size = e.itemTotal();
            } else {
                size = e.n;
            }
            e.weight = KINDS.get(e.what).weight() + Math.min(size, 64) / 4.0;
            out.add(e);
        }
        out.sort((p, q) -> Double.compare(q.weight, p.weight));
        return out.subList(0, Math.min(25, out.size()));
    }

    /** Items a took from b's container and put back in b's containers cancel out: moving things around is not news. */
    static void netOut(Map<String, Entry> agg) {
        for (Entry took : agg.values()) {
            if (!took.what.equals("took_from")) continue;
            Entry left = agg.get("left_in\t" + took.by + "\t" + took.to + "\t");
            if (left == null) continue;
            for (String item : List.copyOf(took.items.keySet())) {
                Long l = left.items.get(item);
                if (l == null) continue;
                long m = Math.min(took.items.get(item), l);
                took.items.merge(item, -m, Long::sum);
                left.items.merge(item, -m, Long::sum);
            }
            took.items.values().removeIf(v -> v <= 0);
            left.items.values().removeIf(v -> v <= 0);
        }
    }

    /**
     * Construction news' rows joined in: a (breaker, victim) pair it already judged loses its old mined_at row, so one act
     * is never reported twice (read() no longer returns mined_at, so today this only guards hand-made lists). Most
     * interesting first, at most 25.
     */
    static List<Entry> merge(List<Entry> entries, List<Entry> extra, Set<String> dropMinedAt) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (!(e.what.equals("mined_at") && dropMinedAt.contains(e.by + "\t" + e.to))) out.add(e);
        out.addAll(extra);
        out.sort((p, q) -> Double.compare(q.weight, p.weight));
        return out.subList(0, Math.min(25, out.size()));
    }

    static JsonArray toJson(List<Entry> entries) {
        JsonArray a = new JsonArray();
        entries.forEach(e -> a.add(e.toJson()));
        return a;
    }
}
