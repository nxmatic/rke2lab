package io.nxmatic.rke2lab.manifests.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.nxmatic.rke2lab.osgi.testkit.FelixFrameworkExtension;
import io.nxmatic.rke2lab.osgi.testkit.OsgiSpike;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.runtime.ServiceComponentRuntime;
import org.osgi.service.component.runtime.dto.ComponentConfigurationDTO;
import org.osgi.service.component.runtime.dto.ComponentDescriptionDTO;
import org.osgi.service.component.runtime.dto.SatisfiedReferenceDTO;

/**
 * R3 — the intra-bundle consumption proof, the cardinality-MULTIPLE twin of R1's 1..1 bind. The
 * real {@code manifests-core} bundle is installed on an embedded Felix with felix.scr; SCR
 * activates {@link NodeEnvContributorRegistry} ({@code @Component}) and binds its
 * {@code @Reference(MULTIPLE)} {@code contributors} field to every published {@link
 * NodeEnvContributor}. The proof reads SCR's own {@link ServiceComponentRuntime} DTOs — so it
 * asserts the WIRING (the registry component is satisfied, its {@code contributors} reference bound
 * exactly the SIX domain contributors) without casting to the registry type: that package is
 * exported BY the bundle, and re-exporting it from the system bundle would split the class (the R1
 * single-exporter rule).
 *
 * <p>The anti-cheat is the COUNT. A {@code 0..n} reference is satisfied even with zero bindings, so
 * "the registry activated" proves nothing on its own; "six contributors bound" proves SCR
 * discovered and injected the real domain set. The framework-less callers still use {@code
 * forServiceLoader()} (dual-path until R5); this is the only place the SCR path is exercised.
 *
 * <p>Because manifests-core is a heavy bundle, every package it imports must be exported from the
 * system bundle for it to RESOLVE — that long {@code systemPackages(...)} list is itself a finding:
 * the contributors (trivial DS components) share a bundle with cdk8s synthesis (heavy), a candidate
 * for the later specialist-units split. Activation stays cheap: SCR only calls the contributors'
 * no-arg constructors and injects the list — no cdk8s/jackson code runs.
 */
@OsgiSpike
class NodeEnvContributorRegistryScrSpikeTest {

  private static final String REGISTRY_COMPONENT =
      "io.nxmatic.rke2lab.manifests.node.NodeEnvContributorRegistry";

  // The six @Component NodeEnvContributor impls declared in R2 (geste A).
  private static final int EXPECTED_CONTRIBUTORS = 6;

  // manifests-core resolves only if every package it imports is wired. Rather than transcribe that
  // list, export exactly what the bundle's own bnd-computed Import-Package header declares — always
  // in sync, fail-fast. We prove SCR wiring, not the sibling bundles' own resolution, so mirroring
  // imports onto the system bundle is enough; no sibling bundle is installed.
  @RegisterExtension
  static final FelixFrameworkExtension felix =
      FelixFrameworkExtension.builder()
          .withScr()
          .exportImportsOf("manifests-core")
          .installBundles("manifests-core")
          .build();

  @Test
  void scrBindsAllSixContributorsIntoTheRegistry() throws Exception {
    ServiceComponentRuntime scr = felix.awaitService(ServiceComponentRuntime.class, 5000);
    assertNotNull(scr, "felix.scr published its ServiceComponentRuntime");

    BundleContext context = felix.context();

    // Every R2 @Component(service=NodeEnvContributor.class) is published by SCR — the population
    // the
    // registry's MULTIPLE reference draws from. getAllServiceReferences, not getServiceReference:
    // the latter hides services whose class the (system) caller bundle cannot load, which these
    // are.
    ServiceReference<?>[] contributorServices =
        context.getAllServiceReferences(NodeEnvContributor.class.getName(), null);
    assertNotNull(contributorServices, "SCR published the NodeEnvContributor services");
    assertEquals(
        EXPECTED_CONTRIBUTORS,
        contributorServices.length,
        "SCR published every domain NodeEnvContributor");

    // The registry is a DELAYED component: SCR keeps it satisfied but does not activate (so does
    // not
    // inject @Reference) until its service is requested. getService forces that activation — the
    // returned instance is not cast (its class is not loadable here), only used to trigger binding.
    ServiceReference<?>[] registryReferences =
        context.getAllServiceReferences(REGISTRY_COMPONENT, null);
    assertNotNull(registryReferences, "SCR registered the NodeEnvContributorRegistry service");
    assertEquals(1, registryReferences.length, "exactly one registry service");
    assertNotNull(
        context.getService(registryReferences[0]),
        "the registry component activates on first request");

    ComponentDescriptionDTO description =
        scr.getComponentDescriptionDTO(felix.bundle("manifests-core"), REGISTRY_COMPONENT);
    assertNotNull(description, "bnd generated a DS descriptor for the registry component");

    ComponentConfigurationDTO configuration =
        scr.getComponentConfigurationDTOs(description).stream()
            .findFirst()
            .orElseThrow(() -> new AssertionError("registry component has no SCR configuration"));
    assertEquals(
        ComponentConfigurationDTO.ACTIVE,
        configuration.state,
        "the requested registry component is ACTIVE, so its @Reference is bound");

    SatisfiedReferenceDTO contributors =
        Arrays.stream(configuration.satisfiedReferences)
            .filter(reference -> "contributors".equals(reference.name))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("no satisfied 'contributors' reference on registry"));

    assertEquals(
        EXPECTED_CONTRIBUTORS,
        contributors.boundServices.length,
        "SCR injected every domain NodeEnvContributor into the MULTIPLE reference");
  }
}
