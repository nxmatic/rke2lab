package io.nxmatic.rke2lab.seed.broker.port;

import java.util.List;
import java.util.Optional;

/**
 * The transactional view of a {@link Cellar} — the two flat-typed accessors a {@code
 * *RunbookHandler} needs to hand a sowed sub-scion the run's in-flight state: the transaction id
 * and the encoded entries. It is a SEAM sub-interface (system-exported, ONE class copy shared by
 * the host-flat realm and every bundle), so a handler may cast the neutral {@link Cellar} it
 * receives to THIS and cross the host↔bundle boundary safely.
 *
 * <p>Why a seam sub-interface, not the concrete cellar: the run's cellar realisation ({@code
 * ScenarioCellar}) is a dual REALM-LIBRARY — flat host-side, a bundle package in-container — so it
 * exists as TWO {@code Class} objects. Casting the crossed {@code Cellar} to that concrete class
 * throws {@code ClassCastException} (the flat copy is not the bundle copy — the
 * DUPLICATE_REALM_CLASS split). Both methods here return FLAT types ({@code Optional<String>},
 * {@code List<String>}), so they are seam-eligible: nothing about them requires the concrete realm
 * class to cross. A durable backend cellar ({@code PulumiCellar}, the recording/refusing test
 * doubles) is NOT transactional, so this stays a sub-interface rather than folding the two methods
 * onto {@link Cellar} — only the scenario cellar implements it.
 */
public interface TransactionalCellar extends Cellar {

  /**
   * The run's transaction id, or empty when this cellar is not transactional (a scenario played
   * outside a run). A handler passes it straight to a sub-sow: present ⇒ the sub-scion inherits the
   * tx; empty ⇒ a non-transactional play, no correlation.
   */
  Optional<String> transactionId();

  /**
   * This run's entries as flat encoded strings — handed DOWN to the {@code sownChild} sub-scion so
   * it inherits the transaction's in-flight stores. The child's crossing crumb is appended to the
   * run's provenance path (the {@code RUN_PROVENANCE} entry's {@link Trail}) as it descends, so a
   * value the child stores carries the full route {@code root → … → child → here} (§ fil-d-ariane,
   * the crossing path). Flat by construction, so nothing live crosses the launcher membrane.
   */
  List<String> entriesEncoded(SeedCoordinate sownChild);
}
