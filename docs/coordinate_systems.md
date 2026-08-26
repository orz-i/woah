# Render Coordinate Systems Documentation

## 1. Visual Coordinate
- **Origin**: Top-left (0.0, 0.0)
- **X-axis**: 0.0 (left) -> 1.0 (right)
- **Y-axis**: 0.0 (top) -> 1.0 (bottom)
- **Used by**:
  - Person bounding boxes (`FloatRect`, `NormalizedRect`)
  - Flutter UI overlay / normalized rects
  - SmoothFollower camera crop rects (`uCropRect`)
  - Sticker head anchors and bounding boxes (`uStickerRect`)
  - Leg stretch region boundaries (`uLegZoneTop`, `uLegZoneBottom`)
  - YOLO source-space detections and inference outputs
  - `NativeMask` buffer contents (Row 0 = visual top / person head)

## 2. OpenGL Quad / Screen Coordinate
- **Origin**: Bottom-left (0.0, 0.0) in standard texture/NDC screen space
- **Texture Base**: Bottom-left oriented
- **screenGlUv**: `aTexCoord.y = 0.0` (bottom / floor), `aTexCoord.y = 1.0` (top / ceiling)
- **Conversion to Visual Y**:
  $$\text{visualUv.y} = 1.0 - \text{screenGlUv.y}$$

## 3. Source Texture Coordinate
- **OES External Video Texture**:
  - Must pass through `SurfaceTexture.getTransformMatrix(stMatrix)` composed with display rotation matrix via `GlRenderer.computeTransformMatrix(stMatrix, rotation)`.
- **2D Bitmap Texture**:
  - Uses explicit bitmap Y-flip matrix (`bitmapTextureMatrix`: `y' = 1 - y`) so top-left Bitmap memory maps consistently onto the bottom-left OpenGL NDC rendering quad.

## 4. Mask Texture
- `NativeMask` `ByteBuffer` byte layout: Row 0 is the visual top (head).
- Therefore, Mask sampling in shaders must occur in Visual top-left semantic space:
  $$\text{vMaskTexCoord.y} = \text{mix}(uMaskCropRect.y, uMaskCropRect.w, 1.0 - \text{contentUv.y})$$
- OpenGL `glReadPixels` on FBO reads from row 0 at the bottom-left; `captureRenderedFrame()` applies a vertical post-scale `-1f` to yield the final top-left visual Bitmap.

> **Strong Architecture Constraint**:
> Never compare person bbox coordinates directly with transformed OES source texture coordinates.
