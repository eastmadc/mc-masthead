# Changelog

Versions marked "private test build" were never published. They are kept here as history.

## 1.0.2

Fixed
- A build with more than 6 builders is no longer picked for a construction story. The facts check left it out,
  so the paper had no construction story that day. The next build gets the story instead.

## 1.0.1

Fixed
- The paper never calls anyone a man, woman, boy, girl or guy. A draft that does is sent back to the AI, like a
  guessed he or she.
- Numbers are written one way: a count of 1,000 or more gets commas ("12,345 blocks"), in award reasons and in the
  AI's writing.
- The in-game book keeps a list label with its text and never leaves just one or two words for the next page.
- `/stats` fits the 10-line chat view.

## 1.0.0

First public release. The notes below list what changed since the last private test build (1.0.0-alpha.34); on a
fresh install nothing here needs action.

Privacy
- A mistake in `config.yml` (a tab, a missing quote) or an empty `config.yml` no longer resets every setting to its
  default. The settings in use stay, and the console says what is wrong (for a mistake, with the line number).
- A server that starts with a broken `config.yml` keeps Masthead off (no web page, no paper) until a good
  `/masthead reload`.
- A player on `privacy.hide-players` who changes their Minecraft name stays hidden. The console asks the owner to add
  the new name.
- The web pages ask search engines not to list them or keep copies (`noindex, noarchive`).
- A letter or tip that names a hidden player is not sent to the AI. It is held as "check: named a hidden player".
- A letter or tip that gives real-life details in the usual words (an age, a school, a real name, where the writer
  lives, a birthday, a social media name), in the player's text or in the editor's answer, is held as "check:
  personal" and not printed. Other wordings are left to the AI editor, which is told to hold them.
- `/masthead reload` says when a newly hidden player is still in today's paper, and how to take them off now.
- `/letter` no longer opens the form for a player who mailed less than a minute ago (the typed text was lost).
- Old files in `blotter/` (14 days), `events/` and `health/` (40 days) are deleted on time, also with the feature off.
- The word lists stop a few more insults about a person, in the paper and in players' mail.
- A `config.yml` that cannot be rewritten (read-only) no longer leaves a backup copy, holding the API key, at every
  start and reload.

Fixed
- A player who changed their name no longer has their whole season counted as one day's play, wins no award for it,
  and is not listed as a new player.
- On a server with many players the AI's data is no longer cut off in the middle. Quiet players are left out instead,
  and the owner's notes say how many.
- `/news qr` no longer gives a blank map while `web.public-url` is empty.
- A dropped connection to a paid AI service no longer locks the spend ledger when the price is known. `/masthead
  status` shows a locked ledger and where to read about it.
- An AI answer that is not a paper (no headings) is replaced by the plain edition.
- The owner page's section numbers no longer change with the server's Java language.
- A mob's or an animal's family in the paper ("the silverfish family", a spider's cousin, a quote from a mob) no
  longer sends a good first draft back. A family word passes only just after a mob or "its", or inside a quote, in a
  sentence that names a mob or an animal and no player, and has no he, she, they, you or owner. Every other family
  word is still held, "cousins" too.
- Made-up big numbers written as `90k`, `90 thousand` or `2 million` are caught by the number check.
- Awards: killing the same player many times scores once.
- Construction news: a build's blocks for the day count the same players as its builders list, and a kind the AI was
  unsure about is passed on as unsure.
- A setting that should be a number and is not (`web.port: banana`) is reported. A number or true/false in quotes is
  used.
- `schedule.time: 17:30` works without quotes.
- With `writer.mode: exchange`, the folders `ai/requests` and `ai/responses` are made at start and on reload.
- In game: `/news qr` and `/news paper` wait separately and say how many seconds; a second `/stats` gets the same
  "Slow down" line; the book skips a section with no text; no "just landed!" join line without a fresh paper; the
  section sign is dropped from AI text in the book and chat.
- After `/news build private`, `/news build` says "Nothing about your builds is in line for the paper."
- The plain edition leaves out Server health while there is no TPS number. Its opening line for construction news
  says "is now open", not "has opened its office".

Changed
- `/masthead` with an unknown word prints one usage line, not the status.
- `/masthead reload` also shows the writer line that `status` shows, so a missing API key is seen at once.
- `/masthead status share` names the server software (Paper, Purpur) with its version.
- The console says when an AI paper is being written: "Writing today's edition (writer ...); this can take a few
  minutes."
- The AI is told to call the two awards by their headings, and to add no number or date to a reader's tip.
- The animal watch data is left out of the day's facts when `animal-watch` is not in `tone.bits`.
- Construction news: the AI gets a build's size and height only when size is that entry's story, and its materials
  only when they are the story or nothing else says what is inside. Entries read less like a list.
- Classic voice: "own server" twice no longer sends the whole paper back for a rewrite.
- Classic voice: the instructions no longer hand the AI ready-made lines ("No arrests were made", "who asked not to
  be named", "clear skies", "the barn is insured", "Jupiter is in your jump count"), and the blotter key no longer
  says "a gift?". Those lines came back in nearly every paper. The weather line no longer hands over an opening
  ("conditions").
- The AI sees only the headline and deck of the last three papers, not their front pages, so it copies fewer of their
  phrases.
- In game: the join message puts its buttons and each award on a line of their own
  (a button no longer wraps in the middle); `/stats` starts a new line before a number that would wrap away from its
  label; the book's pages are measured in pixels, so they are full (about a third fewer pages) and end on a full
  sentence where they can; the "Written by an AI" note stands after an empty line and is never split over two pages;
  the letter form's switch is labelled "Kind".
- The book no longer cuts a long paragraph at 600 characters in the middle of a word (the web page always had it
  whole), and a table's label (such as "Permit tracker") no longer stands alone, since the book has no tables. A book
  that reaches the 50-page limit still ends with the "Written by an AI" note.
- The paper now sees more of what the game counts: distance ridden on a nautilus, a happy ghast, a strider and by
  minecart, distance swum, raids won and started, items enchanted, bells rung, music discs played, villagers talked
  to, and tools and armor worn out.
- In game, numbers are written the same way on every server ("1,234 blocks"), whatever language the server's Java
  uses.
- The jar no longer records the exact Java build it was made with, so the same source gives the same file on
  Temurin 25.0.5 and 25.0.5.1.
- README: an Anthropic set-up example, an exchange quick start, and a short summary for players and parents under
  "Your data".
- A release is marked "pre-release" on GitHub only when its tag has a "-" in it.

## 1.0.0-alpha.34 (private test build)

Privacy, safety-check, web page and construction news fixes.

Privacy
- Back issues on the front page now show a player added to `privacy.hide-players` as "a player", and no longer show
  old building news, so a story does not stay up after `/news build private`, `forget`, a secret build or
  `clear-name`. The files in `editions/` are not rewritten; the README ("Your data") says how to take a paper down.
- Visitor addresses (`web-access.log`): with `features.readership` off no log is written, and old logs are deleted at
  start-up and on `/masthead reload`. Lines older than 60 days are removed.
- `owner.hours-ok: false` now really hides the owner's hours on the Player Cards page. The owner's card is listed
  first (not in hours order), the owner is left out of the hours rank and of every per-hour rank, and the card shows
  no per-hour badge and no "Fresh spawn" badge. Other players' rank numbers have no gap.
- Exchange mode: request and answer files are no longer readable by every user on the machine (mode 640: the owner and
  the folder's group). **Some set-ups must act:** a helper that runs as another user than the server needs a group
  shared with the server's user, and `ai/requests` and `ai/responses` must belong to that group (`chmod 2770`); the
  README has the steps. Until then the AI writer fails with "could not read request" or a timeout. A helper that runs
  as the server's user, or as root, keeps working. Copy the new `tools/masthead-exchange.sh` to wherever your helper
  runs from.
- The folders `events/`, `health/`, `blotter/`, `data/`, `stats/` and `preview/` are private (mode 700) from the moment
  they are made, and a `/masthead preview` edition file is mode 600. Before, other users on the machine could read
  them until the next published paper or restart.
- `/masthead status share` no longer says an alert address is "set" when it is not a valid http(s) address.
- `writer-stderr.log`, `web-access.log` and `notified.txt` are private (mode 600) from the moment they are made.
  Before, other users on the machine could read them until the next daily clean-up.
- The words that open up with the tone dials are for the paper, its rewrites and the editor's replies only. A player's
  own letter is always checked against the full list (and held as "check: word filter").

Safety checks
- The he/she check no longer stops for the rest of a sentence after a mob or animal word. An animal keeps its pronoun
  only in a part of the sentence that names no player.
- At adults + savage a "sus" joke is now checked by the plugin, not only asked for in the prompt: its sentence, or the
  one before, needs a number and no ore, loot or luck word.
- Classic voice: the Blotter-order check finds its sections by any heading with "blotter" or "cold case" (it needed
  the exact titles), and one bad Blotter line is cut instead of the whole day falling back to the plain edition.
- When a draft is trimmed, each line is checked together with its section heading.
- British swears are on the word lists. Never printed: wanker, twat, bellend, tosser, bollocks, arsehole. Mild (refused
  at `language: clean`): arse, bugger.
- `tone.lexicon.remove` on a classic heavy-slang word no longer prints the false console line "is not a shipped word;
  nothing removed". `POV` works with or without the colon, and a removed word is caught with a curly apostrophe too.
- The `tone.lexicon.add` console line for a dated word now reads "is on Masthead's dated-slang list, which your own
  words can't use".
- A list setting written as one value with no brackets (`hide-players: Steve`) is read as a list of one. Before, it
  was ignored with no console line. This covers `tone.bits`, `tone.lexicon.add` and `remove`,
  `privacy.hide-players`, `privacy.never-mention`, `owner.lore`, `construction.opted-in` and
  `construction.ignore-worlds`.
- Mailroom: a player can send at most 6 letters or tips a day. The 7th gets "That's enough mail from you for today.
  Try again tomorrow!", so one player cannot fill the day's mailbag. A worried letter over the limit still reaches
  the owner.

Web pages
- Owner page at `slang: heavy` no longer prints "Mogging", "Big back energy", "Touch grass" or "Bro"; it uses
  `owner.pronoun` ("Weapon stats (she's cooking)").
- Owner page: a row or joke prints only when its number is above zero (tool rows, kill-feed rows, the pickaxe line,
  the egg paragraph, the horse marathon joke, the "Signature tools" row). The Weapon stats and Kill feed cards are
  left out when empty.
- A long unbroken word (key mashing, a web address) in a story, a letter or an owner legend wraps instead of making
  the page wider than a phone screen.
- `/masthead preview` also writes the fonts, players.html and owner.html into `preview/`, so the preview looks like
  the real page and its links work.
- The plain edition's Server health line no longer prints "; backup: ?" when `features.health-extra-file` is empty.
- With no web address (`web.public-url` empty or `web.mode: off`), `/stats` no longer shows a dead [everyone's Player
  Cards] link, and the "the editor answered your letter" join notice now arrives (as plain text; it was lost).
- Built-in web server: every connection is closed 15 seconds after it opens, also while the answer is being sent
  (sending alone had 30 seconds, counted per write), so slow connections can no longer hold the workers for long.
- `/masthead status share` says when the key file named in `api-key-file` is empty or cannot be read.
- Commands typed in capital letters work on a server whose system language is Turkish.
- Built-in web server: a path under a file (`/index.html/x`) or an over-long file name gets a 404 instead of no answer.
- Built-in web server: at most 64 KB a day is logged per visitor address (per /64 network for IPv6), so one visitor
  can no longer use up the day's log and hide the real readers from the reader count.
- The console "edition" line, last-edition.json and the paper-is-out push show `&` and `'` as characters, not as
  `&amp;` and `&#x27;`.
- `/masthead status share` says "API key: set" when the key comes from `api-key-file`, and checks the
  openai-compatible key when that writer is in use.

Construction news
- A build's kind changes at once only for a specific mob kind (spider farm, zombie farm, hoglin farm and so on) whose
  own mob is the top death there, at medium or high AI confidence. The general mob farm, slime farm, iron farm and
  every low-confidence answer wait two papers, like any other kind change.
- A specific mob farm kind needs its mobs to be clearly most: at least twice the next mob that died there in 7 days.
  A near tie prints as "mob farm" and no longer swaps between zombie farm, skeleton farm and creeper farm from paper
  to paper.
- A same-day reprint after the kinds list changed keeps the morning's kind when it gets no new answer; the build is
  asked again the next day.

Setup and files
- README: new parts on what leaves your server, who can read which file, how long things are kept and how to take
  something back.
- config.yml comments corrected: an empty `web.public-url` means no link and no QR code (the comment promised a
  default address); slang counts; what the tone dials open up; which folders `privacy.retention-days` does not cover.
- The shipped word list's header says slang heavy allows 3 words (it said 4).

## 1.0.0-alpha.33 (private test build)

- A build that was in a recent paper keeps its milestone story when it is a farm (the once-only "public works" story no
  longer hides it).

## 1.0.0-alpha.32 (private test build)

Build kinds:
- New kind: hoglin farm (59 kinds). Kinds named from the older list are asked again once, also on a same-day reprint.
- A specific mob kind (spider farm, zombie farm, hoglin farm and so on) whose own mob is the top death at the build
  applies at once, at medium or high AI confidence; it no longer waits two papers behind an earlier guess. The general
  mob farm, slime farm, iron farm and low-confidence answers still wait two papers.

## 1.0.0-alpha.31 (private test build)

Web pages:
- Today's paper comes first; the owner card moved below it, so a phone shows the news on the first screen.
- Digits, commas and periods in the pixel font (headlines, stat tiles, back-issue dates) use Atkinson Hyperlegible, so 5 no
  longer reads as S or 0 as O; letters stay pixel.
- Tighter paragraph rhythm; the reading column is 680px on the front page, Player Cards and Town Survey, so desktop lines
  stay readable.
- Light-mode link, gold and stamp colors pass WCAG AA contrast; the stamp follows dark mode.
- Long unbroken names and headlines wrap instead of pushing the page sideways; arrows stay with their last word; /stats
  never splits after the slash (Firefox).
- Back issues are labelled like "Sat Nov 14, 2026", escaped, with taller tap targets and no box inside a box.
- Owner page: no browser margin, big numbers fit their tiles, and the Economy, silverfish and animal-danger lines are left
  out at zero (no "there's always a gold farm" or "EXTREME (0 and counting)" on a new server).
- Back issues show that day's headline after the date (muted, escaped, cut at a word near 80 characters).
- Owner page slang follows tone.slang: heavy keeps the slang lines ("No cap", "+10,000 aura"); light, off and the
  newsroom voice (which the AI keeps at light) print plain newspaper wording.
- Player Cards: taller jump links; "out of 1 player".

## 1.0.0-alpha.30 (private test build)

- Fix: a build's kind from the facts ("trading hall", "iron farm") counted as an invented claim, so rewrites stripped kinds
  out of the weekly Permit tracker (a permit number with nothing after it). Kinds are now held text, like build names.
- Running storylines no longer feed themselves: a storyline from recent issues comes back only when today's data adds
  a new fact, at most once, never in the headline or deck, and with no edition counters.
- Classic comedy line: every joke must land for a reader who missed earlier issues, one clear picture per joke; the
  earlier "every section needs a skull emoji line" and "push further than a newspaper would dare" quotas are gone.
- Obituaries say only what the death line says (no invented place or activity).
- Construction kinds are hedged only at low confidence, worded differently each time; the Permit tracker always prints
  each row's permit and kind.

## 1.0.0-alpha.29 (private test build)

- The tone settings reach every AI call: the adults line, the trash-talk and "sus" allowances and the drinking rule
  are shared pieces used by the classic paper and its rewrites, the other voices (newsroom, group-chat, sportscast)
  and the editor desk's letter replies. At classic heavy slang the editor desk writes its replies in the readers'
  words too (it used to say "not slang" at every setting).
- The heavy slang word list is configurable: `tone.lexicon.remove` takes words out of it, and `tone.lexicon.add` (kind
  slang, a tier the audience allows) puts the owner's words in. A removed word in a draft sends it back for a rewrite.
- config.yml documents what opens up as the dials go up, and the "sus" rule never covers ore, loot or luck.

## 1.0.0-alpha.28 (private test build)

- Classic voice: `tone.language: strong` asks for 3-4 swears as punchlines, and `tone.audience: adults` adds an adult
  line: ruder, darker, drier, bar and drinking jokes fair game, never about a player's real life, nothing sexual.
  The prompts for `audience: teens` and `all-ages` are unchanged.

## 1.0.0-alpha.27 (private test build)

- Part of the floor now scales with the tone settings. Gamer trash talk about a play (skill issue, noob, ez, washed,
  dogwater) is allowed at spice savage for teens and adults; "sus" jokes at savage for adults only; drinking words and
  emoji for adults. The prompt's hard lines say the same. Everything else in the floor (sexual, slurs, drugs,
  self-harm, looks, family and age words, name jokes, fixed build insults) and the build-location,
  cheating-accusation and fact checks hold at every setting. "unhinged" is allowed at classic heavy slang.
- Heavy slang no longer offers "bro" or "bro thought": players are their name or they/them.

## 1.0.0-alpha.26 (private test build)

- Fewer false alarms in the checks: the floor no longer reads "sleeping with one eye open", "stood up and clapped" or
  "edging" for a close win ("edging out", "edging past") as sexual; a gendered pronoun right after an animal or mob
  ("a cow chewed her cud") is not a guess about a player; in the classic voice the Blotter-order phrases ("that
  same") are checked only in the Police Blotter and Cold Case Files. Every other check is unchanged.

## 1.0.0-alpha.25 (private test build)

- Classic voice, `tone.slang: heavy`, stepped up: the writer is told to sound like the funniest person in the server's
  group chat, hired to write the town paper. More current slang is named (ngl, nah, diabolical, tuff, POV...) and most
  paragraphs get some. The no-tag, no-repeat and safety lines stay.

## 1.0.0-alpha.24 (private test build)

- Classic voice, `tone.slang: heavy`: the officialese is the setup and the punchline lands in the readers' own words.
  The prompt names current slang next to the Minecraft lingo, asks for it inside the sentence (never a tag like
  ", fr."), and not the same word twice or the words recent papers used. At heavy in the classic voice the
  dated-slang check is off and "locked in" is allowed; the AI tics ("chaotic energy", "mad lad"...), insults aimed at
  players and the slang safety lines stay. Other voices and slang levels are unchanged.

## 1.0.0-alpha.23 (private test build)

- New `paper.language`: `en-US` (default) or `en-GB` (also accepts us, american, uk, british, en_GB...). Every AI
  prompt (paper, rewrites, editor desk) asks for that spelling and those words, and the plugin's own dates follow it
  ("Sat Nov 14, 2026" or "Sat 14 Nov 2026"). The mail dialog says "summarised" in en-GB. Not the same as
  `tone.language` (swearing). Old configs get `language: en-US` added on upgrade.
- "mum" and "mums" join the family words the paper never prints, next to "mom" and "moms".
- The classic voice follows `tone.slang`: off has no slang, light keeps "a little", and heavy writes the paper the way
  the readers talk (Minecraft lingo and current slang, no count), with the slang safety lines kept.

## 1.0.0-alpha.22 (private test build)

Privacy and fresh-install fixes.

- Town Survey privacy: a build made secret after the opening leaves the page for good (also when it was later joined
  to another registered build). A secret typed in an old build before any of it was tracked keeps that build out of
  the opening. If survey.json is kept after the opening, a secret typed later inside a build that came only from
  survey.json removes its row too; if the file is gone, the owner is told once.
- The Town Survey page follows `construction.detail` when it is lowered after the opening.
- Build names (signs, `/news build name`, survey names) refuse a position written in words, also run together or
  misspelled ("X Ninety Z Forty Base", "Ninetyfive Shop", "Fourty Base"). Saved names with a number word stop
  printing. The story check catches "x is ninety", "x -forty", "xninety" too, and now runs on every draft, letter
  and reply (before, it was only wired into a test path).
- At `detail: standard`, zombie, skeleton and raid farms print as "mob farm" (they point at dungeons and outposts).
- The kinds AI call sends a build's height in blocks, not its world Y levels.
- Police Blotter: taking from a hopper, or from a chest a hopper feeds, is using a farm or machine, not news. Items
  taken and put back in the same player's chests the same day cancel out. A farm hopper counts only when the chest's
  owner placed it (or nobody known did), so someone else's hopper aimed at a chest cannot hide taking from it.
- A free local AI server settles at $0 after a broken reply, an oversized reply or an interrupted call.
- "[mail the editor]" shows only to players with `masthead.write`.
- Fresh install: no empty Town Survey page is written before there is a register; players.html is always written,
  so the front page link never 404s; `/masthead status` lost a stray space.
- README: construction news adds one small AI call per published paper. README and config.yml comments corrected
  (slang limits, mailroom, permissions, when worried-letter pushes name the player, construction commands).
- config.yml: a comment explains `web.bind`.
- Tests: a flaky exchange-writer test is fixed.

## 1.0.0-alpha.21 (private test build)

Specific build kinds, and what is inside a build.

- 58 kinds (all 20 old ones still work): house, base, storage, tower, library, greenhouse, enchanting room, brewing
  room, smelter, trading hall, villager breeder, nether portal, bridge, road, rail line, wall, garden, decoration,
  redstone machine, item collector, farm, auto farm, wheat / carrot / potato / melon / pumpkin / sugar cane / cactus /
  bamboo / kelp / tree / mushroom / flower / berry / nether wart / bonemeal farm, slime / mob / spider / zombie /
  skeleton / creeper / witch / enderman / guardian / blaze / gold / raid / iron farm, chicken / cow / wool farm,
  animal pen, bee farm, fishing farm, lighthouse, ship. `/news build kind` takes all of them.
- A specific kind must be shown by the build's blocks or deaths, or it is not used: a melon farm needs 8+ melon
  stems, a spider farm needs spiders as the top death (16+ in 7 days; from alpha.34 also at least twice the next mob), a library 24+ bookshelves, a smelter 8+
  furnaces (mostly plain ones, or fed by hoppers), a greenhouse 30+ glass and 16+ plants, a nether portal 10+
  obsidian, a rail line 32+ rails. If the AI's first kind fails, its second is tried, then the first kind's general
  kind (melon farm -> auto farm, spider farm -> mob farm, library -> house), then the rules. Villager and animal
  kinds are never ruled out: the recorder can't see villagers or animals.
- The AI's card now also lists the telling blocks (job blocks, bookshelves, crops, glass, beds, rails, furnaces,
  chests, machine parts) even when they are not in the top 25, and the checks read the same counts.
- New "features" on each story: what the build has inside, with real counts, from a fixed list: trading by villager
  job (17 lecterns = librarian trading, 19 blast furnaces = armorer trading), bookshelves, enchanting tables, brewing
  stands, a smelter's furnaces, chests and barrels, beds, beacons, bells, a nether portal's obsidian, crops by type,
  observers and pistons. Barrels, composters, brewing stands and cauldrons count as trading only when villagers of
  that job were seen (the survey). The writer is told to name them with their numbers; the fact check can find
  every number. The plain edition prints them ("Inside: 17 lecterns for librarian trading.").
- The few kind and feature words with a banned word inside ("nether portal", "mushroom farm", "nether wart farm",
  "nether wart", "cave spider spawners") are allowed as whole phrases, but only when today's builds have them, only
  at a detail level that prints them, and only as the thing itself ("a nether portal", "18 obsidian blocks for nether
  portal"). As a place ("at the nether portal", "by a nether portal") they are caught, and so is the word alone
  ("near the nether"). "nether portal" is not a build trigger word outside the section (players jump through portals in death
  and advancement lines), nor is "chicken farm" on a chicken-themed paper.

The opening edition: the first paper that prints construction news.

- Every build the paper may print gets its permit number that day, and so does every build in an optional survey
  file (`construction/survey.json`: older builds listed by the server owner, with their kind, builders and what is inside).
  Numbers are kept, so a re-run never changes them, and a build on the register is never "new permit" news again.
- A survey build that holds most of a tracked build is one listing with both crews. One that only touches a
  tracked build is listed on its own.
- A survey build is left out, with a note for the owner, if a builder may not be named (private, hidden, no notice
  yet, unknown) or if any part of a tracked build that may not print is inside it (secret, private, or built by
  a hidden player, even one taken down). Bad rows are left out with a note too, and so is a survey.json that
  can't be read.
- Planning & Zoning carries up to 5 stories that day (not max-entries), picked by size, what is inside and
  different kinds, one per crew, and says how many builds the survey registered.
- A new Town Survey page (survey.html), same look as the player cards: every build on the register, grouped (homes,
  farms, mob farms, villagers, rooms and workshops, public works, landmarks), with its kind, builders, what is
  inside (as the detail level allows) and blocks. Never where it is. The front page links to it at the end of Planning &
  Zoning. It stays up after the opening day and is drawn again with every published paper (construction news on
  or off): a builder who goes private, is hidden or is forgotten leaves it, and if nobody is left it says the
  register is empty. Its rows are kept by player ID in `construction/players.json`. A preview never changes it.
- The plain edition prints the same stories and the link.
- A survey build can have a name ("Ye Olde Shop"), printed like a player's build name in its story and on the Town
  Survey page, only when the new `construction.survey-names` is true (off by default). A name that fails the build
  name rules is left out with a note for the owner; the build still lists.
- A preview now shows construction news as it would print, even while printing is off; it keeps nothing.
- After the opening day the paper is as before.
- Known limit: the opening counts as done when its paper is folded, not when it is published. A retry the same
  day redoes it, but if that day's paper never publishes, the opening is not printed later.
- New `construction.detail` (basic | standard | full, default standard): basic prints general kinds and nothing
  inside; standard leaves out what points at loot or a place (chest and barrel counts, beacons, spawners, and
  cactus, bamboo, mushroom, nether wart, kelp and cocoa; guardian, blaze, witch, enderman, gold and spider farms
  print as "mob farm"); full prints everything. Stories, the Town Survey page, the plain
  edition, awards and the blotter all follow it. "1 spider spawner", not "1 spider spawners".
- New `construction.sign-names` (default false): a sign a builder writes on their own unnamed build ("ye olde shop",
  "MAIL DEPT") names it ("Ye Olde Shop", "Mail Dept") if it reads like a place and passes the same checks as /news build
  name, which always wins. Labels like "EGGS IN" are ignored. Signs written before it is on are not read.

No new dependency.

## 1.0.0-alpha.20 (private test build)

Build kinds: every build in the paper now gets a real, specific kind.

- A builder's own label (`/news build kind`) always wins.
- Anything without a label is named by the paper's AI writer, in one call per published paper. The AI sees only
  numbers and block names under stand-in letters: no player names, build names, sign text or positions. At most 30
  builds a day, today's news first. Answers the numbers rule out (one builder is never a shared base, a few chests
  are not storage) fall back to the AI's second choice, then to the rules.
- The answer is saved on the build and asked again only when the build changes a lot or after 14 days. A build's
  first AI kind prints at once; later changes wait two papers. When the AI is off or fails, the rules and then the
  build's shape decide. A preview only reads saved kinds.
- It runs on published papers even while construction printing is off, so kinds are ready when printing starts.
  A paid writer skips it when less than 10% of the month's budget is left.
- 20 kinds: house, base, storage, tower, farm, auto farm, slime farm, mob farm, iron farm, trading hall, villager
  breeder, animal pen, bee farm, garden, redstone machine, bridge, road, wall, item collector, decoration.
- "structure", "unclassified" and "declined to classify" never print. Award reasons say "project" unless the kind
  is certain. A kind of "build" saved by an older version counts as no kind.
- What a build produces now helps name it. For each build the recorder counts, over 7 days, the mobs and animals
  that die in or right next to it (any cause; player deaths are not counted). A death in a chunk where builds stand
  counts only for those builds. The AI sees only the top 8 types and their counts, never where. A slime farm that
  kills slimes reads as a slime farm, even when its spawning floors were dug out. A build is asked again when its
  main kill changes.
- The counting is light: one listener, on mob deaths only; per death a chunk lookup and a counter per build, saved
  once a minute; no world reads, no chunk loads.
- A preview, or a paper re-run the same day, now uses the kinds the AI gave that morning (it used to fall back to the
  rules' guess), and a same-day re-run does not ask again.
- The paper's writer no longer sees kind ids like "auto_farm": it gets "auto farm", and the construction section is
  sent back for a rewrite if it shows a word joined with underscores. "base" no longer triggers the build check, so
  "back to base" in a story is fine. A build's world is "overworld" from the world's own type, not its order.
- If preparing the builds for the kinds call takes too long, they are not sent and the owner's notes say so.
- Going back to alpha.19: papers with a build of a new kind drop that build for a paper or two, until alpha.19
  re-guesses it. The production counts are ignored and later removed.
- Developer: `GoldenTool kinds CARDS.json` prints the exact kinds prompt, to try with `claude -p` before a deploy.
  `GoldenTool site-cards CONSTRUCTION_DIR DATE` prints the cards and prompt the plugin would send from a server's
  data (worked on a copy; the folder is not changed).

Join message: each of the two awards now has its own [why?]. Hovering shows that award's
reasons; clicking opens /awards. One button at the end of the line read as if only the second award had a reason.

No new dependency or configuration option. sites.json gains a few optional fields per build (older versions ignore them).

## 1.0.0-alpha.19 (private test build)

- Tip line: hold a tip reply that adds police words (sheriff, deputies, cops, jail, witnesses, authorities, charges,
  "chasing leads") or repeats the tip's police words as the paper's own statement. Innocent "lead" (the leash) and
  "in case" still print. A tip reply may not add numbers the tip did not give.
- Tip anonymity: the editor desk no longer sees who sent a tip, so it cannot hint at the sender through their stats.
- A free local AI server (both prices 0, local address) that times out no longer blocks the AI writer for good.
- `/news write` and `/news letter` now respect `masthead.write`, like `/letter` and the form's submit button.
- An owner listed in `privacy.hide-players` is treated as no owner everywhere (front page, prompts, checks), and
  `owner.name` matches the in-game name in any letter case, so `owner.hours-ok: false` works.
- Developer GoldenTool `render` writes `editions/DATE.html` again.

No new dependency or configuration change.

## 1.0.0-alpha.18 (private test build)

- Send player news tips to the newspaper editor and print accepted tips under Letters to the Editor.
  Recorded server incidents keep their separate Police Blotter. Existing submitted-tip storage remains compatible.
- Stage the edition's output files before publication and restore the previous files if an ordinary write or
  replacement fails. Rendering no longer changes the published archive early; previews keep their private archive.
  This is not crash-atomic publication or a snapshot guarantee for concurrent readers.
- When the monthly budget is enabled, persist an unresolved paid-request marker before each billable attempt. If a request's charge cannot be settled,
  later paid calls remain blocked across new writer instances and restarts until the owner reconciles the ledger.
  Retry attempts also check the budget. Provider-reported or configured pricing is still required for useful totals;
  this ledger is not a provider-enforced spending cap.
- Correct documentation about numeric fact-check coverage, rewrite/salvage attempts, Dialog API history and the
  distinction between Java 21 bytecode and the Java 25 runtime required by current Paper releases.

No new dependency or configuration migration is part of this version.
