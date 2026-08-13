package io.seedmatic.rke2lab.domain.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The container the compiler folds repeated {@link Exempt} poses into — Java wraps two or more
 * {@code @Exempt} on one element in this. Authored implicitly (write {@code @Exempt} N times), read
 * explicitly: the staging extension handles BOTH the single-pose and the container shape.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface ExemptAll {

  /** The repeated exemptions, one per gate. */
  Exempt[] value();
}
