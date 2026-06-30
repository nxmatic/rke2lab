---
name: nesting-our-own-flat-module-per-realm
description: Pattern (proven 2026-06-30, world-gateway 2D, commits 3183a79e+e690a72a) — to share ONE source of jackson-using logic across the host/OSGi realms WITHOUT it crossing the String-only seam, make it a plain flat jar module and load it per realm: shaded flat into the host, nested into a domain bundle's Bundle-ClassPath (-includeresource;lib:=true). First time we nest one of OUR OWN modules, not a third-party jar. No staging-rule change needed.
metadata:
  type: reference
---

## The problem it solves

You have logic that BOTH realms need (host-flat + OSGi-bundle) and that depends on a
realm-isolated library (jackson, since commit `ae46278b` each realm loads its own copy). You want
ONE source, but the logic must NOT cross the world-gateway seam (the seam is `type=seam`,
String-only — a type crossing it reopens what 2C closed, and jackson on the seam surface breaks
REALM_BOUNDARY/DUPLICATE_REALM_CLASS). So you need ONE source compiled into TWO runtime copies, each
bound to its realm's jackson.

## The pattern (exemplar: `gateway-document-codec`, `DocumentCodec`)

1. **A plain flat jar module** in `osgi/foundation/` (builds before host/exec; foundation→host
   visibility already proven by `world-gateway`). The module has NO `Bundle-SymbolicName`, NO embed
   capability — a plain jar. CRITICAL: verify `unzip -p target/<m>.jar META-INF/MANIFEST.MF | grep
   Bundle-SymbolicName` returns NOTHING. If the bundle-parent forces a BSN, inherit from a plain
   parent instead. A BSN would let `StagingClosure.isRealmLibrary` (which needs `b.isBundle()`) stage
   it as an AUTONOMOUS bundle — not what we want.
2. **Host realm**: the exec module (seed-master) depends on it normally → shaded FLAT into the
   uber-jar, binding the host's flat jackson. No shade-config change (it is never staged, so never in
   the shade-exclude set).
3. **OSGi realm**: a domain bundle (doctor-core) depends on it AND nests it in `bnd.bnd`:
   `-includeresource: <module>-*.jar;lib:=true` → its classes ride that bundle's Bundle-ClassPath,
   binding the bundle's jackson. (The glob is the ARTIFACT id — rename the artifact ⇒ update the
   glob.)

## Why it is safe (verified, gates 0/0)

- The nested package is NOT exported by the host bundle (private to its Bundle-ClassPath, like
  cdk8s/jsii in `manifests-cdk8s`). So no OSGi split-package: only one bundle nests it, and it is
  invisible to the resolver. Keep it that way — if a 2nd OSGi consumer ever needs it, route through a
  single exporter, don't let two bundles nest-and-export it.
- `DUPLICATE_REALM_CLASS` fires on `flat ∧ seamSurface`. The codec package is flat∧nested but NOT a
  seam package → exempt. `REALM_BOUNDARY` 0/0 (no type crosses).
- Verify dual-loading: the class is FLAT in the exec-jar AND the `<module>-*.jar` is inside the
  domain bundle's staged jar (`META-INF/bundles/<domain>.jar`), AND `META-INF/bundles/<module>.jar`
  is ABSENT (not an autonomous bundle).

## Naming lesson (from the same session)

Name the module/package for what it serves, specific enough to avoid a future split. The codec
encodes the gateway's `Document`, so: artifact `gateway-document-codec`, package
`io.nxmatic.rke2lab.world.gateway.codec` — a SIBLING of the seam's `…world.gateway.port`, never
inside the seam. Tempting-but-WRONG: putting shared logic INTO the `world-gateway` seam because "it's
shared" — the seam shares DATA in one copy; this shares LOGIC+jackson in two isolated copies. Opposite
contracts. See [[realm-library-isolation-state]] [[world-gateway-2c-complete-2d-designed-state]]
[[cdk8s-carrier-flat-jar-pattern]].
