#!/usr/bin/env bash
set -euxo pipefail

DAEMONSET_SCRIPT_ROOT="${DAEMONSET_SCRIPT_ROOT:-/srv/host/k8s-daemonset.d/runtime/flox-containerd-shim}"

# shellcheck disable=SC1091
source "${DAEMONSET_SCRIPT_ROOT}/.sh.d/daemonset-logging.sh"
daemonset::logging:stderr:setup "${DAEMONSET_SCRIPT_ROOT}/shim-installer-host.sh"

# shellcheck disable=SC1090
source <(flox activate --dir /var/lib/rancher/rke2)

rke2lab::bool:is_true() {
	case "${1:-}" in
	1 | true | TRUE | yes | YES | on | ON)
		return 0
		;;
	*)
		return 1
		;;
	esac
}

rke2lab::env:load() {
	local env_script

	env_script="${RKE2LAB_SCRIPTS_DIR:-/srv/host/systemd-scripts.d}/rke2lab-env-load.sh"
	[[ -r "${env_script}" ]] || return 0

	# shellcheck disable=SC1090
	source "${env_script}"
	declare -F rke2lab::env:load >/dev/null 2>&1 && rke2lab::env:load
}

host::tooling:init() {
	: "Ensure Nix is available in the host environment for shim installer operations"
	NIX_VAR="/nix/var/nix"
	NIX_VAR_PROFILES_DEFAULT="${NIX_VAR}/profiles/default"

	source "${NIX_VAR_PROFILES_DEFAULT}/etc/profile.d/nix-daemon.sh"

	NIX_BIN="${NIX_VAR_PROFILES_DEFAULT}/bin/nix"
	[[ -x "${NIX_BIN}" ]] || {
		echo "Nix binary not found at ${NIX_BIN}" >&2
		exit 1
	}
	export NIX_BIN

	FLOX_BIN="${NIX_VAR_PROFILES_DEFAULT}/bin/flox"
	[[ -x "${FLOX_BIN}" ]] || {
		echo "Flox CLI not found at ${FLOX_BIN}" >&2
		exit 1
	}
	export FLOX_BIN

	GIT_BIN="${NIX_VAR_PROFILES_DEFAULT}/bin/git"
	[[ -x "${GIT_BIN}" ]] || {
		echo "Git binary not found at ${GIT_BIN}" >&2
		exit 1
	}
	export GIT_BIN

	mkdir -p /usr/bin
	ln -sf "${NIX_BIN}" /usr/bin/nix
	ln -sf "${FLOX_BIN}" /usr/bin/flox
	ln -sf "${GIT_BIN}" /usr/bin/git

	export PATH="${NIX_VAR_PROFILES_DEFAULT}/bin:${PATH}"
	export PATH="/var/lib/rancher/rke2/bin:/var/lib/rancher/rke2/agent/bin:${PATH}"

	export FLOX_NO_TELEMETRY=1
	export FLOX_NONINTERACTIVE=1
}

host::tooling:bin:resolve() {
	local bin_name="$1"
	local candidate

	if candidate="$(command -v "${bin_name}" 2>/dev/null)" && [[ -n "${candidate}" && -x "${candidate}" ]]; then
		printf '%s\n' "${candidate}"
		return 0
	fi

	for candidate in \
		"/var/lib/rancher/rke2/.flox/run"/*/bin/"${bin_name}" \
		"/nix/var/nix/profiles/default/bin/${bin_name}" \
		"/root/.nix-profile/bin/${bin_name}"; do
		if [[ -x "${candidate}" ]]; then
			printf '%s\n' "${candidate}"
			return 0
		fi
	done

	echo "required binary not found: ${bin_name}" >&2
	return 1
}

host::tooling:config-tools:resolve() {
	YQ_BIN="$(host::tooling:bin:resolve yq)"
	DASEL_BIN="$(host::tooling:bin:resolve dasel)"
	export YQ_BIN DASEL_BIN
}

host::nix:flox-conf:ensure() {
	local flox_conf

	[[ -f "${flox_conf:=/etc/nix/flox.conf}" ]] &&
		return 0

	install -m 0644 -T /dev/null "${flox_conf}"
}

shim::assets:path:init() {
	FLOX_SHIM_ROOT="/srv/host/k8s-daemonset.d/runtime/flox-containerd-shim"
	FLOX_BUILD_SCRIPT="${FLOX_SHIM_ROOT}/flox-shim-build.sh"
	FLOX_BUILD_DESCRIPTOR="${FLOX_SHIM_ROOT}/flox-shim-build.yaml"
	FLOX_SHIM_PACKAGE_FLAKE="${FLOX_SHIM_ROOT}/flake.nix"
	FLOX_ROOTFS_SYNC_SCRIPT="${FLOX_SHIM_ROOT}/flox-rootfs-sync.sh"
	FLOX_SHIM_MESH_DIR="${FLOX_SHIM_ROOT}/mesh"
	FLOX_SHIM_NETWORKING_DIR="${FLOX_SHIM_ROOT}/networking"
	FLOX_SHIM_DEBUG_TOOLS_DIR="${FLOX_SHIM_ROOT}/debug-tools"
	RKE2LAB_DEBUG_SHARE_ROOT="${RKE2LAB_DEBUG_SHARE_ROOT:-/srv/host/rke2lab-share.d}"
}

shim::assets:path:validate() {
	[[ -x "${FLOX_BUILD_SCRIPT}" ]] || {
		echo "flox build script missing or not executable: ${FLOX_BUILD_SCRIPT}" >&2
		exit 1
	}
	[[ -r "${FLOX_BUILD_DESCRIPTOR}" ]] || {
		echo "flox build descriptor missing or unreadable: ${FLOX_BUILD_DESCRIPTOR}" >&2
		exit 1
	}
	[[ -r "${FLOX_SHIM_PACKAGE_FLAKE}" ]] || {
		echo "flox shim package flake missing or unreadable: ${FLOX_SHIM_PACKAGE_FLAKE}" >&2
		exit 1
	}
	[[ -x "${FLOX_ROOTFS_SYNC_SCRIPT}" ]] || {
		echo "flox rootfs sync helper missing or not executable: ${FLOX_ROOTFS_SYNC_SCRIPT}" >&2
		exit 1
	}
	[[ -d "${FLOX_SHIM_MESH_DIR}" ]] || {
		echo "flox shim mesh directory missing: ${FLOX_SHIM_MESH_DIR}" >&2
		exit 1
	}
	[[ -d "${FLOX_SHIM_NETWORKING_DIR}" ]] || {
		echo "flox shim networking directory missing: ${FLOX_SHIM_NETWORKING_DIR}" >&2
		exit 1
	}
	[[ -d "${FLOX_SHIM_DEBUG_TOOLS_DIR}" ]] || {
		echo "flox shim debug tools directory missing: ${FLOX_SHIM_DEBUG_TOOLS_DIR}" >&2
		exit 1
	}
}

shim::debug:any_enabled() {
	rke2lab::bool:is_true "${RKE2LAB_POLICY_DEBUG_KDNS_ENABLED:-false}" ||
		rke2lab::bool:is_true "${RKE2LAB_POLICY_DEBUG_FLOX_SHIM_WRAPPER_ENABLED:-false}"
}

shim::debug:tools:install() {
	local source_root target_root source_path relative target_path install_mode force_install

	if ! shim::debug:any_enabled; then
		echo "debug helper installation skipped: no debug policy enabled"
		return 0
	fi

	source_root="${FLOX_SHIM_DEBUG_TOOLS_DIR}"
	target_root="${RKE2LAB_DEBUG_SHARE_ROOT}"
	force_install="${RKE2LAB_DEBUG_SHARE_FORCE_INSTALL:-false}"

	mkdir -p "${target_root}"

	while IFS= read -r -d '' source_path; do
		relative="${source_path#${source_root}/}"
		target_path="${target_root%/}/${relative}"
		install_mode="0644"

		case "$(basename -- "${source_path}")" in
		*.sh)
			install_mode="0755"
			;;
		esac

		install -d "$(dirname -- "${target_path}")"

		if [[ -e "${target_path}" ]] && ! rke2lab::bool:is_true "${force_install}"; then
			echo "preserving existing debug helper at ${target_path}"
			continue
		fi

		install -m "${install_mode}" "${source_path}" "${target_path}"
	done < <(find "${source_root}" -type f -print0 | sort -z)

	echo "installed debug helper scripts in ${target_root}"
}

shim::assets:build:run() {
	: "Ensure we have a git repository in the flox shim root for build operations, and set a default user if not already configured"
	if [[ ! -d "${FLOX_SHIM_ROOT}/.git" ]]; then
		git -C "${FLOX_SHIM_ROOT}" init --initial-branch=main
	fi

	if [[ -z "$(git -C "${FLOX_SHIM_ROOT}" config --get user.name || true)" ]]; then
		git -C "${FLOX_SHIM_ROOT}" config user.name "rke2lab-flox-shim"
	fi
	if [[ -z "$(git -C "${FLOX_SHIM_ROOT}" config --get user.email || true)" ]]; then
		git -C "${FLOX_SHIM_ROOT}" config user.email "rke2lab-flox-shim@localhost"
	fi

	: "Run the flox build script to materialize the shim build output onto the host filesystem for use in installation"
	"${FLOX_BUILD_SCRIPT}" "host" "${FLOX_BUILD_DESCRIPTOR}"

	: "Commit any changes to the flox shim build assets to the git repository for tracking"
	git -C "${FLOX_SHIM_ROOT}" add --all .
	if ! git -C "${FLOX_SHIM_ROOT}" diff --cached --quiet; then
		git -C "${FLOX_SHIM_ROOT}" commit -m "chore(flox-shim): refresh packaged flakes"
	fi
}

shim::runtime:nix-system:resolve() {
	case "$(uname -m)" in
	aarch64 | arm64)
		printf '%s\n' "aarch64-linux"
		;;
	x86_64 | amd64)
		printf '%s\n' "x86_64-linux"
		;;
	*)
		echo "unsupported host architecture: $(uname -m)" >&2
		return 1
		;;
	esac
}

shim::runtime:wrapper-package:build() {
	local package_name="$1"
	local nix_system package_attr

	nix_system="$(shim::runtime:nix-system:resolve)"
	package_attr="packages.${nix_system}.${package_name}"

	(
		cd "${FLOX_SHIM_ROOT}"
		nix build \
			--system "${nix_system}" \
			--extra-experimental-features nix-command \
			--extra-experimental-features flakes \
			--no-link \
			--print-out-paths \
			".#${package_attr}"
	)
}

shim::runtime:containerd:resolve-bin() {
	if command -v containerd >/dev/null 2>&1; then
		command -v containerd
		return 0
	fi
	if [[ -x /var/lib/rancher/rke2/bin/containerd ]]; then
		printf '%s\n' "/var/lib/rancher/rke2/bin/containerd"
		return 0
	fi
	if [[ -x /var/lib/rancher/rke2/agent/bin/containerd ]]; then
		printf '%s\n' "/var/lib/rancher/rke2/agent/bin/containerd"
		return 0
	fi

	echo "containerd binary not found" >&2
	return 1
}

shim::runtime:package:resolve() {
	local containerd_bin="$1"
	local containerd_version containerd_major

	read -r _ _ containerd_version _ < <("${containerd_bin}" --version)
	containerd_version="${containerd_version#v}"
	containerd_major="${containerd_version%%.*}"
	if [[ -z "${containerd_major}" ]]; then
		echo "unable to determine containerd version" >&2
		return 1
	fi

	if [[ "${containerd_major}" -ge 2 ]]; then
		printf '%s\n' "flox/containerd-shim-flox-2x"
	else
		printf '%s\n' "flox/containerd-shim-flox-17"
	fi
}

shim::runtime:env:ensure() {
	local flox_env_dir="$1"

	mkdir -p "${CONTAINERD_CONFIG_DIR}"
	mkdir -p "${flox_env_dir}"
	if [[ ! -d "${flox_env_dir}/.flox" ]]; then
		(cd "${flox_env_dir}" && flox init)
	fi
}

shim::runtime:gcroots:ensure() {
	local gcroots_dir gcroots_link

	gcroots_dir="${NIX_GC_ROOTS:-/nix/var/nix/gcroots}/flox"
	# gcroots_link="${gcroots_dir}/system-profile"
	mkdir -p "${gcroots_dir}"
	# if [[ ! -e "${gcroots_link}" ]]; then
	#  ln -s /nix/var/nix/profiles/default "${gcroots_link}"
	# fi
}

shim::runtime:binary:install() {
	local flox_env_dir="$1"
	local arch="$2"
	local shim_run_dir shim_path
	local install_root real_shim_path
	local wrapper_pkg_path wrapper_bin wrapper_helper
	local debug_wrapper_pkg_path debug_wrapper_bin

	shim_run_dir="$(find "${flox_env_dir}/.flox/run" -maxdepth 1 -name "${arch}-linux.containerd-shim*.run" -print -quit || true)"
	if [[ -z "${shim_run_dir}" ]]; then
		echo "unable to locate Flox shim run directory" >&2
		return 1
	fi

	shim_path="$(realpath "${shim_run_dir}")/bin/containerd-shim-flox-v2"
	if [[ ! -f "${shim_path}" ]]; then
		echo "shim binary missing at ${shim_path}" >&2
		return 1
	fi

	install_root="/usr/local/libexec/rke2lab/flox-shim-wrapper"
	real_shim_path="${install_root}/containerd-shim-flox-v2.real"
	wrapper_pkg_path="$(shim::runtime:wrapper-package:build flox-shim-wrapper)"
	wrapper_bin="${wrapper_pkg_path}/bin/containerd-shim-flox-v2"
	wrapper_helper="${wrapper_pkg_path}/libexec/rke2lab/flox-shim-wrapper/flox-rootfs-sync.sh"
	debug_wrapper_pkg_path="$(shim::runtime:wrapper-package:build delve-sidecar)"
	debug_wrapper_bin="${debug_wrapper_pkg_path}/bin/containerd-shim-flox-v2"

	[[ -x "${wrapper_bin}" ]] || {
		echo "shim wrapper binary missing at ${wrapper_bin}" >&2
		return 1
	}
	[[ -x "${debug_wrapper_bin}" ]] || {
		echo "debug shim wrapper binary missing at ${debug_wrapper_bin}" >&2
		return 1
	}
	[[ -x "${wrapper_helper}" ]] || {
		echo "shim rootfs helper missing at ${wrapper_helper}" >&2
		return 1
	}

	install -D -m 0755 "${shim_path}" "${real_shim_path}"
	install -d /usr/local/bin "${install_root}"
	install -D -m 0755 "${wrapper_bin}" "${install_root}/containerd-shim-flox-v2"
	install -D -m 0755 "${debug_wrapper_bin}" "${install_root}/containerd-shim-flox-delve-v2"
	ln -sfn "${wrapper_helper}" "${install_root}/flox-rootfs-sync.sh"
	ln -sfn "${install_root}/containerd-shim-flox-v2" /usr/local/bin/containerd-shim-flox-v2
	ln -sfn "${install_root}/containerd-shim-flox-delve-v2" /usr/local/bin/containerd-shim-flox-delve-v2
}

shim::runtime:config-template:ensure() {
	if [[ ! -f "${CONTAINERD_CONFIG_TEMPLATE}" ]]; then
		cp "${CONTAINERD_CONFIG_FILE}" "${CONTAINERD_CONFIG_TEMPLATE}"
	fi
}

shim::runtime:core:install() {
	: "Install/refresh flox runtime shim binaries on host"
	local flox_env_dir arch containerd_bin shim_pkg

	flox_env_dir="/var/lib/flox-runtime/containerd-shim"
	arch="$(uname -m)"

	shim::runtime:env:ensure "${flox_env_dir}"
	shim::runtime:gcroots:ensure
	containerd_bin="$(shim::runtime:containerd:resolve-bin)"
	shim_pkg="$(shim::runtime:package:resolve "${containerd_bin}")"
	flox install --dir "${flox_env_dir}" "${shim_pkg}"
	shim::runtime:binary:install "${flox_env_dir}" "${arch}"
	shim::runtime:config-template:ensure
}

containerd::config:path:init() {
	CONTAINERD_CONFIG_DIR="${CONTAINERD_CONFIG_DIR:-$(dirname ${CONTAINERD_CONFIG_FILE})}"
	CONTAINERD_CONFIG_FILE="${CONTAINERD_CONFIG_FILE:-${CONTAINERD_CONFIG_DIR}/config.toml}"
	CONTAINERD_CONFIG_TEMPLATE="${CONTAINERD_CONFIG_FILE}.tmpl"
	CONTAINERD_CONFIG_BASENAME="$(basename --suffix=.toml "${CONTAINERD_CONFIG_FILE}")"
}

container::service:runtime:restart() {
	if systemctl is-active rke2-server >/dev/null; then
		systemctl restart rke2-server
	elif systemctl is-active rke2-agent >/dev/null; then
		systemctl restart rke2-agent
	elif systemctl is-active containerd >/dev/null; then
		systemctl restart containerd
	else
		echo "no known service to restart" >&2
	fi
}

containerd::config:version:detect() {
	local version
	version="$("${DASEL_BIN}" -i toml -o yaml <"${CONTAINERD_CONFIG_FILE}" | "${YQ_BIN}" -r '.version // ""' 2>/dev/null || true)"

	if [[ -z "${version}" ]]; then
		case "$(basename "${CONTAINERD_CONFIG_FILE}")" in
		config-v3.toml | config-v3.toml.tmpl)
			version="3"
			;;
		*)
			version="2"
			;;
		esac
	fi

	printf '%s\n' "${version}"
}

containerd::config:flox:update() {
	local version plugin_root tmp
	version="$(containerd::config:version:detect)"
	if [[ "${version}" == "3" ]]; then
		plugin_root="io.containerd.cri.v1.runtime"
	else
		plugin_root="io.containerd.grpc.v1.cri"
	fi
	tmp="$(mktemp)" &&
		trap "rm -f ${tmp}" RETURN

	"${DASEL_BIN}" -i toml -o yaml <"${CONTAINERD_CONFIG_TEMPLATE}" |
		CRI_PLUGIN_ROOT="${plugin_root}" "${YQ_BIN}" '
      del(.plugins."io.containerd.cri.v1.runtime".containerd.runtimes.flox) |
	  del(.plugins."io.containerd.cri.v1.runtime".containerd.runtimes."flox-delve") |
      del(.plugins."io.containerd.grpc.v1.cri".containerd.runtimes.flox) |
	  del(.plugins."io.containerd.grpc.v1.cri".containerd.runtimes."flox-delve") |
      .plugins[env(CRI_PLUGIN_ROOT)].containerd.runtimes.flox.runtime_path = "/usr/local/bin/containerd-shim-flox-v2" |
      .plugins[env(CRI_PLUGIN_ROOT)].containerd.runtimes.flox.runtime_type = "io.containerd.runc.v2" |
      .plugins[env(CRI_PLUGIN_ROOT)].containerd.runtimes.flox.pod_annotations = ["flox.dev/*"] |
      .plugins[env(CRI_PLUGIN_ROOT)].containerd.runtimes.flox.container_annotations = ["flox.dev/*"] |
	  .plugins[env(CRI_PLUGIN_ROOT)].containerd.runtimes.flox.options.SystemdCgroup = true |
	  .plugins[env(CRI_PLUGIN_ROOT)].containerd.runtimes."flox-delve".runtime_path = "/usr/local/bin/containerd-shim-flox-delve-v2" |
	  .plugins[env(CRI_PLUGIN_ROOT)].containerd.runtimes."flox-delve".runtime_type = "io.containerd.runc.v2" |
	  .plugins[env(CRI_PLUGIN_ROOT)].containerd.runtimes."flox-delve".pod_annotations = ["flox.dev/*"] |
	  .plugins[env(CRI_PLUGIN_ROOT)].containerd.runtimes."flox-delve".container_annotations = ["flox.dev/*"] |
	  .plugins[env(CRI_PLUGIN_ROOT)].containerd.runtimes."flox-delve".options.SystemdCgroup = true
    ' |
		"${DASEL_BIN}" -i yaml -o toml >"${tmp}" &&
		mv "${tmp}" "${CONTAINERD_CONFIG_TEMPLATE}"
}

: "Initialize host tooling and shim asset paths"
host::tooling:init
host::tooling:config-tools:resolve
host::nix:flox-conf:ensure
shim::assets:path:init
shim::assets:path:validate
rke2lab::env:load

: "Initialize resolved containerd config paths"
containerd::config:path:init

: "Execute the shim build before mutating containerd config"
shim::assets:build:run

: "Install/update flox runtime shim binaries on host"
shim::runtime:core:install

: "Install repository-owned shim debug helpers into the shared directory when debug policy is enabled"
shim::debug:tools:install

: "Update containerd configuration to include the flox shim runtime"
containerd::config:flox:update

: "Restart containerd to apply shim installation changes"
container::service:runtime:restart
