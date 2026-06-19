---
name: contract-placement-and-versioning-carto
description: "DESIGN/CARTO (read-only on integration @4b13a39f, 2026-06-19): a re-placement decision the user surfaced while reviewing the bridge→contract rename. The contract modules (manifests-contract, netplan-contract) are currently PLAIN JARS in host/, and impl bundles import their package UNVERSIONED — the only unversioned OSGi-consumed import. The user's insight: the contract is a PURE MODEL (interfaces + records, no pulumi/grpc) → by the atlas purity axis its nature is osgi/, NOT host/; it should be a VERSIONED OSGi bundle living in osgi/, consumed as a plain Java jar by the host. This also fixes a hexagonal error: the PORT belongs to the DOMAIN (osgi/), not the adapter (host/) — 'host owns the port' was who-consumes confused with what-nature. Runtime model VALIDATED against the osgi.core archetype: a versioned API jar, NOT installed as a bundle, imported with version by bundles AND exported flat via system.packages.extra (R1 single-exporter). CONCLUSION: rename slice should be re-scoped to rename+re-place+bundle-ify together; do NOT merge it as-is (it re-confirms the wrong host/ placement). Name `-contract` stays."
metadata:
  node_type: memory
  type: project
---

## Origin — the user's challenge during the rename review

Reviewing the bridge→contract rename (in flight, NOT merged), the user noticed the GENERATED manifest of
`manifests-core` imports the contract package UNVERSIONED:
`io.nxmatic.rke2lab.manifests.contract` (no `;version=`), while every other OSGi-consumed import IS
versioned (`unitrepo.core;version="[0.1,1)"`, `org.osgi.resource;version="[1.0,2)"`, jackson `[2.22,3)`).
First read (mine): "unversioned because delivered by the host classloader, correct, no fix." The user
pushed back twice and was right both times:
1. "the contract interfaces arrive via the Java classloader, not versioned" — yes, BUT that is a
   CONSEQUENCE of the plain-jar choice, not a justification; and
2. "maybe this shows an incoherence — the contract modules should actually be OSGi bundles, cross back
   to the osgi/ side, yet be consumed in plain Java on the host side."

That second point is the real design correction. It is right.

## Why the contract belongs in osgi/, as a bundle (three converging reasons)

1. **Purity axis (the atlas).** The atlas: "OSGi space — pure models that DESCRIBE; no com.pulumi, no
   io.grpc." The contract is exactly that — interfaces + records, zero engine imports. By NATURE it is an
   osgi/ model. We placed it in host/ by WHO-CONSUMES (the host imports it), conflating consumer with
   nature. Nature decides the space; consumption is a dependency edge, not a placement criterion.
2. **Hexagonal correctness.** In hexagonal architecture the PORT belongs to the DOMAIN (the core), not to
   the adapter. The host is an adapter; the contract is the domain's port. "The host owns the port" was
   backwards. Correct DIP: BOTH the host (adapter) and the impl bundle depend on the abstraction; the
   abstraction is pure → it lives with the domain (osgi/). Both arrows point into osgi/, neither world
   depends on the other's impl.
3. **Versioning falls out.** As a real bnd bundle with a `package-info.java @Version`, the contract
   package gets a semver; impl bundles then import it WITH a version, removing the lone unversioned
   import. The contract — the most central seam for R4 — becomes a first-class versioned API.

## The runtime model — VALIDATED against the osgi.core archetype (the key check)

The worry: "if the contract is an osgi/ bundle, does it get installed into Felix and cause a double-class
copy with the system.packages.extra export?" Answer: NO, and the archetype proves it. **`osgi.core` is a
versioned bundle JAR that is NEVER installed as a bundle** — it is on the system classpath, imported WITH
a version by the real bundles (manifests-core imports `org.osgi.resource;version="[1.0,2)"`), and exported
from the system bundle. The bench testkit does exactly this (`systemPackages(...)` /
`system.packages.extra`, `exportImportsOf`, [[osgi-runtime-r1-scr-state]] single-exporter rule).

So the contract is: **a versioned bundle at BUILD time (jar + bnd metadata + @Version), delivered FLAT at
runtime** (host classpath → `system.packages.extra`, NOT `installBundle`). The host consumes it as a
plain Java jar (Maven dep) — being "a bundle" does not stop plain-Java consumption (a bundle IS a jar).
This is the standard OSGi shared-API idiom; the proposal ALIGNS us with it. It also keeps R1's
single-exporter invariant: ONE copy of the contract Class, shared host↔bundle, no ClassCastException.

R4 impact (already a pointer in [[osgi-runtime-r4-boot-seam-state]]): the `system.packages.extra` string
must list the contract packages WITH their version once bundle-ified —
`io.nxmatic.rke2lab.manifests.contract;version="1.0"`, `.contract.node`, `.contract.profiles`,
`io.nxmatic.rke2lab.netplan.contract`. The impl bundles' versioned import then constrains against it.

## What this means for the in-flight rename slice

The rename slice (refactor/rename-bridge-to-contract, committed 8f534d5a, NOT merged) did the NAME
correctly (bridge→contract everywhere, the 4 META-INF/services FQNs, build green) — BUT it re-confirmed
the WRONG placement: the contract modules are still plain jars in host/. Merging it as-is then
re-placing to osgi/ would throw away a host→osgi git-mv. So:

- **Do NOT merge the rename as-is.** Re-scope to ONE slice that does: rename (done) + re-place
  `host/manifests-contract` → `osgi/manifests/manifests-contract` and `host/netplan-contract` →
  `osgi/netplan/...` (placement TBD — sibling of the impl, or a contract/ subdir) + bundle-ify (bnd.bnd
  with Export-Package + `package-info.java @Version`) + impl bundles import the versioned package.
- The NAME `-contract` is settled and stays. Only placement + bundle-ification are added.
- DECISIONS owed before coding (next design step):
  ** exact osgi/ location: `osgi/manifests/manifests-contract` (sibling of manifests-core) vs a grouping?
  ** starting version: `1.0.0` (semver of a published contract) vs align to `project.version` 0.1.0?
     Lean 1.0.0 — the contract is the stable published surface, decoupled from build version.
  ** do the impl bundles (manifests-core, netplan) DEPEND on the contract bundle at compile (yes) and
     does the contract bundle get installed in Felix at R4 (NO — flat via system.packages.extra).
  ** is the host Maven dep unchanged (yes — still a jar dep, just now an osgi/ GAV).

## Sequencing

This is a design/carto note on integration @4b13a39f. NEXT: take the two decisions above (location +
version) WITH the user, then the rename worktree is re-scoped (or a fresh slice supersedes it) to
rename+re-place+bundle-ify, merged as one. The bench-BSN-drift backlog item and R4 are unaffected.

See [[rename-bridge-to-contract-state]] (the in-flight slice that must be re-scoped, NOT merged as-is),
[[extract-bridge-api-state]] (where the contract modules were born as host plain jars),
[[api-extraction-tri-carto-state]] (the bridge→contract naming + the Pohl finding),
[[osgi-runtime-r4-boot-seam-state]] (system.packages.extra must carry the versioned contract packages),
[[system-space-world-universe-glossary]] (purity = nature, the placement criterion),
the atlas §"two spaces" (OSGi describes / host actualises).
