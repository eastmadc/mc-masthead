package io.github.eastmadc.masthead.edition;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word filter (port of bad_words / word_list / names_in). Whole words after NFKD with accents dropped, lowercasing,
 * look-alike letters folded and leetspeak undone; stretched ("shiiit"), starred ("f*ck"), spaced ("f u c k"), hyphened
 * ("f-ing") and compound ("bullshit") swears count too.
 * Game words (kill, die, dead, shot, slain, murder, blew, burn, drown, hoe, blaze, lit) are never listed.
 */
public final class WordGate {
    private WordGate() {}

    public static final Set<String> SEVERE = Set.of("fuck", "fucking", "fucker", "fucked", "fucks", "shit", "shitty", "bitch", "bitches",
            "cunt", "cunts", "dick", "dicks", "pussy", "whore", "slut", "porn", "nude", "nudes", "sex", "sexy", "rape", "raped", "nigger", "niggers", "nigga",
            "niggas", "faggot", "faggots", "fag", "fags", "retard", "retarded", "retards", "kys",
            // slurs: "sped" is also the past of speed, so it is a floor phrase instead
            "tranny", "trannies", "dyke", "dykes", "kike", "kikes", "chink", "chinks", "spic", "spics", "homo", "homos", "tard", "tards",
            "schizo", "schizos", "autist", "autists", "tism", "midget", "midgets", "cripple", "cripples",
            // British (paper.language en-GB asks the writer for British words)
            "wanker", "wankers", "twat", "twats", "bellend", "bellends", "tosser", "tossers", "bollocks", "arsehole", "arseholes");
    /** Mail only. "cock" stays off the edition list on purpose ("cock-a-doodle-doo"). */
    public static final Set<String> MAIL;
    /** Mail only, as runs of whole words: put-downs of a person. The paper may still say "nobody likes phantoms". */
    static final List<String> MAIL_PHRASES = List.of("nobody likes", "no one likes", "noone likes", "no friends", "like a girl");
    public static final Set<String> MILD = Set.of("damn", "dammit", "damnit", "hell", "crap", "crappy", "ass", "asshole", "bastard",
            "dumbass", "badass", "jackass", "smartass", "fatass", "kickass", "asshat", "asswipe", "ahole", "lmao", "hella", "helluva",
            "suck", "sucks", "sucked", "sucky", "arse", "bugger");
    /** Clean only, matched exactly (stretched, "asses" would be "assess"). */
    static final Set<String> MILD_EXACT = Set.of("asses");
    /** Acronyms and short spellings of stronger words than pg13 allows ("fk", "f-ing"): only with language=strong. */
    public static final Set<String> STRONG_ACRONYMS = Set.of("wtf", "af", "stfu", "lmfao", "fml", "mf", "mfs", "bs", "gtfo", "omfg", "ffs", "pos",
            "fck", "fk", "fkn", "fkin", "fking", "fcking", "fckin", "fcked", "effing", "fing");
    /** Allowed in the paper only with language=strong. */
    public static final Set<String> STRONG_OK = Set.of("fuck", "fucking", "fucker", "fucked", "fucks", "shit", "shitty");
    /** Roots caught inside any word ("bullshit", "fuckin", "pissed"), each letter stretchable: never innocent in a game paper. */
    static final List<String> STEMS_SEVERE = List.of("bitch");   // not "cunt": Scunthorpe
    /** Roots allowed only with language=strong. */
    static final List<String> STEMS_STRONG = List.of("fuck", "shit", "piss", "motherf");
    /** Roots caught only with language=clean ("goddamn", "damned"). */
    static final List<String> STEMS_MILD = List.of("damn", "goddam");

    static {
        Set<String> m = new HashSet<>(SEVERE);
        m.addAll(List.of("cock", "ass", "arse", "asshole", "bastard", "stfu", "idiot", "ugly", "fat", "worthless", "pathetic"));
        MAIL = Set.copyOf(m);
    }

    /** word_list(mail, lang) */
    public static Set<String> wordList(boolean mail, String language) {
        Set<String> base = new HashSet<>(mail ? MAIL : SEVERE);
        if ("clean".equals(language)) base.addAll(MILD);
        if (!"strong".equals(language)) base.addAll(STRONG_ACRONYMS);
        else base.removeAll(STRONG_OK);
        return base;
    }

    static List<String> stems(String language) {
        List<String> out = new ArrayList<>(STEMS_SEVERE);
        if (!"strong".equals(language)) out.addAll(STEMS_STRONG);
        if ("clean".equals(language)) out.addAll(STEMS_MILD);
        return out;
    }

    /**
     * Letters that look like Latin ones, folded after lowercasing: Cyrillic and Greek ("gуatt" with a Cyrillic у, "gүatt" with
     * a Cyrillic ү), the dotless ı and the script ɡ.
     */
    private static final String LOOK_FROM = "аеорсухкмтнвіјѕԁԛԝгүұıɡɩαβεικνορτυχγ", LOOK_TO = "aeopcyxkmthbijsdqwryyigiabeikvoptuxy";
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    /** Invisible format characters ("d\u200Bad", "gy\u00ADatt"): dropped before anything else, so they never split a word. */
    private static final Pattern FORMAT = Pattern.compile("\\p{Cf}+");

    /**
     * Invisible format characters dropped, then NFKD with accents and other marks dropped ("bïtch" is "bitch"), lowercased,
     * look-alike letters folded.
     */
    static String norm(String text) {
        String t = Py.lower(MARKS.matcher(Normalizer.normalize(FORMAT.matcher(text).replaceAll(""), Normalizer.Form.NFKD)).replaceAll(""));
        StringBuilder b = null;
        for (int i = 0; i < t.length(); i++) {
            int k = LOOK_FROM.indexOf(t.charAt(i));
            if (k < 0) continue;
            if (b == null) b = new StringBuilder(t);
            b.setCharAt(i, LOOK_TO.charAt(k));
        }
        return b == null ? t : b.toString();
    }

    /** Leetspeak undone. '!' and '|' are an i only between letters or digits ("b!tch", "b|tch"); elsewhere they are a gap ("hell!"). */
    static String leet(String s) {
        s = bangs(s, true);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(switch (c) {
                case '0' -> 'o';
                case '1' -> 'i';
                case '3' -> 'e';
                case '4' -> 'a';
                case '5', '$' -> 's';
                case '7' -> 't';
                case '@' -> 'a';
                default -> c;
            });
        }
        return b.toString();
    }

    /** '!' and '|' runs: between letters or digits an i (or kept, for the token split), elsewhere a space. */
    private static String bangs(String s, boolean asI) {
        if (s.indexOf('!') < 0 && s.indexOf('|') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c != '!' && c != '|') { b.append(c); i++; continue; }
            int j = i;
            while (j < s.length() && (s.charAt(j) == '!' || s.charAt(j) == '|')) j++;
            boolean inside = i > 0 && j < s.length() && wordChar(s.charAt(i - 1)) && wordChar(s.charAt(j));
            for (int k = i; k < j; k++) b.append(!inside ? ' ' : asI ? 'i' : s.charAt(k));
            i = j;
        }
        return b.toString();
    }

    private static boolean wordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '$' || c == '@';
    }

    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");
    private static final Pattern NON_WORD_U = Pattern.compile("[^a-z0-9_]+");
    private static final Pattern LEET_TOKEN = Pattern.compile("[^a-z0-9$@!|]+");
    /** "a-hole", "f-ing": one letter, a hyphen, then the rest of the word, read as one word too. */
    private static final Pattern HYPHENED = Pattern.compile("(?<![a-z0-9])([a-z])-([a-z]+)");
    /** A starred word: "f*ck", "sh*t", "f***", "motherf***er". */
    private static final Pattern STARRED = Pattern.compile("(?<![a-z0-9])[a-z]+\\*+[a-z*]*");

    /**
     * Listed words in text (sorted, distinct). Player names are removed first. Runs of 3+ single letters are joined
     * ("f u c k"), stretched letters count ("shiiit"), a listed root inside a word counts ("bullshit"), and a starred word
     * counts when its letters fit a listed word ("f*ck"). language: clean|pg13|strong.
     */
    public static List<String> bad(String text, Collection<String> names, boolean mail, String language) {
        Set<String> words = wordList(mail, language);
        List<String> roots = stems(language);
        TreeSet<String> hit = new TreeSet<>();
        List<String> toks = tokens(text, names);
        Matcher hy = HYPHENED.matcher(withoutNames(text, names));
        while (hy.find()) toks.add(hy.group(1) + hy.group(2));
        boolean clean = "clean".equals(language);
        for (String x : toks) {
            if (x.isEmpty()) continue;
            if (clean && MILD_EXACT.contains(x)) hit.add(x);
            for (String w : words) if (stretched(x, w)) hit.add(w);
            for (String r : roots) if (stemPattern(r).matcher(x).find()) hit.add(r);
        }
        if (mail) for (String p : MAIL_PHRASES) if (hasPhrase(toks, p)) hit.add(p);
        Matcher m = STARRED.matcher(withoutNames(text, names));
        while (m.find()) {
            String st = m.group();
            for (String w : words) if (fits(st, w, false)) hit.add(w);
            for (String r : roots) if (fits(st, r, true)) hit.add(r);
        }
        return new ArrayList<>(hit);
    }

    /** f+u+c+k+ for "fuck": the root anywhere in a word, any letter stretched. */
    private static final java.util.Map<String, Pattern> STEM_PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();

    private static Pattern stemPattern(String root) {
        return STEM_PATTERNS.computeIfAbsent(root, r -> {
            StringBuilder b = new StringBuilder();
            for (char c : r.toCharArray()) b.append(Pattern.quote(String.valueOf(c))).append('+');
            return Pattern.compile(b.toString());
        });
    }

    /**
     * Whether a starred word could be the listed word: each '*' stands for one letter. inside = the listed word (a root) may sit
     * anywhere in the starred word, with at least one '*' over it; otherwise the whole word must fit.
     */
    static boolean fits(String starred, String listed, boolean inside) {
        if (!inside) {
            if (starred.length() != listed.length()) return false;
            for (int i = 0; i < listed.length(); i++) if (starred.charAt(i) != '*' && starred.charAt(i) != listed.charAt(i)) return false;
            return true;
        }
        outer:
        for (int o = 0; o + listed.length() <= starred.length(); o++) {
            boolean star = false;
            for (int j = 0; j < listed.length(); j++) {
                char c = starred.charAt(o + j);
                if (c == '*') star = true;
                else if (c != listed.charAt(j)) continue outer;
            }
            if (star) return true;
        }
        return false;
    }

    /** Normalised text with player names blanked out (longest first). */
    static String withoutNames(String text, Collection<String> names) {
        String t = norm(text);
        List<String> sorted = new ArrayList<>(names);
        sorted.sort((a, b) -> Integer.compare(Py.len(b), Py.len(a)));   // stable, longest first
        for (String n : sorted) t = Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(Py.lower(n)) + "(?![a-z0-9_])").matcher(t).replaceAll(" ");
        return t;
    }

    /**
     * Player names in text: whole words, also with an "s" on the end (so "bob" does not match "bobbing"); names of 5+
     * letters are also caught when spaced out or with the underscore dropped. Leetspeak is undone first.
     */
    public static List<String> namesIn(String text, Collection<String> names) {
        String t = leet(norm(text));
        Set<String> toks = new HashSet<>(List.of(NON_WORD_U.split(t, -1)));
        String squashed = NON_WORD.matcher(t).replaceAll("");
        List<String> hit = new ArrayList<>();
        for (String n : names) {
            String k = Py.lower(n);
            int a = 0;
            while (a < k.length() && k.charAt(a) == '.') a++;
            k = leet(k.substring(a));
            String k2 = k.replace("_", "");
            if (toks.contains(k) || toks.contains(k2) || toks.contains(k + "s") || toks.contains(k2 + "s")   // "bobs base"
                    || (Py.len(k2) >= 5 && squashed.contains(k2))) hit.add(n);
        }
        return hit;
    }

    /** Player names in text as whole words only (no spaced or squashed match): "Steven" never names Steve. */
    public static List<String> namesExact(String text, Collection<String> names) {
        Set<String> toks = new HashSet<>(rawTokens(text));
        List<String> hit = new ArrayList<>();
        for (String n : names) {
            String k = nameKey(n);
            if (toks.contains(k) || toks.contains(k.replace("_", ""))) hit.add(n);
        }
        return hit;
    }

    /** The text's words with names kept whole (underscores stay), normalised and leetspeak undone, in order. */
    public static List<String> rawTokens(String text) {
        List<String> out = new ArrayList<>();
        for (String x : NON_WORD_U.split(leet(norm(text)), -1)) if (!x.isEmpty()) out.add(x);
        return out;
    }

    /** A player name as rawTokens spells it. */
    public static String nameKey(String name) {
        String k = Py.lower(name);
        int a = 0;
        while (a < k.length() && k.charAt(a) == '.') a++;
        return leet(k.substring(a));
    }

    /**
     * Whether a word mixes letters from outside English into English ones ("bıtch", "gүatt"): a printed letter with one is
     * held, since a look-alike letter is how a word gets past a filter. Accents ("café") are fine.
     */
    public static boolean mixedScript(String text) {
        String t = MARKS.matcher(Normalizer.normalize(FORMAT.matcher(text).replaceAll(""), Normalizer.Form.NFKD)).replaceAll("");
        for (String w : t.split("[^\\p{L}]+")) {
            boolean ascii = false, other = false;
            for (int i = 0; i < w.length(); i++) {
                char c = w.charAt(i);
                if (c < 128) ascii = true; else other = true;
            }
            if (ascii && other) return true;
        }
        return false;
    }

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?]|[.!?][\"'\u201d\u2019)\\]])\\s+|\\n+");

    /** The text split into sentences (after ". ", "! ", "? ", the same with a closing quote or bracket, or a line break). */
    public static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String x : SENTENCE_END.split(text)) if (!x.isBlank()) out.add(x);
        return out;
    }

    // ------------------------------------------------------------ the floor: never printed, at any setting

    /**
     * Words and phrases the paper never prints, whatever the tone settings, the audience or the owner's lexicon say (no key
     * lifts them). Matched as whole tokens after NFKD with accents dropped, lowercasing, folding look-alike letters, undoing
     * leetspeak and allowing stretched letters ("gyattt", "gy4tt", "g y a t t", "s!mp"; "goner" is not "gooner"); a single word
     * also with its endings ("simped", "mogger"); a phrase is a run of tokens. Words that are
     * innocent in the game's own text (high, baked, chopped, wasted, smash, pack, thick, snack, hoe, blaze, lit, sped, lean)
     * are left out or listed only as phrases.
     */
    public static final List<String> FLOOR = List.of(
            // sexual and body slang
            "gyatt", "gyat", "huzz", "rizz", "rizzed", "rizzing", "rizzler", "thicc", "goon", "gooning", "gooner", "seggs", "zesty", "clapped",
            "hawk tuah", "down bad", "body count", "a whole meal", "smash or pass", "slept with", "sleep with", "sleeping with",
            "thot", "thots", "milf", "freaky", "edging", "baddie", "baddies", "zaddy", "hoe phase", "goon cave",
            // beds: a spawn point is never a story about two players
            "slept in", "in bed with", "bed swap", "spawn swap", "share a bed", "sharing a bed", "shared a bed",
            // looks
            "mog", "mogs", "mogged", "mogging", "framemog", "looksmaxxing", "looksmaxx", "looksmax", "mewing", "malding", "low taper fade", "big back",
            // manosphere and gendered
            "sigma", "simp", "simping", "foid", "grindset", "pick me",   // not alpha or beta: build names ("Alpha Hall") and versions use them
            // slur stand-ins (the slurs themselves are in SEVERE)
            "clanker", "clankers", "that's gay", "thats gay", "is sped", "so sped",
            // ableist and self-harm
            "spaz", "psycho", "autistic", "bipolar", "ocd", "kms", "unalive", "unalived", "menty b", "touch grass", "touched grass", "touches grass",
            "touching grass", "nolife", "nolifer", "no lifer", "no life", "get a life",
            // identity mockery and political baggage
            "so gay", "no homo", "fruity", "ohio", "chud", "maga", "gay", "lesbian", "queer",
            // drugs and alcohol
            "zooted", "geeked", "tweaking", "tweakin", "copium", "hopium", "drunk", "stoned", "got high", "getting high", "so baked", "got wasted",
            "vape", "vapes", "vaping", "weed", "got blazed", "get blazed", "blaze it", "sipping lean", "sip lean", "sippin lean",
            // pile-on and build insults
            "ez", "ez clap", "l ratio", "ratioed", "ratio d", "cope and seethe", "cope harder", "noob", "noobs", "dirt hut", "noob box",
            "spaghetti redstone",
            // aimed at a player: roasts hit plays, never people ("trash" and "bad" stay: they're fine about a play)
            "skill issue", "bozo", "bozos", "dogwater", "washed", "is trash", "so trash", "is ass", "so ass", "is an l", "loser", "losers",
            "idiot", "idiots", "stupid", "npc", "npcs", "delulu", "moron", "braindead", "brain dead",
            // swears that name a person, or their body: never printed at any language (pg13 keeps damn, hell, crap, sucks and ass;
            // strong keeps the f-word as an intensifier, never as a noun for a player)
            "dumbass", "jackass", "asshole", "ahole", "bastard", "smartass", "fatass", "asshat", "asswipe", "fucker", "motherfucker",
            "is fat", "so fat", "is ugly", "so ugly",
            // profanity acronyms no setting allows
            "ts", "pmo", "sybau",
            // a player's name is a label, never a joke (a name can be body slang the gates can't see)
            "living up to the name", "lives up to the name", "lived up to the name", "true to the name", "true to their name", "name checks out",
            "the name says it all", "as the name suggests");
    /** Gamer trash talk about a play: lifted at spice savage for teens and adults (never all-ages). Never a build insult. */
    public static final List<String> TRASH_TALK = List.of("ez", "noob", "noobs", "skill issue", "washed", "dogwater");
    /** "Sus" jokes: lifted only at spice savage for adults (to teens, suspicion reads as a cheating accusation). */
    public static final List<String> SUSPICION = List.of("sus", "sussy", "suspicious", "suspiciously");
    /** Drinking words and emoji: lifted for adults. Drugs never are. */
    public static final List<String> DRINK = List.of("drunk", "got wasted");
    public static final List<String> DRINK_EMOJI = List.of("🍺", "🍻", "🍷", "🥂", "🥃", "🍾", "🍸", "🍹", "🍶");
    /** Emoji with a sexual, drug or drink meaning, checked on the raw text. */
    public static final List<String> FLOOR_EMOJI = List.of("🍆", "🍑", "💦", "👅", "🥵", "🍃", "🍺", "🫦", "😏", "🍒", "🍻", "🍷", "🥂", "🥃", "🍾", "🍸",
            "🍹", "🍶", "💊", "💉", "🚬", "🌿");   // not 🍄 or 🍄‍🟫: both mushrooms are in the game
    /** Age and family words about players (the paper never says who is anyone's parent, or how old anyone is). Matched exactly. */
    public static final List<String> FLOOR_FAMILY = List.of("dad", "dads", "mom", "moms", "mum", "mums", "parent", "parents", "the family", "loved ones",
            "in lieu of flowers", "unc", "boomer", "old man", "gramps",
            // words that set players' ages against each other
            "teen", "teens", "teenager", "teenagers", "youngster", "youngsters", "the youth", "zoomer", "zoomers", "year old", "years old",
            "year olds", "grown man", "grown men", "old head", "oldhead", "old timer", "old timers", "elder statesman", "uncle", "aunt", "cousin",
            "cousins", "family", "wife", "husband", "household", "was born", "dadded");
    /** Cheating accusations. The only exemption in the floor: the owner's own lore, retold about the owner. */
    public static final List<String> FLOOR_CHEAT = List.of("x ray", "xray", "xrayed", "xraying", "x rayed", "x raying", "x rays", "xrays",
            "hack", "hacker", "hackers", "hacking", "hacked", "hacks", "hax", "haxx",
            "cheat", "cheats", "cheater", "cheaters", "cheating", "cheated", "dupe", "duped", "dupes", "duping", "aimbot", "killaura", "kill aura",
            "autoclicker", "esp", "baritone", "alt account", "grief", "griefs", "griefer", "griefers", "griefed", "griefing", "exploit", "exploited",
            "exploiting", "banned", "steal", "steals", "stole", "stolen", "stealing", "thief", "thieving", "robbed", "robbing", "robbery", "looted",
            "looting", "swiped", "heist", "dream luck",
            // suspicion is the accusation without the word ("21 diamonds in 51.3 minutes? Sus.")
            "sus", "sussy", "suspicious", "suspiciously");
    /**
     * Dated or fellow-kids slang (lexicon.yml "Left out on purpose"): in a draft it is a "dated" problem and gets one
     * rewrite. Words that are plain English or game text (lit, ate, slay, aura, sweat) are listed only as phrases.
     */
    public static final List<String> DATED = List.of("no cap", "bestie", "besties", "fr", "bussin", "yeet", "yeeted", "sheesh", "poggers", "pog",
            "it's giving", "hits different", "it's lit", "so lit", "fam", "the big 26", "skibidi", "fanum tax", "aw man", "main character",
            "goated", "ate that", "slay queen", "aura points", "aura farming", "plus aura", "minus aura", "nonchalant", "sweaty", "built different");

    /**
     * Normalised tokens: NFKD without marks, lowercase, player names removed, leetspeak undone, and each run of 3+ single
     * letters also joined, whole and as every part of 3+ letters ("is a n i g g a": "aniggа" and "nigga"; parts up to 16 letters).
     */
    public static List<String> tokens(String text, Collection<String> names) {
        List<String> toks = baseTokens(text, names);
        List<String> run = new ArrayList<>();
        List<String> joined = new ArrayList<>();
        for (String x : toks) {
            if (x.length() == 1) { run.add(x); continue; }
            joinRun(run, joined);
            run.clear();
        }
        joinRun(run, joined);
        toks.add("");   // a gap, so a joined run never makes a phrase with the text's last word
        toks.addAll(joined);
        return toks;
    }

    private static void joinRun(List<String> run, List<String> out) {
        if (run.size() < 3) return;
        out.add(String.join("", run));
        for (int a = 0; a < run.size(); a++)
            for (int b = a + 3; b <= Math.min(run.size(), a + 16); b++) if (b - a < run.size()) out.add(String.join("", run.subList(a, b)));
    }

    private static List<String> baseTokens(String text, Collection<String> names) {
        String t = withoutNames(text, names);
        List<String> toks = new ArrayList<>();
        for (String raw : LEET_TOKEN.split(bangs(t, false), -1))   // a number stays a number: "75" is never "ts"
            for (String x : NON_WORD.split(raw.matches("[0-9]+") ? raw : leet(raw), -1)) if (!x.isEmpty()) toks.add(x);
        return toks;
    }

    /** "gyattt" and "gyat" both become "gyat". */
    static String squeeze(String tok) {
        StringBuilder b = new StringBuilder(tok.length());
        for (int i = 0; i < tok.length(); i++) if (i == 0 || tok.charAt(i) != tok.charAt(i - 1)) b.append(tok.charAt(i));
        return b.toString();
    }

    /**
     * Whether tok is the listed word with some letters stretched: the same letters in the same order, each run at least as
     * long as the listed word's ("shiiit" is "shit", "gyattt" is "gyatt"; "goner" is not "gooner", "as" is not "ass").
     */
    static boolean stretched(String tok, String listed) {
        if (tok.equals(listed)) return true;
        int i = 0, j = 0;
        while (i < tok.length() && j < listed.length()) {
            char c = listed.charAt(j);
            if (tok.charAt(i) != c) return false;
            int a = 0, b = 0;
            while (i < tok.length() && tok.charAt(i) == c) { i++; a++; }
            while (j < listed.length() && listed.charAt(j) == c) { j++; b++; }
            if (a < b) return false;
        }
        return i == tok.length() && j == listed.length();
    }

    private static List<String> phraseWords(String phrase) {
        List<String> p = new ArrayList<>();
        for (String x : NON_WORD.split(leet(norm(phrase)), -1)) if (!x.isEmpty()) p.add(x);
        return p;
    }

    /** Whether the phrase (one or more words) appears as a run of whole tokens, letters stretched or not. */
    public static boolean hasPhrase(List<String> toks, String phrase) {
        return run(toks, phraseWords(phrase), true);
    }

    /** Whether the phrase appears exactly ("son" never matches "soon"). */
    static boolean hasExact(List<String> toks, String phrase) {
        return run(toks, phraseWords(phrase), false);
    }

    private static boolean run(List<String> toks, List<String> p, boolean stretch) {
        if (p.isEmpty()) return false;
        outer:
        for (int i = 0; i + p.size() <= toks.size(); i++) {
            for (int j = 0; j < p.size(); j++) {
                String t = toks.get(i + j);
                if (stretch ? !stretched(t, p.get(j)) : !t.equals(p.get(j))) continue outer;
            }
            return true;
        }
        return false;
    }

    /** Endings a floor word may take ("simped", "griefs", "mogger", "hackin"); a doubled last letter is a stretch ("mogged"). */
    private static final List<String> FLOOR_ENDINGS = List.of("s", "es", "d", "ed", "ing", "in", "er", "ers");
    /** Endings a claim word may take ("clutched", "iron farms", "one-shotted"); never -er ("crit" is not "critter"). */
    static final List<String> CLAIM_ENDINGS = List.of("s", "es", "d", "ed", "ing");

    /**
     * Whether tok is the listed word, stretched, or the word with one of the endings: "simped", "gooned", "mogger" (a doubled
     * last letter is a stretch), "fumbling" (a dropped e) and "partied" (y to ied).
     */
    static boolean inflected(String tok, String listed, List<String> endings) {
        if (stretched(tok, listed)) return true;
        for (String e : endings) {
            if (!tok.endsWith(e) || tok.length() <= e.length()) continue;
            String base = tok.substring(0, tok.length() - e.length());
            // a short word takes -ed, -ing or -er only after a doubled last letter ("mogger", "sussed"; never "esper")
            boolean shortWord = listed.length() < 4 && !e.equals("s") && !e.equals("es");
            if (shortWord && (base.length() < 2 || base.charAt(base.length() - 1) != base.charAt(base.length() - 2))) continue;
            if (stretched(base, listed)) return true;
            if (listed.endsWith("e") && !e.startsWith("e") && !e.equals("s") && !e.equals("d") && stretched(base + "e", listed)) return true;
            if (listed.endsWith("y") && (e.equals("ed") || e.equals("es")) && base.endsWith("i") && stretched(base.substring(0, base.length() - 1) + "y", listed)) return true;
        }
        return false;
    }

    /** Whether a floor entry is in the tokens: a phrase as a run of tokens; a single word also with its endings. */
    static boolean hasFloorWord(List<String> toks, String entry) {
        List<String> p = phraseWords(entry);
        if (p.size() != 1) return run(toks, p, true);
        for (String t : toks) if (!t.isEmpty() && inflected(t, p.get(0), FLOOR_ENDINGS)) return true;
        return false;
    }

    /**
     * Whether a claim form is in the tokens: its words in a row, the last with a claim ending ("bucket clutched", "iron farms");
     * a "*" in the form stands for up to 3 words ("popped * totem": "popped his totem", "popped two totems").
     */
    public static boolean hasForm(List<String> toks, String form) {
        List<List<String>> parts = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (String piece : form.trim().split("\\s+")) {
            if (piece.equals("*")) { parts.add(cur); cur = new ArrayList<>(); continue; }
            cur.addAll(phraseWords(piece));
        }
        parts.add(cur);
        if (parts.stream().allMatch(List::isEmpty)) return false;
        return formAt(toks, parts, 0, 0, true);
    }

    private static boolean formAt(List<String> toks, List<List<String>> parts, int part, int from, boolean anywhere) {
        List<String> p = parts.get(part);
        boolean last = part == parts.size() - 1;
        int end = anywhere ? toks.size() : Math.min(toks.size(), from + 4);   // after a "*": 0-3 words in between
        for (int i = from; i < end && i + p.size() <= toks.size(); i++) {
            boolean ok = true;
            for (int j = 0; j < p.size() && ok; j++) {
                String t = toks.get(i + j), w = p.get(j);
                ok = !t.isEmpty() && (last && j == p.size() - 1 ? inflected(t, w, CLAIM_ENDINGS) : stretched(t, w));
            }
            if (ok && (last || formAt(toks, parts, part + 1, i + p.size(), false))) return true;
        }
        return false;
    }

    /** Floor words and phrases in text (in list order, distinct), names removed first. No exemption. */
    public static List<String> floor(String text, Collection<String> names) {
        return floor(text, names, List.of(), "");
    }

    /**
     * Floor words and phrases in text (in list order, distinct). names are removed first. A cheating phrase is let through
     * only inside a sentence that names no player but the owner and retells, word for word, a sentence of the owner's lore
     * that has the same phrase: the owner's own joke about the owner, never anything about anyone else.
     */
    public static List<String> floor(String text, Collection<String> names, Collection<String> lore, String owner) {
        return floor(text, names, lore, owner, Set.of());
    }

    /** Ore, loot and luck words: a lifted suspicion word near one is still refused. */
    private static final Pattern SUS_NEVER = Pattern.compile("\\b(?:diamonds?|ores?|debris|netherite|emeralds?|gold|iron|lapis|redstone|coal|copper|quartz"
            + "|mine[ds]?|mining|veins?|loot\\w*|luck\\w*|chests?|drops?|dropped|fortune|rng|odds)\\b");

    /**
     * floor() without the words the server's tone settings lift (FactCheck.lifted: TRASH_TALK, SUSPICION, DRINK, DRINK_EMOJI)
     * and, in the paper, a mob's or an animal's family (FactCheck.mobKin).
     * A lifted suspicion word still counts unless its sentence, or the one before, has a number and no ore, loot or luck word.
     */
    public static List<String> floor(String text, Collection<String> names, Collection<String> lore, String owner, Collection<String> lifted) {
        List<String> toks = tokens(innocent(text, names), names);
        List<String> hit = new ArrayList<>();
        for (String e : FLOOR_EMOJI) if (text.contains(e) && !lifted.contains(e)) hit.add(e);
        for (String f : FLOOR) if (!lifted.contains(f) && hasFloorWord(toks, f)) hit.add(f);
        for (String f : FLOOR_FAMILY) if (!lifted.contains(f) && hasExact(toks, f)) hit.add(f);
        List<List<String>> loreSentences = new ArrayList<>();
        for (String l : lore) for (String ls : sentences(l)) loreSentences.add(baseTokens(ls, names));
        String prev = "";
        for (String sentence : sentences(text)) {
            List<String> st = tokens(sentence, names);
            boolean ownerOnly = owner != null && !owner.isBlank()
                    && namesIn(sentence, names).stream().allMatch(n -> n.equalsIgnoreCase(owner));
            // a lifted "sus" joke rides on a real number and never on ore, loot or luck (config.yml), in its sentence or the one before
            String near = withoutNames(prev + " " + sentence, names);
            boolean susOk = near.matches("(?s).*\\d.*") && !SUS_NEVER.matcher(near).find();
            for (String f : FLOOR_CHEAT) {
                if (hit.contains(f) || (lifted.contains(f) && susOk) || !hasFloorWord(st, f)) continue;
                boolean retold = ownerOnly && loreSentences.stream().anyMatch(ls -> hasFloorWord(ls, f) && run(st, ls, false));
                if (!retold) hit.add(f);
            }
            prev = sentence;
        }
        return hit;
    }

    /** Everyday phrases that hold a floor word but mean nothing by it. */
    private static final Pattern INNOCENT = Pattern.compile("(?i)\\b(?:(?:sleep|sleeps|sleeping|slept) with one eye open"
            + "|(?:stood|stands|standing|rose|got) up and clapped|clapped (?:politely|along|slowly|twice|once)|slow[- ]clapped"
            + "|edging (?:out|past|ahead))\\b");

    /**
     * text without the innocent phrases, for the floor's word list only: "sleeping with one eye open", "stood up and clapped",
     * and "edging" for a close win ("edging out", "edging Kid_1 31 to 30", "edging 31 to 30").
     */
    static String innocent(String text, Collection<String> names) {
        String out = INNOCENT.matcher(text).replaceAll(" ");
        StringBuilder alt = new StringBuilder("\\d");
        for (String n : names) alt.append('|').append(Pattern.quote(n));
        return Pattern.compile("(?i)\\bedging\\s+(?=(?:" + alt + ")(?![A-Za-z0-9_]))").matcher(out).replaceAll(" ");
    }

    /** Dated slang in text (in list order), names removed first. */
    public static List<String> dated(String text, Collection<String> names) {
        List<String> toks = tokens(text, names);
        List<String> hit = new ArrayList<>();
        for (String d : DATED) if (hasPhrase(toks, d)) hit.add(d);
        return hit;
    }
}
