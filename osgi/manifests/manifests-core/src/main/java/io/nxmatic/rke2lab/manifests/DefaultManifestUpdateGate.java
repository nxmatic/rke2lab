package io.nxmatic.rke2lab.manifests;

import io.nxmatic.rke2lab.manifests.port.ManifestSynthesisRequest;
import io.nxmatic.rke2lab.manifests.port.ManifestSynthesisResult;
import io.nxmatic.rke2lab.manifests.port.ManifestSynthesisService;
import io.nxmatic.rke2lab.manifests.port.ManifestUpdateGate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import org.osgi.service.component.annotations.Component;

/** Default manifests-owned Stage-A update gate. */
@Component(service = ManifestUpdateGate.class)
public final class DefaultManifestUpdateGate implements ManifestUpdateGate {

  @Override
  public String gateId() {
    return "default-manifests-update-gate";
  }

  @Override
  public void enforce(Path worktreePath) {
    final ManifestSynthesisService synthesisService = loadRequiredSingleProvider();
    final ManifestSynthesisRequest request = ManifestSynthesisRequest.ephemeral();
    try {
      final ManifestSynthesisResult result = synthesisService.synthesize(request);
      validateSynthesisResult(result);
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Manifest synthesis smoke gate failed via provider '"
              + synthesisService.providerId()
              + "'",
          ex);
    } finally {
      cleanupEphemeralRequest(request);
    }
  }

  private static ManifestSynthesisService loadRequiredSingleProvider() {
    final List<ManifestSynthesisService> providers =
        ServiceLoader.load(ManifestSynthesisService.class).stream()
            .map(ServiceLoader.Provider::get)
            .toList();

    if (providers.isEmpty()) {
      throw new IllegalStateException(
          "No ManifestSynthesisService provider found via ServiceLoader.");
    }
    if (providers.size() > 1) {
      throw new IllegalStateException(
          "Expected exactly one ManifestSynthesisService provider, found "
              + providers.size()
              + ": "
              + providers.stream().map(ManifestSynthesisService::providerId).toList());
    }
    return providers.getFirst();
  }

  private static void validateSynthesisResult(ManifestSynthesisResult result) throws IOException {
    if (result == null) {
      throw new IllegalStateException("Manifest synthesis returned null result.");
    }

    final Path manifestFile = result.manifestFile();
    if (manifestFile == null) {
      throw new IllegalStateException("Manifest synthesis result missing manifest file path.");
    }
    if (!Files.exists(manifestFile)) {
      throw new IllegalStateException(
          "Manifest synthesis result file does not exist: " + manifestFile);
    }
    if (Files.size(manifestFile) == 0L) {
      throw new IllegalStateException("Manifest synthesis result file is empty: " + manifestFile);
    }
    if (result.manifestUnitHitCount() <= 0) {
      throw new IllegalStateException("Manifest synthesis result reports no manifest unit hits.");
    }
    if (result.domainCount() <= 0) {
      throw new IllegalStateException("Manifest synthesis result reports no registered domains.");
    }
  }

  private static void cleanupEphemeralRequest(ManifestSynthesisRequest request) {
    final Path outdir = request.synthOutdir();
    if (outdir == null || !Files.exists(outdir)) {
      return;
    }

    try (var stream = Files.walk(outdir)) {
      stream
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // best effort cleanup for ephemeral smoke output
                }
              });
    } catch (IOException ignored) {
      // best effort cleanup for ephemeral smoke output
    }
  }
}
