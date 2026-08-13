package io.seedmatic.rke2lab.osgi.runtime.scenario.engine;

import io.seedmatic.rke2lab.osgi.boot.discovery.BootPlan;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The ISOLATION teardown strategy (spec Figure 5, top lane): each case starts clean. On top of the
 * shared {@link BaseWorldExtension} (connect + climb to the bundle level), the method scope
 * SAWTOOTHS — {@code afterEach} descends the cursor to the framework-runtime level (transiently
 * stopping the domain bundles), {@code beforeEach} re-ascends to the bundle level (re-lighting them
 * from autostart). Pulled by {@link IsolatedWorld}.
 */
public final class IsolatedWorldExtension extends BaseWorldExtension
    implements BeforeEachCallback, AfterEachCallback {

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    lever(context).raiseTo(BootPlan.START_LEVEL_BUNDLES);
  }

  @Override
  public void afterEach(ExtensionContext context) throws Exception {
    lever(context).descendTo(BootPlan.START_LEVEL_FRAMEWORK_RUNTIME);
  }
}
