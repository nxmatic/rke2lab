package io.seedmatic.rke2lab.osgibench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.seedmatic.rke2lab.benchcellar.Clean;
import io.seedmatic.rke2lab.benchcellar.Recipient;
import io.seedmatic.rke2lab.benchcellar.SealedBlob;
import io.seedmatic.rke2lab.benchcellar.Smudge;
import io.seedmatic.rke2lab.junit.testkit.OsgiWorld;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Filter;
import org.osgi.util.tracker.ServiceTracker;

/**
 * The multi-recipient cellar seam, on the real engine — three read-only theses over one topology
 * (the boot roster {alice, bob} + the clean + the three readers). The blob is the sops key-slot
 * shape: one ciphertext under a fresh data key, one wrapped slot per recipient.
 *
 * <ul>
 *   <li><b>Roster</b> — the clean's {@code @Reference(MULTIPLE)} seals for every bound recipient.
 *   <li><b>Independent smudge</b> — a reader recovers the payload from its own slot alone, no
 *       shared passphrase.
 *   <li><b>Anti-cheat</b> — an identity absent from the roster never gets a reader (its mandatory
 *       self-reference never binds), so it can never read.
 * </ul>
 *
 * All three read the same world without mutating its bundle topology, so they share one framework;
 * the additivity thesis (which installs a recipient at runtime) lives in its own class.
 */
@OsgiWorld
class CellarSeamTest {

  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      OutOfContainerFrameworkExtension.builder()
          .withScr()
          .systemPackages("io.seedmatic.rke2lab.benchcellar")
          .installMatching(
              "(&(type=fixture)(suite=cellar)(|(role=recipient)(role=clean)(role=smudge)))")
          .build();

  @Test
  void the_clean_seals_for_the_whole_bound_roster() throws Exception {
    final Clean clean = felix.awaitService(Clean.class, 5000);
    assertNotNull(clean, "the clean component activated with its MULTIPLE recipient reference");
    // The clean binds GREEDILY and felix.scr binds within the service registration, so once both
    // recipient services are observed the clean has already bound them — no polling.
    awaitRecipient("alice");
    awaitRecipient("bob");

    final SealedBlob blob = clean.clean("the-secret");

    assertEquals(Set.of("alice", "bob"), clean.lastRoster(), "sealed for the whole boot roster");
    assertEquals(Set.of("alice", "bob"), blob.slots().keySet(), "one key-slot per bound recipient");
  }

  @Test
  void a_smudge_recovers_the_payload_from_its_own_slot_alone() throws Exception {
    final Clean clean = felix.awaitService(Clean.class, 5000);
    awaitRecipient("alice");
    awaitRecipient("bob");
    final Smudge alice = awaitSmudge("alice");
    assertNotNull(alice, "alice is in the roster, so her reader is published");

    final SealedBlob blob = clean.clean("the-secret");

    assertEquals(
        "the-secret",
        alice.smudge(blob),
        "alice unwraps only her slot — no shared passphrase with bob");
  }

  @Test
  void an_identity_absent_from_the_roster_never_gets_a_reader() throws Exception {
    // alice's reader confirms SCR has processed the smudge bundle's components — alice and mallory
    // were offered to SCR together; alice being up means mallory has had its chance and failed.
    assertNotNull(awaitSmudge("alice"), "alice is a recipient — her reader is published");
    assertNull(
        smudgeService("mallory"),
        "mallory is not a recipient — her mandatory self-reference never binds, so no reader");
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

  /** A one-shot lookup with no wait — for the anti-cheat's proof of ABSENCE. */
  private Smudge smudgeService(String id) throws Exception {
    final var refs = felix.context().getServiceReferences(Smudge.class, "(smudge.id=" + id + ")");
    return refs.isEmpty() ? null : felix.context().getService(refs.iterator().next());
  }
}
