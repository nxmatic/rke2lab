package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.annotation.Quoted;
import io.nxmatic.rke2lab.controlplane.incus.BootstrapConfig;
import java.nio.file.Path;

/** Given stage: establishes the bootstrap config the probe will read. */
class GivenSystemdAdapter extends Stage<GivenSystemdAdapter> {

  @ProvidedScenarioState BootstrapConfig config;

  GivenSystemdAdapter an_incus_instance_named(@Quoted String nodeName) {
    // imageSharedFolder is required or build() throws; supply a throwaway path for the scenario.
    config =
        new BootstrapConfig.Builder()
            .nodeName(nodeName)
            .imageSharedFolder(Path.of("/tmp/rke2lab-bdd-shared"))
            .build();
    return self();
  }
}
