// Renders IPTV Enhanced's icon artwork with CoreGraphics.
// usage: swift render.swift <kind: back|front|adaptive|full|banner|square> <width> <height> <out.png>
import AppKit
import CoreGraphics

let args = CommandLine.arguments
let kind = args[1]
let w = Int(args[2])!, h = Int(args[3])!
let out = URL(fileURLWithPath: args[4])

let cs = CGColorSpaceCreateDeviceRGB()
// Only the foreground layers are transparent; opaque images skip the alpha channel (smaller PNGs).
let alpha: CGImageAlphaInfo = kind == "front" || kind == "adaptive" ? .premultipliedLast : .noneSkipLast
let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: cs, bitmapInfo: alpha.rawValue)!
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

/// Four-pointed sparkle: the "enhanced" accent.
func sparkle(center c: CGPoint, radius r: CGFloat, color: CGColor) {
    let path = CGMutablePath()
    let waist = r * 0.22
    path.move(to: CGPoint(x: c.x, y: c.y + r))
    path.addQuadCurve(to: CGPoint(x: c.x + r, y: c.y), control: CGPoint(x: c.x + waist, y: c.y + waist))
    path.addQuadCurve(to: CGPoint(x: c.x, y: c.y - r), control: CGPoint(x: c.x + waist, y: c.y - waist))
    path.addQuadCurve(to: CGPoint(x: c.x - r, y: c.y), control: CGPoint(x: c.x - waist, y: c.y - waist))
    path.addQuadCurve(to: CGPoint(x: c.x, y: c.y + r), control: CGPoint(x: c.x - waist, y: c.y + waist))
    ctx.setFillColor(color)
    ctx.addPath(path)
    ctx.fillPath()
}

/// A rounded screen with a play button and a sparkle on its corner: the IPTV Enhanced mark.
func mark(center: CGPoint, size: CGFloat) {
    let rect = CGRect(x: center.x - size / 2, y: center.y - size * 0.30, width: size, height: size * 0.64)
    let corner = size * 0.12
    // Screen: tinted glass with an accent frame.
    ctx.saveGState()
    ctx.addPath(CGPath(roundedRect: rect, cornerWidth: corner, cornerHeight: corner, transform: nil))
    ctx.clip()
    let glass = CGGradient(colorsSpace: cs, colors: [
        CGColor(red: 0.37, green: 0.92, blue: 0.83, alpha: 0.10),
        CGColor(red: 0.37, green: 0.92, blue: 0.83, alpha: 0.28),
    ] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(glass, start: CGPoint(x: rect.minX, y: rect.minY), end: CGPoint(x: rect.maxX, y: rect.maxY), options: [])
    ctx.restoreGState()
    ctx.setStrokeColor(accent)
    ctx.setLineWidth(size * 0.06)
    ctx.addPath(CGPath(roundedRect: rect, cornerWidth: corner, cornerHeight: corner, transform: nil))
    ctx.strokePath()
    // Play button, optically centred (nudged right).
    let t = size * 0.30
    let cx = rect.midX + t * 0.08, cy = rect.midY
    let play = CGMutablePath()
    play.move(to: CGPoint(x: cx - t * 0.40, y: cy + t * 0.48))
    play.addLine(to: CGPoint(x: cx + t * 0.52, y: cy))
    play.addLine(to: CGPoint(x: cx - t * 0.40, y: cy - t * 0.48))
    play.closeSubpath()
    ctx.saveGState()
    ctx.addPath(CGPath(roundedRect: play.boundingBox, cornerWidth: 0, cornerHeight: 0, transform: nil))
    ctx.addPath(play)
    ctx.setLineJoin(.round)
    ctx.setLineWidth(size * 0.05)
    ctx.setStrokeColor(accent)
    ctx.setFillColor(accent)
    ctx.beginPath()
    ctx.addPath(play)
    ctx.drawPath(using: .fillStroke)
    ctx.restoreGState()
    // Stand.
    ctx.setFillColor(accent)
    ctx.addPath(CGPath(roundedRect: CGRect(x: center.x - size * 0.17, y: rect.minY - size * 0.13, width: size * 0.34, height: size * 0.05),
                       cornerWidth: size * 0.025, cornerHeight: size * 0.025, transform: nil))
    ctx.fillPath()
    // Sparkles on the top-right corner, with a dark halo so they read over the frame.
    let gold = CGColor(red: 1.0, green: 0.84, blue: 0.45, alpha: 1)
    let halo = CGColor(red: 0.04, green: 0.07, blue: 0.12, alpha: 1)
    let big = CGPoint(x: rect.maxX - size * 0.02, y: rect.maxY + size * 0.02)
    sparkle(center: big, radius: size * 0.21, color: halo)
    sparkle(center: big, radius: size * 0.16, color: gold)
    sparkle(center: CGPoint(x: rect.maxX + size * 0.14, y: rect.maxY - size * 0.15), radius: size * 0.065, color: gold)
}

func text(_ s: String, size: CGFloat, at p: CGPoint, weight: NSFont.Weight = .bold, color: CGColor = accent, leading: Bool = false) {
    let ns = NSGraphicsContext(cgContext: ctx, flipped: false)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = ns
    let attrs: [NSAttributedString.Key: Any] = [
        .font: NSFont.systemFont(ofSize: size, weight: weight),
        .foregroundColor: NSColor(cgColor: color)!,
    ]
    let str = NSAttributedString(string: s, attributes: attrs)
    let b = str.size()
    str.draw(at: CGPoint(x: leading ? p.x : p.x - b.width / 2, y: p.y - b.height / 2))
    NSGraphicsContext.restoreGraphicsState()
}

switch kind {
case "back":
    background()
case "front":
    mark(center: CGPoint(x: W / 2, y: H / 2), size: min(W, H) * 0.62)
case "adaptive":
    // Android adaptive icon foreground: launchers mask the outer third, so the mark stays inside
    // the 66 dp safe zone of the 108 dp canvas.
    mark(center: CGPoint(x: W / 2, y: H / 2), size: min(W, H) * 0.42)
case "full", "square":
    background()
    mark(center: CGPoint(x: W / 2, y: H / 2), size: min(W, H) * 0.62)
case "banner":
    background()
    mark(center: CGPoint(x: W / 2 - H * 0.44, y: H / 2), size: H * 0.46)
    let white = CGColor(red: 0.93, green: 0.96, blue: 0.97, alpha: 1)
    text("IPTV", size: H * 0.25, at: CGPoint(x: W / 2 - H * 0.06, y: H * 0.585), weight: .heavy, color: white, leading: true)
    text("Enhanced", size: H * 0.15, at: CGPoint(x: W / 2 - H * 0.055, y: H * 0.37), weight: .semibold, leading: true)
default:
    fatalError("unknown kind")
}
let img = ctx.makeImage()!
let rep = NSBitmapImageRep(cgImage: img)
try! rep.representation(using: .png, properties: [:])!.write(to: out)
