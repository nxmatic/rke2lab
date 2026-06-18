---
name: osgi-logging-and-cli-debt
description: "OSGi-IDIOM debt found 2026-06-18 auditing the refactored osgi/ bundles: standard OSGi services we shadow with non-OSGi mechanisms. (1) Log → logback-classic+logback.xml dragged into bundles; (2) service registry/DS → java.util.ServiceLoader + META-INF/services everywhere (NodeEnvContributor, the 3 manifests services, NetplanSynthesisService); (3) Config Admin/Metatype → Properties/getResourceAsStream. Plus a real BUG: a misnamed services file (rk2lab→rke2lab) silently breaks its ServiceLoader. To fix in DEDICATED future workspaces, NOT the exec-aggregator session in flight."
metadata:
  node_type: memory
  type: project
---

## Origin

The user saw a `logback.xml` in an OSGi bundle and asked to sweep for ALL the standard OSGi services we
missed/shadowed during the layout refactor — the [[check-osgi-standard-before-modeling]] discipline:
the spec usually provides what we hand-roll. Read-only audit on design HEAD c3cfb58c.

## The debt, by OSGi standard service

**1. Log service (`org.osgi.service.log`) — shadowed by logback.**
`osgi/netplan` (1 class) and `osgi/manifests/manifests` (5 classes) use slf4j + declare
`logback-classic` (a RUNTIME backend) as a bundle dep and ship a `logback.xml`. A bundle should depend
only on the `slf4j-api` FAÇADE (or the pure LogService); the backend + config belong to the EXECUTABLE.
Both bundles are actually core+CLI mixes (each has a `Main` + shade `-exec` jar), so logback rides the
CLI half. **Fix = the same core/cli split as netplan in exec/**: extract Main+shade+logback(+`logback.xml`)
+ CLI-only deps (logback-classic; netplan also jackson-dataformat-yaml) into `exec/<name>-cli`; the core
keeps slf4j-api only. netplan's split is already in [[exec-aggregator-state]] (its core MUST lose
logback-classic, not just relocate CLI classes); manifests-cli is a separate later step (ties to the
foreseen manifests model/synthesis re-découpe, [[step2-decomposition-state]]). slf4j-api itself = an
acceptable façade, NOT flagged. Going all the way to OSGi LogService is an optional further step.

**2. Service registry + Declarative Services — shadowed by `java.util.ServiceLoader`.** THE most
widespread shadow. `ServiceLoader`/`META-INF/services` is used for: `NodeEnvContributor`,
`ManifestSynthesisService`, `ManifestUpdateGate`, `ManifestExplodeService` (manifests), and
`NetplanSynthesisService` (netplan). Already noted conceptually in [[docrepo-dag-state]]: "ServiceLoader
is the STATIC poor cousin of the service registry; the living trajectory goes through the registry +
DS (`@Component`/`@Reference`), not ServiceLoader." So the idiomatic target is DS components, but this is
a BIG migration (gated on the framework-move / runtime decision — no OSGi runtime in prod today,
[[step2-decomposition-state]] corrected fact 3). Capture now, sequence later; do NOT convert blindly.

**3. Config Admin (`cm`) / Metatype — partially shadowed by `Properties`/`getResourceAsStream`.** 7
`*Assets`/inclusion classes in manifests read bundled resources via `getResourceAsStream`/`.properties`.
Some of this is legitimate (bundling static asset templates), some is config that Metatype/Config Admin
would model. Needs per-class triage before calling it debt — NOT all of it is wrong. Lower priority.

**Not concerned:** EventAdmin (zero listeners found), Coordinator, Prefs — unused, no shadow.
**Not debt:** jackson-databind in manifests (compile-time data lib, legitimate).

## ★ Real BUG found (fix it, don't just note it)

`osgi/netplan/src/main/resources/META-INF/services/io.nxmatic.rk2lab.netplan.api.NetplanSynthesisService`
— the filename says **`rk2lab`** (missing the `e`), but the FQN is `io.nxmatic.rke2lab.netplan.api.
NetplanSynthesisService`. `ServiceLoader.load(NetplanSynthesisService.class)` looks up the correctly-
spelled path → **never finds this file** → the provider is silently not registered (or a second correct
file exists elsewhere — verify). Classic silent single-source-of-truth mismatch. **In netplan's
perimeter → fix it as part of the exec-aggregator step (or a dedicated netplan pass), NOT from a session
that would collide with exec-aggregator-in-flight.** Verify whether prod currently relies on this
provider at all (it may have been dead since the typo).

## Sequencing / discipline
- The exec-aggregator session is IN FLIGHT — do NOT touch it or its memory (user 2026-06-18: "on la
  laisse terminer, on s'occupe de la dette dans un autre workspace").
- Logback core/cli purge + the rk2lab typo travel with the exec/ netplan work or a dedicated netplan
  pass; manifests-cli + the ServiceLoader→DS migration are their own later chantiers.
- ServiceLoader→DS is gated on the runtime/framework-move decision — don't rush it.

See [[exec-aggregator-state]], [[osgi-leaves-state]], [[docrepo-dag-state]] (ServiceLoader=poor cousin),
[[check-osgi-standard-before-modeling]] (the meta-discipline), [[step2-decomposition-state]] (no OSGi
runtime in prod yet → DS migration is a real lift).
