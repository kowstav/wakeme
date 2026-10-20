package app.wakeme.ui.challenges

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.wakeme.challenge.ChallengeRunner
import app.wakeme.core.ChallengeDefinition
import app.wakeme.core.QuizQuestion
import app.wakeme.core.WakeMeJson
import app.wakeme.core.quizDefinition
import app.wakeme.core.quizQuestions
import app.wakeme.data.WakeMeApi
import app.wakeme.data.net.ApiException
import app.wakeme.data.net.ChallengeDto
import app.wakeme.data.net.TemplateDto
import app.wakeme.data.net.OfflineException
import app.wakeme.designsystem.Body
import app.wakeme.designsystem.Gap
import app.wakeme.designsystem.PrimaryButton
import app.wakeme.designsystem.QuietButton
import app.wakeme.designsystem.SecondaryButton
import app.wakeme.designsystem.SectionTitle
import app.wakeme.designsystem.StatusPill
import app.wakeme.designsystem.Tone
import app.wakeme.designsystem.WM
import app.wakeme.designsystem.WMCard
import app.wakeme.designsystem.WakeMeTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

data class ChallengesState(
    val templates: List<TemplateDto> = emptyList(),
    val challenges: List<ChallengeDto> = emptyList(),
    val credits: Int? = null,
    val trying: Pair<ChallengeDto, ChallengeDefinition>? = null,
    val busy: String? = null,
    val notes: String? = null,
    val error: String? = null,
    val needsPlus: Boolean = false,
    val saved: Boolean = false,
    val meId: String? = null,
    /** The challenge whose next version is being written. */
    val editing: ChallengeDto? = null,
)

@HiltViewModel
class ChallengesViewModel @Inject constructor(private val api: WakeMeApi) : ViewModel() {
    private val _s = MutableStateFlow(ChallengesState())
    val state: StateFlow<ChallengesState> = _s

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        runCatching {
            val me = api.me()
            _s.value = _s.value.copy(templates = api.templates(), challenges = api.challenges(), credits = me.billing.credits.available, meId = me.id)
        }.onFailure { _s.value = _s.value.copy(error = (it as? ApiException)?.message) }
    }

    private fun guard(label: String, block: suspend () -> Unit) = viewModelScope.launch {
        _s.value = _s.value.copy(busy = label)
        try {
            block()
        } catch (e: OfflineException) {
            _s.value = _s.value.copy(error = "You're offline. Nothing was changed.")
        } catch (e: ApiException) {
            val details = (e.code == "challenge_invalid")
            _s.value = _s.value.copy(error = if (details) "${e.message} Check answers, lengths and that it's safe and gentle." else e.message, needsPlus = e.code == "credits_exhausted")
        } finally {
            _s.value = _s.value.copy(busy = null)
            load()
        }
    }

    fun fromTemplate(id: String, difficulty: String) = guard("template") { tryOut(api.fromTemplate(id, difficulty)) }

    /** A new quiz, or (while [ChallengesState.editing]) a new draft version of the one being edited. */
    fun createQuiz(title: String, questions: List<QuizQuestion>) = guard("quiz") {
        val editing = _s.value.editing
        val before = editing?.let { latestDefinition(it) }
        val def = quizDefinition(title, questions, before?.difficulty ?: "easy", before?.steps.orEmpty())
        val body = WakeMeJson.encodeToJsonElement(ChallengeDefinition.serializer(), def)
        tryOut(if (editing != null) api.reviseChallenge(editing.id, body) else api.createChallenge(body))
    }

    fun startEditing(id: String) = guard("load") {
        _s.value = _s.value.copy(editing = api.challenge(id))
    }

    /** Only the creator edits, and only quizzes (templates are made again from the template). */
    fun canEdit(c: ChallengeDto) = c.creatorId == _s.value.meId && latestDefinition(c)?.let(::quizQuestions) != null

    /** AI drafts from facts the creator supplies; the result is an editable draft, never active. */
    fun generate(prompt: String, facts: List<QuizQuestion>, difficulty: String) = guard("ai") {
        // The AI author takes at most three wrong options per fact.
        val g = api.generate(prompt, facts.map { Triple(it.prompt, it.answer, it.wrong.take(3)) }, difficulty)
        var status = g
        repeat(30) {
            if (status.status in setOf("succeeded", "rejected", "failed")) return@repeat
            delay(2000)
            status = api.generation(g.id)
        }
        when (status.status) {
            "succeeded" -> {
                val notes = (status.rejection as? JsonObject)?.get("notes")?.jsonPrimitive?.content
                _s.value = _s.value.copy(notes = notes)
                tryOut(api.challenge(status.definitionId!!))
            }
            "rejected" -> _s.value = _s.value.copy(error = (status.rejection as? JsonObject)?.get("message")?.jsonPrimitive?.content ?: "That can't be made into a safe challenge.")
            "failed" -> _s.value = _s.value.copy(error = "Drafting didn't work this time. Your credit was returned.")
            else -> _s.value = _s.value.copy(error = "Still drafting. Check back in a moment.")
        }
    }

    private fun latestDefinition(c: ChallengeDto): ChallengeDefinition? {
        val latest = c.revisions.maxByOrNull { it.revision } ?: return null
        return runCatching { WakeMeJson.decodeFromJsonElement(ChallengeDefinition.serializer(), latest.body) }.getOrNull()
    }

    private fun tryOut(c: ChallengeDto) {
        val def = latestDefinition(c) ?: return
        _s.value = _s.value.copy(trying = c to def)
    }

    fun open(c: ChallengeDto) = tryOut(c)

    /** Publishing requires trying it first; the published revision is then frozen and signed. */
    fun finishedTrying(publish: Boolean) = guard("publish") {
        val (c, _) = _s.value.trying ?: return@guard
        val rev = c.revisions.maxBy { it.revision }
        if (publish && rev.state == "draft") {
            api.markPreviewed(c.id, rev.revision)
            api.publish(c.id, rev.revision)
            _s.value = _s.value.copy(saved = true)
        }
        _s.value = _s.value.copy(trying = null)
    }

    fun clear() {
        _s.value = _s.value.copy(error = null, notes = null, needsPlus = false)
    }
}

@Composable
fun ChallengesScreen(onNew: () -> Unit, onEdit: (String) -> Unit, onUpgrade: () -> Unit, vm: ChallengesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    // Coming back from the editor shows what was just published.
    LaunchedEffect(Unit) { vm.load() }
    s.trying?.let { (c, def) -> TryOut(c, def, vm); return }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = WakeMeTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceSm)) {
        item {
            Gap()
            Text("Challenges", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary)
            Body("Build challenges from your group's own jokes and goals. They run on the phone, even offline, and always have a way out.")
            PrimaryButton("Create a challenge", onClick = onNew)
        }
        item { SectionTitle("Start from a template") }
        items(s.templates, key = { it.id }) { t ->
            WMCard {
                Text(t.title, style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
                Body(t.description)
                SecondaryButton("Try it", onClick = { vm.fromTemplate(t.id, "easy") })
            }
        }
        if (s.challenges.isNotEmpty()) item { SectionTitle("Yours and your circles'") }
        items(s.challenges, key = { it.id }) { c ->
            WMCard {
                Text(c.title, style = MaterialTheme.typography.titleMedium, color = WM.colors.textPrimary)
                StatusPill(if (c.publishedRevision != null) "Ready to use · version ${c.publishedRevision}" else "Draft", if (c.publishedRevision != null) Tone.Success else Tone.Neutral)
                QuietButton("Open", onClick = { vm.open(c) })
                if (vm.canEdit(c)) QuietButton("Edit", onClick = { onEdit(c.id) })
            }
        }
        item { Gap(WakeMeTokens.spaceXl) }
    }
    Messages(s, vm, onUpgrade)
}

@Composable
private fun TryOut(c: ChallengeDto, def: ChallengeDefinition, vm: ChallengesViewModel) {
    var finished by remember { mutableStateOf(false) }
    val draft = c.revisions.maxBy { it.revision }.state == "draft"
    if (!finished) {
        Column {
            StatusPill(if (draft) "Trying your draft. Answers are checked on this phone." else "Practice run", Tone.Info)
            ChallengeRunner(def, onComplete = { finished = true }, onEscape = { finished = true }, escapeLabel = "Stop trying")
        }
    } else {
        Column(Modifier.padding(WakeMeTokens.spaceLg), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd)) {
            Text(def.title, style = MaterialTheme.typography.headlineSmall, color = WM.colors.textPrimary)
            if (draft) {
                Body("Publishing freezes this version. Friends who accept it get exactly this; later edits become a new version they must accept.")
                PrimaryButton("Publish", onClick = { vm.finishedTrying(publish = true) })
                SecondaryButton("Keep as draft", onClick = { vm.finishedTrying(publish = false) })
            } else PrimaryButton("Done", onClick = { vm.finishedTrying(publish = false) })
        }
    }
}

@Composable
private fun Messages(s: ChallengesState, vm: ChallengesViewModel, onUpgrade: () -> Unit) {
    s.error?.let {
        AlertDialog(
            onDismissRequest = vm::clear,
            confirmButton = { TextButton(onClick = { vm.clear(); if (s.needsPlus) onUpgrade() }) { Text(if (s.needsPlus) "See Plus" else "OK") } },
            dismissButton = { if (s.needsPlus) TextButton(onClick = vm::clear) { Text("Build a quiz myself") } },
            text = { Text(it) },
        )
    }
    s.notes?.let { AlertDialog(onDismissRequest = vm::clear, confirmButton = { TextButton(onClick = vm::clear) { Text("OK") } }, title = { Text("About this draft") }, text = { Text(it) }) }
}

@Composable
fun ChallengeEditorScreen(onDone: () -> Unit, onUpgrade: () -> Unit, editId: String? = null, vm: ChallengesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    LaunchedEffect(editId) { if (editId != null) vm.startEditing(editId) }
    var mode by remember { mutableStateOf("quiz") }
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var difficulty by remember { mutableStateOf("easy") }
    val facts = remember { mutableStateListOf(QuizRow()) }
    var filledFrom by remember { mutableStateOf<String?>(null) }
    val editing = s.editing
    val editQuestions = remember(editing) {
        editing?.revisions?.maxByOrNull { it.revision }
            ?.let { runCatching { WakeMeJson.decodeFromJsonElement(ChallengeDefinition.serializer(), it.body) }.getOrNull() }
            ?.let { def -> quizQuestions(def)?.let { def.title to it } }
    }
    // Fill the form once from the version being edited.
    LaunchedEffect(editQuestions) {
        if (editing != null && editQuestions != null && filledFrom != editing.id) {
            title = editQuestions.first
            facts.clear(); facts.addAll(editQuestions.second.map { QuizRow(it.prompt, it.answer, it.wrong.joinToString(", ")) })
            filledFrom = editing.id
        }
    }
    s.trying?.let { (c, def) -> TryOut(c, def, vm); return }
    LaunchedEffect(s.saved) { if (s.saved) onDone() }
    if (editId != null && filledFrom != editId) {
        Column(Modifier.padding(WakeMeTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd)) {
            Body(if (editing != null && editQuestions == null) "Only quizzes can be edited. Make a template challenge again to change it." else "Loading…")
            QuietButton("Back", onClick = onDone)
        }
        Messages(s, vm, onUpgrade)
        return
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(WakeMeTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd)) {
        Text(if (editId != null) "Edit challenge" else "Create a challenge", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary)
        if (editId != null) {
            Body("Your changes become version ${(editing?.latestRevision ?: 0) + 1}. You try it before publishing, and friends keep the version they accepted until they accept the new one.")
        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
            FilterChip(selected = mode == "quiz", onClick = { mode = "quiz" }, label = { Text("Build a quiz myself") })
            FilterChip(selected = mode == "ai", onClick = { mode = "ai" }, label = { Text("Describe it") })
        }
        if (mode == "ai") {
            s.credits?.let { Body("$it AI creation${if (it == 1) "" else "s"} left. Saved challenges never use credits again.") }
            OutlinedTextField(prompt, { prompt = it.take(500) }, label = { Text("Describe a challenge only your group would get") }, placeholder = { Text("Ask which café we call headquarters. Use the choices I give.") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
                listOf("easy", "medium", "hard").forEach { d -> FilterChip(selected = difficulty == d, onClick = { difficulty = d }, label = { Text(d) }) }
            }
        } else {
            OutlinedTextField(title, { title = it.take(60) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Body(if (mode == "ai") "Add the facts and answers. The AI only uses what you write here; it never invents details about your friends." else "Each question needs your answer. Add wrong options to make it multiple choice.")
        facts.forEachIndexed { i, (q, a, wrong) ->
            WMCard {
                OutlinedTextField(q, { facts[i] = facts[i].copy(prompt = it.take(200)) }, label = { Text("Question ${i + 1}") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(a, { facts[i] = facts[i].copy(answer = it.take(60)) }, label = { Text("Your answer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(wrong, { facts[i] = facts[i].copy(wrong = it.take(300)) }, label = { Text("Wrong options (comma separated, optional)") }, modifier = Modifier.fillMaxWidth())
                if (facts.size > 1) QuietButton("Remove question ${i + 1}", onClick = { facts.removeAt(i) })
            }
        }
        if (facts.size < 8) QuietButton("Add another question", onClick = { facts += QuizRow() })
        val complete = facts.map { it.question() }.filter { it.prompt.isNotBlank() && it.answer.isNotBlank() }
        if (mode == "ai") PrimaryButton(if (s.busy == "ai") "Drafting…" else "Draft it", onClick = { vm.generate(prompt, complete, difficulty) }, enabled = prompt.isNotBlank() && s.busy == null)
        else PrimaryButton(if (s.busy == "quiz") "Saving…" else "Save and try it", onClick = { vm.createQuiz(title, complete) }, enabled = complete.isNotEmpty() && s.busy == null)
        QuietButton("Cancel", onClick = onDone)
    }
    Messages(s, vm, onUpgrade)
}

@Suppress("unused")
private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content ?: this[key]?.jsonObject?.toString()

/** One question while it's being typed; wrong options stay as typed (commas included) until saved. */
private data class QuizRow(val prompt: String = "", val answer: String = "", val wrong: String = "") {
    fun question() = QuizQuestion(prompt.trim(), answer.trim(), wrong.split(",").map(String::trim).filter(String::isNotEmpty).take(5))
}
