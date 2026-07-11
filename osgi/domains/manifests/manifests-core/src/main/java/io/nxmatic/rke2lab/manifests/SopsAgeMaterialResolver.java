package io.nxmatic.rke2lab.manifests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.nxmatic.rke2lab.manifests.contract.SshToAgeConverter;
import io.nxmatic.rke2lab.manifests.contract.profiles.SopsAgeMaterial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The synthesis service's pre-synthesis step: resolve the age key BEFORE the unit loop runs, so the
 * unit only renders. Two contacts, kept distinct: it READS the {@code rke2-cluster} SSH key from
 * the key-store (a host-filesystem contact, structured YAML — no regex), then CONVERTS it through
 * the {@link SshToAgeConverter} edge. The converter is passed in (instance-passing, the
 * registry-bound edge), never reached statically.
 *
 * <p>Fail-soft on absence, fail-fast on malformation: no key-store present ⟹ {@link
 * SopsAgeMaterial#unknown()} and the converter is NOT called (ephemeral / test runs, where the unit
 * then skips). A present-but-malformed key-store raises — a defect to surface, not a silent skip.
 */
final class SopsAgeMaterialResolver {

  private static final Logger LOG = LoggerFactory.getLogger(SopsAgeMaterialResolver.class);

  /** Key-store location, CWD-relative (seed-master synthesises from the worktree root). */
  static final Path KEYS_YAML = Path.of(".ndh-ssh.d/keys.yaml");

  /** Key path inside {@code keys.yaml} to the rke2-cluster private SSH key. */
  static final List<String> RKE2_CLUSTER_PRIVATE_KEY_PATH =
      List.of("keys", "rke2-cluster", "private");

  private final SshToAgeConverter converter;

  SopsAgeMaterialResolver(SshToAgeConverter converter) {
    this.converter = converter;
  }

  SopsAgeMaterial resolve() {
    if (!Files.isReadable(KEYS_YAML)) {
      LOG.debug("No SSH key-store at {} — sops-age Secret will be skipped", KEYS_YAML);
      return SopsAgeMaterial.unknown();
    }
    final String sshPrivateKey = readRke2ClusterPrivateKey();
    final String ageKey = converter.toAgeKey(sshPrivateKey);
    return new SopsAgeMaterial(ageKey);
  }

  private String readRke2ClusterPrivateKey() {
    final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    final JsonNode root;
    try {
      root = mapper.readTree(KEYS_YAML.toFile());
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to parse YAML from " + KEYS_YAML, ex);
    }

    if (root.has("sops")) {
      throw new IllegalStateException(
          KEYS_YAML
              + " appears sops-encrypted at rest; the worktree copy must be plaintext "
              + "(check .gitattributes declares `filter=sops-yaml` and the smudge ran).");
    }

    JsonNode node = root;
    for (String segment : RKE2_CLUSTER_PRIVATE_KEY_PATH) {
      if (node == null || !node.has(segment)) {
        throw new IllegalStateException(
            "Missing key '"
                + String.join(".", RKE2_CLUSTER_PRIVATE_KEY_PATH)
                + "' in "
                + KEYS_YAML
                + " (stuck at '"
                + segment
                + "').");
      }
      node = node.get(segment);
    }
    if (node == null || !node.isTextual()) {
      throw new IllegalStateException(
          "Key '"
              + String.join(".", RKE2_CLUSTER_PRIVATE_KEY_PATH)
              + "' in "
              + KEYS_YAML
              + " is not a string scalar.");
    }
    final String value = node.asText().trim();
    if (value.isEmpty()) {
      throw new IllegalStateException(
          "Key '"
              + String.join(".", RKE2_CLUSTER_PRIVATE_KEY_PATH)
              + "' in "
              + KEYS_YAML
              + " is empty.");
    }
    return value;
  }
}
