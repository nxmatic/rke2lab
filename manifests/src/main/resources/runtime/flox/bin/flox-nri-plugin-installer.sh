#!/usr/bin/env bash
set -exuo pipefail

DAEMONSET_EXEC_MODE="${DAEMONSET_EXEC_MODE:-pod}"
DAEMONSET_ASSET_SUBDIR="runtime/flox"

# Pod-mode constants — ConfigMap mounts and host bind-mounts the daemonset
# uses for cross-volume copies.
HOST_ROOT="${HOST_ROOT:-/host-root}"
SCRIPT_POLICY_ROOT="${SCRIPT_POLICY_ROOT:-/.sh-daemonset}"
SCRIPT_POLICY_LIB_DIR="${SCRIPT_POLICY_LIB_DIR:-${SCRIPT_POLICY_ROOT%/}/.sh.d}"

# Bootstrap the shared runtime lib. Pod mode sources it from the policy
# ConfigMap mount; host mode (post-trampoline) sources it from the workspace
# volume the pod just materialized. paths:bind populates the canonical
# DAEMONSET_HOST_SCRIPT_* set; subsequent helpers all read from there.
case "${DAEMONSET_EXEC_MODE}" in
pod) _bootstrap_lib_dir="${SCRIPT_POLICY_LIB_DIR}" ;;
host) _bootstrap_lib_dir="${DAEMONSET_SCRIPT_ROOT:-/srv/host/k8s-daemonset.d}/${DAEMONSET_ASSET_SUBDIR}/.sh.d" ;;
*)
	echo "unsupported DAEMONSET_EXEC_MODE: ${DAEMONSET_EXEC_MODE} (expected pod or host)" >&2
	exit 1
	;;
esac
# shellcheck disable=SC1091
source "${_bootstrap_lib_dir}/daemonset-runtime.sh"
unset _bootstrap_lib_dir

daemonset::runtime:paths:bind
daemonset::runtime:preflight
daemonset::runtime:libs:source

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

	installer::host:flox:gcroot_env_packages "${env_root}" "${discovered_envs[@]}"
}

# Realize each activated env's static subtree (env/{manifest.toml,manifest.lock})
# into the nix store and GC-root it at a stable well-known path. This is the
# store-resolved env model: the env's static half is immutable cluster state, so
# it belongs in /nix/store; only flox's run/cache/log are mutable (the container
# materializes those locally via flox-nri-env-link-hook.sh). The container's
# /nix/store overlay lowers from the host store, so the same store path resolves
# inside the container.
#
# Mirrors nri::plugin:gcroot:ensure exactly: build/realize -> --add-root
# --indirect, and the consumer (the NRI plugin) reads the gcroot via readlink to
# get the real store path. This supersedes copying the env tree (and its lock)
# into /var/run — the gcroot IS the node-local handoff surface now.
#
# See docs/flox-store-resolved-runtime-and-builder.adoc.
installer::host:flox:gcroot_env_packages() {
	local env_root="$1"
	shift
	local gcroot_base="${NIX_GC_ROOTS:-/nix/var/nix/gcroots}/flox-runtime/env"
	local env_path src_env staging storepath gcroot

	for env_path in "$@"; do
		src_env="${env_root}/${env_path}/.flox/env"
		[[ -r "${src_env}/manifest.lock" ]] || {
			echo "  ⚠ no lock for ${env_path}; skipping store realization (${src_env}/manifest.lock absent)" >&2
			continue
		}

		# Assemble the static subtree under env/ so the realized store path exposes
		# <store-path>/env/{manifest.toml,manifest.lock} — the layout
		# flox-nri-env-link-hook.sh symlinks into the container's .flox/env.
		staging="$(mktemp -d)"
		mkdir -p "${staging}/env"
		cp -f "${src_env}/manifest.toml" "${src_env}/manifest.lock" "${staging}/env/"

		storepath="$(nix --extra-experimental-features 'nix-command flakes' \
			store add-path --name "flox-env-${env_path//\//-}" "${staging}")" || {
			echo "  ✗ failed to realize store path for ${env_path}" >&2
			rm -rf "${staging}"
			return 1
		}
		rm -rf "${staging}"

		gcroot="${gcroot_base}/${env_path}"
		mkdir -p "${gcroot%/*}"
		nix-store --add-root "${gcroot}" --indirect -r "${storepath}" >/dev/null

		echo "  ✓ ${env_path} env GC-rooted: ${gcroot} -> ${storepath}"
	done
}

installer::host:flox:activate() {
	: Resolve flox in the binary path
	source /nix/var/nix/profiles/default/etc/profile.d/nix-daemon.sh
	: Activate the RKE2 flox env
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

host::nix:flox-conf:ensure() {
	local flox_conf

	[[ -f "${flox_conf:=/etc/nix/flox.conf}" ]] &&
		return 0

	install -m 0644 -T /dev/null "${flox_conf}"
}

runtime::assets:path:init() {
	FLOX_RUNTIME_ROOT="${DAEMONSET_HOST_SCRIPT_ROOT}"
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

	# Create runtime directories that may not be materialized by the pod phase
	mkdir -p "${FLOX_RUNTIME_ETC_DIR}" "${FLOX_RUNTIME_LOG_DIR}" "${FLOX_RUNTIME_OVERLAY_MOUNT_POINTS_DIR}"
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

	nix build \
		--no-link \
		--print-out-paths \
		"${FLOX_RUNTIME_ROOT}#${package_name}"
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
	nri::plugin:gcroot:ensure
	flox::runtime:profile:ensure
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

containerd::config:flox:update() {
	# Drop NRI config file into containerd import directory
	# RKE2 reads: imports = ['/var/lib/rancher/rke2/agent/etc/containerd/config-v3.toml.d/*.toml']
	# This approach is simpler than template manipulation and idempotent
	local config_dir="/var/lib/rancher/rke2/agent/etc/containerd/config-v3.toml.d"
	local nri_config="${config_dir}/90-nri.toml"

	mkdir -p "${config_dir}"

	# Check if config already exists and matches desired state
	local desired_config
	desired_config=$(
		cat <<-'EOF'
			[plugins."io.containerd.nri.v1.nri"]
			  disable = false
			  plugin_config_path = "/etc/nri/conf.d"
			  plugin_path = "/opt/nri/plugins"

			[plugins."io.containerd.cri.v1.runtime".containerd.runtimes.runc.options]
			  SystemdCgroup = true
		EOF
	)

	if [[ -f "${nri_config}" ]] && [[ "$(cat "${nri_config}")" == "${desired_config}" ]]; then
		echo "NRI config already present and up to date at ${nri_config}"
		return 1 # No change needed
	fi

	echo "Writing NRI config to ${nri_config}"
	echo "${desired_config}" >"${nri_config}"
	return 0 # Config changed
}

flox_nri_plugin::on_materialize() {
	case "${1}" in
	pre)
		install_deps
		;;
	post)
		# OCI prestart hooks — runc looks them up in /usr/local/sbin on the
		# host filesystem (bind-mounted at ${HOST_ROOT} inside this pod), not
		# under our daemonset asset root.
		daemonset::runtime:assets:install_executable \
			"${DAEMONSET_HOST_SCRIPT_BIN}/flox-nri-overlay-hook.sh" \
			"${HOST_ROOT}/usr/local/sbin/flox-nri-overlay-hook.sh"
		daemonset::runtime:assets:install_executable \
			"${DAEMONSET_HOST_SCRIPT_BIN}/flox-nri-env-link-hook.sh" \
			"${HOST_ROOT}/usr/local/sbin/flox-nri-env-link-hook.sh"
		daemonset::runtime:assets:install_executable \
			"${DAEMONSET_HOST_SCRIPT_BIN}/flox-nri-chown-hook.sh" \
			"${HOST_ROOT}/usr/local/sbin/flox-nri-chown-hook.sh"
		;;
	esac
}

flox_nri_plugin::pod:run() {
	daemonset::runtime:materialize flox_nri_plugin

	# Hand off to host. The trampoline forwards DAEMONSET_SCRIPT_ROOT (host
	# base root) and DAEMONSET_EXEC_MODE=host; the host child re-runs paths:bind
	# to derive the rest. DAEMONSET_HOST_SCRIPT_ROOT in the env is read by the
	# trampoline to find the host bin dir for the re-exec'd command.
	#
	# Root is the shared /srv/host tree, by design: host:run cd's into each env
	# here and `flox activate` writes .flox/env/{flake,manifest}.lock onto the
	# shared NFS filesystem. The master locks once; every node reading the same
	# folder resolves identical /nix/store paths — runtime cluster state, the
	# single source of truth. The arch-specific .flox/{run,cache,lib,log} state
	# written alongside is valid on every (Linux) node; it is excluded from the
	# dev-machine asset sync on the seed-master side, not avoided here.
	local host_base_root="/srv/host/k8s-daemonset.d"
	local host_asset_root="${host_base_root}/${DAEMONSET_ASSET_SUBDIR}"

	DAEMONSET_HOST_SCRIPT_ROOT="${host_asset_root}" \
		DAEMONSET_SCRIPT_ROOT="${host_base_root}" \
		daemonset::trampoline:exec_on_host \
		"flox-nri-plugin-installer.sh"
}

flox_nri_plugin::host:run() {
	installer::host:flox:activate

	: "Initialize runtime asset paths and load environment"
	host::nix:flox-conf:ensure
	runtime::assets:path:init
	runtime::assets:path:validate

	: "Load RKE2Lab environment variables from ConfigMap/Secret manifests"
	local env_script="${RKE2LAB_SCRIPTS_DIR:-/srv/host/systemd-scripts.d}/rke2lab-env-load.sh"
	if [[ -r "${env_script}" ]]; then
		# shellcheck disable=SC1090
		source "${env_script}"
	fi

	: "Pre-activate flox environments to populate /nix/store"
	installer::host:flox:activate_environments

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
	: "NOTE: If NRI was pre-enabled in rke2-server prestart, config is already present and restart is skipped"
	if [[ "${config_changed}" == "true" ]]; then
		container::service:runtime:restart
	else
		echo "NRI config already present, no restart needed"
	fi
}

daemonset::runtime:dispatch flox_nri_plugin
