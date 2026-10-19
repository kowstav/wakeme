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

    /// The self-setup route explains alarm access before any system prompt (doc 03 §5 step 7).
    @MainActor
    func testSelfSetupShowsReadiness() throws {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.buttons["Set up my own wake-up"].waitForExistence(timeout: 15))
        app.buttons["Set up my own wake-up"].tap()
        XCTAssertTrue(app.staticTexts["Let's make sure your alarm rings"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Allow alarms"].exists)
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "02-ready"
        shot.lifetime = .keepAlways
        add(shot)
    }
}
