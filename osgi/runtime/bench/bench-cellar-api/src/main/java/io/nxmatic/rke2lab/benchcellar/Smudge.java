package io.nxmatic.rke2lab.benchcellar;

/**
 * The smudge filter — the coming-OUT-of-the-cellar half (git's {@code smudge}), bound to ONE
 * identity. It reveals a {@link SealedBlob} by unwrapping the slot addressed to its {@link #id()}
 * and decrypting the ciphertext with the recovered data key. A smudge only reaches the registry
 * when its identity is itself in the roster (a mandatory self-reference to its {@link Recipient}) —
 * an identity that is not a recipient never publishes a smudge, so it can never read. Revealing a
 * blob NOT addressed to this identity is a precondition violation, not a nullable outcome: it
 * throws.
 */
public interface Smudge {

  /** The single identity this smudge reads for. */
  String id();

  /** Reveal the blob for this identity. Throws if the blob carries no slot addressed to it. */
  String smudge(SealedBlob blob);
}
