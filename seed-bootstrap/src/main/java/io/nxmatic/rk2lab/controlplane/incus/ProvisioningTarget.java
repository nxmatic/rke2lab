package io.nxmatic.rk2lab.controlplane.incus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A provisioning target — a downstream consumer that reads files we materialize and has its own
 * reload protocol.
 *
 * <p>Each target knows:
 *
 * <ul>
 *   <li>What to materialize (the inputs the consumer reads)
 *   <li>Its reload policy (STATIC → instance config, DYNAMIC → reconciler)
 *   <li>Its materialized paths (for checksum computation)
 * </ul>
 *
 * <p>Known targets:
 *
 * <ul>
 *   <li><b>cloud-init</b>: First-boot consumer of {@code /var/lib/cloud/seed/nocloud/} — STATIC
 *   <li><b>systemd</b>: Loads units from {@code /srv/host/systemd-{units,scripts,libexec}.d/} —
 *       DYNAMIC via daemon-reload
 *   <li><b>k8s</b>: rke2-server inotifies {@code /var/lib/rancher/rke2/server/manifests/} —
 *       DYNAMIC via manifest watch
 *   <li><b>rke2-config</b>: rke2-server reads ConfigMaps from {@code manifestsRoot/runtime/rke2-config/}
 *       at startup (etcd flags, advertise-address, TLS-SAN, …) — DYNAMIC
 *   <li><b>rke2lab-env</b>: {@code rke2lab-bootstrap-env.sh}'s flox profile-common.sh sources YAMLs
 *       under {@code manifestsRoot/runtime/env-config/} and turns their {@code data:} keys into
 *       shell environment variables — DYNAMIC
 * </ul>
 */
public interface ProvisioningTarget {

  /** Unique target identifier (e.g. "cloud-init", "systemd", "k8s", "rke2-config", "rke2lab-env"). */
  String name();

  /** Reload policy determines lifecycle behavior on checksum change. */
  TargetReloadPolicy reloadPolicy();

  /**
   * Materialize this target's inputs to the filesystem.
   *
   * @param paths bootstrap filesystem paths
   * @throws IOException if materialization fails
   */
  void materialize(IncusResourceBootstrap.BootstrapPaths paths) throws IOException;

  /**
   * Paths materialized by this target, used for checksum computation.
   *
   * @return immutable list of filesystem paths
   */
  List<Path> getMaterializedPaths();
}
