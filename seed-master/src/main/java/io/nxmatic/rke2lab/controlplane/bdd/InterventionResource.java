package io.nxmatic.rke2lab.controlplane.bdd;

import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import java.util.Map;

/**
 * Inert component resource representing a single intervention in the intervention-ledger stack. It
 * registers no children and touches no real infrastructure — it exists solely to persist an
 * intervention's data as a per-resource output under {@link InterventionLedgerLayout#OUTPUT_KEY}.
 *
 * <p>The resource name is stable across appends (it does not encode the intervention): accumulation
 * is the history fold, one entry per {@code up()}, exactly as the medical record accumulates Visits
 * under {@code SystemdAdapterResource}'s stable name. The sequence lives in history, not in
 * distinct resource names.
 */
final class InterventionResource extends ComponentResource {

  private static final String TYPE_TOKEN = "rke2lab:controlplane:Intervention";

  InterventionResource(String name, Map<String, Object> data) {
    super(TYPE_TOKEN, name);
    registerOutputs(Map.of(InterventionLedgerLayout.OUTPUT_KEY, Output.of(data)));
  }
}
