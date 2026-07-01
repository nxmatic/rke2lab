package io.nxmatic.rke2lab.pulumi.edge;

import org.jspecify.annotations.Nullable;

/**
 * The typed identity of a Pulumi stack — a pair of project and stack names. This prevents the
 * parameter-swap bug where {@code LocalWorkspace.createOrSelectStack(projectName, stackName, …)}
 * silently accepted swapped arguments because both are strings. Always construct via {@link
 * #builder()} to ensure named parameters prevent project/stack swap. Always source the ledger's
 * coordinate from {@link InterventionLedgerLayout#ledger()} (single source of truth).
 */
public record StackCoordinate(String project, String stack) {

  public StackCoordinate {
    if (project.isBlank()) {
      throw new IllegalArgumentException("StackCoordinate project cannot be blank");
    }
    if (stack.isBlank()) {
      throw new IllegalArgumentException("StackCoordinate stack cannot be blank");
    }
    project = project.trim();
    stack = stack.trim();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private @Nullable String project;
    private @Nullable String stack;

    public Builder project(String project) {
      this.project = project;
      return this;
    }

    public Builder stack(String stack) {
      this.stack = stack;
      return this;
    }

    public StackCoordinate build() {
      if (project == null) {
        throw new IllegalArgumentException("StackCoordinate project must be set");
      }
      if (stack == null) {
        throw new IllegalArgumentException("StackCoordinate stack must be set");
      }
      return new StackCoordinate(project, stack);
    }
  }
}
