//! POSIX shared memory region, opened via `shm_open`/`mmap` — never via a
//! literal `/dev/shm` path. On Linux that path happens to be where
//! `shm_open` objects live (tmpfs); on macOS it doesn't exist at all, so
//! going through the syscalls directly is what makes this portable.

use std::ffi::CString;
use std::io;
use std::os::fd::RawFd;
use std::ptr;
use std::time::{Duration, Instant};

/// A named POSIX shared memory region mapped into this process's address
/// space. Whichever side calls [`ShmRegion::create`] owns the segment and
/// unlinks it on drop; the peer calls [`ShmRegion::open_existing`].
pub struct ShmRegion {
    ptr: *mut u8,
    len: usize,
    name: CString,
    fd: RawFd,
    owner: bool,
}

// SAFETY: the mapped region is used as a synchronized mailbox (state flag
// gates all reads/writes to the rest of the header+payload), so it's sound
// to move the handle across threads.
unsafe impl Send for ShmRegion {}
unsafe impl Sync for ShmRegion {}

/// FNV-1a 32-bit, over the UTF-8 bytes of the trimmed bus name. Must stay
/// byte-for-byte identical to `HeaderLayout`'s Java implementation — it's
/// how a Rust server and a Java client (or vice versa) agree on the same
/// underlying `shm_open` name for a given bus name.
fn fnv1a32(bytes: &[u8]) -> u32 {
    let mut hash: u32 = 0x811c9dc5;
    for &b in bytes {
        hash ^= b as u32;
        hash = hash.wrapping_mul(0x0100_0193);
    }
    hash
}

/// Derive the actual POSIX shm object name for a bus name.
///
/// Not used verbatim: macOS's `shm_open` rejects names longer than ~31
/// bytes total (`PSHMNAMLEN`), so this keeps a short human-readable slug
/// (first 10 alphanumeric characters) plus an 8-hex-digit FNV-1a hash of
/// the full trimmed name for uniqueness — well under the limit on every
/// platform, and deterministic so both sides derive the same name.
fn shm_name(bus: &str) -> io::Result<CString> {
    let trimmed = bus.trim_start_matches('/');
    if trimmed.is_empty() || trimmed.contains('/') {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("invalid bus name: {bus:?}"),
        ));
    }
    let slug: String = trimmed.chars().filter(|c| c.is_ascii_alphanumeric()).take(10).collect();
    let hash = fnv1a32(trimmed.as_bytes());
    CString::new(format!("/cr_{slug}_{hash:08x}"))
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))
}

impl ShmRegion {
    /// Create (or reuse) the named segment, size it to `len` bytes, zero
    /// it, and map it. The caller becomes the owner.
    ///
    /// Deliberately does *not* `shm_unlink` a stale object before creating
    /// — it isn't needed. The zero-fill below is what actually matters for
    /// correctness (it clears any wedged flag left by a crashed prior run).
    /// (An earlier version of this comment blamed a "Darwin unlink quirk"
    /// for a cross-process `EACCES` — that was a misdiagnosis. The real
    /// cause lived on the Java side: `shm_open` is POSIX-variadic, and an
    /// FFM downcall that doesn't declare it as such garbles the `mode`
    /// argument on macOS/arm64. `libc::shm_open` here is unaffected — the
    /// `libc` crate declares it as truly variadic, so Rust already gets
    /// this right.)
    pub fn create(bus: &str, len: usize) -> io::Result<Self> {
        let name = shm_name(bus)?;
        // SAFETY: FFI call with a valid, NUL-terminated name; flags/mode are constants.
        let fd = unsafe { libc::shm_open(name.as_ptr(), libc::O_CREAT | libc::O_RDWR, 0o666) };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        // Only ftruncate if the object isn't already the right size: on
        // macOS, calling ftruncate on a POSIX shm object that's already
        // sized (e.g. reused from a prior run that was never shm_unlink'd)
        // fails with EINVAL — this isn't the "fresh creation" case
        // ftruncate is for, and POSIX leaves re-sizing an existing shm
        // object's behavior implementation-defined anyway.
        let current_size = unsafe {
            let mut stat: libc::stat = std::mem::zeroed();
            if libc::fstat(fd, &mut stat) != 0 {
                let err = io::Error::last_os_error();
                libc::close(fd);
                return Err(err);
            }
            stat.st_size as usize
        };
        if current_size != len {
            // SAFETY: fd is a valid, just-opened shm fd.
            if unsafe { libc::ftruncate(fd, len as libc::off_t) } != 0 {
                let err = io::Error::last_os_error();
                unsafe { libc::close(fd) };
                return Err(err);
            }
        }
        let ptr = Self::map(fd, len)?;
        // Zero the whole region: a leftover segment from a crashed prior
        // run could have a stale non-IDLE flag, which would wedge both
        // sides forever waiting on state that will never arrive.
        unsafe { ptr::write_bytes(ptr, 0, len) };
        Ok(Self {
            ptr,
            len,
            name,
            fd,
            owner: true,
        })
    }

    /// Open a segment created by the peer, retrying until it appears (the
    /// peer process may not have started yet) or `timeout` elapses. Any
    /// failure other than "doesn't exist yet" (e.g. a permission error)
    /// fails immediately rather than retrying until the timeout, so a real
    /// problem doesn't masquerade as "the server never started".
    pub fn open_existing(bus: &str, len: usize, timeout: Duration) -> io::Result<Self> {
        let name = shm_name(bus)?;
        let deadline = Instant::now() + timeout;
        loop {
            // SAFETY: FFI call with a valid, NUL-terminated name.
            let fd = unsafe { libc::shm_open(name.as_ptr(), libc::O_RDWR, 0o666) };
            if fd >= 0 {
                let ptr = Self::map(fd, len)?;
                return Ok(Self {
                    ptr,
                    len,
                    name,
                    fd,
                    owner: false,
                });
            }
            let err = io::Error::last_os_error();
            if err.kind() != io::ErrorKind::NotFound {
                return Err(io::Error::new(
                    err.kind(),
                    format!("shm_open failed for bus {bus:?}: {err}"),
                ));
            }
            if Instant::now() >= deadline {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    format!("bus {bus:?} did not appear within {timeout:?} (is the server running?)"),
                ));
            }
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    fn map(fd: RawFd, len: usize) -> io::Result<*mut u8> {
        // SAFETY: fd is a valid shm fd already sized to at least `len`.
        let ptr = unsafe {
            libc::mmap(
                ptr::null_mut(),
                len,
                libc::PROT_READ | libc::PROT_WRITE,
                libc::MAP_SHARED,
                fd,
                0,
            )
        };
        if ptr == libc::MAP_FAILED {
            let err = io::Error::last_os_error();
            unsafe { libc::close(fd) };
            return Err(err);
        }
        Ok(ptr as *mut u8)
    }

    #[inline]
    pub fn as_ptr(&self) -> *mut u8 {
        self.ptr
    }

    #[inline]
    pub fn len(&self) -> usize {
        self.len
    }
}

impl Drop for ShmRegion {
    fn drop(&mut self) {
        unsafe {
            libc::munmap(self.ptr as *mut libc::c_void, self.len);
            libc::close(self.fd);
            if self.owner {
                libc::shm_unlink(self.name.as_ptr());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shm_name_is_short_and_deterministic() {
        let long_bus = "/cpurest_test_roundtrip_this_is_a_very_long_bus_name_12345";
        let a = shm_name(long_bus).unwrap();
        let b = shm_name(long_bus).unwrap();
        assert_eq!(a, b, "same bus name must map to the same shm name every time");
        // macOS's PSHMNAMLEN is 31 bytes including the NUL terminator.
        assert!(a.as_bytes().len() < 31, "shm name {a:?} exceeds macOS's shm_open limit");
    }

    #[test]
    fn different_bus_names_map_to_different_shm_names() {
        let a = shm_name("/tax_engine").unwrap();
        let b = shm_name("/java_backend").unwrap();
        assert_ne!(a, b);
    }
}
