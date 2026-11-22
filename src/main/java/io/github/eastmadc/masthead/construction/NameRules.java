package io.github.eastmadc.masthead.construction;

import io.github.eastmadc.masthead.edition.ConstructionWords;
import io.github.eastmadc.masthead.edition.MailDesk;
import io.github.eastmadc.masthead.edition.WordGate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Names players give their builds. Strict on purpose: no digits or number words, places, player names, family or school words, put-downs,
 * swearing or instruction-shaped words. A word list can't spot a real first name; the one-paper hold and the owner's push
 * are the answer to that.
 */
public final class NameRules {
    private NameRules() {}

    private static final Pattern SHAPE = Pattern.compile("[A-Za-z][A-Za-z' -]{2,23}");
    private static final Pattern FAMILY = words("(?:mom|mum|mother|dad|father|parent|son|daughter|brother|sister|grandma|grandpa|family|uncle|aunt"
            + "|cousin|school|teacher|class|street|address|home address)(?:s|es|'s)?");
    public static final Pattern INSTRUCTION = words("ignore|instruction|instructions|prompt|system|assistant|rules|json|write|print|say");
    /** A position in words ("X Ninety Z Forty Base"): number words, minus, k, or a lone x, y or z. */
    private static final Pattern NUMBER = words(ConstructionWords.NUMBER_WORDS + "|minus|negative|k|x|y|z");
    /** The same written joined or misspelled ("Xninety", "Ninetyfive", "Fourty", "Hundreds"); "Zone" is a word, not z one. */
    private static final Pattern NUMBER_JOINED = Pattern.compile("(?<![a-z])[xyz]?(?:minus|negative)?(?:" + ConstructionWords.NUMBER_WORDS
            + "|fourty)+s?(?![a-z])");
    public static final Pattern PUT_DOWN = words("(?:noob|newb|loser|trash|garbage|dumb|stupid|cringe|lame|bad|worst|sucks|suck|poop|baby|weird"
            + "|idiot|ugly|fat|smelly|stinky|clown|bozo|dork|nerd)s?");
    private static final Pattern GATE_W;
    private static final Pattern GATE_W_CASE = ConstructionWords.regex(ConstructionWords.CASE_SENSITIVE, true);
    /** Place words checked inside joined or plural words too ("Spawntown", "Twin Lakes Lodge"): letters only, 4+ long, plus sea and sky. */
    private static final List<String> PLACE_SQUASHED = new ArrayList<>(List.of("sea", "sky"));
    static {
        for (List<String> l : List.of(ConstructionWords.PLACE, ConstructionWords.TERRAIN, ConstructionWords.STRUCTURE))
            for (String w : l) { String k = w.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", ""); if (k.length() >= 4) PLACE_SQUASHED.add(k); }
    }
    static {
        List<String> w = new ArrayList<>(ConstructionWords.gateWords());
        w.addAll(ConstructionWords.SECTION_ONLY);
        w.addAll(ConstructionWords.GATE_C);
        GATE_W = ConstructionWords.regex(w, false);
    }

    private static Pattern words(String alt) {
        return Pattern.compile("(?<![a-z])(?:" + alt + ")(?![a-z])", Pattern.CASE_INSENSITIVE);
    }

    /** Aimed at someone, or about hurting: "Kill Yourself", "Jake Is Gay", "Hate Robin". */
    private static final Pattern AIMED = words("you|your|yours|yourself|ur|u|die|dies|died|dead|death|kill|kills|killed|hang|unalive|hate|hates"
            + "|gay|crush|smells?");
    /** Biomes and structures the gate lists don't name. */
    private static final Pattern BIOME = words("deep dark|pale garden|ice spikes|plains|forests?|groves?|meadows?|igloos?|woodlands?|dripstone"
            + "|lush|shipwrecks?|temples?|pyramids?|ruins|geodes?|dungeons?");

    /** A sign names a build only if it reads like a place: a place word or a possessive. "EGGS IN", "ARMOR" never do. */
    private static final Pattern SIGN_PLACE = words("house|home|base|farm|hall|library|shop|store|tower|castle|dept|station|lab|barn"
            + "|garden|temple|school|inn|tavern|market|bank|hotel|museum|arena|fort|lodge|cabin|mill|dock|lighthouse|workshop|forge|smithy"
            + "|stable|bakery|cafe|pub|[a-z]+'s");

    /** A sign's text as a build name (title case if it was all one case), or null. Same rules as /news build name. */
    public static String signName(String raw, Collection<String> knownPlayers) {
        if (raw == null || !SIGN_PLACE.matcher(raw).find()) return null;
        String n = raw.strip().replaceAll("\\s+", " ");
        if (n.equals(n.toLowerCase(Locale.ROOT)) || n.equals(n.toUpperCase(Locale.ROOT))) {
            StringBuilder b = new StringBuilder();
            for (String w : n.toLowerCase(Locale.ROOT).split(" "))
                b.append(b.isEmpty() ? "" : " ").append(w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
            n = b.toString();
        }
        return check(n, knownPlayers, "clean");
    }

    /** The name tidied (trimmed, single spaces), or null if it can't be used. */
    public static String check(String raw, Collection<String> knownPlayers, String language) {
        if (raw == null) return null;
        String n = raw.strip().replaceAll("\\s+", " ");
        if (!SHAPE.matcher(n).matches() || n.split(" ").length > 5) return null;
        if (n.equalsIgnoreCase("clear")) return null;
        String low = n.toLowerCase(Locale.ROOT), squashed = low.replaceAll("[^a-z]", "");
        if (AIMED.matcher(n).find() || BIOME.matcher(n).find() || MailDesk.WELLBEING.matcher(n).find()) return null;
        for (String p : knownPlayers) {
            String pl = p.toLowerCase(Locale.ROOT).replaceAll("^\\.+", "");
            if (pl.length() >= 3 && (low.contains(pl) || squashed.contains(pl.replaceAll("[^a-z]", "")) && pl.replaceAll("[^a-z]", "").length() >= 3)) return null;
        }
        if (!WordGate.bad(n, List.of(), true, "clean").isEmpty() || !WordGate.floor(n, List.of()).isEmpty()) return null;
        if (GATE_W.matcher(n).find() || GATE_W_CASE.matcher(n).find()) return null;
        for (String w : PLACE_SQUASHED) if (squashed.contains(w)) return null;   // refusing a good name is the safe side
        if (FAMILY.matcher(n).find() || INSTRUCTION.matcher(n).find() || PUT_DOWN.matcher(n).find() || NUMBER.matcher(n).find()) return null;
        for (java.util.regex.Matcher m = NUMBER_JOINED.matcher(low); m.find(); ) if (!m.group().matches("zones?")) return null;
        return n;
    }
}
