package com.github.halfdeadgames.hdgife.harness

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.GdxRuntimeException
import com.bladecoder.ink.runtime.StoryException
import com.github.halfdeadgames.hdgife.HdgifeException
import com.github.halfdeadgames.hdgife.HdgifeLog
import com.github.halfdeadgames.hdgife.compiler.InkCompilerService
import com.github.halfdeadgames.hdgife.story.DialogStep
import com.github.halfdeadgames.hdgife.story.IncompatibleLibraryException
import com.github.halfdeadgames.hdgife.story.NumberFormat
import com.github.halfdeadgames.hdgife.story.NumberFormatOverride
import com.github.halfdeadgames.hdgife.story.Rounding
import com.github.halfdeadgames.hdgife.story.StoryEventListener
import com.github.halfdeadgames.hdgife.story.StoryManager
import com.github.halfdeadgames.hdgife.story.StoryStepException
import com.github.halfdeadgames.hdgife.story.VariableObservation
import ktx.log.logger
import kotlin.random.Random

private val log = logger<DialogController>()

private val watchedVariables = listOf("counter", "switchOn", "player_coins")

private const val autosaveFile = "autosave.json"

private class Snapshot(val state: String, val step: DialogStep, val number: Int)

interface DialogView {
    fun say(message: String)

    fun showStep(step: DialogStep, number: Int, note: String?, linesFrom: DialogStep)

    fun showFailure(step: DialogStep, number: Int, note: String, status: String)

    fun setStatus(text: String)

    fun refreshInfo()

    fun setChoiceMode(mode: ChoiceMode)

    fun setViewMode(mode: ViewMode?)

    fun setTypewriter(enabled: Boolean)
}

class DialogController(
    private val manager: StoryManager,
    private val provider: MapStateProvider,
    private val compiler: InkCompilerService,
    private val view: DialogView,
    private val mobile: Boolean,
) : DialogControl {
    var lastStep: DialogStep? = null
        private set
    private var stepNumber = 0
    private var snapshot: Snapshot? = null
    private val observations = mutableMapOf<String, VariableObservation>()

    val watched: Map<String, String> get() = watchedValues
    private val watchedValues = LinkedHashMap<String, String>()
    private var random = Random(1)
    private var fixedRoll: Int? = null

    fun start() {
        val engineLog = manager.log
        manager.log = HdgifeLog { message ->
            engineLog?.log(message)
            view.say("engine: $message")
        }
        manager.formatOverride = NumberFormatOverride { entityId, statName -> harnessFormatOverride(entityId, statName) }
        manager.onEvent = StoryEventListener { event ->
            view.say("event #${event.id} ${event.name}(${event.args.joinToString { show(it) }}) before line ${event.lineIndex}")
            val statName = event.args.firstOrNull()?.toString()
            val amount = event.args.getOrNull(1) as? Int
            if (event.name == "spend" && statName != null && amount != null) {
                val have = manager.stateProvider?.getStat("player", statName) as? Int ?: 0
                applyStat(manager, provider, "player", statName, have - amount)
            }
        }
        manager.bindFunction("safeDouble", lookaheadSafe = true) { args -> args.int(0) * 2 }
        manager.bindFunction("unsafeRoll") { args -> fixedRoll ?: random.nextInt(1, args.int(0) + 1) }
        syncPlayer()
        for (name in watchedVariables) watch(name)
    }

    fun dispose() {
        for (observation in observations.values) manager.unobserve(observation)
        observations.clear()
    }

    fun begin() {
        if (lastStep == null && !restoreAutosave()) advance()
    }

    private fun fail(what: String, e: Exception) {
        log.error(e) { what }
        view.say("$what: ${e.message}")
    }

    private fun advance() {
        showStep(readNext() ?: return)
    }

    private fun readNext(): DialogStep? = try {
        manager.next()
    } catch (e: StoryStepException) {
        showFailure(e)
        null
    }

    private fun showFailure(e: StoryStepException) {
        log.error(e) { "Story error" }
        var root: Throwable = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val why = root.message ?: root.toString()
        lastStep = e.step
        val status = "Story error: " + why.take(110).replace('\n', ' ') + if (why.length > 110) "..." else ""
        view.showFailure(e.step, ++stepNumber, "STORY ERROR: ${e.message} / cause: $why", status)
        view.say("Story error: ${e.message}")
    }

    private fun showStep(step: DialogStep, note: String? = null, number: Int = ++stepNumber, linesFrom: DialogStep = step) {
        lastStep = step
        view.showStep(step, number, note, linesFrom)
    }

    private fun restartView() {
        stepNumber = 0
        lastStep = null
        advance()
    }

    fun autosave() {
        if (!mobile || lastStep == null) return
        try {
            val temp = Gdx.files.local("$autosaveFile.tmp")
            temp.writeString(manager.saveState(), false, "UTF-8")
            temp.moveTo(Gdx.files.local(autosaveFile))
        } catch (e: Exception) {
            fail("Autosave failed", e)
        }
    }

    private fun restoreAutosave(): Boolean {
        val file = Gdx.files.local(autosaveFile)
        if (!mobile || !file.exists()) return false
        try {
            manager.loadState(file.readString("UTF-8"))
        } catch (e: HdgifeException) {
            fail("Autosave could not be loaded", e)
            file.delete()
            return false
        } catch (e: GdxRuntimeException) {
            fail("Autosave could not be loaded", e)
            file.delete()
            return false
        }
        val step = readNext() ?: return true
        showStep(step, "restored from the autosave: the lines are not part of a save, so only the choices and open styles are shown")
        view.say("Restored the story saved when the app was last paused. Stat values are not part of it.")
        return true
    }

    private fun watch(name: String) {
        observe(name)
        watchedValues[name] = show(manager.getVariable(name))
        view.refreshInfo()
    }

    private fun syncPlayer() {
        manager.syncStats("player", playerSyncedStats)
    }

    override fun log(message: String) = view.say(message)

    override fun listVariables(): List<String> = manager.variableNames

    override fun getVariable(name: String): Any? = manager.getVariable(name)

    override fun setVariable(name: String, value: Any) = manager.setVariable(name, value)

    override fun observe(name: String) {
        if (name in observations) {
            view.say("Already observing $name")
            return
        }
        try {
            observations[name] = manager.observe(name) { old, new ->
                view.say("$name: $old -> $new")
                if (name in watchedValues) {
                    watchedValues[name] = show(new)
                    view.refreshInfo()
                }
            }
            view.say("Observing $name")
        } catch (e: IllegalArgumentException) {
            view.say(e.message ?: "Cannot observe $name")
        }
    }

    override fun unobserve(name: String) {
        val observation = observations.remove(name)
        if (observation == null) {
            view.say("Not observing $name")
        } else {
            manager.unobserve(observation)
            watchedValues.remove(name)
            view.refreshInfo()
            view.say("Stopped observing $name")
        }
    }

    override fun setStat(entityId: String, statName: String, value: Any) {
        val name = applyStat(manager, provider, entityId, statName, value)
        val synced = manager.getVariable(name)
        view.say(if (synced == null) "$entityId.$statName = $value (no ink variable $name)" else "$name = $synced")
    }

    override fun setNpcStat(entityId: String, statName: String, value: Any) {
        provider.setStat(entityId, statName, value)
        view.say("$entityId.$statName = $value")
    }

    override fun resetStats() {
        provider.loadShowcaseStats()
        syncPlayer()
        view.say("Stats back to the harness defaults")
    }

    override fun talk(path: String, speaker: String?) {
        try {
            manager.beginScene(path, speaker)
        } catch (e: HdgifeException) {
            fail("Talk failed", e)
            return
        }
        advance()
    }

    override fun seed(value: Int) {
        random = Random(value)
        try {
            manager.seedRandom(value)
            view.say("Seeded $value (ink and the game's dice)")
        } catch (e: IncompatibleLibraryException) {
            view.say("Seed needs INCLUDE hdgife.ink")
        }
    }

    override fun fixRoll(value: Int?) {
        fixedRoll = value
        view.say(if (value == null) "roll() is random again" else "roll() now always gives $value")
    }

    override fun setNumberFormat(decimals: Int, rounding: Rounding) {
        manager.numberFormat = NumberFormat(decimals, rounding)
        view.say("Number format: $decimals decimals, $rounding")
    }

    override fun choose(index: Int) {
        val choice = lastStep?.choices?.firstOrNull { it.index == index }
        if (choice == null) {
            view.say("No choice $index")
            return
        }
        try {
            val step = manager.choose(index)
            showStep(step)
            if (!step.isEnded) view.setStatus("> " + choice.text)
        } catch (e: StoryStepException) {
            showFailure(e)
        }
    }

    override fun setChoiceMode(mode: ChoiceMode) = view.setChoiceMode(mode)

    override fun setViewMode(mode: ViewMode?) = view.setViewMode(mode)

    override fun setTypewriter(enabled: Boolean) = view.setTypewriter(enabled)

    override fun save() {
        val step = lastStep
        if (step == null) {
            view.say("Nothing to save yet")
            return
        }
        val json = manager.saveState()
        snapshot = Snapshot(json, step, stepNumber)
        view.say("Saved ${json.length} chars in memory. Stat values are not part of it.")
    }

    override fun load() {
        val saved = snapshot
        if (saved == null) {
            view.say("Nothing saved yet")
            return
        }
        try {
            manager.loadState(saved.state)
        } catch (e: HdgifeException) {
            fail("Load failed", e)
            return
        }
        val again = readNext() ?: return
        showStep(again, "loaded save: lines kept by the harness, choices and openStyles read again from the engine", saved.number, linesFrom = saved.step)
        view.say("Loaded (speaker is now ${manager.speaker ?: "none"})")
    }

    override fun validate() {
        val report = manager.validateStory()
        view.say("Validation: ${report.errors.size} errors, ${report.warnings.size} warnings, ${report.infos.size} infos")
        for (issue in report.issues) view.say("  ${issue.severity.name.lowercase()} ${issue.kind.name}: ${issue.message}")
    }

    override fun divert(path: String) {
        try {
            manager.story.choosePathString(path)
        } catch (e: StoryException) {
            fail("Divert failed", e)
            return
        }
        advance()
    }

    override fun reload() {
        runInBackground({ compileHarnessStory(compiler) }) { outcome ->
            try {
                manager.replaceStory(outcome.getOrThrow())
                syncPlayer()
                restartView()
                view.say("Reloaded")
            } catch (e: Exception) {
                fail("Reload failed", e)
            }
        }
    }

    override fun restart() {
        manager.reset()
        applyStat(manager, provider, "player", "coins", startCoins)
        syncPlayer()
        restartView()
    }
}
