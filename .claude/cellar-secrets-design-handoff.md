# Handoff — cellar-secrets design lock (git-edge + OSGi-side seal)

**For:** the master conversation.
**From:** the `feature/cluster-seed-scenario` worktree session.
**Ask:** confirm this path before implementation lands. Nothing has been coded yet — this is a
design lock coming out of a working session over `docs/architecture/atlas/cellar-secrets.adoc`
(commits `5978becd` + `19819bda`) and the seed-broker spec § @Amendment.

**Master review — adopted (2026-07-26).** The master conversation reviewed this and **confirmed
#2, #3, #4** (two of them rated better than the original atlas placement). It **rejected #1
("git-edge / pure jgit")** on three counts, all correct, now folded in below:
(A) the cipher is `javax.crypto`/`jagged`, **not jgit** — jgit encrypts nothing, so "pure jgit" is
a misnomer; (B) the jgit **sensitivity oracle is YAGNI** — #3 has the scion declare sensitivity, and
`.secrets` is a sops file so **everything read from it is secret by construction**; reading it is a
plain file read (git already smudged it), no git component needed; (C) the real trap — **`.sops.yaml`
governs the git repo's recipients, NOT the cellar's**. The cellar is a separate store with its own
recipients (seeding-cluster → seeded-cluster); making the cellar cipher read `.sops.yaml` would
conflate two distinct recipient policies. So **#1 is replaced by a plain `CellarCipher` seam** (no
jgit, no `.sops.yaml`, not called "git-edge"); the git-edge idea is dropped.

**Visual:** the whiteboard was updated to match — `scratchpad/cellar-secrets-whiteboard.html`
(session `5f865aeb…`). The four deltas are called out at the top; the C2/C3/sequence diagrams and
the git↔sops↔cellar table now reflect them.

## The four refinements to the atlas (this is what changed)

1. **A `CellarCipher` seam — NOT a git-edge (master-corrected).** A clean OSGi seal/reveal seam
   consumed by `CodecCellar`, with **its own recipient/identity config** (not `.sops.yaml`). No jgit,
   no sops-config reading, no sensitivity oracle. Rationale: the cipher is `javax.crypto`/`jagged`
   (jgit encrypts nothing, so "pure jgit" was a misnomer); the oracle was YAGNI (the scion declares
   sensitivity per #3, and `.secrets` is a sops file so all of it is secret by construction — reading
   it is a plain file read); and `.sops.yaml` governs the **git repo's** recipients, which are a
   different set from the **cellar's** recipients (seeding-cluster → seeded-cluster). The seam lives in
   the neutral cellar port; impl is passphrase (`javax.crypto`) now → `jagged` (age) later.

2. **The seal moves OSGi-side.** `CodecCellar` (OSGi) seals via the `CellarCipher` *before* the payload
   crosses; `PulumiCellar` becomes a **dumb opaque byte store** — zero crypto host-side. Matches the
   atlas C3 label (`OpaqueCellar — sealed String → PulumiCellar`) and dissolves the snag that
   PulumiCellar's fetch reads raw checkpoint JSON (it never has to decrypt — what it stored is already
   opaque; the raw-history walk keeps working untouched). *Master: better than the original host-side
   `Output.secret` placement — the harvest plaintext no longer crosses the seam.*

3. **Sensitivity is a `store` argument, not a registry.** A `Sensitivity {PLAIN, SEALED}` parameter on
   the OSGi `Cellar.store`, declared by the harvesting scion **where the harvest is born** ("know the
   origin"). Not a host coordinate→sensitive registry, not a persisted field on the crossing
   `SeedEnvelope`. Reveal needs no flag — the sealed wrapper self-identifies. Keeps the ❌
   don't-trace / re-declare-where-born discipline, realised as a store arg. *Master: consistent with #2.*

4. **The cipher stays pure-Java.** Passphrase (`javax.crypto`) is the degenerate single-recipient case
   of the `CellarCipher`. The multi-recipient generalisation is **age via `jagged`** (pure-Java, the
   sops key-slot shape — a data key wrapped once per recipient), through the *same* seam — not the sops
   CLI. Full-sops / ssh→age stays `ssh-to-age-edge`'s job; the cellar cipher never shells out.

## Unchanged from the atlas

Three axes (coordinate · role · sensitivity); the unified two-file input (Pulumi stack config +
`.secrets`) merged by coordinate; `worktree.dir` derived at runtime + an untracked local overlay for
per-worktree prefs; one `AmendmentContributor` per coordinate (generalising `ManifestsFacetContributor`);
bbox transport wired independently (no cellar mark — its password never reaches the cellar); the
sequence **foundation → live wiring → bench**. Single live master (bioskop/nikopol), no production.

## Build order (once confirmed)

1. **Foundation cipher seam.** `CellarCipher` seam (passphrase impl, `javax.crypto`) + `Cellar.store(…, Sensitivity)`
   + `CodecCellar` seal/reveal (self-describing wrapper). Its recipient/identity config home is decided
   here (mono: the passphrase suffices). No jgit, no `.sops.yaml`. Covered by its own tests.
2. **Unified input.** Two-file read merged by coordinate; per-coordinate `AmendmentContributor`
   registration; `worktree.dir` derived at runtime; untracked local overlay.
3. **bbox transport (independent).** Host contributes `.secrets:lan.bbox` (uri + password) as `FACET`
   on the `bbox` coordinate; bbox reads uri+password instead of the hardcoded `"admin"`. Unblocks the
   live login. Needs a `BboxAmendReflector` + `@SeedContract`/`@Amendment` bbox runbook input +
   `BboxCoordinate.AMEND` + a host `BboxFacetContributor` (mirrors the manifests amend path).
4. **bench-cellar** (sibling of bench-startlevel/bench-scr, `@OsgiWorld` LDAP `suite=cellar`): 4 theses
   — roster · independent smudge · additivity · anti-cheat; crypto = faithful stand-in (data-key
   wrapped per recipient). Then generalise passphrase → age/jagged.

## Open items to flag (not blocking, but worth a decision)

- **Cellar recipient/identity config home (master flag).** The OSGi cipher needs *its* keys —
  passphrase (mono) now; recipient pubkeys (to seal) + local identity (to reveal) for age (multi).
  These are the **cellar's** recipients (seeding→seeded cluster), NOT the git repo's `.sops.yaml`
  recipients. Needs a config home distinct from `.sops.yaml`. Decide at live-wiring: mono ⇒ the
  passphrase suffices; multi waits for the bench anyway.
- **`GitProvenanceReader` is really an edge.** Noted during the session: jgit-in-`incus-core` is an
  edge by nature but placed in-container by the topology-ownership rule (the incus scion reads the
  worktree it reconstructed). Candidate to reshape onto a neutral git port as a **separate refactor**,
  unrelated to the cellar cipher — not folded into cellar-secrets.
- **Ambient files not to carry** (another session's uncommitted edits): `.secrets`, `Pulumi.dev.yaml`,
  `.gitignore` (the `n#` typo — leave it), `.asciidoctorconfig`, `.ndh-ssh.d/keys.yaml`, `bom/pom.xml`.
  The code *derives* `worktree.dir` and *reads* `.secrets` without editing these files.
