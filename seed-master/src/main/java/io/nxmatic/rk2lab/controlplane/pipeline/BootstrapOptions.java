package io.nxmatic.rk2lab.controlplane.pipeline;

public record BootstrapOptions(
    boolean readinessEnabled, boolean cleanWorktreeRequired, boolean bboxFailOnError) {

  public static Builder builder() {
    return new Builder();
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
