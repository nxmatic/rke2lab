package io.nxmatic.rke2lab.controlplane.pipeline;

public final class PipelineStageFailure extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String topic;

  public PipelineStageFailure(String topic, Throwable cause) {
    super(topic + ": " + cause.getMessage(), cause);
    this.topic = topic;
  }

  public String topic() {
    return topic;
  }
}
