package io.github.eastmadc.masthead.config;

import java.time.LocalTime;
import java.util.List;

/**
 * Everything a server owner can tune, already validated. Pure data: no Bukkit types, so facts/ and edition/ can be tested
 * outside a server. Built by {@link SettingsLoader} from config.yml; {@link #defaults()} is the generic out-of-the-box paper.
 */
public record Settings(Paper paper, Tone tone, Owner owner, Privacy privacy, Schedule schedule, WriterCfg writer, Web web,
                       Features features, Alerts alerts, Construction construction) {

    public Settings(Paper paper, Tone tone, Owner owner, Privacy privacy, Schedule schedule, WriterCfg writer, Web web,
                    Features features, Alerts alerts) {
        this(paper, tone, owner, privacy, schedule, writer, web, features, alerts, Construction.defaults());
    }

    /** "masthead construction pause": the same settings with construction.print off, so paused is exactly print: false. */
    /** The Building Report runs unless construction news prints: then construction owns the builders (one list, and private players stay out). */
    public boolean buildingReportPrints() {
        return features.buildingReport() && !(features.construction() && construction.print());
    }

    /** The same settings with owner.name spelled as in game, so the prompts, checks and pages all use one spelling. */
    public Settings ownerNamed(String name) {
        if (owner == null || name == null || name.isEmpty() || name.equals(owner.name())) return this;
        return new Settings(paper, tone, new Owner(name, owner.deskTitle(), owner.lore(), owner.hoursOk(), owner.pronoun()), privacy, schedule, writer, web,
                features, alerts, construction);
    }

    public Settings constructionPaused(boolean paused) {
        return paused ? constructionPrint(false) : this;
    }

    /** The same settings with construction.print set (a preview shows the section as it will print; nothing is kept). */
    public Settings constructionPrint(boolean print) {
        Construction c = construction;
        if (c.print() == print) return this;
        return new Settings(paper, tone, owner, privacy, schedule, writer, web, features, alerts,
                new Construction(c.title(), print, c.defaultLevel(), c.names(), c.maxEntries(), c.minBlocks(), c.crossDemolition(), c.bigDemolitionBlocks(),
                        c.bigDemolitionPercent(), c.forgetAfterDays(), c.ignoreWorlds(), c.maxCellsPerMinute(), c.optedIn(), c.detail(), c.surveyNames(), c.signNames()));
    }

    /** The newspaper itself. language: en-US | en-GB, the paper's spelling and date order (tone.language is swearing). */
    public record Paper(String name, String serverName, String tagline, String theme, String readers,
                        String goodAward, String badAward, String watchAnimal, String language) {
        public Paper {
            language = "en-GB".equals(language) ? "en-GB" : "en-US";
        }
        public Paper(String name, String serverName, String tagline, String theme, String readers, String goodAward, String badAward, String watchAnimal) {
            this(name, serverName, tagline, theme, readers, goodAward, badAward, watchAnimal, "en-US");
        }
        public boolean british() { return language.equals("en-GB"); }
    }

    /**
     * voice: "" (pick from spice) | newsroom | group-chat | sportscast | classic; spice: mild|medium|spicy|savage; language: clean|pg13|strong;
     * slang: off|light|heavy; audience: all-ages|teens|adults; length: short|normal|long; bits: see StylePrompt. lexiconAdd and
     * lexiconRemove are the owner's own words (tone.lexicon.add/remove). The audience caps the three tone settings (spice,
     * language and slang) here, so no way of building a Tone gets past that cap. Free text (paper.readers, theme, a word's
     * "means") is not capped by the audience: the code checks (WordGate's floor and word list, FactCheck, the loader's
     * checks on owner words) are what hold the hard lines.
     */
    public record Tone(String voice, String spice, String language, String slang, String audience, String length, List<String> bits,
                       List<LexTerm> lexiconAdd, List<String> lexiconRemove) {
        public Tone {
            audience = AUDIENCES.contains(audience) ? audience : "all-ages";
            spice = capped(SPICES, spice, SPICE_CAP.get(audience));
            language = capped(LANGUAGES, language, LANGUAGE_CAP.get(audience));
            slang = capped(SLANGS, slang, SLANG_CAP.get(audience));
            voice = voice == null || !VOICES.contains(voice) ? "" : voice;
            lexiconAdd = lexiconAdd == null ? List.of() : List.copyOf(lexiconAdd);
            lexiconRemove = lexiconRemove == null ? List.of() : List.copyOf(lexiconRemove);
        }

        /** The old four dials: the audience is the lowest one that allows them (so nothing is tamed), slang light. */
        public Tone(String spice, String language, String length, List<String> bits) {
            this("", spice, language, "light", audienceFor(spice, language, "light"), length, bits, List.of(), List.of());
        }

        /** The voice that writes: the configured one, or newsroom for mild/medium and sportscast for spicy/savage. */
        public String effectiveVoice() {
            if (!voice.isEmpty()) return voice;
            return spice.equals("spicy") || spice.equals("savage") ? "sportscast" : "newsroom";
        }

        /** Plain console lines for each dial the audience lowered (raw values as the owner wrote them). */
        public static List<String> capNotes(String spice, String language, String slang, String audience) {
            List<String> out = new java.util.ArrayList<>();
            String a = AUDIENCES.contains(audience) ? audience : "all-ages";
            String[][] dials = {{"spice", spice, SPICE_CAP.get(a)}, {"language", language, LANGUAGE_CAP.get(a)}, {"slang", slang, SLANG_CAP.get(a)}};
            List<List<String>> orders = List.of(SPICES, LANGUAGES, SLANGS);
            for (int i = 0; i < 3; i++) {
                String v = dials[i][1], cap = dials[i][2];
                if (orders.get(i).indexOf(v) > orders.get(i).indexOf(cap))
                    out.add("tone." + dials[i][0] + " is " + v + ", but audience " + a + " caps it at " + cap + ". The paper uses " + cap + ".");
            }
            return out;
        }

        private static String capped(List<String> order, String v, String cap) {
            int i = order.indexOf(v), c = order.indexOf(cap);
            if (i < 0) return order.get(0);
            return i > c ? cap : v;
        }
    }

    /** An owner's own lexicon word (tone.lexicon.add): kind game|slang, tier all-ages|teens|adults. */
    public record LexTerm(String term, String means, String kind, String tier) {}

    public static final List<String> AUDIENCES = List.of("all-ages", "teens", "adults");
    public static final List<String> SLANGS = List.of("off", "light", "heavy");
    /** Built-in voices; "" means pick from spice. */
    public static final List<String> VOICES = List.of("", "newsroom", "group-chat", "sportscast", "classic");
    /** The most each audience allows. Hard lines are not dials: no audience changes them. */
    public static final java.util.Map<String, String> SPICE_CAP = java.util.Map.of("all-ages", "medium", "teens", "savage", "adults", "savage");
    public static final java.util.Map<String, String> LANGUAGE_CAP = java.util.Map.of("all-ages", "clean", "teens", "pg13", "adults", "strong");
    public static final java.util.Map<String, String> SLANG_CAP = java.util.Map.of("all-ages", "light", "teens", "heavy", "adults", "heavy");

    /** The lowest audience whose caps allow these dials (an old config without tone.audience keeps its paper). */
    public static String audienceFor(String spice, String language, String slang) {
        spice = spice == null ? "" : spice.trim().toLowerCase(java.util.Locale.ROOT);
        language = language == null ? "" : language.trim().toLowerCase(java.util.Locale.ROOT);
        slang = slang == null ? "" : slang.trim().toLowerCase(java.util.Locale.ROOT);
        for (String a : AUDIENCES)
            if (Tone.capNotes(spice, language, slang, a).isEmpty()) return a;
        return "adults";
    }

    /** Optional featured owner column. name empty = no column. pronoun: he|she|they. */
    public record Owner(String name, String deskTitle, List<String> lore, boolean hoursOk, String pronoun) {
        public Owner(String name, String deskTitle, List<String> lore, boolean hoursOk) { this(name, deskTitle, lore, hoursOk, "they"); }
        public boolean they() { return !"he".equals(pronoun) && !"she".equals(pronoun); }
        /** subject (he/she/they), object (him/her/them), possessive (his/her/their), "he's"/"she's"/"they're", "has"/"have". */
        public String subj() { return they() ? "they" : pronoun; }
        public String obj() { return they() ? "them" : pronoun.equals("he") ? "him" : "her"; }
        public String poss() { return they() ? "their" : pronoun.equals("he") ? "his" : "her"; }
        public String subjIs() { return they() ? "they're" : pronoun + "'s"; }
        public String has() { return they() ? "have" : "has"; }
    }

    /**
     * hidePlayers are never named; neverMention are extra topics the writer must avoid. retentionDays: the day's facts (data/)
     * and stats snapshots (stats/) older than this are deleted. The editions/ archive is kept.
     */
    public record Privacy(List<String> hidePlayers, List<String> neverMention, int retentionDays) {
        public Privacy(List<String> hidePlayers, List<String> neverMention) { this(hidePlayers, neverMention, 40); }
    }

    public record Schedule(LocalTime time, String zone) {}

    /**
     * mode: anthropic|openai-compatible|command|exchange|none. command = argv run with the prompt on stdin. model, effort,
     * apiKey, apiKeyFile and batch are writer.anthropic.*; openai is writer.openai-compatible.*. monthlyBudgetUsd: 0 = no cap.
     */
    public record WriterCfg(String mode, String model, String effort, String apiKey, List<String> command, int timeoutSeconds,
                            int deskTimeoutSeconds, boolean batch, String apiKeyFile, double monthlyBudgetUsd, OpenAi openai) {
        public WriterCfg(String mode, String model, String effort, String apiKey, List<String> command, int timeoutSeconds, int deskTimeoutSeconds) {
            this(mode, model, effort, apiKey, command, timeoutSeconds, deskTimeoutSeconds, false, "", 10, OpenAi.NONE);
        }
    }

    /** writer.openai-compatible.*. extraBody is a JSON object text ("{}" when unset). Prices are US dollars per million tokens. */
    public record OpenAi(String baseUrl, String model, String apiKeyFile, String apiKey, String reasoningEffort,
                         double pricePerMillionInput, double pricePerMillionOutput, String extraBody) {
        public static final OpenAi NONE = new OpenAi("", "", "", "", "", 0, 0, "{}");
    }

    /** mode: builtin|folder|off. publicUrl ends with '/'. accessLog = optional web access log for readership (folder mode). */
    public record Web(String mode, String bind, int port, String folder, String publicUrl, String accessLog) {}

    /** buildingReport: the daily "Building Report" (blocks placed per player, from the stats files; no locations). */
    public record Features(boolean mailroom, boolean blotter, boolean readership, boolean joinMessage, int joinDelaySeconds,
                           String healthExtraFile, boolean buildingReport, boolean construction) {
        /** Without construction news (off). */
        public Features(boolean mailroom, boolean blotter, boolean readership, boolean joinMessage, int joinDelaySeconds,
                        String healthExtraFile, boolean buildingReport) {
            this(mailroom, blotter, readership, joinMessage, joinDelaySeconds, healthExtraFile, buildingReport, false);
        }
        /** Without the Building Report (the facts stay exactly as before it existed). */
        public Features(boolean mailroom, boolean blotter, boolean readership, boolean joinMessage, int joinDelaySeconds, String healthExtraFile) {
            this(mailroom, blotter, readership, joinMessage, joinDelaySeconds, healthExtraFile, false);
        }
    }

    /**
     * Construction news (used only when features.construction is on). defaultLevel: public|private, what a player's builds
     * may say until they choose. optedIn: names that count as public while listed, unless they chose (a server that was
     * asked in person). crossDemolition: blotter|owner|off. detail: basic|standard|full, how much the paper says about what a
     * build is and what is inside (Kinds.shown, Features.shown). surveyNames: print the build names in survey.json. signNames: a builder's sign may name the build.
     */
    public record Construction(String title, boolean print, String defaultLevel, boolean names, int maxEntries, int minBlocks,
                               String crossDemolition, int bigDemolitionBlocks, int bigDemolitionPercent, int forgetAfterDays,
                               List<String> ignoreWorlds, int maxCellsPerMinute, List<String> optedIn, String detail, boolean surveyNames, boolean signNames) {
        public Construction(String title, boolean print, String defaultLevel, boolean names, int maxEntries, int minBlocks,
                            String crossDemolition, int bigDemolitionBlocks, int bigDemolitionPercent, int forgetAfterDays,
                            List<String> ignoreWorlds, int maxCellsPerMinute, List<String> optedIn, String detail) {
            this(title, print, defaultLevel, names, maxEntries, minBlocks, crossDemolition, bigDemolitionBlocks, bigDemolitionPercent, forgetAfterDays,
                    ignoreWorlds, maxCellsPerMinute, optedIn, detail, false, false);
        }
        public Construction(String title, boolean print, String defaultLevel, boolean names, int maxEntries, int minBlocks,
                            String crossDemolition, int bigDemolitionBlocks, int bigDemolitionPercent, int forgetAfterDays,
                            List<String> ignoreWorlds, int maxCellsPerMinute, List<String> optedIn) {
            this(title, print, defaultLevel, names, maxEntries, minBlocks, crossDemolition, bigDemolitionBlocks, bigDemolitionPercent, forgetAfterDays,
                    ignoreWorlds, maxCellsPerMinute, optedIn, "standard");
        }

        public static Construction defaults() {
            return new Construction("Planning & Zoning", true, "private", true, 3, 64, "blotter", 100, 15, 30, List.of(), 20000, List.of());
        }
    }

    /**
     * Owner pushes. helperPushesConcerns: the host's exchange helper pushes the files in the concern/ folder (a custom
     * host helper can; the one in tools/ does not).
     */
    public record Alerts(String ntfyUrl, String discordWebhook, boolean helperPushesConcerns) {
        public Alerts(String ntfyUrl, String discordWebhook) { this(ntfyUrl, discordWebhook, false); }
        public boolean ntfyOk() { return usableUrl(ntfyUrl) != null; }
        public boolean discordOk() { return usableUrl(discordWebhook) != null; }
        /** Somewhere a worried letter can reach a person. writer.mode exchange alone does not count. */
        public boolean hasChannel() { return ntfyOk() || discordOk() || helperPushesConcerns; }
    }

    /** The URL trimmed, or null when it is not a usable http(s) address. */
    public static String usableUrl(String url) {
        if (url == null) return null;
        String u = url.strip();
        if (u.isEmpty()) return null;
        try {
            java.net.URI uri = java.net.URI.create(u);
            String sc = uri.getScheme();
            if (sc == null || !(sc.equalsIgnoreCase("https") || sc.equalsIgnoreCase("http")) || uri.getHost() == null) return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
        return u;
    }

    public static final List<String> SPICES = List.of("mild", "medium", "spicy", "savage");
    public static final List<String> LANGUAGES = List.of("clean", "pg13", "strong");
    public static final List<String> LENGTHS = List.of("short", "normal", "long");
    public static final List<String> BITS = List.of("obituaries", "sports", "weather", "horoscopes", "market", "classifieds", "animal-watch");
    public static final List<String> WRITER_MODES = List.of("anthropic", "openai-compatible", "command", "exchange", "none");
    public static final List<String> WEB_MODES = List.of("builtin", "folder", "off");
    public static final List<String> LEVELS = List.of("public", "private");
    public static final List<String> CROSS_DEMOLITION = List.of("blotter", "owner", "off");
    public static final List<String> DETAIL = List.of("basic", "standard", "full");

    public static Settings defaults() {
        return new Settings(
                new Paper("The Daily Block", "our Minecraft server", "Delivered every morning.",
                        "a small-town paper that takes everything happening on this server far too seriously",
                        "players of all ages", "🏆 Play of the Day", "🤡 Fumble of the Day", "chicken"),
                // R10: public defaults are safe for every age; a server opts up with tone.audience
                new Tone("", "medium", "clean", "light", "all-ages", "normal", List.of("obituaries", "sports", "weather", "horoscopes"), List.of(), List.of()),
                new Owner("", "The Owner's Desk", List.of(), true),
                new Privacy(List.of(), List.of(), 40),
                new Schedule(LocalTime.of(5, 30), ""),
                new WriterCfg("none", "claude-opus-5-5", "medium", "", List.of("claude", "-p", "--tools", "", "--strict-mcp-config",
                        "--no-session-persistence", "--output-format", "text"), 300, 120, true, "", 10, OpenAi.NONE),
                new Web("builtin", "0.0.0.0", 8150, "", "", ""),
                new Features(true, true, true, true, 3, "", true, false),
                new Alerts("", ""));
    }
}
