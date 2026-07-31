package io.nxmatic.rke2lab.osgi.runtime.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * The pax-logging-logback backend's logback policy, coded in Java rather than an inlined XML
 * string.
 *
 * <p>This class ships in a FRAGMENT attached to {@code org.ops4j.pax.logging.pax-logging-logback}
 * (see this module's {@code bnd.bnd}), so at runtime it is loaded by pax's bundle classloader and
 * links directly against pax's embedded — and unexported — logback. The flat host cannot reach
 * logback (pax exports no package), so {@code FrameworkLauncher} invokes {@link #configure}
 * reflectively across the realm seam via {@code paxBundle.loadClass(...)}, once, right after {@code
 * framework.start()} returns: pax is ACTIVE and its minimal bootstrap XML is already applied, and
 * nothing reconfigures the context afterwards, so this holds the last word.
 *
 * <p>Split of concerns with the bootstrap XML the launcher still writes: that XML wires only the
 * FILE appender + root, so boot noise reaches the file and never the console (whose native stdout
 * write also wedges the boot under a remote debugger). THIS enforces the noise-suppression POLICY —
 * the per-tree levels and the root level from the launch config — the single source that was the
 * old inlined {@code logback.xml}.
 */
public final class PaxLogbackConfigurer {

  /**
   * The noisy third-party trees, quieted so an INFO root never drowns the file. Coded here, in
   * Java: the policy the old inlined logback config carried, now the one source. Insertion order
   * (broadest first) is irrelevant to logback but reads as intent.
   */
  private static final Map<String, Level> TREE_LEVELS = treeLevels();

  private PaxLogbackConfigurer() {}

  private static Map<String, Level> treeLevels() {
    final Map<String, Level> levels = new LinkedHashMap<>();
    levels.put("io.netty", Level.ERROR);
    levels.put("io.netty.util.internal", Level.ERROR);
    levels.put("io.netty.buffer", Level.ERROR);
    levels.put("io.grpc.netty", Level.ERROR);
    levels.put("org.apache.http", Level.WARN);
    levels.put("org.apache.http.wire", Level.ERROR);
    return levels;
  }

  /**
   * Apply the logback policy to pax's live context. Invoked reflectively by {@code
   * FrameworkLauncher} across the host↔pax realm seam with {@code paxBundle} = the resolved
   * pax-logging-logback bundle and {@code rootLevelName} = the launch config's framework log level
   * as a logback level name. Lets a failure propagate: the launcher's reflective call wraps and
   * logs it as a WARN (to the file), so a logging-config slip degrades to pax's bootstrap config
   * rather than felling the boot.
   */
  public static void configure(Bundle paxBundle, String rootLevelName) {
    final LoggerContext context = resolveContext(paxBundle);
    TREE_LEVELS.forEach((name, level) -> context.getLogger(name).setLevel(level));
    context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.toLevel(rootLevelName, Level.INFO));
  }

  /**
   * Reach the ONE {@code LoggerContext} pax logs through. pax's {@code PaxLoggingServiceImpl} holds
   * it in its private instance field {@code m_logbackContext}, and its {@code Activator} publishes
   * that impl — wrapped in a local {@code $1ManagedPaxLoggingService} — under its logservice names.
   * So scan the services pax registered and let {@link #contextFieldOf} pull the field off the
   * impl, unwrapping the wrapper's synthetic enclosing reference. This handles both pax context
   * modes: the instance field carries the live context whether or not {@code StaticLogbackContext}
   * is set.
   */
  private static LoggerContext resolveContext(Bundle paxBundle) {
    final ServiceReference<?>[] registered = paxBundle.getRegisteredServices();
    final BundleContext bundleContext = paxBundle.getBundleContext();
    if (registered != null && bundleContext != null) {
      for (ServiceReference<?> ref : registered) {
        final LoggerContext context = contextFieldOf(bundleContext.getService(ref));
        if (context != null) {
          return context;
        }
      }
    }
    throw new IllegalStateException(
        "pax-logging-logback LoggerContext not reachable via its registered services");
  }

  /**
   * The {@code m_logbackContext} pax holds, reached from a registered service object. The object
   * registered under {@code PaxLoggingService} is not the impl itself but its local wrapper {@code
   * PaxLoggingServiceImpl$1ManagedPaxLoggingService}, whose synthetic enclosing-instance field
   * ({@code this$0}) IS the {@code PaxLoggingServiceImpl} that owns the context — so try the field
   * directly, then through that enclosing instance.
   */
  private static @Nullable LoggerContext contextFieldOf(@Nullable Object service) {
    if (service == null) {
      return null;
    }
    final LoggerContext direct = loggerContextOf(service);
    if (direct != null) {
      return direct;
    }
    final Object enclosing = readField(service, "this$0");
    return enclosing == null ? null : loggerContextOf(enclosing);
  }

  private static @Nullable LoggerContext loggerContextOf(Object target) {
    return readField(target, "m_logbackContext") instanceof LoggerContext context ? context : null;
  }

  /**
   * The value of field {@code name} on {@code target} (walking its supertypes), or {@code null}.
   */
  private static @Nullable Object readField(Object target, String name) {
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        final Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
      } catch (NoSuchFieldException walkUp) {
        // declared higher up — keep walking
      } catch (IllegalAccessException blocked) {
        return null;
      }
    }
    return null;
  }
}
