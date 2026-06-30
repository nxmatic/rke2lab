package io.nxmatic.rke2lab.pipeline;

/** Wraps any throw inside a pipeline topic's body, carrying the topic label for context. */
public final class TopicFailure extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String topic;

  public TopicFailure(String topic, Throwable cause) {
    super(topic + ": " + cause.getMessage(), cause);
    this.topic = topic;
  }

  public String topic() {
    return topic;
  }
}
