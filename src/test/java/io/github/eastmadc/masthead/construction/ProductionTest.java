package io.github.eastmadc.masthead.construction;

import com.google.gson.JsonObject;
import io.github.eastmadc.masthead.config.Settings;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static io.github.eastmadc.masthead.construction.ConstructionTest.ALEX;
import static io.github.eastmadc.masthead.construction.ConstructionTest.STEVE;
import static io.github.eastmadc.masthead.construction.ConstructionTest.X;
import static io.github.eastmadc.masthead.construction.ConstructionTest.Y;
import static io.github.eastmadc.masthead.construction.ConstructionTest.Z;

/**
 * Production at builds (mob deaths, 7 days, on the kinds card), plus the alpha.20 review fixes around the kinds desk: the
 * practice fold's hand-off, and same-day previews and re-runs reusing today's answers. Plain main().
 */
public final class ProductionTest {
    static int ok = 0;

    static void t(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
        ok++;
    }

    static final UUID W = ConstructionTest.Sc.WORLD;
    static final String[] ENT = {"zombie", "slime", "spider", "cow"};
    static final int SLIME = 1;

    public static void main(String[] args) throws Exception {
        watcher();
        foldBuckets();
        cardAndDue();
        farmRegression();
        handlerAndFlush();
        watchLifecycle();
        desk();
        System.out.println("ProductionTest: " + ok + " checks passed");
    }

    static int chunkOf(int block) { return Math.floorDiv(block, 16); }

    /** A house (chunk A) and a hut dx blocks east. */
    static ConstructionTest.Sc twoBuilds(int dx) {
        ConstructionTest.Sc s = new ConstructionTest.Sc().notice(STEVE, ALEX);
        ConstructionTest.house(s, STEVE, X, Y, Z);
        ConstructionTest.hut(s, ALEX, X + dx, Y, Z, "oak_planks");
        s.fold();
        return s;
    }

    static ConstructionTest.Sc twoBuilds() { return twoBuilds(32); }

    static SiteIndex.Site house(ConstructionTest.Sc s) {
        for (SiteIndex.Site x : s.idx.sites.values()) if (x.crew.contains(s.id(STEVE).toString())) return x;
        throw new AssertionError("no house");
    }

    static int other(ConstructionTest.Sc s, int a) { return s.idx.sites.firstKey() == a ? s.idx.sites.lastKey() : s.idx.sites.firstKey(); }

    /** Each build's deaths from one minute of counts, through the raw line and back. */
    static Map<Integer, Map<String, Integer>> minute(Production p) {
        String line = Production.line(p.swap(), p.names);
        Map<Integer, Map<String, Integer>> by = new HashMap<>();
        if (line.isEmpty()) return by;
        RawLine.Line l = RawLine.parse(line.strip());
        t(l != null && l.k() == 'f', "a production line: " + line);
        for (RawLine.Prod x : l.f()) by.put(x.site(), x.died());
        return by;
    }

    // ------------------------------------------------------------ the watch and the minute's counts (main thread side)
    static void watcher() {
        // two chunks apart: their rings share one chunk where no build stands, which credits both
        ConstructionTest.Sc s = twoBuilds();
        t(s.idx.sites.size() == 2, "two builds");
        int a = house(s).id, b = other(s, a);
        Map<UUID, Production.Watch> w = Production.watch(s.idx);
        Production p = new Production(ENT);
        int ca = chunkOf(X), cz = chunkOf(Z), cb = chunkOf(X + 32);
        t(cb == ca + 2, "the hut is two chunks east");
        p.count(w, W, ca, cz, SLIME);             // in the house's chunk
        p.count(w, W, ca - 1, cz + 1, SLIME);     // its ring, any corner
        p.count(w, W, ca + 1, cz, SLIME);         // the shared ring chunk: both builds
        for (int i = 0; i < 100; i++) {
            p.count(w, W, ca + 9, cz, SLIME);     // far away: nobody
            p.count(w, W, ca - 2, cz, SLIME);     // just outside the ring
            p.count(w, UUID.randomUUID(), ca, cz, SLIME);   // another world
        }
        String line = Production.line(new HashMap<>(Map.of()), p.names);
        t(line.isEmpty(), "nothing counted: no line");
        Map<Long, int[]> raw = p.swap();
        line = Production.line(raw, p.names);
        t(!line.contains(String.valueOf(ca)) && !line.contains(String.valueOf(X)) && !line.contains(W.toString()), "the line holds no place: " + line);
        RawLine.Line l = RawLine.parse(line.strip());
        Map<Integer, Map<String, Integer>> by = new HashMap<>();
        for (RawLine.Prod x : l.f()) by.put(x.site(), x.died());
        t(by.size() == 2 && by.get(a).equals(Map.of("slime", 3)), "the house: its chunk, its ring, the shared ring chunk: " + by);
        t(by.get(b).equals(Map.of("slime", 1)), "the hut: the shared ring chunk only: " + by.get(b));
        t(minute(p).isEmpty(), "a quiet minute writes nothing");
        p.count(Map.of(), W, ca, cz, SLIME);
        t(p.swap().isEmpty(), "no watch yet: nothing counted");

        // one chunk apart: each stands in the other's ring, but a chunk where a build stands credits only the builds there
        ConstructionTest.Sc n = twoBuilds(20);
        t(n.idx.sites.size() == 2 && chunkOf(X + 20) == ca + 1, "two builds in neighbouring chunks");
        int na = house(n).id, nb = other(n, na);
        w = Production.watch(n.idx);
        p.count(w, W, ca, cz, SLIME);
        t(minute(p).equals(Map.of(na, Map.of("slime", 1))), "a death in the house's chunk: the house only");
        p.count(w, W, ca + 1, cz, SLIME);
        t(minute(p).equals(Map.of(nb, Map.of("slime", 1))), "a death in the hut's chunk: the hut only");
        p.count(w, W, ca + 2, cz, SLIME);
        p.count(w, W, ca - 1, cz, SLIME);
        t(minute(p).equals(Map.of(nb, Map.of("slime", 1), na, Map.of("slime", 1))), "each build's own ring");
        p.count(w, W, ca, cz + 1, SLIME);
        t(minute(p).equals(Map.of(nb, Map.of("slime", 1), na, Map.of("slime", 1))), "a ring chunk where no build stands: both");
    }

    // ------------------------------------------------------------ the fold: daily buckets, 7 days, merge, alias, forget
    static String prodLine(int site, Map<String, Integer> died) {
        Map<Long, int[]> m = new HashMap<>();
        for (Map.Entry<String, Integer> e : died.entrySet()) m.put(((long) site << 20) | List.of(ENT).indexOf(e.getKey()), new int[] {e.getValue()});
        return Production.line(m, ENT);
    }

    static void foldBuckets() throws Exception {
        ConstructionTest.Sc s = twoBuilds();
        SiteIndex.Site a = house(s), b = s.idx.sites.get(other(s, a.id));
        String d0 = s.day.toString();
        s.raw.append(prodLine(a.id, Map.of("slime", 300)));
        s.raw.append(prodLine(a.id, Map.of("slime", 21)));
        s.raw.append(prodLine(999, Map.of("slime", 5)));   // a build that is gone
        // an alpha.20 draft's line with pickups: the deaths count, the pickups are ignored
        s.raw.append("{\"k\":\"f\",\"f\":{\"" + a.id + "\":{\"d\":{\"cow\":1},\"i\":{\"slime_ball\":280}}}}\n");
        s.fold();
        t(a.mobsDied.equals(new TreeMap<>(Map.of(d0, new TreeMap<>(Map.of("slime", 321, "cow", 1))))), "one bucket for the day: " + a.mobsDied);
        t(b.mobsDied == null, "the other build: none");
        String saved = s.idx.write();
        t(saved.contains("\"mobs_died\"") && !saved.contains("slime_ball") && SiteIndex.read(saved).sites.get(a.id).mobsDied.equals(a.mobsDied),
                "saved in sites.json and read back");
        // an alpha.20 draft's sites.json with pickups: loads, and the field is gone on the next save
        String draft = saved.replace("\"mobs_died\":", "\"items_collected\":{\"" + d0 + "\":{\"slime_ball\":280}},\"mobs_died\":");
        t(draft.contains("items_collected"), "the draft has the old field");
        SiteIndex back = SiteIndex.read(draft);
        t(back.sites.get(a.id).mobsDied.equals(a.mobsDied) && !back.write().contains("items_collected"), "an old items_collected field is ignored");
        for (int i = 0; i < 6; i++) s.fold();
        t(a.mobsDied != null && a.mobsDied.containsKey(d0), "still counted on day 7");
        s.fold();
        t(a.mobsDied == null, "7-day roll-off: gone on day 8");
        // a merged build's production goes with it; an alias's line goes to the live build
        TreeMap<String, TreeMap<String, Integer>> x = Production.add(null, d0, Map.of("slime", 3)), y = Production.add(null, d0, Map.of("slime", 4, "cow", 1));
        t(Production.merge(x, y).get(d0).equals(Map.of("slime", 7, "cow", 1)), "merge adds the buckets");
        // totals stop at the int limit instead of wrapping negative
        TreeMap<String, TreeMap<String, Integer>> big = Production.add(null, d0, Map.of("slime", Integer.MAX_VALUE - 1));
        Production.add(big, d0, Map.of("slime", 5));
        t(big.get(d0).get("slime") == Integer.MAX_VALUE, "a day's total is capped: " + big);
        Production.add(big, s.day.minusDays(1).toString(), Map.of("slime", Integer.MAX_VALUE));
        t(Production.top(big, 8).get(0).getValue() == Integer.MAX_VALUE, "the 7-day total is capped");
        b.aliasOf = a.id;
        s.raw.append(prodLine(b.id, Map.of("cow", 2)));
        s.fold();
        t(a.mobsDied != null && a.mobsDied.firstEntry().getValue().equals(Map.of("cow", 2)), "an alias's production goes to its build");
        // forget: a forgotten player's only build goes, and its production with it
        ConstructionTest.Sc f = new ConstructionTest.Sc().notice(STEVE);
        ConstructionTest.house(f, STEVE, X, Y, Z);
        f.fold();
        int id = f.idx.sites.firstKey();
        f.raw.append(prodLine(id, Map.of("zombie", 50)));
        f.fold();
        t(f.idx.sites.get(id).mobsDied != null, "counted");
        Fold.forget(f.idx, f.prefs, f.id(STEVE).toString());
        t(f.idx.sites.isEmpty() && Production.watch(f.idx).isEmpty(), "forget: the build, its production and its watch are gone");

        // a backlog: raw files from days no paper folded count on their own day, not the fold's
        ConstructionIOTest.Rig r = new ConstructionIOTest.Rig();
        r.req(STEVE, "noticed", null);
        ConstructionTest.house(r.sc, STEVE, X, Y, Z);
        LocalDate d1 = ConstructionIOTest.D1;
        r.flush(d1);
        r.fold(d1, true);
        int rid = r.sites().sites.firstKey();
        r.io.appendRaw(prodLine(rid, Map.of("slime", 21)), d1.plusDays(1));
        r.io.appendRaw(prodLine(rid, Map.of("cow", 30)), d1.plusDays(2));
        r.fold(d1.plusDays(2), true);
        TreeMap<String, TreeMap<String, Integer>> got = r.sites().sites.get(rid).mobsDied;
        t(got != null && got.equals(new TreeMap<>(Map.of(d1.plusDays(1).toString(), new TreeMap<>(Map.of("slime", 21)),
                d1.plusDays(2).toString(), new TreeMap<>(Map.of("cow", 30))))), "each raw file's deaths on its own date: " + got);
    }

    // ------------------------------------------------------------ the card and when to ask again
    static void cardAndDue() {
        ConstructionTest.Sc s = twoBuilds();
        SiteIndex.Site a = house(s);
        String d = s.day.minusDays(1).toString();
        LinkedHashMap<String, Integer> many = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) many.put("mob" + (char) ('a' + i), 10 + i);
        a.mobsDied = Production.add(null, d, many);
        JsonObject c = KindDesk.shown(a, s.fr.out.get(a.id));
        JsonObject died = c.getAsJsonObject("mobs_died_here_7d");
        t(died.size() == 8 && died.has("mobj") && !died.has("moba") && !died.has("mobb") && died.keySet().iterator().next().equals("mobj"), "top 8, biggest first: " + died);
        t(!c.has("items_collected_here_7d") && !KindDesk.RULES.contains("items_collected") && !KindDesk.RULES.contains("picked"), "no pickups anywhere");
        String text = KindDesk.prompt(List.of(c));
        for (String bad : List.of(STEVE, ALEX, String.valueOf(X), String.valueOf(Z), String.valueOf(chunkOf(X)), String.valueOf(chunkOf(Z)), W.toString()))
            t(!text.contains(bad), "the card never holds " + bad);
        t(KindDesk.RULES.contains("AT this build or right next to it") && KindDesk.RULES.contains("it shows what it farms"), "the prompt explains production");
        a.mobsDied = null;
        t(!KindDesk.shown(a, s.fr.out.get(a.id)).has("mobs_died_here_7d"), "none: omitted");

        // due: production appearing, or its top type changing, asks again
        LocalDate today = s.day;
        a.aiKind = "house"; a.aiConf = "high"; a.aiNet = a.net; a.aiTop5 = KindDesk.top5(a.mats); a.aiAsked = today.minusDays(1).toString(); a.aiProd = null; a.aiList = Kinds.LIST;
        t(!KindDesk.due(a, a.net, today), "asked yesterday, nothing new: not due");
        a.mobsDied = Production.add(null, d, Map.of("cow", 3));
        t(!KindDesk.due(a, a.net, today), "a stray kill or two is not production");
        a.mobsDied = Production.add(null, d, Map.of("slime", 200));
        t(KindDesk.due(a, a.net, today), "production appeared: due");
        a.aiProd = "slime";
        t(!KindDesk.due(a, a.net, today), "asked with that production: not due");
        a.aiProd = "slime|slime_ball";
        t(!KindDesk.due(a, a.net, today), "an alpha.20 draft's answer with a pickup: the same death, not due");
        a.aiProd = "|slime_ball";
        t(KindDesk.due(a, a.net, today), "an alpha.20 draft's answer with only a pickup: the deaths are new, due");
        a.aiProd = "slime";
        a.mobsDied = Production.add(a.mobsDied, d, Map.of("spider", 500));
        t(KindDesk.due(a, a.net, today), "the top type changed: due");
    }

    // ------------------------------------------------------------ a slime farm, with what it produces
    static void farmRegression() throws Exception {
        List<KindDesk.Card> fixture = KindDeskTest.fixtureCards();
        JsonObject j = fixture.get(0).shown().deepCopy();
        t(j.getAsJsonObject("materials_net").get("cobblestone").getAsInt() == 1321 && j.getAsJsonObject("materials_net").get("stone_brick_slab").getAsInt() == 512,
                "card A is the mob tower");
        j.add("mobs_died_here_7d", com.google.gson.JsonParser.parseString("{\"slime\":1200}"));
        Map<String, Integer> ki = new HashMap<>();
        for (String k : j.getAsJsonObject("materials_net").keySet()) ki.put(k, KeyRules.info(k, Set.of()));
        List<KindDesk.Card> cards = new ArrayList<>(fixture);
        cards.set(0, KindDesk.card(100, j, ki));
        t(cards.get(0).prod().equals("slime"), "the card's production: " + cards.get(0).prod());
        Map<Integer, KindDesk.Ask> got = KindDesk.read("{\"A\":{\"kind\":\"slime_farm\",\"second\":\"mob_farm\",\"confidence\":\"high\"}}", cards);
        t(got.get(100).kind().equals("slime_farm") && got.get(100).confidence().equals("high") && got.get(100).prod().equals("slime"),
                "the parse and veto path keeps slime_farm: " + got);
        t(KindDesk.prompt(List.of(j)).contains("\"mobs_died_here_7d\":{\"slime\":1200}"), "the AI sees it");
    }

    // ------------------------------------------------------------ the real death handler and the minute flush
    @SuppressWarnings("unchecked")
    static <T> T fake(Class<T> c, Map<String, Object> answers) {
        return (T) Proxy.newProxyInstance(ProductionTest.class.getClassLoader(), new Class<?>[] {c}, (p, m, a) -> switch (m.getName()) {
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> answers.get(m.getName());
        });
    }

    static LivingEntity mob(Class<? extends LivingEntity> c, EntityType type, UUID world, double x, double z) {
        World wd = fake(World.class, Map.of("getUID", world));
        return fake(c, Map.of("getType", type, "getWorld", wd, "getX", x, "getZ", z));
    }

    static void set(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    /** A Construction with no plugin behind it (the constructor needs a running server): just what the handler and flush read. */
    static Construction bare(ConstructionIO io) throws Exception {
        Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        Object unsafe = uf.get(null);
        Construction c = (Construction) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Construction.class);
        set(c, "io", io);
        set(c, "mm", new MinuteMaps());
        set(c, "slots", new WorldSlots());
        set(c, "settings", Settings.defaults());
        Materials m = new Materials();
        m.keyByOrdinal = new String[0];
        set(c, "mat", m);
        String[] names = new String[EntityType.values().length];
        for (EntityType e : EntityType.values()) names[e.ordinal()] = e.name().toLowerCase(java.util.Locale.ROOT);
        c.prod = new Production(names);
        return c;
    }

    static void handlerAndFlush() throws Exception {
        ConstructionIOTest.Rig r = new ConstructionIOTest.Rig();
        r.req(STEVE, "noticed", null);
        ConstructionTest.house(r.sc, STEVE, X, Y, Z);
        LocalDate d1 = ConstructionIOTest.D1;
        r.flush(d1);
        r.fold(d1, true);
        int id = r.sites().sites.firstKey();
        Construction cons = bare(r.io);
        Collector col = new Collector(cons, cons.mm, cons.slots);
        DamageSource src = fake(DamageSource.class, Map.of());
        col.onDeath(new EntityDeathEvent(mob(LivingEntity.class, EntityType.SLIME, W, X + 0.5, Z + 0.5), src, new ArrayList<>()));
        col.onDeath(new EntityDeathEvent(mob(LivingEntity.class, EntityType.SLIME, W, X + 3.5, Z - 1.5), src, new ArrayList<>()));   // the ring
        col.onDeath(new EntityDeathEvent(mob(Player.class, EntityType.PLAYER, W, X + 0.5, Z + 0.5), src, new ArrayList<>()));
        col.onDeath(new EntityDeathEvent(mob(LivingEntity.class, EntityType.ZOMBIE, W, X + 500, Z), src, new ArrayList<>()));   // unwatched chunk
        col.onDeath(new EntityDeathEvent(mob(LivingEntity.class, EntityType.ZOMBIE, UUID.randomUUID(), X, Z), src, new ArrayList<>()));   // other world
        t(col.errors == 0, "the handler ran without failing");

        // the minute flush writes them as one production line to today's raw file
        Path raw = r.dir.resolve("raw");
        Set<Path> before = Set.copyOf(Files.list(raw).toList());
        cons.flushNow();
        r.io.exec.submit(() -> { }).get();
        List<Path> written = Files.list(raw).filter(p -> !before.contains(p)).toList();
        t(written.size() == 1, "the flush wrote a raw file: " + written);
        String text = Files.readString(written.get(0));
        t(text.equals("{\"k\":\"f\",\"f\":{\"" + id + "\":{\"d\":{\"slime\":2}}}}\n"), "slimes in the build and its ring; no player, nothing unwatched: " + text);
        cons.flushNow();
        r.io.exec.submit(() -> { }).get();
        t(Files.readString(written.get(0)).equals(text), "a quiet minute adds nothing");
    }

    // ------------------------------------------------------------ the watch: after the save, at start, and cleared
    static void watchLifecycle() throws Exception {
        ConstructionIOTest.Rig r = new ConstructionIOTest.Rig();
        r.req(STEVE, "noticed", null);
        ConstructionTest.house(r.sc, STEVE, X, Y, Z);
        LocalDate d1 = ConstructionIOTest.D1;
        r.flush(d1);
        r.fold(d1, true);
        Map<UUID, Production.Watch> w1 = r.io.watch;
        t(!w1.isEmpty(), "a published fold publishes the watch");

        // at start: the watch comes from sites.json
        ConstructionIO fresh = new ConstructionIO(r.dir, ConstructionIOTest.LOG);
        t(fresh.watch.isEmpty(), "a new IO thread has no watch");
        fresh.loadWatch();
        t(fresh.watch.keySet().equals(w1.keySet()) && Arrays.equals(fresh.watch.get(W).keys(), w1.get(W).keys()), "loadWatch: the same chunks from sites.json");

        // a watch that cannot be built never leaves a half-done save: requests still rotate and the paper is written
        Files.writeString(r.dir.resolve("sites.json"), r.read("sites.json").replace(W.toString(), "not-a-world"));
        r.req(ALEX, "noticed", null);
        Path out = r.fold(d1.plusDays(1), true);
        t(out != null && Files.exists(out), "the paper is written");
        t(!Files.exists(r.dir.resolve("requests.jsonl")) && r.read("requests.prev.jsonl").contains(r.sc.id(ALEX).toString()),
                "the requests rotated");
        t(r.io.watch == w1, "the old watch stays");

        // the age sweep and reset clear it: build ids start again from 1
        t(!r.io.watch.isEmpty(), "watched before the sweep");
        Files.setLastModifiedTime(r.dir.resolve("sites.json"), FileTime.from(Instant.now().minus(40, ChronoUnit.DAYS)));
        r.io.sweepAged(d1.plusDays(1), 30);
        t(!Files.exists(r.dir.resolve("sites.json")) && r.io.watch.isEmpty(), "the age sweep: no watch");
        r.io.watch = w1;
        r.io.reset();
        t(r.io.watch.isEmpty(), "reset: no watch");
    }

    // ------------------------------------------------------------ review fix 2 and the same-day re-run (with a stub writer)
    static void desk() throws Exception {
        ConstructionIOTest.Rig r = new ConstructionIOTest.Rig();
        r.req(STEVE, "noticed", null);
        ConstructionTest.house(r.sc, STEVE, X, Y, Z);
        LocalDate d1 = ConstructionIOTest.D1;
        r.flush(d1);
        KindDeskTest.Stub w = new KindDeskTest.Stub("{\"A\":{\"kind\":\"villager_breeder\",\"second\":\"house\",\"confidence\":\"medium\"}}");
        List<String> notes = new ArrayList<>();
        Path out = Construction.fold(r.io, KindDeskTest.job(r, d1, true, Map.of(), null), w, Duration.ofSeconds(1), notes);
        t(w.calls == 1 && notes.isEmpty() && Files.readString(out).contains("\"kind\":\"villager_breeder\"")
                && r.read("sites.json").contains("\"ai_kind\":\"villager_breeder\""), "practice fold -> ask -> real fold: " + notes);
        t(!r.io.watch.isEmpty(), "the published fold publishes the production watch");
        // the same day again: the preview and a re-run fold from the .prev files, but today's answer still counts
        Path pv = r.io.analyze(KindDeskTest.job(r, d1, false, Map.of(), null), notes);
        t(Files.readString(pv).contains("\"kind\":\"villager_breeder\""), "a same-day preview prints today's AI kind");
        out = Construction.fold(r.io, KindDeskTest.job(r, d1, true, Map.of(), null), w, Duration.ofSeconds(1), notes);
        t(w.calls == 1 && Files.readString(out).contains("\"kind\":\"villager_breeder\""), "a same-day re-run is not asked again and keeps the kind");
        // a practice fold that times out hands over no cards and asks nothing; the owner gets a note
        r.io.waitMs = 50;
        r.io.testSleepBeforeCommitMs = 300;
        notes.clear();
        Construction.fold(r.io, KindDeskTest.job(r, d1.plusDays(1), true, Map.of(), null), w, Duration.ofSeconds(1), notes);
        r.io.exec.submit(() -> { }).get();   // let the late folds finish
        t(w.calls == 1 && notes.contains("build kinds: practice fold skipped (timeout)"), "a late practice fold: no call, a note: " + notes);
    }
}
