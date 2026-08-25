package io.seedmatic.rke2lab.netplan.contract;

/**
 * The private AS family the cluster owns — the single source for the BGP numbering that used to sit
 * as bare literals inside the Cilium manifests. Each constant pairs the ASN with its canonical AS
 * name (the {@code asns}-dictionary / NetName label consumers render).
 *
 * <p>The home AS ({@code 65000}) is deliberately NOT here: it belongs to ndh's home network, not
 * the cluster. rke2lab owns only {@link #RKE2_CLUSTER} and {@link #GATEWAY}; ndh adds home when it
 * unions the home segments.
 */
public enum ClusterAsn {

  /**
   * rke2 cluster local-AS FAMILY BASE — Cilium BGP {@code localASN} is {@code
   * ClusterNetworkBlueprint.bgpLocalAsn(clusterId) = 64512 + clusterId}, per-cluster so clusters
   * sharing an L2 fabric run unambiguous BGP sessions (see the cluster addressing plan). This
   * constant is the base {@code 64512} (the family anchor), NOT any one cluster's ASN.
   */
  RKE2_CLUSTER(64512, "rke2-cluster"),

  /**
   * vmnet gateway peer AS — Cilium BGP {@code peerASN} (the external gateway the cluster peers
   * with).
   */
  GATEWAY(65020, "gateway");

  private final int number;
  private final String asName;

  ClusterAsn(final int number, final String asName) {
    this.number = number;
    this.asName = asName;
  }

  /** The AS number (e.g. {@code 65010}). */
  public int number() {
    return number;
  }

  /** The canonical AS name (e.g. {@code "rke2-cluster"}). */
  public String asName() {
    return asName;
  }
}
