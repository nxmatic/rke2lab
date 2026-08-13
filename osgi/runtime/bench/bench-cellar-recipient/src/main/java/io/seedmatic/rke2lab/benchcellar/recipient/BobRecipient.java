package io.seedmatic.rke2lab.benchcellar.recipient;

import io.seedmatic.rke2lab.benchcellar.Recipient;
import io.seedmatic.rke2lab.benchcellar.StandInCrypto;
import org.osgi.service.component.annotations.Component;

/** The boot roster's second identity — the twin of {@link AliceRecipient}. */
@Component(service = Recipient.class, immediate = true, property = "recipient.id=bob")
public final class BobRecipient implements Recipient {

  @Override
  public String id() {
    return "bob";
  }

  @Override
  public byte[] wrap(byte[] dataKey) {
    return StandInCrypto.wrap(dataKey, StandInCrypto.keyOf("bob"));
  }
}
