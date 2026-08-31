package io.seedmatic.rke2lab.host.runtime;

/**
 * The execution ENCLOSURE — a runtime FACT: which container this JVM process runs inside. A sibling
 * of {@code RunMode} (the Pulumi execution phase) but ORTHOGONAL to it: a process can be {@link
 * #OPERATOR} within a {@code PULUMI_RUN}, or {@link #IN_CLUSTER} with no Pulumi at all.
 *
 * <p>This enum is the fact's VALUES only. Resolving it from the ambient environment, and projecting
 * it onto a concern (the secrets source, and future ones), is {@link ExecutionEnvironment}'s work —
 * an instance that owns the environment it reads, never a static helper.
 */
public enum ExecutionEnclosure {

  /** A standalone process — the operator's host or container: {@code .secrets} + ndh user files. */
  OPERATOR,

  /** A Kubernetes pod — secrets come from the cluster's own (replicated/mounted) Secrets. */
  IN_CLUSTER;

  public boolean inCluster() {
    return this == IN_CLUSTER;
  }
}
