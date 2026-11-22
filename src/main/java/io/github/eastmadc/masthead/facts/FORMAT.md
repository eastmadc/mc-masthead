# facts/ input formats

`FactsBuilder(dataFolder, settings).build(date, editionTime)` reads these files under the plugin data folder.
The edition window is **[yesterday at editionTime, today at editionTime)** in `schedule.zone` (empty means the server's zone).
Day files are named by the server's local date. The reader opens the day before, the day itself and the day after, then
filters on `t`, so a file written just after midnight is still read.
Bad lines (not JSON, missing fields) are skipped. Every name must match `^\.?[A-Za-z0-9_]{3,16}$`.

## events/DATE.jsonl (written by collect/EventLog)
One JSON object per line, appended in time order. `t` = epoch seconds.

```
{"t":1793798234,"k":"join","p":"Alice"}
{"t":1793798300,"k":"death","p":"Alice","msg":"Alice was slain by Bob using [Sword]","killer":"Bob"}
{"t":1793798301,"k":"death","p":"Alice","msg":"Alice fell from a high place","killer":""}
{"t":1793798302,"k":"death","p":"Alice","msg":"Alice was slain by Zombie","killer":"","v":2}
{"t":1793798392,"k":"adv","p":"Alice","title":"Diamonds!"}
```

- `join`: one line per login.
- `death`: `msg` is the death message as plain text, exactly as the server prints it (it must start with `p` and a space).
  The facts cut it at ` using [` (the weapon's custom name, which players can type) and cap it at 120 characters.
  `killer` is the killing **player's** name, or `""` when no player killed them. Always write the key. If it is missing,
  the killer is guessed from the text ("... by NAME"), as the old log reader did.
  PvP comes only from death lines. Do **not** write separate `pvp` lines (they are ignored).
  Since 2.0.1 the recorder replaces a mob's name tag and a renamed weapon with the plain type before it stores the line
  ("Alice was slain by Zombie", "[Trident]") and adds `"v":2`. A line without `"v":2` (older recorder, or another tool)
  is dropped when, after taking out the players' names, it still has a digit or a `[`: that is text a player typed.
- `adv`: only advancements that are announced in chat (they have a display and `announce_to_chat`; never recipes).
  `title` is the plain display title, 1 to 60 characters, with no `]`.
- Other `k` values are ignored. At most 20000 events per window are read.

## blotter/DATE.jsonl, mailroom/DATE.jsonl
These keep the formats of the original companion plugin:
- `{"t":epoch,"k":kind,"a":by,"b":to,"x":detail,"n":count}`
- `{"id":"DATE-xxxxxxxx","t":epoch,"name":..,"kind":"letter|tip","text":..}`, plus `"wb":true` (since 2.0.1) on a letter
  with worried words. The candidates carry `"wb":true` along.

Already-handled mail ids are listed in `mailbag-done.txt` (separated by whitespace). Letters the editor desk could not
read are listed in `mailbag-carry.txt`; those are read for up to 7 days instead of 3.

## health/DATE.jsonl (written by collect/HealthSampler)
`{"t":epoch,"tps":19.98,"on":2}`, about one line per minute. This gives `server.tps_avg` and `server.tps_min` (one decimal)
and `server.peak_players`. `server.backup` is the first line of `features.health-extra-file` (relative to the data folder),
capped at 200 characters. `server.paper_build` is whatever the caller passes to `paperBuild(..)`.

## Web access log (readership)
The log is `web.access-log` if it is set (relative to the data folder, or absolute). Otherwise it is
**`web-access.log` in the data folder root**. Keep it outside the served `web/` folder, because it holds addresses.
It has one line per request, in BlueMap's webserver.log format (times are local wall-clock):

```
[2026-11-05 04:41:01][INFO] 203.0.113.9 "GET /news/index.html HTTP/1.1" 200 OK
```

Only `GET` with status 200 or 304 counts. Pages are named relative to the path of `web.public-url` (for example `/news`, or
none). `/` and `/index.html` count as "paper", `/players.html` as "player cards", and `/owner.html` or `/<owner lowercase>.html`
as "owner's file". Loopback addresses are skipped. A private address (Python 3.12 `is_private`) counts as "the house".
Only the last 16 MB of the log is read.

## ip-players.txt (data folder root)
Lines of `ADDRESS NAME`, one per address/name pair seen at login. They are used only to turn readers into names in
`circulation.who`, which goes in the owner's push only, and the prompt builder must strip it. Addresses never leave this
file and the access log.

## Output: data/DATE.json
These are the same keys as the Python `data/DATE.json`, in the same order: `date, edition, first_edition, players_on, new_players,
today, week, season_totals, leaders, leaders_today, awards, police_blotter, chicken_watch, circulation, owner,
recent_editions, deaths, advancements, pvp, server, mailbag_candidates`.

Differences from Python:
- `owner` is left out when `owner.name` is empty.
- `cold_cases` is gone.
- `recent_words` (only when non-empty) lists the resting words (slang, game nicknames, swear phrases like "as hell") the
  last 3 papers used, read from each whole paper. It feeds the cooldown; the prompt builder keeps it out of the writer's data.
- `server` comes from health/.
- `chicken_watch` is there only when `animal-watch` is in `tone.bits`. It follows `paper.watch-animal`. The keys keep
  their chicken names for compatibility. For any other animal it adds `"animal":"<type>"` and leaves out eggs.
- Players in `privacy.hide-players` (any case) are removed before anything is computed. Deaths that name them are dropped too.

`mailbag_candidates` and `circulation.who` must never reach the main writer. Since 2.0.1 they are also left out of the
data/DATE.json file on disk (they stay in the in-memory facts for the editor desk and the owner's push only).

Ties between players follow the order of the stats folder listing, as in Python's `glob`.
