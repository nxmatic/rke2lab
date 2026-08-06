package io.nxmatic.rke2lab.netplan.bdd;

import io.nxmatic.rke2lab.doctor.contract.SymptomKind;
import io.nxmatic.rke2lab.doctor.contract.Symptomatic;
import java.nio.file.Path;
import java.util.Map;

/**
 * The netplan blueprint export did not land — the {@code blueprint.json} is missing or empty.
 * {@link Symptomatic}: carries the target {@link Path} and the {@link Reason} as typed members so a
 * consumer knows what to inspect without parsing the message.
 */
public final class NetplanExportError extends AssertionError implements Symptomatic {

  /** Which post-condition the export failed. */
  public enum Reason {
    MISSING,
    EMPTY
  }

  private final transient Path blueprintFile;
  private final Reason reason;

  public NetplanExportError(Path blueprintFile, Reason reason) {
    super(
        "the blueprint export "
            + (reason == Reason.MISSING ? "was not written" : "is empty")
            + ": "
            + blueprintFile);
    this.blueprintFile = blueprintFile;
    this.reason = reason;
  }

  @Override
  public SymptomKind symptom() {
    return SymptomKind.BLUEPRINT_EXPORT_FAILED;
  }

  @Override
  public Map<String, Object> recoveryContext() {
    return Map.of("blueprintFile", blueprintFile.toString(), "reason", reason.name());
  }

  public Path blueprintFile() {
    return blueprintFile;
  }

  public Reason reason() {
    return reason;
  }
}
