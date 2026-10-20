import AuthenticationServices
import CryptoKit
import SwiftUI

/// Minimal sign-in: email code (no phone number, no contacts). Debug builds without
/// Supabase offer a local test identity from the dev backend.
struct SignInView: View {
    @Environment(AppModel.self) private var app
    @State private var email = ""
    @State private var code = ""
    @State private var sent = false
    @State private var working = false
    @State private var appleNonce: String?
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: WMSpacing.md) {
                Text("Sign in").font(.largeTitle.bold()).foregroundStyle(WMColor.textPrimary).accessibilityAddTraits(.isHeader)
                Text("Sign in to keep your friends and settings. We never ask for your phone number or contacts.")
                    .foregroundStyle(WMColor.textSecondary)
                if AppConfig.supabaseConfigured {
                    // Doc 02 §9: Apple, then email; never a phone number.
                    SignInWithAppleButton(.continue) { request in
                        let raw = UUID().uuidString + UUID().uuidString
                        appleNonce = raw
                        request.requestedScopes = [] // a name is asked for on the profile screen; no email needed
                        request.nonce = SHA256.hash(data: Data(raw.utf8)).map { String(format: "%02x", $0) }.joined()
                    } onCompletion: { result in
                        guard case let .success(auth) = result,
                              let credential = auth.credential as? ASAuthorizationAppleIDCredential,
                              let token = credential.identityToken.flatMap({ String(data: $0, encoding: .utf8) }),
                              let nonce = appleNonce else { return } // cancelled: nothing to say
                        Task { await app.signInWithApple(idToken: token, rawNonce: nonce) }
                    }
                    .signInWithAppleButtonStyle(colorScheme == .dark ? .white : .black)
                    .frame(height: WMSize.minTouchTarget)
                    WMCard {
                        Text("Email me a code").font(.headline).foregroundStyle(WMColor.textPrimary)
                        TextField("Email", text: $email)
                            .textContentType(.emailAddress).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled()
                            .padding(WMSpacing.sm).background(WMColor.surfaceSunken, in: RoundedRectangle(cornerRadius: WMRadius.sm))
                        if !sent {
                            SecondaryButton(title: working ? "Sending…" : "Send code") {
                                working = true
                                Task { sent = await app.requestCode(email); working = false }
                            }
                            .disabled(!email.contains("@") || working)
                        } else {
                            TextField("Code from the email", text: $code)
                                .textContentType(.oneTimeCode).keyboardType(.numberPad)
                                .padding(WMSpacing.sm).background(WMColor.surfaceSunken, in: RoundedRectangle(cornerRadius: WMRadius.sm))
                            PrimaryButton(title: "Sign in") { Task { await app.verifyCode(email, code) } }
                                .disabled(code.count < 6)
                        }
                    }
                } else {
                    Text("Email sign-in isn't configured for this build.").foregroundStyle(WMColor.textSecondary)
                }
                if AppConfig.useDevAuth {
                    SecondaryButton(title: "Use a local test account (debug build)") { Task { await app.devSignIn() } }
                }
                Button("Back") { app.backFromSignIn() }
                    .frame(minHeight: WMSize.minTouchTarget)
            }
            .padding(WMSpacing.md).frame(maxWidth: 640).frame(maxWidth: .infinity)
        }
        .background(WMColor.surface)
    }
}

/// Display name and age eligibility (18+). The time zone is used for quiet hours and wake windows.
struct ProfileView: View {
    @Environment(AppModel.self) private var app
    @State private var name = ""
    @State private var adult = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: WMSpacing.md) {
                Text("About you").font(.largeTitle.bold()).foregroundStyle(WMColor.textPrimary).accessibilityAddTraits(.isHeader)
                TextField("Display name", text: $name)
                    .textContentType(.nickname)
                    .padding(WMSpacing.sm).background(WMColor.surfaceSunken, in: RoundedRectangle(cornerRadius: WMRadius.sm))
                    .onChange(of: name) { _, v in if v.count > 40 { name = String(v.prefix(40)) } }
                    .accessibilityIdentifier("displayName")
                Toggle("I'm 18 or older", isOn: $adult).foregroundStyle(WMColor.textPrimary)
                Text("Your time zone (\(TimeZone.current.identifier)) is used for quiet hours and wake windows. You can change it later.")
                    .foregroundStyle(WMColor.textSecondary)
                PrimaryButton(title: "Continue") { Task { await app.createProfile(name: name, adult: adult) } }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || !adult)
            }
            .padding(WMSpacing.md).frame(maxWidth: 640).frame(maxWidth: .infinity)
        }
        .background(WMColor.surface)
    }
}
