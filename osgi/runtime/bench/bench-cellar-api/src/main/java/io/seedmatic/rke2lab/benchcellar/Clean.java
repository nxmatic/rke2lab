package io.seedmatic.rke2lab.benchcellar;

/**
 * The clean filter — the going-INTO-the-cellar half (git's {@code clean}). It binds the whole
 * roster through a {@code @Reference(MULTIPLE)} over {@link Recipient} and seals a plaintext FOR
 * ALL of them at once: one ciphertext under a fresh data key, one wrapped slot per recipient. The
 * roster it sealed for is exactly the set bound at the seal call — so a recipient added to the
 * registry before the call gets a slot, with no shared secret between them.
 */
public interface Clean {

  /** Seal a plaintext addressed to every currently-bound {@link Recipient}. */
  SealedBlob clean(String plaintext);

  /** The ids sealed for at the last {@link #clean(String)} — the roster the blob addresses. */
  java.util.Set<String> lastRoster();
}
