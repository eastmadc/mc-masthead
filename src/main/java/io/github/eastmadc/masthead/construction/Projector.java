package io.github.eastmadc.masthead.construction;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the fold into what may leave the plugin: out/DATE.json (no positions: kinds, size words, materials, counts and
 * builder names), alerts/DATE.json (owner only) and each player's /news build lines. Decides who may be printed and which
 * builds make the paper, so the writer can't slip. Every printed kind and feature list passes construction.detail here
 * (Kinds.shown, Features.shown); the saved kinds and the builder's own /news build lines keep the full kind.
 */
final class Projector {
    private Projector() {}

    private static final Map<String, Integer> PRIORITY = Map.of("new_resident", 100, "ribbon", 95, "incident", 80, "permit", 70, "rebuild", 70,
            "public_works", 65, "progress", 60, "demolition", 55, "renovation", 30);
    private static final Set<String> REPEATABLE = Set.of("ribbon", "incident", "progress", "demolition", "rebuild");
    private static final List<String> ANGLES = List.of("materials", "crew", "size", "day", "parts");

    record Result(JsonObject out, JsonObject alerts, Map<String, List<String>> status) {}

    private record Cand(SiteIndex.Site s, Fold.Out o, String tag, int score, Listing l) {
        Cand(SiteIndex.Site s, Fold.Out o, String tag, int score) { this(s, o, tag, score, null); }
        List<String> crew() { return l != null ? l.crew : s.crew; }
    }

    /** Opening day: how many full stories Planning & Zoning carries (max-entries is for the daily papers). */
    static final int OPENING_STORIES = 5;
    static final int MAX_SURVEY_ROWS = 300;

    /**
     * One row of the opening day's register: a survey build (b set; sites = the tracked builds with most of their cubes in its chunks) or a
     * tracked build on its own (b null; sites = just it).
     */
    private static final class Listing {
        Survey.Build b;
        final List<Integer> sites = new ArrayList<>();
        final List<String> crew = new ArrayList<>();
        String kind, name;                           // name: the survey's build name, checked (null: none)
        List<Features.F> features;
        int total, permit;
    }

    /**
     * commit = remember what was printed (permit numbers, mentions, first homes). False for a practice run (print: false):
     * everything is worked out and written to out/, but the next real paper still sees every build as new.
     */
    static Result project(SiteIndex idx, Prefs prefs, Fold.Result fr, Fold.Cfg cfg, boolean weekly, boolean commit) {
        return project(idx, prefs, fr, cfg, weekly, commit, List.of(), Map.of(), new ArrayList<>());
    }

    /**
     * survey = survey.json's builds (used only on the opening day: prefs.opened still null); dims = world UUID -> overworld,
     * nether or end; notes = the owner's survey lines so far (rows dropped), sent in the alerts file.
     */
    static Result project(SiteIndex idx, Prefs prefs, Fold.Result fr, Fold.Cfg cfg, boolean weekly, boolean commit,
                          List<Survey.Build> survey, Map<String, String> dims, List<String> notes) {
        LocalDate date = cfg.date();
        String ds = date.toString(), recent = date.minusDays(3).toString(), graceEnd = date.minusDays(1).toString();
        var c = cfg.c();
        // who may be printed (§4.1)
        Map<Integer, Boolean> printable = new HashMap<>();
        for (Map.Entry<Integer, Fold.Out> e : fr.out.entrySet()) {
            SiteIndex.Site s = idx.sites.get(e.getKey());
            Fold.Out o = e.getValue();
            printable.put(e.getKey(), printable(s, o, prefs, c, graceEnd));
            // a milestone counts only the blocks the paper may print (a private helper's blocks never cross one)
            // (kept open, so it is printed once the printable blocks really pass it)
            if (s != null && o.milestoneToday > 0 && blocksTotal(s, o, prefs, cfg) < o.milestoneToday) {
                s.milestones.remove(Integer.valueOf(o.milestoneToday));
                o.milestoneToday = 0;
            }
        }

        // candidates (§4.2)
        List<Cand> cands = new ArrayList<>();
        for (Map.Entry<Integer, Fold.Out> e : fr.out.entrySet()) {
            if (!printable.get(e.getKey())) continue;
            SiteIndex.Site s = idx.sites.get(e.getKey());
            Fold.Out o = e.getValue();
            boolean seen = false;
            for (SiteIndex.Mention m : s.mentions) if (m.d.compareTo(recent) >= 0 && m.d.compareTo(ds) < 0) seen = true;
            String tag = tag(s, o, prefs, c.minBlocks(), seen);
            if (tag == null || s.crew.size() > 6) continue;   // the facts check drops more than 6 builders (as the survey does)
            int score = PRIORITY.get(tag);
            for (String p : s.crew) {
                Prefs.P pp = prefs.players.get(p);
                if (pp != null && pp.featured.stream().anyMatch(d -> d.compareTo(recent) >= 0 && d.compareTo(ds) < 0)) { score -= 30; break; }
            }
            cands.add(new Cand(s, o, tag, score));
        }
        cands.sort(Comparator.comparingInt((Cand x) -> -x.score).thenComparingInt(x -> x.s.permit == null ? Integer.MAX_VALUE : x.s.permit)
                .thenComparingInt(x -> x.s.id));
        boolean opening = prefs.opened == null;
        List<Listing> listings = opening ? listings(idx, prefs, fr, cfg, printable, survey, dims, notes) : List.of();
        List<Cand> picked = new ArrayList<>();
        Set<String> named = new HashSet<>();
        if (!opening) for (Cand x : cands) {
            if (picked.size() >= c.maxEntries()) break;
            if (x.s.crew.stream().anyMatch(named::contains)) continue;
            named.addAll(x.s.crew);
            picked.add(x);
        }
        else {
            // the opening: the most interesting builds on the whole register, different kinds first, one story per crew
            Set<Integer> merged = new HashSet<>();
            for (Listing l : listings) if (l.b != null) merged.addAll(l.sites);
            List<Cand> pool = new ArrayList<>();
            for (Listing l : listings) if (l.b != null) pool.add(new Cand(null, null, "survey", interest(l), l));
            for (Cand x : cands) if (!merged.contains(x.s.id)) pool.add(new Cand(x.s, x.o, x.tag, interest(listing(x.s, x.o, prefs, cfg))));
            pool.sort(Comparator.comparingInt((Cand x) -> -x.score));   // stable: survey file order, then the daily order
            Set<String> kinds = new HashSet<>();
            for (int pass = 0; pass < 2; pass++) for (Cand x : pool) {
                String kind = x.l != null ? x.l.kind : x.s.kind;
                if (picked.size() >= OPENING_STORIES || picked.contains(x) || x.crew().stream().anyMatch(named::contains) || (pass == 0 && kinds.contains(kind))) continue;
                named.addAll(x.crew());
                kinds.add(kind);
                picked.add(x);
            }
        }

        // permit numbers only for builds that make the paper; on the opening day, for every build on the register
        int nextPermit = prefs.nextPermit;
        Map<Integer, Integer> permits = new HashMap<>();
        for (SiteIndex.Site s : idx.sites.values()) if (s.permit != null) permits.put(s.id, s.permit);
        for (Listing l : listings) {
            Integer p = l.b == null ? null : prefs.surveyPermits.get(l.b.id());   // a re-run never renumbers
            for (int id : l.sites) if (permits.containsKey(id) && (p == null || permits.get(id) < p)) p = permits.get(id);
            if (p == null) p = nextPermit++;
            l.permit = p;
            for (int id : l.sites) permits.putIfAbsent(id, p);
        }
        for (Cand x : picked) if (x.s != null && !permits.containsKey(x.s.id)) permits.put(x.s.id, nextPermit++);

        JsonArray entries = new JsonArray();
        JsonArray awards = new JsonArray();
        for (Cand x : picked) {
            if (x.l != null) {
                entries.add(surveyEntry(x.l, fr, cfg));
                if (commit) for (String p : x.l.crew) featured(prefs, p, ds);
                continue;
            }
            SiteIndex.Site s = x.s;
            Fold.Out o = x.o;
            JsonObject e = new JsonObject();
            e.addProperty("tag", x.tag);
            e.addProperty("permit", permits.get(s.id));
            e.addProperty("name", printName(s, ds));
            e.add("builders", names(s.crew, fr.names));
            e.add("new_builders", names(o.newCrew, fr.names));
            e.addProperty("kind", Kinds.shown(s.kind, c.detail()));
            e.addProperty("confidence", o.confidence);
            if (!s.existing) e.addProperty("megaproject", o.m.n() >= 2000 && s.days >= 3);
            e.addProperty("day", Math.max(1, s.days));
            int total = blocksTotal(s, o, prefs, cfg);
            e.addProperty("blocks_today", Math.min(blocksToday(s, o), total));   // golems and blasts take blocks with no break
            e.addProperty("blocks_total", total);
            if (!s.existing && s.days > 1) e.addProperty("size", Kinds.size(o.m.n()));
            if (!s.existing && !Kinds.FLAT.contains(s.kind)) e.addProperty("height", Kinds.height(o.m.h()));
            Families.Pick mp = Families.pick(s.mats, o.m.n(), cfg.keyInfo());
            e.add("materials", mats(mp.materials()));
            if (mp.odd() != null) e.add("odd_material", mat(mp.odd()));
            JsonObject parts = parts(s, cfg);
            if (parts.size() > 0 && (x.tag.equals("public_works") || Kinds.WORKS.contains(s.kind))) e.add("parts", parts);
            // what is inside, with real counts; renovation and demolition notices carry no detail
            JsonArray features = Features.json(Features.shown(Features.of(s.mats, Map.of(), Map.of()), c.detail()));
            if (features.size() > 0 && !x.tag.equals("renovation") && !x.tag.equals("demolition")) e.add("features", features);
            if (x.tag.equals("progress")) e.addProperty("milestone_blocks", o.milestoneToday);
            if (x.tag.equals("incident")) {
                JsonObject inc = new JsonObject();
                inc.addProperty("cause", o.incidentCause);
                inc.addProperty("blocks", o.incidentBlocks);
                inc.addProperty("self", o.incidentSelf);
                e.add("incident", inc);
            }
            if (s.existing) e.addProperty("existing", true);
            e.addProperty("angle", angle(s, e, x.tag));
            e.addProperty("times_in_paper", s.mentions.size());
            entries.add(e);
            awardReasons(awards, s, o, x.tag, fr.names, c.detail());
            if (commit) {
                s.permit = permits.get(s.id);
                SiteIndex.Mention m = new SiteIndex.Mention();
                m.d = ds; m.tag = x.tag; m.angle = e.get("angle").getAsString();
                s.mentions.add(m);
                while (s.mentions.size() > 10) s.mentions.remove(0);
                if (x.tag.equals("ribbon")) s.doneReported = true;
                if (x.tag.equals("rebuild")) s.rebuild = false;
                if (x.tag.equals("demolition")) s.demolitionReported = true;
                for (String p : s.crew) featured(prefs, p, ds);
            }
        }
        if (commit) {
            prefs.nextPermit = nextPermit;
            // the register: every listed build keeps its number and is never "new permit" news again
            for (Listing l : listings) {
                for (int id : l.sites) idx.sites.get(id).permit = permits.get(id);
                if (l.b != null) prefs.surveyPermits.put(l.b.id(), l.permit);
            }
        }

        JsonObject cons = new JsonObject();
        cons.addProperty("opening", opening);
        List<Prefs.Row> register = opening ? register(listings) : List.of();
        if (opening && commit) { prefs.opened = ds; prefs.register = register; }
        cons.add("entries", entries);
        if (!listings.isEmpty()) cons.add("survey", surveyRows(register, fr.names, c.surveyNames(), c.detail()));
        if (weekly) {
            JsonArray tracker = tracker(idx, fr, prefs, cfg, printable, permits, commit);
            if (tracker.size() > 0) cons.add("tracker", tracker);
        }
        JsonObject out = new JsonObject();
        out.addProperty("version", 1);
        out.add("construction", cons);
        JsonArray blot = new JsonArray();
        for (Fold.Blot b : fr.blotter) {
            JsonObject o = new JsonObject();
            o.addProperty("what", "broke_build");
            o.addProperty("by", b.by());
            o.addProperty("to", b.to());
            o.addProperty("n", b.n());
            o.addProperty("x", b.x());
            blot.add(o);
        }
        out.add("blotter", blot);
        JsonArray drop = new JsonArray();
        for (List<String> d : fr.dropMinedAt) { JsonArray a = new JsonArray(); d.forEach(a::add); drop.add(a); }
        out.add("drop_mined_at", drop);
        out.add("award_reasons", awards);
        JsonObject ops = new JsonObject();
        ops.addProperty("sites", idx.sites.size());
        ops.addProperty("lines", fr.lines);
        int seeded = 0;   // diagnostics: did the slime chunk test run, and how much of each build is in slime chunks
        JsonObject slime = new JsonObject();
        for (Map.Entry<Integer, Fold.Out> e : fr.out.entrySet()) {
            if (e.getValue().seeded) seeded++;
            if (e.getValue().m != null && e.getValue().m.slimePct() > 0) slime.addProperty(String.valueOf(e.getKey()), e.getValue().m.slimePct());
        }
        ops.addProperty("slime_seeded", seeded);
        ops.add("slime_pct", slime);
        out.add("ops", ops);

        JsonObject alerts = new JsonObject();
        List<Fold.Alert> al = new ArrayList<>(fr.alerts);
        al.sort(Comparator.comparingInt(a -> a.kind() == 2 ? 1 : 0));   // not-helping first
        JsonArray aa = new JsonArray();
        al.stream().limit(3).forEach(a -> aa.add(a.text()));
        JsonArray nl = new JsonArray();
        List<String> lines = new ArrayList<>(fr.newNames);
        lines.addAll(fr.clearedNames);
        lines.stream().limit(5).forEach(nl::add);
        if (lines.size() > 5) nl.add("and " + (lines.size() - 5) + " more cleared names");
        if (aa.size() > 0) alerts.add("alerts", aa);
        if (nl.size() > 0) alerts.add("names", nl);
        if (!notes.isEmpty()) {
            JsonArray sn = new JsonArray();
            notes.stream().limit(10).forEach(sn::add);
            if (notes.size() > 10) sn.add("survey: and " + (notes.size() - 10) + " more rows dropped");
            alerts.add("survey", sn);
        }
        return new Result(out, alerts, status(idx, fr, ds));
    }

    /** Whether the paper may print this build at all (§4.1). graceEnd = the day before the paper's date. */
    static boolean printable(SiteIndex.Site s, Fold.Out o, Prefs prefs, io.github.eastmadc.masthead.config.Settings.Construction c, String graceEnd) {
        boolean ok = s != null && !s.crew.isEmpty() && s.secretBy.isEmpty() && o.suppressed == null && !o.leftOut && (!s.retired || o.demolitionNow);
        if (ok) for (String p : s.crew) if (!nameable(p, prefs, c, graceEnd)) ok = false;
        return ok;
    }

    /** A player the paper may name: had the notice before graceEnd and is not private. */
    static boolean nameable(String p, Prefs prefs, io.github.eastmadc.masthead.config.Settings.Construction c, String graceEnd) {
        Prefs.P pp = prefs.players.get(p);
        return pp != null && pp.noticed != null && pp.noticed.compareTo(graceEnd) < 0 && !"private".equals(prefs.level(p, c));
    }

    private static void featured(Prefs prefs, String p, String ds) {
        Prefs.P pp = prefs.get(p);
        pp.hadSite = true;
        pp.featured.add(ds);
        while (pp.featured.size() > 10) pp.featured.remove(0);
    }

    /**
     * The opening day's register: every valid survey build (with the tracked builds mostly inside its chunks, whose crews join
     * it), then every printable tracked build not inside one, by build id. A survey build is dropped, with an owner note,
     * when a builder may not be named, when any part of a tracked build that may not be printed is inside it (secret, private crew, a hidden builder; retired too),
     * or when a secret typed where nothing is tracked yet is inside it.
     */
    private static List<Listing> listings(SiteIndex idx, Prefs prefs, Fold.Result fr, Fold.Cfg cfg, Map<Integer, Boolean> printable,
                                          List<Survey.Build> survey, Map<String, String> dims, List<String> notes) {
        String graceEnd = cfg.date().minusDays(1).toString();
        // which tracked builds lie inside each survey build (same dimension, chunk and height)
        Map<String, Map<Long, List<Integer>>> byChunk = new HashMap<>();
        for (int i = 0; i < survey.size(); i++)
            for (long ch : survey.get(i).chunks()) byChunk.computeIfAbsent(survey.get(i).dim(), k -> new HashMap<>()).computeIfAbsent(ch, k -> new ArrayList<>()).add(i);
        // a tracked build joins the survey build that holds most of its cubes, and only if that is more than half of them
        Map<Integer, Integer> cubes = new HashMap<>();
        Map<Integer, Map<Integer, Integer>> in = new HashMap<>();   // site -> survey index -> its cubes there
        for (Map.Entry<String, Integer> w : idx.worlds.entrySet()) {
            if (w.getValue() >= idx.cubes.size()) continue;
            Map<Long, List<Integer>> m = byChunk.getOrDefault(dims.get(w.getKey()), Map.of());
            for (Map.Entry<Long, SiteIndex.Cube> e : idx.cubes.get(w.getValue()).entrySet()) {
                if (e.getValue().site <= 0) continue;
                long k = e.getKey();
                int site = Fold.live(idx, e.getValue().site), y = SiteIndex.sy(k) * 4;
                cubes.merge(site, 1, Integer::sum);
                for (int i : m.getOrDefault(Survey.chunk(SiteIndex.sx(k) >> 2, SiteIndex.sz(k) >> 2), List.of()))
                    if (y + 3 >= survey.get(i).yMin() && y <= survey.get(i).yMax()) in.computeIfAbsent(site, x -> new HashMap<>()).merge(i, 1, Integer::sum);
            }
        }
        List<Set<Integer>> inside = new ArrayList<>();
        for (int i = 0; i < survey.size(); i++) inside.add(new java.util.TreeSet<>());
        for (Map.Entry<Integer, Map<Integer, Integer>> e : in.entrySet()) {
            Map.Entry<Integer, Integer> best = null;
            for (Map.Entry<Integer, Integer> x : e.getValue().entrySet())
                if (best == null || x.getValue() > best.getValue() || (x.getValue().equals(best.getValue()) && x.getKey() < best.getKey())) best = x;
            if (best != null && best.getValue() * 2 > cubes.get(e.getKey())) inside.get(best.getKey()).add(e.getKey());
        }
        Map<Integer, String> dimOf = new HashMap<>();   // world index -> dimension, for held secrets
        for (Map.Entry<String, Integer> w : idx.worlds.entrySet()) dimOf.put(w.getValue(), dims.get(w.getKey()));
        List<Listing> out = new ArrayList<>();
        Set<Integer> taken = new HashSet<>();
        for (int i = 0; i < survey.size(); i++) {
            Survey.Build b = survey.get(i);
            Listing l = new Listing();
            String why = null;
            for (String p : b.builders()) {
                String n = fr.names.get(p);
                if (n == null || !nameable(p, prefs, cfg.c(), graceEnd) || cfg.hidden().contains(n.toLowerCase(java.util.Locale.ROOT))) why = "a builder the paper may not name";
                else if (!l.crew.contains(p)) l.crew.add(p);
            }
            for (Map.Entry<Integer, Map<Integer, Integer>> e : in.entrySet()) {   // any cube inside vetoes, a retired build's too
                SiteIndex.Site s = idx.sites.get(e.getKey());
                if (s != null && e.getValue().containsKey(i) && vetoes(s, fr.out.get(s.id), prefs, fr, cfg, graceEnd)) why = "holds a tracked build the paper may not print";
            }
            if (heldSecretIn(idx, b, dimOf)) why = "holds a secret build";
            for (int id : inside.get(i)) {
                SiteIndex.Site s = idx.sites.get(id);
                if (s == null || s.retired || taken.contains(id)) continue;
                l.sites.add(id);
                for (String p : s.crew) if (!l.crew.contains(p)) l.crew.add(p);
            }
            if (why == null && l.crew.size() > 6) why = "more than 6 builders";
            if (why != null) { notes.add("survey: " + b.id() + " dropped (" + why + ")"); continue; }
            taken.addAll(l.sites);
            l.b = b;
            l.kind = Kinds.shown(b.kind(), cfg.c().detail());
            l.features = Features.shown(Features.of(b.blocks(), b.villagers(), b.spawners()), cfg.c().detail());
            l.total = b.total();
            if (b.name() != null && cfg.c().surveyNames()) {
                Set<String> known = new HashSet<>(fr.names.values());
                known.addAll(cfg.hidden());   // the same rule as a player's /news build name
                l.name = NameRules.check(b.name(), known, "clean");
                if (l.name == null) notes.add("survey: " + b.id() + " name not used");
            }
            out.add(l);
        }
        for (Map.Entry<Integer, Fold.Out> e : fr.out.entrySet()) {
            SiteIndex.Site s = idx.sites.get(e.getKey());
            if (!printable.get(e.getKey()) || s.retired || taken.contains(s.id)) continue;
            out.add(listing(s, e.getValue(), prefs, cfg));
        }
        return out;
    }

    /** A secret held where nothing is tracked yet (it binds to the build at or next to its cube) inside survey build b. */
    private static boolean heldSecretIn(SiteIndex idx, Survey.Build b, Map<Integer, String> dimOf) {
        for (SiteIndex.Pending x : idx.pending) {
            int px = SiteIndex.sx(x.c), py = SiteIndex.sy(x.c) * 4, pz = SiteIndex.sz(x.c);
            if (!b.dim().equals(dimOf.get(x.w)) || py + 7 < b.yMin() || py - 4 > b.yMax()) continue;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                if (b.chunks().contains(Survey.chunk((px + dx) >> 2, (pz + dz) >> 2))) return true;
        }
        return false;
    }

    /**
     * After the opening: a survey build's register row goes for good once a secret lies inside it (a held secret, or any cube
     * of a secret tracked build), the same overlap rule as the opening. Rows are found through prefs.surveyPermits.
     */
    static void dropSecretSurveys(SiteIndex idx, Prefs prefs, List<Survey.Build> survey, Map<String, String> dims) {
        if (prefs.register == null || prefs.register.isEmpty()) return;
        Map<Integer, String> dimOf = new HashMap<>();
        for (Map.Entry<String, Integer> w : idx.worlds.entrySet()) dimOf.put(w.getValue(), dims.get(w.getKey()));
        List<long[]> secret = new ArrayList<>();   // {world index, cube key} of every secret tracked build's cubes
        for (int w = 0; w < idx.cubes.size(); w++)
            for (Map.Entry<Long, SiteIndex.Cube> e : idx.cubes.get(w).entrySet()) {
                SiteIndex.Site s = e.getValue().site > 0 ? idx.sites.get(Fold.live(idx, e.getValue().site)) : null;
                if (s != null && !s.secretBy.isEmpty()) secret.add(new long[] {w, e.getKey()});
            }
        for (Survey.Build b : survey) {
            Integer permit = prefs.surveyPermits.get(b.id());
            if (permit == null) continue;
            boolean in = heldSecretIn(idx, b, dimOf);
            for (long[] k : secret) {
                int y = SiteIndex.sy(k[1]) * 4;
                if (b.dim().equals(dimOf.get((int) k[0])) && y + 3 >= b.yMin() && y <= b.yMax()
                        && b.chunks().contains(Survey.chunk(SiteIndex.sx(k[1]) >> 2, SiteIndex.sz(k[1]) >> 2))) in = true;
            }
            if (in) prefs.register.removeIf(x -> x.permit == permit);
        }
    }

    /**
     * Whether a tracked build stops a survey build that overlaps it from printing: secret, held out today (grief alert), an
     * unnameable crew member, or any hidden player who built on it (the crew list already leaves hidden players out).
     */
    private static boolean vetoes(SiteIndex.Site s, Fold.Out o, Prefs prefs, Fold.Result fr, Fold.Cfg cfg, String graceEnd) {
        if (!s.secretBy.isEmpty() || o != null && o.leftOut) return true;
        for (String p : s.crew) if (!nameable(p, prefs, cfg.c(), graceEnd)) return true;
        for (Map.Entry<String, SiteIndex.Who> w : s.players.entrySet()) {
            String n = fr.names.getOrDefault(w.getKey(), w.getValue().name);
            if (n != null && cfg.hidden().contains(n.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    private static Listing listing(SiteIndex.Site s, Fold.Out o, Prefs prefs, Fold.Cfg cfg) {
        Listing l = new Listing();
        l.sites.add(s.id);
        l.crew.addAll(s.crew);
        l.kind = Kinds.shown(s.kind, cfg.c().detail());
        l.features = Features.shown(Features.of(s.mats, Map.of(), Map.of()), cfg.c().detail());
        l.total = blocksTotal(s, o, prefs, cfg);
        return l;
    }

    /** How interesting a build is for the opening stories: size (capped), what is inside, and a specific kind. */
    private static int interest(Listing l) {
        return Math.min(l.total, 10_000) / 500 + 3 * Math.min(l.features.size(), Features.MAX) + (Kinds.PARENT.containsKey(l.kind) ? 10 : 0);
    }

    /** An opening story about a survey build: it stood before the Commission opened, and blocks_total is the whole build. */
    private static JsonObject surveyEntry(Listing l, Fold.Result fr, Fold.Cfg cfg) {
        JsonObject e = new JsonObject();
        e.addProperty("tag", "survey");
        e.addProperty("permit", l.permit);
        if (l.name != null) e.addProperty("name", l.name);
        e.add("builders", names(l.crew, fr.names));
        e.addProperty("kind", l.kind);
        e.addProperty("confidence", "high");   // the owner confirmed it
        e.addProperty("blocks_total", l.total);
        Families.Pick mp = Families.pick(l.b.blocks(), l.total, cfg.keyInfo());
        if (!mp.materials().isEmpty()) e.add("materials", mats(mp.materials()));
        JsonArray features = Features.json(l.features);
        if (features.size() > 0) e.add("features", features);
        return e;
    }

    /** The register by permit number, builders by UUID (kept in players.json on the opening day). */
    private static List<Prefs.Row> register(List<Listing> listings) {
        List<Listing> l = new ArrayList<>(listings);
        l.sort(Comparator.comparingInt(x -> x.permit));
        List<Prefs.Row> out = new ArrayList<>();
        for (Listing x : l.subList(0, Math.min(MAX_SURVEY_ROWS, l.size()))) {
            Prefs.Row r = new Prefs.Row();
            r.permit = x.permit;
            r.builders.addAll(x.crew);
            r.kind = x.kind;
            r.features.addAll(x.features);
            r.blocksTotal = x.total;
            r.existing = x.b != null;
            r.name = x.name;
            out.add(r);
        }
        return out;
    }

    /**
     * The Town Survey page's rows: no positions; a build name only from the owner's survey, and only when withNames
     * (construction.survey-names). names = UUID -> a name the paper may print; a row with a builder not in it is left out.
     * detail = construction.detail today: rows keep the opening's detail, so a lower setting since then is applied here.
     */
    static JsonArray surveyRows(List<Prefs.Row> rows, Map<String, String> names, boolean withNames, String detail) {
        JsonArray a = new JsonArray();
        for (Prefs.Row x : rows) {
            if (!names.keySet().containsAll(x.builders)) continue;
            JsonObject r = new JsonObject();
            r.addProperty("permit", x.permit);
            if (withNames && x.name != null) r.addProperty("name", x.name);
            r.add("builders", names(x.builders, names));
            r.addProperty("kind", Kinds.shown(x.kind, detail));
            JsonArray f = Features.json(Features.shown(x.features, detail));
            if (f.size() > 0) r.add("features", f);
            r.addProperty("blocks_total", x.blocksTotal);
            if (x.existing) r.addProperty("existing", true);
            a.add(r);
        }
        return a;
    }

    /** The entry tag for a printable build, or null if it has no news today. */
    static String tag(SiteIndex.Site s, Fold.Out o, Prefs prefs, int minBlocks) { return tag(s, o, prefs, minBlocks, false); }

    /** seen = the build was in a recent paper: only a tag that may repeat counts, so a farm's milestone still prints. */
    static String tag(SiteIndex.Site s, Fold.Out o, Prefs prefs, int minBlocks, boolean seen) {
        String best = null;
        if (s.retired) return o.demolitionNow && s.permit != null ? "demolition" : null;
        boolean works = Kinds.WORKS.contains(s.kind);
        if (s.permit == null) {
            boolean firstHome = s.kind.equals("house") && !s.existing && o.m.hp() == 0 && o.m.bed() >= 1
                    && s.crew.stream().anyMatch(p -> prefs.players.get(p) == null || !prefs.players.get(p).hadSite);
            best = firstHome ? "new_resident" : works ? "public_works" : "permit";
        }
        List<String> more = new ArrayList<>();
        if (s.done && !s.doneReported && o.m.n() >= minBlocks) more.add("ribbon");
        if (o.incidentCause != null) more.add("incident");
        if (s.permit != null) {
            if (s.rebuild && o.m.n() >= minBlocks) more.add("rebuild");
            if (works && (o.created || o.partsToday >= 20)) more.add("public_works");
            if (o.milestoneToday > 0) more.add("progress");
            if ("renovated".equals(o.status)) more.add("renovation");
        }
        if (seen && best != null && !REPEATABLE.contains(best)) best = null;
        for (String t : more) if ((!seen || REPEATABLE.contains(t)) && (best == null || PRIORITY.get(t) > PRIORITY.get(best))) best = t;
        return best;
    }

    private static String printName(SiteIndex.Site s, String ds) {
        return s.name != null && s.nameFrom != null && s.nameFrom.compareTo(ds) < 0 ? s.name : null;
    }

    private static JsonArray names(List<String> uuids, Map<String, String> names) {
        List<String> l = new ArrayList<>();
        for (String p : uuids) if (names.containsKey(p)) l.add(names.get(p));
        l.sort(String.CASE_INSENSITIVE_ORDER);
        JsonArray a = new JsonArray();
        l.forEach(a::add);
        return a;
    }

    /** Crew and public, noticed helpers count; private helpers' blocks are left out. */
    private static boolean counts(SiteIndex.Site s, String p, Prefs prefs, Fold.Cfg cfg) {
        if (s.crew.contains(p)) return true;
        Prefs.P pp = prefs.players.get(p);
        return pp != null && pp.noticed != null && pp.noticed.compareTo(cfg.date().minusDays(1).toString()) < 0
                && "public".equals(prefs.level(p, cfg.c()));
    }

    /** Crew only, the players "builders" names: a helper's blocks next to that list would read as theirs. */
    private static int blocksToday(SiteIndex.Site s, Fold.Out o) {
        int t = 0;
        for (Map.Entry<String, Integer> e : o.netBy.entrySet()) if (s.crew.contains(e.getKey())) t += e.getValue();
        return Math.max(0, t);
    }

    private static int blocksTotal(SiteIndex.Site s, Fold.Out o, Prefs prefs, Fold.Cfg cfg) {
        int hidden = 0;
        for (Map.Entry<String, SiteIndex.Who> e : s.players.entrySet()) if (!counts(s, e.getKey(), prefs, cfg)) hidden += Math.max(0, e.getValue().net);
        return Math.max(0, o.m.n() - hidden);
    }

    private static JsonArray mats(List<Families.Mat> l) {
        JsonArray a = new JsonArray();
        for (Families.Mat m : l) a.add(mat(m));
        return a;
    }

    private static JsonObject mat(Families.Mat m) {
        JsonObject o = new JsonObject();
        o.addProperty("what", m.what());
        o.addProperty("blocks", m.blocks());
        return o;
    }

    /** Logic parts and crops (never hoppers or chests). */
    private static JsonObject parts(SiteIndex.Site s, Fold.Cfg cfg) {
        LinkedHashMap<String, Integer> p = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : s.mats.entrySet()) {
            int v = Math.max(0, e.getValue());
            if (v == 0) continue;
            String k = switch (e.getKey()) {
                case "piston", "sticky_piston" -> "pistons";
                case "observer" -> "observers";
                case "repeater" -> "repeaters";
                case "comparator" -> "comparators";
                case "redstone_wire" -> "redstone_dust";
                case "dispenser" -> "dispensers";
                default -> (cfg.info(e.getKey()) & KeyRules.CROP) != 0 ? "crops" : null;
            };
            if (k != null) p.merge(k, v, Integer::sum);
        }
        JsonObject o = new JsonObject();
        p.forEach(o::addProperty);
        return o;
    }

    /** The first angle whose field the entry carries and the last 3 mentions did not use; a milestone or incident wins. */
    private static String angle(SiteIndex.Site s, JsonObject e, String tag) {
        if (e.has("milestone_blocks")) return "milestone";
        if (e.has("incident")) return "incident";
        Set<String> used = new HashSet<>();
        for (int i = Math.max(0, s.mentions.size() - 3); i < s.mentions.size(); i++) used.add(s.mentions.get(i).angle);
        for (String a : ANGLES) {
            boolean has = switch (a) {
                case "materials" -> e.getAsJsonArray("materials").size() > 0;
                case "crew" -> e.getAsJsonArray("builders").size() > 1;
                case "size" -> e.has("size");
                case "day" -> true;
                default -> e.has("parts");
            };
            if (has && !used.contains(a)) return a;
        }
        return "day";
    }

    private static void awardReasons(JsonArray out, SiteIndex.Site s, Fold.Out o, String tag, Map<String, String> names, String detail) {
        String kind = "high".equals(o.confidence) ? Kinds.display(Kinds.shown(s.kind, detail)) : "project";   // a guessed kind is never named in an award
        switch (tag) {
            case "ribbon" -> {
                String who = s.doneBy != null && s.crew.contains(s.doneBy) ? s.doneBy : s.crew.get(0);
                reason(out, "golden_egg", names.get(who), 40, "finished their " + kind + " after " + Math.max(1, s.days) + " days of building");
            }
            case "progress" -> {
                if (o.milestoneToday >= 5000) for (String p : s.crew)
                    reason(out, "golden_egg", names.get(p), 30, (s.existing ? "added " : "passed ") + o.milestoneToday + " blocks" + (s.existing ? " to" : " on") + " one build");
            }
            case "new_resident" -> {
                if (s.kind.equals("house") && !s.existing && o.m.hp() == 0 && o.m.bed() >= 1) for (String p : s.crew)
                    reason(out, "golden_egg", names.get(p), 20, "built a first home");
            }
            case "incident" -> {
                if (o.incidentSelf && o.incidentBy != null && o.selfTnt >= 20)
                    reason(out, "headless_chicken", names.get(o.incidentBy), 35, "blew up part of their own " + kind + " with TNT");
            }
            default -> { }
        }
    }

    private static void reason(JsonArray out, String award, String name, int score, String text) {
        if (name == null) return;
        JsonObject r = new JsonObject();
        r.addProperty("award", award);
        r.addProperty("name", name);
        r.addProperty("score", score);
        r.addProperty("text", text);
        out.add(r);
    }

    /** Sundays: printable builds with a permit that had a building day this week, by permit number; one critic pick. */
    private static JsonArray tracker(SiteIndex idx, Fold.Result fr, Prefs prefs, Fold.Cfg cfg, Map<Integer, Boolean> printable,
                                     Map<Integer, Integer> permits, boolean commit) {
        String ds = cfg.date().toString(), weekAgo = cfg.date().minusDays(7).toString();
        List<SiteIndex.Site> rows = new ArrayList<>();
        for (Map.Entry<Integer, Fold.Out> e : fr.out.entrySet()) {
            SiteIndex.Site s = idx.sites.get(e.getKey());
            if (!printable.get(e.getKey()) || s.retired || !permits.containsKey(s.id)) continue;
            if (s.history.stream().anyMatch(h -> h.d.compareTo(weekAgo) > 0 && h.n >= 16)) rows.add(s);
        }
        rows.sort(Comparator.comparingInt(s -> permits.get(s.id)));
        if (rows.size() > 8) rows = rows.subList(0, 8);
        SiteIndex.Site critic = null;
        String criticWhen = null;
        for (SiteIndex.Site s : rows) {
            String last = "";
            for (String p : s.crew) { Prefs.P pp = prefs.players.get(p); String d = pp == null || pp.critic == null ? "" : pp.critic; if (d.compareTo(last) > 0) last = d; }
            if (critic == null || last.compareTo(criticWhen) < 0) { critic = s; criticWhen = last; }
        }
        JsonArray a = new JsonArray();
        for (SiteIndex.Site s : rows) {
            Fold.Out o = fr.out.get(s.id);
            JsonObject r = new JsonObject();
            r.addProperty("permit", permits.get(s.id));
            r.addProperty("name", printName(s, ds));
            r.add("builders", names(s.crew, fr.names));
            r.addProperty("kind", Kinds.shown(s.kind, cfg.c().detail()));
            r.addProperty("day", Math.max(1, s.days));
            r.addProperty("blocks_total", blocksTotal(s, o, prefs, cfg));
            if (s.existing) r.addProperty("existing", true);
            if (s == critic) {
                r.addProperty("critic", true);
                r.addProperty("confidence", o.confidence);
                Families.Pick mp = Families.pick(s.mats, o.m.n(), cfg.keyInfo());
                r.add("materials", mats(mp.materials()));
                if (mp.odd() != null) r.add("odd_material", mat(mp.odd()));
                if (!s.existing && s.days > 1) r.addProperty("size", Kinds.size(o.m.n()));
                if (!s.existing && !Kinds.FLAT.contains(s.kind)) r.addProperty("height", Kinds.height(o.m.h()));
                int week = 0;
                for (SiteIndex.Hist h : s.history) if (h.d.compareTo(weekAgo) > 0) week += Math.max(0, h.n);
                r.addProperty("blocks_week", Math.min(week, blocksTotal(s, o, prefs, cfg)));   // history counts private helpers too
                if (commit) for (String p : s.crew) prefs.get(p).critic = ds;
            }
            a.add(r);
        }
        return a;
    }

    /** /news build lines for each builder, in the plain-edition words (never a position). */
    private static Map<String, List<String>> status(SiteIndex idx, Fold.Result fr, String ds) {
        Map<String, List<String>> out = new HashMap<>();
        for (SiteIndex.Site s : idx.sites.values()) {
            if (s.aliasOf != null || s.retired || s.kind == null) continue;
            Fold.Out o = fr.out.get(s.id);
            int n = o == null ? s.net : o.m.n();
            StringBuilder b = new StringBuilder(s.permit == null ? "No permit yet" : "Permit #" + s.permit);
            if (s.name != null) b.append(" \"").append(s.name).append('"').append(s.nameFrom != null && s.nameFrom.compareTo(ds) >= 0 ? " (in the paper from the next one)" : "");
            b.append(": ").append(article(Kinds.display(s.kind))).append(", day ").append(Math.max(1, s.days)).append(", ").append(String.format(Locale.ROOT, "%,d", n)).append(" blocks");
            if (!s.secretBy.isEmpty()) b.append(", secret");
            b.append('.');
            if (s.done && s.doneBy != null) b.append(" Ribbon cutting filed by ").append(fr.names.getOrDefault(s.doneBy, "a builder")).append('.');
            for (String p : s.crew) {
                List<String> l = out.computeIfAbsent(p, k -> new ArrayList<>());
                if (l.size() < 5) l.add(b.toString());
            }
        }
        Map<String, List<String>> frozen = new HashMap<>();
        out.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return Map.copyOf(frozen);
    }

    static String article(String noun) {
        return ("aeiou".indexOf(noun.charAt(0)) >= 0 ? "an " : "a ") + noun;
    }
}
