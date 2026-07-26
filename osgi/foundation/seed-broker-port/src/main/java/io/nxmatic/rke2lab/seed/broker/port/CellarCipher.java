package io.nxmatic.rke2lab.seed.broker.port;

/**
 * The cellar's clean/smudge filter — the seam that seals a payload on its way into the cellar and
 * reveals it on the way out, modelled on git's {@code clean}/{@code smudge} and refined by sops
 * (encryption <em>for recipients</em>). It runs OSGi-side, inside {@code CodecCellar}, BEFORE the
 * payload crosses to the host: the host backend ({@code PulumiCellar}) only ever persists the
 * opaque result, so the harvest plaintext never crosses the seam and the {@code OpaqueCellar}
 * contract ("never opens a payload") holds literally.
 *
 * <p>It is NOT a git edge and reads no {@code .sops.yaml}: the cellar is a store with its OWN
 * recipients (who may reveal a harvest — the seeding-cluster → seeded-cluster relation), a set
 * distinct from the git repository's sops recipients. An implementation takes its recipient /
 * identity config from a cellar-owned home. The degenerate single-local-recipient case is a
 * passphrase (the live master writes and reads with one identity); the multi-recipient
 * generalisation is age via {@code jagged} (a data key wrapped once per recipient — the sops
 * key-slot shape), the same seam, proven at {@code bench-cellar}. Pure-Java throughout — no CLI.
 *
 * <p>{@link #reveal} is self-describing: it is applied to every fetched payload and returns a
 * non-sealed value verbatim, so a {@link Sensitivity#PLAIN} store round-trips untouched and no flag
 * need be persisted to tell sealed from clear. See docs/architecture/atlas/cellar-secrets.adoc.
 */
public interface CellarCipher {

  /** Seal a plaintext payload into a self-identifying opaque form addressed to the recipients. */
  String seal(String plaintext);

  /** Reveal a sealed payload; a payload that does not carry the seal is returned unchanged. */
  String reveal(String payload);
}
