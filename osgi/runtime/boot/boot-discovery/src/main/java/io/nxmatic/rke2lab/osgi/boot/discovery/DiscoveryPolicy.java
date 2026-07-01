package io.nxmatic.rke2lab.osgi.boot.discovery;

import java.util.List;
import java.util.Set;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;

/**
 * Which bundles of a {@link BundleIndex} a boot installs — the ONE selection rule shared by the
 * live executor and the test harness, so a maintainer evolves it in a single place and the two
 * never drift. A spectrum from least to most deterministic:
 *
 * <ul>
 *   <li>{@link #all()} — install every bundle the index carries (the launcher is already excluded,
 *       it is the system bundle). The default: feed Felix everything that is there and let its
 *       resolver compute the wiring. In the deployed exec-jar the index IS the staged set, so this
 *       is exactly the staged bundles; on a reactor/test classpath it is every bundle on the path,
 *       which is harmless — an installed-but-unused bundle resolves without activating.
 *   <li>{@link #allExcept(String...)} — all, minus the named symbolic names. To drop one bundle a
 *       given test does not want, without re-listing the rest.
 *   <li>{@link #only(String...)} / {@link #only(Filter)} — install ONLY the named bundles (or those
 *       whose embed capability matches the filter). The most deterministic: a closed set, nothing
 *       else, for a test that pins its exact topology.
 * </ul>
 *
 * <p>Start levels are NOT this rule's concern — the executor still pins pax/felix.scr/model-edge to
 * their layers; a bundle selected here that belongs to no layer (e.g. a spec jar like {@code
 * org.osgi.service.component}) installs passively: it resolves so importers wire to it, and never
 * activates (no activator, no component), so its level is immaterial.
 */
public sealed interface DiscoveryPolicy {

  /**
   * Apply this policy to {@code index}, yielding the bundles to install (launcher already gone).
   */
  List<BundleLocation> select(BundleIndex index);

  /** Install every bundle the index carries — the default, least-deterministic boot. */
  static DiscoveryPolicy all() {
    return new All();
  }

  /** Install every bundle except the named symbolic names. */
  static DiscoveryPolicy allExcept(String... symbolicNames) {
    return new AllExcept(Set.of(symbolicNames));
  }

  /** Install only the named symbolic names (absent ones are simply not installed). */
  static DiscoveryPolicy only(String... symbolicNames) {
    return new OnlyNames(Set.of(symbolicNames));
  }

  /** Install only the bundles whose embed capability matches {@code ldapFilter}. */
  static DiscoveryPolicy onlyMatching(String ldapFilter) {
    return new OnlyFilter(filter(ldapFilter));
  }

  record All() implements DiscoveryPolicy {
    @Override
    public List<BundleLocation> select(BundleIndex index) {
      return index.all();
    }
  }

  record AllExcept(Set<String> symbolicNames) implements DiscoveryPolicy {
    @Override
    public List<BundleLocation> select(BundleIndex index) {
      return index.all().stream()
          .filter(
              b -> index.symbolicNameOf(b).map(name -> !symbolicNames.contains(name)).orElse(true))
          .toList();
    }
  }

  record OnlyNames(Set<String> symbolicNames) implements DiscoveryPolicy {
    @Override
    public List<BundleLocation> select(BundleIndex index) {
      return index.all().stream()
          .filter(b -> index.symbolicNameOf(b).map(symbolicNames::contains).orElse(false))
          .toList();
    }
  }

  record OnlyFilter(Filter filter) implements DiscoveryPolicy {
    @Override
    public List<BundleLocation> select(BundleIndex index) {
      return index.matching(filter.toString());
    }
  }

  private static Filter filter(String ldapFilter) {
    try {
      return FrameworkUtil.createFilter(ldapFilter);
    } catch (InvalidSyntaxException ex) {
      throw new IllegalArgumentException("malformed LDAP filter: " + ldapFilter, ex);
    }
  }
}
