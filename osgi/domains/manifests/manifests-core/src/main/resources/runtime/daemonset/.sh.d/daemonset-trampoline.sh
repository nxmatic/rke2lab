#!/usr/bin/env bash

# shellcheck shell=bash

# Generic daemonset trampoline policy for scripts that may start in a pod or guest context
# but need to re-exec on the host.
#
# Forward contract: the trampoline propagates only the two variables the host
# child needs to re-derive everything else via daemonset::runtime:paths:bind:
#
#   DAEMONSET_EXEC_MODE=host
#   DAEMONSET_SCRIPT_ROOT=<host base root>     (e.g. /srv/host/k8s-daemonset.d)
#
# Plus a PATH augmented with the host bin dir for ergonomics. Anything else
# the caller supplies via positional VAR=value pairs is forwarded verbatim.
# The host child must source daemonset-runtime.sh and call paths:bind to
# reconstitute the full DAEMONSET_HOST_SCRIPT_* set.
#
# Required inputs at call site:
# - DAEMONSET_EXEC_MODE=host|guest|pod
# - DAEMONSET_HOST_SCRIPT_ROOT=/srv/host/... (asset root, used to resolve the
#   host command path)
# - DAEMONSET_SCRIPT_ROOT=<host base root> (forwarded to the child)
# - DAEMONSET_HOST_SSH_TARGET=<ssh target> (required only for guest->host re-exec)

daemonset::trampoline:mode:resolve() {
    local mode="${DAEMONSET_EXEC_MODE:-guest}"

    case "${mode}" in
    host | guest | pod)
        printf '%s\n' "${mode}"
        return 0
        ;;
    *)
        echo "unsupported daemonset execution mode: ${mode}" >&2
        return 1
        ;;
    esac
}

daemonset::trampoline:host_script_root:resolve() {
    local root="${DAEMONSET_HOST_SCRIPT_ROOT:-}"
    [[ -n "${root}" ]] || {
        echo "DAEMONSET_HOST_SCRIPT_ROOT is required for daemonset trampoline re-exec" >&2
        return 1
    }
    printf '%s\n' "${root}"
}

daemonset::trampoline:host_script_bin:path() {
    local host_script_root host_script_bin

    host_script_root="$(daemonset::trampoline:host_script_root:resolve)" || return 1
    host_script_bin="${DAEMONSET_HOST_SCRIPT_BIN:-${host_script_root%/}/bin}"
    printf '%s\n' "${host_script_bin}"
}

daemonset::trampoline:host_script_bin:resolve() {
    local host_script_bin

    host_script_bin="$(daemonset::trampoline:host_script_bin:path)" || return 1
    [[ -d "${host_script_bin}" ]] || {
        echo "daemonset host script bin directory not found: ${host_script_bin}" >&2
        return 1
    }

    printf '%s\n' "${host_script_bin}"
}

daemonset::trampoline:host_command_path:path() {
    local script_name="$1"
    local host_script_bin

    host_script_bin="$(daemonset::trampoline:host_script_bin:path)" || return 1
    printf '%s\n' "${host_script_bin%/}/${script_name}"
}

daemonset::trampoline:host_command_path() {
    local script_name="$1"
    local host_script_bin host_command_path

    host_script_bin="$(daemonset::trampoline:host_script_bin:resolve)" || return 1
    host_command_path="${host_script_bin%/}/${script_name}"
    [[ -x "${host_command_path}" ]] || {
        echo "daemonset host command not found or not executable: ${host_command_path}" >&2
        return 1
    }

    printf '%s\n' "${host_command_path}"
}

daemonset::trampoline:exec_on_host() {
    local script_name="$1"
    shift

    local mode host_script_bin host_command_path ssh_target remote_command env_pair arg
    local -a env_pairs=()
    local -a script_args=()

    mode="$(daemonset::trampoline:mode:resolve)" || return 1

    while [[ $# -gt 0 && "$1" == *=* ]]; do
        env_pairs+=("$1")
        shift
    done
    script_args=("$@")

    case "${mode}" in
    host)
        host_script_bin="$(daemonset::trampoline:host_script_bin:resolve)" || return 1
        host_command_path="$(daemonset::trampoline:host_command_path "${script_name}")" || return 1
        echo "daemonset host trampoline should not be used when already on host" >&2
        return 1
        ;;
    pod)
        command -v nsenter >/dev/null 2>&1 || {
            echo "nsenter is required for daemonset pod->host trampoline" >&2
            return 1
        }
        host_script_bin="$(daemonset::trampoline:host_script_bin:path)" || return 1
        host_command_path="$(daemonset::trampoline:host_command_path:path "${script_name}")" || return 1
        [[ -n "${DAEMONSET_SCRIPT_ROOT:-}" ]] || {
            echo "DAEMONSET_SCRIPT_ROOT is required for daemonset pod->host trampoline" >&2
            return 1
        }
        exec nsenter --target 1 --mount --uts --ipc --net --pid -- env \
            DAEMONSET_EXEC_MODE=host \
            DAEMONSET_SCRIPT_ROOT="${DAEMONSET_SCRIPT_ROOT}" \
            PATH="${host_script_bin}:${PATH}" \
            "${env_pairs[@]}" \
            bash -x "${host_command_path}" "${script_args[@]}"
        ;;
    guest)
        ssh_target="${DAEMONSET_HOST_SSH_TARGET:-}"
        [[ -n "${ssh_target}" ]] || {
            echo "DAEMONSET_HOST_SSH_TARGET is required for daemonset guest->host trampoline" >&2
            return 1
        }
        host_script_bin="$(daemonset::trampoline:host_script_bin:path)" || return 1
        host_command_path="$(daemonset::trampoline:host_command_path:path "${script_name}")" || return 1
        [[ -n "${DAEMONSET_SCRIPT_ROOT:-}" ]] || {
            echo "DAEMONSET_SCRIPT_ROOT is required for daemonset guest->host trampoline" >&2
            return 1
        }

        remote_command="env"
        remote_command+=" $(printf '%q' 'DAEMONSET_EXEC_MODE=host')"
        remote_command+=" $(printf '%q' "DAEMONSET_SCRIPT_ROOT=${DAEMONSET_SCRIPT_ROOT}")"
        remote_command+=" $(printf '%q' "PATH=${host_script_bin}:${PATH}")"
        for env_pair in "${env_pairs[@]}"; do
            remote_command+=" $(printf '%q' "${env_pair}")"
        done
        remote_command+=" bash -x $(printf '%q' "${host_command_path}")"
        for arg in "${script_args[@]}"; do
            remote_command+=" $(printf '%q' "${arg}")"
        done

        exec ssh -n "${ssh_target}" -- "${remote_command}"
        ;;
    esac
}
