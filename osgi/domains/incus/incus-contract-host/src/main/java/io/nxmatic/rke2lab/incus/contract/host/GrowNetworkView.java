package io.nxmatic.rke2lab.incus.contract.host;

import java.util.Map;
import java.util.TreeMap;

/**
 * The flat NETWORK view the GROW poses on the Pulumi graph — the two NIC hardware addresses (lan0
 * on the canonical LAN bridge, vmnet0 on the per-cluster bridge) and the resolved dnsmasq config
 * the per-cluster {@code Network} carries (its {@code config} map: {@code raw.dnsmasq} plus the
 * {@code ipv4.*} keys).
 *
 * <p>These all originate in the {@code ClusterNetworkBlueprint} ({@code netplan-contract},
 * OSGi-only), which the host cannot read TYPED. So the scion resolves {@code
 * NetplanSynthesisService} (a published {@code @Component}), assembles the dnsmasq map OSGi-side
 * (pure netplan logic — no host state, no com.pulumi), and projects the three flat values here. The
 * host receives the result and only poses it; it computes nothing of the network.
 */
public record GrowNetworkView(
    String lanHwaddr, String wanHwaddr, Map<String, String> dnsmasqConfig) {

  public GrowNetworkView {
    dnsmasqConfig = new TreeMap<>(dnsmasqConfig);
  }
}
