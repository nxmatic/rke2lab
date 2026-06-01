package io.nxmatic.rk2lab.manifests.systemd;

public final class SynthesisStageFailure extends RuntimeException {

  private final String topic;

  public SynthesisStageFailure(String topic, Throwable cause) {
    super(topic + ": " + cause.getMessage(), cause);
    this.topic = topic;
  }

  public String topic() {
    return topic;
  }
}
