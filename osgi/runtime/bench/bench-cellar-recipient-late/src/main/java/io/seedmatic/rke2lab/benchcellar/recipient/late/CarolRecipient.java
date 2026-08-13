package io.seedmatic.rke2lab.benchcellar.recipient.late;

import io.seedmatic.rke2lab.benchcellar.Recipient;
import io.seedmatic.rke2lab.benchcellar.StandInCrypto;
import org.osgi.service.component.annotations.Component;

/**
 * The runtime addition — installed AFTER the clean is already sealing, to prove additivity: a
 * recipient joining the registry gets its own slot at the next seal, and no existing recipient's
 * slot or the ciphertext is disturbed. In its own bundle so the test controls the moment it
 * appears.
 */
@Component(service = Recipient.class, immediate = true, property = "recipient.id=carol")
public final class CarolRecipient implements Recipient {

  @Override
  public String id() {
    return "carol";
  }

  @Override
  public byte[] wrap(byte[] dataKey) {
    return StandInCrypto.wrap(dataKey, StandInCrypto.keyOf("carol"));
  }
}
