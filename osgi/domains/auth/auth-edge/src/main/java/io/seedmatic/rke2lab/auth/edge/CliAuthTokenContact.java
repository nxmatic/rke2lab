package io.seedmatic.rke2lab.auth.edge;

import io.seedmatic.rke2lab.auth.contract.AuthTokenContact;
import io.seedmatic.rke2lab.auth.contract.AuthTokenSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The realised auth edge: resolves a short-lived credential by asking the provider's CLI — {@code
 * flox auth token} for FloxHub. The single door toward this one external contact — the {@code
 * ProcessBuilder} mechanism formerly inlined in the host {@code
 * IncusResourceBootstrap.LaunchSecretsUpdater}. {@code ProcessBuilder} is playable, so this edge
 * lives in the OSGi world; SCR publishes it and the host updater composes it from the registry
 * after its own environment-variable precedence comes up empty. (GitHub was retired from this edge:
 * its token now flows from the App via {@code ghapp}, never {@code gh auth token}.)
 *
 * <p><b>Runtime dependency:</b> {@code flox} on {@code PATH}, authenticated.
 *
 * <p>Tagged {@code rke2lab.gardening=cultivating} — a token contact is a PURE PROBE (its output IS
 * the live credential, so a surveying impl could only fabricate one), and it is an OPTIONAL
 * collaborator ({@code @OsgiService(await = false)}). So it needs no surveying twin: under a
 * surveying gate the frontier's filter matches neither this (tagged {@code cultivating}) nor an
 * absent one, so the scion resolves it empty and falls back to the environment — no CLI shelled, no
 * fabricated token, honest inertness.
 */
@Component(service = AuthTokenContact.class, property = "rke2lab.gardening=cultivating")
public final class CliAuthTokenContact implements AuthTokenContact {

  private static final Logger LOG = LoggerFactory.getLogger(CliAuthTokenContact.class);
  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);

  @Override
  public Optional<String> tokenFor(AuthTokenSource source) {
    final String token = captureCommandOutput(commandFor(source));
    return token.isBlank() ? Optional.empty() : Optional.of(token);
  }

  private static List<String> commandFor(AuthTokenSource source) {
    return switch (source) {
      case FLOXHUB -> List.of("flox", "auth", "token");
    };
  }

  private static String captureCommandOutput(List<String> command) {
    final ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
    processBuilder.environment().putIfAbsent("LANG", "C");
    try {
      final Process process = processBuilder.start();
      final byte[] stdout = process.getInputStream().readAllBytes();
      final boolean exited = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!exited) {
        process.destroyForcibly();
        LOG.debug("token command {} timed out after {}", command, COMMAND_TIMEOUT);
        return "";
      }
      if (process.exitValue() != 0) {
        return "";
      }
      return new String(stdout, StandardCharsets.UTF_8).trim();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return "";
    } catch (IOException ex) {
      LOG.debug("token command {} failed to execute: {}", command, ex.getMessage());
      return "";
    }
  }
}
