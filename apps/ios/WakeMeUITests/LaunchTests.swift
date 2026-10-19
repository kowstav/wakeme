import XCTest

/// Journeys on the Simulator against the local dev backend (services/api dev.ts,
/// embedded database). Screenshots are kept as attachments for design review.
final class LaunchTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestReset"]
        app.launch()
        return app
    }

    @MainActor
    private func shot(_ app: XCUIApplication, _ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    /// Cold launch reaches the welcome screen without an account.
    @MainActor
    func testWelcomeShowsBothRoutes() throws {
        let app = launch()
        XCTAssertTrue(app.buttons["I have an invite"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["Set up my own wake-up"].exists)
        XCTAssertTrue(app.buttons["Play preview"].exists)
        shot(app, "01-welcome")
    }

    /// Doc 03 §5 route B: self-setup → sign-in → profile (18+) → alarm readiness → Friends.
    @MainActor
    func testSelfSetupJourney() throws {
        let app = launch()
        XCTAssertTrue(app.buttons["Set up my own wake-up"].waitForExistence(timeout: 20))
        app.buttons["Set up my own wake-up"].tap()

        let dev = app.buttons["Use a local test account (debug build)"]
        XCTAssertTrue(dev.waitForExistence(timeout: 10))
        shot(app, "02-sign-in")
        dev.tap()

        let name = app.textFields["displayName"]
        XCTAssertTrue(name.waitForExistence(timeout: 20), "profile screen (dev backend reachable?)")
        name.tap()
        name.typeText("Sam")
        let adult = app.switches["I'm 18 or older"]
        XCTAssertTrue(adult.exists)
        adult.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        shot(app, "03-profile")
        app.buttons["Continue"].firstMatch.tap()

        XCTAssertTrue(app.staticTexts["Let's make sure your alarm rings"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["Allow alarms"].exists, "alarm access is asked here, at wake setup")
        shot(app, "04-ready")
        app.navigationBars.buttons["Continue"].tap()

        XCTAssertTrue(app.staticTexts["Invite someone you trust"].waitForExistence(timeout: 20))
        shot(app, "05-friends-empty")
        app.buttons["Invite a friend"].tap()
        XCTAssertTrue(app.staticTexts["Share your invite"].waitForExistence(timeout: 15))
        shot(app, "06-invite")
        app.buttons["Close"].tap()

        app.tabBars.buttons["Routines"].tap()
        XCTAssertTrue(app.staticTexts["No alarms yet. Set one for yourself, or let a friend suggest one."].waitForExistence(timeout: 15))
        shot(app, "07-routines")

        app.tabBars.buttons["You"].tap()
        XCTAssertTrue(app.staticTexts["Sam"].waitForExistence(timeout: 15))
        shot(app, "08-you")
    }
}
