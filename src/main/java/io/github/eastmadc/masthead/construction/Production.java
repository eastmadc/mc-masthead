package io.github.eastmadc.masthead.construction;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * What builds produce: mob deaths in the chunks a build stands in and the ring of chunks around it, counted per build and
 * type for the minute (main thread), then written as one raw line of build ids and counts (IO thread). No position ever
 * leaves the watch; the fold keeps 7 daily buckets per build.
 */
final class Production {
    /** One world's watched chunks: sorted chunk keys, and the builds each one credits. Immutable once published. */
    record Watch(long[] keys, int[][] sites) {}

    final String[] names;   // entity names, by ordinal
    private HashMap<Long, int[]> counts = new HashMap<>();   // main thread: (build, ordinal) -> deaths this minute

    Production(String[] entities) { names = entities; }

    static long chunk(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    /** One death (main thread): a chunk lookup, then a counter per build. Unwatched chunks cost one map lookup. */
    void count(Map<UUID, Watch> watch, UUID world, int cx, int cz, int ordinal) {
        Watch w = watch.get(world);
        if (w == null) return;
        int i = Arrays.binarySearch(w.keys, chunk(cx, cz));
        if (i < 0) return;
        for (int site : w.sites[i]) counts.computeIfAbsent(((long) site << 20) | ordinal, k -> new int[1])[0]++;
    }

    /** The minute's counts, for the IO thread (main thread starts a new minute). */
    HashMap<Long, int[]> swap() {
        HashMap<Long, int[]> out = counts;
        counts = new HashMap<>();
        return out;
    }

    /** The raw line for a minute's counts (IO thread): {"k":"f","f":{"7":{"d":{"slime":40}}}}, or "". */
    static String line(Map<Long, int[]> counts, String[] names) {
        TreeMap<Integer, JsonObject> by = new TreeMap<>();
        for (Map.Entry<Long, int[]> e : counts.entrySet()) {
            long k = e.getKey();
            int site = (int) (k >>> 20), ord = (int) (k & 0xFFFFF);
            String name = ord < names.length ? names[ord] : null;
            if (name == null || e.getValue()[0] <= 0) continue;
            by.computeIfAbsent(site, x -> new JsonObject()).addProperty(name, e.getValue()[0]);
        }
        if (by.isEmpty()) return "";
        JsonObject f = new JsonObject();
        for (Map.Entry<Integer, JsonObject> e : by.entrySet()) {
            JsonObject one = new JsonObject();
            one.add("d", e.getValue());
            f.add(String.valueOf(e.getKey()), one);
        }
        JsonObject o = new JsonObject();
        o.addProperty("k", "f");
        o.add("f", f);
        return new Gson().toJson(o) + "\n";
    }

    /**
     * The watch for an index (IO thread): every chunk a build's live cubes are in, plus the ring of 8 chunks around each (kill
     * rooms are often dug out beside what was placed), any height. A chunk where builds stand credits only those builds; a
     * ring chunk where no build stands credits every build it rings.
     */
    static Map<UUID, Watch> watch(SiteIndex idx) {
        Map<UUID, Watch> out = new HashMap<>();
        for (Map.Entry<String, Integer> we : idx.worlds.entrySet()) {
            if (we.getValue() >= idx.cubes.size()) continue;
            TreeMap<Long, TreeSet<Integer>> m = new TreeMap<>(), ring = new TreeMap<>();
            for (Map.Entry<Long, SiteIndex.Cube> e : idx.cubes.get(we.getValue()).entrySet()) {
                SiteIndex.Cube c = e.getValue();
                SiteIndex.Site s = c.site > 0 && c.n() > 0 ? idx.sites.get(c.site) : null;
                if (s == null || s.aliasOf != null) continue;
                int cx = SiteIndex.sx(e.getKey()) >> 2, cz = SiteIndex.sz(e.getKey()) >> 2;
                m.computeIfAbsent(chunk(cx, cz), k -> new TreeSet<>()).add(s.id);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) ring.computeIfAbsent(chunk(cx + dx, cz + dz), k -> new TreeSet<>()).add(s.id);
            }
            ring.keySet().removeAll(m.keySet());
            m.putAll(ring);
            if (m.isEmpty()) continue;
            long[] keys = new long[m.size()];
            int[][] sites = new int[m.size()][];
            int i = 0;
            for (Map.Entry<Long, TreeSet<Integer>> e : m.entrySet()) {
                keys[i] = e.getKey();
                sites[i++] = e.getValue().stream().mapToInt(Integer::intValue).toArray();
            }
            out.put(UUID.fromString(we.getKey()), new Watch(keys, sites));
        }
        return Map.copyOf(out);
    }

    // ------------------------------------------------------------ the fold's side: 7 daily buckets per build
    /** Adds a line's counts for one build to its bucket for date. */
    static TreeMap<String, TreeMap<String, Integer>> add(TreeMap<String, TreeMap<String, Integer>> days, String date, Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) return days;
        if (days == null) days = new TreeMap<>();
        TreeMap<String, Integer> d = days.computeIfAbsent(date, k -> new TreeMap<>());
        for (Map.Entry<String, Integer> e : counts.entrySet()) if (e.getValue() > 0) d.merge(e.getKey(), e.getValue(), Production::sum);
        return days;
    }

    /** a + b, stopping at Integer.MAX_VALUE: a busy farm's week never wraps to a negative count. */
    static int sum(int a, int b) { return (int) Math.min(Integer.MAX_VALUE, (long) a + b); }

    /** Only the last 7 days (date and the 6 before it); null when nothing is left. */
    static TreeMap<String, TreeMap<String, Integer>> keep7(TreeMap<String, TreeMap<String, Integer>> days, java.time.LocalDate date) {
        if (days == null) return null;
        days.headMap(date.minusDays(6).toString()).clear();
        return days.isEmpty() ? null : days;
    }

    /** One build's buckets folded into another's (a merge). */
    static TreeMap<String, TreeMap<String, Integer>> merge(TreeMap<String, TreeMap<String, Integer>> into, TreeMap<String, TreeMap<String, Integer>> from) {
        if (from != null) for (Map.Entry<String, TreeMap<String, Integer>> e : from.entrySet()) into = add(into, e.getKey(), e.getValue());
        return into;
    }

    /** The 7-day totals, biggest first then by name, at most max types. */
    static List<Map.Entry<String, Integer>> top(TreeMap<String, TreeMap<String, Integer>> days, int max) {
        TreeMap<String, Integer> sum = new TreeMap<>();
        if (days != null) for (TreeMap<String, Integer> d : days.values()) for (Map.Entry<String, Integer> e : d.entrySet()) sum.merge(e.getKey(), e.getValue(), Production::sum);
        List<Map.Entry<String, Integer>> l = new ArrayList<>(sum.entrySet());
        l.sort((x, y) -> x.getValue().equals(y.getValue()) ? x.getKey().compareTo(y.getKey()) : Integer.compare(y.getValue(), x.getValue()));
        return l.subList(0, Math.min(max, l.size()));
    }
}
