package io.github.eastmadc.masthead.edition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Every word construction news must never use about a build, in one place: the writer's prompt prints these lists and the
 * draft gate builds its checks from the same lists, so the writer is told every word the gate rejects.
 */
public final class ConstructionWords {
    private ConstructionWords() {}

    public static final List<String> PLACE = List.of("north", "south", "east", "west", "northern", "southern", "eastern", "western",
            "northeast", "northwest", "southeast", "southwest", "spawn", "near", "nearby", "next to", "next door", "beside", "neighbor",
            "neighbors", "neighbour", "neighbours", "across from", "close to", "far from", "blocks away", "down the road", "overlooking",
            "outskirts", "downtown", "coordinates");
    public static final List<String> TERRAIN = List.of("biome", "desert", "jungle", "swamp", "badlands", "mesa", "savanna", "taiga",
            "tundra", "snowy", "frozen", "mushroom", "cherry", "mangrove", "mountain", "peak", "hill", "cliff", "valley", "river", "lake",
            "ocean", "sea", "beach", "coast", "shore", "island", "cave", "ravine", "underground", "underwater", "floating", "sky",
            "skyline", "clouds", "forest", "woods", "plains", "meadow", "grove", "snow", "ice", "pond", "waterfall", "harbor", "harbour", "reef");
    public static final List<String> STRUCTURE = List.of("village", "monument", "stronghold", "mansion", "ancient city", "trial chamber",
            "outpost", "fortress", "bastion", "end city", "end dimension", "portal", "nether", "overworld", "highway", "hub");
    /** Matched with this exact case: "the End" (the dimension), never "the end of your losing streak". */
    public static final List<String> CASE_SENSITIVE = List.of("the End");
    public static final List<String> RANKING = List.of("tallest", "biggest", "largest", "highest", "widest", "longest", "smallest",
            "tiniest", "worst", "best build", "best house", "best base");
    /** Jokes about how long a build takes (part of JUDGING). */
    public static final List<String> SCHEDULE = List.of("behind schedule", "ahead of schedule", "overdue", "delayed", "delay", "delays",
            "deadline", "deadlines", "no end in sight", "any day now", "someday", "estimated completion", "completion date",
            "yet to be finished", "yet to be completed", "not yet finished", "not yet done", "isn't finished", "isn't done",
            "hasn't finished", "never finished", "still not", "finally", "at last", "took long enough", "about time", "never ending",
            "endless", "ever be done", "ever be finished", "never be done", "never be finished", "won't be done", "won't be finished",
            "snail's pace", "glacial", "still under construction", "holding its breath");
    public static final List<String> JUDGING;
    static {
        List<String> j = new ArrayList<>(List.of("ugly", "eyesore", "monstrosity", "hideous", "abandoned", "unfinished", "incomplete",
                "stalled", "deserted", "unguarded", "nobody home", "gave up", "condemned", "violation", "illegal", "unpermitted", "rejected",
                "denied", "griefing", "griefed", "griefer", "slow", "boring", "not finished", "not done", "not complete", "half built",
                "half finished", "taking forever", "still building", "work in progress", "failed inspection", "not up to code",
                "up to code", "fined", "citation", "a mere", "barely", "only managed"));
        j.addAll(SCHEDULE);
        JUDGING = List.copyOf(j);
    }
    /** Checked only inside the construction section ("a small upset" is fine in Sports); a build always has a real kind. */
    public static final List<String> SECTION_ONLY = List.of("small", "structure", "unclassified", "classify", "classified", "declined to classify");
    /** Never anywhere in the paper once construction news prints. */
    public static final List<String> GATE_C = List.of("chunk", "coord", "coords", "coordinates", "Y level");
    /** Number words, as a regex alternation: a position can be spelled out ("x ninety z forty"). */
    public static final String NUMBER_WORDS = "zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen"
            + "|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|million";
    /** Scope (b) triggers: the four build verbs (each entry adds its kind word, ConstructionGate.kindWord). */
    public static final List<String> BUILD_VERBS = List.of("built", "building", "construction", "project");

    /** The case-insensitive gate words (place, terrain, structure, ranking, judging), in list order. */
    public static List<String> gateWords() {
        List<String> out = new ArrayList<>(PLACE);
        out.addAll(TERRAIN);
        out.addAll(STRUCTURE);
        out.addAll(RANKING);
        out.addAll(JUDGING);
        return out;
    }

    /** What the prompt prints: every gate word, "the End" with its note, and "small". */
    public static String promptList() {
        List<String> out = new ArrayList<>(PLACE);
        out.addAll(TERRAIN);
        out.addAll(STRUCTURE);
        for (String c : CASE_SENSITIVE) out.add(c + " (the dimension)");
        out.addAll(RANKING);
        out.addAll(JUDGING);
        out.addAll(SECTION_ONLY);
        return String.join(", ", out);
    }

    /** A section title the gate can print: some letters, and no word construction news may not use. */
    public static boolean titleOk(String title) {
        List<String> w = new ArrayList<>(gateWords());
        w.addAll(SECTION_ONLY);
        w.addAll(GATE_C);
        return !normalizeHeading(title).isEmpty() && !regex(w, false).matcher(title).find() && !regex(CASE_SENSITIVE, true).matcher(title).find();
    }

    /** Whole words; a space or hyphen inside a phrase matches [\s-]?, an apostrophe ['’]?. */
    public static Pattern regex(List<String> phrases, boolean caseSensitive) {
        List<String> alts = new ArrayList<>();
        for (String p : phrases) {
            StringBuilder b = new StringBuilder();
            for (char c : p.toCharArray()) {
                if (c == ' ' || c == '-') b.append("[\\s-]?");
                else if (c == '\'') b.append("['’]?");
                else b.append(Pattern.quote(String.valueOf(c)));
            }
            alts.add(b.toString());
        }
        alts.sort((a, b) -> Integer.compare(b.length(), a.length()));   // longest first, so "next to" beats "next"
        String re = "(?<![A-Za-z0-9_])(?:" + String.join("|", alts) + ")(?![A-Za-z0-9_])";
        return Pattern.compile(re, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
    }

    /** The word a regex match stands for (the list phrase it matched), lower case unless case-sensitive. */
    public static String phraseOf(String hit, List<String> phrases) {
        String h = hit.toLowerCase(Locale.ROOT).replaceAll("[\\s-]", "").replace("’", "").replace("'", "");
        for (String p : phrases)
            if (p.toLowerCase(Locale.ROOT).replaceAll("[\\s-]", "").replace("'", "").equals(h)) return p;
        return hit.toLowerCase(Locale.ROOT);
    }

    /**
     * A heading as the gate compares it: entities undone, lower case, & -> and, only letters, digits and spaces, single
     * spaces. "🏗️ Planning &amp; Zoning:" -> "planning and zoning".
     */
    public static String normalizeHeading(String s) {
        String t = Py.unescape(s == null ? "" : s).toLowerCase(Locale.ROOT).replace("&", " and ");
        t = t.replaceAll("[^\\p{L}\\p{Nd} ]", " ");
        return t.replaceAll("\\s+", " ").strip();
    }
}
