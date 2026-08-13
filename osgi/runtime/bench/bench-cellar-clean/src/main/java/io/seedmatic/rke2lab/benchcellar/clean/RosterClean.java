package io.seedmatic.rke2lab.benchcellar.clean;

import io.seedmatic.rke2lab.benchcellar.Clean;
import io.seedmatic.rke2lab.benchcellar.Recipient;
import io.seedmatic.rke2lab.benchcellar.SealedBlob;
import io.seedmatic.rke2lab.benchcellar.Sealing;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

/**
 * The DYNAMIC clean filter. Its MULTIPLE/DYNAMIC {@link Reference} to {@link Recipient} binds
 * through bind/unbind into a live {@link CopyOnWriteArrayList} — the proven roster mechanism
 * (mirrors the fragment-host collector) — so each {@link #clean(String)} seals for whoever is bound
 * AT THAT CALL. Published with {@code resolution=dynamic} so the resolution-mode thesis can
 * contrast it against the static clean in the same world.
 */
@Component(service = Clean.class, immediate = true, property = "resolution=dynamic")
public final class RosterClean implements Clean {

  private final List<Recipient> recipients = new CopyOnWriteArrayList<>();
  private volatile Set<String> lastRoster = Set.of();

  // MULTIPLE + DYNAMIC binds every recipient as it registers, at boot AND at runtime — the default
  // RELUCTANT policyOption is enough (policyOption only bites on STATIC or unary references; for a
  // dynamic multiple it is a no-op). The additivity thesis proves this: carol, installed after the
  // clean is active, binds without GREEDY.
  @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
  void bindRecipient(Recipient recipient) {
    recipients.add(recipient);
  }

  void unbindRecipient(Recipient recipient) {
    recipients.remove(recipient);
  }

  @Override
  public SealedBlob clean(String plaintext) {
    final SealedBlob blob = Sealing.seal(plaintext, List.copyOf(recipients));
    this.lastRoster = Set.copyOf(blob.slots().keySet());
    return blob;
  }

  @Override
  public Set<String> lastRoster() {
    return lastRoster;
  }
}
