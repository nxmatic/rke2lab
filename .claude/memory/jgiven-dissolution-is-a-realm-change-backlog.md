---
name: jgiven-dissolution-is-a-realm-change-backlog
description: The osgi-aggregator-layout §5.4 "dissolve jgiven into pipeline" is NOT a layout move — it is a REALM change, deferred to its own increment. pipeline is type=seam (system-exported, FLAT); jGiven enters the framework as an INSTALLED bundle (JGivenTestkit.installFromClasspath + installBundles(WRAP_BSN)). Making the seam export com.tngtech.jgiven.* would put jGiven in two realms → LinkageError (the exact condition DUPLICATE_REALM_CLASS forbids). The layout spec self-contradicts (§5.4/§3/§6 say dissolve; §8 says "Aucune fusion de modules"). The layout increment (2026-06-30 plan) relocates jgiven AS-IS to foundation/jgiven/ and DEFERS the dissolution. See [[jgiven-domain-into-pipeline-debt]] [[osgi-aggregator-layout-spec-state]] [[document-seam-cannot-expose-jackson-jsonnode]].
metadata:
  type: project
---

## The trap (found while planning the layout, 2026-06-30)

The user asked "did we forget to integrate jgiven into pipeline?" — which surfaced that §5.4 of the
aggregator-layout spec was never executed AND that executing it as written is dangerous.

`osgi/pipeline/bnd.bnd` today exports ONLY `io.nxmatic.rke2lab.pipeline` and carries
`Provide-Capability: io.nxmatic.rke2lab.embed; type=seam` → it is a SEAM: system-exported, FLAT,
read by the host out of the framework typed (BootPipeline, manifests-core, seed-master import
`io.nxmatic.rke2lab.pipeline` flat).

`jgiven-wrap` has NO embed capability — it is installed as a normal bundle by `JGivenTestkit`
(`installFromClasspath(...)` for jGiven's whole tail + `installBundles(WRAP_BSN)`), and the `-test`
fragments (doctor-core-test, jgiven-probe-test, manifests-core-test) include `jgiven-fragment.bnd` and
dep `jgiven-testkit`/`jgiven-wrap` at TEST scope.

If `pipeline` (the seam) absorbed jgiven-wrap and exported `com.tngtech.jgiven.*`, jGiven would be
BOTH system-exported flat (via the seam) AND installed as a bundle (via the testkit) = two exporters
of the same package = two realms = LinkageError. This is exactly the jackson-JsonNode bug 2B fixed
([[document-seam-cannot-expose-jackson-jsonnode]]) and the condition the DUPLICATE_REALM_CLASS gate
detects.

## The decision (user, 2026-06-30)

The layout increment defers §5.4. It relocates `osgi/jgiven/` AS-IS (4 modules + aggregator, exports
unchanged) to `osgi/foundation/jgiven/` — near its future pipeline home, in the compile-time group —
WITHOUT fusing. §8 ("strictement le layout, aucune fusion") governs this increment over the
contradictory §5.4.

## The deferred increment (its own analysis, later)

Dissolving jgiven into pipeline must FIRST re-architect how jGiven enters the framework: if pipeline
exports jGiven flat, the testkit can no longer install it as a bundle (it would have to systemPackage
it, or jGiven stays a bundle and pipeline must NOT export it). That is a boot/testkit redesign, not a
`git mv`. Trace JGivenTestkit.felix() + jgiven-fragment.bnd + the 3 -test fragments before designing.
This is the [[jgiven-domain-into-pipeline-debt]], now with the realm constraint made explicit.

## Also fix the spec while here

`osgi-aggregator-layout-spec.adoc` is internally contradictory: §5.4/§3/§6 prescribe the dissolution,
§8 forbids module fusion. The layout increment's Task 6 adds a NOTE to §5.4 recording the deferral so
the next reader is not misled.
