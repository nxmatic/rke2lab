package io.nxmatic.rke2lab.cdk8s.systemd;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.constructs.Construct;

/**
 * Base class for systemd unit constructs.
 *
 * <p>All systemd units (services, targets, timers, etc.) extend this class and become part of the
 * CDK8s construct tree.
 *
 * <p>Provides common [Unit] and [Install] section builders.
 */
public abstract class SystemdUnit extends Construct {

  private final String unitFileName;
  protected final Map<String, List<String>> unitSection = new LinkedHashMap<>();
  protected final Map<String, List<String>> installSection = new LinkedHashMap<>();

  protected SystemdUnit(Construct scope, String id, String unitFileName) {
    super(scope, id);
    this.unitFileName = unitFileName;

    // Register with parent SystemdChart
    if (scope instanceof SystemdChart chart) {
      chart.registerUnit(this);
    }
  }

  // === [Unit] Section ===

  /** Sets the Description directive. */
  public SystemdUnit description(String description) {
    addUnitDirective("Description", description);
    return this;
  }

  /** Sets the Documentation directive (can be called multiple times). */
  public SystemdUnit documentation(String... urls) {
    for (String url : urls) {
      addUnitDirective("Documentation", url);
    }
    return this;
  }

  /** Adds After dependencies (ordering). */
  public SystemdUnit after(String... units) {
    for (String unit : units) {
      addUnitDirective("After", unit);
    }
    return this;
  }

  /** Adds Before dependencies (ordering). */
  public SystemdUnit before(String... units) {
    for (String unit : units) {
      addUnitDirective("Before", unit);
    }
    return this;
  }

  /** Adds Requires dependencies (strict requirement). */
  public SystemdUnit requires(String... units) {
    for (String unit : units) {
      addUnitDirective("Requires", unit);
    }
    return this;
  }

  /** Adds Wants dependencies (weak requirement). */
  public SystemdUnit wants(String... units) {
    for (String unit : units) {
      addUnitDirective("Wants", unit);
    }
    return this;
  }

  /** Adds Conflicts directive (negative dependency). */
  public SystemdUnit conflicts(String... units) {
    for (String unit : units) {
      addUnitDirective("Conflicts", unit);
    }
    return this;
  }

  /** Adds RequiresMountsFor directive. */
  public SystemdUnit requiresMountsFor(String... paths) {
    addUnitDirective("RequiresMountsFor", String.join(" ", paths));
    return this;
  }

  /** Adds ConditionPathExists directive. */
  public SystemdUnit conditionPathExists(String... paths) {
    for (String path : paths) {
      addUnitDirective("ConditionPathExists", path);
    }
    return this;
  }

  /** Sets DefaultDependencies directive. */
  public SystemdUnit defaultDependencies(boolean value) {
    addUnitDirective("DefaultDependencies", value ? "yes" : "no");
    return this;
  }

  // === [Install] Section ===

  /** Adds WantedBy directive. */
  public SystemdUnit wantedBy(String... targets) {
    for (String target : targets) {
      addInstallDirective("WantedBy", target);
    }
    return this;
  }

  /** Adds RequiredBy directive. */
  public SystemdUnit requiredBy(String... targets) {
    for (String target : targets) {
      addInstallDirective("RequiredBy", target);
    }
    return this;
  }

  // === Helper Methods ===

  protected void addUnitDirective(String key, String value) {
    unitSection.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
  }

  protected void addInstallDirective(String key, String value) {
    installSection.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
  }

  protected void writeSection(Writer writer, Map<String, List<String>> section) throws IOException {
    for (Map.Entry<String, List<String>> entry : section.entrySet()) {
      for (String value : entry.getValue()) {
        writer.write(entry.getKey());
        writer.write("=");
        writer.write(value);
        writer.write("\n");
      }
    }
  }

  // === Synthesis ===

  /**
   * Writes the complete unit file content.
   *
   * <p>Called by {@link SystemdChart#synthesize(java.nio.file.Path)}.
   */
  void writeUnitFile(Writer writer) throws IOException {
    writer.write("[Unit]\n");
    writeSection(writer, unitSection);
    writer.write("\n");

    writeTypeSpecificSection(writer);

    if (!installSection.isEmpty()) {
      writer.write("\n[Install]\n");
      writeSection(writer, installSection);
    }
  }

  /**
   * Writes type-specific sections (e.g., [Service] for services).
   *
   * <p>Subclasses override this to add their specific sections.
   */
  protected abstract void writeTypeSpecificSection(Writer writer) throws IOException;

  /** Returns the unit file name (e.g., "my-service.service"). */
  public String getUnitFileName() {
    return unitFileName;
  }

  /** Returns the construct ID (unit name without suffix). */
  public String getUnitId() {
    return getNode().getId();
  }
}
