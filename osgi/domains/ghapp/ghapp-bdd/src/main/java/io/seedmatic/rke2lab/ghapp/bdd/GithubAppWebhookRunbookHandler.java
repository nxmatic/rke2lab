package io.seedmatic.rke2lab.ghapp.bdd;

import io.seedmatic.rke2lab.ghapp.contract.WebhookReconcileInput;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.GenericRunbookHandler;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.port.SeedHandler;
import java.util.function.Consumer;
import org.junit.platform.engine.support.store.Namespace;
import org.junit.platform.engine.support.store.NamespacedHierarchicalStore;
import org.osgi.service.component.annotations.Component;

/**
 * The ghapp webhook-reconcile runbook handler — the OSGi-side grower behind the broker's {@code
 * ghapp-webhook} host→scion door. Extends {@link GenericRunbookHandler} and supplies the coordinate
 * it serves ({@code RunbookCoordinate("ghapp-webhook")}) and its scenario. It READS the trigger:
 * {@link #seedFrom} decodes the {@link WebhookReconcileInput} (the funnel URL the amend door bound)
 * off {@code trigger.payload()} and routes it through the scenario's {@link
 * GithubAppWebhookScenario#INPUT} channel.
 */
@Component(service = SeedHandler.class)
public final class GithubAppWebhookRunbookHandler extends GenericRunbookHandler {

  private static final RunbookCoordinate COORDINATE = new RunbookCoordinate("ghapp-webhook");

  private final SeedCodec codec = new SeedCodec();

  @Override
  public RunbookCoordinate coordinate() {
    return COORDINATE;
  }

  @Override
  public Class<? extends ScenarioPlayer.Playable> scenarioClass() {
    return GithubAppWebhookScenario.class;
  }

  @Override
  public Consumer<NamespacedHierarchicalStore<Namespace>> seedFrom(SeedEnvelope trigger) {
    return GithubAppWebhookScenario.INPUT.into(
        codec.decode(trigger.payload(), WebhookReconcileInput.class));
  }
}
