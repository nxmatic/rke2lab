#!/usr/bin/env -S bash -euo pipefail

# @codebase
# Enable DBus system bus TCP access on the master node for private-lab diagnostics.
# Canonical behavior only: unix socket remains active, tcp listener is added on vmnet0.

: "Load environment variables from mounted section manifests"
source /srv/host/systemd-scripts.d/rke2lab-env-load.sh
rke2lab::env:load

if [[ "${RKE2LAB_NODE_NAME:-}" != "master" ]]; then
	echo "[rke2lab-dbus-tcp] skipping non-master node (${RKE2LAB_NODE_NAME:-unknown})"
	exit 0
fi

if ! command -v ip >/dev/null 2>&1; then
	echo "[rke2lab-dbus-tcp] missing required command: ip" >&2
	exit 1
fi

dbus_port="${RKE2LAB_DBUS_TCP_PORT:-12434}"
dbus_bind_address="${RKE2LAB_DBUS_TCP_BIND_ADDRESS:-}"

if [[ -z "${dbus_bind_address}" ]]; then
	dbus_bind_address="$(ip -o -4 addr show dev vmnet0 | awk '{print $4}' | cut -d/ -f1 | head -n1)"
fi

if [[ -z "${dbus_bind_address}" ]]; then
	echo "[rke2lab-dbus-tcp] unable to resolve vmnet0 ipv4 address" >&2
	exit 1
fi

dbus_socket_dropin_dir="/etc/systemd/system/dbus.socket.d"
dbus_socket_dropin_file="${dbus_socket_dropin_dir}/40-rke2lab-tcp.conf"
mkdir -p "${dbus_socket_dropin_dir}"

cat >"${dbus_socket_dropin_file}" <<EOF
[Socket]
ListenStream=
ListenStream=/run/dbus/system_bus_socket
ListenStream=${dbus_bind_address}:${dbus_port}
EOF

dbus_policy_dir="/etc/dbus-1/system.d"
dbus_policy_file="${dbus_policy_dir}/40-rke2lab-allow-all.conf"
mkdir -p "${dbus_policy_dir}"

cat >"${dbus_policy_file}" <<'EOF'
<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-BUS Bus Configuration 1.0//EN"
 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
<busconfig>
	<!-- rke2lab-allow-all-policy -->
	<auth>ANONYMOUS</auth>
	<allow_anonymous/>
	<policy context="default">
		<allow send_type="method_call"/>
		<allow send_type="method_return"/>
		<allow send_type="signal"/>
		<allow send_type="error"/>
		<allow send_destination="*"/>
		<allow receive_type="method_call"/>
		<allow receive_type="method_return"/>
		<allow receive_type="signal"/>
		<allow receive_type="error"/>
		<allow eavesdrop="true"/>
		<allow own="*"/>
	</policy>
</busconfig>
EOF

systemctl daemon-reload
systemctl restart dbus.socket
systemctl restart dbus.service

echo "[rke2lab-dbus-tcp] enabled tcp listener on ${dbus_bind_address}:${dbus_port}"
