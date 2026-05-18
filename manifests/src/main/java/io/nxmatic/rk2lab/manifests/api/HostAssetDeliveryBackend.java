package io.nxmatic.rk2lab.manifests.api;

/** Canonical backend categories for host-asset delivery workflows. */
public enum HostAssetDeliveryBackend {
  BOOTSTRAP,
  DAEMONSET,
  CONTROLLER,
  GITOPS_TRIGGERED
}
