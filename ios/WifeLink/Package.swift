// swift-tools-version:5.9
import PackageDescription

// The phone's side of protocol.md, without UIKit: tested on a Mac with `swift test` against pc/tests/vectors.json.
let package = Package(
    name: "WifeLink",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "WifeLink", targets: ["WifeLink"])],
    targets: [
        .target(name: "WifeLink"),
        .testTarget(name: "WifeLinkTests", dependencies: ["WifeLink"]),
    ]
)
