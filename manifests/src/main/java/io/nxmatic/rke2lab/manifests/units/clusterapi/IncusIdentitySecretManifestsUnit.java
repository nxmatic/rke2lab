package io.nxmatic.rke2lab.manifests.units.clusterapi;

import io.nxmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.nxmatic.rke2lab.manifests.ManifestDomainCatalog;
import io.nxmatic.rke2lab.manifests.profiles.BootstrapIdentity;
import io.nxmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Manifest unit that creates the Incus identity secret for Cluster API Provider Incus (CAPN).
 *
 * <p>Single CAPN identity across all clusters: All clusters share the same CAPN identity name
 * ({@code "capn"}) and credentials, reading from:
 *
 * <ul>
 *   <li>Client certificate: {@code /incus/capn-client.crt} (classpath resource)
 *   <li>Client key: {@code incus.capn.clientKey} in {@code .secrets} file (repo root)
 *   <li>Server certificate: {@code ~/.config/incus/servercerts/<remote-host>.crt}
 *   <li>Remote address: from {@code ~/.config/incus/config.yml} (remote name = cluster name)
 * </ul>
 *
 * <p>Cluster-scoped configuration: The Incus remote name equals the cluster name (bioskop → bioskop
 * remote, nikopol → nikopol remote). The remote address and server certificate are looked up using
 * the cluster name as the remote name.
 *
 * <p>The generated secret is named {@code <cluster-name>-incus-identity} in namespace {@code
 * capn-system}.
 */
public final class IncusIdentitySecretManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.CLUSTER_API + "/incus-identity";

  private static final String INCUS_IDENTITY_NAME = "capn";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("cluster-api", "incus-identity");

  public IncusIdentitySecretManifestsUnit(final Construct scope, final String id) {
    super(scope, id, MANIFEST_UNIT_ID, List.of());

    final BootstrapIdentity identity = bootstrapIdentity();
    final String clusterName = identity.clusterName();
    final String incusRemoteName = identity.incusRemoteName();

    // Skip synthesis when running in ephemeral/test mode without real bootstrap identity
    if (BootstrapIdentity.UNKNOWN.equals(clusterName)) {
      return;
    }

    try {
      final Path secretsFile = Path.of(".secrets");
      final Path incusConfigDir = Path.of(System.getProperty("user.home"), ".config", "incus");

      final String remoteAddress = readRemoteAddress(incusRemoteName, incusConfigDir);
      final String clientCert = readClientCertFromClasspath();
      final String clientKey = readClientKeyFromSecrets(secretsFile);
      final String serverCert = readServerCertFromIncusConfig(remoteAddress, incusConfigDir);
      final String serverAddress = encodeBase64(remoteAddress);

      createIncusIdentitySecret(
          this, clusterName, serverAddress, serverCert, clientCert, clientKey);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to materialize Incus identity secret", ex);
    }
  }

  private void createIncusIdentitySecret(
      Construct scope,
      String clusterName,
      String serverAddress,
      String serverCert,
      String clientCert,
      String clientKey) {
    ApiObject secret =
        new ApiObject(
            scope,
            "secret-incus-identity",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(clusterName + "-incus-identity")
                        .namespace("capn-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|capn-system|" + clusterName + "-incus-identity"))
                        .build())
                .build());

    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
    secret.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "server", serverAddress,
                "server-crt", serverCert,
                "client-crt", clientCert,
                "client-key", clientKey)));
  }

  private String readRemoteAddress(String clusterName, Path incusConfigDir) throws IOException {
    final Path configFile = incusConfigDir.resolve("config.yml");
    if (!Files.exists(configFile)) {
      throw new IllegalStateException(
          "Incus config file not found at " + configFile + " - cannot read remote address");
    }

    final String configContent = Files.readString(configFile, StandardCharsets.UTF_8);
    final Pattern remotePattern =
        Pattern.compile(
            "^\\s*" + Pattern.quote(clusterName) + ":\\s*\\n\\s*addr:\\s*(.+)$", Pattern.MULTILINE);
    final Matcher matcher = remotePattern.matcher(configContent);
    if (!matcher.find()) {
      throw new IllegalStateException(
          "Remote '"
              + clusterName
              + "' not found in "
              + configFile
              + " - expected remote definition with addr field");
    }

    return matcher.group(1).trim();
  }

  private String readClientCertFromClasspath() throws IOException {
    final String certResource = "/incus/" + INCUS_IDENTITY_NAME + "-client.crt";
    final var inputStream = getClass().getResourceAsStream(certResource);
    if (inputStream == null) {
      throw new IllegalStateException("Client certificate resource not found: " + certResource);
    }
    final byte[] certBytes = inputStream.readAllBytes();
    return Base64.getEncoder().encodeToString(certBytes);
  }

  private String readClientKeyFromSecrets(Path secretsFile) throws IOException {
    if (!Files.exists(secretsFile)) {
      throw new IllegalStateException(
          "Secrets file not found at " + secretsFile + " - cannot read client key");
    }

    final String secretsContent = Files.readString(secretsFile, StandardCharsets.UTF_8);
    final String secretsPath = "incus." + INCUS_IDENTITY_NAME + ".clientKey";
    final Pattern keyPattern =
        Pattern.compile(
            "incus:\\s*\\n\\s*"
                + INCUS_IDENTITY_NAME
                + ":\\s*\\n(?:.*\\n)*?\\s*clientKey:\\s*\\|\\s*\\n((?:\\s+.*\\n)+)",
            Pattern.MULTILINE);
    final Matcher matcher = keyPattern.matcher(secretsContent);
    if (!matcher.find()) {
      throw new IllegalStateException(
          secretsPath
              + " not found in "
              + secretsFile
              + " - expected YAML block scalar with PEM-encoded private key");
    }
    final String keyBlock = matcher.group(1);
    final String[] lines = keyBlock.split("\\n");
    final StringBuilder key = new StringBuilder();
    for (String line : lines) {
      key.append(line.trim()).append("\n");
    }
    return encodeBase64(key.toString().trim());
  }

  private String readServerCertFromIncusConfig(String remoteAddress, Path incusConfigDir)
      throws IOException {
    final String remoteHost = extractHostFromUri(remoteAddress);
    final Path serverCertPath = incusConfigDir.resolve("servercerts").resolve(remoteHost + ".crt");

    if (!Files.exists(serverCertPath)) {
      throw new IllegalStateException(
          "Server certificate not found for remote "
              + remoteHost
              + " at "
              + serverCertPath
              + " - expected certificate matching remote address host");
    }

    final byte[] certBytes = Files.readAllBytes(serverCertPath);
    return Base64.getEncoder().encodeToString(certBytes);
  }

  private String extractHostFromUri(String uri) {
    final Pattern hostPattern = Pattern.compile("https?://([^:]+)");
    final Matcher matcher = hostPattern.matcher(uri);
    if (matcher.find()) {
      return matcher.group(1);
    }
    throw new IllegalArgumentException("Cannot extract host from URI: " + uri);
  }

  private String encodeBase64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
