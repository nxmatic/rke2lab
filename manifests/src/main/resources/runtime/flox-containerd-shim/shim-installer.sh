#!/usr/bin/env bash
set -exuo pipefail

install_deps() {
  local attempt=0
  local max_attempts=${APK_MAX_RETRIES:-5}
  while true; do
    attempt=$((attempt + 1))
    if apk update && apk add --no-cache jq unzip util-linux >/tmp/apk.log; then
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
install -D -m 0755 "${BUILD_ASSETS_DIR}/flox-rootfs-sync.sh" "${HOST_SCRIPT_ROOT}/flox-rootfs-sync.sh"
install -D -m 0644 "${BUILD_ASSETS_DIR}/wrapper-go.zip.b64" "${HOST_SCRIPT_ROOT}/wrapper-go.zip.b64"
install -D -m 0644 "${BUILD_ASSETS_DIR}/wrapper-go.manifest.json" "${HOST_SCRIPT_ROOT}/wrapper-go.manifest.json"
install -D -m 0644 "${BUILD_ASSETS_DIR}/mesh/headplane/flake.nix" "${HOST_SCRIPT_ROOT}/mesh/headplane/flake.nix"
install -D -m 0644 "${BUILD_ASSETS_DIR}/networking/kdns/flake.nix" "${HOST_SCRIPT_ROOT}/networking/kdns/flake.nix"

materialize_wrapper_go_archive() {
  local archive_b64_path="$1"
  local manifest_path="$2"
  local output_root="$3"
  local decoded_archive expected_archive_size expected_archive_sha256 actual_archive_size actual_archive_sha256
  local unpack_dir expected_entries actual_entries

  [[ -r "${archive_b64_path}" ]] || {
    echo "wrapper-go archive payload missing: ${archive_b64_path}" >&2
    return 1
  }
  [[ -r "${manifest_path}" ]] || {
    echo "wrapper-go archive manifest missing: ${manifest_path}" >&2
    return 1
  }

  decoded_archive="$(mktemp)"
  unpack_dir="$(mktemp -d)"
  expected_entries="$(mktemp)"
  actual_entries="$(mktemp)"
  trap 'rm -f "${decoded_archive}" "${expected_entries}" "${actual_entries}"; rm -rf "${unpack_dir}"' RETURN

  base64 -d < "${archive_b64_path}" > "${decoded_archive}"

  expected_archive_size="$(jq -r '.archive.size' "${manifest_path}")"
  expected_archive_sha256="$(jq -r '.archive.sha256' "${manifest_path}")"
  actual_archive_size="$(wc -c < "${decoded_archive}" | tr -d '[:space:]')"
  actual_archive_sha256="$(sha256sum "${decoded_archive}" | awk '{print $1}')"

  [[ "${actual_archive_size}" == "${expected_archive_size}" ]] || {
    echo "wrapper-go archive size mismatch: expected ${expected_archive_size}, got ${actual_archive_size}" >&2
    return 1
  }
  [[ "${actual_archive_sha256}" == "${expected_archive_sha256}" ]] || {
    echo "wrapper-go archive checksum mismatch: expected ${expected_archive_sha256}, got ${actual_archive_sha256}" >&2
    return 1
  }

  unzip -oq "${decoded_archive}" -d "${unpack_dir}"

  jq -r '.entries[] | [.path, (.size | tostring), .sha256] | @tsv' "${manifest_path}" |
    while IFS=$'\t' read -r relative_path expected_size expected_sha256; do
      local extracted_file actual_size actual_sha256
      extracted_file="${unpack_dir}/${relative_path}"
      [[ -f "${extracted_file}" ]] || {
        echo "wrapper-go archive entry missing after unpack: ${relative_path}" >&2
        return 1
      }
      actual_size="$(wc -c < "${extracted_file}" | tr -d '[:space:]')"
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

  jq -r '.entries[].path' "${manifest_path}" | LC_ALL=C sort > "${expected_entries}"
  find "${unpack_dir}" -type f | sed "s#^${unpack_dir}/##" | LC_ALL=C sort > "${actual_entries}"
  cmp -s "${expected_entries}" "${actual_entries}" || {
    echo "wrapper-go unpacked file set does not match manifest" >&2
    echo "expected:" >&2
    cat "${expected_entries}" >&2
    echo "actual:" >&2
    cat "${actual_entries}" >&2
    return 1
  }

  rm -rf "${output_root}/wrapper-go"
  mv "${unpack_dir}/wrapper-go" "${output_root}/wrapper-go"
}

materialize_wrapper_go_archive \
  "${HOST_SCRIPT_ROOT}/wrapper-go.zip.b64" \
  "${HOST_SCRIPT_ROOT}/wrapper-go.manifest.json" \
  "${HOST_SCRIPT_ROOT}"

if [[ -f "${BUILD_ASSETS_DIR}/flake.lock" ]]; then
  install -D -m 0644 "${BUILD_ASSETS_DIR}/flake.lock" "${HOST_SCRIPT_ROOT}/flake.lock"
fi

nsenter --target 1 --mount --uts --ipc --net --pid -- env \
  CONTAINERD_CONFIG_FILE="${CONTAINERD_CONFIG_FILE}" \
  DAEMONSET_SCRIPT_ROOT="${DAEMONSET_ASSET_ROOT}" \
  bash -x "${DAEMONSET_ASSET_ROOT}/shim-installer-host.sh"
