package io.nxmatic.rke2lab.controlplane.incus;

import com.pulumi.deployment.Deployment;
import com.pulumi.incus.IncusFunctions;
import com.pulumi.incus.inputs.GetNetworkPlainArgs;
import com.pulumi.incus.inputs.GetProjectPlainArgs;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The bridge of first contact between the NixOS-prepopulated Incus host and a virgin Pulumi state:
 * a PROVIDER invoke ({@code getProjectPlain}/{@code getNetworkPlain} through the run's {@link
 * InvokeOptions}, NOT ssh) that discovers a pre-existing project/network so the GROW ADOPTS it via
 * {@code importId} instead of trying to create a duplicate. Host-pure — it computes nothing of the
 * domain, it only reads what the Incus host already reports.
 *
 * <p>Instance-passing: it holds the {@link IncusProviderContext} whose {@code invokeOptions} every
 * lookup rides, and a {@code log} sink; a caller constructs one and asks it for import ids. The
 * former nested-static {@code INSTANCE} + {@code BootstrapContext} coupling is gone.
 */
public final class IncusImportLookup {

  private static final long PREVIEW_INVOKE_TIMEOUT_SECONDS = 10;
  private static final long APPLY_INVOKE_TIMEOUT_SECONDS = 20;

  private enum LookupState {
    FOUND,
    NOT_FOUND,
    FAILED
  }

  private record LookupResult(String importId, LookupState state, Optional<Boolean> managed) {

    private static LookupResult found(String importId, @Nullable Boolean managed) {
      return new LookupResult(importId, LookupState.FOUND, Optional.ofNullable(managed));
    }

    private static LookupResult notFound() {
      return new LookupResult("", LookupState.NOT_FOUND, Optional.empty());
    }

    private static LookupResult failed() {
      return new LookupResult("", LookupState.FAILED, Optional.empty());
    }
  }

  private final IncusProviderContext context;
  private final Consumer<String> log;

  public IncusImportLookup(IncusProviderContext context, Consumer<String> log) {
    this.context = context;
    this.log = log;
  }

  /** The import id to adopt an existing project, or {@code ""} when none is found. */
  public String existingProjectId(String projectName) {
    log.accept("incus lookup getProject: start name=" + projectName);
    try {
      final var project =
          IncusFunctions.getProjectPlain(
                  GetProjectPlainArgs.builder().name(projectName).build(), context.invokeOptions())
              .orTimeout(invokeTimeoutSeconds(), TimeUnit.SECONDS)
              .join();
      if (project == null) {
        return "";
      }
      final String providerId = normalizeImportId(project.id());
      return providerId.isBlank() ? normalizeImportId(project.name()) : providerId;
    } catch (Exception ex) {
      log.accept("incus lookup getProject: failed (" + summarizeLookupFailure(ex) + ")");
      return "";
    }
  }

  /** The import id to adopt an existing network, or {@code ""} when none is found. */
  public String existingNetworkId(String networkName, String incusProject) {
    final LookupResult projectScoped =
        resolveNetworkImportId(
            GetNetworkPlainArgs.builder().name(networkName).project(incusProject).build());
    if (projectScoped.state() == LookupState.FOUND) {
      return projectScoped.importId();
    }
    if (projectScoped.state() == LookupState.FAILED) {
      final String fallbackImportId = normalizeImportId(networkName);
      log.accept(
          "incus lookup getNetwork: deterministic fallback import id after scoped lookup failure"
              + " (name="
              + networkName
              + ", fallbackImportId="
              + fallbackImportId
              + ")");
      return fallbackImportId;
    }
    return resolveNetworkImportId(GetNetworkPlainArgs.builder().name(networkName).build())
        .importId();
  }

  /** Whether the Incus host reports this network as UNMANAGED — the GROW must skip it. */
  public boolean isUnmanagedNetwork(String networkName, String incusProject) {
    final LookupResult projectScoped =
        resolveNetworkImportId(
            GetNetworkPlainArgs.builder().name(networkName).project(incusProject).build());
    if (projectScoped.state() == LookupState.FOUND) {
      return projectScoped.managed().map(Boolean.FALSE::equals).orElse(false);
    }
    if (projectScoped.state() == LookupState.FAILED) {
      return false;
    }
    final LookupResult unscoped =
        resolveNetworkImportId(GetNetworkPlainArgs.builder().name(networkName).build());
    if (unscoped.state() == LookupState.FOUND) {
      return unscoped.managed().map(Boolean.FALSE::equals).orElse(false);
    }
    return false;
  }

  public String normalizeImportId(@Nullable String value) {
    if (value == null) {
      return "";
    }
    final String trimmed = value.trim();
    return trimmed.isBlank() ? "" : trimmed;
  }

  private long invokeTimeoutSeconds() {
    try {
      return Deployment.getInstance().isDryRun()
          ? PREVIEW_INVOKE_TIMEOUT_SECONDS
          : APPLY_INVOKE_TIMEOUT_SECONDS;
    } catch (Exception ignored) {
      return APPLY_INVOKE_TIMEOUT_SECONDS;
    }
  }

  private LookupResult resolveNetworkImportId(GetNetworkPlainArgs args) {
    log.accept("incus lookup getNetwork: start name=" + args.name());
    try {
      final var network =
          IncusFunctions.getNetworkPlain(args, context.invokeOptions())
              .orTimeout(invokeTimeoutSeconds(), TimeUnit.SECONDS)
              .join();
      if (network == null) {
        return LookupResult.notFound();
      }
      final String providerId = normalizeImportId(network.id());
      final Boolean managed = network.managed();
      return providerId.isBlank()
          ? LookupResult.found(normalizeImportId(network.name()), managed)
          : LookupResult.found(providerId, managed);
    } catch (Exception ex) {
      final String summary = summarizeLookupFailure(ex);
      if (isNotFoundFailure(summary)) {
        return LookupResult.notFound();
      }
      log.accept("incus lookup getNetwork: failed (" + summary + ")");
      return LookupResult.failed();
    }
  }

  private boolean isNotFoundFailure(String summary) {
    return summary != null && summary.toLowerCase(Locale.ROOT).contains("not found");
  }

  private String summarizeLookupFailure(Exception ex) {
    if (ex == null) {
      return "unknown";
    }
    Throwable root = ex;
    while (root.getCause() != null
        && (root instanceof CompletionException || root instanceof ExecutionException)) {
      root = root.getCause();
    }
    final String type = root.getClass().getSimpleName();
    final String message = root.getMessage() == null ? "" : root.getMessage().trim();
    return message.isBlank() ? type : type + ": " + message;
  }
}
