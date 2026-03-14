package io.nxmatic.rk2lab.controlplane;

import com.pulumi.Pulumi;
import com.pulumi.Config;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.incus.IncusResourceBootstrap;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entry point for the Pulumi management-cluster bootstrap program.
 */
public final class Main {

    private Main() {
        // Utility class
    }

    public static void main(String[] args) {
        if (!isPulumiEngineAvailable()) {
            runStandalone();
            return;
        }

        Pulumi.run(context -> {
            final Config config = context.config("rke2lab");
            final BootstrapConfig bootstrapConfig = new BootstrapConfig.Builder()
                    .applyConfig(config)
                    .build();
            final BootstrapOutputs outputs = bootstrapAndCollectOutputs(bootstrapConfig);
            outputs.values().forEach(context::export);
        });
    }

    private static BootstrapOutputs bootstrapAndCollectOutputs(BootstrapConfig config) {
        if (!"bioskop".equals(config.clusterName())) {
            throw new IllegalStateException("Stage A bootstrap supports management cluster 'bioskop' only. "
                    + "Set cluster.name=bioskop.");
        }

        final String bootstrapPhase;
        final boolean handoffReady;
        final IncusResourceBootstrap.BootstrapResult bootstrapResult = new IncusResourceBootstrap(config).apply();
        final String seedNodeId = bootstrapResult.seedNodeId();
        final Object imageFingerprint = bootstrapResult.imageFingerprint();
        final Object seedInstanceStatus = bootstrapResult.instanceStatus();
        bootstrapPhase = "Ready";
        handoffReady = true;

        final Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("managementClusterName", config.clusterName());
        outputs.put("apiEndpoint", config.apiEndpoint());
        outputs.put("kubeconfigRef", config.kubeconfigRef());
        outputs.put("seedNodeId", seedNodeId);
        outputs.put("seedImageFingerprint", imageFingerprint);
        outputs.put("seedInstanceStatus", seedInstanceStatus);
        outputs.put("incusProject", config.incusProject());
        outputs.put("imageAlias", config.imageAlias());
        outputs.put("seedLanBridgeParent", config.lanBridgeParent());
        outputs.put("handoffReady", handoffReady);
        outputs.put("bootstrapPhase", bootstrapPhase);
        outputs.put("nextStep", "bootstrap-management-cluster-then-apply-stageb-cluster-manifests");
        return new BootstrapOutputs(outputs);
    }

    private static void runStandalone() {
        final BootstrapConfig bootstrapConfig = new BootstrapConfig.Builder().build();
        final BootstrapOutputs outputs = bootstrapAndCollectOutputs(bootstrapConfig);
        System.out.println("Pulumi engine not detected (missing PULUMI_MONITOR). Running in standalone mode.");
        System.out.println("Bootstrap outputs:");
        outputs.values().forEach((key, value) -> System.out.println(key + "=" + value));
    }

    private static boolean isPulumiEngineAvailable() {
        final String monitor = System.getenv("PULUMI_MONITOR");
        return monitor != null && !monitor.isBlank();
    }

    private record BootstrapOutputs(Map<String, Object> values) {
    }
}
