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
    final String script =
        String.join(
            "\n",
            "set -eu",
            "if test -d /srv/host/rke2lab-environment.d; then echo envDirPresent=true; else echo envDirPresent=false; fi",
            "echo rke2ServerState=$(systemctl is-active rke2-server.service 2>/dev/null || true)",
            "echo pendingJobCount=$(systemctl list-jobs --no-legend --no-pager 2>/dev/null | wc -l | tr -d ' ')",
            "echo failedUnitCount=$(systemctl show --property=NFailedUnits --value 2>/dev/null || true)",
            "echo capturedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)");

    return "incus exec --project "
        + shellQuote(config.incusProject())
        + " "
        + shellQuote(config.nodeName())
        + " -- sh -lc "
        + shellQuote(script);
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
        "rke2ServerState",
        summary.applyValue(value -> value.getOrDefault("rke2ServerState", "unknown")));
    outputs.put(
        "envDirPresent", summary.applyValue(value -> value.getOrDefault("envDirPresent", false)));
    outputs.put(
        "pendingJobCount", summary.applyValue(value -> value.getOrDefault("pendingJobCount", -1)));
    outputs.put(
        "failedUnitCount", summary.applyValue(value -> value.getOrDefault("failedUnitCount", -1)));
    outputs.put(
        "runtimePrecheckReady",
        summary.applyValue(value -> value.getOrDefault("runtimePrecheckReady", false)));
    return outputs;
  }

  private static Map<String, Object> parseSummary(String stdout) {
    final LinkedHashMap<String, Object> parsed = new LinkedHashMap<>();
    parsed.put("source", "pulumi-command:local");
    parsed.put("status", "ok");

    if (stdout != null && !stdout.isBlank()) {
      stdout
          .lines()
          .map(String::trim)
          .filter(line -> !line.isBlank())
          .forEach(
              line -> {
                final int delimiterIndex = line.indexOf('=');
                if (delimiterIndex <= 0) {
                  return;
                }
                final String key = line.substring(0, delimiterIndex).trim();
                final String value = line.substring(delimiterIndex + 1).trim();
                if (!key.isBlank()) {
                  parsed.put(key, value);
                }
              });
    }

    final boolean envDirPresent = Boolean.parseBoolean(stringValue(parsed.get("envDirPresent")));
    final String rke2ServerState = stringValue(parsed.get("rke2ServerState"));
    final int pendingJobCount = parseIntOrDefault(stringValue(parsed.get("pendingJobCount")), -1);
    final int failedUnitCount = parseIntOrDefault(stringValue(parsed.get("failedUnitCount")), -1);
    final boolean rke2ServerActive = "active".equalsIgnoreCase(rke2ServerState);

    parsed.put("envDirPresent", envDirPresent);
    parsed.put("pendingJobCount", pendingJobCount);
    parsed.put("failedUnitCount", failedUnitCount);
    parsed.put("rke2ServerActive", rke2ServerActive);
    parsed.put(
        "runtimePrecheckReady",
        envDirPresent && rke2ServerActive && (pendingJobCount == 0) && (failedUnitCount == 0));
    parsed.put(
        "summary", buildSummary(envDirPresent, rke2ServerState, pendingJobCount, failedUnitCount));

    if (!parsed.containsKey("capturedAt")) {
      parsed.put("capturedAt", Instant.now().toString());
    }

    return Map.copyOf(parsed);
  }

  private static String buildSummary(
      boolean envDirPresent, String rke2ServerState, int pendingJobCount, int failedUnitCount) {
    return "envDir="
        + (envDirPresent ? "ready" : "missing")
        + ", rke2="
        + (rke2ServerState.isBlank() ? "unknown" : rke2ServerState)
        + ", pendingJobs="
        + pendingJobCount
        + ", failedUnits="
        + failedUnitCount;
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
