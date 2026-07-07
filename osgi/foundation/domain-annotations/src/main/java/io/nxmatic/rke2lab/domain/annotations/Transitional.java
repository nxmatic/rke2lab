package io.nxmatic.rke2lab.domain.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type that still lives under the OLD model while a settled spec already describes its
 * successor — the refactor is planned, not done. It is NOT {@link Deprecated}: a deprecated API is
 * a permanent compat shim that says "keep me for callers"; a transitional type says "I migrate to
 * {@link #to}", carries its target, and is meant to DIE when the refactor lands.
 *
 * <p>It also closes the spec-coverage loop the other way: a transitional type may legitimately have
 * DISAPPEARED from the specs (the specs now describe the successor, e.g. {@code EfficacyReport},
 * not the old {@code TreatmentEfficacy}). Such a type is consistent-with-specs by REFERENCE to its
 * target ({@link #spec}), not by appearing in a spec itself — the {@code spec-coverage} gate reads
 * this annotation and counts the type as "in transition", never as drift.
 *
 * <p>Lives in {@code domain-annotations}, a dependency-free bundle common to ALL domains, so even a
 * {@code type=record} leaf under the purity guard can target it without taking a dependency or
 * breaking the leaf, and no domain depends on another's annotation module.
 *
 * <p>Two shapes of transition, distinguished by whether {@link #spec} is given:
 *
 * <ul>
 *   <li>type→type, spec-described (the original): {@code to} names the successor TYPE and {@code
 *       spec} the doc describing it — e.g. {@code TreatmentEfficacy} → {@code EfficacyReport}. The
 *       {@code SPEC_COVERAGE} gate reads the annotation's PRESENCE and counts the type as
 *       in-transition rather than drift.
 *   <li>code-awaiting-a-migration, spec-less: {@code to} describes what this code becomes when a
 *       PENDING migration lands — e.g. host-side I/O that dies once an external OSGi edge exists
 *       ({@code to = "incus-edge (external edge, not yet built)"}). Here {@code spec} is left
 *       empty: there is no successor type in a spec yet, only a named chantier. A documentary
 *       marker so the code point is navigable back to the debt — the gate only ever reads presence,
 *       never the values, so an empty {@code spec} changes nothing for it.
 * </ul>
 *
 * <p>Targets a TYPE or a METHOD. On a method it marks a member of the old model kept ALIVE only for
 * a condemned caller — it dies when that caller is removed (its {@code to} names the removal, not a
 * successor). Method use is documentary only; the {@code SPEC_COVERAGE} gate reads exported TYPES.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Transitional {

  /**
   * What this migrates to: a successor TYPE name (e.g. {@code "EfficacyReport"}) when {@link #spec}
   * describes it, or a free description of the pending migration (e.g. {@code "incus-edge (external
   * edge, not yet built)"}) when there is no successor type yet.
   */
  String to();

  /**
   * The spec file describing the target model (e.g. under {@code docs/architecture/doctor/}), or
   * empty when the transition awaits a chantier with no successor-type spec yet (see class
   * javadoc).
   */
  String spec() default "";
}
