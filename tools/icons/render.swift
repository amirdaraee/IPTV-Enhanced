// Renders Tuner's icon artwork with CoreGraphics.
// usage: swift render.swift <kind: back|front|full|banner|square> <width> <height> <out.png>
import AppKit
import CoreGraphics

let args = CommandLine.arguments
let kind = args[1]
let w = Int(args[2])!, h = Int(args[3])!
let out = URL(fileURLWithPath: args[4])

let cs = CGColorSpaceCreateDeviceRGB()
let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: cs,
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
let W = CGFloat(w), H = CGFloat(h)
let accent = CGColor(red: 0.369, green: 0.918, blue: 0.831, alpha: 1)

func background() {
    let g = CGGradient(colorsSpace: cs, colors: [
        CGColor(red: 0.03, green: 0.05, blue: 0.09, alpha: 1),
        CGColor(red: 0.07, green: 0.12, blue: 0.20, alpha: 1),
        CGColor(red: 0.08, green: 0.30, blue: 0.29, alpha: 1),
    ] as CFArray, locations: [0, 0.6, 1])!
    ctx.drawLinearGradient(g, start: CGPoint(x: 0, y: H), end: CGPoint(x: W, y: 0), options: [])
}

/// A rounded "screen" with three signal bars: the Tuner mark.
func mark(center: CGPoint, size: CGFloat) {
    let rect = CGRect(x: center.x - size / 2, y: center.y - size * 0.36, width: size, height: size * 0.62)
    ctx.setStrokeColor(accent)
    ctx.setLineWidth(size * 0.07)
    ctx.addPath(CGPath(roundedRect: rect, cornerWidth: size * 0.1, cornerHeight: size * 0.1, transform: nil))
    ctx.strokePath()
    ctx.setFillColor(accent)
    let barW = size * 0.09
    for i in 0..<3 {
        let bh = size * (0.14 + CGFloat(i) * 0.1)
        let x = rect.minX + size * 0.26 + CGFloat(i) * size * 0.17
        ctx.addPath(CGPath(roundedRect: CGRect(x: x, y: rect.minY + size * 0.12, width: barW, height: bh),
                           cornerWidth: barW / 2, cornerHeight: barW / 2, transform: nil))
    }
    ctx.fillPath()
    // stand
    ctx.fill(CGRect(x: center.x - size * 0.18, y: rect.minY - size * 0.12, width: size * 0.36, height: size * 0.05))
}

func text(_ s: String, size: CGFloat, at p: CGPoint) {
    let ns = NSGraphicsContext(cgContext: ctx, flipped: false)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = ns
    let attrs: [NSAttributedString.Key: Any] = [
        .font: NSFont.systemFont(ofSize: size, weight: .bold),
        .foregroundColor: NSColor(cgColor: accent)!,
    ]
    let str = NSAttributedString(string: s, attributes: attrs)
    let b = str.size()
    str.draw(at: CGPoint(x: p.x - b.width / 2, y: p.y - b.height / 2))
    NSGraphicsContext.restoreGraphicsState()
}

switch kind {
case "back":
    background()
case "front":
    mark(center: CGPoint(x: W / 2, y: H / 2), size: min(W, H) * 0.62)
case "full", "square":
    background()
    mark(center: CGPoint(x: W / 2, y: H / 2), size: min(W, H) * 0.62)
case "banner":
    background()
    mark(center: CGPoint(x: W * 0.24, y: H / 2), size: H * 0.48)
    text("Tuner", size: H * 0.28, at: CGPoint(x: W * 0.66, y: H / 2))
default:
    fatalError("unknown kind")
}
let img = ctx.makeImage()!
let rep = NSBitmapImageRep(cgImage: img)
try! rep.representation(using: .png, properties: [:])!.write(to: out)
