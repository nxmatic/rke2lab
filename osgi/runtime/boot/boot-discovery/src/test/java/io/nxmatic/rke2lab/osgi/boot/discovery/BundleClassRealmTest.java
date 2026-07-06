package io.nxmatic.rke2lab.osgi.boot.discovery;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;

/**
 * The bundle {@link ClassRealm} delegates {@link ClassRealm#adapt(Class)} to {@link
 * Bundle#adapt(Class)} and normalizes its {@code null} (the OSGi "no such face" answer) to {@link
 * Optional#empty()}, so both worlds present one {@code Optional} contract. Driven with a JDK
 * dynamic proxy standing in for the framework's {@code adapt} (no mock library on this module).
 */
class BundleClassRealmTest {

  interface Face {}

  /** A {@link Bundle} whose {@code adapt(type)} returns {@code faces.get(type)} (may be null). */
  private static Bundle bundleAdaptingTo(Class<?> type, Object face) {
    return (Bundle)
        Proxy.newProxyInstance(
            BundleClassRealmTest.class.getClassLoader(),
            new Class<?>[] {Bundle.class},
            (proxy, method, args) -> {
              if (method.getName().equals("adapt") && args != null && args.length == 1) {
                return args[0] == type ? face : null;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  @Test
  void adapt_delegates_to_the_bundle_and_returns_the_wired_face() {
    final Face wired = new Face() {};
    final Optional<Face> face =
        BundleClassRealm.of(bundleAdaptingTo(Face.class, wired)).adapt(Face.class);
    assertTrue(face.isPresent(), "the realm surfaces the face the framework wires for the bundle");
    assertSame(wired, face.get());
  }

  @Test
  void adapt_normalizes_the_bundles_null_to_empty() {
    assertTrue(
        BundleClassRealm.of(bundleAdaptingTo(Face.class, null)).adapt(Face.class).isEmpty(),
        "Bundle.adapt's null (no such face) becomes Optional.empty, not a null Optional");
  }
}
