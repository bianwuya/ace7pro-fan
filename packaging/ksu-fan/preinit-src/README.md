# Independent KSU preinit bind helper (offline development component)

## Dependency and change boundary

Only new files in this directory and `build/kernel-replace-audit/preinit-helper/` are owned by this component. It does not modify the existing ZIP builder, compatibility/release schemas, Java, kernel policy algorithm, module scripts, or project instructions. There is no deployment, real mount test, driver unload/load, partition write, module-list change, or final ZIP here.

**The checked-in manifest is deliberately disabled. The compiled development helper is not a working fan driver or a completed KSU business deliverable.** Its `--apply` refuses with exit 21 before opening device paths. A separately enabled first-control candidate build now lives at `build/first-kernel-control/preinit/`: its immutable header/JSON bind the real 151040-byte candidate, unchanged stock vermagic, 89 CRC records, and exact supplied runtime uname. `candidatecontroltested=false`; no runtime report, deployment or real mounting was performed. Tests use temporary `.fixture` bytes or mocked metadata, never a fake deliverable `.ko`; all temporary synthetic headers/files are deleted after tests.

## Fixed authority and inputs

* Only target: `/vendor_dlkm/lib/modules/oplus_fan.ko`. `/vendor/lib/modules` is never followed as an alias. Every target path component is opened with `O_NOFOLLOW` while holding its parent FD.
* Pinned stock: 104888 bytes, SHA256 `a1abed95ae8ab56402f13efa39ddd3253da606fafe119cf347a9ed6155039ddd`.
* Replacement leaf: exactly `oplus_fan.replacement` beside the running executable, determined through `/proc/self/exe`, not cwd, `argv[0]`, shell variables, or a runtime path argument. No on-device config parser exists.
* Source and target must be root-owned regular files with one hard link, no group/world write permission, and distinct `(st_dev, st_ino)`. Maximum payload is 8 MiB. A symlink, FIFO, device, insecure file, path escape, oversized file, or source/target alias is refused.
* Both held FDs are re-read/re-statted before binding; SHA256, size, mtime/ctime, identity, mode and links are checked. FD-based source and destination paths are mandatory. Failure of `/proc/self/fd` support fails closed; there is no path-based mount fallback.
* ELF validation is bounded, little-endian ELF64 `ET_REL` / AArch64, with exact `name=oplus_fan`, unique vermagic, CRC records in `__versions`, nonzero u32 CRCs, unique terminated names and `module_layout`. If extended CRC/name sections exist, both must agree record-for-record; only zero padding is allowed afterward.
* The pinned stock sample independently establishes `__this_module` section size 1600 and its 56-byte module name field at offset 24. This target-specific layout is checked as well; this is intentionally not a generic loader for unrelated kernel ABIs.
* The immutable generated manifest pins the whole replacement hash, exact size, exact vermagic, `__versions` hash/count, exact runtime uname release and derived KMI. Runtime uname and the first vermagic token may differ on this observed MODVERSIONS target: stock/new full vermagic must remain byte-identical; its KMI must match runtime KMI and remaining flags must exactly equal pinned stock. The generator can emit the schema-compatible mirror with `--json-output`; its 72-byte C manifest ABI is unchanged. These are file-identity and ABI-structure checks, **not independent proof that every CRC matches the live kernel**, not signature verification, and not proof that OEM lookup/derate/FG/Hall/OCP/power paths are preserved. Kernel checks remain enabled. The main full-driver build/audit must establish semantic preservation and correct kernel symbol versions/signature.

## Vendor loader file context (r5)

A real r4 boot established a successful early bind but no loaded fan driver: the payload inherited `vold_metadata_file`, which the installed policy denies to `vendor_modprobe` for open/read/map and module_load. The stock inode uses `u:object_r:vendor_file:s0`, for which the same read-only policy query permits those operations. No AVC line was emitted; that absence is not evidence of access.

The apply-only preparation now first validates the replacement SHA/ELF/CRC through the held FD, sets only this pinned inode's `security.selinux` xattr to the exact stock context when needed, reads it back, and re-reads/revalidates every byte before recording the refreshed stat/ctime snapshot. Final pre-bind and post-bind verification require the same context. Label failure keeps stock; there are no policy edits, permissive mode, process identity changes, stock mutations or DAC permission widening. `--check-only` still performs no label or payload writes.

`label_probe.c` is a separate unshipped device regression probe using the exact production preparation routine on explicitly opened stock/payload FDs. It may label only the verified replacement; it never mounts, loads a module or writes stock. The new routine was verified on the actual next-boot metadata inode as well as a private build payload, with unchanged bytes and live stock driver. r5's next preinit execution and candidate driver load remain separate pending observations.

## Check versus apply

No arguments and `--check-only` are identical. They open/read only the fixed stock target, require its pinned hash/size/ELF metadata, and emit an explicit `...check-only...no-mount` result. They do not require KSU/preinit/root *caller* privileges and do not parse or apply a payload. Normal Android read permission/SELinux may still deny the read. This Android execution was not performed by this subtask.

`--apply` additionally requires all of:

1. A real enabled generated manifest (the default manifest refuses).
2. Literal `KSU_PREINIT=1` and `KSU=true`; real and effective UID both zero.
3. `CLOCK_BOOTTIME <= 10000 ms` as a stale-invocation ceiling; exact `uname.machine=aarch64`, configured full release and KMI match.
4. `/proc/self/ns/mnt` and `/proc/1/ns/mnt` have the same inode **and device**. Private namespaces are rejected, not entered or changed.
5. Complete bounded `/proc/modules` reading plus `/sys/module` availability, with no `oplus_fan` in either source.
6. Pinned stock/replacement validation, unchanged target path, accessible proc FDs, and no pre-existing file mount at the canonical target.

The environment/UID/boottime/namespace/unloaded gate is repeated immediately before the **single** bind, after read-only file rechecks. A real reboot exposed an incorrect assumption in the old 1200 ms cutoff: KSU invoked this helper at 1.416411 s, before vendor.modprobe started at 2.325284 s and fan probe at 4.850042 s, but the helper refused before checking module state. r4 therefore uses 10000 ms only as an additional stale-invocation ceiling, not as a predicted module-load deadline. Synchronous preinit ordering and both module inventories remain mandatory; PID1 proceeds to SELinux/second-stage after this action. Neither that ordering nor a second unloaded check is a lock against every possible unrelated parallel loader or privileged mount/content mutation. No claim of eliminating all concurrency is made. r4 was rebuilt, tested and installed; actual next-boot application still needs observation.

## Bind, verification and rollback

1. `mount(/proc/self/fd/SOURCE, /proc/self/fd/STOCK, NULL, MS_BIND, NULL)` exactly once.
2. Securely reopen the fixed canonical target; capture the **new overlay FD** and a new `/proc/self/fdinfo` mount ID, distinct from stock's ID. Require one exact canonical mountinfo entry, correct payload inode, and the same current target identity.
3. `mount(NULL, /proc/self/fd/OVERLAY, NULL, MS_REMOUNT|MS_BIND|MS_RDONLY, NULL)` exactly once. The original stock FD deliberately pins the old mount and must not be used for remount.
4. Recheck owned ID/current path, per-mount `ro` in mountinfo, `ST_RDONLY` via `fstatvfs`, and replacement size/hash/ELF/ABI metadata through the overlay FD.
5. On capture/remount/verification failure, unmount only a positively identified own new mount through its overlay FD, using `umount2(..., 0)`. No lazy/forced detach or canonical-path fallback. Then reopen the fixed target, require stock inode/mount ID and pinned stock hash again.
6. If the new mount cannot be identified, unmount fails, or restored stock cannot be verified, emit **`uncertain-mount`**, never a claimed restoration. A successful bind followed by inability to capture an owned overlay FD is necessarily uncertain; the helper will not guess which mount to remove.

No original file is moved, unlinked, truncated, copied over or opened for writing. A successful bind is not module loading and is not proof of replacing an already running driver. There is no retry/second bind, second remount, or second unmount in an invocation. Existing target mounts / non-stock targets refuse re-entry rather than stack another overlay. After a completed rollback there is no persistent retry marker: a separately invoked fresh process is a new attempt, and no module script that does so is supplied here.

## stdout / exit contract

Exactly one helper stdout line:

`fan-preinit result=STATE exit=CODE reason=TOKEN errno=NUMBER`

| Exit | Meaning |
|---|---|
| 0 | Check-only verified stock; `stock-retained`; no mount attempted. |
| **0** (`result=applied`) | **Applied successfully**, owned bind is verified readonly. Internal transaction outcome 10 maps to process/stdout exit 0 for KSU exec; `result` distinguishes it from check-only. |
| 20 | Unsupported arguments (`not-applied`). No target/path/config override exists. |
| 21 | No configured replacement (`not-applied`); default build. |
| 22 | Initial preinit/root/namespace/loaded/boot/KMI refusal. Before stock verification the state is `not-applied`; in a prepared transaction it is `stock-retained`. |
| 23 | Stock open/identity failure (`not-applied`, not a claim that stock was verified). |
| 24 | Malformed immutable manifest (`not-applied`). |
| 25 | Payload directory/read/identity/ELF/ABI refusal after stock verification. |
| 26 | Read-only pre-bind recheck / final unloaded-gate refusal. |
| 27 | Bind syscall failed; this process has not installed an overlay. |
| 28 | Post-bind failure; own mount unmounted **and pinned stock verified restored**. |
| **29** | **Uncertain mount state**, including failed rollback; do not claim restoration or retry blindly. |

For state-only refusals `errno=0` is used; callback errno is cleared before each operation to prevent reporting a stale unrelated error. Actual syscall failures preserve the relevant numeric errno.

## Offline build and future release integration

Run from this directory:

```text
PYTHONDONTWRITEBYTECODE=1 python3 build.py
```

Uses installed Clang 19, AArch64 glibc `libc.a`, Python standard library, `readelf`, and an already installed UBSan library. It installs nothing and works without `make` (not installed on the observed host). The optional Makefile provides the equivalent basic build/test/audit targets on hosts that have make. Outputs are scoped to `build/kernel-replace-audit/preinit-helper/`.

`generate_manifest.py --help` describes required `--stock`, `--replacement`, `--kernel-release`, `--kmi`, `--probe` and `--output` arguments. The generator is **offline only**, calls the local `elf-probe` over inherited held FDs (no shell), independently cross-checks C SHA256 with Python `hashlib`, rejects stock-as-replacement and mismatched stock/replacement/release vermagic, and exclusively creates a new header (does not overwrite an old identity). Missing real replacement creates no header. Its successful mocked-metadata serializer test is not a real release manifest.

After the main agent has a genuine full replacement and independent kernel/CRC/signature evidence, it can generate the immutable header and compile `fan_preinit.c sha256.c` with the same strict/static flags plus `-DFAN_MANIFEST_HEADER='"/absolute/generated-header.h"'`. The optional Makefile accepts `MANIFEST=/absolute/generated-header.h`; `build.py` intentionally builds only the disabled development artifact. The future KSU metadata exec reference must use the literal relative helper filename and literal `--apply`, without `$MODDIR` or variable expansion. Keep the real payload beside it with the exact fixed leaf name. No metadata, caller, module script or release-builder change is made here.

Static ELF validity (no `PT_INTERP`, no `DT_NEEDED`) is necessary but does not establish actual Android preinit execution, mount/remount/proc-FD semantics, boot timing, rollback on this kernel, driver compatibility, or physical fan behavior. Those remain main-agent acceptance work under separately appropriate authorization. In particular, do not use the development artifact as a final release.

## Test evidence summary

The final per-run counts, exact commands/stdout/exits, artifact hashes and untested scope are recorded in `build/kernel-replace-audit/preinit-helper/REPORT.md`, `build-summary.json`, `unit-tests.log`, `manifest-tests.log`, `ubsan-tests.log`, `readelf.log` and `cli-stdout-exitcodes.jsonl`. Unit mount operations are injected and temporary files live only beneath that build output directory. Coverage includes good/bad SHA, ELF damage/bounds/name/layout, missing/invalid/mismatched CRCs, path escape/symlink/hardlink/FIFO/size/mode/owner, missing preinit, late apply, loaded target, namespace mismatch, final loaded race, readonly double-check, bind/remount/readback failure, owned rollback, rollback failure/ownership loss, and no second write attempt. ASan was attempted separately but failed at allocator startup before tests; it is not counted as coverage. No Android helper execution, real bind, reboot, module load/unload, final ZIP or fake replacement is part of these results.
