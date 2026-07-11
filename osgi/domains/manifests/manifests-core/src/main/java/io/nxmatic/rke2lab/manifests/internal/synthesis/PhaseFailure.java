package io.nxmatic.rke2lab.manifests.internal.synthesis;

/** Wraps any throw inside a synthesis phase's body, carrying the phase label for context. */
public final class PhaseFailure extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String phase;

  public PhaseFailure(String phase, Throwable cause) {
    super(phase + ": " + cause.getMessage(), cause);
    this.phase = phase;
  }

  public String phase() {
    return phase;
  }
}
