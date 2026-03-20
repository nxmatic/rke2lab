package io.nxmatic.rk2lab.manifests.api;

import java.util.List;

/** Canonical manifest domain identifiers shared across synthesis and controlplane policy. */
public final class ManifestDomainIds {

  public static final String CLUSTER = "cluster";

  public static final String STORAGE = "storage";

  public static final String REPLICATION = "replication";

  public static final String GITOPS = "gitops";

  public static final String RUNTIME = "runtime";

  public static final String NETWORKING = "networking";

  public static final String MESH = "mesh";

  public static final String HA = "ha";

  public static final String CICD = "cicd";

  private static final List<String> ALL =
      List.of(CLUSTER, STORAGE, REPLICATION, GITOPS, RUNTIME, NETWORKING, MESH, HA, CICD);

  private static final List<String> STAGE_A_LINKABLE_DOMAINS =
      List.of(HA, NETWORKING, REPLICATION, STORAGE, MESH);

  private ManifestDomainIds() {}

  public static List<String> all() {
    return ALL;
  }

  public static List<String> stageALinkableDomains() {
    return STAGE_A_LINKABLE_DOMAINS;
  }
}
