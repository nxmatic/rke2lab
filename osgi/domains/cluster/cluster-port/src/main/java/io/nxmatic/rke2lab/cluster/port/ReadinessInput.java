package io.nxmatic.rke2lab.cluster.port;

import java.nio.file.Path;
import java.time.Duration;

/**
 * The minimal input a cluster-readiness run reasons over: WHERE the kubeconfig is published and HOW
 * LONG each phase may wait for convergence. The host derives it from its fat bootstrap config; the
 * cluster reasoning sees only these two facts, never a host type — so it depends on nothing but
 * this port.
 *
 * @param kubeconfigPath the published kubeconfig, already absolute+normalized by the caller.
 * @param timeout the per-phase readiness deadline.
 */
public record ReadinessInput(Path kubeconfigPath, Duration timeout) {}
