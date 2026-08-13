package io.seedmatic.rke2lab.seed.broker.port;

/**
 * The output sensitivity a scion declares when it files a harvest at the cellar — the OUTPUT twin
 * of the sops mark on the input. It is NOT traced from the input (Java has no taint, and a mark is
 * lost the moment a value flattens to a JSON string crossing the seam); it is RE-DECLARED where the
 * harvest is born, exactly as git needs a second {@code .gitattributes} line for a rendered file.
 * The whole envelope is the unit (like a git filter on a blob), so the grain is the value stored,
 * not a field within it.
 *
 * <p>Honoured OSGi-side by the {@code CellarCipher}: a {@link #SEALED} store is sealed before it
 * rides the run's write set and before it reaches the durable backend, so its plaintext never
 * crosses the seam; a {@link #PLAIN} store is filed in clear. Reveal needs no counterpart flag —
 * the sealed form self-identifies. See docs/architecture/atlas/cellar-secrets.adoc (§ Store &amp;
 * fetch).
 */
public enum Sensitivity {

  /** Filed in clear — the default: the harvest holds nothing that must stay secret at rest. */
  PLAIN,

  /** Sealed by the {@code CellarCipher} before it is stored and before it crosses the seam. */
  SEALED
}
