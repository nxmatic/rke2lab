package io.seedmatic.rke2lab.pulumi.edge;

import java.nio.file.Path;

/**
 * Base exception for stack read failures. The two subclasses distinguish retry-worthy I/O failures
 * from never-retry-worthy content failures.
 */
public abstract class StackException extends Exception {

  private static final long serialVersionUID = 1L;

  private final transient Path path;

  protected StackException(Path path, String message, Throwable cause) {
    super(message, cause);
    this.path = path;
  }

  public Path path() {
    return path;
  }
}
