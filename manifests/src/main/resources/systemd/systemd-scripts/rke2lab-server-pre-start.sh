#!/usr/bin/env bash

set -exu -o pipefail

set +x # Silence flox activation noise
source <(flox activate --dir /var/lib/rancher/rke2)
set -x

db::check() {
	local -A inet=([current]="$(nmcli -g IP4.ADDRESS device show vmnet0)")
	local file="/var/lib/rancher/rke2/server/last-ip"
	if [[ -r "$file" ]]; then
		inet+=([last]="$(cat "$file")")
	else
		inet+=([last]="")
	fi
	if [[ "${inet[current]}" != "${inet[last]}" ]]; then
		: "IP address changed: ${inet[last]} - ${inet[current]}, resetting RKE2 server DB"
		rm -rf /var/lib/rancher/rke2/server/db
		mkdir -p /var/lib/rancher/rke2/server/db
		echo "${inet[current]}" >"$file"
	fi
}

nri::enable() {
	local config_dir="/var/lib/rancher/rke2/agent/etc/containerd/config-v3.toml.d"
	mkdir -p "${config_dir}"

	# Enable NRI before RKE2 starts via containerd config import
	# RKE2 will read: imports = ['/var/lib/rancher/rke2/agent/etc/containerd/config-v3.toml.d/*.toml']
	cat >"${config_dir}/90-nri.toml" <<-'EOF'
		[plugins."io.containerd.nri.v1.nri"]
		  disable = false
		  plugin_config_path = "/etc/nri/conf.d"
		  plugin_path = "/opt/nri/plugins"

		[plugins."io.containerd.cri.v1.runtime".containerd.runtimes.runc.options]
		  SystemdCgroup = true
	EOF
}

: "Check server database for IP address changes"
db::check

: "Enable NRI for containerd plugins (avoids restart)"
nri::enable

exit 0
