package io.nxmatic.rke2lab.scenario.probe;

import com.tngtech.jgiven.impl.Scenario;
import com.tngtech.jgiven.report.model.ReportModel;

/**
 * The guard's in-container entry point, called reflectively by the harness THROUGH THE HOST
 * BUNDLE'S classloader (the fragment has no activator — fragments cannot be started). It drives a
 * trivial jGiven scenario end to end: {@code given a vault, when 100 is deposited, then the balance
 * is 100}. Returning a plain {@link String} keeps every jGiven type inside the bundle world — the
 * harness, in the host/app world, never shares a jGiven class, so the guard needs no system-package
 * export of {@code com.tngtech.jgiven.*}.
 *
 * <p>This is where byte-buddy fires: {@code Scenario.create} builds a proxy subclass of {@link
 * VaultStage} via byte-buddy's INJECTION strategy into VaultStage's classloader (the host bundle).
 * It loads only because the fragment contributed {@code net.bytebuddy.*} to that host. A real host
 * authors the exact same shape — this runner is the template.
 */
public final class VaultScenarioRunner {

  private VaultScenarioRunner() {}

  /**
   * @return {@code "OK"} when the scenario passes, or {@code "FAIL: <message>"} otherwise.
   */
  public static String run() {
    try {
      Scenario<VaultStage, VaultStage, VaultStage> scenario = Scenario.create(VaultStage.class);
      // Standalone use must supply the report model the JUnit integration would otherwise inject;
      // without it scenarioFinished() NPEs. Not an OSGi concern — plain jGiven embedding API.
      scenario.setModel(new ReportModel());
      scenario.startScenario("a deposit increases the balance");

      scenario.given().a_vault();
      scenario.when().$_is_deposited(100);
      scenario.then().the_balance_is(100);

      scenario.finished();
      return "OK";
    } catch (Throwable t) {
      StringBuilder chain = new StringBuilder("FAIL:");
      for (Throwable c = t; c != null; c = c.getCause()) {
        chain.append(" [").append(c.getClass().getName()).append(": ").append(c.getMessage());
        if (c.getStackTrace().length > 0) {
          chain.append(" @ ").append(c.getStackTrace()[0]);
        }
        chain.append(']');
      }
      return chain.toString();
    }
  }
}
