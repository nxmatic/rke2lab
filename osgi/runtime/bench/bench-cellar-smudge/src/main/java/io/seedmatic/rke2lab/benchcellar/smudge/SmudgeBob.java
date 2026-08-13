package io.seedmatic.rke2lab.benchcellar.smudge;

import io.seedmatic.rke2lab.benchcellar.Recipient;
import io.seedmatic.rke2lab.benchcellar.SealedBlob;
import io.seedmatic.rke2lab.benchcellar.Smudge;
import io.seedmatic.rke2lab.benchcellar.StandInCrypto;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/** The bob reader — the twin of {@link SmudgeAlice}, gated on its own roster entry. */
@Component(service = Smudge.class, property = "smudge.id=bob")
public final class SmudgeBob implements Smudge {

  private final Recipient self;

  @Activate
  public SmudgeBob(@Reference(target = "(recipient.id=bob)") Recipient self) {
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
