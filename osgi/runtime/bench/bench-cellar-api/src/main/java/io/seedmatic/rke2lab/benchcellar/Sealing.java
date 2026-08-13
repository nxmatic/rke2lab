package io.seedmatic.rke2lab.benchcellar;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The seal step shared by every {@link Clean} implementation — the sops key-slot construction: one
 * fresh data key encrypts the payload once, then is wrapped once per recipient. Factored out so the
 * dynamic and static clean fixtures seal identically and differ ONLY in how they resolve their
 * roster (the thing the resolution-mode thesis contrasts).
 */
public final class Sealing {

  private Sealing() {}

  public static SealedBlob seal(String plaintext, List<Recipient> roster) {
    final byte[] dataKey = StandInCrypto.freshDataKey();
    final byte[] ciphertext = StandInCrypto.encrypt(plaintext, dataKey);
    final Map<String, byte[]> slots = new LinkedHashMap<>();
    for (Recipient recipient : roster) {
      slots.put(recipient.id(), recipient.wrap(dataKey));
    }
    return new SealedBlob(slots, ciphertext);
  }
}
