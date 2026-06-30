package io.nxmatic.rke2lab.manifests.units.runtime.flox;

import io.nxmatic.rke2lab.manifests.port.FloxRuntimeAssetService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.osgi.service.component.annotations.Component;

/** Default {@link FloxRuntimeAssetService} backed by {@link FloxRuntimeAssets}. */
@Component(service = FloxRuntimeAssetService.class)
public final class DefaultFloxRuntimeAssetService implements FloxRuntimeAssetService {

  private final FloxRuntimeAssets assets = FloxRuntimeAssets.builder().build();

  @Override
  public String providerId() {
    return "default-flox-runtime-assets";
  }

  @Override
  public void writeInstallerAssetTree(Path targetDir) throws IOException {
    assets.writeInstallerAssetTree(targetDir);
  }

  @Override
  public List<FloxEnvironment> discoveredEnvironments() {
    return assets.getDiscoveredEnvironments().stream()
        .map(env -> new FloxEnvironment(env.category(), env.name()))
        .toList();
  }
}
