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

    struct StepFailed: Error {}

    /// Records the failure and stops the journey: async tests don't honour
    /// continueAfterFailure, and later steps would only screenshot the wrong screen.
    private func must(_ condition: Bool, _ message: String, file: StaticString = #filePath, line: UInt = #line) throws {
        if !condition {
            XCTFail(message, file: file, line: line)
            throw StepFailed()
        }
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
        try must(app.buttons["Set up my own wake-up"].waitForExistence(timeout: 20), "step failed")
        app.buttons["Set up my own wake-up"].tap()

        let dev = app.buttons["Use a local test account (debug build)"]
        try must(dev.waitForExistence(timeout: 10), "step failed")
        shot(app, "02-sign-in")
        dev.tap()

        let name = app.textFields["displayName"]
        try must(name.waitForExistence(timeout: 20), "profile screen (dev backend reachable?)")
        name.tap()
        name.typeText("Sam")
        let adult = app.switches["I'm 18 or older"]
        try must(adult.exists, "step failed")
        adult.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        shot(app, "03-profile")
        app.buttons["Continue"].firstMatch.tap()

        try must(app.staticTexts["Let's make sure your alarm rings"].waitForExistence(timeout: 20), "step failed")
        try must(app.buttons["Allow alarms"].exists, "alarm access is asked here, at wake setup")
        shot(app, "04-ready")
        app.navigationBars.buttons["Continue"].tap()

        try must(app.staticTexts["Invite someone you trust"].waitForExistence(timeout: 20), "step failed")
        shot(app, "05-friends-empty")
        app.buttons["Invite a friend"].tap()
        try must(app.staticTexts["Share your invite"].waitForExistence(timeout: 15), "step failed")
        shot(app, "06-invite")
        let code = app.staticTexts["inviteCode"].label
        app.buttons["Close"].tap()
        try await friendRedeems(code: code, name: "Alex")

        app.tabBars.buttons["Routines"].tap()
        try must(app.staticTexts["No alarms yet. Set one for yourself, or let a friend suggest one."].waitForExistence(timeout: 15), "step failed")
        shot(app, "07-routines")

        // Doc 03 §5 step 7: set your own wake-up; alarm access is asked for when it's saved.
        app.buttons["Set a wake-up"].tap()
        try must(app.navigationBars["New wake-up"].waitForExistence(timeout: 10), "step failed")
        try must(app.staticTexts["Weekdays"].exists, "step failed")
        shot(app, "08-new-wake-up")
        app.navigationBars["New wake-up"].buttons["Save"].tap()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        if springboard.buttons["Allow"].waitForExistence(timeout: 8) { springboard.buttons["Allow"].tap() }
        try must(app.staticTexts["07:00 · Weekdays"].waitForExistence(timeout: 20), "the saved routine is listed")
        // Readiness reflects what this phone reported to the server, not just that it was saved.
        let ready = app.staticTexts["Ready on this phone"].waitForExistence(timeout: 15)
        try must(ready || app.staticTexts["Couldn't schedule on this phone. Check alarm permission."].exists, "this phone's installation receipt reached the server")
        shot(app, "09-routine-saved")

        app.tabBars.buttons["You"].tap()
        try must(app.staticTexts["Sam"].waitForExistence(timeout: 15), "step failed")
        shot(app, "10-you")
        let delete = app.buttons["Delete account"]
        for _ in 0..<4 where !delete.isHittable { app.swipeUp() }
        try must(delete.exists, "account deletion is in the app (App Store 5.1.1(v))")
        try must(app.buttons["Export my data"].exists, "step failed")
        shot(app, "11-you-data")
        delete.tap()
        let confirm = app.alerts["Delete your account?"]
        try must(confirm.waitForExistence(timeout: 5), "deletion asks for confirmation")
        shot(app, "12-delete-confirm")
        confirm.buttons["Cancel"].tap()
        try must(confirm.waitForNonExistence(timeout: 5), "cancelled without deleting")
        // Doc 03 §6: friendship grants nothing; Sam chooses what Alex may do on Sam's phone.
        app.tabBars.buttons["Friends"].tap()
        if !app.staticTexts["Alex"].waitForExistence(timeout: 5) { app.swipeDown() } // pull to refresh
        try must(app.staticTexts["Alex"].waitForExistence(timeout: 20), "Alex accepted the invite")
        app.staticTexts["Alex"].tap()
        let choose = app.buttons["Choose what Alex can do"]
        try must(choose.waitForExistence(timeout: 15), "step failed")
        shot(app, "13-friend")
        choose.tap()
        let gentle = app.buttons["Gentle nudges"]
        try must(gentle.waitForExistence(timeout: 15), "step failed")
        gentle.tap()
        shot(app, "14-permissions")
        app.navigationBars["What can Alex do?"].buttons["Save"].tap()
        try must(app.staticTexts["Alex can send quacks."].waitForExistence(timeout: 15), "the grant is saved and shown in words")
        shot(app, "15-friend-granted")

        app.tabBars.buttons["Challenges"].tap()
        try must(app.staticTexts["No challenges yet. Quizzes and puzzles made on Android, or shared in your circles, appear here."].waitForExistence(timeout: 15), "the Challenges tab lists what can be practised")
        shot(app, "16-challenges")
    }
}
