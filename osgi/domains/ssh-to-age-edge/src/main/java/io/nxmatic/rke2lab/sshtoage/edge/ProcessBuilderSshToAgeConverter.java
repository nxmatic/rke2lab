package io.nxmatic.rke2lab.sshtoage.edge;

import io.nxmatic.rke2lab.manifests.contract.SshToAgeConversionException;
import io.nxmatic.rke2lab.manifests.contract.SshToAgeConverter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The realised {@code ssh-to-age} edge: converts an OpenSSH private key to an age key by piping it
 * to the {@code ssh-to-age -private-key} CLI. The whole adapter — the single door toward this one
 * external tool.
 *
 * <p><b>Runtime dependency:</b> {@code ssh-to-age} on {@code PATH}, provided by the {@code
 * fleet/flox/keyhole} environment in the rke2lab flox manifest. Always run via {@code flox activate
 * -- ...} so the tool is available. {@code ProcessBuilder} is playable, so this edge lives in the
 * OSGi world; SCR publishes it and the synthesis service binds it with a mandatory
 * {@code @Reference}.
 */
@Component(service = SshToAgeConverter.class)
public final class ProcessBuilderSshToAgeConverter implements SshToAgeConverter {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessBuilderSshToAgeConverter.class);

  @Override
  public String toAgeKey(String sshPrivateKey) {
    final ProcessBuilder pb = new ProcessBuilder("ssh-to-age", "-private-key");
    pb.redirectErrorStream(true);

    final Process process;
    try {
      process = pb.start();
    } catch (IOException ex) {
      throw new SshToAgeConversionException(
          "ssh-to-age could not be started — is it on PATH? (flox activate -- ...)", ex);
    }

    try {
      try (var out = process.getOutputStream()) {
        out.write(sshPrivateKey.getBytes(StandardCharsets.UTF_8));
        out.flush();
      }

      final String output;
      try (var in = process.getInputStream()) {
        output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }

      final int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new SshToAgeConversionException(
            "ssh-to-age exited with code " + exitCode + ": " + output);
      }
      LOG.debug("ssh-to-age converted an SSH key to an age key");
      return output.trim();
    } catch (IOException ex) {
      throw new SshToAgeConversionException("ssh-to-age I/O failed", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new SshToAgeConversionException("interrupted waiting for ssh-to-age", ex);
    }
  }
}
