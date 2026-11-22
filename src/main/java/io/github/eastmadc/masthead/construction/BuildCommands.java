package io.github.eastmadc.masthead.construction;

import io.github.eastmadc.masthead.config.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * /news build: a player's say over what the paper writes about their builds. Every reply is fixed text, the same whether
 * or not anything was found, and never a position. Requests are written for the next paper's fold, which applies a build
 * request only to a build the player helped make. Main thread.
 */
final class BuildCommands {
    static final List<String> SUBS = List.of("private", "public", "secret", "unsecret", "name", "kind", "done", "forget");

    private final Construction c;
    private final Map<UUID, Long> cooldown = new HashMap<>(), forgetAsked = new HashMap<>();
    private final Map<UUID, String> typedLevel = new HashMap<>();   // chosen since the last paper

    BuildCommands(Construction c) {
        this.c = c;
        // the list read in the background, never every player's file on the main thread (review PT-3)
        offline = () -> io.github.eastmadc.masthead.game.KnownNames.get(c.plugin());
    }

    void command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
        Settings s = c.settings();
        if (!s.features().construction()) { say(p, "Construction news is off on this server."); return; }
        if (neverRecorded(Construction.lower(s.privacy().hidePlayers()), p.getName())) { say(p, "Your builds are never in this paper."); return; }
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = cooldown.get(u);
        if (last != null && now - last < 3_000) { say(p, "Slow down. Try /news build again in a few seconds."); return; }
        cooldown.put(u, now);
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        String rest = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)).strip() : "";
        switch (sub) {
            case "" -> status(p, s);
            case "private" -> {
                level(p, "private");
                say(p, "Done. The paper won't describe your builds. Shared builds you helped on are left out too.");
            }
            case "public" -> {
                level(p, "public");
                say(p, "Done. The paper may describe your builds (never where).");
            }
            case "secret" -> { site(p, "secret", null); say(p, "Got it. The paper won't mention that build."); }
            case "unsecret" -> { site(p, "unsecret", null); say(p, "That build can be in the paper again."); }
            case "done" -> { site(p, "done", null); say(p, "Ribbon cutting scheduled. Someone find the big scissors."); }
            case "kind" -> {
                String k = rest.toLowerCase(Locale.ROOT).replace(' ', '_');
                if (!k.equals("clear") && !Kinds.ALL.contains(k)) { say(p, "Kinds: " + String.join(", ", kindWords()) + ", or clear."); return; }
                site(p, "kind", k);
                say(p, k.equals("clear") ? "Filed. The Commission goes back to guessing." : "Filed. The Commission will stop guessing.");
            }
            case "name" -> {
                if (rest.equalsIgnoreCase("clear")) {
                    site(p, "name_clear", null);
                    say(p, "Filed. If you helped build it, its name comes off at the next paper.");
                    return;
                }
                String n = s.construction().names() ? NameRules.check(rest, knownNames(s), "clean") : null;
                if (n == null) { say(p, "That name can't be used."); return; }
                site(p, "name", n);
                say(p, "Name filed. The Commission will review the paperwork.");
            }
            case "forget" -> {
                Long asked = forgetAsked.remove(u);
                if (rest.equalsIgnoreCase("confirm") && asked != null && now - asked <= 60_000) {
                    typedLevel.put(u, "private");
                    c.forget(u);
                    say(p, "Done. Note: the paper can no longer tell if someone breaks those builds.");
                } else {
                    forgetAsked.put(u, now);
                    say(p, "This erases your building records and makes your builds private. Type /news build forget confirm within 60 seconds.");
                }
            }
            default -> say(p, "/news build [private | public | secret | unsecret | name <name> | name clear | kind <kind> | done | forget]");
        }
    }

    private void status(Player p, Settings s) {
        UUID u = p.getUniqueId();
        String lvl = typedLevel.getOrDefault(u, c.level(u, p.getName()));
        say(p, "Your builds: " + lvl + "." + (c.noticedOrSent(u) ? "" : " (The paper writes about nobody's builds until they have had the notice.)"));
        // typed private since the last paper: that paper's lines no longer hold
        List<String> lines = "private".equals(typedLevel.get(u)) ? List.of() : c.statusLines(u);
        if (lines.isEmpty()) say(p, "Nothing about your builds is in line for the paper.");
        else lines.forEach(l -> say(p, l));
        say(p, "A build nobody builds on for " + s.construction().forgetAfterDays()
                + " days is forgotten; after that the paper can't tell if someone breaks it.");
    }

    private void level(Player p, String level) {
        typedLevel.put(p.getUniqueId(), level);
        c.request(RawLine.request(p.getUniqueId(), level, null, null, null, null));
    }

    /** A build request carries the 4-block cube the player stands in (the fold looks there first). */
    private void site(Player p, String op, String value) {
        boolean vanished = false;
        if (p.hasMetadata("vanished")) for (MetadataValue v : p.getMetadata("vanished")) if (v.asBoolean()) vanished = true;
        if (!positionOk(p.getGameMode(), vanished)) { c.request(RawLine.request(p.getUniqueId(), op, null, null, value, null)); return; }
        Location l = p.getLocation();
        long cube = SiteIndex.cubeKey(l.getBlockX() >> 2, l.getBlockY() >> 2, l.getBlockZ() >> 2);
        c.request(RawLine.request(p.getUniqueId(), op, l.getWorld().getUID(), cube, value, null));
    }

    /** Every name a build name may not contain: players who have been on the server, online players and hidden names. */
    private Set<String> knownNames(Settings s) {
        Set<String> out = new HashSet<>(s.privacy().hidePlayers());
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        out.addAll(offlineNames(System.currentTimeMillis()));
        return out;
    }

    Supplier<Collection<String>> offline;   // set in the constructor; tests replace it

    private Set<String> offlineCache;
    private long offlineAt;

    /** Players who have been on the server, read at most every 10 minutes (the list can mean reading every playerdata file). */
    Set<String> offlineNames(long now) {
        if (offlineCache == null || offlineCache.isEmpty() || now - offlineAt > 600_000) {   // empty: the background read has not finished yet
            offlineCache = Set.copyOf(offline.get()); offlineAt = now; }
        return offlineCache;
    }

    /** privacy.hide-players: the recorder never records them, so /news build stores nothing for them either. */
    /** The kinds a builder may file, as typed ("slime farm"). */
    static List<String> kindWords() { return Kinds.ALL.stream().map(k -> k.replace('_', ' ')).toList(); }

    static boolean neverRecorded(Set<String> hiddenLower, String name) { return hiddenLower.contains(name.toLowerCase(Locale.ROOT)); }

    /** A request carries the player's cube only when the recorder would record them (survival or adventure, not vanished). */
    static boolean positionOk(GameMode g, boolean vanished) { return (g == GameMode.SURVIVAL || g == GameMode.ADVENTURE) && !vanished; }

    List<String> tabComplete(String[] args) {
        if (args.length == 1) return SUBS.stream().filter(x -> x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("name")) return "clear".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("clear") : List.of();
        if (args.length >= 2 && args[0].equalsIgnoreCase("kind")) {
            String typed = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).toLowerCase(Locale.ROOT);
            return java.util.stream.Stream.concat(kindWords().stream(), java.util.stream.Stream.of("clear")).filter(x -> x.startsWith(typed)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("forget")) return "confirm".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("confirm") : List.of();
        return List.of();
    }

    private static void say(Player p, String text) { p.sendMessage(Component.text(text, NamedTextColor.GOLD)); }
}
