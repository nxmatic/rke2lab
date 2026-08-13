package io.seedmatic.rke2lab.osgi.boot.discovery;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The topology a boot wants, stated as pure inputs to {@link BootPlanner} — the executor's builder
 * fields, lifted out of the launcher so the plan can be computed (and asserted) without a
 * framework. Two topologies feed the SAME planner:
 *
 * <ul>
 *   <li>the CLASSPATH topology names its boot stack + model bundles explicitly ({@link
 *       #paxLogging}, {@link #runtimeBundle}, {@link #model}) — a reactor test pinning an exact
 *       set;
 *   <li>the EMBEDDED topology DISCOVERS them: {@link #embedBootStack()} lets {@link BootPlanner}
 *       select from the index via {@link #discoveryPolicy}, the deployed exec-jar's staged stack.
 * </ul>
 *
 * Either way every bundle is a {@link BundleLocation}, so the planner's assembly is
 * source-agnostic.
 */
public final class BootRequest {

  private final List<BundleLocation> paxLoggingBundles = new ArrayList<>();
  private final List<BundleLocation> runtimeBundles = new ArrayList<>();
  private final List<BundleLocation> modelBundles = new ArrayList<>();
  private final Set<String> systemPackages = new LinkedHashSet<>();
  private boolean embedsBootStack;
  private DiscoveryPolicy discoveryPolicy = DiscoveryPolicy.all();

  public static BootRequest create() {
    return new BootRequest();
  }

  /** Pax Logging (api then backend, in order) installed at the logging layer. */
  public BootRequest paxLogging(BundleLocation paxLoggingApi, BundleLocation paxLoggingLogback) {
    this.paxLoggingBundles.add(paxLoggingApi);
    this.paxLoggingBundles.add(paxLoggingLogback);
    return this;
  }

  /** A felix runtime bundle (e.g. {@code org.apache.felix.scr}) at the framework-runtime layer. */
  public BootRequest runtimeBundle(BundleLocation bundle) {
    this.runtimeBundles.add(bundle);
    return this;
  }

  /** A model/edge bundle at the bundle layer; its {@code Import-Package} feeds system-exports. */
  public BootRequest model(BundleLocation bundle) {
    this.modelBundles.add(bundle);
    return this;
  }

  /** Discover the whole stack from the index (deployed exec-jar) rather than naming it. */
  public BootRequest embedBootStack() {
    this.embedsBootStack = true;
    return this;
  }

  /** How the embedded boot selects which discovered bundles to install (default {@code all()}). */
  public BootRequest discover(DiscoveryPolicy policy) {
    this.discoveryPolicy = policy;
    return this;
  }

  /** Extra packages to system-export beyond those derived from the model bundles' imports. */
  public BootRequest systemPackages(Set<String> packages) {
    this.systemPackages.addAll(packages);
    return this;
  }

  List<BundleLocation> paxLoggingBundles() {
    return paxLoggingBundles;
  }

  List<BundleLocation> runtimeBundles() {
    return runtimeBundles;
  }

  List<BundleLocation> modelBundles() {
    return modelBundles;
  }

  Set<String> explicitSystemPackages() {
    return systemPackages;
  }

  boolean embedsBootStack() {
    return embedsBootStack;
  }

  DiscoveryPolicy discoveryPolicy() {
    return discoveryPolicy;
  }
}
