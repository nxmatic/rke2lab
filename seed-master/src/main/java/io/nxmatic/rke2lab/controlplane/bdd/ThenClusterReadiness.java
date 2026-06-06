package io.nxmatic.rke2lab.controlplane.bdd;

import com.tngtech.jgiven.Stage;

/**
 * Then stage: the cluster is ready. A failing phase throws in the When stage (fail-fast), so this
 * step is only ever reached once every phase passed — it is the readable closing assertion of the
 * scenario, not where phase evaluation happens. The failing phase's dossier (with its symptom) is
 * captured by the stage via the probe-holder seam, not read back through a stage getter (JGiven
 * intercepts public stage methods as steps), exactly as {@code SystemdAdapterStage} does.
 */
public class ThenClusterReadiness extends Stage<ThenClusterReadiness> {

  public ThenClusterReadiness the_cluster_is_ready() {
    return self();
  }
}
