package io.seedmatic.rke2lab.pulumi.edge;

import java.nio.file.Path;

/**
 * Invalid content — malformed JSON, missing required fields, or rejected by StackDeployment.
 * Retrying will not help; the content itself is broken.
 */
public final class StackContentException extends StackException {

  private static final long serialVersionUID = 1L;

  public StackContentException(Path path, Throwable cause) {
    super(path, "stack content invalid at " + path, cause);
  }
}
