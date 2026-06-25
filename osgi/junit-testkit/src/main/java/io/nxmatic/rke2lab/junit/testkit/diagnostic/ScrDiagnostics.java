package io.nxmatic.rke2lab.junit.testkit.diagnostic;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.runtime.ServiceComponentRuntime;
import org.osgi.service.component.runtime.dto.ComponentConfigurationDTO;
import org.osgi.service.component.runtime.dto.ComponentDescriptionDTO;
import org.osgi.service.component.runtime.dto.ReferenceDTO;
import org.osgi.service.component.runtime.dto.UnsatisfiedReferenceDTO;

/**
 * A test diagnostic for the {@code scr} subject: it asks Felix SCR (via {@link
 * ServiceComponentRuntime}) what it knows about every Declarative Services component, so a wiring
 * failure tells you WHY — which component is not published, which {@code @Reference} is unbound,
 * which target filter selected nothing — instead of a bare {@code awaitService} returning null.
 *
 * <p>The need recurs whenever a {@code @Component} (a domain contributing a {@code Specialist}, a
 * mapper, an edge probe) fails to activate: "charging a class does not activate a bundle", a
 * component stays {@code UNSATISFIED} until every mandatory reference binds, and a fragment-
 * contributed component is invisible unless its host declares {@code Service-Component:
 * OSGI-INF/*.xml}. Each of those shows up here as a state + an unsatisfied-reference list. The
 * first subject under {@code testkit.diagnostic}; siblings (bundle resolution, the service
 * registry) join as they are needed.
 *
 * <p>Reads SCR's own runtime DTOs only — it never loads an application type, so it is safe to call
 * from the bare-JVM harness against an embedded framework.
 */
public final class ScrDiagnostics {

  private final ServiceComponentRuntime scr;

  private ScrDiagnostics(ServiceComponentRuntime scr) {
    this.scr = scr;
  }

  /**
   * Bind to the {@link ServiceComponentRuntime} felix.scr publishes in {@code context}, or {@code
   * null} if SCR is not present (the framework was booted without {@code withScr()}).
   */
  public static ScrDiagnostics of(BundleContext context) {
    final ServiceReference<ServiceComponentRuntime> reference =
        context.getServiceReference(ServiceComponentRuntime.class);
    if (reference == null) {
      return null;
    }
    return new ScrDiagnostics(context.getService(reference));
  }

  /**
   * A human-readable report of every known component: its implementation class, each
   * configuration's {@link ComponentConfigurationDTO#state state}, and — when a configuration is
   * not satisfied — the references that are unbound (with their target filters). The string a
   * failing assertion appends so the reason travels with the failure.
   */
  public String report() {
    final StringBuilder report = new StringBuilder("\n=== SCR components ===\n");
    for (ComponentDescriptionDTO description : scr.getComponentDescriptionDTOs()) {
      report.append("component ").append(description.implementationClass).append('\n');
      for (ComponentConfigurationDTO configuration :
          scr.getComponentConfigurationDTOs(description)) {
        report
            .append("  state=")
            .append(stateName(configuration.state))
            .append(" failure=")
            .append(configuration.failure)
            .append('\n');
        for (UnsatisfiedReferenceDTO unsatisfied : configuration.unsatisfiedReferences) {
          report
              .append("    unsatisfied ref ")
              .append(unsatisfied.name)
              .append(" target=")
              .append(targetOf(description, unsatisfied.name))
              .append('\n');
        }
      }
    }
    return report.toString();
  }

  /** The {@code ComponentConfigurationDTO} state constant as its readable name. */
  private static String stateName(int state) {
    return switch (state) {
      case ComponentConfigurationDTO.UNSATISFIED_CONFIGURATION -> "UNSATISFIED_CONFIGURATION";
      case ComponentConfigurationDTO.UNSATISFIED_REFERENCE -> "UNSATISFIED_REFERENCE";
      case ComponentConfigurationDTO.SATISFIED -> "SATISFIED";
      case ComponentConfigurationDTO.ACTIVE -> "ACTIVE";
      case ComponentConfigurationDTO.FAILED_ACTIVATION -> "FAILED_ACTIVATION";
      default -> "state(" + state + ")";
    };
  }

  /** The declared target filter of a named reference, or {@code null} if it states none. */
  private static String targetOf(ComponentDescriptionDTO description, String referenceName) {
    for (ReferenceDTO reference : description.references) {
      if (reference.name.equals(referenceName)) {
        return reference.target;
      }
    }
    return null;
  }
}
