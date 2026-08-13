package io.seedmatic.rke2lab.osgi.boot.discovery;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleReference;
import org.osgi.framework.wiring.BundleWiring;

/**
 * {@link ClassRealm#of(ClassLoader)} is the scenario-engine's membrane primitive: it tells the two
 * OSGi-vs-host worlds apart from a raw loader. A {@link BundleReference} loader resolves to the
 * {@link BundleClassRealm} of its owning bundle (each bundle owns its classloader — so each bundle
 * IS a realm); any other loader resolves to the flat {@link HostClassRealm}. This is the ONE place
 * the decision is made, so a caller reads the same {@link ClassRealm#adapt(Class)} either way.
 */
class ClassRealmOfLoaderTest {

  /**
   * A classloader that is also a {@link BundleReference} — the in-container shape Felix hands out.
   */
  private static final class BundleBackedLoader extends ClassLoader implements BundleReference {
    private final Bundle bundle;

    BundleBackedLoader(Bundle bundle) {
      super(ClassRealmOfLoaderTest.class.getClassLoader());
      this.bundle = bundle;
    }

    @Override
    public Bundle getBundle() {
      return bundle;
    }
  }

  private static Bundle bundleAdaptingTo(Class<?> type, Object face) {
    return (Bundle)
        Proxy.newProxyInstance(
            ClassRealmOfLoaderTest.class.getClassLoader(),
            new Class<?>[] {Bundle.class},
            (proxy, method, args) ->
                method.getName().equals("adapt") && args != null && args[0] == type ? face : null);
  }

  @Test
  void a_bundle_reference_loader_resolves_to_its_bundle_realm() {
    final BundleWiring wiring =
        (BundleWiring)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {BundleWiring.class},
                (p, m, a) -> null);
    final ClassLoader loader = new BundleBackedLoader(bundleAdaptingTo(BundleWiring.class, wiring));

    final ClassRealm realm = ClassRealm.of(loader);

    assertInstanceOf(BundleClassRealm.class, realm, "a BundleReference loader is the OSGi realm");
    assertSame(
        wiring,
        realm.adapt(BundleWiring.class).orElseThrow(),
        "the bundle realm delegates adapt to its bundle");
  }

  @Test
  void a_plain_loader_resolves_to_the_host_realm() {
    final ClassRealm realm = ClassRealm.of(getClass().getClassLoader());

    assertInstanceOf(HostClassRealm.class, realm, "a non-bundle loader is the flat host realm");
    assertTrue(
        realm.adapt(BundleWiring.class).isEmpty(),
        "the host realm offers no BundleWiring face (semantics B)");
  }
}
