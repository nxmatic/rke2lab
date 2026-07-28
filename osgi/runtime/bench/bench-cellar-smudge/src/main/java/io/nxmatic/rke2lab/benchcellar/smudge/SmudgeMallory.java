package io.nxmatic.rke2lab.benchcellar.smudge;

import io.nxmatic.rke2lab.benchcellar.Recipient;
import io.nxmatic.rke2lab.benchcellar.SealedBlob;
import io.nxmatic.rke2lab.benchcellar.Smudge;
import io.nxmatic.rke2lab.benchcellar.StandInCrypto;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The intruder. mallory is NOT in the roster, so its mandatory self-reference to {@code
 * recipient.id=mallory} can never be injected and SCR never constructs this smudge — even sitting
 * in the same bundle as the satisfied alice/bob readers. That absence IS the anti-cheat: an
 * identity not sealed for cannot acquire a reader, let alone a slot.
 */
@Component(service = Smudge.class, property = "smudge.id=mallory")
public final class SmudgeMallory implements Smudge {

  private final Recipient self;

  @Activate
  public SmudgeMallory(@Reference(target = "(recipient.id=mallory)") Recipient self) {
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
