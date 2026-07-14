package io.nxmatic.rke2lab.pulumi.edge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulumi.automation.LocalWorkspace;
import com.pulumi.automation.LocalWorkspaceOptions;
import com.pulumi.automation.ProjectBackend;
import com.pulumi.automation.ProjectRuntimeName;
import com.pulumi.automation.ProjectSettings;
import com.pulumi.automation.WorkspaceStack;
import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import io.nxmatic.rke2lab.seed.broker.port.Cellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The Pulumi realisation of the {@link Cellar} conservation role — the commissioner's neutral store
 * over the Pulumi file backend, fusing the three former doctor-named host journals ({@code
 * MedicalRecordJournal}, {@code InterventionJournal}, {@code InterventionLedgerWriter}) into one.
 * It holds nothing but sealed {@link SeedEnvelope}s addressed by a {@link Parcel}; it never names a
 * doctor type and never opens a payload.
 *
 * <p><b>store</b> — files a sealed envelope append-only, its mode routed by the injected {@link
 * RunGate} (the cellar consults the gate, the scion never picks the mode — its {@code store} is one
 * neutral verb): cultivating → an out-of-run {@code up()} CONSERVES it, registering an inert
 * component resource carrying the payload under an output whose NAME is the envelope's coordinate
 * slug (the shelf label); a stable resource name per coordinate makes each append a new history
 * entry (the fold), not a churned resource. Surveying → a {@code preview()} PRE-RESERVES it: the
 * plan is computed against the same inert program but the state is NOT touched (no history entry),
 * so a dry-run neither loses the harvest nor lies it into the conserved timeline.
 *
 * <p><b>fetch</b> — collect-all-neutral (the capability-as-service model): it walks the parcel's
 * stack history and, for each entry, rebuilds one opaque {@link SeedEnvelope} per stored output,
 * keyed by that output's NATIVE name. It stamps NO domain coordinate and hardcodes no case name —
 * it returns what the soil holds, by the soil's own output names; the capable (OSGi) side reads the
 * cases it knows. The envelope's {@code domain} field is NOT carried across the Pulumi shell (only
 * the coordinate/output-name and the payload survive); no read path consumes it (the readers guard
 * on coordinate only), so it is rebuilt empty.
 *
 * <p><b>neighbours</b> — the sibling parcels under the same backend soil (the parcel's own first).
 *
 * <p>An absent file backend yields an empty neighbourhood (just the parcel) and empty fetches; a
 * present-but-unreadable history propagates (corruption is not absence).
 */
public final class PulumiCellar implements Cellar {

  private static final String BACKEND_URL_ENV = "PULUMI_BACKEND_URL";
  private static final String FILE_SCHEME = "file://";

  // A file backend's default secrets provider requires a passphrase even when no secret is stored;
  // the value is immaterial for a store that holds no secrets.
  private static final String PASSPHRASE = "rke2lab-cellar";

  private static final TypeReference<Map<String, Object>> PAYLOAD_SHAPE = new TypeReference<>() {};

  private final ObjectMapper mapper = new ObjectMapper();
  private final Optional<Path> backendDir;
  private final RunGate gate;
  private final Consumer<String> logger;

  public PulumiCellar(Optional<Path> backendDir, RunGate gate, Consumer<String> logger) {
    this.backendDir = backendDir;
    this.gate = gate;
    this.logger = logger;
  }

  public static PulumiCellar fromEnvironment(RunGate gate, Consumer<String> logger) {
    return new PulumiCellar(backendDirFromUrl(System.getenv(BACKEND_URL_ENV)), gate, logger);
  }

  static Optional<Path> backendDirFromUrl(@Nullable String pulumiBackendUrl) {
    return Optional.ofNullable(pulumiBackendUrl)
        .filter(url -> url.startsWith(FILE_SCHEME))
        .map(url -> Path.of(url.substring(FILE_SCHEME.length())));
  }

  /** The file-backend root this cellar reads from, or empty when no file:// backend is set. */
  public Optional<Path> backendDir() {
    return backendDir;
  }

  @Override
  @SuppressWarnings("try") // WorkspaceStack.close() declares InterruptedException; handled in catch
  public void store(Parcel parcel, SeedEnvelope vegetal) {
    if (backendDir.isEmpty()) {
      throw new IllegalStateException(
          "cannot store to parcel "
              + parcel.project()
              + "/"
              + parcel.stack()
              + ": no file:// PULUMI_BACKEND_URL configured");
    }
    final Path backend = backendDir.orElseThrow();
    final Map<String, Object> payload = deserialize(vegetal.payload());
    final String coordinate = vegetal.coordinate();

    final ProjectSettings projectSettings =
        ProjectSettings.builder(parcel.project(), ProjectRuntimeName.JAVA)
            .backend(ProjectBackend.builder().url(FILE_SCHEME + backend).build())
            .build();

    final LocalWorkspaceOptions options =
        LocalWorkspaceOptions.builder()
            .projectSettings(projectSettings)
            .program(ctx -> new CellarEntry(coordinate, payload))
            .environmentVariables(
                Map.of(
                    "PULUMI_BACKEND_URL",
                    FILE_SCHEME + backend,
                    "PULUMI_CONFIG_PASSPHRASE",
                    PASSPHRASE))
            .build();

    try (WorkspaceStack stack =
        LocalWorkspace.createOrSelectStack(
            parcel.project(), parcel.stack(), options.program(), options)) {
      // The cellar consults the gate: cultivating conserves (up), surveying pre-reserves (preview —
      // the plan is computed, the state left intact). preview() returns a change summary the caller
      // has no channel for yet (store is void); the runbook narrates the plan, so we only log it.
      if (gate.cultivating()) {
        stack.up();
      } else {
        logger.accept(
            "cellar pre-reserve (preview) for "
                + parcel.project()
                + "/"
                + parcel.stack()
                + " at "
                + coordinate
                + ": "
                + stack.preview().changeSummary());
      }
    } catch (Exception e) {
      throw new RuntimeException(
          "failed to store to parcel "
              + parcel.project()
              + "/"
              + parcel.stack()
              + " under "
              + backend,
          e);
    }
  }

  @Override
  public List<SeedEnvelope> fetch(Parcel parcel) {
    if (backendDir.isEmpty()) {
      logger.accept(
          "cellar empty for "
              + parcel.project()
              + "/"
              + parcel.stack()
              + ": no file:// PULUMI_BACKEND_URL configured");
      return List.of();
    }
    final Path root = backendDir.orElseThrow();
    final StackHandle handle = StackHandle.forBackend(root, parcel.project(), parcel.stack());

    final List<StackHistory.Entry> entries;
    try {
      entries = handle.history().entries();
    } catch (StackException e) {
      // A present-but-unreadable history is corruption, not absence: propagate rather than mask an
      // empty store (the dishonesty the ledger exists to kill).
      throw new RuntimeException("cellar history present but unreadable under " + root, e);
    }

    final List<SeedEnvelope> reaped = new ArrayList<>();
    for (StackHistory.Entry entry : entries) {
      try {
        collectEntry(handle.snapshotOf(entry), reaped);
      } catch (StackException e) {
        // A present entry that cannot be materialised degrades to a skip with a reason — the fetch
        // continues on the readable prefix rather than throwing.
        logger.accept(
            "cellar entry skipped for "
                + parcel.project()
                + "/"
                + parcel.stack()
                + ": version="
                + entry.version()
                + " at "
                + entry.when()
                + " unreadable — "
                + e.getMessage());
      }
    }
    return reaped;
  }

  @Override
  public List<Parcel> neighbours(Parcel parcel) {
    if (backendDir.isEmpty()) {
      return List.of(parcel);
    }
    final Path stacksDir =
        PulumiBackendLayout.stacksDir(backendDir.orElseThrow(), parcel.project());
    if (!Files.isDirectory(stacksDir)) {
      return List.of(parcel);
    }
    try (Stream<Path> entries = Files.list(stacksDir)) {
      return entries
          .filter(Files::isDirectory)
          .map(dir -> dir.getFileName().toString())
          .map(stack -> new Parcel(parcel.project(), stack))
          .sorted(
              Comparator.comparing((Parcel p) -> p.stack().equals(parcel.stack()) ? 0 : 1)
                  .thenComparing(Parcel::stack))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot enumerate neighbours under " + stacksDir, e);
    }
  }

  /**
   * Rebuild one opaque {@link SeedEnvelope} per stored output in the entry's snapshot, keyed by the
   * output's native name (the coordinate the store filed it under). The payload is re-serialized
   * with the host's own jackson — no jackson type crosses the seam. The {@code domain} is not
   * carried by the shell (see class doc) and is rebuilt empty.
   */
  private void collectEntry(StackSnapshot snapshot, List<SeedEnvelope> into) {
    snapshot
        .allOutputs()
        .forEach(
            (coordinate, values) ->
                values.forEach(
                    value -> into.add(new SeedEnvelope("", coordinate, serialize(value)))));
  }

  private Map<String, Object> deserialize(String payload) {
    try {
      return mapper.readValue(payload, PAYLOAD_SHAPE);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("malformed SeedEnvelope payload", e);
    }
  }

  private String serialize(Object blob) {
    try {
      return mapper.writeValueAsString(blob);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not serialize stored blob", e);
    }
  }

  /**
   * An inert component resource that persists one stored envelope's payload as a per-resource
   * output under the coordinate-slug name. It touches no real infrastructure — the store IS the
   * history fold: one entry per {@code up()}, the sequence lives in history, not in distinct
   * resource names (so the name is the stable coordinate slug, not the payload).
   */
  private static final class CellarEntry extends ComponentResource {
    private static final String TYPE_TOKEN = "rke2lab:cellar:Entry";

    CellarEntry(String coordinate, Map<String, Object> payload) {
      super(TYPE_TOKEN, coordinate);
      registerOutputs(Map.of(coordinate, Output.of(payload)));
    }
  }
}
