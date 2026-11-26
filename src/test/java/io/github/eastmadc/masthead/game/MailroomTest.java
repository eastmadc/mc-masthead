package io.github.eastmadc.masthead.game;

import io.github.eastmadc.masthead.config.Settings;
import io.github.eastmadc.masthead.notify.Concerns;
import io.github.eastmadc.masthead.notify.Push;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The mailroom without a server (review round 1): a worried letter reaches the owner and the player gets the
 * 988 line even when the letter is not stored (mailroom closed, cooldown, a failed check, a hidden player). Plain main().
 */
public final class MailroomTest {
    static int checks = 0;
    static final String HELP_START = "If you're going through something real";
    static java.time.LocalDate today;   // read once; the mailroom reads the clock itself, so the run must not cross midnight

    public static void main(String[] args) throws Exception {
        java.time.LocalDateTime start = java.time.LocalDateTime.now();
        if (start.getHour() == 23 && start.getMinute() == 59)   // the run takes about a second: in the day's last minute, wait for the next day
            Thread.sleep(java.time.Duration.between(start, start.toLocalDate().plusDays(1).atStartOfDay()).toMillis() + 100);
        today = java.time.LocalDate.now();
        Path root = Files.createTempDirectory("mh-mailroom");
        // a failed check: phone number, link, too long
        for (String text : List.of("i want to kill myself, my number is 555 123 4567",
                "nobody would care if i was gone, tell my mom @ her insta",
                "kms " + "x".repeat(210))) {
            World w = new World(root.resolve("c" + checks), true, true, List.of());
            List<String> said = w.send("Bob_1", text);
            check(said.get(0).startsWith("No ") || said.get(0).startsWith("That's over"), "refused: " + said);
            check(said.stream().anyMatch(m -> m.startsWith(HELP_START)), "988 line shown: " + said);
            check(w.pushes().equals(List.of(String.format(Concerns.NAMED, "Bob_1"))), "owner told once: " + w.push.bodies);
        }
        // cooldown: an ordinary letter, then a worried one 0 s later
        World w = new World(root.resolve("cool"), true, true, List.of());
        UUID u = UUID.randomUUID();
        w.send("Cal_2", u, "where is the best place to find diamonds");
        List<String> said = w.send("Cal_2", u, "i want to die honestly");
        check(said.get(0).startsWith("The mail carrier just left"), "cooldown: " + said);
        check(w.pushes().equals(List.of(String.format(Concerns.NAMED, "Cal_2"))), "worried letter in the cooldown reaches the owner: " + w.push.bodies);
        check(said.stream().anyMatch(m -> m.startsWith(HELP_START)), "988 line in the cooldown");
        // ... but repeating it straight away does not push again
        w.send("Cal_2", u, "i want to die honestly");
        check(w.pushes().size() == 1, "at most one unstored alert a minute per player");
        // the mailroom is closed (no AI writer, say) but an alert channel exists
        World closed = new World(root.resolve("closed"), false, true, List.of());
        said = closed.send("Dot_3", "i want to die");
        check(said.get(0).equals(Mailroom.CLOSED) && closed.pushes().size() == 1 && said.stream().anyMatch(m -> m.startsWith(HELP_START)),
                "closed mailroom: owner told, 988 line: " + said);
        // no alert channel at all: nobody to tell, but the player still sees the 988 line
        World none = new World(root.resolve("none"), false, false, List.of());
        said = none.send("Dot_3", "i want to die");
        check(none.push.bodies.isEmpty() && said.stream().anyMatch(m -> m.startsWith(HELP_START)), "no channel: 988 line only");
        // a hidden player: refused (their letters can never run), a worried one still reaches the owner
        World hid = new World(root.resolve("hidden"), true, true, List.of("quartz_pim"));
        said = hid.send("Quartz_Pim", "the farm is cool");
        check(said.get(0).equals(Mailroom.HIDDEN) && !Files.exists(hid.data.resolve("mailroom/" + today + ".jsonl")), "hidden player refused: " + said);
        said = hid.send("Quartz_Pim", UUID.randomUUID(), "i want to die");
        check(hid.pushes().size() == 1, "hidden player's worried letter still reaches the owner");
        // a worried letter that is stored is reported once (by store), not twice
        World ok = new World(root.resolve("stored"), true, true, List.of());
        said = ok.send("Eve_5", "nobody would care if i stopped playing");
        // review round 3 (CSP-R3-3): a worried letter is never printed, so the player is not told it may run tomorrow
        check(said.get(0).equals(Mailroom.PRIVATE) && said.stream().noneMatch(m -> m.contains("paper")), "stored worried letter: told it stays private: " + said);
        check(said.stream().anyMatch(m -> m.startsWith(HELP_START)), "stored worried letter: 988 line after it");
        check(ok.send("Fin_6", "the farm is cool").get(0).startsWith("Sealed"), "an ordinary letter still hears about tomorrow's paper");
        check(ok.pushes().size() == 1, "stored worried letter reported once: " + ok.push.bodies);
        // Tips are stored as before, but are addressed to the newspaper editor at both ends of the flow.
        World newsroom = new World(root.resolve("newsroom"), true, true, List.of());
        List<String> tipReceipt = new ArrayList<>();
        newsroom.room.submit("Tipster_7", UUID.randomUUID(), "tip", "the creeper ate my wheat farm", c -> tipReceipt.add(PlainTextComponentSerializer.plainText().serialize(c)));
        check(tipReceipt.size() == 1 && tipReceipt.get(0).contains("editor's mailbox") && tipReceipt.get(0).contains("without player names"), "tip receipt is editorial and anonymous: " + tipReceipt);
        String storedTip = Files.readString(newsroom.data.resolve("mailroom/" + today + ".jsonl"));
        check(storedTip.contains("\"kind\":\"tip\"") && storedTip.contains("the creeper ate my wheat farm") && !Files.exists(newsroom.data.resolve("blotter")), "existing tip format preserved in mailroom only");
        List<String> tipPrinted = new ArrayList<>();
        org.bukkit.entity.Player tipster = (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(MailroomTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Player.class}, (proxy, method, values) -> {
                    if (method.getName().equals("getName")) return "Tipster_7";
                    if (method.getName().equals("sendMessage")) { tipPrinted.add(PlainTextComponentSerializer.plainText().serialize((Component) values[0])); return null; }
                    throw new UnsupportedOperationException(method.getName());
                });
        List<Mailroom.Printed> published = List.of(new Mailroom.Printed("Tipster_7", "tip", today.toString()));
        newsroom.room.tellPrinted(tipster, published, "https://paper.example/");
        newsroom.room.tellPrinted(tipster, published, "https://paper.example/");
        check(tipPrinted.size() == 1 && tipPrinted.get(0).contains("editor printed your tip") && tipPrinted.get(0).contains("we didn't name you"), "printed-tip notification names the editor and is sent once: " + tipPrinted);
        check(java.util.stream.Stream.concat(tipReceipt.stream(), tipPrinted.stream()).noneMatch(s -> s.contains("Officers") || s.contains("Police Blotter")), "tip notifications never suggest police delivery");
        // no web address (web.public-url empty, or web.mode off): the notice still arrives, as plain text with nothing to click
        for (String url : List.of("", "https://paper.example/")) {
            List<Component> got = new ArrayList<>();
            org.bukkit.entity.Player writer = (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(MailroomTest.class.getClassLoader(),
                    new Class<?>[]{org.bukkit.entity.Player.class}, (proxy, method, values) -> {
                        if (method.getName().equals("getName")) return "Kai_Ore";
                        if (method.getName().equals("sendMessage")) { got.add((Component) values[0]); return null; }
                        throw new UnsupportedOperationException(method.getName());
                    });
            new World(root.resolve("answered" + url.length()), true, true, List.of()).room
                    .tellPrinted(writer, List.of(new Mailroom.Printed("Kai_Ore", "letter", "2031-03-04")), url);
            check(got.size() == 1 && PlainTextComponentSerializer.plainText().serialize(got.get(0)).contains("answered your letter"), "answered-letter notice sent: " + got);
            check(clicks(got).equals(url.isEmpty() ? List.of() : List.of(url)) && plain(got).contains("[read it]") == !url.isEmpty(),
                    "[read it] only with a web address ('" + url + "'): " + clicks(got));
        }
        statsLink();
        // notified.txt names the player behind a printed tip: private from the moment it is written
        Path toldFile = newsroom.data.resolve("notified.txt");
        check(Files.readString(toldFile).contains("Tipster_7 tip"), "notified.txt written: " + Files.readString(toldFile));
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
            check(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(toldFile)).equals("rw-------"), "notified.txt is private");
        // one player cannot fill the day's mailbag: every send adds a line to the file, so sends per player are limited
        World bag = new World(root.resolve("bag"), true, true, List.of());
        for (int i = 1; i <= Mailroom.PLAYER_CAP; i++)
            check(bag.send("Sol_Dig", "the iron farm is finished, come and see it " + i).get(0).startsWith("Sealed"), "send " + i + " of the day is taken");
        check(bag.send("Sol_Dig", "one more thing about the farm").get(0).startsWith("That's enough mail from you"), "the next send is refused");
        Path bagFile = bag.data.resolve("mailroom/" + today + ".jsonl");
        check(Files.readAllLines(bagFile).size() == Mailroom.PLAYER_CAP, "the refused letter is not stored");
        check(bag.send("Kai_Ore", "who built the bridge at spawn").get(0).startsWith("Sealed"), "another player can still write");
        World bagAgain = new World(root.resolve("bag"), true, true, List.of());   // after a restart the count comes from the file
        check(bagAgain.send("Sol_Dig", "one more thing about the farm").get(0).startsWith("That's enough mail from you"), "still refused after a restart");
        check(bagAgain.send("Steve_Builder", "the nether hub needs signs").get(0).startsWith("Sealed"), "and others still can");
        bagAgain.send("Sol_Dig", "i want to die");   // a worried letter over the limit is not stored, but the owner still hears
        check(bagAgain.pushes().equals(List.of(String.format(Concerns.NAMED, "Sol_Dig"))), "a worried letter over the limit still reaches the owner: " + bagAgain.pushes());
        // the next day: yesterday's count is forgotten (today's file is now yesterday's, and the mailroom's day is behind)
        Files.move(bagFile, bagFile.resolveSibling(today.minusDays(1) + ".jsonl"));
        var countDay = Mailroom.class.getDeclaredField("countDay");
        countDay.setAccessible(true);
        countDay.set(bag.room, today.minusDays(1).toString());
        check(bag.send("Sol_Dig", "the farm has a roof now").get(0).startsWith("Sealed"), "a new day: the player can write again");
        // review round 2 (SEC-R2-1): one player cannot flood the owner's phone. First one at once, then at most one a
        // minute and 3 a day, stored and not-stored letters counted together; the rest show in /masthead status.
        World flood = new World(root.resolve("flood"), true, true, List.of());
        UUID t = UUID.randomUUID();
        long t0 = System.currentTimeMillis();
        said = flood.send("Troll_9", t, "kms lol");                       // stored, pushed
        check(said.get(0).equals(Mailroom.PRIVATE) && flood.pushes().size() == 1, "first worried letter pushed at once: " + flood.push.bodies);
        flood.send("Troll_9", t, "kms lol");                               // cooldown, not stored, within the minute: held
        check(flood.pushes().size() == 1, "a second one within the minute is held: " + flood.push.bodies);
        for (int i = 1; i <= 20; i++) flood.room.alertAllowed("Troll_9", t, t0 + i * 61_000L);   // 20 more, a minute apart
        check(flood.room.heldAlerts().equals(java.util.Map.of("Troll_9", 19)), "2 more allowed, the rest held and counted: " + flood.room.heldAlerts());
        flood.send("Troll_9", t, "i want to die");                         // over the limit: no push, still the 988 line
        check(flood.pushes().size() == 1, "over the limit: no more pushes for this player today");
        // the limit is per player: someone else's first worried letter still goes at once
        said = flood.send("Amy_10", "i want to die");
        check(flood.pushes().size() == 2 && flood.pushes().get(1).equals(String.format(Concerns.NAMED, "Amy_10")), "another player's first one still pushed");
        check(said.stream().anyMatch(m -> m.startsWith(HELP_START)), "988 line shown");
        // review round 2 (r2-csp-1): a command that carries a letter is kept out of the server log
        java.util.Set<String> letter = java.util.Set.of("letter", "mail", "editor"), news = java.util.Set.of("news", "daily", "newspaper", "post");
        for (String line : List.of("Kid_1 issued server command: /mail i want to die", "Kid_1 issued server command: /letter tip Bob griefed me",
                "Kid_1 issued server command: /masthead:letter hello there", "Kid_1 issued server command: /LETTER hello",
                "Kid_1 issued server command: /editor  hi", "Kid_1 issued server command: /news write i want to die",
                "Kid_1 issued server command: /post letter hello", "Kid_1 issued server command: /masthead:news WRITE hi"))
            check(LetterLogFilter.hides(line, letter, news), "hidden from the log: " + line);
        for (String line : List.of("Kid_1 issued server command: /letter", "Kid_1 issued server command: /mail ",
                "Kid_1 issued server command: /news", "Kid_1 issued server command: /news qr", "Kid_1 issued server command: /news write",
                "Kid_1 issued server command: /gamemode creative", "Kid_1 issued server command: /letters hi", "Kid_1 joined the game"))
            check(!LetterLogFilter.hides(line, letter, news), "still logged: " + line);
        LetterLogFilter f = new LetterLogFilter(letter, news);
        var deny = org.apache.logging.log4j.core.impl.Log4jLogEvent.newBuilder().setLoggerName("net.minecraft.server.network.ServerGamePacketListenerImpl")
                .setLevel(org.apache.logging.log4j.Level.INFO)
                .setMessage(new org.apache.logging.log4j.message.ParameterizedMessage("{} issued server command: {}", "Kid_1", "/mail i want to die")).build();
        var keep = org.apache.logging.log4j.core.impl.Log4jLogEvent.newBuilder().setLevel(org.apache.logging.log4j.Level.INFO)
                .setMessage(new org.apache.logging.log4j.message.ParameterizedMessage("{} issued server command: {}", "Kid_1", "/letter")).build();
        check(f.filter(deny) == org.apache.logging.log4j.core.Filter.Result.DENY && f.filter(keep) == org.apache.logging.log4j.core.Filter.Result.NEUTRAL,
                "the log4j filter drops the line with the words, keeps a bare /letter");
        // CC-11: the letter is on disk before the player is told it was mailed, even if no background task ever runs
        {
            Path d2 = Files.createDirectories(Files.createTempDirectory("mh-mail-sync"));
            Settings dd = Settings.defaults();
            Settings s2 = new Settings(dd.paper(), dd.tone(), dd.owner(), dd.privacy(), new Settings.Schedule(LocalTime.of(5, 30), "UTC"), dd.writer(), dd.web(),
                    new Settings.Features(true, true, false, true, 3, ""), new Settings.Alerts("https://ntfy.example/topic", "", false));
            Logger lg = Logger.getAnonymousLogger();
            lg.setUseParentHandlers(false);
            Mailroom never = new Mailroom(d2.toFile(), lg, r -> { }, () -> s2, new Concerns(d2, lg, a -> null));   // background tasks never run
            List<String> told = new ArrayList<>();
            never.submit("Kid_9", UUID.randomUUID(), "letter", "the creeper ate my wheat farm again", (Component c) -> told.add(PlainTextComponentSerializer.plainText().serialize(c)));
            Path day = d2.resolve("mailroom").resolve(today + ".jsonl");
            check(told.get(0).startsWith("Sealed") && Files.isRegularFile(day) && Files.readString(day).contains("ate my wheat farm"), "letter written at once: " + told);
        }
        // /news write reaches command() without plugin.yml's /letter gate: no masthead.write, no letter
        {
            World gate = new World(root.resolve("gate"), true, true, List.of());
            List<String> told = new ArrayList<>();
            org.bukkit.entity.Player noPerm = (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(MailroomTest.class.getClassLoader(),
                    new Class<?>[]{org.bukkit.entity.Player.class}, (proxy, method, values) -> {
                        if (method.getName().equals("hasPermission")) return false;
                        if (method.getName().equals("sendMessage")) { told.add(PlainTextComponentSerializer.plainText().serialize((Component) values[0])); return null; }
                        throw new UnsupportedOperationException(method.getName());
                    });
            gate.room.command(noPerm, new String[]{"the", "farm", "is", "cool"});
            gate.room.command(noPerm, new String[0]);
            check(told.equals(List.of(Mailroom.NO_WRITE, Mailroom.NO_WRITE)) && !Files.exists(gate.data.resolve("mailroom/" + today + ".jsonl")), "no masthead.write: refused, nothing stored: " + told);
            // the [mail the editor] button is only offered to players who may write, and only while the mailroom is open
            org.bukkit.entity.Player writer = (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(MailroomTest.class.getClassLoader(),
                    new Class<?>[]{org.bukkit.entity.Player.class}, (proxy, method, values) -> {
                        if (method.getName().equals("hasPermission")) return "masthead.write".equals(values[0]);
                        if (method.getName().equals("getName")) return "Quartz_Pim";
                        if (method.getName().equals("getUniqueId")) return new UUID(0, 4);
                        if (method.getName().equals("sendMessage")) { told.add(PlainTextComponentSerializer.plainText().serialize((Component) values[0])); return null; }
                        throw new UnsupportedOperationException(method.getName());
                    });
            check(!gate.room.offeredTo(noPerm) && gate.room.offeredTo(writer), "button offered only with masthead.write");
            check(!new World(root.resolve("gate-closed"), false, true, List.of()).room.offeredTo(writer), "no button while the mailroom is closed");
            check(told.size() == 2, "offeredTo tells the player nothing: " + told);
            // a hidden player keeps the button and the form: a worried letter typed there still reaches the owner
            check(new World(root.resolve("gate-hidden"), true, true, List.of("quartz_pim")).room.offeredTo(writer), "a hidden player still gets the button");
            // G4: /letter refuses before the form opens for a player who mailed less than a minute ago (the proxy cannot show a form)
            told.clear();
            gate.send("Quartz_Pim", new UUID(0, 4), "where is the best place to find diamonds");
            gate.room.command(writer, new String[0]);
            check(told.equals(List.of("The mail carrier just left. Try again in a minute.")), "cooldown: told before the form opens: " + told);
        }
        System.out.println("MailroomTest PASS (" + checks + " checks)");
    }

    /** /stats: the Player Cards link is only there when the paper has a web address. */
    static void statsLink() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");   // an InGame without a server: statLines only reads publicUrl
        var f = unsafe.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        InGame g = (InGame) unsafe.getMethod("allocateInstance", Class.class).invoke(f.get(null), InGame.class);
        InGame.Stats s = new InGame.Stats("Steve_Builder", true, 1.5, 1, 2, 0, 10, 0, 0, 5, 1, 0, 0, 0, 0, 0, 0, 0, 0, java.util.Map.of());
        g.publicUrl = "";
        check(clicks(g.statLines(s, true)).isEmpty() && !plain(g.statLines(s, true)).contains("Player Cards"), "/stats with no web address: no link: " + clicks(g.statLines(s, true)));
        check(plain(g.statLines(s, true)).contains("Blocks mined 10"), "/stats with no web address: the numbers are still there");
        g.publicUrl = "http://paper.example:8150/";
        check(clicks(g.statLines(s, true)).equals(List.of("http://paper.example:8150/players.html#Steve_Builder")), "/stats with a web address: " + clicks(g.statLines(s, true)));
        // typed command words on a server whose language is Turkish: "PREVIEW" must not become "prevıew"
        java.util.Locale was = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"));
        try {
            var cmd = new org.bukkit.command.Command("masthead") {
                @Override public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) { return true; }
            };
            check(g.onTabComplete(null, cmd, "masthead", new String[] {"PREVI"}).equals(List.of("preview")), "tab complete with a Turkish default language");
        } finally {
            java.util.Locale.setDefault(was);
        }
        // numbers on a server whose language is German: still "7,654" and "62.5 km", never "7.654" and "62,5 km"
        java.util.Locale.setDefault(java.util.Locale.GERMANY);
        try {
            String big = plain(g.statLines(new InGame.Stats("Steve_Builder", true, 7654.5, 1, 2, 0, 7654, 0, 0, 5, 62.5, 7654, 0, 0, 0, 0, 0, 0, 0, java.util.Map.of()), true));
            check(big.contains("Played 7,654.5 h") && big.contains("Blocks mined 7,654") && big.contains("On foot 62.5 km") && big.contains("Horse 7,654 km"),
                    "/stats numbers with a German default language: " + big);
        } finally {
            java.util.Locale.setDefault(was);
        }
        // M6: no web address -> /news qr gives no (blank) map and is not offered in tab completion
        List<String> told = new ArrayList<>();
        org.bukkit.entity.Player kai = (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(MailroomTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Player.class}, (proxy, method, values) -> {
                    if (method.getName().equals("getName")) return "Kai_Ore";
                    if (method.getName().equals("getUniqueId")) return new UUID(0, 7);
                    if (method.getName().equals("hasPermission")) return true;
                    if (method.getName().equals("sendMessage")) { told.add(PlainTextComponentSerializer.plainText().serialize((Component) values[0])); return null; }
                    throw new UnsupportedOperationException(method.getName());
                });
        var news = new org.bukkit.command.Command("news") {
            @Override public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) { return true; }
        };
        g.publicUrl = "";
        g.onCommand(kai, news, "news", new String[] {"qr"});
        check(told.equals(List.of("This server has no web page address yet.")), "/news qr with no web address: " + told);
        check(g.onTabComplete(kai, news, "news", new String[] {""}).equals(List.of("paper", "write", "build")), "no web address: qr is not offered");
        g.publicUrl = "http://paper.example:8150/";
        check(g.onTabComplete(kai, news, "news", new String[] {""}).equals(List.of("qr", "paper", "write", "build")), "with a web address: qr is offered");
        // G1: /news qr and /news paper wait separately, and the refusal says how long
        var cd = InGame.class.getDeclaredField("cooldown");
        cd.setAccessible(true);
        cd.set(g, new java.util.HashMap<>());   // no constructor ran, so the field is still empty
        told.clear();
        check(!g.onCooldown(kai, "/news qr", 10_000) && !g.onCooldown(kai, "/news paper", 30_000), "/news paper right after /news qr is not refused: " + told);
        check(g.onCooldown(kai, "/news paper", 30_000) && told.size() == 1 && told.get(0).matches("Slow down\\. Try /news paper again in (29|30) seconds\\."),
                "a second /news paper hears the real wait: " + told);
        // G7: a second /stats inside its 3 seconds gets the "Slow down" line, not silence
        var stats = new org.bukkit.command.Command("stats") {
            @Override public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) { return true; }
        };
        told.clear();
        g.onCommand(kai, stats, "stats", new String[] {"no"});
        g.onCommand(kai, stats, "stats", new String[] {"no"});
        check(told.size() == 2 && told.get(0).equals("That isn't a Minecraft name.") && told.get(1).startsWith("Slow down. Try /stats again in "), "/stats twice in a row: " + told);
        // G2: the book has no heading with nothing under it (the plain paper's Highlights is a table)
        String book = plain(g.pages(new InGame.Edition("2031-03-04", "Daily edition", "The Daily Block", null, null, List.of(
                new InGame.Section("Highlights", List.of()), new InGame.Section("Kill feed", List.of("- Kai_Ore fell")), new InGame.Section("", List.of())), List.of())));
        check(!book.contains("Highlights") && book.contains("Kill feed\n- Kai_Ore fell"), "book skips a section with no text: " + book);
        // the book: pages are measured in pixels, never past the bottom, and the closing note stays whole after an empty line
        String para = "Kai_Ore dug up 12 blocks of clay before lunch and called it a career. The hens held a short meeting about it and voted to stay out of the river. ";
        List<Component> bookPages = g.pages(new InGame.Edition("2031-03-04", "Daily edition", "The Daily Block", null, null, List.of(
                new InGame.Section("Front page", List.of(para.repeat(4).strip(), para.repeat(3).strip())), new InGame.Section("Weather", List.of(para.repeat(5).strip())),
                new InGame.Section("", List.of("Written by an AI from this server's game stats. Numbers are checked; jokes are jokes."))), List.of()));
        List<String> pageText = bookPages.stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
        check(pageText.stream().allMatch(x -> InGame.lines(x.endsWith("\n") ? x.substring(0, x.length() - 1) : x, false) <= 14), "book: no page is longer than 14 lines: " + pageText);
        check(pageText.size() <= 10 && pageText.subList(1, pageText.size() - 1).stream().allMatch(x -> InGame.lines(x, false) >= 10), "book: the pages are filled (" + pageText.size() + " pages)");
        check(pageText.stream().filter(x -> x.contains("Written by an AI")).allMatch(x -> x.contains("jokes are jokes.") && (x.startsWith("Written") || x.contains("\n\nWritten"))),
                "book: the closing note is whole on one page, after an empty line: " + pageText.get(pageText.size() - 1));
        check(InGame.px("Written by an AI from", false) == 107 && InGame.px("Written by an AI from this", false) > InGame.BOOK_W, "book: the widths match a line break seen in the game");
        check(InGame.px("()*{}", false) == 25, "book: round and curly brackets and the star are 5 pixels wide, not 4");
        check(pageText.subList(1, pageText.size() - 1).stream().allMatch(x -> x.strip().endsWith(".")), "book: a page ends on a full sentence where it can: " + pageText);
        List<InGame.Section> fullPages = new ArrayList<>(java.util.Collections.nCopies(3, new InGame.Section("", java.util.Collections.nCopies(20, String.join(" ", java.util.Collections.nCopies(13, "W".repeat(19)))))));
        fullPages.add(new InGame.Section("", List.of(para.repeat(4).strip())));
        check(g.pages(new InGame.Edition("2031-03-04", "Daily edition", "The Daily Block", null, null, fullPages, List.of())).size() == 50, "book: never more than 50 pages");
        // a full book still ends with the closing note (it was cut off with the text)
        fullPages.add(new InGame.Section("", List.of(io.github.eastmadc.masthead.edition.Pages.AI_FOOTER)));
        List<Component> noted = g.pages(new InGame.Edition("2031-03-04", "Daily edition", "The Daily Block", null, null, fullPages, List.of()));
        check(noted.size() == 50 && PlainTextComponentSerializer.plainText().serialize(noted.get(49)).startsWith("Written by an AI"), "book: a full book still ends with the closing note");
        // the book at every offset: a list item's "Label:" never ends a page or splits over two, and no paragraph leaves one or two words for the next page
        List<String> labels = List.of("- The Shiny Spoon Award:", "- Mud Pies:", "- The Long Ladder Award:");
        for (int n = 1; n <= 60; n++) {
            List<String> paras = new ArrayList<>(List.of("hen ".repeat(n) + "end"));
            for (String l : labels) paras.add(l + " Kai_Ore crafted 304 blue rugs and the coop has opinions about where every one of them should go");
            paras.add("Sol_Dig dug a long tunnel under the old barn while the hens kept count of every block and the rooster checked it against his standards");
            List<String> pt = g.pages(new InGame.Edition("2031-03-04", "Daily edition", "The Daily Block", null, null, List.of(new InGame.Section("Awards", paras)), List.of()))
                    .stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
            for (int i = 1; i < pt.size(); i++) {
                String x = pt.get(i), seg = x.substring(x.lastIndexOf('\n') + 1), next = i + 1 < pt.size() ? pt.get(i + 1) : "";
                check(InGame.lines(x.endsWith("\n") ? x.substring(0, x.length() - 1) : x, false) <= 13, "book: no page runs past the bottom (" + n + "): " + x);
                if (x.endsWith("\n")) continue;   // the page ends between paragraphs
                check(labels.stream().noneMatch(l -> l.startsWith(seg) || seg.startsWith(l) && InGame.lines(seg, false) <= InGame.lines(l, false)),
                        "book: a label keeps a line of its text on its page (" + n + "): " + seg);
                String carried = next.contains("\n") ? next.substring(0, next.indexOf('\n')) : "";
                check(carried.isEmpty() || carried.split(" ").length > 2 && carried.length() >= 15, "book: no one or two words left for the next page (" + n + "): " + carried);
            }
            // a "- Label: Name." item first under a heading: the heading never ends a page alone
            List<String> ph = g.pages(new InGame.Edition("2031-03-04", "Daily edition", "The Daily Block", null, null, List.of(new InGame.Section("Front", List.of("hen ".repeat(n) + "end.")),
                    new InGame.Section("Awards", List.of("- Mud Pies: Kai won. The coop has opinions about where every one of the blue rugs should go and the rooster agrees with them."))), List.of()))
                    .stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
            check(ph.stream().noneMatch(x -> x.stripTrailing().endsWith("Awards")), "book: no page ends on a bare heading (" + n + "): " + ph);
        }
        // today.json round trip: a paragraph longer than the old 600 cut reaches the book whole
        String longP = "word ".repeat(150).strip();
        String todayJson = io.github.eastmadc.masthead.edition.PyJson.dumps(io.github.eastmadc.masthead.edition.GameEdition.build(Settings.defaults(),
                java.time.LocalDate.parse("2031-03-04"), new com.google.gson.JsonObject(), "<h2>T</h2><h3>Long</h3><p>" + longP + "</p>", "L", false, null), false);
        check(longP.length() > 600 && InGame.edition(com.google.gson.JsonParser.parseString(todayJson).getAsJsonObject()).sections().get(0).paragraphs().get(0).equals(longP),
                "book: today.json keeps a paragraph longer than the old 600 cut whole");
        // /stats and the join message: no line is wider than the chat, so no number wraps away from its label and no button is cut in two
        List<Component> wide = g.statLines(new InGame.Stats("Steve_Builder", true, 7654.5, 1234, 56789, 12, 987654, 4321, 77, 123456, 1234.5, 23.4, 345.6, 6789.1, 9876, 543, 0, 8765, 0, java.util.Map.of("zombie", 3456L)), true);
        check(wide.size() > 6 && wide.stream().allMatch(x -> InGame.px(PlainTextComponentSerializer.plainText().serialize(x), false) <= 320), "/stats: every line fits the chat: " + plain(wide));
        String longName = plain(g.statLines(new InGame.Stats("W".repeat(16), false, 1.5, 1, 2, 0, 10, 0, 0, 5, 1, 0, 0, 0, 0, 0, 0, 0, 0,
                java.util.Map.of("zombified_piglin", 456789L, "wither_skeleton", 345678L, "elder_guardian", 234567L)), true));
        check(longName.contains("stats \n  (as of their last logout)\n") && InGame.px(longName.split("\n")[0], true) <= 320, "/stats: after a long name the grey note has a line of its own: " + longName);
        check(longName.contains("wither skeleton 345,678,\n   elder guardian 234,567\n") && longName.lines().skip(1).allMatch(x -> InGame.px(x, false) <= 320), "/stats: no kill count wraps away from its mob: " + longName);
        // /stats fits Minecraft's 10-line chat with every stat: a long name (the note on its own line), big numbers, long mob names and the link
        for (boolean hours : new boolean[] {true, false}) {
            List<Component> most = g.statLines(new InGame.Stats("Rex_Owner_Shovel", false, 7654.5, 1234, 56789, 12, 987654, 4321, 77, 123456, 1234.5, 23.4, 345.6, 6789.1, 9876, 543, 21, 8765, 0,
                    java.util.Map.of("zombified_piglin", 456789L, "wither_skeleton", 345678L, "elder_guardian", 234567L)), hours);
            String all = plain(most);
            check(most.size() <= 10 && all.contains("stats \n  (as of their last logout)\n") && !clicks(most).isEmpty(), "/stats fits in 10 lines (hours " + hours + "): " + all);
            check(java.util.stream.Stream.of("Deaths 1,234", "Mob kills 56,789", "Player kills 12", "Blocks mined 987,654", "Diamond ore 4,321", "Ancient debris 77", "On foot 1,235 km",
                    "Horse 23.4 km", "Boat 346 km", "Elytra 6,789 km", "Trades 9,876", "Bred 543", "Fish 21", "Eggs 8,765", "Jumps 123,456", "elder guardian 234,567").allMatch(all::contains)
                    && all.contains("Played 7,654.5 h") == hours && most.stream().allMatch(x -> InGame.px(PlainTextComponentSerializer.plainText().serialize(x), false) <= InGame.CHAT_W),
                    "/stats in 10 lines drops no stat and keeps each number with its label: " + all);
        }
        // G3: no paper, or one older than yesterday: no "just landed" line on join, and /news says when the paper comes out
        var st = InGame.class.getDeclaredField("settings");
        st.setAccessible(true);
        st.set(g, (java.util.function.Supplier<Settings>) Settings::defaults);
        var td = InGame.class.getDeclaredField("today");
        td.setAccessible(true);
        for (String date : new String[] {null, today.minusDays(5).toString()}) {
            td.set(g, date == null ? null : new InGame.Edition(date, "Daily edition", "The Daily Block", null, null, List.of(), List.of()));
            told.clear();
            g.onCommand(kai, news, "news", new String[0]);
            check(g.welcome(kai).isEmpty() && told.equals(List.of("No paper yet today. It comes out every morning at 05:30.")), "no fresh paper (" + date + "): " + plain(g.welcome(kai)) + " " + told);
        }
        td.set(g, new InGame.Edition(today.toString(), "Daily edition", "The Daily Block", null, null, List.of(), List.of()));
        check(plain(g.welcome(kai)).contains("The Daily Block just landed!"), "a fresh paper still lands: " + plain(g.welcome(kai)));
        check(plain(g.welcome(kai)).contains("just landed!\n  [") && g.welcome(kai).stream().allMatch(x -> InGame.px(PlainTextComponentSerializer.plainText().serialize(x), false) <= 320)
                && InGame.px(plain(g.welcome(kai)).split("\n")[0], true) <= 320,
                "the join message: the buttons are on a line of their own and every line fits the chat: " + plain(g.welcome(kai)));
        // all four buttons and both awards
        var mr = InGame.class.getDeclaredField("mailroom");
        mr.setAccessible(true);
        mr.set(g, new World(Files.createTempDirectory("mh-join"), true, true, List.of()).room);
        g.publicUrl = "http://paper.example/";
        td.set(g, new InGame.Edition(today.toString(), "Daily edition", "The Daily Block", new InGame.Award("Kai_Ore", List.of()), new InGame.Award("Quartz_Pim", List.of()), List.of(), List.of()));
        List<String> join = g.welcome(kai).stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
        check(join.get(1).equals("  [read it] [QR for your phone] [newspaper] [mail the editor]") && InGame.px(join.get(1), false) <= 320, "the join message: all four buttons fit on one line: " + join);
        check(join.size() == 4 && join.get(2).endsWith(": Kai_Ore [why?]") && join.get(3).endsWith(": Quartz_Pim [why?]"), "the join message: each award has a line of its own: " + join);
        for (String file : List.of("game/InGame.java", "game/Mailroom.java", "Masthead.java", "construction/BuildCommands.java")) {
            String src = Files.readString(Path.of("src/main/java/io/github/eastmadc/masthead").resolve(file));
            check(!src.contains("toLowerCase()") && !src.contains("toUpperCase()"), file + " changes case with Locale.ROOT, never the server's language");
        }
    }

    static String plain(List<Component> lines) {
        return String.join("\n", lines.stream().map(PlainTextComponentSerializer.plainText()::serialize).toList());
    }

    /** Every click target in these chat lines. */
    static List<String> clicks(List<Component> lines) {
        List<String> out = new ArrayList<>();
        for (Component c : lines) {
            if (c.clickEvent() != null) out.add(((net.kyori.adventure.text.event.ClickEvent.Payload.Text) c.clickEvent().payload()).value());
            out.addAll(clicks(c.children()));
        }
        return out;
    }

    static final class World {
        final Path data;
        final Rec push = new Rec();
        final Mailroom room;

        World(Path root, boolean open, boolean channel, List<String> hidden) throws Exception {
            data = Files.createDirectories(root);
            Settings d = Settings.defaults();
            Settings s = new Settings(d.paper(), d.tone(), d.owner(), new Settings.Privacy(hidden, List.of(), 40), new Settings.Schedule(LocalTime.of(5, 30), "UTC"),
                    d.writer(), d.web(), new Settings.Features(open, true, false, true, 3, ""),
                    new Settings.Alerts(channel ? "https://ntfy.example/topic" : "", "", false));
            Logger log = Logger.getAnonymousLogger();
            log.setUseParentHandlers(false);
            Concerns c = new Concerns(data, log, a -> push);
            room = new Mailroom(data.toFile(), log, Runnable::run, () -> s, c);
        }

        List<String> send(String name, String text) { return send(name, UUID.randomUUID(), text); }

        List<String> send(String name, UUID u, String text) {
            List<String> said = new ArrayList<>();
            room.submit(name, u, "letter", text, (Component c) -> said.add(PlainTextComponentSerializer.plainText().serialize(c)));
            return said;
        }

        List<String> pushes() { return push.bodies; }
    }

    static final class Rec implements Push {
        final List<String> bodies = new ArrayList<>();
        @Override public boolean hasNtfy() { return true; }
        @Override public boolean hasDiscord() { return false; }
        @Override public boolean sendNtfy(String title, String body, String priority, List<String> tags) { bodies.add(body); return true; }
        @Override public boolean sendDiscord(String title, String body) { return true; }
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
