import XCTest

/// Cold launch reaches the welcome screen without a backend or account.
final class LaunchTests: XCTestCase {
    @MainActor
    func testWelcomeShowsBothRoutes() throws {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.buttons["I have an invite"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["Set up my own wake-up"].exists)
        XCTAssertTrue(app.buttons["Play preview"].exists)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "01-welcome"
        shot.lifetime = .keepAlways
        add(shot)
    }
}
