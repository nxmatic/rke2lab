package io.seedmatic.rke2lab.benchcellar.smudge;

import io.seedmatic.rke2lab.benchcellar.Recipient;
import io.seedmatic.rke2lab.benchcellar.SealedBlob;
import io.seedmatic.rke2lab.benchcellar.Smudge;
import io.seedmatic.rke2lab.benchcellar.StandInCrypto;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The alice reader. Its mandatory self-reference to its OWN roster entry ({@code
 * recipient.id=alice}), injected at construction, is both the anti-cheat gate — no smudge is
 * constructed for an identity absent from the roster — and the identity source it reveals under. It
 * unwraps only its own slot; no shared passphrase with the other readers.
 */
@Component(service = Smudge.class, property = "smudge.id=alice")
public final class SmudgeAlice implements Smudge {

  private final Recipient self;

  @Activate
  public SmudgeAlice(@Reference(target = "(recipient.id=alice)") Recipient self) {
    this.self = self;
  }

  @Override
  public String id() {
    return self.id();
  }

  @Override
  public String smudge(SealedBlob blob) {
    final byte[] slot = blob.slots().get(self.id());
    if (slot == null) {
      throw new IllegalStateException("blob is not addressed to " + self.id());
    }
    final byte[] dataKey = StandInCrypto.unwrap(slot, StandInCrypto.keyOf(self.id()));
    return StandInCrypto.decrypt(blob.ciphertext(), dataKey);
  }
}
