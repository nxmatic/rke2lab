package io.seedmatic.rke2lab.host.runtime;

import io.seedmatic.rke2lab.seed.broker.port.SecretsGateway;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The ambient execution environment — the instance that OWNS a process's environment and resolves
 * the runtime facts + projections that depend on it: the {@link ExecutionEnclosure} it runs in, and
 * (projected from that) the {@link SecretsGateway} chain it sources secrets from. An instance, not
 * a static helper: it holds the environment it reads, is passed through the call graph, and is
 * trivially substituted in a test (construct it over a fixed map). Future container-dependent
 * concerns (push auth, telemetry) add member methods here, resolved from the same held environment.
 *
 * <p>Enclosure detection is detect-but-let-yourself-be-contradicted: an explicit override ({@code
 * RKE2LAB_EXECUTION_ENCLOSURE=operator|in-cluster}) wins; otherwise the kubelet signal {@code
 * KUBERNETES_SERVICE_HOST} — injected into every pod, absent everywhere else — decides. The secrets
 * projection:
 *
 * <ul>
 *   <li>{@link ExecutionEnclosure#OPERATOR} → ndh's provisioned OAuth client ({@code tailscale})
 *       chained ahead of the operator's {@code .secrets} (everything else) — the standalone chain.
 *   <li>{@link ExecutionEnclosure#IN_CLUSTER} → an {@link EmptySecretsGateway}: an in-cluster
 *       render is structural (secret-blind), because all secret material rides the {@code
 *       NODE_BOOTSTRAP} lane and never reaches the reconciled branch a render pushes.
 * </ul>
 */
public final class ExecutionEnvironment {

  /** The override env var; when set to {@code operator} or {@code in-cluster} it wins. */
  public static final String OVERRIDE_ENV = "RKE2LAB_EXECUTION_ENCLOSURE";

  /** The kubelet-injected signal present in every pod, absent everywhere else. */
  public static final String KUBERNETES_SIGNAL = "KUBERNETES_SERVICE_HOST";

  private final Map<String, String> env;

  public ExecutionEnvironment(final Map<String, String> env) {
    this.env = Map.copyOf(env);
  }

  /** The enclosure this process runs in, resolved from the held environment. */
  public ExecutionEnclosure enclosure() {
    final String override = env.get(OVERRIDE_ENV);
    if (override != null && !override.isBlank()) {
      return switch (override.strip().toLowerCase(Locale.ROOT)) {
        case "in-cluster", "in_cluster", "cluster" -> ExecutionEnclosure.IN_CLUSTER;
        case "operator", "standalone", "local" -> ExecutionEnclosure.OPERATOR;
        default ->
            throw new IllegalArgumentException(
                OVERRIDE_ENV + " must be 'operator' or 'in-cluster', got: " + override);
      };
    }
    final String kubernetes = env.get(KUBERNETES_SIGNAL);
    return kubernetes != null && !kubernetes.isBlank()
        ? ExecutionEnclosure.IN_CLUSTER
        : ExecutionEnclosure.OPERATOR;
  }

  /** The secrets gateway chain this environment's enclosure sources its secrets from. */
  public SecretsGateway secretsGateway() {
    return switch (enclosure()) {
      case OPERATOR ->
          new ChainedSecretsGateway(
              List.of(new TailscaleOauthClientGateway(), new DotSecretsGateway()));
      case IN_CLUSTER -> new EmptySecretsGateway();
    };
  }
}
