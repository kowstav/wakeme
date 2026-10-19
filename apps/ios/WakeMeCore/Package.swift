// swift-tools-version: 6.0
// WakeMeCore: the pure-Swift domain core shared by the iOS app and its
// extensions (policy re-validation, occurrences, delivery state, offline
// challenge verification, signed challenge envelopes). No UIKit/SwiftUI,
// so `swift test` also runs on Linux against the shared golden fixtures.
import PackageDescription

let package = Package(
    name: "WakeMeCore",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [.library(name: "WakeMeCore", targets: ["WakeMeCore"])],
    dependencies: [
        // Only used where CryptoKit is unavailable (Linux CI); Apple platforms use CryptoKit.
        .package(url: "https://github.com/apple/swift-crypto.git", exact: "5.0.1"),
    ],
    targets: [
        .target(
            name: "WakeMeCore",
            dependencies: [.product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux]))]
        ),
        .testTarget(name: "WakeMeCoreTests", dependencies: ["WakeMeCore"]),
    ]
)
