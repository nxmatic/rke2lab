package io.seedmatic.rke2lab.domain.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The container the compiler folds repeated {@link GovernedBy} poses into — Java wraps two or more
 * {@code @GovernedBy} on one package in this. Authored implicitly (write {@code @GovernedBy} N
 * times), read explicitly: the staging extension handles BOTH the single-pose and the container
 * shape.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PACKAGE)
public @interface GovernedByAll {

  /** The repeated declarations, one per gate. */
  GovernedBy[] value();
}
