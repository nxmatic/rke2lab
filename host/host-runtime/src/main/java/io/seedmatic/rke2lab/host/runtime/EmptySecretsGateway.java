package io.seedmatic.rke2lab.host.runtime;

import io.seedmatic.rke2lab.seed.broker.port.SecretsGateway;
import java.util.Optional;

/**
 * The secret-blind {@link SecretsGateway} — every read is empty. The {@link
 * ExecutionEnclosure#IN_CLUSTER} realisation: an in-cluster (Tekton) render is STRUCTURAL, it needs
 * no operator secret. All secret-bearing units ({@code GithubAppSecretManifestsUnit}, {@code
 * ReplicatorManifestsUnit}) render onto the {@code NODE_BOOTSTRAP} lane, which the exploder writes
 * OUTSIDE the git worktree ({@code NodeBootstrapArtifact}, a sibling one level above the tree) — so
 * it is never staged, committed, or pushed. The reconciled branch Flux tracks carries zero secret
 * bytes; an empty gateway makes those units seal nothing (their honest local skip) with no effect
 * on what the render delivers. The secret DATA already lives in-cluster (mittwald-replicated across
 * the namespaces), so nothing needs to descend from the operator's {@code .secrets}.
 */
public final class EmptySecretsGateway implements SecretsGateway {

  @Override
  public Optional<String> read(final String dottedPath) {
    return Optional.empty();
  }
}
