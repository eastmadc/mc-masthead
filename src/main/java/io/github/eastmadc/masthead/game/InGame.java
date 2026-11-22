package io.github.eastmadc.masthead.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.CommandExecutor;
import io.github.eastmadc.masthead.collect.Blotter;
import io.github.eastmadc.masthead.pipeline.State;
import io.github.eastmadc.masthead.config.Settings;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;

/**
 * The in-game side of the paper: join message, /news (link, QR map, newspaper book), /awards, /stats, and the mailroom hooks.
 * It shows today.json, which the edition job writes. All paper text is shown as plain text (never parsed as formatting),
 * so nothing in it can create links or run commands.
 */
public final class InGame implements Listener, TabCompleter, CommandExecutor {

    record Award(String winner, List<String> reasons) {}
    record Section(String heading, List<String> paragraphs) {}
    record Edition(String date, String label, String title, Award coolest, Award mostSus, List<Section> sections, List<Mailroom.Printed> printed) {}

    private final JavaPlugin plugin;
    private final java.util.function.Supplier<Settings> settings;
    private final Mailroom mailroom;
    private final java.util.function.BiConsumer<CommandSender, String[]> admin;   // /masthead admin commands live in the main class

    private volatile Edition today;
    private long todayMtime = -1;
    private boolean[] qr;            // 128x128, true = dark
    private MapView qrMap;
    private NamespacedKey paperKey;
    private final Map<String, Long> cooldown = new HashMap<>();   // player + command -> last use
    private final Blotter blotter;
    private volatile io.github.eastmadc.masthead.construction.Construction construction;   // /news build

    // ---------------------------------------------------------------- startup
    public InGame(JavaPlugin plugin, java.util.function.Supplier<Settings> settings, Mailroom mailroom, Blotter blotter,
                  java.util.function.BiConsumer<CommandSender, String[]> admin) {
        this.plugin = plugin;
        this.settings = settings;
        this.mailroom = mailroom;
        this.blotter = blotter;
        this.admin = admin;
        paperKey = new NamespacedKey(plugin, "edition");
        qr = loadQr();
        setupMap();
        loadToday();
        // Re-read today.json once a minute (off the main thread); the edition job replaces it each morning.
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::loadToday, 20L * 60, 20L * 60);
    }

    private org.bukkit.configuration.file.FileConfiguration getConfig() { return plugin.getConfig(); }
    private File getDataFolder() { return plugin.getDataFolder(); }
    private java.util.logging.Logger getLogger() { return plugin.getLogger(); }
    private String paperName() { return settings.get().paper().name(); }
    /** "🏆 Play of the Day" -> "Play of the Day" (Minecraft's font has no emoji). */
    private static String plainName(String s) { return s.replaceAll("[\\x{10000}-\\x{10FFFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]", "").strip(); }

    /** The address players open (web.public-url). "" when it is not set or web.mode is off: then no link and no QR code. */
    public volatile String publicUrl = "";
    String url() { return publicUrl; }
    int qrMapId() { return qrMap == null ? -1 : qrMap.getId(); }
    String todayDate() { return today == null ? "none" : today.date(); }
    public void forceReload() { todayMtime = -1; loadToday(); }
    public String todayDateForStatus() { return todayDate(); }
    public void selftestPublic(CommandSender s) { selftest(s); }
    public void construction(io.github.eastmadc.masthead.construction.Construction c) { construction = c; }
    /** New address -> new QR picture on the same map (posters update by themselves). */
    public void refreshQr(boolean[] bits) { if (bits != null) { qr = bits; setupMap(); } }

    private boolean[] loadQr() {
        boolean[] dark = new boolean[128 * 128];
        if (getResourceStream() == null && !new File(getDataFolder(), "qr.png").isFile()) return dark;
        try {
            File custom = new File(getDataFolder(), "qr.png");
            BufferedImage img;
            if (custom.isFile()) {
                img = ImageIO.read(custom);
            } else {
                try (InputStream in = getResourceStream()) { img = ImageIO.read(in); }
            }
            for (int y = 0; y < 128; y++)
                for (int x = 0; x < 128; x++) {
                    int px = x * img.getWidth() / 128, py = y * img.getHeight() / 128;
                    int rgb = img.getRGB(px, py);
                    int lum = ((rgb >> 16 & 255) + (rgb >> 8 & 255) + (rgb & 255)) / 3;
                    dark[y * 128 + x] = lum < 128;
                }
        } catch (Exception e) {
            getLogger().warning("Could not load the QR image: " + e);
        }
        return dark;
    }

    private InputStream getResourceStream() { return plugin.getResource("qr.png"); }

    @SuppressWarnings("deprecation") // Bukkit.getMap(int) is the only way to find our map again after a restart
    private void setupMap() {
        try {
            // The QR map's id lives in state.yml (not config.yml, which is the owner's file), saved safely (State).
            int id = State.load(getDataFolder().toPath()).getInt("qr-map-id", -1);
            MapView v = id >= 0 ? Bukkit.getMap(id) : null;
            if (v == null) {
                v = Bukkit.createMap(Bukkit.getWorlds().get(0));
                if (!State.set(getDataFolder().toPath(), "qr-map-id", v.getId())) getLogger().warning("state.yml could not be saved (QR map id)");
            }
            for (MapRenderer r : new ArrayList<>(v.getRenderers())) v.removeRenderer(r);
            v.setScale(MapView.Scale.CLOSEST);
            v.setTrackingPosition(false);
            v.setUnlimitedTracking(false);
            v.addRenderer(new QrRenderer(qr));
            qrMap = v;
        } catch (Exception e) {
            getLogger().warning("QR map not available: " + e);
        }
    }

    static final class QrRenderer extends MapRenderer {
        private final boolean[] dark;
        private boolean drawn;
        QrRenderer(boolean[] dark) { super(false); this.dark = dark; }
        @Override
        public void render(MapView view, MapCanvas canvas, Player player) {
            if (drawn) return;
            for (int y = 0; y < 128; y++)
                for (int x = 0; x < 128; x++)
                    canvas.setPixelColor(x, y, dark[y * 128 + x] ? Color.BLACK : Color.WHITE);
            drawn = true;
        }
    }

    // ---------------------------------------------------------------- today.json
    private void loadToday() {
        File f = new File(getDataFolder(), "today.json");
        try {
            if (!f.isFile()) return;
            long m = f.lastModified();
            if (m == todayMtime) return;
            if (f.length() > 512 * 1024) { getLogger().warning("today.json is too big; ignored"); return; }
            today = edition(JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject());
            todayMtime = m;
            getLogger().info("Loaded edition " + today.date());
        } catch (Exception e) {
            getLogger().warning("Could not read today.json: " + e);
        }
    }

    /** today.json as the book gets it: every field capped (a paragraph at the cap GameEdition wrote it with). */
    static Edition edition(JsonObject o) {
        List<Section> secs = new ArrayList<>();
        if (o.get("sections") instanceof JsonArray arr)
            for (JsonElement e : arr) {
                if (!(e instanceof JsonObject so) || secs.size() >= 20) continue;
                secs.add(new Section(str(so, "h", 60), strs(so, "p", 20, io.github.eastmadc.masthead.edition.GameEdition.PARA_MAX)));
            }
        List<Mailroom.Printed> printed = new ArrayList<>();
        if (o.get("printed") instanceof JsonArray pa)
            for (JsonElement e : pa)
                if (e instanceof JsonObject po && printed.size() < 100) {
                    String n = str(po, "name", 16), k = str(po, "kind", 8), d = str(po, "date", 10);
                    if (n.matches("\\.?[A-Za-z0-9_]{3,16}") && (k.equals("letter") || k.equals("tip")) && d.matches("\\d{4}-\\d\\d-\\d\\d"))
                        printed.add(new Mailroom.Printed(n, k, d));
                }
        return new Edition(str(o, "date", 10), str(o, "label", 60), str(o, "title", 80),
                award(o, "coolest"), award(o, "most_sus"), secs, printed);
    }

    private static String str(JsonObject o, String k, int max) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive()) return "";
        String s = e.getAsString().replaceAll("[\\p{Cntrl}&&[^\n]]", "").strip();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static List<String> strs(JsonObject o, String k, int maxItems, int maxLen) {
        List<String> out = new ArrayList<>();
        if (o.get(k) instanceof JsonArray arr)
            for (JsonElement e : arr) {
                if (out.size() >= maxItems || !e.isJsonPrimitive()) continue;
                String s = e.getAsString().replaceAll("\\p{Cntrl}", " ").strip();
                if (!s.isEmpty()) out.add(s.length() > maxLen ? s.substring(0, maxLen) : s);
            }
        return out;
    }

    private static Award award(JsonObject o, String k) {
        if (!(o.get(k) instanceof JsonObject a)) return null;
        String w = str(a, "winner", 16);
        return w.isEmpty() ? null : new Award(w, strs(a, "reasons", 3, 120));
    }

    /** Is the loaded edition from today or yesterday (the paper comes out at 05:30)? */
    private Edition fresh() {
        Edition e = today;
        if (e == null) return null;
        try {
            LocalDate d = LocalDate.parse(e.date());
            return d.isBefore(LocalDate.now().minusDays(1)) ? null : e;
        } catch (Exception ex) { return null; }
    }

    // ---------------------------------------------------------------- messages
    private Component button(String label, NamedTextColor color, String hover, ClickEvent click) {
        return Component.text(label, color).decorate(TextDecoration.UNDERLINED)
                .hoverEvent(HoverEvent.showText(Component.text(hover))).clickEvent(click);
    }

    List<Component> welcome(CommandSender viewer) {
        Edition e = fresh();
        List<Component> lines = new ArrayList<>();
        if (e == null) return lines;   // no paper from today or yesterday: nothing "just landed"
        lines.add(Component.text().append(Component.text("★ ", NamedTextColor.YELLOW))
                .append(Component.text(paperName() + " just landed!", NamedTextColor.GOLD, TextDecoration.BOLD)).build());
        // the buttons on a line of their own: after the paper's name the line wrapped in the middle of a button
        lines.add(Component.text().append(Component.text("  "))
                .append(url().isEmpty() ? Component.empty() : button("[read it]", NamedTextColor.AQUA, "Opens today's paper in your browser", ClickEvent.openUrl(url())).append(Component.text(" ")))
                .append(url().isEmpty() ? Component.empty() : button("[QR for your phone]", NamedTextColor.GREEN, "Gives you a map with a QR code", ClickEvent.runCommand("/news qr")).append(Component.text(" ")))
                .append(button("[newspaper]", NamedTextColor.LIGHT_PURPLE, "Gives you today's paper as a book", ClickEvent.runCommand("/news paper")))
                .append(mailroom != null && mailroom.offeredTo(viewer) ? Component.text(" ").append(button("[mail the editor]", NamedTextColor.YELLOW, "Write a letter or a tip for tomorrow's paper", ClickEvent.runCommand("/letter"))) : Component.empty())
                .build());
        if (e != null && (e.coolest() != null || e.mostSus() != null)) {
            // each award gets its own [why?]: one button at the end read as if only the second award had a reason
            if (e.coolest() != null)
                lines.add(Component.text().append(Component.text("  ", NamedTextColor.GRAY)).append(Component.text(plainName(settings.get().paper().goodAward()) + ": ", NamedTextColor.GRAY)).append(Component.text(e.coolest().winner(), NamedTextColor.GOLD))
                        .append(Component.text(" ")).append(why(e.coolest())).build());
            if (e.mostSus() != null)
                lines.add(Component.text().append(Component.text("  ", NamedTextColor.GRAY)).append(Component.text(plainName(settings.get().paper().badAward()) + ": ", NamedTextColor.GRAY)).append(Component.text(e.mostSus().winner(), NamedTextColor.RED))
                        .append(Component.text(" ")).append(why(e.mostSus())).build());
        } else {
            lines.add(Component.text("  Today's " + plainName(settings.get().paper().goodAward()) + ", " + plainName(settings.get().paper().badAward()) + ", and more.", NamedTextColor.GRAY));
        }
        return lines;
    }

    /** [why?] for one award: hovering shows its reasons, clicking shows /awards. */
    private Component why(Award a) {
        String hover = a.reasons().isEmpty() ? "Show the details" : "- " + String.join("\n- ", a.reasons());
        return button("[why?]", NamedTextColor.YELLOW, hover, ClickEvent.runCommand("/awards"));
    }

    List<Component> sniffLines() {
        Edition e = fresh();
        List<Component> lines = new ArrayList<>();
        if (e == null || (e.coolest() == null && e.mostSus() == null)) {
            lines.add(Component.text("No awards yet today. Go do something legendary (or something dumb).", NamedTextColor.GRAY));
            return lines;
        }
        addAward(lines, plainName(settings.get().paper().goodAward()) + ": ", NamedTextColor.GOLD, e.coolest());
        addAward(lines, plainName(settings.get().paper().badAward()) + ": ", NamedTextColor.RED, e.mostSus());
        return lines;
    }

    private static void addAward(List<Component> lines, String title, NamedTextColor c, Award a) {
        if (a == null) return;
        lines.add(Component.text().append(Component.text(title, c, TextDecoration.BOLD)).append(Component.text(a.winner(), NamedTextColor.WHITE)).build());
        for (String r : a.reasons()) lines.add(Component.text("  - " + r, NamedTextColor.GRAY));
    }

    // ---------------------------------------------------------------- newspaper book
    static final int BOOK_W = 114, CHAT_W = 310;   // a book page is 114 pixels wide; chat is 320 by default

    /** Width in pixels in Minecraft's default font (each glyph plus 1; bold adds 1). Other characters count as wide. */
    static int px(String s, boolean bold) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            n += (bold ? 1 : 0) + ("!',.:;i|".indexOf(c) >= 0 ? 2 : "`l".indexOf(c) >= 0 ? 3 : " \"I[]t".indexOf(c) >= 0 ? 4
                    : "()*<>fk{}".indexOf(c) >= 0 ? 5 : "@~".indexOf(c) >= 0 ? 7 : c < 0x250 ? 6 : 9);
        }
        return n;
    }

    /** Lines the text takes on a book page, wrapped at spaces the way the game does. */
    static int lines(String s, boolean bold) {
        int n = 0, sp = px(" ", bold);
        for (String para : s.split("\n", -1)) {
            int len = 0, l = 1;
            for (String w : para.split(" ")) {
                if (w.isEmpty()) continue;
                int pw = px(w, bold);
                if (len > 0 && len + sp + pw > BOOK_W) { l++; len = pw; }
                else len += (len > 0 ? sp : 0) + pw;
                while (len > BOOK_W) { l++; len -= BOOK_W; }
            }
            n += l;
        }
        return n;
    }

    List<Component> pages(Edition e) {
        final int H = 13, MAXP = 50;   // a page shows 14 lines; one is kept spare for a client with a wider font
        List<Component> pages = new ArrayList<>();
        TextComponent.Builder cover = Component.text()
                .append(Component.text(e.title() + "\n\n", NamedTextColor.DARK_RED, TextDecoration.BOLD))
                .append(Component.text(e.label() + "\n\n", NamedTextColor.DARK_GRAY));
        if (e.coolest() != null) cover.append(Component.text(plainName(settings.get().paper().goodAward()) + ":\n", NamedTextColor.GOLD, TextDecoration.BOLD)).append(Component.text(e.coolest().winner() + "\n", NamedTextColor.BLACK));
        if (e.mostSus() != null) cover.append(Component.text(plainName(settings.get().paper().badAward()) + ":\n", NamedTextColor.RED, TextDecoration.BOLD)).append(Component.text(e.mostSus().winner() + "\n", NamedTextColor.BLACK));
        pages.add(cover.build());

        TextComponent.Builder page = Component.text();
        int used = 0;
        for (Section s : e.sections()) {
            if (s.paragraphs().isEmpty()) continue;   // nothing under the heading (a table is not carried into the book)
            // a heading starts a new page if fewer than 4 lines are left
            if (!s.heading().isEmpty()) {
                int need = lines(s.heading(), true);
                if (used > 0 && used + 1 + need + 3 > H) { pages.add(page.build()); page = Component.text(); used = 0; }
                page.append(Component.text((used > 0 ? "\n" : "") + s.heading() + "\n", NamedTextColor.DARK_BLUE, TextDecoration.BOLD));
                used += need + (used > 0 ? 1 : 0);
            } else if (used > 0) {   // the closing note ("Written by an AI..."): after an empty line, and never split over two pages
                int need = 1;
                for (String p : s.paragraphs()) need += lines(p, false);
                if (used + need > H) { pages.add(page.build()); page = Component.text(); used = 0; }
                else { page.append(Component.text("\n")); used++; }
            }
            for (String p : s.paragraphs()) {
                // split long paragraphs across pages word by word
                StringBuilder chunk = new StringBuilder();
                for (String w : p.split(" ")) {
                    String trial = chunk.isEmpty() ? w : chunk + " " + w;
                    if (used + lines(trial, false) > H) {
                        // end the page on a full sentence when that leaves at most 3 lines empty
                        int cut = Math.max(chunk.lastIndexOf(". "), Math.max(chunk.lastIndexOf("! "), chunk.lastIndexOf("? "))) + 1;
                        String rest = "";
                        if (cut > 0 && lines(chunk.toString(), false) - lines(chunk.substring(0, cut), false) <= 3) { rest = chunk.substring(cut + 1) + " "; chunk.setLength(cut); }
                        if (!chunk.isEmpty()) page.append(Component.text(chunk.toString(), NamedTextColor.BLACK));
                        pages.add(page.build()); page = Component.text(); used = 0;
                        if (pages.size() >= MAXP) return cap(pages, MAXP, e.sections());   // a page added at the end of a paragraph is not counted here
                        chunk = new StringBuilder(rest + w);
                    } else {
                        chunk = new StringBuilder(trial);
                    }
                }
                page.append(Component.text(chunk + "\n", NamedTextColor.BLACK));
                used += lines(chunk.toString(), false);
                if (used >= H) { pages.add(page.build()); page = Component.text(); used = 0; }
            }
        }
        if (used > 0) pages.add(page.build());
        return pages.size() > MAXP ? cap(pages, MAXP, e.sections()) : pages;
    }

    /** The first max pages; the closing "Written by an AI" note, when the edition has it, keeps the last one instead of being cut off. */
    private static List<Component> cap(List<Component> pages, int max, List<Section> secs) {
        Section note = secs.isEmpty() ? null : secs.get(secs.size() - 1);
        if (note == null || !note.heading().isEmpty() || !note.paragraphs().equals(List.of(io.github.eastmadc.masthead.edition.Pages.AI_FOOTER))) return pages.subList(0, max);
        List<Component> out = new ArrayList<>(pages.subList(0, max - 1));
        out.add(Component.text().append(Component.text(io.github.eastmadc.masthead.edition.Pages.AI_FOOTER + "\n", NamedTextColor.BLACK)).build());
        return out;
    }

    // ---------------------------------------------------------------- commands
    /** /news write ... is the same as /letter ... */
    private void writeLetter(CommandSender sender, String[] args) {
        if (mailroom == null || !mailroom.enabled()) { sender.sendMessage(Component.text(Mailroom.CLOSED, NamedTextColor.GRAY)); return; }
        mailroom.command(sender, args);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.equals("awards")) { sniffLines().forEach(sender::sendMessage); return true; }
        if (name.equals("stats")) { stats(sender, args.length > 0 ? args[0] : null); return true; }
        if (name.equals("letter")) {
            if (mailroom == null || !mailroom.enabled()) sender.sendMessage(Component.text(Mailroom.CLOSED, NamedTextColor.GRAY));
            else mailroom.command(sender, args);
            return true;
        }
        if (name.equals("masthead")) { admin.accept(sender, args); return true; }
        String sub = args.length > 0 ? args[0].toLowerCase(java.util.Locale.ROOT) : "";
        switch (sub) {
            case "qr" -> giveQr(sender);
            case "paper", "book" -> givePaper(sender);
            case "write", "letter" -> writeLetter(sender, args.length > 1 ? java.util.Arrays.copyOfRange(args, 1, args.length) : new String[0]);
            case "build" -> {
                if (construction == null) sender.sendMessage(Component.text("Construction news is off on this server.", NamedTextColor.GRAY));
                else construction.command(sender, java.util.Arrays.copyOfRange(args, 1, args.length));
            }
            default -> {
                Edition e = fresh();
                if (e == null) sender.sendMessage(Component.text("No paper yet today. It comes out every morning at " + settings.get().schedule().time() + ".", NamedTextColor.GRAY));
                if (e != null && !e.title().isEmpty()) sender.sendMessage(Component.text(e.title() + "  (" + e.label() + ")", NamedTextColor.GOLD));
                welcome(sender).forEach(sender::sendMessage);
            }
        }
        return true;
    }

    boolean onCooldown(Player p, String what, long ms) {
        long now = System.currentTimeMillis();
        String key = p.getUniqueId() + what;   // each command waits on its own: the join message has both buttons side by side
        Long last = cooldown.get(key);
        if (last != null && now - last < ms) {
            long wait = (ms - (now - last) + 999) / 1000;
            p.sendMessage(Component.text("Slow down. Try " + what + " again in " + wait + (wait == 1 ? " second." : " seconds."), NamedTextColor.GRAY));
            return true;
        }
        cooldown.put(key, now);
        return false;
    }

    private void giveQr(CommandSender sender) {
        if (url().isEmpty()) { sender.sendMessage(Component.text("This server has no web page address yet.", NamedTextColor.GRAY)); return; }
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
        if (qrMap == null) { p.sendMessage(Component.text("The QR map isn't available right now.", NamedTextColor.RED)); return; }
        for (ItemStack it : p.getInventory().getContents())
            if (it != null && it.getType() == Material.FILLED_MAP && it.getItemMeta() instanceof MapMeta mm
                    && mm.hasMapView() && mm.getMapView() != null && mm.getMapView().getId() == qrMap.getId()) {
                p.sendMessage(Component.text("You already have the QR map. Hold it and point your phone camera at the screen.", NamedTextColor.GRAY));
                return;
            }
        if (onCooldown(p, "/news qr", 10_000)) return;
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        map.editMeta(MapMeta.class, m -> {
            m.setMapView(qrMap);
            m.displayName(Component.text(paperName() + " (QR)", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            m.lore(List.of(Component.text("Scan with your phone camera", NamedTextColor.GRAY)));
        });
        p.getInventory().addItem(map).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
        p.sendMessage(Component.text("Here's the QR map! Hold it, then point your phone camera at the screen. Put it in an item frame for a poster.", NamedTextColor.GREEN));
    }

    private void givePaper(CommandSender sender) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
        Edition e = fresh();
        if (e == null || e.sections().isEmpty()) { p.sendMessage(Component.text("No paper yet today. It comes out every morning at " + settings.get().schedule().time() + ".", NamedTextColor.GRAY)); return; }
        for (ItemStack it : p.getInventory().getContents())
            if (it != null && it.getType() == Material.WRITTEN_BOOK && it.getItemMeta() != null
                    && e.date().equals(it.getItemMeta().getPersistentDataContainer().get(paperKey, PersistentDataType.STRING))) {
                p.sendMessage(Component.text("You already have today's paper.", NamedTextColor.GRAY));
                return;
            }
        if (onCooldown(p, "/news paper", 30_000)) return;
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        book.editMeta(BookMeta.class, m -> {
            String t = paperName();
            m.setTitle(t.length() > 32 ? t.substring(0, 32) : t);   // book titles are capped at 32 characters
            m.setAuthor((t.startsWith("The ") ? t.substring(4) : t) + " staff");
            m.addPages(pages(e).toArray(new Component[0]));
            m.lore(List.of(Component.text(e.label(), NamedTextColor.GRAY)));
            m.getPersistentDataContainer().set(paperKey, PersistentDataType.STRING, e.date());
        });
        p.getInventory().addItem(book).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
        p.sendMessage(Component.text("Fresh off the press! Today's " + (paperName().startsWith("The ") ? paperName().substring(4) : paperName()) + " is in your inventory.", NamedTextColor.GREEN));
    }

    /** Console check: writes qr-check.png (exactly what the map draws) and prints the messages and book pages as text. */
    private void selftest(CommandSender sender) {
        try {
            BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) img.setRGB(x, y, qr[y * 128 + x] ? 0 : 0xFFFFFF);
            ImageIO.write(img, "png", new File(getDataFolder(), "qr-check.png"));
        } catch (Exception ex) { sender.sendMessage("qr-check failed: " + ex); }
        var plain = PlainTextComponentSerializer.plainText();
        sender.sendMessage("map=" + (qrMap == null ? "none" : qrMap.getId()) + " edition=" + (today == null ? "none" : today.date()) + " fresh=" + (fresh() != null));
        welcome(sender).forEach(c -> sender.sendMessage("WELCOME| " + plain.serialize(c)));
        sniffLines().forEach(c -> sender.sendMessage("SNIFF| " + plain.serialize(c)));
        java.nio.file.Path statsDir = io.github.eastmadc.masthead.collect.StatsSnapshot.statsFolder();
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            try {
                Stats st = op.getName() == null || hidden(op.getName()) ? null : fileStats(statsDir, op.getName(), op.getUniqueId());
                if (st != null) statLines(st, true).forEach(c -> sender.sendMessage("STATS| " + plain.serialize(c)));
            } catch (Exception ex) { sender.sendMessage("STATS| error " + ex); }
        }
        Edition e = fresh();
        if (e != null) {
            List<Component> ps = pages(e);
            sender.sendMessage("BOOK pages=" + ps.size());
            for (int i = 0; i < ps.size(); i++) sender.sendMessage("PAGE " + (i + 1) + "| " + plain.serialize(ps.get(i)).replace("\n", " / "));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("stats") && args.length == 1) {
            java.util.Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            names.addAll(KnownNames.get(plugin));   // read in the background: never a player file on the main thread
            String start = args[0].toLowerCase(java.util.Locale.ROOT);
            return names.stream().filter(n -> !hidden(n) && n.toLowerCase(java.util.Locale.ROOT).startsWith(start)).toList();
        }
        if (cmd.getName().equalsIgnoreCase("letter") && args.length == 1) return "tip".startsWith(args[0].toLowerCase(java.util.Locale.ROOT)) ? List.of("tip") : List.of();
        if (cmd.getName().equalsIgnoreCase("masthead") && args.length == 1)
            return java.util.stream.Stream.of("reload", "status", "now", "preview", "selftest", "construction")
                    .filter(o -> o.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
        if (cmd.getName().equalsIgnoreCase("masthead") && args.length == 2 && args[0].equalsIgnoreCase("status"))
            return "share".startsWith(args[1].toLowerCase(java.util.Locale.ROOT)) ? List.of("share") : List.of();
        if (cmd.getName().equalsIgnoreCase("masthead") && args.length == 2 && args[0].equalsIgnoreCase("construction"))
            return java.util.stream.Stream.of("status", "pause", "resume", "clear-name", "reset", "forget")
                    .filter(o -> o.startsWith(args[1].toLowerCase(java.util.Locale.ROOT))).toList();
        if (cmd.getName().equalsIgnoreCase("news") && args.length >= 2 && args[0].equalsIgnoreCase("build") && construction != null)
            return construction.tabComplete(java.util.Arrays.copyOfRange(args, 1, args.length));
        if (!cmd.getName().equalsIgnoreCase("news") || args.length != 1) return List.of();
        List<String> opts = new ArrayList<>(List.of("qr", "paper", "write", "build"));
        if (url().isEmpty()) opts.remove("qr");   // no address: /news qr has nothing to show
        if (!sender.hasPermission("masthead.write")) opts.remove("write");
        return opts.stream().filter(o -> o.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList();
    }


    // ---------------------------------------------------------------- /stats
    record Stats(String name, boolean live, double playH, long deaths, long mobKills, long playerKills, long blocksMined, long diamonds,
                 long debris, long jumps, double footKm, double horseKm, double boatKm, double elytraKm, long trades, long bred,
                 long fish, long eggs, long cake, Map<String, Long> killed) {}

    /** Live numbers for an online player (from the game's memory). */
    private static Stats liveStats(Player p) {
        long mined = 0, dia = 0, debris = 0;
        for (Material m : Material.values()) {
            if (!m.isBlock()) continue;
            try {
                int v = p.getStatistic(Statistic.MINE_BLOCK, m);
                mined += v;
                if (m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE) dia += v;
                if (m == Material.ANCIENT_DEBRIS) debris += v;
            } catch (Exception ignored) { }
        }
        Map<String, Long> killed = new HashMap<>();
        for (EntityType t : EntityType.values()) {
            try {
                int v = p.getStatistic(Statistic.KILL_ENTITY, t);
                if (v > 0) killed.put(t.getKey().getKey(), (long) v);
            } catch (Exception ignored) { }
        }
        long eggs = 0;
        try { eggs = p.getStatistic(Statistic.USE_ITEM, Material.EGG); } catch (Exception ignored) { }
        return new Stats(p.getName(), true, st(p, Statistic.PLAY_ONE_MINUTE) / 72000.0, st(p, Statistic.DEATHS), st(p, Statistic.MOB_KILLS),
                st(p, Statistic.PLAYER_KILLS), mined, dia, debris, st(p, Statistic.JUMP),
                (st(p, Statistic.WALK_ONE_CM) + st(p, Statistic.SPRINT_ONE_CM)) / 100000.0, st(p, Statistic.HORSE_ONE_CM) / 100000.0,
                st(p, Statistic.BOAT_ONE_CM) / 100000.0, st(p, Statistic.AVIATE_ONE_CM) / 100000.0, st(p, Statistic.TRADED_WITH_VILLAGER),
                st(p, Statistic.ANIMALS_BRED), st(p, Statistic.FISH_CAUGHT), eggs, st(p, Statistic.CAKE_SLICES_EATEN), killed);
    }

    private static long st(Player p, Statistic s) {
        try { return p.getStatistic(s); } catch (Exception e) { return 0; }
    }

    /**
     * Saved numbers for a player who is offline (the game's stats file, written when they log out). statsDir comes from
     * StatsSnapshot.statsFolder(), found on the main thread, so this needs no server calls and can run off it.
     */
    private static Stats fileStats(java.nio.file.Path statsDir, String name, UUID id) throws Exception {
        if (statsDir == null) return null;
        File f = statsDir.resolve(id + ".json").toFile();
        if (!f.isFile() || f.length() > 4 * 1024 * 1024) return null;
        JsonObject root = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject s = root.get("stats") instanceof JsonObject o ? o : new JsonObject();
        Map<String, Long> custom = cat(s, "custom"), minedM = cat(s, "mined"), used = cat(s, "used"), killed = cat(s, "killed");
        long mined = minedM.values().stream().mapToLong(Long::longValue).sum();
        java.util.function.Function<String, Long> c = k -> custom.getOrDefault(k, 0L);
        return new Stats(name, false, c.apply("play_time") / 72000.0, c.apply("deaths"), c.apply("mob_kills"), c.apply("player_kills"), mined,
                minedM.getOrDefault("diamond_ore", 0L) + minedM.getOrDefault("deepslate_diamond_ore", 0L), minedM.getOrDefault("ancient_debris", 0L),
                c.apply("jump"), (c.apply("walk_one_cm") + c.apply("sprint_one_cm")) / 100000.0, c.apply("horse_one_cm") / 100000.0,
                c.apply("boat_one_cm") / 100000.0, c.apply("aviate_one_cm") / 100000.0, c.apply("traded_with_villager"), c.apply("animals_bred"),
                c.apply("fish_caught"), used.getOrDefault("egg", 0L), c.apply("eat_cake_slice"), killed);
    }

    private static Map<String, Long> cat(JsonObject stats, String category) {
        Map<String, Long> out = new HashMap<>();
        if (stats.get("minecraft:" + category) instanceof JsonObject o)
            for (var e : o.entrySet())
                if (e.getValue().isJsonPrimitive()) {
                    try { out.put(e.getKey().replaceFirst("^minecraft:", ""), e.getValue().getAsLong()); } catch (Exception ignored) { }
                }
        return out;
    }

    private void stats(CommandSender sender, String who) {
        if (sender instanceof Player p && onCooldown(p, "/stats", 3_000)) return;
        if (who == null) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Usage: stats <player>"); return; }
            who = p.getName();
        }
        if (!who.matches("[A-Za-z0-9_]{3,16}")) { sender.sendMessage(Component.text("That isn't a Minecraft name.", NamedTextColor.RED)); return; }
        Component nobody = Component.text("Nobody called " + who + " has played here yet.", NamedTextColor.GRAY);
        boolean self = sender instanceof Player p && p.getName().equalsIgnoreCase(who);
        if (hidden(who) && !self) { sender.sendMessage(nobody); return; }   // privacy.hide-players: their own /stats only
        boolean hours = self || hoursShown(who);
        Player online = Bukkit.getPlayerExact(who);
        if (online != null) { statLines(liveStats(online), hours).forEach(sender::sendMessage); return; }
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(who);
        if (op == null || !op.hasPlayedBefore()) { sender.sendMessage(nobody); return; }
        final String name = op.getName() == null ? who : op.getName();
        final UUID id = op.getUniqueId();
        final java.nio.file.Path statsDir = io.github.eastmadc.masthead.collect.StatsSnapshot.statsFolder();   // main thread: server calls
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Component> lines;
            try {
                Stats s = fileStats(statsDir, name, id);
                lines = s == null ? List.of(Component.text("No saved stats for " + name + " yet.", NamedTextColor.GRAY)) : statLines(s, hours);
            } catch (Exception e) {
                getLogger().warning("stats for " + name + ": " + e);
                lines = List.of(Component.text("Couldn't read the stats for " + name + " right now.", NamedTextColor.RED));
            }
            final List<Component> out = lines;
            Bukkit.getScheduler().runTask(plugin, () -> out.forEach(sender::sendMessage));
        });
    }

    private static String n(long v) { return String.format(Locale.ROOT, "%,d", v); }   // the JVM's own locale would print "1.234" in English text
    private static String km(double v) { return v < 100 ? String.format(Locale.ROOT, "%,.1f km", v) : String.format(Locale.ROOT, "%,.0f km", v); }

    /** label, value, label, value ... as chat lines; a new line starts before a pair that would not fit, so no number wraps away from its label. */
    private static List<Component> kv(Object... parts) {
        List<Component> out = new ArrayList<>();
        TextComponent.Builder b = null;
        int w = 0, sep = px("  ·  ", false);
        for (int i = 0; i + 1 < parts.length; i += 2) {
            String label = parts[i] + " ", value = String.valueOf(parts[i + 1]);
            int pw = px(label + value, false);
            if (b != null && w + sep + pw > CHAT_W) { out.add(b.build()); b = null; }
            if (b == null) { b = Component.text().append(Component.text("  ")); w = px("  ", false); }
            else { b.append(Component.text("  ·  ", NamedTextColor.DARK_GRAY)); w += sep; }
            b.append(Component.text(label, NamedTextColor.GRAY)).append(Component.text(value, NamedTextColor.WHITE));
            w += pw;
        }
        if (b != null) out.add(b.build());
        return out;
    }

    /** privacy.hide-players (any case). */
    private boolean hidden(String name) {
        for (String h : settings.get().privacy().hidePlayers()) if (h.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** owner.hours-ok: false keeps the owner's hours played off /stats for everyone but the owner. */
    private boolean hoursShown(String name) {
        Settings s = settings.get();
        return s.owner().hoursOk() || s.owner().name() == null || !s.owner().name().trim().equalsIgnoreCase(name);
    }

    /** hours = false leaves out the hours played. */
    List<Component> statLines(Stats s, boolean hours) {
        List<Component> l = new ArrayList<>();
        String head = s.name() + "'s season stats ", note = s.live() ? "(live)" : "(as of their last logout)";
        boolean own = px(head, true) + px(note, false) > CHAT_W;   // after a long name the grey note gets a line of its own
        l.add(Component.text().append(Component.text(head, NamedTextColor.GOLD, TextDecoration.BOLD)).append(Component.text(own ? "" : note, NamedTextColor.DARK_GRAY)).build());
        if (own) l.add(Component.text("  " + note, NamedTextColor.DARK_GRAY));
        if (hours) l.addAll(kv("Played", String.format(Locale.ROOT, "%,.1f h", s.playH()), "Deaths", n(s.deaths()), "Mob kills", n(s.mobKills()), "Player kills", n(s.playerKills())));
        else l.addAll(kv("Deaths", n(s.deaths()), "Mob kills", n(s.mobKills()), "Player kills", n(s.playerKills())));
        l.addAll(kv("Blocks mined", n(s.blocksMined()), "Diamond ore", n(s.diamonds()), "Ancient debris", n(s.debris())));
        l.addAll(kv("On foot", km(s.footKm()), "Horse", km(s.horseKm()), "Boat", km(s.boatKm()), "Elytra", km(s.elytraKm())));
        l.addAll(kv("Trades", n(s.trades()), "Bred", n(s.bred()), "Fish", n(s.fish()), "Eggs", n(s.eggs()), "Jumps", n(s.jumps())));
        List<Map.Entry<String, Long>> top = new ArrayList<>(s.killed().entrySet());
        top.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        if (!top.isEmpty()) {
            String label = "Top kills";
            StringBuilder t = new StringBuilder();
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                String one = top.get(i).getKey().replace('_', ' ') + ' ' + n(top.get(i).getValue());
                // a new line before a mob that would not fit, so no count wraps away from its mob
                if (i > 0 && px("  " + label + " " + t + ", " + one, false) > CHAT_W) { l.addAll(kv(label, t + ",")); label = ""; t.setLength(0); }
                t.append(t.isEmpty() ? "" : ", ").append(one);
            }
            l.addAll(kv(label, t.toString()));
        }
        if (url().isEmpty()) return l;   // no web address: a link would go nowhere
        String base = url().endsWith("/") ? url() : url() + "/";
        l.add(Component.text().append(Component.text("  ")).append(button("[everyone's Player Cards]", NamedTextColor.AQUA,
                "Opens the Player Cards page (updated every morning)", ClickEvent.openUrl(base + "players.html#" + s.name()))).build());
        return l;
    }

    // ---------------------------------------------------------------- join
    @EventHandler
    public void onJoin(PlayerJoinEvent ev) {
        Settings s = settings.get();
        if (!s.features().joinMessage()) return;
        Player p = ev.getPlayer();
        long delay = Math.max(1, s.features().joinDelaySeconds()) * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            welcome(p).forEach(p::sendMessage);
            Edition e = today;
            if (mailroom != null && e != null) mailroom.tellPrinted(p, e.printed(), url());
        }, delay);
    }
}
