package io.nxmatic.rke2lab.osgibench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.nxmatic.rke2lab.junit.testkit.OsgiWorld;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import io.nxmatic.rke2lab.osgibench.fragment.ContributedService;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;
import org.osgi.service.component.runtime.ServiceComponentRuntime;
import org.osgi.service.component.runtime.dto.ComponentConfigurationDTO;

/**
 * The load-bearing proof of the fragment-contribution mediation model: SCR activates a
 * {@code @Component} that lives in a FRAGMENT, not in a started bundle. The fragment has no
 * lifecycle — it attaches to its host as extra resources (the component class + {@code
 * OSGI-INF/*.xml} + the {@code Service-Component} header). We install the fragment with its host
 * (neither started), resolve the host (attaching the fragment, OSGi Core §3.14), start ONLY the
 * host, and assert the fragment's {@link ContributedService} reached the registry. If it did, SCR
 * scanned the host's fragments (DS 112.4.1) and activated the contributed component in the host's
 * context — exactly what the cluster contribution (a fragment of doctor-core carrying a Specialist
 * + a Mediator) will rely on.
 *
 * <p>No existing {@code -test} fragment carries a {@code @Component}, so this mechanism was
 * unproven until here.
 */
@OsgiWorld
class FragmentContributedComponentTest {

  private static final String FRAGMENT_FILTER =
      "(&(type=fixture)(suite=fragment)(role=contribution))";

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      OutOfContainerFrameworkExtension.builder()
          .withScr()
          // ONLY the SCR runtime package is system-exported, so the DTO query below reads the same
          // class SCR publishes. The service type (io.nxmatic.rke2lab.osgibench.fragment) is NOT
          // system-exported: the host already exports it, and a second copy would split the class —
          // so we observe the published service BY NAME, never by a test-loaded Class.
          .systemPackages(
              "org.osgi.service.component.runtime;version=1.5.0",
              "org.osgi.service.component.runtime.dto;version=1.5.0")
          .build();

  @Test
  void scrActivatesAComponentContributedByAFragment() throws Exception {
    // Install the fragment + its host (neither started), then resolve the host so the fragment's
    // resources — including its Service-Component — merge in.
    final Bundle host = felix.installFixtureWithHost(FRAGMENT_FILTER).host();
    if (!felix.resolve(List.of(host))) {
      fail("the fragment-host (with the contribution fragment attached) must resolve");
    }
    host.start();

    // The descriptor merges into the host (findEntries spans attached fragments).
    assertNotNull(
        host.findEntries("OSGI-INF", "*.xml", false),
        "the host's findEntries must expose the fragment-contributed OSGI-INF/*.xml");

    // PROOF, via SCR's own runtime DTOs: the fragment-contributed component reaches state ACTIVE,
    // with no unsatisfied references and no failure. That IS the model's load-bearing claim — SCR
    // scans the host's fragments (DS 112.4.1) and activates a component that lives in a fragment.
    final ServiceComponentRuntime scr = felix.awaitService(ServiceComponentRuntime.class, 5000);
    assertNotNull(scr, "felix.scr must publish ServiceComponentRuntime");
    final var configuration =
        scr.getComponentDescriptionDTOs().stream()
            .filter(dto -> dto.implementationClass != null)
            .filter(dto -> dto.implementationClass.contains("FragmentContributedComponent"))
            .flatMap(dto -> scr.getComponentConfigurationDTOs(dto).stream())
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "SCR loaded no configuration for the fragment-contributed component —"
                            + " known: "
                            + scr.getComponentDescriptionDTOs().stream()
                                .map(d -> d.implementationClass)
                                .toList()));
    assertEquals(
        ComponentConfigurationDTO.ACTIVE,
        configuration.state,
        "the fragment-contributed component must be ACTIVE (unsatisfied="
            + Arrays.toString(configuration.unsatisfiedReferences)
            + ", failure="
            + configuration.failure
            + ")");

    // And — the roster claim — the HOST collector @Component RECEIVED the fragment's service into
    // its MULTIPLE/DYNAMIC @Reference List. Read SCR's DTO for the collector and assert its
    // reference has a bound service. This is the mechanism doctor-core uses for List<Specialist>:
    // not just "the contribution activated" but "the host consumed it".
    final ComponentConfigurationDTO collector =
        scr.getComponentDescriptionDTOs().stream()
            .filter(dto -> dto.implementationClass != null)
            .filter(dto -> dto.implementationClass.contains("ContributionCollector"))
            .flatMap(dto -> scr.getComponentConfigurationDTOs(dto).stream())
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("the host collector component is not known to SCR"));
    final boolean bound =
        collector.satisfiedReferences.length > 0
            && Arrays.stream(collector.satisfiedReferences)
                .anyMatch(ref -> ref.boundServices != null && ref.boundServices.length > 0);
    assertTrue(
        bound,
        "the host collector's MULTIPLE/DYNAMIC @Reference must have a bound service — the"
            + " fragment-contributed service was received into the host roster. satisfied="
            + Arrays.toString(collector.satisfiedReferences));
  }
}
