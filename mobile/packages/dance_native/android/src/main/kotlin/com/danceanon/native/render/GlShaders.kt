package com.danceanon.native.render

object GlShaders {

    val VERTEX_SHADER = """attribute vec4 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uTexMatrix;
uniform vec4 uCropRect;
uniform vec4 uMaskCropRect;
varying vec2 vOesTexCoord;
varying vec2 vMaskTexCoord;

void main() {
    gl_Position = aPosition;
    vec2 screenUv = aTexCoord;
    vec2 contentUv = vec2(
        mix(uCropRect.x, uCropRect.z, screenUv.x),
        mix(uCropRect.y, uCropRect.w, screenUv.y)
    );
    vec4 transformed = uTexMatrix * vec4(contentUv, 0.0, 1.0);
    vOesTexCoord = transformed.xy;
    vMaskTexCoord = vec2(
        mix(uMaskCropRect.x, uMaskCropRect.z, contentUv.x),
        mix(uMaskCropRect.y, uMaskCropRect.w, 1.0 - contentUv.y)
    );
}""".trimIndent()

    private const val SHADER_BODY = """
varying vec2 vOesTexCoord;
varying vec2 vMaskTexCoord;

uniform sampler2D uMaskTexture;
uniform sampler2D uStickerTexture;

uniform int uHasMask;
uniform int uFillMode; // 0: none/solid, 1: outline, 2: blur, 3: gradient, 4: skin_whiten, 5: mosaic

uniform vec4 uFillColor;
uniform vec4 uBorderColor;
uniform vec4 uGradientColor;
uniform float uOpacity;
uniform float uBorderWidth;
uniform float uBlurRadius;
uniform float uSkinWhiten;

uniform int uLegStretchEnabled;
uniform float uLegStretch;
uniform float uLegZoneTop;
uniform float uLegZoneBottom;

uniform int uHasSticker;
uniform vec4 uStickerRect; // (left, top, right, bottom)
uniform vec2 uTexelSize;

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
    vec2 uv = vOesTexCoord;
    vec2 maskUv = vMaskTexCoord;

    // 1. Leg stretch non-linear dual coordinate warp
    if (uLegStretchEnabled == 1 && uLegStretch > 1.0) {
        if (uv.y >= uLegZoneTop && uv.y <= uLegZoneBottom) {
            float range = max(0.01, uLegZoneBottom - uLegZoneTop);
            float t = (uv.y - uLegZoneTop) / range;
            uv.y = uLegZoneTop + (t / uLegStretch) * range;
        }
    }

    vec4 color = texture2D(uBaseTexture, uv);

    if (uHasMask == 0) {
        gl_FragColor = color;
        return;
    }

    float maskVal = texture2D(uMaskTexture, maskUv).r;

    // 2. Skin whiten effect
    if (uSkinWhiten > 0.01 && maskVal > 0.1) {
        vec3 hsv = rgb2hsv(color.rgb);
        if (hsv.x >= 0.02 && hsv.x <= 0.18) {
            hsv.z = min(1.0, hsv.z + 0.25 * uSkinWhiten * maskVal);
            hsv.y = max(0.0, hsv.y - 0.15 * uSkinWhiten * maskVal);
            color = vec4(hsv2rgb(hsv), color.a);
        }
    }

    // 3. Body Anonymization / Fill Effect (Solid / Gradient / Blur / Mosaic)
    if (maskVal > 0.05) {
        vec4 effectColor = color;
        if (uFillMode == 0) { // Solid
            effectColor = vec4(uFillColor.rgb, 1.0);
        } else if (uFillMode == 2) { // Blur
            vec4 sum = vec4(0.0);
            float count = 0.0;
            float r = max(2.0, uBlurRadius * 2.0);
            for (float dx = -2.0; dx <= 2.0; dx += 1.0) {
                for (float dy = -2.0; dy <= 2.0; dy += 1.0) {
                    sum += texture2D(uBaseTexture, uv + vec2(dx * r * uTexelSize.x, dy * r * uTexelSize.y));
                    count += 1.0;
                }
            }
            effectColor = sum / count;
        } else if (uFillMode == 3) { // Gradient
            effectColor = mix(uFillColor, uGradientColor, maskUv.y);
        } else if (uFillMode == 5) { // Mosaic / Pixelate
            float blockSize = max(4.0, uBlurRadius * 4.0);
            vec2 blockUv = vec2(
                floor(uv.x / (blockSize * uTexelSize.x)) * (blockSize * uTexelSize.x),
                floor(uv.y / (blockSize * uTexelSize.y)) * (blockSize * uTexelSize.y)
            );
            effectColor = texture2D(uBaseTexture, blockUv);
        }

        float blendAlpha = uOpacity * maskVal;
        color = mix(color, effectColor, blendAlpha);
    }

    // 4. Real Outward Outline Expansion
    if (uBorderWidth > 0.1 && uBorderColor.a > 0.01) {
        float r = uBorderWidth;
        float m0 = texture2D(uMaskTexture, maskUv + vec2(0.0, r * uTexelSize.y)).r;
        float m1 = texture2D(uMaskTexture, maskUv - vec2(0.0, r * uTexelSize.y)).r;
        float m2 = texture2D(uMaskTexture, maskUv + vec2(r * uTexelSize.x, 0.0)).r;
        float m3 = texture2D(uMaskTexture, maskUv - vec2(r * uTexelSize.x, 0.0)).r;
        float m4 = texture2D(uMaskTexture, maskUv + vec2(0.707 * r * uTexelSize.x, 0.707 * r * uTexelSize.y)).r;
        float m5 = texture2D(uMaskTexture, maskUv + vec2(-0.707 * r * uTexelSize.x, 0.707 * r * uTexelSize.y)).r;
        float m6 = texture2D(uMaskTexture, maskUv + vec2(0.707 * r * uTexelSize.x, -0.707 * r * uTexelSize.y)).r;
        float m7 = texture2D(uMaskTexture, maskUv + vec2(-0.707 * r * uTexelSize.x, -0.707 * r * uTexelSize.y)).r;

        float maxNeighbor = max(max(max(m0, m1), max(m2, m3)), max(max(m4, m5), max(m6, m7)));
        float outline = clamp(maxNeighbor - maskVal, 0.0, 1.0);
        if (outline > 0.05) {
            color = mix(color, vec4(uBorderColor.rgb, 1.0), outline * uBorderColor.a);
        }
    }

    // 5. Sticker Overlay
    if (uHasSticker == 1) {
        if (uv.x >= uStickerRect.x && uv.x <= uStickerRect.z &&
            uv.y >= uStickerRect.y && uv.y <= uStickerRect.w) {
            vec2 stickerUv = vec2(
                (uv.x - uStickerRect.x) / (uStickerRect.z - uStickerRect.x),
                (uv.y - uStickerRect.y) / (uStickerRect.w - uStickerRect.y)
            );
            vec4 stickerColor = texture2D(uStickerTexture, stickerUv);
            color = mix(color, vec4(stickerColor.rgb, 1.0), stickerColor.a);
        }
    }

    gl_FragColor = color;
}
"""

    val FRAGMENT_SHADER_OES = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uBaseTexture;
$SHADER_BODY
""".trimIndent()

    val FRAGMENT_SHADER_2D = """
precision mediump float;
uniform sampler2D uBaseTexture;
$SHADER_BODY
""".trimIndent()

    // Backward compatibility
    val FRAGMENT_SHADER = FRAGMENT_SHADER_OES
}
