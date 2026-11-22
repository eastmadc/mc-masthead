package io.github.eastmadc.masthead.construction;

import io.github.eastmadc.masthead.config.Settings;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.block.SignChangeEvent;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.eastmadc.masthead.construction.ConstructionTest.ALEX;
import static io.github.eastmadc.masthead.construction.ConstructionTest.STEVE;
import static io.github.eastmadc.masthead.construction.ConstructionTest.X;
import static io.github.eastmadc.masthead.construction.ConstructionTest.Y;
import static io.github.eastmadc.masthead.construction.ConstructionTest.Z;

/** construction.sign-names: a builder's sign names their build, only through NameRules, and never over a /news build name. */
public final class SignNamesTest {
    static int ok;

    static void t(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
        ok++;
    }

    static Settings.Construction cons(String level, boolean signNames) {
        return new Settings.Construction("Planning & Zoning", true, level, true, 3, 64, "blotter", 100, 15, 30, List.of(), 20000, List.of(), "standard", false, signNames);
    }

    /** Steve's house, folded; then the sign (and an optional /news build name first); folded again. */
    static ConstructionTest.Sc signed(String signer, String text, int x, int y, int z, boolean on, String nameFirst) throws Exception {
        ConstructionTest.Sc s = new ConstructionTest.Sc().notice(STEVE, ALEX);
        s.c = cons("public", on);
        ConstructionTest.house(s, STEVE, X, Y, Z);
        s.fold();
        if (nameFirst != null) s.req(STEVE, "name", nameFirst);
        s.reqs.add(RawLine.request(s.id(signer), "sign", ConstructionTest.Sc.WORLD, SiteIndex.cubeKey(x >> 2, y >> 2, z >> 2), text, null));
        s.fold();
        return s;
    }

    static String name(ConstructionTest.Sc s) { return s.idx.sites.firstEntry().getValue().name; }

    public static void main(String[] args) throws Exception {
        rules();
        fold();
        handler();
        System.out.println("SignNamesTest: " + ok + " checks passed");
    }

    static void rules() {
        List<String> known = List.of(STEVE, ALEX);
        Map<String, String> good = Map.of("ye olde shop", "Ye Olde Shop", "MAIL DEPT", "Mail Dept", "pixels house", "Pixels House", "Pixel's House", "Pixel's House",
                "Spruce Hotel", "Spruce Hotel");
        for (Map.Entry<String, String> e : good.entrySet())
            t(e.getValue().equals(NameRules.signName(e.getKey(), known)), "sign accepted: " + e.getKey() + " -> " + NameRules.signName(e.getKey(), known));
        for (String bad : List.of("EGGS IN", "AUTO FEED", "ARMOR", "iron", "noob house", "ignore the rules hall", "fuck house", "Steve_Builder house",
                "stupid shop", "kill yourself house", "home 123", "", "shoplibrary"))
            t(NameRules.signName(bad, known) == null, "sign refused: " + bad);
        // a position in words is still a position; words that only contain a number word are fine
        for (String bad : List.of("x ninety z forty base", "Base Minus Forty", "Minus Nine Hundred Shop", "Two Thousand Base", "Forty-Two Shop", "Z Shop", "K Base",
                "Xninety Zforty Base", "Ninetyfive Shop", "Twelvehundred Base", "Fourty Base", "Hundreds Base"))
            t(NameRules.signName(bad, known) == null && NameRules.check(bad, known, "clean") == null, "number words refused: " + bad);
        for (String fine : List.of("Often Shop", "Tent Shop", "Xylo's Shop", "Zone Shop", "Kitchen Shop", "Stone Shop", "Money Shop", "Zones Shop"))
            t(fine.equals(NameRules.signName(fine, known)), "whole words only: " + fine + " -> " + NameRules.signName(fine, known));
    }

    static void fold() throws Exception {
        ConstructionTest.Sc s = signed(STEVE, "ye olde shop", X + 5, Y + 2, Z + 1, true, null);
        SiteIndex.Site site = s.idx.sites.firstEntry().getValue();
        t("Ye Olde Shop".equals(site.name) && site.nameFrom != null && s.id(STEVE).toString().equals(site.nameBy), "a crew member's sign names the build: " + site.name);
        t(s.names().size() == 1 && s.names().get(0).contains("\"Ye Olde Shop\", from a sign by Steve_Builder"), "the owner hears it first: " + s.names());
        t(!s.res.out().toString().contains("Olde"), "not printed in the paper that accepts it");
        s.req(STEVE, "name", "Spruce Hotel");
        s.fold();
        t("Spruce Hotel".equals(name(s)), "a later /news build name replaces a sign name");

        t("Mail Dept".equals(name(signed(STEVE, "MAIL DEPT", X + 5, Y + 2, Z + 1, true, null))), "MAIL DEPT -> Mail Dept");
        ConstructionTest.Sc label = signed(STEVE, "EGGS IN", X + 5, Y + 2, Z + 1, true, null);
        t(name(label) == null && label.outputs.stream().noneMatch(o -> o.contains("EGGS") || o.contains("Eggs")), "a label never names a build or shows up anywhere");
        t(name(signed(STEVE, "noob house", X + 5, Y + 2, Z + 1, true, null)) == null, "a put-down is refused");
        t(name(signed(ALEX, "ye olde shop", X + 5, Y + 2, Z + 1, true, null)) == null, "a signer not in the crew names nothing");
        t(name(signed(STEVE, "ye olde shop", X + 200, Y + 2, Z + 1, true, null)) == null, "a sign outside any build names nothing (not the signer's other build)");
        t(name(signed(STEVE, "ye olde shop", X + 5, Y + 2, Z + 1, false, null)) == null, "setting off: the fold ignores signs");
        t("Spruce Hotel".equals(name(signed(STEVE, "ye olde shop", X + 5, Y + 2, Z + 1, true, "Spruce Hotel"))), "a /news build name is never replaced by a sign");

        ConstructionTest.Sc priv = new ConstructionTest.Sc().notice(STEVE);
        priv.c = cons("public", true);
        ConstructionTest.house(priv, STEVE, X, Y, Z);
        priv.fold();
        priv.prefs.get(priv.id(STEVE).toString()).level = "private";
        priv.reqs.add(RawLine.request(priv.id(STEVE), "sign", ConstructionTest.Sc.WORLD, SiteIndex.cubeKey((X + 5) >> 2, (Y + 2) >> 2, (Z + 1) >> 2), "ye olde shop", null));
        priv.fold();
        t(name(priv) == null, "a private builder's sign names nothing");
    }

    /** The real handler: nothing written while the setting is off; the sign's text and cube when on; the fold names the build. */
    static void handler() throws Exception {
        ConstructionIOTest.Rig r = new ConstructionIOTest.Rig();
        r.c = cons("public", true);
        r.req(STEVE, "noticed", null);
        ConstructionTest.house(r.sc, STEVE, X, Y, Z);
        LocalDate d1 = ConstructionIOTest.D1;
        r.flush(d1);
        r.fold(d1, true);
        Construction c = ProductionTest.bare(r.io);
        Collector col = new Collector(c, c.mm, c.slots);
        World wd = ProductionTest.fake(World.class, Map.of("getUID", ConstructionTest.Sc.WORLD, "getName", "world", "getEnvironment", World.Environment.NORMAL,
                "isBedWorks", true, "isRespawnAnchorWorks", false));
        Block b = ProductionTest.fake(Block.class, Map.of("getWorld", wd, "getX", X + 5, "getY", Y + 2, "getZ", Z + 1));
        Player p = ProductionTest.fake(Player.class, Map.of("getGameMode", GameMode.SURVIVAL, "getUniqueId", r.sc.id(STEVE), "getName", STEVE,
                "hasMetadata", false));
        List<Component> lines = List.of(Component.text("ye olde"), Component.text("shop"), Component.empty(), Component.empty());
        String before = r.read("requests.jsonl");
        col.onSign(new SignChangeEvent(b, p, lines, Side.FRONT));
        r.io.exec.submit(() -> { }).get();
        t(java.util.Objects.equals(before, r.read("requests.jsonl")), "setting off: nothing recorded");
        ProductionTest.set(c, "settings", with(Settings.defaults(), cons("public", true)));
        col.onSign(new SignChangeEvent(b, p, lines, Side.FRONT));
        r.io.exec.submit(() -> { }).get();
        String rq = r.read("requests.jsonl");
        t(col.errors == 0 && rq.contains("\"op\":\"sign\"") && rq.contains("\"v\":\"ye olde shop\""), "setting on: the sign is recorded: " + rq);
        r.fold(d1.plusDays(1), true);
        t("Ye Olde Shop".equals(r.sites().sites.firstEntry().getValue().name), "the fold names the build from the recorded sign");
    }

    /** The same settings with another construction section. */
    static Settings with(Settings s, Settings.Construction c) throws Exception {
        RecordComponent[] rc = Settings.class.getRecordComponents();
        Object[] a = new Object[rc.length];
        Class<?>[] types = new Class<?>[rc.length];
        for (int i = 0; i < rc.length; i++) {
            types[i] = rc[i].getType();
            a[i] = rc[i].getName().equals("construction") ? c : rc[i].getAccessor().invoke(s);
        }
        return Settings.class.getDeclaredConstructor(types).newInstance(a);
    }
}
