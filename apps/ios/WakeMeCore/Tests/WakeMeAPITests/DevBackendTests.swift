import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
import XCTest
import WakeMeAPI
import WakeMeCore

/// Contract checks between this client and the real server code: run against the local dev
/// backend (services/api `npm run dev`) when WAKEME_DEV_BACKEND is set, e.g.
/// `WAKEME_DEV_BACKEND=http://127.0.0.1:8787 swift test`. Skipped otherwise.
final class DevBackendTests: XCTestCase {
    final class Token: TokenProvider, @unchecked Sendable {
        var value: String?
        func accessToken() async -> String? { value }
        func refresh() async -> Bool { false }
    }

    final class Signer: DeviceSigner, @unchecked Sendable {
        let key = P256.Signing.PrivateKey()
        var id: String?
        func deviceId() async -> String? { id }
        func signBase64(_ data: Data) async throws -> String { try key.signature(for: data).derRepresentation.base64EncodedString() }
    }

    private func devToken(_ base: URL) async throws -> String {
        var r = URLRequest(url: base.appendingPathComponent("dev/token"))
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "content-type")
        r.httpBody = Data("{}".utf8)
        let (data, _) = try await URLSession.shared.data(for: r)
        return try XCTUnwrap((try JSONSerialization.jsonObject(with: data) as? [String: Any])?["accessToken"] as? String)
    }

    func testOwnRoutineIsReadyAfterTheSignedInstallationReceipt() async throws {
        guard let raw = ProcessInfo.processInfo.environment["WAKEME_DEV_BACKEND"], let base = URL(string: raw) else {
            throw XCTSkip("set WAKEME_DEV_BACKEND to run against the dev backend")
        }
        let token = Token()
        let signer = Signer()
        let api = WakeMeAPI(client: APIClient(baseURL: base, tokens: token, signer: signer))
        token.value = try await devToken(base)
        _ = try await api.createAccount(name: "Sam", timeZone: "UTC", locale: "en-US")
        let head = try await api.sync(cursor: nil).cursor
        let device = try await api.registerDevice(
            installId: "i_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased(),
            osVersion: "26.0", appBuild: "1",
            publicKeySPKIBase64: signer.key.publicKey.derRepresentation.base64EncodedString(),
            push: nil,
            capabilities: .init(notificationsAuthorized: false, alarmAuthorized: true),
            makePrimary: true
        )
        signer.id = device.id

        let plan = try await api.createPlan(.init(label: "", localTime: "07:00", timeZone: "UTC", timeZonePolicy: "fixed", recurrence: .weekly([1, 2, 3, 4, 5]), soundId: "bundled:rise.gentle", ringSeconds: 90))
        XCTAssertEqual(plan.state, "active")
        let version = try XCTUnwrap(plan.accepted?.version)
        // What the app arms for it (ADR 0011), computed by the same code the app uses.
        let routine = InstalledRoutine(planId: plan.id, version: version, label: "", localTime: "07:00", timeZone: "UTC", timeZonePolicy: "fixed", recurrence: .weekly([1, 2, 3, 4, 5]),
                                       soundId: "bundled:rise.gentle", ringSeconds: 90, challengeRevisionId: nil, followUpDelaySeconds: nil, paused: false)
        let armed = try scheduleFor(routine, now: Date(), deviceZone: .current)
        XCTAssertGreaterThan(armed.count, 1, "a weekday routine is armed for more than its next occurrence")
        try await api.reportInstallation(planId: plan.id, .init(version: version, result: "scheduled", nextFireAt: Instant.format(armed[0].fireAt), scheduledInstants: armed.map { Instant.format($0.fireAt) }))

        let mine = try await api.plans().mine
        XCTAssertEqual(mine.first { $0.id == plan.id }?.readiness, "ready", "the server counts this phone's receipt")

        // The sync feed carries the routine itself, as the sync engine applies it.
        let feed = try await api.sync(cursor: head)
        XCTAssertEqual(feed.changes.first { $0.entity == "alarm_plan" && $0.id == plan.id }?.plan?.readiness, "ready")
        XCTAssertEqual(feed.changes.first { $0.entity == "me" }?.me?.primaryDeviceId, device.id, "phones learn which one rings from the profile")

        // The wake session's outcome, as the challenge screen reports it (signed by this phone).
        let occurrence = armed[0].occurrenceKey
        try await api.reportOccurrence(planId: plan.id, occurrenceKey: occurrence, version: version, outcome: "challenge_completed")
        try await api.reportOccurrence(planId: plan.id, occurrenceKey: occurrence, version: version, outcome: "rang") // late, lower rank: ignored, not an error
    }

    func testAQuizAndATemplateBecomeChallengesThisAppCanRun() async throws {
        guard let raw = ProcessInfo.processInfo.environment["WAKEME_DEV_BACKEND"], let base = URL(string: raw) else {
            throw XCTSkip("set WAKEME_DEV_BACKEND to run against the dev backend")
        }
        let token = Token()
        let api = WakeMeAPI(client: APIClient(baseURL: base, tokens: token, signer: nil))
        token.value = try await devToken(base)
        _ = try await api.createAccount(name: "Sam", timeZone: "UTC", locale: "en-US")

        // The server validates the definition strictly; this is the body the iPhone quiz builder sends.
        let draft = try await api.createQuiz(title: "Morning warm-up", questions: [
            .init(question: "What do we call our Thursday dinner?", answer: "Chaos curry", wrongOptions: ["Brunch"]),
            .init(question: "Which café is headquarters?", answer: "Lark"),
        ])
        let revision = try XCTUnwrap(draft.latest)
        XCTAssertEqual(revision.definition?.steps.count, 2, "the draft runs on this phone")
        try await api.markPreviewed(challengeId: draft.id, revision: revision.revision)
        let published = try await api.publishChallenge(draft.id, revision: revision.revision)
        XCTAssertEqual(published.publishedRevision, revision.revision)
        let listed = try await api.challenges()
        XCTAssertNotNil(listed.first { $0.id == draft.id }?.publishedRevisionId, "listed for practice and wake-ups")

        // Editing makes version 2 as a draft; version 1 stays published until 2 is tried and published.
        var questions = try XCTUnwrap(WakeMeAPI.quizQuestions(in: XCTUnwrap(revision.definition)))
        questions.append(.init(question: "Who forgets the keys?", answer: "Sam"))
        let edited = try await api.reviseQuiz(draft.id, title: "Morning warm-up", questions: questions, editing: revision.definition)
        let second = try XCTUnwrap(edited.latest)
        XCTAssertEqual(second.revision, revision.revision + 1)
        XCTAssertEqual(second.state, "draft")
        XCTAssertEqual(second.definition?.steps.count, 3)
        XCTAssertEqual(edited.publishedRevision, revision.revision, "the published version doesn't change")
        try await api.markPreviewed(challengeId: draft.id, revision: second.revision)
        let republished = try await api.publishChallenge(draft.id, revision: second.revision)
        XCTAssertEqual(republished.publishedRevision, second.revision)

        let templates = try await api.challengeTemplates()
        XCTAssertFalse(templates.isEmpty)
        for t in templates {
            let fromTemplate = try await api.challengeFromTemplate(t.id)
            XCTAssertNotNil(fromTemplate.latest?.definition, "template \(t.id) runs on this phone")
        }
    }

    func testSafetyShareCheckInAndSosBetweenFriends() async throws {
        guard let raw = ProcessInfo.processInfo.environment["WAKEME_DEV_BACKEND"], let base = URL(string: raw) else {
            throw XCTSkip("set WAKEME_DEV_BACKEND to run against the dev backend")
        }
        // Sam shares from this phone; Alex is the friend who watches.
        let samToken = Token(), alexToken = Token(), signer = Signer()
        let sam = WakeMeAPI(client: APIClient(baseURL: base, tokens: samToken, signer: signer))
        let alex = WakeMeAPI(client: APIClient(baseURL: base, tokens: alexToken, signer: nil))
        samToken.value = try await devToken(base)
        alexToken.value = try await devToken(base)
        _ = try await sam.createAccount(name: "Sam", timeZone: "UTC", locale: "en-US")
        let alexMe = try await alex.createAccount(name: "Alex", timeZone: "UTC", locale: "en-US")
        let device = try await sam.registerDevice(
            installId: "i_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased(),
            osVersion: "26.0", appBuild: "1",
            publicKeySPKIBase64: signer.key.publicKey.derRepresentation.base64EncodedString(),
            push: nil, capabilities: .init(notificationsAuthorized: false, alarmAuthorized: true), makePrimary: true
        )
        signer.id = device.id
        let invite = try await sam.createInvite()
        _ = try await alex.redeemInvite(token: invite.token, code: nil)

        // A location share: Alex sees when it was captured and how accurate, never "live".
        let share = try await sam.startSafety(purpose: "share", viewerIds: [alexMe.id], minutes: 30, shareLocation: true, checkinDueAt: nil, message: "Walking home")
        XCTAssertTrue(share.active)
        XCTAssertEqual(share.viewers.map(\.displayName), ["Alex"], "the sharer sees who can see it")
        let before = try await alex.latestLocation(share.id)
        XCTAssertEqual(LocationLabel(before).text, "No location available")
        try await sam.postLocation(sessionId: share.id, capturedAt: Date().addingTimeInterval(-90), latitude: 52.52, longitude: 13.405, accuracyMeters: 35)
        let seen = try await alex.latestLocation(share.id)
        XCTAssertEqual(seen.status, "available")
        XCTAssertEqual(seen.latitude ?? 0, 52.52, accuracy: 0.0001)
        XCTAssertEqual(LocationLabel(seen).text, "Captured 1 minute ago · accuracy about 35 m")
        let alexSees = try await alex.safetySessions().sharedWithMe
        XCTAssertEqual(alexSees.first { $0.id == share.id }?.message, "Walking home")
        let ended = try await sam.endSafety(share.id)
        XCTAssertFalse(ended.active)

        // A check-in: "I'm OK" completes and ends it.
        let checkin = try await sam.startSafety(purpose: "checkin", viewerIds: [alexMe.id], minutes: 60, shareLocation: false, checkinDueAt: Date().addingTimeInterval(1800), message: nil)
        XCTAssertNotNil(checkin.checkinDueAt)
        let done = try await sam.checkIn(checkin.id)
        XCTAssertNotNil(done.checkedInAt)
        XCTAssertFalse(done.active)

        // SOS without location; Alex confirms seeing it and Sam is told who did.
        let sos = try await sam.sos(viewerIds: [alexMe.id], shareLocation: false)
        XCTAssertEqual(sos.purpose, "sos")
        let sosLocation = try await alex.latestLocation(sos.id)
        XCTAssertEqual(LocationLabel(sosLocation, shared: sos.shareLocation).text, "Check-in only · no location shared")
        try await alex.acknowledgeSafety(sos.id)
        let mine = try await sam.safetySessions().mine.first { $0.id == sos.id }
        XCTAssertNotNil(mine?.viewers.first?.acknowledgedAt, "the sender sees that Alex saw it")
        _ = try await sam.endSafety(sos.id)
    }
}
