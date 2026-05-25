#!/usr/bin/env bash
set -exuo pipefail

DAEMONSET_ASSET_ROOT="/srv/host/k8s-daemonset.d/runtime/flox"
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
		script_path="${HOST_SCRIPT_ROOT}/bin/flox-k8s-runtime-installer.sh"
		DAEMONLESS_HOST_SCRIPT_ROOT="${HOST_SCRIPT_ROOT}"
		DAEMONLESS_HOST_SCRIPT_BIN="${DAEMONLESS_HOST_SCRIPT_ROOT%/}/bin"
		DAEMONLESS_HOST_SCRIPT_LIB_DIR="${DAEMONLESS_HOST_SCRIPT_ROOT%/}/.sh.d"
		# shellcheck disable=SC2034  # Passed as env var to daemonless functions below
		DAEMONSET_SCRIPT_LOG_DIR="${HOST_SCRIPT_ROOT%/}/log"
		script_log_dir="$(daemonless::host_shell:log:resolve)"
		;;
	host)
		script_path="${DAEMONSET_SCRIPT_ROOT}/bin/flox-k8s-runtime-installer.sh"
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
SCRIPT_POLICY_ROOT="${SCRIPT_POLICY_ROOT:-/.sh-daemonset}"
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
	# Build-derived inputs are already in the per-node workspace at
	# ${SCRIPT_MOUNT_DIR} (the init container `cp -an`'d /.sh/. there before
	# running this script). We only materialize the things that genuinely
	# live elsewhere:
	#   1. Script-policy library (daemonset-logging.sh, daemonless-*.sh) —
	#      these ride a separate ConfigMap mounted at /.sh-daemonset, so
	#      they need to be copied into ${SCRIPT_MOUNT_DIR}/.sh.d/.
	#   2. OCI prestart hooks — runc looks them up in /usr/local/sbin on the
	#      host, not under our daemonset asset root.
	#
	# IMPORTANT: write to ${SCRIPT_MOUNT_DIR} (the workspace mount in this
	# pod), NOT to ${HOST_SCRIPT_ROOT} (which routes through /host-root/).
	# /host-root is a hostPath bind of '/', but the host's /var/run is a
	# separate tmpfs mounted *inside* /, and that tmpfs does NOT propagate
	# through the hostPath bind. Writing under /host-root/var/run/... lands
	# in the container's own overlay, invisible to the host. The workspace
	# mount, on the other hand, is the host's /var/run/k8s-daemonset.d/...
	# directly, so writes here actually land on the host filesystem.
	local policy_shell_root policy_shell_bin policy_shell_lib_dir policy_shell_log_dir

	policy_shell_root="${SCRIPT_MOUNT_DIR}"
	policy_shell_bin="${SCRIPT_MOUNT_DIR%/}/bin"
	policy_shell_lib_dir="${SCRIPT_MOUNT_DIR%/}/.sh.d"
	policy_shell_log_dir="${SCRIPT_MOUNT_DIR%/}/log"

	DAEMONLESS_HOST_SCRIPT_ROOT="${policy_shell_root}" \
		DAEMONLESS_HOST_SCRIPT_BIN="${policy_shell_bin}" \
		DAEMONLESS_HOST_SCRIPT_LIB_DIR="${policy_shell_lib_dir}" \
		DAEMONSET_SCRIPT_LOG_DIR="${policy_shell_log_dir}" \
		daemonless::host_shell:layout:ensure "${SCRIPT_MOUNT_DIR}"

	# Script-policy library — separate ConfigMap mount, real cross-volume copy.
	for lib in daemonset-logging.sh daemonless-host-asset-materializer.sh \
		daemonless-trampoline.sh daemonless-host-shell-policy.sh; do
		DAEMONLESS_HOST_SCRIPT_ROOT="${policy_shell_root}" \
			DAEMONLESS_HOST_SCRIPT_BIN="${policy_shell_bin}" \
			DAEMONLESS_HOST_SCRIPT_LIB_DIR="${policy_shell_lib_dir}" \
			DAEMONSET_SCRIPT_LOG_DIR="${policy_shell_log_dir}" \
			daemonless::host_shell:library:install \
			"${SCRIPT_POLICY_LIB_DIR}/${lib}" \
			"${SCRIPT_MOUNT_DIR}" \
			"${lib}" >/dev/null
	done

	# OCI prestart hooks — runc looks them up in /usr/local/sbin on the host
	# filesystem (bind-mounted at ${HOST_ROOT} inside this pod), not under
	# our daemonset asset root.
	install -D -m 0755 "${SCRIPT_MOUNT_DIR}/bin/flox-nri-overlay-hook.sh" "${HOST_ROOT}/usr/local/sbin/flox-nri-overlay-hook.sh"
	install -D -m 0755 "${SCRIPT_MOUNT_DIR}/bin/flox-nri-chown-hook.sh" "${HOST_ROOT}/usr/local/sbin/flox-nri-chown-hook.sh"
}

installer::host:flox:prebuild_runtime_packages() {
	# Pre-build every workload package the parent runtime flake exposes for the
	# current system. Workloads live in the parent flake (kdns, kdns-debug,
	# headplane, headscale, …); per-env manifest.toml just references them by
	# absolute path. Doing one pre-build pass against the parent flake populates
	# /nix/store once and means each env's `flox activate` is a cache hit.
	#
	# Locks are *cluster state*, not build artifacts: the master writes flake.lock
	# next to the parent flake on first activation; peers reading from the same
	# host filesystem see the master's lock and skip resolution. We deliberately
	# omit `--no-write-lock-file` so that first run produces the lock; subsequent
	# runs are idempotent because nix detects the lock is current.
	local flake_dir="$1"
	local nix_system="$2"
	local pkg_names_json pkg_name

	# nix's path-flake fetcher refuses paths that traverse a symlink. On most
	# systemd hosts /var/run is a symlink to /run; passing the daemonset
	# workspace through that symlink hop trips nix with "path '//var/run' is a
	# symlink". Resolve the path to its real location before handing it to
	# `getFlake` / `nix build`.
	flake_dir="$(realpath "${flake_dir}")"

	# We do NOT pre-vendor the NRI plugin's Go modules here. The parent flake
	# pins `vendorHash`, which makes `buildGoModule` fetch dependencies in its
	# own fixed-output derivation (network allowed there, output narHash must
	# match). Committing vendor/ + setting vendorHash makes buildGoModule
	# refuse with "vendor folder exists, please set 'vendorHash = null;'".
	# `nri-plugin/.gitignore` keeps vendor/ out of the build tree.

	pkg_names_json="$(nix \
		--extra-experimental-features 'nix-command flakes' \
		--option pure-eval false \
		eval --json \
		--expr "builtins.attrNames (builtins.getFlake \"path:${flake_dir}\").packages.${nix_system}")" || {
		echo "  ✗ Failed to enumerate packages for ${flake_dir}" >&2
		return 1
	}

	while IFS= read -r pkg_name; do
		[[ -n "${pkg_name}" ]] || continue
		echo "  Pre-building ${pkg_name}..."
		nix \
			--extra-experimental-features 'nix-command flakes' \
			--option pure-eval false --print-build-logs \
			build --no-link \
			"path:${flake_dir}#packages.${nix_system}.${pkg_name}^*" || {
			echo "  ✗ Pre-build of ${pkg_name} failed" >&2
			return 1
		}
	done < <(printf '%s\n' "${pkg_names_json}" | jq -r '.[]')
}

installer::host:flox:activate_environments() {
	echo "=== Pre-activating flox environments (host mode) ==="
	echo "Workload packages live in the parent runtime flake; per-env manifest.toml"
	echo "references them by absolute path. The committed flake.lock and (when"
	echo "present) per-env manifest.lock keep every node activating from identical"
	echo "pinned state — no node-local re-locking."

	local env_root="${FLOX_RUNTIME_ROOT}/environment.d"
	local runtime_flake_dir="${FLOX_RUNTIME_ROOT}"
	local -a discovered_envs=()
	local category name category_dir name_dir env_path env_path_dir nix_system

	nix_system="$(runtime::runtime:nix-system:resolve)"

	if [[ ! -d "${env_root}" ]]; then
		echo "Warning: environment.d not present at ${env_root}; nothing to activate"
		return 0
	fi

	for category_dir in "${env_root}"/*; do
		[[ -d "${category_dir}" ]] || continue
		category="${category_dir##*/}"

		for name_dir in "${category_dir}"/*; do
			[[ -d "${name_dir}" ]] || continue
			[[ -d "${name_dir}/.flox" ]] || continue

			name="${name_dir##*/}"
			discovered_envs+=("${category}/${name}")
		done
	done

	if [[ ${#discovered_envs[@]} -eq 0 ]]; then
		echo "Warning: No flox environments found to activate"
		return 0
	fi

	echo "Pre-building parent runtime flake packages once..."
	installer::host:flox:prebuild_runtime_packages "${runtime_flake_dir}" "${nix_system}" || return 1

	for env_path in "${discovered_envs[@]}"; do
		env_path_dir="${env_root}/${env_path}"

		echo "Activating ${env_path}..."
		if (cd "${env_path_dir}" && flox -vvv activate -- echo "  ✓ ${env_path} environment resolved"); then
			echo "  ✓ ${env_path} activated and /nix/store populated"
		else
			echo "  ✗ Failed to activate ${env_path}" >&2
			return 1
		fi
	done

	echo "=== All ${#discovered_envs[@]} environments activated ==="
}

installer::pod:run() {
	install_deps
	installer::policy:source
	installer::pod:materialize_assets

	# nri-plugin source tree now ships as part of the build inputs (it's a
	# `src = ./nri-plugin` reference from the parent flake), so no archive
	# decode step is needed — `cp -af /.sh/. ${SCRIPT_MOUNT_DIR}/` already brought it into
	# the workspace next to flake.nix.

	DAEMONLESS_HOST_SCRIPT_ROOT="${DAEMONSET_SCRIPT_ROOT}" \
		DAEMONLESS_HOST_SCRIPT_BIN="${DAEMONSET_SCRIPT_ROOT%/}/bin" \
		DAEMONLESS_HOST_SCRIPT_LIB_DIR="${DAEMONSET_SCRIPT_ROOT%/}/.sh.d" \
		daemonless::trampoline:exec_on_host \
		"flox-k8s-runtime-installer.sh" \
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
	# Per-env trees live under environment.d/<category>/ now (was networking/ and
	# mesh/ as flat siblings of the asset root).
	FLOX_RUNTIME_NETWORKING_DIR="${FLOX_RUNTIME_ROOT}/environment.d/networking"
	# shellcheck disable=SC2034  # Used in future mesh integration
	FLOX_RUNTIME_MESH_DIR="${FLOX_RUNTIME_ROOT}/environment.d/mesh"
	# Debug tooling is part of the build inputs under build-assets/ so it
	# survives the workspace overwrite each pod start.
	FLOX_RUNTIME_DEBUG_TOOLS_DIR="${FLOX_RUNTIME_ROOT}/build-assets/debug-tools"
	FLOX_RUNTIME_OVERLAY_MOUNT_POINTS_DIR="${FLOX_RUNTIME_ROOT}/overlay-mount-points"
	RKE2LAB_DEBUG_SHARE_ROOT="${RKE2LAB_DEBUG_SHARE_ROOT:-/srv/host/rke2lab-share.d}"

	# Create base directory for overlay mount points
	# NRI plugin will create container-specific subdirectories (using container ID)
	# to avoid conflicts between concurrent containers
	mkdir -p "${FLOX_RUNTIME_OVERLAY_MOUNT_POINTS_DIR}"
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
		echo "flox runtime log directory missing: ${FLOX_RUNTIME_LOG_DIR}" >&2
		exit 1
	}
	[[ -r "${FLOX_RUNTIME_PACKAGE_FLAKE}" ]] || {
		echo "flox runtime package flake missing or unreadable: ${FLOX_RUNTIME_PACKAGE_FLAKE}" >&2
		exit 1
	}
	# Note: networking/ and mesh/ directories are created during flox environment installation
	# They may not exist yet if environments haven't been activated, so we only warn
	[[ -d "${FLOX_RUNTIME_NETWORKING_DIR}" ]] || {
		echo "Warning: flox runtime networking directory not yet populated: ${FLOX_RUNTIME_NETWORKING_DIR}" >&2
	}
	[[ -d "${FLOX_RUNTIME_DEBUG_TOOLS_DIR}" ]] || {
		echo "flox runtime debug tools directory missing: ${FLOX_RUNTIME_DEBUG_TOOLS_DIR}" >&2
		exit 1
	}
}

runtime::debug:any_enabled() {
	rke2lab::bool:is_true "${RKE2LAB_POLICY_DEBUG_KDNS_ENABLED:-false}" ||
		rke2lab::bool:is_true "${RKE2LAB_POLICY_DEBUG_NRI_PLUGINS_FLOX_ENABLED:-false}"
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

runtime::debug:nri:configure() {
	# NRI plugin debug: use dlv attach to running process
	# No special configuration needed - plugin runs normally
	# To debug:
	#   1. Find PID: pgrep -af 10-flox
	#   2. Attach: dlv attach <pid>
	#   3. Set breakpoints in CreateContainer hook
	: # No-op, just documentation
}

flox::env:configmap:create() {
	local nix_profile_path nix_profile_bin_path flox_runtime_profile_bin namespace kubectl_bin

	echo "Creating/updating flox-env ConfigMap with runtime-resolved paths"

	# Resolve the Nix default profile to get actual store path. Append /sbin
	# and /bin so the carrier image's busybox applets (sh, env, …) remain on
	# PATH after `flox activate` swaps the profile in — flox needs `/bin/sh`
	# to bootstrap activation, and busybox is the only thing in the prod
	# carrier image (see FloxDebugPolicy.PROD_IMAGE).
	nix_profile_path="$(realpath /nix/var/nix/profiles/default)"
	nix_profile_bin_path="${nix_profile_path}/bin:/sbin:/bin"

	echo "  Resolved Nix profile: ${nix_profile_path}"
	echo "  PATH (with busybox tail): ${nix_profile_bin_path}"

	# Get the Flox runtime profile bin path (created by flox::runtime:profile:ensure)
	flox_runtime_profile_bin="${FLOX_RUNTIME_PROFILE_BIN:-/nix/var/nix/profiles/flox-runtime/bin}"
	echo "  Flox runtime profile bin: ${flox_runtime_profile_bin}"

	# Determine namespace (from environment or default)
	namespace="${FLOX_ENV_CONFIGMAP_NAMESPACE:-rke2lab-system}"

	# Find kubectl (should be in PATH from flox activate or host)
	kubectl_bin="$(command -v kubectl)" || {
		echo "ERROR: kubectl not found in PATH" >&2
		return 1
	}

	# Create ConfigMap with resolved paths
	"${kubectl_bin}" create configmap flox-env \
		--namespace="${namespace}" \
		--from-literal="NIX_DEFAULT_PROFILE_BIN_STORE_PATH=${nix_profile_bin_path}" \
		--from-literal="FLOX_RUNTIME_PROFILE_BIN=${flox_runtime_profile_bin}" \
		--dry-run=client -o yaml | "${kubectl_bin}" apply -f -

	echo "  flox-env ConfigMap created/updated in namespace ${namespace}"
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
	# shellcheck disable=SC2034  # Reserved for future force-install logic
	force_install="${RKE2LAB_DEBUG_SHARE_FORCE_INSTALL:-false}"

	mkdir -p "${target_root}"

	while IFS= read -r -d '' source_path; do
		relative="${source_path#"${source_root}"/}"
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

runtime::runtime:nri-plugin:build() {
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

nri::plugin:gcroot:ensure() {
	local nri_plugin_pkg_path package_name

	# Choose debug or production build based on debug policy
	if runtime::debug:any_enabled; then
		package_name="flox-nri-plugin-debug"
		echo "Debug mode enabled: building NRI plugin with debug symbols"
	else
		package_name="flox-nri-plugin"
		echo "Production mode: building optimized NRI plugin"
	fi

	# Build and GC-root the NRI plugin (but don't install to /opt/nri/plugins)
	# The main container will run it directly via flox-nri-plugin-run.sh
	nri_plugin_pkg_path="$(runtime::runtime:nri-plugin:build "${package_name}")"

	# GC-root the NRI plugin closure
	nix-store --add-root \
		"${NIX_GC_ROOTS:-/nix/var/nix/gcroots}/flox-runtime/flox-nri-plugin" \
		--indirect -r "${nri_plugin_pkg_path}" >/dev/null

	echo "NRI plugin package GC-rooted (${package_name}): ${nri_plugin_pkg_path}"
}

flox::runtime:profile:ensure() {
	local profile_path default_profile_path

	# Create a dedicated Nix profile for the Flox runtime environment
	# Instead of installing individual packages, we'll use the packages already
	# available in the default Nix profile, which includes bash, coreutils, nix, flox
	profile_path="/nix/var/nix/profiles/flox-runtime"
	default_profile_path="${NIX_VAR_PROFILES_DEFAULT:-/nix/var/nix/profiles/default}"

	echo "Creating Flox runtime profile based on default profile: ${default_profile_path}"

	# Install packages by name from nixpkgs (they're already in the store via the default profile)
	nix-env --profile "${profile_path}" \
		--install --attr nixpkgs.bash nixpkgs.coreutils nixpkgs.nix nixpkgs.flox 2>&1 || {
		echo "WARNING: Failed to install via nixpkgs attrs, trying by derivation path..." >&2
		# Fallback: copy the default profile's package set
		# This ensures we get the same versions being used
		nix-env --profile "${profile_path}" --set "${default_profile_path}"
	}

	echo "Flox runtime profile created: ${profile_path}"

	# Store the profile bin path for later use
	FLOX_RUNTIME_PROFILE_BIN="${profile_path}/bin"
	export FLOX_RUNTIME_PROFILE_BIN
}

runtime::runtime:core:install() {
	: "Install/refresh flox runtime binaries on host"
	# NRI plugin approach: no longer install custom shim binaries
	# The NRI plugin handles Flox environment injection instead
	# runtime::runtime:env:ensure "${flox_env_dir}"
	# runtime::runtime:gcroots:ensure
	# containerd_bin="$(runtime::runtime:containerd:resolve-bin)"
	# variant="$(runtime::runtime:variant:resolve "${containerd_bin}")"
	# runtime::runtime:binary:install "${flox_env_dir}" "${arch}" "${variant}"
	runtime::runtime:config-template:ensure
	nri::plugin:gcroot:ensure
	flox::runtime:profile:ensure
}

containerd::config:path:init() {
	CONTAINERD_CONFIG_DIR="${CONTAINERD_CONFIG_DIR:-$(dirname "${CONTAINERD_CONFIG_FILE}")}"
	CONTAINERD_CONFIG_FILE="${CONTAINERD_CONFIG_FILE:-${CONTAINERD_CONFIG_DIR}/config.toml}"
	CONTAINERD_CONFIG_TEMPLATE="${CONTAINERD_CONFIG_FILE}.tmpl"
	# shellcheck disable=SC2034  # Reserved for future template logic
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
	local version plugin_root nri_plugin_root tmp checksum_before checksum_after
	version="$(containerd::config:version:detect)"
	if [[ "${version}" == "3" ]]; then
		plugin_root="io.containerd.cri.v1.runtime"
		nri_plugin_root="io.containerd.nri.v1.nri"
	else
		plugin_root="io.containerd.grpc.v1.cri"
		nri_plugin_root="io.containerd.nri.v1.nri"
	fi

	# Calculate checksum before update
	checksum_before="$(sha256sum "${CONTAINERD_CONFIG_TEMPLATE}" 2>/dev/null | awk '{print $1}' || echo "none")"

	local tmp
	tmp="$(mktemp)"

	"${DASEL_BIN}" -i toml -o yaml <"${CONTAINERD_CONFIG_TEMPLATE}" |
		CRI_PLUGIN_ROOT="${plugin_root}" NRI_PLUGIN_ROOT="${nri_plugin_root}" "${YQ_BIN}" '
      .plugins[env(CRI_PLUGIN_ROOT)].containerd.systemd_cgroup = true |
      .plugins[env(NRI_PLUGIN_ROOT)].disable = false |
      .plugins[env(NRI_PLUGIN_ROOT)].plugin_config_path = "/etc/nri/conf.d" |
      .plugins[env(NRI_PLUGIN_ROOT)].plugin_path = "/opt/nri/plugins"
    ' |
		"${DASEL_BIN}" -i yaml -o toml >"${tmp}" &&
		mv "${tmp}" "${CONTAINERD_CONFIG_TEMPLATE}"

	rm -f "${tmp}"

	# Calculate checksum after update
	checksum_after="$(sha256sum "${CONTAINERD_CONFIG_TEMPLATE}" 2>/dev/null | awk '{print $1}' || echo "none")"

	# Return 0 (true) if config changed, 1 (false) if unchanged
	if [[ "${checksum_before}" != "${checksum_after}" ]]; then
		return 0
	else
		return 1
	fi
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

	: "Pre-activate flox environments to populate /nix/store"
	installer::host:flox:activate_environments

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

	: "Configure NRI plugin debug mode based on policy"
	runtime::debug:nri:configure

	: "Create/update flox-env ConfigMap with runtime-resolved paths"
	flox::env:configmap:create

	: "Update containerd configuration to include the flox runtime"
	local config_changed=false
	if containerd::config:flox:update; then
		config_changed=true
	fi

	: "Restart containerd only if configuration changed"
	if [[ "${config_changed}" == "true" ]]; then
		container::service:runtime:restart
	else
		echo "containerd configuration unchanged, skipping restart"
	fi
}

installer::mode:validate

if [[ "${DAEMONLESS_EXEC_MODE}" == "pod" ]]; then
	installer::pod:run
	return 0 2>/dev/null || exit 0
fi

installer::host:run
