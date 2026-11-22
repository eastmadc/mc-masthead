package io.github.eastmadc.masthead.writer;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlock;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaUsage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.anthropic.models.beta.messages.batches.BatchCreateParams;
import com.anthropic.models.beta.messages.batches.BetaMessageBatch;
import com.anthropic.models.beta.messages.batches.BetaMessageBatchIndividualResponse;
import com.anthropic.models.messages.Model;
import io.github.eastmadc.masthead.config.Settings;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * writer.mode: anthropic. Calls the Claude Messages API with the official Java SDK (com.anthropic:anthropic-java, loaded by
 * Paper from plugin.yml {@code libraries:}). This is the only class that touches SDK types.
 *
 * <p>Request: model and effort from settings (output_config.effort), {@code system} as one text block with
 * cache_control ephemeral (stable prefix, cached for the rewrite and later runs), {@code user} as the user message,
 * max_tokens 32000 (thinking counts toward it; Haiku at high effort used all of 16000 on one busy day), non-streaming:
 * we always pass our own timeout, so the SDK's "streaming required" check does not apply. Server-side refusal fallback is
 * on ({@code fallbacks: "default"}, beta {@code server-side-fallback-2026-07-01}), so a classifier false positive is
 * retried on another model by the API.
 *
 * <p>Batch (writer.anthropic.batch, the day's first draft only): the draft goes in as a one-request Message Batch, at half
 * price. Batches usually take 1.5-6 minutes; after {@link #BATCH_DEADLINE} we cancel it and make a normal call. Any other
 * batch failure (including a refusal, since batches can't use the server-side fallback) also gets a normal call. The
 * batch is then deleted (best effort): Anthropic otherwise keeps it, with the whole prompt and the paper, for 29 days.
 *
 * <p>Money: every reply's usage (usage.iterations when present, so fallback attempts count) is priced from
 * {@link #PRICES} and added to the {@link Spend} ledger; {@link Spend#check()} runs before each call.
 *
 * <p>Retries (ours, SDK retries off so one policy applies): 408/409/429/5xx and connection errors, up to
 * {@link #MAX_ATTEMPTS} attempts with backoff (honouring retry-after), all inside the caller's timeout. Other 4xx throw at
 * once. stop_reason refusal throws {@link RefusalException}; max_tokens or no text throws. The API key is never logged.
 */
public final class AnthropicWriter implements Writer {
    static final long MAX_TOKENS = 32_000;
    static final int MAX_ATTEMPTS = 4;
    static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");
    static final Duration BATCH_DEADLINE = Duration.ofMinutes(20);
    static final String ENV = "ANTHROPIC_API_KEY";
    /**
     * US dollars per million tokens: input, cache write (5 minutes, 1.25x input), cache read, output. Checked 2026-11-05.
     * shortcut: Haiku's higher price above 100K prompt tokens is ignored (the paper's prompt is about 10K); add it if prompts grow.
     */
    static final Map<String, double[]> PRICES = Map.of(
            "claude-opus-5-5", new double[]{4, 5, 0.20, 20},
            "claude-sonnet-5-5", new double[]{2, 2.5, 0.10, 10},
            "claude-haiku-5-5", new double[]{0.10, 0.125, 0.01, 0.50},
            "claude-fable-5-1", new double[]{10, 12.5, 0.25, 50},
            "claude-opus-5", new double[]{5, 6.25, 0.50, 25});
    /** An unknown model is priced like the dearest one, so the budget errs on the safe side. */
    static final double[] UNKNOWN_PRICE = PRICES.get("claude-fable-5-1");

    private final String model;
    private final String effort;
    private final boolean batch;
    private final Settings.WriterCfg cfg;
    private final String baseUrl;   // null = SDK default (tests point it at a local server)
    private final Logger log;
    private final Spend spend;      // null = no ledger
    private AnthropicClient client;
    Duration batchDeadline = BATCH_DEADLINE;   // tests shorten these three
    long pollMs = 20_000;
    Duration cancelWait = Duration.ofSeconds(60);   // after a cancel: how long to wait for the batch to end

    public AnthropicWriter(Settings.WriterCfg cfg, Logger log, Spend spend) {
        this(cfg, log, spend, null);
    }

    AnthropicWriter(Settings.WriterCfg cfg, Logger log, Spend spend, String baseUrl) {
        this.model = cfg.model() == null || cfg.model().isBlank() ? "claude-opus-5-5" : cfg.model().strip();
        String e = cfg.effort() == null ? "" : cfg.effort().strip().toLowerCase(java.util.Locale.ROOT);
        this.effort = EFFORTS.contains(e) ? e : "medium";
        this.batch = cfg.batch();
        this.cfg = cfg;
        this.baseUrl = baseUrl;
        this.log = log;
        this.spend = spend;
    }

    @Override
    public String name() {
        return "anthropic (" + model + ", effort " + effort + (batch ? ", batch" : "") + ")";
    }

    private synchronized AnthropicClient client() throws IOException {
        if (client == null) {
            String apiKey = Writer.apiKey(cfg.apiKeyFile(), ENV, cfg.apiKey(), "writer.anthropic.", log);
            if (apiKey.isEmpty())
                throw new IllegalStateException("no Anthropic API key: set writer.anthropic.api-key-file, the " + ENV + " environment variable, or writer.anthropic.api-key");
            AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().apiKey(apiKey).maxRetries(0);
            if (baseUrl != null) b.baseUrl(baseUrl);
            client = b.build();
        }
        return client;
    }

    MessageCreateParams params(String system, String user) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(effort)).build())
                .addUserMessage(user);
        if (!model.startsWith("claude-haiku")) b.fallbacksDefault().addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01);   // Haiku has none
        if (system != null && !system.isBlank()) b.systemOfBetaTextBlockParams(List.of(systemBlock(system)));
        return b.build();
    }

    /** The same request as one Message Batch, without the server-side fallback (batches reject it). */
    BatchCreateParams batchParams(String system, String user) {
        BatchCreateParams.Request.Params.Builder p = BatchCreateParams.Request.Params.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(effort)).build())
                .addUserMessage(user);
        if (system != null && !system.isBlank()) p.systemOfBetaTextBlockParams(List.of(systemBlock(system)));
        return BatchCreateParams.builder()
                .addRequest(BatchCreateParams.Request.builder().customId("paper").params(p.build()).build())
                .build();
    }

    private static BetaTextBlockParam systemBlock(String system) {
        return BetaTextBlockParam.builder().text(system).cacheControl(BetaCacheControlEphemeral.builder().build()).build();
    }

    @Override
    public String writePaper(String system, String user, Duration timeout) throws Exception {
        if (!batch) return write(system, user, timeout);
        AnthropicClient c = client();
        BatchCreateParams params = batchParams(system, user);
        RequestOptions o = RequestOptions.builder().timeout(Duration.ofSeconds(60)).build();
        String id = null;
        if (spend != null) spend.check();
        try {
            try {
                id = c.beta().messages().batches().create(params, o).id();
                long deadline = System.nanoTime() + batchDeadline.toNanos();
                boolean ended = false;
                while (!ended && System.nanoTime() + pollMs * 1_000_000L <= deadline) {
                    Thread.sleep(pollMs);
                    try {
                        ended = c.beta().messages().batches().retrieve(id, o).processingStatus().equals(BetaMessageBatch.ProcessingStatus.ENDED);
                    } catch (AnthropicServiceException | AnthropicIoException | AnthropicRetryableException e) {
                        if (log != null) log.warning("Anthropic batch: checking on it failed (" + describe(e) + "); still waiting");
                    }
                }
                if (!ended) {
                    // A request already running when the cancel arrives still finishes and is billed: wait a little for
                    // the batch to end, so its result is used (or at least counted) instead of being lost.
                    cancel(c, id, o);
                    long until = System.nanoTime() + cancelWait.toNanos();
                    while (!ended && System.nanoTime() < until) {
                        Thread.sleep(Math.min(pollMs, Math.max(1, cancelWait.toMillis())));
                        try { ended = c.beta().messages().batches().retrieve(id, o).processingStatus().equals(BetaMessageBatch.ProcessingStatus.ENDED); }
                        catch (AnthropicServiceException | AnthropicIoException | AnthropicRetryableException e) { /* try again */ }
                    }
                    if (!ended && spend != null) spend.addPaid(worstCase(system, user, true), log);   // may still be billed: count the most it can cost
                    if (log != null) log.warning("Anthropic batch: not done after " + batchDeadline.toSeconds() / 60 + " minutes; cancelled it"
                            + (ended ? "" : ", making a normal call"));
                }
                if (ended) {
                    BetaMessageBatchIndividualResponse r;
                    try (var results = c.beta().messages().batches().resultsStreaming(id, o)) {
                        r = results.stream().filter(x -> "paper".equals(x.customId())).findFirst().orElse(null);
                    }
                    if (r != null && r.result().isSucceeded()) return extract(r.result().asSucceeded().message(), true);
                    // Only a terminal per-request result proves this one was not billed. A cancel acknowledgement
                    // alone does not: it may race a successful request, whose usage must still be counted above.
                    if (spend != null && r != null && (r.result().isErrored() || r.result().isExpired() || r.result().isCanceled()))
                        spend.addPaid(0, log);
                    if (log != null) log.warning("Anthropic batch: " + (r == null ? "no result" : r.result().isErrored() ? "errored"
                            : r.result().isExpired() ? "expired" : "cancelled") + "; making a normal call");
                }
            } catch (InterruptedException e) {
                if (id != null) cancel(c, id, o);
                throw e;
            } catch (Exception e) {   // a refusal too: the normal call has the server-side fallback, the batch had none
                // rejected batch creation / no connection: no batch to bill. Sent with no answer: a batch may exist, count the most it can cost
                if (spend != null && id == null)
                    spend.addPaid(e instanceof AnthropicServiceException || notSent(e) ? 0 : worstCase(system, user, true), log);
                // the batch ended and the connection dropped while its results were fetched (the only call here that lets one through): same
                else if (spend != null && (e instanceof AnthropicIoException || e instanceof AnthropicRetryableException))
                    spend.addPaid(worstCase(system, user, true), log);
                if (log != null) log.warning("Anthropic batch failed (" + describe(e) + "); making a normal call");
            }
            return write(system, user, timeout);
        } finally {
            if (id != null) delete(c, id, o);
        }
    }

    /** Best effort: a batch that is still being cancelled can't be deleted yet; it then stays at Anthropic for 29 days. */
    private void delete(AnthropicClient c, String id, RequestOptions o) {
        try { c.beta().messages().batches().delete(id, o); }
        catch (RuntimeException e) { if (log != null) log.warning("Anthropic batch: could not delete it, so Anthropic keeps it for 29 days (" + describe(e) + ")"); }
    }

    private void cancel(AnthropicClient c, String id, RequestOptions o) {
        try { c.beta().messages().batches().cancel(id, o); }
        catch (RuntimeException e) { if (log != null) log.warning("Anthropic batch: could not cancel it (" + describe(e) + ")"); }
    }

    @Override
    public String write(String system, String user, Duration timeout) throws Exception {
        MessageCreateParams params = params(system, user);
        AnthropicClient c = client();
        long deadline = System.nanoTime() + timeout.toNanos();
        for (int attempt = 1; ; attempt++) {
            long left = deadline - System.nanoTime();
            if (left < 1_000_000_000L) throw new TimeoutException("Anthropic API: out of time after " + (attempt - 1) + " attempt(s)");
            Exception failure;
            long retryAfterMs = -1;
            RequestOptions options = RequestOptions.builder().timeout(Duration.ofNanos(left)).build();
            if (spend != null) spend.check();   // every attempt, including after a billed timeout
            try {
                BetaMessage m = c.beta().messages().create(params, options);
                return extract(m, false);
            } catch (AnthropicServiceException e) {          // an explicit API error is not a billed Message
                if (spend != null) spend.addPaid(0, log);
                int s = e.statusCode();
                if (s != 408 && s != 409 && s != 429 && s < 500) throw new IOException(describe(e), e);
                failure = e;
                retryAfterMs = retryAfter(e);
            } catch (AnthropicIoException | AnthropicRetryableException e) {   // connection / timeout
                failure = e;
                // a request that went out and got no answer (timeout, dropped connection) may still be billed: count the most it can cost
                if (spend != null) spend.addPaid(notSent(e) ? 0 : worstCase(system, user, false), log);
            }
            if (attempt >= MAX_ATTEMPTS) throw new IOException("Anthropic API failed after " + attempt + " attempts: " + describe(failure), failure);
            long waitMs = retryAfterMs > 0 ? Math.min(retryAfterMs, 60_000) : 5_000L * (1L << (2 * (attempt - 1)));   // 5s, 20s, 80s
            left = deadline - System.nanoTime();
            if (waitMs * 1_000_000L >= left - 5_000_000_000L)
                throw new IOException("Anthropic API failed and no time left to retry: " + describe(failure), failure);
            if (log != null) log.warning("Anthropic API: " + describe(failure) + " - retrying in " + waitMs / 1000 + "s (attempt "
                    + (attempt + 1) + "/" + MAX_ATTEMPTS + ")");
            Thread.sleep(waitMs);
        }
    }

    String extract(BetaMessage m, boolean batched) throws Exception {
        double usd = cost(m, batched, log);
        if (spend != null) spend.addPaid(usd, log);   // before the checks below: a refused or cut-off reply is billed too
        Optional<BetaStopReason> stop = m.stopReason();
        if (stop.isPresent() && stop.get().equals(BetaStopReason.REFUSAL)) {
            String cat = m.stopDetails().flatMap(d -> d.category()).map(x -> x.asString()).orElse("unknown");
            throw new RefusalException(cat);
        }
        if (stop.isPresent() && stop.get().equals(BetaStopReason.MAX_TOKENS))
            throw new IOException("Anthropic API: reply cut off at max_tokens (" + MAX_TOKENS + ")");
        String text = m.content().stream().flatMap(b -> b.text().stream()).map(BetaTextBlock::text).collect(Collectors.joining());
        if (text.isBlank()) throw new IOException("Anthropic API: reply had no text (stop_reason "
                + stop.map(BetaStopReason::asString).orElse("none") + ")");
        if (log != null && log.isLoggable(Level.INFO)) {
            var u = m.usage();
            log.info("Anthropic API: " + m.model().asString() + " in=" + u.inputTokens() + " out=" + u.outputTokens()
                    + " cache_read=" + u.cacheReadInputTokens().orElse(0L) + " cache_write=" + u.cacheCreationInputTokens().orElse(0L)
                    + (batched ? " batch" : "") + String.format(java.util.Locale.ROOT, " cost=$%.4f", usd));
        }
        return text;
    }

    /** The most one call can cost: every prompt character a token at the cache-write price, plus max_tokens of output. */
    double worstCase(String system, String user, boolean batched) {
        long chars = (system == null ? 0 : system.length()) + (user == null ? 0 : user.length());
        double usd = usd(model, 0, chars, 0, MAX_TOKENS, log);
        return batched ? usd / 2 : usd;
    }

    /** These failures occur before an HTTP request reaches the provider; other transport failures stay uncertain. */
    static boolean notSent(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof java.net.ConnectException || t instanceof java.net.UnknownHostException
                    || t instanceof java.net.http.HttpConnectTimeoutException) return true;
        return false;
    }

    /** List price of one reply in US dollars: each attempt in usage.iterations at its own model's price, else the top level. */
    static double cost(BetaMessage m, boolean batched, Logger log) {
        BetaUsage u = m.usage();
        String top = m.model().asString();
        List<BetaUsage.Iteration> its = u.iterations().orElse(List.of());
        double usd = 0;
        if (its.isEmpty())
            usd = usd(top, u.inputTokens(), u.cacheCreationInputTokens().orElse(0L), u.cacheReadInputTokens().orElse(0L), u.outputTokens(), log);
        else for (BetaUsage.Iteration it : its)
            usd += usd(it.model().map(Model::asString).orElse(top), it.inputTokens(), it.cacheCreationInputTokens(), it.cacheReadInputTokens(), it.outputTokens(), log);
        return batched ? usd / 2 : usd;
    }

    private static double usd(String model, long in, long cacheWrite, long cacheRead, long out, Logger log) {
        double[] p = null;
        int best = -1;
        for (var e : PRICES.entrySet())   // longest matching id wins: "claude-opus-5-5-..." is Opus 5.5, not Opus 5
            if (model.startsWith(e.getKey()) && e.getKey().length() > best) { p = e.getValue(); best = e.getKey().length(); }
        if (p == null) {
            p = UNKNOWN_PRICE;
            if (log != null) log.warning("Anthropic API: no price known for " + model + "; counting it at the dearest model's price for the monthly budget");
        }
        return (in * p[0] + cacheWrite * p[1] + cacheRead * p[2] + out * p[3]) / 1e6;
    }

    private static long retryAfter(AnthropicServiceException e) {
        try {
            List<String> v = e.headers().values("retry-after");
            if (!v.isEmpty()) return (long) (Double.parseDouble(v.get(0).strip()) * 1000);
        } catch (RuntimeException ignored) {
            // HTTP-date form or garbage: use our own backoff
        }
        return -1;
    }

    /** Status, error type and the API's message. Never includes request headers (the key). */
    static String describe(Exception e) {
        if (e instanceof AnthropicServiceException s)
            return "HTTP " + s.statusCode() + s.type().map(t -> " " + t.asString()).orElse("")
                    + s.apiMessage().map(msg -> ": " + msg).orElse("");
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    @Override
    public synchronized void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
