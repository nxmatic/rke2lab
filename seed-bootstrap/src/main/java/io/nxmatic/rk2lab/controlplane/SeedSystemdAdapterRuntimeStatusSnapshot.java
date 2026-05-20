package io.nxmatic.rk2lab.controlplane;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.systemdcontract.api.SystemdStatusSnapshot;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Canonical runtime status snapshot probe backed by systemd-adapter API. */
public final class SeedSystemdAdapterRuntimeStatusSnapshot {

  private static final String API_VERSION = "rk2lab.nxmatic.io/v1alpha1";
  private static final String KIND = "SystemdAdapterRuntimeStatus";
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
  private static final Gson GSON = new Gson();
  private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
  private static final String MANDATORY_TARGET_UNIT = "rke2lab.target";
  private static final String CLOUD_INIT_MAIN_UNIT = "cloud-init-main.service";

  private SeedSystemdAdapterRuntimeStatusSnapshot() {
    // Utility class
  }

  public static Map<String, Object> deferredPreview(BootstrapConfig config) {
    return envelope(
        "deferred-preview",
        "runtime status deferred during preview; probe=systemd-adapter-runtime",
        Map.of("source", "systemd-adapter-runtime-probe", "probeMode", "systemd-adapter-runtime"));
  }

  public static Map<String, Object> snapshot(BootstrapConfig config, Consumer<String> logger) {
    try {
      final Map<String, Object> rawPayload = readStatusPayload(config);
      final SystemdStatusSnapshot statusSnapshot = toSystemdStatusSnapshot(rawPayload);

      final LinkedHashMap<String, Object> parsed =
          new LinkedHashMap<>(statusSnapshot.toPayloadMap());
      mergeRuntimeDetails(parsed, rawPayload);
      parsed.put("apiVersion", API_VERSION);
      parsed.put("kind", KIND);
      parsed.put("source", "systemd-adapter-runtime-probe");
      parsed.put("probeMode", "systemd-adapter-runtime");
      parsed.put("status", "ok");
      parsed.putIfAbsent("capturedAt", Instant.now().toString());
      parsed.putIfAbsent("summary", "systemd adapter api probe captured runtime state");

      if (logger != null) {
        logger.accept("systemd adapter runtime summary: " + parsed.getOrDefault("summary", "n/a"));
      }
      return Map.copyOf(parsed);
    } catch (IllegalStateException ex) {
      return envelope(
          "execution-error",
          "systemd adapter runtime probe execution error: " + sanitizeProbeError(ex.getMessage()),
          Map.of(
              "source", "systemd-adapter-runtime-probe", "probeMode", "systemd-adapter-runtime"));
    }
  }

  private static void mergeRuntimeDetails(
      LinkedHashMap<String, Object> target, Map<String, Object> source) {
    if (target == null || source == null || source.isEmpty()) {
      return;
    }

    copyIfPresent(source, target, "currentJobs");
    copyIfPresent(source, target, "currentStartingService");
    copyIfPresent(source, target, "currentActiveUnits");
    copyIfPresent(source, target, "targetWants");
    copyIfPresent(source, target, "cloudInitMainState");
    copyIfPresent(source, target, "cloudInitMainResult");
    copyIfPresent(source, target, "cloudInitMainHealthy");
  }

  private static void copyIfPresent(
      Map<String, Object> source, LinkedHashMap<String, Object> target, String key) {
    if (source == null || target == null || key == null || key.isBlank()) {
      return;
    }

    final Object value = source.get(key);
    if (value != null) {
      target.put(key, value);
    }
  }

  public static Map<String, Object> snapshotStandalone(BootstrapConfig config) {
    return snapshot(config, message -> SeedLog.info("readiness", message));
  }

  private static Map<String, Object> readStatusPayload(BootstrapConfig config) {
    final CommandResult result =
        runCommandInInstance(config, buildLocalSystemdSnapshotScript(config));

    if (looksLikeIncusHelp(result.stdout())) {
      throw new IllegalStateException("received Incus CLI help while querying runtime snapshot");
    }
    if (result.exitCode() != 0) {
      throw new IllegalStateException(
          "failed querying runtime snapshot (" + summarizeCommandFailure(result) + ")");
    }

    final String stdout = result.stdout() == null ? "" : result.stdout().trim();
    if (stdout.isBlank()) {
      throw new IllegalStateException("empty response from runtime snapshot");
    }

    final Map<String, Object> parsed = decodePayload(stdout);
    if (parsed == null || parsed.isEmpty()) {
      throw new IllegalStateException("invalid/empty JSON payload from runtime snapshot");
    }
    return Map.copyOf(parsed);
  }

  private static Map<String, Object> decodePayload(String stdout) {
    final String raw = stdout == null ? "" : stdout.trim();
    if (raw.isBlank()) {
      return Map.of();
    }

    final Map<String, Object> direct = parseJsonObject(raw);
    if (direct != null && !direct.isEmpty()) {
      return direct;
    }

    final String unwrapped = unwrapJsonString(raw);
    if (!unwrapped.equals(raw)) {
      final Map<String, Object> unwrappedParsed = parseJsonObject(unwrapped);
      if (unwrappedParsed != null && !unwrappedParsed.isEmpty()) {
        return unwrappedParsed;
      }
    }

    final String extracted = extractJsonObject(raw);
    if (!extracted.isBlank()) {
      final Map<String, Object> extractedParsed = parseJsonObject(extracted);
      if (extractedParsed != null && !extractedParsed.isEmpty()) {
        return extractedParsed;
      }
    }

    throw new IllegalStateException(
        "runtime snapshot payload is not a JSON object: "
            + sanitizeProbeError(firstNonBlankLine(raw, "unknown")));
  }

  private static Map<String, Object> parseJsonObject(String payload) {
    try {
      return GSON.fromJson(payload, MAP_TYPE);
    } catch (JsonSyntaxException ex) {
      return null;
    }
  }

  private static String unwrapJsonString(String payload) {
    try {
      final String value = GSON.fromJson(payload, String.class);
      if (value == null || value.isBlank()) {
        return payload;
      }
      return value.trim();
    } catch (JsonSyntaxException ex) {
      return payload;
    }
  }

  private static String extractJsonObject(String payload) {
    if (payload == null || payload.isBlank()) {
      return "";
    }

    final int start = payload.indexOf('{');
    final int end = payload.lastIndexOf('}');
    if (start < 0 || end < 0 || end <= start) {
      return "";
    }
    return payload.substring(start, end + 1).trim();
  }

  private static String buildLocalSystemdSnapshotScript(BootstrapConfig config) {
    final String nixosHost = normalizeString(config.imageBuilderHost(), "unknown");
    final String nixosHostShort =
        nixosHost.contains(".") ? nixosHost.substring(0, nixosHost.indexOf('.')) : nixosHost;
    final String configuredIncusInstance = normalizeString(config.nodeName(), "unknown");

    return "set -eu\n"
        + "mandatory_target="
        + shellQuote(MANDATORY_TARGET_UNIT)
        + "\n"
        + "cloud_init_main="
        + shellQuote(CLOUD_INIT_MAIN_UNIT)
        + "\n"
        + "configured_nixos_host="
        + shellQuote(nixosHost)
        + "\n"
        + "configured_nixos_host_short="
        + shellQuote(nixosHostShort)
        + "\n"
        + "configured_incus_instance="
        + shellQuote(configuredIncusInstance)
        + "\n"
        + "observed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo unknown)\n"
        + "target_state=$(systemctl is-active \"$mandatory_target\" 2>/dev/null || true)\n"
        + "if [ -z \"$target_state\" ]; then target_state=unknown; fi\n"
        + "mandatory_target_healthy=false\n"
        + "if [ \"$target_state\" = active ]; then mandatory_target_healthy=true; fi\n"
        + "pending_jobs=$(systemctl list-jobs --no-legend 2>/dev/null | sed '/^[[:space:]]*$/d' | wc -l | tr -d ' ' || echo 0)\n"
        + "failed_units=$(systemctl --failed --no-legend --plain 2>/dev/null | sed '/^[[:space:]]*$/d' | wc -l | tr -d ' ' || echo 0)\n"
        + "target_wants=$(systemctl show -p Wants --value \"$mandatory_target\" 2>/dev/null | awk '{print NF}' || echo unavailable)\n"
        + "cloud_init_state=$(systemctl show -p ActiveState --value \"$cloud_init_main\" 2>/dev/null || echo unknown)\n"
        + "cloud_init_result=$(systemctl show -p Result --value \"$cloud_init_main\" 2>/dev/null || echo unknown)\n"
        + "cloud_init_healthy=false\n"
        + "if [ \"$cloud_init_state\" = active ] && [ \"$cloud_init_result\" = success ]; then cloud_init_healthy=true; fi\n"
        + "runtime_precheck_ready=false\n"
        + "if [ \"$mandatory_target_healthy\" = true ] && [ \"$pending_jobs\" = 0 ] && [ \"$failed_units\" = 0 ]; then runtime_precheck_ready=true; fi\n"
        + "sanitize_text() { printf '%s' \"$1\" | tr '\\n' ' ' | sed 's/[^[:alnum:]_.:@/+,-]/_/g'; }\n"
        + "current_jobs=$(systemctl list-jobs --no-legend 2>/dev/null | awk '{print $1 \"(\" $3 \"/\" $4 \")\"}' | head -n 8 | paste -sd, - || true)\n"
        + "if [ -z \"${current_jobs:-}\" ]; then current_jobs=none; fi\n"
        + "current_starting_service=$(systemctl list-jobs --no-legend 2>/dev/null | awk '$1 ~ /\\.service$/ {print $1 \"(\" $3 \"/\" $4 \")\"}' | head -n 5 | paste -sd, - || true)\n"
        + "if [ -z \"${current_starting_service:-}\" ]; then current_starting_service=none; fi\n"
        + "current_active_units=$(systemctl list-units --type=service --state=activating --no-legend 2>/dev/null | awk '{print $1 \"(\" $4 \")\"}' | head -n 8 | paste -sd, - || true)\n"
        + "if [ -z \"${current_active_units:-}\" ]; then current_active_units=none; fi\n"
        + "current_jobs=$(sanitize_text \"$current_jobs\")\n"
        + "current_starting_service=$(sanitize_text \"$current_starting_service\")\n"
        + "current_active_units=$(sanitize_text \"$current_active_units\")\n"
        + "instance_host=$(hostname 2>/dev/null || echo unknown)\n"
        + "if [ \"$instance_host\" = \"$configured_nixos_host\" ] || [ \"$instance_host\" = \"$configured_nixos_host_short\" ]; then\n"
        + "  echo \"probe executed on nixos host ($instance_host), expected incus instance context\" >&2\n"
        + "  exit 42\n"
        + "fi\n"
        + "summary=mandatoryTarget=$mandatory_target(state=$target_state),\n"
        + "summary=\"$summary pendingJobs=$pending_jobs, failedUnits=$failed_units, cloudInitMain=$cloud_init_main(state=$cloud_init_state,result=$cloud_init_result,healthy=$cloud_init_healthy), source=systemd-local-probe\"\n"
        + "cat <<JSON\n"
        + "{\n"
        + "  \"observedAt\": \"$observed_at\",\n"
        + "  \"mandatoryTarget\": \"$mandatory_target\",\n"
        + "  \"mandatoryTargetState\": \"$target_state\",\n"
        + "  \"mandatoryTargetHealthy\": $mandatory_target_healthy,\n"
        + "  \"pendingJobs\": $pending_jobs,\n"
        + "  \"jobsByState\": {},\n"
        + "  \"failedUnits\": $failed_units,\n"
        + "  \"runtimePrecheckReady\": $runtime_precheck_ready,\n"
        + "  \"connectionContext\": {\n"
        + "    \"adapterHost\": \"$instance_host\",\n"
        + "    \"incusInstance\": \"$configured_incus_instance\",\n"
        + "    \"nixosHost\": \"$configured_nixos_host\",\n"
        + "    \"systemBusAddress\": \"unix:path=/var/run/dbus/system_bus_socket\"\n"
        + "  },\n"
        + "  \"summary\": \"$summary\",\n"
        + "  \"currentJobs\": \"$current_jobs\",\n"
        + "  \"currentStartingService\": \"$current_starting_service\",\n"
        + "  \"currentActiveUnits\": \"$current_active_units\",\n"
        + "  \"targetWants\": \"$target_wants\",\n"
        + "  \"cloudInitMainState\": \"$cloud_init_state\",\n"
        + "  \"cloudInitMainResult\": \"$cloud_init_result\",\n"
        + "  \"cloudInitMainHealthy\": $cloud_init_healthy\n"
        + "}\n"
        + "JSON\n";
  }

  private static SystemdStatusSnapshot toSystemdStatusSnapshot(Map<String, Object> payload) {
    final String observedAt = normalizeString(payload.get("observedAt"), Instant.now().toString());
    final String mandatoryTarget =
        normalizeString(payload.get("mandatoryTarget"), "rke2lab.target");
    final String mandatoryTargetState =
        normalizeString(payload.get("mandatoryTargetState"), "unknown");
    final boolean mandatoryTargetHealthy = toBoolean(payload.get("mandatoryTargetHealthy"));
    final int pendingJobs = toInt(payload.get("pendingJobs"), 0);
    final Map<String, Integer> jobsByState = toIntMap(payload.get("jobsByState"));
    final int failedUnits = toInt(payload.get("failedUnits"), 0);
    final boolean runtimePrecheckReady = toBoolean(payload.get("runtimePrecheckReady"));
    final Map<String, String> connectionContext = toStringMap(payload.get("connectionContext"));
    final String summary = normalizeString(payload.get("summary"), "n/a");

    return new SystemdStatusSnapshot(
        observedAt,
        mandatoryTarget,
        mandatoryTargetState,
        mandatoryTargetHealthy,
        pendingJobs,
        jobsByState,
        failedUnits,
        runtimePrecheckReady,
        connectionContext,
        summary);
  }

  private static List<String> incusExec(BootstrapConfig config, String... args) {
    // ssh joins post-destination argv with spaces and re-parses on the remote
    // side, so a multi-line script passed as a separate `sh -lc <script>` argv
    // entry would be split on whitespace. Build the entire remote command as a
    // single shell-quoted string and hand it to ssh as one argument.
    final String remoteIncusCommand =
        "incus --project "
            + shellQuote(config.incusProject())
            + " exec "
            + shellQuote(config.nodeName())
            + " -- "
            + joinShellQuoted(args);

    return List.of(
        "ssh",
        "-o",
        "BatchMode=yes",
        "-o",
        "ConnectTimeout=10",
        config.imageBuilderHost(),
        remoteIncusCommand);
  }

  private static String joinShellQuoted(String... values) {
    if (values == null || values.length == 0) {
      return "";
    }

    final ArrayList<String> quoted = new ArrayList<>(values.length);
    for (String value : values) {
      quoted.add(shellQuote(value == null ? "" : value));
    }
    return String.join(" ", quoted);
  }

  private static int toInt(Object value, int fallback) {
    if (value instanceof Number numberValue) {
      return Math.max(numberValue.intValue(), 0);
    }
    try {
      return Math.max(Integer.parseInt(String.valueOf(value).trim()), 0);
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static boolean toBoolean(Object value) {
    if (value instanceof Boolean boolValue) {
      return boolValue;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  private static Map<String, Integer> toIntMap(Object value) {
    if (!(value instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
      return Map.of();
    }
    final LinkedHashMap<String, Integer> parsed = new LinkedHashMap<>();
    rawMap.forEach(
        (key, rawValue) -> {
          final String normalizedKey = normalizeString(key, "");
          if (normalizedKey.isBlank()) {
            return;
          }
          parsed.put(normalizedKey, toInt(rawValue, 0));
        });
    return Map.copyOf(parsed);
  }

  private static Map<String, String> toStringMap(Object value) {
    if (!(value instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
      return Map.of();
    }
    final LinkedHashMap<String, String> parsed = new LinkedHashMap<>();
    rawMap.forEach(
        (key, rawValue) -> {
          final String normalizedKey = normalizeString(key, "");
          if (normalizedKey.isBlank()) {
            return;
          }
          parsed.put(normalizedKey, normalizeString(rawValue, "unknown"));
        });
    return Map.copyOf(parsed);
  }

  private static String summarizeCommandFailure(CommandResult result) {
    final String stderrFirst = firstNonBlankLine(result.stderr(), "");
    if (!stderrFirst.isBlank()) {
      return sanitizeProbeError(stderrFirst);
    }
    final String stdoutFirst = firstNonBlankLine(result.stdout(), "");
    if (!stdoutFirst.isBlank()) {
      return sanitizeProbeError(stdoutFirst);
    }
    return "exit=" + result.exitCode();
  }

  private static String sanitizeProbeError(String raw) {
    final String value = raw == null ? "unknown" : raw.trim();
    if (value.isBlank()) {
      return "unknown";
    }

    final String lower = value.toLowerCase();
    if (lower.contains("curl:")
        || lower.contains("wget:")
        || lower.contains("failed to connect")
        || lower.contains("connection refused")
        || lower.contains("connection reset")
        || lower.contains("timed out")) {
      return "adapter runtime endpoint not reachable yet";
    }

    if (lower.contains("missing http client")) {
      return "adapter runtime probe prerequisites not present in instance";
    }

    return value;
  }

  private static String normalizeString(Object value, String fallback) {
    if (value == null) {
      return fallback;
    }
    final String raw = value.toString().trim();
    if (raw.isBlank()) {
      return fallback;
    }
    return raw;
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }

  private static String firstNonBlankLine(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }

    return value
        .lines()
        .map(String::trim)
        .filter(line -> !line.isBlank())
        .findFirst()
        .orElse(fallback);
  }

  private static CommandResult runCommand(List<String> command) {
    final ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.environment().putIfAbsent("LANG", "C");
    try {
      final Process process = processBuilder.start();
      final boolean exited = process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!exited) {
        process.destroyForcibly();
        return new CommandResult(-1, "", "timed out after " + PROBE_TIMEOUT);
      }

      final String stdout =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      final String stderr =
          new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      return new CommandResult(process.exitValue(), stdout, stderr);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return new CommandResult(-1, "", "command interrupted");
    } catch (IOException ex) {
      return new CommandResult(-1, "", "failed to execute command: " + ex.getMessage());
    }
  }

  private static CommandResult runCommandInInstance(BootstrapConfig config, String script) {
    return runCommand(incusExec(config, "sh", "-lc", script));
  }

  private static boolean looksLikeIncusHelp(String output) {
    if (output == null || output.isBlank()) {
      return false;
    }

    final String normalized = output.toLowerCase();
    return normalized.contains("command line client for incus")
        || (normalized.contains("usage:") && normalized.contains("incus"));
  }

  private static Map<String, Object> envelope(
      String status, String summary, Map<String, Object> details) {
    final LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
    payload.put("apiVersion", API_VERSION);
    payload.put("kind", KIND);
    payload.put("status", status);
    payload.put("summary", summary);
    payload.put("capturedAt", Instant.now().toString());
    if (details != null && !details.isEmpty()) {
      payload.putAll(details);
    }
    return Map.copyOf(payload);
  }

  private record CommandResult(int exitCode, String stdout, String stderr) {}
}
