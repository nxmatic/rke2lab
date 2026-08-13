package io.seedmatic.rke2lab.doctor.contract;

import java.util.Map;

/**
 * A capability a scenario's readiness/verification failure carries so its recovery data is TYPED,
 * not flattened into the throwable's message. Implemented by each domain's {@link AssertionError}
 * subclass (which jGiven marks the step FAILED on): the failure additionally exposes a {@link
 * SymptomKind} — the doctor's symptom→specialist routing key — and an open {@code recoveryContext}
 * bag of producer-specific facts.
 *
 * <p>An interface, not a base class: {@code doctor-contract} is a pure-contract bundle (only
 * records / enums / interfaces / sealed ADTs may cross its exported surface), and a throwable base
 * would be a concrete class. So the shared shape is a capability the concrete {@code
 * AssertionError} subclasses implement, and a consumer reads it uniformly by pattern:
 *
 * <pre>{@code
 * if (throwable instanceof Symptomatic s) {
 *   route(s.symptom());            // typed routing key, no string-parsing
 *   inspect(s.recoveryContext());  // structured facts
 * }
 * }</pre>
 *
 * <p>A domain-aware consumer downcasts to the concrete subclass for its own typed member (the
 * systemd snapshot, the refused reservation rows, …). This is the in-realm twin of the {@link
 * ObservationWire} recovery channel the same failure records into the readiness-checkpoint: the
 * typed throwable serves a direct catcher, it does not replace the checkpoint.
 */
public interface Symptomatic {

  /** The typed routing key for the doctor's symptom→specialist dispatch. */
  SymptomKind symptom();

  /**
   * Producer-specific recovery facts, keyed; the typed twin of the observation's {@code details}.
   */
  Map<String, Object> recoveryContext();
}
