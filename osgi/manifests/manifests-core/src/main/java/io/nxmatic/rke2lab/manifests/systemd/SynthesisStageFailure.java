package io.nxmatic.rke2lab.manifests.systemd;

final class SynthesisStageFailure extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String topic;

  SynthesisStageFailure(String topic, Throwable cause) {
    super(topic + ": " + cause.getMessage(), cause);
    this.topic = topic;
  }

  String topic() {
    return topic;
  }
}
