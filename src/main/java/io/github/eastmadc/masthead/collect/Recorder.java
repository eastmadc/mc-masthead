package io.github.eastmadc.masthead.collect;

import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import io.github.eastmadc.masthead.facts.FactsBuilder;
import io.github.eastmadc.masthead.pipeline.Housekeeping;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records what the paper reports, in the plugin's own files (no server-log parsing):
 * events/DATE.jsonl  joins, deaths (with the killer if a player), advancements that the game announces in chat;
 * health/DATE.jsonl  one line a minute: TPS and players online;
 * ip-players.txt     "address name" for readership (only if enabled; stays on the server; never printed in the paper; mode
 *                    600; a pair not seen for 60 days is dropped; deleted at start-up when readership is off).
 * Writes happen off the main thread; lines are buffered and flushed every 30 s. Files older than 40 days are deleted.
 * Line formats are documented in facts/FORMAT.md (deaths always carry "killer", "" when no player killed them).
 */
public final class Recorder implements Listener {
    public static final int KEEP_DAYS = 40;
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private final Plugin plugin;
    private final boolean readership;
    /** ip-players.txt keeps an address-to-name pair this many days after it was last seen. */
    static final int IP_KEEP_DAYS = 60;
    private final List<String[]> pending = new ArrayList<>();   // {dir, line}
    private final List<List<String[]>> inflight = new ArrayList<>();   // batches handed to a background write; under its own lock
    private final Map<String, LocalDate> ipSeen = new LinkedHashMap<>();   // "address name" -> last seen; main thread
    private boolean ipDirty;

    public Recorder(Plugin plugin, boolean readership) {
        this.plugin = plugin;
        this.readership = readership;
        if (readership) loadIps();
        else deleteIps();
        Bukkit.getScheduler().runTaskTimer(plugin, this::sampleHealth, 20L * 60, 20L * 60);
        Bukkit.getScheduler().runTaskTimer(plugin, this::flushAsync, 20L * 30, 20L * 30);
    }

    // ------------------------------------------------------------ events
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        add("events", "{\"t\":" + now() + ",\"k\":\"join\",\"p\":" + q(p.getName()) + "}");
        if (readership) {
            InetSocketAddress a = p.getAddress();
            if (a != null && a.getAddress() != null) {
                String ip = a.getAddress().getHostAddress();
                LocalDate today = LocalDate.now();
                if (ip.matches("[0-9a-fA-F:.]{2,45}") && !today.equals(ipSeen.put(ip + " " + p.getName(), today))) ipDirty = true;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Component msg = e.deathMessage();
        if (msg == null) return;                              // death messages turned off: nothing to report
        // Name tags and renamed weapons are player-typed text: stored as the plain type ("Zombie", "[Trident]").
        String text = PLAIN.serialize(DeathText.scrub(msg));
        text = DeathText.replaceTyped(text, e.getEntity().getName(), typedNames(e));
        Player killer = e.getEntity().getKiller();
        // "v":2 = written by a recorder that cleans names (older lines get a stricter check when the paper reads them)
        add("events", "{\"t\":" + now() + ",\"k\":\"death\",\"p\":" + q(e.getEntity().getName()) + ",\"msg\":" + q(cap(text, 300))
                + ",\"killer\":" + q(killer != null && killer != e.getEntity() ? killer.getName() : "") + ",\"v\":2}");
    }

    /** {typed name, type} for the mob that caused the death (if it has a name tag) and the weapon it held (if renamed). */
    private static List<String[]> typedNames(PlayerDeathEvent e) {
        List<String[]> out = new ArrayList<>();
        try {
            Entity src = e.getDamageSource().getCausingEntity();
            if (src == null) src = e.getDamageSource().getDirectEntity();
            if (src == null) return out;
            if (!(src instanceof Player) && src.customName() != null)
                out.add(new String[]{PLAIN.serialize(src.customName()), DeathText.pretty(src.getType().getKey().getKey())});
            if (src instanceof LivingEntity le && le.getEquipment() != null) {
                ItemStack it = le.getEquipment().getItemInMainHand();
                if (it != null && it.hasItemMeta() && it.getItemMeta().hasDisplayName())
                    out.add(new String[]{PLAIN.serialize(it.getItemMeta().displayName()), DeathText.pretty(it.getType().getKey().getKey())});
            }
        } catch (RuntimeException | LinkageError ignored) { }   // older servers: the hover-card pass already did the work
        return out;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        AdvancementDisplay d = e.getAdvancement().getDisplay();
        if (d == null || !d.doesAnnounceToChat()) return;     // recipes and hidden ones
        String kind = switch (d.frame()) { case CHALLENGE -> "challenge"; case GOAL -> "goal"; default -> "task"; };
        add("events", "{\"t\":" + now() + ",\"k\":\"adv\",\"p\":" + q(e.getPlayer().getName()) + ",\"title\":" + q(cap(PLAIN.serialize(d.title()).replace("]", "").strip(), 60))
                + ",\"frame\":\"" + kind + "\"}");
    }

    // ------------------------------------------------------------ health
    private void sampleHealth() {
        double tps = Bukkit.getTPS()[0];
        add("health", "{\"t\":" + now() + ",\"tps\":" + Math.round(Math.min(20.0, tps) * 100) / 100.0 + ",\"on\":" + Bukkit.getOnlinePlayers().size() + "}");
    }

    // ------------------------------------------------------------ files
    private void add(String dir, String line) {
        synchronized (pending) { pending.add(new String[]{dir, line}); }
    }

    private void flushAsync() {
        if (readership && pruneIps(ipSeen, LocalDate.now())) ipDirty = true;
        List<String[]> batch = drain();
        String ips = ipDirty ? ipsText(ipSeen) : null;
        ipDirty = false;
        if (batch.isEmpty() && ips == null) return;
        handOff(batch);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeInflight(batch, ips));
    }

    /** Main thread: never waits for a background write (that holds this object's lock while the disk works). */
    void handOff(List<String[]> batch) {
        synchronized (inflight) { inflight.add(batch); }
    }

    private List<String[]> drain() {
        synchronized (pending) {
            List<String[]> batch = new ArrayList<>(pending);
            pending.clear();
            return batch;
        }
    }

    /** The background write. A batch that flushNow already wrote (at shutdown) is skipped. */
    private synchronized void writeInflight(List<String[]> batch, String ips) {
        boolean mine;
        synchronized (inflight) { mine = inflight.removeIf(b -> b == batch); }
        if (mine) write(batch, ips);
    }

    /**
     * Synchronous flush (plugin shutdown): what is still waiting, plus any batch whose background write had not run yet
     * (the server cancels those tasks when it stops), so nothing recorded is lost.
     */
    public void flushNow() {
        List<String[]> batch = drain();
        String ips = readership && !ipSeen.isEmpty() ? ipsText(ipSeen) : null;
        synchronized (this) {   // lock order: this, then inflight (never the other way)
            List<List<String[]>> waiting;
            synchronized (inflight) { waiting = new ArrayList<>(inflight); inflight.clear(); }
            for (List<String[]> b : waiting) write(b, null);
            write(batch, ips);
        }
    }

    private synchronized void write(List<String[]> batch, String ips) {
        String day = LocalDate.now().toString();
        for (String dir : new String[]{"events", "health"}) {
            StringBuilder b = new StringBuilder();
            for (String[] x : batch) if (x[0].equals(dir)) b.append(x[1]).append('\n');
            if (b.isEmpty()) continue;
            File d = new File(plugin.getDataFolder(), dir);
            try { Housekeeping.createPrivateDirs(d.toPath()); } catch (IOException e) { continue; }
            try {
                Files.writeString(new File(d, day + ".jsonl").toPath(), b, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) { plugin.getLogger().warning(dir + " write failed: " + e); }
            File[] old = d.listFiles((x, n) -> n.matches("\\d{4}-\\d\\d-\\d\\d\\.jsonl") && n.compareTo(LocalDate.now().minusDays(KEEP_DAYS) + ".jsonl") < 0);
            if (old != null) for (File o : old) o.delete();
        }
        if (ips != null) {
            try {
                Housekeeping.writePrivate(new File(plugin.getDataFolder(), FactsBuilder.IP_PLAYERS).toPath(), ips);   // 600 from the start
            } catch (IOException e) { plugin.getLogger().warning("ip-players write failed: " + e); }
        }
    }

    /** Drops pairs not seen for IP_KEEP_DAYS. True if any went. */
    static boolean pruneIps(Map<String, LocalDate> ipSeen, LocalDate today) {
        LocalDate cut = today.minusDays(IP_KEEP_DAYS);
        return ipSeen.values().removeIf(d -> d.isBefore(cut));
    }

    /**
     * ip-players.txt: "address name" lines under a "# YYYY-MM-DD" line (the day they were last seen), newest first. The
     * date lines are ignored by older versions' readers (they need exactly an address and a name).
     */
    static String ipsText(Map<String, LocalDate> ipSeen) {
        Map<LocalDate, List<String>> byDay = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        ipSeen.forEach((k, d) -> byDay.computeIfAbsent(d, x -> new ArrayList<>()).add(k));
        StringBuilder b = new StringBuilder();
        byDay.forEach((d, ks) -> { b.append("# ").append(d).append('\n'); ks.forEach(k -> b.append(k).append('\n')); });
        return b.toString();
    }

    private void loadIps() {
        File f = new File(plugin.getDataFolder(), FactsBuilder.IP_PLAYERS);
        if (!f.isFile() || f.length() > 1 << 20) return;
        try {
            parseIps(ipSeen, Files.readAllLines(f.toPath(), StandardCharsets.UTF_8), LocalDate.now());
        } catch (IOException e) { plugin.getLogger().warning("ip-players.txt: " + e); }
        if (pruneIps(ipSeen, LocalDate.now())) ipDirty = true;
        Housekeeping.privateFile(f.toPath());
    }

    /** Reads the lines; pairs from before the date lines existed count as seen today (their 60 days start now). */
    static void parseIps(Map<String, LocalDate> ipSeen, List<String> lines, LocalDate today) {
        LocalDate day = today;
        for (String l : lines) {
            if (l.matches("# \\d{4}-\\d\\d-\\d\\d")) {
                try { day = LocalDate.parse(l.substring(2)); } catch (java.time.DateTimeException e) { day = today; }
            } else if (l.matches("[0-9a-fA-F:.]{2,45} \\.?[A-Za-z0-9_]{3,16}") && ipSeen.size() < 5000) ipSeen.merge(l, day, (a, b) -> a.isAfter(b) ? a : b);
        }
    }

    /** features.readership is off: the address-to-name file has no reason to exist. */
    private void deleteIps() {
        try {
            if (Files.deleteIfExists(new File(plugin.getDataFolder(), FactsBuilder.IP_PLAYERS).toPath()))
                plugin.getLogger().info("Readership is off: deleted ip-players.txt (player addresses).");
        } catch (IOException e) { plugin.getLogger().warning("ip-players.txt: " + e); }
    }

    private static long now() { return System.currentTimeMillis() / 1000; }
    private static String cap(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
