// @codebase
package io.seedmatic.rke2lab.manifests.contract;

/**
 * The SINGLE SOURCE of the flox pod-injection annotation keys — the contract the flox NRI plugin
 * and the flox-controller pod-mutating webhook key on to inject a container's flox runtime. Every
 * rke2lab manifest that annotates a pod for flox references a constant of this enum (never a string
 * literal), so the {@code flox.seedmatic.io} namespace and the per-capability keys are defined
 * once.
 *
 * <p>Annotations are PER-CONTAINER: the emitted key is {@code <key>.<container-name>} (build it
 * with {@link #forContainer}). There is NO bare-key fallback — each container opts in by name.
 *
 * <p>The Go readers (flox-nri-plugin, flox-controller) carry their own copies of these strings —
 * two languages can't share a constant — so the values here are a cross-repo contract; keep them in
 * sync with the plugin's {@code plugin.go} consts and the webhook's {@code podflox.go} consts.
 */
public enum FloxAnnotation {

  /** {@code <key>.<c> = "<category>/<name>"} — opt container {@code c} into a flox env. */
  ENVIRONMENT("environment"),

  /** {@code <key>.<c>} — override the container's HOME (where {@code .flox} is materialised). */
  HOME("home"),

  /** {@code <key>.<c>} — desired UID for flox env ownership (default 0). */
  UID("uid"),

  /** {@code <key>.<c>} — desired GID for flox env ownership (default 0). */
  GID("gid"),

  /** {@code <key>.<c> = "true"} — pause the container for a debugger. */
  DEBUG("debug"),

  /** {@code <key>.<c>} — delve port for the debug pause (default 2345). */
  DEBUG_PORT("debug-port"),

  /**
   * {@code <key>.<c> = "<pvc-name>"} — opt container {@code c} into the nix-build runtime. The
   * webhook ensures that PVC + mounts it at {@link #NIX_BUILD_STORE_MOUNT}; the NRI plugin hosts
   * the {@code /nix} store overlay's upper/work there and puts {@code nix} on PATH. The named PVC
   * is the step's persistent store, reused across its task runs (a warm cache).
   */
  NIX_BUILD("nix-build");

  private static final String NAMESPACE = "flox.seedmatic.io/";

  /**
   * The container-absolute path the webhook mounts the nix-build store PVC at, and the NRI plugin's
   * overlay upper-backing — a cross-repo contract with flox-nri-plugin ({@code nixBuildStoreMount})
   * and flox-controller ({@code nixBuildStoreMount}). Internal, not user-facing.
   */
  public static final String NIX_BUILD_STORE_MOUNT = "/var/lib/flox-nri/nix-build-store";

  private final String key;

  FloxAnnotation(final String suffix) {
    this.key = NAMESPACE + suffix;
  }

  /** The bare annotation key (namespace + suffix), e.g. {@code flox.seedmatic.io/environment}. */
  public String key() {
    return key;
  }

  /** The per-container annotation key {@code <key>.<containerName>}. */
  public String forContainer(final String containerName) {
    return key + "." + containerName;
  }
}
