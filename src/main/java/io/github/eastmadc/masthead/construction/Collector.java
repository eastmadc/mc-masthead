package io.github.eastmadc.masthead.construction;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.ExplosionResult;
import org.bukkit.metadata.MetadataValue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The recorder: 8 event handlers that turn placed, broken, burnt and blown-up blocks into counts per 4-block cube for the
 * open minute, and 1 that counts mob deaths at builds (Production). The exact block position never leaves a
 * handler's local variables. Hidden, vanished, creative and spectating players are never recorded. Nothing here logs or
 * touches a file. Main thread only.
 */
final class Collector implements Listener {
    private static final int PER_EVENT = 512, PER_TICK = 4096;

    /** Who a player is, read once (vanished once a minute). */
    private static final class Who {
        final String name;
        final boolean hidden;
        boolean vanished;
        int minute = -1;

        Who(String name, boolean hidden) { this.name = name; this.hidden = hidden; }
    }

    private final Construction owner;
    final MinuteMaps mm;
    final WorldSlots slots;
    Materials mat;
    Set<String> hidden = Set.of(), ignoreWorlds = Set.of();   // lower case; replaced on reload
    private HashMap<UUID, Who> who = new HashMap<>();
    long errors;
    private int tick = -1, tickBlocks;
    // the last head (pumpkin or skull) placed: a golem or wither spawning from it in the same tick is that player's own
    private UUID headBy;
    private String headName;
    private int headTick = -1, headW, headX, headY, headZ;

    Collector(Construction owner, MinuteMaps mm, WorldSlots slots) {
        this.owner = owner;
        this.mm = mm;
        this.slots = slots;
    }

    /** On reload: everyone is looked up again with the new hidden list. */
    void clearWho() { who = new HashMap<>(); }

    // ------------------------------------------------------------ who and where
    /** The player's cache entry, or null if they are not recorded right now. */
    Who who(Player p) {
        GameMode g = p.getGameMode();
        if (g != GameMode.SURVIVAL && g != GameMode.ADVENTURE) return null;
        Who w = who.get(p.getUniqueId());
        if (w == null) {
            w = new Who(p.getName(), hidden.contains(p.getName().toLowerCase(Locale.ROOT)));
            who.put(p.getUniqueId(), w);
        }
        if (w.hidden) return null;
        if (w.minute != mm.minute) {
            w.minute = mm.minute;
            w.vanished = false;
            if (p.hasMetadata("vanished")) for (MetadataValue v : p.getMetadata("vanished")) if (v.asBoolean()) w.vanished = true;
        }
        return w.vanished ? null : w;
    }

    /** The world's slot, or -1 if it is ignored or over the cap. */
    private int slot(World wd) {
        UUID u = wd.getUID();
        int w = slots.slotOf(u);
        if (w != WorldSlots.NEW) return w;
        w = slots.assign(u, ignoreWorlds.contains(wd.getName().toLowerCase(Locale.ROOT)), bedsExplode(wd), anchorsExplode(wd));
        if (slots.overflow == 1) owner.warnOnce("construction: more than 63 worlds; the others are not recorded");
        return w;
    }

    private static boolean bedsExplode(World wd) {
        try { return bedsExplode(wd.isBedWorks(), wd.getEnvironment()); }
        catch (RuntimeException | LinkageError e) { return wd.getEnvironment() != World.Environment.NORMAL; }
    }

    /**
     * On Paper 26.x isBedWorks() means "can you sleep right now", so it is false in the Overworld all day. Only a world that
     * is not a normal one (Nether, End) is taken as a world where beds explode.
     */
    static boolean bedsExplode(boolean bedWorksNow, World.Environment env) {
        return !bedWorksNow && env != World.Environment.NORMAL;
    }

    private static boolean anchorsExplode(World wd) {
        try { return !wd.isRespawnAnchorWorks(); }
        catch (RuntimeException | LinkageError e) { return wd.getEnvironment() != World.Environment.NETHER; }
    }

    /** A bed where beds explode, or an anchor where anchors explode: used up with no break event, so never counted. */
    private boolean usedUp(int o, int w) {
        return mat.bed[o] ? slots.bedsExplode[w] : o == mat.anchorOrd && slots.anchorsExplode[w];
    }

    // ------------------------------------------------------------ the 8 handlers
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (owner.stoppedBy != null) return;
        try {
            Player p = e.getPlayer();
            Who w = who(p);
            if (w == null) return;
            Block against = e.getBlockAgainst(), placed = e.getBlockPlaced();
            if (e instanceof BlockMultiPlaceEvent mp && !mat.twoPart[placed.getType().ordinal()]) {
                for (BlockState s : mp.getReplacedBlockStates()) if (newBlock(s.getType(), s.getBlock().getType())) placeOne(p, w, s.getBlock(), against);
            } else if (newBlock(e.getBlockReplacedState().getType(), placed.getType())) placeOne(p, w, placed, against);
        } catch (Exception x) { errors++; } catch (LinkageError x) { owner.stopRecorder(x); }
    }

    private void placeOne(Player p, Who who, Block b, Block against) {
        Material m = b.getType();
        int o = m.ordinal();
        byte c = mat.cls[o];
        if (c == KeyRules.IGNORE || c == KeyRules.EARTH) return;
        if (c == KeyRules.GROWN) c = mat.grownAs[o];
        World wd = b.getWorld();
        int w = slot(wd);
        if (w < 0 || usedUp(o, w)) return;
        int x = b.getX(), y = b.getY(), z = b.getZ();
        MinuteMaps.Acc a = mm.place(p.getUniqueId(), who.name, w, x, y, z, c, mat.keyAs[o]);
        if (a != null && mat.head[o]) { headBy = p.getUniqueId(); headName = who.name; headTick = Bukkit.getCurrentTick(); headW = w; headX = x; headY = y; headZ = z; }
        if (a == null || c != KeyRules.BUILT) return;
        if (mat.ground[o]) a.groundPlaced(WorldSlots.sc(x, y, z), mat.keyAs[o]);
        if (y > wd.getMinHeight()) {   // bridge signal
            Material below = b.getRelative(BlockFace.DOWN).getType();
            if (below.isAir() || below == Material.WATER || below == Material.LAVA) a.overAir++;
        }
        if (against != null) {   // existing-build signal: against a crafted block in another 4-block cube
            int ax = against.getX(), ay = against.getY(), az = against.getZ();
            if (((ax >> 2) != (x >> 2) || (ay >> 2) != (y >> 2) || (az >> 2) != (z >> 2)) && mat.crafted[against.getType().ordinal()])
                MinuteMaps.against(a, w, x, y, z, ax, ay, az);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (owner.stoppedBy != null) return;
        try {
            Player p = e.getPlayer();
            Who who = who(p);
            if (who == null) return;
            Block b = e.getBlock();
            Material m = b.getType();
            int o = m.ordinal();
            byte c = mat.cls[o];
            if (c == KeyRules.IGNORE || c == KeyRules.EARTH) return;
            if (c == KeyRules.GROWN) {   // harvest: only the base of cane, bamboo or cactus counts
                if (!mat.stacking[o] || b.getRelative(BlockFace.DOWN).getType() == m) return;
                c = KeyRules.PLANT;
            }
            if (mat.twoPart[o]) b = recordedHalf(b);
            World wd = b.getWorld();
            int w = slot(wd);
            if (w < 0 || usedUp(o, w)) return;
            if (mat.ground[o]) mm.breakGround(p.getUniqueId(), who.name, w, b.getX(), b.getY(), b.getZ(), mat.keyAs[o]);
            else mm.breakBlock(p.getUniqueId(), who.name, w, b.getX(), b.getY(), b.getZ(), c, mat.keyAs[o]);
        } catch (Exception x) { errors++; } catch (LinkageError x) { owner.stopRecorder(x); }
    }

    /** False when the block was already there in another form: a hoe on grass, a shovel path, an axe on a log, wax on copper. */
    private boolean newBlock(Material was, Material now) { return mat.replaceable[was.ordinal()] || was == now; }

    /** A bed, door or tall plant was counted once, at its lower half (a bed: its foot). */
    private static Block recordedHalf(Block b) {
        BlockData d = b.getBlockData();
        if (d instanceof Bed bd) return bd.getPart() == Bed.Part.HEAD ? b.getRelative(bd.getFacing().getOppositeFace()) : b;
        if (d instanceof Bisected bi && bi.getHalf() == Bisected.Half.TOP) return b.getRelative(BlockFace.DOWN);
        return b;
    }

    private static boolean isUpperHalf(Block b) {
        BlockData d = b.getBlockData();
        if (d instanceof Bed bd) return bd.getPart() == Bed.Part.HEAD;
        return d instanceof Bisected bi && bi.getHalf() == Bisected.Half.TOP;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (owner.stoppedBy != null) return;
        try {
            if (!destroys(e.getExplosionResult())) return;
            Entity en = e.getEntity();
            String cause = "explosion";
            UUID by = null;
            if (en instanceof TNTPrimed t) {
                cause = "tnt";
                if (t.getSource() instanceof Player p && who(p) != null) by = p.getUniqueId();
            } else if (en instanceof Creeper) cause = "creeper";
            else if (en instanceof Wither || en instanceof WitherSkull) cause = "wither";
            losses(cause, by, e.blockList());
        } catch (Exception x) { errors++; } catch (LinkageError x) { owner.stopRecorder(x); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (owner.stoppedBy != null) return;
        try {
            if (destroys(e.getExplosionResult())) losses("explosion", null, e.blockList());
        } catch (Exception x) { errors++; } catch (LinkageError x) { owner.stopRecorder(x); }
    }

    private static boolean destroys(ExplosionResult r) {
        return r == ExplosionResult.DESTROY || r == ExplosionResult.DESTROY_WITH_DECAY;
    }

    /** At most 512 blocks an explosion and 4,096 a tick; the rest is counted as overflow. */
    private void losses(String cause, UUID by, List<Block> blocks) {
        int now = Bukkit.getCurrentTick();
        if (now != tick) { tick = now; tickBlocks = 0; }
        int n = 0;
        for (Block b : blocks) {
            if (n >= PER_EVENT || tickBlocks >= PER_TICK) { mm.overflow += blocks.size() - n; return; }
            n++;
            tickBlocks++;
            lose(cause, by, b);
        }
    }

    private void lose(String cause, UUID by, Block b) {
        Material m = b.getType();
        int o = m.ordinal();
        byte c = mat.cls[o];
        if (c == KeyRules.IGNORE || c == KeyRules.EARTH) return;
        if (c == KeyRules.GROWN) {
            if (!mat.stacking[o] || b.getRelative(BlockFace.DOWN).getType() == m) return;
            c = KeyRules.PLANT;
        }
        if (mat.twoPart[o] && isUpperHalf(b)) return;   // counted once, at the lower half
        int w = slot(b.getWorld());
        if (w < 0 || usedUp(o, w)) return;
        mm.lose(cause, by, w, b.getX(), b.getY(), b.getZ(), c, mat.keyAs[o]);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (owner.stoppedBy != null) return;
        try {
            lose("fire", null, e.getBlock());
        } catch (Exception x) { errors++; } catch (LinkageError x) { owner.stopRecorder(x); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (owner.stoppedBy != null) return;
        try {
            if (e.getBucket() != Material.WATER_BUCKET) return;
            Player p = e.getPlayer();
            Who who = who(p);
            if (who == null) return;
            Block b = e.getBlock();
            int w = slot(b.getWorld());
            if (w < 0) return;
            MinuteMaps.Acc a = mm.acc(p.getUniqueId(), who.name, WorldSlots.sectionKey(w, b.getX() >> 4, b.getY() >> 4, b.getZ() >> 4));
            if (a != null) a.water++;
        } catch (Exception x) { errors++; } catch (LinkageError x) { owner.stopRecorder(x); }
    }

    /**
     * construction.sign-names: a player finished writing a sign. Its text goes to requests.jsonl only (the fold uses it as
     * a build name if NameRules accepts it, else it is dropped); never logged, never shown. A sign written before it is on is not read.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSign(SignChangeEvent e) {
        if (owner.stoppedBy != null || !owner.settings().construction().signNames()) return;
        try {
            Player p = e.getPlayer();
            if (who(p) == null) return;
            Block b = e.getBlock();
            if (slot(b.getWorld()) < 0) return;
            StringBuilder t = new StringBuilder();
            for (net.kyori.adventure.text.Component c : e.lines())
                if (c != null) t.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c)).append(' ');
            String text = t.toString().strip().replaceAll("\\s+", " ");
            if (text.isEmpty()) return;
            if (text.length() > 60) text = text.substring(0, 60);
            owner.request(RawLine.request(p.getUniqueId(), "sign", b.getWorld().getUID(), SiteIndex.cubeKey(b.getX() >> 2, b.getY() >> 2, b.getZ() >> 2), text, null));
        } catch (Exception x) { errors++; } catch (LinkageError x) { owner.stopRecorder(x); }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        try {
            UUID u = e.getPlayer().getUniqueId();
            who.remove(u);
            mm.quit(u);
        } catch (Exception x) { errors++; } catch (LinkageError x) { owner.stopRecorder(x); }
    }

    /** Golems and withers built from blocks: the game clears the pattern with no break event, so take it off here. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (owner.stoppedBy != null) return;
        try {
            int r = e.getSpawnReason().ordinal();
            if (r >= mat.summonHeight.length) return;
            int h = mat.summonHeight[r];
            if (h == 0) return;
            Location l = e.getLocation();
            World wd = l.getWorld();
            int w = slot(wd);
            if (w < 0) return;
            int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
            // The player who just placed the head built it: take the blocks off their own count, so golems never make
            // anyone a builder of the build they stand in. Anyone else's: an anonymous loss.
            UUID by = sameSummon(headTick, headW, headX, headY, headZ, Bukkit.getCurrentTick(), w, x, y, z) ? headBy : null;
            take(wd, w, x, y, z, by);   // the spawn block is always part of the pattern
            if (h == 3 && mat.head[wd.getBlockAt(x, y + 2, z).getType().ordinal()]
                    && mat.body[wd.getBlockAt(x, y + 1, z).getType().ordinal()] == mat.summonBody[r]) {   // upright build only
                take(wd, w, x, y + 1, z, by);
                take(wd, w, x, y + 2, z, by);
            }
        } catch (Exception x) { errors++; } catch (LinkageError x) { owner.stopRecorder(x); }
    }

    // ------------------------------------------------------------ production at builds (it can fire many times a second)
    /** A mob or animal died, by any cause: counted for the builds of its chunk. Never a world read, never a chunk load. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        if (owner.stoppedBy != null) return;
        try {
            Entity en = e.getEntity();
            Map<UUID, Production.Watch> w = owner.io.watch;
            if (en instanceof Player || w.isEmpty() || owner.prod == null) return;
            owner.prod.count(w, en.getWorld().getUID(), (int) Math.floor(en.getX()) >> 4, (int) Math.floor(en.getZ()) >> 4, en.getType().ordinal());
        } catch (Exception x) { errors++; } catch (LinkageError x) { owner.stopRecorder(x); }
    }

    /** The head placed at (hx, hy, hz) in tick ht finished the pattern spawning at (x, y, z) in tick t. */
    static boolean sameSummon(int ht, int hw, int hx, int hy, int hz, int t, int w, int x, int y, int z) {
        return ht >= 0 && ht == t && hw == w && Math.abs(hx - x) <= 1 && Math.abs(hz - z) <= 1 && hy >= y && hy <= y + 2;
    }

    private void take(World wd, int w, int x, int y, int z, UUID by) {
        int o = wd.getBlockAt(x, y, z).getType().ordinal();
        if (mat.cls[o] != KeyRules.BUILT) return;
        if (by != null) mm.unplace(by, headName, w, x, y, z, KeyRules.BUILT, mat.keyAs[o]);
        else mm.lose("summon", null, w, x, y, z, KeyRules.BUILT, mat.keyAs[o]);
    }
}
