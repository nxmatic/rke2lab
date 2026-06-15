package io.nxmatic.rke2lab.unitrepo.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.osgi.resource.Capability;
import org.osgi.resource.Namespace;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;

/**
 * A unit as an OSGi generic {@link Resource} (Core ch.6). It carries the Provide/Require graph that
 * already exists in rke2lab as ad-hoc strings ({@code ManifestDomainCatalog} IDs + {@code
 * dependsOn…}); here it is expressed in the resolver's own vocabulary so the Felix Resolver
 * standalone — not a hand-rolled walker — computes the closure.
 *
 * <p>Capabilities and requirements reference their resource (a cycle), so they are built bound to
 * {@code this} via the fluent {@link #provide}/{@link #require} verbs rather than a plain record.
 */
public final class UnitResource implements Resource {

  private final String id;
  private final List<Capability> capabilities = new ArrayList<>();
  private final List<Requirement> requirements = new ArrayList<>();

  public UnitResource(String id) {
    this.id = id;
  }

  /** Provide a capability (e.g. {@code unitrepo.unit} with {@code id=…}, or a manifest domain). */
  public UnitResource provide(String namespace, Map<String, Object> attributes) {
    capabilities.add(new UnitCapability(namespace, Map.of(), Map.copyOf(attributes), this));
    return this;
  }

  /** Require a capability via an LDAP {@code filter:} directive (= {@code dependsOn…}). */
  public UnitResource require(String namespace, String filter) {
    requirements.add(new UnitRequirement(namespace, Map.of("filter", filter), Map.of(), this));
    return this;
  }

  /**
   * Require <em>every</em> capability matching the {@code filter:} ({@code cardinality:=multiple}).
   * Used for containment edges: a parent gathers all its members with one requirement.
   */
  public UnitResource requireAll(String namespace, String filter) {
    requirements.add(
        new UnitRequirement(
            namespace,
            Map.of(
                "filter",
                filter,
                Namespace.REQUIREMENT_CARDINALITY_DIRECTIVE,
                Namespace.CARDINALITY_MULTIPLE),
            Map.of(),
            this));
    return this;
  }

  @Override
  public List<Capability> getCapabilities(String namespace) {
    if (namespace == null) {
      return List.copyOf(capabilities);
    }
    return capabilities.stream().filter(c -> c.getNamespace().equals(namespace)).toList();
  }

  @Override
  public List<Requirement> getRequirements(String namespace) {
    if (namespace == null) {
      return List.copyOf(requirements);
    }
    return requirements.stream().filter(r -> r.getNamespace().equals(namespace)).toList();
  }

  @Override
  public String toString() {
    return "UnitResource[" + id + "]";
  }

  record UnitCapability(
      String getNamespace,
      Map<String, String> getDirectives,
      Map<String, Object> getAttributes,
      Resource getResource)
      implements Capability {}

  record UnitRequirement(
      String getNamespace,
      Map<String, String> getDirectives,
      Map<String, Object> getAttributes,
      Resource getResource)
      implements Requirement {}
}
