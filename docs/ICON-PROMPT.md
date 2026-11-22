# Icon and imagery prompt for Masthead (mc-masthead)

Paste everything between the lines into each model. Generate 4 or more options per model, then score them with the
checklist at the bottom.

---

**Design brief: icon and banner for "Masthead", a free Minecraft server plugin**

What it is: Masthead is a plugin for Minecraft (Paper) servers. Every morning it writes a funny daily newspaper about
the server, built from real game stats: who built what, who fell in lava, awards, horoscopes, a police blotter. Players
read it on a web page (through a QR code on an in-game map) and as an in-game book. The audience is server owners and
their players, mostly teenagers.

**Deliverable 1: the project icon (most important)**
- **Subject:** a folded newspaper, seen slightly from the front. Its top band, the masthead, is a bold dark bar.
- **The "M":** a single large blocky letter "M" sits in the masthead.
- **The body:** below the masthead, 2-3 chunky "headline" lines and a small block that suggests a photo.
- **Optional details**, only if they stay clear at small size:
  - a tiny QR-like pixel square in one corner;
  - a small sparkle;
  - a printing-press "fresh off the press" motion line.
- **Style:** original pixel art in the spirit of blocky voxel games.
  - A clean 32×32 pixel grid, scaled up with hard edges: no blur, no anti-aliasing, no gradients.
  - Strong silhouette, 1-pixel dark outline, limited palette of 6-8 colors.
- **Palette:**
  - warm newsprint cream (#F3E9D2) and ink black (#1D1B18);
  - one bold accent for the masthead: deep red (#B83A2E) or emerald green (#2E8B57);
  - a gold highlight (#E0A55E).
- **Background:** solid, either the accent color or a dark charcoal (#22201D), filling the whole square. Make a second
  version on a transparent background.
- **Readability:** it must read clearly at 32×32 and 64×64 pixels, so no small text.
- **Size and composition:** square, 512×512 (and 1024×1024 if possible), centered, with about 8% padding.

**Deliverable 2: banner / social preview**
- Size: 1280×640 (GitHub social preview); also a 1200×400 crop for Hangar/Modrinth headers.
- Same pixel-art style and palette. On the left, the newspaper icon at large size.
- On the right, room for the title "Masthead" and the line "The AI newspaper for your Minecraft server".
  - If your tool renders text badly, leave that area as a clean flat panel; the text will be added by hand.
- Optional background: a soft, blocky landscape silhouette (rolling hills, a few blocky trees) in muted tones. Keep it
  generic.

**Hard rules (the listing will be rejected or taken down if broken):**
- Do NOT use Minecraft's logo, wordmark, font, or the word "Minecraft" in the image.
- Do NOT show any Mojang character, mob or item likeness:
  - no Creeper face, Steve, Alex, Enderman, villager, pickaxe icon or diamond icon;
  - no real Minecraft block textures, such as grass block sides, dirt, cobblestone or TNT.
  - Everything must be original shapes and flat colors.
- No people, no children, no faces.
- No animals: keep the product art generic.
- No other brand marks or logos, and no Claude/Anthropic logo.
- No hidden or extra text, watermarks, signatures or fake QR codes that contain data.

**Output:**
- PNG files at the sizes above.
- Image models: give 4 distinct variations. Vary the accent color (red / green / blue #2E6BB8), the angle (straight-on
  vs. slight tilt) and the background (solid vs. transparent).
- Text or code models that cannot make images: produce the icon as a hand-written SVG on a 32×32 grid of `<rect>`
  pixels (`shape-rendering="crispEdges"`, viewBox 0 0 32 32), with no filters or gradients. Then produce the banner as
  an SVG at 1280×640 that reuses the icon.

---

## Model-specific notes (add to the brief when using that tool)

- **ChatGPT / DALL·E, Gemini / Imagen, Grok:**
  - Paste the brief as is.
  - Then ask: "Make the 512×512 icon first. After I pick one, make the banner to match."
- **Midjourney:**
  ```
  original pixel art icon, folded newspaper with bold dark masthead bar and big blocky letter M, chunky headline lines, 32x32 pixel grid upscaled, hard edges, 1px outline, flat colors, cream and ink black with deep red accent and gold highlight, solid charcoal background, centered, no text, game asset style --ar 1:1 --style raw --no minecraft logo, creeper, steve, block textures, people, chickens, watermark, gradient, blur
  ```
- **Stable Diffusion / Flux:**
  - Use the same positive text.
  - Use the `--no` list as the negative prompt, plus: anti-aliasing, 3d render, photo, realistic.
  - Settings: steps 30-40; then downscale with nearest-neighbor to 32×32 and back up to check that the pixels are
    clean.
- **Claude, or any LLM writing SVG:** ask for the SVG version described in "Output", then render it in a browser at
  32, 64 and 512 px.

## Scoring checklist (pick the best per model, then the winner)

1. Recognisable as a newspaper at 32×32 (view it at actual size).
2. The "M" reads at 64×64.
3. Feels like a blocky-game plugin, but contains nothing from Minecraft itself (re-check the hard rules).
4. Works on both light and dark page backgrounds.
5. Distinct from other plugin icons on Hangar's front page.
6. Clean pixels: no blurry edges, stray colors or hidden text.

## Where each image may be used (important)

- **GitHub and Hangar:** an AI-generated icon is fine (Hangar's guidelines say nothing about AI images; checked
  2026-11-05).
- **Modrinth:** its rules (§6.2(1), last changed 2026-08-13) say no image on a project page may be "created or derived
  from generative AI output". That covers the icon, gallery and description images. For Modrinth, the icon must be
  made by a person, for example drawn in a free pixel editor such as Piskel or Aseprite.
  - Hand-tracing a picked AI image still counts as "derived from" it.
  - The safe route is a person drawing it from this brief.
  - The 32×32 grid makes that a 30-minute job.
