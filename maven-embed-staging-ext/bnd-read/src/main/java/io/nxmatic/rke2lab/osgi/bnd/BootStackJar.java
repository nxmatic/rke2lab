package io.nxmatic.rke2lab.osgi.bnd;

/**
 * The third-party boot-stack jars every embedded-OSGi deployment installs — Pax Logging at the
 * logging layer, felix.scr + felix.resolver at the framework-runtime layer. A typed registry, NOT a
 * capability scan: these jars are third-party and carry no {@link
 * EmbedCapability#EMBED_CAPABILITY_NAMESPACE embed capability} ({@code pax-logging-api} declares no
 * {@code Provide-Capability} at all), so they are not ours to mark — the honest frontier is "ours →
 * discovered by capability, third-party → this enum". They are the closure's seed by NATURE, not by
 * wiring: nothing of ours imports felix.scr (DS wires components by reflection, not
 * Import-Package), so no Import-Package closure ever reaches it — it must be named. Read once on
 * both sides: the runtime ({@code OsgiRuntime}) maps {@link Layer} to its own start-level numbers
 * and locates each jar by its {@link #symbolicName()} (on the classpath, or among the staged
 * bundles); the build-time staging extension seeds the stage-vs-flat closure from the same registry
 * — never a file name. Declaration order is install order; within a layer the framework keeps it,
 * so pax-api precedes its backend.
 */
public enum BootStackJar {
  PAX_LOGGING_API("pax-logging-api", "org.ops4j.pax.logging.pax-logging-api", Layer.LOGGING),
  PAX_LOGGING_LOGBACK(
      "pax-logging-logback", "org.ops4j.pax.logging.pax-logging-logback", Layer.LOGGING),
  FELIX_SCR("org.apache.felix.scr", "org.apache.felix.scr", Layer.FRAMEWORK_RUNTIME),
  FELIX_RESOLVER("org.apache.felix.resolver", "org.apache.felix.resolver", Layer.FRAMEWORK_RUNTIME);

  /**
   * The boot layer a jar belongs to — its ROLE, not its numeric start level. Logging comes before
   * the framework runtime so the LogService is live before scr/resolver activate. The executor
   * translates this to its own start-level integers; the model only states the ordering intent.
   */
  public enum Layer {
    LOGGING,
    FRAMEWORK_RUNTIME
  }

  private final String artifactId;
  private final String symbolicName;
  private final Layer layer;

  BootStackJar(String artifactId, String symbolicName, Layer layer) {
    this.artifactId = artifactId;
    this.symbolicName = symbolicName;
    this.layer = layer;
  }

  /**
   * The Maven artifactId — the pom-side identity the {@code stage-embedded-bundles} {@code
   * artifactItem} copies from. Also how the build-time staging extension names the jar in the
   * derived shade-exclude / staging lists, so this registry is the single source tying the runtime
   * boot model to the staging pom. Not used to FIND the jar at boot (that is {@link
   * #symbolicName()}).
   */
  public String artifactId() {
    return artifactId;
  }

  /**
   * The {@code Bundle-SymbolicName} — how the jar is located, on the classpath AND among the staged
   * bundles, by the identity it declares rather than a file name we would have to keep in sync (the
   * staged name is ours to choose, so it is not a reliable key). Differs from the artifactId for
   * pax ({@code org.ops4j.pax.logging.pax-logging-api}).
   */
  public String symbolicName() {
    return symbolicName;
  }

  /** The boot layer (logging before framework runtime); the executor maps it to a start level. */
  public Layer layer() {
    return layer;
  }
}
