#!/usr/bin/env bash
set -exuo pipefail

DAEMONSET_ASSET_ROOT="/srv/host/k8s-daemonset.d/runtime/flox-runtime"
DAEMONLESS_EXEC_MODE="${DAEMONLESS_EXEC_MODE:-pod}"
DAEMONSET_SCRIPT_ROOT="${DAEMONSET_SCRIPT_ROOT:-${DAEMONLESS_HOST_SCRIPT_ROOT:-${DAEMONSET_ASSET_ROOT}}}"
DAEMONLESS_HOST_SCRIPT_ROOT="${DAEMONLESS_HOST_SCRIPT_ROOT:-${DAEMONSET_SCRIPT_ROOT}}"
DAEMONLESS_HOST_SCRIPT_LIB_DIR="${DAEMONLESS_HOST_SCRIPT_LIB_DIR:-${DAEMONLESS_HOST_SCRIPT_ROOT%/}/.sh.d}"
DAEMONLESS_HOST_SCRIPT_BIN="${DAEMONLESS_HOST_SCRIPT_BIN:-${DAEMONLESS_HOST_SCRIPT_ROOT%/}/bin}"

installer::mode:validate() {
	case "${DAEMONLESS_EXEC_MODE}" in
	host | pod)
		return 0
		;;
	*)
		echo "unsupported runtime-installer mode: ${DAEMONLESS_EXEC_MODE} (expected pod or host)" >&2
		exit 1
		;;
	esac
}

installer::policy:source() {
	local policy_lib_dir

	case "${DAEMONLESS_EXEC_MODE}" in
	pod)
		policy_lib_dir="${SCRIPT_POLICY_LIB_DIR}"
		;;
	host)
		policy_lib_dir="${DAEMONLESS_HOST_SCRIPT_LIB_DIR}"
		;;
	esac

	# shellcheck disable=SC1091
	source "${policy_lib_dir}/daemonset-logging.sh"
	# shellcheck disable=SC1091
	source "${policy_lib_dir}/daemonless-host-shell-policy.sh"
	installer::logging:setup
	# shellcheck disable=SC1091
	source "${policy_lib_dir}/daemonless-trampoline.sh"
	# shellcheck disable=SC1091
	source "${policy_lib_dir}/daemonless-host-asset-materializer.sh"
}

installer::logging:setup() {
	local script_path script_log_dir

	case "${DAEMONLESS_EXEC_MODE}" in
	pod)
		script_path="${HOST_SCRIPT_ROOT}/bin/runtime-installer.sh"
		DAEMONLESS_HOST_SCRIPT_ROOT="${HOST_SCRIPT_ROOT}"
		DAEMONLESS_HOST_SCRIPT_BIN="${DAEMONLESS_HOST_SCRIPT_ROOT%/}/bin"
		DAEMONLESS_HOST_SCRIPT_LIB_DIR="${DAEMONLESS_HOST_SCRIPT_ROOT%/}/.sh.d"
		DAEMONSET_SCRIPT_LOG_DIR="${HOST_SCRIPT_ROOT%/}/log"
		script_log_dir="$(daemonless::host_shell:log:resolve)"
		;;
	host)
		script_path="${DAEMONSET_SCRIPT_ROOT}/bin/runtime-installer.sh"
		DAEMONLESS_HOST_SCRIPT_ROOT="${DAEMONSET_SCRIPT_ROOT}"
		DAEMONLESS_HOST_SCRIPT_BIN="${DAEMONLESS_HOST_SCRIPT_ROOT%/}/bin"
		DAEMONLESS_HOST_SCRIPT_LIB_DIR="${DAEMONLESS_HOST_SCRIPT_ROOT%/}/.sh.d"
		script_log_dir="$(daemonless::host_shell:log:resolve)"
		;;
	esac

	DAEMONSET_SCRIPT_LOG_DIR="${script_log_dir}" \
		daemonset::logging:stderr:setup "${script_path}"
}

install_deps() {
	local attempt=0
	local max_attempts=${APK_MAX_RETRIES:-5}
	while true; do
		attempt=$((attempt + 1))
		if apk update && apk add --no-cache jq util-linux >/tmp/apk.log; then
			return 0
		fi
		if [[ ${attempt} -ge ${max_attempts} ]]; then
			echo "apk install failed after ${attempt} attempts" >&2
			sleep infinity
		fi
		sleep $((attempt * 2))
	done
}

: "Materialize bundled flox build resources onto host filesystem"
HOST_ROOT="${HOST_ROOT:-/host-root}"
SCRIPT_MOUNT_DIR="${SCRIPT_MOUNT_DIR:-/scripts}"
SCRIPT_POLICY_ROOT="${SCRIPT_POLICY_ROOT:-/runtime-daemonset}"
SCRIPT_POLICY_LIB_DIR="${SCRIPT_POLICY_LIB_DIR:-${SCRIPT_POLICY_ROOT%/}/.sh.d}"
BUILD_ASSETS_DIR="${BUILD_ASSETS_DIR:-/build-assets}"
HOST_SCRIPT_ROOT="${HOST_ROOT}${DAEMONSET_SCRIPT_ROOT}"

runtime::assets:root:resolve() {
	local resolved_root

	resolved_root="${DAEMONLESS_HOST_SCRIPT_ROOT:-${DAEMONSET_ASSET_ROOT}}"
	[[ -n "${resolved_root}" ]] || {
		echo "flox runtime asset root is not defined" >&2
		exit 1
	}

	printf '%s\n' "${resolved_root}"
}

installer::pod:materialize_assets() {
	local policy_shell_root policy_shell_bin policy_shell_lib_dir policy_shell_log_dir

	policy_shell_root="${HOST_SCRIPT_ROOT}"
	policy_shell_bin="${HOST_SCRIPT_ROOT%/}/bin"
	policy_shell_lib_dir="${HOST_SCRIPT_ROOT%/}/.sh.d"
	policy_shell_log_dir="${HOST_SCRIPT_ROOT%/}/log"

	# `BUILD_ASSETS_DIR` is the runtime-installer ConfigMap mounted by Kubernetes into this init
	# container. Read archive payloads from that mount directly; only materialize extracted runtime
	# content onto the host asset root.
	# Canonical pattern: host-reexec-capable shell entrypoints go through
	# daemonless::host_shell:binary:install, while sourced shell helper files go through
	# daemonless::host_shell:library:install into <asset-root>/.sh.d.

	DAEMONLESS_HOST_SCRIPT_ROOT="${policy_shell_root}" \
		DAEMONLESS_HOST_SCRIPT_BIN="${policy_shell_bin}" \
		DAEMONLESS_HOST_SCRIPT_LIB_DIR="${policy_shell_lib_dir}" \
		DAEMONSET_SCRIPT_LOG_DIR="${policy_shell_log_dir}" \
		daemonless::host_shell:layout:ensure "${HOST_SCRIPT_ROOT}"

	DAEMONLESS_HOST_SCRIPT_ROOT="${policy_shell_root}" \
		DAEMONLESS_HOST_SCRIPT_BIN="${policy_shell_bin}" \
		DAEMONLESS_HOST_SCRIPT_LIB_DIR="${policy_shell_lib_dir}" \
		DAEMONSET_SCRIPT_LOG_DIR="${policy_shell_log_dir}" \
		daemonless::host_shell:executable:install \
		"${SCRIPT_MOUNT_DIR}/bin/runtime-installer.sh" \
		"${HOST_SCRIPT_ROOT}" \
		"runtime-installer.sh" >/dev/null
	DAEMONLESS_HOST_SCRIPT_ROOT="${policy_shell_root}" \
		DAEMONLESS_HOST_SCRIPT_BIN="${policy_shell_bin}" \
		DAEMONLESS_HOST_SCRIPT_LIB_DIR="${policy_shell_lib_dir}" \
		DAEMONSET_SCRIPT_LOG_DIR="${policy_shell_log_dir}" \
		daemonless::host_shell:library:install \
		"${SCRIPT_POLICY_LIB_DIR}/daemonset-logging.sh" \
		"${HOST_SCRIPT_ROOT}" \
		"daemonset-logging.sh" >/dev/null
	DAEMONLESS_HOST_SCRIPT_ROOT="${policy_shell_root}" \
		DAEMONLESS_HOST_SCRIPT_BIN="${policy_shell_bin}" \
		DAEMONLESS_HOST_SCRIPT_LIB_DIR="${policy_shell_lib_dir}" \
		DAEMONSET_SCRIPT_LOG_DIR="${policy_shell_log_dir}" \
		daemonless::host_shell:library:install \
		"${SCRIPT_POLICY_LIB_DIR}/daemonless-host-asset-materializer.sh" \
		"${HOST_SCRIPT_ROOT}" \
		"daemonless-host-asset-materializer.sh" >/dev/null
	DAEMONLESS_HOST_SCRIPT_ROOT="${policy_shell_root}" \
		DAEMONLESS_HOST_SCRIPT_BIN="${policy_shell_bin}" \
		DAEMONLESS_HOST_SCRIPT_LIB_DIR="${policy_shell_lib_dir}" \
		DAEMONSET_SCRIPT_LOG_DIR="${policy_shell_log_dir}" \
		daemonless::host_shell:library:install \
		"${SCRIPT_POLICY_LIB_DIR}/daemonless-trampoline.sh" \
		"${HOST_SCRIPT_ROOT}" \
		"daemonless-trampoline.sh" >/dev/null
	DAEMONLESS_HOST_SCRIPT_ROOT="${policy_shell_root}" \
		DAEMONLESS_HOST_SCRIPT_BIN="${policy_shell_bin}" \
		DAEMONLESS_HOST_SCRIPT_LIB_DIR="${policy_shell_lib_dir}" \
		DAEMONSET_SCRIPT_LOG_DIR="${policy_shell_log_dir}" \
		daemonless::host_shell:library:install \
		"${SCRIPT_POLICY_LIB_DIR}/daemonless-host-shell-policy.sh" \
		"${HOST_SCRIPT_ROOT}" \
		"daemonless-host-shell-policy.sh" >/dev/null

	DAEMONLESS_HOST_SCRIPT_ROOT="${policy_shell_root}" \
		DAEMONLESS_HOST_SCRIPT_BIN="${policy_shell_bin}" \
		DAEMONLESS_HOST_SCRIPT_LIB_DIR="${policy_shell_lib_dir}" \
		DAEMONSET_SCRIPT_LOG_DIR="${policy_shell_log_dir}" \
	install -D -m 0644 "${BUILD_ASSETS_DIR}/flake.nix" "${HOST_SCRIPT_ROOT}/flake.nix"
	DAEMONLESS_HOST_SCRIPT_ROOT="${policy_shell_root}" \
		DAEMONLESS_HOST_SCRIPT_BIN="${policy_shell_bin}" \
		DAEMONLESS_HOST_SCRIPT_LIB_DIR="${policy_shell_lib_dir}" \
		DAEMONSET_SCRIPT_LOG_DIR="${policy_shell_log_dir}" \
		daemonless::host_shell:library:install \
		"${BUILD_ASSETS_DIR}/debug-tools/.sh.d/rke2lab-debug-tooling.sh" \
		"${HOST_SCRIPT_ROOT}/debug-tools" \
		"rke2lab-debug-tooling.sh" >/dev/null
	install -D -m 0755 "${BUILD_ASSETS_DIR}/debug-tools/attach_live_flox_runtime_strace.sh" "${HOST_SCRIPT_ROOT}/debug-tools/attach_live_flox_runtime_strace.sh"
	install -D -m 0755 "${BUILD_ASSETS_DIR}/debug-tools/crictl-kdns-repro.sh" "${HOST_SCRIPT_ROOT}/debug-tools/crictl-kdns-repro.sh"
	install -D -m 0755 "${BUILD_ASSETS_DIR}/debug-tools/kdns-containerd-bundle-watch.sh" "${HOST_SCRIPT_ROOT}/debug-tools/kdns-containerd-bundle-watch.sh"
	install -D -m 0755 "${BUILD_ASSETS_DIR}/debug-tools/kdns-containerd-remote-capture.sh" "${HOST_SCRIPT_ROOT}/debug-tools/kdns-containerd-remote-capture.sh"
	install -D -m 0755 "${BUILD_ASSETS_DIR}/debug-tools/master-runtime-pprof.sh" "${HOST_SCRIPT_ROOT}/debug-tools/master-runtime-pprof.sh"
	install -D -m 0755 "${BUILD_ASSETS_DIR}/debug-tools/rke2lab-dlv.sh" "${HOST_SCRIPT_ROOT}/debug-tools/rke2lab-dlv.sh"
	install -D -m 0755 "${BUILD_ASSETS_DIR}/debug-tools/rke2lab-runtime-dlv.sh" "${HOST_SCRIPT_ROOT}/debug-tools/rke2lab-runtime-dlv.sh"
	install -D -m 0644 "${BUILD_ASSETS_DIR}/mesh/headplane/flake.nix" "${HOST_SCRIPT_ROOT}/mesh/headplane/flake.nix"
	install -D -m 0644 "${BUILD_ASSETS_DIR}/networking/kdns/flake.nix" "${HOST_SCRIPT_ROOT}/networking/kdns/flake.nix"
	# Install kdns flox environment files
	install -D -m 0644 "${BUILD_ASSETS_DIR}/networking/kdns/.flox/env.json" "${HOST_SCRIPT_ROOT}/networking/kdns/.flox/env.json"
	install -D -m 0644 "${BUILD_ASSETS_DIR}/networking/kdns/.flox/env.lock" "${HOST_SCRIPT_ROOT}/networking/kdns/.flox/env.lock"
	install -D -m 0644 "${BUILD_ASSETS_DIR}/networking/kdns/.flox/env/manifest.toml" "${HOST_SCRIPT_ROOT}/networking/kdns/.flox/env/manifest.toml"
	install -D -m 0644 "${BUILD_ASSETS_DIR}/networking/kdns/.flox/env/manifest.lock" "${HOST_SCRIPT_ROOT}/networking/kdns/.flox/env/manifest.lock"
	# Install mesh/headplane flox environment files
	install -D -m 0644 "${BUILD_ASSETS_DIR}/mesh/headplane/.flox/env.json" "${HOST_SCRIPT_ROOT}/mesh/headplane/.flox/env.json"
	install -D -m 0644 "${BUILD_ASSETS_DIR}/mesh/headplane/.flox/env.lock" "${HOST_SCRIPT_ROOT}/mesh/headplane/.flox/env.lock"
	install -D -m 0644 "${BUILD_ASSETS_DIR}/mesh/headplane/.flox/env/manifest.toml" "${HOST_SCRIPT_ROOT}/mesh/headplane/.flox/env/manifest.toml"
	install -D -m 0644 "${BUILD_ASSETS_DIR}/mesh/headplane/.flox/env/manifest.lock" "${HOST_SCRIPT_ROOT}/mesh/headplane/.flox/env/manifest.lock"
}

installer::pod:run() {
	install_deps
	installer::policy:source
	installer::pod:materialize_assets

	daemonless::host_asset:materialize_encoded_tar \
		"${BUILD_ASSETS_DIR}/wrapper-go.tar.b64" \
		"${BUILD_ASSETS_DIR}/wrapper-go.manifest.json" \
		"${HOST_SCRIPT_ROOT}" \
		"wrapper-go"

	DAEMONLESS_HOST_SCRIPT_ROOT="${DAEMONSET_SCRIPT_ROOT}" \
		DAEMONLESS_HOST_SCRIPT_BIN="${DAEMONSET_SCRIPT_ROOT%/}/bin" \
		DAEMONLESS_HOST_SCRIPT_LIB_DIR="${DAEMONSET_SCRIPT_ROOT%/}/.sh.d" \
		daemonless::trampoline:exec_on_host \
		"runtime-installer.sh" \
		"CONTAINERD_CONFIG_FILE=${CONTAINERD_CONFIG_FILE}" \
		"DAEMONSET_SCRIPT_ROOT=${DAEMONSET_SCRIPT_ROOT}"
}

installer::host:activate_flox() {
	# shellcheck disable=SC1090
	source <(flox activate --dir /var/lib/rancher/rke2)
}

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
	# declare -F rke2lab::env:load >/dev/null 2>&1 && rke2lab::env:load
}

host::tooling:init() {
	: "Ensure Nix is available in the host environment for runtime installer operations"
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

runtime::assets:path:init() {
	FLOX_RUNTIME_ROOT="$(runtime::assets:root:resolve)"
	FLOX_RUNTIME_BIN_DIR="${FLOX_RUNTIME_ROOT}/bin"
	FLOX_RUNTIME_ETC_DIR="${FLOX_RUNTIME_ROOT}/etc"
	FLOX_RUNTIME_LOG_DIR="${FLOX_RUNTIME_ROOT}/log"
	FLOX_RUNTIME_PACKAGE_FLAKE="${FLOX_RUNTIME_ROOT}/flake.nix"
	FLOX_RUNTIME_MESH_DIR="${FLOX_RUNTIME_ROOT}/mesh"
	FLOX_RUNTIME_NETWORKING_DIR="${FLOX_RUNTIME_ROOT}/networking"
	FLOX_RUNTIME_DEBUG_TOOLS_DIR="${FLOX_RUNTIME_ROOT}/debug-tools"
	RKE2LAB_DEBUG_SHARE_ROOT="${RKE2LAB_DEBUG_SHARE_ROOT:-/srv/host/rke2lab-share.d}"
}

runtime::assets:path:validate() {
	[[ -d "${FLOX_RUNTIME_BIN_DIR}" ]] || {
		echo "flox runtime bin directory missing: ${FLOX_RUNTIME_BIN_DIR}" >&2
		exit 1
	}
	[[ -d "${FLOX_RUNTIME_ETC_DIR}" ]] || {
		echo "flox runtime etc directory missing: ${FLOX_RUNTIME_ETC_DIR}" >&2
		exit 1
	}
	[[ -d "${FLOX_RUNTIME_LOG_DIR}" ]] || {
	}
		exit 1
	}
	[[ -r "${FLOX_RUNTIME_PACKAGE_FLAKE}" ]] || {
		echo "flox runtime package flake missing or unreadable: ${FLOX_RUNTIME_PACKAGE_FLAKE}" >&2
		exit 1
	}
		exit 1
	}
	[[ -d "${FLOX_RUNTIME_NETWORKING_DIR}" ]] || {
		echo "flox runtime networking directory missing: ${FLOX_RUNTIME_NETWORKING_DIR}" >&2
		exit 1
	}
	[[ -d "${FLOX_RUNTIME_DEBUG_TOOLS_DIR}" ]] || {
		echo "flox runtime debug tools directory missing: ${FLOX_RUNTIME_DEBUG_TOOLS_DIR}" >&2
		exit 1
	}
}

runtime::debug:any_enabled() {
	rke2lab::bool:is_true "${RKE2LAB_POLICY_DEBUG_KDNS_ENABLED:-false}" ||
		rke2lab::bool:is_true "${RKE2LAB_POLICY_DEBUG_FLOX_RUNTIME_WRAPPER_ENABLED:-false}"
}

runtime::debug:dlv:ensure() {
	if command -v dlv >/dev/null 2>&1; then
		return 0
	fi
	local rke2_env_dir="/var/lib/rancher/rke2"
	[[ -d "${rke2_env_dir}/.flox" ]] || {
		echo "dlv install skipped: ${rke2_env_dir}/.flox not initialized" >&2
		return 0
	}
	flox install --dir="${rke2_env_dir}" delve
}

runtime::debug:tools:install() {
	local source_root target_root source_path relative target_path install_mode force_install

	if ! runtime::debug:any_enabled; then
		echo "debug helper installation skipped: no debug policy enabled"
		return 0
	fi

	runtime::debug:dlv:ensure

	source_root="${FLOX_RUNTIME_DEBUG_TOOLS_DIR}"
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

		# Always install debug tools from ConfigMap to ensure updates are applied.
		# The ConfigMap is the source of truth for debug tooling controlled by the
		# manifests codebase.
		install -m "${install_mode}" "${source_path}" "${target_path}"
	done < <(find "${source_root}" -type f -print0 | sort -z)

	if [[ -x "${target_root%/}/rke2lab-runtime-dlv.sh" ]]; then
		install -d /usr/local/bin
		ln -sfn "${target_root%/}/rke2lab-runtime-dlv.sh" /usr/local/bin/rke2lab-runtime-dlv
	fi

	echo "installed debug helper scripts in ${target_root}"
}




runtime::runtime:nix-system:resolve() {
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

runtime::runtime:wrapper-package:build() {
	local package_name="$1"
	local nix_system package_attr

	nix_system="$(runtime::runtime:nix-system:resolve)"
	package_attr="packages.${nix_system}.${package_name}"

	(
		nix build \
			--no-link \
			--print-out-paths \
			"${FLOX_RUNTIME_ROOT}#${package_attr}"
	)
}

runtime::runtime:containerd:resolve-bin() {
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


# The shim variants `flox/flox-runtime-{17,2x}` are not directly
# resolvable via `flox install`; the canonical entrypoint published on FloxHub
# is `flox/flox-runtime-installer`, whose closure carries both shim
# variants. Pull that env, then read the shim path out of its requisites.

# Find the flox-runtime-${variant}-* store path inside the installer
# env's closure. The upstream installer hardcodes these paths; here we let
# nix-store discover them so version bumps land via `flox pull`.



runtime::runtime:config-template:ensure() {
	if [[ ! -f "${CONTAINERD_CONFIG_TEMPLATE}" ]]; then
		cp "${CONTAINERD_CONFIG_FILE}" "${CONTAINERD_CONFIG_TEMPLATE}"
	fi
}

nri::plugin:binary:install() {
	local nri_plugin_pkg_path nri_plugin_bin install_path

	nri_plugin_pkg_path="$(runtime::runtime:wrapper-package:build flox-nri-plugin)"
	nri_plugin_bin="${nri_plugin_pkg_path}/bin/flox-nri-plugin"

	[[ -x "${nri_plugin_bin}" ]] || {
		echo "NRI plugin binary missing at ${nri_plugin_bin}" >&2
		return 1
	}

	install_path="/opt/nri/plugins/10-flox"
	install -D -m 0755 "${nri_plugin_bin}" "${install_path}"

	# GC-root the NRI plugin closure
	nix-store --add-root \
		"${NIX_GC_ROOTS:-/nix/var/nix/gcroots}/flox-runtime/flox-nri-plugin" \
		--indirect -r "${nri_plugin_pkg_path}" >/dev/null
}

runtime::runtime:core:install() {
	: "Install/refresh flox runtime binaries on host"
	local flox_env_dir arch containerd_bin variant

	flox_env_dir="/var/lib/flox-runtime"
	arch="$(uname -m)"

	# NRI plugin approach: no longer install custom shim binaries
	# The NRI plugin handles Flox environment injection instead
	# runtime::runtime:env:ensure "${flox_env_dir}"
	# runtime::runtime:gcroots:ensure
	# containerd_bin="$(runtime::runtime:containerd:resolve-bin)"
	# variant="$(runtime::runtime:variant:resolve "${containerd_bin}")"
	# runtime::runtime:binary:install "${flox_env_dir}" "${arch}" "${variant}"
	runtime::runtime:config-template:ensure
	nri::plugin:binary:install
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
	local version plugin_root nri_plugin_root tmp
	version="$(containerd::config:version:detect)"
	if [[ "${version}" == "3" ]]; then
		plugin_root="io.containerd.cri.v1.runtime"
		nri_plugin_root="io.containerd.nri.v1.nri"
	else
		plugin_root="io.containerd.grpc.v1.cri"
		nri_plugin_root="io.containerd.nri.v1.nri"
	fi
	tmp="$(mktemp)" &&
		trap "rm -f ${tmp}" RETURN

	"${DASEL_BIN}" -i toml -o yaml <"${CONTAINERD_CONFIG_TEMPLATE}" |
		CRI_PLUGIN_ROOT="${plugin_root}" NRI_PLUGIN_ROOT="${nri_plugin_root}" "${YQ_BIN}" '
      del(.plugins."io.containerd.cri.v1.runtime".containerd.runtimes.flox) |
	  del(.plugins."io.containerd.cri.v1.runtime".containerd.runtimes."flox-delve") |
      del(.plugins."io.containerd.grpc.v1.cri".containerd.runtimes.flox) |
	  del(.plugins."io.containerd.grpc.v1.cri".containerd.runtimes."flox-delve") |
      .plugins[env(CRI_PLUGIN_ROOT)].containerd.systemd_cgroup = true |
      .plugins[env(NRI_PLUGIN_ROOT)].disable = false |
      .plugins[env(NRI_PLUGIN_ROOT)].plugin_config_path = "/etc/nri/conf.d" |
      .plugins[env(NRI_PLUGIN_ROOT)].plugin_path = "/opt/nri/plugins"
    ' |
		"${DASEL_BIN}" -i yaml -o toml >"${tmp}" &&
		mv "${tmp}" "${CONTAINERD_CONFIG_TEMPLATE}"
}

installer::host:run() {
	installer::policy:source
	installer::host:activate_flox

	: "Initialize host tooling and runtime asset paths"
	host::tooling:init
	host::tooling:config-tools:resolve
	host::nix:flox-conf:ensure
	runtime::assets:path:init
	runtime::assets:path:validate
	rke2lab::env:load

	: "Initialize resolved containerd config paths"
	containerd::config:path:init

	# NRI plugin approach: no longer need to pre-build or sync store-paths
	# Flox will build packages on-demand during 'flox activate' based on flake references
	# : "Execute the shim build before mutating containerd config"
	# runtime::assets:build:run
	# : "Synchronize flox environment store-paths with actual built packages and push to FloxHub"
	# flox::env:sync:run

	: "Install/update flox runtime binaries on host"
	runtime::runtime:core:install

	: "Install repository-owned runtime debug helpers into the shared directory when debug policy is enabled"
	runtime::debug:tools:install

	: "Update containerd configuration to include the flox runtime"
	containerd::config:flox:update

	: "Restart containerd to apply runtime installation changes"
	container::service:runtime:restart
}

installer::mode:validate

if [[ "${DAEMONLESS_EXEC_MODE}" == "pod" ]]; then
	installer::pod:run
	return 0 2>/dev/null || exit 0
fi

installer::host:run
