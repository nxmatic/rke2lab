package io.nxmatic.rke2lab.controlplane.pipeline;

import io.nxmatic.rke2lab.controlplane.config.Rke2labConfig;

public record BootstrapOptions(
    boolean readinessEnabled, boolean cleanWorktreeRequired, boolean bboxFailOnError) {

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Derive the bootstrap options from the root config DTO (defaults true, matching the prior gate).
   */
  public static BootstrapOptions from(Rke2labConfig config) {
    return builder()
        .readinessEnabled(config.readiness().enabled().orElse(true))
        .cleanWorktreeRequired(config.entryGate().cleanWorktreeRequired().orElse(true))
        .bboxFailOnError(config.bbox().failOnError().orElse(true))
        .build();
  }

  public static final class Builder {
    private boolean readinessEnabled = true;
    private boolean cleanWorktreeRequired = true;
    private boolean bboxFailOnError = true;

    public Builder readinessEnabled(boolean value) {
      this.readinessEnabled = value;
      return this;
    }

    public Builder cleanWorktreeRequired(boolean value) {
      this.cleanWorktreeRequired = value;
      return this;
    }

    public Builder bboxFailOnError(boolean value) {
      this.bboxFailOnError = value;
      return this;
    }

    public BootstrapOptions build() {
      return new BootstrapOptions(readinessEnabled, cleanWorktreeRequired, bboxFailOnError);
    }
  }
}
