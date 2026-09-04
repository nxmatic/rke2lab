package io.seedmatic.rke2lab.manifests.units.runtime.flox;

/**
 * The GC-root category a flox env lives under on the node ({@code <base>/<folder>/<name>}) — the
 * single source for a {@code FloxEnv} CR's {@code spec.folder} and, conceptually, the {@code
 * flox.seedmatic.io/environment.<container>} pod annotation's {@code <folder>/} prefix that the
 * flox NRI plugin keys on. A typed vocabulary prevents the folder ↔ annotation-prefix mismatches
 * that would silently misroute a workload's env (the plugin would readlink a GC-root the controller
 * never provisioned).
 *
 * <p>Lives in {@code manifests-core} for now, consumed only by {@link FloxEnvManifestsUnit}. It
 * promotes to {@code manifests-contract} (with a spec) once the {@code "mesh/…"} / {@code
 * "networking/…"} compound strings in {@code FloxDebugPolicy.resolve*Environment} call sites are
 * unified to derive from it.
 */
public enum FloxEnvFolder {
  NETWORKING("networking"),
  MESH("mesh"),
  CICD("cicd"),
  // A cross-cutting toolchain tier — not a workload domain. kube/base carries the kube-API
  // scripting tools (kubectl + yq-go) that helper Jobs activate; domain envs will `[include]` it.
  KUBE("kube");

  private final String value;

  FloxEnvFolder(final String value) {
    this.value = value;
  }

  /** The on-node path segment (and annotation prefix), e.g. {@code "mesh"}. */
  public String value() {
    return value;
  }
}
