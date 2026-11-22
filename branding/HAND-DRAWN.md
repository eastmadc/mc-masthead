# A hand-drawn icon (for Modrinth)

Modrinth does not allow images "created or derived from generative AI output" on a project page (rules §6.2(1)). The
set in this folder was made with an AI image tool. Everything an AI makes counts too, including an icon Claude "draws"
in code. Tracing or copying the AI icon pixel by pixel also counts, because it is derived from it.

The clean way is for a person to draw an original icon from the written brief:

1. Open Piskel (piskelapp.com, free, runs in the browser) or Aseprite.
2. Make a new sprite of 32 × 32 pixels and load the colours from `masthead.gpl` (Piskel: Palettes → Import).
3. Draw from the brief in `docs/ICON-PROMPT.md`, "Deliverable 1": a folded newspaper, a bold masthead band with a big
   blocky "M", 2-3 headline bars, a small photo block, a dark outline and a little padding. Don't trace or copy the AI
   icon: make your own version.
4. Export a PNG at 32 × 32 and at 512 × 512 (Piskel: Export → PNG, scale 16). Save them as `branding/hand-32.png` and
   `branding/hand-512.png`.

It takes about 30 minutes, and the person who draws it can sign it as their own work. Modrinth then gets the hand-drawn icon. GitHub and
Hangar can keep the AI set or switch to the hand-drawn one.
