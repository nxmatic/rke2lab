package io.nxmatic.rke2lab.pipeline;

/**
 * A fluent-pipeline topic — an IDENTIFICATION contract, not an orchestration one. A topic is a
 * fluent multi-verb builder (each verb returns {@code this} and pushes its part of the work through
 * a sink); it is NOT a {@code O run(I)} mono-shot. So this interface does not prescribe HOW a topic
 * runs — {@link FluentTopicRunner#runDuring} drives the body as a {@code Function<S,S>} — it only
 * lets any topic be recognized: its NATURE (which nested sub-interface it implements) and its
 * {@link #role()} (its place in the pipeline). That recognition is what the governance gate and the
 * retrofit tooling read.
 *
 * <h2>Nature is a type, not a value</h2>
 *
 * <p>A topic's nature is carried by which nested interface it implements — {@link Execution},
 * {@link Checkpoint}, {@link Pipeline} — not by a returned enum. Governance and retrofit test it at
 * compile time ({@code topic instanceof Topic.Checkpoint}) rather than switching on a stringly
 * value. The three are the exhaustive body shapes:
 *
 * <ul>
 *   <li>{@link Execution} — does a gesture and pushes its output through its {@link Sink}. Most
 *       topics.
 *   <li>{@link Checkpoint} — plays a jGiven scenario to produce a narrative + verdict, for the
 *       parts an operator must read.
 *   <li>{@link Pipeline} — its body is itself a {@code during/then} chain with its own local
 *       accumulator, sharing the parent's {@link PipelineContext}. ("Sub" is implicit in the
 *       nesting.)
 * </ul>
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

  /** This topic's place in the pipeline — a short human-readable label, e.g. {@code "path"}. */
  String role();

  /**
   * The write-face of a topic — a marker. Each topic declares its own {@code Sink extends
   * Topic.Sink} with the verbs for its outputs; the transition supplies an implementation that
   * folds those pushes into the pipeline accumulator.
   */
  interface Sink {}

  /** An execution topic — does a gesture and pushes its output through its {@link Sink}. */
  interface Execution extends Topic {}

  /**
   * A checkpoint topic — plays a jGiven scenario to produce a narrative + verdict. The base stays
   * jGiven-free (a topic is recognized, not scripted, here); the concrete checkpoint hosts the
   * scenario where jGiven lives.
   */
  interface Checkpoint extends Topic {}

  /**
   * A pipeline topic — its body is itself a {@code during/then} chain with its own local
   * accumulator, sharing the parent's {@link PipelineContext}.
   */
  interface Pipeline extends Topic {}
}
