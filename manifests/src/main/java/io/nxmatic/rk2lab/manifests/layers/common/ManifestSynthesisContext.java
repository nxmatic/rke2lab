// @codebase
package io.nxmatic.rk2lab.manifests.layers.common;

import io.nxmatic.rk2lab.manifests.layers.common.profiles.FloxDebugPolicy;
import java.util.Objects;

/**
 * Synthesis-scoped context exposing per-synth policies (e.g., {@link FloxDebugPolicy}) to layer
 * code. The synthesizer publishes the context for the duration of {@code synthesize(...)} and
 * resets it on exit; layers reach it through {@link AbstractManifestUnit#floxDebugPolicy()} (or
 * their own equivalent accessor) without keeping a static dependency on a process-wide singleton.
 *
 * <p>When no synthesis is in progress (e.g., direct unit tests of a Layer without going through
 * {@code synthesize}), {@link #current()} returns a default context with {@link
 * FloxDebugPolicy#disabled()}, matching the production shape.
 */
public final class ManifestSynthesisContext {

  private static final ManifestSynthesisContext DEFAULT =
      new ManifestSynthesisContext(FloxDebugPolicy.disabled());

  private static final ThreadLocal<ManifestSynthesisContext> CURRENT = new ThreadLocal<>();

  private final FloxDebugPolicy floxDebugPolicy;

  private ManifestSynthesisContext(FloxDebugPolicy floxDebugPolicy) {
    this.floxDebugPolicy = Objects.requireNonNull(floxDebugPolicy, "floxDebugPolicy");
  }

  public static ManifestSynthesisContext of(FloxDebugPolicy floxDebugPolicy) {
    return new ManifestSynthesisContext(floxDebugPolicy);
  }

  public static ManifestSynthesisContext current() {
    final ManifestSynthesisContext current = CURRENT.get();
    return current != null ? current : DEFAULT;
  }

  /**
   * Publishes {@code context} for the calling thread. Returns an {@link AutoCloseable} that
   * restores the previous binding (which may be the default) when closed — wrap in
   * try-with-resources to keep the scope tight and exception-safe.
   */
  public static Scope bind(ManifestSynthesisContext context) {
    final ManifestSynthesisContext previous = CURRENT.get();
    CURRENT.set(Objects.requireNonNull(context, "context"));
    return new Scope(previous);
  }

  public FloxDebugPolicy floxDebugPolicy() {
    return floxDebugPolicy;
  }

  /** Restores the previous binding when closed. */
  public static final class Scope implements AutoCloseable {
    private final ManifestSynthesisContext previous;

    private Scope(ManifestSynthesisContext previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }
}
