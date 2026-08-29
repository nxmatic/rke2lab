package io.seedmatic.rke2lab.sops.edge;

import io.seedmatic.rke2lab.clusterpki.contract.SopsDecryptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The realised {@code sops} decrypt edge, twin of {@link ProcessBuilderSopsEncryptor}: opens a
 * sops-encrypted YAML by piping it to {@code sops --decrypt --config /dev/null --input-type yaml
 * --output-type yaml /dev/stdin}, with the age identity supplied out-of-band via the {@code
 * SOPS_AGE_KEY} environment variable (never on the command line — it would leak to the process
 * table). {@code --config /dev/null} mirrors the encrypt edge (the repo's {@code .sops.yaml} plays
 * no part in an explicit decrypt).
 *
 * <p>stdout carries the plaintext YAML, stderr only diagnostics — kept SEPARATE so a diagnostic
 * cannot corrupt the recovered secret.
 *
 * <p><b>Runtime dependency:</b> {@code sops} on {@code PATH} (rke2lab flox manifest). Always via
 * {@code flox activate -- ...}.
 */
@Component(service = SopsDecryptor.class)
public final class ProcessBuilderSopsDecryptor implements SopsDecryptor {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessBuilderSopsDecryptor.class);

  @Override
  public String decryptYaml(String sopsYaml, String ageIdentity) {
    if (ageIdentity == null || ageIdentity.isBlank()) {
      throw new SopsEncryptionException("no age identity — cannot open the sealed bundle");
    }
    final ProcessBuilder pb =
        new ProcessBuilder(
            "sops",
            "--decrypt",
            "--config",
            "/dev/null",
            "--input-type",
            "yaml",
            "--output-type",
            "yaml",
            "/dev/stdin");
    pb.environment().put("SOPS_AGE_KEY", ageIdentity);

    final Process process;
    try {
      process = pb.start();
    } catch (IOException ex) {
      throw new SopsEncryptionException(
          "sops could not be started — is it on PATH? (flox activate -- ...)", ex);
    }

    try {
      try (var stdin = process.getOutputStream()) {
        stdin.write(sopsYaml.getBytes(StandardCharsets.UTF_8));
        stdin.flush();
      }
      final String plaintext;
      try (var stdout = process.getInputStream()) {
        plaintext = new String(stdout.readAllBytes(), StandardCharsets.UTF_8);
      }
      final String diagnostics;
      try (var stderr = process.getErrorStream()) {
        diagnostics = new String(stderr.readAllBytes(), StandardCharsets.UTF_8);
      }
      final int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new SopsEncryptionException(
            "sops --decrypt exited with code " + exitCode + ": " + diagnostics);
      }
      LOG.debug("sops opened a {}-byte sealed bundle", plaintext.length());
      return plaintext;
    } catch (IOException ex) {
      throw new SopsEncryptionException("sops I/O failed", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new SopsEncryptionException("interrupted waiting for sops", ex);
    }
  }
}
