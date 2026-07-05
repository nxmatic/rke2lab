package io.nxmatic.rke2lab.osgibench;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nxmatic.rke2lab.junit.testkit.OsgiWorld;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.OutOfContainerFrameworkExtension;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.osgi.framework.Bundle;

/**
 * P1 — the real Felix framework resolves the config bundle's {@code Require osgi.extender} against
 * the host bundle's {@code Provide}, and fails to resolve when the host is absent (loud, not a
 * silent empty closure). The real-engine successor to the hand-rolled {@code
 * ConfigExtenderResolutionSpike}.
 */
@OsgiWorld
class ExtenderContractTest {

  // Plain framework, no declared topology: each method installs its own bundle set and drives
  // resolution by hand (resolve, not start) — the two cases need different bundles.
  @RegisterExtension
  static final OutOfContainerFrameworkExtension felix =
      OutOfContainerFrameworkExtension.builder().build();

  @Test
  void configResolvesWhenHostProvidesTheExtenders() throws Exception {
    // Host + config selected by what they DECLARE (their role), never by a Bundle-SymbolicName a
    // test keeps in sync. Each role installed once; the config handle drives the per-bundle assert.
    Bundle host = felix.installMatching("(&(type=fixture)(suite=extender)(role=host))").get(0);
    Bundle config = felix.installMatching("(&(type=fixture)(suite=extender)(role=config))").get(0);

    boolean resolved = felix.resolve(List.of(host, config));

    assertTrue(resolved, "framework resolved the bundle set");
    assertTrue(
        config.getState() >= Bundle.RESOLVED && config.getState() != Bundle.INSTALLED,
        "config bundle wired to the extender-providing host");
  }

  @Test
  void configStaysUnresolvedWhenHostAbsent() throws Exception {
    // The consumer ALONE — omitting role=host is how the anti-cheat proves it stays unresolved.
    // Selection by declaration, so "no provider" is expressed IN the filter, not by not-naming it.
    List<Bundle> config = felix.installMatching("(&(type=fixture)(suite=extender)(role=config))");

    boolean resolved = felix.resolve(config);

    assertFalse(resolved, "no provider for osgi.extender — resolution refuses, loudly");
  }
}
