package io.seedmatic.rke2lab.osgi.bnd;

/**
 * The third-party boot-stack jars every embedded-OSGi deployment installs — the DS-API trio at the
 * passive layer, Pax Logging at the logging layer, felix.scr + felix.resolver at the
 * framework-runtime layer. A typed registry, NOT a capability scan: these jars are third-party and
 * carry no {@link EmbedCapability#EMBED_CAPABILITY_NAMESPACE embed capability} ({@code
 * pax-logging-api} declares no {@code Provide-Capability} at all), so they are not ours to mark —
 * the honest frontier is "ours → discovered by capability, third-party → this enum". They must be
 * NAMED, not derived: nothing of ours imports felix.scr (DS wires components by reflection, not
 * Import-Package), so no Import-Package closure reaches it; and felix.scr declares the DS-API trio
 * {@code <provided>} while the whole stack is {@code optional} in {@code osgi/runtime} (off the
 * host JCL), so the trio reaches no resolved graph a closure could fan out to either. Read once on
 * both sides: the runtime ({@code FrameworkLauncher}) maps {@link Layer} to its own start-level
 * numbers and locates each jar by its {@link #symbolicName()} (on the classpath, or among the
 * staged bundles); the build-time staging extension RE-RESOLVES each by {@link #groupId()}:{@link
 * #artifactId()} (managed version) to seed the stage-vs-flat closure — never a file name.
 * Declaration order is install order; within a layer the framework keeps it, so pax-api precedes
 * its backend.
 */
public enum BootStackJar {
  // The DS-API trio felix.scr imports as MANDATORY: passive spec jars (no activator), they must
  // only
  // RESOLVE before felix.scr activates. felix.scr declares them <provided>, so they never transit
  // to
  // an exec module — and the boot stack is optional in osgi/runtime (off the host JCL), so they
  // reach
  // no resolved graph the StagingClosure could derive them from. Named here so the build stages
  // them
  // and the runtime locates them, like the rest of the stack — single source, no transitive luck.
  OSGI_SERVICE_COMPONENT(
      "org.osgi", "org.osgi.service.component", "org.osgi.service.component", Layer.PASSIVE),
  OSGI_UTIL_PROMISE("org.osgi", "org.osgi.util.promise", "org.osgi.util.promise", Layer.PASSIVE),
  OSGI_UTIL_FUNCTION("org.osgi", "org.osgi.util.function", "org.osgi.util.function", Layer.PASSIVE),
  PAX_LOGGING_API(
      "org.ops4j.pax.logging",
      "pax-logging-api",
      "org.ops4j.pax.logging.pax-logging-api",
      Layer.LOGGING),
  PAX_LOGGING_LOGBACK(
      "org.ops4j.pax.logging",
      "pax-logging-logback",
      "org.ops4j.pax.logging.pax-logging-logback",
      Layer.LOGGING),
  FELIX_SCR(
      "org.apache.felix", "org.apache.felix.scr", "org.apache.felix.scr", Layer.FRAMEWORK_RUNTIME),
  FELIX_RESOLVER(
      "org.apache.felix",
      "org.apache.felix.resolver",
      "org.apache.felix.resolver",
      Layer.FRAMEWORK_RUNTIME);

  /**
   * The boot layer a jar belongs to — its ROLE, not its numeric start level. Logging comes before
   * the framework runtime so the LogService is live before scr/resolver activate. The executor
   * translates this to its own start-level integers; the model only states the ordering intent.
   */
  public enum Layer {
    PASSIVE,
    LOGGING,
    FRAMEWORK_RUNTIME
  }

  private final String groupId;
  private final String artifactId;
  private final String symbolicName;
  private final Layer layer;

  BootStackJar(String groupId, String artifactId, String symbolicName, Layer layer) {
    this.groupId = groupId;
    this.artifactId = artifactId;
    this.symbolicName = symbolicName;
    this.layer = layer;
  }

  /**
   * The Maven groupId — with {@link #artifactId()} the full coordinate the build-time staging
   * extension RE-RESOLVES the jar by, rather than fishing it out of the resolved dependency graph:
   * the boot stack is {@code optional} in {@code osgi/runtime} (kept off the host JCL), so it never
   * reaches an exec module's transitive graph. The version is supplied by the managed BOM, so the
   * coordinate is enough to locate and stage the jar.
   */
  public String groupId() {
    return groupId;
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
