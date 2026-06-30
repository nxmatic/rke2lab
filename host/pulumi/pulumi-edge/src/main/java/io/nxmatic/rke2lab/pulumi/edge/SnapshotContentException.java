package io.nxmatic.rke2lab.pulumi.edge;

/**
 * Content failure — the source was reachable but malformed or rejected. Retrying will not help; the
 * content itself is broken. The pure counterpart of the host {@code StackContentException}.
 */
public final class SnapshotContentException extends SnapshotException {

  private static final long serialVersionUID = 1L;

  public SnapshotContentException(String location, Throwable cause) {
    super(location, "snapshot content invalid at " + location, cause);
  }
}
