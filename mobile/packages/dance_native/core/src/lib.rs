//! Platform-independent privacy decisions and geometry.
//!
//! Pixel ownership, decoding, model execution and GPU resources stay in adapters.
//! The C ABI is the only unsafe boundary; algorithms operate on checked slices.

mod ffi;
pub mod geometry;

pub use ffi::*;

pub const ABI_VERSION: u32 = 1;
