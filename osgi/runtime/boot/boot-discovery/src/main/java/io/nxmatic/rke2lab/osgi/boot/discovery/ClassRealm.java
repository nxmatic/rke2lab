package io.nxmatic.rke2lab.osgi.boot.discovery;

import java.util.Optional;
import org.osgi.framework.BundleReference;

/**
 * A classloader-bounded world, seen as the source of the capability faces it can offer — a
 * <em>class realm</em> in the Plexus ClassWorlds sense (a classloader-isolated space, the term the
 * specs already use: "a class references only types reachable in its own classloader realm"), here
 * MATERIALIZED as a type. NOT the security sense (Tomcat/JAAS users+roles); the {@code Class}
 * prefix anchors the classloader meaning. The two incarnations are the two spaces of the invariant:
 * {@link HostClassRealm} (the flat JCL the boot runs on) and {@code BundleClassRealm} (the
 * bundle-wired OSGi world, delegating to {@code Bundle.adapt}).
 *
 * <p>A class realm answers by CAPABILITY: {@link #adapt(Class)} returns the face this world offers,
 * or {@link Optional#empty()} when it does not. The asymmetry is real — the host realm offers few
 * faces, the OSGi realm many — not a defect. The default is the self-cast idiom (a realm that IS
 * the face surfaces itself); realms that reach faces elsewhere (a booted framework, a service
 * registry) override it.
 *
 * <p>This is the single door the two worlds share, so a reader following "how do I get face T from
 * this world?" lands on one contract instead of scattered {@code instanceof}/{@code adapt} reaches.
 */
public interface ClassRealm {

  /**
   * The realm {@code loader} belongs to — the {@link BundleClassRealm} of its owning bundle when
   * the loader is a {@link BundleReference} (the in-container, bundle-wired world), else the {@link
   * HostClassRealm} of the flat classpath. This is the one place the two worlds are told apart: a
   * caller asks the realm for a face and reads the same {@link #adapt(Class)} contract either way,
   * instead of branching on {@code instanceof BundleReference} at each reach.
   */
  static ClassRealm of(ClassLoader loader) {
    return loader instanceof BundleReference bundleReference
        ? BundleClassRealm.of(bundleReference.getBundle())
        : HostClassRealm.of(loader);
  }

  /**
   * The face of this realm that implements {@code type}, or {@link Optional#empty()} if this world
   * does not offer it. The default is the self-cast — a realm that IS the face returns itself.
   */
  default <T> Optional<T> adapt(Class<T> type) {
    return type.isInstance(this) ? Optional.of(type.cast(this)) : Optional.empty();
  }
}
