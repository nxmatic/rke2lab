package io.seedmatic.rke2lab.bbox.bdd;

import io.seedmatic.rke2lab.bbox.contract.BboxRowOutcome;
import io.seedmatic.rke2lab.doctor.contract.SymptomKind;
import io.seedmatic.rke2lab.doctor.contract.Symptomatic;
import java.util.List;
import java.util.Map;

/**
 * The router refused one or more reservation rows. {@link Symptomatic}: carries the refused {@link
 * BboxRowOutcome}s as a typed member (each with its own node/mac/failureMessage) so a consumer can
 * act per row, not just on a joined reason string.
 */
public final class BboxReservationError extends AssertionError implements Symptomatic {

  private final transient List<BboxRowOutcome> refusedRows;

  public BboxReservationError(List<BboxRowOutcome> refusedRows, String reasons) {
    super(refusedRows.size() + " reservation row(s) refused by the router — " + reasons);
    this.refusedRows = List.copyOf(refusedRows);
  }

  @Override
  public SymptomKind symptom() {
    return SymptomKind.RESERVATION_REFUSED;
  }

  @Override
  public Map<String, Object> recoveryContext() {
    return Map.of("refusedCount", refusedRows.size());
  }

  public List<BboxRowOutcome> refusedRows() {
    return refusedRows;
  }
}
