package io.seedmatic.rke2lab.osgibench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.seedmatic.rke2lab.benchcellar.Clean;
import io.seedmatic.rke2lab.benchcellar.Recipient;
import io.seedmatic.rke2lab.junit.testkit.OsgiWorld;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Filter;
import org.osgi.util.tracker.ServiceTracker;

/**
 * The resolution-mode thesis — the proof that we cover DYNAMIC service resolution, stated by
 * CONTRAST against STATIC in one world. Two cleans seal identically and differ ONLY in how their
 * MULTIPLE recipient roster resolves:
 *
 * <ul>
 *   <li>the {@code resolution=dynamic} clean binds recipients through DYNAMIC bind/unbind;
 *   <li>the {@code resolution=static} clean snapshots its roster at activation (STATIC, the default
 *       RELUCTANT policyOption — no reactivation for late arrivals).
 * </ul>
 *
 * Both snapshot the boot roster {alice, bob}; then carol is installed at runtime. The DYNAMIC clean
 * seals for {alice, bob, carol} — it saw the late arrival live — while the STATIC clean still seals
 * for {alice, bob}: carol is invisible to it. That gap IS why a roster that must react to services
 * coming and going at runtime needs policy=DYNAMIC, not STATIC (and why policyOption is a no-op on
 * a dynamic multiple — see the additivity thesis). This engraves the lesson the policyOption audit
 * had to correct.
 */
@OsgiWorld
class CellarResolutionModeTest {

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      OutOfContainerFrameworkExtension.builder()
          .withScr()
          .systemPackages("io.seedmatic.rke2lab.benchcellar")
          .installMatching(
              "(&(type=fixture)(suite=cellar)(|(role=recipient)(role=clean)(role=clean-static)))")
          .build();

  @Test
  void dynamic_resolution_sees_a_late_recipient_where_static_resolution_is_frozen()
      throws Exception {
    // The boot roster must be bound before the static clean snapshots it.
    awaitRecipient("alice");
    awaitRecipient("bob");
    final Clean dynamic = awaitClean("dynamic");
    final Clean statik =
        awaitClean("static"); // activating it now freezes its snapshot at {alice,bob}
    assertNotNull(dynamic, "the dynamic clean is published");
    assertNotNull(statik, "the static clean is published");

    assertEquals(
        Set.of("alice", "bob"),
        dynamic.clean("baseline").slots().keySet(),
        "both cleans start from the same boot roster");
    assertEquals(Set.of("alice", "bob"), statik.clean("baseline").slots().keySet());

    // Carol joins at runtime, AFTER the static clean has already snapshotted.
    felix.startAll(felix.installMatching("(&(type=fixture)(suite=cellar)(role=recipient-late))"));
    awaitRecipient("carol");

    assertEquals(
        Set.of("alice", "bob", "carol"),
        dynamic.clean("after").slots().keySet(),
        "DYNAMIC resolution bound the late recipient live");
    assertEquals(
        Set.of("alice", "bob"),
        statik.clean("after").slots().keySet(),
        "STATIC/RELUCTANT resolution froze its roster at activation — carol is invisible to it");
  }

  private Clean awaitClean(String resolution) throws Exception {
    return awaitByFilter(Clean.class, "(resolution=" + resolution + ")");
  }

  private void awaitRecipient(String id) throws Exception {
    assertNotNull(
        awaitByFilter(Recipient.class, "(recipient.id=" + id + ")"),
        "expected the " + id + " recipient to be published");
  }

  /** Event-driven wait for a service matching {@code props} — a ServiceTracker, never a sleep. */
  private <T> T awaitByFilter(Class<T> type, String props) throws Exception {
    final Filter filter =
        felix.context().createFilter("(&(objectClass=" + type.getName() + ")" + props + ")");
    final ServiceTracker<T, T> tracker = new ServiceTracker<>(felix.context(), filter, null);
    tracker.open();
    try {
      return tracker.waitForService(5000);
    } finally {
      tracker.close();
    }
  }
}
