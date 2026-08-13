package io.seedmatic.rke2lab.seed.broker.internal;

import io.seedmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.AmendmentAssembler;
import io.seedmatic.rke2lab.seed.broker.port.AmendmentContributor;
import io.seedmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedHandler;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.annotations.Activate;
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

  // Package-private (not private) so the same-package unit test injects the contributor roster
  // directly; SCR binds it by reflection regardless of visibility.
  @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
  volatile List<AmendmentContributor> contributors = List.of();

  // ServiceReferences, NOT the SeedHandler services: SCR binds these WITHOUT constructing the
  // handlers, so THIS component's activation never triggers SeedHandler construction. That breaks a
  // boot service-factory cycle — the SeedBroker's constructor collects List<SeedHandler>, one of
  // which is an amend reflector whose OWN constructor @References this assembler; were growers the
  // service type, activating the assembler would re-get every SeedHandler (that reflector included)
  // while it is still mid-construction → "ServiceFactory.getService() resulted in a cycle". Deref
  // is
  // deferred to gather() through growerRoster, post-boot, where the BETA guard already runs and
  // every grower is built.
  @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
  volatile List<ServiceReference<SeedHandler>> growerRefs = List.of();

  // The grower roster resolved at gather time (never at activation): production derefs growerRefs
  // through the BundleContext; the same-package unit test supplies a fixed roster via the test
  // constructor. Held as a supplier so neither path leaves the assembler in a partial state.
  private final Supplier<Collection<SeedHandler>> growerRoster;

  @Activate
  public DefaultAmendmentAssembler(BundleContext bundleContext) {
    this.growerRoster =
        () -> growerRefs.stream().map(bundleContext::getService).filter(Objects::nonNull).toList();
  }

  // Test seam: a fixed grower roster, no OSGi lookup (the test injects contributors on the field).
  DefaultAmendmentAssembler(Collection<SeedHandler> growers) {
    this.growerRoster = () -> growers;
  }

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
        growerRoster.get().stream()
            .map(SeedHandler::serves)
            .collect(Collectors.toUnmodifiableSet());
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
