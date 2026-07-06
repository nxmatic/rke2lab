package io.nxmatic.rke2lab.controlplane.bdd;

/**
 * How this seed run executes: live (bodies run) or preview (bodies rendered PENDING, no traversal).
 *
 * <p>The component is {@code previewMode} (not {@code preview}) so its accessor does not collide
 * with the {@link #preview()} factory — a record's implicit {@code preview()} accessor and a {@code
 * static preview()} factory cannot coexist.
 */
public record RunMode(boolean pulumiMode, boolean previewMode) {

  public static RunMode live(boolean pulumiMode) {
    return new RunMode(pulumiMode, false);
  }

  public static RunMode preview() {
    return new RunMode(true, true);
  }
}
