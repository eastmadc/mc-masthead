package io.github.eastmadc.masthead.collect;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Being near someone's spawn point does not make a place theirs (two players can share a house and its beds). A container with no placed_by stamp gives no owner-named line, and nor does building or
 * mining near a spawn point. A stamped container still does (the control: the instrument sees a known-good case).
 * The Blotter is made without its constructor (which needs a running server); the event objects are interface proxies.
 */
public final class BlotterOwnerTest {
    static int fails;
    static void t(boolean ok, String what) { if (!ok) { fails++; System.out.println("FAIL " + what); } else System.out.println("ok   " + what); }

    @SuppressWarnings("unchecked")
    static <T> T fake(Class<T> c, Map<String, Function<Object[], Object>> m) {
        return (T) Proxy.newProxyInstance(BlotterOwnerTest.class.getClassLoader(), new Class<?>[]{c}, (p, meth, a) -> {
            Function<Object[], Object> f = m.get(meth.getName());
            if (f != null) return f.apply(a);
            if (meth.getName().equals("equals")) return p == a[0];
            if (meth.getName().equals("hashCode")) return System.identityHashCode(p);
            Class<?> r = meth.getReturnType();
            if (!r.isPrimitive() || r == void.class) return null;   // (no ?: chain here: it would unbox the null)
            if (r == boolean.class) return false;
            if (r == long.class) return 0L;
            if (r == double.class) return 0.0;
            if (r == float.class) return 0f;
            return 0;
        });
    }

    static Player player(String name, UUID id) {
        return fake(Player.class, Map.of("getName", a -> name, "getUniqueId", a -> id));
    }

    public static void main(String[] args) throws Exception {
        List<String> logged = new ArrayList<>();
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.setLevel(Level.ALL);
        log.addHandler(new Handler() {
            @Override public void publish(LogRecord r) { logged.add(r.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        Plugin plugin = fake(Plugin.class, Map.of("getLogger", a -> log));

        Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        Object unsafe = uf.get(null);
        Blotter b = (Blotter) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Blotter.class);
        set(b, "plugin", plugin);
        set(b, "ownerKey", new NamespacedKey("masthead", "placed_by"));
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        Map<UUID, Location> homes = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        set(b, "counts", counts);
        set(b, "homes", homes);
        set(b, "names", names);
        set(b, "open", new HashMap<>());

        World w = fake(World.class, Map.of());
        UUID aId = UUID.randomUUID(), bId = UUID.randomUUID();
        names.put(aId, "Player_A");
        names.put(bId, "Player_B");
        homes.put(aId, new Location(w, 100, 64, 100));   // A's spawn is in the pb's house (he slept in the pb's bed)
        Player pb = player("Player_B", bId);
        Location here = new Location(w, 103, 64, 102);     // three blocks from A's spawn

        // an unstamped chest in the pb's house, near A's spawn
        PersistentDataContainer noStamp = fake(PersistentDataContainer.class, Map.of());
        Chest chest = fake(Chest.class, Map.of("getPersistentDataContainer", a -> noStamp, "getLocation", a -> here,
                "getType", a -> Material.CHEST));
        Inventory inv1 = fake(Inventory.class, Map.of("getContents", a -> new ItemStack[0], "getHolder", a -> chest));
        InventoryView view1 = fake(InventoryView.class, Map.of("getPlayer", a -> pb, "getTopInventory", a -> inv1));
        b.onOpen(new InventoryOpenEvent(view1));
        b.onClose(new InventoryCloseEvent(view1));
        t(counts.isEmpty(), "unstamped chest near another player's spawn: no line " + counts);

        // building and mining near A's spawn: no "A's place" line
        Block blk = fake(Block.class, Map.of("getLocation", a -> here, "getWorld", a -> w, "getType", a -> Material.STONE));
        b.onPlace(new BlockPlaceEvent(blk, null, blk, null, pb, true, org.bukkit.inventory.EquipmentSlot.HAND));
        b.onBreak(new BlockBreakEvent(blk, pb));
        t(counts.isEmpty(), "building and mining near another player's spawn: no line " + counts);

        // control: the same chest stamped by A is A's, and the line says so
        PersistentDataContainer stamp = fake(PersistentDataContainer.class, Map.of("get", a -> aId.toString()));
        Chest stamped = fake(Chest.class, Map.of("getPersistentDataContainer", a -> stamp, "getLocation", a -> here,
                "getType", a -> Material.CHEST));
        Inventory inv2 = fake(Inventory.class, Map.of("getContents", a -> new ItemStack[0], "getHolder", a -> stamped));
        InventoryView view2 = fake(InventoryView.class, Map.of("getPlayer", a -> pb, "getTopInventory", a -> inv2));
        b.onOpen(new InventoryOpenEvent(view2));
        t(counts.keySet().equals(java.util.Set.of("opened_container\tPlayer_B\tPlayer_A\tchest")), "stamped chest: owner named " + counts);
        // a stamped chest a hopper feeds is a farm's output; taking from it is using the farm, not news
        counts.clear();
        org.bukkit.block.data.type.Hopper down = fake(org.bukkit.block.data.type.Hopper.class, Map.of("getFacing", a -> org.bukkit.block.BlockFace.DOWN));
        Block hopper = fake(Block.class, Map.of("getType", a -> Material.HOPPER, "getBlockData", a -> down));
        Block air = fake(Block.class, Map.of("getType", a -> Material.AIR));
        Block fed = fake(Block.class, Map.of("getType", a -> Material.CHEST, "getRelative", a -> a[0] == org.bukkit.block.BlockFace.UP ? hopper : air));
        Chest farmChest = fake(Chest.class, Map.of("getPersistentDataContainer", a -> stamp, "getLocation", a -> here,
                "getType", a -> Material.CHEST, "getBlock", a -> fed));
        Inventory inv3 = fake(Inventory.class, Map.of("getContents", a -> new ItemStack[0], "getHolder", a -> farmChest));
        b.onOpen(new InventoryOpenEvent(fake(InventoryView.class, Map.of("getPlayer", a -> pb, "getTopInventory", a -> inv3))));
        t(counts.isEmpty(), "hopper-fed chest (farm output): no line " + counts);
        Block sideways = fake(Block.class, Map.of("getType", a -> Material.CHEST, "getRelative", a -> a[0] == org.bukkit.block.BlockFace.NORTH ? hopper : air));
        Chest plain = fake(Chest.class, Map.of("getPersistentDataContainer", a -> stamp, "getLocation", a -> here,
                "getType", a -> Material.CHEST, "getBlock", a -> sideways));
        Inventory inv4 = fake(Inventory.class, Map.of("getContents", a -> new ItemStack[0], "getHolder", a -> plain));
        b.onOpen(new InventoryOpenEvent(fake(InventoryView.class, Map.of("getPlayer", a -> pb, "getTopInventory", a -> inv4))));
        t(counts.size() == 1, "a hopper beside it pointing down (not into it) does not make it a farm chest: " + counts);
        counts.clear();
        // someone else's hopper aimed into the owner's chest does not hide what is taken from it
        PersistentDataContainer otherStamp = fake(PersistentDataContainer.class, Map.of("get", a -> bId.toString()));
        org.bukkit.block.Hopper hopState = fake(org.bukkit.block.Hopper.class, Map.of("getPersistentDataContainer", a -> otherStamp));
        Block theirHopper = fake(Block.class, Map.of("getType", a -> Material.HOPPER, "getBlockData", a -> down, "getState", a -> hopState));
        Block fedByThem = fake(Block.class, Map.of("getType", a -> Material.CHEST, "getRelative", a -> a[0] == org.bukkit.block.BlockFace.UP ? theirHopper : air));
        Chest victim = fake(Chest.class, Map.of("getPersistentDataContainer", a -> stamp, "getLocation", a -> here,
                "getType", a -> Material.CHEST, "getBlock", a -> fedByThem));
        Inventory inv5 = fake(Inventory.class, Map.of("getContents", a -> new ItemStack[0], "getHolder", a -> victim));
        b.onOpen(new InventoryOpenEvent(fake(InventoryView.class, Map.of("getPlayer", a -> pb, "getTopInventory", a -> inv5))));
        t(counts.size() == 1, "a hopper placed by someone else does not make the owner's chest a farm chest: " + counts);
        counts.clear();
        // tamed animals have a real owner (Tameable.getOwner): riding or leashing someone's animal is a line; your own is not
        counts.clear();
        org.bukkit.entity.Player pa = player("Player_A", aId);
        org.bukkit.entity.Horse horse = fake(org.bukkit.entity.Horse.class, Map.of("isTamed", a -> true, "getOwner", a -> pa,
                "getType", a -> org.bukkit.entity.EntityType.HORSE));
        org.bukkit.entity.Wolf wolf = fake(org.bukkit.entity.Wolf.class, Map.of("isTamed", a -> true, "getOwner", a -> pa,
                "getType", a -> org.bukkit.entity.EntityType.WOLF));
        b.onMount(new org.bukkit.event.entity.EntityMountEvent(pb, horse));
        b.onLeash(new org.bukkit.event.entity.PlayerLeashEntityEvent(wolf, pb, pb, org.bukkit.inventory.EquipmentSlot.HAND));
        b.onMount(new org.bukkit.event.entity.EntityMountEvent(pa, horse));
        b.onLeash(new org.bukkit.event.entity.PlayerLeashEntityEvent(wolf, pa, pa, org.bukkit.inventory.EquipmentSlot.HAND));
        org.bukkit.entity.Wolf wild = fake(org.bukkit.entity.Wolf.class, Map.of("getType", a -> org.bukkit.entity.EntityType.WOLF));
        b.onLeash(new org.bukkit.event.entity.PlayerLeashEntityEvent(wild, pb, pb, org.bukkit.inventory.EquipmentSlot.HAND));
        t(counts.keySet().equals(java.util.Set.of("rode_horse\tPlayer_B\tPlayer_A\thorse", "leashed_pet\tPlayer_B\tPlayer_A\twolf")),
                "someone else's tamed horse and wolf: lines; your own or a wild one: none " + counts);
        t(logged.isEmpty(), "no handler failed quietly " + logged);

        if (fails > 0) { System.out.println(fails + " failed"); System.exit(1); }
        System.out.println("BlotterOwnerTest: ok");
    }

    static void set(Object o, String f, Object v) throws Exception {
        Field fd = Blotter.class.getDeclaredField(f);
        fd.setAccessible(true);
        try {
            fd.set(o, v);
        } catch (IllegalAccessException e) {   // final field: write it the Unsafe way
            Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            Object u = uf.get(null);
            long off = (long) u.getClass().getMethod("objectFieldOffset", Field.class).invoke(u, fd);
            u.getClass().getMethod("putObject", Object.class, long.class, Object.class).invoke(u, o, off, v);
        }
    }
}
