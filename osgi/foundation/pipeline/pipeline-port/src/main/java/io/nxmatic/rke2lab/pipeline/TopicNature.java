package io.nxmatic.rke2lab.pipeline;

/**
 * The nature of a {@link Topic}'s body — an identification aid, not an orchestration distinction
 * (the {@link FluentTopicRunner} treats every topic uniformly). Naming the nature makes a topic's
 * role in the pipeline readable at a glance and guides decomposition.
 *
 * <ul>
 *   <li>{@link #EXECUTION} — does a gesture and pushes an output through its sink. Most topics; a
 *       plain {@link Topic}, no base needed.
 *   <li>{@link #CHECKPOINT} — runs a jGiven scenario to produce a narrative + verdict, for the
 *       parts an operator must read. Its base (which hosts the scenario ceremony) lives where
 *       jGiven lives, not in this pure port.
 *   <li>{@link #PIPELINE} — its body is itself a {@code during/then} chain with its own local
 *       accumulator, sharing the parent's {@link PipelineContext}. ("Sub" is implicit in the
 *       nesting.)
 * </ul>
 *
 * <p>See docs/architecture/patterns/fluent-pipeline-grammar.adoc ("The three topic-body natures").
 */
public enum TopicNature {
  EXECUTION,
  CHECKPOINT,
  PIPELINE
}
