# Standalone build only. No dependency on, or edit to, the existing ZIP builder.
ROOT := $(abspath ../../..)
OUT ?= $(ROOT)/build/kernel-replace-audit/preinit-helper
CC := clang-19
WARN := -std=c11 -Wall -Wextra -Werror -Wpedantic -Wformat=2 -Wshadow -Wconversion
CFLAGS := $(WARN) -O2 -fstack-protector-strong -D_FORTIFY_SOURCE=2
# Override with the ABSOLUTE generated header path to compile a real release.
MANIFEST ?= $(CURDIR)/manifest.h
CPPFLAGS := -DFAN_MANIFEST_HEADER='"$(MANIFEST)"'
.PHONY: all test audit clean
all: $(OUT)/fan-preinit
$(OUT):
	mkdir -p "$@"
$(OUT)/fan-preinit: fan_preinit.c fan_preinit.h sha256.c sha256.h $(MANIFEST) | $(OUT)
	$(CC) $(CPPFLAGS) $(CFLAGS) -static -fno-pie -Wl,-no-pie -Wl,-z,noexecstack,-z,relro,-z,now -o "$@" fan_preinit.c sha256.c
$(OUT)/unit-tests: tests.c fan_preinit.c fan_preinit.h sha256.c sha256.h manifest.h | $(OUT)
	$(CC) $(CFLAGS) -o "$@" tests.c sha256.c
$(OUT)/elf-probe: elf_probe.c fan_preinit.c fan_preinit.h sha256.c sha256.h manifest.h | $(OUT)
	$(CC) $(CFLAGS) -o "$@" elf_probe.c sha256.c
test: $(OUT)/unit-tests $(OUT)/elf-probe $(OUT)/fan-preinit
	FAN_TEST_OUT="$(OUT)" "$(OUT)/unit-tests"
	PYTHONDONTWRITEBYTECODE=1 python3 test_manifest.py --out "$(OUT)"
audit: $(OUT)/fan-preinit
	readelf -h -l -d "$(OUT)/fan-preinit"
	! readelf -l "$(OUT)/fan-preinit" | grep -w INTERP
	! readelf -d "$(OUT)/fan-preinit" | grep NEEDED
# Explicitly scoped; not automatically invoked by another target.
clean:
	rm -f "$(OUT)/fan-preinit" "$(OUT)/unit-tests" "$(OUT)/elf-probe"
