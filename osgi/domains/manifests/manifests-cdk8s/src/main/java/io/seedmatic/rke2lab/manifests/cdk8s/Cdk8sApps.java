package io.seedmatic.rke2lab.manifests.cdk8s;

import org.cdk8s.App;
import org.cdk8s.AppProps;

/**
 * Constructs cdk8s {@link App}s with the thread context classloader pinned to THIS carrier bundle's
 * loader for the duration of the call.
 *
 * <p>Why this exists: the first {@code new App(...)} drives jsii's {@code
 * JsiiObjectMapper.<clinit>} → {@code ObjectMapper.findAndRegisterModules()} → {@code
 * ServiceLoader<com.fasterxml.jackson.databind.Module>}. {@code ServiceLoader} reads {@code
 * META-INF/services} off the THREAD CONTEXT classloader, not through OSGi package wiring. Since
 * jackson-databind is now a separate bundle, the runtime-discovered {@code JavaTimeModule} (nested
 * in this carrier, see bnd.bnd) must be loaded by the classloader that also sees the databind
 * bundle's {@code Module} — this carrier's — or it {@code extends} a different {@code Module} copy
 * and jsii throws {@code ServiceConfigurationError: JavaTimeModule not a subtype}. Callers (e.g.
 * manifests-core's synthesis) run on the host/flat thread, whose context classloader would
 * otherwise resolve the flat jsr310; routing every {@code App} construction through here makes the
 * carrier the sole, owning entry point — the bundle that embeds the closure also owns its
 * classloader contract.
 *
 * <p>{@code JsiiObjectMapper.INSTANCE} is {@code static final}, so its module discovery runs once
 * per classloader on the first touch; pinning here for every construction is idempotent and keeps
 * the invariant robust even if the construction order changes.
 */
public final class Cdk8sApps {

  private Cdk8sApps() {}

  /** Builds an {@link App} from {@code props} with the carrier's classloader as TCCL. */
  public static App create(AppProps props) {
    final Thread thread = Thread.currentThread();
    final ClassLoader previous = thread.getContextClassLoader();
    thread.setContextClassLoader(Cdk8sApps.class.getClassLoader());
    try {
      return new App(props);
    } finally {
      thread.setContextClassLoader(previous);
    }
  }
}
