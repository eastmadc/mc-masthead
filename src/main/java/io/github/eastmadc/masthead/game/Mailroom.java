package io.github.eastmadc.masthead.game;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import io.github.eastmadc.masthead.notify.Concerns;
import io.github.eastmadc.masthead.pipeline.Housekeeping;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The Mailroom: players write to the editor with /letter (a pop-up form; /letter <text> and /letter tip <text>
 * also work, and {@link LetterLogFilter} keeps their words out of the server log).
 * Letters go to plugins/Masthead/mailroom/YYYY-MM-DD.jsonl for the daily edition, where an AI editor decides what runs and the plugin checks
 * it. Nothing a player writes is ever shown to other players from here. Player text is only ever shown with Component.text (plain).
 * Rules: 1 letter + 1 tip per player per day (a new one replaces the old, at most {@link #PLAYER_CAP} sends a day), 60 s cooldown,
 * 30 per server per day, 200 characters,
 * no long digit runs / links / @handles (those are never stored). Files are deleted after 7 days.
 * A letter with worried words (MailDesk.WELLBEING) is stored with "wb":true, never printed, and reported to the owner at
 * once through {@link Concerns} (the player's name and a fixed sentence only).
 * The mailroom only opens when there is an alert channel (Settings.Alerts.hasChannel), so a worried letter reaches a person.
 * A worried letter that is not stored (mailroom closed, cooldown, a check failed, a hidden player) still reaches the owner
 * when an alert channel exists, and the player still gets the 988 line.
 * Worried-letter pushes per player: the first one of the day always goes at once; after that at most one a minute and
 * {@link #ALERTS_PER_DAY} a day. Later ones are counted and shown in /masthead status (review round 2: one player could
 * otherwise fill the owner's phone). A stored one that was not pushed is still reported at the next edition.
 */
public final class Mailroom {
    static final int KEEP_DAYS = 7, MAX_LEN = 200, SERVER_CAP = 30, PLAYER_CAP = 6;
    static final long FILE_CAP = 64 * 1024, COOLDOWN_MS = 60_000;
    public static final int ALERTS_PER_DAY = 3;
    private static final Pattern DIGITS = Pattern.compile("\\d{7,}");
    private static final Pattern LINKISH = Pattern.compile("(?i)(@|https?:|www\\.|\\.(com|net|org|gg|io|tv)\\b|discord\\s*\\.\\s*gg)");
    private static final Pattern WELLBEING = io.github.eastmadc.masthead.edition.MailDesk.WELLBEING;
    /** What /letter says when the mailroom is off (also when it is off for want of an alert channel). */
    public static final String CLOSED = "The mailroom is closed on this server.";
    /** What /letter says to a player on privacy.hide-players (the paper never names them, so it never prints their letters). */
    public static final String NO_WRITE = "You can't send letters here.";
    public static final String HIDDEN = "Your name is kept out of the paper on this server, so the mailroom can't take your letters.";
    /** What a player is told when a worried letter is stored: it is never printed, so never promise the paper. */
    public static final String PRIVATE = "Got it. This one stays private: it won't be printed.";
    public static final String HELP = "If you're going through something real, talk to someone you trust, or call or text 988 any time. It's free.";
    private static final SecureRandom RNG = new SecureRandom();

    public record Printed(String name, String kind, String date) {}

    private final File dataFolder;
    private final java.util.logging.Logger log;
    private final java.util.function.Consumer<Runnable> async;
    private final java.util.function.Supplier<io.github.eastmadc.masthead.config.Settings> settings;
    private final Concerns concerns;
    private final File dir;
    private final Map<UUID, Long> lastSent = new HashMap<>();
    private final Map<UUID, Alerts> alerts = new HashMap<>();   // worried-letter pushes today, per player
    private String alertDay = "";
    private final Set<String> sentToday = new HashSet<>();   // "name\tkind" seen in today's file
    private final Map<String, Integer> linesToday = new HashMap<>();   // name -> that player's lines in today's file
    private String countDay = "";
    private final Set<String> notified = new HashSet<>();     // "date name kind" already told "your letter ran"
    private final Object fileLock = new Object();

    /** One player's worried-letter pushes today: sent, held back (over the limit), and when the last one went. */
    private static final class Alerts { String name; int sent, held; long lastAt; }

    public Mailroom(org.bukkit.plugin.java.JavaPlugin plugin, java.util.function.Supplier<io.github.eastmadc.masthead.config.Settings> settings,
                    Concerns concerns) {
        this(plugin.getDataFolder(), plugin.getLogger(), r -> Bukkit.getScheduler().runTaskAsynchronously(plugin, r), settings, concerns);
    }

    /** For tests: no server. async runs file writes and alerts (a test can run them at once). */
    Mailroom(File dataFolder, java.util.logging.Logger log, java.util.function.Consumer<Runnable> async,
             java.util.function.Supplier<io.github.eastmadc.masthead.config.Settings> settings, Concerns concerns) {
        this.dataFolder = dataFolder;
        this.log = log;
        this.async = async;
        this.settings = settings;
        this.concerns = concerns;
        this.dir = new File(dataFolder, "mailroom");
        if (!dir.isDirectory() && !dir.mkdirs()) log.warning("could not create the mailroom folder");
        Housekeeping.privateDir(dir.toPath());
        loadNotified();
        prune();
    }

    public boolean enabled() { return settings.get().features().mailroom(); }

    /** Show the [mail the editor] button: the mailroom is open and this player may write (no message, unlike mayWrite). */
    public boolean offeredTo(CommandSender s) { return enabled() && s.hasPermission("masthead.write"); }

    // ------------------------------------------------------------ the form
    public void open(Player p) {
        if (!enabled()) { p.sendMessage(Component.text(CLOSED, NamedTextColor.GRAY)); return; }
        // the cooldown is checked before the player types a letter that cannot be taken. A hidden player still gets the
        // form: their letter never runs, but a worried one reaches the owner (submit)
        Long last = lastSent.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < COOLDOWN_MS) { p.sendMessage(Component.text("The mail carrier just left. Try again in a minute.", NamedTextColor.GRAY)); return; }
        p.showDialog(dialog());
    }

    Dialog dialog() {
        return Dialog.create(f -> f.empty()
                .base(DialogBase.builder(Component.text(settings.get().paper().name() + ": Mailroom", NamedTextColor.GOLD))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                "Letters and tips can be printed in " + settings.get().paper().name() + ", a web page other people can open. A robot editor picks "
                                + "what runs. Letters may be shortened, never reworded; news tips may be " + (settings.get().paper().british() ? "summarised" : "summarized") + " without player names. The server's owners can read anything sent here. Game stuff only: no real "
                                + "names, schools, socials, phone numbers or coordinates. Be nice: mean letters go in the bin. Letters are deleted after 7 days."), 300)))
                        .inputs(List.of(
                                DialogInput.singleOption("kind", Component.text("Kind"), List.of(
                                        OptionEntry.create("letter", Component.text("Ask or tell the editor"), true),
                                        OptionEntry.create("tip", Component.text("News tip for the editor"), false))).build(),
                                DialogInput.text("text", Component.text("Your letter or tip (200 characters)")).maxLength(MAX_LEN).width(300)
                                        .multiline(TextDialogInput.MultilineOptions.create(5, 90)).build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("Send to editor", NamedTextColor.GREEN))
                                .action(DialogAction.customClick((DialogResponseView v, Audience who) -> {
                                    if (who instanceof Player pl && mayWrite(pl)) {   // the permission can be revoked while the form is open
                                        String kind = v.getText("kind"), text = v.getText("text");
                                        submit(pl, "tip".equals(kind) ? "tip" : "letter", text == null ? "" : text);
                                    }
                                }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(15)).build()))
                                .build(),
                        ActionButton.builder(Component.text("Never mind")).build())));
    }

    /** /letter, /letter <text>, /letter tip <text>. */
    public void command(CommandSender sender, String[] args) {
        if (!mayWrite(sender)) return;
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
        if (args.length == 0) { open(p); return; }
        boolean tip = args[0].equalsIgnoreCase("tip");
        String text = String.join(" ", tip ? java.util.Arrays.copyOfRange(args, 1, args.length) : args);
        if (text.isBlank()) { open(p); return; }
        submit(p, tip ? "tip" : "letter", text);
    }

    /** /letter is gated by plugin.yml, but /news write and the open form are not: every route checks here. */
    static boolean mayWrite(CommandSender s) {
        if (s.hasPermission("masthead.write")) return true;
        s.sendMessage(Component.text(NO_WRITE, NamedTextColor.RED));
        return false;
    }

    // ------------------------------------------------------------ storing a letter (main thread)
    void submit(Player p, String kind, String raw) {
        submit(p.getName(), p.getUniqueId(), kind, raw, p::sendMessage);
    }

    /**
     * The checks, then store. A worried letter reaches the owner and the player gets the 988 line whatever the checks
     * say: a stored one is reported by store(), one that was not stored is reported here.
     */
    void submit(String name, UUID uuid, String kind, String raw, java.util.function.Consumer<Component> tell) {
        boolean worried = WELLBEING.matcher(raw).find() || WELLBEING.matcher(clean(raw)).find();
        long now = System.currentTimeMillis();
        Long last = lastSent.get(uuid);
        String r;
        NamedTextColor color = NamedTextColor.RED;
        boolean channel = concerns != null && settings.get().alerts().hasChannel();
        boolean push = worried && channel && alertAllowed(name, uuid, now);
        if (!enabled()) { r = "!" + CLOSED; color = NamedTextColor.GRAY; }
        else if (hidden(name)) { r = "!" + HIDDEN; color = NamedTextColor.GRAY; }
        else if (last != null && now - last < COOLDOWN_MS) { r = "!The mail carrier just left. Try again in a minute."; color = NamedTextColor.GRAY; }
        else r = store(name, kind, raw, push);
        if (r.startsWith("!")) {
            tell.accept(Component.text(r.substring(1), color));
            if (push) reportUnstored(name, raw);
        } else {
            lastSent.put(uuid, now);
            tell.accept(Component.text(worried ? PRIVATE : kind.equals("tip")
                    ? "Your tip is in the editor's mailbox. If selected, it runs in tomorrow's paper without player names."
                    : "Sealed and dropped in the mailbox. If the editor picks it, it runs in tomorrow's paper.", NamedTextColor.GREEN));
            if (r.equals("replaced") && !worried)
                tell.accept(Component.text("Your new " + (kind.equals("tip") ? "tip" : "letter") + " replaced today's. The editor only reads one per customer.", NamedTextColor.GRAY));
        }
        if (worried) tell.accept(Component.text(HELP, NamedTextColor.AQUA));
    }

    private boolean hidden(String name) {
        for (String h : settings.get().privacy().hidePlayers()) if (h.equalsIgnoreCase(name)) return true;
        return false;
    }

    /**
     * May this worried letter push the owner now? The first one of the day from a player always may. After that: at most
     * one a minute and ALERTS_PER_DAY a day per player; the rest are counted for /masthead status. Stored and not-stored
     * letters share the count. Main thread.
     */
    synchronized boolean alertAllowed(String name, UUID uuid, long now) {
        String day = LocalDate.now().toString();
        if (!day.equals(alertDay)) { alertDay = day; alerts.clear(); }
        Alerts a = alerts.computeIfAbsent(uuid, k -> new Alerts());
        a.name = name;
        if (a.sent == 0 || (a.sent < ALERTS_PER_DAY && now - a.lastAt >= COOLDOWN_MS)) {
            a.sent++;
            a.lastAt = now;
            return true;
        }
        a.held++;
        return false;
    }

    /** For /masthead status: players whose later worried letters today were not pushed (name -> how many). */
    public synchronized Map<String, Integer> heldAlerts() {
        Map<String, Integer> out = new java.util.TreeMap<>();
        if (!LocalDate.now().toString().equals(alertDay)) return out;
        for (Alerts a : alerts.values()) if (a.held > 0) out.merge(Concerns.safeName(a.name), a.held, Integer::sum);
        return out;
    }

    /** A worried letter that was not stored: to the owner anyway (alertAllowed said yes, and there is a channel). */
    private void reportUnstored(String name, String raw) {
        var s = settings.get();
        String id = LocalDate.now() + "-" + String.format("%08x", RNG.nextInt());
        String text = clean(raw);
        String capped = text.length() > MAX_LEN ? text.substring(0, MAX_LEN) : text;
        async.accept(() -> concerns.report(s, new Concerns.Letter(id, name, "worried words", capped)));
    }

    /**
     * Checks and stores one letter. Returns "stored", "replaced", or "!<reason shown to the player>". Main thread.
     * push: a worried letter is reported to the owner now (false = over the per-player limit; the edition reports it).
     */
    String store(String name, String kind, String raw, boolean push) {
        String text = clean(raw);
        if (text.length() < 3) return "!That letter is a little short. Write a sentence!";
        if (text.length() > MAX_LEN) return "!That's over 200 characters. The editor has a short attention span.";
        if (DIGITS.matcher(text.replaceAll("[\\s-]", "")).find()) return "!No phone numbers in the mail. Game stuff only.";
        if (LINKISH.matcher(text).find()) return "!No links, emails or @handles in the mail. Game stuff only.";
        rollDay();
        // every send adds a line to the day's file (a replaced letter stays in it), so one player may not fill it
        if (linesToday.getOrDefault(name, 0) >= PLAYER_CAP) return "!That's enough mail from you for today. Try again tomorrow!";
        String key = name + "\t" + kind;
        boolean replaced = sentToday.contains(key);
        if (!replaced && sentToday.size() >= SERVER_CAP) return "!The mailbag is full today. Try again tomorrow!";
        String day = LocalDate.now().toString();
        String id = day + "-" + String.format("%08x", RNG.nextInt());
        boolean worried = WELLBEING.matcher(raw).find() || WELLBEING.matcher(text).find();
        String line = line(id, System.currentTimeMillis() / 1000, name, kind, text, worried);
        File f = new File(dir, day + ".jsonl");
        if (f.length() + line.length() > FILE_CAP) return "!The mailbag is full today. Try again tomorrow!";
        // Written now, on the main thread (one short line, at most once a minute per player): a background write could be
        // dropped if the server stopped first, after the player was told "Sealed and dropped in the mailbox".
        synchronized (fileLock) {
            try {
                Files.writeString(f.toPath(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Housekeeping.privateFile(f.toPath());
            } catch (IOException e) {
                log.warning("mailroom write failed: " + e.getClass().getSimpleName());
                return "!The mail carrier dropped your letter. Try again in a minute.";
            }
        }
        sentToday.add(key);
        linesToday.merge(name, 1, Integer::sum);
        var s = settings.get();
        // the safety path: the owner hears about a worried letter now, whether or not the desk or the paper runs
        if (worried && push && concerns != null) async.accept(() -> concerns.report(s, new Concerns.Letter(id, name, "worried words", text)));
        return replaced ? "replaced" : "stored";
    }

    /** One stored letter, as a mailroom/DATE.jsonl line. worried adds "wb":true (older versions ignore it). */
    public static String line(String id, long t, String name, String kind, String text, boolean worried) {
        return "{\"id\":" + q(id) + ",\"t\":" + t + ",\"name\":" + q(name)
                + ",\"kind\":" + q(kind) + ",\"text\":" + q(text) + (worried ? ",\"wb\":true" : "") + "}\n";
    }

    static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i); i += Character.charCount(c);
            int t = Character.getType(c);
            if (c == '§' || t == Character.CONTROL || t == Character.FORMAT || t == Character.PRIVATE_USE || t == Character.UNASSIGNED) {
                if (Character.isWhitespace(c)) b.append(' ');
                continue;
            }
            b.appendCodePoint(c);
        }
        return b.toString().replaceAll("\\s+", " ").strip();
    }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** On a new day: forget yesterday's counts, re-read today's file (after a restart), delete files older than 7 days. */
    private void rollDay() {
        String day = LocalDate.now().toString();
        if (day.equals(countDay)) return;
        countDay = day;
        sentToday.clear();
        linesToday.clear();
        File f = new File(dir, day + ".jsonl");
        if (f.isFile() && f.length() <= FILE_CAP * 2) {
            try {
                for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    var m = java.util.regex.Pattern.compile("\"name\":\"([.A-Za-z0-9_]{3,16})\",\"kind\":\"(letter|tip)\"").matcher(l);
                    if (m.find()) { sentToday.add(m.group(1) + "\t" + m.group(2)); linesToday.merge(m.group(1), 1, Integer::sum); }
                }
            } catch (IOException e) { log.warning("mailroom read failed: " + e); }
        }
        prune();
    }

    private void prune() { prune(dir, LocalDate.now(), log); }

    /**
     * Deletes mailroom/DATE.jsonl files older than 7 days. Also run by the daily edition (EditionJob.tidy), so letters go
     * on time even when nobody writes and the server is not restarted.
     */
    public static void prune(File dir, LocalDate today, java.util.logging.Logger log) {
        String cut = today.minusDays(KEEP_DAYS) + ".jsonl";
        File[] old = dir.listFiles((d, n) -> n.matches("\\d{4}-\\d\\d-\\d\\d\\.jsonl") && n.compareTo(cut) < 0);
        if (old != null) for (File o : old) if (!o.delete()) log.warning("could not delete old mail " + o.getName());
    }

    // ------------------------------------------------------------ "your letter made the paper"
    public void tellPrinted(Player p, List<Printed> printed, String url) {
        if (printed == null) return;
        List<String> newKeys = new ArrayList<>();
        for (Printed x : printed) {
            if (!x.name().equalsIgnoreCase(p.getName())) continue;
            String key = x.date() + " " + x.name() + " " + x.kind();
            if (notified.contains(key)) continue;
            notified.add(key); newKeys.add(key);
            Component msg = x.kind().equals("tip")
                    ? Component.text("✉ The editor printed your tip in the " + x.date() + " " + settings.get().paper().name() + " (we didn't name you). ", NamedTextColor.GOLD)
                    : Component.text("✉ The editor answered your letter in the " + x.date() + " " + settings.get().paper().name() + "! ", NamedTextColor.GOLD);
            // no web address: plain text (Paper cannot send a link to "", the whole notice would be lost)
            p.sendMessage(url.isEmpty() ? msg : msg.append(Component.text("[read it]", NamedTextColor.AQUA).decorate(TextDecoration.UNDERLINED)
                    .hoverEvent(HoverEvent.showText(Component.text("Opens the paper in your browser"))).clickEvent(ClickEvent.openUrl(url))));
        }
        if (!newKeys.isEmpty()) saveNotified();
    }

    private File notifiedFile() { return new File(dataFolder, "notified.txt"); }

    private void loadNotified() {
        File f = notifiedFile();
        if (!f.isFile() || f.length() > 256 * 1024) return;
        String cut = LocalDate.now().minusDays(KEEP_DAYS + 1).toString();
        try {
            for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8))
                if (l.length() > 10 && l.substring(0, 10).compareTo(cut) >= 0) notified.add(l.strip());
        } catch (IOException e) { log.warning("notified.txt: " + e); }
    }

    private void saveNotified() {
        String cut = LocalDate.now().minusDays(KEEP_DAYS + 1).toString();
        notified.removeIf(k -> k.length() < 10 || k.substring(0, 10).compareTo(cut) < 0);
        String body = String.join("\n", notified) + "\n";
        File f = notifiedFile();
        async.accept(() -> {
            synchronized (fileLock) {
                try {
                    Housekeeping.writePrivate(f.toPath(), body);   // it names the players behind printed tips
                } catch (IOException e) { log.warning("notified.txt write failed: " + e); }
            }
        });
    }
}
