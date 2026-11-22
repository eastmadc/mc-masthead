# Paid-writer ledger recovery

## Recover an unsettled paid request

When `writer.monthly-budget-usd` is positive, the ledger records a pending request before the provider call and clears
it only when settlement can be saved. An ambiguous timeout, incomplete result or failed settlement write can leave
that marker in place. The plugin then uses its plain fallback instead of making more paid calls. A zero budget keeps
the existing behavior of disabling this accounting; it is not a recovery method.

1. Stop or disable paid writer activity and back up `plugins/Masthead/data/spend.json`.
2. Check the provider's request/billing records and the plugin's `unrecorded cost $...` log, if present. Determine the
   charge for the pending request; do not assume a timeout means that the provider did no billable work.
3. Add any missing charge to the ledger month's `usd`, then remove `pending` only after that charge is accounted for.
   Preserve valid JSON and the existing month. If the pending request is from an older month, reconcile that month
   first; the next successful budget check can roll the ledger forward.
4. Resume the writer. The ledger is read on each call, so a restart is not required solely for a repaired ledger.

This protocol handles ordinary persistence failures and pending state across restarts. It does not promise fsync-level
power-loss durability or coordinate multiple processes sharing one ledger. The edition lock serializes this plugin's
writer calls. Provider billing records remain authoritative, and provider-side limits are still recommended.
