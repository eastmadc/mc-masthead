package io.github.eastmadc.masthead.web;

import io.github.eastmadc.masthead.facts.FactsBuilder;
import io.github.eastmadc.masthead.pipeline.Housekeeping;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * web.mode: builtin. A tiny read-only static file server for the published pages (plain sockets, one request per
 * connection), built so that slow or idle visitors cannot freeze it (review finding SEC-3):
 *
 * <ul>
 *   <li>The request line and headers must arrive within {@link #HEAD_TIMEOUT_MS} (5 s in total, not per read, so a
 *       visitor sending one byte at a time is cut off too) and fit in {@link #MAX_HEAD} bytes (else 431). Every
 *       connection is closed {@link #MAX_CONNECTION_MS} (15 s) after it was accepted, whatever it is doing, so
 *       a visitor who reads the answer slowly cannot hold a worker either.</li>
 *   <li>{@link #THREADS} workers and a queue of {@link #QUEUE}; past that a new connection is closed at once. One address
 *       may hold at most {@link #PER_ADDRESS} connections (the head deadline starts at accept), so one visitor can't
 *       fill the queue.</li>
 *   <li>GET and HEAD only (others: 405). Paths are percent-decoded, then any segment that is empty-dot, "..", hidden
 *       (starts with "."), or contains a backslash or NUL is refused; the resolved file's real path (symlinks followed)
 *       must stay inside the root's real path. Only known file types are served (html, css, js, png, json, svg, ico, jpg,
 *       webp, ttf, txt); everything else is 404, so stray files (logs, yml) are never published.</li>
 *   <li>Directories serve index.html; a directory without a trailing slash redirects (301) to the slash form.</li>
 *   <li>html: Cache-Control no-cache; other types max-age=300. Last-Modified / If-Modified-Since → 304.</li>
 *   <li>Access log in BlueMap's format, one line per request:
 *       {@code [YYYY-MM-DD HH:MM:SS][INFO] ADDR "GET /path HTTP/1.1" 200 OK} (query strings dropped). It must live
 *       outside the served root (the constructor refuses otherwise). At most {@link #LOG_DAILY_CAP} bytes per day, and
 *       {@link #LOG_ADDRESS_CAP} of them per address (so one visitor cannot use up the day and hide the real readers); the
 *       file is rotated to {@code .1} past {@link #LOG_MAX_BYTES}. It holds visitor addresses, so it is mode 600, lines
 *       older than {@link #LOG_KEEP_DAYS} days are removed by the daily tidy ({@link #pruneLog}), and with
 *       features.readership off there is no log at all ({@link #accessLog(Path, boolean, Logger)}).</li>
 * </ul>
 * For a big public server, a real web server ("folder" mode) is still the better choice.
 */
public final class WebServer {
    static final long LOG_DAILY_CAP = 4L << 20;
    static final long LOG_ADDRESS_CAP = 64L << 10;
    static final long LOG_MAX_BYTES = 16L << 20;
    /** A visitor's line (address, time, page) is kept this many days: the same as an address in ip-players.txt. */
    public static final int LOG_KEEP_DAYS = 60;
    private static final Object LOG_LOCK = new Object();   // one lock for the log's writer and pruneLog, so no line is lost between them
    static final int THREADS = 16, QUEUE = 64, PER_ADDRESS = 8;   // a browser opens up to 6 at once
    // 15 s in total, so a reader on a weak phone signal still gets the page and its font (about 12 KB/s is enough);
    // a slower reader gets the file cut short
    static final int HEAD_TIMEOUT_MS = 5_000, MAX_HEAD = 8 * 1024, MAX_CONNECTION_MS = 15_000;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneId.of("GMT"));
    static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("htm", "text/html; charset=utf-8"), Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"), Map.entry("json", "application/json; charset=utf-8"), Map.entry("png", "image/png"),
            Map.entry("svg", "image/svg+xml"), Map.entry("ico", "image/x-icon"), Map.entry("jpg", "image/jpeg"), Map.entry("webp", "image/webp"),
            Map.entry("ttf", "font/ttf"), Map.entry("txt", "text/plain; charset=utf-8"));   // the fonts and their OFL.txt

    private final Path root;
    private final String bind;
    private final int port;
    private final Path accessLog;
    private final ZoneId zone;
    private final Logger log;
    private ServerSocket server;
    private ThreadPoolExecutor pool;
    private ScheduledExecutorService reaper;
    private Thread acceptor;
    private final java.util.Set<Socket> live = java.util.concurrent.ConcurrentHashMap.newKeySet();   // open connections, closed by stop()
    private final java.util.concurrent.ConcurrentHashMap<java.net.InetAddress, Integer> perAddress = new java.util.concurrent.ConcurrentHashMap<>();
    private Path rootReal;
    private LocalDate logDay;
    private long logDayBytes;
    private final Map<String, Long> logAddressBytes = new java.util.HashMap<>();   // today's logged bytes per address

    /**
     * @param root      folder to serve (created if missing), e.g. {@code <dataFolder>/web}
     * @param bind      address to listen on ("0.0.0.0" = all)
     * @param port      TCP port (0 = any free port, see {@link #port()})
     * @param accessLog access log file, e.g. {@code <dataFolder>/web-access.log}; null = no log. Must be outside root.
     * @param zone      time zone for log timestamps (the edition window is computed in the same zone)
     * @param log       plugin logger for errors
     */
    public WebServer(Path root, String bind, int port, Path accessLog, ZoneId zone, Logger log) {
        this.root = root.toAbsolutePath().normalize();
        this.bind = bind == null || bind.isBlank() ? "0.0.0.0" : bind.strip();
        this.port = port;
        this.accessLog = accessLog == null ? null : accessLog.toAbsolutePath().normalize();
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
        this.log = log;
        if (this.accessLog != null && this.accessLog.startsWith(this.root))
            throw new IllegalArgumentException("the web access log must not be inside the served folder (it holds visitor addresses)");
    }

    public synchronized void start() throws IOException {
        if (server != null) return;
        Files.createDirectories(root);
        rootReal = root.toRealPath();
        if (accessLog != null) {
            Files.createDirectories(accessLog.getParent());
            if (accessLog.toAbsolutePath().startsWith(rootReal))
                throw new IllegalArgumentException("the web access log must not be inside the served folder");
        }
        AtomicInteger n = new AtomicInteger();
        pool = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE), r -> daemon(r, "masthead-web-" + n.incrementAndGet()));
        pool.allowCoreThreadTimeOut(true);
        reaper = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "masthead-web-reaper"));
        ServerSocket ss = new ServerSocket();
        try {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(bind, port), 50);
        } catch (IOException e) {
            ss.close();
            pool.shutdownNow();
            reaper.shutdownNow();
            throw e;
        }
        server = ss;
        acceptor = daemon(() -> accept(ss), "masthead-web-accept");
        acceptor.start();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private void accept(ServerSocket ss) {
        ThreadPoolExecutor p = pool;
        ScheduledExecutorService rp = reaper;
        while (!ss.isClosed()) {
            Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                if (ss.isClosed()) return;
                continue;
            }
            long accepted = System.nanoTime();
            java.net.InetAddress addr = s.getInetAddress();
            if (perAddress.merge(addr, 1, Integer::sum) > PER_ADDRESS) {   // one visitor holding too many: drop the new one
                release(addr);
                quietClose(s);
                continue;
            }
            try {
                live.add(s);
                ScheduledFuture<?> cut = rp.schedule(() -> quietClose(s), MAX_CONNECTION_MS, TimeUnit.MILLISECONDS);
                p.execute(() -> {
                    try {
                        handle(s, accepted);
                    } catch (Throwable t) {   // nothing a visitor sends may kill a worker or print to the console
                        if (log != null) log.log(Level.FINE, "web request failed", t);
                    } finally {
                        cut.cancel(false); quietClose(s); live.remove(s); release(addr);
                    }
                });
            } catch (RejectedExecutionException e) {   // every worker busy and the queue full (or stopping): drop it
                quietClose(s);
                live.remove(s);
                release(addr);
            }
        }
    }

    private void release(java.net.InetAddress addr) {
        perAddress.computeIfPresent(addr, (k, v) -> v <= 1 ? null : v - 1);
    }

    private static void quietClose(Socket s) {
        try { s.close(); } catch (IOException ignored) { }
    }

    /** The port actually bound (useful with port 0). */
    public synchronized int port() {
        return server == null ? port : server.getLocalPort();
    }

    public synchronized void stop() {
        if (server == null) return;
        quietCloseServer(server);
        pool.shutdownNow();
        reaper.shutdownNow();   // this drops the cut-offs, so close every open connection here
        for (Socket s : live) quietClose(s);   // a worker blocked reading or writing only stops when its socket closes
        live.clear();
        perAddress.clear();   // queued connections dropped by shutdownNow() never release their count
        try {
            // the port is only free once the accepting thread has left accept() (the JDK closes it then), so a reload
            // that starts again at once can bind it
            acceptor.join(3000);
            pool.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        server = null;
        acceptor = null;
        pool = null;
        reaper = null;
    }

    private static void quietCloseServer(ServerSocket s) {
        try { s.close(); } catch (IOException ignored) { }
    }

    /** One parsed request: method, the target as sent, protocol, headers (lower-case names). */
    record Request(String method, String target, String protocol, Map<String, String> headers) {}

    /**
     * Reads the request line and headers: all of it within HEAD_TIMEOUT_MS, at most MAX_HEAD bytes. Returns null when
     * the visitor was too slow or closed the connection; throws HeadTooLarge past MAX_HEAD.
     */
    static Request readHead(Socket s) throws IOException {
        return readHead(s, System.nanoTime());
    }

    /** The same, with the 5 s counted from {@code accepted} (System.nanoTime() when the connection was accepted). */
    static Request readHead(Socket s, long accepted) throws IOException {
        long deadline = accepted + TimeUnit.MILLISECONDS.toNanos(HEAD_TIMEOUT_MS);
        InputStream in = new java.io.BufferedInputStream(s.getInputStream(), 2048);
        byte[] buf = new byte[MAX_HEAD];
        int len = 0;
        while (true) {
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (left <= 0) return null;
            s.setSoTimeout((int) Math.max(1, left));
            int c;
            try {
                c = in.read();
            } catch (SocketTimeoutException e) {
                return null;
            }
            if (c < 0) return null;
            if (len >= MAX_HEAD) throw new HeadTooLarge();
            buf[len++] = (byte) c;
            // the head ends at an empty line: "\n\n" or "\n\r\n"
            if (c == '\n' && len >= 2 && (buf[len - 2] == '\n' || (len >= 3 && buf[len - 2] == '\r' && buf[len - 3] == '\n'))) break;
        }
        String[] lines = new String(buf, 0, len, StandardCharsets.ISO_8859_1).split("\r?\n");
        if (lines.length == 0) return new Request("", "", "", Map.of());   // only empty lines: 400
        String[] first = lines[0].split(" ", -1);
        if (first.length != 3 || first[0].isEmpty() || first[1].isEmpty() || !first[2].startsWith("HTTP/")) return new Request("", "", "", Map.of());
        Map<String, String> h = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) h.putIfAbsent(lines[i].substring(0, colon).strip().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).strip());
        }
        return new Request(first[0], first[1], first[2], h);
    }

    static final class HeadTooLarge extends IOException {
        HeadTooLarge() { super("request head too large"); }
    }

    /** One connection: read the request, answer, close. */
    private void handle(Socket s, long accepted) {
        Request req;
        try {
            req = readHead(s, accepted);
        } catch (HeadTooLarge e) {
            try { respond(s, null, 431, "text/plain; charset=utf-8", "Request header too large\n", Map.of()); } catch (IOException ignored) { }
            return;
        } catch (IOException e) {
            return;
        }
        if (req == null) return;   // too slow, or nothing sent
        int status = 500;
        String rawPath = req.target();
        try {
            if (req.method().isEmpty()) { status = respond(s, req, 400, "text/plain; charset=utf-8", "Bad request\n", Map.of()); return; }
            URI uri;
            try {
                uri = new URI(req.target());
                if (uri.getRawPath() != null) rawPath = uri.getRawPath();
            } catch (URISyntaxException e) {
                int q = rawPath.indexOf('?');
                if (q >= 0) rawPath = rawPath.substring(0, q);
                status = respond(s, req, 400, "text/plain; charset=utf-8", "Bad request\n", Map.of());
                return;
            }
            if (!req.method().equals("GET") && !req.method().equals("HEAD")) {
                status = respond(s, req, 405, "text/plain; charset=utf-8", "Method not allowed\n", Map.of("Allow", "GET, HEAD"));
                return;
            }
            Resolved r = resolve(uri);
            if (r == null) {
                status = respond(s, req, 404, "text/plain; charset=utf-8", "Not found\n", Map.of());
                return;
            }
            if (r.redirect != null) {
                status = respond(s, req, 301, "text/plain; charset=utf-8", "Moved\n", Map.of("Location", r.redirect));
                return;
            }
            status = serveFile(s, req, r.file);
        } catch (IOException | RuntimeException e) {
            if (log != null) log.log(Level.FINE, "web request failed", e);
        } finally {
            access(s, req, rawPath, status);
        }
    }

    record Resolved(Path file, String redirect) {}

    /** Maps a request URI to a real file inside the root, a redirect, or null (404). */
    Resolved resolve(URI uri) throws IOException {
        String path = uri.getPath();   // percent-decoded once
        if (path == null || !path.startsWith("/") || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0) return null;
        StringBuilder rel = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            if (seg.startsWith(".")) return null;            // "..", ".", hidden files
            if (rel.length() > 0) rel.append('/');
            rel.append(seg);
        }
        Path p = root.resolve(rel.toString()).normalize();
        if (!p.startsWith(root)) return null;
        Path real;
        try {
            real = p.toRealPath();
        } catch (IOException e) {   // not there, under a file ("/index.html/x"), or a name the disk refuses (too long)
            return null;
        }
        if (!real.startsWith(rootReal)) return null;            // a symlink pointing out of the root
        if (Files.isDirectory(real)) {
            if (!path.endsWith("/")) {
                try {
                    return new Resolved(null, new URI(null, null, "/" + rel + (rel.length() > 0 ? "/" : ""), null).toASCIIString());
                } catch (java.net.URISyntaxException e) {
                    return null;
                }
            }
            Path index = real.resolve("index.html");
            if (!Files.isRegularFile(index)) return null;
            real = index.toRealPath();
            if (!real.startsWith(rootReal)) return null;
        } else if (path.endsWith("/")) {
            return null;   // "/index.html/" is not a folder: served there, the page's own links would break
        }
        if (!Files.isRegularFile(real) || type(real) == null) return null;
        return new Resolved(real, null);
    }

    static String type(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? null : TYPES.get(n.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private int serveFile(Socket s, Request req, Path file) throws IOException {
        String type = type(file);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Cache-Control", type.startsWith("text/html") ? "no-cache" : "max-age=300");
        Instant modified = Files.getLastModifiedTime(file).toInstant().truncatedTo(ChronoUnit.SECONDS);
        h.put("Last-Modified", HTTP_DATE.format(modified));
        String ims = req.headers().get("if-modified-since");
        if (ims != null) {
            try {
                Instant since = ZonedDateTime.parse(ims.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                if (!modified.isAfter(since)) {
                    writeHead(s, 304, null, -1, h);
                    return 304;
                }
            } catch (RuntimeException ignored) {
                // unparseable date: send the file
            }
        }
        long size = Files.size(file);
        OutputStream out = writeHead(s, 200, type, size, h);
        if (!req.method().equals("HEAD")) {
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                in.transferTo(out);
            }
            out.flush();
        }
        finish(s, req);
        return 200;
    }

    /** A small text answer (body left out for HEAD). Returns the status. */
    private static int respond(Socket s, Request req, int status, String type, String body, Map<String, String> extra) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> h = new LinkedHashMap<>(extra);
        h.put("Cache-Control", "no-cache");
        OutputStream out = writeHead(s, status, type, b.length, h);
        if (req == null || !req.method().equals("HEAD")) out.write(b);
        out.flush();
        finish(s, req);
        return status;
    }

    /** Status line and headers. length < 0 = no Content-Length (304). */
    private static OutputStream writeHead(Socket s, int status, String type, long length, Map<String, String> headers) throws IOException {
        StringBuilder b = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (type != null) b.append("Content-Type: ").append(type).append("\r\n");
        if (length >= 0) b.append("Content-Length: ").append(length).append("\r\n");
        b.append("X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nX-Robots-Tag: noindex, noarchive\r\n");
        headers.forEach((k, v) -> b.append(k).append(": ").append(v).append("\r\n"));
        b.append("Connection: close\r\n\r\n");
        OutputStream out = s.getOutputStream();
        out.write(b.toString().getBytes(StandardCharsets.ISO_8859_1));
        return out;
    }

    /**
     * Ends the answer cleanly: half-close, then (only if the visitor sent a body, e.g. a POST, or a head too big to read)
     * read and drop up to 64 KB of it for at most a second in total (a body sent a byte at a time doesn't stretch it), so
     * closing never resets the connection before the visitor has read the answer.
     */
    private static void finish(Socket s, Request req) {
        try {
            s.getOutputStream().flush();
            s.shutdownOutput();
            if (req != null && !req.headers().containsKey("content-length") && !req.headers().containsKey("transfer-encoding")) return;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            InputStream in = s.getInputStream();
            byte[] junk = new byte[4096];
            for (int total = 0; total < 64 * 1024; ) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) break;
                s.setSoTimeout((int) left);
                int n = in.read(junk);
                if (n < 0) break;
                total += n;
            }
        } catch (IOException ignored) {
            // the answer is out; nothing more to do
        }
    }

    static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 301 -> "Moved Permanently";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 431 -> "Request Header Fields Too Large";
            default -> "Internal Server Error";
        };
    }

    /** Keeps printable ASCII except quote/backslash, so a request can't forge or break log lines. */
    static String clean(String s, int max) {
        if (s == null) return "-";
        StringBuilder b = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char c = s.charAt(i);
            b.append(c > 0x20 && c < 0x7f && c != '"' && c != '\\' ? c : '?');
        }
        return b.toString();
    }

    /** Whose share of the log a line counts against: an IPv6 visitor has a whole /64 to pick addresses from, so that network is one visitor. */
    static String logShareKey(java.net.InetAddress a) {
        if (a == null) return "-";
        byte[] b = a.getAddress();
        return b.length == 16 ? java.util.HexFormat.of().formatHex(b, 0, 8) : a.getHostAddress();
    }

    private void access(Socket s, Request req, String rawPath, int status) {
        if (accessLog == null || req == null) return;
        String addr = s.getInetAddress() == null ? "-" : s.getInetAddress().getHostAddress();
        String share = logShareKey(s.getInetAddress());
        ZonedDateTime now = ZonedDateTime.now(zone);
        String line = "[" + now.format(LOG_TIME) + "][INFO] " + clean(addr, 64) + " \"" + clean(req.method(), 10) + " "
                + clean(rawPath, 300) + " " + clean(req.protocol(), 16) + "\" " + status + " " + reason(status) + "\n";
        byte[] b = line.getBytes(StandardCharsets.US_ASCII);
        synchronized (LOG_LOCK) {
            LocalDate day = now.toLocalDate();
            if (!day.equals(logDay)) {
                logDay = day;
                logDayBytes = 0;
                logAddressBytes.clear();
            }
            if (logDayBytes + b.length > LOG_DAILY_CAP || logAddressBytes.getOrDefault(share, 0L) + b.length > LOG_ADDRESS_CAP) return;
            try {
                if (Files.exists(accessLog) && Files.size(accessLog) > LOG_MAX_BYTES)
                    Files.move(accessLog, accessLog.resolveSibling(accessLog.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
                if (!Files.exists(accessLog)) Housekeeping.createPrivate(accessLog);   // mode 600 before an address is in it
                Files.write(accessLog, b, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                logDayBytes += b.length;
                logAddressBytes.merge(share, (long) b.length, Long::sum);
            } catch (IOException e) {
                if (log != null) log.log(Level.FINE, "web access log write failed", e);
            }
        }
    }

    /**
     * Where the built-in server logs visits: data/web-access.log. Readership off: null (no log), and a log from earlier days
     * (with its rotated .1) is deleted, because nothing reads it then and it holds visitor addresses.
     */
    public static Path accessLog(Path data, boolean readership, Logger log) {
        Path f = data.resolve(FactsBuilder.BUILTIN_ACCESS_LOG);
        if (readership) return f;
        synchronized (LOG_LOCK) {
            for (Path p : new Path[]{f, f.resolveSibling(f.getFileName() + ".1")}) {
                try {
                    if (Files.deleteIfExists(p) && log != null) log.info("Readership is off: deleted " + p.getFileName() + " (visitor addresses).");
                } catch (IOException e) { if (log != null) log.warning(p.getFileName() + ": " + e); }
            }
        }
        return null;
    }

    /**
     * The age limit: lines dated before cut leave the log and its rotated .1 (a line with no date goes too). A file left
     * empty is deleted. Run from the daily tidy, so it also works when no visitor comes or the built-in server is off.
     */
    public static void pruneLog(Path accessLog, LocalDate cut) throws IOException {
        String c = "[" + cut;
        synchronized (LOG_LOCK) {
            for (Path p : new Path[]{accessLog, accessLog.resolveSibling(accessLog.getFileName() + ".1")}) {
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
                java.util.List<String> lines = Files.readAllLines(p, StandardCharsets.ISO_8859_1);   // at most about 16 MB
                StringBuilder keep = new StringBuilder();
                int kept = 0;
                for (String l : lines) if (l.matches("\\[\\d{4}-\\d\\d-\\d\\d .*") && l.substring(0, 11).compareTo(c) >= 0) { keep.append(l).append('\n'); kept++; }
                if (kept == lines.size()) continue;
                if (kept == 0) Files.delete(p);
                else Housekeeping.writePrivate(p, keep.toString());
            }
        }
    }
}
