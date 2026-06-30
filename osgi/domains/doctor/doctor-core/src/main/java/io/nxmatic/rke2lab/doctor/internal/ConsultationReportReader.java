package io.nxmatic.rke2lab.doctor.internal;

import io.nxmatic.rke2lab.doctor.records.*;
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
 * pulls each known key by name and ignores the rest; for an {@link Observation} the "rest" is
 * preserved (details = everything that is not a canonical key), so a key the producer adds tomorrow
 * survives a round-trip through a reader written today.
 */
public final class ConsultationReportReader {

  private ConsultationReportReader() {}

  /**
   * Three keys are HARD requirements, because the target records cannot be honestly built without
   * them: {@code raw} must be a map, {@code checkpointId} must be a present string, and the {@code
   * plan} must carry a parseable {@link Symptom} (the diagnosis is the reason a consultation
   * exists, and {@link RemediationPlan} demands a non-null symptom). Everything else degrades to
   * empty/default — including replies, which degrade individually: a reply with no parseable {@link
   * Assessment} (no "why") is dropped, but its absence does not sink the plan.
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
        new ConsultationReport(
            checkpointId, observationsFrom(map.get("observations")), plan.get()));
  }

  private static List<Observation> observationsFrom(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    final List<Observation> observations = new ArrayList<>(list.size());
    for (Object element : list) {
      observationFrom(element).ifPresent(observations::add);
    }
    return observations;
  }

  private static Optional<Observation> observationFrom(Object raw) {
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
    return Optional.of(Observation.of(status, symptom, summary, details));
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
            repliesFrom(map.get("replies")),
            stringOrEmpty(map.get("generalistSummary"))));
  }

  private static List<ReferralReply> repliesFrom(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    final List<ReferralReply> replies = new ArrayList<>(list.size());
    for (Object element : list) {
      replyFrom(element).ifPresent(replies::add);
    }
    return replies;
  }

  /**
   * A reply needs its {@link Assessment} (the "why"); without a parseable one it is not a reply and
   * is dropped. The prescription is optional — a malformed or absent one yields empty, and the
   * reply keeps its assessment.
   */
  private static Optional<ReferralReply> replyFrom(Object raw) {
    if (!(raw instanceof Map<?, ?> map)) {
      return Optional.empty();
    }
    final Optional<Assessment> assessment = assessmentFrom(map.get("assessment"));
    if (assessment.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        ReferralReply.reconstructed(assessment.get(), prescriptionFrom(map.get("prescription"))));
  }

  private static Optional<Assessment> assessmentFrom(Object raw) {
    if (!(raw instanceof Map<?, ?> map)) {
      return Optional.empty();
    }
    // No declared shape (schemaRef) = unusable; no summary = the "why" is missing. Degrade rather
    // than throw on the Assessment invariants (non-null schemaRef, non-blank summary).
    final Optional<SchemaRef> schemaRef = SchemaRef.parse(stringOrEmpty(map.get("schemaRef")));
    if (schemaRef.isEmpty()) {
      return Optional.empty();
    }
    final String summary = stringOrEmpty(map.get("summary"));
    if (summary.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(Assessment.of(schemaRef.get(), mapOrEmpty(map.get("payload")), summary));
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
