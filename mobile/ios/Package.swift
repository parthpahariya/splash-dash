// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "WakeRunnerCore",
    products: [.library(name: "WakeRunnerCore", targets: ["WakeRunnerCore"])],
    targets: [
        .target(name: "WakeRunnerCore", path: "WakeRunner", exclude: ["WakeRunnerApp.swift", "GameSurface.swift", "Info.plist", "Assets.xcassets"], sources: ["Game.swift"]),
        .testTarget(name: "WakeRunnerCoreTests", dependencies: ["WakeRunnerCore"], path: "Tests/WakeRunnerCoreTests")
    ]
)
