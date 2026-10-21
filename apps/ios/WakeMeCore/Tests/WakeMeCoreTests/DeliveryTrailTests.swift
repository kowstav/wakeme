import Foundation
import XCTest
@testable import WakeMeCore

/// packages/policy-fixtures/fixtures/delivery-trail.json: the same table Android checks.
final class DeliveryTrailTests: XCTestCase {
    private static let dir: URL = {
        if let env = ProcessInfo.processInfo.environment["WAKEME_FIXTURES"] { return URL(fileURLWithPath: env) }
        return URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../../../packages/policy-fixtures/fixtures").standardized
    }()

    func testFixtureCases() throws {
        let data = try Data(contentsOf: Self.dir.appendingPathComponent("delivery-trail.json"))
        let fx = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let cases = try XCTUnwrap(fx["cases"] as? [[String: Any]])
        XCTAssertGreaterThan(cases.count, 20)
        for c in cases {
            let kind = try XCTUnwrap(c["kind"] as? String)
            let display = try XCTUnwrap(c["display"] as? String)
            let expected = try XCTUnwrap(c["expected"] as? [String: Any])
            let trail = DeliveryTrail(displayStatus: display, alarm: kind == "alarm")
            XCTAssertEqual(trail.reached, expected["reached"] as? Int, "\(kind) \(display) reached")
            XCTAssertEqual(trail.state.rawValue, expected["state"] as? String, "\(kind) \(display) state")
        }
    }

    func testNeverClaimsAwake() {
        for s in ["challenge_completed", "acknowledged", "stopped"] {
            XCTAssertLessThanOrEqual(DeliveryTrail(displayStatus: s, alarm: true).reached, DeliveryTrail.steps)
        }
    }
}
