package io.nxmatic.rke2lab.osgi.runtime.scenario.engine;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Seeds one framework launch property into the embedded Felix config for the annotated test class,
 * over {@link io.nxmatic.rke2lab.osgi.runtime.framework.LaunchConfig#applyFrameworkInvariants the
 * shared invariants}. {@link OutOfContainerFrameworkExtension} reads it in {@code beforeAll} (like
 * {@link FrameworkLog}) and puts it on the config before {@code newFramework}.
 *
 * <p>For the few boot knobs the prod {@code FrameworkLauncher} sets outside the invariants that a
 * proof must MIRROR so the test boot cannot drift from prod — notably the pax-logging properties a
 * bundle reads from the framework properties at activation:
 *
 * <pre>{@code
 * @OsgiWorld
 * @FrameworkProperty(name = "org.ops4j.pax.logging.StaticLogbackContext", value = "true")
 * @FrameworkProperty(name = "org.ops4j.pax.logging.DefaultServiceLog.level", value = "ERROR")
 * class SomeOsgiTest { … }
 * }</pre>
 *
 * <p>It is an annotation, not a builder verb, for the same reason as {@link FrameworkLog}: many
 * tests obtain their extension from a SHARED factory ({@code ScenarioTestkit.felix()}), where a
 * per-test builder call is not reachable — the annotation rides on the test class regardless of how
 * the extension was assembled. Repeatable: stack one per property.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(FrameworkProperties.class)
public @interface FrameworkProperty {

  String name();

  String value();
}
