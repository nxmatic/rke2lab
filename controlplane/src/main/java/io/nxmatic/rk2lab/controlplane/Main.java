package io.nxmatic.rk2lab.controlplane;

import com.pulumi.Pulumi;
import com.pulumi.Config;
import io.nxmatic.rk2lab.controlplane.incus.BootstrapConfig;
import io.nxmatic.rk2lab.controlplane.incus.IncusResourceBootstrap;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
        enforceCleanWorktree(config.localWorktreePath());

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
        final Object seedInstanceUrn = bootstrapResult.instanceUrn();
        final Object seedProviderUrn = bootstrapResult.providerUrn();
        final String provisioningChecksum = bootstrapResult.provisioningChecksum();
        final String imageBuildChecksum = bootstrapResult.imageBuildChecksum();
        bootstrapPhase = "Ready";
        handoffReady = true;

        final Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("managementClusterName", config.clusterName());
        outputs.put("apiEndpoint", config.apiEndpoint().toString());
        outputs.put("kubeconfigRef", config.kubeconfigRef().toString());
        outputs.put("seedNodeId", seedNodeId);
        outputs.put("seedInstanceUrn", seedInstanceUrn);
        outputs.put("seedProviderUrn", seedProviderUrn);
        outputs.put("seedProvisioningChecksum", provisioningChecksum);
        outputs.put("seedImageBuildChecksum", imageBuildChecksum);
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

    private static void enforceCleanWorktree(Path worktreePath) {
        final Path normalizedWorktreePath = worktreePath.toAbsolutePath().normalize();
        try {
            final FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(normalizedWorktreePath.toFile());
            if (builder.getGitDir() == null) {
                throw new IllegalStateException("No git repository found for worktree: " + normalizedWorktreePath);
            }

            try (Repository repository = builder.build(); Git git = new Git(repository)) {
                final Status status = git.status().call();
                if (status.isClean()) {
                    return;
                }

                final List<String> changes = summarizeStatus(status);
                throw new IllegalStateException(
                        "Pulumi update requires a clean git worktree. Resolve or commit local changes before running Stage A. "
                                + "Worktree: " + normalizedWorktreePath
                                + (changes.isEmpty() ? "" : "\nDirty paths:\n- " + String.join("\n- ", changes)));
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to verify clean git worktree at: " + normalizedWorktreePath,
                    ex);
        }
    }

    private static List<String> summarizeStatus(Status status) {
        final LinkedHashSet<String> paths = new LinkedHashSet<>();
        append(paths, status.getAdded());
        append(paths, status.getChanged());
        append(paths, status.getModified());
        append(paths, status.getRemoved());
        append(paths, status.getMissing());
        append(paths, status.getUntracked());
        append(paths, status.getUntrackedFolders());
        append(paths, status.getConflicting());

        final ArrayList<String> ordered = new ArrayList<>(paths);
        final int maxEntries = 20;
        if (ordered.size() <= maxEntries) {
            return ordered;
        }

        final ArrayList<String> truncated = new ArrayList<>(ordered.subList(0, maxEntries));
        truncated.add("... and " + (ordered.size() - maxEntries) + " more");
        return truncated;
    }

    private static void append(LinkedHashSet<String> target, Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        target.addAll(values);
    }

    private record BootstrapOutputs(Map<String, Object> values) {
    }
}
