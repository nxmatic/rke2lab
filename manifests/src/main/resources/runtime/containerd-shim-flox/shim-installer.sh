#!/usr/bin/env bash
set -exuo pipefail

DAEMONSET_ASSET_ROOT="/srv/host/k8s-daemonset.d/runtime/containerd-shim-flox"
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
		echo "unsupported shim-installer mode: ${DAEMONLESS_EXEC_MODE} (expected pod or host)" >&2
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
	installer::logging:setup
	# shellcheck disable=SC1091
	source "${policy_lib_dir}/daemonless-trampoline.sh"
	# shellcheck disable=SC1091
	source "${policy_lib_dir}/daemonless-host-asset-materializer.sh"
	# shellcheck disable=SC1091
	source "${policy_lib_dir}/daemonless-host-shell-policy.sh"
}

installer::logging:setup() {
	local script_path script_log_dir

	case "${DAEMONLESS_EXEC_MODE}" in
	pod)
		script_path="${HOST_SCRIPT_ROOT}/bin/shim-installer.sh"
		DAEMONLESS_HOST_SCRIPT_ROOT="${HOST_SCRIPT_ROOT}"
		script_log_dir="$(daemonless::host_shell:log:resolve)"
		;;
	host)
		script_path="${DAEMONSET_SCRIPT_ROOT}/bin/shim-installer.sh"
		DAEMONLESS_HOST_SCRIPT_ROOT="${DAEMONSET_SCRIPT_ROOT}"
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

shim::assets:root:resolve() {
	local resolved_root

	resolved_root="${DAEMONLESS_HOST_SCRIPT_ROOT:-${DAEMONSET_ASSET_ROOT}}"
	[[ -n "${resolved_root}" ]] || {
		echo "flox shim asset root is not defined" >&2
		exit 1
	}

	printf '%s\n' "${resolved_root}"
}

installer::pod:materialize_assets() {
	# `BUILD_ASSETS_DIR` is the runtime-installer ConfigMap mounted by Kubernetes into this init
	# container. Read archive payloads from that mount directly; only materialize extracted runtime
	# content onto the host asset root.
	# Canonical pattern: host-reexec-capable shell entrypoints go through
	# daemonless::host_shell:binary:install, while sourced shell helper files go through
	# daemonless::host_shell:library:install into <asset-root>/.sh.d.

	daemonless::host_shell:layout:ensure "${HOST_SCRIPT_ROOT}"

	daemonless::host_shell:executable:install \
		"${SCRIPT_MOUNT_DIR}/bin/shim-installer.sh" \
		"${HOST_SCRIPT_ROOT}" \
		"shim-installer.sh" >/dev/null
	daemonless::host_shell:library:install \
		"${SCRIPT_POLICY_LIB_DIR}/daemonset-logging.sh" \
		"${HOST_SCRIPT_ROOT}" \
		"daemonset-logging.sh" >/dev/null
	daemonless::host_shell:library:install \
		"${SCRIPT_POLICY_LIB_DIR}/daemonless-host-asset-materializer.sh" \
		"${HOST_SCRIPT_ROOT}" \
		"daemonless-host-asset-materializer.sh" >/dev/null
	daemonless::host_shell:library:install \
		"${SCRIPT_POLICY_LIB_DIR}/daemonless-trampoline.sh" \
		"${HOST_SCRIPT_ROOT}" \
		"daemonless-trampoline.sh" >/dev/null
	daemonless::host_shell:library:install \
		"${SCRIPT_POLICY_LIB_DIR}/daemonless-host-shell-policy.sh" \
		"${HOST_SCRIPT_ROOT}" \
		"daemonless-host-shell-policy.sh" >/dev/null

	daemonless::host_shell:executable:install \
		"${BUILD_ASSETS_DIR}/bin/shim-build.sh" \
		"${HOST_SCRIPT_ROOT}" \
		"shim-build.sh" >/dev/null
	daemonless::host_shell:config:install \
		"${BUILD_ASSETS_DIR}/shim-build.yaml" \
		"${HOST_SCRIPT_ROOT}" \
		"shim-build.yaml" >/dev/null
	install -D -m 0644 "${BUILD_ASSETS_DIR}/flake.nix" "${HOST_SCRIPT_ROOT}/flake.nix"
	daemonless::host_shell:executable:install \
		"${BUILD_ASSETS_DIR}/bin/flox-rootfs-sync.sh" \
		"${HOST_SCRIPT_ROOT}" \
		"flox-rootfs-sync.sh" >/dev/null
	daemonless::host_shell:library:install \
		"${BUILD_ASSETS_DIR}/debug-tools/.sh.d/rke2lab-debug-tooling.sh" \
		"${HOST_SCRIPT_ROOT}/debug-tools" \
		"rke2lab-debug-tooling.sh" >/dev/null
	install -D -m 0755 "${BUILD_ASSETS_DIR}/debug-tools/attach_live_flox_shim_strace.sh" "${HOST_SCRIPT_ROOT}/debug-tools/attach_live_flox_shim_strace.sh"
	install -D -m 0755 "${BUILD_ASSETS_DIR}/debug-tools/crictl-kdns-repro.sh" "${HOST_SCRIPT_ROOT}/debug-tools/crictl-kdns-repro.sh"
	install -D -m 0755 "${BUILD_ASSETS_DIR}/debug-tools/kdns-containerd-bundle-watch.sh" "${HOST_SCRIPT_ROOT}/debug-tools/kdns-containerd-bundle-watch.sh"
	install -D -m 0755 "${BUILD_ASSETS_DIR}/debug-tools/kdns-containerd-remote-capture.sh" "${HOST_SCRIPT_ROOT}/debug-tools/kdns-containerd-remote-capture.sh"
	install -D -m 0755 "${BUILD_ASSETS_DIR}/debug-tools/master-shim-pprof.sh" "${HOST_SCRIPT_ROOT}/debug-tools/master-shim-pprof.sh"
	install -D -m 0644 "${BUILD_ASSETS_DIR}/mesh/headplane/flake.nix" "${HOST_SCRIPT_ROOT}/mesh/headplane/flake.nix"
	install -D -m 0644 "${BUILD_ASSETS_DIR}/networking/kdns/flake.nix" "${HOST_SCRIPT_ROOT}/networking/kdns/flake.nix"
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

	daemonless::trampoline:exec_on_host \
		"shim-installer.sh" \
		"CONTAINERD_CONFIG_FILE=${CONTAINERD_CONFIG_FILE}" \
		"DAEMONLESS_HOST_SCRIPT_LIB_DIR=${DAEMONLESS_HOST_SCRIPT_LIB_DIR}" \
		"DAEMONLESS_HOST_SCRIPT_BIN=${DAEMONSET_SCRIPT_ROOT%/}/bin" \
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
	FLOX_SHIM_ROOT="$(shim::assets:root:resolve)"
	FLOX_SHIM_BIN_DIR="${FLOX_SHIM_ROOT}/bin"
	FLOX_SHIM_ETC_DIR="${FLOX_SHIM_ROOT}/etc"
	FLOX_SHIM_LOG_DIR="${FLOX_SHIM_ROOT}/log"
	FLOX_BUILD_SCRIPT="${FLOX_SHIM_BIN_DIR}/shim-build.sh"
	FLOX_BUILD_ENTRYPOINT="${FLOX_SHIM_BIN_DIR}/shim-build.sh"
	FLOX_BUILD_DESCRIPTOR="${FLOX_SHIM_ETC_DIR}/shim-build.yaml"
	FLOX_SHIM_PACKAGE_FLAKE="${FLOX_SHIM_ROOT}/flake.nix"
	FLOX_ROOTFS_SYNC_SCRIPT="${FLOX_SHIM_BIN_DIR}/flox-rootfs-sync.sh"
	FLOX_SHIM_MESH_DIR="${FLOX_SHIM_ROOT}/mesh"
	FLOX_SHIM_NETWORKING_DIR="${FLOX_SHIM_ROOT}/networking"
	FLOX_SHIM_DEBUG_TOOLS_DIR="${FLOX_SHIM_ROOT}/debug-tools"
	RKE2LAB_DEBUG_SHARE_ROOT="${RKE2LAB_DEBUG_SHARE_ROOT:-/srv/host/rke2lab-share.d}"
}

shim::assets:path:validate() {
	[[ -d "${FLOX_SHIM_BIN_DIR}" ]] || {
		echo "flox shim bin directory missing: ${FLOX_SHIM_BIN_DIR}" >&2
		exit 1
	}
	[[ -d "${FLOX_SHIM_ETC_DIR}" ]] || {
		echo "flox shim etc directory missing: ${FLOX_SHIM_ETC_DIR}" >&2
		exit 1
	}
	[[ -d "${FLOX_SHIM_LOG_DIR}" ]] || {
		echo "flox shim log directory missing: ${FLOX_SHIM_LOG_DIR}" >&2
		exit 1
	}
	[[ -x "${FLOX_BUILD_ENTRYPOINT}" ]] || {
		echo "flox build entrypoint missing or not executable: ${FLOX_BUILD_ENTRYPOINT}" >&2
		exit 1
	}
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
	DAEMONLESS_EXEC_MODE=host \
		DAEMONLESS_HOST_SCRIPT_ROOT="${FLOX_SHIM_ROOT}" \
		DAEMONLESS_HOST_SCRIPT_BIN="${FLOX_SHIM_BIN_DIR}" \
		DAEMONSET_SCRIPT_LOG_DIR="${FLOX_SHIM_LOG_DIR}" \
		PATH="${FLOX_SHIM_BIN_DIR}:${PATH}" \
		"${FLOX_BUILD_ENTRYPOINT}" "${FLOX_BUILD_DESCRIPTOR}"

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
		nix build \
			--no-link \
			--print-out-paths \
			"${FLOX_SHIM_ROOT}#${package_attr}"
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
	local gcroots_dir

	gcroots_dir="${NIX_GC_ROOTS:-/nix/var/nix/gcroots}/flox"
	mkdir -p "${gcroots_dir}"
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

installer::host:run() {
	installer::policy:source
	installer::host:activate_flox

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
}

installer::mode:validate

if [[ "${DAEMONLESS_EXEC_MODE}" == "pod" ]]; then
	installer::pod:run
	return 0 2>/dev/null || exit 0
fi

installer::host:run
