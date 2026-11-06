//! Platform-independent frame geometry and region processing.
//!
//! Pixel ownership, decoding, model execution and GPU resources stay in adapters.
//! The C ABI is the only unsafe boundary; algorithms operate on checked slices.
#![doc = include_str!("../README.md")]

pub mod association;
pub mod association_score;
pub mod box_filter;
pub mod camera;
mod ffi;
pub mod geometry;
pub mod instances;
mod mask_transitions;
pub mod pixels;
pub mod protection;
pub mod region_continuation;
pub mod temporal_region;
pub mod yuv;

pub use ffi::*;

pub const ABI_VERSION: u32 = 1;
