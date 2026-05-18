package io.nxmatic.rk2lab.controlplane;

import com.pulumi.command.local.Command;
import com.pulumi.command.local.CommandArgs;
import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.CustomResourceOptions;
import com.pulumi.resources.Resource;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Component resource that captures seed-node systemd runtime status via the Pulumi command
 * provider.
 */
public final class SeedSystemdRuntimeStatusResource extends ComponentResource {

  private static final String TYPE_TOKEN = "rk2lab:controlplane:SeedSystemdRuntimeStatus";

  private static final Duration STANDALONE_TIMEOUT = Duration.ofSeconds(20);

  private static final String MANDATORY_TARGET = "rke2lab.target";

  private final Output<Map<String, Object>> summary;

  public SeedSystemdRuntimeStatusResource(
      String name,
      BootstrapConfig config,
      String provisioningChecksum,
      String imageBuildChecksum,
      Resource dependsOnResource) {
    super(TYPE_TOKEN, name, buildOptions(dependsOnResource));

    final Command probe =
        new Command(
            name + "-probe",
            CommandArgs.builder()
                .create(buildProbeCommand(config))
                .triggers(
                    provisioningChecksum,
                    imageBuildChecksum,
                    config.incusProject(),
                    config.nodeName())
                .build(),
            buildCommandOptions(dependsOnResource));

    this.summary = probe.stdout().applyValue(SeedSystemdRuntimeStatusResource::parseSummary);
    registerOutputs(asResourceOutputs(summary));
  }

  public Output<Map<String, Object>> summary() {
    return summary;
  }

  public static Map<String, Object> snapshotStandalone(BootstrapConfig config) {
    final ProcessBuilder processBuilder =
        new ProcessBuilder("sh", "-lc", buildProbeCommand(config));
    processBuilder.environment().putIfAbsent("LANG", "C");

    try {
      final Process process = processBuilder.start();
      final boolean exited = process.waitFor(STANDALONE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!exited) {
        process.destroyForcibly();
        return Map.of(
            "source",
            "standalone",
            "status",
            "timeout",
            "summary",
            "runtime probe timed out after " + STANDALONE_TIMEOUT,
            "capturedAt",
            Instant.now().toString());
      }

      final String stdout =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      final String stderr =
          new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      final Map<String, Object> parsed = parseSummary(stdout);
      if (process.exitValue() == 0) {
        return parsed;
      }

      final LinkedHashMap<String, Object> failed = new LinkedHashMap<>(parsed);
      failed.put("status", "command-failed");
      failed.put("exitCode", process.exitValue());
      failed.put("stderr", summarizeFirstLine(stderr));
      failed.put("summary", "runtime probe command failed");
      return Map.copyOf(failed);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return Map.of(
          "source", "standalone",
          "status", "interrupted",
          "summary", "runtime probe interrupted",
          "capturedAt", Instant.now().toString());
    } catch (IOException ex) {
      return Map.of(
          "source",
          "standalone",
          "status",
          "execution-error",
          "summary",
          "runtime probe execution error: " + ex.getMessage(),
          "capturedAt",
          Instant.now().toString());
    }
  }

  private static String buildProbeCommand(BootstrapConfig config) {
    final String mandatoryTargetLine =
        "echo mandatoryTarget="
            + MANDATORY_TARGET
            + "|$(systemctl is-active "
            + MANDATORY_TARGET
            + " 2>/dev/null || true)";

    final ArrayList<String> scriptLines = new ArrayList<>();
    scriptLines.add("set -eu");
    scriptLines.add(
        "if test -d /srv/host/rke2lab-environment.d; then echo envDirPresent=true; else echo envDirPresent=false; fi");
    scriptLines.add(
        "echo pendingJobCount=$(systemctl list-jobs --no-legend --no-pager 2>/dev/null | wc -l | tr -d ' ')");
    scriptLines.add(
        "echo failedUnitCount=$(systemctl list-units --failed --no-legend --no-pager 2>/dev/null | wc -l | tr -d ' ')");
    scriptLines.add(mandatoryTargetLine);
    scriptLines.add(
        "systemctl list-jobs --no-legend --no-pager 2>/dev/null | awk 'NF >= 4 {print \"job=\"$2\"|\"$3\"|\"$4}'");
    scriptLines.add("echo capturedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)");

    final String script = String.join("\n", scriptLines);

    final String remoteIncusCommand =
        "incus exec --project "
            + shellQuote(config.incusProject())
            + " "
            + shellQuote(config.nodeName())
            + " -- sh -lc "
            + shellQuote(script);

    return "ssh -o BatchMode=yes -o ConnectTimeout=10 "
        + shellQuote(config.imageBuilderHost())
        + " sh -lc "
        + shellQuote(remoteIncusCommand);
  }

  private static ComponentResourceOptions buildOptions(Resource dependsOnResource) {
    final ComponentResourceOptions.Builder optionsBuilder = ComponentResourceOptions.builder();
    if (dependsOnResource != null) {
      optionsBuilder.dependsOn(List.of(dependsOnResource));
    }
    return optionsBuilder.build();
  }

  private static CustomResourceOptions buildCommandOptions(Resource dependsOnResource) {
    final CustomResourceOptions.Builder optionsBuilder = CustomResourceOptions.builder();
    if (dependsOnResource != null) {
      optionsBuilder.dependsOn(List.of(dependsOnResource));
    }
    return optionsBuilder.build();
  }

  private static Map<String, Output<?>> asResourceOutputs(Output<Map<String, Object>> summary) {
    final LinkedHashMap<String, Output<?>> outputs = new LinkedHashMap<>();
    outputs.put("summary", summary);
    outputs.put(
        "envDirPresent", summary.applyValue(value -> value.getOrDefault("envDirPresent", false)));
    outputs.put(
        "pendingJobCount", summary.applyValue(value -> value.getOrDefault("pendingJobCount", -1)));
    outputs.put(
        "failedUnitCount", summary.applyValue(value -> value.getOrDefault("failedUnitCount", -1)));
    outputs.put(
        "mandatoryTarget",
        summary.applyValue(
            value ->
                value.getOrDefault(
                    "mandatoryTarget", Map.of("unit", MANDATORY_TARGET, "state", "unknown"))));
    outputs.put(
        "mandatoryTargetHealthy",
        summary.applyValue(value -> value.getOrDefault("mandatoryTargetHealthy", false)));
    outputs.put("jobs", summary.applyValue(value -> value.getOrDefault("jobs", List.of())));
    outputs.put(
        "jobsByState", summary.applyValue(value -> value.getOrDefault("jobsByState", Map.of())));
    outputs.put(
        "runtimePrecheckReady",
        summary.applyValue(value -> value.getOrDefault("runtimePrecheckReady", false)));
    return outputs;
  }

  private static Map<String, Object> parseSummary(String stdout) {
    final LinkedHashMap<String, Object> parsed = new LinkedHashMap<>();
    parsed.put("source", "pulumi-command:local");
    parsed.put("status", "ok");
    final ArrayList<Map<String, String>> jobs = new ArrayList<>();
    final LinkedHashMap<String, List<String>> jobsByState = new LinkedHashMap<>();
    Map<String, String> mandatoryTarget = Map.of("unit", MANDATORY_TARGET, "state", "unknown");

    if (stdout != null && !stdout.isBlank()) {
      for (String rawLine : stdout.lines().toList()) {
        final String line = rawLine.trim();
        if (line.isBlank()) {
          continue;
        }

        final int delimiterIndex = line.indexOf('=');
        if (delimiterIndex <= 0) {
          continue;
        }

        final String key = line.substring(0, delimiterIndex).trim();
        final String value = line.substring(delimiterIndex + 1).trim();
        if ("job".equals(key)) {
          final Map<String, String> job = parseJob(value);
          jobs.add(job);
          final String state = job.get("state");
          final String unit = job.get("unit");
          jobsByState.computeIfAbsent(state, ignored -> new ArrayList<>()).add(unit);
          continue;
        }
        if ("mandatoryTarget".equals(key)) {
          mandatoryTarget = parseMandatoryTarget(value);
          continue;
        }
        if (!key.isBlank()) {
          parsed.put(key, value);
        }
      }
    }

    final boolean envDirPresent = Boolean.parseBoolean(stringValue(parsed.get("envDirPresent")));
    final int pendingJobCount = parseIntOrDefault(stringValue(parsed.get("pendingJobCount")), -1);
    final int failedUnitCount = parseIntOrDefault(stringValue(parsed.get("failedUnitCount")), -1);
    final boolean mandatoryTargetHealthy = isMandatoryTargetHealthy(mandatoryTarget.get("state"));

    parsed.put("envDirPresent", envDirPresent);
    parsed.put("pendingJobCount", pendingJobCount);
    parsed.put("failedUnitCount", failedUnitCount);
    parsed.put("mandatoryTarget", mandatoryTarget);
    parsed.put("mandatoryTargetHealthy", mandatoryTargetHealthy);
    parsed.put("jobs", List.copyOf(jobs));
    final LinkedHashMap<String, List<String>> immutableJobsByState = new LinkedHashMap<>();
    jobsByState.forEach((state, units) -> immutableJobsByState.put(state, List.copyOf(units)));
    parsed.put("jobsByState", Map.copyOf(immutableJobsByState));
    parsed.put(
        "runtimePrecheckReady",
        envDirPresent
            && mandatoryTargetHealthy
            && (pendingJobCount == 0)
            && (failedUnitCount == 0));
    parsed.put(
        "summary",
        buildSummary(envDirPresent, pendingJobCount, failedUnitCount, mandatoryTarget, jobs));

    if (!parsed.containsKey("capturedAt")) {
      parsed.put("capturedAt", Instant.now().toString());
    }

    return Map.copyOf(parsed);
  }

  private static String buildSummary(
      boolean envDirPresent,
      int pendingJobCount,
      int failedUnitCount,
      Map<String, String> mandatoryTarget,
      List<Map<String, String>> jobs) {
    return "envDir="
        + (envDirPresent ? "ready" : "missing")
        + ", pendingJobs="
        + pendingJobCount
        + ", failedUnits="
        + failedUnitCount
        + ", mandatoryTarget="
        + summarizeMandatoryTarget(mandatoryTarget)
        + ", jobsTop="
        + summarizeJobs(jobs, 5);
  }

  private static Map<String, String> parseMandatoryTarget(String encodedTarget) {
    if (encodedTarget == null || encodedTarget.isBlank()) {
      return Map.of("unit", MANDATORY_TARGET, "state", "unknown");
    }

    final String[] parts = encodedTarget.split("\\|", 2);
    final String unit = parts.length > 0 && !parts[0].isBlank() ? parts[0] : MANDATORY_TARGET;
    final String state = parts.length > 1 && !parts[1].isBlank() ? parts[1] : "unknown";
    return Map.of("unit", unit, "state", state);
  }

  private static Map<String, String> parseJob(String encodedJob) {
    if (encodedJob == null || encodedJob.isBlank()) {
      return Map.of("unit", "unknown", "type", "unknown", "state", "unknown");
    }

    final String[] parts = encodedJob.split("\\|", 3);
    final String unit = parts.length > 0 && !parts[0].isBlank() ? parts[0] : "unknown";
    final String type = parts.length > 1 && !parts[1].isBlank() ? parts[1] : "unknown";
    final String state = parts.length > 2 && !parts[2].isBlank() ? parts[2] : "unknown";
    return Map.of("unit", unit, "type", type, "state", state);
  }

  private static String summarizeJobs(List<Map<String, String>> jobs, int max) {
    if (jobs == null || jobs.isEmpty() || max <= 0) {
      return "none";
    }

    final int size = Math.min(max, jobs.size());
    final ArrayList<String> tokens = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      final Map<String, String> job = jobs.get(i);
      tokens.add(job.get("unit") + "(" + job.get("state") + ")");
    }
    if (jobs.size() > size) {
      tokens.add("+" + (jobs.size() - size) + " more");
    }
    return String.join(",", tokens);
  }

  private static String summarizeMandatoryTarget(Map<String, String> mandatoryTarget) {
    if (mandatoryTarget == null || mandatoryTarget.isEmpty()) {
      return "none";
    }

    final String unit = mandatoryTarget.getOrDefault("unit", MANDATORY_TARGET);
    final String state = mandatoryTarget.getOrDefault("state", "unknown");
    return unit + "=" + state;
  }

  private static boolean isMandatoryTargetHealthy(String mandatoryTargetState) {
    final String normalizedState =
        mandatoryTargetState == null ? "" : mandatoryTargetState.trim().toLowerCase();
    return "active".equals(normalizedState);
  }

  private static String summarizeFirstLine(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return value.lines().map(String::trim).filter(line -> !line.isBlank()).findFirst().orElse("");
  }

  private static String stringValue(Object value) {
    return value == null ? "" : value.toString();
  }

  private static int parseIntOrDefault(String value, int fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }
}
