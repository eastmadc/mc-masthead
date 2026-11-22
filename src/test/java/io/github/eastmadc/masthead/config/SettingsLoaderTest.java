package io.github.eastmadc.masthead.config;

import io.github.eastmadc.masthead.web.WebServer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.StringReader;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.ZoneId;
import java.util.logging.Logger;

/** Plain-main test: config upgrades (T17), the anthropic refusal check (T16), the port line (T20), status share (T26). */
public final class SettingsLoaderTest {
    static int failures;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static Settings load(String yaml) {
        return SettingsLoader.load(YamlConfiguration.loadConfiguration(new StringReader(yaml)), m -> { }, null);
    }

    static String mode(Path f) throws Exception { return PosixFilePermissions.toString(Files.getPosixFilePermissions(f)); }

    public static void main(String[] args) throws Exception {
        String shipped;
        try (var in = SettingsLoaderTest.class.getResourceAsStream("/config.yml")) { shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8); }

        // a bare off is YAML false: it still means "off"
        check("web.mode: off (unquoted) is off", load("web:\n  mode: off\n").web().mode().equals("off"));
        check("web.mode: \"off\" is off", load("web:\n  mode: \"off\"\n").web().mode().equals("off"));

        // construction.detail: three levels, anything else is standard with a warning
        check("detail: missing is standard", load("").construction().detail().equals("standard"));
        for (String d : java.util.List.of("basic", "standard", "full"))
            check("detail: " + d + " accepted", load("construction:\n  detail: " + d + "\n").construction().detail().equals(d));
        java.util.List<String> warned = new java.util.ArrayList<>();
        String badDetail = SettingsLoader.load(YamlConfiguration.loadConfiguration(new StringReader("construction:\n  detail: everything\n")), warned::add, null)
                .construction().detail();
        check("detail: a bad value is standard, with a warning", badDetail.equals("standard") && warned.stream().anyMatch(w -> w.startsWith("construction.detail:")));
        check("detail: the shipped file sets standard", shipped.contains("  detail: standard\n"));
        // construction.survey-names: off unless the owner turns it on
        check("survey-names: missing is off", !load("").construction().surveyNames());
        check("survey-names: true is on", load("construction:\n  survey-names: true\n").construction().surveyNames());
        warned.clear();
        check("survey-names: a bad value is off, with a warning", !SettingsLoader.load(YamlConfiguration.loadConfiguration(new StringReader("construction:\n  survey-names: maybe\n")),
                warned::add, null).construction().surveyNames() && warned.stream().anyMatch(w -> w.startsWith("construction.survey-names:")));
        check("survey-names: the shipped file sets false", shipped.contains("  survey-names: false\n"));
        check("sign-names: off unless turned on", !load("").construction().signNames() && shipped.contains("  sign-names: false\n")
                && load("construction:\n  sign-names: true\n").construction().signNames());

        // paper.language: en-US | en-GB in common spellings; anything else is en-US with a warning
        check("language: missing is en-US", load("").paper().language().equals("en-US"));
        for (String v : java.util.List.of("en-US", "en-us", "en_US", "US", "usa", "American"))
            check("language: " + v + " is en-US", load("paper:\n  language: " + v + "\n").paper().language().equals("en-US"));
        for (String v : java.util.List.of("en-GB", "en-gb", "en_GB", "uk", "UK", "gb", "en-UK", "British"))
            check("language: " + v + " is en-GB", load("paper:\n  language: " + v + "\n").paper().language().equals("en-GB"));
        warned.clear();
        check("language: a bad value is en-US, with a warning", SettingsLoader.load(YamlConfiguration.loadConfiguration(new StringReader("paper:\n  language: fr-FR\n")),
                warned::add, null).paper().language().equals("en-US") && warned.contains("paper.language: 'fr-FR' isn't en-US or en-GB; using en-US"));
        check("language: tone.language (swearing) is untouched", load("paper:\n  language: en-GB\ntone:\n  audience: teens\n  language: pg13\n").tone().language().equals("pg13"));
        check("language: the shipped file sets en-US", shipped.contains("  language: en-US\n"));
        YamlConfiguration oldCfg = new YamlConfiguration(), shippedCfg = new YamlConfiguration();
        oldCfg.loadFromString("paper:\n  name: Old\n");
        shippedCfg.loadFromString(shipped);
        SettingsLoader.addMissing(oldCfg, shippedCfg);
        check("language: an old config gets en-US on upgrade", "en-US".equals(oldCfg.getString("paper.language")));

        // ---- T16: by method, not by version number
        check("T16: Jackson with JsonProperty.isRequired() is fine", !SettingsLoader.jacksonTooOld(com.fasterxml.jackson.annotation.JsonProperty.class));
        check("T16: a JsonProperty without isRequired() is too old", SettingsLoader.jacksonTooOld(Deprecated.class));
        check("T16: this classpath (Jackson 2.19) is not refused", !SettingsLoader.anthropicRefused());
        check("T16: the exact console line", SettingsLoader.refusedLine("1.21.8")
                .equals("The anthropic writer needs Paper 1.21.11 or newer (this server runs 1.21.8); using the plain edition."));

        // ---- T17: the shipped file says what an empty file says, so adding missing keys never changes behaviour
        check("T17: shipped config.yml has config-version " + SettingsLoader.CONFIG_VERSION,
                YamlConfiguration.loadConfiguration(new StringReader(shipped)).getInt("config-version") == SettingsLoader.CONFIG_VERSION);
        check("T17: shipped config.yml loads to the built-in defaults", load(shipped).equals(load("")));

        Path dir = Files.createTempDirectory("masthead-cfg-test");
        Path cfg = dir.resolve("config.yml");
        String old = "# my server\n\npaper:\n  name: \"The Daily Cluck\"   # ours\n  awards:\n    good: \"🏆 Big Play\"\nwriter:\n  mode: exchange\n"
                + "web: 5\nalerts:\n  ntfy-url: \"https://ntfy.sh/secret-topic\"\n";
        Files.writeString(cfg, old);
        Files.setPosixFilePermissions(cfg, PosixFilePermissions.fromString("rw-r-----"));
        Settings before = load(old);
        SettingsLoader.Upgrade u = SettingsLoader.upgradeFile(cfg, shipped);
        String now = Files.readString(cfg);
        Settings after = load(now);
        check("T17: something was added", u != null && u.added().contains("config-version") && u.added().contains("tone") && u.added().contains("paper.tagline"));
        check("T17: only top-most keys are listed (tone, not tone.spice)", u != null && !u.added().contains("tone.spice"));
        check("T17: a plain value where a section belongs is left alone (web: 5)", u != null && u.added().stream().noneMatch(k -> k.startsWith("web")) && now.contains("web: 5"));
        check("T17: the owner's values and settings are unchanged", before.equals(after));
        check("T17: the owner's comments stay", now.contains("# my server") && now.contains("# ours"));
        check("T17: added keys come with their comments", now.contains("# How hard the paper roasts") && now.contains("# Leave this alone"));
        check("T17: emoji kept as written", now.contains("🏆 Big Play"));
        check("T17: config-version set", YamlConfiguration.loadConfiguration(cfg.toFile()).getInt("config-version") == SettingsLoader.CONFIG_VERSION);
        // the new file gets the old mode minus what the umask takes away: 640 stays 640, or becomes 600 under umask 077
        check("T17: the new config is never more readable than the old one", mode(cfg).matches("rw-[r-]-----"));
        check("T17: backup holds the old file, mode 600", u != null && Files.readString(u.backup()).equals(old) && mode(u.backup()).equals("rw-------"));
        check("T17: a second load changes nothing", SettingsLoader.upgradeFile(cfg, shipped) == null && Files.readString(cfg).equals(now));
        try (var s = Files.list(dir)) { check("T17: exactly one backup", s.count() == 2); }

        // SO-2: a write that fails half-way leaves config.yml exactly as it was (here config.yml.new can't be made)
        Path d2 = Files.createTempDirectory("masthead-cfg-fail");
        Path c2 = d2.resolve("config.yml");
        Files.writeString(c2, old);
        Files.createDirectories(d2.resolve("config.yml.new").resolve("x"));
        boolean failed = false;
        try { SettingsLoader.upgradeFile(c2, shipped); } catch (java.io.IOException e) { failed = true; }
        check("SO-2: a failed upgrade write leaves config.yml byte-for-byte unchanged", failed && Files.readString(c2).equals(old));
        try (var s = Files.list(d2)) { check("SO-2: and leaves no backup (it holds the API key)", s.noneMatch(p -> p.toString().endsWith(".bak"))); }

        Path bad = dir.resolve("broken.yml");
        Files.writeString(bad, "paper:\n  name: [unclosed\n");
        boolean threw = false;
        try { SettingsLoader.upgradeFile(bad, shipped); } catch (InvalidConfigurationException e) { threw = true; }
        check("T17: broken YAML is left alone, no backup", threw && Files.readString(bad).equals("paper:\n  name: [unclosed\n"));
        try (var s = Files.list(dir)) { check("T17: still one backup", s.filter(p -> p.getFileName().toString().endsWith(".bak")).count() == 1); }

        Path deploy = Path.of("examples/config-teens-classic.yml");
        if (Files.isRegularFile(deploy)) {
            Path live = dir.resolve("live").resolve("config.yml");
            Files.createDirectories(live.getParent());
            Files.copy(deploy, live);
            Settings b = load(Files.readString(live));
            SettingsLoader.upgradeFile(live, shipped);
            check("T17: the example preset keeps every setting after the upgrade", b.equals(load(Files.readString(live))));
        }

        // ---- T20: the port line
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Files.createDirectories(dir.resolve("web"));
            WebServer w = new WebServer(dir.resolve("web"), "127.0.0.1", taken.getLocalPort(), null, ZoneId.of("UTC"), Logger.getLogger("t"));
            Exception e = null;
            try { w.start(); } catch (Exception x) { e = x; } finally { try { w.stop(); } catch (Exception ignored) { } }
            check("T20: a taken port is a BindException", e instanceof BindException);
            String line = SettingsLoader.webBindLine("127.0.0.1", taken.getLocalPort(), e == null ? "" : e.getMessage());
            check("T20: the line says what to change", line.startsWith("The web page is off: port " + taken.getLocalPort()) && line.contains("Set web.port")
                    && line.contains("/masthead reload") && line.contains("The book and join message still work."));
        }

        // ---- T26: status share
        Settings s = load("web:\n  public-url: \"http://play.example.net:8150/\"\nalerts:\n  discord-webhook: \"https://discord.com/api/webhooks/1/abc\"\nwriter:\n  anthropic:\n    api-key: sk-ant-xyz\n  openai-compatible:\n    api-key: sk-or-abc\n");
        String safe = SettingsLoader.shareSafe("fetch http://play.example.net:8150/today.json failed; push to https://discord.com/api/webhooks/1/abc; key sk-ant-xyz; or sk-or-abc;"
                + " 10.0.0.5 refused; owner.name: 'Steve_99' isn't a Minecraft name", s);
        check("T26: no addresses, key or quoted names: " + safe, !safe.contains("example") && !safe.contains("discord") && !safe.contains("sk-ant") && !safe.contains("sk-or")
                && !safe.contains("10.0.0.5") && !safe.contains("Steve") && safe.contains("owner.name: '<hidden>'"));

        // QC4: YAML reads an unquoted off as false; the paper still means slang off
        check("QC4: slang: off (unquoted) is off", load("tone:\n  audience: teens\n  slang: off\n").tone().slang().equals("off"));
        check("QC4: slang: on (unquoted) is light", load("tone:\n  audience: teens\n  slang: on\n").tone().slang().equals("light"));

        // a heavy-slang word is not in lexicon.yml and is removed all the same: no "nothing removed" line for it
        warned.clear();
        Settings heavy = SettingsLoader.load(YamlConfiguration.loadConfiguration(new StringReader(
                "tone:\n  voice: classic\n  audience: teens\n  slang: heavy\n  lexicon:\n    remove: [ngl, \"POV:\", zzznope]\n")), warned::add, null);
        java.util.List<String> gone = io.github.eastmadc.masthead.edition.StylePrompt.heavyRemoved(heavy);
        check("remove: a heavy-slang word is removed with no warning, a made-up word still warns: " + warned,
                gone.contains("ngl") && gone.contains("POV:") && warned.stream().filter(w -> w.contains("not a shipped word")).count() == 1
                        && warned.stream().anyMatch(w -> w.contains("'zzznope' is not a shipped word")));
        check("remove: POV with no colon takes POV: out", io.github.eastmadc.masthead.edition.StylePrompt.heavyRemoved(
                load("tone:\n  voice: classic\n  audience: teens\n  slang: heavy\n  lexicon:\n    remove: [POV]\n")).contains("POV:"));
        warned.clear();
        SettingsLoader.load(YamlConfiguration.loadConfiguration(new StringReader(
                "tone:\n  audience: teens\n  lexicon:\n    add:\n      - {term: \"no cap\", means: \"for real\", kind: slang, tier: teens}\n")), warned::add, null);
        check("add: a dated word is refused for a reason that is true in every voice: " + warned,
                warned.stream().anyMatch(w -> w.contains("dated-slang list")) && warned.stream().noneMatch(w -> w.contains("would refuse")));
        // one value written without brackets is a list of one, never silently nothing
        Settings one = load("tone:\n  audience: teens\n  bits: obituaries\n  lexicon:\n    remove: lowkey\n    add: {term: \"skrrt\", means: \"a fast getaway\", kind: slang, tier: teens}\n"
                + "privacy:\n  hide-players: Steve_Builder\n  never-mention: the old map\n");
        check("single value: tone.bits", one.tone().bits().equals(java.util.List.of("obituaries")));
        check("single value: tone.lexicon.remove", one.tone().lexiconRemove().equals(java.util.List.of("lowkey")));
        check("single value: tone.lexicon.add", one.tone().lexiconAdd().size() == 1 && one.tone().lexiconAdd().get(0).term().equals("skrrt"));
        check("single value: privacy.hide-players and never-mention", one.privacy().hidePlayers().equals(java.util.List.of("Steve_Builder"))
                && one.privacy().neverMention().equals(java.util.List.of("the old map")));
        warned.clear();
        Settings none = SettingsLoader.load(YamlConfiguration.loadConfiguration(new StringReader("tone:\n  audience: teens\n  bits: \"\"\n  lexicon:\n    remove:\n    add:\n")), warned::add, null);
        check("single value: an empty key is still an empty list, with no warning: " + warned, none.tone().lexiconRemove().isEmpty() && none.tone().lexiconAdd().isEmpty()
                && none.tone().bits().isEmpty() && warned.isEmpty());

        // ---- M1: a config.yml with a mistake is never loaded: the caller keeps the settings it has
        Path m1dir = Files.createTempDirectory("masthead-cfg-m1"), m1 = m1dir.resolve("config.yml");
        String m1good = "privacy:\n  hide-players: [Kai_Ore]\nwriter:\n  anthropic:\n    api-key: sk-ant-xyz\nweb:\n  bind: \"127.0.0.1\"\n  port: 35927\n";
        String m1tab = m1good.replace("    api-key:", "\tapi-key:");   // one TAB in front of line 5
        check("M1: a deleted file is not loaded either (it would be all defaults)", SettingsLoader.brokenLine(m1).startsWith("config.yml is missing. Nothing was changed."));
        Files.writeString(m1, m1good);
        Settings inUse = load(Files.readString(m1));
        check("M1: a good file is read", SettingsLoader.brokenLine(m1).isEmpty() && inUse.privacy().hidePlayers().equals(java.util.List.of("Kai_Ore"))
                && inUse.web().bind().equals("127.0.0.1") && inUse.web().port() == 35927);
        Files.writeString(m1, m1tab);
        String brokenTab = SettingsLoader.brokenLine(m1);
        check("M1: a TAB: one line with the line number and what to do: " + brokenTab,
                brokenTab.equals("config.yml has a mistake on or above line 5 (a TAB: indent with spaces). Nothing was changed. Fix it and run /masthead reload."));
        check("M1: the line never quotes the file (the broken line holds the API key)", !brokenTab.contains("sk-ant") && !brokenTab.contains("\n"));
        try (var files = Files.list(m1dir)) {
            check("M1: the owner's file is not rewritten and gets no backup", Files.readString(m1).equals(m1tab) && files.count() == 1);
        }
        Files.writeString(m1, "paper:\n  name: [unclosed\nweb:\n  port: 35927\n");
        check("M1: another mistake also gets a line number: " + SettingsLoader.brokenLine(m1),
                SettingsLoader.brokenLine(m1).matches("config\\.yml has a mistake on or above line \\d+\\. Nothing was changed\\. Fix it and run /masthead reload\\."));
        Files.writeString(m1, "- just\n- a list\n");
        check("M1: a file that is not a list of settings is a mistake too", SettingsLoader.brokenLine(m1).startsWith("config.yml has a mistake. Nothing was changed."));
        for (String blank : java.util.List.of("", "# only a comment\n\n")) {   // valid YAML with no setting in it would also load as all defaults
            Files.writeString(m1, blank);
            check("M1: an empty file is not loaded either: " + SettingsLoader.brokenLine(m1), SettingsLoader.brokenLine(m1).startsWith("config.yml is empty. Nothing was changed."));
        }
        Files.writeString(m1, m1good.replace("35927", "35928"));
        check("M1: the file fixed: read again", SettingsLoader.brokenLine(m1).isEmpty() && load(Files.readString(m1)).web().port() == 35928);
        // Masthead needs a server, so the three places that must hold are checked in its text: a broken file stops loadSettings
        // before anything is upgraded or loaded; a refused reload returns before the web server is touched; at server start
        // nothing is set up until loadSettings says yes.
        String mh = Files.readString(Path.of("src/main/java/io/github/eastmadc/masthead/Masthead.java"));
        String ls = mh.substring(mh.indexOf("private boolean loadSettings()"), mh.indexOf("private void upgradeConfig()"));
        check("M1: loadSettings stops at a broken file before upgradeConfig and reloadConfig",
                ls.contains("if (!configBroken.isEmpty()) return false;") && ls.indexOf("return false;") < ls.indexOf("upgradeConfig();")
                        && ls.indexOf("upgradeConfig();") < ls.indexOf("reloadConfig();") && ls.indexOf("SettingsLoader.brokenLine(") < ls.indexOf("return false;"));
        String rl = mh.substring(mh.indexOf("case \"reload\" ->"), mh.indexOf("case \"now\" ->"));
        check("M1: a refused reload returns before the web server is restarted",
                rl.indexOf("if (!loadSettings()) {") >= 0 && rl.indexOf("if (!loadSettings()) {") < rl.indexOf("return;") && rl.indexOf("return;") < rl.indexOf("job.start();")
                        && rl.contains("if (job == null) start();"));
        String en = mh.substring(mh.indexOf("public void onEnable()"), mh.indexOf("private void start()"));
        check("M1: at server start nothing runs on a broken file", en.contains("if (loadSettings()) start();") && !en.contains("job.start()") && !en.contains("new Recorder"));
        // what the owner sees, and the way back: the console line at start, /masthead answering while the paper is off (reload
        // must get through), the sender told on a refused reload, and status saying why
        String oc = mh.substring(mh.indexOf("public boolean onCommand("), mh.indexOf("private void start()"));
        String ad = mh.substring(mh.indexOf("private void admin("), mh.indexOf("case \"reload\" ->"));
        String su = mh.substring(mh.indexOf("private void status("), mh.indexOf("YamlConfiguration state()"));
        check("M1: a broken start says so on the console, and why Masthead is off", en.contains("else getLogger().severe(brokenLine());")
                && oc.contains("return configBroken + (job == null ? \" Masthead stays off until then.\" : \"\");"));
        check("M1: after a broken start /masthead still reaches its commands, and only reload gets past the mistake",
                oc.contains("if (command.getName().equals(\"masthead\")) admin(sender, args);")
                        && ad.contains("if (job == null && !sub.equals(\"reload\")) {") && ad.contains("sender.sendMessage(Component.text(brokenLine(), NamedTextColor.RED));"));
        check("M1: a refused reload tells the sender", rl.indexOf("sender.sendMessage(Component.text(brokenLine(), NamedTextColor.RED));") > rl.indexOf("if (!loadSettings()) {")
                && rl.indexOf("sender.sendMessage(Component.text(brokenLine(), NamedTextColor.RED));") < rl.indexOf("return;"));
        check("M1: status says why", su.contains("if (!configBroken.isEmpty()) sender.sendMessage(Component.text(configBroken, NamedTextColor.RED));"));
        check("M1: Bukkit's reloadConfig (empty config on a mistake) is called in one place only", mh.split("reloadConfig\\(\\)", -1).length == 2 && !mh.contains("getConfig().load"));

        // ---- C1: a number or true/false setting that is something else is reported; in quotes it is used
        warned.clear();
        Settings c1 = SettingsLoader.load(YamlConfiguration.loadConfiguration(new StringReader("tone:\n  audience: all-ages\nweb:\n  port: banana\nprivacy:\n  retention-days: soon\n"
                + "writer:\n  monthly-budget-usd: lots\nfeatures:\n  readership: nope\n  blotter: maybe\n")), warned::add, null);
        check("C1: a port that is not a number is the default, with a warning: " + warned, c1.web().port() == Settings.defaults().web().port()
                && warned.contains("web.port: 'banana' is not a number; using " + Settings.defaults().web().port()));
        check("C1: the same for every other number", warned.stream().anyMatch(w -> w.startsWith("privacy.retention-days: 'soon' is not a number"))
                && warned.stream().anyMatch(w -> w.startsWith("writer.monthly-budget-usd: 'lots' is not a number")));
        check("C1: a true/false setting that is something else is reported", warned.stream().anyMatch(w -> w.startsWith("features.readership: 'nope' isn't true or false"))
                && warned.stream().anyMatch(w -> w.startsWith("features.blotter: 'maybe' isn't true or false")) && warned.size() == 5);
        warned.clear();
        Settings c1q = SettingsLoader.load(YamlConfiguration.loadConfiguration(new StringReader("tone:\n  audience: all-ages\nweb:\n  port: \"35927\"\nprivacy:\n  retention-days: \" 45 \"\n"
                + "writer:\n  monthly-budget-usd: \"2.5\"\nfeatures:\n  readership: \"false\"\n  blotter: \"False\"\n  join-message: \"false\"\nowner:\n  hours-ok: \"false\"\n")), warned::add, null);
        check("C1: numbers in quotes are used, with no warning: " + warned, c1q.web().port() == 35927 && c1q.privacy().retentionDays() == 45
                && c1q.writer().monthlyBudgetUsd() == 2.5 && warned.isEmpty());
        check("C1: true/false in quotes is used", !c1q.features().readership() && !c1q.features().blotter() && !c1q.features().joinMessage() && !c1q.owner().hoursOk());
        warned.clear();
        check("C1: a number in quotes still has its limits", SettingsLoader.load(YamlConfiguration.loadConfiguration(new StringReader("tone:\n  audience: all-ages\nweb:\n  port: \"99999\"\n")),
                warned::add, null).web().port() == Settings.defaults().web().port() && warned.equals(java.util.List.of("web.port: must be 1-65535; using " + Settings.defaults().web().port())));
        check("C1: features.mailroom in quotes counts as the owner's wish", SettingsLoader.mailroomWanted(YamlConfiguration.loadConfiguration(new StringReader("features:\n  mailroom: \"true\"\n")))
                && !SettingsLoader.mailroomWanted(YamlConfiguration.loadConfiguration(new StringReader("features:\n  mailroom: \"false\"\n"))));

        // ---- C2: schedule.time without quotes: YAML hands over 17:30 as the number 1050
        warned.clear();
        for (String[] t : new String[][]{{"17:30", "17:30"}, {"6:30", "06:30"}, {"23:59", "23:59"}, {"05:30", "05:30"}, {"\"17:30\"", "17:30"}})
            check("C2: schedule.time: " + t[0] + " is " + t[1], SettingsLoader.load(YamlConfiguration.loadConfiguration(new StringReader("tone:\n  audience: all-ages\nschedule:\n  time: " + t[0] + "\n")),
                    warned::add, null).schedule().time().equals(java.time.LocalTime.parse(t[1])));
        check("C2: no warning for any of them: " + warned, warned.isEmpty());
        for (String t : java.util.List.of("25:99", "24:00", "-5", "\"25:99\""))
            check("C2: schedule.time: " + t + " is still refused", SettingsLoader.load(YamlConfiguration.loadConfiguration(new StringReader("tone:\n  audience: all-ages\nschedule:\n  time: " + t + "\n")),
                    warned::add, null).schedule().time().equals(java.time.LocalTime.parse("05:30")));
        check("C2: each with the HH:MM warning: " + warned, warned.size() == 4 && warned.stream().allMatch(w -> w.equals("schedule.time: use HH:MM, e.g. 05:30; using 05:30")));
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
