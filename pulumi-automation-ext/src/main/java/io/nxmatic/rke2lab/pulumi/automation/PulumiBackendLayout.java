package io.nxmatic.rke2lab.pulumi.automation;

import java.nio.file.Path;

/**
 * The single source of truth for the Pulumi file-backend on-disk layout. Both the reader ({@link
 * StackHistory}) and any writer (test fixtures that lay down a backend) resolve paths through here,
 * so the {@code .pulumi/history/<project>/<stack>} convention is encoded exactly once.
 */
public final class PulumiBackendLayout {

  private PulumiBackendLayout() {}

  /** The history directory for a stack under a file-backend root. */
  public static Path historyDir(Path backendDir, String project, String stack) {
    return backendDir.resolve(".pulumi/history").resolve(project).resolve(stack);
  }
}
