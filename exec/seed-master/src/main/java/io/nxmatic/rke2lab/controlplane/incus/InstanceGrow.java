package io.nxmatic.rke2lab.controlplane.incus;

import com.pulumi.core.Output;
import com.pulumi.incus.Image;
import com.pulumi.incus.ImageArgs;
import com.pulumi.incus.Instance;
import com.pulumi.incus.InstanceArgs;
import com.pulumi.incus.Network;
import com.pulumi.incus.NetworkArgs;
import com.pulumi.incus.Profile;
import com.pulumi.incus.ProfileArgs;
import com.pulumi.incus.Project;
import com.pulumi.incus.ProjectArgs;
import com.pulumi.incus.inputs.ImageAliasArgs;
import com.pulumi.incus.inputs.ImageSourceFileArgs;
import com.pulumi.incus.inputs.InstanceDeviceArgs;
import com.pulumi.incus.inputs.ProfileDeviceArgs;
import com.pulumi.resources.CustomResourceOptions;
import com.pulumi.resources.Resource;
import io.nxmatic.rke2lab.controlplane.config.BootstrapConfig;
import io.nxmatic.rke2lab.incus.contract.host.BootstrapPaths;
import io.nxmatic.rke2lab.incus.contract.host.BootstrapPaths.HostPathCatalog;
import io.nxmatic.rke2lab.incus.contract.host.GrowImageView;
import io.nxmatic.rke2lab.incus.contract.host.GrowNetworkView;
import io.nxmatic.rke2lab.incus.contract.host.InstanceGrowPlan;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The pure-host GROW beat — the {@code com.pulumi} graph that actualises the {@link
 * InstanceGrowPlan} the incus scion projected (§ host-cellar § the-grow-anatomy, the
 * scion-projects/host-actualises rule). It runs OUTSIDE Felix (the Pulumi graph cannot enter it),
 * so it is NOT a scion; it computes NOTHING of the domain — it fetches the plan, adopts
 * pre-existing project/network via provider invokes ({@link IncusImportLookup}), and declares
 * Project→{Network,Profile,Image}→Instance from the plan's flat values plus the mounts it derives
 * from {@link BootstrapPaths} (dual-realm, same topology the scion materialised into).
 *
 * <p>Instance-passing: it holds the run's {@link BootstrapConfig}, the {@link IncusProviderContext}
 * it builds once, the {@link IncusImportLookup} riding that context's invoke options, and a log
 * sink. Its one act is {@link #grow(InstanceGrowPlan)}.
 */
public final class InstanceGrow {

  private final BootstrapConfig config;
  private final IncusProviderContext providerContext;
  private final IncusImportLookup importLookup;
  private final Consumer<String> log;

  public InstanceGrow(BootstrapConfig config, Consumer<String> log) {
    this.config = config;
    this.providerContext = IncusProviderContext.forBootstrap("seed-incus-provider", config);
    this.importLookup = new IncusImportLookup(providerContext, log);
    this.log = log;
  }

  /** Declare the whole instance graph from the plan; the Pulumi engine schedules it. */
  public void grow(InstanceGrowPlan plan) {
    final Project project = ensureProject();
    ensureNetwork(config.vmnetNetworkName(), project, plan.network());
    final Output<String> profileName = ensureProfile(project);
    final Output<String> imageFingerprint = ensureImage(plan.image(), project);
    createInstance(plan, project, profileName, imageFingerprint);
  }

  private Project ensureProject() {
    final String existingProjectId =
        importLookup.normalizeImportId(importLookup.existingProjectId(config.incusProject()));

    final CustomResourceOptions.Builder options =
        CustomResourceOptions.builder().provider(providerContext.provider()).retainOnDelete(true);
    if (!existingProjectId.isBlank()) {
      options.importId(existingProjectId);
    }

    return new Project(
        "seed-project",
        ProjectArgs.builder()
            .name(config.incusProject())
            // Per-project network namespacing so instance NIC parent references resolve under this
            // project, even though the actual bridges live in the default project (incus only
            // allows
            // OVN networks in non-default projects).
            .config(Map.of("features.networks", "true"))
            .build(),
        options.build());
  }

  /**
   * Ensure the per-cluster {@code vmnet} bridge from the plan's flat network view. Skips the
   * canonical host-provided LAN bridge and any bridge the provider reports UNMANAGED (a provider
   * invoke, not ssh). The vmnet network's config map is the plan's — the scion assembled it
   * OSGi-side from the netplan blueprint; the host only poses it.
   */
  private void ensureNetwork(String networkName, Resource projectDependency, GrowNetworkView view) {
    if (networkName.equals(config.lanBridgeParent())) {
      log.accept(
          "incus network ensure: skipping canonical host-provided bridge (" + networkName + ")");
      return;
    }
    // vmnet-br lives in the default project (only OVN networks are allowed in non-default
    // projects).
    final String networkProject = "default";
    if (importLookup.isUnmanagedNetwork(networkName, networkProject)) {
      log.accept("incus network ensure: skipping unmanaged bridge (" + networkName + ")");
      return;
    }
    final String existingNetworkId =
        importLookup.normalizeImportId(importLookup.existingNetworkId(networkName, networkProject));
    // An already-existing vmnet bridge is adopted, not re-declared (its config is host-owned).
    if (!existingNetworkId.isBlank()) {
      return;
    }

    final CustomResourceOptions options =
        CustomResourceOptions.builder()
            .provider(providerContext.provider())
            .retainOnDelete(true)
            .dependsOn(List.of(projectDependency))
            .ignoreChanges(List.of("project"))
            .build();

    new Network(
        "seed-network-" + networkName,
        NetworkArgs.builder()
            .name(networkName)
            .type("bridge")
            .project("default")
            .config(view.dnsmasqConfig())
            .build(),
        options);
  }

  private Output<String> ensureProfile(Resource projectDependency) {
    final CustomResourceOptions options =
        CustomResourceOptions.builder()
            .provider(providerContext.provider())
            .retainOnDelete(true)
            .dependsOn(List.of(projectDependency))
            .ignoreChanges(List.of("name", "project", "devices", "config", "description"))
            .build();

    final Profile profile =
        new Profile(
            "seed-profile",
            ProfileArgs.builder()
                .name(config.profileName())
                .project(config.incusProject())
                .devices(
                    ProfileDeviceArgs.builder()
                        .name("root")
                        .type("disk")
                        .properties(Map.of("path", "/", "pool", "default"))
                        .build())
                .build(),
            options);

    return profile.name();
  }

  /**
   * Declare the seed image from the plan's artifact paths and return its fingerprint {@link Output}
   * for the instance. The scion already built the artifacts (the edge {@code ImageBuilder}) and
   * projected their readable paths + the alias; the host only schedules the {@code new Image} —
   * {@code com.pulumi}, the one irreducibly-host gesture.
   */
  private Output<String> ensureImage(GrowImageView view, Resource projectDependency) {
    final Image image =
        new Image(
            "seed-image",
            ImageArgs.builder()
                .project(config.incusProject())
                .aliases(ImageAliasArgs.builder().name(view.imageAlias()).build())
                .sourceFile(
                    ImageSourceFileArgs.builder()
                        .metadataPath(view.metadataPath())
                        .dataPath(view.dataPath())
                        .build())
                .build(),
            CustomResourceOptions.builder()
                .provider(providerContext.provider())
                .dependsOn(List.of(projectDependency))
                .build());
    return image.fingerprint();
  }

  private void createInstance(
      InstanceGrowPlan plan,
      Resource projectDependency,
      Output<String> profileName,
      Output<String> imageFingerprint) {
    final Map<String, String> instanceConfig = new LinkedHashMap<>();
    instanceConfig.put(
        "raw.lxc",
        String.join(
            "\n",
            "lxc.mount.auto = proc:rw sys:rw cgroup:rw",
            "lxc.apparmor.profile = unconfined",
            "lxc.cap.drop ="));
    instanceConfig.put("security.privileged", "true");
    instanceConfig.put("security.nesting", "true");
    instanceConfig.put("security.syscalls.intercept.bpf", "true");
    instanceConfig.put("security.syscalls.intercept.bpf.devices", "true");
    // Wire #7: the nocloud checksum arms replaceOnChanges — a changed seed recreates the instance
    // (cloud-init reads its seed once at first boot). Kept as slice.cloud-init for wire stability.
    instanceConfig.put("user.rke2lab.provisioning.slice.cloud-init", plan.cloudInitChecksum());
    instanceConfig.put("user.rke2lab.imageBuildChecksum", plan.image().buildChecksum());

    new Instance(
        "seed-instance",
        InstanceArgs.builder()
            .name(config.nodeName())
            .project(config.incusProject())
            .image(imageFingerprint)
            .profiles(profileName.applyValue(List::of))
            .config(instanceConfig)
            .running(true)
            .devices(seedInstanceDevices(plan.network()))
            .build(),
        CustomResourceOptions.builder()
            .provider(providerContext.provider())
            .deleteBeforeReplace(true)
            .replaceOnChanges(List.of("config", "config.*"))
            .ignoreChanges(List.of("image"))
            .build());
  }

  /**
   * The 17 instance devices, derived host-side from {@link BootstrapPaths} — the same topology the
   * scion materialised into (dual-realm), NFS-automount translated for the NixOS host that mounts
   * them. 2 NICs (hwaddrs from the plan's network view), 2 unix-char (fixed), 13 disks (host source
   * from the automount view, guest target from {@link HostPathCatalog}).
   */
  private List<InstanceDeviceArgs> seedInstanceDevices(GrowNetworkView network) {
    final BootstrapPaths paths =
        BootstrapPaths.fromLocalWorktree(
                config.localWorktreePath(), config.clusterName(), config.nodeName())
            .asAutomountView(config.nfsAutomount(), config.netPrefix());

    final List<InstanceDeviceArgs> devices = new ArrayList<>();
    devices.add(nic("lan0", network.lanHwaddr(), "lan0", "bridged", config.lanBridgeParent()));
    devices.add(nic("vmnet0", network.wanHwaddr(), "vmnet0", "bridged", config.vmnetNetworkName()));
    devices.add(unixChar("kmsg.dev", "/dev/kmsg", "/dev/kmsg"));
    devices.add(unixChar("zfs.dev", "/dev/zfs", "/dev/zfs"));
    devices.add(disk("worktree.dir", paths.worktreeRoot(), HostPathCatalog.WORKTREE.path()));
    devices.add(
        disk("rke2lab.environment.dir", paths.runtimeEnvConfigRoot(), HostPathCatalog.ENV.path()));
    devices.add(disk("rke2lab.scripts.dir", paths.scriptsRoot(), HostPathCatalog.SCRIPTS.path()));
    devices.add(disk("git.dir", paths.gitRoot(), HostPathCatalog.GIT_WORKTREE.path()));
    devices.add(
        disk(
            "rke2lab.systemd.libexec.dir",
            paths.systemdLibexecRoot(),
            HostPathCatalog.SYSTEMD_LIBEXEC.path()));
    devices.add(
        disk("rke2lab.system.dir", paths.systemdRoot(), HostPathCatalog.SYSTEMD_UNITS.path()));
    devices.add(disk("manifests.dir", paths.manifestsRoot(), HostPathCatalog.MANIFESTS.path()));
    devices.add(
        disk("rke2.config.dir", paths.runtimeRke2ConfigRoot(), HostPathCatalog.RKE2_CONFIG.path()));
    devices.add(
        disk(
            "cloudconfig.nocloud.dir",
            paths.runtimeCloudConfigRoot(),
            HostPathCatalog.CLOUDCONFIG_NOCLOUD.path()));
    devices.add(disk("shared.dir", paths.shareRoot(), HostPathCatalog.SHARE.path()));
    devices.add(disk("daemonset.dir", paths.daemonsetRoot(), HostPathCatalog.DAEMONSET.path()));
    devices.add(disk("kubeconfig.dir", paths.kubeconfigRoot(), HostPathCatalog.KUBECONFIG.path()));
    devices.add(disk("nocloud.dir", paths.cloudSeedRoot(), HostPathCatalog.NOCLOUD_SEED.path()));
    return List.copyOf(devices);
  }

  private static InstanceDeviceArgs nic(
      String name, String hwaddr, String ifName, String nictype, String parent) {
    return device(
        name,
        "nic",
        Map.of("hwaddr", hwaddr, "name", ifName, "nictype", nictype, "parent", parent));
  }

  private static InstanceDeviceArgs unixChar(String name, String source, String path) {
    return device(name, "unix-char", Map.of("source", source, "path", path));
  }

  private static InstanceDeviceArgs disk(String name, Path source, String path) {
    return device(name, "disk", Map.of("source", source.toString(), "path", path));
  }

  private static InstanceDeviceArgs device(
      String name, String type, Map<String, String> properties) {
    return InstanceDeviceArgs.builder().name(name).type(type).properties(properties).build();
  }
}
