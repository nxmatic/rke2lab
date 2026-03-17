// @codebase
package io.nxmatic.rk2lab.manifests;

import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisRequest;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisResult;
import io.nxmatic.rk2lab.manifests.api.ManifestSynthesisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.ServiceLoader;

public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws IOException {
        final ManifestSynthesisService synthesisService = loadRequiredSingleProvider();
        final ManifestSynthesisResult result = synthesisService.synthesize(ManifestSynthesisRequest.fromSystemProperties());
        LOG.info("Manifest synthesis completed by provider '{}'", synthesisService.providerId());
        LOG.info("Consolidated manifest output written to {}", result.manifestFile());
    }

    private static ManifestSynthesisService loadRequiredSingleProvider() {
        final List<ManifestSynthesisService> providers = ServiceLoader.load(ManifestSynthesisService.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        if (providers.isEmpty()) {
            throw new IllegalStateException("No ManifestSynthesisService provider found via ServiceLoader.");
        }
        if (providers.size() > 1) {
            throw new IllegalStateException("Expected exactly one ManifestSynthesisService provider, found "
                    + providers.size() + ": "
                    + providers.stream().map(ManifestSynthesisService::providerId).toList());
        }
        return providers.getFirst();
    }
}
