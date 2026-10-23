import Foundation
import XCTest
@testable import WakeMeCore

final class ChallengeRunTests: XCTestCase {
    private func definition(_ steps: [ChallengeStep]) -> ChallengeDefinition {
        ChallengeDefinition(schemaVersion: 1, title: "Morning", kind: "sequence", difficulty: "easy", maxDurationSeconds: 90, steps: steps, verification: "local_deterministic", fallback: Fallback(type: "accessible_checkin", prompt: nil), permissionsRequired: [])
    }

    func testWrongAnswersRetryAndRightAnswersAdvanceToTheEnd() {
        var run = ChallengeProgress(definition([
            .multipleChoice(id: "q1", prompt: "Thursday dinner?", options: ["Chaos curry", "Brunch"], correctIndex: 0),
            .math(id: "q2", operands: [7, 8], operator: "+", answer: 15),
        ]))
        XCTAssertEqual(run.stepNumber, 1)
        XCTAssertFalse(run.answer(.index(1)))
        XCTAssertTrue(run.retry)
        XCTAssertEqual(run.stepNumber, 1, "a wrong answer never skips or penalises")
        XCTAssertTrue(run.answer(.index(0)))
        XCTAssertFalse(run.retry)
        XCTAssertEqual(run.stepNumber, 2)
        XCTAssertFalse(run.answer(.text("16")))
        XCTAssertTrue(run.answer(.text(" 15 ")))
        XCTAssertTrue(run.finished)
        XCTAssertFalse(run.usedFallback)
        XCTAssertNil(run.step)
        XCTAssertFalse(run.answer(.text("15")), "nothing to answer once finished")
    }

    func testTheCheckInFinishesFromAnyStepAndIsMarkedAsFallback() {
        var run = ChallengeProgress(definition([.timer(id: "t", prompt: "Stretch", seconds: 60, activity: "stretch")]))
        XCTAssertFalse(run.answer(.elapsed(30)))
        run.completeWithFallback()
        XCTAssertTrue(run.finished)
        XCTAssertTrue(run.usedFallback)
    }

    func testFenBoardAndMoveStrings() {
        let board = parseFenBoard("6k1/4P3/8/8/8/8/8/K7 w - - 0 1")
        XCTAssertEqual(board["g8"], "k")
        XCTAssertEqual(board["e7"], "P")
        XCTAssertEqual(board["a1"], "K")
        XCTAssertNil(board["e8"])
        XCTAssertEqual(chessMove(from: "e7", to: "e8", board: board), "e7e8q", "a pawn on the last rank promotes to a queen")
        XCTAssertEqual(chessMove(from: "a1", to: "a2", board: board), "a1a2")
        XCTAssertTrue(verifyStep(.chess(id: "c", prompt: "Mate in one", fen: "6k1/4P3/8/8/8/8/8/K7 w - - 0 1", solutions: ["e7e8q"], goal: "mate_in_1"), .move(chessMove(from: "e7", to: "e8", board: board))))
    }
}
