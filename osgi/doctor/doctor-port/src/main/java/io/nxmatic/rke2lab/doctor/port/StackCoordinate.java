package io.nxmatic.rke2lab.doctor.port;

/**
 * The typed identity of a Pulumi stack — a pair of project and stack names. This prevents the
 * parameter-swap bug where {@code LocalWorkspace.createOrSelectStack(projectName, stackName, …)}
 * silently accepted swapped arguments because both are strings. Always construct via {@link
 * #builder()} to ensure named parameters prevent project/stack swap. Always source the ledger's
 * coordinate from {@link InterventionLedgerLayout#ledger()} (single source of truth).
 */
public record StackCoordinate(String project, String stack) {

  public StackCoordinate {
    if (project == null || project.isBlank()) {
      throw new IllegalArgumentException("StackCoordinate project cannot be null or blank");
    }
    if (stack == null || stack.isBlank()) {
      throw new IllegalArgumentException("StackCoordinate stack cannot be null or blank");
    }
    project = project.trim();
    stack = stack.trim();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String project;
    private String stack;

    public Builder project(String project) {
      this.project = project;
      return this;
    }

    public Builder stack(String stack) {
      this.stack = stack;
      return this;
    }

    public StackCoordinate build() {
      return new StackCoordinate(project, stack);
    }
  }
}
