package io.github.eastmadc.masthead.writer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eastmadc.masthead.config.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * writer.mode: openai-compatible (Beta). One Chat Completions request to {@code <base-url>/chat/completions}, which
 * OpenAI, OpenRouter, Gemini, Groq, Mistral, Together, Ollama, LM Studio and vLLM all accept. JDK HttpClient + Gson, no
 * new library.
 *
 * <p>Safety: the key comes from api-key-file, then MASTHEAD_AI_KEY, then the inline api-key (never another company's
 * environment variable, so changing base-url can't send that key somewhere new). Redirects are not followed. Plain http
 * only to this machine or the LAN. Replies over 200 KB are refused. extra-body can't change model, messages, stream or
 * the token limit. finish_reason length or content_filter, or a refusal, is a failure (plain edition). A leading
 * {@code <think>} block is removed. Same retry policy as {@link AnthropicWriter}, and the reply body too must arrive by the
 * deadline (the JDK's request timeout only covers the headers). The key is never logged.
 */
public final class OpenAiCompatibleWriter implements Writer {
    static final long MAX_TOKENS = 16_000;
    static final int MAX_ATTEMPTS = AnthropicWriter.MAX_ATTEMPTS;
    static final int MAX_REPLY = 200 * 1024;
    static final String ENV = "MASTHEAD_AI_KEY";
    private static final String P = "writer.openai-compatible.";

    private final Settings.OpenAi cfg;
    private final Logger log;
    private final Spend spend;   // null = no ledger
    private HttpClient http;
    private boolean warnedUncounted;

    public OpenAiCompatibleWriter(Settings.OpenAi cfg, Logger log, Spend spend) {
        this.cfg = cfg;
        this.log = log;
        this.spend = spend;
    }

    @Override
    public String name() {
        String host;
        try { host = URI.create(cfg.baseUrl()).getHost(); } catch (RuntimeException e) { host = "?"; }
        return "openai-compatible (" + host + ", " + cfg.model() + ")";   // the host only: no user:pass@, path or query
    }

    @Override
    public String write(String system, String user, Duration timeout) throws Exception {
        if (cfg.baseUrl().isBlank() || cfg.model().isBlank()) throw new IllegalStateException(P + "base-url and " + P + "model must both be set");
        URI uri = URI.create(cfg.baseUrl().replaceAll("/+$", "") + "/chat/completions");
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !("http".equalsIgnoreCase(uri.getScheme()) && nearby(uri.getHost())))
            throw new IOException(P + "base-url must use https:// unless the server is on this machine or your LAN");
        String key = Writer.apiKey(cfg.apiKeyFile(), ENV, cfg.apiKey(), P, log);
        String tokenField = "max_tokens";
        long deadline = System.nanoTime() + timeout.toNanos();
        for (int attempt = 1; ; attempt++) {
            long left = deadline - System.nanoTime();
            if (left < 1_000_000_000L) throw new TimeoutException("AI server: out of time after " + (attempt - 1) + " attempt(s)");
            HttpRequest.Builder rq = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(left))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(system, user, tokenField).toString(), StandardCharsets.UTF_8));
            try {
                if (!key.isEmpty()) rq.header("Authorization", "Bearer " + key);
            } catch (IllegalArgumentException e) {   // its message quotes the whole key: never pass it on
                throw new IOException(P + "API key can't be sent (unexpected character)");
            }
            String failure;
            long retryAfterMs = -1;
            HttpResponse<InputStream> r = null;
            byte[] raw = null;
            HttpRequest request = rq.build();
            HttpClient client = client();
            if (spend != null) spend.check();   // reserve every attempt, including the token-field compatibility retry
            try {
                r = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                raw = readBody(r.body(), deadline);
                failure = null;
            } catch (InterruptedException e) {   // stopped mid-call (plugin shutdown): a paid server keeps pending
                settleFree();
                throw e;
            } catch (IOException e) {   // connection trouble or a timeout: worth another try
                failure = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + scrub(e.getMessage(), key));
                // a request that went out and got no answer (timeout, dropped connection) may still be billed: count the most it can cost
                if (spend != null) {
                    if (AnthropicWriter.notSent(e) || (r != null && r.statusCode() != 200) || freeLocal()) spend.addPaid(0, log);
                    else if (worstCase(system, user) > 0) spend.addPaid(worstCase(system, user), log);
                    // No known price: keep pending, rather than silently count zero.
                }
            }
            if (failure == null) {
                if (raw.length > MAX_REPLY) {
                    settleFree();
                    throw new IOException("AI server: reply over " + MAX_REPLY / 1024 + " KB; refused");
                }
                String text = new String(raw, StandardCharsets.UTF_8);
                int s = r.statusCode();
                if (s == 200) return extract(text);
                if (spend != null) spend.addPaid(0, log);   // explicit API rejection / redirect, no completion to bill
                failure = scrub("HTTP " + s + errorMessage(text), key);
                if (s == 400 && tokenField.equals("max_tokens") && text.contains("max_completion_tokens")) {   // newer OpenAI models
                    tokenField = "max_completion_tokens";
                    attempt--;
                    continue;
                }
                if (s != 408 && s != 409 && s != 429 && s < 500) throw new IOException("AI server: " + failure);
                retryAfterMs = r.headers().firstValue("retry-after").map(OpenAiCompatibleWriter::seconds).orElse(-1L);
            }
            if (attempt >= MAX_ATTEMPTS) throw new IOException("AI server failed after " + attempt + " attempts: " + failure);
            long waitMs = retryAfterMs > 0 ? Math.min(retryAfterMs, 60_000) : 5_000L * (1L << (2 * (attempt - 1)));   // 5s, 20s, 80s
            left = deadline - System.nanoTime();
            if (waitMs * 1_000_000L >= left - 5_000_000_000L) throw new IOException("AI server failed and no time left to retry: " + failure);
            if (log != null) log.warning("AI server: " + failure + " - retrying in " + waitMs / 1000 + "s (attempt " + (attempt + 1) + "/" + MAX_ATTEMPTS + ")");
            Thread.sleep(waitMs);
        }
    }

    /**
     * The reply body, at most MAX_REPLY + 1 bytes, read by the deadline. HttpRequest.timeout only covers the wait for the
     * headers; a server that sends them and then stalls the body would otherwise block the edition for ever.
     */
    static byte[] readBody(InputStream in, long deadline) throws IOException, InterruptedException {
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        Thread t = Thread.ofVirtual().start(() -> {
            try { f.complete(in.readNBytes(MAX_REPLY + 1)); } catch (Throwable e) { f.completeExceptionally(e); }
        });
        try (in) {
            return f.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            t.interrupt();   // the close above unblocks it too
            throw new java.net.http.HttpTimeoutException("reply body not finished in time");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        }
    }

    /** extra-body first, then our own fields on top (they always win). */
    JsonObject body(String system, String user, String tokenField) {
        JsonObject b = JsonParser.parseString(cfg.extraBody() == null || cfg.extraBody().isBlank() ? "{}" : cfg.extraBody()).getAsJsonObject();
        for (String k : new String[]{"model", "messages", "stream", "max_tokens", "max_completion_tokens"}) b.remove(k);
        b.addProperty("model", cfg.model());
        JsonArray msgs = new JsonArray();
        if (system != null && !system.isBlank()) msgs.add(msg("system", system));
        msgs.add(msg("user", user == null ? "" : user));
        b.add("messages", msgs);
        b.addProperty(tokenField, MAX_TOKENS);
        if (!cfg.reasoningEffort().isBlank()) b.addProperty("reasoning_effort", cfg.reasoningEffort());
        return b;
    }

    private static JsonObject msg(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    String extract(String text) throws Exception {
        JsonObject r, m;
        String finish, content, refusal;
        try {
            r = JsonParser.parseString(text).getAsJsonObject();
            JsonArray ch = r.getAsJsonArray("choices");
            if (ch == null || ch.isEmpty()) throw new IOException("reply had no choices");
            JsonObject c0 = ch.get(0).getAsJsonObject();
            finish = str(c0.get("finish_reason"));
            m = c0.getAsJsonObject("message");
            content = m == null ? "" : str(m.get("content"));
            refusal = m == null ? "" : str(m.get("refusal"));
        } catch (RuntimeException e) {
            settleFree();   // a paid server keeps pending: it may have billed this reply
            throw new IOException("reply was not the expected JSON (" + e.getClass().getSimpleName() + ")");
        }
        double usd = cost(r.get("usage"));
        if (spend != null) spend.addPaid(usd, log);   // before the checks below: a refused or cut-off reply is billed too
        if (!warnedUncounted && uncounted(r.get("usage"))) {
            warnedUncounted = true;
            if (log != null) log.warning(P + "price-per-million-input/output are 0 and this server reports no cost, so writer.monthly-budget-usd "
                    + "can't count its spend. Set the prices (OpenRouter reports the cost itself), or rely on your provider's spend limit.");
        }
        if (!refusal.isBlank()) throw new RefusalException("refusal");
        if (finish.equals("content_filter")) throw new RefusalException("content_filter");
        if (finish.equals("length")) throw new IOException("AI server: reply cut off at the length limit (" + MAX_TOKENS + " tokens)");
        String out = content.replaceFirst("^(\\s*<think>[\\s\\S]*?</think>)+\\s*", "");
        if (out.isBlank() || out.stripLeading().startsWith("<think>")) throw new IOException("AI server: reply had no text (finish_reason " + finish + ")");
        if (log != null) log.info("AI server: " + cfg.model() + " finish=" + finish + String.format(java.util.Locale.ROOT, " cost=$%.4f", usd));
        return out;
    }

    /** The most one call can cost at the configured prices: every prompt character a token, plus the whole reply limit. */
    double worstCase(String system, String user) {
        long chars = (system == null ? 0 : system.length()) + (user == null ? 0 : user.length());
        return (chars * cfg.pricePerMillionInput() + MAX_TOKENS * cfg.pricePerMillionOutput()) / 1e6;
    }

    /** usage.cost when the server reports it (OpenRouter), else tokens times the configured prices. */
    double cost(JsonElement usage) {
        if (!(usage instanceof JsonObject u)) return 0;
        try {
            if (u.has("cost") && u.get("cost").isJsonPrimitive()) return Math.max(0, u.get("cost").getAsDouble());
            long in = u.has("prompt_tokens") ? u.get("prompt_tokens").getAsLong() : 0, out = u.has("completion_tokens") ? u.get("completion_tokens").getAsLong() : 0;
            return (in * cfg.pricePerMillionInput() + out * cfg.pricePerMillionOutput()) / 1e6;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** True when there is a budget but this reply can't be counted: no usage.cost, both prices 0, and a server that isn't on this machine or the LAN. */
    boolean uncounted(JsonElement usage) {
        if (spend == null || !spend.on() || cfg.pricePerMillionInput() > 0 || cfg.pricePerMillionOutput() > 0) return false;
        if (usage instanceof JsonObject u && u.has("cost") && u.get("cost").isJsonPrimitive()) return false;
        return !freeLocal();
    }

    /** A free local server costs nothing: settle its reservation at $0 so a bad reply or a stop can't leave it pending. */
    private void settleFree() {
        if (spend != null && freeLocal()) spend.addPaid(0, log);
    }

    /** Both prices 0 and the server on this machine or the LAN (Ollama, LM Studio): its calls cost nothing. */
    boolean freeLocal() {
        if (cfg.pricePerMillionInput() > 0 || cfg.pricePerMillionOutput() > 0) return false;
        try { return nearby(URI.create(cfg.baseUrl()).getHost()); } catch (IOException | RuntimeException e) { return false; }
    }

    /** True when every address of host is this machine or a private network (where plain http is fine). */
    static boolean nearby(String host) throws IOException {
        if (host == null) return false;
        for (InetAddress a : InetAddress.getAllByName(host)) {
            boolean ula = a instanceof Inet6Address && (a.getAddress()[0] & 0xfe) == 0xfc;   // fc00::/7
            if (!(a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || ula)) return false;
        }
        return true;
    }

    private synchronized HttpClient client() {
        if (http == null) http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(30)).build();
        return http;
    }

    /** ": <the server's error message>", short, or "" if there is none. */
    private static String errorMessage(String body) {
        String m;
        try {
            JsonElement e = JsonParser.parseString(body).getAsJsonObject().get("error");
            m = e instanceof JsonObject o ? str(o.get("message")) : str(e);
        } catch (RuntimeException e) {
            m = body;
        }
        m = m.replaceAll("[\\r\\n\\t]+", " ").strip();
        return m.isEmpty() ? "" : ": " + (m.length() > 200 ? m.substring(0, 200) : m);
    }

    private static String scrub(String s, String key) {
        return key.isEmpty() ? s : s.replace(key, "[key]");
    }

    private static String str(JsonElement e) {
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    private static long seconds(String v) {
        try { return (long) (Double.parseDouble(v.strip()) * 1000); } catch (RuntimeException e) { return -1; }
    }

    @Override
    public synchronized void close() {
        if (http != null) {
            http.close();
            http = null;
        }
    }
}
