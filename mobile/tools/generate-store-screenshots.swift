import AppKit
import ImageIO

// Builds the 1080 × 1920 (9:16) Google Play screenshots from the raw phone captures in
// mobile/android/listing/captures. Each capture is trimmed of the system navigation bar and shown
// in a tilted frame over a sunburst, with a two-line hook.
//
// Rebuild from the repository root with: swift mobile/tools/generate-store-screenshots.swift
// Store graphics must not claim rankings or prices ("#1", "Best", "Free") or say "Download now".

struct Shot {
    let capture: String
    let hook: String  // cream line
    let punch: String  // big gold line
    /// Frame tilt in degrees; positive leans right.
    let tilt: Double
}

let listing = "mobile/android/listing"
let shots = [
    Shot(capture: "pirates.jpg", hook: "Can you survive", punch: "THE PIRATES?", tilt: -3),
    Shot(capture: "rocks.jpg", hook: "One wrong move…", punch: "CRASH!", tilt: 3),
    Shot(capture: "captain.jpg", hook: "The Captain wants", punch: "YOU SUNK!", tilt: -3),
    Shot(capture: "treasure.jpg", hook: "Only the brave", punch: "FIND THE TREASURE", tilt: 3),
]

let width = 1080.0, height = 1920.0
/// Fraction of each capture's height kept; the rest is the Android navigation bar.
let keep = 2205.0 / 2340.0

func rgb(_ hex: UInt32, _ alpha: Double = 1) -> CGColor {
    CGColor(
        srgbRed: Double((hex >> 16) & 255) / 255, green: Double((hex >> 8) & 255) / 255,
        blue: Double(hex & 255) / 255, alpha: alpha)
}
let ink: UInt32 = 0x0B2530, deep: UInt32 = 0x0E3440, surface: UInt32 = 0x17485A
let cream: UInt32 = 0xFFF6E0, gold: UInt32 = 0xFFC94D

func font(_ size: CGFloat) -> NSFont {
    let base = NSFont.systemFont(ofSize: size, weight: .black)
    return base.fontDescriptor.withDesign(.rounded).flatMap { NSFont(descriptor: $0, size: size) } ?? base
}
func line(_ text: String, _ size: CGFloat, _ color: UInt32, outline: Double = 0) -> CTLine {
    var attributes: [NSAttributedString.Key: Any] = [.font: font(size), .foregroundColor: rgb(color), .kern: 1.5]
    if outline > 0 { attributes[.strokeWidth] = outline; attributes[.strokeColor] = rgb(ink) }
    return CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: attributes))
}
func lineWidth(_ l: CTLine) -> Double { CTLineGetTypographicBounds(l, nil, nil, nil) }

for (index, shot) in shots.enumerated() {
    guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: "\(listing)/captures/\(shot.capture)") as CFURL, nil),
        let full = CGImageSourceCreateImageAtIndex(source, 0, nil)
    else { fatalError("Could not read \(shot.capture)") }
    let capture = full.cropping(to: CGRect(x: 0, y: 0, width: full.width, height: Int(Double(full.height) * keep)))!

    let c = CGContext(
        data: nil, width: Int(width), height: Int(height), bitsPerComponent: 8, bytesPerRow: Int(width) * 4,
        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    // Work top-down like the rest of the app art.
    c.translateBy(x: 0, y: height)
    c.scaleBy(x: 1, y: -1)
    let space = CGColorSpace(name: CGColorSpace.sRGB)

    // Background: deep sea with a gold glow and sunburst rays behind the phone.
    c.setFillColor(rgb(ink))
    c.fill(CGRect(x: 0, y: 0, width: width, height: height))
    let centre = CGPoint(x: width / 2, y: 1020)
    let glow = CGGradient(colorsSpace: space, colors: [rgb(surface), rgb(deep), rgb(ink)] as CFArray, locations: [0, 0.55, 1])!
    c.drawRadialGradient(glow, startCenter: centre, startRadius: 0, endCenter: centre, endRadius: 1150, options: [])
    let rays = 18
    for i in 0..<rays where i % 2 == 0 {
        let a = Double(i) / Double(rays) * 2 * .pi, b = Double(i + 1) / Double(rays) * 2 * .pi
        c.move(to: centre)
        c.addLine(to: CGPoint(x: centre.x + cos(a) * 2000, y: centre.y + sin(a) * 2000))
        c.addLine(to: CGPoint(x: centre.x + cos(b) * 2000, y: centre.y + sin(b) * 2000))
        c.closePath()
    }
    c.setFillColor(rgb(gold, 0.07))
    c.fillPath()

    // Headline: a cream hook, then the punchline in big outlined gold, tipped slightly.
    func draw(_ l: CTLine, centreX: Double, baseline: Double) {
        c.saveGState()
        c.translateBy(x: centreX - lineWidth(l) / 2, y: baseline)
        c.scaleBy(x: 1, y: -1)  // text draws bottom-up
        c.textPosition = .zero
        CTLineDraw(l, c)
        c.restoreGState()
    }
    draw(line(shot.hook.uppercased(), 64, cream), centreX: width / 2, baseline: 140)
    var punchSize: CGFloat = 132
    while lineWidth(line(shot.punch, punchSize, gold)) > width - 90 { punchSize -= 4 }
    c.saveGState()
    c.translateBy(x: width / 2, y: 270)
    c.rotate(by: -2 * .pi / 180)
    c.setShadow(offset: CGSize(width: 0, height: 10), blur: 0, color: rgb(0x000000, 0.5))
    draw(line(shot.punch, punchSize, gold, outline: -5), centreX: 0, baseline: 0)
    c.restoreGState()

    // The capture in a tilted rounded frame.
    let frameHeight = 1440.0
    let frameWidth = frameHeight * Double(capture.width) / Double(capture.height)
    let frameCentre = CGPoint(x: width / 2, y: 1155)
    c.saveGState()
    c.translateBy(x: frameCentre.x, y: frameCentre.y)
    c.rotate(by: shot.tilt * .pi / 180)
    let frame = CGRect(x: -frameWidth / 2, y: -frameHeight / 2, width: frameWidth, height: frameHeight)
    let bezel = CGPath(roundedRect: frame.insetBy(dx: -14, dy: -14), cornerWidth: 66, cornerHeight: 66, transform: nil)
    c.saveGState()
    c.setShadow(offset: CGSize(width: 0, height: 30), blur: 50, color: rgb(0x000000, 0.6))
    c.addPath(bezel)
    c.setFillColor(rgb(ink))
    c.fillPath()
    c.restoreGState()
    c.addPath(bezel)
    c.setStrokeColor(rgb(gold))
    c.setLineWidth(6)
    c.strokePath()
    c.saveGState()
    c.addPath(CGPath(roundedRect: frame, cornerWidth: 52, cornerHeight: 52, transform: nil))
    c.clip()
    c.interpolationQuality = .high
    // CGContext.draw puts images bottom-up, so flip around the frame.
    c.translateBy(x: 0, y: frame.midY * 2)
    c.scaleBy(x: 1, y: -1)
    c.draw(capture, in: frame)
    c.restoreGState()
    c.restoreGState()

    let path = "\(listing)/screenshots/screenshot-\(index + 1).png"
    let destination = CGImageDestinationCreateWithURL(URL(fileURLWithPath: path) as CFURL, "public.png" as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, c.makeImage()!, nil)
    guard CGImageDestinationFinalize(destination) else { fatalError("Could not write \(path)") }
    print(path)
}
