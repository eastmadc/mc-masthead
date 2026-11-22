package io.github.eastmadc.masthead.facts;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python readership(): newsletter readers in the edition window, from a web access log (format: FORMAT.md).
 * A reader = one address. Addresses are only held in memory here; the result has counts, page names and (in "who") player names.
 * "who" is for the owner's push only: the prompt builder must strip it before the writer sees the facts.
 */
final class Readership {
    private Readership() {}

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * log = access log (null or missing = no readership, JSON null). ipPlayers = "address name" lines.
     * prefix = URL path the paper lives under ("" or e.g. "/news"). Times in the log are local wall-clock times.
     */
    static com.google.gson.JsonElement read(Path log, Path ipPlayers, String prefix, LocalDateTime lo, LocalDateTime hi,
                                           String owner, Set<String> hiddenLower) {
        if (log == null || !Files.isRegularFile(log)) return JsonNull.INSTANCE;
        Map<String, Set<String>> who = new HashMap<>();
        for (String ln : Py.lines(ipPlayers, 4 << 20, 1000000)) {
            String[] p = ln.trim().split("\\s+");
            if (p.length == 2 && Py.isName(p[1]) && !hiddenLower.contains(p[1].toLowerCase(Locale.ROOT)))
                who.computeIfAbsent(p[0], k -> new TreeSet<>()).add(p[1]);
        }
        Pattern line = Pattern.compile("^\\[(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d)\\]\\[\\w+\\] (\\S+) \"GET (" + Pattern.quote(prefix)
                + "(?:/\\S*)?) HTTP/[\\d.]+\" (?:200|304)");
        int views = 0;
        Map<String, Set<String>> readers = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> pages = new LinkedHashMap<>();
        for (String ln : tail(log, 16 << 20)) {
            Matcher m = line.matcher(ln);
            if (!m.find()) continue;
            LocalDateTime t;
            try {
                t = LocalDateTime.parse(m.group(1), TS);
            } catch (RuntimeException e) {
                continue;
            }
            if (t.isBefore(lo) || !t.isBefore(hi)) continue;
            String ip = m.group(2);
            byte[] addr = parseIp(ip);
            if (addr == null || loopback(addr)) continue;   // the server checking itself
            String path = m.group(3).split("\\?", 2)[0].replaceAll("/+$", "");
            String page = page(path.substring(prefix.length()), owner);
            if (page == null) continue;
            views++;
            pages.merge(page, 1, Integer::sum);
            String label;
            if (isPrivate(addr)) {
                label = "the house";
            } else {
                Set<String> names = who.getOrDefault(ip, Set.of());
                label = !owner.isEmpty() && names.contains(owner) ? "the house" : names.isEmpty() ? "someone new" : String.join(" / ", names);
            }
            readers.computeIfAbsent(label, k -> new java.util.HashSet<>()).add(ip);
        }
        JsonObject out = new JsonObject();
        if (views == 0) {
            out.addProperty("readers", 0);
            out.addProperty("page_views", 0);
            out.add("pages", new JsonObject());
            out.add("who", new JsonArray());
            return out;
        }
        int n = 0;
        for (Map.Entry<String, Set<String>> e : readers.entrySet()) n += e.getKey().equals("someone new") ? e.getValue().size() : 1;
        JsonArray whol = new JsonArray();
        new TreeSet<>(readers.keySet()).stream().filter(k -> !k.equals("someone new")).forEach(whol::add);
        if (readers.containsKey("someone new")) whol.add(readers.get("someone new").size() + " new");
        out.addProperty("readers", n);
        out.addProperty("page_views", views);
        JsonObject pg = new JsonObject();
        pages.forEach(pg::addProperty);
        out.add("pages", pg);
        out.add("who", whol);
        return out;
    }

    /** Page name for a path below the paper's prefix (trailing slashes already removed). */
    private static String page(String rel, String owner) {
        if (rel.isEmpty() || rel.equals("/index.html")) return "paper";
        if (rel.equals("/players.html")) return "player cards";
        if (rel.equals("/owner.html") || (!owner.isEmpty() && rel.equals("/" + owner.toLowerCase(Locale.ROOT) + ".html"))) return "owner's file";
        return null;
    }

    private static List<String> tail(Path f, int cap) {
        try {
            long size = Files.size(f);
            if (size <= cap) return Py.lines(f, cap, Integer.MAX_VALUE);
            try (var ch = Files.newByteChannel(f)) {
                ch.position(size - cap);
                java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(cap);
                while (b.hasRemaining() && ch.read(b) > 0) { }
                String s = new String(b.array(), 0, b.position(), java.nio.charset.StandardCharsets.UTF_8);
                List<String> l = new java.util.ArrayList<>(List.of(s.split("\r\n|\r|\n")));
                if (!l.isEmpty()) l.remove(0);   // first line is probably cut
                return l;
            }
        } catch (java.io.IOException e) {
            return List.of();
        }
    }

    private static final Pattern V4 = Pattern.compile("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern V6 = Pattern.compile("[0-9a-fA-F:.]+");

    /** Address bytes for a literal IPv4/IPv6 address, else null. Never does a DNS lookup. */
    static byte[] parseIp(String s) {
        try {
            if (V4.matcher(s).matches()) return InetAddress.getByName(s).getAddress();
            if (s.indexOf(':') >= 0 && V6.matcher(s).matches()) {
                InetAddress a = InetAddress.getByName(s);
                byte[] b = a.getAddress();
                if (b.length == 4) {   // Java folds ::ffff:a.b.c.d to IPv4; keep it IPv6 as Python does
                    byte[] m = new byte[16];
                    m[10] = (byte) 0xff;
                    m[11] = (byte) 0xff;
                    System.arraycopy(b, 0, m, 12, 4);
                    return m;
                }
                return b;
            }
        } catch (java.net.UnknownHostException e) {
            return null;
        }
        return null;
    }

    private static boolean loopback(byte[] a) {
        if (a.length == 4) return (a[0] & 0xff) == 127;
        return in(a, "0000:0000:0000:0000:0000:0000:0000:0001", 128);
    }

    /** Python 3.12 ipaddress is_private. */
    static boolean isPrivate(byte[] a) {
        String[][] nets = a.length == 4
                ? new String[][]{{"0.0.0.0", "8"}, {"10.0.0.0", "8"}, {"127.0.0.0", "8"}, {"169.254.0.0", "16"}, {"172.16.0.0", "12"},
                {"192.0.0.0", "29"}, {"192.0.0.170", "31"}, {"192.0.2.0", "24"}, {"192.168.0.0", "16"}, {"198.18.0.0", "15"},
                {"198.51.100.0", "24"}, {"203.0.113.0", "24"}, {"240.0.0.0", "4"}, {"255.255.255.255", "32"}}
                : new String[][]{{"::1", "128"}, {"::", "128"}, {"::ffff:0:0", "96"}, {"100::", "64"}, {"2001::", "23"},
                {"2001:db8::", "32"}, {"2001:10::", "28"}, {"fc00::", "7"}, {"fe80::", "10"}};
        for (String[] n : nets) if (in(a, n[0], Integer.parseInt(n[1]))) return true;
        return false;
    }

    private static boolean in(byte[] a, String net, int bits) {
        byte[] n;
        try {
            n = InetAddress.getByName(net).getAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
        if (n.length != a.length) {
            if (net.equals("::ffff:0:0")) return a.length == 16 && a[10] == (byte) 0xff && a[11] == (byte) 0xff && allZero(a, 10);
            return false;
        }
        for (int i = 0; i < bits; i++) {
            int bit = 7 - (i % 8);
            if (((a[i / 8] >> bit) & 1) != ((n[i / 8] >> bit) & 1)) return false;
        }
        return true;
    }

    private static boolean allZero(byte[] a, int upTo) {
        for (int i = 0; i < upTo; i++) if (a[i] != 0) return false;
        return true;
    }
}
