package io.nxmatic.rke2lab.domain.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Removes a SINGLE element (a type, method, or field) from ONE staging {@link Gate}, with a written
 * reason — the precise opposite of widening a gate's detection heuristic. The gate stays strict
 * (the {@link Gate#INSTANCE_DISCIPLINE} target is zero static helpers); a member that must
 * legitimately stay carries an explicit, reviewable exemption rather than being silently absorbed
 * by a broader name rule. {@link GovernedBy} lowers a whole package's report level; this exempts
 * one element.
 *
 * <p>The {@link #reason} is mandatory and must be non-blank — an exemption with no justification is
 * itself a smell. It does NOT replace factory recognition: real factories (the construction verbs
 * the {@code INSTANCE_DISCIPLINE} rule already knows) need no annotation; {@code @Exempt} is for
 * the residue the rule cannot recognise but review accepts. A {@code @Exempt} that recurs across
 * many elements is the signal to reconsider the rule or the design — the count is a backlog metric.
 *
 * <p>{@code @Repeatable} (an element may be exempt from more than one gate), retention {@link
 * RetentionPolicy#CLASS} — the staging extension reads it via ASM, like every other marker.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@Repeatable(ExemptAll.class)
public @interface Exempt {

  /** The gate this element is exempt from. */
  Gate value();

  /** Why this element legitimately stays despite the gate — mandatory, non-blank, reviewable. */
  String reason();
}
