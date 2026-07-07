package io.nxmatic.rke2lab.controlplane.bdd;

/**
 * Thrown by a phase whose checkpoint failed with a STOP verdict — it propagates out of the scenario
 * to abort the seed (the readiness authority judged the failure fatal). A CONTINUE_DEGRADED verdict
 * does NOT throw this: the phase records a degraded observation and the seed proceeds. Carries the
 * checkpoint label for context.
 */
public final class SeedAborted extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String checkpoint;

  public SeedAborted(String checkpoint, Throwable cause) {
    super(checkpoint + ": " + cause.getMessage(), cause);
    this.checkpoint = checkpoint;
  }

  public String checkpoint() {
    return checkpoint;
  }
}
