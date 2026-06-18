package io.nxmatic.rke2lab.controlplane.bdd;

import com.pulumi.automation.LocalWorkspace;
import com.pulumi.automation.LocalWorkspaceOptions;
import com.pulumi.automation.ProjectBackend;
import com.pulumi.automation.ProjectRuntimeName;
import com.pulumi.automation.ProjectSettings;
import com.pulumi.automation.WorkspaceStack;
import java.nio.file.Path;
import java.util.Map;

/**
 * Writes interventions to the intervention-ledger Pulumi stack via the Automation API. Each {@link
 * #append(Intervention)} runs an out-of-run {@code up()} that registers a single {@link
 * InterventionResource} carrying the intervention's data. The per-resource output (NOT a top-level
 * export) persists the intervention as a new history entry in the stack.
 *
 * <p>The write mechanism mirrors the medical record's persistence contract: register per-resource
 * outputs under a component resource, bypassing top-level {@code ctx.export()} (which trips on
 * array-valued outputs post-up).
 */
public final class PulumiInterventionLedgerWriter implements InterventionLedgerWriter {

  // A file backend's default secrets provider requires a passphrase even when no secret is stored;
  // the value is immaterial for a ledger that holds no secrets.
  private static final String PASSPHRASE = "rke2lab-intervention-ledger";

  // A STABLE resource name, exactly like the medical record's SystemdAdapterResource
  // ("seed-systemd-adapter"). Accumulation is NOT many resources in one snapshot — it is the
  // history fold: each append() is one up() that writes a new history entry carrying this run's
  // intervention, and InterventionLedgerSource folds the timeline into one Intervention per entry.
  // So the name must NOT encode the intervention (that would churn delete+create every append for
  // no gain); the sequence lives in history, not in distinct resource names.
  private static final String RESOURCE_NAME = "intervention";

  private final Path backendDir;
  private final StackCoordinate coordinate;

  public PulumiInterventionLedgerWriter(Path backendDir, StackCoordinate coordinate) {
    this.backendDir = backendDir;
    this.coordinate = coordinate;
  }

  public PulumiInterventionLedgerWriter(Path backendDir) {
    this(backendDir, InterventionLedgerLayout.ledger());
  }

  @Override
  public void append(Intervention intervention) {
    final ProjectSettings projectSettings =
        ProjectSettings.builder(coordinate.project(), ProjectRuntimeName.JAVA)
            .backend(ProjectBackend.builder().url("file://" + backendDir).build())
            .build();

    final LocalWorkspaceOptions options =
        LocalWorkspaceOptions.builder()
            .projectSettings(projectSettings)
            .program(ctx -> new InterventionResource(RESOURCE_NAME, intervention.toOutputMap()))
            .environmentVariables(
                Map.of(
                    "PULUMI_BACKEND_URL",
                    "file://" + backendDir,
                    "PULUMI_CONFIG_PASSPHRASE",
                    PASSPHRASE))
            .build();

    try (WorkspaceStack stack =
        LocalWorkspace.createOrSelectStack(
            coordinate.project(), coordinate.stack(), options.program(), options)) {
      stack.up();
    } catch (Exception e) {
      throw new RuntimeException(
          "failed to append intervention to ledger "
              + coordinate.project()
              + "/"
              + coordinate.stack()
              + " under "
              + backendDir,
          e);
    }
  }
}
