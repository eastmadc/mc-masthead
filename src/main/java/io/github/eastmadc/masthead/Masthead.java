package io.github.eastmadc.masthead;

import io.github.eastmadc.masthead.collect.Blotter;
import io.github.eastmadc.masthead.collect.Recorder;
import io.github.eastmadc.masthead.config.Settings;
import io.github.eastmadc.masthead.config.SettingsLoader;
import io.github.eastmadc.masthead.construction.Construction;
import io.github.eastmadc.masthead.game.InGame;
import io.github.eastmadc.masthead.game.Mailroom;
import io.github.eastmadc.masthead.notify.Concerns;
import io.github.eastmadc.masthead.notify.Notifier;
import io.github.eastmadc.masthead.pipeline.Attempts;
import io.github.eastmadc.masthead.pipeline.EditionJob;
import io.github.eastmadc.masthead.pipeline.Housekeeping;
import io.github.eastmadc.masthead.pipeline.State;
import io.github.eastmadc.masthead.writer.ExchangeWriter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Plugin entry point: settings, recorders, in-game commands, the daily edition schedule. */
public final class Masthead extends JavaPlugin {
    private volatile Settings settings = Settings.defaults();
    private volatile Settings loaded = Settings.defaults();   // as config.yml says (settings = this, minus construction news while paused)
    private final List<String> configProblems = new ArrayList<>();
    private volatile String configBroken = "";   // M1: the line to show while config.yml has a mistake ("" = the file was read)
    private Recorder recorder;
    private Blotter blotter;
    private Mailroom mailroom;
    private InGame game;
    private EditionJob job;
    private Construction construction;
    private Concerns concerns;
    private Object letterLogFilter;   // keeps /letter <text> out of the server log (LetterLogFilter)
    private final AtomicBoolean running = new AtomicBoolean();
    private Settings started = Settings.defaults();   // the settings the recorders and command names were set up with
    private Map<String, List<String>> startedAliases = Map.of();
    // today's tries, also kept in memory in case state.yml cannot be saved (so a broken disk never means a try every 30 s)
    private String memTryDate = "";
    private int memTries;
    private long memTryAt;
    private volatile String memDoneDate = "";   // the day this server published (so an unsaved state.yml never means a second paper)
    private volatile String memToldDate = "";   // the day the owner was told "today's paper failed"

    public Settings settings() { return settings; }

    // ------------------------------------------------------------ lifecycle
    @Override
    public void onEnable() {
        saveDefaultConfig();
        // M1: with a mistake in config.yml nothing is set up (no web server, no recorders, no paper, no join message):
        // default settings would undo the owner's privacy choices. onCommand below answers until a good /masthead reload.
        if (loadSettings()) start();
        else getLogger().severe(brokenLine());
    }

    /** M1: the commands' own executor is set in start(); until then (config.yml had a mistake at server start) they land here. */
    @Override
    public boolean onCommand(CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (command.getName().equals("masthead")) admin(sender, args);
        else sender.sendMessage(Component.text("The paper is off for now.", NamedTextColor.GRAY));
        return true;
    }

    /** M1: what to say while config.yml has a mistake; "stays off" when the server started that way. */
    private String brokenLine() {
        return configBroken + (job == null ? " Masthead stays off until then." : "");
    }

    /** Everything that runs on the settings: at server start, or (M1) on the first /masthead reload that finds a good config.yml. */
    private void start() {
        Settings s = settings;
        started = s;
        startedAliases = aliases();
        recorder = new Recorder(this, s.features().readership());
        Bukkit.getPluginManager().registerEvents(recorder, this);
        if (s.features().blotter()) {
            blotter = new Blotter(this);
            Bukkit.getPluginManager().registerEvents(blotter, this);
        }
        concerns = new Concerns(getDataFolder().toPath(), getLogger(), a -> new Notifier(a, getLogger()));
        mailroom = new Mailroom(this, this::settings, concerns);
        game = new InGame(this, this::settings, mailroom, blotter, this::admin);
        io.github.eastmadc.masthead.game.KnownNames.refresh(this);   // player names for tab complete, read in the background
        Bukkit.getPluginManager().registerEvents(game, this);
        for (String c : List.of("news", "awards", "stats", "letter", "masthead")) {
            PluginCommand pc = getCommand(c);
            if (pc != null) { pc.setExecutor(game); pc.setTabCompleter(game); }
        }
        registerExtraAliases();
        letterLogFilter = io.github.eastmadc.masthead.game.LetterLogFilter.install(commandNames("letter"), commandNames("news"), getLogger());
        job = new EditionJob(this, this::settings, game, concerns);
        construction = new Construction(this);              // its file thread lives until onDisable
        game.construction(construction);
        job.construction(construction);
        construction.apply(s);                              // the recorder only if features.construction; the age sweep always
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {   // a hidden player who joins under a new name stays hidden
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
            public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) { rehide(Map.of(e.getPlayer().getUniqueId().toString(), e.getPlayer().getName())); }
        }, this);
        job.onPublished(this::markDone);                    // the day counts as done the moment its paper is out
        job.start();                                        // web server (builtin mode), QR picture, public URL
        // Start-up clean-up, off the main thread, before the first edition check: old exchange files, files 2.0.0 left with
        // letters in them, private file modes, old facts and stats, worried-letter alerts still waiting.
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Path d = getDataFolder().toPath();
            ExchangeWriter.clearStale(d.resolve("ai"));
            int scrubbed = Housekeeping.scrubOld(d);
            if (scrubbed > 0) getLogger().info("Removed letters and reader names from " + scrubbed + " older file(s).");
            Settings st = settings;
            Housekeeping.prune(d, LocalDate.now(zone()), st.privacy().retentionDays());
            Housekeeping.tighten(d, st.web().mode().equals("folder") && !st.web().folder().isEmpty() ? d.resolve(st.web().folder()).normalize() : null);
            if (concerns.waiting() > 0) concerns.retry(st);
        });
        // Check twice a minute whether today's edition is due (also catches up after the server was down at that time).
        Bukkit.getScheduler().runTaskTimer(this, this::maybeRunEdition, 20L * 20, 20L * 30);
        // Worried-letter alerts that could not be sent are tried again every 10 minutes.
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> { if (concerns.waiting() > 0) concerns.retry(settings); }, 20L * 600, 20L * 600);
        getLogger().info(writerLine());
        getLogger().info(s.paper().name() + " ready: writer " + s.writer().mode() + ", web " + s.web().mode()
                + ", edition at " + s.schedule().time() + ", " + mailroomLine() + (configProblems.isEmpty() ? "" : " (" + configProblems.size() + " config problem(s), see above)"));
    }

    @Override
    public void onDisable() {
        if (running.get()) getLogger().warning("The edition was still being written when the server stopped; it will try again after the restart.");
        if (construction != null) construction.stop();
        if (recorder != null) recorder.flushNow();
        if (blotter != null) blotter.flush();
        if (job != null) job.stop();
        io.github.eastmadc.masthead.game.LetterLogFilter.remove(letterLogFilter);
        letterLogFilter = null;
    }

    /** False when config.yml has a mistake (M1): nothing is loaded, upgraded or rewritten, and the settings in use stay. */
    private boolean loadSettings() {
        configBroken = SettingsLoader.brokenLine(getDataFolder().toPath().resolve("config.yml"));
        if (!configBroken.isEmpty()) return false;
        upgradeConfig();
        reloadConfig();
        configProblems.clear();
        loaded = SettingsLoader.load(getConfig(), m -> { configProblems.add(m); getLogger().warning("config.yml " + m); });
        settings = effective(loaded, getDataFolder().toPath());
        for (Player p : Bukkit.getOnlinePlayers())   // hidden just now while online: the UUID is remembered at once
            settings = follow(settings, getDataFolder().toPath(), List.of(Map.of(p.getUniqueId().toString(), p.getName())), true, getLogger());
        if (settings.writer().mode().equals("anthropic") && SettingsLoader.anthropicRefused())
            getLogger().warning(SettingsLoader.refusedLine(Bukkit.getMinecraftVersion()));
        if (mailroomNeedsChannel()) getLogger().warning(SettingsLoader.MAILROOM_OFF_LINE);
        if (mailroomNeedsWriter()) getLogger().warning(SettingsLoader.MAILROOM_NO_WRITER_LINE);
        return true;
    }

    /** T17: settings a newer version added go into the owner's config.yml, with their comments, after a private backup. */
    private void upgradeConfig() {
        try (var in = getResource("config.yml")) {
            if (in == null) return;
            var u = SettingsLoader.upgradeFile(getDataFolder().toPath().resolve("config.yml"), new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            if (u != null) getLogger().info("config.yml: added " + String.join(", ", u.added()) + " (with their comments). The old file is saved as "
                    + u.backup().getFileName() + "; it holds your API key and alert addresses, so delete it once you're happy.");
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            // broken YAML: left exactly as it is (loadSettings stops before this)
        } catch (IOException | RuntimeException e) {
            getLogger().warning("config.yml could not be upgraded (" + e.getClass().getSimpleName() + "); nothing was changed, defaults apply to missing settings");
        }
    }

    /**
     * The settings in effect: construction news prints nothing while "masthead construction pause" is on, and
     * privacy.hide-players also holds every later name of a hidden player (both kept in state.yml).
     */
    public static Settings effective(Settings fromConfig, Path dataFolder) {
        YamlConfiguration st = State.load(dataFolder);
        return hideRenamed(fromConfig, st).constructionPaused(st.getBoolean(Construction.PAUSE_KEY, false));
    }

    /** state.yml: UUID -> the names a hidden player has had. Never written to config.yml. */
    public static final String HIDDEN_UUIDS = "hidden-uuids";

    /**
     * privacy.hide-players plus the other names of every remembered UUID, as long as one of its names is still in the list
     * (taking the name out of config.yml un-hides the player, as before). Every name check reads this one list.
     */
    static Settings hideRenamed(Settings s, YamlConfiguration st) {
        org.bukkit.configuration.ConfigurationSection known = st.getConfigurationSection(HIDDEN_UUIDS);
        if (known == null) return s;   // nothing remembered yet (a state.yml written before 1.0 has no such key)
        List<String> listed = s.privacy().hidePlayers(), hide = new ArrayList<>(listed);
        for (String uuid : known.getKeys(false)) {
            List<String> names = known.getStringList(uuid);
            if (names.stream().anyMatch(n -> has(listed, n))) for (String n : names) if (!has(hide, n)) hide.add(n);
        }
        if (hide.size() == listed.size()) return s;
        return new Settings(s.paper(), s.tone(), s.owner(), new Settings.Privacy(List.copyOf(hide), s.privacy().neverMention(), s.privacy().retentionDays()), s.schedule(),
                s.writer(), s.web(), s.features(), s.alerts(), s.construction());
    }

    private static boolean has(List<String> names, String name) {
        return names.stream().anyMatch(name::equalsIgnoreCase);
    }

    /**
     * Players that stats snapshots (oldest first) or a join show, each UUID -> name. Remembers the UUID behind each hidden
     * name; when a remembered UUID shows a new name, that name is hidden too and the console says so. save = false (a
     * preview) keeps nothing. Returns s with the new names hidden (s itself when nothing changed).
     */
    public static Settings follow(Settings s, Path dataFolder, List<Map<String, String>> seen, boolean save, java.util.logging.Logger log) {
        synchronized (State.LOCK) {
            YamlConfiguration st = State.load(dataFolder);
            List<String> hide = s.privacy().hidePlayers();
            Map<String, Object> changed = new LinkedHashMap<>();
            for (Map<String, String> shown : seen) shown.forEach((uuid, name) -> {
                if (!uuid.matches("[0-9a-f-]{36}")) return;
                List<String> names = new ArrayList<>(st.getStringList(HIDDEN_UUIDS + "." + uuid));
                boolean hidden = names.stream().anyMatch(n -> has(hide, n));
                if (has(names, name) || !(hidden || has(hide, name))) return;
                if (hidden && !has(hide, name)) log.warning("hidden player " + names.get(names.size() - 1) + " is now called " + name + "; still hidden. Add " + name + " to privacy.hide-players.");
                names.add(name);
                st.set(HIDDEN_UUIDS + "." + uuid, names);
                changed.put(HIDDEN_UUIDS + "." + uuid, names);
            });
            if (save && !changed.isEmpty() && !State.setAll(dataFolder, changed)) log.warning("state.yml could not be saved");
            return hideRenamed(s, st);
        }
    }

    /** Main thread. A hidden player's new name was seen (a join, or the snapshot of the paper just written): in game they stay hidden too. */
    private void rehide(Map<String, String> seen) {
        Settings s = follow(settings, getDataFolder().toPath(), List.of(seen), true, getLogger());
        if (s == settings) return;
        settings = s;
        construction.apply(s);   // the building recorder keeps its own copy of the list
    }

    /** features.mailroom is on, but there is no alert channel, so the mailroom stays closed. */
    private boolean mailroomNeedsChannel() {
        return SettingsLoader.mailroomWanted(getConfig()) && !settings.alerts().hasChannel();
    }

    /** features.mailroom is on, but no working AI writer is set up, so the mailroom stays closed. */
    private boolean mailroomNeedsWriter() {
        return SettingsLoader.mailroomWanted(getConfig()) && !SettingsLoader.noWriterReason(settings.writer(), System.getenv("ANTHROPIC_API_KEY")).isEmpty();
    }

    private String writerLine() {
        return SettingsLoader.writerLine(settings.writer(), System.getenv("ANTHROPIC_API_KEY"));
    }

    private String mailroomLine() {
        if (settings.features().mailroom()) return "Mailroom: open";
        boolean channel = mailroomNeedsChannel(), writer = mailroomNeedsWriter();
        if (channel && writer) return "Mailroom: off (needs an alert channel and an AI writer)";
        if (channel) return "Mailroom: off (needs an alert channel)";
        if (writer) return "Mailroom: off (needs an AI writer)";
        return "Mailroom: off";
    }

    /**
     * commands.<name>: [extra, names] in config.yml -> more labels for the same command (for servers where /news etc. are
     * taken, or for a server's own short names). The jar itself has no extra names: /masthead:<command> always works.
     */
    private void registerExtraAliases() {
        for (String base : List.of("news", "letter", "awards", "stats", "masthead", "build")) {
            boolean build = base.equals("build");   // commands.build: [permit] -> /permit is /news build
            PluginCommand target = getCommand(build ? "news" : base);
            if (target == null) continue;
            for (String alias : getConfig().getStringList("commands." + base)) {
                String a = alias.trim().toLowerCase(java.util.Locale.ROOT).replaceFirst("^/", "");
                if (!a.matches("[a-z0-9_-]{2,32}")) { getLogger().warning("commands." + base + ": '" + alias + "' isn't a valid command name"); continue; }
                org.bukkit.command.Command c = new org.bukkit.command.Command(a, target.getDescription(), "/" + a, List.of()) {
                    @Override public boolean execute(CommandSender sender, String label, String[] args) {
                        if (!target.testPermission(sender)) return true;
                        return game.onCommand(sender, target, label, build ? withBuild(args) : args);
                    }
                    @Override public List<String> tabComplete(CommandSender sender, String label, String[] args) {
                        List<String> r = game.onTabComplete(sender, target, label, build ? withBuild(args) : args);
                        return r == null ? List.of() : r;
                    }
                };
                c.setPermission(target.getPermission());
                if (!Bukkit.getCommandMap().register(getName().toLowerCase(java.util.Locale.ROOT), c))
                    getLogger().warning("/" + a + " is already taken by another plugin; use /" + getName().toLowerCase(java.util.Locale.ROOT) + ":" + a);
            }
        }
    }

    /** commands.<name> lists in config.yml, as they are now. */
    private Map<String, List<String>> aliases() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (String base : List.of("news", "letter", "awards", "stats", "masthead", "build")) m.put(base, getConfig().getStringList("commands." + base));
        return m;
    }

    /**
     * Settings that are only read at start-up: the Police Blotter and readership recorders are set up then, and so are
     * the extra command names. /masthead reload compares them with what the server started with, and says so instead of
     * quietly ignoring a change.
     */
    public static List<String> restartNeeded(Settings before, Settings after, Map<String, List<String>> aliasesBefore, Map<String, List<String>> aliasesAfter) {
        List<String> out = new ArrayList<>();
        if (before.features().blotter() != after.features().blotter()) out.add("features.blotter");
        if (before.features().readership() != after.features().readership()) out.add("features.readership");
        if (!aliasesBefore.equals(aliasesAfter)) out.add("commands");
        return out;
    }

    /** /masthead reload: a paper already out still names a player hidden just now. The line that says so, or "" when no name was added. */
    public static String newlyHidden(Settings before, Settings after) {
        for (String n : after.privacy().hidePlayers())
            if (!has(before.privacy().hidePlayers(), n)) return "Newly hidden players stay in today's paper and Player Cards until the next paper. Run /masthead now to take them off now.";
        return "";
    }

    private static String[] withBuild(String[] args) {
        String[] a = new String[args.length + 1];
        a[0] = "build";
        System.arraycopy(args, 0, a, 1, args.length);
        return a;
    }

    /** Every name a command answers to (lower case): its own, plugin.yml aliases, and commands.<name> in config.yml. */
    private java.util.Set<String> commandNames(String base) {
        java.util.Set<String> out = new java.util.HashSet<>();
        out.add(base);
        PluginCommand c = getCommand(base);
        if (c != null) c.getAliases().forEach(a -> out.add(a.toLowerCase(java.util.Locale.ROOT)));
        for (String alias : getConfig().getStringList("commands." + base)) {
            String a = alias.trim().toLowerCase(java.util.Locale.ROOT).replaceFirst("^/", "");
            if (a.matches("[a-z0-9_-]{2,32}")) out.add(a);
        }
        return out;
    }

    // ------------------------------------------------------------ schedule
    private ZoneId zone() {
        String z = settings.schedule().zone();
        return z.isEmpty() ? ZoneId.systemDefault() : ZoneId.of(z);
    }

    /** The scheduled run: a day is done only once its paper is published; failed tries are retried (see Attempts). */
    private void maybeRunEdition() {
        ZonedDateTime now = ZonedDateTime.now(zone());
        if (now.toLocalTime().isBefore(settings.schedule().time()) || running.get()) return;
        LocalDate today = now.toLocalDate();
        YamlConfiguration st = state();
        String day = today.toString(), tryDate = st.getString("edition-attempt-date", "");
        int tries = tryDate.equals(day) ? st.getInt("edition-attempts", 0) : 0;
        long at = tryDate.equals(day) ? st.getLong("edition-attempt-at", 0) : 0;
        if (memTryDate.equals(day) && memTries > tries) { tries = memTries; at = Math.max(at, memTryAt); tryDate = day; }
        String last = Attempts.lastEdition(st.getString("last-edition", ""), memDoneDate, day);
        switch (Attempts.next(day, last, tryDate, tries, at, System.currentTimeMillis() / 1000)) {
            case RUN -> runEdition(today, true, null, tries + 1, null);
            case PLAIN -> runEdition(today, true, null, tries + 1, "today's paper fell back to the plain edition after " + Attempts.TRIES
                    + " failed tries (" + st.getString("edition-last-error", "a try was cut off, maybe by a restart") + ")");
            case GAVE_UP -> {   // the plain try was cut off (a restart or crash), so nobody was told yet: tell the owner once
                if (Attempts.tellGaveUp(day, st.getString("edition-failed-told", ""), memToldDate)) {
                    String why = "the plain edition was cut off, maybe by a restart (earlier: " + st.getString("edition-last-error", "none") + ")";
                    toldFailed(day);
                    getLogger().severe("Today's paper failed: " + why + ". Players still see the last paper. Fix the problem, then run /masthead now.");
                    Bukkit.getScheduler().runTaskAsynchronously(this, () -> job.failed(today, why));
                }
            }
            default -> { }
        }
    }

    /** The day's paper is published: never print it again today (memory first, in case state.yml cannot be saved). */
    private void markDone(LocalDate date) {
        memDoneDate = date.toString();
        if (!State.set(getDataFolder().toPath(), "last-edition", date.toString())) getLogger().warning("state.yml could not be saved");
    }

    private void toldFailed(String day) {
        memToldDate = day;
        if (!State.set(getDataFolder().toPath(), "edition-failed-told", day)) getLogger().warning("state.yml could not be saved");
    }

    /** /masthead now and preview: runs (or re-runs) an edition off the main thread. publish=false = preview only. */
    void runEdition(LocalDate date, boolean publish, CommandSender who) {
        runEdition(date, publish, who, 0, null);
    }

    /** try = 1..3 for a scheduled try, Attempts.TRIES + 1 for the plain one, 0 for an op's /masthead now or preview. */
    private void runEdition(LocalDate date, boolean publish, CommandSender who, int attempt, String plainBecause) {
        if (!running.compareAndSet(false, true)) {
            if (who != null) who.sendMessage(Component.text("An edition is already being written.", NamedTextColor.GRAY));
            return;
        }
        if (attempt > 0) {   // noted before the run: a crash or restart mid-run counts as a failed try
            long now = System.currentTimeMillis() / 1000;
            memTryDate = date.toString(); memTries = attempt; memTryAt = now;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("edition-attempt-date", date.toString()); m.put("edition-attempts", attempt); m.put("edition-attempt-at", now);
            if (attempt == 1) m.put("edition-last-error", null);   // a new day: forget yesterday's error
            if (!State.setAll(getDataFolder().toPath(), m)) getLogger().warning("state.yml could not be saved");
        }
        if (blotter != null) blotter.refreshHomes();               // offline players' spawn points, read in the background
        try {
            job.prepareOnMainThread(date);                         // save online players' stats, find the stats folder
        } catch (RuntimeException e) {
            getLogger().warning("could not save players' stats first: " + e);
        }
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            String result;
            boolean ok = false;
            try {
                result = job.run(date, publish, plainBecause);
                ok = true;
                if (publish) markDone(date);   // done already by onPublished; harmless again
            } catch (Throwable t) {
                String why = Attempts.reason(t);
                result = "FAILED: " + why;
                getLogger().severe("edition " + date + " failed: " + t);
                if (attempt > 0) {
                    long now = System.currentTimeMillis() / 1000;
                    memTryAt = now;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("edition-attempt-at", now); m.put("edition-last-error", why);
                    State.setAll(getDataFolder().toPath(), m);
                    if (attempt < Attempts.TRIES) getLogger().warning("Today's paper: try " + attempt + " of " + Attempts.TRIES + " failed; trying again in about 10 minutes.");
                    else if (attempt == Attempts.TRIES) getLogger().warning("Today's paper: " + Attempts.TRIES + " tries failed; publishing the plain edition.");
                    else {
                        getLogger().severe("Today's paper failed, the plain edition too. Players still see the last paper. Fix the problem above, then run /masthead now.");
                        toldFailed(date.toString());
                        job.failed(date, why);
                    }
                }
            } finally { running.set(false); }
            final String r = result;
            if (ok) getLogger().info("edition " + date + ": " + r);
            if (ok) try { Bukkit.getScheduler().runTask(this, () -> rehide(Map.of())); } catch (RuntimeException ignored) { }   // a new name the snapshot showed
            if (who != null) {
                try { Bukkit.getScheduler().runTask(this, () -> who.sendMessage(Component.text("Edition " + date + ": " + r, NamedTextColor.GREEN))); }
                catch (RuntimeException ignored) { }   // the plugin was turned off meanwhile
            }
        }, 20L);   // one second later, so the stats files are written
    }

    // ------------------------------------------------------------ /masthead (ops)
    /** The one line for a word /masthead does not know. */
    public static final String USAGE = "Use: /masthead status [share] | reload | now | preview | selftest | construction";

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("masthead.admin")) { sender.sendMessage(Component.text("Ops only.", NamedTextColor.RED)); return; }
        String sub = args.length > 0 ? args[0].toLowerCase(java.util.Locale.ROOT) : "status";
        if (job == null && !sub.equals("reload")) {   // M1: the server started with a mistake in config.yml
            sender.sendMessage(Component.text(brokenLine(), NamedTextColor.RED));
            return;
        }
        switch (sub) {
            case "reload" -> {
                Settings before = job == null ? null : settings;   // null: the paper was off (M1), so no name is "newly" hidden
                if (!loadSettings()) {   // M1: the settings in use stay and the web server is not restarted
                    sender.sendMessage(Component.text(brokenLine(), NamedTextColor.RED));
                    if (sender instanceof Player) getLogger().severe(brokenLine());
                    return;
                }
                if (job == null) start();   // M1: the first good config.yml since the server started
                else {
                    construction.apply(settings);   // never doubles the recorder or its timer
                    game.forceReload();
                    job.start();
                }
                sender.sendMessage(Component.text("Reloaded. " + (configProblems.isEmpty() ? "Settings OK." : configProblems.size() + " problem(s):"), NamedTextColor.GREEN));
                configProblems.forEach(p -> sender.sendMessage(Component.text(" - " + p, NamedTextColor.YELLOW)));
                sender.sendMessage(Component.text(writerLine(), NamedTextColor.GOLD));   // as in status: "no API key set" is seen at once
                String hid = before == null ? "" : newlyHidden(before, settings);
                if (!hid.isEmpty()) {
                    sender.sendMessage(Component.text(hid, NamedTextColor.YELLOW));
                    if (sender instanceof Player) getLogger().warning(hid);
                }
                List<String> restart = restartNeeded(started, settings, startedAliases, aliases());
                if (!restart.isEmpty()) {
                    String line = "Restart the server for these changes to take effect: " + String.join(", ", restart) + ".";
                    sender.sendMessage(Component.text(line, NamedTextColor.YELLOW));
                    if (sender instanceof Player) getLogger().warning(line);
                }
            }
            case "now" -> runEdition(LocalDate.now(zone()), true, sender);
            case "preview" -> runEdition(LocalDate.now(zone()), false, sender);
            case "selftest" -> game.selftestPublic(sender);
            case "construction" -> construction(sender, java.util.Arrays.copyOfRange(args, 1, args.length));
            case "status" -> status(sender, args.length > 1 && args[1].equalsIgnoreCase("share"));
            default -> sender.sendMessage(Component.text(USAGE, NamedTextColor.YELLOW));
        }
    }

    /** /masthead construction ...: pause and resume here (they change what every part of the paper reads), the rest in Construction. */
    private void construction(CommandSender sender, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(java.util.Locale.ROOT) : "status";
        boolean paused = State.load(getDataFolder().toPath()).getBoolean(Construction.PAUSE_KEY, false);
        if (sub.equals("pause") || sub.equals("resume")) {
            boolean pause = sub.equals("pause");
            if (!State.set(getDataFolder().toPath(), Construction.PAUSE_KEY, pause ? true : null)) {
                sender.sendMessage(Component.text("state.yml could not be saved; nothing changed.", NamedTextColor.RED));
                return;
            }
            settings = effective(loaded, getDataFolder().toPath());
            sender.sendMessage(Component.text(pause ? "Construction news paused: nothing about building prints until \"masthead construction resume\"."
                    : "Construction news resumed: building news prints from the next paper." + (loaded.construction().print() ? "" : " (config.yml still has construction print: false.)"),
                    NamedTextColor.GREEN));
            return;
        }
        construction.admin(sender, args, loaded.construction().print(), paused);
    }

    /** The web part of /masthead status: the mode, then the address (share: only whether one is set). */
    public static String webStatus(Settings s, boolean share, String url) {
        if (share) return s.web().mode() + (s.web().publicUrl().isEmpty() ? " (public-url not set)" : " (public-url set)");
        return url.isEmpty() ? s.web().mode() : s.web().mode() + " " + url;
    }

    /**
     * /masthead status [share]. share (T26): the same report to paste in a bug report, starting with the plugin and server versions,
     * with no web address, no player names and no alert addresses (the API key and each alert channel only "set"/"not set").
     * Worried letters: the console shows only the count. In game an admin sees the names (last 7 days)
     * only when Discord is the only alert channel (its push has no name); otherwise the names already went to the owner,
     * so the count is shown with a pointer to the owner's alert channel (Concerns.namesInGame).
     */
    private void status(CommandSender sender, boolean share) {
        Settings s = settings;
        YamlConfiguration st = state();
        if (share) sender.sendMessage(Component.text("Masthead " + getPluginMeta().getVersion() + ", " + Bukkit.getName() + " " + Bukkit.getVersion(), NamedTextColor.GOLD));
        if (!configBroken.isEmpty()) sender.sendMessage(Component.text(configBroken, NamedTextColor.RED));   // M1: the last reload was refused
        String web = webStatus(s, share, game.publicUrl);
        sender.sendMessage(Component.text(s.paper().name() + ": writer " + s.writer().mode() + ", web " + web
                + ", next edition " + s.schedule().time() + ", last " + st.getString("last-edition", "never")
                + ", today's edition " + game.todayDateForStatus() + (running.get() ? " (writing now)" : ""), NamedTextColor.GOLD));
        sender.sendMessage(Component.text(writerLine(), NamedTextColor.GOLD));
        // not while a paper is being written: a request under way is unsettled until its answer is in
        String ledger = running.get() || !List.of("anthropic", "openai-compatible").contains(s.writer().mode()) ? null
                : new io.github.eastmadc.masthead.writer.Spend(getDataFolder().toPath().resolve("data").resolve("spend.json"), s.writer().monthlyBudgetUsd(), null).locked();
        if (ledger != null) sender.sendMessage(Component.text(ledger, NamedTextColor.RED));
        if (share) {
            var a = s.alerts();
            sender.sendMessage(Component.text("API key: " + SettingsLoader.apiKeyStatus(s.writer(), System.getenv("ANTHROPIC_API_KEY"), System.getenv("MASTHEAD_AI_KEY"))
                    + "; alerts: ntfy " + (a.ntfyUrl().isBlank() ? "not set" : a.ntfyOk() ? "set" : "set, but not a valid http(s) address")
                    + ", Discord " + (a.discordWebhook().isBlank() ? "not set" : a.discordOk() ? "set" : "set, but not a valid http(s) address")
                    + ", helper pushes worried letters " + (a.helperPushesConcerns() ? "on" : "off"), NamedTextColor.GOLD));
        }
        sender.sendMessage(Component.text(mailroomLine(), s.features().mailroom() ? NamedTextColor.GOLD : NamedTextColor.YELLOW));
        String today = LocalDate.now(zone()).toString();
        if (today.equals(st.getString("edition-attempt-date", "")) && !today.equals(st.getString("last-edition", "")) && !st.getString("edition-last-error", "").isEmpty())
            sender.sendMessage(Component.text("Today's paper: " + Math.min(st.getInt("edition-attempts", 0), Attempts.TRIES + 1) + " tr(ies) so far, last error: "
                    + (share ? SettingsLoader.shareSafe(st.getString("edition-last-error", ""), s) : st.getString("edition-last-error", "")), NamedTextColor.YELLOW));
        boolean names = Concerns.namesInGame(s.alerts()), showNames = names && sender instanceof Player && !share;
        String whoLine = names ? " An admin sees who with /masthead status in game." : " The names went to the owner's alert channel.";
        List<String> worried = concerns.recentNames(LocalDate.now());
        if (!worried.isEmpty()) {
            if (showNames) sender.sendMessage(Component.text("Worried letters (last 7 days, never printed): " + String.join(", ", worried), NamedTextColor.AQUA));
            else sender.sendMessage(Component.text("Worried letters (last 7 days, never printed): " + worried.size() + "." + whoLine, NamedTextColor.AQUA));
        }
        Map<String, Integer> held = mailroom.heldAlerts();
        if (!held.isEmpty()) {
            int more = held.values().stream().mapToInt(Integer::intValue).sum();
            if (showNames) {
                List<String> who = new ArrayList<>();
                held.forEach((n, k) -> who.add(n + " (" + k + " more)"));
                sender.sendMessage(Component.text("Worried letters today not pushed again (limit " + Mailroom.ALERTS_PER_DAY + " a player a day): "
                        + String.join(", ", who), NamedTextColor.AQUA));
            } else sender.sendMessage(Component.text(more + " more worried letter(s) today were not pushed again (limit " + Mailroom.ALERTS_PER_DAY
                    + " a player a day)." + whoLine, NamedTextColor.AQUA));
        }
        int waiting = concerns.waiting();
        if (waiting > 0) sender.sendMessage(Component.text(waiting + " worried-letter alert(s) waiting to be sent (tried again every 10 minutes).", NamedTextColor.RED));
        configProblems.forEach(p -> sender.sendMessage(Component.text(" config: " + (share ? SettingsLoader.shareSafe(p, s) : p), NamedTextColor.YELLOW)));
        if (share) sender.sendMessage(Component.text("Before posting: check for player names and server addresses, and never paste config.yml (it holds your API key and alert addresses).", NamedTextColor.GRAY));
    }

    // ------------------------------------------------------------ small state file (not the owner's config)
    YamlConfiguration state() { return State.load(getDataFolder().toPath()); }
}
