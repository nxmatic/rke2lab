package io.seedmatic.rke2lab.dataplan.bdd;

import java.nio.file.Path;

/**
 * The dataplan layout export did not land — the {@code dataplan.json} is missing or empty. A typed
 * {@link AssertionError} carrying the target {@link Path} and the {@link Reason} as members so a
 * consumer knows what to inspect without parsing the message. (Unlike {@code NetplanExportError} it
 * is not {@code Symptomatic} — the dataplan export is not yet routed through the doctor's
 * readiness-checkpoint symptom wire; add a {@code SymptomKind} + mapping if it ever needs to be.)
 */
public final class DataplanExportError extends AssertionError {

  /** Which post-condition the export failed. */
  public enum Reason {
    MISSING,
    EMPTY
  }

  private final transient Path layoutFile;
  private final Reason reason;

  public DataplanExportError(Path layoutFile, Reason reason) {
    super(
        "the dataplan export "
            + (reason == Reason.MISSING ? "was not written" : "is empty")
            + ": "
            + layoutFile);
    this.layoutFile = layoutFile;
    this.reason = reason;
  }

  public Path layoutFile() {
    return layoutFile;
  }

  public Reason reason() {
    return reason;
  }
}
