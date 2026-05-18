#!/usr/bin/env bash

# shellcheck shell=bash

daemonless::host_asset:materialize_encoded_tar() {
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
