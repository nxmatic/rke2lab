package io.nxmatic.rke2lab.pulumi.edge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulumi.automation.LocalWorkspace;
import com.pulumi.automation.LocalWorkspaceOptions;
import com.pulumi.automation.ProjectBackend;
import com.pulumi.automation.ProjectRuntimeName;
import com.pulumi.automation.ProjectSettings;
import com.pulumi.automation.WorkspaceStack;
import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import io.nxmatic.rke2lab.seed.broker.port.OpaqueCellar;
import io.nxmatic.rke2lab.seed.broker.port.Parcel;
import io.nxmatic.rke2lab.seed.broker.port.RunGate;
import io.nxmatic.rke2lab.seed.broker.port.SeedCoordinate;
import io.nxmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.nxmatic.rke2lab.seed.broker.port.Trail;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * The Pulumi realisation of the {@link Cellar} conservation role — the commissioner's neutral store
 * over the Pulumi file backend, fusing the three former doctor-named host journals ({@code
 * MedicalRecordJournal}, {@code InterventionJournal}, {@code InterventionLedgerWriter}) into one.
 * It holds nothing but sealed {@link SeedEnvelope}s addressed by a {@link Parcel}; it never names a
 * doctor type and never opens a payload.
 *
 * <p><b>The durable coquille.</b> Every entry is persisted as a self-characterising CLEAR shell of
 * five flat fields around the opaque payload — {@code {domain, trail, tombstone, mac, payload}} —
 * registered as the entry's Pulumi output (keyed by the coordinate slug, the shelf label). The
 * {@code domain} and the {@code trail} (the value's fil d'Ariane, § fil-d-ariane) thus SURVIVE the
 * durable round-trip; the {@code payload} rides as a STRING field, so a SEALED {@code
 * cellar:sealed:v1:…} value is just a field value, no longer a Map the store must parse (the
 * blocker that made durable sealing impossible). The {@code mac} is an HMAC over the clear
 * characterisation, keyed by the cellar's own passphrase — a property of the CELLAR, not the seal
 * (decision A): every coquille carries one, so a fetch can trust ANY value's lineage and any tamper
 * of the clear shell OR the sealed payload fails closed. The host never opens the payload — it MACs
 * and carries the opaque bytes — so the {@link OpaqueCellar} contract ("never opens a payload")
 * holds literally.
 *
 * <p><b>store</b> — files a coquille append-only, its mode routed by the injected {@link RunGate}
 * (the cellar consults the gate, the scion never picks the mode — its {@code store} is one neutral
 * verb): cultivating → an out-of-run {@code up()} CONSERVES it; a stable resource name per
 * coordinate makes each append a new history entry (the fold), not a churned resource. Surveying →
 * a {@code preview()} PRE-RESERVES it: the plan is computed against the same inert program but the
 * state is NOT touched (no history entry), so a dry-run neither loses the harvest nor lies it into
 * the conserved timeline.
 *
 * <p><b>fetch</b> — walks the parcel's stack history and, for each entry, VERIFIES the coquille's
 * MAC then rebuilds one {@link SeedEnvelope} per stored output from the shell's own {@code domain}
 * + {@code trail} + opaque {@code payload}, keyed by the output's NATIVE name (the coordinate). The
 * typed overloads ({@code fetch(parcel, type)}, {@code fetch(parcel, coordinate, type)}) DELEGATE
 * the decode to the {@link SeedCodec}, which reflects on the {@code Class} passed — the host
 * decodes any type ITS classpath holds; a type it lacks (a bundle type) is the codec's throw, not a
 * cellar rule.
 *
 * <p><b>withdraw</b> — the fridge take: over an append-only backend it files a TOMBSTONE coquille
 * ({@code tombstone=true}, a first-class CLEAR shell flag — no in-band payload marker) so the
 * current-state fold ({@code fetch(parcel, coordinate)}, last-wins) reads the case as empty. The
 * history keeps the trace (the audit does not lie); only the current state is emptied. This serves
 * the ring rotation (the sole {@code withdraw} usage); the doctor's journal never takes.
 *
 * <p><b>neighbours</b> — the sibling parcels under the same backend soil (the parcel's own first).
 *
 * <p>An absent file backend yields an empty neighbourhood (just the parcel) and empty fetches; a
 * present-but-unreadable history propagates (corruption is not absence); a coquille whose MAC does
 * not verify is tamper, and fails closed rather than degrading to a skip.
 */
public final class PulumiCellar implements OpaqueCellar {

  private static final String BACKEND_URL_ENV = "PULUMI_BACKEND_URL";
  private static final String FILE_SCHEME = "file://";

  // The cellar's own (mono) identity — the same value the codec's PassphraseCellarCipher seals
  // with,
  // doubling here as the file-backend secrets-provider passphrase AND the key of the coquille's
  // MAC.
  // The MAC is a property of the CELLAR, not the seal (decision A): every coquille carries one,
  // sealed or clear, so the doctor can trust ANY value's lineage without a per-seal key.
  private static final String PASSPHRASE = "rke2lab-cellar";

  // The five CLEAR fields of the durable coquille — the self-characterising shell around the opaque
  // payload. domain + trail + tombstone restore the envelope characterisation the old (coordinate →
  // payload) shell dropped; mac binds the clear layer to the sealed payload; payload rides as a
  // STRING field (sealed or clear), so a "cellar:sealed:v1:…" value is just a field value, no
  // longer
  // a Map the store must parse (the blocker that made durable sealing impossible).
  private static final String KEY_DOMAIN = "domain";
  private static final String KEY_TRAIL = "trail";
  private static final String KEY_TOMBSTONE = "tombstone";
  private static final String KEY_MAC = "mac";
  private static final String KEY_PAYLOAD = "payload";

  private static final String MAC_ALGORITHM = "HmacSHA256";

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
  public void store(Parcel parcel, SeedEnvelope vegetal) {
    // A live store files a self-characterising coquille: the CLEAR shell (domain, trail, tombstone)
    // around the OPAQUE payload, MAC-bound so a later fetch can trust the whole. tombstone is false
    // — a store fills the case; withdraw is the sole writer of a tombstone coquille.
    writeShell(
        parcel,
        vegetal.coordinate(),
        shell(vegetal.domain(), vegetal.coordinate(), vegetal.trail(), false, vegetal.payload()));
  }

  /**
   * Persist one coquille under {@code coordinate} — the sole Pulumi writer, shared by {@link
   * #store} (a fill) and {@link #withdraw} (a tombstone). The shell is registered verbatim as the
   * entry's output; the history fold is the audit, one entry per {@code up()}.
   */
  @SuppressWarnings("try") // WorkspaceStack.close() declares InterruptedException; handled in catch
  private void writeShell(Parcel parcel, String coordinate, Map<String, Object> shell) {
    if (backendDir.isEmpty()) {
      throw new IllegalStateException(
          "cannot store to parcel "
              + parcel.project()
              + "/"
              + parcel.stack()
              + ": no file:// PULUMI_BACKEND_URL configured");
    }
    final Path backend = backendDir.orElseThrow();

    final ProjectSettings projectSettings =
        ProjectSettings.builder(parcel.project(), ProjectRuntimeName.JAVA)
            .backend(ProjectBackend.builder().url(FILE_SCHEME + backend).build())
            .build();

    final LocalWorkspaceOptions options =
        LocalWorkspaceOptions.builder()
            .projectSettings(projectSettings)
            .program(ctx -> new CellarEntry(coordinate, shell))
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
    return reap(parcel).stream().map(Shelved::envelope).toList();
  }

  /**
   * The parcel's timeline as {@link Shelved} coquilles — each carrying its rebuilt {@link
   * SeedEnvelope} (domain + trail + payload restored from the CLEAR shell) and its first-class
   * tombstone flag, so the fold ({@link #currentAt}) reads the case's state without opening the
   * opaque payload. Oldest first. A present-but-unreadable history is corruption (propagates); a
   * single entry that cannot be materialised degrades to a skip. A coquille whose MAC does NOT
   * verify is NOT a skip — it is tamper, and {@link #collectEntry} throws it closed.
   */
  private List<Shelved> reap(Parcel parcel) {
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

    final List<Shelved> reaped = new ArrayList<>();
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
  public Optional<SeedEnvelope> fetch(Parcel parcel, SeedCoordinate coordinate) {
    // Peek ONE case OPAQUE: the last-wins fold of the timeline at this coordinate. An empty case
    // (never stored, or withdrawn — a tombstone won) is Optional.empty(), a legitimate state.
    return currentAt(parcel, coordinate.slug());
  }

  @Override
  public Optional<SeedEnvelope> withdraw(Parcel parcel, SeedCoordinate coordinate) {
    // Take the case out OPAQUE: hand back its current envelope, then file a TOMBSTONE coquille
    // (tombstone=true, first-class — no in-band payload marker) so a later fold reads the case
    // empty.
    // The removal is of the CURRENT state (the fold); history keeps the trace.
    final Optional<SeedEnvelope> current = fetch(parcel, coordinate);
    if (current.isPresent()) {
      writeShell(parcel, coordinate.slug(), shell("", coordinate.slug(), Trail.empty(), true, ""));
    }
    return current;
  }

  /**
   * The CURRENT coquille at {@code coordinate} — the last-wins fold of the parcel's timeline, or
   * empty if the case was never filled or its most-recent state is a tombstone (a {@link
   * #withdraw}). Reads the tombstone off the CLEAR shell flag, never the opaque payload.
   */
  private Optional<SeedEnvelope> currentAt(Parcel parcel, String coordinate) {
    final Map<String, SeedEnvelope> current = new LinkedHashMap<>();
    for (Shelved shelved : reap(parcel)) {
      if (!shelved.envelope().coordinate().equals(coordinate)) {
        continue;
      }
      if (shelved.tombstone()) {
        current.remove(coordinate);
      } else {
        current.put(coordinate, shelved.envelope());
      }
    }
    return Optional.ofNullable(current.get(coordinate));
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
   * Rebuild one {@link Shelved} coquille per stored output in the entry's snapshot, keyed by the
   * output's native name (the coordinate the store filed it under). Each output value is a CLEAR
   * shell {@code {domain, trail, tombstone, mac, payload}}: its MAC is VERIFIED first (over the
   * same canonical characterisation the store bound), then the {@link SeedEnvelope} is rebuilt from
   * the shell's own {@code domain} + {@code trail} + opaque {@code payload} — the characterisation
   * the old {@code (coordinate → payload)} shell dropped. The host never opens the payload: it MACs
   * and carries the opaque bytes, so the {@link OpaqueCellar} contract holds. A MAC mismatch is
   * tamper and throws (fail-closed).
   */
  private void collectEntry(StackSnapshot snapshot, List<Shelved> into) {
    snapshot
        .allOutputs()
        .forEach(
            (coordinate, values) ->
                values.forEach(value -> into.add(unshell(coordinate, asShell(value)))));
  }

  /** One rebuilt durable coquille: its restored envelope and its first-class tombstone flag. */
  record Shelved(SeedEnvelope envelope, boolean tombstone) {}

  /**
   * Build a CLEAR shell around an opaque {@code payload}: the five flat fields, MAC-bound. The
   * trail is stored as its literal JSON STRING (a clear field, readable without the passphrase), so
   * the MAC covers exactly the bytes that ride — no re-serialisation drift, no dependence on the
   * map key order Pulumi may reshuffle. The MAC is the LAST field added; it binds the four that
   * precede it.
   */
  Map<String, Object> shell(
      String domain, String coordinate, Trail trail, boolean tombstone, String payload) {
    final String trailJson = writeString(trail);
    final Map<String, Object> shell = new LinkedHashMap<>();
    shell.put(KEY_DOMAIN, domain);
    shell.put(KEY_TRAIL, trailJson);
    shell.put(KEY_TOMBSTONE, tombstone);
    shell.put(KEY_PAYLOAD, payload);
    shell.put(KEY_MAC, mac(domain, coordinate, tombstone, trailJson, payload));
    return shell;
  }

  /**
   * Verify the coquille's MAC and rebuild its {@link Shelved} form. The {@code coordinate} (the
   * output name) is part of the bound characterisation, so a coquille moved to another case fails
   * the MAC too.
   */
  Shelved unshell(String coordinate, Map<String, Object> shell) {
    final String domain = stringField(shell, KEY_DOMAIN);
    final String trailJson = stringField(shell, KEY_TRAIL);
    final boolean tombstone = Boolean.TRUE.equals(shell.get(KEY_TOMBSTONE));
    final String payload = stringField(shell, KEY_PAYLOAD);
    final String stored = stringField(shell, KEY_MAC);

    final String expected = mac(domain, coordinate, tombstone, trailJson, payload);
    if (!MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8))) {
      throw new SecurityException(
          "cellar coquille MAC mismatch at "
              + coordinate
              + " — the clear shell or payload was tampered");
    }
    final Trail trail = readTrail(trailJson);
    return new Shelved(new SeedEnvelope(domain, coordinate, payload, trail), tombstone);
  }

  /**
   * The coquille's MAC — an HMAC over the CLEAR characterisation {@code (domain, coordinate,
   * tombstone, trail, payload)}, keyed by the cellar's own {@link #PASSPHRASE}. A property of the
   * CELLAR (decision A): every coquille carries one, sealed or clear, so the trail's lineage is
   * trustworthy and any tamper of the clear shell OR the sealed payload fails at fetch. Cipher-
   * agnostic (a proper MAC, not AES-GCM's AAD), so it works identically for the passphrase (mono)
   * and age (multi) seals. The fields are HMAC'd as a JSON array — jackson's string escaping
   * delimits them unambiguously, so no field's content can be confused with the separator.
   */
  private String mac(
      String domain, String coordinate, boolean tombstone, String trailJson, String payload) {
    try {
      final Mac hmac = Mac.getInstance(MAC_ALGORITHM);
      hmac.init(new SecretKeySpec(PASSPHRASE.getBytes(StandardCharsets.UTF_8), MAC_ALGORITHM));
      final String canonical =
          writeString(List.of(domain, coordinate, tombstone, trailJson, payload));
      return Base64.getEncoder()
          .encodeToString(hmac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("cellar coquille MAC failed", e);
    }
  }

  private static String stringField(Map<String, Object> shell, String key) {
    final Object value = shell.get(key);
    if (!(value instanceof String s)) {
      throw new IllegalArgumentException(
          "malformed cellar coquille: missing string field '" + key + "'");
    }
    return s;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> asShell(Object value) {
    if (!(value instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("malformed cellar coquille: output is not a shell map");
    }
    return (Map<String, Object>) value;
  }

  private Trail readTrail(String trailJson) {
    try {
      return mapper.readValue(trailJson, Trail.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("malformed cellar coquille trail", e);
    }
  }

  private String writeString(Object blob) {
    try {
      return mapper.writeValueAsString(blob);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not serialize cellar coquille field", e);
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
