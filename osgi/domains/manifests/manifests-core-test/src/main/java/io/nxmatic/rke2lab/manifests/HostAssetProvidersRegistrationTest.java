package io.nxmatic.rke2lab.manifests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.nxmatic.rke2lab.manifests.contract.hostasset.HostAssetProvider;
import io.nxmatic.rke2lab.manifests.hostasset.CloudConfigHostAssetProvider;
import io.nxmatic.rke2lab.manifests.hostasset.EnvConfigHostAssetProvider;
import io.nxmatic.rke2lab.manifests.hostasset.SystemdBundleHostAssetProvider;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/**
 * Proves manifests-core PUBLISHES its host-asset providers as SCR services — the manifests half of
 * the seam the incus materializer collects through {@code @Reference(MULTIPLE)}. Run IN-CONTAINER:
 * the providers must appear in the SAME registry the materializer resolves from, so an in-JVM
 * {@code new CloudConfigHostAssetProvider()} would prove nothing. Each is a bare
 * {@code @Component(service = HostAssetProvider.class)} with no {@code @Reference}, so SCR
 * activates all three the moment manifests-core starts.
 *
 * <p>The regression this guards: a provider that silently stops registering (a dropped
 * {@code @Component}, a bnd that misses the OSGI-INF descriptor) would make its asset vanish from
 * the materialised host with no error — exactly the silent gap the typed seam exists to abolish.
 */
class HostAssetProvidersRegistrationTest {

  @Test
  void allThreeProvidersRegisterAsServices() throws Exception {
    final BundleContext context =
        FrameworkUtil.getBundle(HostAssetProvidersRegistrationTest.class).getBundleContext();

    final Collection<ServiceReference<HostAssetProvider>> references =
        context.getServiceReferences(HostAssetProvider.class, null);
    assertNotNull(references, "SCR must publish the HostAssetProvider services");

    final Set<String> registered =
        references.stream()
            .map(context::getService)
            .map(provider -> provider.getClass().getName())
            .collect(Collectors.toSet());

    assertEquals(
        Set.of(
            CloudConfigHostAssetProvider.class.getName(),
            EnvConfigHostAssetProvider.class.getName(),
            SystemdBundleHostAssetProvider.class.getName()),
        registered,
        "exactly the three host-asset providers register — no more (stray @Component), no fewer"
            + " (a dropped descriptor would silently drop its asset from the host)");
  }
}
