#!/usr/bin/env bash
set -exuo pipefail

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

install_deps

: "Materialize bundled flox build resources onto host filesystem"
HOST_ROOT="${HOST_ROOT:-/host-root}"
SCRIPT_MOUNT_DIR="${SCRIPT_MOUNT_DIR:-/scripts}"
SCRIPT_POLICY_DIR="${SCRIPT_POLICY_DIR:-/runtime-daemonset}"
BUILD_ASSETS_DIR="${BUILD_ASSETS_DIR:-/build-assets}"
DAEMONSET_ASSET_ROOT="/srv/host/k8s-daemonset.d/runtime/flox-containerd-shim"
HOST_SCRIPT_ROOT="${HOST_ROOT}${DAEMONSET_ASSET_ROOT}"

# `BUILD_ASSETS_DIR` is the runtime-installer ConfigMap mounted by Kubernetes into this init
# container. Read archive payloads from that mount directly; only materialize extracted runtime
# content onto the host asset root.

mkdir -p "${HOST_SCRIPT_ROOT}"

install -D -m 0755 "${SCRIPT_MOUNT_DIR}/shim-installer.sh" "${HOST_SCRIPT_ROOT}/shim-installer.sh"
install -D -m 0755 "${SCRIPT_MOUNT_DIR}/shim-installer-host.sh" "${HOST_SCRIPT_ROOT}/shim-installer-host.sh"
install -D -m 0644 "${SCRIPT_POLICY_DIR}/daemonset-logging.sh" "${HOST_SCRIPT_ROOT}/.sh.d/daemonset-logging.sh"

# shellcheck disable=SC1091
source "${SCRIPT_POLICY_DIR}/daemonset-logging.sh"
daemonset::logging:stderr:setup "${HOST_SCRIPT_ROOT}/shim-installer.sh"

install -D -m 0755 "${BUILD_ASSETS_DIR}/flox-shim-build.sh" "${HOST_SCRIPT_ROOT}/flox-shim-build.sh"
install -D -m 0644 "${BUILD_ASSETS_DIR}/flox-shim-build.yaml" "${HOST_SCRIPT_ROOT}/flox-shim-build.yaml"
install -D -m 0644 "${BUILD_ASSETS_DIR}/flake.nix" "${HOST_SCRIPT_ROOT}/flake.nix"
install -D -m 0644 "${BUILD_ASSETS_DIR}/flake.lock" "${HOST_SCRIPT_ROOT}/flake.lock"
install -D -m 0755 "${BUILD_ASSETS_DIR}/flox-rootfs-sync.sh" "${HOST_SCRIPT_ROOT}/flox-rootfs-sync.sh"
install -D -m 0644 "${BUILD_ASSETS_DIR}/debug-tools/.sh.d/rke2lab-debug-tooling.sh" "${HOST_SCRIPT_ROOT}/debug-tools/.sh.d/rke2lab-debug-tooling.sh"
install -D -m 0755 "${BUILD_ASSETS_DIR}/debug-tools/attach_live_flox_shim_strace.sh" "${HOST_SCRIPT_ROOT}/debug-tools/attach_live_flox_shim_strace.sh"
install -D -m 0755 "${BUILD_ASSETS_DIR}/debug-tools/crictl-kdns-repro.sh" "${HOST_SCRIPT_ROOT}/debug-tools/crictl-kdns-repro.sh"
install -D -m 0755 "${BUILD_ASSETS_DIR}/debug-tools/kdns-containerd-bundle-watch.sh" "${HOST_SCRIPT_ROOT}/debug-tools/kdns-containerd-bundle-watch.sh"
install -D -m 0755 "${BUILD_ASSETS_DIR}/debug-tools/kdns-containerd-remote-capture.sh" "${HOST_SCRIPT_ROOT}/debug-tools/kdns-containerd-remote-capture.sh"
install -D -m 0755 "${BUILD_ASSETS_DIR}/debug-tools/master-shim-pprof.sh" "${HOST_SCRIPT_ROOT}/debug-tools/master-shim-pprof.sh"
install -D -m 0644 "${BUILD_ASSETS_DIR}/mesh/headplane/flake.nix" "${HOST_SCRIPT_ROOT}/mesh/headplane/flake.nix"
install -D -m 0644 "${BUILD_ASSETS_DIR}/mesh/headplane/flake.lock" "${HOST_SCRIPT_ROOT}/mesh/headplane/flake.lock"
install -D -m 0644 "${BUILD_ASSETS_DIR}/networking/kdns/flake.nix" "${HOST_SCRIPT_ROOT}/networking/kdns/flake.nix"
install -D -m 0644 "${BUILD_ASSETS_DIR}/networking/kdns/flake.lock" "${HOST_SCRIPT_ROOT}/networking/kdns/flake.lock"

materialize_wrapper_go_archive() {
	local archive_b64_path="$1"
	local manifest_path="$2"
	local output_root="$3"
	local expected_archive_size expected_archive_sha256 actual_archive_size actual_archive_sha256
	local archive_size_file archive_sha256_file
	local unpack_dir

	[[ -r "${archive_b64_path}" ]] || {
		echo "wrapper-go archive payload missing: ${archive_b64_path}" >&2
		return 1
	}
	[[ -r "${manifest_path}" ]] || {
		echo "wrapper-go archive manifest missing: ${manifest_path}" >&2
		return 1
	}

	unpack_dir="$(mktemp -d)"
	archive_size_file="$(mktemp)"
	archive_sha256_file="$(mktemp)"
	trap 'rm -f "${archive_size_file}" "${archive_sha256_file}"; rm -rf "${unpack_dir}"' RETURN

	expected_archive_size="$(jq -r '.archive.size' "${manifest_path}")"
	expected_archive_sha256="$(jq -r '.archive.sha256' "${manifest_path}")"

	base64 -d <"${archive_b64_path}" |
		tee \
			>(wc -c | awk '{print $1}' >"${archive_size_file}") \
			>(sha256sum | awk '{print $1}' >"${archive_sha256_file}") |
		tar -xf - -C "${unpack_dir}"

	actual_archive_size="$(cat "${archive_size_file}")"
	actual_archive_sha256="$(cat "${archive_sha256_file}")"

	[[ "${actual_archive_size}" == "${expected_archive_size}" ]] || {
		echo "wrapper-go archive size mismatch: expected ${expected_archive_size}, got ${actual_archive_size}" >&2
		return 1
	}
	[[ "${actual_archive_sha256}" == "${expected_archive_sha256}" ]] || {
		echo "wrapper-go archive checksum mismatch: expected ${expected_archive_sha256}, got ${actual_archive_sha256}" >&2
		return 1
	}

	jq -r '.entries[] | [.path, (.size | tostring), .sha256] | @tsv' "${manifest_path}" |
		while IFS=$'\t' read -r relative_path expected_size expected_sha256; do
			local extracted_file actual_size actual_sha256
			extracted_file="${unpack_dir}/${relative_path}"
			[[ -f "${extracted_file}" ]] || {
				echo "wrapper-go archive entry missing after unpack: ${relative_path}" >&2
				return 1
			}
			actual_size="$(wc -c <"${extracted_file}" | tr -d '[:space:]')"
			actual_sha256="$(sha256sum "${extracted_file}" | awk '{print $1}')"
			[[ "${actual_size}" == "${expected_size}" ]] || {
				echo "wrapper-go entry size mismatch for ${relative_path}: expected ${expected_size}, got ${actual_size}" >&2
				return 1
			}
			[[ "${actual_sha256}" == "${expected_sha256}" ]] || {
				echo "wrapper-go entry checksum mismatch for ${relative_path}: expected ${expected_sha256}, got ${actual_sha256}" >&2
				return 1
			}
		done

	cmp -s \
		<(jq -r '.entries[].path' "${manifest_path}" | LC_ALL=C sort) \
		<(find "${unpack_dir}" -type f | sed "s#^${unpack_dir}/##" | LC_ALL=C sort) || {
		echo "wrapper-go unpacked file set does not match manifest" >&2
		echo "expected:" >&2
		jq -r '.entries[].path' "${manifest_path}" | LC_ALL=C sort >&2
		echo "actual:" >&2
		find "${unpack_dir}" -type f | sed "s#^${unpack_dir}/##" | LC_ALL=C sort >&2
		return 1
	}

	rm -rf "${output_root}/wrapper-go"
	mv "${unpack_dir}/wrapper-go" "${output_root}/wrapper-go"
}

materialize_wrapper_go_archive \
	"${BUILD_ASSETS_DIR}/wrapper-go.tar.b64" \
	"${BUILD_ASSETS_DIR}/wrapper-go.manifest.json" \
	"${HOST_SCRIPT_ROOT}"

nsenter --target 1 --mount --uts --ipc --net --pid -- env \
	CONTAINERD_CONFIG_FILE="${CONTAINERD_CONFIG_FILE}" \
	DAEMONSET_SCRIPT_ROOT="${DAEMONSET_ASSET_ROOT}" \
	bash -x "${DAEMONSET_ASSET_ROOT}/shim-installer-host.sh"
