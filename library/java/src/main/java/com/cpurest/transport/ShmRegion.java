package com.cpurest.transport;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A named POSIX shared memory region mapped into this process. Whichever
 * side calls {@link #create} owns the segment and unlinks it on {@link #close()};
 * the peer calls {@link #openExisting}. Mirrors {@code cpurest-core::shm::ShmRegion}.
 */
public final class ShmRegion implements AutoCloseable {
    private final MemorySegment segment;
    private final MemorySegment nameSeg;
    private final Arena nameArena;
    private final Arena mappingArena;
    private final int fd;
    private final boolean owner;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private ShmRegion(MemorySegment segment, MemorySegment nameSeg, Arena nameArena, Arena mappingArena, int fd, boolean owner) {
        this.segment = segment;
        this.nameSeg = nameSeg;
        this.nameArena = nameArena;
        this.mappingArena = mappingArena;
        this.fd = fd;
        this.owner = owner;
    }

    /**
     * Create (or reuse) the named segment, size it, zero it, and map it.
     *
     * <p>Deliberately does <em>not</em> {@code shm_unlink} a stale object
     * before creating — it isn't needed. The zero-fill below is what
     * actually matters for correctness (it clears any wedged flag left by a
     * crashed prior run); see {@link Libc}'s {@code SHM_OPEN} field javadoc
     * for the real bug that once made this path look unlink-sensitive (it
     * wasn't — it was a variadic-call ABI bug that garbled the stored file
     * mode on every {@code O_CREAT} call, cross-process EACCES included).
     * Matches {@code cpurest-core::shm::ShmRegion::create}.
     */
    public static ShmRegion create(String bus, long length) {
        Arena nameArena = Arena.ofShared();
        MemorySegment nameSeg = nameArena.allocateFrom(HeaderLayout.shmName(bus));

        Libc.OpenResult opened = Libc.shmOpen(nameSeg, Libc.O_CREAT | Libc.O_RDWR, Libc.SHM_MODE);
        int fd = opened.fd();
        if (fd < 0) {
            nameArena.close();
            throw new ShmException("shm_open(O_CREAT) failed for bus " + bus + " (errno=" + opened.errno() + ")");
        }
        Libc.CallResult truncated = Libc.ftruncate(fd, length);
        // EINVAL here almost always means the object was reused from a
        // prior run that already sized it (macOS rejects ftruncate on an
        // already-sized POSIX shm object) — not a real failure, since our
        // bus size is a fixed constant. Anything else is a real problem.
        if (truncated.rc() != 0 && truncated.errno() != Libc.EINVAL) {
            Libc.close(fd);
            nameArena.close();
            throw new ShmException("ftruncate failed for bus " + bus + " (errno=" + truncated.errno() + ")");
        }
        Mapped mapped = map(fd, length, nameArena);
        // Zero the whole region: a leftover segment from a crashed prior run
        // could have a stale non-IDLE flag, which would wedge both sides
        // forever waiting on a state that will never arrive.
        mapped.segment().fill((byte) 0);

        return new ShmRegion(mapped.segment(), nameSeg, nameArena, mapped.arena(), fd, true);
    }

    /** Open a segment created by the peer, retrying until it appears or {@code timeout} elapses. */
    public static ShmRegion openExisting(String bus, long length, Duration timeout) {
        Arena nameArena = Arena.ofShared();
        MemorySegment nameSeg = nameArena.allocateFrom(HeaderLayout.shmName(bus));
        Instant deadline = Instant.now().plus(timeout);

        while (true) {
            Libc.OpenResult opened = Libc.shmOpen(nameSeg, Libc.O_RDWR, 0);
            if (opened.fd() >= 0) {
                Mapped mapped = map(opened.fd(), length, nameArena);
                return new ShmRegion(mapped.segment(), nameSeg, nameArena, mapped.arena(), opened.fd(), false);
            }
            if (opened.errno() != Libc.ENOENT) {
                nameArena.close();
                throw new ShmException("shm_open failed for bus " + bus + " (errno=" + opened.errno() + ")");
            }
            if (Instant.now().isAfter(deadline)) {
                nameArena.close();
                throw new ShmException(
                        "bus " + bus + " did not appear within " + timeout + " (is the server running?)");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                nameArena.close();
                throw new ShmException("interrupted while waiting for bus " + bus, e);
            }
        }
    }

    private static Mapped map(int fd, long length, Arena nameArena) {
        MemorySegment addr = Libc.mmap(length, Libc.PROT_READ | Libc.PROT_WRITE, Libc.MAP_SHARED, fd, 0);
        if (addr.address() == Libc.MAP_FAILED_ADDR) {
            Libc.close(fd);
            nameArena.close();
            throw new ShmException("mmap failed");
        }
        Arena mappingArena = Arena.ofShared();
        MemorySegment reinterpreted = addr.reinterpret(length, mappingArena, seg -> Libc.munmap(seg, length));
        return new Mapped(reinterpreted, mappingArena);
    }

    private record Mapped(MemorySegment segment, Arena arena) {}

    public MemorySegment segment() {
        return segment;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        mappingArena.close(); // triggers munmap via the cleanup action registered in map()
        Libc.close(fd);
        if (owner) {
            Libc.shmUnlink(nameSeg);
        }
        nameArena.close();
    }

    public static final class ShmException extends RuntimeException {
        public ShmException(String message) {
            super(message);
        }

        public ShmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
