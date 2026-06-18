#!/usr/bin/env bash

# shellcheck shell=bash

# Canonical daemonset host-binary installation pattern:
# 1. Install the real executable asset under the daemonset-owned host asset root.
# 2. Expose the host-executable entrypoint under <asset-root>/bin.
# 3. Let daemonset::trampoline:exec_on_host resolve commands from that bin directory.
#
# Installers should prefer daemonset::host_binary:install for host-reexec-capable entrypoints,
# and use raw install(1) only for non-entrypoint assets such as config, policy, or data files.

daemonset::host_binary:bin:dir() {
	local binary_root="${1:?binary root required}"
	printf '%s/bin\n' "${binary_root%/}"
}

daemonset::host_binary:bin:ensure() {
	local binary_root="${1:?binary root required}"
	local bin_dir

	bin_dir="$(daemonset::host_binary:bin:dir "${binary_root}")"
	mkdir -p "${bin_dir}"
	printf '%s\n' "${bin_dir}"
}

daemonset::host_binary:entrypoint:install() {
	local binary_root="${1:?binary root required}"
	local command_name="${2:?command name required}"
	local target_relative_path="${3:?target relative path required}"
	local bin_dir entrypoint_path

	bin_dir="$(daemonset::host_binary:bin:ensure "${binary_root}")" || return 1
	entrypoint_path="${bin_dir%/}/${command_name}"

	cat >"${entrypoint_path}" <<EOF
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_BIN_DIR="\$(cd -- "\$(dirname -- "\${BASH_SOURCE[0]:-\$0}")" && pwd)"
SCRIPT_ROOT="\$(cd -- "\${SCRIPT_BIN_DIR}/.." && pwd)"

exec "\${SCRIPT_ROOT}/${target_relative_path}" "\$@"
EOF
	chmod 0755 "${entrypoint_path}"
	printf '%s\n' "${entrypoint_path}"
}

daemonset::host_binary:install() {
	local source_path="${1:?source path required}"
	local binary_root="${2:?binary root required}"
	local command_name="${3:?command name required}"
	local target_relative_path="${4:?target relative path required}"
	local install_mode="${5:-0755}"
	local target_path

	[[ -r "${source_path}" ]] || {
		echo "host binary source missing or unreadable: ${source_path}" >&2
		return 1
	}

	target_path="${binary_root%/}/${target_relative_path}"
	install -D -m "${install_mode}" "${source_path}" "${target_path}"
	daemonset::host_binary:entrypoint:install "${binary_root}" "${command_name}" "${target_relative_path}" >/dev/null
	printf '%s\n' "${target_path}"
}

daemonset::host_asset:materialize_encoded_tar() {
	local archive_b64_path="$1"
	local manifest_path="$2"
	local output_root="$3"
	local output_subdir="${4:-}"
	local expected_archive_size expected_archive_sha256 actual_archive_size actual_archive_sha256
	local archive_size_file archive_sha256_file
	local unpack_dir unpacked_root

	[[ -r "${archive_b64_path}" ]] || {
		echo "archive payload missing: ${archive_b64_path}" >&2
		return 1
	}
	[[ -r "${manifest_path}" ]] || {
		echo "archive manifest missing: ${manifest_path}" >&2
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
		echo "archive size mismatch: expected ${expected_archive_size}, got ${actual_archive_size}" >&2
		return 1
	}
	[[ "${actual_archive_sha256}" == "${expected_archive_sha256}" ]] || {
		echo "archive checksum mismatch: expected ${expected_archive_sha256}, got ${actual_archive_sha256}" >&2
		return 1
	}

	jq -r '.entries[] | [.path, (.size | tostring), .sha256] | @tsv' "${manifest_path}" |
		while IFS=$'\t' read -r relative_path expected_size expected_sha256; do
			local extracted_file actual_size actual_sha256
			extracted_file="${unpack_dir}/${relative_path}"
			[[ -f "${extracted_file}" ]] || {
				echo "archive entry missing after unpack: ${relative_path}" >&2
				return 1
			}
			actual_size="$(wc -c <"${extracted_file}" | tr -d '[:space:]')"
			actual_sha256="$(sha256sum "${extracted_file}" | awk '{print $1}')"
			[[ "${actual_size}" == "${expected_size}" ]] || {
				echo "archive entry size mismatch for ${relative_path}: expected ${expected_size}, got ${actual_size}" >&2
				return 1
			}
			[[ "${actual_sha256}" == "${expected_sha256}" ]] || {
				echo "archive entry checksum mismatch for ${relative_path}: expected ${expected_sha256}, got ${actual_sha256}" >&2
				return 1
			}
		done

	cmp -s \
		<(jq -r '.entries[].path' "${manifest_path}" | LC_ALL=C sort) \
		<(find "${unpack_dir}" -type f | sed "s#^${unpack_dir}/##" | LC_ALL=C sort) || {
		echo "archive unpacked file set does not match manifest" >&2
		echo "expected:" >&2
		jq -r '.entries[].path' "${manifest_path}" | LC_ALL=C sort >&2
		echo "actual:" >&2
		find "${unpack_dir}" -type f | sed "s#^${unpack_dir}/##" | LC_ALL=C sort >&2
		return 1
	}

	if [[ -n "${output_subdir}" ]]; then
		unpacked_root="${unpack_dir}/${output_subdir}"
		[[ -d "${unpacked_root}" ]] || {
			echo "archive output root missing after unpack: ${output_subdir}" >&2
			return 1
		}
		rm -rf "${output_root}/${output_subdir}"
		mv "${unpacked_root}" "${output_root}/${output_subdir}"
		return 0
	fi

	find "${unpack_dir}" -mindepth 1 -maxdepth 1 -exec mv {} "${output_root}/" \;
}
