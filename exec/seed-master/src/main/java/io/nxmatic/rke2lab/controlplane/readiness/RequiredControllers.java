package io.nxmatic.rke2lab.controlplane.readiness;

import io.nxmatic.rke2lab.cluster.port.ControllerRef;
import io.nxmatic.rke2lab.controlplane.policy.ControlplanePolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Projects the host {@link ControlplanePolicy} into the {@link ControllerRef}s a ready cluster must
 * have rolled out. This is a HOST projection by construction — it reads the host policy's
 * manifest-link flags and names the controllers each enables — so the cluster readiness reasoning
 * downstream sees only the projected refs, never the policy. That separation is what lets the
 * verifier be pure cluster reasoning (it reasons "are THESE refs effective?"), the host owning the
 * "which controllers does this policy require?" question.
 *
 * <p>A pure function of the policy, no state — the one place the controller catalog (which flag
 * enables which controller) is named.
 */
public final class RequiredControllers {

  private RequiredControllers() {}

  public static List<ControllerRef> from(ControlplanePolicy policy) {
    final ArrayList<ControllerRef> refs = new ArrayList<>();

    if (policy.manifestLink().highAvailabilityEnabled()) {
      refs.add(new ControllerRef("daemonset", "kube-vip-ds", "kube-vip"));
    }

    if (policy.manifestLink().networkingEnabled()) {
      refs.add(new ControllerRef("deployment", "cilium-operator", "kube-system"));
      refs.add(new ControllerRef("deployment", "kdns", "rke2lab-system"));
    }

    if (policy.manifestLink().platformEnabled()) {
      refs.add(new ControllerRef("deployment", "kubernetes-replicator", "kube-system"));
    }

    if (policy.manifestLink().storageEnabled()) {
      refs.add(new ControllerRef("deployment", "openebs-zfs-zfs-localpv-controller", "openebs"));
      refs.add(new ControllerRef("daemonset", "openebs-zfs-zfs-localpv-node", "openebs"));
    }

    if (policy.manifestLink().meshEnabled()) {
      refs.add(new ControllerRef("deployment", "headscale", "mesh-system"));
      refs.add(new ControllerRef("deployment", "headscale-gateway", "mesh-system"));
      refs.add(new ControllerRef("daemonset", "headscale-client", "mesh-system"));
      refs.add(new ControllerRef("deployment", "headplane", "mesh-system"));
    }

    return List.copyOf(refs);
  }
}
