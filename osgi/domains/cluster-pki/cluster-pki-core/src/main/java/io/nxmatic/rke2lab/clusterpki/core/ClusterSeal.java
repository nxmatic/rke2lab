package io.nxmatic.rke2lab.clusterpki.core;

import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.nxmatic.rke2lab.clusterpki.contract.ClusterAgeKey;
import io.nxmatic.rke2lab.clusterpki.contract.ClusterCaBundle;
import io.nxmatic.rke2lab.clusterpki.contract.SopsEncryptor;
import io.nxmatic.rke2lab.manifests.contract.SshToAgeConverter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The cluster-PKI seal, in one act — the domain logic the seal scion drives (instance-passing: the
 * two external seams are handed in, never reached statically). Reads the operator's ndh key-store +
 * the repo's {@code .sops.yaml} in-container, mints the deterministic CA with {@link
 * ClusterCaGenerator}, seals the eleven-key bundle for the operator + cluster age recipients, and
 * derives the cluster age identity the node will decrypt it with:
 *
 * <ol>
 *   <li>{@link NdhKeystore} → the {@code mammoth-skate-tls} root (cert + key) and the {@code
 *       rke2-cluster} SSH key;
 *   <li>{@link ClusterCaGenerator} → the eleven-key node bundle ({@code fileName -> PEM});
 *   <li>render it to YAML (literal block scalars) and seal it via {@link SopsEncryptor} for the
 *       {@link SopsRecipients} — the node consumes the sops envelope via sops-nix;
 *   <li>{@link SshToAgeConverter} on the {@code rke2-cluster} key → the cluster age identity.
 * </ol>
 *
 * <p>All reads are CWD-relative (the scion runs in the host JVM rooted at the worktree). Nothing is
 * written to disk: the cleartext bundle is piped straight into sops, and only the sealed forms
 * leave as a {@link SealedClusterPki}. Called ONCE per cluster — the scion's idempotency gate (a
 * cellar hit) skips it on a re-grow, so the CA stays stable. See
 * docs/architecture/cluster-api/deterministic-cluster-access.adoc.
 */
public final class ClusterSeal {

  private final SshToAgeConverter sshToAge;
  private final SopsEncryptor sops;

  public ClusterSeal(SshToAgeConverter sshToAge, SopsEncryptor sops) {
    this.sshToAge = sshToAge;
    this.sops = sops;
  }

  public SealedClusterPki seal() {
    final NdhKeystore keystore = new NdhKeystore();
    final List<String> recipients = SopsRecipients.fromDefault();
    final long timestamp = Instant.now().getEpochSecond();

    final LinkedHashMap<String, String> bundle =
        new ClusterCaGenerator().generate(keystore.rootCertPem(), keystore.rootKeyPem(), timestamp);
    final String sealed = sops.encryptYaml(renderYaml(bundle), recipients);
    final String ageIdentity = sshToAge.toAgeKey(keystore.rke2ClusterPrivateKey());
    return new SealedClusterPki(new ClusterCaBundle(sealed), new ClusterAgeKey(ageIdentity));
  }

  /**
   * Render {@code fileName -> PEM} to the flat YAML the node's {@code sops.nix} declares — literal
   * block scalars so each PEM stays readable and its trailing newline is preserved.
   */
  private static String renderYaml(LinkedHashMap<String, String> bundle) {
    final YAMLMapper mapper =
        YAMLMapper.builder()
            .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .build();
    try {
      return mapper.writeValueAsString(bundle);
    } catch (Exception ex) {
      throw new IllegalStateException("failed to render the cluster CA bundle YAML", ex);
    }
  }
}
