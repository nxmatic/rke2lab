package io.nxmatic.rke2lab.manifests.internal.synthesis;

/**
 * A synthesis phase — an IDENTIFICATION contract, not an orchestration one. A phase is a fluent
 * multi-verb builder (each verb returns {@code this} and pushes its part of the work through a
 * sink); it is NOT a {@code O run(I)} mono-shot. So this interface does not prescribe HOW a phase
 * runs — {@link PhaseRunner#runDuring} drives the body as a {@code Function<S,S>} — it only lets
 * any phase be recognized: its NATURE ({@link Execution}) and its {@link #role()} (its place in the
 * pipeline). That recognition is what the {@code SYNTHESIS_PATTERN} governance gate reads.
 *
 * <p>The value-threading synthesis core: a phase does a gesture and pushes its output through its
 * {@link Sink}, and a {@code during/then} chain folds those pushes into an accumulator. It is
 * jGiven-free by construction — narration and verdict belong to the BDD engine, never here.
 *
 * <h2>The write-face: {@link Sink}</h2>
 *
 * <p>A phase writes its output by pushing into a sink, never by holding a reference to the
 * pipeline's accumulator. {@code Phase.Sink} is nested here — a sink is the write-face OF a phase,
 * so it is named by the phase, not a free-standing type. It is a marker: no verb is common to all
 * sinks, each phase declares its own {@code <ThatPhase>.Sink extends Phase.Sink} carrying the verbs
 * for its own outputs. The sink is IMPLEMENTATION the transition wires ({@code new Phase(input,
 * sink)}); it never appears on the phase's fluent API. Because a phase sees only its frozen input
 * and its sink — never the accumulator — it is deterministic and testable in isolation: give it an
 * input and a throwaway sink, assert what it pushes.
 *
 * <p>This is manifests-core's own synthesis grammar — the fluent machinery the host once shared as
 * a {@code pipeline} seam, now internal to its sole surviving consumer.
 */
public interface Phase {

  /** This phase's place in the pipeline — a short human-readable label, e.g. {@code "path"}. */
  String role();

  /**
   * The write-face of a phase — a marker. Each phase declares its own {@code Sink extends
   * Phase.Sink} with the verbs for its outputs; the transition supplies an implementation that
   * folds those pushes into the pipeline accumulator.
   */
  interface Sink {}

  /** An execution phase — does a gesture and pushes its output through its {@link Sink}. */
  interface Execution extends Phase {}
}
