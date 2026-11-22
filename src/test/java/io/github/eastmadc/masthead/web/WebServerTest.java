package io.github.eastmadc.masthead.web;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/** Plain-main test: serves a temp folder and checks paths, methods, types, caching and the access log. */
public final class WebServerTest {
    static int port;

    public static void main(String[] args) throws Exception {
        Path data = Files.createTempDirectory("cp-web-test");
        Path web = data.resolve("web");
        Files.createDirectories(web.resolve("sub"));
        Files.writeString(web.resolve("index.html"), "<h1>The Coop Courier</h1>");
        Files.writeString(web.resolve("players.html"), "<p>players</p>");
        Files.writeString(web.resolve("style.css"), "body{}");
        Files.writeString(web.resolve("app.js"), "1");
        Files.writeString(web.resolve("today.json"), "{}");
        Files.write(web.resolve("qr.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        Files.writeString(web.resolve("sub/index.html"), "sub");
        Files.createDirectories(web.resolve("fonts/f"));
        Files.write(web.resolve("fonts/f/Font[wght].ttf"), new byte[]{0, 1, 0, 0});
        Files.writeString(web.resolve("fonts/f/OFL.txt"), "license");
        Files.writeString(web.resolve("notes.yml"), "secret: 1");
        Files.writeString(web.resolve(".hidden.html"), "hidden");
        Files.writeString(data.resolve("secret.html"), "OUTSIDE");
        Files.writeString(data.resolve("config.yml"), "api-key: nope");
        Files.createSymbolicLink(web.resolve("escape.html"), data.resolve("secret.html"));
        Files.createSymbolicLink(web.resolve("updir"), data);
        Files.writeString(web.resolve("target.html"), "inside");
        Files.createSymbolicLink(web.resolve("inside-link.html"), web.resolve("target.html"));
        Path accessLog = data.resolve("web-access.log");

        try {
            new WebServer(web, "127.0.0.1", 0, web.resolve("access.log"), ZoneId.systemDefault(), null);
            throw new AssertionError("log inside root must be refused");
        } catch (IllegalArgumentException expected) {
            System.out.println("ok access log inside root refused");
        }

        WebServer ws = new WebServer(web, "127.0.0.1", 0, accessLog, ZoneId.systemDefault(), null);
        ws.start();
        port = ws.port();
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            HttpResponse<String> r = get(http, "/");
            check(r.statusCode() == 200 && r.body().contains("Coop Courier"), "index at /");
            check(r.headers().firstValue("content-type").orElse("").equals("text/html; charset=utf-8"), "html type");
            check(r.headers().firstValue("cache-control").orElse("").equals("no-cache"), "html no-cache");
            for (String p : List.of("/", "/nope.html", "/sub"))   // M4: on every answer, also a 404 and a redirect
                check(get(http, p).headers().firstValue("x-robots-tag").orElse("").equals("noindex, noarchive"), "X-Robots-Tag on " + p);
            check(get(http, "/index.html").statusCode() == 200, "/index.html");
            check(type(http, "/style.css").startsWith("text/css"), "css type");
            check(type(http, "/app.js").startsWith("text/javascript"), "js type");
            check(type(http, "/today.json").startsWith("application/json"), "json type");
            check(type(http, "/qr.png").equals("image/png"), "png type");
            check(get(http, "/qr.png").headers().firstValue("cache-control").orElse("").equals("max-age=300"), "png max-age");
            check(type(http, "/fonts/f/Font%5Bwght%5D.ttf").equals("font/ttf"), "ttf type, [ ] percent-encoded as the pages link it");
            check(type(http, "/fonts/f/OFL.txt").startsWith("text/plain") && get(http, "/fonts/f/OFL.txt").body().equals("license"), "OFL.txt served");
            System.out.println("ok content types + cache headers");

            HttpResponse<String> red = get(http, "/sub");
            check(red.statusCode() == 301 && red.headers().firstValue("location").orElse("").equals("/sub/"), "dir redirect");
            check(get(http, "/sub/").body().equals("sub"), "sub index");
            System.out.println("ok directory index + redirect");

            check(get(http, "/nope.html").statusCode() == 404, "404");
            check(get(http, "/notes.yml").statusCode() == 404, "unknown type not served");
            check(get(http, "/.hidden.html").statusCode() == 404, "hidden not served");
            check(get(http, "/escape.html").statusCode() == 404, "symlink escaping root");
            check(get(http, "/updir/secret.html").statusCode() == 404, "dir symlink escaping root");
            check(get(http, "/inside-link.html").body().equals("inside"), "symlink inside root ok");
            System.out.println("ok 404s + symlinks");

            // a file is not a folder, and a name the disk refuses is just not there: each gets a 404, never silence
            for (String t : List.of("/index.html/x", "/index.html/", "/sub/index.html/", "/" + "a".repeat(300) + ".html", "/" + "a".repeat(300) + "/x.html", "/sub/" + "a".repeat(300))) {
                String resp = raw("GET " + t + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
                check(resp.startsWith("HTTP/1.1 404"), "404 for " + t + ": " + resp.lines().findFirst().orElse("(no answer)"));
            }
            System.out.println("ok 404 for a path under a file and for an over-long name");

            // traversal: sent raw over a socket so no client normalizes them
            for (String t : List.of("/../secret.html", "/sub/../../secret.html", "/%2e%2e/secret.html", "/%2E%2E/config.yml",
                    "/sub/%2e%2e/%2e%2e/secret.html", "/..%2fsecret.html", "/%2e%2e%2fsecret.html", "/..\\secret.html",
                    "/%5c..%5csecret.html", "//etc/passwd", "/etc/passwd", "/" + data.resolve("secret.html"),
                    "/%252e%252e/secret.html", "/web-access.log", "/../web-access.log", "/index.html%00.png",
                    "http://127.0.0.1/../secret.html")) {
                String resp = raw("GET " + t + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
                check(!resp.contains("OUTSIDE") && !resp.contains("api-key") && !resp.contains("root:"), "traversal leaked: " + t);
                check(!resp.startsWith("HTTP/1.1 200"), "traversal got 200: " + t + " -> " + resp.lines().findFirst().orElse(""));
            }
            System.out.println("ok traversal attempts refused");

            HttpResponse<String> head = http.send(HttpRequest.newBuilder(URI.create(base() + "/index.html"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            check(head.statusCode() == 200 && head.body().isEmpty(), "HEAD no body");
            String headRaw = raw("HEAD /index.html HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            check(headRaw.toLowerCase().contains("content-length: 25") && !headRaw.contains("<h1>"), "HEAD content-length: " + headRaw);
            System.out.println("ok HEAD");

            HttpResponse<String> post = http.send(HttpRequest.newBuilder(URI.create(base() + "/index.html"))
                    .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());
            check(post.statusCode() == 405 && post.headers().firstValue("allow").orElse("").equals("GET, HEAD"), "POST 405");
            System.out.println("ok POST -> 405");

            String lm = get(http, "/index.html").headers().firstValue("last-modified").orElseThrow();
            HttpResponse<String> cond = http.send(HttpRequest.newBuilder(URI.create(base() + "/index.html"))
                    .header("If-Modified-Since", lm).build(), HttpResponse.BodyHandlers.ofString());
            check(cond.statusCode() == 304, "304 on If-Modified-Since");
            System.out.println("ok 304");

            // log injection attempt
            raw("GET /index.html?x=\"evil HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");

            // 1A.4 (T11): idle and slow visitors cannot freeze it. 6 idle sockets and 6 that send one byte at a time:
            // a normal visitor is still answered at once, and each slow one is cut off within about 5 s.
            List<Socket> slow = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {   // from another address: one address may hold only PER_ADDRESS connections
                Socket x = new Socket("127.0.0.1", port, java.net.InetAddress.getByName("127.0.0.2"), 0);
                if (i % 2 == 1) { x.getOutputStream().write("GET / HT".getBytes(StandardCharsets.ISO_8859_1)); x.getOutputStream().flush(); }
                slow.add(x);
            }
            Thread.sleep(300);
            long t0 = System.nanoTime();
            check(get(http, "/").statusCode() == 200, "answered while slow sockets are open");
            check((System.nanoTime() - t0) / 1_000_000 < 2000, "answered at once");
            Socket drip = slow.get(1);
            drip.setSoTimeout(9000);
            long t1 = System.nanoTime();
            for (int i = 0; i < 12; i++) {   // keeps sending a byte every half second; the 5 s limit is for the whole head
                try { drip.getOutputStream().write('x'); drip.getOutputStream().flush(); } catch (java.io.IOException closed) { break; }
                Thread.sleep(500);
            }
            int got;
            try { got = drip.getInputStream().read(); } catch (java.io.IOException e) { got = -1; }
            long held = (System.nanoTime() - t1) / 1_000_000;
            check(got == -1 && held < 8000, "a drip-fed request is cut off (" + held + " ms)");
            for (Socket x : slow) x.close();
            System.out.println("ok slow visitors cut off after " + held + " ms; others answered at once");
            // a head over 8 KB is refused
            String big = raw("GET / HTTP/1.1\r\nHost: x\r\nX-Big: " + "a".repeat(9000) + "\r\n\r\n");
            check(big.startsWith("HTTP/1.1 431"), "huge head refused: " + big.lines().findFirst().orElse(""));
            System.out.println("ok request head capped");
        } finally {
            ws.stop();
        }
        check(port > 0, "port");
        // /masthead reload stops and starts it on the same port at once (on one CPU the old port used to be still taken)
        for (int i = 0; i < 5; i++) {
            WebServer again = new WebServer(web, "127.0.0.1", port, null, ZoneId.systemDefault(), null);
            again.start();
            again.stop();
        }
        System.out.println("ok stop then start on the same port, 5 times");
        try {
            new Socket("127.0.0.1", port).close();
            throw new AssertionError("still listening after stop()");
        } catch (java.net.ConnectException expected) {
            System.out.println("ok stop() closes the port");
        }

        List<String> lines = Files.readAllLines(accessLog);
        String fmt = "\\[\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\]\\[INFO\\] 127\\.0\\.0\\.1 \"(GET|HEAD|POST) \\S+ HTTP/1\\.1\" \\d{3} [A-Za-z ]+";
        for (String l : lines) check(l.matches(fmt), "log line format: " + l);
        check(lines.stream().anyMatch(l -> l.contains("\"GET / HTTP/1.1\" 200 OK")), "GET / logged");
        check(lines.stream().anyMatch(l -> l.contains("\"GET /nope.html HTTP/1.1\" 404 Not Found")), "404 logged");
        check(lines.stream().anyMatch(l -> l.contains("\"HEAD /index.html HTTP/1.1\" 200 OK")), "HEAD logged");
        check(lines.stream().anyMatch(l -> l.contains("\" 304 Not Modified")), "304 logged");
        // the reference Python script's readership regex must match a GET line
        check(lines.stream().anyMatch(l -> l.matches("\\[(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d)\\]\\[\\w+\\] (\\S+) \"GET (/\\S*) HTTP/[\\d.]+\" (?:200|304).*")),
                "BlueMap/Python regex matches");
        System.out.println("ok access log: " + lines.size() + " lines, e.g. " + lines.get(0));
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
            check(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(accessLog)).equals("rw-------"), "the log is private from its first line");
        logRules(data.resolve("logs"));
        logShare(data);
        stopWithStuckClient(data);
        slowReaders(data);
        review2(data);
        System.out.println("WebServerTest PASS");
    }

    /** The log holds visitor addresses: none with readership off (old files deleted), and no line older than the age limit. */
    static void logRules(Path data) throws Exception {
        Files.createDirectories(data);
        Path log = data.resolve("web-access.log"), rolled = data.resolve("web-access.log.1");
        String old = "[2031-01-08 09:00:00][INFO] 203.0.113.9 \"GET /index.html HTTP/1.1\" 200 OK\n";
        String fresh = "[2031-03-10 09:00:00][INFO] 203.0.113.9 \"GET /players.html HTTP/1.1\" 200 OK\n";
        Files.writeString(log, old + "no date on this line 203.0.113.9\n" + fresh);
        Files.writeString(rolled, old);
        java.time.LocalDate cut = java.time.LocalDate.of(2031, 3, 10);
        WebServer.pruneLog(log, cut);
        check(Files.readString(log).equals(fresh), "lines older than the limit leave the log, the line from the cut day stays: " + Files.readString(log));
        check(!Files.exists(rolled), "a rotated log with only old lines is deleted");
        WebServer.pruneLog(log, cut.plusDays(1));
        check(!Files.exists(log), "a log left empty is deleted");
        WebServer.pruneLog(log, cut);   // no log: nothing to do
        Files.writeString(log, fresh);
        Files.writeString(rolled, old);
        check(log.equals(WebServer.accessLog(data, true, null)) && Files.exists(log) && Files.exists(rolled), "readership on: the log is used and kept");
        check(WebServer.accessLog(data, false, null) == null && !Files.exists(log) && !Files.exists(rolled), "readership off: no log, and the old ones are deleted");
        System.out.println("ok access log: age limit, and none with readership off");
    }

    /** PT-1: a client that stops reading a big file: stop() returns at once and leaves no worker behind. */
    static void stopWithStuckClient(Path data) throws Exception {
        Path root = Files.createDirectories(data.resolve("big"));
        Files.write(root.resolve("big.png"), new byte[16 << 20]);
        WebServer w = new WebServer(root, "127.0.0.1", 0, null, ZoneId.systemDefault(), null);
        w.start();
        try (Socket c = new Socket()) {
            c.setReceiveBufferSize(4096);
            c.connect(new java.net.InetSocketAddress("127.0.0.1", w.port()));
            c.getOutputStream().write("GET /big.png HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            c.getOutputStream().flush();
            Thread.sleep(1500);   // the worker is now blocked writing; the client reads nothing
            long t0 = System.nanoTime();
            w.stop();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check(ms < 500, "PT-1: stop() returns at once with a stuck client (" + ms + " ms)");
            Thread.sleep(1000);
            List<String> left = Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive() && t.getName().startsWith("masthead-web-")).map(Thread::getName).toList();
            check(left.isEmpty(), "PT-1: no web worker left after stop(): " + left);
        }
        System.out.println("ok stop() with a stuck client");
    }

    /**
     * Visitors who read the answer very slowly: one on every worker. Each is cut off 15 s after it connected, so the next
     * ordinary reader is answered at once.
     */
    static void slowReaders(Path data) throws Exception {
        Path root = Files.createDirectories(data.resolve("slow"));
        Files.write(root.resolve("big.png"), new byte[16 << 20]);
        Files.writeString(root.resolve("index.html"), "hi");
        WebServer w = new WebServer(root, "127.0.0.1", 0, null, ZoneId.systemDefault(), null);
        w.start();
        List<Socket> slow = new java.util.ArrayList<>();
        try {
            long t0 = System.nanoTime();
            for (int i = 0; i < WebServer.THREADS; i++) {   // from two other addresses: one address may hold only PER_ADDRESS
                Socket c = new Socket();
                c.setReceiveBufferSize(4096);
                c.bind(new java.net.InetSocketAddress(i % 2 == 0 ? "127.0.0.2" : "127.0.0.3", 0));
                c.connect(new java.net.InetSocketAddress("127.0.0.1", w.port()));
                c.setSoTimeout(100);
                c.getOutputStream().write("GET /big.png HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                c.getOutputStream().flush();
                slow.add(c);
            }
            boolean allBusy = false;
            while ((System.nanoTime() - t0) / 1_000_000 < 16000) {   // the promised 15 s and one more (a fixed number, so a longer limit fails here)
                for (Socket c : slow) try { c.getInputStream().read(); } catch (java.io.IOException cutOff) { }   // one byte each, a few times a second
                Thread.sleep(250);
                if (!allBusy) {   // the test is only worth something if the slow readers really hold every worker
                    try (Socket c = new Socket("127.0.0.1", w.port())) {
                        c.setSoTimeout(1000);
                        c.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                        c.getInputStream().read();
                    } catch (java.net.SocketTimeoutException waiting) {
                        allBusy = true;
                    }
                    check(allBusy, "the slow readers hold every worker at first");
                }
            }
            long t1 = System.nanoTime();
            String r = rawTo(w.port(), null, "GET / HTTP/1.1\r\nHost: x\r\n\r\n");
            long ms = (System.nanoTime() - t1) / 1_000_000;
            check(r.startsWith("HTTP/1.1 200") && ms < 1000, "slow readers are cut off after 15 s, the next reader is answered at once ("
                    + ms + " ms): " + r.lines().findFirst().orElse("(no answer)"));
            System.out.println("ok slow readers cut off; the next reader answered in " + ms + " ms");
        } finally {
            for (Socket c : slow) c.close();
            w.stop();
        }
    }

    /** Review round 2: an empty head, one address holding many sockets, a body sent a byte at a time. */
    static void review2(Path data) throws Exception {
        java.util.List<String> died = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Thread.UncaughtExceptionHandler was = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> died.add(t.getName() + ": " + e));
        Path root = Files.createDirectories(data.resolve("r2"));
        Files.writeString(root.resolve("index.html"), "hi");
        WebServer w = new WebServer(root, "127.0.0.1", 0, null, ZoneId.systemDefault(), null);
        w.start();
        int p = w.port();
        try {
            // PT-R2-1: "\n\n" is a 400, and no worker dies
            for (int i = 0; i < 3; i++) {
                String r = rawTo(p, null, "\n\n");
                check(r.startsWith("HTTP/1.1 400"), "PT-R2-1: empty head -> 400: " + r.lines().findFirst().orElse("(nothing)"));
            }
            Thread.sleep(300);
            check(died.isEmpty(), "PT-R2-1: a worker died: " + died);
            System.out.println("ok empty head -> 400, no worker died");

            // PT-R2-2: 90 idle sockets from one address can't lock out another; the count is given back on close
            List<Socket> idle = new java.util.ArrayList<>();
            for (int i = 0; i < 90; i++) idle.add(new Socket("127.0.0.1", p, java.net.InetAddress.getByName("127.0.0.2"), 0));
            Thread.sleep(300);
            long t0 = System.nanoTime();
            String r = rawTo(p, null, "GET / HTTP/1.1\r\nHost: x\r\n\r\n");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check(r.startsWith("HTTP/1.1 200") && ms < 1000, "PT-R2-2: another address answered at once (" + ms + " ms): " + r.lines().findFirst().orElse("(closed)"));
            for (Socket x : idle) x.close();
            Thread.sleep(500);
            r = rawTo(p, "127.0.0.2", "GET / HTTP/1.1\r\nHost: x\r\n\r\n");
            check(r.startsWith("HTTP/1.1 200"), "PT-R2-2: the same address is answered once its sockets close: " + r.lines().findFirst().orElse("(closed)"));
            System.out.println("ok one address can't fill the queue (" + ms + " ms)");

            // PT-R2-2: a body sent a byte at a time doesn't hold a worker past ~1 s after the answer
            try (Socket c = new Socket("127.0.0.1", p)) {
                c.setSoTimeout(15000);
                OutputStream o = c.getOutputStream();
                o.write("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 100000\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                o.flush();
                String answer = new String(c.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);   // ends at the server's half-close
                check(answer.startsWith("HTTP/1.1 405"), "PT-R2-2: POST answered: " + answer.lines().findFirst().orElse(""));
                long t1 = System.nanoTime(), held = -1;
                for (int i = 0; i < 25 && held < 0; i++) {   // a byte every 300 ms; a write fails once the server has really closed
                    try { o.write('x'); o.flush(); Thread.sleep(300); } catch (java.io.IOException closed) { held = (System.nanoTime() - t1) / 1_000_000; }
                }
                check(held >= 0 && held < 3000, "PT-R2-2: a body sent a byte at a time is cut off (" + held + " ms)");
                System.out.println("ok trickled body cut off after " + held + " ms");
            }
        } finally {
            w.stop();
            Thread.setDefaultUncaughtExceptionHandler(was);
        }
    }

    /** One address cannot use up the day's access log (the reader count comes from it): each gets LOG_ADDRESS_CAP bytes a day. */
    static void logShare(Path data) throws Exception {
        // an IPv6 visitor can pick any address in a /64, so the share is counted per network there
        check(WebServer.logShareKey(java.net.InetAddress.getByName("2001:db8:1:2::a")).equals(WebServer.logShareKey(java.net.InetAddress.getByName("2001:db8:1:2:ffff::b")))
                && !WebServer.logShareKey(java.net.InetAddress.getByName("2001:db8:1:2::a")).equals(WebServer.logShareKey(java.net.InetAddress.getByName("2001:db8:1:3::a")))
                && !WebServer.logShareKey(java.net.InetAddress.getByName("192.0.2.1")).equals(WebServer.logShareKey(java.net.InetAddress.getByName("192.0.2.2"))),
                "log share: one IPv6 /64 is one visitor, IPv4 addresses stay apart");
        Path root = Files.createDirectories(data.resolve("share"));
        Files.writeString(root.resolve("index.html"), "hi");
        Path shareLog = data.resolve("share-access.log");
        WebServer w = new WebServer(root, "127.0.0.1", 0, shareLog, ZoneId.systemDefault(), null);
        w.start();
        try {
            String junk = "GET " + ("/" + "j".repeat(40)).repeat(7) + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n";
            for (int i = 0; i < 300; i++) check(rawTo(w.port(), null, junk).startsWith("HTTP/1.1 404"), "junk request " + i + " answered");
            check(rawTo(w.port(), "127.0.0.2", "GET /index.html HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 200"), "a reader from another address");
            var logDay = WebServer.class.getDeclaredField("logDay");   // the next visit is on a new day: every share starts again
            logDay.setAccessible(true);
            logDay.set(w, java.time.LocalDate.MIN);
            check(rawTo(w.port(), null, junk.replace('j', 'k')).startsWith("HTTP/1.1 404"), "the noisy address on the next day");
        } finally {
            w.stop();
        }
        List<String> lines = Files.readAllLines(shareLog);
        long noisy = lines.stream().filter(l -> l.contains(" 127.0.0.1 \"GET /jjj")).mapToLong(l -> l.length() + 1).sum();
        check(noisy > WebServer.LOG_ADDRESS_CAP - 1000 && noisy <= WebServer.LOG_ADDRESS_CAP, "one address's lines stop at its daily share: " + noisy + " bytes");
        check(lines.stream().anyMatch(l -> l.contains(" 127.0.0.2 \"GET /index.html HTTP/1.1\" 200 OK")), "another address is still logged: " + lines.get(lines.size() - 1));
        check(lines.stream().anyMatch(l -> l.contains(" 127.0.0.1 \"GET /kkk")), "a new day gives the noisy address its share back: " + lines.get(lines.size() - 1));
        System.out.println("ok access log: one address is held to " + noisy + " bytes a day, others still logged");
    }

    /** A raw request from an optional local address; "" if the server closed the connection without an answer. */
    static String rawTo(int p, String from, String request) throws Exception {
        try (Socket s = from == null ? new Socket("127.0.0.1", p) : new Socket("127.0.0.1", p, java.net.InetAddress.getByName(from), 0)) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    static String base() {
        return "http://127.0.0.1:" + port;
    }

    static HttpResponse<String> get(HttpClient http, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static String type(HttpClient http, String path) throws Exception {
        HttpResponse<String> r = get(http, path);
        check(r.statusCode() == 200, path + " status " + r.statusCode());
        return r.headers().firstValue("content-type").orElse("");
    }

    static String raw(String request) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            OutputStream o = s.getOutputStream();
            o.write(request.getBytes(StandardCharsets.ISO_8859_1));
            o.flush();
            InputStream in = s.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    static void check(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }
}
