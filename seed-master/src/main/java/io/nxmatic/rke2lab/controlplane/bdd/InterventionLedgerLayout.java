package io.nxmatic.rke2lab.controlplane.bdd;

import io.nxmatic.rke2lab.pulumi.automation.PulumiBackendLayout;
import java.nio.file.Path;

/**
 * The single source of truth for the intervention-ledger stack's identity. The writer (C3, via the
 * Automation API) and the reader (C4, via {@link io.nxmatic.rke2lab.pulumi.automation.StackHandle})
 * resolve project, stack, and output key through here, so the two can never drift on which stack
 * holds the interventions — the same discipline as {@code SystemdUnitCatalog}.
 */
public final class InterventionLedgerLayout {

  public static final String PROJECT = "intervention-ledger";
  public static final String STACK = "dev";
  public static final String OUTPUT_KEY = "interventions";

  private InterventionLedgerLayout() {}

  /**
   * The ledger's own stacks directory under a file-backend root, with {@link #PROJECT} pre-bound.
   * Mirrors the reader path, which locates a stack through {@link
   * PulumiBackendLayout#stacksDir(Path, String)} (see {@code LiveMedicalRecordRegistry}).
   */
  public static Path stacksDir(Path backendDir) {
    return PulumiBackendLayout.stacksDir(backendDir, PROJECT);
  }
}
