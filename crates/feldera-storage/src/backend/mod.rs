//! Storage backend APIs for Feldera.
//!
//! This file provides the traits that need to be implemented by a storage
//! backend. The traits are split into three parts:
//! - [`StorageControl`]: for creating and deleting files.
//! - [`StorageWrite`]: for writing data to files.
//! - [`StorageRead`]: for reading data from files.
//!
//! A file transitions from being created to being written to, to being read
//! to (eventually) deleted.
//! The API prevents writing to a file again that is completed/sealed.
//! The API also prevents reading from a file that is not completed.
#![allow(async_fn_in_trait)]

use std::rc::Rc;
use thiserror::Error;

use crate::buffer_cache::FBuf;

#[cfg(feature = "glommio")]
pub mod glommio_impl;
pub mod monoio_impl;

#[cfg(test)]
pub(crate) mod tests;

/// A file-descriptor we can write to.
pub struct FileHandle(i64);
#[cfg(test)]
impl FileHandle {
    /// Creating arbitrary file-handles is only necessary for testing, and
    /// dangerous otherwise. Use the StorageControl API instead.
    pub(crate) fn new(fd: i64) -> Self {
        Self(fd)
    }
}

impl From<&FileHandle> for i64 {
    fn from(fd: &FileHandle) -> Self {
        fd.0
    }
}

/// A file-descriptor we can read or prefetch from.
pub struct ImmutableFileHandle(i64);

#[cfg(test)]
impl ImmutableFileHandle {
    /// Creating arbitrary file-handles is only necessary for testing, and
    /// dangerous otherwise. Use the StorageControl API instead.
    pub(crate) fn new(fd: i64) -> Self {
        Self(fd)
    }
}

impl From<&ImmutableFileHandle> for i64 {
    fn from(fd: &ImmutableFileHandle) -> Self {
        fd.0
    }
}

/// An error that can occur when using the storage backend.
#[derive(Error, Debug)]
pub enum StorageError {
    #[cfg(feature = "glommio")]
    #[error("Got IO error during glommio operation")]
    Io(#[from] glommio::GlommioError<()>),
    #[error("Got IO error during monoio operation")]
    StdIo(#[from] std::io::Error),
    #[error("The range to be written overlaps with a previous write")]
    OverlappingWrites,
    #[error("The read would have returned less data than requested.")]
    ShortRead,
}

#[cfg(test)]
/// Implementation of PartialEq for StorageError.
///
/// This is for testing only and therefore intentionally not a complete
/// implementation.
impl PartialEq for StorageError {
    fn eq(&self, other: &Self) -> bool {
        match (self, other) {
            (Self::OverlappingWrites, Self::OverlappingWrites) => true,
            (Self::ShortRead, Self::ShortRead) => true,
            _ => false,
        }
    }
}

#[cfg(test)]
impl Eq for StorageError {}

/// A trait for a storage backend to implement so client can create/delete
/// files.
pub trait StorageControl {
    /// Creates a new persistent file used for writing data.
    ///
    /// Returns a file-descriptor that can be used for writing data.
    /// Note that it is not possible to read from this file until
    /// [`StorageWrite::complete`] is called and the [`FileHandle`] is
    /// converted to an [`ImmutableFileHandle`].
    async fn create(&self) -> Result<FileHandle, StorageError>;

    /// Deletes a previously completed file.
    ///
    /// This removes the file from the storage backend and makes it unavailable
    /// for reading.
    async fn delete(&self, fd: ImmutableFileHandle) -> Result<(), StorageError>;

    /// Deletes a previously created file.
    ///
    /// This removes the file from the storage backend and makes it unavailable
    /// for writing.
    ///
    /// Use [`delete`] for deleting a file that has been completed.
    async fn delete_mut(&self, fd: FileHandle) -> Result<(), StorageError>;
}

/// A trait for a storage backend to implement so clients can write to files.
pub trait StorageWrite {
    /// Allocates a buffer suitable for writing to a file using Direct I/O over
    /// `io_uring`.
    fn allocate_buffer(sz: usize) -> FBuf {
        FBuf::with_capacity(sz)
    }

    /// Writes a block of data to a file.
    ///
    /// ## Arguments
    /// - `fd` is the file-handle to write to.
    /// - `offset` is the offset in the file to write to.
    /// - `data` is the data to write.
    ///
    /// ## Preconditions
    /// - `offset.is_power_of_two()`
    /// - `data.len() >= 512 && data.len().is_power_of_two()`
    ///
    /// ## Returns
    /// A reference to the (now cached) buffer.
    ///
    /// API returns an error if any of the above preconditions are not met.
    async fn write_block(
        &self,
        fd: &FileHandle,
        offset: u64,
        data: FBuf,
    ) -> Result<Rc<FBuf>, StorageError>;

    /// Completes writing of a file.
    ///
    /// This makes the file available for reading by returning a file-descriptor
    /// that can be used for reading data.
    ///
    /// ## Arguments
    /// - `fd` is the file-handle to complete.
    ///
    /// ## Returns
    /// A file-descriptor that can be used for reading data. See also
    /// [`StorageRead`].
    async fn complete(&self, fd: FileHandle) -> Result<ImmutableFileHandle, StorageError>;
}

pub trait StorageRead {
    /// Prefetches a block of data from a file.
    ///
    /// This is an asynchronous operation that will be completed in the
    /// background. The data is likely available for reading from DRAM once the
    /// prefetch operation has completed.
    /// The implementation is free to choose how to prefetch the data. This may
    /// not be implemented for all storage backends.
    ///
    /// ## Arguments
    /// - `fd` is the file-handle to prefetch from.
    /// - `offset` is the offset in the file to prefetch from.
    /// - `size` is the size of the block to prefetch.
    ///
    /// ## Pre-conditions
    /// - `offset.is_power_of_two()`
    /// - `size >= 512 && size.is_power_of_two()`
    async fn prefetch(&self, fd: &ImmutableFileHandle, offset: u64, size: usize);

    /// Reads a block of data from a file.
    ///
    /// ## Arguments
    /// - `fd` is the file-handle to read from.
    /// - `offset` is the offset in the file to read from.
    /// - `size` is the size of the block to read.
    ///
    /// ## Pre-conditions
    /// - `offset.is_power_of_two()`
    /// - `size >= 512 && size.is_power_of_two()`
    ///
    /// ## Post-conditions
    /// - `result.len() == size`: In case we read less than the required size,
    ///   we return [`StorageError::ShortRead`], as opposed to a partial result.
    ///
    /// API returns an error if any of the above pre/post-conditions are not
    /// met.
    ///
    /// ## Returns
    /// A [`FBuf`] containing the data read from the file.
    async fn read_block(
        &self,
        fd: &ImmutableFileHandle,
        offset: u64,
        size: usize,
    ) -> Result<Rc<FBuf>, StorageError>;
}