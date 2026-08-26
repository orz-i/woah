package com.danceanon.native.render

object GlShaders {

    val VERTEX_SHADER = """attribute vec4 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uTexMatrix;
uniform vec4 uCropRect;
varying vec2 vOesTexCoord;
varying vec2 vMaskTexCoord;

void main() {
    gl_Position = aPosition;
    vec2 cropped = vec2(
        mix(uCropRect.x, uCropRect.z, aTexCoord.x),
        mix(uCropRect.y, uCropRect.w, aTexCoord.y)
    );
    vec4 transformed = uTexMatrix * vec4(cropped, 0.0, 1.0);
    vOesTexCoord = transformed.xy;
    vMaskTexCoord = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
}""".trimIndent()

    val FRAGMENT_SHADER = """#extension GL_OES_EGL_image_external : require
precision mediump float;

varying vec2 vOesTexCoord;
varying vec2 vMaskTexCoord;

uniform samplerExternalOES uBaseTexture;
uniform sampler2D uMaskTexture;
uniform int uHasMask;
uniform int uEffectType; // 0: solid, 1: outline, 2: blur, 3: gradient, 4: skin_whiten, 5: leg_stretch

uniform vec4 uFillColor;
uniform vec4 uOutlineColor;
uniform vec4 uGradientColor;
uniform float uOpacity;
uniform float uOutlineWidth;
uniform float uBlurRadius;
uniform float uSkinWhitenStrength;
uniform float uLegStretchRatio;
uniform float uFootY;
uniform vec2 uTexelSize;

// RGB to HSV conversion
vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

// HSV to RGB conversion
vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
    vec2 uv = vOesTexCoord;
    vec2 maskUv = vMaskTexCoord;

    // 1. Leg stretch UV warping
    if (uHasMask == 1 && uEffectType == 5 && uLegStretchRatio > 1.0) {
        float waistY = uFootY - 0.45;
        if (maskUv.y > waistY && maskUv.y <= uFootY) {
            float t = (maskUv.y - waistY) / (uFootY - waistY);
            maskUv.y = waistY + t / uLegStretchRatio * (uFootY - waistY);
        }
    }

    vec4 baseColor = texture2D(uBaseTexture, uv);
    if (uHasMask == 0) {
        gl_FragColor = baseColor;
        return;
    }

    float maskVal = texture2D(uMaskTexture, maskUv).r;

    // 2. Solid Color Fill
    if (uEffectType == 0) {
        if (maskVal > 0.3) {
            float alpha = uOpacity * (uFillColor.a > 0.01 ? uFillColor.a : 1.0);
            gl_FragColor = mix(baseColor, vec4(uFillColor.rgb, 1.0), alpha);
            return;
        }
    }
    // 3. Outline Glow
    else if (uEffectType == 1) {
        float mUp    = texture2D(uMaskTexture, maskUv + vec2(0.0, uOutlineWidth * uTexelSize.y)).r;
        float mDown  = texture2D(uMaskTexture, maskUv - vec2(0.0, uOutlineWidth * uTexelSize.y)).r;
        float mLeft  = texture2D(uMaskTexture, maskUv - vec2(uOutlineWidth * uTexelSize.x, 0.0)).r;
        float mRight = texture2D(uMaskTexture, maskUv + vec2(uOutlineWidth * uTexelSize.x, 0.0)).r;
        
        float edge = max(max(abs(maskVal - mUp), abs(maskVal - mDown)), max(abs(maskVal - mLeft), abs(maskVal - mRight)));
        if (edge > 0.2) {
            float outAlpha = uOpacity * (uOutlineColor.a > 0.01 ? uOutlineColor.a : 1.0);
            gl_FragColor = mix(baseColor, vec4(uOutlineColor.rgb, 1.0), outAlpha);
            return;
        } else if (maskVal > 0.3) {
            float alpha = uOpacity * (uFillColor.a > 0.01 ? uFillColor.a : 1.0);
            gl_FragColor = mix(baseColor, vec4(uFillColor.rgb, 1.0), alpha);
            return;
        }
    }
    // 4. Blur / Mosaic
    else if (uEffectType == 2) {
        if (maskVal > 0.3) {
            vec4 sum = vec4(0.0);
            float count = 0.0;
            float r = max(3.0, uBlurRadius * 2.0);
            for (float dx = -3.0; dx <= 3.0; dx += 1.5) {
                for (float dy = -3.0; dy <= 3.0; dy += 1.5) {
                    sum += texture2D(uBaseTexture, uv + vec2(dx * r * uTexelSize.x, dy * r * uTexelSize.y));
                    count += 1.0;
                }
            }
            vec4 blurred = sum / count;
            gl_FragColor = mix(baseColor, blurred, uOpacity);
            return;
        }
    }
    // 5. Gradient Color
    else if (uEffectType == 3) {
        if (maskVal > 0.3) {
            vec4 gradColor = mix(uFillColor, uGradientColor, maskUv.y);
            float alpha = uOpacity * (gradColor.a > 0.01 ? gradColor.a : 1.0);
            gl_FragColor = mix(baseColor, vec4(gradColor.rgb, 1.0), alpha);
            return;
        }
    }
    // 6. Skin whiten
    else if (uEffectType == 4) {
        if (maskVal > 0.3) {
            vec3 hsv = rgb2hsv(baseColor.rgb);
            if (hsv.x >= 0.02 && hsv.x <= 0.18) {
                hsv.z = min(1.0, hsv.z + 0.20 * uSkinWhitenStrength);
                hsv.y = max(0.0, hsv.y - 0.10 * uSkinWhitenStrength);
            }
            vec3 whitenedRgb = hsv2rgb(hsv);
            gl_FragColor = vec4(mix(baseColor.rgb, whitenedRgb, uOpacity), baseColor.a);
            return;
        }
    }

    gl_FragColor = baseColor;
}""".trimIndent()
}
