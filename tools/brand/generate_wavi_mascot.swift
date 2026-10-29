// Woah · Wavi mascot v1: original CoreGraphics vector silhouettes.
// A small rhythm spirit with an open curling crest, pearl face and soft limbs.
// These images are decorative IP illustrations, never protection indicators.
// Re-run from repository root with: swift tools/brand/generate_wavi_mascot.swift
import AppKit
import Foundation
import CoreGraphics

private let mint = NSColor(srgbRed: 120 / 255, green: 220 / 255, blue: 199 / 255, alpha: 1).cgColor
private let bright = NSColor(srgbRed: 169 / 255, green: 243 / 255, blue: 220 / 255, alpha: 1).cgColor
private let deep = NSColor(srgbRed: 33 / 255, green: 106 / 255, blue: 94 / 255, alpha: 1).cgColor
private let ink = NSColor(srgbRed: 11 / 255, green: 20 / 255, blue: 21 / 255, alpha: 1).cgColor
private let pearl = NSColor(srgbRed: 242 / 255, green: 246 / 255, blue: 243 / 255, alpha: 1).cgColor

private func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }

private enum Pose: String, CaseIterable {
    case idle, peek, joy, wave
    var tilt: CGFloat {
        switch self { case .peek: -0.075; case .joy: 0.07; case .wave: -0.04; default: 0 }
    }
    var leftHand: CGPoint {
        switch self { case .idle: pt(94, 189); case .peek: pt(97, 151); case .joy: pt(82, 169); case .wave: pt(102, 227) }
    }
    var rightHand: CGPoint {
        switch self { case .idle: pt(314, 194); case .peek: pt(314, 153); case .joy: pt(326, 164); case .wave: pt(313, 125) }
    }
    var leftFoot: CGPoint {
        switch self { case .joy: pt(145, 332); case .wave: pt(165, 337); default: pt(171, 341) }
    }
    var rightFoot: CGPoint {
        switch self { case .joy: pt(259, 319); case .peek: pt(251, 340); default: pt(244, 341) }
    }
}

private func pathBody() -> CGPath {
    let p = CGMutablePath()
    p.move(to: pt(139, 183))
    p.addCurve(to: pt(182, 138), control1: pt(142, 151), control2: pt(158, 136))
    p.addCurve(to: pt(226, 139), control1: pt(199, 116), control2: pt(215, 131))
    p.addCurve(to: pt(285, 189), control1: pt(255, 126), control2: pt(284, 147))
    p.addCurve(to: pt(274, 265), control1: pt(292, 222), control2: pt(289, 242))
    p.addCurve(to: pt(205, 310), control1: pt(260, 293), control2: pt(237, 305))
    p.addCurve(to: pt(134, 260), control1: pt(171, 313), control2: pt(143, 292))
    p.addCurve(to: pt(139, 183), control1: pt(119, 238), control2: pt(129, 206))
    p.closeSubpath()
    return p
}

private func oval(_ c: CGContext, _ box: CGRect, fill: CGColor) {
    c.setFillColor(fill)
    c.fillEllipse(in: box)
}
private func stroke(_ c: CGContext, from a: CGPoint, control: CGPoint, to b: CGPoint, color: CGColor, line: CGFloat) {
    c.setStrokeColor(color)
    c.setLineWidth(line)
    c.setLineCap(.round)
    c.move(to: a)
    c.addQuadCurve(to: b, control: control)
    c.strokePath()
}
private func sparkle(_ c: CGContext, x: CGFloat, y: CGFloat, size: CGFloat) {
    c.setStrokeColor(mint)
    c.setLineCap(.round)
    c.setLineWidth(3.0)
    c.move(to: pt(x-size, y)); c.addLine(to: pt(x+size,y))
    c.move(to: pt(x, y-size)); c.addLine(to: pt(x,y+size))
    c.strokePath()
}

private func draw(pose: Pose, context c: CGContext) {
    // Coordinates below are authored top-down in a 400 × 400 frame.
    c.saveGState()
    c.translateBy(x: 0, y: 400)
    c.scaleBy(x: 1, y: -1)
    // Static foot shadow, not a halo or protective shield.
    oval(c, CGRect(x: 122, y: 346, width: 164, height: 15),
         fill: NSColor(srgbRed: 33/255, green: 106/255, blue: 94/255, alpha: 0.17).cgColor)

    c.translateBy(x: 204, y: 219)
    c.rotate(by: pose.tilt)
    c.translateBy(x: -204, y: -219)
    // Legs sit behind the body. A contrasting dark inner edge helps at 120 px.
    stroke(c, from: pt(183, 291), control: pt(174, 316), to: pose.leftFoot, color: deep, line: 23)
    stroke(c, from: pt(224, 294), control: pt(231, 315), to: pose.rightFoot, color: deep, line: 23)
    oval(c, CGRect(x: pose.leftFoot.x - 17, y: pose.leftFoot.y - 11, width: 33, height: 18), fill: mint)
    oval(c, CGRect(x: pose.rightFoot.x - 16, y: pose.rightFoot.y - 10, width: 33, height: 18), fill: mint)

    let l = pose.leftHand, r = pose.rightHand
    stroke(c, from: pt(144, 222), control: pt(103, 208), to: l, color: deep, line: 22)
    stroke(c, from: pt(270, 221), control: pt(308, 208), to: r, color: deep, line: 22)
    oval(c, CGRect(x: l.x - 13, y: l.y - 13, width: 26, height: 26), fill: mint)
    oval(c, CGRect(x: r.x - 13, y: r.y - 13, width: 26, height: 26), fill: mint)

    // Rounded, one-piece elastic body with a subtle downward darkening.
    let p = pathBody()
    c.saveGState()
    c.addPath(p); c.clip()
    let cs = CGColorSpaceCreateDeviceRGB()
    if let gradient = CGGradient(colorsSpace: cs, colors: [bright, mint, deep] as CFArray, locations: [0, 0.57, 1]) {
        c.drawLinearGradient(gradient, start: pt(175, 128), end: pt(237, 331), options: [])
    }
    c.restoreGState()
    c.addPath(p)
    c.setStrokeColor(NSColor(srgbRed: 13/255,green: 88/255,blue: 80/255,alpha:0.75).cgColor)
    c.setLineWidth(2.8)
    c.strokePath()

    // Signature open curl: a ribbon-like crest, not the registered W symbol.
    stroke(c, from: pt(177, 147), control: pt(145, 104), to: pt(164, 91), color: deep, line: 21)
    stroke(c, from: pt(177, 147), control: pt(145, 104), to: pt(164, 91), color: mint, line: 13)
    stroke(c, from: pt(234, 144), control: pt(256, 119), to: pt(276, 136), color: deep, line: 17)
    stroke(c, from: pt(235, 144), control: pt(255, 121), to: pt(275, 136), color: mint, line: 11)

    // Pearl face and gentle feature hierarchy.
    oval(c, CGRect(x: 160, y: 171, width: 92, height: 83), fill: pearl)
    if pose == .joy {
        stroke(c, from: pt(177, 206), control: pt(187, 191), to: pt(193, 205), color: ink, line: 4.2)
        stroke(c, from: pt(216, 205), control: pt(227, 191), to: pt(234, 206), color: ink, line: 4.2)
    } else if pose == .peek {
        stroke(c, from: pt(177, 208), control: pt(185, 199), to: pt(193, 207), color: ink, line: 4)
        oval(c, CGRect(x: 219, y: 200, width: 10, height: 16), fill: ink)
    } else {
        oval(c, CGRect(x: 178, y: 200, width: 10, height: 15), fill: ink)
        oval(c, CGRect(x: 223, y: 200, width: 10, height: 15), fill: ink)
    }
    if pose == .joy || pose == .peek {
        oval(c, CGRect(x: 202, y: 222, width: 12, height: 14), fill: ink)
        oval(c, CGRect(x: 205, y: 226, width: 6, height: 5), fill: mint)
    } else {
        stroke(c, from: pt(199, 222), control: pt(208, 235), to: pt(217, 222), color: ink, line: 3.5)
    }
    oval(c, CGRect(x: 168, y: 216, width: 6, height: 4), fill: mint)
    oval(c, CGRect(x: 238, y: 216, width: 6, height: 4), fill: mint)

    // The small unclosed flowline on the chest establishes character continuity.
    stroke(c, from: pt(190, 274), control: pt(207, 286), to: pt(221, 272), color: deep, line: 5)
    if pose == .joy {
        sparkle(c, x: 304, y: 99, size: 8)
        sparkle(c, x: 111, y: 123, size: 5)
    } else if pose == .wave {
        sparkle(c, x: 326, y: 101, size: 6)
    } else if pose == .peek {
        sparkle(c, x: 314, y: 119, size: 5)
    }
    c.restoreGState()
}

private func render(pose: Pose, side: Int) -> Data {
    let cs = CGColorSpaceCreateDeviceRGB()
    guard let c = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8,
                            bytesPerRow: 0, space: cs,
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
    else { fatalError("unable to make CGContext") }
    c.clear(CGRect(x: 0,y: 0,width: side,height: side))
    c.scaleBy(x: CGFloat(side)/400, y: CGFloat(side)/400)
    draw(pose: pose, context: c)
    guard let i = c.makeImage(), let data = NSBitmapImageRep(cgImage: i).representation(using: .png, properties: [:])
    else { fatalError("PNG encode failed") }
    return data
}

let path = FileManager.default.currentDirectoryPath + "/mobile/app/assets/ip/wavi/"
try FileManager.default.createDirectory(atPath: path, withIntermediateDirectories: true)
for pose in Pose.allCases {
    let target = path + "wavi_\(pose.rawValue).png"
    let bytes = render(pose: pose, side: 512)
    try bytes.write(to: URL(fileURLWithPath: target))
    print("WAVI \(pose.rawValue): \(bytes.count) bytes")
}
