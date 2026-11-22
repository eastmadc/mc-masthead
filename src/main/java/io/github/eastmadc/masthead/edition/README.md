# edition/ — check, desk, sanitize, render

A port of the edition side of an earlier Python version. With the same settings it gives the same bytes as the
Python. Every name comes from `Settings`: paper name, server, tagline,
theme, readers, award names, watch animal, owner and desk title. No Bukkit calls. It uses only Gson 2.11 APIs, which is
the version Paper ships.

## Public API

| class | method | Python |
|---|---|---|
| `StylePrompt` | `build(Settings)` returns the writer's instructions. `prompt(Settings, JsonObject data)` returns the instructions plus `DATA (JSON)`, capped at 60000 characters | `style()`, `prompt` |
| `FactCheck` | `problems(data, draftHtml, date, settings)` returns `List<String>`: numbers not in the data, then quoted banned phrases. Empty means the draft passed. `style(data, draftHtml, settings)` lists style slips (slang cap, slang tags, resting slang, a repeated joke shape): noted for the owner, never a rewrite. `line(problems)` gives the shell-style string. `facts(data)` drops letters and reader names. `numbersIn`, `known` | `check`, `facts`, `numbers_in` |
| `WordGate` | `bad(text, names, mail, language)` and `namesIn(text, names)` | `bad_words`, `names_in` |
| `HtmlClean` | `clean(html)` for the edition allow-list, `cleanReply(html)` for p/strong/em/br only, `text(html)` | `Clean`, `ReplyClean` |
| `MailDesk` | `prompt(Settings, data)` returns the desk prompt, or null when there is no mail. `rules(Settings)`. `validate(data, deskRaw, usedAi, language, date)` returns a `Result` (`toJson()` is mailbag.json, plus `letters` and `tips` HTML). `quoteOk`. `candidates(mailroomDir, doneFile, date, players)`. `printedHistory(printedLog, date, todayPrinted)` | `desk`, `mailroom`, `quote_ok`, `mail_candidates`, `printed_history` |
| `Splice` | `apply(Settings, frag, letters, tips, plug)` and `mailPlug(Settings, mailroomSince, date)` | `splice`, `mail_plug` |
| `Fallback` | `edition(Settings, data)` returns the plain edition | `fallback` |
| `Pages` | `index(...)`, `players(Settings, stats, updated)`, `owner(Settings, stats, updated)`. Each returns HTML, or null when there is nothing to show | `PAGE`, `players_page`, `owner_full` |
| `GameEdition` | `build(Settings, date, data, frag, label, usedAi, printed)` returns the today.json object | `game_json` |
| `StatsReader` | `load(statsDir)` reads one stats snapshot into rows per player | `load_stats` |
| `Render` | `render(Settings, data, date, draft, deskRaw, Inputs, updated)` returns `Rendered` | `render` |
| `PyJson` | Strict `loads`. `dumps` / `dumpsCompact` match Python's `json.dumps`, including `ensure_ascii` and the float repr | `json` |

`Render.Inputs(editionsDir, statsDir, printedLog, mailroomSince)`:
- `editionsDir`: `editions/DATE.html` is written here. The newest 30 files are read back for the front page.
- `statsDir`: today's snapshot (`uuid.json` files plus `usercache.json`).
- `printedLog`: `mailbag-printed.jsonl`. It may be missing.
- `mailroomSince`: the date the mailroom opened. Pass null when the mailroom is off, and no plug line is added.

`Rendered` gives you:
- `indexHtml`, `playersHtml` and `ownerHtml` (null means don't publish)
- `game`: write it with `PyJson.dumps(game, false)`
- `mail.toJson()`: this is mailbag.json
- `usedAi`, `gate`, `summary`, `warnings`

`Render` builds the current archive fragment in memory. A published render does not write the archive: `EditionJob`
stages the archive, game JSON and web pages together and restores prior files on ordinary commit failures. The front
page is replaced last. This is not a crash-atomic transaction or a snapshot for concurrent readers. A preview writes
only its own `preview/editions/DATE.html`; the published archive is never touched by preview.

## Settings generalization

- **Watch column.** The `animal-watch` bit (the old `chicken-watch` key also works) together with `paper.watchAnimal` gives the "<Animal> Watch" column. The facts key stays `chicken_watch`.
- **Chicken puns.** The ban list in the prompt and in the fact check applies only when the watch animal is `chicken`.
- **Chicken-town framing.** The coop/farm framing, the hen and coop quotes, "SOURCES CLOSE TO THE COOP" and the quiet-day-on-the-farm line need two things: the watch animal is chicken, and the theme mentions chickens.
- **Owner.** When `owner.name` is empty, there is no owner sentence, no desk section, no owner card, no owner page, and no owner block in the fallback. `owner.deskTitle` is the desk title. `owner.hoursOk` chooses between "His total hours played are fine…" and "Never mention his hours played…".
- **Never-mention list.** `privacy.neverMention` is added to HARD LINES as `- Never mention: a; b.` and to the desk rules.
- **Rules added after a review.** These are in the writer prompt, and the desk prompt gets the record rule:
  - no invented "record", "personal best", "first time" or "all-time"
  - don't tell the same story twice under different headings
  - never invent what a player did or how they reacted ("when questioned, X did not return our calls")
- **Owner file.** It is `owner.html` (an earlier version named it after the owner). The lore sections were hard-coded in the Python version; now they come from `owner.lore` as a "Server legends" card. The old villager KPI is now villager trades.
- **Pronouns.** The owner uses `owner.pronoun` (he, she or they; default they). Players are named or called they/them; the prompt says never to guess anyone's gender.

## Tests (`src/test/java/io/github/eastmadc/masthead/edition/`)

- `EditionUnitTest`: the 42 production assertions plus 15 extras (sanitizer edge cases and generalization). Plain `main()`.
- `GoldenTool`: has the same commands as the Python script (`style`, `prompt`, `desk`, `check`, `mail`, `render`, `batch`) so the outputs can be diffed.
