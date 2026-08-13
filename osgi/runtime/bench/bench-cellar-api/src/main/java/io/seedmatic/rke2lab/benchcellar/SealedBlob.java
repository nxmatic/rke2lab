package io.seedmatic.rke2lab.benchcellar;

import java.util.Map;

/**
 * The sops-shaped sealed payload: the ciphertext encrypted ONCE under a fresh data key, plus one
 * {@code slot} per recipient holding that data key wrapped for that identity. Any recipient unwraps
 * its own slot to recover the data key and decrypt the ciphertext — no shared passphrase, and
 * adding a recipient adds a slot without touching the ciphertext or the other slots.
 */
public record SealedBlob(Map<String, byte[]> slots, byte[] ciphertext) {

  public SealedBlob {
    slots = Map.copyOf(slots);
    ciphertext = ciphertext.clone();
  }

  @Override
  public byte[] ciphertext() {
    return ciphertext.clone();
  }
}
