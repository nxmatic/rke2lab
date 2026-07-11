package io.nxmatic.rke2lab.controlplane.incus;

import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.manifests.contract.profiles.IncusIdentityMaterial;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host-side assembler for the {@link IncusIdentityMaterial} that backs the {@code
 * <cluster>-incus-identity} Secret. The {@code capn-provider} identity lives entirely in the host
 * world — the client cert ships as a seed-master application resource, the client key in the
 * worktree {@code .secrets}, the server cert + remote address in {@code ~/.config/incus/} — so this
 * reads them here and hands the material across the frontier via {@link
 * io.nxmatic.rke2lab.manifests.contract.ManifestSynthesisRequest}. The OSGi synthesis unit never
 * reaches back across the world boundary to read a host file or classpath resource.
 *
 * <p>Values are returned RAW (PEM text, plain URI); the manifest unit base64-encodes them when it
 * renders the Secret.
 */
final class IncusIdentityMaterialAssembler {

  private static final String INCUS_IDENTITY_NAME = "capn";

  private final BootstrapConfig config;

  IncusIdentityMaterialAssembler(BootstrapConfig config) {
    this.config = config;
  }

  /**
   * Assemble the material from the host world. Returns {@link IncusIdentityMaterial#unknown()} when
   * the operator environment is absent (no {@code ~/.config/incus} / {@code .secrets}), so
   * ephemeral and CI synth runs degrade to a skipped Secret rather than failing — the unit checks
   * {@link IncusIdentityMaterial#isUnknown()}.
   */
  IncusIdentityMaterial assemble() {
    final Path incusConfigDir = config.incusConfigDir();
    final Path secretsFile = config.localWorktreePath().resolve(".secrets");
    final URI remoteAddress = config.incusRemoteAddress();
    if (remoteAddress == null || incusConfigDir == null || !Files.exists(secretsFile)) {
      return IncusIdentityMaterial.unknown();
    }
    return IncusIdentityMaterial.builder()
        .serverAddress(remoteAddress.toString())
        .serverCert(readServerCert(remoteAddress, incusConfigDir))
        .clientCert(readClientCertResource())
        .clientKey(readClientKey(secretsFile))
        .build();
  }

  private String readClientCertResource() {
    final String resource = "/incus/" + INCUS_IDENTITY_NAME + "-client.crt";
    try (InputStream in = IncusIdentityMaterialAssembler.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Client certificate resource not found: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to read " + resource, ex);
    }
  }

  private String readClientKey(Path secretsFile) {
    final String secretsPath = "incus." + INCUS_IDENTITY_NAME + ".clientKey";
    final String secretsContent = readString(secretsFile);
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
    final StringBuilder key = new StringBuilder();
    for (String line : matcher.group(1).split("\\n")) {
      key.append(line.trim()).append("\n");
    }
    return key.toString().trim();
  }

  private String readServerCert(URI remoteAddress, Path incusConfigDir) {
    final String remoteHost = remoteAddress.getHost();
    if (remoteHost == null) {
      throw new IllegalArgumentException(
          "Cannot extract host from Incus remote URI: " + remoteAddress);
    }
    final Path serverCertPath = incusConfigDir.resolve("servercerts").resolve(remoteHost + ".crt");
    if (!Files.exists(serverCertPath)) {
      throw new IllegalStateException(
          "Server certificate not found for remote "
              + remoteHost
              + " at "
              + serverCertPath
              + " - expected certificate matching remote address host");
    }
    return readString(serverCertPath);
  }

  private static String readString(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to read " + path, ex);
    }
  }
}
