import CryptoKit
import XCTest

/// Journeys on the Simulator against the local dev backend (services/api dev.ts,
/// embedded database). Screenshots are kept as attachments for design review.
final class LaunchTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    @MainActor
    private func launch(timeZone: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestReset"]
        if let timeZone { app.launchEnvironment["TZ"] = timeZone }
        app.launch()
        return app
    }

    /// A zone without daylight saving where it's about 14:00 now, so Gentle nudges' 08:00–22:00
    /// hours allow a quack whatever time CI runs.
    private static func afternoonZone(now: Date = Date()) -> String {
        let zones = [-11: "Pacific/Pago_Pago", -10: "Pacific/Honolulu", -9: "Pacific/Gambier", -8: "Pacific/Pitcairn", -7: "America/Phoenix",
                     -6: "America/Regina", -5: "America/Bogota", -4: "America/La_Paz", -3: "America/Sao_Paulo", -2: "Atlantic/South_Georgia",
                     -1: "Atlantic/Cape_Verde", 0: "Africa/Abidjan", 1: "Africa/Lagos", 2: "Africa/Johannesburg", 3: "Europe/Istanbul",
                     4: "Asia/Dubai", 5: "Asia/Karachi", 6: "Asia/Dhaka", 7: "Asia/Bangkok", 8: "Asia/Singapore", 9: "Asia/Tokyo",
                     10: "Australia/Brisbane", 11: "Pacific/Guadalcanal", 12: "Pacific/Tarawa"]
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        var offset = (14 - utc.component(.hour, from: now) + 24) % 24
        if offset > 12 { offset -= 24 }
        return zones[offset]!
    }

    /// A registered phone for a test account: the server takes friend requests only from one.
    struct TestDevice { let id: String; let key: P256.Signing.PrivateKey }

    @MainActor
    private func registerDevice(token: String) async throws -> TestDevice {
        let key = P256.Signing.PrivateKey()
        let installId = "uitest" + UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(12)
        let d = try await http("POST", "/v1/devices", token, ["installId": installId, "platform": "ios", "osVersion": "26.0", "appBuild": "1",
                                                              "publicKey": key.publicKey.derRepresentation.base64EncodedString()])
        return TestDevice(id: try XCTUnwrap(d["id"] as? String), key: key)
    }

    struct StepFailed: Error {}

    /// Records the failure and stops the journey: async tests don't honour
    /// continueAfterFailure, and later steps would only screenshot the wrong screen.
    @MainActor
    private func must(_ condition: Bool, _ message: String, file: StaticString = #filePath, line: UInt = #line) throws {
        if !condition {
            // What was on screen (a system alert included) when the step failed.
            let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            a.name = "fail-line-\(line)"
            a.lifetime = .keepAlways
            add(a)
            XCTFail(message, file: file, line: line)
            throw StepFailed()
        }
    }

    /// Accepts a system permission alert (AlarmKit, notifications) if one comes up; slow simulators
    /// can take several seconds to show it.
    @MainActor
    private func allowSystemPrompt(_ springboard: XCUIApplication, timeout: TimeInterval = 15) {
        let allow = springboard.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Allow")).firstMatch
        if allow.waitForExistence(timeout: timeout) { allow.tap() }
    }

    /// Types into a field and retypes if a slow simulator dropped keystrokes (seen in run 35:
    /// "Brunch, Pasta night" arrived as "Brunch, P").
    @MainActor
    private func typeChecked(_ field: XCUIElement, _ text: String, file: StaticString = #filePath, line: UInt = #line) throws {
        for _ in 0..<3 {
            field.tap()
            let current = (field.value as? String) ?? ""
            let placeholder = field.placeholderValue ?? ""
            if !current.isEmpty, current != placeholder {
                field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count))
            }
            field.typeText(text)
            if (field.value as? String) == text { return }
        }
        try must(false, "Could not enter \(text) in \(field.identifier); got \(String(describing: field.value)) after three attempts", file: file, line: line)
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

    /// The dev backend over HTTP, for a second test account acting as its own app would.
    @MainActor
    private func http(_ method: String, _ path: String, _ token: String?, _ body: [String: Any]? = nil, device: TestDevice? = nil) async throws -> [String: Any] {
        var r = URLRequest(url: URL(string: "http://127.0.0.1:8787" + path)!)
        r.httpMethod = method
        if let token { r.setValue("Bearer \(token)", forHTTPHeaderField: "authorization") }
        let payload = try body.map { try JSONSerialization.data(withJSONObject: $0) } ?? Data()
        if body != nil {
            r.setValue("application/json", forHTTPHeaderField: "content-type")
            r.httpBody = payload
        }
        if let device {
            // WAKEME-V1 canonical request, as the apps sign it (services/api/src/http/device-auth.ts).
            let ts = String(Int(Date().timeIntervalSince1970))
            let digest = SHA256.hash(data: payload).map { String(format: "%02x", $0) }.joined()
            let message = ["WAKEME-V1", method, path, ts, digest].joined(separator: "\n")
            let signature = try device.key.signature(for: Data(message.utf8)).derRepresentation.base64EncodedString()
            r.setValue(device.id, forHTTPHeaderField: "x-wakeme-device")
            r.setValue(ts, forHTTPHeaderField: "x-wakeme-timestamp")
            r.setValue(signature, forHTTPHeaderField: "x-wakeme-signature")
        }
        let (data, response) = try await URLSession.shared.data(for: r)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        try must((200..<300).contains(status), "\(method) \(path) -> \(status): \(String(decoding: data, as: UTF8.self))")
        return (try JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    /// A second account that redeems an invite code; returns its token.
    @MainActor
    private func friendRedeems(code: String, name: String) async throws -> String {
        let issued = try await http("POST", "/dev/token", nil, [:])
        let token = try XCTUnwrap(issued["accessToken"] as? String)
        _ = try await http("POST", "/v1/account", token, ["displayName": name, "timeZone": "UTC", "locale": "en-US", "ageAttestation": true])
        _ = try await http("POST", "/v1/invites/redeem", token, ["code": code])
        return token
    }

    /// That account makes `friendName` a wake buddy with the Wake buddy preset (weekday
    /// mornings 06:00-10:00 in its zone), as its own permission editor would.
    @MainActor
    private func grantWakeBuddy(token: String, to friendName: String) async throws {
        let friends = try await http("GET", "/v1/friends", token)["friends"] as? [[String: Any]] ?? []
        let user = friends.compactMap { $0["user"] as? [String: Any] }.first { $0["displayName"] as? String == friendName }
        let granteeId = try XCTUnwrap(user?["id"] as? String, "\(friendName) is a friend")
        let presets = try await http("GET", "/v1/grants/presets", token)["presets"] as? [[String: Any]] ?? []
        let preset = try XCTUnwrap(presets.first { $0["id"] as? String == "wake_buddy" })
        _ = try await http("POST", "/v1/grants", token, ["granteeId": granteeId, "capabilities": preset["capabilities"] ?? [], "limits": preset["limits"] ?? [:], "validUntil": preset["validUntil"] ?? ""])
    }

    /// Doc 03 §5 route B: self-setup → sign-in → profile (18+) → alarm readiness → Friends,
    /// then a wake-up of your own, a friend's permissions, and the account controls.
    @MainActor
    func testSelfSetupJourney() async throws {
        let app = launch(timeZone: Self.afternoonZone())
        try must(app.buttons["Set up my own wake-up"].waitForExistence(timeout: 20), "step failed")
        app.buttons["Set up my own wake-up"].tap()

        // Doc 03 §5 step 2: a personal wake-up first, with no account; it is armed on this phone.
        try must(app.navigationBars["Your wake-ups"].waitForExistence(timeout: 15), "guest use without an account")
        shot(app, "02-guest")
        app.buttons["Make this phone ready"].tap()
        try must(app.staticTexts["Let's make sure your alarm rings"].waitForExistence(timeout: 10), "step failed")
        try must(app.buttons["Allow alarms"].exists, "alarm access is asked here, at wake setup")
        shot(app, "04-ready")
        // Ask here, where the button is; the screen shows the result (test alarm card = granted,
        // "turned off" = refused, the button again = the request failed).
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        app.buttons["Allow alarms"].tap()
        allowSystemPrompt(springboard)
        shot(app, "04b-alarms-asked")
        try must(app.buttons["Ring a test alarm in 1 minute"].waitForExistence(timeout: 10), "AlarmKit access granted on the readiness screen")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["New wake-up"].tap()
        try must(app.navigationBars["New wake-up"].waitForExistence(timeout: 10), "step failed")
        try must(app.staticTexts["Weekdays"].exists, "step failed")
        shot(app, "08-new-wake-up")
        app.navigationBars["New wake-up"].buttons["Save"].tap()
        allowSystemPrompt(springboard, timeout: 3) // already granted above
        try must(app.staticTexts["07:00 · Weekdays"].waitForExistence(timeout: 20), "the guest wake-up is listed")
        shot(app, "08b-guest-saved")
        try must(app.staticTexts["Ready on this phone"].waitForExistence(timeout: 10), "AlarmKit armed it on this phone")
        shot(app, "09-guest-wake-up")
        // Friends need an account; the wake-up moves to it.
        let signIn = app.buttons["Sign in to add friends"]
        for _ in 0..<3 where !signIn.isHittable { app.swipeUp() }
        signIn.tap()

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

        try must(app.staticTexts["Invite someone you trust"].waitForExistence(timeout: 20), "step failed")
        shot(app, "05-friends-empty")
        app.buttons["Invite a friend"].tap()
        try must(app.staticTexts["Share your invite"].waitForExistence(timeout: 15), "step failed")
        shot(app, "06-invite")
        let code = app.staticTexts["inviteCode"].label
        app.buttons["Close"].tap()
        let alex = try await friendRedeems(code: code, name: "Alex")

        app.tabBars.buttons["Routines"].tap()
        // The guest wake-up is now the account's routine: listed by the server and armed again here.
        try must(app.staticTexts["07:00 · Weekdays"].waitForExistence(timeout: 20), "the guest wake-up moved to the account")
        // Readiness reflects what this phone reported to the server, not just that it was saved.
        let ready = app.staticTexts["Ready on this phone"].waitForExistence(timeout: 15)
        try must(ready || app.staticTexts["Couldn't schedule on this phone. Check alarm permission."].exists, "this phone's installation receipt reached the server")
        shot(app, "09b-moved-to-account")

        app.tabBars.buttons["You"].tap()
        try must(app.staticTexts["Sam"].waitForExistence(timeout: 15), "step failed")
        shot(app, "10-you")
        // Focus (ADR 0014): reachable from You; Screen Time access is asked for here, never at sign-up.
        let focusLink = app.buttons["Focus"]
        for _ in 0..<3 where !focusLink.isHittable { app.swipeUp() }
        focusLink.tap()
        try must(app.navigationBars["Focus"].waitForExistence(timeout: 10), "the Focus screen opens")
        try must(app.buttons["Allow Screen Time access"].exists || app.buttons["Choose apps"].exists, "focus explains Screen Time before asking")
        shot(app, "10b-focus")
        app.navigationBars.buttons.element(boundBy: 0).tap()
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
        for _ in 0..<3 where !choose.isHittable { app.swipeUp() }
        choose.tap()
        let gentle = app.buttons["Gentle nudges"]
        try must(gentle.waitForExistence(timeout: 15), "step failed")
        gentle.tap()
        shot(app, "14-permissions")
        app.navigationBars["What can Alex do?"].buttons["Save"].tap()
        // Doc 03 §5 step 6: the sounds just allowed need notifications; the app explains first.
        let hear = app.alerts["Hear Alex's quacks?"]
        // Saving waits on a few requests (the grant, the friend again, this phone's copy of the
        // grants); on a slow Simulator that took over 15 s (ios #51). Still saving at the deadline
        // means slow, not wrong, so the message says which it was.
        let saving = app.navigationBars["What can Alex do?"].buttons["Saving…"]
        try must(hear.waitForExistence(timeout: 45), saving.exists ? "the grant was still saving after 45 s" : "the app explains before the system prompt")
        shot(app, "14b-hear-quacks")
        hear.buttons["Allow notifications"].tap()
        allowSystemPrompt(springboard)
        try must(app.staticTexts["Alex can send quacks."].waitForExistence(timeout: 15), "the grant is saved and shown in words")
        shot(app, "15-friend-granted")

        // Alex quacks Sam. There is no APNs in CI: the dev backend's push is relayed into the
        // Simulator (scripts/ci/relay_sim_pushes.py), and the app re-validates it against Sam's own
        // cached grant before showing it, then reports what happened.
        let alexPhone = try await registerDevice(token: alex)
        let samFriend = (try await http("GET", "/v1/friends", alex)["friends"] as? [[String: Any]] ?? [])
            .compactMap { $0["user"] as? [String: Any] }.first { $0["displayName"] as? String == "Sam" }
        let samUserId = try XCTUnwrap(samFriend?["id"] as? String)
        let quack = try await http("POST", "/v1/commands", alex, ["targetUserId": samUserId, "capability": "nudge.send", "soundId": "bundled:quack.classic",
                                                                  "idempotencyKey": "uitest-quack-\(Int(Date().timeIntervalSince1970))"], device: alexPhone)
        let quackId = try XCTUnwrap(quack["id"] as? String)
        var quackStatus = ""
        for _ in 0..<45 {
            quackStatus = (try await http("GET", "/v1/commands/\(quackId)", alex)["displayStatus"] as? String) ?? ""
            if ["phone_received", "opened", "acknowledged"].contains(quackStatus) { break }
            try await Task.sleep(for: .seconds(1))
        }
        shot(app, "15b-quack-received")
        try must(["phone_received", "opened", "acknowledged"].contains(quackStatus), "Sam's phone reported Alex's quack (status: \(quackStatus))")

        // The other direction: Alex makes Sam a wake buddy, so Sam can send a wake alarm
        // (inside Alex's hours), suggest a wake-up for Alex, and message Alex.
        try await grantWakeBuddy(token: alex, to: "Sam")
        app.navigationBars.buttons.element(boundBy: 0).tap() // back to the list; the page reloads when opened
        try must(app.staticTexts["Alex"].waitForExistence(timeout: 10), "step failed")
        app.staticTexts["Alex"].tap()
        try must(app.staticTexts["Wake alarm"].waitForExistence(timeout: 15), "Alex's permission reached Sam")
        try must(app.staticTexts["Rings Alex's phone for up to 120 seconds, inside the wake hours they chose."].exists, "the limits Alex chose are shown")
        shot(app, "16-friend-wake-buddy")
        let suggest = app.buttons["Suggest a wake-up routine"]
        for _ in 0..<3 where !suggest.isHittable { app.swipeUp() }
        try must(suggest.exists, "Alex allowed suggested wake-ups")
        suggest.tap()
        try must(app.navigationBars["Suggest a wake-up"].waitForExistence(timeout: 10), "step failed")
        shot(app, "17-suggest")
        app.navigationBars["Suggest a wake-up"].buttons["Suggest"].tap()
        try must(app.staticTexts["Waiting for Alex to accept"].waitForExistence(timeout: 15), "the suggestion is sent and changes nothing until Alex accepts")
        try must(app.staticTexts["07:00 · Weekdays"].exists, "step failed")
        shot(app, "18-suggested")
        let messages = app.buttons["Messages"]
        for _ in 0..<3 where !messages.isHittable { app.swipeUp() }
        messages.tap()
        let field = app.descendants(matching: .any)["messageField"].firstMatch
        try must(field.waitForExistence(timeout: 10), "the conversation opens")
        field.tap()
        field.typeText("Morning, Alex!")
        app.buttons["Send"].tap()
        try must(app.staticTexts["Morning, Alex!"].waitForExistence(timeout: 10), "the message is sent and listed")
        shot(app, "19-messages")
        app.navigationBars.buttons.element(boundBy: 0).tap() // back; the keyboard covered the tab bar

        app.tabBars.buttons["Challenges"].tap()
        try must(app.buttons["Create a challenge"].waitForExistence(timeout: 15), "the Challenges tab leads with creating one")
        shot(app, "22-challenges")

        // Doc 03: write a one-question quiz; every draft is tried before it can be published.
        app.buttons["Create a challenge"].tap()
        try must(app.navigationBars["Create a challenge"].waitForExistence(timeout: 10), "step failed")
        func quizField(_ id: String) -> XCUIElement { app.descendants(matching: .any)[id].firstMatch }
        for (id, text) in [("quizTitle", "Thursday quiz"), ("quizQuestion", "What do we call our Thursday dinner?"), ("quizAnswer", "Chaos curry"), ("quizWrong", "Brunch, Pasta night")] {
            try must(quizField(id).waitForExistence(timeout: 5), "field \(id)")
            try typeChecked(quizField(id), text)
        }
        shot(app, "23-create-challenge")
        app.navigationBars["Create a challenge"].buttons["Create"].tap()
        try must(app.staticTexts["What do we call our Thursday dinner?"].waitForExistence(timeout: 20), "the draft opens to be tried first")
        app.buttons["Chaos curry"].tap()
        try must(app.buttons["Publish"].waitForExistence(timeout: 10), "a tried draft can be published")
        shot(app, "24-try-out-done")
        app.buttons["Publish"].tap()
        let published = app.alerts.firstMatch
        try must(published.waitForExistence(timeout: 15), "publishing is confirmed")
        published.buttons["OK"].tap()
        try must(app.buttons["Thursday quiz"].waitForExistence(timeout: 10), "the published quiz can be practised")
        shot(app, "25-challenge-published")

        // Edit it: the form starts from version 1; a second question makes version 2, which is
        // tried and published like any draft.
        app.buttons["Edit Thursday quiz"].tap()
        try must(app.navigationBars["Edit challenge"].waitForExistence(timeout: 15), "the editor opens")
        // Choices were shuffled when published, so the wrong options may come back in either order.
        let wrongBack = Set(((quizField("quizWrong").value as? String) ?? "").components(separatedBy: ", "))
        try must(wrongBack == ["Brunch", "Pasta night"], "the form starts from version 1 (got \(wrongBack))")
        app.buttons["Add another"].tap()
        for (id, text) in [("quizQuestion", "Who forgets the keys?"), ("quizAnswer", "Sam")] {
            let field = app.descendants(matching: .any).matching(identifier: id).element(boundBy: 1)
            for _ in 0..<3 where !field.isHittable { app.swipeUp() }
            field.tap()
            field.typeText(text)
        }
        shot(app, "25b-edit-challenge")
        app.navigationBars["Edit challenge"].buttons["Save"].tap()
        try must(app.staticTexts["What do we call our Thursday dinner?"].waitForExistence(timeout: 20), "version 2 opens to be tried first")
        app.buttons["Chaos curry"].tap()
        let typed = app.textFields["Your answer"]
        try must(typed.waitForExistence(timeout: 5), "the new question")
        typed.tap()
        typed.typeText("Sam")
        app.buttons["Check"].tap()
        try must(app.buttons["Publish"].waitForExistence(timeout: 10), "version 2 can be published")
        app.buttons["Publish"].tap()
        let republished = app.alerts.firstMatch
        try must(republished.waitForExistence(timeout: 15), "publishing version 2 is confirmed")
        republished.buttons["OK"].tap()
        try must(app.buttons["Thursday quiz"].waitForExistence(timeout: 10), "still listed for practice")

        // Safety: Alex sends Sam an SOS (no location); Sam sees it and confirms, then starts a
        // check-in with Alex and checks in.
        let alexFriends = try await http("GET", "/v1/friends", alex)["friends"] as? [[String: Any]] ?? []
        let samId = try XCTUnwrap(alexFriends.compactMap { $0["user"] as? [String: Any] }.first { $0["displayName"] as? String == "Sam" }?["id"] as? String)
        _ = try await http("POST", "/v1/sos", alex, ["viewerIds": [samId], "shareLocation": false])
        app.tabBars.buttons["You"].tap()
        // Safety is a tile near the top of You, and the account steps above left You scrolled to
        // its end (Delete account): scroll back up to it.
        let safety = app.buttons["Safety check-ins and location"]
        for _ in 0..<4 where !safety.isHittable { app.swipeDown() }
        safety.tap()
        try must(app.staticTexts["SOS from Alex"].waitForExistence(timeout: 20), "Sam sees Alex's SOS")
        try must(app.staticTexts["Check-in only · no location shared"].exists, "no location is shown as none, not as live")
        shot(app, "26-sos-received")
        let seen = app.buttons["I've seen this"]
        for _ in 0..<3 where !seen.isHittable { app.swipeUp() }
        seen.tap()
        try must(app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH \"Alex knows you've seen this\"")).firstMatch.waitForExistence(timeout: 15), "Sam sees that Alex was told")
        // Start is below what friends share, under the floating tab bar: scroll it up, and wait
        // until the acknowledgement has finished (buttons are disabled while it runs).
        let checkIn = app.buttons["Check in with friends later"]
        app.swipeUp()
        try must(checkIn.waitForExistence(timeout: 10), "Start is shown")
        let enabled = expectation(for: NSPredicate(format: "enabled == true AND hittable == true"), evaluatedWith: checkIn)
        await fulfillment(of: [enabled], timeout: 15)
        checkIn.tap()
        let alexSwitch = app.switches["Alex"]
        try must(alexSwitch.waitForExistence(timeout: 10), "pick who can see it")
        alexSwitch.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        try must(app.staticTexts.containing(NSPredicate(format: "label BEGINSWITH 'Alex will be notified'")).firstMatch.waitForExistence(timeout: 5), "the summary names who is told")
        shot(app, "27-check-in-sheet")
        app.navigationBars["Check in later"].buttons["Start"].tap()
        let ok = app.buttons["I'm OK — check in now"]
        try must(ok.waitForExistence(timeout: 20), "the check-in is running")
        for _ in 0..<3 where !ok.isHittable { app.swipeUp() }
        shot(app, "28-check-in-running")
        ok.tap()
        try must(app.staticTexts["You're not sharing anything"].waitForExistence(timeout: 20), "checking in ends it")
        let sessions = try await http("GET", "/v1/location-sessions", alex)
        let sos = (sessions["mine"] as? [[String: Any]])?.first { $0["purpose"] as? String == "sos" }
        try must((sos?["viewers"] as? [[String: Any]])?.first?["acknowledgedAt"] is String, "Alex is told that Sam saw the SOS")

        // A private circle: created from Friends; Sam is its owner.
        app.tabBars.buttons["Friends"].tap()
        let newCircle = app.buttons["New circle"]
        // The tab keeps its navigation: back out of Alex's page to the list first.
        for _ in 0..<3 where !app.navigationBars["Friends"].exists { app.navigationBars.buttons.element(boundBy: 0).tap() }
        for _ in 0..<4 where !newCircle.isHittable { app.swipeUp() }
        newCircle.tap()
        let naming = app.alerts["New circle"]
        try must(naming.waitForExistence(timeout: 10), "the circle is named first")
        naming.textFields.firstMatch.typeText("Flat 3B")
        naming.buttons["Create"].tap()
        let circle = app.staticTexts["Flat 3B"]
        try must(circle.waitForExistence(timeout: 15), "the new circle is listed")
        circle.tap()
        try must(app.staticTexts["Owner"].waitForExistence(timeout: 15), "Sam owns the circle")
        shot(app, "29-circle")
    }

    /// The challenge runner checks answers on the phone: a wrong answer is a calm retry, the
    /// escape is always visible, and the right answers complete it (debug demo, no backend).
    @MainActor
    func testChallengeRunnerChecksAnswersOnThePhone() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestReset", "-uiTestChallengeDemo"]
        app.launch()
        try must(app.staticTexts["Morning warm-up"].waitForExistence(timeout: 20), "the runner opens")
        try must(app.buttons["End this wake session"].exists, "the escape is always on screen")
        try must(app.buttons["Use the simple check-in instead"].exists, "the check-in is offered")
        app.buttons["Brunch"].tap()
        try must(app.staticTexts["Not quite. Take your time and try again."].waitForExistence(timeout: 15), "a wrong answer is a calm retry")
        shot(app, "20-challenge-retry")
        app.buttons["Chaos curry"].tap()
        try must(app.staticTexts["7 + 8 = ?"].waitForExistence(timeout: 5), "the next step")
        let answer = app.textFields["Your answer"]
        answer.tap()
        answer.typeText("15")
        shot(app, "21-challenge-math")
        app.buttons["Check"].tap()
        try must(app.staticTexts["Challenge complete"].waitForExistence(timeout: 5), "the right answers complete it")
    }
}
