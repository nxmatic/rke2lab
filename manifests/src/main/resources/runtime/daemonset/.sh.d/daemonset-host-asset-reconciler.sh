#!/usr/bin/env bash

# shellcheck shell=bash

# Generic daemonset reconcile policy: watch a ConfigMap for changes, materialize
# verified assets onto the host, then invoke a workflow-specific hook.
#
# Canonical contract:
# - Watches <source_dir> for ConfigMap atomic-swap events (inotifywait -e moved_to)
# - On change, materializes encoded tar archive from <source_dir>/<archive_key>
# - Validates against <source_dir>/<manifest_key> via daemonset::host_asset:materialize_encoded_tar
# - If checksums differ, trampolines <on_change_hook> to host via daemonset::trampoline:exec_on_host
# - Hook receives no arguments; reads DAEMONSET_HOST_SCRIPT_ROOT from environment

daemonset::host_asset:watch_and_reconcile() {
	local source_dir="${1:?source_dir required}"
	local archive_key="${2:?archive_key required}"
	local manifest_key="${3:?manifest_key required}"
	local target_root="${4:?target_root required}"
	local on_change_hook="${5:?on_change_hook required}"
	local archive_path manifest_path checksum_file prior_checksum current_checksum

	archive_path="${source_dir%/}/${archive_key}"
	manifest_path="${source_dir%/}/${manifest_key}"
	checksum_file="/tmp/daemonset-reconciler-${archive_key}.sha256"

	[[ -d "${source_dir}" ]] || {
		echo "daemonset reconciler: source_dir does not exist: ${source_dir}" >&2
		return 1
	}

	# Compute initial checksum if archive already exists
	if [[ -r "${archive_path}" ]]; then
		sha256sum "${archive_path}" | awk '{print $1}' >"${checksum_file}"
		echo "daemonset reconciler: initial checksum recorded for ${archive_key}"
	fi

	echo "daemonset reconciler: watching ${source_dir} for ConfigMap updates..."
	echo "  archive_key: ${archive_key}"
	echo "  manifest_key: ${manifest_key}"
	echo "  target_root: ${target_root}"
	echo "  on_change_hook: ${on_change_hook}"

	# ConfigMap volume mounts in Kubernetes use atomic symlink swap: a ..data -> ..data_tmp
	# rename. Watch for moved_to events on the parent directory.
	while inotifywait -q -e moved_to "${source_dir}"; do
		echo "daemonset reconciler: change detected in ${source_dir}"

		[[ -r "${archive_path}" ]] || {
			echo "daemonset reconciler: archive missing after change event: ${archive_path}" >&2
			continue
		}
		[[ -r "${manifest_path}" ]] || {
			echo "daemonset reconciler: manifest missing after change event: ${manifest_path}" >&2
			continue
		}

		current_checksum="$(sha256sum "${archive_path}" | awk '{print $1}')"
		prior_checksum="$(cat "${checksum_file}" 2>/dev/null || echo "")"

		if [[ -n "${prior_checksum}" && "${current_checksum}" == "${prior_checksum}" ]]; then
			echo "daemonset reconciler: checksum unchanged (${current_checksum}); skipping reconcile"
			continue
		fi

		echo "daemonset reconciler: checksum changed (${prior_checksum:-none} -> ${current_checksum})"
		echo "daemonset reconciler: materializing ${archive_key} to ${target_root}..."

		if daemonset::host_asset:materialize_encoded_tar \
			"${archive_path}" \
			"${manifest_path}" \
			"${target_root}" \
			""; then
			echo "daemonset reconciler: materialization succeeded; recording new checksum"
			echo "${current_checksum}" >"${checksum_file}"

			echo "daemonset reconciler: invoking hook ${on_change_hook} on host..."
			if daemonset::trampoline:exec_on_host "${on_change_hook}"; then
				echo "daemonset reconciler: hook ${on_change_hook} completed successfully"
			else
				echo "daemonset reconciler: hook ${on_change_hook} failed (exit $?)" >&2
			fi
		else
			echo "daemonset reconciler: materialization failed; keeping prior checksum" >&2
		fi
	done
}
