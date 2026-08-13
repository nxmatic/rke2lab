package io.seedmatic.rke2lab.pulumi.edge;

/**
 * Access failure — the source was absent, unreadable, or denied. Retrying may succeed if the source
 * becomes available. The pure counterpart of the host {@code StackAccessException}.
 */
public final class SnapshotAccessException extends SnapshotException {

  private static final long serialVersionUID = 1L;

  public SnapshotAccessException(String location, Throwable cause) {
    super(location, "snapshot access failed at " + location, cause);
  }
}
