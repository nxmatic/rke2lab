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
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Transitional {

  /** The successor type this one migrates to (e.g. {@code "EfficacyReport"}). */
  String to();

  /** The spec file (under {@code docs/architecture/doctor/}) that describes the target model. */
  String spec();
}
