package io.nxmatic.rke2lab.cluster.port;

/**
 * A neutral reference to a control-plane controller the readiness gate waits on — a Kubernetes
 * workload identified by {@code kind} (deployment/daemonset), {@code name}, and {@code namespace}.
 *
 * <p>It is the contact vocabulary, free of any host policy type: the host projects its {@code
 * ControlplanePolicy} into a {@code List<ControllerRef>} and hands them to the edge, which does
 * pure kubectl over the refs it is given. The edge never sees the policy — only these refs.
 */
public record ControllerRef(String kind, String name, String namespace) {

  public ControllerRef {
    if (kind == null || kind.isBlank()) {
      throw new IllegalArgumentException("ControllerRef kind cannot be null or blank");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("ControllerRef name cannot be null or blank");
    }
    if (namespace == null || namespace.isBlank()) {
      throw new IllegalArgumentException("ControllerRef namespace cannot be null or blank");
    }
  }

  /** The {@code kind/name} pair a {@code kubectl -n namespace} command names. */
  public String resourceRef() {
    return kind + "/" + name;
  }

  /** A human-readable {@code kind/name@namespace} label for logs and result projection. */
  public String ref() {
    return kind + "/" + name + "@" + namespace;
  }
}
