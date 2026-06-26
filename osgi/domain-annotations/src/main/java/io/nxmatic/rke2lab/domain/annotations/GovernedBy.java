package io.nxmatic.rke2lab.domain.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares, for ONE staging {@link Gate}, the {@link EnforcementLevel} at which that gate reports
 * this package's violations — placed on a {@code package-info} (a {@code -core} bundle necessarily
 * defines its root package, so the anchor is guaranteed present and canonical). The gate always
 * checks the package; this only decides what a violation DOES to the build.
 *
 * <p>{@code @Repeatable}: one pose per gate, each with its own level
 * ({@code @GovernedBy(SPEC_COVERAGE, WARN)} and {@code @GovernedBy(INSTANCE_DISCIPLINE, ERROR)} on
 * the same package). A gate with NO {@code @GovernedBy} for a package treats it at the default
 * {@link EnforcementLevel#ERROR} — so <em>governed by default</em> is the standing guarantee; a
 * package opts a single gate down to {@code WARN}/{@code IGNORE} explicitly, and drops the
 * annotation to return to the locked default once clean.
 *
 * <p>Lives in {@code domain-annotations}, a dependency-free bundle common to ALL domains, so even a
 * {@code type=record} leaf under the purity guard can be annotated without taking a dependency, and
 * no domain depends on another's annotation module. {@link RetentionPolicy#CLASS} —
 * bytecode-visible (the extension reads it via ASM), no runtime OSGi import on the leaf.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PACKAGE)
@Repeatable(GovernedByAll.class)
public @interface GovernedBy {

  /** The staging gate this declaration sets the level for. */
  Gate value();

  /** The level at which {@link #value()} reports this package's violations. */
  EnforcementLevel level() default EnforcementLevel.ERROR;
}
