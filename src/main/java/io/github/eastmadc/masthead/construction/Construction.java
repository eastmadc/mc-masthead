package io.github.eastmadc.masthead.construction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eastmadc.masthead.config.Settings;
import io.github.eastmadc.masthead.writer.Writer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Construction news, the server side: switches the recorder on and off on reload (never twice), hands each minute to the
 * IO thread, runs the daily fold for the edition, sends the one-time notice, and answers /news build and
 * /masthead construction. Created once when the plugin starts; stop() only when it stops.
 */
public final class Construction {
    /** state.yml key: "masthead construction pause" (survives a restart; works exactly like construction.print: false). */
    public static final String PAUSE_KEY = "construction-paused";

    private final JavaPlugin plugin;
    private final Logger log;
    private final Path data;
    final MinuteMaps mm = new MinuteMaps();
    final WorldSlots slots = new WorldSlots();
    final Collector collector;
    final ConstructionIO io;
    private final BuildCommands build;
    Supplier<Materials> materials;   // swappable for the throwaway-server failure test (L-9b)
    private Materials mat;
    Production prod;                 // production counts at builds (main thread), once the materials are read
    private volatile Map<String, Long> slimeSeeds = Map.of();   // Overworld UUID -> seed, read on the main thread at start
    private BukkitTask timer;
    volatile String stoppedBy;       // set when this Paper version broke the recorder
    private volatile Settings settings = Settings.defaults();
    private boolean folderOk = true, noticeLoadStarted, overflowWarned;
    private volatile Set<UUID> noticed;   // null until loaded from disk
    private final Set<UUID> sentThisRun = new HashSet<>();
    private final Set<String> warned = new HashSet<>();

    public Construction(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.data = plugin.getDataFolder().toPath();
        this.io = new ConstructionIO(data.resolve("construction"), log);
        this.collector = new Collector(this, mm, slots);
        this.build = new BuildCommands(this);
        this.materials = () -> Materials.build(log);
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onJoin(PlayerJoinEvent e) {
                Player p = e.getPlayer();
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline() && needsNotice(p)) sendNotice(p); }, 60L);
            }
        }, plugin);
    }

    Settings settings() { return settings; }

    // ------------------------------------------------------------ on, off, reload
    /**
     * Idempotent (spec §2.2): always unregisters the recorder and cancels its timer first, then registers one recorder and
     * one timer only if construction news is on. The age sweep runs either way. A failure to read this Paper's blocks stops
     * only construction news; the rest of Masthead carries on.
     */
    public void apply(Settings s) {
        HandlerList.unregisterAll(collector);
        if (timer != null) { timer.cancel(); timer = null; }
        stoppedBy = null;
        flushNow();
        settings = s;
        collector.clearWho();
        collector.hidden = lower(s.privacy().hidePlayers());
        collector.ignoreWorlds = lower(s.construction().ignoreWorlds());
        slots.reset();
        mm.maxCells = s.construction().maxCellsPerMinute();
        folderOk = folderOk(s);
        LocalDate today = editionDate(s);
        int days = s.construction().forgetAfterDays();
        io.submit("age sweep", () -> io.sweepAged(today, days));
        if (!s.features().construction()) return;
        if (!folderOk) {
            log.severe("web.folder would publish the building records; construction news is off until web.folder is changed");
            return;
        }
        if (mat == null) {
            try { mat = materials.get(); }
            catch (RuntimeException | LinkageError e) { stopRecorder(e); return; }
        }
        collector.mat = mat;
        if (prod == null) prod = new Production(mat.entityNames);
        Map<String, Long> seeds = new HashMap<>();
        for (org.bukkit.World w : Bukkit.getWorlds()) if (w.getEnvironment() == org.bukkit.World.Environment.NORMAL) seeds.put(w.getUID().toString(), w.getSeed());
        slimeSeeds = Map.copyOf(seeds);
        // survey.json names dimensions: the first loaded world of each (the main world and its nether and end)
        Map<String, String> dims = new HashMap<>();
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            String d = switch (w.getEnvironment()) { case NORMAL -> "overworld"; case NETHER -> "nether"; case THE_END -> "end"; default -> null; };
            if (d != null && !dims.containsValue(d)) dims.put(w.getUID().toString(), d);
        }
        io.dims = Map.copyOf(dims);
        log.info("construction: slime chunk test " + (seeds.isEmpty() ? "off (no Overworld loaded)" : "on for " + seeds.size() + " world(s): " + seeds.keySet()));
        Bukkit.getPluginManager().registerEvents(collector, plugin);
        timer = Bukkit.getScheduler().runTaskTimer(plugin, this::flushNow, 1200L, 1200L);
        if (!noticeLoadStarted) {
            noticeLoadStarted = true;
            io.submit("production watch", io::loadWatch);
            io.submit("notice list", () -> {
                Set<UUID> n = Set.copyOf(io.loadNoticed());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    noticed = n;
                    for (Player p : Bukkit.getOnlinePlayers()) if (needsNotice(p)) sendNotice(p);
                });
            });
        }
    }

    /** This Paper version changed something the recorder uses: stop recording (the rest of Masthead carries on). */
    void stopRecorder(Throwable t) {
        if (stoppedBy != null) return;
        stoppedBy = t.getClass().getSimpleName();
        log.severe("construction: recorder stopped (" + stoppedBy + "); this Paper version is not supported");
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (stoppedBy == null) return;   // a reload since then started it again
                HandlerList.unregisterAll(collector);
                if (timer != null) { timer.cancel(); timer = null; }
            });
        } catch (RuntimeException stopping) { /* the plugin is being turned off */ }
    }

    void warnOnce(String line) {
        if (warned.add(line)) log.warning(line);
    }

    /** Hands the open minute to the IO thread (every minute, before an edition, on reload and at shutdown). */
    public void flushNow() {
        MinuteMaps.Minute min = mm.swap(slots.snapshot());
        if (mm.overflow > 0 && !overflowWarned) {
            overflowWarned = true;
            log.warning("construction: more building at once than max-cells-per-minute; the extra is not counted");
        }
        Materials m = mat;
        HashMap<Long, int[]> made = prod == null ? new HashMap<>() : prod.swap();
        if (m == null || (min.current().isEmpty() && min.losses().isEmpty() && made.isEmpty())) return;
        LocalDate d = editionDate(settings);
        String[] keys = m.keyByOrdinal;
        String[] names = prod.names;
        io.submit("raw write", () -> io.appendRaw(RawLine.write(min, keys) + Production.line(made, names), d));
    }

    /** Only from onDisable: the recorder off, the last minute saved, the IO thread stopped (never in the middle of a commit). */
    public void stop() {
        HandlerList.unregisterAll(collector);
        if (timer != null) { timer.cancel(); timer = null; }
        flushNow();
        try {
            if (!io.drain(5_000, 30_000)) log.warning("construction: open minute not saved (fold still running at shutdown)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        io.exec.shutdownNow();
    }

    // ------------------------------------------------------------ the edition
    /**
     * The daily fold for this edition (waits up to 60 s, then 30 s more for a fold that is already writing). Returns
     * construction/out/DATE.json (or preview.json), or null with a note when there is no section today. kinds = the writer
     * for the kinds desk (null: none today). It is asked only for a published paper, printing on or off, after a practice
     * fold that writes nothing; a preview only reads the kinds already saved.
     */
    public Path analyze(Settings s, LocalDate date, boolean publish, List<String> notes, Writer kinds) {
        if (!s.features().construction()) return null;
        if (!folderOk) { notes.add("construction: skipped (web.folder)"); return null; }
        Materials m = mat;
        if (m == null) { notes.add("construction: skipped (recorder stopped)"); return null; }
        Set<String> hidden = lower(s.privacy().hidePlayers());
        return fold(io, new ConstructionIO.Job(date, publish, s.construction(), hidden, m.keyInfo, editionDate(s), new AtomicInteger(ConstructionIO.RUNNING),
                slimeSeeds, Map.of(), null), kinds, Duration.ofSeconds(s.writer().deskTimeoutSeconds()), notes);
    }

    /**
     * The edition's fold j, after the kinds desk when there is a writer and j publishes: a practice fold (writes nothing) gives
     * the cards, the writer names them, and j applies the answers. Cards are used only if the practice fold reached its
     * hand-off, so a timed-out fold can never fill them while the desk reads them; its note reaches the owner.
     */
    static Path fold(ConstructionIO io, ConstructionIO.Job j, Writer kinds, Duration timeout, List<String> notes) {
        Map<Integer, KindDesk.Ask> ai = Map.of();
        if (j.publish() && kinds != null) {
            List<KindDesk.Card> cards = new ArrayList<>();
            ConstructionIO.Job practice = new ConstructionIO.Job(j.date(), false, j.c(), j.hidden(), j.keyInfo(), j.today(),
                    new AtomicInteger(ConstructionIO.RUNNING), j.slimeSeeds(), Map.of(), cards);
            List<String> pn = new ArrayList<>();
            io.analyze(practice, pn);
            if (pn.isEmpty() && practice.state().get() == ConstructionIO.COMMITTING) ai = KindDesk.ask(kinds, cards, timeout, notes);
            for (String n : pn) notes.add(n.replace("construction: skipped", "build kinds: practice fold skipped"));
        }
        return io.analyze(new ConstructionIO.Job(j.date(), j.publish(), j.c(), j.hidden(), j.keyInfo(), j.today(), j.state(), j.slimeSeeds(), ai, null), notes);
    }

    /** The owner's lines (big damage, new and cleared build names) from an edition's construction/alerts file. Never printed. */
    public static List<String> ownerLines(Path dataFolder, LocalDate date) {
        List<String> out = new ArrayList<>();
        Path f = dataFolder.resolve("construction").resolve("alerts").resolve(date + ".json");
        try {
            if (!Files.isRegularFile(f)) return out;
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            for (String k : List.of("alerts", "names", "survey"))
                if (o.get(k) instanceof JsonArray a) for (JsonElement e : a) out.add(e.getAsString());
        } catch (Exception ignored) { }
        return out;
    }

    /**
     * The Town Survey page's rows as they may print today, from the register kept in construction/players.json: a row stays
     * only while every builder may still be named (not private, hidden or forgotten), at today's construction.detail. Shaped like an out file
     * ({"construction":{"opening":true,"survey":[...]}}) plus "opened", the opening's date. null before the opening, or when
     * players.json can't be read.
     */
    public static JsonObject register(Path dataFolder, Settings.Construction c, List<String> hidePlayers, LocalDate date) {
        try {
            Path f = dataFolder.resolve("construction").resolve("players.json");
            if (!Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
            String pj = Files.readString(f);
            if (SiteIndex.version(pj) != Prefs.VERSION) return null;
            Prefs prefs = Prefs.read(pj);
            if (prefs.opened == null || prefs.register == null) return null;
            Set<String> hidden = lower(hidePlayers);
            String graceEnd = date.minusDays(1).toString();
            Map<String, String> names = new HashMap<>();
            for (Map.Entry<String, Prefs.P> e : prefs.players.entrySet()) {
                String n = e.getValue().name;
                if (n != null && !hidden.contains(n.toLowerCase(Locale.ROOT)) && Projector.nameable(e.getKey(), prefs, c, graceEnd)) names.put(e.getKey(), n);
            }
            JsonObject cons = new JsonObject();
            cons.addProperty("opening", true);
            cons.add("survey", Projector.surveyRows(prefs.register, names, c.surveyNames(), c.detail()));
            JsonObject out = new JsonObject();
            out.add("construction", cons);
            out.addProperty("opened", prefs.opened);
            return out;
        } catch (java.io.IOException | RuntimeException e) { return null; }
    }

    // ------------------------------------------------------------ the one-time notice
    boolean noticedOrSent(UUID u) {
        Set<UUID> n = noticed;
        return sentThisRun.contains(u) || (n != null && n.contains(u));
    }

    boolean needsNotice(Player p) {
        Settings s = settings;
        return s.features().construction() && noticed != null && !noticedOrSent(p.getUniqueId())
                && !collector.hidden.contains(p.getName().toLowerCase(Locale.ROOT));
    }

    void sendNotice(Player p) {
        sentThisRun.add(p.getUniqueId());
        p.sendMessage(Component.text(noticeText(settings.paper().name(), level(p.getUniqueId(), p.getName())), NamedTextColor.GOLD));
        request(RawLine.request(p.getUniqueId(), "noticed", null, null, null, LocalDate.now(zone(settings)).toString()));
    }

    /** The one-time notice, by the player's level: only a public player is told the paper may write about them. */
    static String noticeText(String paper, String level) {
        if (!"public".equals(level))
            return paper + " writes about players' builds only if they choose it. Nothing about your builds is printed unless you type "
                    + "/news build public. Never where they are.";
        return paper + " may now write about what you build: what kind of build, how big, the materials "
                + "and who helped. Never where it is. Type /news build private to keep your builds out of the paper, or /news build secret "
                + "while standing in one of your builds to hide just that one.";
    }

    // ------------------------------------------------------------ commands
    /** /news build ... (args after "build"). */
    public void command(CommandSender sender, String[] args) { build.command(sender, args); }

    public List<String> tabComplete(String[] args) { return build.tabComplete(args); }

    void request(String line) { io.submit("request", () -> io.appendRequest(line)); }

    List<String> statusLines(UUID u) { return io.status.getOrDefault(u.toString(), List.of()); }

    JavaPlugin plugin() { return plugin; }

    String level(UUID u, String name) { return effectiveLevel(io.levels, u, name, settings.construction()); }

    /** The player's own choice; else public if construction.opted-in names them now; else default-level. */
    static String effectiveLevel(java.util.Map<String, String> levels, UUID u, String name, Settings.Construction c) {
        return Levels.level(levels.get(u.toString()), name, c);
    }

    /** /news build forget and /masthead construction forget: this minute's counts now, the files on the IO thread. */
    void forget(UUID u) {
        mm.dropPlayer(u);
        io.submit("forget", () -> io.forget(u));
    }

    /**
     * /masthead construction [status | clear-name NAME | reset confirm | forget PLAYER]. Pause and resume live in the
     * main class (they change the settings every part of the paper reads).
     */
    public void admin(CommandSender sender, String[] args, boolean configPrint, boolean paused) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "status";
        String rest = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).strip() : "";
        switch (sub) {
            case "clear-name" -> {
                if (rest.isEmpty()) { say(sender, "Usage: /masthead construction clear-name <name>", NamedTextColor.YELLOW); return; }
                io.submit("clear-name", () -> {
                    int n = io.clearName(rest);
                    later(sender, n > 0 ? "Name \"" + rest + "\" taken off (" + n + ")." : "No build has the name \"" + rest + "\".");
                });
            }
            case "reset" -> {
                if (!rest.equalsIgnoreCase("confirm")) {
                    say(sender, "This deletes every building record (for a world rollback); permit numbers are kept. Type /masthead construction reset confirm", NamedTextColor.YELLOW);
                    return;
                }
                flushNow();
                io.submit("reset", () -> { io.reset(); later(sender, "Construction news: building records deleted."); });
            }
            case "forget" -> {
                if (rest.isEmpty()) { say(sender, "Usage: /masthead construction forget <player>", NamedTextColor.YELLOW); return; }
                Player online = Bukkit.getPlayerExact(rest);
                OfflinePlayer op = online != null ? online : Bukkit.getOfflinePlayerIfCached(rest);
                if (op == null) { say(sender, "No player called " + rest + " has been on this server.", NamedTextColor.YELLOW); return; }
                forget(op.getUniqueId());
                say(sender, "Done: " + rest + "'s building records are erased and their builds are private.", NamedTextColor.GREEN);
            }
            default -> status(sender, configPrint, paused);
        }
    }

    private void status(CommandSender sender, boolean configPrint, boolean paused) {
        Settings s = settings;
        String print = paused ? "paused" : configPrint ? "on" : "off (print: false)";
        say(sender, "Construction news: " + (s.features().construction() ? "on" : "off") + ", printing " + print
                + (folderOk ? "" : ", off because web.folder would publish the building records"), NamedTextColor.GOLD);
        if (stoppedBy != null) say(sender, "recorder stopped (" + stoppedBy + "); this Paper version is not supported", NamedTextColor.RED);
        say(sender, countsLine(io.sites, mm.cells, mm.overflow, collector.errors), NamedTextColor.GOLD);
        say(sender, "Last fold: " + (io.lastFoldMs < 0 ? "none since start" : io.lastFoldMs + " ms") + "; last paper: " + io.lastEntries + " entries, "
                + io.lastAlerts + " owner alerts. Raw files: " + io.rawFiles + " (" + (io.rawBytes + 1023) / 1024 + " KB).", NamedTextColor.GOLD);
    }

    static String countsLine(int sites, int cells, long overflow, long errors) {
        return "Builds tracked: " + sites + ". This minute: " + cells + " cells, " + overflow + " not counted since start (over the limit), " + errors + " errors.";
    }

    private static void say(CommandSender to, String text, NamedTextColor color) { to.sendMessage(Component.text(text, color)); }

    /** A reply from the IO thread, sent on the main thread. */
    private void later(CommandSender to, String text) {
        try { Bukkit.getScheduler().runTask(plugin, () -> say(to, text, NamedTextColor.GREEN)); }
        catch (RuntimeException stopping) { /* turned off meanwhile */ }
    }

    // ------------------------------------------------------------ small helpers
    /** web.folder must never be, contain or sit inside construction/ (it would publish the building records). */
    private boolean folderOk(Settings s) {
        Path web = switch (s.web().mode()) {
            case "builtin" -> data.resolve("web");
            case "folder" -> s.web().folder().isEmpty() ? null : data.resolve(s.web().folder());
            default -> null;
        };
        if (web == null) return true;
        Path w = web.toAbsolutePath().normalize(), cons = data.resolve("construction").toAbsolutePath().normalize();
        return !cons.startsWith(w) && !w.startsWith(cons);
    }

    static ZoneId zone(Settings s) {
        return s.schedule().zone().isEmpty() ? ZoneId.systemDefault() : ZoneId.of(s.schedule().zone());
    }

    /** The paper a moment belongs to. */
    static LocalDate editionDate(Settings s) {
        return EditionWindow.dateFor(Instant.now(), s.schedule().time(), zone(s));
    }

    static Set<String> lower(List<String> names) {
        Set<String> out = new HashSet<>();
        for (String n : names) out.add(n.toLowerCase(Locale.ROOT));
        return Set.copyOf(out);
    }
}
