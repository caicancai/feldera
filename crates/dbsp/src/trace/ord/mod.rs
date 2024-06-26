pub mod fallback;
pub mod file;
pub mod merge_batcher;
pub mod vec;

pub use fallback::{
    indexed_wset::{FallbackIndexedWSet, FallbackIndexedWSetFactories},
    key_batch::{FallbackKeyBatch, FallbackKeyBatchFactories},
    val_batch::{FallbackValBatch, FallbackValBatchFactories},
    wset::{FallbackWSet, FallbackWSetFactories},
};
pub use file::{
    FileIndexedWSet, FileIndexedWSetFactories, FileKeyBatch, FileKeyBatchFactories, FileValBatch,
    FileValBatchFactories, FileWSet, FileWSetFactories,
};
pub use vec::{
    VecIndexedWSet as OrdIndexedWSet, VecIndexedWSetFactories as OrdIndexedWSetFactories,
    VecKeyBatch as OrdKeyBatch, VecKeyBatchFactories as OrdKeyBatchFactories,
    VecValBatch as OrdValBatch, VecValBatchFactories as OrdValBatchFactories, VecWSet as OrdWSet,
    VecWSetFactories as OrdWSetFactories,
};

use super::Filter;

fn filter<T>(f: &Option<Filter<T>>, t: &T) -> bool
where
    T: ?Sized,
{
    f.as_ref().map_or(true, |f| f(t))
}
