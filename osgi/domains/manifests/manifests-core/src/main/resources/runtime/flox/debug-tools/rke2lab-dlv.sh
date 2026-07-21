#!/usr/bin/env bash
set -euo pipefail

STATE_DIR="/run/rke2lab"
PID_FILE="${STATE_DIR}/dlv-singleton.pid"
STATE_FILE="${STATE_DIR}/dlv-singleton.state"
LOG_FILE="${STATE_DIR}/dlv-singleton.log"
DEFAULT_LISTEN="0.0.0.0:59333"

usage() {
    cat <<'EOF'
Usage:
	rke2lab-dlv attach <pid> [listen-address] [--continue|--no-continue]
	rke2lab-dlv restart
	rke2lab-dlv stop
	rke2lab-dlv status

Commands:
  attach  Start/restart a singleton headless Delve attached to target pid.
          Optional listen-address defaults to 0.0.0.0:59333.
					Defaults to paused target (no --continue) to allow breakpoints before run.
  restart Restart Delve using last attach state.
  stop    Stop current singleton Delve process.
  status  Show process status and saved attach state.
EOF
}

require_dlv() {
    command -v dlv >/dev/null 2>&1 || {
        echo "dlv is required" >&2
        exit 1
    }
}

is_running() {
    local pid="$1"
    [[ -n "$pid" ]] || return 1
    kill -0 "$pid" 2>/dev/null
}

read_pidfile() {
    [[ -r "$PID_FILE" ]] || return 1
    tr -d '[:space:]' <"$PID_FILE"
}

write_state_file() {
    local pid="$1"
    local listen="${2:-$DEFAULT_LISTEN}"
    local continue_mode="${3:-false}"

    [[ "$pid" =~ ^[0-9]+$ ]] || {
        echo "invalid pid: $pid" >&2
        exit 1
    }

    mkdir -p "$STATE_DIR"
    cat >"$STATE_FILE" <<EOF
TARGET_PID=$pid
LISTEN=$listen
CONTINUE=$continue_mode
EOF
}

read_state_file() {
    [[ -r "$STATE_FILE" ]] || {
        echo "no state file found: $STATE_FILE" >&2
        exit 1
    }
    # shellcheck disable=SC1090
    source "$STATE_FILE"
    [[ -n "${TARGET_PID:-}" ]] || {
        echo "state file missing TARGET_PID" >&2
        exit 1
    }
}

stop_running_if_any() {
    local running_pid
    running_pid="$(read_pidfile || true)"
    if [[ -n "$running_pid" ]] && is_running "$running_pid"; then
        kill "$running_pid"
        for _ in $(seq 1 20); do
            is_running "$running_pid" || break
            sleep 0.1
        done
        if is_running "$running_pid"; then
            kill -9 "$running_pid" 2>/dev/null || true
        fi
    fi
    rm -f "$PID_FILE"
}

start_attach() {
    local pid="$1"
    local listen="$2"
    local continue_mode="$3"
    local -a args

    args=(attach "$pid" --headless --api-version=2 --accept-multiclient --listen="$listen")
    if [[ "$continue_mode" == "true" ]]; then
        args+=(--continue)
    fi

    stop_running_if_any
    write_state_file "$pid" "$listen" "$continue_mode"

    nohup dlv "${args[@]}" >>"$LOG_FILE" 2>&1 &
    local dlv_pid=$!
    echo "$dlv_pid" >"$PID_FILE"
    sleep 0.1
    if ! is_running "$dlv_pid"; then
        echo "failed to start dlv; see $LOG_FILE" >&2
        exit 1
    fi

    echo "attached singleton Delve to pid=$pid listen=$listen continue=$continue_mode dlv_pid=$dlv_pid"
}

cmd_attach() {
    local pid="$1"
    local listen="${2:-$DEFAULT_LISTEN}"
    local continue_mode="${3:-false}"
    start_attach "$pid" "$listen" "$continue_mode"
}

cmd_restart() {
    read_state_file
    start_attach "${TARGET_PID}" "${LISTEN:-$DEFAULT_LISTEN}" "${CONTINUE:-false}"
}

cmd_stop() {
    stop_running_if_any
    echo "stopped singleton Delve"
}

cmd_status() {
    local running_pid
    running_pid="$(read_pidfile || true)"
    if [[ -n "$running_pid" ]] && is_running "$running_pid"; then
        echo "singleton Delve is running: pid=$running_pid"
    else
        echo "singleton Delve is not running"
    fi

    if [[ -r "$STATE_FILE" ]]; then
        echo
        echo "# $STATE_FILE"
        cat "$STATE_FILE"
    fi

    if [[ -r "$LOG_FILE" ]]; then
        echo
        echo "log: $LOG_FILE"
    fi
}

main() {
    require_dlv

    local command="${1:-}"
    case "$command" in
    attach)
        [[ $# -ge 2 ]] || {
            usage
            exit 1
        }
        local pid="$2"
        local listen="$DEFAULT_LISTEN"
        local continue_mode="false"
        if [[ $# -ge 3 ]]; then
            case "$3" in
            --continue)
                continue_mode="true"
                ;;
            --no-continue)
                continue_mode="false"
                ;;
            *)
                listen="$3"
                ;;
            esac
        fi
        if [[ $# -ge 4 ]]; then
            case "$4" in
            --continue)
                continue_mode="true"
                ;;
            --no-continue)
                continue_mode="false"
                ;;
            *)
                echo "invalid attach flag: $4" >&2
                exit 1
                ;;
            esac
        fi
        cmd_attach "$pid" "$listen" "$continue_mode"
        ;;
    restart)
        cmd_restart
        ;;
    stop)
        cmd_stop
        ;;
    status)
        cmd_status
        ;;
    *)
        usage
        exit 1
        ;;
    esac
}

main "$@"
