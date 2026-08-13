package io.seedmatic.rke2lab.pulumi.edge;

/**
 * Base failure of the observation port: the snapshot that should have been readable could not be
 * read. The two subclasses preserve the host's distinction between retry-worthy access failures and
 * never-retry-worthy content failures, without leaking the backend's file types — the offending
 * source is named by an opaque {@code location} descriptor the adapter fills in.
 */
public abstract class SnapshotException extends Exception {

  private static final long serialVersionUID = 1L;

  private final String location;

  protected SnapshotException(String location, String message, Throwable cause) {
    super(message, cause);
    this.location = location;
  }

  /** A human-readable descriptor of where the unreadable snapshot came from. */
  public String location() {
    return location;
  }
}
