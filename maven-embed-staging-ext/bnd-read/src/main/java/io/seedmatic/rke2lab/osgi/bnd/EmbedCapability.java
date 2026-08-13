package io.seedmatic.rke2lab.osgi.bnd;

import org.osgi.framework.Filter;

/**
 * A typed view of the embed {@link Clause} a bundle self-declares in its {@code Provide-Capability}
 * — the single place that clause is read, shared by the build-time extension and the runtime boot
 * model. A bundle marks both that it is embeddable AND its nature in one declaration:
 *
 * <pre>{@code
 * Provide-Capability: io.seedmatic.rke2lab.embed; type=model;   model=manifests
 * Provide-Capability: io.seedmatic.rke2lab.embed; type=edge;    edge=ssh-to-age
 * Provide-Capability: io.seedmatic.rke2lab.embed; type=fixture; suite=scr; role=provider
 * }</pre>
 *
 * <p>A consumer SELECTS bundles by an LDAP {@link Filter} over the clause attributes, so it
 * installs exactly what it needs by what the bundle DECLARES, never by a file name it keeps in
 * sync. The {@code type} discriminator carries the seam law ({@link #isDomain()} vs {@link
 * #isSeam()}): both the runtime export guard and the build-time staging classification turn on it,
 * so it lives once.
 */
public record EmbedCapability(Clause clause) {

  /**
   * The {@code Provide-Capability} namespace a bundle self-declares (in its {@code bnd.bnd}) to
   * mark itself embeddable — the single source of truth for "which jars are ours to embed".
   * Boot-stack jars (pax / felix.scr / felix.resolver) do not carry it, so they are excluded
   * objectively with no parallel hand-list. A namespace with no matching {@code
   * Require-Capability}, so the OSGi resolver ignores it — it never affects package wiring.
   */
  public static final String EMBED_CAPABILITY_NAMESPACE = "io.seedmatic.rke2lab.embed";

  /** The {@code type} attribute key — the carrier's boot face (its role at install time). */
  public static final String TYPE = "type";

  /**
   * A domain bundle: pure model logic, installed as a bundle, its exports never system-exported.
   */
  public static final String TYPE_MODEL = "model";

  /** An external edge: an adapter contacting one target, installed as a bundle like a model. */
  public static final String TYPE_EDGE = "edge";

  /**
   * A domain's CONTRACT bundle: its data vocabulary (records / enums / sealed ADT) AND the service
   * interfaces a consumer resolves from the registry — but NO concrete class and NO
   * {@code @Component}. Installed bundle-side like a model (its exports are its own, never
   * system-exported) and wired bundle-to-bundle, NOT a seam (a domain contract is consumed inside
   * OSGi, never host-side — the host speaks only to the broker). It carries no {@code @Component},
   * so it does NOT {@code Require} the SCR extender: a consumer that needs only the interface
   * imports THIS, not the domain's {@code -core} (whose live {@code @Component} impl pulls SCR into
   * resolution). A test mocks the interface via {@code registerService} — no {@code @Component}
   * needed to mock. The staging extension's {@link
   * io.seedmatic.rke2lab.domain.annotations.StagingGate#CONTRACT_PURITY contract-purity} guard
   * fails the build if a {@code type=contract} bundle exports a concrete class. Supersedes the
   * {@code type=seam} {@code -port} for a domain whose contract does not cross to the host.
   */
  public static final String TYPE_CONTRACT = "contract";

  /**
   * The seam (a {@code -port}): the membrane the flat host shares with the framework. NOT installed
   * as a bundle — system-exported so the host's JCL copy and the bundles' copy are ONE class. The
   * one {@code type} that legitimately appears in {@code system.packages.extra}. See {@code
   * docs/architecture/patterns/port-edge-domain-ownership.adoc} § "The boot face".
   */
  public static final String TYPE_SEAM = "seam";

  /** A test-only fixture (with {@code suite}/{@code role}); installed only by the test harness. */
  public static final String TYPE_FIXTURE = "fixture";

  /**
   * OUR OWN DUAL-REALM carrier: logic we wrote that depends on a realm-isolated third-party library
   * (jackson) and is needed in BOTH realms. It follows jackson's own treatment — staged as a bundle
   * (installed in-framework, binding the OSGi copy of its dependency) AND kept flat in the host
   * uber-jar (binding the host copy). Unlike a {@code model}/{@code edge}/{@code record} (bundle
   * ONLY, excluded from flat), a {@code dual-realm} is BOTH realms at once. Its exported package
   * legitimately lives in two realms — exempt from {@code DUPLICATE_REALM_CLASS} like jackson,
   * because it is NOT a seam surface (no type of it crosses the String-only seam; each realm holds
   * its own copy bound to its own jackson). A {@code dual-realm} is only justified when BOTH realms
   * actually consume it — a carrier referenced from only one realm is wearing the wrong face
   * (demote it to a plain bundle, or keep it host-flat). Exemplar: {@code seed-broker-codec} (the
   * {@code SeedCodec}, one source, two realm-bound copies).
   */
  public static final String TYPE_DUAL_REALM = "dual-realm";

  /**
   * A neutral runtime MECHANISM installed in-framework: a {@code @Component} that is neither a
   * domain model nor an edge — it belongs to no domain and serves them all. Installed AND activated
   * bundle-side like a model (its exports, if any, are its own and never system-exported), but its
   * nature is the framework's own machinery, not a domain's. Exemplar: {@code seed-broker-runtime}
   * (the {@code DefaultSeedBroker} that collects every {@code SeedHandler} by DS and dispatches by
   * coordinate) — the dual-axis integrator that serves all domains and both worlds, so it lives in
   * {@code foundation}, not under a domain.
   */
  public static final String TYPE_RUNTIME = "runtime";

  /**
   * The embed capability declared in {@code provideCapability}, or {@code null} if the header does
   * not name {@link #EMBED_CAPABILITY_NAMESPACE}. Reads only the embed clause; other capability
   * namespaces in the same header are ignored.
   */
  public static EmbedCapability of(OsgiHeader provideCapability) {
    final Clause clause = provideCapability.named(EMBED_CAPABILITY_NAMESPACE);
    return clause == null ? null : new EmbedCapability(clause);
  }

  /** The carrier's {@code type} ({@link #TYPE_MODEL} / {@link #TYPE_EDGE} / …), or {@code null}. */
  public String type() {
    return clause.attributes().get(TYPE);
  }

  /**
   * Whether this carrier loads on the BUNDLE side of the seam — a {@code model}, an {@code edge},
   * or a {@code contract}. Its exported packages are owned by its own bundle classloader and must
   * NEVER reach {@code system.packages.extra} (a second exporter there would split the class). The
   * discriminator the leak guard turns on.
   */
  public boolean isDomain() {
    return TYPE_MODEL.equals(type()) || TYPE_EDGE.equals(type()) || TYPE_CONTRACT.equals(type());
  }

  /**
   * Whether this carrier is a domain CONTRACT bundle — data vocabulary (records / enums / sealed
   * ADT) + service interfaces, no concrete class, no {@code @Component}. Subject to the build-time
   * contract-purity guard, and installed bundle-side (never system-exported) like a model.
   */
  public boolean isContract() {
    return TYPE_CONTRACT.equals(type());
  }

  /** Whether this carrier is the seam — system-exported for the flat host, never installed. */
  public boolean isSeam() {
    return TYPE_SEAM.equals(type());
  }

  /**
   * Whether this carrier is our own DUAL-REALM carrier — staged as a bundle AND kept flat in the
   * host (jackson's treatment, for our code). The staging closure turns on this to stage it yet
   * keep it in the flat uber-jar; {@code DUPLICATE_REALM_CLASS} exempts its exported package (not a
   * seam surface, each realm holds its own copy).
   */
  public boolean isDualRealm() {
    return TYPE_DUAL_REALM.equals(type());
  }

  /**
   * Whether this carrier is a neutral runtime MECHANISM — installed AND activated in-framework like
   * a domain (staged, resolves at the bundle start level), but belonging to no domain. It shares a
   * {@code model}'s INSTALL/ACTIVATION regime, NOT its leak-guard: a {@code runtime} bundle is a
   * pure {@code @Component} that exports nothing (its contract is the seam it serves), so it has no
   * bundle-only exports the flat host could leak. Exemplar: {@code seed-broker-runtime}.
   */
  public boolean isRuntime() {
    return TYPE_RUNTIME.equals(type());
  }

  /** Whether {@code filter} (an LDAP filter over the embed attributes) selects this capability. */
  public boolean matches(Filter filter) {
    return filter.matches(clause.attributes());
  }
}
