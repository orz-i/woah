// Woah (舞哇) Quiet Frame v1.1 icon renderer.
// Original identity geometry: the left half is mirrored around x=128.
// Requires macOS AppKit only; no third-party font, SVG parser or raster pack.
import Foundation
import AppKit

let ink = CGColor(red: 11/255.0, green: 20/255.0, blue: 21/255.0, alpha: 1)
let mint = CGColor(red: 120/255.0, green: 220/255.0, blue: 199/255.0, alpha: 1)
let pearl = CGColor(red: 242/255.0, green: 246/255.0, blue: 243/255.0, alpha: 1)
let deepMint = CGColor(red: 33/255.0, green: 106/255.0, blue: 94/255.0, alpha: 1)

// DeviceRGB encodes slightly different pixel values on some macOS profiles.
// Single Size app icons and themed Android fallbacks must use literal sRGB
// token values so branded backgrounds match their native resource palettes.
let sRGB = CGColorSpace(name: CGColorSpace.sRGB)!
func brandedRGB(_ red: CGFloat, _ green: CGFloat, _ blue: CGFloat) -> CGColor {
    CGColor(colorSpace: sRGB,
            components: [red / 255, green / 255, blue / 255, 1])!
}
let iconLightBG = brandedRGB(242, 246, 243)
let iconLightFG = brandedRGB(33, 106, 94)
let iconDarkBG = brandedRGB(11, 20, 21)
let iconDarkFG = brandedRGB(120, 220, 199)
let iconTintBG = brandedRGB(16, 16, 16)
let iconTintFG = brandedRGB(250, 250, 250)

func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }
func leftHalf() -> CGPath {
    let p = CGMutablePath()
    p.move(to: point(16, 8))
    p.addLine(to: point(48, 8))
    p.addQuadCurve(to: point(64, 24), control: point(64, 8))
    p.addLine(to: point(64, 111))
    p.addQuadCurve(to: point(76, 124), control: point(64, 119))
    p.addLine(to: point(76, 65))
    p.addQuadCurve(to: point(89, 56), control: point(76, 51))
    p.addLine(to: point(111, 64))
    p.addQuadCurve(to: point(120, 78), control: point(120, 67))
    p.addLine(to: point(120, 113))
    p.addQuadCurve(to: point(111, 127), control: point(120, 122))
    p.addLine(to: point(70, 148))
    p.addQuadCurve(to: point(21, 151), control: point(47, 160))
    p.addQuadCurve(to: point(0, 124), control: point(0, 145))
    p.addLine(to: point(0, 24))
    p.addQuadCurve(to: point(16, 8), control: point(0, 8))
    p.closeSubpath()
    return p
}

func png(width: Int, height: Int, markWidth: CGFloat,
         background: CGColor?, fill: CGColor, opaque: Bool = false) -> Data {
    let space = opaque ? sRGB : CGColorSpaceCreateDeviceRGB()
    guard let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                             bytesPerRow: 0, space: space,
                             bitmapInfo: (opaque ? CGImageAlphaInfo.noneSkipLast
                                                 : CGImageAlphaInfo.premultipliedLast).rawValue) else {
        fatalError("Cannot create bitmap CGContext")
    }
    ctx.setAllowsAntialiasing(true)
    ctx.setShouldAntialias(true)
    if let bg = background {
        ctx.setFillColor(bg)
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
    }
    // CoreGraphics is bottom-up; normalize into the original top-down SVG frame.
    ctx.translateBy(x: 0, y: CGFloat(height))
    ctx.scaleBy(x: 1, y: -1)
    let markHeight = markWidth * 160 / 256
    ctx.translateBy(x: (CGFloat(width) - markWidth) / 2,
                    y: (CGFloat(height) - markHeight) / 2)
    ctx.scaleBy(x: markWidth / 256, y: markWidth / 256)
    ctx.setFillColor(fill)
    let left = leftHalf()
    ctx.addPath(left)
    ctx.fillPath()
    ctx.saveGState()
    ctx.translateBy(x: 256, y: 0)
    ctx.scaleBy(x: -1, y: 1)
    ctx.addPath(left)
    ctx.fillPath()
    ctx.restoreGState()
    guard let cgImage = ctx.makeImage(),
          let data = NSBitmapImageRep(cgImage: cgImage).representation(using: .png, properties: [:]) else {
        fatalError("Cannot export PNG")
    }
    return data
}

func write(_ path: String, _ image: Data) {
    let url = URL(fileURLWithPath: path)
    try! FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try! image.write(to: url)
    print("ICON ✓ \(path) \(image.count) bytes")
}

let root = FileManager.default.currentDirectoryPath
let app = root + "/mobile/app"
let assets = app + "/ios/Runner/Assets.xcassets"
let iconSet = assets + "/AppIcon.appiconset"
// The iOS 17-compatible Single Size catalog provides three native appearances.
// Do not register an .icon bundle of the same name in the Xcode project: Icon
// Composer replaces this catalog and may change the legacy iOS appearance.
let jsonData = try! Data(contentsOf: URL(fileURLWithPath: iconSet + "/Contents.json"))
let contents = try! JSONSerialization.jsonObject(with: jsonData) as! [String: Any]
let images = contents["images"] as! [[String: Any]]
precondition(images.count == 3, "AppIcon needs Any / Dark / Tinted")
for entry in images {
    guard let filename = entry["filename"] as? String else {
        fatalError("An AppIcon variant has no filename")
    }
    precondition(entry["size"] as? String == "1024x1024")
    precondition(entry["idiom"] as? String == "universal")
    let appearances = entry["appearances"] as? [[String: String]] ?? []
    let appearance = appearances.first?["value"] ?? "any"
    let colors: (background: CGColor, foreground: CGColor)
    switch appearance {
    case "any":
        colors = (iconLightBG, iconLightFG)
    case "dark":
        colors = (iconDarkBG, iconDarkFG)
    case "tinted":
        // Grayscale pixels, no alpha channel. iOS owns the final user tint.
        colors = (iconTintBG, iconTintFG)
    default:
        fatalError("Unsupported AppIcon appearance: \(appearance)")
    }
    write(iconSet + "/" + filename,
          png(width: 1024, height: 1024, markWidth: 720,
              background: colors.background, fill: colors.foreground,
              opaque: true))
}
// Fixed-size transparent symbol for runtime; no font dependency.
write(app + "/assets/brand/woah-symbol-256.png", png(width: 256, height: 160,
     markWidth: 256, background: nil, fill: mint))
// Unrounded, layered-source-compatible variants for future Icon Composer work.
write(app + "/assets/brand/woah-icon-default-1024.png", png(width: 1024, height: 1024,
     markWidth: 720, background: ink, fill: mint))
write(app + "/assets/brand/woah-icon-monochrome-1024.png", png(width: 1024, height: 1024,
     markWidth: 720, background: nil, fill: pearl))
write(app + "/assets/brand/woah-icon-light-1024.png", png(width: 1024, height: 1024,
     markWidth: 720, background: iconLightBG, fill: iconLightFG, opaque: true))
write(app + "/assets/brand/woah-icon-dark-1024.png", png(width: 1024, height: 1024,
     markWidth: 720, background: iconDarkBG, fill: iconDarkFG, opaque: true))
write(app + "/assets/brand/woah-icon-tinted-1024.png", png(width: 1024, height: 1024,
     markWidth: 720, background: iconTintBG, fill: iconTintFG,
     opaque: true))
// Platform launch appearance: deep mint on Pearl, soft Mint on Ink.
// Assets are flat/static; there is no launch animation or extra startup wait.
for factor in 1...3 {
    let prefix = factor == 1 ? "" : "@\(factor)x"
    write(assets + "/LaunchImage.imageset/LaunchImage\(prefix).png",
          png(width: 168 * factor, height: 105 * factor,
              markWidth: CGFloat(168 * factor) * 0.92,
              background: nil, fill: deepMint))
    write(assets + "/LaunchImage.imageset/LaunchImage_dark\(prefix).png",
          png(width: 168 * factor, height: 105 * factor,
              markWidth: CGFloat(168 * factor) * 0.92,
              background: nil, fill: mint))
}
let densities: [(String, Int)] = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                                   ("xxhdpi", 144), ("xxxhdpi", 192)]
for (density, side) in densities {
    // Android 7 / legacy launcher PNGs are also configuration-qualified.
    // Android 8+ resolves adaptive layers, and Android 13+ can theme monochrome.
    write(app + "/android/app/src/main/res/mipmap-\(density)/ic_launcher.png",
          png(width: side, height: side,
              markWidth: CGFloat(side) * 0.703125,
              background: iconLightBG, fill: iconLightFG, opaque: true))
    write(app + "/android/app/src/main/res/mipmap-night-\(density)/ic_launcher.png",
          png(width: side, height: side,
              markWidth: CGFloat(side) * 0.703125,
              background: iconDarkBG, fill: iconDarkFG, opaque: true))
}
