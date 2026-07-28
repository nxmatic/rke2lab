package io.nxmatic.rke2lab.benchcellar.recipient;

import io.nxmatic.rke2lab.benchcellar.Recipient;
import io.nxmatic.rke2lab.benchcellar.StandInCrypto;
import org.osgi.service.component.annotations.Component;

/**
 * The boot roster's first identity. Publishes {@code recipient.id=alice} as a service property, so
 * the {@link io.nxmatic.rke2lab.benchcellar.Clean} binds it by MULTIPLE and the alice smudge
 * targets its own roster entry by that property.
 */
@Component(service = Recipient.class, immediate = true, property = "recipient.id=alice")
public final class AliceRecipient implements Recipient {

  @Override
  public String id() {
    return "alice";
  }

  @Override
  public byte[] wrap(byte[] dataKey) {
    return StandInCrypto.wrap(dataKey, StandInCrypto.keyOf("alice"));
  }
}
