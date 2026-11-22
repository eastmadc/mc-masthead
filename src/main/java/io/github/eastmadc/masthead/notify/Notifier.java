package io.github.eastmadc.masthead.notify;

import io.github.eastmadc.masthead.config.Settings;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

/**
 * Owner push (Settings.alerts): ntfy and/or a Discord webhook, both optional. Blocking; call off the main thread.
 *
 * <p>The URLs are secrets (anyone holding them can post), so they are never logged: failures log only the channel,
 * the HTTP status or the exception type.
 */
public final class Notifier implements Push {
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final int DISCORD_MAX = 2000;

    private final String ntfyUrl;
    private final String discordUrl;
    private final Logger log;
    private final HttpClient http;

    public Notifier(Settings.Alerts alerts, Logger log) {
        this.ntfyUrl = usable(alerts == null ? null : alerts.ntfyUrl());
        this.discordUrl = usable(alerts == null ? null : alerts.discordWebhook());
        this.log = log;
        if (log != null && alerts != null) {
            if (ntfyUrl == null && alerts.ntfyUrl() != null && !alerts.ntfyUrl().isBlank())
                log.warning("alerts: the ntfy URL is not a valid http(s) URL - ntfy disabled");
            if (discordUrl == null && alerts.discordWebhook() != null && !alerts.discordWebhook().isBlank())
                log.warning("alerts: the Discord webhook is not a valid http(s) URL - Discord disabled");
        }
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** True if at least one channel is configured. */
    public boolean enabled() {
        return ntfyUrl != null || discordUrl != null;
    }

    @Override public boolean hasNtfy() { return ntfyUrl != null; }
    @Override public boolean hasDiscord() { return discordUrl != null; }
    @Override public boolean sendNtfy(String title, String body, String priority, List<String> tags) { return ntfyUrl != null && ntfy(title, body, priority, tags); }
    @Override public boolean sendDiscord(String title, String body) { return discordUrl != null && discord(title, body); }

    /**
     * Sends to every configured channel. Returns true if at least one accepted it.
     *
     * @param priority ntfy priority: min|low|default|high|urgent (ignored by Discord)
     * @param tags     ntfy tags / emoji shortcodes, e.g. "chicken" (ignored by Discord)
     */
    @Override
    public boolean send(String title, String body, String priority, List<String> tags) {
        boolean ok = false;
        if (ntfyUrl != null) ok |= ntfy(title, body, priority, tags);
        if (discordUrl != null) ok |= discord(title, body);
        return ok;
    }

    private boolean ntfy(String title, String body, String priority, List<String> tags) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(ntfyUrl)).timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
            if (title != null && !title.isBlank()) b.header("Title", headerValue(title));
            if (priority != null && !priority.isBlank()) b.header("Priority", headerValue(priority));
            if (tags != null && !tags.isEmpty()) b.header("Tags", headerValue(String.join(",", tags)));
            return check("ntfy", http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed("ntfy", e);
        } catch (Exception e) {
            return failed("ntfy", e);
        }
    }

    private boolean discord(String title, String body) {
        try {
            String text = (title == null || title.isBlank() ? "" : "**" + title.strip() + "**\n") + (body == null ? "" : body);
            JsonObject json = new JsonObject();
            json.addProperty("content", truncate(text, DISCORD_MAX));
            JsonObject mentions = new JsonObject();
            mentions.add("parse", new JsonArray());          // never ping @everyone / roles / users
            json.add("allowed_mentions", mentions);
            HttpRequest req = HttpRequest.newBuilder(URI.create(discordUrl)).timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString(), StandardCharsets.UTF_8)).build();
            return check("Discord", http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed("Discord", e);
        } catch (Exception e) {
            return failed("Discord", e);
        }
    }

    private boolean check(String channel, int status) {
        if (status >= 200 && status < 300) return true;
        if (log != null) log.warning(channel + " push failed: HTTP " + status);
        return false;
    }

    private boolean failed(String channel, Exception e) {
        // the exception message can contain the URL; log the type only
        if (log != null) log.warning(channel + " push failed: " + e.getClass().getSimpleName());
        return false;
    }

    static String usable(String url) {
        return Settings.usableUrl(url);
    }

    /** Header-safe text: no CR/LF; non-ASCII as an RFC 2047 encoded word (ntfy decodes it). */
    static String headerValue(String s) {
        String v = s.replaceAll("[\\r\\n\\t]+", " ").strip();
        if (v.chars().allMatch(c -> c >= 0x20 && c < 0x7f)) return v;
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    /** At most max UTF-16 units without splitting a surrogate pair; adds an ellipsis when cut. */
    static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        int end = max - 1;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end) + "…";
    }
}
