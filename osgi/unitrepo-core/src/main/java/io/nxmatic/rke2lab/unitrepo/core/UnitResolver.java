package io.nxmatic.rke2lab.unitrepo.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.felix.resolver.Logger;
import org.apache.felix.resolver.ResolverImpl;
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
 * Resolves a unit's Provide/Require closure with the Apache Felix Resolver running STANDALONE — no
 * OSGi framework, no classloading, no ServiceLoader/TCCL. This is the resolution track de-risked in
 * the design: the resolver is a pure function of (units, their constraints) → wiring, replacing the
 * hand-rolled dependency walker in the manifests module.
 *
 * <p>The universe of candidate units is supplied via {@link ResolveContext#findProviders}, matched
 * by namespace + the requirement's {@code filter:} directive over each capability's attributes.
 */
public final class UnitResolver {

  private final List<UnitResource> universe;
  private final Resolver felix = new ResolverImpl(new Logger(Logger.LOG_ERROR));

  public UnitResolver(List<UnitResource> universe) {
    this.universe = List.copyOf(universe);
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
