package io.nxmatic.rke2lab.benchcellar.staticclean;

import io.nxmatic.rke2lab.benchcellar.Clean;
import io.nxmatic.rke2lab.benchcellar.Recipient;
import io.nxmatic.rke2lab.benchcellar.SealedBlob;
import io.nxmatic.rke2lab.benchcellar.Sealing;
import java.util.List;
import java.util.Set;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;

/**
 * The STATIC clean filter — the contrast fixture for the resolution-mode thesis. Its MULTIPLE
 * reference to {@link Recipient} uses the DEFAULT policy (STATIC) with constructor injection, so
 * the roster is a SNAPSHOT frozen at activation, and the default RELUCTANT policyOption means SCR
 * never reactivates it for a recipient that registers later. It seals exactly like the dynamic
 * clean ({@link Sealing}) — the ONLY difference is how it resolves its roster.
 *
 * <p>Delayed (not immediate) on purpose: the test activates it — freezing its snapshot — at a
 * chosen moment, then installs a recipient afterwards to show that recipient is never in this
 * clean's roster. Published {@code resolution=static} so the test tells the two cleans apart.
 */
@Component(service = Clean.class, property = "resolution=static")
public final class StaticRosterClean implements Clean {

  private final List<Recipient> snapshot;
  private volatile Set<String> lastRoster = Set.of();

  @Activate
  public StaticRosterClean(
      @Reference(cardinality = ReferenceCardinality.MULTIPLE) List<Recipient> recipients) {
    this.snapshot = List.copyOf(recipients);
  }

  @Override
  public SealedBlob clean(String plaintext) {
    final SealedBlob blob = Sealing.seal(plaintext, snapshot);
    this.lastRoster = Set.copyOf(blob.slots().keySet());
    return blob;
  }

  @Override
  public Set<String> lastRoster() {
    return lastRoster;
  }
}
