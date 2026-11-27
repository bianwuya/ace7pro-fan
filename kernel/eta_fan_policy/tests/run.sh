#!/bin/sh
# Pure userspace + conditional-header smoke tests. NEVER accesses a device,
# installs dependencies, builds a module, creates an archive or changes sources.
# Each invocation keeps a fresh output directory; no prior result is replaced.
set -eu
umask 077

sanitize=auto
case "${1:-}" in
    '') ;;
    --sanitize|--sanitize=on) sanitize=on; shift ;;
    --no-sanitize|--sanitize=off) sanitize=off; shift ;;
    --sanitize=auto) shift ;;
    *) printf 'Usage: %s [--sanitize|--no-sanitize|--sanitize=auto]\n' "$0" >&2; exit 2 ;;
esac
if [ "$#" -ne 0 ]; then
    printf 'Unexpected additional arguments\n' >&2
    exit 2
fi
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
core_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
project_dir=$(CDPATH= cd -- "$script_dir/../../.." && pwd)
out_root="$project_dir/build/kernel-policy-core"
mkdir -p -- "$out_root"
out=$(mktemp -d "$out_root/run.XXXXXXXX")
log="$out/test.log"
: > "$log"

record() {
    printf '%s\n' "$*" >> "$log"
}
run_logged() {
    printf '\n$' >> "$log"
    for argument do printf ' [%s]' "$argument" >> "$log"; done
    printf '\n' >> "$log"
    if "$@" >> "$log" 2>&1; then
        :
    else
        result=$?
        record "FAIL command exit=$result"
        cat "$log"
        printf 'Preserved failure log: %s\n' "$log" >&2
        exit "$result"
    fi
}

if [ -n "${CC:-}" ]; then
    compiler=$CC
elif command -v cc >/dev/null 2>&1; then
    compiler=cc
elif command -v clang >/dev/null 2>&1; then
    compiler=clang
else
    record 'FAIL: no existing cc/clang; no dependencies installed'
    cat "$log"
    exit 1
fi
if ! command -v "$compiler" >/dev/null 2>&1; then
    record "FAIL: CC must name one installed compiler executable: $compiler"
    cat "$log"
    exit 1
fi
record 'Scope: pure integer policy + normal request ramp; OFFLINE USERSPACE ONLY'
record 'Not a kernel build/load test, device test, ABI check or performance A/B.'
record "Compiler: $compiler; sanitizer: $sanitize"
run_logged "$compiler" --version
# Deliberately unquoted fixed flag words; no caller-controlled flag evaluation.
flags='-std=c11 -O2 -Wall -Wextra -Werror -Wpedantic -Wconversion -Wshadow -Wstrict-prototypes -Wmissing-prototypes -Wvla -fno-common'
run_logged "$compiler" $flags -ffreestanding -fno-builtin -I"$core_dir" \
    -c "$core_dir/policy_core.c" -o "$out/policy_core.freestanding.o"
# Installed Linux UAPI types exercise __KERNEL__ conditional inclusion only.
# This is explicitly NOT compilation against the target kernel's build tree.
run_logged "$compiler" $flags -D__KERNEL__ -ffreestanding -fno-builtin -I"$core_dir" \
    -c "$core_dir/policy_core.c" -o "$out/policy_core.kernel-header-smoke.o"
record 'PASS __KERNEL__ conditional-header/freestanding smoke (host headers, NOT Kbuild)'
run_logged "$compiler" $flags -I"$core_dir" "$core_dir/policy_core.c" \
    "$script_dir/test_policy_core.c" -o "$out/test_policy_core"
run_logged "$out/test_policy_core"

if [ "$sanitize" != off ]; then
    # A negative control must REALLY detect overflow, not merely link a stub.
    printf '#include <limits.h>\nint main(void) { volatile int x = INT_MAX; return x + 1; }\n' \
        > "$out/sanitize_probe.c"
    available=no
    san_link=''
    for linkage in default installed-libubsan; do
        if [ "$linkage" = installed-libubsan ]; then
            # Existing GCC libubsan can serve Clang when its own runtime is
            # absent. This is only accepted after the negative control passes.
            san_link='-fno-sanitize-link-runtime -lubsan'
        fi
        record "Probe UBSan linkage=$linkage; extra flags=$san_link"
        if "$compiler" -std=c11 -fsanitize=undefined -fno-sanitize-recover=all \
            "$out/sanitize_probe.c" -o "$out/sanitize_probe.$linkage" $san_link \
            >> "$log" 2>&1; then
            if env LC_ALL=C UBSAN_OPTIONS=halt_on_error=1 \
                "$out/sanitize_probe.$linkage" > "$out/probe.$linkage.log" 2>&1; then
                record "REJECT UBSan $linkage: intentional overflow was not trapped"
            elif grep -q 'runtime error: signed integer overflow' "$out/probe.$linkage.log"; then
                cat "$out/probe.$linkage.log" >> "$log"
                record "PASS UBSan negative control ($linkage)"
                available=yes
                break
            else
                cat "$out/probe.$linkage.log" >> "$log"
                record "REJECT UBSan $linkage: failure was not a sanitizer diagnostic"
            fi
        fi
    done
    if [ "$available" = yes ]; then
        run_logged "$compiler" $flags -O1 -g -fsanitize=undefined \
            -fno-sanitize-recover=all -fno-omit-frame-pointer -I"$core_dir" \
            "$core_dir/policy_core.c" "$script_dir/test_policy_core.c" \
            -o "$out/test_policy_core.ubsan" $san_link
        run_logged env UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
            "$out/test_policy_core.ubsan"
        record 'PASS UBSan (undefined behavior checks, halt on first error)'
    elif [ "$sanitize" = on ]; then
        record 'FAIL requested UBSan unavailable; installed no dependencies'
        cat "$log"
        exit 1
    else
        record 'SKIP UBSan: selected compiler/runtime probe unavailable'
    fi
else
    record 'SKIP UBSan: explicitly disabled'
fi
if command -v nm >/dev/null 2>&1; then
    run_logged nm -u "$out/policy_core.freestanding.o"
fi
run_logged sha256sum "$core_dir/policy_core.h" "$core_dir/policy_core.c" \
    "$script_dir/test_policy_core.c" "$script_dir/run.sh"
record "PASS completed; preserved output: $out"
cat "$log"
