import AppKit
import ImageIO

// Rebuild the native icon with: swift mobile/tools/generate-ios-icon.swift
let size = 1024
let c = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
    bytesPerRow: size * 4, space: CGColorSpaceCreateDeviceRGB(),
    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
c.scaleBy(x: Double(size)/108, y: Double(size)/108)
func color(_ hex: UInt32) { c.setFillColor(NSColor(red: Double((hex>>16)&255)/255, green: Double((hex>>8)&255)/255, blue: Double(hex&255)/255, alpha: 1).cgColor) }
func polygon(_ p: [Double], _ hex: UInt32) {
    c.beginPath(); c.move(to: CGPoint(x: p[0], y: p[1]))
    for i in stride(from: 2, to: p.count, by: 2) { c.addLine(to: CGPoint(x: p[i], y: p[i+1])) }
    c.closePath(); color(hex); c.fillPath()
}
color(0x102C34); c.fill(CGRect(x: 0, y: 0, width: 108, height: 108))
c.setLineWidth(6); c.setLineCap(.round)
c.setStrokeColor(NSColor(red: 0.35, green: 0.8, blue: 0.76, alpha: 1).cgColor)
for y in [17.0, 28.0] {
    c.beginPath(); c.move(to: CGPoint(x: 20, y: y)); c.addCurve(to: CGPoint(x: 88,y: y), control1: CGPoint(x: 42,y: y+13), control2: CGPoint(x: 64,y: y-13)); c.strokePath()
}
polygon([54,94,34,67,37,36,71,36,74,67], 0xF2C982)
polygon([54,83,40,57,68,57], 0xF8EED6)
polygon([44,52,64,52,64,40,44,40], 0xE27659)
let output = URL(fileURLWithPath: "mobile/ios/WakeRunner/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
let destination = CGImageDestinationCreateWithURL(output as CFURL, "public.png" as CFString, 1, nil)!
CGImageDestinationAddImage(destination, c.makeImage()!, nil)
guard CGImageDestinationFinalize(destination) else { fatalError("Could not write app icon") }
