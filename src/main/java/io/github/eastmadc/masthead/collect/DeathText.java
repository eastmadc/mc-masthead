package io.github.eastmadc.masthead.collect;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.HoverEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Takes the words players type out of a death message before it is stored: a mob's name tag and a renamed weapon become
 * the plain type ("was slain by Zombie", "[Trident]"). Players can write anything in a name tag ("come to 120 64 -300", a
 * slur), and the paper only prints facts. Player names stay. Pure Adventure, no server needed (tested in DeathTextTest).
 *
 * <p>How it finds them: the game builds a death message from pieces, and every mob or item piece carries a hover card
 * saying what it is. A mob piece whose text is not the game's own name for that mob type had a name tag; an item piece only
 * appears when the item was renamed.
 */
public final class DeathText {
    private DeathText() {}

    /** The message with every non-player mob name and every item name replaced by its type. */
    public static Component scrub(Component c) {
        HoverEvent<?> h = c.hoverEvent();
        if (h != null && h.action() == HoverEvent.Action.SHOW_ENTITY && h.value() instanceof HoverEvent.ShowEntity se) {
            if (se.type().asString().equals("minecraft:player")) return c;
            // the game's own name for the type is a translation key "entity.<ns>.<type>..." with nothing added: keep it
            if (c instanceof TranslatableComponent t && t.key().startsWith("entity.") && c.children().isEmpty()) return c;
            return Component.text(pretty(se.type().value()));
        }
        if (h != null && h.action() == HoverEvent.Action.SHOW_ITEM && h.value() instanceof HoverEvent.ShowItem si)
            return Component.text("[" + pretty(si.item().value()) + "]");
        Component out = c;
        if (c instanceof TranslatableComponent t && !t.arguments().isEmpty()) {
            List<Component> args = new ArrayList<>();
            for (TranslationArgument a : t.arguments())
                args.add(a.value() instanceof Component ac ? scrub(ac) : Component.text(String.valueOf(a.value())));
            out = t.arguments(args);
        }
        if (!c.children().isEmpty()) {
            List<Component> kids = new ArrayList<>();
            for (Component k : c.children()) kids.add(scrub(k));
            out = out.children(kids);
        }
        return out;
    }

    /**
     * Second net, for messages built without hover cards (another plugin's death messages): replace a name we know was
     * typed (a mob's name tag, a renamed item) after the victim's name. names: {typed name, replacement} pairs.
     */
    public static String replaceTyped(String text, String victim, List<String[]> names) {
        String head = text.startsWith(victim + " ") ? victim + " " : "";
        String rest = text.substring(head.length());
        for (String[] n : names) {
            if (n[0] == null || n[0].isBlank()) continue;
            rest = rest.replace("[" + n[0] + "]", "[" + n[1] + "]").replace(n[0], n[1]);
        }
        return head + rest;
    }

    /** "minecraft:cave_spider" or "cave_spider" -> "Cave Spider". */
    public static String pretty(String key) {
        String k = key.substring(key.indexOf(':') + 1);
        StringBuilder b = new StringBuilder();
        for (String w : k.split("[_./]+")) {
            if (w.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return b.isEmpty() ? "Something" : b.toString();
    }
}
