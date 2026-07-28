package io.nxmatic.rke2lab.osgibench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.nxmatic.rke2lab.benchcellar.Clean;
import io.nxmatic.rke2lab.benchcellar.Recipient;
import io.nxmatic.rke2lab.benchcellar.SealedBlob;
import io.nxmatic.rke2lab.benchcellar.Smudge;
import io.nxmatic.rke2lab.junit.testkit.OsgiWorld;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Filter;
import org.osgi.util.tracker.ServiceTracker;

/**
 * The additivity thesis, in its own framework because it MUTATES the roster at runtime. The boot
 * roster {alice, bob} seals a first blob; then carol is installed as a live bundle and the clean's
 * GREEDY {@code @Reference(MULTIPLE)} binds her; the next seal earns carol a slot, while the first
 * blob is untouched and alice still reveals both. Adding a recipient extends future seals without
 * breaking the existing ones — the property a travelling cellar leans on (bioskop-seeds-nikopol:
 * the seeded cluster is a recipient added to the roster).
 */
@OsgiWorld
class CellarAdditivityTest {

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      OutOfContainerFrameworkExtension.builder()
          .withScr()
          .systemPackages("io.nxmatic.rke2lab.benchcellar")
          .installMatching(
              "(&(type=fixture)(suite=cellar)(|(role=recipient)(role=clean)(role=smudge)))")
          .build();

  @Test
  void adding_a_recipient_at_runtime_extends_the_next_seal_and_leaves_the_prior_intact()
      throws Exception {
    final Clean clean = felix.awaitService(Clean.class, 5000);
    assertNotNull(clean, "the clean is active over the boot roster");
    awaitRecipient("alice");
    awaitRecipient("bob");
    final Smudge alice = awaitSmudge("alice");
    assertNotNull(alice, "alice reads for the boot roster");

    final SealedBlob before = clean.clean("secret-before");
    assertEquals(Set.of("alice", "bob"), before.slots().keySet(), "boot roster: alice, bob");

    // Carol joins at runtime — a real bundle install+start, not a hand-registered service. The
    // instance installMatching installs WITHOUT starting (unlike the builder's), so start her
    // explicitly. Her Recipient service appears only after SCR has GREEDILY bound her into the
    // (already active) clean, so observing the service is enough — no polling of the seal result.
    felix.startAll(felix.installMatching("(&(type=fixture)(suite=cellar)(role=recipient-late))"));
    awaitRecipient("carol");

    final SealedBlob after = clean.clean("secret-after");
    assertEquals(
        Set.of("alice", "bob", "carol"),
        after.slots().keySet(),
        "carol earned a slot at the next seal");

    assertEquals("secret-before", alice.smudge(before), "the prior blob is untouched");
    assertEquals("secret-after", alice.smudge(after), "and alice still reveals the new one");
  }

  private void awaitRecipient(String id) throws Exception {
    assertNotNull(
        awaitByFilter(Recipient.class, "(recipient.id=" + id + ")"),
        "expected the " + id + " recipient to be published");
  }

  private Smudge awaitSmudge(String id) throws Exception {
    return awaitByFilter(Smudge.class, "(smudge.id=" + id + ")");
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
