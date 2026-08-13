package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import com.tngtech.jgiven.impl.Config;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;

/**
 * Keeps jGiven's plain-text scenario report OFF the console for the whole run. The seeding engine
 * harvests the {@link com.tngtech.jgiven.report.model.ReportModel} programmatically ({@link
 * ScenarioOutcomeExtension}) and the framework logs to its file appender, so jGiven's default
 * console dump is pure noise — and it corrupts a CLI whose stdout IS the product (netplan-cli's
 * blueprint YAML). jGiven prints it from {@code JGivenExtension.afterAll} via {@code
 * CommonReportHelper.finishReport}, gated on {@code Config.config().textReport()} (which
 * reads/writes the {@code jgiven.report.text} system property).
 *
 * <p>This extension BRACKETS the jGiven lifecycle: {@link #beforeAll} disables the text report,
 * {@link #afterAll} restores the prior value — so no global state leaks past the scenario scope.
 * The bracket is correct ONLY if it is registered FIRST on {@code @SeedScenario} (before {@code
 * JGivenExtension}): Jupiter runs {@code beforeAll} in registration order (this disables before
 * jGiven starts) and {@code afterAll} in REVERSE order (this restores AFTER {@code
 * JGivenExtension.afterAll} has already emitted the report with the report still disabled). Ordered
 * anywhere after jGiven, the restore would run first and jGiven would print. Nested scion scopes
 * compose: each brackets its own realm's value.
 */
public final class ConsoleReportExtension implements BeforeAllCallback, AfterAllCallback {

  private static final Namespace NAMESPACE = Namespace.create(ConsoleReportExtension.class);
  private static final String PRIOR_TEXT_REPORT = "prior-text-report";

  @Override
  public void beforeAll(ExtensionContext context) {
    final Config config = Config.config();
    context.getStore(NAMESPACE).put(PRIOR_TEXT_REPORT, config.textReport());
    config.setTextReport(false);
  }

  @Override
  public void afterAll(ExtensionContext context) {
    final Boolean prior = context.getStore(NAMESPACE).get(PRIOR_TEXT_REPORT, Boolean.class);
    if (prior != null) {
      Config.config().setTextReport(prior);
    }
  }
}
