package com.cpurest.transport;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/**
 * Downcalls into the platform libc for POSIX shared memory: {@code shm_open},
 * {@code ftruncate}, {@code mmap}, {@code munmap}, {@code close},
 * {@code shm_unlink}. Never touches {@code /dev/shm} as a path — that only
 * happens to be where Linux's {@code shm_open} objects live (tmpfs); macOS
 * exposes no such path, so going through the syscalls directly is what
 * makes this portable, and it's exactly what the Rust side does via the
 * {@code libc} crate.
 */
final class Libc {
    private Libc() {}

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = LINKER.defaultLookup();

    private static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    // open(2)/shm_open(2) flags. Values genuinely differ between Linux and
    // Darwin; O_RDWR happens to agree (2) but O_CREAT does not.
    static final int O_RDWR = 0x0002;
    static final int O_CREAT = IS_MAC ? 0x0200 : 0100; // 0100 is octal (=64) on Linux/glibc.
    static final int SHM_MODE = 0666; // octal

    // mmap(2) flags — these do agree across Linux and Darwin.
    static final int PROT_READ = 0x1;
    static final int PROT_WRITE = 0x2;
    static final int MAP_SHARED = 0x0001;
    static final long MAP_FAILED_ADDR = -1L;

    // errno values — standard across Linux/Darwin/BSD for these two.
    static final int ENOENT = 2;
    static final int EINVAL = 22;

    private static final Linker.Option CAPTURE_ERRNO = Linker.Option.captureCallState("errno");
    private static final StructLayout CAPTURE_LAYOUT = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO_HANDLE =
            CAPTURE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("errno"));

    // POSIX declares shm_open as *variadic* — `shm_open(const char*, int, ...)`
    // — because `mode` is only meaningful with O_CREAT. Declaring the
    // downcall as an ordinary fixed-arity 3-parameter function (as every
    // shm_open FFM example we could find does) passes `mode` through the
    // wrong part of the platform ABI on arm64: the value that actually
    // lands in the kernel is garbage, silently producing a file with
    // near-random permission bits instead of the requested 0666. That
    // creator process still has a valid fd (open() grants the access mode
    // it asked for to its own fd regardless of the botched stored mode
    // bits), so it doesn't notice — but every *other* process's later
    // shm_open() gets a real, correct permission check against those
    // garbled bits and fails with EACCES. `firstVariadicArg(2)` tells the
    // linker mode is the variadic part, which fixes the ABI and was
    // confirmed by inspecting the resulting file's mode directly (0666
    // became something like 0340 without this; 0644 = 0666 & ~umask with
    // it). shm_open is the only call whose failure mode we also need to
    // disambiguate (ENOENT "not created yet, keep polling" vs. anything
    // else "give up now"), so it alone also captures errno.
    private static final MethodHandle SHM_OPEN = downcall(
            "shm_open",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
            Linker.Option.firstVariadicArg(2),
            CAPTURE_ERRNO);
    private static final MethodHandle SHM_UNLINK =
            downcall("shm_unlink", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle FTRUNCATE = downcall(
            "ftruncate",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
            CAPTURE_ERRNO);
    private static final MethodHandle MMAP = downcall(
            "mmap",
            FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG));
    private static final MethodHandle MUNMAP =
            downcall("munmap", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final MethodHandle CLOSE = downcall("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    private static MethodHandle downcall(String symbol, FunctionDescriptor descriptor, Linker.Option... options) {
        MemorySegment address = LOOKUP.find(symbol)
                .orElseThrow(() -> new UnsatisfiedLinkError("libc symbol not found: " + symbol));
        return LINKER.downcallHandle(address, descriptor, options);
    }

    record OpenResult(int fd, int errno) {}
    record CallResult(int rc, int errno) {}

    static OpenResult shmOpen(MemorySegment name, int flags, int mode) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capState = arena.allocate(CAPTURE_LAYOUT);
            int fd = (int) SHM_OPEN.invokeExact(capState, name, flags, mode);
            int errno = (fd < 0) ? (int) ERRNO_HANDLE.get(capState, 0L) : 0;
            return new OpenResult(fd, errno);
        } catch (Throwable t) {
            throw new RuntimeException("shm_open failed", t);
        }
    }

    static int shmUnlink(MemorySegment name) {
        try {
            return (int) SHM_UNLINK.invokeExact(name);
        } catch (Throwable t) {
            throw new RuntimeException("shm_unlink failed", t);
        }
    }

    static CallResult ftruncate(int fd, long length) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment capState = arena.allocate(CAPTURE_LAYOUT);
            int rc = (int) FTRUNCATE.invokeExact(capState, fd, length);
            int errno = (rc != 0) ? (int) ERRNO_HANDLE.get(capState, 0L) : 0;
            return new CallResult(rc, errno);
        } catch (Throwable t) {
            throw new RuntimeException("ftruncate failed", t);
        }
    }

    static MemorySegment mmap(long length, int prot, int flags, int fd, long offset) {
        try {
            return (MemorySegment) MMAP.invokeExact(MemorySegment.NULL, length, prot, flags, fd, offset);
        } catch (Throwable t) {
            throw new RuntimeException("mmap failed", t);
        }
    }

    static int munmap(MemorySegment addr, long length) {
        try {
            return (int) MUNMAP.invokeExact(addr, length);
        } catch (Throwable t) {
            throw new RuntimeException("munmap failed", t);
        }
    }

    static int close(int fd) {
        try {
            return (int) CLOSE.invokeExact(fd);
        } catch (Throwable t) {
            throw new RuntimeException("close failed", t);
        }
    }
}
