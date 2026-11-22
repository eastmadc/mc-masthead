package io.github.eastmadc.masthead.facts;

import io.github.eastmadc.masthead.config.Settings;
import com.google.gson.FormattingStyle;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The day's facts: a port of collect() from the reference Python script. Pure Java + Gson, works on files only.
 * Reads stats/, events/, blotter/, mailroom/, health/, editions/ and an optional web access log under the data folder
 * (formats: FORMAT.md) and returns the same JSON keys and values as the Python data/DATE.json. Also writes data/DATE.json,
 * without the letters (mailbag_candidates) and reader names (circulation.who): those stay in memory only.
 *
 * The edition window is [yesterday at editionTime, today at editionTime) in the configured zone.
 */
public final class FactsBuilder {
    /** Where the built-in web server writes its access log, relative to the data folder (outside the served web/ folder). */
    public static final String BUILTIN_ACCESS_LOG = "web-access.log";
    /** "address name" lines written by the plugin, relative to the data folder. Never copied anywhere else. */
    public static final String IP_PLAYERS = "ip-players.txt";

    private final Path dataFolder;
    private final Settings settings;
    private String paperBuild = "";
    private Path outDir;          // where DATE.json goes; null = data/ under the data folder
    private boolean write = true;
    private Path todayStats;      // today's stats snapshot; null = stats/DATE
    private Set<String> blockItems = Set.of();   // item keys that are blocks, for the Building Report
    private Path constructionOut; // construction/out/DATE.json; null = no construction news today
    private List<String> constructionDropped = List.of();
    private String owner = "";

    public FactsBuilder(Path dataFolder, Settings settings) {
        this.dataFolder = dataFolder;
        this.settings = settings;
    }

    /** Where to write DATE.json (null = don't write it at all, e.g. a preview). Default: data/ in the data folder. */
    public FactsBuilder writeTo(Path dir) {
        this.outDir = dir;
        this.write = dir != null;
        return this;
    }

    /** Read today's stats from this folder instead of stats/DATE (a preview's throwaway copy). */
    public FactsBuilder todayStats(Path dir) {
        this.todayStats = dir;
        return this;
    }

    /** Item keys ("oak_planks") that are placeable blocks, from the server. Empty = no Building Report. */
    public FactsBuilder blockItems(Set<String> keys) {
        this.blockItems = keys == null ? Set.of() : keys;
        return this;
    }

    /** Construction news for this edition (construction/out/DATE.json), used only when the feature is on and printing. */
    public FactsBuilder construction(Path outFile) {
        this.constructionOut = outFile;
        return this;
    }

    /** Keys Gate 1 dropped from the last construction file (names only, for the console). */
    public List<String> constructionDropped() {
        return constructionDropped;
    }

    /** The owner as spelled in game after the last build ("" = no owner, or a hidden one). */
    public String owner() {
        return owner;
    }

    /**
     * The seam for a separate "busiest builders" report: when the paper has a construction section, construction news
     * owns that role and the other report should leave it out.
     */
    public static boolean constructionOwnsBuilders(JsonObject data) {
        return data.has("construction");
    }

    /** Optional server version for the 'server' block (e.g. Bukkit.getVersion(), capped at 40 characters). */
    public FactsBuilder paperBuild(String build) {
        this.paperBuild = build == null ? "" : build;
        return this;
    }

    public JsonObject build(LocalDate date, LocalTime editionTime) throws IOException {
        String ds = date.toString();
        ZoneId zone = zone();
        LocalDateTime loLocal = LocalDateTime.of(date.minusDays(1), editionTime), hiLocal = LocalDateTime.of(date, editionTime);
        long lo = loLocal.atZone(zone).toEpochSecond(), hi = hiLocal.atZone(zone).toEpochSecond();
        Set<String> hidden = new HashSet<>();
        for (String h : settings.privacy().hidePlayers()) hidden.add(h.toLowerCase(Locale.ROOT));
        String owner = settings.owner().name() == null ? "" : settings.owner().name().trim();
        if (hidden.contains(owner.toLowerCase(Locale.ROOT))) owner = "";

        // Stats: today's snapshot, the previous one, and (Sundays) the one a week back.
        Path statsDir = dataFolder.resolve("stats");
        List<String> snaps = new ArrayList<>();
        if (Files.isDirectory(statsDir)) {
            try (DirectoryStream<Path> s = Files.newDirectoryStream(statsDir)) {
                for (Path p : s) {
                    String n = p.getFileName().toString();
                    if (n.matches("\\d{4}-\\d\\d-\\d\\d") && n.compareTo(ds) < 0) snaps.add(n);
                }
            }
        }
        snaps.sort(null);
        boolean hasPrev = !snaps.isEmpty();
        Map<String, Stats.Row> now = hide(Stats.load(todayStats != null ? todayStats : statsDir.resolve(ds)), hidden);
        Map<String, Stats.Row> prev = hasPrev ? hide(Stats.load(statsDir.resolve(snaps.get(snaps.size() - 1)), now), hidden) : Map.of();
        boolean weekly = date.getDayOfWeek() == DayOfWeek.SUNDAY;
        Set<String> players = now.keySet();
        for (String p : players) if (p.equalsIgnoreCase(owner)) owner = p;   // owner.name in any case means the in-game name
        this.owner = owner;

        Events ev = Events.read(dataFolder, date, lo, hi, hidden, players);
        List<BlotterFacts.Entry> blot = settings.features().blotter() ? BlotterFacts.read(dataFolder, date, lo, hi, players) : List.of();
        ConstructionFacts.Result cons = null;
        if (settings.features().construction() && settings.construction().print() && constructionOut != null) {
            cons = ConstructionFacts.read(constructionOut, players, hidden, weekly, settings.construction().maxEntries());
            constructionDropped = cons.dropped();
            if (settings.features().blotter()) blot = BlotterFacts.merge(blot, cons.blotter(), cons.dropMinedAt());
        }
        Map<String, Stats.Row> today = hasPrev ? Stats.delta(now, prev) : Map.of();

        LinkedHashSet<String> on = new LinkedHashSet<>(ev.joined);   // blotter actors were online too
        blot.forEach(e -> on.add(e.by));
        List<String> playersOn = namesOk(on, players);

        JsonObject data = new JsonObject();
        data.addProperty("date", ds);
        data.addProperty("edition", weekly ? "weekly" : "daily");
        data.addProperty("first_edition", !hasPrev);
        data.add("players_on", strings(playersOn));
        JsonArray newPlayers = new JsonArray();
        if (hasPrev) {
            TreeSet<String> np = new TreeSet<>(namesOk(ev.joined, players));
            np.removeAll(prev.keySet());
            np.forEach(newPlayers::add);
        }
        data.add("new_players", newPlayers);
        data.add("today", Stats.rows(today));
        JsonObject week = new JsonObject();
        if (weekly && hasPrev) {
            String cut = date.minusDays(7).toString(), base = snaps.get(0);
            for (String s : snaps) if (s.compareTo(cut) <= 0) base = s;
            week = Stats.rows(Stats.delta(now, hide(Stats.load(statsDir.resolve(base), now), hidden)));
        }
        data.add("week", week);
        JsonObject season = new JsonObject();
        now.forEach((n, r) -> season.add(n, Stats.rounded(r)));
        data.add("season_totals", season);
        data.add("leaders", Stats.leaders(now));
        data.add("leaders_today", hasPrev ? Stats.leaders(today) : new JsonObject());
        data.add("awards", Awards.pick(today, ev, players, blot, cons == null ? List.of() : cons.awardReasons()));
        data.add("police_blotter", BlotterFacts.toJson(blot));
        if (cons != null && cons.construction() != null) data.add("construction", cons.construction());
        if (io.github.eastmadc.masthead.edition.StylePrompt.watchOn(settings)) data.add("chicken_watch", watch(now, prev, hasPrev));   // no column, no data for it
        data.add("circulation", settings.features().readership() ? readership(loLocal, hiLocal, owner, hidden) : JsonNull.INSTANCE);
        if (!owner.isEmpty()) data.add("owner", ownerBlock(owner, now, prev, hasPrev, playersOn));
        if (settings.features().construction()) dropBlockUses(data, blockItems);
        JsonArray recentWords = new JsonArray();
        data.add("recent_editions", recent(ds, hidden, recentWords, players));
        if (!recentWords.isEmpty()) data.add("recent_words", recentWords);   // the cooldown (Lexicon); FactCheck.facts keeps it from the writer
        data.add("deaths", strings(ev.deaths.subList(0, Math.min(40, ev.deaths.size()))));
        JsonArray adv = new JsonArray();
        ev.advancements.stream().limit(60).forEach(a -> adv.add(pair(a[0], a[1])));
        data.add("advancements", adv);
        JsonArray pvp = new JsonArray();
        ev.pvp.stream().filter(p -> players.contains(p[0]) && players.contains(p[1])).limit(20).forEach(p -> pvp.add(pair(p[0], p[1])));
        data.add("pvp", pvp);
        if (settings.buildingReportPrints() && hasPrev) {
            long gapMin = (java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(snaps.get(snaps.size() - 1)), date) + 1) * 1440;
            // construction news on (practice run or paused): list only players it may name; everyone still counts in the total
            java.util.function.BiPredicate<String, String> listed = settings.features().construction()
                    ? io.github.eastmadc.masthead.construction.Levels.nameable(dataFolder, settings.construction()) : (u, n) -> true;
            JsonObject b = Building.report(now, prev, blockItems, gapMin, listed);
            if (b != null) data.add("building", b);
        }
        data.add("server", Health.read(dataFolder, date, lo, hi, extraFile(), paperBuild));
        if (!owner.isEmpty() && !settings.owner().hoursOk()) dropOwnerHours(data, owner);
        // Never shown to the main writer: the prompt builder must leave this out.
        data.add("mailbag_candidates", settings.features().mailroom() ? MailCandidates.read(dataFolder, date, players) : new JsonArray());

        if (write) write(ds, redacted(data));
        return data;
    }

    /**
     * With construction news on, placed blocks never reach the writer through the stats: a private builder's "used" counts
     * would describe their build, and a block like pale moss names a place. Uses that aren't blocks (ender pearls, eggs)
     * stay. With no block list from the server, every "used" map is emptied. (In a practice run this is the one way the
     * facts differ from construction off: a player who chose private is promised it from the first notice.)
     */
    static void dropBlockUses(JsonObject data, Set<String> blockItems) {
        List<JsonObject> rows = new ArrayList<>();
        for (String k : List.of("today", "week"))
            if (data.get(k) instanceof JsonObject o) for (Map.Entry<String, JsonElement> e : o.entrySet()) if (e.getValue() instanceof JsonObject r) rows.add(r);
        if (data.get("owner") instanceof JsonObject ob && ob.get("today") instanceof JsonObject r) rows.add(r);
        for (JsonObject r : rows) {
            if (!(r.get("used") instanceof JsonObject u)) continue;
            for (String k : new ArrayList<>(u.keySet())) if (blockItems.isEmpty() || blockItems.contains(k)) u.remove(k);
        }
    }

    /** owner.hours-ok: false: the owner's minutes played leave the facts (their rows, the owner block and the leader lists). */
    static void dropOwnerHours(JsonObject data, String owner) {
        for (String k : List.of("today", "week", "season_totals"))
            if (data.get(k) instanceof JsonObject o && o.get(owner) instanceof JsonObject r) r.remove("play_min");
        for (String k : List.of("leaders", "leaders_today"))
            if (data.get(k) instanceof JsonObject o && o.get("play_min") instanceof JsonArray a) {
                JsonArray keep = new JsonArray();
                for (JsonElement e : a) if (!(e instanceof JsonArray p && !p.isEmpty() && owner.equals(str(p.get(0))))) keep.add(e);
                o.add("play_min", keep);
            }
        if (data.get("owner") instanceof JsonObject ob)
            for (String k : List.of("season", "today")) if (ob.get(k) instanceof JsonObject r) r.remove("play_min");
    }

    private ZoneId zone() {
        String z = settings.schedule().zone();
        return z == null || z.isBlank() ? ZoneId.systemDefault() : ZoneId.of(z);
    }

    /** Hidden players are dropped from the stats, so they never rank, win, or count as real players anywhere. */
    private static Map<String, Stats.Row> hide(LinkedHashMap<String, Stats.Row> rows, Set<String> hidden) {
        rows.keySet().removeIf(n -> hidden.contains(n.toLowerCase(Locale.ROOT)));
        return rows;
    }

    /** Python names_ok(): valid names that are real players (Bedrock names may carry a leading dot). */
    private static List<String> namesOk(Iterable<String> xs, Set<String> players) {
        List<String> out = new ArrayList<>();
        for (String x : xs) if (Py.isName(x) && (players.contains(x) || players.contains(x.replaceAll("^\\.+", "")))) out.add(x);
        return out;
    }

    /** Python chicken_watch(), for the configured watch animal. Eggs only count when the animal is the chicken. */
    private JsonObject watch(Map<String, Stats.Row> now, Map<String, Stats.Row> prev, boolean hasPrev) {
        String animal = settings.paper().watchAnimal() == null ? "chicken" : settings.paper().watchAnimal().toLowerCase(Locale.ROOT).trim();
        animal = animal.substring(animal.indexOf(':') + 1);
        if (animal.isEmpty()) animal = "chicken";
        String mob = animal;
        boolean eggs = animal.equals("chicken");
        java.util.function.Function<Stats.Row, Long> ck = r -> r == null ? 0L : r.map("killed").getOrDefault(mob, 0L);
        java.util.function.Function<Stats.Row, Long> eg = r -> r == null ? 0L : r.num("eggs");
        JsonObject s = new JsonObject();
        s.addProperty("chickens_killed", now.values().stream().mapToLong(ck::apply).sum());
        if (eggs) s.addProperty("eggs_thrown", now.values().stream().mapToLong(eg::apply).sum());
        s.add("top_chicken_hunter", top(now, ck));
        if (eggs) s.add("top_egg_thrower", top(now, eg));
        JsonObject out = new JsonObject();
        if (!eggs) out.addProperty("animal", animal);   // keys keep their chicken names for compatibility
        out.add("season", s);
        if (hasPrev) {
            JsonObject by = new JsonObject();
            long tk = 0, te = 0;
            for (Map.Entry<String, Stats.Row> e : now.entrySet()) {
                Stats.Row p = prev.get(e.getKey());
                long k = ck.apply(e.getValue()) - ck.apply(p), g = eggs ? eg.apply(e.getValue()) - eg.apply(p) : 0;
                if (k > 0 || g > 0) {
                    JsonObject v = new JsonObject();
                    v.addProperty("chickens_killed", k);
                    if (eggs) v.addProperty("eggs_thrown", g);
                    by.add(e.getKey(), v);
                    tk += k;
                    te += g;
                }
            }
            JsonObject t = new JsonObject();
            t.addProperty("chickens_killed", tk);
            if (eggs) t.addProperty("eggs_thrown", te);
            t.add("by_player", by);
            out.add("today", t);
        }
        return out;
    }

    /** First player with the highest value, or null if nobody has any. */
    private static JsonElement top(Map<String, Stats.Row> now, java.util.function.Function<Stats.Row, Long> f) {
        String best = null;
        long bv = 0;
        for (Map.Entry<String, Stats.Row> e : now.entrySet()) {
            long v = f.apply(e.getValue());
            if (v > bv) {
                bv = v;
                best = e.getKey();
            }
        }
        return best == null ? JsonNull.INSTANCE : new JsonPrimitive(best);
    }

    /** Python owner_block(), with played_today taken from the join log as well. */
    private JsonObject ownerBlock(String owner, Map<String, Stats.Row> now, Map<String, Stats.Row> prev, boolean hasPrev, List<String> playersOn) {
        JsonObject o = new JsonObject();
        o.addProperty("name", owner);
        Stats.Row r = now.get(owner);
        if (r == null) {
            o.addProperty("note", "no stats yet");
            o.addProperty("played_today", playersOn.contains(owner));
            return o;
        }
        o.addProperty("role", "server owner and admin");
        JsonObject season = Stats.rounded(r);
        for (String m : List.of("killed", "used", "crafted", "mined", "broken")) {
            Map<String, Long> v = r.map(m);
            if (m.equals("used") && settings.features().construction()) {   // only what isn't a block (see dropBlockUses)
                v = new LinkedHashMap<>(v);
                if (blockItems.isEmpty()) v.clear(); else v.keySet().removeAll(blockItems);
            }
            season.add("top_" + m, top6(v));
        }
        o.add("season", season);
        JsonObject today = new JsonObject();
        if (hasPrev) {
            Map<String, Stats.Row> one = new LinkedHashMap<>(), before = new LinkedHashMap<>();
            one.put(owner, r);
            if (prev.containsKey(owner)) before.put(owner, prev.get(owner));
            Stats.Row d = Stats.delta(one, before).get(owner);
            if (d != null) today = d.toJson();
        }
        o.add("today", today);
        o.addProperty("played_today", playersOn.contains(owner) || today.size() > 0);
        JsonArray lore = new JsonArray();
        settings.owner().lore().forEach(lore::add);
        o.add("lore", lore);
        return o;
    }

    private static JsonObject top6(Map<String, Long> m) {
        List<Map.Entry<String, Long>> l = new ArrayList<>();
        m.forEach((k, v) -> { if (v > 0) l.add(Map.entry(k, v)); });
        l.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        JsonObject o = new JsonObject();
        l.stream().limit(6).forEach(e -> o.addProperty(e.getKey(), e.getValue()));
        return o;
    }

    private JsonElement readership(LocalDateTime lo, LocalDateTime hi, String owner, Set<String> hidden) {
        Path log;
        String configured = settings.web().accessLog();
        if (configured != null && !configured.isBlank()) {
            log = dataFolder.resolve(configured);
        } else {
            log = dataFolder.resolve(BUILTIN_ACCESS_LOG);
        }
        return Readership.read(log, dataFolder.resolve(IP_PLAYERS), pathPrefix(), lo, hi, owner, hidden);
    }

    /** URL path the paper is served under, from web.public-url ("http://host/news/" -> "/news"; none -> ""). */
    private String pathPrefix() {
        String url = settings.web().publicUrl();
        if (url == null || url.isBlank()) return "";
        try {
            String p = URI.create(url.trim()).getPath();
            return p == null ? "" : p.replaceAll("/+$", "");
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private Path extraFile() {
        String f = settings.features().healthExtraFile();
        return f == null || f.isBlank() ? null : dataFolder.resolve(f);
    }

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WS = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Python recent_text(): plain text of the last 3 editions, so the writer can avoid repeating headlines and angles. Only
     * the first 250 characters each (the headline and deck): with 1200 (the front pages) the writer copied their stock
     * phrases back, several times a paper. words gets the resting words each whole paper used (Lexicon.usedWords), for the cooldown.
     */
    private JsonArray recent(String ds, Set<String> hidden, JsonArray words, Collection<String> names) throws IOException {
        Path dir = dataFolder.resolve("editions");
        List<String> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.html")) {
                for (Path p : s) {
                    String n = p.getFileName().toString();
                    if (!n.startsWith(".") && Py.head(n, 10).compareTo(ds) < 0) files.add(n);
                }
            }
        }
        files.sort(null);
        JsonArray out = new JsonArray();
        for (String n : files.subList(Math.max(0, files.size() - 3), files.size())) {
            String html = Py.readCapped(dir.resolve(n), 4 << 20);
            if (html == null) continue;
            html = io.github.eastmadc.masthead.edition.ConstructionGate.withoutBuilds(html, settings.construction().title());
            String text = WS.matcher(TAG.matcher(html).replaceAll(" ")).replaceAll(" ");
            for (String h : hidden)   // a player hidden since that paper ran is not named again
                text = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(h) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE).matcher(text).replaceAll("a player");
            out.add(Py.head(text, 250));
            for (String w : io.github.eastmadc.masthead.edition.Lexicon.usedWords(settings, text, names))   // names out: a player called Cooked_Kid never rests "cooked"
                if (!words.contains(new com.google.gson.JsonPrimitive(w))) words.add(w);
        }
        return out;
    }

    /** The facts without letters and reader names: what may be kept on disk (shallow copy; data is not changed). */
    public static JsonObject redacted(JsonObject data) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : data.entrySet()) {
            if (e.getKey().equals("mailbag_candidates")) continue;
            if (e.getKey().equals("circulation") && e.getValue().isJsonObject()) {
                JsonObject c = new JsonObject();
                for (Map.Entry<String, JsonElement> ce : e.getValue().getAsJsonObject().entrySet())
                    if (!ce.getKey().equals("who")) c.add(ce.getKey(), ce.getValue());
                out.add("circulation", c);
                continue;
            }
            out.add(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * Rewrites an older data/DATE.json (2.0.0 kept the letters and reader names in it) without them. Returns true if the
     * file changed. Files that are not a JSON object, or are bigger than 8 MB, are left alone.
     */
    public static boolean rewriteRedacted(Path file) throws IOException {
        String txt = Py.readCapped(file, 8 << 20);
        if (txt == null) return false;
        JsonElement el = Py.parse(txt);
        if (el == null || !el.isJsonObject()) return false;
        JsonObject o = el.getAsJsonObject();
        boolean who = o.get("circulation") instanceof JsonObject c && c.has("who");
        if (!o.has("mailbag_candidates") && !who) return false;
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(redacted(o)), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    private void write(String ds, JsonObject data) throws IOException {
        Path dir = outDir != null ? outDir : dataFolder.resolve("data");
        io.github.eastmadc.masthead.pipeline.Housekeeping.createPrivateDirs(dir);
        Path tmp = dir.resolve(ds + ".json.tmp"), dst = dir.resolve(ds + ".json");
        Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
        Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static final Gson GSON = new GsonBuilder().setFormattingStyle(FormattingStyle.PRETTY.withIndent(" ")).disableHtmlEscaping()
            .serializeNulls().create();

    private static JsonArray strings(List<String> xs) {
        JsonArray a = new JsonArray();
        xs.forEach(a::add);
        return a;
    }

    private static JsonArray pair(String a, String b) {
        JsonArray p = new JsonArray();
        p.add(a);
        p.add(b);
        return p;
    }

    /** A JSON string, or a number's text (Python str() on a loaded value); null otherwise. */
    static String str(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || e.getAsJsonPrimitive().isBoolean()) return null;
        return e.getAsString();
    }

    /** A JSON number, or a string holding one (Python float()); null otherwise. */
    static Double dbl(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || e.getAsJsonPrimitive().isBoolean()) return null;
        try {
            double d = e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : Double.parseDouble(e.getAsString().trim());
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
