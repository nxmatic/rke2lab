---
name: diagram-preview-file
description: "When presenting mermaid/diagrams to the user, build a Claude artifact (self-contained HTML + mermaid, published via the Artifact tool) — more readable than the old kroki/VSCode preview; the .claude/claude-preview.adoc scratch file + kroki is now the offline/in-editor fallback"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 4d3d8a2e-f292-4cbe-a699-fb4abfbd1e6c
---

**★ MAJOR UPDATE 2026-07-28 — prefer CLAUDE ARTIFACTS over the kroki/asciidoc preview.** The user
discovered Claude UI *artifacts* (self-contained HTML published via the Artifact tool, rendered on
claude.ai): they render mermaid NATIVELY, are far more readable than the VSCode asciidoctor+kroki
preview, and sidestep the ENTIRE kroki-server / CSP / local-vs-online / long-label / subgraph-label
saga below (all of which was kroki-BUILD-specific, never a universal mermaid rule). **New default for
whiteboards / design diagrams:**

- Build a **self-contained HTML artifact** — inline CSS/JS, mermaid in `<pre class="mermaid">` (the
  artifact runtime renders it; no kroki, no server, no CSP). Publish with the Artifact tool;
  **republishing the SAME file path keeps the SAME URL**, so the user re-looks at a pinned browser tab
  exactly as with the old pinned preview.
- Refinements that landed well (2026-07): draw each mermaid on a **fixed-white "board"** (a literal
  whiteboard) so figures read in both light/dark while the page chrome stays theme-aware via CSS
  tokens; a **click-to-zoom lightbox** (clone the figure into a full-screen overlay with +/−/reset,
  Esc/backdrop to close) makes dense diagrams legible — the user explicitly liked this; a stable
  `<title>` + emoji `favicon` + one-line `description`; C4 levels (context/containers/components) + a
  flow/sequence + a decision panel (composes with [[decision-options-in-preview]] /
  [[options-always-as-c4-diagrams]]).
- The kroki **safe-dialect constraints below DO NOT apply** to the artifact's mermaid (it is a full
  build): `subgraph id["Title"]`, parentheses in edge labels, long labels, non-ascii all render fine.
  Keep labels reasonably short for READABILITY, not as a hard limit.
- Still load the `artifact-design` skill before authoring (it calibrates the treatment), and remember
  the artifact is private until the user shares it.
- `.claude/claude-preview.adoc` + the VSCode kroki preview is **retained as the offline / in-editor
  fallback**; everything below is kept as history for that path. The artifact is the default because it
  is more readable with zero server/CSP setup.

When I present a mermaid diagram (or any schema) for the user to look at, I must ALSO write it to a
single stable scratch file INSIDE THE WORKSPACE: **`.claude/claude-preview.adoc`** (NOT /tmp — the
extension only renders workspace files; NOT under `docs/` — it pollutes the real-docs tree, relocated
2026-06-08). I OVERWRITE it each time with ONLY the diagram(s) from my latest query. The user keeps
that one tab open in VSCode with the AsciiDoc preview pane on (mermaid in chat is not rendered for
them). Confirmed working end-to-end 2026-06-08.

**Why:** the user reviews designs from diagrams, not prose or Java ([[docs-diagrams-not-java]]), and
mermaid only renders in VSCode's preview. A single fixed path means they leave the preview pinned and
re-look after I say I've updated it.

**How to apply:** (1) Write the diagram to `.claude/claude-preview.adoc`, replacing prior content;
(2) tell the user it's updated; (3) the canonical diagrams also go into the real design doc — the
scratch file is a *view*, not the source of truth. The repo-root `.asciidoctorconfig` (kroki URL)
reaches `.claude/` too, since resolution walks up to the repo root.

**★ REVERSAL 2026-06-16 — the ONLINE `kroki.io` is now the DEFAULT and renders everything; the LOCAL
container was the failing one.** The repo switched `.asciidoctorconfig` to the public server by default
(rke2lab main commit `abc56741` "chore(kroki): use online server by default" — the `:kroki-server-url:`
line is now commented out, so the extension falls back to its built-in `https://kroki.io`). With the
online server, figures that the LOCAL `bioskop-nixos` container rejected NOW RENDER FINE — including the
"long-label + several subgraphs" diagrams that 400'd locally (see the 2026-06-15 long-label gotcha
below). So that 400 was a limit of the LOCAL kroki/mermaid BUILD, **not** a universal mermaid rule, and
**not** the public server. This also INVERTS the 2026-06-08 claim that "public kroki.io only serves its
cache and can't fresh-render from this network" — today it fresh-renders new/edited diagrams correctly
(network/topology changed, or that 2026-06-08 diagnosis was partial). **PRACTICAL RULE NOW:** default to
the online server (no `:kroki-server-url:` needed); short labels remain good hygiene for readability but
are NO LONGER a hard constraint; https also sidesteps the http-CSP webview gotcha entirely. The
local-kroki + CSP material below is retained as history in case the local server is ever reinstated.

**PROVEN ROOT CAUSE of the empty-frame saga (2026-06-08) — it was the KROKI SERVER + the webview CSP,
NOT the mermaid syntax.** All my earlier syntax theories were WRONG and are retracted (it was NOT
strict-ASCII, NOT quoted edge labels, NOT dotted arrows, NOT multi-block, NOT subgraph). Three real,
stacked causes, in order of discovery:
1. *Public `kroki.io` only serves its CACHE and cannot do a fresh render from this network* — so any
   NEW/edited diagram showed an empty frame, while a byte-identical copy of an already-cached block
   rendered. FIX: run a LOCAL kroki (+ kroki-mermaid companion) — done on bioskop-nixos podman, see
   `.dev/kroki/compose.yaml` and link:../../docs/guides/diagram-preview-kroki.adoc (committed
   74a081c6).
2. *The extension reads the server URL ONLY from the doc attribute* `kroki-server-url`
   (`document.getAttribute('kroki-server-url') || 'https://kroki.io'`), NOT from `.vscode` settings.
   FIX: put `:kroki-server-url: http://bioskop-nixos.local:8000` once in a repo-root
   `.asciidoctorconfig` (the extension prefixes it to every .adoc). **ADDRESS CORRECTED 2026-06-10 —
   use the mDNS name, never a tailscale IP.** TOPOLOGY (the thing to remember): seed-master is ALWAYS
   operated from `bioskop` (reached via screen-sharing to the RDP host); `bioskop` and the kroki host
   `bioskop-nixos` are on the same LAN, so `bioskop-nixos.local` (→ 192.168.1.130) is the reliable
   channel that BOTH this shell and the VSCode webview resolve. The stale `100.64.0.15` tailscale IP
   gave empty frames; the earlier "tailscale is stable across networks" rationale was a mix-up with a
   DIFFERENT machine (`nikopol`, on a guest hotspot) — irrelevant to the bioskop work environment.
   Verified live: POST to `http://bioskop-nixos.local:8000/mermaid/svg` returned a real SVG (HTTP 200).
3. *The webview CSP allows `img-src https:` but NOT `http:` at the default security level* — so the
   http:// kroki image was blocked even though the server rendered it (verified: server logged
   "Request received /mermaid/svg" + "Convert took 170ms"). FIX: VSCode palette → "AsciiDoc: Change
   Preview Security Settings" → "Allow insecure content" (per-workspace, manual). OR serve kroki over
   https. Diagnosis method that finally worked: enable kroki DEBUG logging, watch whether the server
   receives a request on reload (it did → ruled out URL/cache, pointed at the webview/CSP).
Caveat still true: the preview can show a STALE render after an external write; a window reload forces
a re-read. SVG via mmdc remains a reliable last-resort fallback (renders any mermaid, displays
natively in VSCode without kroki).

**Additional kroki dialect gotcha (2026-06-08): `subgraph` must NOT carry a bracket label.**
`subgraph Name["Title"]` breaks the render (empty frame); use a bare `subgraph Name` (id only). Same
empty-frame symptom as the edge-quote issue. Safe-dialect checklist for kroki mermaid: solid arrows
`-->`, nude edge labels `-->|label|`, node text in `ID["..."]` ok, NO `subgraph X["..."]`, avoid
`@ + / :` and unicode inside the block. **Edge labels must contain NO parentheses** (2026-06-08:
`-->|snapshotFor(entry)|` broke the render; `-->|snapshotFor entry|` fixed it). Parens are fine inside
node text `ID["foo(bar)"]`, only edge-label text rejects them — same class as the edge-quote issue.

**Refinement (2026-06-15): `subgraph "Title"` (QUOTED title, no id bracket) renders fine** — it is
`subgraph id["Title"]` (id + bracket) that 400s, not a titled subgraph per se. Use the quoted form.

**NEW root cause found 2026-06-15 — a LONG-LABEL layout threshold.** Two long node labels combined
with several subgraphs reproducibly 400 the local kroki/mermaid build (`Error 400: Internal Server
Error`, not a lexical error), even though each long node renders fine in isolation. It is a dagre
layout limit, NOT a syntax/token issue. FIX: keep node + subgraph labels SHORT (a few words). A whole
afternoon of line-by-line bisection traced it here — don't re-bisect; shorten labels first.

**NEW operational gotcha 2026-06-15 — "Allow insecure content" is per-workspace-PATH.** After the repo
moved to an external-worktree path (`rke2lab.d/main`), the local http kroki rendered (server logged the
request) but the webview stayed blank: the CSP "allow insecure content" grant was tied to the OLD path
and does NOT carry to the new one. Re-grant it for the new workspace (palette → AsciiDoc: Change Preview
Security Settings → Allow insecure content). DIAGNOSIS that nailed it: pointing `.asciidoctorconfig` at
the public `https://kroki.io` made figures appear (https bypasses the http-CSP) — proving server+syntax
were fine and the blocker was the http CSP. Caveat: public kroki only serves its cache, so revert to the
local URL + the CSP grant for new/edited diagrams. Also: `.asciidoctorconfig` is read at preview OPEN —
a plain re-render won't pick up a change; do Developer: Reload Window.
