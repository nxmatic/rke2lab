package io.nxmatic.rke2lab.seed.broker.internal;

import io.nxmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.AmendmentAssembler;
import io.nxmatic.rke2lab.seed.broker.port.AmendmentContributor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

/**
 * Collects every {@link AmendmentContributor} published OSGi-side (by Declarative Services, host
 * registrations included) and, for a wanted {@link AmendCoordinate}, merges the roles of those that
 * fill it. The gathering sibling of {@link DefaultSeedBroker}: same {@code @Reference(MULTIPLE)}
 * roster, same fail-loud discipline — two contributors claiming one role for one coordinate is a
 * wiring bug the merge refuses to ship, rather than silently dropping one.
 *
 * <p>The reference is DYNAMIC, unlike the broker's static handler roster: a host-side contributor
 * (the FACET owner) is {@code registerService}d at RUNTIME from the scenario's GIVEN, long after
 * the assembler activated at boot. A static reference would capture the boot-time (empty) set and
 * miss it; the dynamic collection field is rebound live as contributors come and go, so a gather at
 * consult-time sees the late registration.
 */
@Component(service = AmendmentAssembler.class)
public final class DefaultAmendmentAssembler implements AmendmentAssembler {

  @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
  private volatile List<AmendmentContributor> contributors = List.of();

  @Override
  public Map<String, String> gather(AmendCoordinate coordinate) {
    final Map<String, String> merged = new LinkedHashMap<>();
    for (AmendmentContributor contributor : contributors) {
      if (!coordinate.equals(contributor.coordinate())) {
        continue;
      }
      contributor
          .roles()
          .forEach(
              (role, value) -> {
                final String prior = merged.putIfAbsent(role, value);
                if (prior != null) {
                  throw new IllegalStateException(
                      "two AmendmentContributors fill role '"
                          + role
                          + "' for coordinate "
                          + coordinate
                          + " — the role is ambiguous to gather");
                }
              });
    }
    return Map.copyOf(merged);
  }
}
