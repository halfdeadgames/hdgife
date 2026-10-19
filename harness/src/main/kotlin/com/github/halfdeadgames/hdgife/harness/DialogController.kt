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

/** Ink variables the harness watches from the start. Their latest value is in the info line under the panels. */
private val WATCHED = listOf("suspicion", "door_open", "player_coin")

/** Where a phone run keeps the story between launches. Written by [DialogController.autosave], called from the screen's `pause`. */
private const val AUTOSAVE = "autosave.json"

/** What `save` keeps: the engine's state string and the step that was on screen, so `load` can show it again. */
private class Snapshot(val state: String, val step: DialogStep, val number: Int)

/** What the controller needs from the screen: somewhere to write, draw a step and change the layout. */
interface DialogView {
    fun say(message: String)

    /** Draws [step]. The panels take the lines and the step tags from [linesFrom]. [note] goes to the raw panel. */
    fun showStep(step: DialogStep, number: Int, note: String?, linesFrom: DialogStep)

    /** Draws a partial [step] from a failed continue, with [note] in the raw panel and a one line [status]. */
    fun showFailure(step: DialogStep, number: Int, note: String, status: String)

    fun setStatus(text: String)

    /** Rewrites the info line (layout, choice mode and the watched variables from [DialogController.watched]). */
    fun refreshInfo()

    fun setChoiceMode(mode: ChoiceMode)

    /** Null cycles to the next layout. */
    fun setViewMode(mode: ViewMode?)
}

/**
 * The console backend and the harness's game side: it drives the [StoryManager], keeps the harness's game state
 * (inventory, dice, stats), the in-memory save and the phone autosave, and tells the [view] what to draw.
 */
class DialogController(
    private val manager: StoryManager,
    private val provider: MapStateProvider,
    private val compiler: InkCompilerService,
    private val view: DialogView,
    private val mobile: Boolean,
) : DialogControl {
    /** The latest step as the engine gave it, or null before the first one. */
    var lastStep: DialogStep? = null
        private set
    private var stepNumber = 0
    private var snapshot: Snapshot? = null
    private val observations = mutableMapOf<String, VariableObservation>()

    val watched: Map<String, String> get() = watchedValues
    private val watchedValues = LinkedHashMap<String, String>()
    private var rng = Random(1)
    private var fixedRoll: Int? = null

    /** The harness's own game state, driven by ink: `emit("give_item", x)` adds to it, ink reads it back. */
    private val inventory = mutableMapOf<String, Int>()

    /** Connects the manager to the harness. Called once, when the screen is built. */
    fun start() {
        // the engine's warnings go to the console too, next to wherever the game already sent them
        val engineLog = manager.log
        manager.log = HdgifeLog { message ->
            engineLog?.log(message)
            view.say("engine: $message")
        }
        manager.formatOverride = NumberFormatOverride { entityId, statName -> harnessFormatOverride(entityId, statName) }
        // The engine reports events and never acts on them. This is the harness acting on them.
        manager.onEvent = StoryEventListener { event ->
            view.say("event #${event.id} ${event.name}(${event.args.joinToString { show(it) }}) before line ${event.lineIndex}")
            val first = event.args.firstOrNull()?.toString()
            when (event.name) {
                "give_item" -> if (first != null) inventory.merge(first, 1, Int::plus)
                "spend" -> {
                    val amount = event.args.getOrNull(1) as? Int
                    if (first != null && amount != null) {
                        val have = manager.stateProvider?.getStat("player", first) as? Int ?: 0
                        applyStat(manager, provider, "player", first, have - amount)
                    }
                }
            }
        }
        // And the other direction: ink asks the game. roll has a side effect (the dice), so it is not lookahead safe.
        manager.bindFunction("inventory_count", lookaheadSafe = true) { args -> inventory[args.string(0)] ?: 0 }
        manager.bindFunction("player_name", lookaheadSafe = true) { "Wren" }
        manager.bindFunction("roll") { args -> fixedRoll ?: rng.nextInt(1, args.int(0) + 1) }
        syncPlayer()
        for (name in WATCHED) watch(name)
    }

    /** Stops watching and releases the manager's observers. */
    fun dispose() {
        for (observation in observations.values) manager.unobserve(observation)
        observations.clear()
    }

    /** Shows the first step: the autosave of a phone run if there is one, else the start of the story. */
    fun begin() {
        if (lastStep == null && !restoreAutosave()) advance()
    }

    /** The harness's one way to report a failure: to the log with its stack trace, and to the console. */
    private fun fail(what: String, e: Exception) {
        log.error(e) { what }
        view.say("$what: ${e.message}")
    }

    private fun advance() {
        showStep(readNext() ?: return)
    }

    /** [StoryManager.next], with a failure shown on screen (a StoryStepException with its partial step) and null returned. */
    private fun readNext(): DialogStep? = try {
        manager.next()
    } catch (e: StoryStepException) {
        showFailure(e)
        null
    }

    /** The engine keeps the lines it built before a failure. Show that partial step with the error in the raw panel. */
    private fun showFailure(e: StoryStepException) {
        log.error(e) { "Story error" }
        var root: Throwable = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val why = root.message ?: root.toString()
        lastStep = e.step
        // one short line: a long label would widen the whole layout. The raw panel has the full text.
        val status = "Story error: " + why.take(110).replace('\n', ' ') + if (why.length > 110) "..." else ""
        view.showFailure(e.step, ++stepNumber, "STORY ERROR: ${e.message} / cause: $why", status)
        view.say("Story error: ${e.message}")
    }

    /**
     * Shows [step]. [linesFrom] is where the panels take the lines and the step tags from. It is [step] itself, except
     * after a load: a save holds no lines, so the harness keeps the lines of the step that was saved and takes
     * everything else (choices, open styles, ended, events) from the engine.
     */
    private fun showStep(step: DialogStep, note: String? = null, number: Int = ++stepNumber, linesFrom: DialogStep = step) {
        lastStep = step
        view.showStep(step, number, note, linesFrom)
    }

    private fun restartView() {
        stepNumber = 0
        lastStep = null
        advance()
    }

    /** Saves the story for the next launch. Phone only. Called when the app goes to the background. */
    fun autosave() {
        if (!mobile || lastStep == null) return
        try {
            // write aside and move over, so a process killed in the middle never leaves a half-written save
            val temp = Gdx.files.local("$AUTOSAVE.tmp")
            temp.writeString(manager.saveState(), false, "UTF-8")
            temp.moveTo(Gdx.files.local(AUTOSAVE))
        } catch (e: Exception) {
            // the app is going to the background: whatever went wrong must not take the process down
            fail("Autosave failed", e)
        }
    }

    /** Brings back the story saved by [autosave], if there is one. The lines are not part of a save, so only the choices show. */
    private fun restoreAutosave(): Boolean {
        val file = Gdx.files.local(AUTOSAVE)
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

    /** Keeps the latest value of an ink variable for the info line, and logs each change to the console. */
    private fun watch(name: String) {
        observe(name)
        watchedValues[name] = show(manager.getVariable(name))
        view.refreshInfo()
    }

    private fun syncPlayer() {
        manager.syncStats("player", PLAYER_SYNCED_STATS)
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
        rng = Random(value)
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
        // The engine brings back the story, the speaker and the open styles. The lines are not part of a save, so the
        // game shows the ones it kept, with the choices and open styles read again from the engine.
        val again = readNext() ?: return
        showStep(again, "loaded save: lines kept by the harness, choices and openStyles read again from the engine", saved.number, linesFrom = saved.step)
        view.say("Loaded (speaker is now ${manager.speaker ?: "none"})")
    }

    override fun validate() {
        val report = manager.validateStory()
        view.say("Validation: ${report.errors.size} errors, ${report.warnings.size} warnings, ${report.infos.size} infos")
        for (issue in report.issues) view.say("  ${issue.severity.name.lowercase()} ${issue.kind.name.lowercase()}: ${issue.message}")
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
                // the outermost boundary: this runs from the render loop, and the compile ran on another thread
                fail("Reload failed", e)
            }
        }
    }

    override fun restart() {
        manager.reset()
        inventory.clear()
        applyStat(manager, provider, "player", "coin", START_COIN)
        syncPlayer()
        restartView()
    }
}
