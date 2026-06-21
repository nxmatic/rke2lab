package io.nxmatic.rke2lab.jgiven.testkit;

import io.nxmatic.rke2lab.osgi.testkit.FelixFrameworkExtension;

/**
 * The jGiven boot closure for the OSGi testkit, in one call. Returns a {@link
 * FelixFrameworkExtension.Builder} already carrying everything a host needs to run jGiven scenarios
 * in-container; the host adds only its OWN bundle(s) and {@code build()}s:
 *
 * <pre>{@code
 * @RegisterExtension
 * static final FelixFrameworkExtension felix =
 *     JGivenTestkit.felix().installBundles("doctor-core").build();
 * }</pre>
 *
 * <p>This is where jGiven-specific OSGi knowledge lives, deliberately OUT of the generic {@link
 * FelixFrameworkExtension} (which stays jGiven-agnostic). The closure has three measured parts,
 * each from the wrap spike (see {@code docs/architecture/osgi/jgiven-osgi-wrap-spike-report.adoc}):
 *
 * <ul>
 *   <li>{@code bootDelegation(sun.misc)} — byte-buddy's {@code ClassInjector.UsingReflection}
 *       reaches {@code Unsafe} reflectively WITHOUT importing it, so no system-export can serve it;
 *       it must be parent-loaded. One JDK-internal package, NOT a viral {@code
 *       DynamicImport-Package}.
 *   <li>{@code installFromClasspath(...)} — jGiven's whole dependency tail, every one ALREADY an
 *       OSGi bundle, installed as bundles. guava splits {@code util.concurrent.internal} into the
 *       failureaccess companion, so that ships too. Plus {@code jgiven-wrap} itself.
 *   <li>{@code systemPackages(...)} — slf4j and the junit.jupiter packages the wrap bundle imports
 *       but no scenario executes against: host concerns served flat from the system bundle.
 * </ul>
 *
 * <p>Nothing of jGiven is system-exported — the LOCAL claim made literal. The build-time half of
 * the model (the single forced fragment import) lives in the {@code jgiven-fragment.bnd} include,
 * not here.
 */
public final class JGivenTestkit {

  // The testkit locates bundles by classpath SUBSTRING; "jgiven-wrap" is a prefix of nothing else
  // under osgi/jgiven now, but anchoring on "<module>/target" keeps it unambiguous against any
  // future jgiven-wrap-* sibling (reactor -am resolves the wrap to its target/classes dir).
  public static final String WRAP_ARTIFACT = "jgiven-wrap/target";

  private JGivenTestkit() {}

  /**
   * A {@link FelixFrameworkExtension.Builder} pre-loaded with the jGiven boot closure (boot
   * delegation, the jGiven dependency bundles, {@code jgiven-wrap}, and the host slf4j/junit
   * packages). The caller adds its own host bundle(s) via {@code installBundles(...)} and {@code
   * build()}s. The {@code jgiven-wrap} bundle and its dependency jars must be on the calling test
   * module's classpath (test-scope dependencies) so the testkit can locate them.
   */
  public static FelixFrameworkExtension.Builder felix() {
    return FelixFrameworkExtension.builder()
        .bootDelegation("sun.misc")
        .systemPackages(
            "org.slf4j;version=2.0.17",
            "org.slf4j.spi;version=2.0.17",
            "org.junit.jupiter.api;version=5.11.0",
            "org.junit.jupiter.api.extension;version=5.11.0",
            "org.junit.jupiter.params.aggregator;version=5.11.0",
            "org.junit.jupiter.params.provider;version=5.11.0",
            "org.junit.jupiter.params.support;version=5.11.0")
        // jGiven's whole dependency tail — all already OSGi bundles — installed as bundles. guava
        // splits its util.concurrent.internal package into the failureaccess companion bundle, so
        // that ships too (itself a proper OSGi bundle: com.google.guava.failureaccess).
        .installFromClasspath(
            "failureaccess",
            "guava",
            "gson",
            "paranamer",
            "jansi",
            "jakarta.annotation",
            "byte-buddy")
        .installBundles(WRAP_ARTIFACT);
  }
}
