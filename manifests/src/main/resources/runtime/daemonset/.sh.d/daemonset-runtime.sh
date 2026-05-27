#!/usr/bin/env bash

# shellcheck shell=bash

# Shared daemonset runtime: path contract + preflight + dispatch.
#
# Each daemonset's installer script reduces to:
#
#   #!/usr/bin/env bash
#   set -exuo pipefail
#   DAEMONSET_ASSET_SUBDIR="runtime/<my-daemonset>"
#   source "${SCRIPT_POLICY_LIB_DIR}/daemonset-runtime.sh"   # in pod
#   # or `source "${DAEMONSET_HOST_SCRIPT_LIB_DIR}/daemonset-runtime.sh"` in host
#
#   my_daemonset::pod:run()  { … }     # pre-trampoline work in the init container
#   my_daemonset::host:run() { … }     # work after the trampoline lands on host
#
#   daemonset::runtime:bind
#   daemonset::runtime:dispatch my_daemonset
#
# Path contract (set by daemonset::runtime:paths:bind):
#
#   DAEMONSET_SCRIPT_ROOT        — base root, sibling of every daemonset's tree.
#                                  host: /srv/host/k8s-daemonset.d
#                                  pod:  /var/run/k8s-daemonset.d (workspace
#                                        hostPath, path-identical inside)
#   DAEMONSET_HOST_SCRIPT_ROOT   — asset root = ${DAEMONSET_SCRIPT_ROOT}/${DAEMONSET_ASSET_SUBDIR}
#   DAEMONSET_HOST_SCRIPT_BIN    — ${DAEMONSET_HOST_SCRIPT_ROOT}/bin
#   DAEMONSET_HOST_SCRIPT_LIB_DIR — ${DAEMONSET_HOST_SCRIPT_ROOT}/.sh.d
#   DAEMONSET_HOST_SCRIPT_ETC_DIR — ${DAEMONSET_HOST_SCRIPT_ROOT}/etc
#   DAEMONSET_SCRIPT_LOG_DIR     — ${DAEMONSET_HOST_SCRIPT_ROOT}/log

daemonset::runtime:mode:resolve() {
	local mode="${DAEMONSET_EXEC_MODE:-pod}"

	case "${mode}" in
	host | pod) printf '%s\n' "${mode}" ;;
	*)
		echo "unsupported daemonset exec mode: ${mode} (expected host or pod)" >&2
		return 1
		;;
	esac
}

daemonset::runtime:base_root:default() {
	case "${1:?mode required}" in
	host) printf '%s\n' "/srv/host/k8s-daemonset.d" ;;
	pod) printf '%s\n' "/var/run/k8s-daemonset.d" ;;
	esac
}

# Populate the canonical DAEMONSET_* path variables. Idempotent: an environment
# value for DAEMONSET_SCRIPT_ROOT (e.g. supplied by flox activation in host
# mode, or an explicit pod-spec env entry) wins over the per-mode default.
daemonset::runtime:paths:bind() {
	local mode subdir base_root

	mode="$(daemonset::runtime:mode:resolve)" || return 1
	subdir="${DAEMONSET_ASSET_SUBDIR:?DAEMONSET_ASSET_SUBDIR required (e.g. runtime/flox)}"
	base_root="$(daemonset::runtime:base_root:default "${mode}")"

	DAEMONSET_SCRIPT_ROOT="${DAEMONSET_SCRIPT_ROOT:-${base_root}}"
	DAEMONSET_HOST_SCRIPT_ROOT="${DAEMONSET_SCRIPT_ROOT%/}/${subdir}"
	DAEMONSET_HOST_SCRIPT_BIN="${DAEMONSET_HOST_SCRIPT_ROOT}/bin"
	DAEMONSET_HOST_SCRIPT_LIB_DIR="${DAEMONSET_HOST_SCRIPT_ROOT}/.sh.d"
	DAEMONSET_HOST_SCRIPT_ETC_DIR="${DAEMONSET_HOST_SCRIPT_ROOT}/etc"
	DAEMONSET_SCRIPT_LOG_DIR="${DAEMONSET_HOST_SCRIPT_ROOT}/log"
}

# Fail fast with a single concise error naming all four paths + the mode if
# any required input is missing. Catches layout drift between writer and
# reader before sourcing 200 lines deep.
daemonset::runtime:preflight() {
	local mode missing=()

	mode="$(daemonset::runtime:mode:resolve)" || return 1

	for var in DAEMONSET_SCRIPT_ROOT DAEMONSET_HOST_SCRIPT_ROOT \
		DAEMONSET_HOST_SCRIPT_BIN DAEMONSET_HOST_SCRIPT_LIB_DIR; do
		[[ -n "${!var:-}" ]] || missing+=("${var} (unset)")
	done

	[[ ${#missing[@]} -eq 0 ]] || {
		echo "daemonset runtime preflight failed (mode=${mode}):" >&2
		printf '  - %s\n' "${missing[@]}" >&2
		echo "  call daemonset::runtime:paths:bind first" >&2
		return 1
	}

	# Required inputs the runtime expects to find on disk before the daemonset's
	# topic functions run. We don't validate BIN here (the daemonset's own
	# installer is the entrypoint, often invoked by the kubelet directly), but
	# the LIB_DIR has to host the policy libs we'll source.
	for required_lib in daemonset-logging.sh daemonset-host-shell-policy.sh \
		daemonset-trampoline.sh; do
		[[ -r "${DAEMONSET_HOST_SCRIPT_LIB_DIR}/${required_lib}" ]] || {
			echo "daemonset runtime preflight failed (mode=${mode}):" >&2
			echo "  missing required policy lib: ${DAEMONSET_HOST_SCRIPT_LIB_DIR}/${required_lib}" >&2
			echo "  resolved paths:" >&2
			echo "    DAEMONSET_SCRIPT_ROOT=${DAEMONSET_SCRIPT_ROOT}" >&2
			echo "    DAEMONSET_HOST_SCRIPT_ROOT=${DAEMONSET_HOST_SCRIPT_ROOT}" >&2
			echo "    DAEMONSET_HOST_SCRIPT_LIB_DIR=${DAEMONSET_HOST_SCRIPT_LIB_DIR}" >&2
			return 1
		}
	done
}

# Source the canonical policy libs. Callers should have already run
# daemonset::runtime:paths:bind + daemonset::runtime:preflight, so any
# missing-file failure here is a programming error in the runtime itself.
daemonset::runtime:libs:source() {
	local lib_dir="${DAEMONSET_HOST_SCRIPT_LIB_DIR}"
	# shellcheck disable=SC1091
	source "${lib_dir}/daemonset-logging.sh"
	# shellcheck disable=SC1091
	source "${lib_dir}/daemonset-host-shell-policy.sh"
	# shellcheck disable=SC1091
	source "${lib_dir}/daemonset-trampoline.sh"
}

# Materialize the shared policy library from the ConfigMap mount
# (${SCRIPT_POLICY_LIB_DIR}) into the per-node workspace volume
# (${DAEMONSET_HOST_SCRIPT_LIB_DIR}). Required in pod mode before the
# trampoline can re-exec on the host: the host filesystem doesn't see the
# in-pod ConfigMap mount, so the workspace is the only place the host child
# can read the libs from.
daemonset::runtime:assets:install_policy_lib() {
	local source_lib_dir="${SCRIPT_POLICY_LIB_DIR:?SCRIPT_POLICY_LIB_DIR required (ConfigMap mount, e.g. /.sh-daemonset/.sh.d)}"
	local target_lib_dir="${DAEMONSET_HOST_SCRIPT_LIB_DIR:?DAEMONSET_HOST_SCRIPT_LIB_DIR required; call paths:bind first}"

	mkdir -p "${target_lib_dir}"
	for lib in daemonset-runtime.sh daemonset-logging.sh \
		daemonset-host-shell-policy.sh daemonset-trampoline.sh \
		daemonset-host-asset-materializer.sh daemonset-host-asset-reconciler.sh; do
		[[ -r "${source_lib_dir%/}/${lib}" ]] || {
			echo "policy lib source missing or unreadable: ${source_lib_dir%/}/${lib}" >&2
			return 1
		}
		install -D -m 0644 "${source_lib_dir%/}/${lib}" "${target_lib_dir%/}/${lib}"
	done
}

# Install a single executable from the workspace bin/ to a host path. Used
# for OCI hooks and any other daemonset-specific assets the daemonset wants
# to drop into a well-known host directory. Wraps `install -D` with a clearer
# error on missing source so layout drift surfaces immediately.
daemonset::runtime:assets:install_executable() {
	local source_path="${1:?source path required}"
	local target_path="${2:?target path required}"
	local install_mode="${3:-0755}"

	[[ -r "${source_path}" ]] || {
		echo "asset source missing or unreadable: ${source_path}" >&2
		return 1
	}
	install -D -m "${install_mode}" "${source_path}" "${target_path}"
}

# Dispatch to the daemonset's <namespace>::<mode>:run function. The function
# naming is convention-bound so the runtime never has to know which daemonset
# is running — the caller passes its namespace.
daemonset::runtime:dispatch() {
	local namespace="${1:?namespace required (e.g. flox_nri_plugin)}"
	local mode entry

	mode="$(daemonset::runtime:mode:resolve)" || return 1
	entry="${namespace}::${mode}:run"

	declare -F "${entry}" >/dev/null || {
		echo "daemonset dispatch: function not defined: ${entry}" >&2
		return 1
	}

	"${entry}"
}
