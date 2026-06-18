package io.nxmatic.rke2lab.pulumi.automation;

import java.nio.file.Path;

/**
 * Base exception for stack read failures. The two subclasses distinguish retry-worthy I/O failures
 * from never-retry-worthy content failures.
 */
public abstract class StackException extends Exception {

  private final Path path;

  protected StackException(Path path, String message, Throwable cause) {
    super(message, cause);
    this.path = path;
  }

  public Path path() {
    return path;
  }
}
