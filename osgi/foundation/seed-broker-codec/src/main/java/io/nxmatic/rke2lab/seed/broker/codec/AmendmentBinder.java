package io.nxmatic.rke2lab.seed.broker.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nxmatic.rke2lab.seed.broker.port.Amendment;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The amont reconciliation engine: compose a wire-record from the {@code {role → value}} map a
 * sower offered, placing each role's value into the field its {@code @Amendment} designates. The
 * reconciling twin of {@code DoctorSplitReflector} — Split DECOMPOSES a reaped record into {@code
 * {rootstock → {role → value}}} (aval), this COMPOSES an input record (amont). A domain's {@code
 * AmendReflector} holds the {@code @SeedContract}-indexed wire-record class and drives this binder,
 * so the caller names a NEUTRAL role and the domain's field name never crosses (see
 * docs/architecture/osgi/seed-broker-spec.adoc § @Amendment).
 *
 * <p>It works on JSON, never on the record's constructor: it reads the record's components'
 * {@code @Amendment} roles, maps each offered role to its component's name, and sets that field on
 * a fresh node; the reflector then decodes the amended node into the instance with its own codec.
 * So the binder holds no domain class and touches no constructor — it is the pure {@code role →
 * field} bridge, foundation-generic.
 *
 * <p><b>The input is a contract the sower must honour.</b> The wire-record's component TYPES
 * declare what is mandatory: a non-{@link Optional} {@code @Amendment} component MUST be filled by
 * an offered role — the door binds onto an EMPTY node and never fabricates a default, so a
 * mandatory role no sower offered fails the bind LOUD rather than silently synthesising a
 * wrong-posture or blank record. An {@code Optional<>} component may be absent (it decodes to
 * {@code Optional.empty()} — a survey / unknown is legitimately allowed). The defaults a sower
 * chooses are its OWN: built sower-side and sown, never a door-side fallback.
 *
 * <p>Driven by the roles OFFERED for placement, VALIDATED against the record's mandatory
 * components. It rejects (1) an offered role NO component declares (a value for a role the target
 * does not amend), (2) an offered role borne by MORE THAN ONE component (a genuine ambiguity —
 * which field?), and (3) a mandatory (non-{@code Optional}) component whose role no sower offered
 * (the contract breach this enforcement exists for). An offered {@code Optional} role that is
 * omitted is simply absent.
 */
public final class AmendmentBinder {

  /**
   * Bind {@code roleValues} onto a fresh node for {@code wireRecord}: for each OFFERED role, set
   * the field its {@code @Amendment} names; then require every mandatory (non-{@link Optional})
   * amendment to have been offered. Returns the amended node; the caller decodes it into the
   * record.
   */
  public JsonNode bind(Class<?> wireRecord, Map<String, JsonNode> roleValues) {
    final ObjectNode amended = JsonNodeFactory.instance.objectNode();
    roleValues.forEach(
        (role, value) -> {
          if (value == null) {
            return;
          }
          amended.set(fieldFor(wireRecord, role), value);
        });
    requireMandatoryAmendments(wireRecord, roleValues.keySet());
    return amended;
  }

  /**
   * Fail loud if any mandatory (non-{@link Optional}) {@code @Amendment} component's role was not
   * among the offered roles — the sower did not honour the input contract, and the door supplies no
   * default to cover for it.
   */
  private static void requireMandatoryAmendments(Class<?> wireRecord, Set<String> offeredRoles) {
    final List<String> missing = new ArrayList<>();
    for (RecordComponent component : wireRecord.getRecordComponents()) {
      final Amendment amendment = component.getAnnotation(Amendment.class);
      if (amendment == null || component.getType() == Optional.class) {
        continue;
      }
      if (!offeredRoles.contains(amendment.value())) {
        missing.add(amendment.value() + " (" + component.getName() + ")");
      }
    }
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException(
          wireRecord.getSimpleName()
              + " is missing mandatory amendment role(s) no sower offered: "
              + missing
              + " — the sower must honour the input contract (the amend door supplies no default)");
    }
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
