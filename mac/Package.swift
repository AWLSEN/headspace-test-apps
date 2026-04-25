// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "headspace-recorder",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "headspace-recorder",
            path: "Sources/headspace-recorder",
            linkerSettings: [
                .linkedFramework("AVFoundation"),
                .linkedFramework("CoreMedia"),
                .linkedFramework("AppKit"),
            ]
        ),
    ]
)
