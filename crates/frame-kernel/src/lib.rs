//! Platform-independent frame geometry and region processing.
//!
//! Pixel ownership, decoding, model execution and GPU resources stay in adapters.
//! The C ABI is the only unsafe boundary; algorithms operate on checked slices.
#![doc = include_str!("../README.md")]

pub mod association;
pub mod association_score;
pub mod box_filter;
pub mod camera;
pub mod face_geometry;
pub mod face_localization;
pub mod face_observations;
mod ffi;
mod ffi_class;
mod ffi_face;
mod ffi_face_geometry;
mod ffi_face_observations;
mod ffi_tracking;
pub mod geometry;
pub mod grid_face;
pub mod instances;
mod mask_transitions;
pub mod pixels;
pub mod privacy_class;
pub mod protection;
pub mod region_continuation;
pub mod temporal_region;
pub mod tracking;
pub mod tracking_evidence;
pub mod tracking_groups;
pub mod tracking_lifecycle;
pub mod tracking_mask;
pub mod tracking_motion;
pub mod tracking_replay;
mod tracking_update;
mod tracking_update_groups;
mod tracking_update_misses;
pub mod work_lifecycle;
pub mod yuv;

pub use ffi::*;
pub use ffi_class::*;
pub use ffi_face::*;
pub use ffi_face_geometry::*;
pub use ffi_face_observations::*;
pub use ffi_tracking::*;

pub const ABI_VERSION: u32 = 1;
