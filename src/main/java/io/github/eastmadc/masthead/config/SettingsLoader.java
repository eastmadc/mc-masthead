package io.github.eastmadc.masthead.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Reads config.yml into {@link Settings}. Every bad value falls back to the default and is reported once, in plain words. */
public final class SettingsLoader {
    /** A lexicon meaning that gives the writer an order: "call" at the start or aimed at someone, "every section", "every paper". */
    static final java.util.regex.Pattern ORDER_WORDS = java.util.regex.Pattern.compile(
            "(?i)(?<![a-z])calls?\\s+(?:him|her|them|everyone|everybody|anyone|us|me|you|\\w+_\\w*)(?![a-z])|^\\s*call\\b|(?<![a-z])every (?:section|paper)(?![a-z])");

    private SettingsLoader() {}

    public static Settings load(FileConfiguration c, Consumer<String> warn) {
        return load(c, warn, System.getenv("ANTHROPIC_API_KEY"));
    }

    /** envKey: the ANTHROPIC_API_KEY value (null if unset). Tests pass their own. */
    public static Settings load(FileConfiguration c, Consumer<String> warn, String envKey) {
        Settings d = Settings.defaults();
        var dp = d.paper();
        var paper = new Settings.Paper(
                text(c, "paper.name", dp.name(), 60, warn),
                text(c, "paper.server-name", dp.serverName(), 60, warn),
                text(c, "paper.tagline", dp.tagline(), 120, warn),
                text(c, "paper.theme", dp.theme(), 300, warn),
                text(c, "paper.readers", dp.readers(), 120, warn),
                text(c, "paper.awards.good", dp.goodAward(), 60, warn),
                text(c, "paper.awards.bad", dp.badAward(), 60, warn),
                word(c, "paper.watch-animal", dp.watchAnimal(), warn),
                paperLanguage(c.getString("paper.language"), warn));
        var dt = d.tone();
        List<String> bits = new ArrayList<>();
        for (String b : strings(c, "tone.bits")) {
            String x = b.trim().toLowerCase(Locale.ROOT);
            if (Settings.BITS.contains(x)) { if (!bits.contains(x)) bits.add(x); }
            else warn.accept("tone.bits: unknown section '" + b + "' ignored (choose from " + Settings.BITS + ")");
        }
        // YAML 1.1 reads an unquoted off/on as a boolean: "slang: off" means off
        Object rawSlang = c.get("tone.slang");
        String spice = choice(c, "tone.spice", dt.spice(), Settings.SPICES, warn), lang = choice(c, "tone.language", dt.language(), Settings.LANGUAGES, warn),
                slang = rawSlang instanceof Boolean on ? (on ? "light" : "off") : choice(c, "tone.slang", dt.slang(), Settings.SLANGS, warn), audience;
        if (c.isSet("tone.audience")) audience = choice(c, "tone.audience", dt.audience(), Settings.AUDIENCES, warn);
        else {   // a config from before tone.audience: never tame a running paper by surprise
            audience = Settings.audienceFor(spice, lang, slang);
            warn.accept("tone.audience is not set; using " + audience + ", the lowest audience that allows your spice and language.");
        }
        Settings.Tone.capNotes(spice, lang, slang, audience).forEach(warn);
        List<String> remove = new ArrayList<>();
        for (Object o : list(c, "tone.lexicon.remove")) {
            if (o instanceof Boolean) {   // YAML 1.1 reads an unquoted yes/no/on/off as true/false
                warn.accept("tone.lexicon.remove: put the term in quotes (YAML reads yes/no/on/off as true/false); '" + o + "' ignored");
                continue;
            }
            String r = String.valueOf(o);
            if (o == null || r.isBlank()) continue;
            String k = r.strip().toLowerCase(Locale.ROOT);
            String whole = io.github.eastmadc.masthead.edition.Lexicon.shippedTermFor(k);
            if (whole == null) {   // a classic heavy-slang word is no lexicon word, and it is removed all the same (StylePrompt.heavyRemoved)
                if (io.github.eastmadc.masthead.edition.StylePrompt.HEAVY_SLANG.stream().noneMatch(h -> io.github.eastmadc.masthead.edition.StylePrompt.heavyKey(h).equals(io.github.eastmadc.masthead.edition.StylePrompt.heavyKey(k))))
                    warn.accept("tone.lexicon.remove: '" + r.strip() + "' is not a shipped word; nothing removed");
            }
            else if (!whole.equalsIgnoreCase(k)) warn.accept("tone.lexicon.remove: '" + r.strip() + "' is part of '" + whole + "'; the whole word is removed");
            remove.add(k);
        }
        List<Settings.LexTerm> add = new ArrayList<>();
        boolean capped = false;
        for (Object o : list(c, "tone.lexicon.add")) {
            if (!(o instanceof java.util.Map<?, ?> m)) {
                warn.accept("tone.lexicon.add: '" + o + "' is not a {term, means, kind, tier} entry; ignored");
                continue;
            }
            if (m.get("term") instanceof Boolean || m.get("means") instanceof Boolean) {   // YAML 1.1: an unquoted yes/no/on/off is true/false
                warn.accept("tone.lexicon.add: put the term in quotes (YAML reads yes/no/on/off as true/false); '" + m.get("term") + "' ignored");
                continue;
            }
            String term = String.valueOf(m.get("term") == null ? "" : m.get("term")).replaceAll("\\p{Cntrl}", " ").strip();
            String means = String.valueOf(m.get("means") == null ? "" : m.get("means")).replaceAll("\\p{Cntrl}", " ").strip();
            String kind = String.valueOf(m.get("kind") == null ? "slang" : m.get("kind")).strip().toLowerCase(Locale.ROOT);
            String tier = String.valueOf(m.get("tier") == null ? "all-ages" : m.get("tier")).strip().toLowerCase(Locale.ROOT);
            String low = term.toLowerCase(Locale.ROOT);
            if (!low.matches("[a-z0-9' -]{2,30}") || !low.matches(".*[a-z].*[a-z].*") || means.isEmpty() || means.length() > 60 || !List.of("game", "slang").contains(kind)
                    || !Settings.AUDIENCES.contains(tier)) {
                warn.accept("tone.lexicon.add: each word needs term (2-30 English letters, digits, space, apostrophe or hyphen, with 2 letters at least), means (up to 60 "
                        + "characters), kind game|slang and tier " + Settings.AUDIENCES + "; '" + term + "' ignored");
                continue;
            }
            // means goes to the writer word for word: a meaning, never an order or a put-down (the tone caps don't cover free text)
            // "what we call a big build day" is a meaning; "call them the champ" is an order
            String order = null;
            for (java.util.regex.Pattern p : List.of(io.github.eastmadc.masthead.construction.NameRules.INSTRUCTION,
                    io.github.eastmadc.masthead.construction.NameRules.PUT_DOWN, ORDER_WORDS)) {
                java.util.regex.Matcher om = p.matcher(means);
                if (om.find()) { order = om.group().strip(); break; }
            }
            if (order != null) {
                warn.accept("tone.lexicon.add: '" + term + "' has a meaning that reads like an order or a put-down ('" + order + "'); say what the word means, nothing else; ignored");
                continue;
            }
            String both = term + " " + means;
            List<String> dated = io.github.eastmadc.masthead.edition.WordGate.dated(both, List.of());
            if (!dated.isEmpty()) {   // the draft check sends a paper that uses it back (every voice but classic at slang heavy)
                warn.accept("tone.lexicon.add: '" + dated.get(0) + "' is on Masthead's dated-slang list, which your own words can't use; '" + term + "' ignored");
                continue;
            }
            if (!io.github.eastmadc.masthead.edition.WordGate.floor(both, List.of()).isEmpty()) {   // the floor holds at every setting
                warn.accept("tone.lexicon.add: '" + term + "' uses a word the paper never prints; ignored");
                continue;
            }
            if (!io.github.eastmadc.masthead.edition.Lexicon.floorOk(both, tier)) {
                warn.accept("tone.lexicon.add: '" + term + "' has a swear that tier " + tier + " never prints (" + tier + " allows "
                        + io.github.eastmadc.masthead.edition.Lexicon.tierLanguage(tier) + " at most); ignored");
                continue;
            }
            if (io.github.eastmadc.masthead.edition.Lexicon.isClaim(low) || (io.github.eastmadc.masthead.edition.Lexicon.isShipped(low) && !remove.contains(low))) {
                warn.accept("tone.lexicon.add: '" + term + "' is a play the paper can't check, or a shipped word (drop it with remove first to redefine it); ignored");
                continue;
            }
            if (Settings.AUDIENCES.indexOf(tier) > Settings.AUDIENCES.indexOf(audience))
                warn.accept("tone.lexicon.add: '" + term + "' has tier " + tier + ", above tone.audience " + audience + ", so it is never offered");
            if (add.size() < 40) add.add(new Settings.LexTerm(term, means, kind, tier));
            else if (!capped) { capped = true; warn.accept("tone.lexicon.add: only the first 40 words are used"); }
        }
        var tone = new Settings.Tone(choice(c, "tone.voice", dt.voice(), Settings.VOICES, warn), spice, lang, slang, audience,
                choice(c, "tone.length", dt.length(), Settings.LENGTHS, warn),
                c.isSet("tone.bits") ? List.copyOf(bits) : dt.bits(), add, remove);
        for (Settings.LexTerm a : add)   // tone.language after the audience cap
            if (!io.github.eastmadc.masthead.edition.Lexicon.swearOk(a.term() + " " + a.means(), tone.language()))
                warn.accept("tone.lexicon.add: '" + a.term() + "' has a swear but tone.language is " + tone.language() + ", so it is never offered");
        if (tone.slang().equals("heavy") && tone.effectiveVoice().equals("newsroom"))
            warn.accept("tone.slang heavy is used as light with the newsroom voice (set voice: group-chat or sportscast for heavy)");
        var dow = d.owner();
        String ownerName = c.getString("owner.name", "").trim();
        if (!ownerName.isEmpty() && !ownerName.matches("\\.?[A-Za-z0-9_]{3,16}")) {
            warn.accept("owner.name: '" + ownerName + "' isn't a Minecraft name; owner column off"); ownerName = "";
        }
        List<String> lore = lines(c, "owner.lore", 20, 500);
        for (String l : lore)
            for (String w : io.github.eastmadc.masthead.edition.WordGate.dated(l, List.of()))
                warn.accept("owner.lore: '" + w + "' is on Masthead's dated-slang list, so a paper that retells this legend is sent back; say it another way");
        var owner = new Settings.Owner(ownerName, text(c, "owner.desk-title", dow.deskTitle(), 60, warn),
                lore, flag(c, "owner.hours-ok", dow.hoursOk(), warn),
                choice(c, "owner.pronoun", "they", List.of("he", "she", "they"), warn));
        List<String> hide = new ArrayList<>();
        for (String n : strings(c, "privacy.hide-players"))
            if (n.trim().matches("\\.?[A-Za-z0-9_]{3,16}")) hide.add(n.trim()); else warn.accept("privacy.hide-players: '" + n + "' isn't a Minecraft name");
        var privacy = new Settings.Privacy(List.copyOf(hide), lines(c, "privacy.never-mention", 30, 200),
                range(c, "privacy.retention-days", d.privacy().retentionDays(), 10, 3650, warn));
        LocalTime time = d.schedule().time();
        // YAML reads an unquoted 17:30 as the number 1050 (17 x 60 + 30): minutes since midnight
        if (c.get("schedule.time") instanceof Integer n && n >= 0 && n <= 1439) time = LocalTime.of(n / 60, n % 60);
        else try { time = LocalTime.parse(c.getString("schedule.time", "05:30").trim()); }
        catch (DateTimeParseException e) { warn.accept("schedule.time: use HH:MM, e.g. 05:30; using 05:30"); }
        String zone = c.getString("schedule.zone", "").trim();
        if (!zone.isEmpty()) {
            try { ZoneId.of(zone); } catch (Exception e) { warn.accept("schedule.zone: unknown time zone '" + zone + "'; using the server's"); zone = ""; }
        }
        var dw = d.writer();
        List<String> cmd = c.isList("writer.command") ? List.copyOf(c.getStringList("writer.command")) : dw.command();
        for (String k : List.of("model", "effort", "api-key"))
            if (c.isSet("writer." + k)) warn.accept("writer." + k + " has moved to writer.anthropic." + k + "; the old one is ignored");
        String oaUrl = c.getString("writer.openai-compatible.base-url", "").trim();
        if (!oaUrl.isEmpty() && Settings.usableUrl(oaUrl) == null) { warn.accept("writer.openai-compatible.base-url: must start with http:// or https://"); oaUrl = ""; }
        String oaModel = c.getString("writer.openai-compatible.model", "").replaceAll("\\p{Cntrl}", "").trim();
        if (oaModel.length() > 120) { warn.accept("writer.openai-compatible.model: longer than 120 characters; ignored"); oaModel = ""; }
        var openai = new Settings.OpenAi(oaUrl, oaModel,
                c.getString("writer.openai-compatible.api-key-file", "").trim(), c.getString("writer.openai-compatible.api-key", "").trim(),
                c.getString("writer.openai-compatible.reasoning-effort", "").trim().toLowerCase(Locale.ROOT),
                money(c, "writer.openai-compatible.price-per-million-input", 0, warn), money(c, "writer.openai-compatible.price-per-million-output", 0, warn),
                c.isConfigurationSection("writer.openai-compatible.extra-body")
                        ? new com.google.gson.Gson().toJson(plain(c.getConfigurationSection("writer.openai-compatible.extra-body"))) : "{}");
        var writer = new Settings.WriterCfg(
                choice(c, "writer.mode", dw.mode(), Settings.WRITER_MODES, warn),
                modelId(c.getString("writer.anthropic.model", dw.model()), dw.model(), warn),
                choice(c, "writer.anthropic.effort", dw.effort(), List.of("low", "medium", "high", "xhigh", "max"), warn),
                c.getString("writer.anthropic.api-key", "").trim(),
                cmd.isEmpty() ? dw.command() : cmd,
                range(c, "writer.timeout-seconds", dw.timeoutSeconds(), 30, 900, warn),
                range(c, "writer.desk-timeout-seconds", dw.deskTimeoutSeconds(), 30, 600, warn),
                flag(c, "writer.anthropic.batch", dw.batch(), warn),
                c.getString("writer.anthropic.api-key-file", "").trim(),
                money(c, "writer.monthly-budget-usd", dw.monthlyBudgetUsd(), warn),
                openai);
        var dweb = d.web();
        String url = c.getString("web.public-url", "").trim();
        if (!url.isEmpty() && !url.matches("https?://[^\\s\"'<>]+")) { warn.accept("web.public-url: must start with http:// or https://"); url = ""; }
        if (!url.isEmpty() && !url.endsWith("/")) url += "/";
        var web = new Settings.Web(choice(c, "web.mode", dweb.mode(), Settings.WEB_MODES, warn),
                c.getString("web.bind", dweb.bind()).trim(), range(c, "web.port", dweb.port(), 1, 65535, warn),
                c.getString("web.folder", "").trim(), url, c.getString("web.access-log", "").trim());
        var df = d.features();
        var alerts = new Settings.Alerts(c.getString("alerts.ntfy-url", "").trim(), c.getString("alerts.discord-webhook", "").trim(),
                flag(c, "alerts.helper-pushes-concerns", false, warn));
        // No alert channel, no mailroom: a worried letter must reach a person. No AI writer, no mailroom: letters are
        // printed only after the AI editor reads them. (Masthead logs the reason once.)
        var features = new Settings.Features(flag(c, "features.mailroom", df.mailroom(), warn) && alerts.hasChannel() && noWriterReason(writer, envKey).isEmpty(), flag(c, "features.blotter", df.blotter(), warn),
                flag(c, "features.readership", df.readership(), warn), flag(c, "features.join-message", df.joinMessage(), warn),
                range(c, "features.join-delay-seconds", df.joinDelaySeconds(), 0, 60, warn), c.getString("features.health-extra-file", "").trim(),
                flag(c, "features.building-report", df.buildingReport(), warn), flag(c, "features.construction", df.construction(), warn));
        if (!web.mode().equals("folder") && !web.accessLog().isEmpty()) warn.accept("web.access-log is only used in folder mode");
        if (web.mode().equals("folder") && web.folder().isEmpty()) warn.accept("web.mode is folder but web.folder is empty; pages won't be published");
        return new Settings(paper, tone, owner, privacy, new Settings.Schedule(time, zone), writer, web, features, alerts, construction(c, warn));
    }

    private static Settings.Construction construction(ConfigurationSection c, Consumer<String> warn) {
        var d = Settings.Construction.defaults();
        List<String> opted = new ArrayList<>();
        for (String n : strings(c, "construction.opted-in"))
            if (n.trim().matches("\\.?[A-Za-z0-9_]{3,16}")) opted.add(n.trim()); else warn.accept("construction.opted-in: '" + n + "' isn't a Minecraft name");
        String title = text(c, "construction.title", d.title(), 40, warn);
        if (!io.github.eastmadc.masthead.edition.ConstructionWords.titleOk(title)) {
            warn.accept("construction.title: uses a word construction news may not print; using \"" + d.title() + "\"");
            title = d.title();
        }
        return new Settings.Construction(
                title,
                flag(c, "construction.print", d.print(), warn),
                choice(c, "construction.default-level", d.defaultLevel(), Settings.LEVELS, warn),
                flag(c, "construction.names", d.names(), warn),
                range(c, "construction.max-entries", d.maxEntries(), 1, 5, warn),
                range(c, "construction.min-blocks", d.minBlocks(), 16, 10000, warn),
                choice(c, "construction.cross-demolition", d.crossDemolition(), Settings.CROSS_DEMOLITION, warn),
                range(c, "construction.big-demolition-blocks", d.bigDemolitionBlocks(), 20, 100000, warn),
                range(c, "construction.big-demolition-percent", d.bigDemolitionPercent(), 5, 100, warn),
                range(c, "construction.forget-after-days", d.forgetAfterDays(), 7, 90, warn),
                lines(c, "construction.ignore-worlds", 32, 64),
                range(c, "construction.max-cells-per-minute", d.maxCellsPerMinute(), 1000, 1000000, warn),
                List.copyOf(opted),
                choice(c, "construction.detail", d.detail(), Settings.DETAIL, warn),
                flag(c, "construction.survey-names", d.surveyNames(), warn),
                flag(c, "construction.sign-names", d.signNames(), warn));
    }

    /** features.mailroom as the owner set it (the loaded setting is also off when there is no alert channel). */
    public static boolean mailroomWanted(ConfigurationSection c) {
        return flag(c, "features.mailroom", Settings.defaults().features().mailroom(), m -> { });
    }

    /**
     * Why there is no working AI writer, or "" when there is one. command and exchange count as a writer: the plugin cannot
     * see whether that program or helper works.
     */
    public static String noWriterReason(Settings.WriterCfg w, String envKey) {
        return switch (w.mode()) {
            case "none" -> "writer.mode is none";
            case "anthropic" -> anthropicRefused() ? "the anthropic writer needs Paper 1.21.11 or newer"
                    : apiKeySet(w, envKey, null) ? "" : "no API key set";
            case "openai-compatible" -> w.openai().baseUrl().isEmpty() || w.openai().model().isEmpty() ? "no base-url or model set" : "";
            default -> "";
        };
    }

    /**
     * Whether the writer in use has an API key from any of its sources: its api-key-file, its environment variable
     * (envKey = ANTHROPIC_API_KEY, openAiEnvKey = MASTHEAD_AI_KEY; null if unset) or its api-key.
     */
    public static boolean apiKeySet(Settings.WriterCfg w, String envKey, String openAiEnvKey) {
        boolean openAi = w.mode().equals("openai-compatible");
        String env = openAi ? openAiEnvKey : envKey;
        return !(openAi ? w.openai().apiKeyFile() : w.apiKeyFile()).isBlank() || (env != null && !env.isBlank())
                || !(openAi ? w.openai().apiKey() : w.apiKey()).isBlank();
    }

    /**
     * The words after "API key: " in /masthead status share. An api-key-file that is named but cannot be read or is
     * empty is no key: the writer never falls back to another source then.
     */
    public static String apiKeyStatus(Settings.WriterCfg w, String envKey, String openAiEnvKey) {
        String file = w.mode().equals("openai-compatible") ? w.openai().apiKeyFile() : w.apiKeyFile();
        if (!file.isBlank()) {
            try {
                if (Files.readString(Path.of(file.strip())).replaceFirst("^\uFEFF", "").isBlank()) return "not set (api-key-file is empty)";
            } catch (IOException | RuntimeException e) {
                return "not set (api-key-file can't be read)";
            }
        }
        return apiKeySet(w, envKey, openAiEnvKey) ? "set" : "not set";
    }

    private static volatile Boolean refused;

    /**
     * T16: true when the server's Jackson is too old for the Anthropic SDK (Paper below 1.21.11), so anthropic mode writes the
     * plain edition. Checked once, by looking for the method the SDK needs, never by the version number.
     */
    public static boolean anthropicRefused() {
        Boolean r = refused;
        if (r == null) {
            try { r = jacksonTooOld(Class.forName("com.fasterxml.jackson.annotation.JsonProperty", false, SettingsLoader.class.getClassLoader())); }
            catch (Throwable e) { r = true; }   // no Jackson at all: the SDK could not run either
            refused = r;
        }
        return r;
    }

    /** The SDK calls JsonProperty.isRequired(), which older Jackson (on Paper before 1.21.11) does not have. */
    public static boolean jacksonTooOld(Class<?> jsonProperty) {
        try { jsonProperty.getMethod("isRequired"); return false; }
        catch (NoSuchMethodException e) { return true; }
    }

    /** The one console line when anthropic mode is refused (T16). */
    public static String refusedLine(String minecraftVersion) {
        return "The anthropic writer needs Paper 1.21.11 or newer (this server runs " + minecraftVersion + "); using the plain edition.";
    }

    /** The one console line when the built-in web server cannot get its port (T20). */
    public static String webBindLine(String bind, int port, String why) {
        return "The web page is off: port " + port + " (web.bind " + bind + ") is in use or not allowed (" + why + "). Set web.port in"
                + " config.yml to a free port, or web.mode to folder or off, then run /masthead reload. The book and join message still work.";
    }

    /** The writer actually in effect, for the start-up summary and /masthead status. */
    public static String writerLine(Settings.WriterCfg w, String envKey) {
        if (w.mode().equals("none")) return "Writer: none (plain edition)";
        if (w.mode().equals("anthropic") && anthropicRefused()) return "Writer: anthropic refused on this Paper version: plain edition";
        String why = noWriterReason(w, envKey);
        if (!why.isEmpty()) return "Writer: " + w.mode() + " (" + why + ": plain edition)";
        return "Writer: " + w.mode() + (w.mode().equals("openai-compatible") ? " (Beta)" : "");
    }

    /** The exact console line when the mailroom is off for want of an AI writer. */
    public static final String MAILROOM_NO_WRITER_LINE = "Mailroom is off: it needs an AI writer (writer.mode anthropic with an API key, openai-compatible with a base-url and model, command or exchange), because letters are printed only after the AI editor reads them.";

    /** The exact console line when the mailroom is off for want of an alert channel. */
    public static final String MAILROOM_OFF_LINE = "Mailroom is off: it needs an alert channel (alerts.ntfy-url or a private alerts.discord-webhook) so worried letters reach a person.";

    // ------------------------------------------------------------ a config.yml with a mistake in it (M1)
    /**
     * "" when config.yml can be read. Otherwise (a mistake, no file, or a file with no
     * setting in it) the one plain line to show; the caller then loads nothing and keeps the settings it has. Bukkit's
     * own loader hands back an empty config for a file with a mistake, so every setting would fall to its default
     * (hidden players shown, the web page public). The line gives the line number only, never the file's text: the line
     * with the mistake may hold a key or an address.
     */
    public static String brokenLine(Path config) {
        // not there (deleted while the server runs; at start the plugin writes the shipped file first): the same fall to the defaults
        if (!Files.exists(config)) return "config.yml is missing. Nothing was changed. Put it back and run /masthead reload, or restart the server for a fresh one.";
        String what;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.load(config.toFile());
            if (!y.getKeys(false).isEmpty()) return "";
            // no setting at all (an upload that failed, a full disk): loading it would be the same fall to the defaults
            return "config.yml is empty. Nothing was changed. Put your settings back and run /masthead reload, or delete the file and restart the server for a fresh one.";
        } catch (InvalidConfigurationException e) {
            String msg = String.valueOf(e.getMessage()), line = "";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("line (\\d+), column").matcher(msg);
            while (m.find()) line = m.group(1);   // the last one: where the reader stopped
            what = "has a mistake" + (line.isEmpty() ? "" : " on or above line " + line) + (msg.contains("(TAB)") ? " (a TAB: indent with spaces)" : "");
        } catch (IOException | RuntimeException e) {
            what = "could not be read (" + e.getClass().getSimpleName() + ")";
        }
        return "config.yml " + what + ". Nothing was changed. Fix it and run /masthead reload.";
    }

    // ------------------------------------------------------------ config upgrades (T17)
    /** The config.yml layout this jar ships. Raise it when a release needs more than "add the missing keys". */
    public static final int CONFIG_VERSION = 1;

    /** What an upgrade did: the keys added (top-most only) and the backup of the old file. */
    public record Upgrade(List<String> added, Path backup) {}

    /**
     * Adds every key the shipped config.yml has and the owner's file lacks, with its comments, and sets config-version. Values
     * the owner set are never changed. Before writing, the old file is copied next to it at mode 600 (it holds the API key and
     * alert addresses). Returns null when nothing was missing. Broken YAML is left alone (the loader reports it).
     */
    public static Upgrade upgradeFile(Path config, String shipped) throws IOException, InvalidConfigurationException {
        String old = Files.readString(config, StandardCharsets.UTF_8);
        YamlConfiguration user = new YamlConfiguration(), def = new YamlConfiguration();
        user.loadFromString(old);
        def.loadFromString(shipped);
        List<String> added = addMissing(user, def);
        if (added.isEmpty()) return null;
        Path backup = writePrivate(config.resolveSibling("config.yml." + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".bak"), old);
        try { replace(config, user.saveToString()); }
        catch (IOException | RuntimeException e) { Files.deleteIfExists(backup); throw e; }   // a read-only config.yml would gain a copy of the API key at every start
        return new Upgrade(added, backup);
    }

    /**
     * Writes the new text to config.yml.new (the old file's mode, or tighter under a strict umask; never looser) and renames
     * it over config.yml in one step, so a full disk or a stop half-way leaves the old config.yml whole.
     */
    static void replace(Path config, String text) throws IOException {
        Path tmp = config.resolveSibling(config.getFileName() + ".new");
        Files.deleteIfExists(tmp);
        try {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(Files.getPosixFilePermissions(config)));
        } catch (UnsupportedOperationException e) {
            Files.createFile(tmp);
        }
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, config, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** The in-memory part of {@link #upgradeFile}: returns the top-most keys it added (config-version counts as one). */
    public static List<String> addMissing(YamlConfiguration user, YamlConfiguration def) {
        List<String> added = new ArrayList<>();
        for (String k : def.getKeys(true)) {
            if (k.equals("config-version") || user.contains(k, true) || blocked(user, k)) continue;
            if (def.isConfigurationSection(k)) user.createSection(k);
            else if (k.equals("tone.audience"))   // the lowest audience that keeps the owner's paper as it is, not the shipped all-ages
                user.set(k, Settings.audienceFor(user.getString("tone.spice", "medium"), user.getString("tone.language", "clean"), user.getString("tone.slang", "light")));
            else user.set(k, def.get(k));
            user.setComments(k, def.getComments(k));
            user.setInlineComments(k, def.getInlineComments(k));
            if (added.stream().noneMatch(a -> k.startsWith(a + "."))) added.add(k);
        }
        if (user.getInt("config-version", 0) < CONFIG_VERSION) {
            boolean had = user.contains("config-version", true);
            user.set("config-version", CONFIG_VERSION);
            if (!had) user.setComments("config-version", def.getComments("config-version"));
            added.add("config-version");
        }
        return added;
    }

    /** A parent of k is a plain value in the owner's file: leave that alone. */
    private static boolean blocked(YamlConfiguration user, String k) {
        for (int i = k.indexOf('.'); i > 0; i = k.indexOf('.', i + 1)) {
            String p = k.substring(0, i);
            if (user.contains(p, true) && !user.isConfigurationSection(p)) return true;
        }
        return false;
    }

    /** Writes a new file readable only by the server's user (best effort where the disk has no file modes). */
    static Path writePrivate(Path f, String text) throws IOException {
        try { Files.createFile(f, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
        catch (UnsupportedOperationException e) { Files.createFile(f); }
        Files.writeString(f, text, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        return f;
    }

    // ------------------------------------------------------------ /masthead status share (T26)
    /** A status line made safe to post: no web or alert addresses, no API key, no values quoted from config.yml. */
    public static String shareSafe(String line, Settings s) {
        for (String secret : List.of(s.web().publicUrl(), s.alerts().ntfyUrl(), s.alerts().discordWebhook(), s.writer().apiKey(),
                s.writer().openai().apiKey()))
            if (!secret.isBlank()) line = line.replace(secret, "<hidden>");
        return line.replaceAll("https?://\\S+", "<address>")
                .replaceAll("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b", "<address>")
                .replaceAll("'[^']*'", "'<hidden>'");
    }

    private static String text(ConfigurationSection c, String k, String def, int max, Consumer<String> warn) {
        String v = c.getString(k, def);
        if (v == null || v.isBlank()) return def;
        v = v.replaceAll("\\p{Cntrl}", " ").strip();
        if (v.length() > max) { warn.accept(k + ": longer than " + max + " characters; cut"); v = v.substring(0, max); }
        return v;
    }

    private static String word(ConfigurationSection c, String k, String def, Consumer<String> warn) {
        String v = c.getString(k, def).trim().toLowerCase(Locale.ROOT);
        if (!v.matches("[a-z_ ]{2,30}")) { warn.accept(k + ": use a plain animal name like 'chicken'"); return def; }
        return v;
    }

    private static boolean flag(ConfigurationSection c, String k, boolean def, Consumer<String> warn) {
        if (c.get(k) instanceof String q && q.trim().matches("(?i)true|false")) return Boolean.parseBoolean(q.trim());   // "true" in quotes
        if (c.contains(k) && !c.isBoolean(k)) warn.accept(k + ": '" + c.get(k) + "' isn't true or false; using " + def);
        return c.getBoolean(k, def);
    }

    private static String choice(ConfigurationSection c, String k, String def, List<String> ok, Consumer<String> warn) {
        // YAML reads a bare off/on as false/true
        if (c.get(k) instanceof Boolean b && ok.contains(b ? "on" : "off")) return b ? "on" : "off";
        String v = c.getString(k, def).trim().toLowerCase(Locale.ROOT);
        if (ok.contains(v)) return v;
        warn.accept(k + ": '" + v + "' isn't one of " + ok + "; using " + def);
        return def;
    }

    /** paper.language: en-US or en-GB, in any common spelling; anything else is en-US. */
    static String paperLanguage(String raw, Consumer<String> warn) {
        String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        if (v.isEmpty() || List.of("en-us", "us", "usa", "american").contains(v)) return "en-US";
        if (List.of("en-gb", "en-uk", "gb", "uk", "british").contains(v)) return "en-GB";
        warn.accept("paper.language: '" + raw.strip() + "' isn't en-US or en-GB; using en-US");
        return "en-US";
    }

    private static int range(ConfigurationSection c, String k, int def, int lo, int hi, Consumer<String> warn) {
        int v = c.getInt(k, def);
        if (c.contains(k) && !(c.get(k) instanceof Number)) {   // getInt alone gives the default for these, without a word
            try { v = Integer.parseInt(String.valueOf(c.get(k)).trim()); }   // "8150" in quotes
            catch (NumberFormatException e) { warn.accept(k + ": '" + c.get(k) + "' is not a number; using " + def); return def; }
        }
        if (v < lo || v > hi) { warn.accept(k + ": must be " + lo + "-" + hi + "; using " + def); return def; }
        return v;
    }

    private static double money(ConfigurationSection c, String k, double def, Consumer<String> warn) {
        double v = c.getDouble(k, def);
        if (c.contains(k) && !(c.get(k) instanceof Number)) {
            try { v = Double.parseDouble(String.valueOf(c.get(k)).trim()); }
            catch (NumberFormatException e) { warn.accept(k + ": '" + c.get(k) + "' is not a number; using " + def); return def; }
        }
        if (!(v >= 0 && v <= 100_000)) { warn.accept(k + ": must be 0 or more (US dollars); using " + def); return def; }
        return v;
    }

    /** A config section as plain maps and lists, for Gson. */
    private static Object plain(Object v) {
        if (v instanceof ConfigurationSection s) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            for (String k : s.getKeys(false)) m.put(k, plain(s.get(k)));
            return m;
        }
        if (v instanceof List<?> l) return l.stream().map(SettingsLoader::plain).toList();
        return v;
    }

    /** A list of strings. One value written without brackets ("bits: obituaries") is a list of one, never silently nothing. */
    private static List<String> strings(ConfigurationSection c, String k) {
        return c.get(k) instanceof String one && !one.isBlank() ? List.of(one) : c.getStringList(k);
    }

    /** A list of anything. One value written without brackets ("remove: lowkey", "add: {term: ...}") is a list of one. */
    private static List<?> list(ConfigurationSection c, String k) {
        Object v = c.get(k);
        return v instanceof List<?> l ? l : v instanceof ConfigurationSection m ? List.of(m.getValues(false)) : v == null ? List.of() : List.of(v);
    }

    private static List<String> lines(ConfigurationSection c, String k, int maxItems, int maxLen) {
        List<String> out = new ArrayList<>();
        for (String s : strings(c, k)) {
            s = s.replaceAll("\\p{Cntrl}", " ").strip();
            if (!s.isEmpty() && out.size() < maxItems) out.add(s.length() > maxLen ? s.substring(0, maxLen) : s);
        }
        return List.copyOf(out);
    }

    private static String modelId(String v, String def, Consumer<String> warn) {
        v = v == null ? "" : v.trim();
        if (v.matches("[a-z0-9][a-z0-9.\\-]{2,60}")) return v;
        warn.accept("writer.anthropic.model: '" + v + "' doesn't look like a model id; using " + def);
        return def;
    }
}
