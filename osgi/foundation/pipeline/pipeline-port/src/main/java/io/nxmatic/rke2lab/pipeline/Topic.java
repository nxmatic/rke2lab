package io.nxmatic.rke2lab.pipeline;

/**
 * A fluent-pipeline topic: consumes a typed input {@code I} and produces a typed output {@code O}.
 *
 * <p>This is the orchestration contract — the only shape {@link FluentTopicRunner} ever sees. A
 * topic sees ONLY its frozen input {@code I}, never the pipeline context, so its output is a pure
 * function of {@code I}: given the same input a topic yields the same output, independent of any
 * runtime state accumulated elsewhere. That is what keeps a topic deterministic and testable in
 * isolation.
 *
 * <p>The two natures a topic depends on both arrive folded into {@code I}, assembled by the
 * transition (never by the topic):
 *
 * <ul>
 *   <li><b>flux</b> — data produced by an upstream topic. Carried by fields of {@code I}, built
 *       explicitly at the transition from the accumulated outputs. Compile-time, {@code @NonNull}
 *       by construction.
 *   <li><b>ambient</b> — transverse data known before the first topic (modes, services, shared
 *       charts). Read from the pipeline context by the transition, then placed into {@code I}.
 * </ul>
 *
 * <p>Because the context is touched only while a transition builds an input — read and write both
 * confined there — a topic has no handle to reopen it mid-run, so an input, once frozen, cannot
 * drift. See docs/architecture/patterns/fluent-pipeline-grammar.adoc.
 *
 * @param <I> the narrow input record this topic consumes
 * @param <O> the output record this topic produces
 */
@FunctionalInterface
public interface Topic<I, O> {

  /** Runs the topic against its frozen input, yielding its output. */
  O run(I inputs);
}
