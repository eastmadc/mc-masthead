package io.github.eastmadc.masthead.collect;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.HoverEvent;

import java.util.List;
import java.util.UUID;

/**
 * T5: a death message as the game builds it (pieces with hover cards) loses the name tag and the renamed weapon before it
 * is stored. Plain main(); throws on the first failure.
 */
public final class DeathTextTest {
    public static void main(String[] args) {
        Component victim = Component.text("Alice_1").hoverEvent(HoverEvent.showEntity(Key.key("minecraft:player"), UUID.randomUUID(), Component.text("Alice_1")));
        Component namedZombie = Component.text("base 1200 -340").hoverEvent(HoverEvent.showEntity(Key.key("minecraft:zombie"), UUID.randomUUID(), Component.text("base 1200 -340")));
        Component sword = Component.translatable("chat.square_brackets", Component.text("Bad Word 777")).hoverEvent(HoverEvent.showItem(Key.key("minecraft:diamond_sword"), 1));
        Component msg = Component.translatable("death.attack.mob.item", victim, namedZombie, sword);
        String all = texts(DeathText.scrub(msg));
        check(!all.contains("1200") && !all.contains("Bad Word") && !all.contains("777"), "typed names gone: " + all);
        check(all.contains("Alice_1") && all.contains("Zombie") && all.contains("[Diamond Sword]"), "victim kept, types in: " + all);

        // a mob without a name tag: the game's own type name (a translation key) is left exactly as it was
        Component plainSkeleton = Component.translatable("entity.minecraft.skeleton").hoverEvent(HoverEvent.showEntity(Key.key("minecraft:skeleton"), UUID.randomUUID()));
        Component m2 = Component.translatable("death.attack.arrow", victim, plainSkeleton);
        check(DeathText.scrub(m2).equals(m2), "unnamed mob unchanged");

        // a named wolf hidden inside a team prefix wrapper (children), a player killer kept
        Component bob = Component.text("Bob_22").hoverEvent(HoverEvent.showEntity(Key.key("minecraft:player"), UUID.randomUUID()));
        Component wolf = Component.text("").append(Component.text("[Team] ")).append(Component.text("Mr Fluffy"))
                .hoverEvent(HoverEvent.showEntity(Key.key("minecraft:wolf"), UUID.randomUUID()));
        String t3 = texts(DeathText.scrub(Component.translatable("death.attack.mob", victim, wolf)));
        check(!t3.contains("Fluffy") && !t3.contains("Team") && t3.contains("Wolf"), "wrapped name tag: " + t3);
        check(texts(DeathText.scrub(Component.translatable("death.attack.player", victim, bob))).contains("Bob_22"), "player killer kept");

        // second net (no hover cards): replace the typed names we know about, never the victim's own name
        check(DeathText.replaceTyped("Alice_1 was slain by base 1200 using [Bad Word 777]", "Alice_1",
                List.of(new String[]{"base 1200", "Zombie"}, new String[]{"Bad Word 777", "Diamond Sword"}))
                .equals("Alice_1 was slain by Zombie using [Diamond Sword]"), "replaceTyped");
        check(DeathText.pretty("minecraft:cave_spider").equals("Cave Spider") && DeathText.pretty("trident").equals("Trident"), "pretty");
        ipPlayers();
        System.out.println("DeathTextTest PASS");
    }

    /** Every piece of literal text in the component, including translation arguments. */
    /** CC-9 / P9: ip-players.txt keeps a pair 60 days after it was last seen; older files are read without loss. */
    static void ipPlayers() {
        java.time.LocalDate today = java.time.LocalDate.parse("2026-11-05");
        java.util.Map<String, java.time.LocalDate> seen = new java.util.LinkedHashMap<>();
        Recorder.parseIps(seen, java.util.List.of("1.2.3.4 Alice_1", "# 2026-11-01", "5.6.7.8 Bob_22", "# 2026-08-01", "9.9.9.9 Carol_3",
                "1.2.3.4 Alice_1", "not a line", "# 2026-13-99", "2.2.2.2 Dan_4"), today);
        check(seen.get("1.2.3.4 Alice_1").equals(today), "a pair from the old format starts its 60 days today (newest date wins)");
        check(seen.get("5.6.7.8 Bob_22").toString().equals("2026-11-01") && seen.get("2.2.2.2 Dan_4").equals(today), "dates read; a bad date line counts as today");
        check(Recorder.pruneIps(seen, today) && !seen.containsKey("9.9.9.9 Carol_3") && seen.size() == 3, "older than 60 days dropped");
        check(!Recorder.pruneIps(seen, today), "nothing more to drop");
        String txt = Recorder.ipsText(seen);
        check(txt.equals("# 2026-11-05\n1.2.3.4 Alice_1\n2.2.2.2 Dan_4\n# 2026-11-01\n5.6.7.8 Bob_22\n"), "file text: " + txt);
        // what an older version (and the readership reader) sees: exactly "address name" lines; date lines never match
        for (String l : txt.split("\n")) check(l.startsWith("# ") || l.matches("[0-9a-fA-F:.]{2,45} \\.?[A-Za-z0-9_]{3,16}"), "line readable by 2.0.1: " + l);
        java.util.Map<String, java.time.LocalDate> again = new java.util.LinkedHashMap<>();
        Recorder.parseIps(again, java.util.Arrays.asList(txt.split("\n")), today.plusDays(10));
        check(again.equals(seen), "round trip keeps the dates");
        check(Recorder.pruneIps(again, java.time.LocalDate.parse("2027-01-01")) && again.size() == 2 && !again.containsKey("5.6.7.8 Bob_22"), "61 days later Bob's pair goes");
    }

    static String texts(Component c) {
        StringBuilder b = new StringBuilder();
        if (c instanceof TextComponent t) b.append(t.content()).append(' ');
        if (c instanceof TranslatableComponent t) {
            b.append('{').append(t.key()).append("} ");
            for (TranslationArgument a : t.arguments()) if (a.value() instanceof Component ac) b.append(texts(ac));
        }
        for (Component k : c.children()) b.append(texts(k));
        return b.toString();
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
