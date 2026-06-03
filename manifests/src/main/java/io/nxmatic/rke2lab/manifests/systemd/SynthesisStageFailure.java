package io.nxmatic.rke2lab.manifests.systemd;

final class SynthesisStageFailure extends RuntimeException {

  private final String topic;

  SynthesisStageFailure(String topic, Throwable cause) {
    super(topic + ": " + cause.getMessage(), cause);
    this.topic = topic;
  }

  String topic() {
    return topic;
  }
}
