package io.nxmatic.rke2lab.manifests;

/** Canonical backend categories for host-asset delivery workflows. */
public enum HostAssetDeliveryBackend {
  BOOTSTRAP,
  DAEMONSET,
  CONTROLLER,
  GITOPS_TRIGGERED
}
