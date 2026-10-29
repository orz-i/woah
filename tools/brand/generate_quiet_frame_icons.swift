// Woah (舞哇) Quiet Frame v1.1 icon renderer.
// Original identity geometry: the left half is mirrored around x=128.
// Requires macOS AppKit only; no third-party font, SVG parser or raster pack.
import Foundation
import AppKit

let ink = CGColor(red: 11/255.0, green: 20/255.0, blue: 21/255.0, alpha: 1)
let mint = CGColor(red: 120/255.0, green: 220/255.0, blue: 199/255.0, alpha: 1)
let pearl = CGColor(red: 242/255.0, green: 246/255.0, blue: 243/255.0, alpha: 1)

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

func png(width: Int, height: Int, markWidth: CGFloat, background: CGColor?, fill: CGColor) -> Data {
    let space = CGColorSpaceCreateDeviceRGB()
    guard let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                             bytesPerRow: 0, space: space,
                             bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
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
let jsonData = try! Data(contentsOf: URL(fileURLWithPath: iconSet + "/Contents.json"))
let iconContents = try! JSONSerialization.jsonObject(with: jsonData) as! [String: Any]
let images = iconContents["images"] as! [[String: Any]]
var made = Set<String>()
for entry in images {
    guard let filename = entry["filename"] as? String,
          let sizeLabel = entry["size"] as? String,
          let scaleLabel = entry["scale"] as? String,
          let logical = Double(sizeLabel.components(separatedBy: "x")[0]),
          let scale = Double(scaleLabel.dropLast()) else { continue }
    if !made.insert(filename).inserted { continue }
    let side = Int((logical * scale).rounded())
    write(iconSet + "/" + filename, png(width: side, height: side,
         markWidth: CGFloat(side) * 0.703125, background: ink, fill: mint))
}
// Fixed-size transparent symbol for runtime; no font dependency.
write(app + "/assets/brand/woah-symbol-256.png", png(width: 256, height: 160,
     markWidth: 256, background: nil, fill: mint))
// Unrounded, layered-source-compatible variants for future Icon Composer work.
write(app + "/assets/brand/woah-icon-default-1024.png", png(width: 1024, height: 1024,
     markWidth: 720, background: ink, fill: mint))
write(app + "/assets/brand/woah-icon-monochrome-1024.png", png(width: 1024, height: 1024,
     markWidth: 720, background: nil, fill: pearl))
// Static launch illustration; no forced pause, no loop, no font loading.
for factor in 1...3 {
    let prefix = factor == 1 ? "" : "@\(factor)x"
    write(assets + "/LaunchImage.imageset/LaunchImage\(prefix).png",
          png(width: 168 * factor, height: 105 * factor,
              markWidth: CGFloat(168 * factor) * 0.92, background: nil, fill: mint))
}
let densities: [(String, Int)] = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                                   ("xxhdpi", 144), ("xxxhdpi", 192)]
for (density, side) in densities {
    write(app + "/android/app/src/main/res/mipmap-\(density)/ic_launcher.png",
          png(width: side, height: side,
              markWidth: CGFloat(side) * 0.703125, background: ink, fill: mint))
}
