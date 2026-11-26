// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "TheLastSailCore",
    products: [.library(name: "TheLastSailCore", targets: ["TheLastSailCore"])],
    targets: [
        .target(name: "TheLastSailCore", path: "TheLastSail", exclude: ["TheLastSailApp.swift", "GameSurface.swift", "Info.plist", "Assets.xcassets", "PrivacyInfo.xcprivacy"], sources: ["Game.swift"]),
        .testTarget(name: "TheLastSailCoreTests", dependencies: ["TheLastSailCore"], path: "Tests/TheLastSailCoreTests")
    ]
)
