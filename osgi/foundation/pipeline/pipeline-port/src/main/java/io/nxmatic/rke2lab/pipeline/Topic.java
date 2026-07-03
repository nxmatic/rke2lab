package io.nxmatic.rke2lab.pipeline;

/**
 * A fluent-pipeline topic — an IDENTIFICATION contract, not an orchestration one. A topic is a
 * fluent multi-verb builder (each verb returns {@code this} and pushes its part of the work through
 * a sink); it is NOT a {@code O run(I)} mono-shot. So this interface does not prescribe HOW a topic
 * runs — {@link FluentTopicRunner#runDuring} drives the body as a {@code Function<S,S>} — it only
 * lets any topic be recognized: its {@link #nature()} (how its body is shaped) and its {@link
 * #role()} (its place in the pipeline). That recognition is what the governance gate and the
 * retrofit tooling read.
 *
 * <h2>The write-face: {@link Sink}</h2>
 *
 * <p>A topic writes its output by pushing into a sink, never by holding a reference to the
 * pipeline's accumulator. {@code Topic.Sink} is nested here — a sink is the write-face OF a topic,
 * so it is named by the topic, not a free-standing type. It is a marker: no verb is common to all
 * sinks, each topic declares its own {@code <ThatTopic>.Sink extends Topic.Sink} carrying the verbs
 * for its own outputs. The sink is IMPLEMENTATION the transition wires ({@code new Stage(input,
 * sink)}); it never appears on the topic's fluent API. Because a topic sees only its frozen input
 * and its sink — never the accumulator, never the {@link PipelineContext} — it is deterministic and
 * testable in isolation: give it an input and a throwaway sink, assert what it pushes.
 *
 * <p>See docs/architecture/patterns/fluent-pipeline-grammar.adoc.
 */
public interface Topic {

  /**
   * How this topic's body is shaped. Defaults to {@link TopicNature#EXECUTION}, the common case.
   */
  default TopicNature nature() {
    return TopicNature.EXECUTION;
  }

  /** This topic's place in the pipeline — a short human-readable label, e.g. {@code "path"}. */
  String role();

  /**
   * The write-face of a topic — a marker. Each topic declares its own {@code Sink extends
   * Topic.Sink} with the verbs for its outputs; the transition supplies an implementation that
   * folds those pushes into the pipeline accumulator.
   */
  interface Sink {}
}
