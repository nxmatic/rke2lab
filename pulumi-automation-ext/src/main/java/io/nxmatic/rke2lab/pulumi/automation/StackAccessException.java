package io.nxmatic.rke2lab.pulumi.automation;

import java.nio.file.Path;

/**
 * I/O access failure — file/directory absent, unreadable, or permission denied. Retrying may
 * succeed if the resource becomes available.
 */
public final class StackAccessException extends StackException {

  public StackAccessException(Path path, Throwable cause) {
    super(path, "stack access failed at " + path, cause);
  }
}
