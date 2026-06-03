package io.nxmatic.rke2lab.cdk8s.systemd;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.constructs.Construct;

/**
 * A systemd service unit construct.
 *
 * <p><b>Example</b>:
 *
 * <pre>{@code
 * SystemdChart chart = new SystemdChart(app, "systemd");
 *
 * new SystemdService(chart, "cluster-api-manifests")
 *     .description("Install Cluster API manifests")
 *     .after("rke2-server.service")
 *     .requires("rke2-server.service")
 *     .type(ServiceType.ONESHOT)
 *     .execStart("/srv/host/systemd-scripts.d/rke2lab-manifests-install.sh clusterapi")
 *     .remainAfterExit(true)
 *     .standardOutput(StandardStream.JOURNAL)
 *     .wantedBy("rke2lab.target");
 * }</pre>
 */
public class SystemdService extends SystemdUnit {

  private final Map<String, List<String>> serviceSection = new LinkedHashMap<>();

  public SystemdService(Construct scope, String id) {
    super(scope, id, ensureSuffix(id, ".service"));
  }

  /**
   * Creates a one-shot installer service with the directives every rke2lab installer shares: {@code
   * Type=oneshot}, {@code RemainAfterExit=true}, host-share mounts, and journal logging. Callers
   * add only the phase-specific ordering ({@code Before=}/{@code After=}), condition, exec, and
   * target wiring.
   */
  public static SystemdService oneshotInstaller(Construct scope, String id) {
    return new SystemdService(scope, id)
        .type(ServiceType.ONESHOT)
        .remainAfterExit(true)
        .requiresMountsFor("/srv/host/systemd-units.d", "/srv/host")
        .standardOutput(StandardStream.JOURNAL)
        .standardError(StandardStream.JOURNAL);
  }

  private static String ensureSuffix(String name, String suffix) {
    return name.endsWith(suffix) ? name : name + suffix;
  }

  // === [Service] Section ===

  /** Sets the Type directive. */
  public SystemdService type(ServiceType type) {
    addServiceDirective("Type", type.value());
    return this;
  }

  /** Adds ExecStart directive. */
  public SystemdService execStart(String command) {
    addServiceDirective("ExecStart", command);
    return this;
  }

  /** Adds ExecStartPre directive. */
  public SystemdService execStartPre(String command) {
    addServiceDirective("ExecStartPre", command);
    return this;
  }

  /** Adds ExecStartPost directive. */
  public SystemdService execStartPost(String command) {
    addServiceDirective("ExecStartPost", command);
    return this;
  }

  /** Sets RemainAfterExit directive. */
  public SystemdService remainAfterExit(boolean value) {
    addServiceDirective("RemainAfterExit", value ? "true" : "false");
    return this;
  }

  /** Sets StandardOutput directive. */
  public SystemdService standardOutput(StandardStream stream) {
    addServiceDirective("StandardOutput", stream.value());
    return this;
  }

  /** Sets StandardError directive. */
  public SystemdService standardError(StandardStream stream) {
    addServiceDirective("StandardError", stream.value());
    return this;
  }

  /** Sets Restart directive. */
  public SystemdService restart(RestartPolicy policy) {
    addServiceDirective("Restart", policy.value());
    return this;
  }

  /** Sets RestartSec directive (in seconds). */
  public SystemdService restartSec(int seconds) {
    addServiceDirective("RestartSec", String.valueOf(seconds));
    return this;
  }

  /** Sets StartLimitIntervalSec directive. */
  public SystemdService startLimitIntervalSec(int seconds) {
    addServiceDirective("StartLimitIntervalSec", String.valueOf(seconds));
    return this;
  }

  /** Sets StartLimitBurst directive. */
  public SystemdService startLimitBurst(int count) {
    addServiceDirective("StartLimitBurst", String.valueOf(count));
    return this;
  }

  /** Sets TimeoutStopSec directive. */
  public SystemdService timeoutStopSec(int seconds) {
    addServiceDirective("TimeoutStopSec", String.valueOf(seconds));
    return this;
  }

  /** Sets KillMode directive. */
  public SystemdService killMode(KillMode mode) {
    addServiceDirective("KillMode", mode.value());
    return this;
  }

  /** Sets SuccessExitStatus directive. */
  public SystemdService successExitStatus(String status) {
    addServiceDirective("SuccessExitStatus", status);
    return this;
  }

  private void addServiceDirective(String key, String value) {
    serviceSection.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
  }

  @Override
  protected void writeTypeSpecificSection(Writer writer) throws IOException {
    if (!serviceSection.isEmpty()) {
      writer.write("[Service]\n");
      writeSection(writer, serviceSection);
    }
  }

  // === Override base methods to return SystemdService for fluent API ===

  @Override
  public SystemdService description(String description) {
    super.description(description);
    return this;
  }

  @Override
  public SystemdService documentation(String... urls) {
    super.documentation(urls);
    return this;
  }

  @Override
  public SystemdService after(String... units) {
    super.after(units);
    return this;
  }

  @Override
  public SystemdService before(String... units) {
    super.before(units);
    return this;
  }

  @Override
  public SystemdService requires(String... units) {
    super.requires(units);
    return this;
  }

  @Override
  public SystemdService wants(String... units) {
    super.wants(units);
    return this;
  }

  @Override
  public SystemdService conflicts(String... units) {
    super.conflicts(units);
    return this;
  }

  @Override
  public SystemdService requiresMountsFor(String... paths) {
    super.requiresMountsFor(paths);
    return this;
  }

  @Override
  public SystemdService conditionPathExists(String... paths) {
    super.conditionPathExists(paths);
    return this;
  }

  @Override
  public SystemdService defaultDependencies(boolean value) {
    super.defaultDependencies(value);
    return this;
  }

  @Override
  public SystemdService wantedBy(String... targets) {
    super.wantedBy(targets);

    // Auto-register with SystemdChart for reciprocal Wants= in targets
    var scope = getNode().getScope();
    if (scope instanceof SystemdChart chart) {
      for (String target : targets) {
        chart.registerServiceWithTarget(getUnitFileName(), target);
      }
    }

    return this;
  }

  @Override
  public SystemdService requiredBy(String... targets) {
    super.requiredBy(targets);
    return this;
  }

  @Override
  public SystemdService partOf(String... units) {
    super.partOf(units);
    return this;
  }

  @Override
  public SystemdService also(String... units) {
    super.also(units);
    return this;
  }

  // === Enums ===

  public enum ServiceType {
    SIMPLE("simple"),
    FORKING("forking"),
    ONESHOT("oneshot"),
    DBUS("dbus"),
    NOTIFY("notify"),
    IDLE("idle");

    private final String value;

    ServiceType(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  public enum StandardStream {
    JOURNAL("journal"),
    INHERIT("inherit"),
    NULL("null"),
    TTY("tty"),
    SOCKET("socket");

    private final String value;

    StandardStream(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  public enum RestartPolicy {
    NO("no"),
    ALWAYS("always"),
    ON_SUCCESS("on-success"),
    ON_FAILURE("on-failure"),
    ON_ABNORMAL("on-abnormal"),
    ON_ABORT("on-abort"),
    ON_WATCHDOG("on-watchdog");

    private final String value;

    RestartPolicy(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }

  public enum KillMode {
    CONTROL_GROUP("control-group"),
    PROCESS("process"),
    MIXED("mixed"),
    NONE("none");

    private final String value;

    KillMode(String value) {
      this.value = value;
    }

    public String value() {
      return value;
    }
  }
}
