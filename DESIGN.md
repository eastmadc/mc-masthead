# Masthead: design

A Paper plugin that writes a daily AI newspaper about a Minecraft server: who played, deaths, PvP, awards, a Police Blotter of
players messing with each other's stuff, a player mailroom, player stat cards, and a roast column about the server owner.
It publishes to a web page (with a QR code), to an in-game written book, and to the join message.

Player-submitted tips are news leads for the editor. Checked anonymous tips run in Letters to the Editor, not the
automatic Police Blotter. The stored `kind: tip` format and existing mailroom isolation/validation rules stay unchanged.

Reference implementation: the Java code ports an earlier Python version of the pipeline and its companion plugin.
PORT ITS BEHAVIOUR, don't reinvent. The current Java pipeline allows up to two rewrites and
validated section/sentence salvage before the plain fallback. Never copy real IP addresses, chat or player data into test
fixtures or logs.

## Targets
- Java 21 bytecode (`--release 21`), compiled against `paper-api 1.21.8`, targeting 1.21.8+ and 26.x.
  Runtime-tested on Paper 1.21.8 and 1.21.11 (Java 21) and on Paper and Purpur 26.3 (Java 25); see the README table. `plugin.yml api-version: '1.21.8'`
  is the loading minimum. Paper introduced dialogs in 1.21.7; its guide is fully accurate for 1.21.8
  (https://docs.papermc.io/paper/dev/dialogs/). The host JVM must meet Paper's own requirements.
- No shaded deps. Gson + Adventure come with Paper. The Anthropic Java SDK is declared in `plugin.yml libraries:` (Paper downloads it
  from Maven Central at startup) and is only touched by `writer/AnthropicWriter.java`.
- Build: `build.sh` (javac + jar; no Maven/Gradle needed). Compile classpath = `lib/compile/*.jar` (paper-api 1.21.8 + its deps,
  anthropic-java + deps). `tools/fetch-libs.sh` downloads them (checksummed) from Maven Central / repo.papermc.io.

## Packages (`src/main/java/io/github/eastmadc/masthead/…`)
| package | what |
|---|---|
| `Masthead.java` (root) | JavaPlugin: lifecycle, config load, scheduler, commands, wiring |
| `config/` | `Settings` record tree parsed from config.yml (+ validation, defaults) |
| `game/` | join message, `/news` (qr, paper book), `/awards`, `/stats`, QR map, `Mailroom` (dialog, /letter) |
| `collect/` | in-game recorders: `Recorder` (deaths, advancements, joins → events/DATE.jsonl; health), `DeathText` (name tags and renamed items out of death messages), `Blotter`, `StatsSnapshot` (copy stats files at edition time, all-or-nothing) |
| `facts/` | **port of Python collect()**: reads snapshots/events/blotter/readership → `Facts` JSON (same keys as Python) |
| `edition/` | **port of Python check/render/desk-validate**: `StylePrompt`, `FactCheck`, `WordGate`, `HtmlClean`, `MailDesk`, `Splice`, `Pages` (index/players/owner html), `GameEdition` (book/join json), `Fallback` |
| `writer/` | `Writer` interface + `AnthropicWriter` (SDK), `CommandWriter` (stdin→stdout process, e.g. `claude -p`), `ExchangeWriter` (request/response files for a host helper), `NoWriter` |
| `web/` | `WebServer` (JDK HttpServer, static files, safe paths, access log for readership) or folder mode |
| `notify/` (Settings.alerts) | owner push: ntfy URL / Discord webhook (optional); `Concerns`: the worried-letter path |
| `qr/` | vendored Nayuki QR Code generator (MIT, with license header) + `QrMap` rendering |
| `pipeline/` | `EditionJob`: the daily run, in the original pipeline's order; `Attempts` (retries), `State` (state.yml), `Housekeeping` (private modes, retention, a one-time clean-up of older files) |

Each package compiles standalone against `lib/compile/*` + Gson; cross-package calls only through the interfaces below.

## Data folder layout (`plugins/Masthead/`)
```
config.yml
stats/DATE/<uuid>.json, usercache.json   # snapshot copied at each edition time (Python: state/stats/DATE)
events/DATE.jsonl                        # {"t":epoch,"k":"join|death|adv","p":name,...}; newer death lines carry "v":2
blotter/DATE.jsonl                       # unchanged format from the original Blotter
mailroom/DATE.jsonl                      # the original Mailroom format, plus "wb":true on a worried letter
health/DATE.jsonl                        # {"t":epoch,"tps":19.98,"on":2}
data/DATE.json                           # Facts (Python keys), on disk without mailbag_candidates and circulation.who
editions/DATE.html                       # sanitized fragment with eyebrow label (as Python)
web/index.html players.html owner.html   # published pages (or written to web.folder)
today.json                               # game edition (same shape as Python game.json)
desk/DATE.json  mailbag-done.txt  mailbag-printed.jsonl  mailbag-carry.txt  # mailroom bookkeeping
mailbag.json                             # last desk result; concern entries are id + name only
concern/<DATE>-<hex>.json                # helper-pushes-concerns only: one worried letter each, [{"id","name","note","text"}]
concern-outbox/  concern-sent.txt        # worried-letter alerts waiting / already sent (ids and names, 10 days)
preview/                                 # everything /masthead preview writes (index.html, editions/, desk/, stats/)
ai/requests/ ai/responses/               # exchange-mode files: <date>-t<seconds>-<16 hex>.txt
notified.txt                             # mailroom "your letter ran" memory
```

## Interfaces
```java
// facts/
public final class FactsBuilder {                 // pure: no Bukkit calls; works on files only (testable outside a server)
  public FactsBuilder(Path dataFolder, Settings.Paper paper, Settings.Owner owner, Settings.Awards awards);
  public JsonObject build(LocalDate date, LocalTime editionTime, boolean mailroomOn) throws IOException;  // writes data/DATE.json too
}
// edition/
public final class StylePrompt { public static String build(Settings s); }               // Python style(cfg), generalized by Settings
public final class FactCheck { public static List<String> problems(JsonObject facts, String draftHtml, LocalDate date); } // numbers + banned phrases
public final class WordGate  { public static List<String> bad(String text, Collection<String> names, boolean mail, String language); }
public final class HtmlClean { public static String clean(String html); public static String cleanReply(String html); }
public final class MailDesk  { public static String prompt(Settings s, JsonObject facts);   // null if no candidates
                               public static Result validate(JsonObject facts, String deskRaw, boolean usedAi, String language); }
public final class Splice    { public static String apply(String frag, List<String> letters, List<String> tips, String plug); }
public final class Render    { public static Rendered render(Path dataFolder, Settings s, JsonObject facts, String draftOrNull, MailDesk.Result mail); }
// writer/
public interface Writer extends AutoCloseable { String name(); String write(String system, String user, Duration timeout) throws Exception; } // system = cached style text, user = facts; throws on failure (RefusalException carries the category)
```

## Generalization (Settings)
- `paper.name`, `paper.server-name`, `paper.tagline`, `paper.theme` (the running joke, one sentence),
  `paper.awards.good`, `paper.awards.bad`, `paper.readers` (who reads it).
- `tone.audience` all-ages|teens|adults (caps the next three; the cap lives in the `Settings.Tone` constructor), `tone.voice`
  newsroom|group-chat|sportscast|classic, `tone.spice` mild|medium|spicy|savage, `tone.language` clean|pg13|strong, `tone.slang`
  off|light|heavy, `tone.length` short|normal|long, `tone.bits` list, `tone.lexicon.add/remove` (the shipped list is
  `lexicon.yml`; `edition/Lexicon` gates each word on the day's facts). Public defaults: all-ages, medium, clean, light.
  The floor (`WordGate.FLOOR*`) and the claim-word gate run on every draft through FactCheck.
- `owner.name` (optional; empty → no owner desk), `owner.desk-title`, `owner.lore` list, `owner.hours-ok`, `owner.pronoun`.
- `privacy.hide-players` (never named), `privacy.never-mention` (extra topics), hard lines fixed in code.
- `schedule.time` (05:30), `schedule.zone` (server default), `writer.mode` anthropic|openai-compatible|command|exchange|none,
  `writer.anthropic.*` (model `claude-opus-5-5`, effort, batch, api-key-file / env `ANTHROPIC_API_KEY` / api-key),
  `writer.openai-compatible.*` (base-url, model, key, prices, extra-body), `writer.monthly-budget-usd`, `writer.command` (list), `writer.timeout-seconds`.
- `web.mode` builtin|folder|off, `web.port`, `web.bind`, `web.folder`, `web.public-url` (used in links and the QR code).
- `features.mailroom`, `features.blotter`, `features.readership`, `alerts.ntfy-url`, `alerts.discord-webhook`.
- The animal column is optional: `tone.bits: animal-watch` follows `paper.watch-animal`.

## Hard rules carried from the Python version (do not weaken)
Fact-constrained writing; the fact check (3+ digit numeric values, selected claims and banned phrases) → up to two rewrites
→ revalidated section/sentence salvage → plain fallback. Numeric membership is not complete semantic or attribution verification.
Whole-edition word gate; HTML allow-list;
mailroom isolation (main writer never sees letters or reader names; editor desk is a separate call; Python-side checks: quote is whole or
a word-boundary prefix + "…", tips name no player, numbers in data, word gate; concern letters never printed, owner pushed by name only);
player text only ever via Component.text; no IPs/chat/times of day in the paper; readership names only in the owner push.

## Safety fixes (kept in every later version)
- **Preview** writes only under `preview/` (one `Outputs` object tells `EditionJob` and `Render` where things go).
- **A day is done only when its paper is published.** Tries are noted in state.yml (`edition-attempt-*`); 3 tries
  about 10 minutes apart, then the plain edition, then a "today's paper failed" note to the owner. Any error from the
  AI library (also `LinkageError`) means the plain edition, not no paper.
- **Player-typed text stays out of the facts**: name tags and renamed items become the type; the fact check's list of
  allowed numbers ignores death lines; the plain edition goes through the word filter.
- **Worried letters** are marked when stored and reported at once (name + fixed sentence; Discord: no name), whether or
  not the desk or the paper runs. Failed pushes wait in `concern-outbox/`. No alert channel = no mailroom.
- **Retention and modes**: no letters or reader names on disk in `data/`; `data/` and `stats/` pruned after
  `privacy.retention-days`; files 600 and folders 700, never `editions/` or the web output.
- **Release hygiene**: `mailtest` and `blottertest` are gone; plugin.yml has no fixed aliases (`commands:` in config.yml).
- File formats stay readable by older builds (state.yml keys, last-edition.json shape, mailroom lines), so going back works.

## Product name
**Masthead** — listing title "Masthead – the AI newspaper for your server". Admin command /masthead. plugin.yml description
must say "AI newspaper" (shown by /version Masthead). Player commands must be configurable (/news, /letter, /stats collide
with other plugins).
