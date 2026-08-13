package io.seedmatic.rke2lab.osgi.boot.discovery;

import java.io.IOException;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;

/**
 * The one place the two Felix executors — the prod {@code FrameworkLauncher} and the test {@code
 * OutOfContainerFrameworkExtension} — share their bundle LIFECYCLE gesture, so a test provisions a
 * bundle exactly as prod does (a test cannot pass for a boot prod would fail). Both used to carry
 * their own copy of the two primitives below; this instance holds the {@link BundleContext} they
 * install into and offers them once.
 *
 * <p>What is shared is only the gesture, not the SOURCE of the bundle set — prod installs a {@code
 * BootPlan}'s installables (staged bytes / classpath URL, pinned to start-levels), the test
 * installs a classpath-scanned closure; that divergence is their nature and stays each side's own.
 * What is IDENTICAL is: install a {@link BundleLocation} by streaming (staged) or by URL
 * (classpath), and start a bundle ONLY when it is not a fragment (a fragment has no lifecycle —
 * {@code start()} on it throws; it merges into its host when the host resolves, OSGi Core §3.14).
 */
public final class BundleInstaller {

  private final BundleContext context;

  public BundleInstaller(BundleContext context) {
    this.context = context;
  }

  /**
   * Install {@code location} into the framework, source-agnostically: a {@link
   * BundleLocation.Staged} streams its bytes into Felix's cache, a {@link
   * BundleLocation.OnClasspath} installs by its file/reference URL. The one place the boot's
   * runtime NATURE shows (a sealed switch), everything upstream being source-agnostic.
   */
  public Bundle install(BundleLocation location) throws BundleException, IOException {
    return switch (location) {
      case BundleLocation.Staged staged -> {
        try (var in = staged.open()) {
          yield context.installBundle(staged.locationId(), in);
        }
      }
      case BundleLocation.OnClasspath onClasspath ->
          context.installBundle(onClasspath.locationId());
    };
  }

  /**
   * Start {@code bundle} unless it is a fragment. A fragment (a {@code Fragment-Host} header) has
   * no lifecycle of its own — {@code start()} on it throws; it is left to merge into its host when
   * the host resolves. Returns {@code true} if it was started, {@code false} if skipped as a
   * fragment.
   */
  public boolean startIfNotFragment(Bundle bundle) throws BundleException {
    if (bundle.getHeaders().get(Constants.FRAGMENT_HOST) != null) {
      return false;
    }
    bundle.start();
    return true;
  }
}
