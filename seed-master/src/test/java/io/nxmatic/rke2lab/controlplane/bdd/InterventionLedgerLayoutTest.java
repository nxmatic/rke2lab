package io.nxmatic.rke2lab.controlplane.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.nxmatic.rke2lab.pulumi.automation.PulumiBackendLayout;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class InterventionLedgerLayoutTest {

  @Test
  void project_names_the_intervention_ledger_stack() {
    assertEquals("intervention-ledger", InterventionLedgerLayout.PROJECT);
  }

  @Test
  void stack_is_dev() {
    assertEquals("dev", InterventionLedgerLayout.STACK);
  }

  @Test
  void output_key_is_interventions() {
    assertEquals("interventions", InterventionLedgerLayout.OUTPUT_KEY);
  }

  @Test
  void stacks_dir_delegates_to_pulumi_backend_layout_with_project_pre_bound() {
    final Path backendDir = Path.of("/tmp/whatever");
    assertEquals(
        PulumiBackendLayout.stacksDir(backendDir, "intervention-ledger"),
        InterventionLedgerLayout.stacksDir(backendDir));
  }
}
