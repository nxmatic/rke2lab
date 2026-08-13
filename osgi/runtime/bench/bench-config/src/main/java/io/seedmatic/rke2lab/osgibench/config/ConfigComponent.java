package io.seedmatic.rke2lab.osgibench.config;

import org.osgi.service.component.annotations.RequireServiceComponentRuntime;
import org.osgi.service.metatype.annotations.RequireMetaTypeExtender;

/**
 * Disposable bench stand-in for a config-bearing bundle. The two {@code Require osgi.extender}
 * headers are now GENERATED from Java, each by the annotation that HONESTLY states the need: {@link
 * RequireServiceComponentRuntime} (itself a {@code @Requirement} on {@code
 * osgi.extender=osgi.component}) emits the component-extender require, and {@link
 * RequireMetaTypeExtender} emits the metatype one. We require the DS extender — we are NOT a DS
 * component — so this is the require annotation, not {@code @Component}: the latter would also
 * declare the class a component and emit a {@code Service-Component}/{@code OSGI-INF} descriptor
 * for a component this DS-free bench has no runtime to activate.
 */
@RequireServiceComponentRuntime
@RequireMetaTypeExtender
public final class ConfigComponent {
  private ConfigComponent() {}
}
