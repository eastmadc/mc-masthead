package io.github.eastmadc.masthead.writer;

import io.github.eastmadc.masthead.config.Settings;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Logger;

/**
 * Plain-main test: points AnthropicWriter at a local JDK HttpServer that plays the Messages API and the Message Batches API,
 * checks the request JSON (system cache_control, output_config.effort, fallbacks, beta header, max_tokens 32000), the
 * retry / refusal / error handling, the batch path (success, deadline + cancel, failure -> normal call), the monthly
 * budget ledger, and the key file.
 * Runtime classpath needs lib/compile/* and lib/test/* (SDK runtime deps).
 */
public final class AnthropicWriterTest {
    record Canned(int status, String body, String retryAfter) {}

    record Seen(String method, String path, String beta, boolean keyOk, String body, java.util.Set<String> headerNames) {}

    static final Deque<Canned> replies = new ArrayDeque<>();
    static final List<Seen> seen = new ArrayList<>();
    static final String KEY = "sk-ant-api03-test-not-a-real-key";
    static volatile String batchStatus = "ended";   // what GET /v1/messages/batches/{id} answers
    static volatile String batchResult = "";        // one JSONL line for .../results
    static volatile boolean endOnCancel = false;     // C5: a batch that still finishes after it is cancelled
    static volatile long delayMs = 0;                // SO-4: the next normal call answers this late
    static volatile Runnable onBatchCreate = null;   // runs when a batch is created (a test makes the ledger unwritable here)
    static volatile Runnable onMessageCreate = null; // tests change the ledger between attempts
    static volatile String hangUpOn = null;          // W2: the next request to this path gets no answer (the connection is closed)

    public static void main(String[] args) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (seen) {
                seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(), String.valueOf(ex.getRequestHeaders().get("anthropic-beta")),
                        // SDK 3.5 sends the key as "Authorization: Bearer <key>"; older SDKs used x-api-key. Either is fine.
                        KEY.equals(ex.getRequestHeaders().getFirst("x-api-key"))
                                || ("Bearer " + KEY).equals(ex.getRequestHeaders().getFirst("authorization")),
                        body, ex.getRequestHeaders().keySet()));
            }
            Canned c;
            String path = ex.getRequestURI().getPath();
            if (path.equals(hangUpOn)) { hangUpOn = null; ex.close(); return; }
            if (path.startsWith("/v1/messages/batches") && ex.getRequestMethod().equals("DELETE")) {
                c = new Canned(200, "{\"id\":\"msgbatch_test\",\"type\":\"message_batch_deleted\"}", null);
            } else if (path.startsWith("/v1/messages/batches")) {
                if (path.equals("/v1/messages/batches") && onBatchCreate != null) onBatchCreate.run();
                if (path.endsWith("/cancel") && endOnCancel) batchStatus = "ended";
                String st = path.endsWith("/cancel") ? "canceling" : path.equals("/v1/messages/batches") ? "in_progress" : batchStatus;
                c = path.endsWith("/results") ? new Canned(200, batchResult, null) : new Canned(200, batch(st), null);
            } else {
                if (onMessageCreate != null) onMessageCreate.run();
                long d = delayMs;
                if (d > 0) { delayMs = 0; try { Thread.sleep(d); } catch (InterruptedException ignored) { } }
                synchronized (replies) { c = replies.poll(); }
            }
            if (c == null) c = new Canned(500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"no canned reply\"}}", null);
            byte[] out = c.body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("content-type", "application/json");
            if (c.retryAfter != null) ex.getResponseHeaders().set("retry-after", c.retryAfter);
            ex.sendResponseHeaders(c.status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        Settings.WriterCfg cfg = new Settings.WriterCfg("anthropic", "claude-opus-5-5", "high", KEY, List.of(), 60, 30);
        Logger log = Logger.getLogger("test");
        try (AnthropicWriter w = new AnthropicWriter(cfg, log, null, base)) {
            String system = "STYLE GUIDE ".repeat(50);

            // 1. success: request shape
            replies.add(new Canned(200, message("end_turn", "Hello, <b>paper</b>!", null), null));
            String text = w.write(system, "FACTS {\"date\":\"2026-11-05\"}", Duration.ofSeconds(30));
            check(text.equals("Hello, <b>paper</b>!"), "text extracted: " + text);
            Seen s = seen.get(0);
            check(s.path.equals("/v1/messages"), "path " + s.path);
            check(s.keyOk, "API key sent; header names seen: " + s.headerNames);
            check(s.beta.contains("server-side-fallback-2026-07-01"), "beta header " + s.beta);
            JsonObject req = JsonParser.parseString(s.body).getAsJsonObject();
            check(req.get("model").getAsString().equals("claude-opus-5-5"), "model");
            check(req.get("max_tokens").getAsLong() == 32000, "max_tokens 32000, sent without streaming (the SDK did not refuse it)");
            check(!req.has("stream") || !req.get("stream").getAsBoolean(), "non-streaming");
            check(req.getAsJsonObject("output_config").get("effort").getAsString().equals("high"), "effort");
            check(req.get("fallbacks").getAsString().equals("default"), "fallbacks default");
            JsonObject sys0 = req.getAsJsonArray("system").get(0).getAsJsonObject();
            check(sys0.get("type").getAsString().equals("text") && sys0.get("text").getAsString().equals(system), "system text block");
            check(sys0.getAsJsonObject("cache_control").get("type").getAsString().equals("ephemeral"), "cache_control ephemeral");
            JsonObject m0 = req.getAsJsonArray("messages").get(0).getAsJsonObject();
            check(m0.get("role").getAsString().equals("user"), "user role");
            check(m0.toString().contains("FACTS"), "user content");
            check(!req.has("thinking"), "thinking omitted (always on for Opus 5.5)");
            System.out.println("ok request shape: " + redact(s.body));

            // 2. 529 overloaded with retry-after, then success
            seen.clear();
            replies.add(new Canned(529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}", "0.05"));
            replies.add(new Canned(200, message("end_turn", "second try", null), null));
            check(w.write(system, "x", Duration.ofSeconds(30)).equals("second try"), "retry result");
            check(seen.size() == 2, "retried once: " + seen.size());
            System.out.println("ok 529 retried");

            // 3. 400: no retry
            seen.clear();
            replies.add(new Canned(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad thing\"}}", null));
            try {
                w.write(system, "x", Duration.ofSeconds(30));
                throw new AssertionError("400 should throw");
            } catch (IOException e) {
                check(e.getMessage().contains("HTTP 400") && e.getMessage().contains("bad thing"), "400 message: " + e.getMessage());
                check(!e.getMessage().contains(KEY), "key not in message");
            }
            check(seen.size() == 1, "400 not retried: " + seen.size());
            System.out.println("ok 400 not retried");

            // 4. 401: no retry, key never echoed
            seen.clear();
            replies.add(new Canned(401, "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}", null));
            try {
                w.write(system, "x", Duration.ofSeconds(30));
                throw new AssertionError("401 should throw");
            } catch (IOException e) {
                check(e.getMessage().contains("HTTP 401") && !e.getMessage().contains(KEY), "401 message");
            }
            check(seen.size() == 1, "401 not retried");
            System.out.println("ok 401 not retried");

            // 5. refusal
            replies.add(new Canned(200, message("refusal", null, "cyber"), null));
            try {
                w.write(system, "x", Duration.ofSeconds(30));
                throw new AssertionError("refusal should throw");
            } catch (RefusalException e) {
                check(e.category().equals("cyber"), "category " + e.category());
            }
            System.out.println("ok refusal -> RefusalException(cyber)");

            // 6. max_tokens
            replies.add(new Canned(200, message("max_tokens", "half a sto", null), null));
            try {
                w.write(system, "x", Duration.ofSeconds(30));
                throw new AssertionError("max_tokens should throw");
            } catch (IOException e) {
                check(e.getMessage().contains("max_tokens"), "max_tokens message");
            }
            System.out.println("ok max_tokens -> throws");

            // 7. 500 four times -> gives up after MAX_ATTEMPTS
            seen.clear();
            for (int i = 0; i < 4; i++)
                replies.add(new Canned(500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}", "0.01"));
            try {
                w.write(system, "x", Duration.ofSeconds(30));
                throw new AssertionError("500s should throw");
            } catch (IOException e) {
                check(e.getMessage().contains("after 4 attempts"), "gave up: " + e.getMessage());
            }
            check(seen.size() == 4, "4 attempts: " + seen.size());
            System.out.println("ok 5xx gives up after 4 attempts");

            // 8. no key -> clear error, no request
            seen.clear();
            try (AnthropicWriter nokey = new AnthropicWriter(new Settings.WriterCfg("anthropic", "", "", "", List.of(), 60, 30), log, null, base)) {
                if (System.getenv("ANTHROPIC_API_KEY") == null) {
                    try {
                        nokey.write("s", "u", Duration.ofSeconds(5));
                        throw new AssertionError("no key should throw");
                    } catch (IllegalStateException e) {
                        check(seen.isEmpty(), "no request without key");
                    }
                    System.out.println("ok missing key -> IllegalStateException");
                }
                check(nokey.name().contains("claude-opus-5-5") && nokey.name().contains("medium"), "defaults: " + nokey.name());
            }
            batchTests(base, log);
            budgetTests(base, log);
            keyFileTests(base, log);
        } finally {
            srv.stop(0);
        }
        System.out.println("AnthropicWriterTest PASS");
    }

    static Settings.WriterCfg cfg(boolean batch, String keyFile, String inlineKey) {
        return new Settings.WriterCfg("anthropic", "claude-opus-5-5", "medium", inlineKey, List.of(), 60, 30, batch, keyFile, 10, Settings.OpenAi.NONE);
    }

    static AnthropicWriter batchWriter(String base, Logger log, Spend spend) {
        AnthropicWriter w = new AnthropicWriter(cfg(true, "", KEY), log, spend, base);
        w.pollMs = 20;
        w.batchDeadline = Duration.ofMillis(400);
        w.cancelWait = Duration.ofMillis(200);
        return w;
    }

    static List<String> paths() {
        synchronized (seen) { return seen.stream().map(x -> x.path).toList(); }
    }

    static long deletes() {
        synchronized (seen) { return seen.stream().filter(x -> x.method.equals("DELETE") && x.path.equals("/v1/messages/batches/msgbatch_test")).count(); }
    }

    static void batchTests(String base, Logger log) throws Exception {
        String system = "STYLE GUIDE ".repeat(50);
        // 1. the batch ends: one request in, the paper out, no normal call
        seen.clear(); replies.clear();
        batchStatus = "ended";
        batchResult = "{\"custom_id\":\"paper\",\"result\":{\"type\":\"succeeded\",\"message\":" + message("end_turn", "batched paper", null) + "}}\n";
        try (AnthropicWriter w = batchWriter(base, log, null)) {
            check(w.writePaper(system, "FACTS", Duration.ofSeconds(30)).equals("batched paper"), "batch text");
            check(w.name().contains("batch"), "name says batch: " + w.name());
        }
        List<String> p = paths();
        check(p.get(0).equals("/v1/messages/batches") && p.contains("/v1/messages/batches/msgbatch_test/results") && !p.contains("/v1/messages"),
                "batch create, poll, results, no normal call: " + p);
        JsonObject req = JsonParser.parseString(seen.get(0).body).getAsJsonObject();
        JsonObject r0 = req.getAsJsonArray("requests").get(0).getAsJsonObject(), params = r0.getAsJsonObject("params");
        check(r0.get("custom_id").getAsString().equals("paper"), "custom_id");
        check(params.get("max_tokens").getAsLong() == 32000 && params.get("model").getAsString().equals("claude-opus-5-5"), "batch params");
        check(params.getAsJsonObject("output_config").get("effort").getAsString().equals("medium"), "batch effort");
        check(!params.has("fallbacks"), "no fallbacks in a batch (the API rejects them)");
        check(params.getAsJsonArray("system").get(0).getAsJsonObject().has("cache_control"), "batch system block cached");
        check(seen.get(0).keyOk, "batch: key sent");
        check(deletes() == 1 && seen.get(seen.size() - 1).method.equals("DELETE"), "batch deleted after its results are read: " + paths());
        System.out.println("ok batch success: " + redact(seen.get(0).body));

        // 2. the batch is still running at the deadline: cancelled, then a normal call
        seen.clear();
        batchStatus = "in_progress";
        replies.add(new Canned(200, message("end_turn", "normal paper", null), null));
        try (AnthropicWriter w = batchWriter(base, log, null)) {
            check(w.writePaper(system, "FACTS", Duration.ofSeconds(30)).equals("normal paper"), "fallback text");
        }
        p = paths();
        check(p.contains("/v1/messages/batches/msgbatch_test/cancel") && p.contains("/v1/messages"), "cancelled, then a normal call: " + p);
        check(deletes() == 1, "a cancelled batch is deleted after the normal call: " + p);
        check(JsonParser.parseString(seen.stream().filter(x -> x.path.equals("/v1/messages")).findFirst().orElseThrow().body).getAsJsonObject().get("fallbacks").getAsString().equals("default"), "normal call keeps fallbacks");
        // ... and if that normal call fails, it is not made a second time
        seen.clear();
        replies.add(new Canned(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}", null));
        try (AnthropicWriter w = batchWriter(base, log, null)) { w.writePaper(system, "FACTS", Duration.ofSeconds(30)); throw new AssertionError("400 after batch"); }
        catch (IOException e) { check(paths().stream().filter("/v1/messages"::equals).count() == 1, "one normal call only: " + paths()); }
        System.out.println("ok batch deadline -> cancel + normal call");

        // 3. the batch's request errored, and 4. a refusal in the batch: both get a normal call
        for (String res : List.of("{\"type\":\"errored\",\"error\":{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"x\"}}}",
                "{\"type\":\"succeeded\",\"message\":" + message("refusal", null, "cyber") + "}")) {
            seen.clear();
            batchStatus = "ended";
            batchResult = "{\"custom_id\":\"paper\",\"result\":" + res + "}\n";
            replies.add(new Canned(200, message("end_turn", "normal paper", null), null));
            try (AnthropicWriter w = batchWriter(base, log, null)) {
                check(w.writePaper(system, "FACTS", Duration.ofSeconds(30)).equals("normal paper"), "normal call after a failed batch");
            }
            check(paths().contains("/v1/messages") && deletes() == 1, "normal call made, batch deleted: " + paths());
        }
        System.out.println("ok batch errored / refused -> normal call");

        // 5. batch off: writePaper is a normal call; write() never batches
        seen.clear();
        replies.add(new Canned(200, message("end_turn", "plain call", null), null));
        try (AnthropicWriter w = new AnthropicWriter(cfg(false, "", KEY), log, null, base)) {
            check(w.writePaper(system, "x", Duration.ofSeconds(30)).equals("plain call") && paths().equals(List.of("/v1/messages")), "batch off");
        }
        seen.clear();
        replies.add(new Canned(200, message("end_turn", "desk", null), null));
        try (AnthropicWriter w = batchWriter(base, log, null)) {
            check(w.write(system, "x", Duration.ofSeconds(30)).equals("desk") && paths().equals(List.of("/v1/messages")), "write() (the desk, the rewrite) never batches");
        }
        System.out.println("ok batch only for the first draft");
        // Haiku has no server-side fallback: the parameter is left out (the docs say not to send it)
        Settings.WriterCfg haiku = new Settings.WriterCfg("anthropic", "claude-haiku-5-5", "high", KEY, List.of(), 60, 30);
        try (AnthropicWriter w = new AnthropicWriter(haiku, log, null, base)) {
            JsonObject hreq = JsonParser.parseString(new String(com.anthropic.core.ObjectMappers.jsonMapper().writeValueAsBytes(w.params("s", "u")._body()), StandardCharsets.UTF_8)).getAsJsonObject();
            check(!hreq.has("fallbacks") && hreq.get("model").getAsString().equals("claude-haiku-5-5"), "no fallbacks for Haiku: " + hreq);
        }
    }

    static void budgetTests(String base, Logger log) throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("spend");
        java.nio.file.Path ledger = dir.resolve("data").resolve("spend.json");
        List<String> alerts = new ArrayList<>();
        Spend spend = new Spend(ledger, 1.00, alerts::add);
        double one = (10 * 4 + 600 * 0.20 + 5 * 20) / 1e6;   // message(): in 10, cache read 600, out 5, Opus 5.5 prices

        // 1. a normal call and a batch call are counted (the batch at half price)
        seen.clear(); replies.clear();
        replies.add(new Canned(200, message("end_turn", "a", null), null));
        try (AnthropicWriter w = new AnthropicWriter(cfg(false, "", KEY), log, spend, base)) { w.write("s", "u", Duration.ofSeconds(30)); }
        check(Math.abs(usd(ledger) - one) < 1e-9, "normal call counted: " + usd(ledger));
        batchStatus = "ended";
        batchResult = "{\"custom_id\":\"paper\",\"result\":{\"type\":\"succeeded\",\"message\":" + message("end_turn", "b", null) + "}}\n";
        try (AnthropicWriter w = batchWriter(base, log, spend)) { w.writePaper("s", "u", Duration.ofSeconds(30)); }
        check(Math.abs(usd(ledger) - one * 1.5) < 1e-9, "batch counted at half price: " + usd(ledger));
        // a refused reply is billed too
        replies.add(new Canned(200, message("refusal", null, "cyber"), null));
        try (AnthropicWriter w = new AnthropicWriter(cfg(false, "", KEY), log, spend, base)) { w.write("s", "u", Duration.ofSeconds(30)); throw new AssertionError("refusal"); }
        catch (RefusalException e) { check(Math.abs(usd(ledger) - one * 2.5) < 1e-9, "refusal counted: " + usd(ledger)); }
        System.out.println("ok spend counted (normal, batch at half price, refusal)");

        // C5: a batch cancelled at the deadline that still finishes: its paper is used and counted at half price
        double b0 = usd(ledger);
        seen.clear(); replies.clear();
        batchStatus = "in_progress";
        endOnCancel = true;
        batchResult = "{\"custom_id\":\"paper\",\"result\":{\"type\":\"succeeded\",\"message\":" + message("end_turn", "late paper", null) + "}}\n";
        try (AnthropicWriter w = batchWriter(base, log, spend)) {
            check(w.writePaper("s", "u", Duration.ofSeconds(30)).equals("late paper"), "C5: a batch that ends after the cancel is used");
        } finally { endOnCancel = false; }
        check(!paths().contains("/v1/messages") && Math.abs(usd(ledger) - b0 - one / 2) < 1e-9, "C5: counted at half price, no second paid call: " + (usd(ledger) - b0) + " " + paths());
        // ... and one that has not ended by then is counted at the most it can cost (half price), then a normal call
        b0 = usd(ledger);
        seen.clear();
        batchStatus = "in_progress";
        replies.add(new Canned(200, message("end_turn", "normal paper", null), null));
        try (AnthropicWriter w = batchWriter(base, log, spend)) {
            check(w.writePaper("s", "u", Duration.ofSeconds(30)).equals("normal paper"), "C5: normal call after a cancel");
            check(Math.abs(usd(ledger) - b0 - one - w.worstCase("s", "u", true)) < 1e-9, "C5: a still-running cancelled batch is counted at its worst case: " + (usd(ledger) - b0));
        }
        // SO-4: a normal call that times out after it was sent is counted at the most it can cost
        b0 = usd(ledger);
        seen.clear(); replies.clear();
        delayMs = 2500;
        replies.add(new Canned(200, message("end_turn", "too late", null), null));
        try (AnthropicWriter w = new AnthropicWriter(cfg(false, "", KEY), log, spend, base)) {
            try { w.write("s", "u", Duration.ofMillis(1500)); throw new AssertionError("timeout"); }
            catch (java.io.IOException | java.util.concurrent.TimeoutException e) { /* expected */ }
            check(Math.abs(usd(ledger) - b0 - w.worstCase("s", "u", false)) < 1e-9, "SO-4: a timed-out call is counted at its worst case: " + (usd(ledger) - b0));
        }
        Thread.sleep(1200);   // let the late reply go
        replies.clear();
        System.out.println("ok C5/SO-4: late and timed-out calls counted");

        // W2: a request that went out and got no answer (the server hung up) is counted at the most it can cost, like a
        // timeout, so the ledger is not left locked: the batch falls back to a normal call, and the normal call really retries
        java.nio.file.Path dropLedger = dir.resolve("drop-spend.json");
        Spend dropSpend = new Spend(dropLedger, 10, null);
        seen.clear(); replies.clear();
        hangUpOn = "/v1/messages/batches";
        replies.add(new Canned(200, message("end_turn", "normal paper", null), null));
        try (AnthropicWriter w = batchWriter(base, log, dropSpend)) {
            check(w.writePaper("s", "u", Duration.ofSeconds(30)).equals("normal paper"), "W2: normal call after a batch request with no answer");
            check(Math.abs(usd(dropLedger) - one - w.worstCase("s", "u", true)) < 1e-9 && dropSpend.locked() == null,
                    "W2: the unanswered batch request is counted at its worst case, nothing left pending: " + java.nio.file.Files.readString(dropLedger));
        }
        b0 = usd(dropLedger);
        seen.clear();
        hangUpOn = "/v1/messages";
        replies.add(new Canned(200, message("end_turn", "second try", null), null));
        try (AnthropicWriter w = new AnthropicWriter(cfg(false, "", KEY), log, dropSpend, base)) {
            check(w.write("s", "u", Duration.ofSeconds(30)).equals("second try") && paths().equals(List.of("/v1/messages", "/v1/messages")), "W2: the retry reaches the server: " + paths());
            check(Math.abs(usd(dropLedger) - b0 - one - w.worstCase("s", "u", false)) < 1e-9 && dropSpend.locked() == null,
                    "W2: the unanswered call is counted at its worst case, nothing left pending: " + java.nio.file.Files.readString(dropLedger));
        }
        // the batch ended and the connection dropped while its results were fetched: it may be billed, so the same rule
        b0 = usd(dropLedger);
        seen.clear();
        String wasStatus = batchStatus;
        batchStatus = "ended";
        batchResult = "{\"custom_id\":\"paper\",\"result\":{\"type\":\"succeeded\",\"message\":" + message("end_turn", "batched paper", null) + "}}\n";
        hangUpOn = "/v1/messages/batches/msgbatch_test/results";
        replies.add(new Canned(200, message("end_turn", "normal paper", null), null));
        try (AnthropicWriter w = batchWriter(base, log, dropSpend)) {
            check(w.writePaper("s", "u", Duration.ofSeconds(30)).equals("normal paper") && paths().contains("/v1/messages/batches/msgbatch_test/results"),
                    "W2: normal call after the batch results got no answer: " + paths());
            check(Math.abs(usd(dropLedger) - b0 - one - w.worstCase("s", "u", true)) < 1e-9 && dropSpend.locked() == null,
                    "W2: the batch whose results got no answer is counted at its worst case, nothing left pending: " + java.nio.file.Files.readString(dropLedger));
        }
        batchStatus = wasStatus;
        System.out.println("ok W2: a dropped connection is counted at its worst case and does not lock the ledger");

        // 2. usage.iterations: each attempt at its own model's price (a refusal on Opus 5.5, then Opus 5 wrote it)
        String its = "\"iterations\":[{\"type\":\"message\",\"model\":\"claude-opus-5-5\",\"input_tokens\":1000,\"output_tokens\":0,"
                + "\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0},{\"type\":\"fallback_message\",\"model\":\"claude-opus-5\","
                + "\"input_tokens\":1000,\"output_tokens\":100,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}]";
        double before = usd(ledger);
        replies.add(new Canned(200, message("end_turn", "c", null).replace("\"cache_read_input_tokens\":600}", "\"cache_read_input_tokens\":600," + its + "}"), null));
        try (AnthropicWriter w = new AnthropicWriter(cfg(false, "", KEY), log, spend, base)) { w.write("s", "u", Duration.ofSeconds(30)); }
        check(Math.abs(usd(ledger) - before - (1000 * 4 + 1000 * 5 + 100 * 25) / 1e6) < 1e-9, "iterations summed: " + (usd(ledger) - before));
        System.out.println("ok usage.iterations priced per attempt");

        // 3. budget reached: no request, a clear error, the owner told once a month
        String month = java.time.YearMonth.now().toString();
        java.nio.file.Files.writeString(ledger, "{\"month\":\"" + month + "\",\"usd\":1.0}");
        seen.clear();
        for (int i = 0; i < 2; i++) {
            try (AnthropicWriter w = batchWriter(base, log, spend)) { w.writePaper("s", "u", Duration.ofSeconds(30)); throw new AssertionError("over budget"); }
            catch (java.io.IOException e) { check(e.getMessage().contains("monthly AI budget reached"), "budget message: " + e.getMessage()); }
        }
        check(seen.isEmpty(), "no request once the budget is reached: " + paths());
        check(alerts.size() == 1 && alerts.get(0).contains("writer.monthly-budget-usd"), "one owner alert: " + alerts);
        System.out.println("ok budget reached -> no call, one alert");

        // 4. last month's ledger starts over
        java.nio.file.Files.writeString(ledger, "{\"month\":\"2000-01\",\"usd\":99.0,\"alerted\":\"2000-01\"}");
        replies.add(new Canned(200, message("end_turn", "d", null), null));
        try (AnthropicWriter w = new AnthropicWriter(cfg(false, "", KEY), log, spend, base)) { w.write("s", "u", Duration.ofSeconds(30)); }
        check(Math.abs(usd(ledger) - one) < 1e-9 && java.nio.file.Files.readString(ledger).contains(month), "new month resets the ledger");
        System.out.println("ok new month starts at 0");

        // 5. fails closed: a broken ledger stops paid calls
        java.nio.file.Files.writeString(ledger, "not json");
        seen.clear();
        try (AnthropicWriter w = new AnthropicWriter(cfg(false, "", KEY), log, spend, base)) { w.write("s", "u", Duration.ofSeconds(30)); throw new AssertionError("broken ledger"); }
        catch (java.io.IOException e) { check(e.getMessage().contains("can't be read"), "broken ledger message: " + e.getMessage()); }
        check(seen.isEmpty(), "no request with a broken ledger");
        // budget 0 = no cap, ledger not even read
        replies.add(new Canned(200, message("end_turn", "e", null), null));
        try (AnthropicWriter w = new AnthropicWriter(cfg(false, "", KEY), log, new Spend(ledger, 0, null), base)) {
            check(w.write("s", "u", Duration.ofSeconds(30)).equals("e"), "budget 0 = no cap");
        }
        System.out.println("ok broken ledger fails closed; 0 = no cap");

        // 6. fails closed: a ledger that can't be SAVED stops paid calls before any money is spent
        java.nio.file.Files.delete(ledger);
        Spend saved = new Spend(ledger, 1.00, alerts::add);
        saved.check();   // writable: fine, and the ledger now exists
        saved.add(0);    // the preflight above did not send a request
        java.nio.file.Path data = ledger.getParent();
        var rw = java.nio.file.Files.getPosixFilePermissions(data);
        java.nio.file.Files.setPosixFilePermissions(data, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        replies.add(new Canned(200, message("end_turn", "paid", null), null));
        try {
            seen.clear();
            try (AnthropicWriter w = new AnthropicWriter(cfg(false, "", KEY), log, saved, base)) { w.write("s", "u", Duration.ofSeconds(30)); throw new AssertionError("unsaveable ledger"); }
            catch (java.io.IOException e) { check(!(e.getMessage() != null && e.getMessage().contains("monthly AI budget reached")), "unsaveable ledger: " + e); }
            check(seen.isEmpty(), "no paid request when the ledger can't be saved: " + paths());
        } finally {
            replies.clear();
            java.nio.file.Files.setPosixFilePermissions(data, rw);
        }
        System.out.println("ok unsaveable ledger fails closed");

        // 7. the ledger breaks after a paid batch reply: the reply is kept, no second paid call
        seen.clear(); replies.clear();
        replies.add(new Canned(200, message("end_turn", "second paid call", null), null));
        batchStatus = "ended";
        batchResult = "{\"custom_id\":\"paper\",\"result\":{\"type\":\"succeeded\",\"message\":" + message("end_turn", "batched paper", null) + "}}\n";
        onBatchCreate = () -> {
            try { java.nio.file.Files.setPosixFilePermissions(data, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x")); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        };
        try (AnthropicWriter w = batchWriter(base, log, saved)) {
            check("batched paper".equals(w.writePaper("s", "u", Duration.ofSeconds(30))), "the paid batch reply is kept");
        } finally {
            onBatchCreate = null;
            java.nio.file.Files.setPosixFilePermissions(data, rw);
        }
        check(!paths().contains("/v1/messages"), "no second paid call after the ledger broke: " + paths());
        try { new Spend(ledger, 1, null).check(); throw new AssertionError("recovered I/O must not forget the paid reply"); }
        catch (IOException e) { check(e.getMessage().contains("unsettled request"), "new edition still blocked after folder permissions recover"); }
        replies.clear();
        System.out.println("ok ledger broken after a paid batch: reply kept, one paid request");
        pendingLedgerTests();

        // A retry must re-check the ledger, even when another accounting update used the remaining budget.
        java.nio.file.Path retryLedger = dir.resolve("retry-spend.json");
        Spend retrySpend = new Spend(retryLedger, 1, null);
        seen.clear(); replies.clear();
        replies.add(new Canned(429, "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"retry\"}}", "0.01"));
        replies.add(new Canned(200, message("end_turn", "must not be requested", null), null));
        onMessageCreate = () -> {
            onMessageCreate = null;
            try { retrySpend.add(1); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        };
        try (AnthropicWriter w = new AnthropicWriter(cfg(false, "", KEY), log, retrySpend, base)) {
            try { w.write("s", "u", Duration.ofSeconds(30)); throw new AssertionError("retry over budget"); }
            catch (IOException e) { check(e.getMessage().contains("monthly AI budget reached"), "retry checks the updated budget"); }
        } finally { onMessageCreate = null; replies.clear(); }
        check(seen.size() == 1, "no second billable attempt after reaching budget");

        // Only a terminal non-success result proves an ended batch is free; a missing result leaves it unresolved.
        for (String result : List.of("{\"type\":\"canceled\"}", "{\"type\":\"expired\"}",
                "{\"type\":\"errored\",\"error\":{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"x\"}}}")) {
            java.nio.file.Path terminalLedger = java.nio.file.Files.createTempDirectory("terminal-batch").resolve("spend.json");
            seen.clear();
            batchStatus = "ended";
            batchResult = "{\"custom_id\":\"paper\",\"result\":" + result + "}\n";
            replies.add(new Canned(200, message("end_turn", "fallback", null), null));
            try (AnthropicWriter w = batchWriter(base, log, new Spend(terminalLedger, 1, null))) {
                check(w.writePaper("s", "u", Duration.ofSeconds(30)).equals("fallback"), "terminal unbilled result permits normal fallback");
            }
            check(Math.abs(usd(terminalLedger) - one) < 1e-9, "only the fallback is charged after a terminal unbilled result");
        }
        java.nio.file.Path unknownLedger = dir.resolve("unknown-batch.json");
        seen.clear(); replies.clear();
        batchResult = "";
        try (AnthropicWriter w = batchWriter(base, log, new Spend(unknownLedger, 1, null))) {
            try { w.writePaper("s", "u", Duration.ofSeconds(30)); throw new AssertionError("missing batch result"); }
            catch (IOException e) { check(e.getMessage().contains("unsettled request"), "missing result blocks a paid fallback"); }
        }
        check(!paths().contains("/v1/messages"), "no fallback when batch cost is unknown");
        try { new Spend(unknownLedger, 1, null).check(); throw new AssertionError("unknown batch survived into a new edition"); }
        catch (IOException e) { check(e.getMessage().contains("unsettled request"), "new edition also blocks an unresolved batch"); }
        System.out.println("ok terminal batch outcomes settle zero; missing results block the next paid call");
    }

    static void pendingLedgerTests() throws Exception {
        var dir = java.nio.file.Files.createTempDirectory("mh-spend-pending");
        var ledger = dir.resolve("spend.json");
        Spend first = new Spend(ledger, 10, null);
        first.check();
        check(JsonParser.parseString(java.nio.file.Files.readString(ledger)).getAsJsonObject().get("pending").getAsBoolean(), "pending persisted before a paid attempt");
        String lockedLine = first.locked();   // W2: what /masthead status shows
        check(lockedLine != null && lockedLine.contains(ledger.toString()) && lockedLine.contains("docs/LEDGER-RECOVERY.md"), "locked ledger: the status line names the file and the recovery page: " + lockedLine);
        check(new Spend(ledger, 0, null).locked() == null, "no budget, no ledger: never locked");
        var obstruction = java.nio.file.Files.createDirectory(dir.resolve("spend.json.new"));
        List<String> messages = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) { messages.add(r.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        first.addPaid(12, logger);   // the already-paid reply remains usable, even though the accounting write fails
        java.nio.file.Files.delete(obstruction);
        check(messages.stream().anyMatch(s -> s.contains("unrecorded cost $12.000000")), "known missing cost is logged for reconciliation");
        for (Spend next : List.of(first, new Spend(ledger, 10, null))) {
            try { next.check(); throw new AssertionError("lost paid cost must not allow another request"); }
            catch (IOException e) { check(e.getMessage().contains("unsettled request"), "same and new instances fail closed after write recovery"); }
        }
        check(usd(ledger) == 0, "failed settlement did not claim it saved the cost");
        Spend repaired = new Spend(ledger, 10, null);
        repaired.add(12);   // explicit reconciliation of this fixture's known unrecorded charge
        check(usd(ledger) == 12 && !java.nio.file.Files.readString(ledger).contains("pending"), "reconciliation records the missing cost and clears pending atomically");
        check(repaired.locked() == null, "a settled ledger is not locked");
        try { repaired.check(); throw new AssertionError("reconciled cost exceeds budget"); }
        catch (IOException e) { check(e.getMessage().contains("monthly AI budget reached"), "reconciled charge still enforces the cap"); }

        String priorMonth = java.time.YearMonth.now().minusMonths(1).toString();
        String prior = "{\"month\":\"" + priorMonth + "\",\"usd\":7,\"pending\":true}";
        java.nio.file.Files.writeString(ledger, prior);
        Spend rollover = new Spend(ledger, 10, null);
        try { rollover.check(); throw new AssertionError("new month must not erase an unresolved request"); }
        catch (IOException e) { check(e.getMessage().contains("unsettled request") && java.nio.file.Files.readString(ledger).equals(prior), "pending survives month rollover unchanged"); }
        rollover.add(2);
        check(usd(ledger) == 9 && java.nio.file.Files.readString(ledger).contains(priorMonth), "late settlement belongs to the reserved month");
        rollover.check();
        check(usd(ledger) == 0 && java.nio.file.Files.readString(ledger).contains(java.time.YearMonth.now().toString()), "settled previous month can roll over");
        new Spend(ledger, 10, null).add(0);
        check(!java.nio.file.Files.readString(ledger).contains("pending"), "confirmed zero-cost outcome also settles a reservation");
        new Spend(ledger, 10, null).check();
        try { new Spend(ledger, 10, null).add(Double.NaN); throw new AssertionError("invalid cost"); }
        catch (IOException e) { check(java.nio.file.Files.readString(ledger).contains("pending"), "invalid cost cannot clear pending"); }
        System.out.println("ok persistent pending, recovery, zero settlement and month rollover");
    }

    static void keyFileTests(String base, Logger log) throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("key");
        java.nio.file.Path f = dir.resolve("anthropic-key");
        java.nio.file.Files.writeString(f, KEY + "\n");
        java.nio.file.Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        // the file wins over an inline key
        seen.clear();
        replies.add(new Canned(200, message("end_turn", "k", null), null));
        try (AnthropicWriter w = new AnthropicWriter(cfg(false, f.toString(), "sk-wrong-inline"), log, null, base)) { w.write("s", "u", Duration.ofSeconds(30)); }
        check(seen.get(0).keyOk, "key file used, inline ignored");
        // a key file that can't be read is an error, not a quiet fall-through to another key
        seen.clear();
        java.nio.file.Path invalidLedger = dir.resolve("invalid-key-spend.json");
        try (AnthropicWriter w = new AnthropicWriter(cfg(false, dir.resolve("missing").toString(), KEY), log, new Spend(invalidLedger, 1, null), base)) {
            w.write("s", "u", Duration.ofSeconds(30));
            throw new AssertionError("missing key file");
        } catch (java.io.IOException e) {
            check(e.getMessage().contains("api-key-file can't be read") && !e.getMessage().contains(KEY), "missing key file message: " + e.getMessage());
        }
        check(seen.isEmpty(), "no request without the key file");
        check(!java.nio.file.Files.exists(invalidLedger), "invalid configuration does not reserve spend");
        System.out.println("ok api-key-file");
    }

    static double usd(java.nio.file.Path ledger) throws IOException {
        return JsonParser.parseString(java.nio.file.Files.readString(ledger)).getAsJsonObject().get("usd").getAsDouble();
    }

    static String batch(String status) {
        return "{\"id\":\"msgbatch_test\",\"type\":\"message_batch\",\"processing_status\":\"" + status + "\","
                + "\"request_counts\":{\"processing\":0,\"succeeded\":1,\"errored\":0,\"canceled\":0,\"expired\":0},"
                + "\"created_at\":\"2026-11-05T05:30:00Z\",\"expires_at\":\"2026-11-06T05:30:00Z\",\"ended_at\":null,"
                + "\"archived_at\":null,\"cancel_initiated_at\":null,\"results_url\":null}";
    }

    static String message(String stop, String text, String category) {
        String content = text == null ? "[]" : "[{\"type\":\"text\",\"text\":" + new com.google.gson.Gson().toJson(text) + "}]";
        String details = category == null ? "null" : "{\"type\":\"refusal\",\"category\":\"" + category + "\",\"explanation\":\"test\"}";
        return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\",\"content\":" + content
                + ",\"stop_reason\":\"" + stop + "\",\"stop_sequence\":null,\"stop_details\":" + details
                + ",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":600}}";
    }

    static String redact(String body) {
        return body.replaceAll("(STYLE GUIDE )+", "STYLE GUIDE …");
    }

    static void check(boolean b, String what) {
        if (!b) throw new AssertionError(what);
    }
}
