package io.nxmatic.rke2lab.scenario.testkit;

import io.nxmatic.rke2lab.osgi.runtime.framework.LaunchConfig;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;

/**
 * The jGiven boot closure for the OSGi testkit, in one call. Returns a {@link
 * OutOfContainerFrameworkExtension.Builder} already carrying everything a host needs to run jGiven
 * scenarios in-container; the host then {@code build()}s and, in the test body, installs its own
 * {@code -test} fixture fragment by what it DECLARES — never by a {@code Bundle-SymbolicName}
 * literal:
 *
 * <pre>{@code
 * @RegisterExtension
 * static final OutOfContainerFrameworkExtension felix = ScenarioTestkit.felix().build();
 *
 * // in the test: select the fixture by capability; its host comes from the fragment's Fragment-Host
 * var fixture = felix.installFixtureWithHost("(&(type=fixture)(suite=doctor)(role=core))");
 * }</pre>
 *
 * <p>This is where jGiven-specific OSGi knowledge lives, deliberately OUT of the generic {@link
 * OutOfContainerFrameworkExtension} (which stays jGiven-agnostic). The closure has three measured
 * parts, each from the wrap spike (see {@code
 * docs/architecture/osgi/jgiven-osgi-wrap-spike-report.adoc}):
 *
 * <ul>
 *   <li>{@code bootDelegation(...)} — byte-buddy's {@code ClassInjector.UsingReflection} reaches
 *       {@code Unsafe} reflectively WITHOUT importing it, so no system-export can serve it; it must
 *       be parent-loaded. One JDK-internal package, NOT a viral {@code DynamicImport-Package}. From
 *       the ONE shared source {@link LaunchConfig#SCENARIO_PLAY_BOOT_DELEGATION} the live boot also
 *       uses — so the requirement is stated once, never set here and forgotten there.
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
public final class ScenarioTestkit {

  // Located on the classpath by its Bundle-SymbolicName — the identity it declares, not a file
  // name.
  public static final String WRAP_BSN = "io.nxmatic.rke2lab.jgiven.wrap";

  private ScenarioTestkit() {}

  /**
   * A {@link OutOfContainerFrameworkExtension.Builder} pre-loaded with the jGiven boot closure
   * (boot delegation, the jGiven dependency bundles, {@code jgiven-wrap}, and the host slf4j/junit
   * packages). The caller adds its own host bundle(s) via {@code installBundles(...)} and {@code
   * build()}s. The {@code jgiven-wrap} bundle and its dependency jars must be on the calling test
   * module's classpath (test-scope dependencies) so the testkit can locate them.
   */
  public static OutOfContainerFrameworkExtension.Builder felix() {
    return OutOfContainerFrameworkExtension.builder()
        // SCR runs by default (inherited from the builder): a -bdd scion's Felix matches the live
        // boot posture, where the DS extender is present and the domain @Components activate.
        // felix.scr
        // is on every -test module's classpath via bundle-test-parent. The rare jGiven test whose
        // module lacks felix.scr (the ScenarioTestkitGuardTest in scenario-testkit) opts out with
        // .withoutScr() at its own call site.
        .bootDelegation(LaunchConfig.SCENARIO_PLAY_BOOT_DELEGATION.toArray(String[]::new))
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
            "com.google.guava.failureaccess",
            "com.google.guava",
            "com.google.gson",
            "com.thoughtworks.paranamer",
            "org.fusesource.jansi",
            "jakarta.annotation-api",
            "net.bytebuddy.byte-buddy")
        .installBundles(WRAP_BSN);
  }
}
