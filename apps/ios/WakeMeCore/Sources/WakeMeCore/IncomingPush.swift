import Foundation

/// A remote command as it arrives in a push: the `wm` dictionary the server sends to both
/// platforms (Android reads the same keys from FCM data). Nil when a required field is missing,
/// so a malformed push is ignored rather than guessed at.
public func incomingCommand(fromPush data: [String: String]) -> IncomingCommand? {
    guard data["t"] == "command",
          let id = data["id"], !id.isEmpty,
          let capability = data["cap"],
          let actor = data["actor"],
          let grantVersion = data["gv"].flatMap(Int.init),
          let expiresAt = data["exp"] else { return nil }
    return IncomingCommand(id: id, capability: capability, actorId: actor, grantVersion: grantVersion, expiresAt: expiresAt,
                           soundId: data["snd"], ringSeconds: data["ring"].flatMap(Int.init))
}

/// The `wm` dictionary from an APNs payload's user info; values are strings on the wire.
public func pushData(fromUserInfo userInfo: [AnyHashable: Any]) -> [String: String] {
    guard let wm = userInfo["wm"] as? [String: Any] else { return [:] }
    var out: [String: String] = [:]
    for (k, v) in wm {
        if let s = v as? String { out[k] = s } else if let n = v as? NSNumber { out[k] = n.stringValue }
    }
    return out
}

/// What this phone tells the server about a local decision (same mapping as Android's
/// IncomingHandler). A duplicate is reported once, by the first delivery, so it returns nil.
public func receipt(for decision: LocalDecision) -> (kind: String, reason: String?)? {
    switch decision {
    case .skip(reason: "duplicate"): return nil
    case let .skip(reason): return ("execution_refused", reason)
    case .execute: return ("received", nil)
    }
}
