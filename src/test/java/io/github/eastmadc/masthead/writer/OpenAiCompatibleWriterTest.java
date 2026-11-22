package io.github.eastmadc.masthead.writer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.github.eastmadc.masthead.config.Settings;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Plain-main test: OpenAiCompatibleWriter against a local JDK HttpServer playing /v1/chat/completions. Request shape,
 * extra-body merge, think-block removal, finish_reason handling, retries, no redirects, the http-only-on-LAN rule, the
 * 200 KB cap, the budget ledger, and that the key never shows up in messages or logs.
 */
public final class OpenAiCompatibleWriterTest {
    record Canned(int status, String body, String header) {}

    record Seen(String path, String auth, String body) {}

    static final Deque<Canned> replies = new ArrayDeque<>();
    static final List<Seen> seen = new ArrayList<>();
    static final List<String> logged = new ArrayList<>();
    static final String KEY = "sk-or-v1-test-not-a-real-key";
    static volatile Runnable onRequest;

    public static void main(String[] args) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (seen) { seen.add(new Seen(ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("authorization"), body)); }
            if (onRequest != null) onRequest.run();
            Canned c;
            synchronized (replies) { c = replies.poll(); }
            if (c == null) c = new Canned(500, "{\"error\":{\"message\":\"no canned reply\"}}", null);
            if (c.status < 0) { ex.close(); return; }   // W2: no answer, the connection is closed
            byte[] out = c.body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("content-type", "application/json");
            if (c.header != null) ex.getResponseHeaders().set(c.header.split(":", 2)[0], c.header.split(":", 2)[1].strip());
            ex.sendResponseHeaders(c.status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1/";
        Logger log = Logger.getLogger("oa-test");
        log.addHandler(new Handler() {
            @Override public void publish(LogRecord r) { synchronized (logged) { logged.add(r.getMessage()); } }
            @Override public void flush() { }
            @Override public void close() { }
        });
        Path dir = Files.createTempDirectory("oa");
        Path ledger = dir.resolve("spend.json");
        Spend spend = new Spend(ledger, 1.0, null);
        String extra = "{\"provider\":{\"data_collection\":\"deny\"},\"model\":\"evil\",\"stream\":true,\"max_tokens\":5,\"messages\":[]}";
        try {
            // 1. success: request shape, extra-body can't override ours, <think> removed, usage.cost counted
            try (OpenAiCompatibleWriter w = new OpenAiCompatibleWriter(cfg(base, KEY, "low", extra, 0, 0), log, spend)) {
                replies.add(new Canned(200, reply("stop", "<think>hmm, the data says 3</think>\n<h1>Paper</h1>", null, "\"cost\":0.0123"), null));
                String text = w.write("STYLE", "FACTS", Duration.ofSeconds(30));
                check(text.equals("<h1>Paper</h1>"), "think block removed: " + text);
                Seen s = seen.get(0);
                check(s.path.equals("/v1/chat/completions"), "path " + s.path);
                check(("Bearer " + KEY).equals(s.auth), "bearer key");
                JsonObject req = JsonParser.parseString(s.body).getAsJsonObject();
                check(req.get("model").getAsString().equals("test/model-1"), "model not overridden by extra-body");
                check(!req.has("stream"), "stream not set by extra-body");
                check(req.get("max_tokens").getAsLong() == 16000, "max_tokens ours");
                check(req.getAsJsonArray("messages").size() == 2 && req.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString().equals("system")
                        && req.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString().equals("FACTS"), "messages ours");
                check(req.get("reasoning_effort").getAsString().equals("low"), "reasoning_effort");
                check(req.getAsJsonObject("provider").get("data_collection").getAsString().equals("deny"), "extra-body merged");
                check(Math.abs(usd(ledger) - 0.0123) < 1e-9, "usage.cost counted: " + usd(ledger));
                check(w.name().equals("openai-compatible (127.0.0.1, test/model-1)"), "name shows host and model only: " + w.name());
                System.out.println("ok request shape: " + s.body);

                // 2. finish_reason length, content_filter, refusal
                replies.add(new Canned(200, reply("length", "half a sto", null, ""), null));
                try { w.write("s", "u", Duration.ofSeconds(30)); throw new AssertionError("length"); }
                catch (IOException e) { check(e.getMessage().contains("length limit"), "length: " + e.getMessage()); }
                replies.add(new Canned(200, reply("content_filter", "", null, ""), null));
                try { w.write("s", "u", Duration.ofSeconds(30)); throw new AssertionError("content_filter"); }
                catch (RefusalException e) { check(e.category().equals("content_filter"), "content_filter"); }
                replies.add(new Canned(200, reply("stop", null, "I can't help with that.", ""), null));
                try { w.write("s", "u", Duration.ofSeconds(30)); throw new AssertionError("refusal"); }
                catch (RefusalException e) { check(e.category().equals("refusal"), "refusal"); }
                replies.add(new Canned(200, reply("stop", "<think>never closed", null, ""), null));
                try { w.write("s", "u", Duration.ofSeconds(30)); throw new AssertionError("only thinking"); }
                catch (IOException e) { check(e.getMessage().contains("no text"), "only thinking: " + e.getMessage()); }
                System.out.println("ok length / content_filter / refusal / only-thinking are failures");

                // 3. 429 with retry-after, then success
                seen.clear();
                replies.add(new Canned(429, "{\"error\":{\"message\":\"slow down\"}}", "retry-after: 0.05"));
                replies.add(new Canned(200, reply("stop", "second", null, ""), null));
                check(w.write("s", "u", Duration.ofSeconds(30)).equals("second") && seen.size() == 2, "429 retried");
                System.out.println("ok 429 retried");

                // 4. 401 echoing the key: not retried, key scrubbed
                seen.clear();
                replies.add(new Canned(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + KEY + "\"}}", null));
                try { w.write("s", "u", Duration.ofSeconds(30)); throw new AssertionError("401"); }
                catch (IOException e) { check(e.getMessage().contains("HTTP 401") && !e.getMessage().contains(KEY), "401 scrubbed: " + e.getMessage()); }
                check(seen.size() == 1, "401 not retried");

                // 5. a redirect is not followed (the key must not go to another host)
                seen.clear();
                replies.add(new Canned(302, "", "location: http://127.0.0.1:1/elsewhere"));
                try { w.write("s", "u", Duration.ofSeconds(30)); throw new AssertionError("302"); }
                catch (IOException e) { check(e.getMessage().contains("HTTP 302"), "302: " + e.getMessage()); }
                check(seen.size() == 1, "redirect not followed");
                System.out.println("ok 401 not retried, key scrubbed; redirect not followed");

                // 6. a reply over 200 KB is refused
                replies.add(new Canned(200, reply("stop", "x".repeat(210 * 1024), null, ""), null));
                try { w.write("s", "u", Duration.ofSeconds(30)); throw new AssertionError("too big"); }
                catch (IOException e) { check(e.getMessage().contains("200 KB"), "cap: " + e.getMessage()); }
                // w is a free local server (prices 0, 127.0.0.1), so a bad reply settles at $0 instead of staying pending
                double before = usd(ledger);
                check(!Files.readString(ledger).contains("pending") && usd(ledger) == before, "free local: oversized reply settled at $0");
                replies.add(new Canned(200, "{broken", null));
                try { w.write("s", "u", Duration.ofSeconds(30)); throw new AssertionError("malformed success"); }
                catch (IOException e) { check(e.getMessage().contains("expected JSON"), "malformed: " + e.getMessage()); }
                check(!Files.readString(ledger).contains("pending") && usd(ledger) == before, "free local: broken-JSON reply settled at $0");
            }
            // ... but a priced server may have billed those replies: pending stays until reconciled
            try (OpenAiCompatibleWriter w = new OpenAiCompatibleWriter(cfg(base, KEY, "", "{}", 1, 1), log, spend)) {
                replies.add(new Canned(200, reply("stop", "x".repeat(210 * 1024), null, ""), null));
                try { w.write("s", "u", Duration.ofSeconds(30)); throw new AssertionError("too big"); }
                catch (IOException e) { check(e.getMessage().contains("200 KB"), "cap: " + e.getMessage()); }
                try { new Spend(ledger, 1, null).check(); throw new AssertionError("unaccounted oversized reply"); }
                catch (IOException e) { check(e.getMessage().contains("unsettled request"), "priced: oversized successful reply remains pending"); }
                spend.add(0);   // explicit reconciliation of the test fixture
                replies.add(new Canned(200, "{broken", null));
                try { w.write("s", "u", Duration.ofSeconds(30)); throw new AssertionError("malformed success"); }
                catch (IOException e) { check(e.getMessage().contains("expected JSON"), "malformed: " + e.getMessage()); }
                try { new Spend(ledger, 1, null).check(); throw new AssertionError("unaccounted malformed reply"); }
                catch (IOException e) { check(e.getMessage().contains("unsettled request"), "priced: malformed successful reply remains pending"); }
                spend.add(0);
            }
            try (OpenAiCompatibleWriter w = new OpenAiCompatibleWriter(cfg(base, KEY, "low", extra, 0, 0), log, spend)) {

                // 7. max_tokens rejected by a newer model: sent again once as max_completion_tokens
                seen.clear();
                replies.add(new Canned(400, "{\"error\":{\"message\":\"Unsupported parameter: 'max_tokens'. Use 'max_completion_tokens' instead.\"}}", null));
                replies.add(new Canned(200, reply("stop", "ok", null, ""), null));
                check(w.write("s", "u", Duration.ofSeconds(30)).equals("ok"), "max_completion_tokens retry");
                JsonObject second = JsonParser.parseString(seen.get(1).body).getAsJsonObject();
                check(second.get("max_completion_tokens").getAsLong() == 16000 && !second.has("max_tokens"), "renamed field");
                System.out.println("ok 200 KB cap; max_completion_tokens");
            }

            // The compatibility retry is another request: it must observe the ledger again, not bypass its gate.
            seen.clear();
            Path retryLedger = dir.resolve("retry-spend.json");
            Spend retrySpend = new Spend(retryLedger, 1, null);
            replies.add(new Canned(400, "{\"error\":{\"message\":\"Use max_completion_tokens\"}}", null));
            replies.add(new Canned(200, reply("stop", "must not be requested", null, ""), null));
            onRequest = () -> {
                onRequest = null;
                try { retrySpend.add(1); } catch (IOException e) { throw new RuntimeException(e); }
            };
            try (OpenAiCompatibleWriter w = new OpenAiCompatibleWriter(cfg(base, KEY, "", "{}", 0, 0), log, retrySpend)) {
                try { w.write("s", "u", Duration.ofSeconds(30)); throw new AssertionError("retry bypassed budget"); }
                catch (IOException e) { check(e.getMessage().contains("monthly AI budget reached"), "compatibility retry checks budget"); }
            } finally { onRequest = null; replies.clear(); }
            check(seen.size() == 1, "no compatibility request after budget was reached");

            // W2: a priced server hangs up with no answer: counted at the most it can cost, like a timeout, and the retry goes out
            seen.clear();
            Path dropLedger = dir.resolve("drop-spend.json");
            replies.add(new Canned(-1, "", null));
            replies.add(new Canned(200, reply("stop", "second try", null, "\"prompt_tokens\":10,\"completion_tokens\":5"), null));
            try (OpenAiCompatibleWriter w = new OpenAiCompatibleWriter(cfg(base, KEY, "", "{}", 3, 15), log, new Spend(dropLedger, 10, null))) {
                check(w.write("s", "u", Duration.ofSeconds(30)).equals("second try") && seen.size() == 2, "W2: the retry reaches the server: " + seen.size());
                check(Math.abs(usd(dropLedger) - w.worstCase("s", "u") - (10 * 3 + 5 * 15) / 1e6) < 1e-9 && !Files.readString(dropLedger).contains("pending"),
                        "W2: the unanswered call is counted at its worst case, nothing left pending: " + Files.readString(dropLedger));
            } finally { replies.clear(); }
            System.out.println("ok malformed/oversized success: free local settles at $0, priced remains pending; compatibility retry checks the ledger");

            // 8. tokens x configured price when the server reports no cost; no key = no Authorization header
            Files.deleteIfExists(ledger);
            seen.clear();
            try (OpenAiCompatibleWriter w = new OpenAiCompatibleWriter(cfg(base, "", "", "{}", 2.0, 10.0), log, spend)) {
                replies.add(new Canned(200, reply("stop", "local", null, "\"prompt_tokens\":1000,\"completion_tokens\":100"), null));
                w.write("", "u", Duration.ofSeconds(30));
                check(Math.abs(usd(ledger) - (1000 * 2.0 + 100 * 10.0) / 1e6) < 1e-12, "priced from config: " + usd(ledger));
                check(seen.get(0).auth == null, "no key, no Authorization header");
                check(JsonParser.parseString(seen.get(0).body).getAsJsonObject().getAsJsonArray("messages").size() == 1, "no empty system message");
                check(!JsonParser.parseString(seen.get(0).body).getAsJsonObject().has("reasoning_effort"), "reasoning_effort only when set");

                // 9. budget reached: no request
                Files.writeString(ledger, "{\"month\":\"" + YearMonth.now() + "\",\"usd\":5}");
                seen.clear();
                try { w.write("s", "u", Duration.ofSeconds(30)); throw new AssertionError("budget"); }
                catch (IOException e) { check(e.getMessage().contains("monthly AI budget reached"), "budget: " + e.getMessage()); }
                check(seen.isEmpty(), "no request over budget");
            }
            System.out.println("ok price from config; budget blocks the call");

            // 10. plain http to a public address is refused before anything is sent
            try (OpenAiCompatibleWriter w = new OpenAiCompatibleWriter(cfg("http://8.8.8.8:9/v1", KEY, "", "{}", 0, 0), log, null)) {
                w.write("s", "u", Duration.ofSeconds(5));
                throw new AssertionError("public http");
            } catch (IOException e) { check(e.getMessage().contains("https://"), "public http refused: " + e.getMessage()); }
            check(OpenAiCompatibleWriter.nearby("127.0.0.1") && OpenAiCompatibleWriter.nearby("192.168.1.5") && OpenAiCompatibleWriter.nearby("10.0.0.2")
                    && OpenAiCompatibleWriter.nearby("fd00::1") && !OpenAiCompatibleWriter.nearby("8.8.8.8") && !OpenAiCompatibleWriter.nearby("2001:4860::1"), "nearby()");
            // not set up: a clear error
            try (OpenAiCompatibleWriter w = new OpenAiCompatibleWriter(Settings.OpenAi.NONE, log, null)) {
                w.write("s", "u", Duration.ofSeconds(5));
                throw new AssertionError("not set up");
            } catch (IllegalStateException e) { check(e.getMessage().contains("base-url"), "not set up message"); }
            System.out.println("ok http only on the LAN");

            // 11. a key file with a second line: a clear error that never shows the key, and nothing sent
            Path kf = dir.resolve("key");
            Files.writeString(kf, "sk-SECRET123\n# rotated 2026-11\n");
            Files.setPosixFilePermissions(kf, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            seen.clear();
            try (OpenAiCompatibleWriter w = new OpenAiCompatibleWriter(new Settings.OpenAi(base, "test/model-1", kf.toString(), "", "", 0, 0, "{}"), log, null)) {
                w.write("s", "u", Duration.ofSeconds(5));
                throw new AssertionError("two-line key file");
            } catch (Exception e) {
                check(e instanceof IOException && !String.valueOf(e.getMessage()).contains("SECRET") && e.getMessage().contains("one line"), "two-line key: " + e.getClass().getSimpleName());
            }
            check(seen.isEmpty(), "no request with a two-line key");
            System.out.println("ok two-line key file refused without showing the key");
            // 11b (R2-SO-1). A zero-width space in the key: refused without showing the key (the HTTP client's own error
            // quotes the whole header), nothing sent. A Windows BOM in front is just dropped.
            Files.writeString(kf, "sk-SECRET123\u200B\n");
            seen.clear();
            try (OpenAiCompatibleWriter w = new OpenAiCompatibleWriter(new Settings.OpenAi(base, "test/model-1", kf.toString(), "", "", 0, 0, "{}"), log, null)) {
                w.write("s", "u", Duration.ofSeconds(5));
                throw new AssertionError("zero-width space in the key");
            } catch (Exception e) {
                check(e instanceof IOException && !String.valueOf(e.getMessage()).contains("SECRET"), "zero-width key: " + e.getClass().getSimpleName() + ": "
                        + String.valueOf(e.getMessage()).replace("SECRET", "******"));
            }
            check(seen.isEmpty(), "no request with a zero-width space in the key");
            Files.writeString(kf, "\uFEFFsk-SECRET123\n");
            check(Writer.apiKey(kf.toString(), "NO_SUCH_ENV_R2", "", "x.", null).equals("sk-SECRET123"), "a leading BOM is dropped");
            System.out.println("ok odd characters in the key refused without showing it; a BOM is dropped");

            // 12. a server that sends the headers and then stalls the body: write() gives up inside its timeout
            for (double price : new double[]{1, 0})
            try (java.net.ServerSocket ss = new java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())) {
                Thread stall = new Thread(() -> {
                    try (java.net.Socket so = ss.accept()) {
                        java.io.InputStream in = so.getInputStream();
                        String head = "";
                        while (!head.contains("\r\n\r\n")) head += (char) in.read();
                        so.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"c".getBytes(StandardCharsets.US_ASCII));
                        so.getOutputStream().flush();
                        Thread.sleep(60_000);
                    } catch (Exception ignored) { }
                });
                stall.setDaemon(true);
                stall.start();
                Exception[] got = new Exception[1];
                Path stallLedger = dir.resolve("stall-spend-" + price + ".json");
                OpenAiCompatibleWriter sw = new OpenAiCompatibleWriter(cfg("http://127.0.0.1:" + ss.getLocalPort() + "/v1", "", "", "{}", price, 2 * price), log, new Spend(stallLedger, 10, null));
                Thread t = new Thread(() -> {
                    try (sw) {
                        sw.write("s", "u", Duration.ofSeconds(3));
                    } catch (Exception e) { got[0] = e; }
                });
                t.setDaemon(true);
                long t0 = System.nanoTime();
                t.start();
                t.join(10_000);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                check(!t.isAlive(), "stalled body: write() still blocked after 10 s");
                check(got[0] != null && ms < 5_000, "stalled body: gave up in " + ms + " ms with " + got[0]);
                // SO-4: the stalled reply may still be billed: counted at the most it can cost
                check(Files.exists(stallLedger) && Math.abs(usd(stallLedger) - sw.worstCase("s", "u")) < 1e-12, "SO-4: a timed-out call is counted at its worst case");
                // a free local server (prices 0) settles at 0: no pending left to block the writer for good
                check(!JsonParser.parseString(Files.readString(stallLedger)).getAsJsonObject().has("pending"), "stalled call (price " + price + ") left pending");
                System.out.println("ok stalled body (price " + price + "): gave up in " + ms + " ms (" + got[0].getMessage() + ")");
            }

            // a free local server stopped mid-reply (shutdown interrupts the edition thread) settles at $0
            try (java.net.ServerSocket ss = new java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())) {
                java.util.concurrent.CountDownLatch headersSent = new java.util.concurrent.CountDownLatch(1);
                Thread stall = new Thread(() -> {
                    try (java.net.Socket so = ss.accept()) {
                        java.io.InputStream in = so.getInputStream();
                        String head = "";
                        while (!head.contains("\r\n\r\n")) head += (char) in.read();
                        so.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"c".getBytes(StandardCharsets.US_ASCII));
                        so.getOutputStream().flush();
                        headersSent.countDown();
                        Thread.sleep(60_000);
                    } catch (Exception ignored) { }
                });
                stall.setDaemon(true);
                stall.start();
                Path intLedger = dir.resolve("interrupt-spend.json");
                Exception[] got = new Exception[1];
                OpenAiCompatibleWriter iw = new OpenAiCompatibleWriter(cfg("http://127.0.0.1:" + ss.getLocalPort() + "/v1", "", "", "{}", 0, 0), log, new Spend(intLedger, 10, null));
                Thread t = new Thread(() -> {
                    try (iw) { iw.write("s", "u", Duration.ofSeconds(30)); } catch (Exception e) { got[0] = e; }
                });
                t.setDaemon(true);
                t.start();
                check(headersSent.await(10, java.util.concurrent.TimeUnit.SECONDS), "interrupt: request never arrived");
                Thread.sleep(300);   // let write() reach the body read
                t.interrupt();
                t.join(10_000);
                check(got[0] instanceof InterruptedException, "interrupted read: " + got[0]);
                check(Files.exists(intLedger) && !Files.readString(intLedger).contains("pending") && usd(intLedger) == 0, "interrupted read: free local settled at $0");
                System.out.println("ok interrupted read: free local server settled at $0");
            }

            // a free remote server or a priced local one is not settled at 0 on a timeout: pending stays (or worst case is counted)
            for (Settings.OpenAi c : List.of(cfg("https://8.8.8.8/v1", "", "", "{}", 0, 0), cfg(base, "", "", "{}", 0, 1), cfg("not a url", "", "", "{}", 0, 0)))
                try (OpenAiCompatibleWriter w = new OpenAiCompatibleWriter(c, log, null)) { check(!w.freeLocal(), "freeLocal: " + c.baseUrl()); }
            try (OpenAiCompatibleWriter w = new OpenAiCompatibleWriter(cfg(base, "", "", "{}", 0, 0), log, null)) { check(w.freeLocal(), "freeLocal: local, prices 0"); }

            // 13. a paid provider that reports no cost and has no prices: the budget can't count it, so the owner is warned
            try (OpenAiCompatibleWriter w = new OpenAiCompatibleWriter(cfg("https://8.8.8.8/v1", "", "", "{}", 0, 0), log, new Spend(ledger, 10, null))) {
                Files.deleteIfExists(ledger);
                int mark = logged.size();
                check(w.extract(reply("stop", "a", null, "\"prompt_tokens\":1000,\"completion_tokens\":100")).equals("a")
                        && w.extract(reply("stop", "b", null, "\"prompt_tokens\":1000,\"completion_tokens\":100")).equals("b"), "uncounted reply still used");
                synchronized (logged) {
                    check(logged.subList(mark, logged.size()).stream().filter(m -> m.contains("can't count") && m.contains("price-per-million")).count() == 1, "warned once: " + logged.subList(mark, logged.size()));
                }
                check(!Files.exists(ledger) || usd(ledger) == 0, "nothing counted");
            }
            for (OpenAiCompatibleWriter w : List.of(
                    new OpenAiCompatibleWriter(cfg("https://8.8.8.8/v1", "", "", "{}", 0, 0), log, new Spend(ledger, 0, null)),    // no budget
                    new OpenAiCompatibleWriter(cfg("https://8.8.8.8/v1", "", "", "{}", 1, 0), log, new Spend(ledger, 10, null)),   // priced
                    new OpenAiCompatibleWriter(cfg(base, "", "", "{}", 0, 0), log, new Spend(ledger, 10, null)))) {                // on this machine
                int mark = logged.size();
                w.extract(reply("stop", "a", null, "\"prompt_tokens\":1000,\"completion_tokens\":100"));
                w.close();
                synchronized (logged) { check(logged.subList(mark, logged.size()).stream().noneMatch(m -> m.contains("can't count")), "no warning when the budget can count or is off: " + logged); }
            }
            try (OpenAiCompatibleWriter w = new OpenAiCompatibleWriter(cfg("https://8.8.8.8/v1", "", "", "{}", 0, 0), log, new Spend(ledger, 10, null))) {
                int mark = logged.size();
                w.extract(reply("stop", "a", null, "\"cost\":0.01"));
                synchronized (logged) { check(logged.subList(mark, logged.size()).stream().noneMatch(m -> m.contains("can't count")), "no warning when the server reports cost: " + logged); }
            }
            System.out.println("ok uncounted provider warned about");

            // 14. the key never reaches the log; the inline key is warned about
            synchronized (logged) {
                check(logged.stream().noneMatch(m -> m.contains(KEY)), "key never logged");
                check(logged.stream().anyMatch(m -> m.contains("api-key is in config.yml")), "inline key warned: " + logged);
            }
            System.out.println("ok key never logged");
        } finally {
            srv.stop(0);
        }
        System.out.println("OpenAiCompatibleWriterTest PASS");
    }

    static Settings.OpenAi cfg(String base, String key, String effort, String extra, double in, double out) {
        return new Settings.OpenAi(base, "test/model-1", "", key, effort, in, out, extra);
    }

    static String reply(String finish, String content, String refusal, String usage) {
        com.google.gson.Gson g = new com.google.gson.Gson();
        return "{\"id\":\"c1\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"finish_reason\":\"" + finish + "\",\"message\":{\"role\":\"assistant\",\"content\":"
                + (content == null ? "null" : g.toJson(content)) + ",\"refusal\":" + (refusal == null ? "null" : g.toJson(refusal)) + "}}],\"usage\":{" + usage + "}}";
    }

    static double usd(Path ledger) throws IOException {
        return JsonParser.parseString(Files.readString(ledger)).getAsJsonObject().get("usd").getAsDouble();
    }

    static void check(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }
}
