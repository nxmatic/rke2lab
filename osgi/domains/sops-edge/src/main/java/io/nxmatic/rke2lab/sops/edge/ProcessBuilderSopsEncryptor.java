package io.nxmatic.rke2lab.sops.edge;

import io.nxmatic.rke2lab.clusterpki.contract.SopsEncryptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The realised {@code sops} edge: seals a plaintext YAML for a set of age recipients by piping it
 * to {@code sops --encrypt --age <r1,r2> --config /dev/null --input-type yaml --output-type yaml
 * /dev/stdin}. The whole adapter — the single door toward this one external tool.
 *
 * <p>{@code --config /dev/null} is load-bearing: without it sops discovers the repo's {@code
 * .sops.yaml}, whose {@code encrypted_comment_regex} rule would leave the bundle in the CLEAR;
 * {@code /dev/null} forces the default encrypt-everything.
 *
 * <p>stdout and stderr are kept SEPARATE (unlike the ssh-to-age edge): stdout carries the sealed
 * YAML, stderr only diagnostics — merging them would corrupt the ciphertext. sops consumes all of
 * stdin before it emits stdout, so writing+closing stdin then draining stdout does not deadlock.
 *
 * <p><b>Runtime dependency:</b> {@code sops} on {@code PATH}, provided by the rke2lab flox
 * manifest. Always run via {@code flox activate -- ...}.
 */
@Component(service = SopsEncryptor.class)
public final class ProcessBuilderSopsEncryptor implements SopsEncryptor {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessBuilderSopsEncryptor.class);

  @Override
  public String encryptYaml(String plaintextYaml, List<String> ageRecipients) {
    if (ageRecipients == null || ageRecipients.isEmpty()) {
      throw new SopsEncryptionException("no age recipients — refusing to seal for nobody");
    }
    final ProcessBuilder pb =
        new ProcessBuilder(
            "sops",
            "--encrypt",
            "--age",
            String.join(",", ageRecipients),
            "--config",
            "/dev/null",
            "--input-type",
            "yaml",
            "--output-type",
            "yaml",
            "/dev/stdin");

    final Process process;
    try {
      process = pb.start();
    } catch (IOException ex) {
      throw new SopsEncryptionException(
          "sops could not be started — is it on PATH? (flox activate -- ...)", ex);
    }

    try {
      try (var stdin = process.getOutputStream()) {
        stdin.write(plaintextYaml.getBytes(StandardCharsets.UTF_8));
        stdin.flush();
      }
      final String sealed;
      try (var stdout = process.getInputStream()) {
        sealed = new String(stdout.readAllBytes(), StandardCharsets.UTF_8);
      }
      final String diagnostics;
      try (var stderr = process.getErrorStream()) {
        diagnostics = new String(stderr.readAllBytes(), StandardCharsets.UTF_8);
      }
      final int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new SopsEncryptionException("sops exited with code " + exitCode + ": " + diagnostics);
      }
      LOG.debug(
          "sops sealed a {}-byte bundle for {} recipients", sealed.length(), ageRecipients.size());
      return sealed;
    } catch (IOException ex) {
      throw new SopsEncryptionException("sops I/O failed", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new SopsEncryptionException("interrupted waiting for sops", ex);
    }
  }
}
