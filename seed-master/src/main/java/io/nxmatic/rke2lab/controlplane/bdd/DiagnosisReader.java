package io.nxmatic.rke2lab.controlplane.bdd;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tolerant, additive inverse of {@link ConsultationReport#toOutputMap()} (and its nested {@code
 * toOutputMap}s): rebuilds a typed {@link ConsultationReport} from the flat, string-keyed map a
 * checkpoint registered as a Pulumi output. It never throws — malformed input yields {@link
 * Optional#empty()} — so a stale or partially-written record degrades instead of crashing the read.
 *
 * <p>Additive by design: the schema evolves by adding keys, never by a version number. The reader
 * pulls each known key by name and ignores the rest; for a {@link Dossier} the "rest" is preserved
 * (details = everything that is not a canonical key), so a key the producer adds tomorrow survives
 * a round-trip through a reader written today.
 */
final class DiagnosisReader {

  private DiagnosisReader() {}

  /**
   * Three keys are HARD requirements, because the target records cannot be honestly built without
   * them: {@code raw} must be a map, {@code checkpointId} must be a present string, and the {@code
   * plan} must carry a parseable {@link Symptom} (the diagnosis is the reason a consultation
   * exists, and {@link RemediationPlan} demands a non-null symptom). Everything else degrades to
   * empty/default.
   */
  public static Optional<ConsultationReport> fromOutputMap(Object raw) {
    if (!(raw instanceof Map<?, ?> map)) {
      return Optional.empty();
    }
    if (!(map.get("checkpointId") instanceof String checkpointId)) {
      return Optional.empty();
    }
    final Optional<RemediationPlan> plan = planFrom(map.get("plan"));
    if (plan.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new ConsultationReport(checkpointId, dossiersFrom(map.get("dossiers")), plan.get()));
  }

  private static List<Dossier> dossiersFrom(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    final List<Dossier> dossiers = new ArrayList<>(list.size());
    for (Object element : list) {
      dossierFrom(element).ifPresent(dossiers::add);
    }
    return dossiers;
  }

  private static Optional<Dossier> dossierFrom(Object raw) {
    if (!(raw instanceof Map<?, ?> map)) {
      return Optional.empty();
    }
    final String status = stringOrEmpty(map.get("status"));
    final String summary = stringOrEmpty(map.get("summary"));
    final Optional<Symptom> symptom = Symptom.parse(stringOrEmpty(map.get(Symptom.ENVELOPE_KEY)));

    // details = everything else: drop the three canonical keys, keep all the rest so unknown keys
    // survive the round-trip (the additivity guarantee).
    final LinkedHashMap<String, Object> details = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      final String key = String.valueOf(entry.getKey());
      if (key.equals("status") || key.equals("summary") || key.equals(Symptom.ENVELOPE_KEY)) {
        continue;
      }
      details.put(key, entry.getValue());
    }
    return Optional.of(Dossier.of(status, symptom, summary, details));
  }

  private static Optional<RemediationPlan> planFrom(Object raw) {
    if (!(raw instanceof Map<?, ?> map)) {
      return Optional.empty();
    }
    final Optional<Symptom> symptom = Symptom.parse(stringOrEmpty(map.get(Symptom.ENVELOPE_KEY)));
    if (symptom.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new RemediationPlan(
            symptom.get(),
            prescriptionsFrom(map.get("prescriptions")),
            stringOrEmpty(map.get("generalistSummary"))));
  }

  private static List<Prescription> prescriptionsFrom(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    final List<Prescription> prescriptions = new ArrayList<>(list.size());
    for (Object element : list) {
      prescriptionFrom(element).ifPresent(prescriptions::add);
    }
    return prescriptions;
  }

  private static Optional<Prescription> prescriptionFrom(Object raw) {
    if (!(raw instanceof Map<?, ?> map)) {
      return Optional.empty();
    }
    // An unparseable programRef cannot address a treatment, so the prescription is dropped rather
    // than reconstructed against a wrong program (silent mis-dispatch).
    final Optional<RemediationProgramRef> programRef =
        RemediationProgramRef.parse(stringOrEmpty(map.get("programRef")));
    if (programRef.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        Prescription.of(
            programRef.get(), mapOrEmpty(map.get("payload")), stringOrEmpty(map.get("humanHint"))));
  }

  private static String stringOrEmpty(Object value) {
    return value instanceof String s ? s : "";
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapOrEmpty(Object value) {
    return value instanceof Map<?, ?> ? Map.copyOf((Map<String, Object>) value) : Map.of();
  }
}
