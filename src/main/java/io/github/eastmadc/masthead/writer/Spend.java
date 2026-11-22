package io.github.eastmadc.masthead.writer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.YearMonth;
import java.util.function.Consumer;

/**
 * writer.monthly-budget-usd: a small dollar ledger, data/spend.json = {"month":"2026-11","usd":1.23,"alerted":"2026-11"}.
 * The paid writers call {@link #check()} before each attempt and {@link #add(double)} after its outcome. Once the month's total reaches
 * the budget, every paid call throws (so the paper comes out plain) and the owner is told once that month. It fails
 * closed: check writes pending=true before money is spent; add atomically records the cost and removes pending. A failed
 * settlement or interrupted request therefore blocks later instances too, until its cost is reconciled. The existing
 * edition lock serializes writers; this ledger is not a multi-process billing service. Atomic rename is not a guarantee
 * against power loss (there is no fsync). Overshoot is at most the one accounted attempt that crossed the line.
 * Budget 0 = no cap, no ledger.
 */
public final class Spend {
    private final Path file;
    private final double budget;
    private final Consumer<String> alert;

    /** alert: told once a month, in plain words, when the budget is reached. */
    public Spend(Path file, double budgetUsd, Consumer<String> alert) {
        this.file = file;
        this.budget = budgetUsd;
        this.alert = alert == null ? m -> { } : alert;
    }

    /** True when there is a budget to count against. */
    public boolean on() {
        return budget > 0;
    }

    /** Before a paid call: throws when this month's budget is used up or the ledger can't be read or saved. */
    public synchronized void check() throws IOException {
        if (budget <= 0) return;
        JsonObject o = read();
        if (o.has("pending") && o.get("pending").getAsBoolean())
            throw new IOException("the AI spend ledger (" + file.getFileName() + ") has an unsettled request; paid AI is off until its cost is reconciled and pending is removed");
        if (o.get("usd").getAsDouble() < budget) {
            o.addProperty("pending", true);
            write(o);   // persisted before the request, so a failed settlement cannot silently become a fresh start
            return;
        }
        String month = YearMonth.now().toString();
        if (!month.equals(o.has("alerted") ? o.get("alerted").getAsString() : "")) {
            o.addProperty("alerted", month);
            write(o);
            alert.accept(String.format(java.util.Locale.ROOT, "The AI writer has used this month's budget ($%.2f of $%.2f, writer.monthly-budget-usd). "
                    + "The paper comes out plain until next month, or until you raise the budget and run /masthead reload.", o.get("usd").getAsDouble(), budget));
        }
        throw new IOException(String.format(java.util.Locale.ROOT, "monthly AI budget reached ($%.2f of $%.2f)", o.get("usd").getAsDouble(), budget));
    }

    /** True when optional paid calls should wait: a request is unsettled, under 10% of the budget is left, or the ledger can't be read. */
    public synchronized boolean low() {
        if (budget <= 0) return false;
        try {
            JsonObject o = read();
            return o.has("pending") && o.get("pending").getAsBoolean() || o.get("usd").getAsDouble() >= 0.9 * budget;
        } catch (IOException e) {
            return true;
        }
    }

    /** For /masthead status: null, or one line saying that paid AI is off (a request is unsettled, or the ledger can't be read) and what to do. */
    public synchronized String locked() {
        if (budget <= 0) return null;
        try {
            JsonObject o = read();
            if (!o.has("pending") || !o.get("pending").getAsBoolean()) return null;
        } catch (IOException e) { /* can't be read: check() refuses paid calls then too */ }
        return "AI spend ledger locked: " + file + " has an unsettled request or can't be read. The paper comes out plain until you fix it: see docs/LEDGER-RECOVERY.md.";
    }

    /**
     * After a paid call: adds its cost. A ledger that can't be saved is logged, not thrown: the reply is already paid
     * for, so it is kept. The persisted pending flag makes the next check fail even after I/O recovers or a new instance
     * is created. The log preserves the known unrecorded amount for manual reconciliation.
     */
    public void addPaid(double usd, java.util.logging.Logger log) {
        try {
            add(usd);
        } catch (IOException e) {
            if (log != null) log.warning(e.getMessage() + String.format(java.util.Locale.ROOT,
                    "; unrecorded cost $%.6f; this reply is kept, and paid AI stops until the pending request is reconciled", usd));
        }
    }

    /** Settle a request: add its cost (zero only for a known unbilled outcome), then clear pending in the same write. */
    public synchronized void add(double usd) throws IOException {
        if (budget <= 0) return;
        if (!Double.isFinite(usd) || usd < 0) throw new IOException("invalid AI cost; the pending request must be reconciled");
        JsonObject o = read();
        o.addProperty("usd", Math.round((o.get("usd").getAsDouble() + usd) * 1e6) / 1e6);
        o.remove("pending");
        write(o);
    }

    /** This month's ledger; a new month starts at 0. A missing file is a fresh start; a broken one throws. */
    private JsonObject read() throws IOException {
        String month = YearMonth.now().toString();
        JsonObject fresh = new JsonObject();
        fresh.addProperty("month", month);
        fresh.addProperty("usd", 0.0);
        if (!Files.exists(file)) return fresh;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            double usd = o.get("usd").getAsDouble();
            if (!(usd >= 0) || Double.isInfinite(usd)) throw new IllegalStateException("bad usd");
            if (o.has("pending") && (!o.get("pending").isJsonPrimitive() || !o.get("pending").getAsJsonPrimitive().isBoolean()))
                throw new IllegalStateException("bad pending flag");
            // A new month must not erase an unresolved request from the previous one.
            return (o.has("pending") && o.get("pending").getAsBoolean()) || month.equals(o.get("month").getAsString()) ? o : fresh;
        } catch (IOException | RuntimeException e) {
            throw new IOException("the AI spend ledger (" + file.getFileName() + ") can't be read, so paid AI is off until it is reconciled and repaired ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    private void write(JsonObject o) throws IOException {
        try {
            io.github.eastmadc.masthead.pipeline.Housekeeping.createPrivateDirs(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".new");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            throw new IOException("the AI spend ledger (" + file.getFileName() + ") can't be saved, so paid AI is off until the data folder is writable ("
                    + e.getClass().getSimpleName() + ")");
        }
    }
}
