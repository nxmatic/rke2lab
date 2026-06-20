package io.nxmatic.rke2lab.unitrepo.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.resource.Wiring;
import org.osgi.service.resolver.HostedCapability;
import org.osgi.service.resolver.ResolutionException;
import org.osgi.service.resolver.ResolveContext;
import org.osgi.service.resolver.Resolver;

/**
 * Resolves a unit's Provide/Require closure with an OSGi {@link Resolver}: a pure function of
 * (units, their constraints) → wiring, replacing the hand-rolled dependency walker in the manifests
 * module. The {@code Resolver} is INJECTED, not constructed — in production it is the {@code
 * org.osgi.service.resolver.Resolver} service that the felix.resolver bundle's activator registers,
 * so this module imports only the OSGi service interface (never felix.resolver's impl package) and
 * resolves as a clean bundle. Callers without a framework (unit tests) supply their own {@code
 * Resolver} (e.g. a {@code new ResolverImpl(...)}); the test owns that dependency.
 *
 * <p>The universe of candidate units is supplied via {@link ResolveContext#findProviders}, matched
 * by namespace + the requirement's {@code filter:} directive over each capability's attributes.
 */
public final class UnitResolver {

  private final List<UnitResource> universe;
  private final Resolver felix;

  public UnitResolver(List<UnitResource> universe, Resolver resolver) {
    this.universe = List.copyOf(universe);
    this.felix = resolver;
  }

  /** Resolve {@code root}'s closure against the universe; returns the wiring per resolved unit. */
  public Map<Resource, List<Wire>> resolve(UnitResource root) throws ResolutionException {
    ResolveContext context =
        new ResolveContext() {
          @Override
          public Collection<Resource> getMandatoryResources() {
            return List.of(root);
          }

          @Override
          public List<Capability> findProviders(Requirement requirement) {
            String filter = requirement.getDirectives().get("filter");
            List<Capability> providers = new ArrayList<>();
            for (UnitResource unit : universe) {
              for (Capability cap : unit.getCapabilities(requirement.getNamespace())) {
                if (CapabilityFilter.matches(filter, cap.getAttributes())) {
                  providers.add(cap);
                }
              }
            }
            return providers;
          }

          @Override
          public int insertHostedCapability(List<Capability> capabilities, HostedCapability hc) {
            capabilities.add(hc);
            return capabilities.size() - 1;
          }

          @Override
          public boolean isEffective(Requirement requirement) {
            return true;
          }

          @Override
          public Map<Resource, Wiring> getWirings() {
            return Collections.emptyMap();
          }
        };
    return new LinkedHashMap<>(felix.resolve(context));
  }
}
