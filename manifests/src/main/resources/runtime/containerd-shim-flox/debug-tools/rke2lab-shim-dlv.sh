#!/usr/bin/env bash
set -euo pipefail

WRAPPER_BIN_NAME="containerd-shim-flox-delve-v2"
CONTINUE_FILE_PREFIX="/tmp/containerd-shim-flox-v2-wrapper-continue"
DLV_LISTEN="0.0.0.0:59333"
RKE2LAB_DLV="${RKE2LAB_DLV:-/srv/host/rke2lab-share.d/rke2lab-dlv.sh}"

usage() {
	cat <<'EOF'
Usage:
  rke2lab-shim-dlv list
  rke2lab-shim-dlv auto-attach
  rke2lab-shim-dlv attach <namespace> <id>
  rke2lab-shim-dlv resume <namespace> <id>
  rke2lab-shim-dlv detach

Commands:
  list        Print one row per containerd-shim-flox wrapper currently parked at the
              debug-suspend gate: pid, namespace, id, age, continue-file path.
  auto-attach Automatically attach to suspended wrapper: if only one, attach immediately;
              if multiple, show interactive menu to select which one.
  attach      Resolve the wrapper PID for <namespace>/<id> and start the singleton
              headless Delve attached to it (paused, listen 0.0.0.0:59333).
  resume      Touch the per-shim continue-file so the wrapper exits the gate and
              syscall.Exec()s the real shim.
  detach      Stop the singleton headless Delve.
EOF
}

sanitize_token() {
	local value="$1"
	local out=""
	local i char
	for ((i = 0; i < ${#value}; i++)); do
		char="${value:i:1}"
		case "$char" in
		[a-zA-Z0-9._-]) out+="$char" ;;
		*) out+="-" ;;
		esac
	done
	printf '%s' "$out"
}

continue_file_for() {
	local sanitized_ns sanitized_id
	sanitized_ns="$(sanitize_token "$1")"
	sanitized_id="$(sanitize_token "$2")"
	printf '%s-%s-%s' "$CONTINUE_FILE_PREFIX" "$sanitized_ns" "$sanitized_id"
}

read_cmdline() {
	local pid="$1"
	[[ -r "/proc/$pid/cmdline" ]] || return 1
	tr '\0' ' ' <"/proc/$pid/cmdline"
}

extract_flag() {
	local flag="$1"
	shift
	while [[ $# -gt 0 ]]; do
		if [[ "$1" == "$flag" && $# -ge 2 ]]; then
			printf '%s' "$2"
			return 0
		fi
		shift
	done
	return 1
}

each_wrapper_pid() {
	pgrep -f -- "$WRAPPER_BIN_NAME" 2>/dev/null || true
}

resolve_pid() {
	local target_ns="$1" target_id="$2"
	local pid cmdline ns id
	for pid in $(each_wrapper_pid); do
		cmdline="$(read_cmdline "$pid" || true)"
		[[ -n "$cmdline" ]] || continue
		[[ "$cmdline" == *"$WRAPPER_BIN_NAME"* ]] || continue
		# shellcheck disable=SC2086
		ns="$(extract_flag -namespace $cmdline || true)"
		# shellcheck disable=SC2086
		id="$(extract_flag -id $cmdline || true)"
		if [[ "$ns" == "$target_ns" && "$id" == "$target_id" ]]; then
			printf '%s' "$pid"
			return 0
		fi
	done
	return 1
}

collect_suspended_containers() {
	local pid cmdline ns id wait_file
	local -a containers=()
	for pid in $(each_wrapper_pid); do
		cmdline="$(read_cmdline "$pid" || true)"
		[[ -n "$cmdline" ]] || continue
		[[ "$cmdline" == *"$WRAPPER_BIN_NAME"* ]] || continue
		# shellcheck disable=SC2086
		ns="$(extract_flag -namespace $cmdline || true)"
		# shellcheck disable=SC2086
		id="$(extract_flag -id $cmdline || true)"
		[[ -n "$ns" && -n "$id" ]] || continue
		wait_file="$(continue_file_for "$ns" "$id")"
		# Only include wrappers actively parked at the gate
		if [[ ! -e "$wait_file" ]]; then
			containers+=("$ns|$id")
		fi
	done
	printf '%s\n' "${containers[@]}"
}

cmd_list() {
	local pid cmdline ns id wait_file age start_seconds now_seconds entries=""
	now_seconds="$(date +%s)"
	for pid in $(each_wrapper_pid); do
		cmdline="$(read_cmdline "$pid" || true)"
		[[ -n "$cmdline" ]] || continue
		[[ "$cmdline" == *"$WRAPPER_BIN_NAME"* ]] || continue
		# shellcheck disable=SC2086
		ns="$(extract_flag -namespace $cmdline || true)"
		# shellcheck disable=SC2086
		id="$(extract_flag -id $cmdline || true)"
		[[ -n "$ns" && -n "$id" ]] || continue
		wait_file="$(continue_file_for "$ns" "$id")"
		# Show only wrappers actively parked at the gate (waiting for the file).
		# Heuristic: wrapper exists but the continue-file does not yet exist —
		# means it's still polling. If continue-file already exists, it has
		# already been requested to resume.
		if [[ ! -e "$wait_file" ]]; then
			start_seconds="$(stat -c '%Y' "/proc/$pid" 2>/dev/null || echo "$now_seconds")"
			age="$((now_seconds - start_seconds))s"
			entries+="$(printf '  - pid: %s\n    namespace: %s\n    id: %s\n    age: %s\n    continueFile: %s\n' \
				"$pid" "$ns" "$id" "$age" "$wait_file")"
			entries+=$'\n'
		fi
	done
	if [[ -z "$entries" ]]; then
		printf 'suspended: []\n'
	else
		printf 'suspended:\n%s' "$entries"
	fi
}

cmd_auto_attach() {
	local -a containers
	mapfile -t containers < <(collect_suspended_containers)

	local count="${#containers[@]}"

	if [[ "$count" -eq 0 ]]; then
		echo "No suspended containers found" >&2
		echo "hint: containers must be started with flox.dev/debug-suspend annotation" >&2
		exit 1
	fi

	local selected_ns selected_id
	if [[ "$count" -eq 1 ]]; then
		# Only one container, auto-attach
		IFS='|' read -r selected_ns selected_id <<<"${containers[0]}"
		echo "Found 1 suspended container: $selected_ns/$selected_id"
		echo "Auto-attaching..."
	else
		# Multiple containers, show menu
		echo "Found $count suspended containers:"
		echo
		local i
		for i in "${!containers[@]}"; do
			IFS='|' read -r ns id <<<"${containers[$i]}"
			echo "  $((i + 1)). $ns/$id"
		done
		echo
		read -p "Select container (1-$count): " selection

		if ! [[ "$selection" =~ ^[0-9]+$ ]] || [[ "$selection" -lt 1 ]] || [[ "$selection" -gt "$count" ]]; then
			echo "Invalid selection" >&2
			exit 1
		fi

		IFS='|' read -r selected_ns selected_id <<<"${containers[$((selection - 1))]}"
	fi

	cmd_attach "$selected_ns" "$selected_id"
}

cmd_attach() {
	local ns="$1" id="$2" pid wait_file
	pid="$(resolve_pid "$ns" "$id" || true)"
	if [[ -z "$pid" ]]; then
		echo "no containerd-shim-flox-delve-v2 wrapper found for namespace=$ns id=$id" >&2
		echo "hint: run 'rke2lab-shim-dlv list' to see currently suspended wrappers" >&2
		exit 1
	fi
	wait_file="$(continue_file_for "$ns" "$id")"
	"$RKE2LAB_DLV" attach "$pid" "$DLV_LISTEN"
	cat <<EOF

Delve attached to $ns/$id (pid=$pid, listen=$DLV_LISTEN)

Next steps:
  1. From your workstation: connect your debugger client to this VM's IP on port 59333
     (e.g., VS Code launch config "Attach to Shim Wrapper Delve (remote)")
  2. Set breakpoints in wrapper-go (e.g., internal/wrapper/wrapper.go)
  3. Hit Continue in your debugger, then run:
       rke2lab-shim-dlv resume $ns $id
     to release the wrapper from the suspend gate

Tips:
  - Use 'rke2lab-shim-dlv auto-attach' for quick attach (auto-selects if only one suspended)
  - Use 'rke2lab-shim-dlv detach' to stop Delve when done
  - Use 'rke2lab-shim-dlv list' to see all suspended containers
EOF
}

cmd_resume() {
	local ns="$1" id="$2" wait_file
	wait_file="$(continue_file_for "$ns" "$id")"
	mkdir -p "$(dirname "$wait_file")"
	: >"$wait_file"
	echo "resume requested: $wait_file"
}

cmd_detach() {
	"$RKE2LAB_DLV" stop
}

main() {
	local command="${1:-}"
	case "$command" in
	list)
		cmd_list
		;;
	auto-attach)
		cmd_auto_attach
		;;
	attach)
		[[ $# -ge 3 ]] || {
			usage
			exit 1
		}
		cmd_attach "$2" "$3"
		;;
	resume)
		[[ $# -ge 3 ]] || {
			usage
			exit 1
		}
		cmd_resume "$2" "$3"
		;;
	detach)
		cmd_detach
		;;
	-h | --help | help | "")
		usage
		;;
	*)
		usage
		exit 1
		;;
	esac
}

main "$@"
