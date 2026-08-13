package io.seedmatic.rke2lab.osgi.runtime.scenario.engine;

import static org.junit.jupiter.api.Assertions.fail;

import io.seedmatic.rke2lab.osgi.runtime.junit.launcher.InContainerJUnitRunner;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.osgi.framework.Bundle;

/**
 * The single in-container driver every {@code *InContainerTest} proxy shares: resolve a provisioned
 * bundle graph, start the host, run the in-framework JUnit launcher reflectively, and fan each
 * encoded result out to one {@link DynamicTest}. It replaces the resolve→start→run→map block that
 * was copied verbatim across the domain proxies — the ONE thing that varies (which fixtures to
 * install, what to close the import graph over, whether the whole graph must be started) is what
 * the caller's {@link Provisioning} lambda decides; everything after it is identical, so it lives
 * here.
 *
 * <p>jGiven-agnostic on purpose: it only resolves bundles and reflects a no-arg {@code static
 * List<String> run()} off the host loader (the front-door contract every {@code *Tests} runner
 * honours). No jGiven type crosses — the encoded strings are flat.
 */
public final class InContainerScenarios {

  private InContainerScenarios() {}

  /**
   * The bundle graph a proxy provisioned: the host to start, the set to resolve, whether to start
   * it whole.
   */
  public record Provisioning(Bundle host, List<Bundle> toResolve, boolean startWholeGraph) {}

  /**
   * Installs+selects the bundle graph for one proxy. A checked-throwing {@link
   * java.util.function.Function} — the install/closure primitives declare {@code throws Exception},
   * which {@code Function} cannot carry.
   */
  @FunctionalInterface
  public interface Provisioner {
    Provisioning provision(OutOfContainerFrameworkExtension felix) throws Exception;
  }

  /**
   * Drive the in-container run for {@code runnerFqn}: apply {@code provision} to install+select the
   * graph, resolve it (failing with the still-unresolved bundles named), start it (the whole graph
   * when the provisioning asks — a scenario resolving REAL SCR services needs its siblings ACTIVE,
   * not merely resolved), then load {@code runnerFqn} through the host loader and invoke its no-arg
   * {@code run()}. Each returned encoded string maps to one {@link DynamicTest}, so VSCode shows a
   * node per in-container test and a single failure fails alone.
   */
  public static Stream<DynamicTest> drive(
      OutOfContainerFrameworkExtension felix, String runnerFqn, Provisioner provisioner)
      throws Exception {
    final Provisioning provisioning = provisioner.provision(felix);
    final List<Bundle> toResolve = provisioning.toResolve();
    if (!felix.resolve(toResolve)) {
      final StringBuilder states = new StringBuilder();
      for (Bundle bundle : toResolve) {
        if ((bundle.getState() & Bundle.RESOLVED) == 0) {
          states
              .append("\n  UNRESOLVED ")
              .append(bundle.getSymbolicName())
              .append(" [")
              .append(bundle.getBundleId())
              .append("]");
        }
      }
      fail(runnerFqn + " must resolve in-container" + states);
    }
    if (provisioning.startWholeGraph()) {
      felix.startAll(toResolve);
    }
    provisioning.host().start();

    final Class<?> runner = provisioning.host().loadClass(runnerFqn);
    final Method run = runner.getMethod("run");
    @SuppressWarnings("unchecked")
    final List<String> results = (List<String>) run.invoke(null);

    if (results.isEmpty()) {
      fail("no in-container test was discovered — the jupiter engine did not attach");
    }

    return results.stream().map(InContainerScenarios::toDynamicTest);
  }

  private static DynamicTest toDynamicTest(String encoded) {
    final String[] parts = encoded.split(InContainerJUnitRunner.SEP, 3);
    final String status = parts[0];
    final String displayName = parts.length > 1 ? parts[1] : "(unnamed)";
    return DynamicTest.dynamicTest(
        displayName,
        () -> {
          if ("FAIL".equals(status)) {
            fail(parts.length > 2 ? parts[2] : "in-container test failed");
          }
        });
  }
}
