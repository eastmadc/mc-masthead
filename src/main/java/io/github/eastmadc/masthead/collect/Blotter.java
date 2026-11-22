package io.github.eastmadc.masthead.collect;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.TileState;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Police Blotter: counts what players do to EACH OTHER or each other's stuff, for the newspaper.
 * Records names, counts and item types only. Coordinates are used in memory to work out whose bed it is, never saved.
 * An owner is named only when it is known: a container's placed_by stamp, or a bed someone's spawn point is on. Being near
 * someone's spawn point proves nothing (families share houses and beds), so it never makes a place "theirs".
 * Every handler is MONITOR + ignoreCancelled (we only watch, never change the game) and wrapped so it can't break gameplay.
 * Output: plugins/Masthead/blotter/YYYY-MM-DD.jsonl, one aggregated line per (what, who, whom, detail) per minute.
 */
public final class Blotter implements Listener {
    public static final int KEEP_DAYS = 14;

    private final org.bukkit.plugin.Plugin plugin;
    private final NamespacedKey ownerKey;
    private final Map<String, Long> counts = new LinkedHashMap<>();            // "kind\ta\tb\tdetail" -> n
    private final Map<UUID, Location> homes = new HashMap<>();                 // player -> respawn point (memory only)
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Snapshot> open = new HashMap<>();                  // viewer -> container contents when opened

    record Snapshot(String owner, Map<Material, Integer> before, Inventory inv) {}

    public Blotter(org.bukkit.plugin.Plugin plugin) {
        this.plugin = plugin;
        this.ownerKey = new NamespacedKey(plugin, "placed_by");
        refreshOnline();
        refreshHomes();
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOnline, 20L * 60 * 5, 20L * 60 * 5);
        Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 20L * 60, 20L * 60);
    }

    // ------------------------------------------------------------ bookkeeping
    void add(String kind, String a, String b, String detail, long n) {
        if (a == null || b == null || a.equals(b) || n <= 0) return;
        counts.merge(kind + "\t" + a + "\t" + b + "\t" + (detail == null ? "" : detail), n, Long::sum);
    }

    /** Write this minute's counts to today's file (runs on the main thread; the file is tiny). */
    public void flush() {
        if (counts.isEmpty()) return;
        File dir = new File(plugin.getDataFolder(), "blotter");
        try { io.github.eastmadc.masthead.pipeline.Housekeeping.createPrivateDirs(dir.toPath()); } catch (IOException e) { return; }
        long t = System.currentTimeMillis() / 1000;
        try (Writer w = new FileWriter(new File(dir, LocalDate.now() + ".jsonl"), StandardCharsets.UTF_8, true)) {
            for (Map.Entry<String, Long> e : counts.entrySet()) {
                String[] p = e.getKey().split("\t", -1);
                w.write("{\"t\":" + t + ",\"k\":" + q(p[0]) + ",\"a\":" + q(p[1]) + ",\"b\":" + q(p[2]) + ",\"x\":" + q(p[3]) + ",\"n\":" + e.getValue() + "}\n");
            }
            counts.clear();
        } catch (IOException ex) {
            plugin.getLogger().warning("blotter write failed: " + ex);
        }
        File[] old = dir.listFiles((d, n) -> n.endsWith(".jsonl") && n.compareTo(LocalDate.now().minusDays(KEEP_DAYS) + ".jsonl") < 0);
        if (old != null) for (File f : old) f.delete();
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

    /** Online players' spawn points: main thread, from memory only (no chunk loads, no files). Every 5 minutes. */
    private void refreshOnline() {
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.put(p.getUniqueId(), p.getName());
                setHome(p.getUniqueId(), p.getRespawnLocation(false));
            }
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().warning("blotter: could not read spawn points: " + e);
        }
    }

    /**
     * Every player's saved spawn point. An offline player's comes from their data file, so it is read on a background
     * thread, and never loads a chunk (getRespawnLocation(false)). Once at start-up and once per edition.
     */
    public void refreshHomes() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, String> n = new HashMap<>();
            Map<UUID, Location> h = new HashMap<>();
            try {
                for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                    if (op.getName() == null || op instanceof Player) continue;   // online players: refreshOnline
                    n.put(op.getUniqueId(), op.getName());
                    Location l = op.getRespawnLocation(false);
                    if (l != null) h.put(op.getUniqueId(), l.clone());
                }
            } catch (RuntimeException | LinkageError e) {
                plugin.getLogger().warning("blotter: could not read spawn points: " + e);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {   // the maps are main-thread only
                for (Map.Entry<UUID, String> e : n.entrySet()) {
                    if (Bukkit.getPlayer(e.getKey()) != null) continue;   // came online meanwhile: refreshOnline has it
                    names.put(e.getKey(), e.getValue());
                    setHome(e.getKey(), h.get(e.getKey()));
                }
                refreshOnline();
            });
        });
    }

    private void setHome(UUID id, Location l) {
        if (l != null && l.getWorld() != null) homes.put(id, l); else homes.remove(id);
    }

    /** Whose bed is this? Someone whose spawn point is on this bed (either half). */
    private String bedOwner(Block bed) {
        for (Map.Entry<UUID, Location> h : homes.entrySet()) {
            Location l = h.getValue();
            if (l.getWorld() != null && l.getWorld().equals(bed.getWorld()) && l.distanceSquared(bed.getLocation().add(0.5, 0, 0.5)) <= 4.5)
                return names.get(h.getKey());
        }
        return null;
    }

    private String placedBy(BlockState st) {
        if (st instanceof TileState ts) {
            String id = ts.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (id != null) {
                try { return names.get(UUID.fromString(id)); } catch (IllegalArgumentException ignored) { }
            }
        }
        return null;
    }

    private static String nice(Material m) { return m.getKey().getKey(); }
    private static String nice(Entity e) { return e.getType().getKey().getKey(); }

    private interface Body { void run() throws Exception; }
    private void safe(Body b) {
        try { b.run(); } catch (Exception e) { plugin.getLogger().fine("blotter: " + e); }
    }

    // ------------------------------------------------------------ player vs player
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectile(ProjectileHitEvent ev) {
        safe(() -> {
            Projectile pr = ev.getEntity();
            if (pr.getShooter() instanceof Player a && ev.getHitEntity() instanceof Player b) {
                if (pr instanceof Egg) add("egged", a.getName(), b.getName(), null, 1);
                else if (pr instanceof Snowball) add("snowballed", a.getName(), b.getName(), null, 1);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent ev) {
        safe(() -> {
            if (ev.getState() == PlayerFishEvent.State.CAUGHT_ENTITY && ev.getCaught() instanceof Player b)
                add("reeled_in", ev.getPlayer().getName(), b.getName(), null, 1);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent ev) {
        safe(() -> {
            if (ev.getDamager() instanceof Player a && ev.getEntity() instanceof Player b) {
                ItemStack hand = a.getInventory().getItemInMainHand();
                add("bonked", a.getName(), b.getName(), hand.getType().isAir() ? "fist" : nice(hand.getType()), 1);
            } else if (ev.getDamager() instanceof Player a && ev.getEntity() instanceof Tameable t && t.isTamed()
                    && t.getOwner() instanceof AnimalTamer o && o.getName() != null && !o.getUniqueId().equals(a.getUniqueId())) {
                add("hit_pet", a.getName(), o.getName(), nice(t), 1);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent ev) {
        safe(() -> {
            if (ev.getEntity() instanceof Tameable t && t.isTamed() && t.getOwner() instanceof AnimalTamer o && o.getName() != null) {
                Player a = ev.getEntity().getKiller();
                if (a != null && !a.getUniqueId().equals(o.getUniqueId())) add("killed_pet", a.getName(), o.getName(), nice(t), 1);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMount(EntityMountEvent ev) {
        safe(() -> {
            if (ev.getEntity() instanceof Player a && ev.getMount() instanceof AbstractHorse h && h.isTamed()
                    && h.getOwner() instanceof AnimalTamer o && o.getName() != null && !o.getUniqueId().equals(a.getUniqueId()))
                add("rode_horse", a.getName(), o.getName(), nice(h), 1);
        });
    }

    /** A leash on someone else's tamed animal: the owner is the game's own (Tameable.getOwner), never a guess. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent ev) {
        safe(() -> {
            Player a = ev.getPlayer();
            if (a != null && ev.getEntity() instanceof Tameable t && t.isTamed() && t.getOwner() instanceof AnimalTamer o && o.getName() != null
                    && !o.getUniqueId().equals(a.getUniqueId()))
                add("leashed_pet", a.getName(), o.getName(), nice(t), 1);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBed(PlayerBedEnterEvent ev) {
        safe(() -> {
            if (ev.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;
            String owner = bedOwner(ev.getBed());
            if (owner != null) add("slept_in_bed", ev.getPlayer().getName(), owner, null, 1);
        });
    }

    // ------------------------------------------------------------ their stuff
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent ev) {
        safe(() -> {
            Block b = ev.getBlockPlaced();
            Player a = ev.getPlayer();
            if (b.getState() instanceof Container) {   // stamp new containers with who placed them (next tick, once the block exists)
                String id = a.getUniqueId().toString();
                Bukkit.getScheduler().runTask(plugin, () -> safe(() -> {
                    if (b.getState() instanceof TileState ts) {
                        ts.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, id);
                        ts.update(false, false);
                    }
                }));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent ev) {
        safe(() -> {
            Block b = ev.getBlock();
            String a = ev.getPlayer().getName();
            if (b.getBlockData() instanceof org.bukkit.block.data.type.Bed) {
                String owner = bedOwner(b);
                if (owner != null) { add("broke_bed", a, owner, null, 1); return; }
            }
            BlockState st = b.getState();
            if (st instanceof Container) {
                String owner = placedBy(st);
                if (owner != null) add("broke_container", a, owner, nice(b.getType()), 1);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent ev) {
        safe(() -> {
            if (!(ev.getPlayer() instanceof Player a)) return;
            Inventory inv = ev.getInventory();
            InventoryHolder h = inv.getHolder(false);
            BlockState st = null;
            if (h instanceof DoubleChest dc) st = dc.getLeftSide(false) instanceof Chest c ? c : null;
            else if (h instanceof Container c) st = c;
            if (st == null) return;   // not a block container (ender chests, villagers, minecarts... are ignored)
            String owner = placedBy(st);
            if (owner == null && h instanceof DoubleChest dc && dc.getRightSide(false) instanceof Chest c2) owner = placedBy(c2);
            if (owner == null || owner.equals(a.getName())) return;   // no stamp: whose it is is not known, so no line
            if (farmPart(st.getBlock(), owner) || h instanceof DoubleChest dc2 && dc2.getRightSide(false) instanceof Chest r && farmPart(r.getBlock(), owner)) return;
            open.put(a.getUniqueId(), new Snapshot(owner, contents(inv), inv));
            add("opened_container", a.getName(), owner, nice(st.getType()), 1);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent ev) {
        safe(() -> {
            Snapshot s = open.remove(ev.getPlayer().getUniqueId());
            if (s == null || s.inv() != ev.getInventory()) return;
            Map<Material, Integer> after = contents(ev.getInventory());
            String a = ev.getPlayer().getName();
            for (Material m : union(s.before(), after)) {
                int d = after.getOrDefault(m, 0) - s.before().getOrDefault(m, 0);
                if (d < 0) add("took_from", a, s.owner(), nice(m), -d);
                if (d > 0) add("left_in", a, s.owner(), nice(m), d);
            }
        });
    }

    private static final org.bukkit.block.BlockFace[] FEEDS = {org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.NORTH,
            org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST};

    /**
     * A hopper, or a container a hopper feeds: a farm's or machine's output, so taking from it is using the farm, not news.
     * A feeding hopper counts only if the owner placed it (or nobody known did): someone else's hopper can't hide a theft.
     */
    boolean farmPart(Block b, String owner) {
        if (b == null) return false;
        if (b.getType() == Material.HOPPER) return true;
        for (org.bukkit.block.BlockFace f : FEEDS) {
            Block n = b.getRelative(f);
            if (n.getType() == Material.HOPPER && n.getBlockData() instanceof org.bukkit.block.data.Directional d
                    && d.getFacing() == f.getOppositeFace()) {
                String by = placedBy(n.getState());
                if (by == null || by.equals(owner)) return true;
            }
        }
        return false;
    }

    private static Map<Material, Integer> contents(Inventory inv) {
        Map<Material, Integer> m = new HashMap<>();
        for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir()) m.merge(it.getType(), it.getAmount(), Integer::sum);
        return m;
    }

    private static List<Material> union(Map<Material, Integer> a, Map<Material, Integer> b) {
        List<Material> out = new ArrayList<>(a.keySet());
        for (Material m : b.keySet()) if (!a.containsKey(m)) out.add(m);
        return out;
    }
}
