package io.nxmatic.rke2lab.netplan.bdd;

import com.tngtech.jgiven.Stage;
import com.tngtech.jgiven.annotation.ExpectedScenarioState;
import com.tngtech.jgiven.annotation.Hidden;
import com.tngtech.jgiven.annotation.ProvidedScenarioState;
import com.tngtech.jgiven.base.ScenarioTestBase;
import com.tngtech.jgiven.impl.Scenario;
import inet.ipaddr.IPAddressString;
import io.nxmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import io.nxmatic.rke2lab.netplan.contract.NetplanRunbookInput;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.InputReceiver;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioInputSeed;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioPlayer;
import io.nxmatic.rke2lab.osgi.runtime.scenario.engine.container.SeedScenario;
import io.nxmatic.rke2lab.seed.broker.codec.SeedCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The netplan blueprint-export scenario — a production jGiven scenario told in the NETPLAN DOMAIN's
 * own vocabulary and played IN-CONTAINER by the engine. It is the transposition of the former flat
 * {@code BlueprintExportCommand}: from the (hardcoded) cluster/node topology it derives every
 * node's {@link ClusterNetworkBlueprint} and projects the complete addressing metadata (MACs, IPv4
 * + the ULA v6 mirror, DHCP leases), then MATERIALISES it as {@code blueprint.json} into the SOIL.
 *
 * <p>Why in-container and not a flat CLI dump: {@link ClusterNetworkBlueprint} is a {@code
 * type=contract} bundle record — it lives in the bundle realm and cannot cross the host frontier as
 * a TYPE (a flat reference {@code NoClassDefFoundError}s, the realm-boundary law is right). So the
 * derivation runs HERE, where the type is reachable, and the result crosses to the host as pure
 * JSON: the scion serialises the metadata tree to {@code blueprint.json}, the netplan-cli reads
 * that generic JSON (never the contract type) and converts it to YAML flat. SAFE for the flake
 * bridge — nix-darwin-home re-parses via {@code yq -o=json}, so only the DATA matters, not YAML
 * formatting.
 *
 * <p>MODE-BLIND like the manifests scion: a pure FS materialiser with no live touch, so it runs
 * identically in both modes; the materialisation target is carried by the SOIL amendment alone (the
 * host's export dir when amended, a temp dir for a bare survey). The input is seeded by the
 * front-door via the inbound {@link #INPUT} channel and received here ({@link InputReceiver})
 * before the play.
 */
@SeedScenario
public class NetplanBlueprintScenario
    extends ScenarioTestBase<
        NetplanBlueprintScenario.Given,
        NetplanBlueprintScenario.When,
        NetplanBlueprintScenario.Then>
    implements InputReceiver<NetplanRunbookInput>, ScenarioPlayer.Playable {

  /**
   * The inbound channel the runbook handler ({@code NetplanRunbookHandler.seedFrom}) seeds the
   * {@link NetplanRunbookInput} through and this scenario receives it from. Single-sourced here.
   */
  @RegisterExtension
  public static final ScenarioInputSeed<NetplanRunbookInput> INPUT =
      new ScenarioInputSeed<>(NetplanRunbookInput.class, "netplan-runbook-input");

  private final Scenario<Given, When, Then> scenario = createScenario();

  @MonotonicNonNull private NetplanRunbookInput input;

  @Override
  public Scenario<Given, When, Then> getScenario() {
    return scenario;
  }

  @Override
  public void receiveInput(NetplanRunbookInput input) {
    this.input = input;
  }

  @Test
  void the_blueprint_is_exported_to_the_soil() {
    final NetplanRunbookInput facet =
        Objects.requireNonNull(input, "the netplan runbook input was not seeded before the body");
    given().the_runbook_input(facet);
    when().the_blueprint_metadata_is_derived().and().the_blueprint_is_written_as_json();
    then().the_blueprint_file_is_written();
  }

  /** Given: the runbook input carrying the SOIL to materialise into. */
  public static class Given extends Stage<Given> {

    @ProvidedScenarioState NetplanRunbookInput facet;

    @Hidden
    public Given the_runbook_input(NetplanRunbookInput facet) {
      this.facet = facet;
      return self();
    }
  }

  /**
   * When: the transposition of {@code BlueprintExportCommand.execute}. Derives the complete
   * addressing metadata from the cluster/node topology (each node's {@link
   * ClusterNetworkBlueprint}) and writes it as {@code blueprint.json} into the SOIL.
   */
  public static class When extends Stage<When> {

    @ExpectedScenarioState NetplanRunbookInput facet;

    @ProvidedScenarioState Path blueprintFile;

    private final SeedCodec codec = new SeedCodec();

    // Derived by the first WHEN step, read by the second on the same stage instance — intra-stage,
    // so a plain field, not a cross-stage @ProvidedScenarioState.
    @MonotonicNonNull private NetworkBlueprintMetadata metadata;

    public When the_blueprint_metadata_is_derived() {
      // Cluster ID mappings (nikopol was renamed from alcide, keeping cluster ID 1).
      final Map<String, Integer> clusters = new LinkedHashMap<>();
      clusters.put("bioskop", 0);
      clusters.put("nikopol", 1);

      final Map<String, Integer> nodes = new LinkedHashMap<>();
      nodes.put("master", 0);
      nodes.put("peer1", 1);
      nodes.put("peer2", 2);
      nodes.put("peer3", 3);
      nodes.put("worker1", 10);
      nodes.put("worker2", 11);

      final Map<String, String> macPatterns = new LinkedHashMap<>();
      macPatterns.put("lan", "10:66:6a:4c:{clusterId:02x}:{nodeId:02x}");
      macPatterns.put("wan", "52:54:00:{clusterId:02x}:{nodeType:02x}:{nodeId:02x}");
      macPatterns.put("lanBridge", "02:00:00:bb:{clusterId:02x}:{nodeId:02x}");

      final Map<String, Integer> nodeTypes = new LinkedHashMap<>();
      nodeTypes.put("SERVER", 0); // master, peer1-3
      nodeTypes.put("AGENT", 1); // worker1-2

      final Map<String, Map<String, NodeAddressing>> allAddressing = new LinkedHashMap<>();
      for (String cluster : clusters.keySet()) {
        final Map<String, NodeAddressing> clusterAddressing = new LinkedHashMap<>();
        for (String node : nodes.keySet()) {
          final ClusterNetworkBlueprint bp =
              ClusterNetworkBlueprint.builder()
                  .cluster(cluster)
                  .node(node)
                  .deriveRecipeModel()
                  .build();

          final NodeMacs macs =
              new NodeMacs(
                  bp.lan().hostMacaddr().value(),
                  bp.wan().hostMacaddr().value(),
                  bp.lan().bridgeMacaddr().value());

          final NodeIPs ips =
              new NodeIPs(
                  bp.lan().hostInetaddr().getHostAddress(),
                  bp.nodeNetwork().nodeHostInetaddr().getHostAddress(),
                  bp.lan().gatewayInetaddr().getHostAddress(),
                  bp.nodeNetwork().nodeGatewayInetaddr().getHostAddress(),
                  mixed(bp.lan().hostInetaddr6()),
                  mixed(bp.nodeNetwork().nodeHostInetaddr6()),
                  mixed(bp.lan().gatewayInetaddr6()),
                  mixed(bp.nodeNetwork().nodeGatewayInetaddr6()),
                  bp.lan().nodeCidr6().toString(),
                  bp.nodeNetwork().nodeCidr6().toString());

          final LanLease lanLease =
              new LanLease(
                  bp.lan().hostMacaddr().value(),
                  bp.lan().hostInetaddr().getHostAddress(),
                  bp.lan().nodeCidr().toString());
          final WanLease wanLease =
              new WanLease(bp.wan().hostMacaddr().value(), bp.wan().dhcpRange());
          final NodeLeases leases = new NodeLeases(lanLease, wanLease);

          clusterAddressing.put(node, new NodeAddressing(macs, ips, leases));
        }
        allAddressing.put(cluster, clusterAddressing);
      }

      this.metadata =
          new NetworkBlueprintMetadata(clusters, nodes, macPatterns, nodeTypes, allAddressing);
      return self();
    }

    public When the_blueprint_is_written_as_json() {
      final Path root = resolveSoil();
      final Path file = root.resolve("blueprint.json");
      try {
        Files.createDirectories(root);
        // SeedCodec renders the metadata tree to JSON — the wire the host reaps. The scion never
        // hands the host a ClusterNetworkBlueprint type, only this serialized String.
        Files.writeString(
            file,
            codec.encode(
                Objects.requireNonNull(
                    metadata, "the metadata step must run before the write step")));
      } catch (IOException ex) {
        throw new UncheckedIOException("cannot write the blueprint export " + file, ex);
      }
      this.blueprintFile = file;
      return self();
    }

    private Path resolveSoil() {
      return facet
          .materializationRoot()
          .map(soil -> Path.of(soil).toAbsolutePath().normalize())
          .orElseGet(this::freshTempDir);
    }

    private Path freshTempDir() {
      try {
        return Files.createTempDirectory("rke2lab-netplan-").toAbsolutePath().normalize();
      } catch (IOException ex) {
        throw new UncheckedIOException("cannot create the blueprint export dir", ex);
      }
    }

    /** Format a v6 address in IPv4-embedded mixed notation (fd..:CCRR::a.b.c.d) for readability. */
    private static String mixed(InetAddress v6) {
      return new IPAddressString(v6.getHostAddress()).getAddress().toIPv6().toMixedString();
    }
  }

  /** Then: the export landed — {@code blueprint.json} exists and is non-empty. */
  public static class Then extends Stage<Then> {

    @ExpectedScenarioState Path blueprintFile;

    public Then the_blueprint_file_is_written() {
      if (!Files.exists(blueprintFile)) {
        throw new NetplanExportError(blueprintFile, NetplanExportError.Reason.MISSING);
      }
      final long size;
      try {
        size = Files.size(blueprintFile);
      } catch (IOException ex) {
        throw new UncheckedIOException("cannot stat the blueprint export " + blueprintFile, ex);
      }
      if (size <= 0) {
        throw new NetplanExportError(blueprintFile, NetplanExportError.Reason.EMPTY);
      }
      return self();
    }
  }

  // The exported metadata tree, mirroring the former flat BlueprintExportCommand records EXACTLY so
  // the JSON keys the flake consumes (clusters/nodes/macPatterns/nodeTypes/addressing) are
  // byte-identical. SeedCodec serialises records by component name.
  record NetworkBlueprintMetadata(
      Map<String, Integer> clusters,
      Map<String, Integer> nodes,
      Map<String, String> macPatterns,
      Map<String, Integer> nodeTypes,
      Map<String, Map<String, NodeAddressing>> addressing) {}

  record NodeAddressing(NodeMacs macs, NodeIPs ips, NodeLeases leases) {}

  record NodeMacs(String lan, String wan, String lanBridge) {}

  record NodeIPs(
      String lanHost,
      String nodeHost,
      String lanGateway,
      String nodeGateway,
      String lanHost6,
      String nodeHost6,
      String lanGateway6,
      String nodeGateway6,
      String lanCidr6,
      String nodeCidr6) {}

  record NodeLeases(LanLease lan, WanLease wan) {}

  record LanLease(String mac, String ip, String cidr) {}

  record WanLease(String mac, String dhcpRange) {}
}
