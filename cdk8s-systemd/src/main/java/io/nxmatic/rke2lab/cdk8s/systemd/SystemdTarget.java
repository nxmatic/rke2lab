package io.nxmatic.rke2lab.cdk8s.systemd;

import java.io.IOException;
import java.io.Writer;
import software.constructs.Construct;

/**
 * A systemd target unit construct.
 *
 * <p>Targets are grouping mechanisms for units with no execution behavior of their own.
 *
 * <p><b>Example</b>:
 *
 * <pre>{@code
 * new SystemdTarget(chart, "rke2lab-tools")
 *     .description("RKE2 Lab Tools Installation Target")
 *     .after("rke2lab-network.target")
 *     .wants("rke2lab-nix-install.service", "rke2lab-flox-install.service")
 *     .wantedBy("rke2lab.target");
 * }</pre>
 */
public class SystemdTarget extends SystemdUnit {

  public SystemdTarget(Construct scope, String id) {
    super(scope, id, ensureSuffix(id, ".target"));
  }

  private static String ensureSuffix(String name, String suffix) {
    return name.endsWith(suffix) ? name : name + suffix;
  }

  @Override
  protected void writeTypeSpecificSection(Writer writer) throws IOException {
    // Targets have no type-specific section (no [Service] or equivalent)
  }

  // === Override base methods to return SystemdTarget for fluent API ===

  @Override
  public SystemdTarget description(String description) {
    super.description(description);
    return this;
  }

  @Override
  public SystemdTarget documentation(String... urls) {
    super.documentation(urls);
    return this;
  }

  @Override
  public SystemdTarget after(String... units) {
    super.after(units);
    return this;
  }

  @Override
  public SystemdTarget before(String... units) {
    super.before(units);
    return this;
  }

  @Override
  public SystemdTarget requires(String... units) {
    super.requires(units);
    return this;
  }

  @Override
  public SystemdTarget wants(String... units) {
    super.wants(units);
    return this;
  }

  @Override
  public SystemdTarget requiresMountsFor(String... paths) {
    super.requiresMountsFor(paths);
    return this;
  }

  @Override
  public SystemdTarget conditionPathExists(String... paths) {
    super.conditionPathExists(paths);
    return this;
  }

  @Override
  public SystemdTarget defaultDependencies(boolean value) {
    super.defaultDependencies(value);
    return this;
  }

  @Override
  public SystemdTarget wantedBy(String... targets) {
    super.wantedBy(targets);
    return this;
  }

  @Override
  public SystemdTarget requiredBy(String... targets) {
    super.requiredBy(targets);
    return this;
  }
}
