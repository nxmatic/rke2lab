package io.seedmatic.rke2lab.osgi.boot.discovery;

import java.util.Optional;
import org.osgi.framework.Bundle;

/**
 * The OSGi {@link ClassRealm} — a bundle-wired world, seen through the {@link Bundle} that owns it.
 * Where {@link HostClassRealm} IS its (few) faces, a bundle realm REACHES its faces through the
 * framework: it does not implement {@code BundleWiring}/{@code BundleRevision}/… itself, the
 * framework hands them out via {@link Bundle#adapt(Class)}. So this realm overrides the self-cast
 * default and delegates — the "realms that reach faces elsewhere override it" case the {@link
 * ClassRealm} contract anticipates.
 *
 * <p>{@link Bundle#adapt(Class)} returns {@code null} when the bundle offers no such face; this
 * realm normalizes that to {@link Optional#empty()}, so a caller reads one {@code Optional}
 * contract across both worlds instead of a null check on the OSGi side and an {@code Optional} on
 * the host side. That normalization is the wrapper's reason to exist.
 */
public final class BundleClassRealm implements ClassRealm {

  private final Bundle bundle;

  private BundleClassRealm(Bundle bundle) {
    this.bundle = bundle;
  }

  /** The OSGi realm owned by {@code bundle} — the faces the framework wires for it. */
  public static BundleClassRealm of(Bundle bundle) {
    return new BundleClassRealm(bundle);
  }

  /**
   * The face the framework adapts {@code bundle} to, or {@link Optional#empty()} when the bundle
   * offers none — {@link Bundle#adapt(Class)}'s {@code null} normalized to empty.
   */
  @Override
  public <T> Optional<T> adapt(Class<T> type) {
    return Optional.ofNullable(bundle.adapt(type));
  }
}
