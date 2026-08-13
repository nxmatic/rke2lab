package io.seedmatic.rke2lab.benchcellar;

/**
 * A roster member — a public identity the {@link Clean} filter seals FOR. The clean binds every
 * published {@code Recipient} through a {@code @Reference(MULTIPLE)} and asks each to {@link
 * #wrap(byte[])} the run's data key, producing one key-slot per recipient (the sops shape). The
 * private counterpart lives on the {@link Smudge} that holds the same {@link #id()}: on the bench
 * both derive the same symmetric key from the id, so a recipient's slot is exactly what its smudge
 * can unwrap — the asymmetry is the only thing the stand-in fakes.
 */
public interface Recipient {

  /** The identity this recipient seals for; a {@link Smudge} with the same id can read its slot. */
  String id();

  /** Wrap the data key for this recipient — the key-slot only its {@link Smudge} unwraps. */
  byte[] wrap(byte[] dataKey);
}
