package io.nxmatic.rke2lab.seed.broker.shape;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nxmatic.rke2lab.seed.broker.port.Amendment;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The amont reconciliation engine: bind a {@code {role → value}} map onto a wire-record's {@link
 * Amendment}-marked components. The reconciling twin of {@code DoctorSplitReflector} — Split
 * DECOMPOSES a reaped record into {@code {rootstock → {role → value}}} (aval), this COMPOSES an
 * input record by placing each role's value into the field its {@code @Amendment} designates
 * (amont). A domain's {@code AmendReflector} holds the {@code @SeedContract}-indexed wire-record
 * class and drives this binder, so the caller names a NEUTRAL role and the domain's field name
 * never crosses (see docs/architecture/osgi/seed-broker-spec.adoc § @Amendment).
 *
 * <p>It works on JSON, never on the record's constructor: given the record's {@code defaults} as a
 * node (the reflector, which owns the class, serializes them) and the {@code roleValues}, it reads
 * the record's components' {@code @Amendment} roles, maps each supplied role to the component's own
 * name, and sets that field on a copy of the defaults. The reflector then decodes the amended node
 * into the instance with its own codec. So the binder holds no domain class and touches no
 * constructor — it is the pure {@code role → field} bridge, foundation-generic.
 *
 * <p>Driven by the roles OFFERED, not by a strict 1:1 role→field map — a role may legitimately be
 * borne by several components ({@code ManifestsRunbookInput} marks both {@code link} and {@code
 * debug} {@code FACET}, but its {@code SOIL} is one field). So the binder rejects only (1) an
 * offered role NO component declares (a value for a role the target does not amend), and (2) an
 * offered role borne by MORE THAN ONE component (a genuine ambiguity — which field?). A role the
 * caller omits keeps its default (a partial amendment is legal — fill only what you hold). A
 * multi-field role that is NOT offered is fine; it is simply not this caller's concern.
 */
public final class AmendmentBinder {

  /**
   * Amend {@code defaults} with {@code roleValues}: for each OFFERED role, find the
   * {@code @Amendment} component it names on {@code wireRecord} and set that field on a copy of
   * {@code defaults}. Returns the amended node; the caller decodes it into the record.
   */
  public JsonNode bind(Class<?> wireRecord, JsonNode defaults, Map<String, JsonNode> roleValues) {
    final ObjectNode amended = defaults.deepCopy();
    roleValues.forEach(
        (role, value) -> {
          if (value == null) {
            return;
          }
          amended.set(fieldFor(wireRecord, role), value);
        });
    return amended;
  }

  /**
   * The single {@code @Amendment} component name for {@code role} — loud on absent or ambiguous.
   */
  private static String fieldFor(Class<?> wireRecord, String role) {
    final List<String> fields = new ArrayList<>();
    for (RecordComponent component : wireRecord.getRecordComponents()) {
      final Amendment amendment = component.getAnnotation(Amendment.class);
      if (amendment != null && amendment.value().equals(role)) {
        fields.add(component.getName());
      }
    }
    if (fields.isEmpty()) {
      throw new IllegalArgumentException(
          wireRecord.getSimpleName() + " declares no @Amendment for role '" + role + "'");
    }
    if (fields.size() > 1) {
      throw new IllegalArgumentException(
          wireRecord.getSimpleName()
              + " has "
              + fields.size()
              + " components for amendment role '"
              + role
              + "' ("
              + fields
              + ") — the role is ambiguous to bind by value");
    }
    return fields.get(0);
  }
}
