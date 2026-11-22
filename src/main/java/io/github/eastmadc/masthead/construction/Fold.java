package io.github.eastmadc.masthead.construction;

import io.github.eastmadc.masthead.config.Settings;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The daily fold: replays the raw lines into the index, groups cubes into builds, applies the players' requests, and works
 * out each build's kind, status, milestones, damage and retirement. Pure: everything in memory; the caller reads and
 * writes the files. Positions never leave this class and SiteIndex.
 */
final class Fold {
    private Fold() {}

    static final int[] MILESTONES = {1000, 2500, 5000, 10000, 25000, 50000};
    private static final long MASK58 = (1L << 58) - 1;

    /** What one fold needs besides the index and players.json. hidden = privacy.hide-players, lower case. */
    /** slimeSeeds: Overworld world UUID -> seed, for the slime chunk test (empty = no slime farms). ai: the kinds desk's answers by build id. */
    record Cfg(Settings.Construction c, Set<String> hidden, LocalDate date, Map<String, Integer> keyInfo, Map<String, Long> slimeSeeds,
               Map<Integer, KindDesk.Ask> ai) {
        Cfg(Settings.Construction c, Set<String> hidden, LocalDate date, Map<String, Integer> keyInfo) { this(c, hidden, date, keyInfo, Map.of()); }
        Cfg(Settings.Construction c, Set<String> hidden, LocalDate date, Map<String, Integer> keyInfo, Map<String, Long> slimeSeeds) {
            this(c, hidden, date, keyInfo, slimeSeeds, Map.of());
        }
        int info(String key) { return keyInfo.getOrDefault(key, (int) KeyRules.BUILT); }
        Long seed(SiteIndex idx, int world) {
            for (Map.Entry<String, Integer> e : idx.worlds.entrySet()) if (e.getValue() == world) return slimeSeeds.get(e.getKey());
            return null;
        }
    }

    /** One cube's activity in this fold (players by UUID string). Each map is null until used: a big day touches 300,000 cubes (L-8). */
    static final class DC {
        HashMap<String, Integer> placed, all, real, add, take, selfBreak, ownTnt, loss, mats;
        HashMap<String, HashMap<String, Integer>> knock;
        int clamped, yMin = Integer.MAX_VALUE, yMax = Integer.MIN_VALUE, air, wb;
        boolean created;
    }

    record Ag(long dom, int ag, long[] refs) {}

    /** A build's activity in this fold, summed over its cubes. */
    static final class SD {
        final HashMap<String, Integer> placed = new HashMap<>(), all = new HashMap<>(), real = new HashMap<>(), add = new HashMap<>(), take = new HashMap<>(),
                selfBreak = new HashMap<>(), ownTnt = new HashMap<>(), loss = new HashMap<>(), mats = new HashMap<>();
        final HashMap<String, HashMap<String, Integer>> knock = new HashMap<>();
        final HashMap<String, Set<Long>> breakCubes = new HashMap<>();   // breaker -> cubes they broke in (renovation spread)
        int clamped, touched, touchedOld;
    }

    /** One owner alert (kind 1 big, 2 maybe helping, 3 co-builders, 4 cause unknown) and its text. */
    record Alert(int kind, String text) {}

    /** One "broke_build" Police Blotter row. */
    record Blot(String by, String to, int n, String x) {}

    /** Per-build results the projector reads. */
    static final class Out {
        Kinds.M m;
        String suppressed, confidence, status;   // status: new | growing | renovated | self_demolished | null
        int netToday, startNet, crewPlacedToday, partsToday;
        int milestoneToday;
        boolean created, unretired, demolitionNow, leftOut;
        boolean seeded;   // the slime chunk test had this world's seed
        String incidentCause;
        int incidentBlocks;
        boolean incidentSelf;
        int selfTnt;         // blocks of their own the builder's TNT took today (when self)
        String incidentBy;   // the TNT's player when self
        List<String> newCrew = List.of();
        LinkedHashMap<String, Integer> netBy = new LinkedHashMap<>();   // player -> (placed - broken) today
    }

    static final class Result {
        final TreeMap<Integer, Out> out = new TreeMap<>();
        final List<Alert> alerts = new ArrayList<>();
        final List<String> newNames = new ArrayList<>(), clearedNames = new ArrayList<>();
        final List<Blot> blotter = new ArrayList<>();
        final List<List<String>> dropMinedAt = new ArrayList<>();
        final Map<String, String> names = new HashMap<>();   // UUID -> latest name
        int lines;
    }

    static Result run(SiteIndex idx, Prefs prefs, Iterable<RawLine.Line> lines, List<RawLine.Request> reqs, Cfg cfg) {
        Replay rp = new Replay(idx, prefs, reqs, cfg);
        for (RawLine.Line l : lines) rp.add(l);
        return rp.finish();
    }

    /**
     * A fold fed one raw line at a time (L-8): each line goes straight into the per-cube tallies and is then dropped, so
     * memory grows with the cubes touched, never with the size of the day's raw files. Create it after the requests are
     * read, add every line in file order, then finish() once.
     */
    static final class Replay {
        private final SiteIndex idx;
        private final Prefs prefs;
        private final List<RawLine.Request> reqs;
        private final Cfg cfg;
        private final Result r = new Result();
        private final String ds;
        private final Set<String> forgot = new HashSet<>();
        private final HashMap<Long, DC> day = new HashMap<>();
        private final List<Ag> ags = new ArrayList<>();
        private final HashMap<String, String> canon = new HashMap<>();   // one copy of each UUID, cause and key string, not one per line
        private boolean finished;
        private String rawDate;   // the date of the raw file being replayed (production is counted on that day)

        Replay(SiteIndex idx, Prefs prefs, List<RawLine.Request> reqs, Cfg cfg) {
            this.idx = idx; this.prefs = prefs; this.reqs = reqs; this.cfg = cfg;
            ds = rawDate = cfg.date().toString();
            for (RawLine.Request q : reqs) if (q.op().equals("forget")) forgot.add(q.player().toString());
            for (Map.Entry<String, Prefs.P> e : prefs.players.entrySet()) if (e.getValue().name != null) r.names.put(e.getKey(), e.getValue().name);
            for (Map.Entry<Integer, SiteIndex.Site> e : idx.sites.entrySet())
                for (Map.Entry<String, SiteIndex.Who> w : e.getValue().players.entrySet()) r.names.putIfAbsent(w.getKey(), w.getValue().name);
        }

        Result finish() {
            if (finished) throw new IllegalStateException("fold already finished");
            finished = true;
            return rest(idx, prefs, reqs, cfg, r, ds, day, ags);
        }

        /** The lines that follow come from the raw file for date (a backlog day's production stays on its own day). */
        void file(String date) { rawDate = date; }

        private String canon(String s) {
            String c = canon.putIfAbsent(s, s);
            return c == null ? s : c;
        }

        // 1. replay one line
        void add(RawLine.Line l) {
            if (finished) throw new IllegalStateException("fold already finished");
            if (l.k() == 'f') {   // production at builds: to the build (or the one it merged into); a forgotten build's is dropped
                for (RawLine.Prod pr : l.f()) {
                    SiteIndex.Site s = idx.sites.get(live(idx, pr.site()));
                    if (s == null) continue;
                    s.mobsDied = Production.add(s.mobsDied, rawDate, pr.died());
                }
                return;
            }
            String p = l.player() == null ? null : canon(l.player().toString());
            if (l.k() == 'p' && (forgot.contains(p) || cfg.hidden().contains(l.name().toLowerCase(Locale.ROOT)))) return;
            r.lines++;
            if (p != null) r.names.put(p, l.name());
            int w = idx.world(l.world().toString());
            HashMap<Long, SiteIndex.Cube> cubes = idx.cubes.get(w);
            long dom = -1;
            int domAbs = 0;
            for (int sc = 0; sc < 64; sc++) {
                int a = Math.abs(l.d()[sc]) + Math.abs(l.d()[64 + sc]) + Math.abs(l.d()[128 + sc]) + l.g()[sc];
                if (a > domAbs) { domAbs = a; dom = SiteIndex.cubeOf(l.cx(), l.sy(), l.cz(), sc); }
            }
            String by = l.by() == null ? null : canon(l.by().toString());
            if (by != null && forgot.contains(by)) by = null;
            // pass 0: natural ground broken (BUILT breaks of what was already tracked); pass 1: the netted deltas
            for (int pass = 0; pass < 2; pass++) for (int c = 0; c < 3; c++) for (int sc = 0; sc < 64; sc++) {
                int delta = pass == 1 ? l.d()[c * 64 + sc] : c == 0 ? -l.g()[sc] : 0;
                if (delta == 0) continue;
                long ck = SiteIndex.cubeOf(l.cx(), l.sy(), l.cz(), sc), gk = ((long) w << 58) | ck;
                SiteIndex.Cube cube = cubes.get(ck);
                if (delta > 0) {
                    if (l.k() != 'p') continue;
                    DC dc = day.computeIfAbsent(gk, k -> new DC());
                    if (cube == null) { cube = new SiteIndex.Cube(); cubes.put(ck, cube); dc.created = true; }
                    int add = Math.min(delta, Math.max(0, 64 - cube.n()));
                    if (c == 0) { cube.b += add; dc.clamped += delta - add; dc.placed = inc(dc.placed, p, delta); }
                    else if (c == 1) cube.l += add; else cube.p += add;
                    if (cube.own == null && add > 0) cube.own = p;
                    if (c < 2 && add > 0 && !p.equals(cube.own)) { if (cube.o == null) cube.o = new HashMap<>(); cube.o.merge(p, add, Integer::sum); }
                    cube.t = ds;
                    dc.add = inc(dc.add, p, add);
                    dc.all = inc(dc.all, p, delta);
                } else {
                    int have = cube == null ? 0 : c == 0 ? cube.b : c == 1 ? cube.l : cube.p;
                    if (have <= 0) continue;   // nobody built here: terrain, villages, older builds
                    int take = Math.min(-delta, have);
                    if (pass == 0) {   // ground: only what the breaker could have put there, never someone else's blocks
                        if (l.k() != 'p') continue;
                        if (!p.equals(cube.own)) take = theirs(cube, p, take);
                        if (take == 0) continue;
                        cube.b -= take;
                        cube.t = ds;
                        DC dc = day.computeIfAbsent(gk, k -> new DC());
                        dc.take = inc(dc.take, p, take);
                        dc.selfBreak = inc(dc.selfBreak, p, take);
                        trim(cube);
                        continue;
                    }
                    if (c == 0) cube.b -= take; else if (c == 1) cube.l -= take; else cube.p -= take;
                    cube.t = ds;
                    DC dc = day.computeIfAbsent(gk, k -> new DC());
                    if (l.k() == 'p') {
                        dc.take = inc(dc.take, p, take);
                        if (c < 2) {
                            int mine = p.equals(cube.own) ? take : theirs(cube, p, take);   // their own blocks first, never the owner's
                            if (mine > 0) dc.selfBreak = inc(dc.selfBreak, p, mine);
                            if (take > mine && cube.own != null) knock(dc, p, cube.own, take - mine);
                        }
                    } else if (l.cause().equals("summon")) {
                        // a golem or wither built here: the blocks are just gone, never damage
                    } else if (by != null && c < 2) {
                        int mine = by.equals(cube.own) ? take : theirs(cube, by, take);
                        if (mine > 0) dc.ownTnt = inc(dc.ownTnt, by, mine);
                        if (take > mine && cube.own != null) knock(dc, by, cube.own, take - mine);
                    } else if (by == null) dc.loss = inc(dc.loss, canon(l.cause()), take);
                    if (c < 2) trim(cube);
                }
            }
            if (dom < 0) return;
            long dgk = ((long) w << 58) | dom;
            DC dc = day.computeIfAbsent(dgk, k -> new DC());
            for (Map.Entry<String, Integer> e : l.m().entrySet()) {
                dc.mats = inc(dc.mats, canon(e.getKey()), e.getValue());
                int info = cfg.info(e.getKey());
                if (p != null && e.getValue() > 0 && KeyRules.cls(info) == KeyRules.BUILT && (info & KeyRules.UTILITY) == 0)
                    dc.real = inc(dc.real, p, e.getValue());
            }
            if (l.k() == 'p') {
                if (l.y0() >= 0) { dc.yMin = Math.min(dc.yMin, l.sy() * 16 + l.y0()); dc.yMax = Math.max(dc.yMax, l.sy() * 16 + l.y1()); }
                dc.air += l.air();
                dc.wb += l.wb();
                if (l.ag() > 0) {
                    long[] refs = new long[l.agc().size()];
                    for (int i = 0; i < refs.length; i++) {
                        int[] a = l.agc().get(i);
                        refs[i] = ((long) w << 58) | SiteIndex.cubeOf(a[0], a[1], a[2], a[3]);
                    }
                    ags.add(new Ag(dgk, l.ag(), refs));
                }
            }
        }
    }

    // Small first tables: most cubes see one player. Same iteration order where it counts: keys that share a bucket in a
    // bigger map share one here too, and keep their order, so the per-build maps fill exactly as before.
    private static HashMap<String, Integer> inc(HashMap<String, Integer> m, String k, int v) {
        if (m == null) m = new HashMap<>(2);
        m.merge(k, v, Integer::sum);
        return m;
    }

    private static void knock(DC dc, String by, String owner, int n) {
        if (dc.knock == null) dc.knock = new HashMap<>(2);
        dc.knock.computeIfAbsent(by, k -> new HashMap<>(2)).merge(owner, n, Integer::sum);
    }

    private static Result rest(SiteIndex idx, Prefs prefs, List<RawLine.Request> reqs, Cfg cfg, Result r, String ds, HashMap<Long, DC> day, List<Ag> ags) {
        // 2. cluster the cubes that changed today
        HashMap<Long, Long> parent = new HashMap<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        for (long gk : day.keySet()) {
            SiteIndex.Cube c = cube(idx, gk);
            if (c != null && c.n() > 0 && seen.add(gk)) queue.add(gk);
        }
        while (!queue.isEmpty()) {
            long gk = queue.poll();
            parent.putIfAbsent(gk, gk);
            int w = (int) (gk >>> 58);
            long ck = gk & MASK58;
            SiteIndex.Cube me = idx.cubes.get(w).get(ck);
            int x = SiteIndex.sx(ck), y = SiteIndex.sy(ck), z = SiteIndex.sz(ck);
            for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                long nk = SiteIndex.cubeKey(x + dx, y + dy, z + dz);
                SiteIndex.Cube nb = idx.cubes.get(w).get(nk);
                if (nb == null || (nb.n() == 0 && nb.site == 0)) continue;
                boolean far = Math.abs(dx) == 2 || Math.abs(dy) == 2 || Math.abs(dz) == 2;
                if (far && !Objects.equals(nb.own, me.own)) continue;   // across a gap only for the same builder
                long ngk = ((long) w << 58) | nk;
                parent.putIfAbsent(ngk, ngk);
                union(parent, gk, ngk);
                if (nb.site == 0 && nb.n() > 0 && seen.add(ngk)) queue.add(ngk);
            }
        }
        HashMap<Long, List<Long>> comps = new HashMap<>();
        for (long gk : parent.keySet()) comps.computeIfAbsent(find(parent, gk), k -> new ArrayList<>()).add(gk);
        HashMap<Long, List<Ag>> agsByDom = new HashMap<>();
        for (Ag a : ags) agsByDom.computeIfAbsent(a.dom(), k -> new ArrayList<>()).add(a);
        HashMap<Integer, Integer> merged = new HashMap<>();   // old id -> new id
        Set<Integer> created = new HashSet<>();
        for (List<Long> comp : comps.values()) {
            TreeSet<Integer> ids = new TreeSet<>();
            for (long gk : comp) { int s = cube(idx, gk).site; if (s > 0) ids.add(live(idx, s)); }
            if (ids.isEmpty()) {
                int net = 0, placed = 0;
                double u = 0;
                for (long gk : comp) {
                    SiteIndex.Cube c = cube(idx, gk);
                    net += c.n();
                    placed += c.pb + sum(day.get(gk) == null ? null : day.get(gk).placed);
                    u += c.u;
                }
                HashMap<Long, Double> uBy = new HashMap<>();
                for (long gk : comp) for (Ag a : agsByDom.getOrDefault(gk, List.of())) {
                    double share = untrackedShare(idx, a);
                    u += share;
                    uBy.merge(gk, share, Double::sum);
                }
                if (net >= 16) {
                    SiteIndex.Site s = new SiteIndex.Site();
                    s.id = idx.nextSite++;
                    s.world = (int) (comp.get(0) >>> 58);
                    s.created = ds;
                    s.lastGrowth = ds;
                    s.existing = placed >= 16 && u * 4 >= placed;
                    idx.sites.put(s.id, s);
                    created.add(s.id);
                    for (long gk : comp) { SiteIndex.Cube c = cube(idx, gk); c.site = s.id; c.pb = 0; c.u = 0; }
                } else {
                    for (long gk : comp) {
                        SiteIndex.Cube c = cube(idx, gk);
                        c.pb += sum(day.get(gk) == null ? null : day.get(gk).placed);
                        c.u += (int) Math.round(uBy.getOrDefault(gk, 0.0));
                    }
                }
            } else {
                int target = ids.stream().filter(i -> idx.sites.get(i) != null && !idx.sites.get(i).retired).findFirst().orElse(ids.first());   // never into a retired build
                for (int other : ids) if (other != target) { mergeInto(idx.sites.get(target), idx.sites.get(other)); merged.put(other, target); }
                for (long gk : comp) { SiteIndex.Cube c = cube(idx, gk); c.site = target; c.pb = 0; c.u = 0; }
            }
        }
        if (!merged.isEmpty())   // follow the whole alias chain: two merges in one fold can leave 3 -> 2 -> 1
            for (HashMap<Long, SiteIndex.Cube> m : idx.cubes) for (SiteIndex.Cube c : m.values()) if (c.site > 0) c.site = live(idx, c.site);

        // map the day's tallies to builds
        HashMap<Integer, SD> sd = new HashMap<>();
        for (Map.Entry<Long, DC> e : day.entrySet()) {
            SiteIndex.Cube c = cube(idx, e.getKey());
            if (c == null || c.site == 0) continue;
            SD s = sd.computeIfAbsent(c.site, k -> new SD());
            DC d = e.getValue();
            s.touched++;
            if (!d.created) s.touchedOld++;
            add(s.placed, d.placed); add(s.all, d.all); add(s.real, d.real); add(s.add, d.add); add(s.take, d.take); add(s.selfBreak, d.selfBreak);
            add(s.ownTnt, d.ownTnt); add(s.loss, d.loss); add(s.mats, d.mats);
            if (d.knock != null) for (Map.Entry<String, HashMap<String, Integer>> k : d.knock.entrySet()) {
                add(s.knock.computeIfAbsent(k.getKey(), x -> new HashMap<>()), k.getValue());
                s.breakCubes.computeIfAbsent(k.getKey(), x -> new HashSet<>()).add(e.getKey());
            }
            if (d.selfBreak != null) for (String b : d.selfBreak.keySet()) s.breakCubes.computeIfAbsent(b, x -> new HashSet<>()).add(e.getKey());
            s.clamped += d.clamped;
            SiteIndex.Site site = idx.sites.get(c.site);
            if (d.yMin <= d.yMax) { site.yMin = Math.min(site.yMin, d.yMin); site.yMax = Math.max(site.yMax, d.yMax); }
            site.air += d.air;
            site.wb += d.wb;
        }

        // 3. crew and totals
        for (Map.Entry<Integer, SD> e : sd.entrySet()) {
            SiteIndex.Site s = idx.sites.get(e.getKey());
            SD d = e.getValue();
            Set<String> who = new HashSet<>(d.placed.keySet());
            who.addAll(d.take.keySet());
            who.addAll(d.add.keySet());
            for (String p : who) {
                SiteIndex.Who w = s.players.computeIfAbsent(p, k -> new SiteIndex.Who());
                w.name = r.names.getOrDefault(p, w.name);
                w.placed += d.all.getOrDefault(p, 0);   // every block placed, so a pure crop farm has a crew too
                w.real += d.real.getOrDefault(p, 0);
                w.broken += d.take.getOrDefault(p, 0);
                w.net += d.add.getOrDefault(p, 0) - d.take.getOrDefault(p, 0);
            }
            for (Map.Entry<String, Integer> m : d.mats.entrySet()) s.mats.merge(m.getKey(), m.getValue(), Integer::sum);
            s.clamped += d.clamped;
        }
        HashMap<Integer, List<String>> newCrew = new HashMap<>();
        for (SiteIndex.Site s : idx.sites.values()) {
            if (s.aliasOf != null || !sd.containsKey(s.id)) continue;
            List<String> old = s.crew;
            List<String> crew = crew(s, created.contains(s.id), cfg.hidden());
            List<String> joined = new ArrayList<>();
            if (!created.contains(s.id)) for (String p : crew) if (!old.contains(p)) joined.add(p);
            newCrew.put(s.id, joined);
            s.crew = crew;
        }

        // 4. requests: levels and notices first, then build requests (only ever for a build the player is crew of)
        for (RawLine.Request q : reqs) {
            Prefs.P pp = prefs.get(q.player().toString());
            switch (q.op()) {
                case "private", "public" -> pp.level = q.op();
                case "noticed" -> { if (q.date() != null && (pp.noticed == null || q.date().compareTo(pp.noticed) < 0)) pp.noticed = q.date(); }
                case "forget" -> forget(idx, prefs, q.player().toString());
                default -> { }
            }
        }
        int newNames = 0;
        for (RawLine.Request q : reqs) {
            String p = q.player().toString();
            if (!Set.of("secret", "unsecret", "name", "name_clear", "kind", "done", "sign").contains(q.op())) continue;
            boolean secretOp = q.op().equals("secret") || q.op().equals("unsecret");
            // a sign names only the build it is on (never the signer's other builds)
            SiteIndex.Site s = q.op().equals("sign") ? at(idx, q.world(), q.cube(), p)
                    : secretOp && q.cube() != null ? at(idx, q.world(), q.cube(), p) : resolve(idx, q, p);
            if (secretOp && q.cube() != null && q.world() != null) {
                // a build too small to track yet: hold the secret for the build that grows there (never another build)
                Integer w = idx.worlds.get(q.world().toString());
                int wi = w != null ? w : idx.world(q.world().toString());
                idx.pending.removeIf(x -> x.p.equals(p) && x.w == wi && near(x.c, q.cube()));
                if (s == null && q.op().equals("secret")) {
                    SiteIndex.Pending x = new SiteIndex.Pending();
                    x.p = p; x.w = wi; x.c = q.cube(); x.d = ds;
                    idx.pending.add(x);
                }
            }
            if (s == null) continue;
            switch (q.op()) {
                case "secret" -> { if (!s.secretBy.contains(p)) s.secretBy.add(p); }
                case "unsecret" -> s.secretBy.remove(p);
                case "name", "sign" -> {
                    boolean sign = q.op().equals("sign");
                    if (!cfg.c().names() || q.value() == null) break;
                    // a sign only fills a build with no name (a /news build name always wins), and never for a private builder
                    if (sign && (!cfg.c().signNames() || s.name != null || "private".equals(prefs.level(p, cfg.c())))) break;
                    Set<String> known = new HashSet<>(r.names.values());
                    known.addAll(cfg.hidden());   // a hidden player's name is never printed, in a build name either
                    String n = sign ? NameRules.signName(q.value(), known) : NameRules.check(q.value(), known, "clean");
                    if (n == null || n.equals(s.name)) break;
                    if (newNames >= 5) break;   // at most 5 new names a paper, so every name that can print was in a push
                    newNames++;
                    s.name = n;
                    s.nameBy = p;
                    s.nameFrom = ds;
                    r.newNames.add("New build name: \"" + n + "\", " + (sign ? "from a sign by " : "filed by ") + r.names.getOrDefault(p, "a player") + " (" + permitText(s)
                            + ", builders " + String.join(", ", crewNames(s, r.names)) + "). Not printed today; it prints from the next paper. "
                            + "/masthead construction clear-name " + n + " removes it.");
                }
                case "name_clear" -> {
                    if (s.name == null) break;
                    r.clearedNames.add("Build name cleared: \"" + s.name + "\" (" + permitText(s) + ", builders " + String.join(", ", crewNames(s, r.names))
                            + ") was filed by " + r.names.getOrDefault(s.nameBy, "a player") + " and cleared by " + r.names.getOrDefault(p, "a player") + ".");
                    s.name = null; s.nameBy = null; s.nameFrom = null;
                }
                case "kind" -> {   // a builder's word beats the Commission's guess
                    if ("clear".equals(q.value())) s.kindSet = null;
                    else if (Kinds.ALL.contains(q.value())) s.kindSet = q.value();
                }
                case "done" -> {
                    if (s.done && s.doneReported) break;   // already reported: a second "done" changes nothing
                    s.done = true;
                    s.doneBy = p;
                }
                default -> { }
            }
        }

        String held = cfg.date().minusDays(cfg.c().forgetAfterDays()).toString();
        idx.pending.removeIf(x -> x.d == null || x.d.compareTo(held) < 0);
        for (java.util.Iterator<SiteIndex.Pending> it = idx.pending.iterator(); it.hasNext(); ) {
            SiteIndex.Pending x = it.next();
            SiteIndex.Site s = x.w < idx.cubes.size() ? at(idx, x.w, x.c, x.p) : null;
            if (s == null) continue;
            if (!s.secretBy.contains(x.p)) s.secretBy.add(x.p);
            it.remove();
        }
        // a secret build leaves the Town Survey page for good (unsecret does not bring it back)
        if (prefs.register != null)
            for (SiteIndex.Site s : idx.sites.values()) {
                SiteIndex.Site l = idx.sites.get(live(idx, s.id));   // a joined build's secret covers the alias's old permit too
                if (s.permit != null && l != null && !l.secretBy.isEmpty()) prefs.register.removeIf(x -> x.permit == s.permit);
            }

        // 5. metrics, kind, status, milestones, damage; 6. retire, un-retire, forget
        HashMap<Integer, List<Long>> siteCubes = new HashMap<>();
        for (int w = 0; w < idx.cubes.size(); w++)
            for (Map.Entry<Long, SiteIndex.Cube> e : idx.cubes.get(w).entrySet())
                if (e.getValue().site > 0) siteCubes.computeIfAbsent(e.getValue().site, k -> new ArrayList<>()).add(e.getKey());
        int bigBlocks = cfg.c().bigDemolitionBlocks(), bigPct = cfg.c().bigDemolitionPercent();
        for (SiteIndex.Site s : new ArrayList<>(idx.sites.values())) {
            if (s.aliasOf != null) continue;
            Out o = new Out();
            r.out.put(s.id, o);
            SD d = sd.getOrDefault(s.id, new SD());
            o.created = created.contains(s.id);
            o.newCrew = newCrew.getOrDefault(s.id, List.of());
            Long seed = cfg.seed(idx, s.world);
            o.seeded = seed != null;
            o.m = metrics(s, idx.cubes.get(s.world), siteCubes.getOrDefault(s.id, List.of()), cfg, seed);
            s.mobsDied = Production.keep7(s.mobsDied, cfg.date());
            int n = o.m.n();
            o.startNet = o.created ? 0 : s.net;
            o.netToday = n - o.startNet;
            s.net = n;
            for (String p : s.players.keySet()) {
                int v = d.add.getOrDefault(p, 0) - d.take.getOrDefault(p, 0);
                if (v != 0) o.netBy.put(p, v);
            }
            int crewAllToday = 0;   // every crew placement, crops too: a replanted farm is still in use
            for (String p : s.crew) { o.crewPlacedToday += d.placed.getOrDefault(p, 0); crewAllToday += d.all.getOrDefault(p, 0); }
            o.suppressed = Kinds.suppressed(o.m, cfg.c().minBlocks());

            // kind: the builder's label, else the AI's, else the rules, else the shape's guess. A printed build's kind waits
            // two folds to change, except a label, a build's first AI kind or a mob kind its own deaths show (not a low
            // answer). A failed re-ask keeps the last AI kind.
            KindDesk.Ask ai = cfg.ai().get(s.id);
            boolean atOnce = false;
            if (ai != null && s.kindSet == null && KindDesk.same(ai, n, s.mats)) {
                atOnce = s.aiKind == null || KindDesk.shownByDeaths(ai);
                s.aiKind = ai.kind(); s.aiConf = ai.confidence(); s.aiNet = ai.net(); s.aiTop5 = ai.top5(); s.aiProd = ai.prod(); s.aiAsked = ds; s.aiList = ai.list();
            }
            if ("build".equals(s.kind)) s.kind = null;   // stored by older versions: no kind at all
            String rk = Kinds.kind(o.m);
            String k = s.kindSet != null ? s.kindSet : s.retired && s.kind != null ? s.kind   // a taken-down build keeps its last kind
                    : s.aiKind != null ? s.aiKind : !rk.equals("build") ? rk : Kinds.fallback(o.m);
            boolean changed = false;
            if ((s.kindSet != null || atOnce) && !k.equals(s.kind)) { changed = s.kind != null; s.kind = k; s.kindStreak = 1; s.kindPending = null; }
            else if (s.kind == null || (s.permit == null && !k.equals(s.kind))) { changed = s.kind != null; s.kind = k; s.kindStreak = 1; s.kindPending = null; }
            else if (k.equals(s.kind)) { s.kindStreak++; s.kindPending = null; }
            else if (k.equals(s.kindPending)) { s.kind = k; s.kindStreak = 1; s.kindPending = null; changed = true; }
            else s.kindPending = k;
            o.confidence = s.kindSet != null ? "high" : Kinds.confidence(o.m, s.kind, s.aiKind, s.aiConf, s.kindStreak, changed || s.kindPending != null);

            // damage by other players (§3.9)
            int renBreaks = 0;
            Set<Long> renCubes = new HashSet<>();
            for (String p : s.crew) {
                renBreaks += d.selfBreak.getOrDefault(p, 0);
                if (d.selfBreak.containsKey(p)) renCubes.addAll(d.breakCubes.getOrDefault(p, Set.of()));
                s.crewBroke += d.selfBreak.getOrDefault(p, 0) + d.ownTnt.getOrDefault(p, 0);
                s.crewBrokeOwn += d.selfBreak.getOrDefault(p, 0) + d.ownTnt.getOrDefault(p, 0);
            }
            for (Map.Entry<String, HashMap<String, Integer>> kb : d.knock.entrySet()) {
                String breaker = kb.getKey();
                int total = 0;
                for (int v : kb.getValue().values()) total += v;
                boolean crew = s.crew.contains(breaker);
                if (crew) s.crewBroke += total;
                for (Map.Entry<String, Integer> kv : kb.getValue().entrySet()) {
                    String victim = kv.getKey();
                    int nn = kv.getValue();
                    if (nn < 20) continue;   // walking past
                    int m = d.real.getOrDefault(breaker, 0);
                    boolean helping = m * 2 >= nn, big = nn >= bigBlocks || nn * 100L >= (long) bigPct * o.startNet;
                    String bn = r.names.getOrDefault(breaker, "someone"), vn = r.names.getOrDefault(victim, "someone");
                    if (!crew && cfg.c().crossDemolition().equals("off")) continue;
                    r.dropMinedAt.add(List.of(bn, vn));
                    if (helping) {
                        if (crew) { renBreaks += nn; renCubes.addAll(d.breakCubes.getOrDefault(breaker, Set.of())); }
                        if (big) {
                            r.alerts.add(new Alert(2, "Building check: " + bn + " broke about " + round10(nn) + " and placed about " + round10(m)
                                    + " blocks on a build " + vn + " made (may be helping). It was not printed."));
                            s.harmed = true;
                        }
                    } else if (crew) {
                        if (big) {
                            r.alerts.add(new Alert(3, "Building check: " + bn + " broke about " + round10(nn) + " blocks that co-builder " + vn
                                    + " placed on a shared build. Could be a redesign, could be a falling-out. It was not printed."));
                            s.harmed = true;
                            o.leftOut = true;
                        }
                    } else if (big) {
                        r.alerts.add(new Alert(1, "Building check: " + bn + " broke about " + round10(nn) + " blocks of a build " + vn + " made. "
                                + bn + " is not listed as a builder there, so this could be teamwork or could be griefing. It was not printed. "
                                + "Worth checking before saying anything."));
                        s.harmed = true;
                        o.leftOut = true;
                    } else if (cfg.c().crossDemolition().equals("blotter")) {
                        s.harmed = true;
                        if (blotterOk(s, victim, prefs, cfg)) r.blotter.add(new Blot(bn, vn, nn, Kinds.display(Kinds.shown(s.kind, cfg.c().detail()))));
                    }
                }
            }
            // damage nobody can be named for, and a builder's own TNT
            int incident = 0;
            String cause = null;
            int top = 0;
            for (Map.Entry<String, Integer> l : d.loss.entrySet()) {
                int nn = l.getValue();
                boolean big = nn >= bigBlocks || (nn >= 20 && nn * 100L >= (long) bigPct * o.startNet);
                if (!l.getKey().equals("creeper") && big) {
                    String bn = crewNames(s, r.names).isEmpty() ? "someone" : crewNames(s, r.names).get(0);
                    r.alerts.add(new Alert(4, "Building check: about " + round10(nn) + " blocks of " + bn + "'s build were burned or blown up, cause unknown. It was not printed."));
                    s.harmed = true;
                    o.leftOut = true;
                    continue;
                }
                incident += nn;
                if (nn > top) { top = nn; cause = l.getKey(); }
            }
            for (Map.Entry<String, Integer> t : d.ownTnt.entrySet()) {
                incident += t.getValue();
                if (t.getValue() > top) { top = t.getValue(); cause = "tnt"; }
            }
            // self only when the one builder's own TNT is the only TNT on their own blocks here today
            if (d.ownTnt.size() == 1 && s.crew.size() == 1 && d.ownTnt.containsKey(s.crew.get(0))) {
                o.incidentSelf = true; o.incidentBy = s.crew.get(0); o.selfTnt = d.ownTnt.get(s.crew.get(0));
            }
            if (incident >= 10 && !o.leftOut) { o.incidentCause = cause; o.incidentBlocks = incident; }
            if (o.incidentSelf && !"tnt".equals(cause)) { o.incidentSelf = false; o.incidentBy = null; }

            // status
            boolean old = !o.created && s.created != null && LocalDate.parse(s.created).isBefore(cfg.date().minusDays(1));
            if (o.created && o.suppressed == null) o.status = "new";
            else if (o.netToday >= 16) o.status = "growing";
            if (old && d.touched > 0 && d.touchedOld * 10 >= d.touched * 7 && o.crewPlacedToday >= 32 && renBreaks >= 16
                    && renBreaks * 4 >= o.crewPlacedToday && renCubes.size() >= 3) o.status = "renovated";
            if (!s.retired && s.peakNet >= 200 && s.crewBroke * 2 >= s.peakNet && n * 4 < s.peakNet) {
                o.status = "self_demolished";
                s.retired = true;
                s.retiredOn = ds;
                if (s.crewBrokeOwn * 2 >= s.crewBroke && !s.harmed && !s.demolitionReported) s.demolitionDue = true;
            }
            if (o.suppressed == null && !s.retired)
                for (int ms : MILESTONES) if (n >= ms && !s.milestones.contains(ms)) { s.milestones.add(ms); o.milestoneToday = ms; }
            o.partsToday = partsToday(d.mats, cfg);

            // un-retire, retire, growth
            if (s.retired && !ds.equals(s.retiredOn) && o.netToday >= 16) {
                s.retired = false; s.retiredOn = null; s.peakNet = n; s.lowFolds = 0; s.demolitionDue = false; s.demolitionReported = false;
                s.crewBroke = 0; s.crewBrokeOwn = 0; s.harmed = false; s.rebuild = s.permit != null;
                o.unretired = true;
            }
            if (s.demolitionDue && !ds.equals(s.retiredOn)) {
                if (!o.unretired && !s.harmed) o.demolitionNow = true;
                s.demolitionDue = false;
            }
            if (!s.retired) {
                if (s.peakNet > 0 && n * 4 < s.peakNet) { if (++s.lowFolds >= 3) { s.retired = true; s.retiredOn = ds; } }
                else s.lowFolds = 0;
                s.peakNet = Math.max(s.peakNet, n);
            }
            if (crewAllToday >= 16 || (o.created && sum(d.placed) >= 16)) s.lastGrowth = ds;
            if (o.netToday >= 16) s.days++;
            if (d.touched > 0) {
                SiteIndex.Hist h = new SiteIndex.Hist();
                h.d = ds; h.pl = sum(d.placed); h.br = sum(d.take); h.n = o.netToday;
                s.history.add(h);
                if (s.history.size() > 60) s.history.remove(0);
            }
            // a forgotten secret build's secret carries over to the builder's next addition to an old build
            if (o.created && s.existing)
                for (String p : s.crew) { Prefs.P pp = prefs.players.get(p); if (pp != null && pp.secretNext) { if (!s.secretBy.contains(p)) s.secretBy.add(p); pp.secretNext = false; } }
        }

        // forget idle builds, drop old loose cubes
        Set<Integer> gone = new HashSet<>();
        for (SiteIndex.Site s : new ArrayList<>(idx.sites.values())) {
            if (s.aliasOf != null) continue;
            String last = s.lastGrowth != null ? s.lastGrowth : s.created;
            if (last == null || !LocalDate.parse(last).plusDays(cfg.c().forgetAfterDays()).isBefore(cfg.date())) continue;
            for (String p : s.secretBy) prefs.get(p).secretNext = true;
            idx.sites.remove(s.id);
            r.out.remove(s.id);
            gone.add(s.id);
        }
        if (!gone.isEmpty()) for (HashMap<Long, SiteIndex.Cube> m : idx.cubes) m.values().removeIf(c -> gone.contains(c.site));
        idx.sites.values().removeIf(s -> s.aliasOf != null && idx.sites.get(live(idx, s.id)) == null);   // an alias goes with its build
        // shortcut: no 200,000-cube cap on loose cubes yet; add it (oldest loose first, one warning a day) before big servers
        String looseCut = cfg.date().minusDays(7).toString();
        for (HashMap<Long, SiteIndex.Cube> m : idx.cubes)
            m.values().removeIf(c -> c.site == 0 && (c.n() == 0 || c.t == null || c.t.compareTo(looseCut) < 0));
        for (Map.Entry<String, String> e : r.names.entrySet()) if (prefs.players.containsKey(e.getKey())) prefs.players.get(e.getKey()).name = e.getValue();
        return r;
    }

    private static int partsToday(Map<String, Integer> mats, Cfg cfg) {
        int n = 0;
        for (Map.Entry<String, Integer> e : mats.entrySet()) {
            int info = cfg.info(e.getKey());
            if (e.getValue() > 0 && (info & (KeyRules.REDSTONE | KeyRules.MACHINE | KeyRules.CROP)) != 0) n += e.getValue();
        }
        return n;
    }

    /** Crew: 10% of the build's placements or 200 placed; joining a build that already has a crew takes real blocks. */
    static List<String> crew(SiteIndex.Site s, boolean createdToday, Set<String> hidden) {
        int total = 0;
        for (SiteIndex.Who w : s.players.values()) total += Math.max(0, w.placed);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, SiteIndex.Who> e : s.players.entrySet()) {
            SiteIndex.Who w = e.getValue();
            if (w.name != null && hidden.contains(w.name.toLowerCase(Locale.ROOT))) continue;
            int count = createdToday || s.crew.isEmpty() || s.crew.contains(e.getKey()) ? w.placed : w.real;
            if (count > 0 && (count >= 200 || count * 10L >= total)) out.add(e.getKey());
        }
        return out;
    }

    static List<String> crewNames(SiteIndex.Site s, Map<String, String> names) {
        List<String> out = new ArrayList<>();
        for (String p : s.crew) out.add(names.getOrDefault(p, s.players.containsKey(p) ? s.players.get(p).name : "someone"));
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    static String permitText(SiteIndex.Site s) {
        return s.permit == null ? "no permit yet" : "Permit #" + s.permit;
    }

    /** A Blotter line names a build: the victim and every crew member public and noticed, and the build not secret (§4.1). */
    private static boolean blotterOk(SiteIndex.Site s, String victim, Prefs prefs, Cfg cfg) {
        if (!s.secretBy.isEmpty()) return false;
        Set<String> who = new HashSet<>(s.crew);
        who.add(victim);
        for (String u : who) {
            Prefs.P p = prefs.players.get(u);
            if (!"public".equals(prefs.level(u, cfg.c())) || p == null || p.noticed == null
                    || p.noticed.compareTo(cfg.date().minusDays(1).toString()) >= 0) return false;
        }
        return true;
    }

    /** The build a request is about: the one stood in (or next to) if crew, else the player's most recently grown build. */
    private static SiteIndex.Site resolve(SiteIndex idx, RawLine.Request q, String p) {
        SiteIndex.Site here = at(idx, q.world(), q.cube(), p);
        if (here != null) return here;
        SiteIndex.Site best = null;
        for (SiteIndex.Site s : idx.sites.values()) {
            if (s.aliasOf != null || !s.crew.contains(p)) continue;
            if (best == null || String.valueOf(s.lastGrowth).compareTo(String.valueOf(best.lastGrowth)) >= 0) best = s;
        }
        return best;
    }

    /** The build at (or next to) a cube that p is crew of, or null. */
    private static SiteIndex.Site at(SiteIndex idx, java.util.UUID world, Long cube, String p) {
        if (world == null || cube == null) return null;
        Integer w = idx.worlds.get(world.toString());
        return w == null ? null : at(idx, w, cube, p);
    }

    private static SiteIndex.Site at(SiteIndex idx, int w, long ck, String p) {
        int x = SiteIndex.sx(ck), y = SiteIndex.sy(ck), z = SiteIndex.sz(ck);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            SiteIndex.Cube c = idx.cubes.get(w).get(SiteIndex.cubeKey(x + dx, y + dy, z + dz));
            if (c == null || c.site == 0) continue;
            SiteIndex.Site s = idx.sites.get(live(idx, c.site));
            if (s != null && s.crew.contains(p)) return s;
        }
        return null;
    }

    private static boolean near(long a, long b) {
        return Math.abs(SiteIndex.sx(a) - SiteIndex.sx(b)) <= 1 && Math.abs(SiteIndex.sy(a) - SiteIndex.sy(b)) <= 1 && Math.abs(SiteIndex.sz(a) - SiteIndex.sz(b)) <= 1;
    }

    /** /news build forget, as the fold sees it: the player's cubes and totals go, and they are private. */
    static void forget(SiteIndex idx, Prefs prefs, String p) {
        Set<Integer> touched = new HashSet<>();
        for (HashMap<Long, SiteIndex.Cube> m : idx.cubes) {
            m.values().removeIf(c -> { if (!p.equals(c.own)) return false; touched.add(c.site); return true; });
            for (SiteIndex.Cube c : m.values()) if (c.o != null && c.o.remove(p) != null) { touched.add(c.site); if (c.o.isEmpty()) c.o = null; }
        }
        idx.pending.removeIf(x -> p.equals(x.p));
        for (SiteIndex.Site s : new ArrayList<>(idx.sites.values())) {
            if (s.players.containsKey(p)) touched.add(s.id);
            s.players.remove(p);
            s.crew.remove(p);
            s.secretBy.remove(p);
            if (p.equals(s.doneBy)) s.doneBy = null;
            if (p.equals(s.nameBy)) s.nameBy = null;
        }
        Set<Integer> live = new HashSet<>();
        for (HashMap<Long, SiteIndex.Cube> m : idx.cubes) for (SiteIndex.Cube c : m.values()) live.add(c.site);
        idx.sites.values().removeIf(s -> s.aliasOf == null && !live.contains(s.id));
        // what is left is the build now: no drop to read as a decline, and no forgotten player's materials in print
        Map<Integer, Integer> left = new HashMap<>();
        for (HashMap<Long, SiteIndex.Cube> m : idx.cubes) for (SiteIndex.Cube c : m.values()) if (touched.contains(c.site)) left.merge(c.site, c.n(), Integer::sum);
        for (int id : touched) {
            SiteIndex.Site s = idx.sites.get(id);
            if (s == null || s.aliasOf != null) continue;
            s.net = s.peakNet = left.getOrDefault(id, 0);
            s.lowFolds = 0;
            s.mats.clear();
        }
        if (prefs.register != null) prefs.register.removeIf(r -> r.builders.contains(p));   // off the Town Survey page too
        Prefs.P pp = prefs.get(p);
        pp.level = "private";
        pp.hadSite = false;
        pp.featured.clear();
        pp.critic = null;
    }

    private static Kinds.M metrics(SiteIndex.Site s, HashMap<Long, SiteIndex.Cube> cubes, List<Long> keys, Cfg cfg, Long seed) {
        int n = 0, built = 0, minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        Set<Long> cols = new HashSet<>();
        int slimeCubes = 0, liveCubes = 0;
        for (long k : keys) {
            SiteIndex.Cube c = cubes.get(k);
            if (c == null || c.n() == 0) continue;
            liveCubes++;
            if (seed != null && Kinds.slimeChunk(seed, SiteIndex.sx(k) >> 2, SiteIndex.sz(k) >> 2)) slimeCubes++;
            n += c.n();
            built += c.b;
            int x = SiteIndex.sx(k), y = SiteIndex.sy(k), z = SiteIndex.sz(k);
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            cols.add(((long) x << 32) | (z & 0xFFFFFFFFL));
        }
        int dx = n == 0 ? 0 : (maxX - minX + 1) * 4, dz = n == 0 ? 0 : (maxZ - minZ + 1) * 4;
        int h = s.yMax >= s.yMin ? s.yMax - s.yMin + 1 : n == 0 ? 0 : (maxY - minY + 1) * 4;
        int ut = 0, home = 0, dr = 0, bed = 0, ss = 0, fn = 0, crop = 0, rs = 0, mc = 0, hp = 0, gl = 0, rail = 0, sk = 0, plants = 0;
        for (Map.Entry<String, Integer> e : s.mats.entrySet()) {
            int v = Math.max(0, e.getValue()), f = cfg.info(e.getKey());
            if (v == 0) continue;
            if ((f & KeyRules.UTILITY) != 0) ut += v;
            if ((f & KeyRules.HOME) != 0) home += v;
            if ((f & KeyRules.DOOR) != 0) dr += v;
            if ((f & KeyRules.BED) != 0) bed += v;
            if ((f & KeyRules.STAIRSLAB) != 0) ss += v;
            if ((f & KeyRules.FENCE) != 0) fn += v;
            if ((f & KeyRules.CROP) != 0) crop += v;
            if ((f & KeyRules.REDSTONE) != 0) rs += v;
            if ((f & KeyRules.MACHINE) != 0) mc += v;
            if ((f & KeyRules.HOPPER) != 0) hp += v;
            if ((f & KeyRules.GLASS) != 0) gl += v;
            if ((f & KeyRules.RAIL) != 0) rail += v;
            if ((f & KeyRules.STICKY) != 0) sk += v;
            if (KeyRules.cls(f) == KeyRules.PLANT && (f & KeyRules.CROP) == 0) plants += v;
        }
        if (mc < 4) home += bed;   // villager beds in an iron farm don't make it a house
        int placements = 0;
        for (SiteIndex.Who w : s.players.values()) placements += Math.max(0, w.placed);
        return new Kinds.M(n, built, h, Math.max(dx, dz), Math.min(dx, dz), cols.size() * 16, Families.count(s.mats, n, cfg.keyInfo()), ut, home, dr,
                bed, ss, fn, crop, rs, mc, hp, gl, rail, sk, s.air, s.wb, placements, plants, s.clamped, liveCubes == 0 ? 0 : slimeCubes * 100 / liveCubes);
    }

    private static void mergeInto(SiteIndex.Site t, SiteIndex.Site o) {
        if (t == null || o == null) return;
        for (Map.Entry<String, SiteIndex.Who> e : o.players.entrySet()) {
            SiteIndex.Who w = t.players.computeIfAbsent(e.getKey(), k -> new SiteIndex.Who());
            w.name = e.getValue().name; w.placed += e.getValue().placed; w.real += e.getValue().real; w.broken += e.getValue().broken; w.net += e.getValue().net;
        }
        for (String c : o.crew) if (!t.crew.contains(c)) t.crew.add(c);
        for (Map.Entry<String, Integer> e : o.mats.entrySet()) t.mats.merge(e.getKey(), e.getValue(), Integer::sum);
        t.air += o.air; t.wb += o.wb; t.net += o.net; t.clamped += o.clamped;
        t.mobsDied = Production.merge(t.mobsDied, o.mobsDied);
        o.mobsDied = null;
        if (!o.retired) t.peakNet += o.peakNet;   // a torn-down build's old peak would make the live one look torn down
        if (o.lastGrowth != null && (t.lastGrowth == null || o.lastGrowth.compareTo(t.lastGrowth) > 0)) t.lastGrowth = o.lastGrowth;
        t.yMin = Math.min(t.yMin, o.yMin); t.yMax = Math.max(t.yMax, o.yMax);
        t.existing |= o.existing;
        t.harmed |= o.harmed;
        t.days = Math.max(t.days, o.days);
        t.history.addAll(o.history);
        t.history.sort((a, b) -> a.d.compareTo(b.d));
        while (t.history.size() > 60) t.history.remove(0);
        t.mentions.addAll(o.mentions);
        while (t.mentions.size() > 10) t.mentions.remove(0);
        for (int m : o.milestones) if (!t.milestones.contains(m)) t.milestones.add(m);
        for (String p : o.secretBy) if (!t.secretBy.contains(p)) t.secretBy.add(p);
        if (t.permit == null) t.permit = o.permit;
        if (t.name == null) { t.name = o.name; t.nameBy = o.nameBy; t.nameFrom = o.nameFrom; }
        o.aliasOf = t.id;
    }

    /** Takes up to n of p's own blocks out of a cube someone else filled first; returns how many were p's. */
    private static int theirs(SiteIndex.Cube cube, String p, int n) {
        if (cube.o == null || p == null) return 0;
        int have = cube.o.getOrDefault(p, 0), mine = Math.min(n, have);
        if (have > mine) cube.o.put(p, have - mine); else cube.o.remove(p);
        if (cube.o.isEmpty()) cube.o = null;
        return mine;
    }

    /** Nobody can have more blocks in a cube than are left in it. */
    private static void trim(SiteIndex.Cube cube) {
        if (cube.o == null) return;
        int max = cube.b + cube.l;
        cube.o.replaceAll((k, v) -> Math.min(v, max));
        cube.o.values().removeIf(v -> v <= 0);
        if (cube.o.isEmpty()) cube.o = null;
    }

    private static double untrackedShare(SiteIndex idx, Ag a) {
        if (a.refs().length == 0) return 0;
        int un = 0;
        for (long r : a.refs()) { SiteIndex.Cube c = cube(idx, r); if (c == null || c.n() == 0) un++; }
        return a.ag() * (double) un / a.refs().length;
    }

    static SiteIndex.Cube cube(SiteIndex idx, long gk) {
        int w = (int) (gk >>> 58);
        return w < idx.cubes.size() ? idx.cubes.get(w).get(gk & MASK58) : null;
    }

    static int live(SiteIndex idx, int id) {
        SiteIndex.Site s = idx.sites.get(id);
        for (int guard = 0; s != null && s.aliasOf != null && guard < 100; guard++) { id = s.aliasOf; s = idx.sites.get(id); }
        return id;
    }

    private static long find(HashMap<Long, Long> parent, long x) {
        long r = x;
        while (parent.get(r) != r) r = parent.get(r);
        while (parent.get(x) != r) { long nx = parent.get(x); parent.put(x, r); x = nx; }
        return r;
    }

    private static void union(HashMap<Long, Long> parent, long a, long b) {
        long ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent.put(ra, rb);
    }

    private static void add(Map<String, Integer> into, Map<String, Integer> from) {
        if (from != null) for (Map.Entry<String, Integer> e : from.entrySet()) into.merge(e.getKey(), e.getValue(), Integer::sum);
    }

    static int sum(Map<String, Integer> m) {
        int s = 0;
        if (m != null) for (int v : m.values()) s += v;
        return s;
    }

    static int round10(int n) { return Math.max(10, Math.round(n / 10f) * 10); }
}
