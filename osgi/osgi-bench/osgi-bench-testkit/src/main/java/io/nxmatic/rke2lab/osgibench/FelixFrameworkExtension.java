package io.nxmatic.rke2lab.osgibench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

/**
 * Boots a real embedded Felix framework once per test class so bench tests observe the actual OSGi
 * resolution/runtime — not the hand-rolled resolver algorithm. A plain Jupiter extension (sibling
 * of {@code GrpcChannelNoiseCapture}) so the tests stay ordinary JUnit5 and launch from VSCode Test
 * Explorer as well as surefire.
 *
 * <p>Pass {@code systemPackagesExtra} to export an API package from the system bundle (= the test's
 * app classloader). A bundle that imports that package then shares the SAME class as the test, so a
 * service it registers is castable to the test's type — TYPED access, no reflection, no {@code
 * ClassCastException} across the bundle/app classloader boundary. This is how the Metatype proof
 * reads {@code MetaTypeService} typed.
 */
public final class FelixFrameworkExtension implements BeforeAllCallback, AfterAllCallback {

  private final String systemPackagesExtra;

  private Framework framework;

  /** Plain framework — for resolution-only proofs that need no shared API package. */
  public FelixFrameworkExtension() {
    this(null);
  }

  /**
   * @param systemPackagesExtra value for {@code org.osgi.framework.system.packages.extra} (e.g.
   *     {@code "org.osgi.service.metatype;version=1.4"}), exported from the system bundle so the
   *     test and the bundles share one copy of that API.
   */
  public FelixFrameworkExtension(String systemPackagesExtra) {
    this.systemPackagesExtra = systemPackagesExtra;
  }

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    Path storage = Files.createTempDirectory("osgi-bench-felix");
    FrameworkFactory factory =
        ServiceLoader.load(FrameworkFactory.class)
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("no OSGi FrameworkFactory on the classpath"));
    Map<String, String> config = new java.util.HashMap<>();
    config.put(Constants.FRAMEWORK_STORAGE, storage.toString());
    config.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
    if (systemPackagesExtra != null) {
      config.put(Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA, systemPackagesExtra);
    }
    framework = factory.newFramework(config);
    framework.init();
    framework.start();
  }

  @Override
  public void afterAll(ExtensionContext context) throws Exception {
    if (framework != null) {
      framework.stop();
      framework.waitForStop(5000);
    }
  }

  public BundleContext context() {
    return framework.getBundleContext();
  }

  /**
   * Install the bench bundle whose artifact ID contains {@code osgi-bench-<classifier>}. Locates
   * the bundle on the test classpath (java.class.path), not from target/ directory, because this
   * test module depends on the sibling bundle modules as maven dependencies. During reactor builds
   * with {@code -am}, dependencies resolve to {@code target/classes} directories (not jars), which
   * OSGi can load as directory-based bundles if they contain META-INF/MANIFEST.MF.
   */
  public Bundle install(String classifier) throws Exception {
    String classpathProperty = System.getProperty("java.class.path");
    Path bundleLocation =
        Arrays.stream(classpathProperty.split(System.getProperty("path.separator")))
            .map(Paths::get)
            .filter(
                p ->
                    p.toString().contains("osgi-bench-" + classifier)
                        && (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")
                            || Files.isDirectory(p)
                                && Files.exists(p.resolve("META-INF/MANIFEST.MF"))))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no osgi-bench-"
                            + classifier
                            + " bundle (jar or classes dir) on classpath"));
    String bundleUrl =
        Files.isDirectory(bundleLocation)
            ? "reference:" + bundleLocation.toUri().toString()
            : bundleLocation.toUri().toString();
    return context().installBundle(bundleUrl);
  }

  /**
   * Install a runtime bundle jar (e.g. {@code org.apache.felix.metatype}) located on the test
   * classpath by artifact-id substring. For the felix.* runtime bundles a test needs to ACTIVATE
   * (vs the bench bundles installed via {@link #install(String)}).
   */
  public Bundle installFromClasspath(String artifactId) throws Exception {
    String jar =
        Arrays.stream(
                System.getProperty("java.class.path").split(System.getProperty("path.separator")))
            .filter(p -> p.contains(artifactId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(artifactId + " not on test classpath"));
    return context().installBundle("file:" + jar);
  }

  public boolean resolve(List<Bundle> bundles) {
    return framework.adapt(org.osgi.framework.wiring.FrameworkWiring.class).resolveBundles(bundles);
  }
}
