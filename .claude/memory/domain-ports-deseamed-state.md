---
name: domain-ports-deseamed-state
description: "The de-seam sweep (2026-07-11): all 6 domain -port modules (auth/cluster/systemd/incus/netplan/manifests) fused into -contract (type=seam→type=contract, system-export dropped, installed bundle-to-bundle). Doctor was already done (doctor-contract, the template). OSGi side FULLY GREEN. Only TWO seams remain in osgi/: pipeline (shared engine, next to de-seam via BDD-scenarios migration) and seed-broker-port (the one true host↔OSGi membrane, stays a seam). host/exec Java intentionally broken (deferred to the scenario-rewrite-from-specs chantier)."
metadata:
  type: project
---

**What shipped (uncommitted working tree, 2026-07-11, OSGi side green).** Replicated the doctor
`-contract` template across every domain `-port`: module `X-port`→`X-contract`, package root
`io.nxmatic.rke2lab.X.port`→`.contract`, bnd `type=seam`→`type=contract` (⇒ installed bundle, boot's
`deriveSystemExports` no longer system-exports it — auto-derived, no allow-list), dependents recabled
(poms + imports).

- **auth** — auth-contract (`AuthTokenContact`/`AuthTokenSource`); rewired auth-edge. Template-setter.
- **cluster** — cluster-contract; rewired cluster-core/-edge; **cluster-bdd retrofitted onto the bbox
  in-container scion** (new `cluster-bdd-test` fragment + `@OsgiWorld` proxy `ClusterBddInContainerTest`,
  old out-of-container `ClusterReadinessScenarioInContainerTest` DELETED).
- **systemd** — systemd-contract; rewired systemd-core/dbus-edge + doctor-core + manifests-core(-test);
  **systemd-bdd retrofitted onto the scion** (systemd-bdd-test fragment + proxy).
- **incus** — incus-contract; rewired incus-edge (no scion, no systemPackages).
- **netplan** — netplan-contract, the SPECIAL case: BOTH packages `netplan.port` + `netplan.api` FUSED
  into one `netplan.contract` package (user's call). rewired netplan-core/manifests/bbox.
- **manifests** — manifests-contract, THREE packages KEPT distinct (user's call, NOT merged):
  `manifests.contract` + `.contract.node` + `.contract.profiles`. rewired manifests-core(-test),
  ssh-to-age-edge, manifests-cli.

**The two seams that REMAIN in osgi/ (verified `grep type=seam osgi/`):**
- `pipeline` (`io.nxmatic.rke2lab.pipeline`, foundation/pipeline/pipeline-port) — NOT a domain port: it
  is the shared fluent `Topic`/grammar ENGINE, consumed on BOTH sides (host seed pipeline AND two OSGi
  bundles: `framework-launcher`'s `FrameworkLaunchPipeline` — the boot IS a `Topic.Execution` — and
  `manifests-core`'s whole systemd-synthesis stage tree). PROVEN by experiment: removing all 4
  `pipeline-port` maven deps broke exactly those two OSGi bundles → reverted. It de-seams only when the
  fluent grammar dissolves in the BDD-scenarios migration (all pipelines onto the jGiven-launcher engine).
- `seed-broker-port` — the ONE true host↔OSGi membrane (RunGate + SeedEnvelope). Stays a seam BY DESIGN
  (the host keeps it; everything crosses through the broker).

**Host/exec: poms trimmed to seed-broker ONLY (user: "on garde juste le seed port", "host parle QUE
seed").** Removed from `exec/seed-master` (all 5 domain ports + doctor-contract/-core + bbox-core +
auth-edge + doctor-*-test + pipeline-port), `exec/netplan-cli` (netplan), `exec/manifests-cli`
(manifests still referenced — its boot test breaks), `host/pulumi/pulumi-edge`(-testkit) (doctor-contract).
Kept: seed-broker-port/-codec/-runtime + domain-annotations. **The host Java that still names domain
types does NOT compile — INTENTIONAL, deferred** to the scenario-rewrite-from-specs chantier (user:
"on s'aligne sur la vision scenarios des specs juste apres dans les modules OSGi").

**The retrofit pattern (cluster/systemd scions), from [[mock-service-substitution-pattern]]:** an
out-of-container `-bdd` test that system-exported the port to cross a host-registered mock DIES when the
port de-seams. Replace with the bbox 3-piece in-container scion: `{domain}-bdd-test` FRAGMENT (passenger
registers mocks via `FrameworkUtil.getBundle(...).registerService` — same loader, no seam) + `@OsgiWorld`
proxy whose `.systemPackages(...)` lists ONLY `seed.broker.port` (everything else pulled by
`installImportClosureOf`, wired bundle-to-bundle in-container). User's key insight: "in-container, les
wirings résolvent" — no domain systemPackages needed.

**GOTCHAS (each cost a build):**
- The closure walk `installImportClosureOf` SKIPS seams but PULLS type=contract bundles. When ONLY a
  `-test` fragment (not its host) imports a de-seamed package, you must add the FRAGMENT to the closure
  seed: `installImportClosureOf(host, fixture.fragment())` — else the host resolves without the fragment
  attaching (silent: `resolve()` true, then `ClassNotFoundException` on the runner). Hit in
  `DoctorCoreInContainerTest` (its fragment imports systemd.contract).
- A closure-based `-test` module must DECLARE the de-seamed bundle as a `provided` maven dep so the jar
  is on the proxy classpath for `installImportClosureOf` to find it. Added `systemd-contract` to
  `doctor-core-test` pom.
- Cross-domain consumer proxies that hand-listed a now-de-seamed package in `.systemPackages` must DROP
  it (else double-export = split): fixed doctor-core-test, doctor-contract-test, manifests-core-test
  (systemd), bbox-bdd-test (netplan+doctor stale refs), cluster-bdd's own test.
- The `nxmatic` profile writes to `target~nxmatic`; the build cache replays STALE variants. ALWAYS
  `find . -name 'target~nxmatic' -type d -prune -exec rm -rf {} +` before a `-Pall-worlds,nxmatic` run,
  or you get phantom failures against deleted files (a deleted test recompiled against the old package).
- The dbus-systemd-edge boot test + manifests-cli/netplan-cli boot tests are the CLASSIC boot-seam shape
  (host `awaitService` observes the service TYPED, installs only the edge/core, no closure). They rely on
  the system-export as sole provider. dbus-edge still passes (installs just the edge); the CLI boot tests
  break (host reads manifests/netplan typed) — deferred with the rest of exec/host.

**NEXT:** commit the OSGi-green state, then align the OSGi modules on the scenarios vision from the specs
(the user thought this was already done). THEN the host/exec Java rewrite onto the broker. See
[[mock-service-substitution-pattern]] [[seed-broker-host-adaptation]] [[world-gateway-frontier-discipline]].
