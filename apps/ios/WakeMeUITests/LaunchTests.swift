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

    /// A second account on the dev backend, made over HTTP, that redeems an invite code.
    @MainActor
    private func friendRedeems(code: String, name: String) async throws {
        func call(_ path: String, _ token: String?, _ body: [String: Any]) async throws -> [String: Any] {
            var r = URLRequest(url: URL(string: "http://127.0.0.1:8787" + path)!)
            r.httpMethod = "POST"
            r.setValue("application/json", forHTTPHeaderField: "content-type")
            if let token { r.setValue("Bearer \(token)", forHTTPHeaderField: "authorization") }
            r.httpBody = try JSONSerialization.data(withJSONObject: body)
            let (data, response) = try await URLSession.shared.data(for: r)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            XCTAssertTrue((200..<300).contains(status), "POST \(path) -> \(status): \(String(decoding: data, as: UTF8.self))")
            return (try JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        }
        let issued = try await call("/dev/token", nil, [:])
        let token = try XCTUnwrap(issued["accessToken"] as? String)
        _ = try await call("/v1/account", token, ["displayName": name, "timeZone": "UTC", "locale": "en-US", "ageAttestation": true])
        _ = try await call("/v1/invites/redeem", token, ["code": code])
    }

    /// Doc 03 §5 route B: self-setup → sign-in → profile (18+) → alarm readiness → Friends,
    /// then a wake-up of your own, a friend's permissions, and the account controls.
    @MainActor
    func testSelfSetupJourney() async throws {
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
        let code = app.staticTexts["inviteCode"].label
        app.buttons["Close"].tap()
        try await friendRedeems(code: code, name: "Alex")

        app.tabBars.buttons["Routines"].tap()
        XCTAssertTrue(app.staticTexts["No alarms yet. Set one for yourself, or let a friend suggest one."].waitForExistence(timeout: 15))
        shot(app, "07-routines")

        // Doc 03 §5 step 7: set your own wake-up; alarm access is asked for when it's saved.
        app.buttons["Set a wake-up"].tap()
        XCTAssertTrue(app.navigationBars["New wake-up"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Weekdays"].exists)
        shot(app, "08-new-wake-up")
        app.navigationBars["New wake-up"].buttons["Save"].tap()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        if springboard.buttons["Allow"].waitForExistence(timeout: 8) { springboard.buttons["Allow"].tap() }
        XCTAssertTrue(app.staticTexts["07:00 · Weekdays"].waitForExistence(timeout: 20), "the saved routine is listed")
        // Readiness reflects this phone's report; AlarmKit is permitted once "Allow" was tapped.
        _ = app.staticTexts["Ready on this phone"].waitForExistence(timeout: 10)
        shot(app, "09-routine-saved")

        app.tabBars.buttons["You"].tap()
        XCTAssertTrue(app.staticTexts["Sam"].waitForExistence(timeout: 15))
        shot(app, "10-you")
        let delete = app.buttons["Delete account"]
        for _ in 0..<4 where !delete.isHittable { app.swipeUp() }
        XCTAssertTrue(delete.exists, "account deletion is in the app (App Store 5.1.1(v))")
        XCTAssertTrue(app.buttons["Export my data"].exists)
        shot(app, "11-you-data")
        delete.tap()
        XCTAssertTrue(app.staticTexts["Delete your account?"].waitForExistence(timeout: 5))
        shot(app, "12-delete-confirm")
        // iOS 26 shows this dialog as a popover with no Cancel button; a tap outside dismisses it.
        if app.buttons["Cancel"].exists { app.buttons["Cancel"].tap() } else { app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.04)).tap() }
        XCTAssertTrue(app.staticTexts["Delete your account?"].waitForNonExistence(timeout: 5), "dismissed without deleting")

        // Doc 03 §6: friendship grants nothing; Sam chooses what Alex may do on Sam's phone.
        app.tabBars.buttons["Friends"].tap()
        if !app.staticTexts["Alex"].waitForExistence(timeout: 5) { app.swipeDown() } // pull to refresh
        XCTAssertTrue(app.staticTexts["Alex"].waitForExistence(timeout: 20), "Alex accepted the invite")
        app.staticTexts["Alex"].tap()
        let choose = app.buttons["Choose what Alex can do"]
        XCTAssertTrue(choose.waitForExistence(timeout: 15))
        shot(app, "13-friend")
        choose.tap()
        let gentle = app.buttons["Gentle nudges"]
        XCTAssertTrue(gentle.waitForExistence(timeout: 15))
        gentle.tap()
        shot(app, "14-permissions")
        app.navigationBars["What can Alex do?"].buttons["Save"].tap()
        XCTAssertTrue(app.staticTexts["Alex can send quacks."].waitForExistence(timeout: 15), "the grant is saved and shown in words")
        shot(app, "15-friend-granted")
    }
}
