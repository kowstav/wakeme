import SwiftUI
import WakeMeAPI
import WakeMeCore

/// Challenges from your group's own jokes and goals: start from a template, write a quiz, or
/// describe one for an AI draft (doc 03; same flows as Android). Every draft is tried before
/// it can be published, and published ones can be practised any time. They run on the phone,
/// even offline, and always have a way out.
struct ChallengesView: View {
    @Environment(AppModel.self) private var app
    @State private var challenges: [WakeMeAPI.ChallengeSummary] = []
    @State private var templates: [WakeMeAPI.ChallengeTemplate] = []
    @State private var loaded = false
    @State private var practice: PracticeRun?
    @State private var tryOut: TryOut?
    @State private var pendingTryOut: TryOut?
    @State private var creating = false
    @State private var editing: EditTarget?
    @State private var busy = false
    @State private var message: String?

    struct PracticeRun: Identifiable { let id: String; let definition: ChallengeDefinition }
    /// Your own quiz, opened to write its next version.
    struct EditTarget: Identifiable { let id: String; let definition: ChallengeDefinition; let nextRevision: Int }

    /// A draft is tried before it can be published; publishing freezes exactly what was tried.
    struct TryOut: Identifiable {
        let challengeId: String
        let revision: Int
        let draft: Bool
        let definition: ChallengeDefinition
        /// What the AI author said about its draft, if it wrote one.
        var notes: String?
        var id: String { "\(challengeId):\(revision)" }
    }

    var body: some View {
        List {
            Group {
                Section {
                    Text("Build challenges from your group's own jokes and goals. They run on the phone, even offline, and always have a way out.")
                        .foregroundStyle(WMColor.textSecondary)
                    Button("Create a challenge") { creating = true }.frame(minHeight: WMSize.minTouchTarget)
                }
                let ready = challenges.filter { $0.publishedRevisionId != nil }
                let drafts = challenges.filter { $0.publishedRevision == nil && $0.creatorId == app.me?.id }
                if loaded && ready.isEmpty && drafts.isEmpty {
                    Section { Text("No challenges yet. Make one, or start from a template.").foregroundStyle(WMColor.textSecondary) }
                }
                if !ready.isEmpty {
                    Section("Try one") {
                        ForEach(ready) { c in
                            HStack {
                                Button(c.title) { Task { await practise(c) } }
                                    .buttonStyle(.borderless)
                                    .frame(maxWidth: .infinity, minHeight: WMSize.minTouchTarget, alignment: .leading)
                                if c.creatorId == app.me?.id {
                                    Button("Edit") { Task { await edit(c.id) } }
                                        .buttonStyle(.borderless)
                                        .frame(minHeight: WMSize.minTouchTarget)
                                        .accessibilityLabel("Edit \(c.title)")
                                        .disabled(busy)
                                }
                            }
                        }
                    }
                }
                if !drafts.isEmpty {
                    Section {
                        ForEach(drafts) { c in
                            Button(c.title) { Task { await openDraft(c.id) } }.frame(minHeight: WMSize.minTouchTarget)
                        }
                    } header: {
                        Text("Your drafts")
                    } footer: {
                        Text("Try a draft to publish it.")
                    }
                }
                if !templates.isEmpty {
                    Section("Start from a template") {
                        ForEach(templates) { t in
                            VStack(alignment: .leading, spacing: WMSpacing.xxs) {
                                Text(t.title).font(.headline).foregroundStyle(WMColor.textPrimary)
                                Text(t.description).foregroundStyle(WMColor.textSecondary)
                                Button("Try “\(t.title)”") { Task { await fromTemplate(t) } }
                                    .frame(minHeight: WMSize.minTouchTarget)
                                    .disabled(busy)
                            }
                        }
                    }
                }
            }
            .cardRows()
        }
        .scrollContentBackground(.hidden)
        .background(WMColor.surface)
        .navigationTitle("Challenges")
        .refreshable { await load() }
        .task { await load() }
        .sheet(isPresented: $creating, onDismiss: {
            // Open the try-out once the sheet has gone.
            if let next = pendingTryOut {
                pendingTryOut = nil
                tryOut = next
            }
        }) {
            ChallengeCreatorView { detail, notes in
                pendingTryOut = tryOutFor(detail, notes: notes)
                creating = false
            }
        }
        .sheet(item: $editing, onDismiss: {
            if let next = pendingTryOut {
                pendingTryOut = nil
                tryOut = next
            }
        }) { target in
            ChallengeCreatorView(editing: target) { detail, _ in
                pendingTryOut = tryOutFor(detail)
                editing = nil
            }
        }
        .fullScreenCover(item: $practice) { run in
            ChallengeRunnerView(definition: run.definition, escapeLabel: "Close", onComplete: { _ in practice = nil }, onEscape: { practice = nil })
        }
        .fullScreenCover(item: $tryOut) { t in
            TryOutView(tryOut: t) { publish in
                tryOut = nil
                Task { await finish(t, publish: publish) }
            }
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") { message = nil } }
    }

    private func load() async {
        if let list = try? await app.api.challenges() { challenges = list }
        if templates.isEmpty, let list = try? await app.api.challengeTemplates() { templates = list }
        loaded = true
    }

    private func practise(_ c: WakeMeAPI.ChallengeSummary) async {
        guard let revision = c.publishedRevisionId else { return }
        if await ChallengeStore.shared.ensureInstalled(revision, api: app.api), let definition = await ChallengeStore.shared.definition(revision) {
            practice = PracticeRun(id: revision, definition: definition)
        } else {
            message = "This challenge couldn't be checked and installed. Try again when you're connected."
        }
    }

    /// Opens the latest version of your quiz in the editor; saving it makes the next version.
    private func edit(_ id: String) async {
        busy = true
        defer { busy = false }
        do {
            let detail = try await app.api.challenge(id)
            guard let latest = detail.latest, let definition = latest.definition, WakeMeAPI.quizQuestions(in: definition) != nil else {
                message = "Only quizzes can be edited. Make a template challenge again to change it."
                return
            }
            editing = EditTarget(id: detail.id, definition: definition, nextRevision: latest.revision + 1)
        } catch {
            message = (error as? APIError)?.message ?? "You're offline. Try again when you're connected."
        }
    }

    private func openDraft(_ id: String) async {
        do {
            let detail = try await app.api.challenge(id)
            if let t = tryOutFor(detail) { tryOut = t } else { message = "This draft uses a step this iPhone can't run yet." }
        } catch {
            message = (error as? APIError)?.message ?? "You're offline. Try again when you're connected."
        }
    }

    private func fromTemplate(_ t: WakeMeAPI.ChallengeTemplate) async {
        busy = true
        defer { busy = false }
        do {
            let detail = try await app.api.challengeFromTemplate(t.id)
            tryOut = tryOutFor(detail)
            await load()
        } catch {
            message = (error as? APIError)?.message ?? "You're offline. Nothing was made."
        }
    }

    private func tryOutFor(_ detail: WakeMeAPI.ChallengeDetail, notes: String? = nil) -> TryOut? {
        guard let latest = detail.latest, let definition = latest.definition else { return nil }
        return TryOut(challengeId: detail.id, revision: latest.revision, draft: latest.state == "draft", definition: definition, notes: notes)
    }

    private func finish(_ t: TryOut, publish: Bool) async {
        if publish && t.draft {
            do {
                try await app.api.markPreviewed(challengeId: t.challengeId, revision: t.revision)
                _ = try await app.api.publishChallenge(t.challengeId, revision: t.revision)
                message = "Published. You can use it in your wake-ups, and friends you allow can assign it."
            } catch {
                message = (error as? APIError)?.message ?? "You're offline. It's still a draft."
            }
        }
        await load()
    }
}

/// Runs a challenge as its friends would, then offers to publish a draft.
private struct TryOutView: View {
    let tryOut: ChallengesView.TryOut
    let onFinish: (_ publish: Bool) -> Void
    /// nil while running; true when every step was done, false when stopped early.
    @State private var completed: Bool?

    var body: some View {
        if let completed {
            ScrollView {
                VStack(alignment: .leading, spacing: WMSpacing.md) {
                    Text(tryOut.definition.title).font(.largeTitle.bold()).foregroundStyle(WMColor.textPrimary).accessibilityAddTraits(.isHeader)
                    StatusPill(text: completed ? "You finished it." : "You stopped before the end. Friends always have the same way out.", tone: completed ? .success : .neutral)
                    if let notes = tryOut.notes {
                        Text("About this draft: \(notes)").foregroundStyle(WMColor.textPrimary)
                    }
                    if tryOut.draft {
                        Text("Publishing freezes this version. Friends who accept it get exactly this; later edits become a new version they must accept.")
                            .foregroundStyle(WMColor.textSecondary)
                        PrimaryButton(title: "Publish") { onFinish(true) }
                        SecondaryButton(title: "Keep as draft") { onFinish(false) }
                    } else {
                        PrimaryButton(title: "Done") { onFinish(false) }
                    }
                }
                .padding(WMSpacing.lg).frame(maxWidth: 640).frame(maxWidth: .infinity)
            }
            .background(WMColor.surface)
        } else {
            ChallengeRunnerView(definition: tryOut.definition, escapeLabel: "Stop trying", onComplete: { _ in completed = true }, onEscape: { completed = false })
        }
    }
}

/// Write a quiz, or describe a challenge for an AI draft (one credit). Either way the result is
/// a draft to try; nothing is active until it's published and someone accepts it.
struct ChallengeCreatorView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    /// Your quiz whose next version this writes; nil to make a new challenge.
    var editing: ChallengesView.EditTarget? = nil
    /// The new draft, and notes from the AI author about it, if any.
    let onCreated: (WakeMeAPI.ChallengeDetail, String?) -> Void

    enum Mode: String, CaseIterable { case quiz = "Write a quiz", ai = "Describe it" }
    struct Question: Identifiable {
        let id = UUID()
        var question = ""
        var answer = ""
        var wrong = ""
    }

    @State private var mode = Mode.quiz
    @State private var title = ""
    @State private var questions = [Question()]
    @State private var prompt = ""
    @State private var difficulty = "easy"
    @State private var working = false
    @State private var progress: String?
    @State private var message: String?
    @State private var outOfCredits = false
    @State private var showPlus = false
    @State private var filledFromEdit = false

    private var filled: [WakeMeAPI.QuizQuestion] {
        questions.compactMap { q in
            let question = q.question.trimmingCharacters(in: .whitespacesAndNewlines)
            let answer = q.answer.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !question.isEmpty, !answer.isEmpty else { return nil }
            let wrong = q.wrong.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            // Up to five wrong options (six choices); the AI author takes at most three of them.
            return .init(question: question, answer: answer, wrongOptions: Array(wrong.prefix(5)))
        }
    }
    private var canCreate: Bool {
        switch mode {
        case .quiz: !filled.isEmpty
        case .ai: !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    /// A label that stays visible after typing (a placeholder disappears).
    private func labelled<Field: View>(_ label: String, @ViewBuilder _ field: () -> Field) -> some View {
        VStack(alignment: .leading, spacing: WMSpacing.xxs) {
            Text(label).font(.footnote).foregroundStyle(WMColor.textSecondary).accessibilityHidden(true)
            field().accessibilityLabel(label)
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    if let editing {
                        Section {
                            Text("Your changes become version \(editing.nextRevision). You try it before publishing, and friends keep the version they accepted until they accept the new one.")
                                .foregroundStyle(WMColor.textSecondary)
                        }
                    } else {
                        Section {
                            Picker("How", selection: $mode) {
                                ForEach(Mode.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                            }
                            .pickerStyle(.segmented)
                        }
                    }
                    if mode == .ai {
                        Section {
                            TextField("Describe a challenge only your group would get", text: $prompt, axis: .vertical)
                                .lineLimit(3...6)
                                .onChange(of: prompt) { _, v in if v.count > 500 { prompt = String(v.prefix(500)) } }
                            Picker("Difficulty", selection: $difficulty) {
                                Text("Easy").tag("easy")
                                Text("Medium").tag("medium")
                                Text("Hard").tag("hard")
                            }
                        } footer: {
                            let credits = app.me?.billing.credits.available ?? 0
                            Text("\(credits) AI creation\(credits == 1 ? "" : "s") left. Saved challenges never use credits again. Quiz answers come only from the facts below.")
                        }
                    } else {
                        Section {
                            labelled("Title") {
                                TextField("e.g. Thursday quiz", text: $title)
                                    .accessibilityIdentifier("quizTitle")
                                    .onChange(of: title) { _, v in if v.count > 60 { title = String(v.prefix(60)) } }
                            }
                        }
                    }
                    ForEach($questions) { $q in
                        Section(mode == .ai ? "Fact (optional)" : "Question") {
                            labelled("Question") {
                                TextField("e.g. What do we call our Thursday dinner?", text: $q.question, axis: .vertical)
                                    .accessibilityIdentifier("quizQuestion")
                                    .onChange(of: q.question) { _, v in if v.count > 200 { q.question = String(v.prefix(200)) } }
                            }
                            labelled("Right answer") {
                                TextField("e.g. Chaos curry", text: $q.answer)
                                    .accessibilityIdentifier("quizAnswer")
                                    .onChange(of: q.answer) { _, v in if v.count > 60 { q.answer = String(v.prefix(60)) } }
                            }
                            labelled("Wrong options (optional, comma separated)") {
                                TextField("e.g. Brunch, Pasta night", text: $q.wrong)
                                    .accessibilityIdentifier("quizWrong")
                            }
                        }
                    }
                    if questions.count < 8 {
                        Button("Add another") { questions.append(Question()) }
                    }
                    if let progress {
                        Section { Text(progress).foregroundStyle(WMColor.textSecondary) }
                    }
                }
                .cardRows()
            }
            .scrollContentBackground(.hidden)
            .background(WMColor.surface)
            .navigationTitle(editing == nil ? "Create a challenge" : "Edit challenge")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(working ? "Working…" : editing == nil ? "Create" : "Save") { Task { await create() } }.disabled(!canCreate || working)
                }
            }
            .onAppear(perform: fillFromEdit)
            .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") { message = nil } }
            .alert("You've used your AI creations for now", isPresented: $outOfCredits) {
                Button("See Plus") { showPlus = true }
                Button("Write a quiz myself", role: .cancel) { mode = .quiz }
            } message: {
                Text("Plus adds monthly AI credits. Writing a quiz yourself is always free, and saved challenges never use credits.")
            }
            .sheet(isPresented: $showPlus) { PaywallView() }
        }
    }

    /// The form starts from the version being edited (once, so typing isn't undone).
    private func fillFromEdit() {
        guard let editing, !filledFromEdit, let existing = WakeMeAPI.quizQuestions(in: editing.definition) else { return }
        filledFromEdit = true
        mode = .quiz
        title = editing.definition.title
        questions = existing.map { Question(question: $0.question, answer: $0.answer, wrong: $0.wrongOptions.joined(separator: ", ")) }
    }

    private func create() async {
        working = true
        defer {
            working = false
            progress = nil
        }
        do {
            switch mode {
            case .quiz:
                let name = title.trimmingCharacters(in: .whitespaces)
                let quizTitle = name.isEmpty ? "Our morning quiz" : name
                if let editing {
                    onCreated(try await app.api.reviseQuiz(editing.id, title: quizTitle, questions: filled, editing: editing.definition), nil)
                } else {
                    onCreated(try await app.api.createQuiz(title: quizTitle, questions: filled), nil)
                }
            case .ai:
                try await draftWithAI()
            }
        } catch let e as APIError where e.code == "credits_exhausted" {
            outOfCredits = true
        } catch let e as APIError where e.code == "challenge_invalid" {
            message = e.message + " Check answers, lengths and that it's safe and gentle."
        } catch {
            message = (error as? APIError)?.message ?? "You're offline. Nothing was made."
        }
    }

    /// The draft is made on the server; this waits for it (about a minute at most).
    private func draftWithAI() async throws {
        progress = "Drafting… this takes a few seconds."
        var generation = try await app.api.generateChallenge(prompt: prompt.trimmingCharacters(in: .whitespacesAndNewlines), facts: filled, difficulty: difficulty)
        for _ in 0..<30 where !["succeeded", "rejected", "failed"].contains(generation.status) {
            try await Task.sleep(for: .seconds(2))
            generation = try await app.api.challengeGeneration(generation.id)
        }
        await app.refreshMe() // credits
        switch generation.status {
        case "succeeded":
            guard let id = generation.definitionId else {
                message = "The draft couldn't be opened. Try again."
                return
            }
            onCreated(try await app.api.challenge(id), generation.rejection?.notes)
        case "rejected":
            message = generation.rejection?.message ?? "That can't be made into a safe challenge."
        case "failed":
            message = "Drafting didn't work this time. Your credit was returned."
        default:
            message = "Still drafting. Check back in a moment."
        }
    }
}
