package io.github.eastmadc.masthead.edition;

import io.github.eastmadc.masthead.config.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The words the writer may use today (DESIGN §1.3-1.4, §1.7): the shipped lexicon.yml plus the owner's tone.lexicon.add,
 * minus tone.lexicon.remove, filtered by audience tier, freshness, slang level, cooldown (a slang word in a recent paper
 * rests) and each term's fact gate. Slang never invents a play: a claim word whose facts aren't there is a fact-check
 * problem (claimProblems).
 */
public final class Lexicon {
    private Lexicon() {}

    /**
     * needs: a list of condition maps, any one of which backs the term (empty = always). never = never offered, always gated.
     * rests = a recent paper's use rests it (every slang word and read; game words marked rests: true). owner = from tone.lexicon.add.
     */
    public record Term(String term, List<String> forms, String kind, String tier, String means, List<Map<String, Object>> needs,
                       boolean never, boolean claim, LocalDate freshUntil, boolean rests, boolean owner) {}

    /** Today's offer, ready to print. slangMax = how many slang words the paper may use. */
    public record Palette(List<String> game, List<String> reads, List<String> slang, List<String> avoid, int slangMax) {}

    static final List<String> KINDS = List.of("game", "slang", "read");
    static final Set<String> PLAYER_KEYS = Set.of("stat", "zero", "killed", "mined", "used", "crafted", "advancement");
    static final Set<String> DAY_KEYS = Set.of("death", "mob-death", "pvp", "blotter", "race", "fumble", "recent", "lore", "building", "tps", "leaders");

    public static final List<Term> SHIPPED;
    /** When a person last checked the shipped list, and how often it should be checked. */
    public static final LocalDate REVIEWED;
    public static final int REVIEW_DAYS;

    static {
        try (InputStream in = Lexicon.class.getResourceAsStream("/lexicon.yml")) {
            if (in == null) throw new IllegalStateException("lexicon.yml is missing from the jar");
            Map<String, Object> root = load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            SHIPPED = parse(root);
            REVIEWED = toDate(root.get("reviewed"));
            REVIEW_DAYS = ((Number) root.get("review-every-days")).intValue();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> load(String yaml) {
        Object o = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        if (!(o instanceof Map)) throw new IllegalArgumentException("lexicon: not a map");
        return (Map<String, Object>) o;
    }

    /** The terms of a lexicon file. A malformed term is an error: the shipped file is tested, so this never fails at a server. */
    @SuppressWarnings("unchecked")
    static List<Term> parse(Map<String, Object> root) {
        List<Term> out = new ArrayList<>();
        for (Object o : (List<Object>) root.get("terms")) {
            Map<String, Object> m = (Map<String, Object>) o;
            String term = String.valueOf(m.get("term"));
            List<String> forms = new ArrayList<>();
            if (m.get("forms") instanceof List<?> l) for (Object f : l) forms.add(String.valueOf(f));
            else forms.add(term);
            String kind = String.valueOf(m.get("kind")), tier = String.valueOf(m.get("tier"));
            if (!KINDS.contains(kind) || !Settings.AUDIENCES.contains(tier) || m.get("means") == null)
                throw new IllegalArgumentException("lexicon: bad term " + term);
            Object n = m.get("needs");
            boolean never = "never".equals(n);
            List<Map<String, Object>> needs = new ArrayList<>();
            if (n instanceof Map) needs.add((Map<String, Object>) n);
            else if (n instanceof List<?> l) for (Object c : l) needs.add((Map<String, Object>) c);
            else if (n != null && !never) throw new IllegalArgumentException("lexicon: bad needs for " + term);
            for (Map<String, Object> c : needs)
                for (String k : c.keySet()) if (!PLAYER_KEYS.contains(k) && !DAY_KEYS.contains(k)) throw new IllegalArgumentException("lexicon: unknown needs key " + k);
            Object fu = m.get("fresh-until");
            LocalDate fresh = fu == null ? null : toDate(fu);
            out.add(new Term(term, List.copyOf(forms), kind, tier, String.valueOf(m.get("means")), List.copyOf(needs), never,
                    Boolean.TRUE.equals(m.get("claim")), fresh, !kind.equals("game") || Boolean.TRUE.equals(m.get("rests")), false));
        }
        return List.copyOf(out);
    }

    /** YAML reads 2026-11-06 as a timestamp (midnight UTC). */
    static LocalDate toDate(Object o) {
        return o instanceof java.util.Date d ? LocalDate.ofInstant(d.toInstant(), java.time.ZoneOffset.UTC) : LocalDate.parse(String.valueOf(o));
    }

    /** The terms this server uses: shipped minus tone.lexicon.remove, plus the owner's words that pass the floor. */
    public static List<Term> terms(Settings s) {
        Set<String> gone = new java.util.HashSet<>();
        for (String r : s.tone().lexiconRemove()) gone.add(r.toLowerCase(Locale.ROOT));
        List<Term> out = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (Term t : SHIPPED) {
            boolean removed = gone.contains(t.term().toLowerCase(Locale.ROOT));
            for (String f : t.forms()) removed |= gone.contains(f.toLowerCase(Locale.ROOT));
            if (!removed) {   // a removed word may come back as the owner's own (remove + add redefines it)
                out.add(t);
                seen.add(t.term().toLowerCase(Locale.ROOT));
            }
        }
        for (Settings.LexTerm a : s.tone().lexiconAdd()) {
            String text = a.term() + " " + a.means();
            if (!floorOk(text, a.tier()) || !swearOk(text, s.tone().language()) || isClaim(a.term()) || !seen.add(a.term().toLowerCase(Locale.ROOT))) continue;
            out.add(new Term(a.term(), List.of(a.term()), a.kind(), a.tier(), a.means(), List.of(), false, false, null, a.kind().equals("slang"), true));
        }
        return out;
    }

    /**
     * The terms the draft gates check: every shipped term, ignoring tone.lexicon.remove (removing a word never loosens a
     * gate), plus the owner's words. An owner word that redefines a shipped one (remove + add) keeps the owner's kind and
     * meaning, at the higher of the two tiers, so remove + add never lowers a word's tier.
     */
    static List<Term> gateTerms(Settings s) {
        List<Term> out = new ArrayList<>();
        java.util.Map<String, Term> own = new java.util.LinkedHashMap<>();
        for (Term t : terms(s)) if (t.owner()) own.put(t.term().toLowerCase(Locale.ROOT), t);
        for (Term t : SHIPPED) {
            Term o = own.remove(t.term().toLowerCase(Locale.ROOT));
            if (o == null) { out.add(t); continue; }
            String tier = rank(Settings.AUDIENCES, t.tier()) > rank(Settings.AUDIENCES, o.tier()) ? t.tier() : o.tier();
            out.add(new Term(o.term(), t.forms(), o.kind(), tier, o.means(), o.needs(), false, false, null, o.rests(), true));
        }
        out.addAll(own.values());
        return out;
    }

    /** Shipped slang and reads the owner dropped with tone.lexicon.remove and did not add back. */
    static List<Term> removedTerms(Settings s) {
        Set<String> kept = new java.util.HashSet<>();
        for (Term t : terms(s)) kept.add(t.term().toLowerCase(Locale.ROOT));
        List<Term> out = new ArrayList<>();
        for (Term t : SHIPPED) if (!t.kind().equals("game") && !kept.contains(t.term().toLowerCase(Locale.ROOT))) out.add(t);
        return out;
    }

    /** The shipped term a word or form belongs to ("hoppers" is part of "redstone"), or null. */
    public static String shippedTermFor(String form) {
        String k = form.strip().toLowerCase(Locale.ROOT);
        for (Term t : SHIPPED) if (t.term().toLowerCase(Locale.ROOT).equals(k)) return t.term();
        for (Term t : SHIPPED) if (t.forms().stream().anyMatch(f -> f.toLowerCase(Locale.ROOT).equals(k))) return t.term();
        return null;
    }

    /**
     * Owner text never carries a floor word in (the cheating words included) or dated slang (the draft check would refuse it),
     * and no swear above its tier: all-ages none, teens up to pg13, adults up to strong.
     */
    public static boolean floorOk(String text, String tier) {
        return WordGate.floor(text, List.of()).isEmpty() && WordGate.dated(text, List.of()).isEmpty() && swearOk(text, tierLanguage(tier));
    }

    /** The most swearing a tier's words may carry. */
    public static String tierLanguage(String tier) {
        return "all-ages".equals(tier) ? "clean" : "teens".equals(tier) ? "pg13" : "strong";
    }

    /** Whether text has no swear tone.language refuses (an owner word with one would be offered, then sent back). */
    public static boolean swearOk(String text, String language) {
        return WordGate.bad(text, List.of(), false, language).isEmpty();
    }

    /** Whether a word says a play the facts must back (a shipped claim form): never an owner word, the gate would refuse it. */
    public static boolean isClaim(String term) {
        List<String> toks = WordGate.tokens(term, List.of());
        for (Term t : SHIPPED) if (t.claim()) for (String f : t.forms()) if (WordGate.hasForm(toks, f)) return true;
        return false;
    }

    /** Whether a word is a shipped term or form (an owner word with that name is ignored unless tone.lexicon.remove drops it). */
    public static boolean isShipped(String term) {
        String k = term.toLowerCase(Locale.ROOT);
        for (Term t : SHIPPED) if (t.term().toLowerCase(Locale.ROOT).equals(k) || t.forms().stream().anyMatch(f -> f.toLowerCase(Locale.ROOT).equals(k))) return true;
        return false;
    }

    /** evergreen | current | fading | expired on this date. */
    public static String freshness(Term t, LocalDate date) {
        if (t.freshUntil() == null) return "evergreen";
        if (!date.isAfter(t.freshUntil())) return "current";
        return date.isAfter(t.freshUntil().plusYears(1)) ? "expired" : "fading";
    }

    /** A reminder once the shipped list is older than review-every-days ("" while it is fresh). */
    public static String reviewNote(LocalDate today) {
        if (!today.isAfter(REVIEWED.plusDays(REVIEW_DAYS))) return "";
        return "Masthead's slang list was last checked on " + REVIEWED + ". Slang goes stale fast: look for a plugin update, or drop words with tone.lexicon.remove.";
    }

    // ---------------------------------------------------------------- today's palette

    static int rank(List<String> order, String v) { return Math.max(0, order.indexOf(v)); }

    public static Palette palette(Settings s, JsonObject facts) {
        Settings.Tone tone = s.tone();
        LocalDate date = date(facts);
        String slang = tone.slang();
        if (slangFit(tone.effectiveVoice()).equals("low") && slang.equals("heavy")) slang = "light";   // a stiff voice saying "cooked" is cringe
        int aud = rank(Settings.AUDIENCES, tone.audience());
        boolean first = PyJson.truthy(facts.get("first_edition"));   // backed by season totals, so never "today"
        Set<String> recent = recentWords(s, facts);
        List<String> game = new ArrayList<>(), ownGame = new ArrayList<>(), claims = new ArrayList<>(), reads = new ArrayList<>(), sl = new ArrayList<>(), always = new ArrayList<>(),
                avoid = new ArrayList<>();
        for (Term t : terms(s)) {
            if (t.never() || rank(Settings.AUDIENCES, t.tier()) > aud) continue;
            String fr = freshness(t, date);
            if (fr.equals("expired") || (fr.equals("fading") && !slang.equals("heavy"))) continue;
            if (t.kind().equals("slang") && slang.equals("off")) continue;
            List<String> who = backers(t, facts);
            if (who.isEmpty()) continue;
            if (t.rests() && recent.contains(t.term().toLowerCase(Locale.ROOT))) {
                avoid.add("\"" + t.term() + "\"");
                continue;
            }
            String means = first ? t.means().replaceAll(" today(?!'s)", " this season") : t.means();
            switch (t.kind()) {
                case "game" -> (t.owner() ? ownGame : t.claim() ? claims : game).add("- " + t.term() + ": " + means);
                case "read" -> reads.add("- " + t.term() + " (" + means + "): " + String.join("; ", who.subList(0, Math.min(2, who.size()))));
                default -> (t.needs().isEmpty() ? always : sl).add("- " + t.term() + ": " + means);
            }
        }
        // slang a fact of today backs comes first, rotated by date so no two days get the same list; then the always-words
        int offer = slang.equals("heavy") ? 10 : 6, day = (int) Math.floorMod(date.toEpochDay(), 997);
        List<String> offered = cap(sl, offer, day % Math.max(1, sl.size()));
        offered.addAll(cap(always, offer - offered.size(), day % Math.max(1, always.size())));
        if (!"clean".equals(tone.language())) for (String w : SWEAR_PHRASES) if (recent.contains(w)) avoid.add("\"" + w + "\"");
        for (String w : RESTING_PHRASES) if (recent.contains(w)) avoid.add("\"" + w + "\"");
        if (recent.contains(CONTRAST)) avoid.add("the \"" + CONTRAST + "\" shape");
        ownGame.addAll(game);   // the owner's own words first, so a busy day never crowds them out
        List<String> g = cap(ownGame, 12, 0);
        g.addAll(claims);   // a backed play word is never cut: a missing one would read as the play not happening
        return new Palette(g, cap(reads, 3, 0), offered, avoid, slang.equals("heavy") ? 3 : slang.equals("light") ? 2 : 0);
    }

    /** Swear phrases that rest after a paper uses them (a recurring "as hell" reads as a quota). */
    static final List<String> SWEAR_PHRASES = List.of("as hell", "hell of a");
    /**
     * Frames that rest after a paper uses them, at any language: "a very librarian-reroll-shaped number", the same cobble
     * joke about the same builder ("grey is the whole plan", "a full commitment"), the stock sign-off ("That's the paper.
     * Feed your chickens.") and the stock read ("looks a lot like a librarian reroll").
     */
    static final List<String> RESTING_PHRASES = List.of("shaped number", "grey", "gray", "commitment", "that's the paper", "that's the news",
            "feed your", "looks a lot like", "look a lot like");
    /** The "That's not a trade, that's a statement" shape (the classic AI tell): rests after a paper uses it, once a paper at most. */
    public static final String CONTRAST = "not a X, that's a Y";
    private static final java.util.regex.Pattern CONTRAST_SHAPE = java.util.regex.Pattern.compile(
            "(?i)(?<![a-z])not (?:just )?(?:a|an|the) [^.,;:!?\u2014\u2013]{1,40}?[,.;:!\u2014\u2013-]+\\s*(?:(?:that|it|this)(?:'s|\u2019s| is| was)\\s+)?(?:just )?(?:a|an|the) ");

    /** How many times text uses the "not a X, that's a Y" shape. */
    public static int contrasts(String text) {
        return (int) CONTRAST_SHAPE.matcher(Py.collapse(text)).results().count();
    }

    /** Item names with a slang word in them: never the slang ("cooked chicken" is food). */
    private static final java.util.regex.Pattern ITEM_WORDS = java.util.regex.Pattern.compile(
            "(?i)(?<![a-z])cooked[ _](?:chicken|beef|porkchops?|mutton|rabbit|cod|salmon)(?![a-z])");

    /** Tokens for finding slang: item names that hold a slang word removed first, then WordGate.tokens. */
    static List<String> slangTokens(String text, Collection<String> names) {
        return WordGate.tokens(ITEM_WORDS.matcher(text).replaceAll(" "), names);
    }

    /**
     * The resting words a paper used: every term that rests (slang, reads, game words marked rests), the swear phrases and the
     * resting frames, scanning the whole text with player names removed (a player called Cooked_Kid never rests "cooked").
     * FactsBuilder stores them as "recent_words" for the last few papers.
     */
    public static List<String> usedWords(Settings s, String text, Collection<String> names) {
        List<String> toks = slangTokens(text, names);
        List<String> out = new ArrayList<>();
        for (Term t : gateTerms(s))   // a removed word the writer still uses still rests
            if (t.rests() && t.forms().stream().anyMatch(f -> WordGate.hasPhrase(toks, f))) out.add(t.term().toLowerCase(Locale.ROOT));
        for (String w : SWEAR_PHRASES) if (WordGate.hasPhrase(toks, w)) out.add(w);
        for (String w : RESTING_PHRASES) if (WordGate.hasPhrase(toks, w)) out.add(w);
        if (contrasts(text) > 0) out.add(CONTRAST);
        return out;
    }

    /** Words above tone.audience's tier in a draft ("tier (\"mid\")"): the tier is checked in the paper, not only in the offer. */
    public static List<String> tierProblems(Settings s, String text, Collection<String> names) {
        int aud = rank(Settings.AUDIENCES, s.tone().audience());
        List<String> toks = slangTokens(text, names);
        List<String> out = new ArrayList<>();
        for (Term t : gateTerms(s))   // removing a word never lets it past the audience
            if (rank(Settings.AUDIENCES, t.tier()) > aud && t.forms().stream().anyMatch(f -> WordGate.hasPhrase(toks, f))) out.add("tier (\"" + t.term() + "\")");
        return out;
    }

    /**
     * The slang cap, checked in the draft: more distinct slang words than StylePrompt.slangMax ("slang (4 over 3: diff, folded,
     * fumbled, gg)"), or two in one section (split at each h3).
     */
    public static List<String> slangProblems(Settings s, String html, Collection<String> names) {
        return slangProblems(s, null, html, names);
    }

    /**
     * As above, plus a slang word left alone as a sentence ("Let them cook.", "Big W."): a sentence of at most 3 words with no
     * player and no number is a tag, "slang tag (\"let them cook\")". One closing "GG." is fine when today's offer has GG
     * (facts null = never).
     */
    public static List<String> slangProblems(Settings s, JsonObject facts, String html, Collection<String> names) {
        List<Term> slang = new ArrayList<>();
        for (Term t : gateTerms(s)) if (t.kind().equals("slang")) slang.add(t);   // a removed word still counts
        boolean ggOk = facts != null && palette(s, facts).slang().stream().anyMatch(x -> x.startsWith("- GG:"));
        LinkedHashSet<String> all = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String sec : html.split("(?i)<h3")) {
            String txt = HtmlClean.checkText(sec);
            List<String> toks = slangTokens(txt, names);
            List<String> here = new ArrayList<>();
            for (Term t : slang) if (t.forms().stream().anyMatch(f -> WordGate.hasPhrase(toks, f))) here.add(t.term().toLowerCase(Locale.ROOT));
            if (here.size() >= 2) out.add("slang (" + here.size() + " in one section: " + String.join(", ", here) + ")");
            all.addAll(here);
            for (String sn : WordGate.sentences(txt)) {
                List<String> raw = WordGate.rawTokens(sn);
                if (raw.size() > 3 || !WordGate.namesExact(sn, names).isEmpty() || raw.stream().anyMatch(x -> x.matches(".*[0-9].*"))) continue;
                List<String> st = slangTokens(sn, names);
                for (Term t : slang) {
                    if (t.forms().stream().noneMatch(f -> WordGate.hasPhrase(st, f))) continue;
                    if (t.term().equals("GG") && ggOk) { ggOk = false; continue; }   // the one closing GG
                    out.add("slang tag (\"" + t.term().toLowerCase(Locale.ROOT) + "\")");
                }
            }
        }
        int max = StylePrompt.slangMax(s);
        if (all.size() > max) out.add(0, "slang (" + all.size() + " over " + max + ": " + String.join(", ", all) + ")");
        return out;
    }

    /**
     * Slang and reads the draft may not use: "slang (resting: gg)" for one a recent paper used (it is on AVOID THIS PAPER),
     * "removed (\"mid\")" for one the owner dropped with tone.lexicon.remove. Game words are the game's own names (redstone,
     * cobble), so removing one only takes it off the offer.
     */
    public static List<String> restProblems(Settings s, JsonObject facts, String text, Collection<String> names) {
        List<String> toks = slangTokens(text, names), out = new ArrayList<>();
        Set<String> recent = recentWords(s, facts);
        for (Term t : terms(s))
            if (t.kind().equals("slang") && recent.contains(t.term().toLowerCase(Locale.ROOT)) && t.forms().stream().anyMatch(f -> WordGate.hasPhrase(toks, f)))
                out.add("slang (resting: " + t.term().toLowerCase(Locale.ROOT) + ")");
        for (Term t : removedTerms(s)) if (t.forms().stream().anyMatch(f -> WordGate.hasPhrase(toks, f))) out.add("removed (\"" + t.term() + "\")");
        return out;
    }

    /** The slang and reads the owner removed, for the prompt ("" when none). */
    public static List<String> removedWords(Settings s) {
        List<String> out = new ArrayList<>();
        for (Term t : removedTerms(s)) out.add("\"" + t.term() + "\"");
        return out;
    }

    /** "recent_words" from the facts; older facts without it: the words found in "recent_editions" (the start of each paper). */
    static Set<String> recentWords(Settings s, JsonObject facts) {
        Set<String> out = new java.util.HashSet<>();
        if (facts.has("recent_words")) for (String w : strings(facts.get("recent_words"))) out.add(w.toLowerCase(Locale.ROOT));
        else out.addAll(usedWords(s, String.join(" ", strings(facts.get("recent_editions"))), names(facts)));
        return out;
    }

    /** newsroom and nature-doc style voices keep slang at light whatever tone.slang says. */
    static String slangFit(String voice) {
        return voice.equals("newsroom") ? "low" : "high";
    }

    /** n items starting at from (wrapping), in rotation, so no two days get the same list. */
    static List<String> cap(List<String> in, int n, int from) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, in.size()); i++) out.add(in.get((from + i) % in.size()));
        return out;
    }

    // ---------------------------------------------------------------- fact gates

    /**
     * Who backs the term today: player lines with their numbers for a per-player condition ("Alex: 96 lectern broken,
     * 9 trades"), "*" for a whole-day condition. Empty = not backed. A term with no needs is always backed.
     */
    public static List<String> backers(Term t, JsonObject facts) {
        return backers(t, facts, false);
    }

    /** gate = the claim check, which also counts the season's PvP (a Cold Case kill line backs "bodied"); the offer counts today only. */
    static List<String> backers(Term t, JsonObject facts, boolean gate) {
        if (t.never()) return List.of();
        if (t.needs().isEmpty()) return List.of("*");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Map<String, Object> c : t.needs()) out.addAll(match(c, facts, gate));
        return new ArrayList<>(out);
    }

    @SuppressWarnings("unchecked")
    static List<String> match(Map<String, Object> c, JsonObject f, boolean gate) {
        for (Map.Entry<String, Object> e : c.entrySet())
            if (DAY_KEYS.contains(e.getKey()) && !day(e.getKey(), e.getValue(), f, gate)) return List.of();
        boolean perPlayer = c.keySet().stream().anyMatch(PLAYER_KEYS::contains);
        if (!perPlayer) return List.of("*");
        List<String> out = new ArrayList<>();
        JsonObject who = PyJson.truthy(f.get("first_edition")) ? PyJson.objOf(f, "season_totals") : PyJson.objOf(f, "today");
        for (Map.Entry<String, JsonElement> p : who.entrySet()) {
            JsonObject row = PyJson.obj(p.getValue());
            List<String> because = new ArrayList<>();
            boolean ok = true;
            for (Map.Entry<String, Object> e : c.entrySet()) {
                String k = e.getKey();
                if (!PLAYER_KEYS.contains(k)) continue;
                switch (k) {
                    case "stat" -> {
                        for (Map.Entry<String, Object> x : ((Map<String, Object>) e.getValue()).entrySet()) {
                            double v = num(row.get(x.getKey()));
                            if (v < ((Number) x.getValue()).doubleValue()) ok = false;
                            else because.add(x.getKey().replace('_', ' ') + " " + fmt(v));
                        }
                    }
                    case "zero" -> {
                        for (Object x : (List<Object>) e.getValue())
                            if (!row.has(String.valueOf(x)) || num(row.get(String.valueOf(x))) != 0) ok = false;
                            else because.add(x + " 0");
                    }
                    case "advancement" -> {
                        String got = null;
                        for (JsonElement a : PyJson.arr(f.get("advancements"))) {
                            JsonArray pa = PyJson.arr(a);
                            if (pa.size() == 2 && p.getKey().equals(PyJson.strOr(pa.get(0), "")) && ((List<Object>) e.getValue()).contains(PyJson.strOr(pa.get(1), "")))
                                got = PyJson.strOr(pa.get(1), "");
                        }
                        if (got == null) ok = false; else because.add("\"" + got + "\"");
                    }
                    default -> {   // killed, mined, used, crafted
                        JsonObject sub = PyJson.objOf(row, k);
                        for (Map.Entry<String, Object> x : ((Map<String, Object>) e.getValue()).entrySet()) {
                            double v = num(sub.get(x.getKey()));
                            if (v < ((Number) x.getValue()).doubleValue()) ok = false;
                            else because.add(x.getKey().replace('_', ' ') + " " + VERB.get(k) + " " + fmt(v));   // "torch crafted 177": no plural to get wrong
                        }
                    }
                }
                if (!ok) break;
            }
            if (ok) out.add(p.getKey() + ": " + String.join(", ", because));
        }
        return out;
    }

    /** Boards where the top spot is losing: never a race or a fumble. */
    static final Set<String> LOWER_BETTER = Set.of("deaths", "damage_taken");

    static final Map<String, String> VERB = Map.of("killed", "killed", "mined", "broken", "used", "used", "crafted", "crafted");

    @SuppressWarnings("unchecked")
    static boolean day(String k, Object v, JsonObject f, boolean gate) {
        List<String> deaths = strings(f.get("deaths"));
        switch (k) {
            case "death": {
                for (String d : deaths) for (Object x : (List<Object>) v) if (deathHas(d, String.valueOf(x))) return true;
                return false;
            }
            case "mob-death": {   // a death line whose killer is a mob: a capitalised name that is not a player ("by lightning", "by [Intentional Game Design]" are not)
                Set<String> players = names(f);
                for (String d : deaths) {
                    int i = d.lastIndexOf(" by ");
                    if (i < 0) continue;
                    String killer = d.substring(i + 4).split(" ")[0];
                    if (!killer.isEmpty() && Character.isUpperCase(killer.charAt(0)) && !players.contains(killer)) return true;
                }
                return false;
            }
            case "pvp": {
                if (!PyJson.arr(f.get("pvp")).isEmpty()) return true;
                for (Map.Entry<String, JsonElement> p : PyJson.objOf(f, "today").entrySet()) if (num(PyJson.obj(p.getValue()).get("player_kills")) >= 1) return true;
                // the season board backs it only where the paper tells the season: a first edition (or a Cold Case kill line)
                return gate && (PyJson.truthy(f.get("first_edition")) || !PyJson.arr(f.get("cold_cases")).isEmpty())
                        && day("leaders", List.of("player_kills"), f, true);
            }
            case "blotter": {   // every listed kind between the same two players ("rage bait": one player took from and left in one chest owner's)
                Map<String, Set<String>> pairs = new java.util.HashMap<>();
                for (JsonElement e : PyJson.arr(f.get("police_blotter"))) {
                    JsonObject o = PyJson.obj(e);
                    pairs.computeIfAbsent(PyJson.strOr(o.get("by"), "") + "\t" + PyJson.strOr(o.get("to"), ""), k2 -> new java.util.HashSet<>())
                            .add(PyJson.strOr(o.get("what"), ""));
                }
                for (Set<String> kinds : pairs.values()) {
                    boolean all = true;
                    for (Object x : (List<Object>) v) all &= kinds.contains(String.valueOf(x));
                    if (all) return true;
                }
                return false;
            }
            case "race": {
                for (Map.Entry<String, JsonElement> e : PyJson.objOf(f, "leaders_today").entrySet())
                    if (!LOWER_BETTER.contains(e.getKey()) && PyJson.arr(e.getValue()).size() >= 2) return true;
                return false;
            }
            case "fumble": {   // a season leader who is on today's board for that stat, but not first
                JsonObject season = PyJson.objOf(f, "leaders");
                for (Map.Entry<String, JsonElement> e : PyJson.objOf(f, "leaders_today").entrySet()) {
                    if (LOWER_BETTER.contains(e.getKey())) continue;   // second in deaths today is a better day, not a fumble
                    JsonArray today = PyJson.arr(e.getValue()), all = PyJson.arr(season.get(e.getKey()));
                    if (today.size() < 2 || all.isEmpty()) continue;
                    String top = first(all);
                    if (top.equals(first(today))) continue;
                    for (JsonElement r : today) if (PyJson.arr(r).size() > 0 && top.equals(PyJson.strOr(PyJson.arr(r).get(0), ""))) return true;
                }
                return false;
            }
            case "recent": return !strings(f.get("recent_editions")).isEmpty();
            case "lore": return !strings(PyJson.objOf(f, "owner").get("lore")).isEmpty();
            case "building": return !PyJson.arr(PyJson.objOf(f, "building").get("builders")).isEmpty();
            case "tps": return PyJson.isNum(PyJson.objOf(f, "server").get("tps_avg"));
            case "leaders": {
                for (Object x : (List<Object>) v) {
                    JsonArray a = PyJson.arr(PyJson.objOf(f, "leaders").get(String.valueOf(x)));
                    if (!a.isEmpty() && PyJson.arr(a.get(0)).size() == 2 && num(PyJson.arr(a.get(0)).get(1)) > 0) return true;
                }
                return false;
            }
            default: return false;
        }
    }

    private static final java.util.regex.Pattern MOB_NAME = java.util.regex.Pattern.compile("[A-Z][a-z]+");

    /**
     * Whether a death line backs a death need: a mob name ("Skeleton") must be the whole killer ("shot by Skeleton", "slain by
     * Skeleton using ..."; never "Wither Skeleton" or "Zombie Villager"); any other need is a piece of the line ("smashed").
     */
    static boolean deathHas(String line, String need) {
        if (MOB_NAME.matcher(need).matches())
            return java.util.regex.Pattern.compile("(?i) by " + java.util.regex.Pattern.quote(need) + "(?:$|[.!]| using | while | whilst )").matcher(line).find();
        return line.toLowerCase(Locale.ROOT).contains(need.toLowerCase(Locale.ROOT));
    }

    /**
     * Claim words in a draft that today's facts don't back (or never can): "claim (\"bucket clutch\")". Uses the shipped
     * list, ignoring tone.lexicon.remove: removing a word never lets it invent a play. A claim word is backed only in a
     * sentence that names a player the facts back for it (the death line's victim, the player who used the pearl). Text the
     * facts already hold (a build's name, the owner's lore, a death line, an advancement title) is never a claim.
     */
    public static List<String> claimProblems(Settings s, JsonObject facts, String text, Collection<String> names) {
        text = IDIOMS.matcher(text).replaceAll(" ");   // "a clutch of eggs", "one shot at the board": plain English, never a play
        List<String> toks = WordGate.tokens(text, names);
        List<List<String>> held = new ArrayList<>();
        for (String h : heldText(s, facts)) held.add(WordGate.tokens(h, names));
        List<String> sentences = WordGate.sentences(text);
        List<String> out = new ArrayList<>();
        for (Term t : SHIPPED) {
            if (!t.claim()) continue;
            String hit = null;
            for (String f : t.forms()) if (WordGate.hasForm(toks, f)) { hit = f; break; }
            if (hit == null) continue;
            String h = hit;
            if (held.stream().anyMatch(l -> WordGate.hasForm(l, h))) continue;
            Set<String> who = claimNames(t, facts);
            if (who == null) continue;   // backed by a day fact that names no one
            boolean ok = !who.isEmpty();
            boolean pvp = t.needs().stream().anyMatch(c -> c.containsKey("pvp"));
            // a Blotter or race verdict may follow the sentence that names the player ("29 out and 3 back is rage bait"), if it names no one else
            boolean follows = t.needs().stream().allMatch(c -> c.containsKey("blotter") || c.containsKey("fumble"));
            for (int i = 0; i < sentences.size(); i++) {
                String sn = sentences.get(i);
                List<String> st = WordGate.tokens(sn, names);
                if (t.forms().stream().noneMatch(f -> WordGate.hasForm(st, f))) continue;
                boolean named = !WordGate.namesExact(sn, who).isEmpty()   // exact: "Steven" never names Steve
                        || (follows && i > 0 && WordGate.namesExact(sn, names).isEmpty() && !WordGate.namesExact(sentences.get(i - 1), who).isEmpty());
                if (!named || (pvp && !pvpWay(sn, t, facts, names))) ok = false;
            }
            if (!ok) out.add("claim (\"" + hit + "\")");
        }
        return out;
    }

    /** Ordinary English that holds a claim word: blanked before the claim scan. */
    static final java.util.regex.Pattern IDIOMS = java.util.regex.Pattern.compile("(?i)(?<![a-z])(?:clutch(?:es)? of|one[ -]shot at)(?![a-z])");

    private static final Set<String> PASSIVE = Set.of("got", "get", "gets", "getting", "was", "were", "been", "be", "is");

    /**
     * A PvP claim word says who won ("pvp" pairs are [who died, who killed them]): "Juno_B bodied Kai_Ore" needs Juno_B to
     * have killed Kai_Ore; "Kai_Ore got bodied (by Juno_B)" needs Kai_Ore to have died to a player (to Juno_B). A killer
     * backed only by a kill count or the kill board (first edition) names no victim: it backs a sentence that names
     * no victim ("Moss_T bodied 9 players"), never one that names who they beat.
     */
    static boolean pvpWay(String sentence, Term t, JsonObject f, Collection<String> names) {
        Set<String> killers = new java.util.HashSet<>(), victims = new java.util.HashSet<>(), pairs = new java.util.HashSet<>();
        for (JsonElement p : PyJson.arr(f.get("pvp"))) {
            JsonArray pa = PyJson.arr(p);
            if (pa.size() != 2) continue;
            String v = WordGate.nameKey(PyJson.strOr(pa.get(0), "")), k = WordGate.nameKey(PyJson.strOr(pa.get(1), ""));
            victims.add(v); killers.add(k); pairs.add(v + "\t" + k);
        }
        for (Map.Entry<String, JsonElement> p : PyJson.objOf(f, "today").entrySet()) if (num(PyJson.obj(p.getValue()).get("player_kills")) >= 1) killers.add(WordGate.nameKey(p.getKey()));
        if (PyJson.truthy(f.get("first_edition")) || !PyJson.arr(f.get("cold_cases")).isEmpty())
            for (JsonElement r : PyJson.arr(PyJson.objOf(f, "leaders").get("player_kills"))) if (PyJson.arr(r).size() == 2) killers.add(WordGate.nameKey(PyJson.strOr(PyJson.arr(r).get(0), "")));
        Set<String> all = new java.util.HashSet<>(killers);
        all.addAll(victims);
        for (String n : names) all.add(WordGate.nameKey(n));
        List<String> toks = WordGate.rawTokens(sentence);
        int at = -1;
        for (int i = 0; i < toks.size() && at < 0; i++) {
            String x = toks.get(i);
            for (String form : t.forms()) if (WordGate.hasForm(List.of(x), form.trim().split("\\s+")[0])) { at = i; break; }
        }
        if (at < 0) return true;
        String before = null, after = null;
        for (int i = at - 1; i >= 0 && before == null; i--) if (all.contains(toks.get(i))) before = toks.get(i);
        boolean by = false;   // "bodied by", "mace smashed by", "got bodied hard by": a "by" before the next name
        for (int i = at + 1; i < toks.size() && i <= at + 3 && !all.contains(toks.get(i)); i++) if (toks.get(i).equals("by")) { by = true; break; }
        for (int i = at + 1; i < toks.size() && after == null; i++) if (all.contains(toks.get(i))) after = toks.get(i);
        boolean passive = by || (at > 0 && PASSIVE.contains(toks.get(at - 1)));
        String killer = passive ? (by ? after : null) : before, victim = passive ? before : after;
        if (killer == null && victim == null) return false;
        if (killer != null && !killers.contains(killer)) return false;
        // a named victim needs the exact pair: a kill count or the season board backs the killer, never who they beat
        if (victim != null) return killer == null ? victims.contains(victim) : pairs.contains(victim + "\t" + killer);
        return true;
    }

    /** The paper's own titles (its name, the award titles, the owner's desk, the construction section), cut from text. */
    public static String withoutTitles(Settings s, String text) {
        List<String> titles = new ArrayList<>(List.of(s.paper().name(), s.paper().goodAward(), s.paper().badAward()));
        if (StylePrompt.hasOwner(s)) titles.add(s.owner().deskTitle());
        if (s.construction() != null) titles.add(s.construction().title());
        for (String t : titles) {
            String core = t == null ? "" : t.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
            if (!core.isBlank()) text = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(core), java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).replaceAll(" ");
        }
        return text;
    }

    /** Fact text the paper prints as written: owner lore, death lines, advancement titles, build names. */
    static List<String> heldText(Settings s, JsonObject f) {
        List<String> out = new ArrayList<>(strings(f.get("deaths")));
        if (s != null && StylePrompt.hasOwner(s)) out.addAll(s.owner().lore());
        out.addAll(strings(PyJson.objOf(f, "owner").get("lore")));
        for (JsonElement a : PyJson.arr(f.get("advancements"))) {
            JsonArray pa = PyJson.arr(a);
            if (pa.size() == 2 && PyJson.isStr(pa.get(1))) out.add(pa.get(1).getAsString());
        }
        buildNames(f.get("construction"), out);
        return out;
    }

    private static void buildNames(JsonElement e, List<String> out) {
        if (e == null) return;
        if (e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) buildNames(x, out);
        else if (e.isJsonObject())
            for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
                if ((x.getKey().equals("name") || x.getKey().equals("kind")) && PyJson.isStr(x.getValue())) out.add(x.getValue().getAsString());
                else buildNames(x.getValue(), out);
            }
    }

    /**
     * The players a claim word may be said about today: the backers of a per-player condition, the victims of the death lines
     * that back it, or the players in a PvP fact. Empty = not backed; null = backed by a day fact that names no one.
     */
    static Set<String> claimNames(Term t, JsonObject f) {
        Set<String> who = new LinkedHashSet<>();
        if (t.never()) return who;
        for (Map<String, Object> c : t.needs()) {
            List<String> m = match(c, f, true);
            if (m.isEmpty()) continue;
            if (c.keySet().stream().anyMatch(PLAYER_KEYS::contains)) {
                for (String x : m) who.add(x.substring(0, Math.max(0, x.indexOf(": "))));
            } else if (c.containsKey("death")) {
                for (String d : strings(f.get("deaths")))
                    for (Object x : (List<?>) c.get("death"))
                        if (deathHas(d, String.valueOf(x))) who.add(d.split(" ", 2)[0]);
            } else if (c.containsKey("fumble")) {   // the season leader who is on today's board for that stat, but not first
                JsonObject season = PyJson.objOf(f, "leaders");
                for (Map.Entry<String, JsonElement> e : PyJson.objOf(f, "leaders_today").entrySet()) {
                    JsonArray today = PyJson.arr(e.getValue()), all = PyJson.arr(season.get(e.getKey()));
                    if (LOWER_BETTER.contains(e.getKey()) || today.size() < 2 || all.isEmpty() || first(all).equals(first(today))) continue;
                    for (JsonElement r : today) if (PyJson.arr(r).size() > 0 && first(all).equals(PyJson.strOr(PyJson.arr(r).get(0), ""))) who.add(first(all));
                }
            } else if (c.containsKey("blotter")) {   // the "by" player of a pair that has every listed kind
                Map<String, Set<String>> pairs = new java.util.HashMap<>();
                for (JsonElement e : PyJson.arr(f.get("police_blotter"))) {
                    JsonObject o = PyJson.obj(e);
                    pairs.computeIfAbsent(PyJson.strOr(o.get("by"), "") + "\t" + PyJson.strOr(o.get("to"), ""), k2 -> new java.util.HashSet<>())
                            .add(PyJson.strOr(o.get("what"), ""));
                }
                for (Map.Entry<String, Set<String>> e : pairs.entrySet())
                    if (((List<?>) c.get("blotter")).stream().allMatch(x -> e.getValue().contains(String.valueOf(x)))) who.add(e.getKey().split("\t", -1)[0]);
            } else if (c.containsKey("pvp")) {
                for (JsonElement p : PyJson.arr(f.get("pvp"))) for (JsonElement n : PyJson.arr(p)) if (PyJson.isStr(n)) who.add(n.getAsString());
                for (Map.Entry<String, JsonElement> p : PyJson.objOf(f, "today").entrySet()) if (num(PyJson.obj(p.getValue()).get("player_kills")) >= 1) who.add(p.getKey());
                if (PyJson.truthy(f.get("first_edition")) || !PyJson.arr(f.get("cold_cases")).isEmpty())
                    for (JsonElement r : PyJson.arr(PyJson.objOf(f, "leaders").get("player_kills"))) if (PyJson.arr(r).size() == 2) who.add(PyJson.strOr(PyJson.arr(r).get(0), ""));
            } else return null;
        }
        return who;
    }

    // ---------------------------------------------------------------- small helpers

    static LocalDate date(JsonObject f) {
        try { return LocalDate.parse(PyJson.strOr(f.get("date"), "")); }
        catch (RuntimeException e) { return LocalDate.of(2026, 1, 1); }
    }

    static Set<String> names(JsonObject f) {
        Set<String> out = new java.util.HashSet<>(PyJson.objOf(f, "season_totals").keySet());
        out.addAll(PyJson.objOf(f, "today").keySet());
        return out;
    }

    static List<String> strings(JsonElement e) {
        List<String> out = new ArrayList<>();
        for (JsonElement x : PyJson.arr(e)) if (PyJson.isStr(x)) out.add(x.getAsString());
        return out;
    }

    static String first(JsonArray board) {
        JsonArray r = PyJson.arr(board.get(0));
        return r.isEmpty() ? "" : PyJson.strOr(r.get(0), "");
    }

    static double num(JsonElement e) {
        return PyJson.isNum(e) ? PyJson.dbl(e) : 0;
    }

    /** 1166 -> "1,166"; 147.8 stays 147.8 (the fact check knows the data's own numbers). */
    static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.format(Locale.ROOT, "%,d", (long) v);
        return String.valueOf(v);
    }
}
