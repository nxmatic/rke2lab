package io.nxmatic.rke2lab.seed.broker.internal;

import io.nxmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.AmendmentAssembler;
import io.nxmatic.rke2lab.seed.broker.port.AmendmentContributor;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedHandler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
 * <p>BETA — the orphan guard. The one silent seam of the fill-by-role is here: a contributor
 * published for a coordinate NO grower serves is silently orphaned (its roles never gather, the
 * amend falls to its defaults, nothing throws — unlike a SOW, which the broker routes and throws
 * on). So the assembler cross-checks every contributor's {@link AmendmentContributor#coordinate}
 * against the SERVED set — the same {@link SeedHandler} roster the broker routes on — and throws on
 * an orphan, turning that silent path loud for every domain. This is why a slug divergence needs no
 * shared constant to be SAFE: loud divergence replaces compile-time equality.
 *
 * <p>Both references are DYNAMIC and the check runs at GATHER, not at bind: a host-side contributor
 * (the FACET owner) is {@code registerService}d at RUNTIME from the scenario's GIVEN, after boot,
 * and the launch lifts the framework to its start level but does not awaitService the growers, so
 * SCR activation can lag a bind. A gather happens at the consult — well after boot, the GIVEN, and
 * DS settling — so both rosters are complete and the check is race-free. (A future OSGi-native
 * bundle contributor is not launch-sequenced; it needs a start-level guarantee — see
 * docs/architecture/osgi/seed-broker-spec.adoc § Amend integrity, the bench-proven start level.)
 */
@Component(service = AmendmentAssembler.class)
public final class DefaultAmendmentAssembler implements AmendmentAssembler {

  // Package-private (not private) so the same-package unit test injects the two rosters directly;
  // SCR binds them by reflection regardless of visibility.
  @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
  volatile List<AmendmentContributor> contributors = List.of();

  @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
  volatile List<SeedHandler> growers = List.of();

  @Override
  public Map<String, String> gather(AmendCoordinate coordinate) {
    failOnOrphanContributor();
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

  /** BETA: every contributor must fill a coordinate some grower serves, or it is a wiring bug. */
  private void failOnOrphanContributor() {
    final Set<SeedCoordinate> served =
        growers.stream().map(SeedHandler::serves).collect(Collectors.toUnmodifiableSet());
    for (AmendmentContributor contributor : contributors) {
      if (!served.contains(contributor.coordinate())) {
        throw new IllegalStateException(
            "contributor for a coordinate no grower serves: "
                + contributor.coordinate()
                + " ("
                + contributor.getClass().getName()
                + ") — a slug divergence between the contributor and the amend reflector");
      }
    }
  }
}
