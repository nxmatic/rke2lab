package io.seedmatic.rke2lab.osgi.runtime.framework;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A live embedded Felix framework, booted by {@link FrameworkLauncher} — the host SEAM. The host
 * world (flat JCL) reads model services out of it by type ({@link #awaitService(Class, long)}) or
 * by name ({@link #awaitService(String, long)}), then {@link #close() closes} it. The boot ACT
 * produced this; the boot DECISION ({@code BootPlan}) is upstream and framework-free.
 *
 * <p>{@code AutoCloseable} so an entrypoint that owns the boot span ({@code
 * FrameworkLaunch.embedded()}) can try-with-resources it; the test executor hands it out and closes
 * it in its own lifecycle.
 */
public final class BootedFramework implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(BootedFramework.class);

  private final Framework framework;
  private final Optional<FelixStorage> storage;
  private final boolean owns;

  BootedFramework(Framework framework, FelixStorage storage) {
    this(framework, Optional.of(storage), true);
  }

  private BootedFramework(Framework framework, Optional<FelixStorage> storage, boolean owns) {
    this.framework = framework;
    this.storage = storage;
    this.owns = owns;
  }

  /**
   * A view that ATTACHES to an already-running framework (e.g. reached from an {@code
   * OsgiConnection}) without owning its lifecycle: {@link #close()} is a no-op, because the
   * attacher did not boot it and must not stop it. Parallel to {@code
   * OsgiConnection.over(ownsLifecycle = false)} — a phase that needs the service-lookup shape of a
   * {@code BootedFramework} but did not perform the boot.
   */
  public static BootedFramework attached(Framework framework) {
    return new BootedFramework(framework, Optional.empty(), false);
  }

  /**
   * The booted framework's bundle context, for the host seam to read services from the registry.
   */
  public BundleContext context() {
    return framework.getBundleContext();
  }

  /**
   * Resolve a single service of {@code type} from the registry, waiting up to {@code timeoutMillis}
   * for SCR to publish it (a component's service appears only after its mandatory references bind).
   */
  public <T> T awaitService(Class<T> type, long timeoutMillis) {
    final ServiceTracker<T, T> tracker = new ServiceTracker<>(context(), type, null);
    tracker.open();
    try {
      return tracker.waitForService(timeoutMillis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted awaiting service " + type.getName(), ex);
    } finally {
      tracker.close();
    }
  }

  /**
   * The packages the live wiring serves in BOTH realms at once — each package the system bundle (id
   * 0, the flat host realm) exports that an INSTALLED bundle (its own classloader realm) ALSO
   * exports. This is the loader-constraint collision that surfaces as a {@link LinkageError} the
   * moment an instance of such a package crosses the seam; an empty map is a clean single-realm
   * assembly. OBSERVED from the resolved {@link BundleWiring}, never inferred from manifests — the
   * faithful successor to the static {@code flat ∩ staged-export} intersection.
   *
   * <p>Raw facts only, keyed by the offending installed bundle's symbolic name → the packages it
   * co-exports with the system bundle. Shared-provider intent (the R1 pax {@code org.slf4j} scar)
   * and known debt are NOT filtered here; the governance policy that tolerates some duplications
   * belongs to the gate that consumes this, not to the observation.
   */
  public Map<String, List<String>> realmDuplications() {
    final Set<String> systemExports = packageExportsOf(framework);
    final Map<String, List<String>> duplications = new LinkedHashMap<>();
    for (Bundle bundle : framework.getBundleContext().getBundles()) {
      if (bundle.getBundleId() == 0) {
        continue; // the system bundle IS the flat realm — it cannot duplicate itself
      }
      final List<String> shared = new ArrayList<>();
      for (String exported : packageExportsOf(bundle)) {
        if (systemExports.contains(exported)) {
          shared.add(exported);
        }
      }
      if (!shared.isEmpty()) {
        duplications.put(bundle.getSymbolicName(), shared);
      }
    }
    return duplications;
  }

  /**
   * The installed (non-system) bundles that FAILED to resolve — those still at state {@link
   * Bundle#INSTALLED} after the boot started the graph, keyed by symbolic name. An empty list means
   * the whole assembly wired; a non-empty one is a broken assembly the embedded-boot smoke test
   * must catch (a missing import, an unattachable fragment, an {@code osgi.ee} mismatch — the
   * failures a static manifest scan cannot see because they only surface against the real
   * resolver).
   */
  public List<String> unresolvedBundles() {
    final List<String> unresolved = new ArrayList<>();
    for (Bundle bundle : framework.getBundleContext().getBundles()) {
      if (bundle.getBundleId() != 0 && bundle.getState() == Bundle.INSTALLED) {
        unresolved.add(bundle.getSymbolicName());
      }
    }
    return unresolved;
  }

  /**
   * The package names {@code bundle} exports into the live wiring — its resolved {@code
   * PACKAGE_NAMESPACE} capabilities. Empty for an unresolved bundle (it contributes nothing to the
   * wiring). Read from the same source the boot's closure walk trusts, so it reflects what actually
   * loads, not what a manifest declared.
   */
  private static Set<String> packageExportsOf(Bundle bundle) {
    final BundleWiring wiring = bundle.adapt(BundleWiring.class);
    if (wiring == null) {
      return Set.of();
    }
    final Set<String> names = new LinkedHashSet<>();
    for (var capability : wiring.getCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
      names.add((String) capability.getAttributes().get(PackageNamespace.PACKAGE_NAMESPACE));
    }
    return names;
  }

  /** By-name variant for a service the host cannot type (its package is bundle-internal). */
  public Object awaitService(String className, long timeoutMillis) {
    final ServiceTracker<Object, Object> tracker = new ServiceTracker<>(context(), className, null);
    tracker.open();
    try {
      return tracker.waitForService(timeoutMillis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted awaiting service " + className, ex);
    } finally {
      tracker.close();
    }
  }

  @Override
  public void close() {
    if (!owns) {
      return; // attached, not owned — do not stop a framework we did not boot
    }
    try {
      framework.stop();
      framework.waitForStop(5000);
    } catch (BundleException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      LOG.warn("OSGi runtime shutdown was not clean", ex);
    } finally {
      storage.ifPresent(FelixStorage::delete);
    }
  }
}
