package app.wakeme.ui.challenges

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
import app.wakeme.core.WakeMeJson
import app.wakeme.data.WakeMeApi
import app.wakeme.data.net.ApiException
import app.wakeme.data.net.ChallengeDto
import app.wakeme.data.net.TemplateDto
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
            _s.value = _s.value.copy(templates = api.templates(), challenges = api.challenges(), credits = api.me().billing.credits.available)
        }.onFailure { _s.value = _s.value.copy(error = (it as? ApiException)?.message) }
    }

    private fun guard(label: String, block: suspend () -> Unit) = viewModelScope.launch {
        _s.value = _s.value.copy(busy = label)
        try {
            block()
        } catch (e: ApiException) {
            val details = (e.code == "challenge_invalid")
            _s.value = _s.value.copy(error = if (details) "${e.message} Check answers, lengths and that it's safe and gentle." else e.message, needsPlus = e.code == "credits_exhausted")
        } finally {
            _s.value = _s.value.copy(busy = null)
            load()
        }
    }

    fun fromTemplate(id: String, difficulty: String) = guard("template") { tryOut(api.fromTemplate(id, difficulty)) }

    fun createQuiz(title: String, questions: List<Triple<String, String, List<String>>>) = guard("quiz") {
        val steps = buildJsonArray {
            questions.forEachIndexed { i, (q, answer, wrong) ->
                if (wrong.isEmpty()) add(buildJsonObject { put("type", "short_answer"); put("id", "q${i + 1}"); put("prompt", q); put("acceptedAnswers", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(answer)) }); put("source", "creator_provided") })
                else {
                    val options = (wrong + answer).shuffled()
                    add(buildJsonObject {
                        put("type", "multiple_choice"); put("id", "q${i + 1}"); put("prompt", q)
                        put("options", buildJsonArray { options.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                        put("correctIndex", options.indexOf(answer)); put("source", "creator_provided")
                    })
                }
            }
        }
        val body = buildJsonObject {
            put("schemaVersion", 1); put("title", title.ifBlank { "Our morning quiz" }); put("kind", "sequence"); put("difficulty", "easy")
            put("maxDurationSeconds", (questions.size * 25 + 30).coerceAtMost(600)); put("steps", steps); put("verification", "local_deterministic")
            put("fallback", buildJsonObject { put("type", "accessible_checkin") }); put("permissionsRequired", JsonArray(emptyList()))
        }
        tryOut(api.createChallenge(body))
    }

    /** AI drafts from facts the creator supplies; the result is an editable draft, never active. */
    fun generate(prompt: String, facts: List<Triple<String, String, List<String>>>, difficulty: String) = guard("ai") {
        val g = api.generate(prompt, facts, difficulty)
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

    private fun tryOut(c: ChallengeDto) {
        val latest = c.revisions.maxByOrNull { it.revision } ?: return
        val def = WakeMeJson.decodeFromJsonElement(ChallengeDefinition.serializer(), latest.body)
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
fun ChallengesScreen(onNew: () -> Unit, onUpgrade: () -> Unit, vm: ChallengesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
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
fun ChallengeEditorScreen(onDone: () -> Unit, onUpgrade: () -> Unit, vm: ChallengesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    s.trying?.let { (c, def) -> TryOut(c, def, vm); return }
    LaunchedEffect(s.saved) { if (s.saved) onDone() }
    var mode by remember { mutableStateOf("quiz") }
    var title by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var difficulty by remember { mutableStateOf("easy") }
    val facts = remember { mutableStateListOf(Triple("", "", listOf<String>())) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(WakeMeTokens.spaceMd), verticalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceMd)) {
        Text("Create a challenge", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = WM.colors.textPrimary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(WakeMeTokens.spaceXs)) {
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
                OutlinedTextField(q, { facts[i] = Triple(it.take(200), a, wrong) }, label = { Text("Question ${i + 1}") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(a, { facts[i] = Triple(q, it.take(60), wrong) }, label = { Text("Your answer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(wrong.joinToString(", "), { facts[i] = Triple(q, a, it.split(",").map(String::trim).filter(String::isNotEmpty).take(3)) }, label = { Text("Wrong options (comma separated, optional)") }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (facts.size < 8) QuietButton("Add another question", onClick = { facts += Triple("", "", emptyList()) })
        val complete = facts.filter { it.first.isNotBlank() && it.second.isNotBlank() }
        if (mode == "ai") PrimaryButton(if (s.busy == "ai") "Drafting…" else "Draft it", onClick = { vm.generate(prompt, complete, difficulty) }, enabled = prompt.isNotBlank() && s.busy == null)
        else PrimaryButton(if (s.busy == "quiz") "Saving…" else "Save and try it", onClick = { vm.createQuiz(title, complete) }, enabled = complete.isNotEmpty() && s.busy == null)
        QuietButton("Cancel", onClick = onDone)
    }
    Messages(s, vm, onUpgrade)
}

@Suppress("unused")
private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content ?: this[key]?.jsonObject?.toString()
