package io.nxmatic.rk2lab.manifests;

import java.nio.file.Path;

/** Stage-A update gate contract owned by the manifests module. */
public interface ManifestUpdateGate {

  /** Stable gate identifier for diagnostics. */
  String gateId();

  /** Enforces manifests-specific update requirements before controlnode execution. */
  void enforce(Path worktreePath);
}
