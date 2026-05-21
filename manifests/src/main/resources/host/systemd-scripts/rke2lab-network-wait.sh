#!/usr/bin/env -S bash -exu -o pipefail

# Log all operations
exec > >(logger -t rke2-network-wait) 2>&1

: "=== RKE2 Network Wait at $(date) ==="

# Wait for networkd to be active
while ! systemctl is-active systemd-networkd >/dev/null 2>&1; do
	: "[.] Waiting for systemd-networkd..."
	sleep 2
done

# Wait for interfaces to be configured
for iface in vmnet0 lan0; do
	: "[+] Waiting for interface $iface..."
	timeout=30
	while [ $timeout -gt 0 ]; do
		if ip link show "$iface" >/dev/null 2>&1 &&
			networkctl status "$iface" | grep -q "State: configured\|routable" 2>/dev/null; then
			: "[i] Interface $iface is ready"
			break
		fi
		: "[.] Waiting for $iface (timeout: $timeout)..."
		sleep 1
		timeout=$((timeout - 1))
	done

	if [ $timeout -eq 0 ]; then
		: "[!] Warning: Interface $iface not ready after 30 seconds"
	fi
done

# Brief additional wait for routes to stabilize
: "[+] Allowing routes to stabilize..."
sleep 3

# Wait for DNS resolution to work with IPv4
: "[+] Waiting for IPv4 DNS resolution..."
timeout=30
while [ $timeout -gt 0 ]; do
	# Try to resolve a known hostname using IPv4-only DNS
	if getent ahosts raw.githubusercontent.com >/dev/null 2>&1 ||
		host -4 raw.githubusercontent.com 8.8.8.8 >/dev/null 2>&1; then
		: "[i] DNS resolution working"
		break
	fi
	: "[.] Waiting for DNS resolution (timeout: $timeout)..."
	sleep 1
	timeout=$((timeout - 1))
done

if [ $timeout -eq 0 ]; then
	: "[!] Warning: DNS resolution not working after 30 seconds"
	: "[!] Attempting to force IPv4-only DNS configuration..."

	# Emergency: force systemd-resolved to use IPv4 fallback DNS
	mkdir -p /etc/systemd/resolved.conf.d
	cat >/etc/systemd/resolved.conf.d/00-emergency-ipv4.conf <<'EOF'
[Resolve]
FallbackDNS=8.8.8.8 1.1.1.1
DNS=8.8.8.8 1.1.1.1
DNSOverTLS=no
DNSSEC=no
Domains=~.
EOF
	resolvectl flush-caches 2>/dev/null || true
	systemctl restart systemd-resolved 2>/dev/null || true

	# Wait a bit more for DNS to work
	sleep 5
	if ! getent ahosts raw.githubusercontent.com >/dev/null 2>&1; then
		: "[!] ERROR: DNS still not working after emergency fix"
		exit 1
	fi
	: "[i] DNS resolution working after emergency fix"
fi

: "=== Final Network Status ==="
ip addr show || true
: "=== Final Routes ==="
ip route show || true
: "=== DNS Status ==="
resolvectl status || true

: "=== Network Wait Complete at $(date) ==="
