package com.github.halfdeadgames.hdgife.lwjgl3

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.github.halfdeadgames.hdgife.harness.*
import ktx.async.skipFrame

private const val hubLine = "tester: This is the hub, and each choice opens one group of features."

private val hubChoices = listOf(
    "Plain text and speakers", "Tags", "Styles", "Style edge cases", "Point markers", "Stats", "Number formats",
    "Util helpers and combined metrics", "Glue", "Events", "Bound functions and observed variables", "Requirements",
    "Choice kinds", "Randomness", "Validation and debug cases", "Ending",
)

internal fun Ui.expectRaw(vararg parts: String) {
    for (part in parts) expect(part in raw) { "Raw panel is missing <$part>.\n--- raw panel ---\n$raw" }
}

internal fun Ui.expectRows(vararg rows: String) {
    val renderedRows = log
    for (row in rows) expect(row in renderedRows) { "Rendered panel is missing the row <$row>.\n--- rows ---\n${renderedRows.joinToString("\n")}" }
}

internal fun Ui.markupRow(part: String): String =
    markup.firstOrNull { part in plain(it) } ?: throw Check("No rendered row contains <$part>.\n--- rows ---\n${log.joinToString("\n")}")

internal fun Ui.expectMarkup(part: String, vararg pieces: String) {
    val row = markupRow(part)
    for (piece in pieces) expect(piece in row) { "Row <$part> is missing <$piece>: $row" }
}

internal fun Ui.expectInfo(part: String) {
    val info = screen.infoLabel.text.toString()
    expect(part in info) { "Info line is missing <$part>: $info" }
}

internal fun Ui.expectNote(name: String, note: String, available: Boolean) {
    val button = button(name)
    val text = buttonText(button)
    expect(note in text) { "Choice <$name> should say <$note>: $text" }
    expectEquals(!available, button.isDisabled, "greyed state of <$name>")
}

internal fun Ui.line(part: String) = screen.renderedLines.firstOrNull { part in it.text }
    ?: throw Check("No rich line contains <$part>: ${screen.renderedLines.map { it.text }}")

private fun pressKey(keycode: Int) {
    Gdx.input.inputProcessor.keyDown(keycode)
    Gdx.input.inputProcessor.keyUp(keycode)
}

internal suspend fun scenarios(ui: Ui, report: Report) {
    ui.settle()

    ui.scenario(report, "hub: first step, speaker and one choice per group") {
        expectEquals(hubLine, log[0], "first row")
        expectEquals(hubChoices, buttons.map(::buttonText), "hub choices")
        expect(buttons.none { it.isDisabled }) { "No hub choice is greyed" }
        expectRaw("STEP 1", "ended     : false", "LINES (1)", "speaker   : tester", "CHOICES (16)", "OPEN STYLES (0)", "EVENTS (0)")
        expectInfo("counter=0")
        expectInfo("switchOn=false")
        expectInfo("player_coins=12")
        shot("01-hub")
    }

    ui.scenario(report, "text: speak(), speaker tags, an empty speaker and a trailing speak()") {
        click("Plain text and speakers")
        expectRows(
            "tester: This line is plain text with no markup.",
            "tester: The speaker stays tester until speak() is called again.",
            "subject: This line is spoken by subject, which speak(\"subject\") set.",
            "player: The speaker of this line is set with a speaker tag.",
            "# speaker: player (text)",
            "subject: This line is back to the speaker that speak() set last.",
            "This line has no speaker because speak(\"\") cleared it.",
            "tester: This line is spoken by tester again after a second speak(\"tester\").",
            "tester: This is the last line, and a trailing speak(\"subject\") follows it before the choices.",
        )
        expect(markupRow("This line is plain text").startsWith("[SKY]tester:[] ")) { "tester label colour" }
        expect(markupRow("spoken by subject").startsWith("[SALMON]subject:[] ")) { "subject label colour" }
        expect(markupRow("set with a speaker tag").startsWith("[LIME]player:[] ")) { "player label colour" }
        expect(markupRow("has no speaker").startsWith("This line")) { "no label without a speaker" }
        expectEquals(
            listOf("A requirement without an entity reads the trailing speaker\n(met subject.intStat < 5 (have 3))", "Back to the hub"),
            buttons.map(::buttonText), "choices read for the trailing speaker",
        )
        expectRaw(
            "speaker   : player", "tags.map  : {speaker=player}", "speaker   : null", "CHOICES (2)",
            "entity=subject stat=intStat op=<", "target=5 (Int) actual=3 (Int)", "result=Met isMet=true",
        )
        shot("02-text")
        click("A requirement without an entity")
        expectEquals(hubLine, log[0], "the hub speaks as tester again")
    }

    ui.scenario(report, "tags: key and value, bare, repeated, typed, carried and on a choice") {
        click("Tags")
        expectRows(
            "# color: red (text)", "# flagged (bare)", "# layer: one (text, 1 of 2)  # layer: two (text, 2 of 2)",
            "# intTag: 42 (int)", "# floatTag: 1.5 (float)", "# boolTag: true (bool)", "# note: hello there (text)",
            "# carried: from the line above (text)", "# speaker: tester (text)",
            "tester: This line receives the tag from the tag line above it.",
        )
        expectRaw(
            "tags.raw  : [\"color: red\"]", "tags.map  : {color=red}", "tags.raw  : [\"flagged\"]", "tags.map  : {flagged=}",
            "tags.raw  : [\"layer: one\", \"layer: two\"]", "tags.map  : {layer=two}", "tags.map  : {intTag=42}",
            "tags.map  : {floatTag=1.5}", "tags.map  : {boolTag=true}", "tags.map  : {note=hello there}",
            "tags.map  : {carried=from the line above}", "tags.map  : {choiceTag=yes, choiceCount=3}",
        )
        shot("03-tags")
    }

    ui.scenario(report, "styles: named, hex and nested colors, effects, a span over two lines, the escape") {
        click("Styles")
        expectMarkup("inline color tags", "[RED]inline color tags[]")
        expectMarkup("named colors", "[GOLD]gold[]", "[CORAL]coral[]", "[SKY]sky[]")
        expectMarkup("shows a hex color", "[#ff8800ff]hex color[]")
        expectMarkup("three digit hex", "[#ff8800ff]three digit hex color[]")
        expectMarkup("eight digit hex", "[#ff8800cc]eight digit hex color with alpha[]")
        expectMarkup("0x prefixed", "[#ff8800ff]0x prefixed hex color[]")
        expectMarkup("hash prefixed", "[#ff8800ff]hash prefixed hex color[]")
        val unknown = markupRow("unknown color name")
        expect("[#8a8a8a][[color=notacolor][]" in unknown && "[#" !in unknown.substringBefore("[#8a8a8a]").substringAfter("shows an ")) { "Unknown color is noted, not drawn: $unknown" }
        expectMarkup("shows bold", "[#ffffff]bold[][#8a8a8a][[b][]", "[#ffd27f]emphasis[][#8a8a8a][[em][]")
        expectMarkup("nesting", "[#ffffff]nesting with [][#ffd27f]emphasis inside bold[][#8a8a8a][[em][]", "[RED]red inside bold[][#8a8a8a][[b][]")
        expectMarkup("blue with", "[BLUE]blue with [][RED]red nested[][BLUE] inside it[]")
        expectMarkup("a shake effect", "[#8a8a8a][[shake amp=2][]")
        expectMarkup("two named params", "[#8a8a8a][[shake amp=3 speed=0.5][]")
        expectMarkup("positional params", "[#8a8a8a][[wave=2,0.5][]")
        expectMarkup("a bounce effect", "[#8a8a8a][[bounce amp=3][]")
        expectRows("tester: This line shows a style that spans two lines", "tester: and ends on the second line.")
        expectMarkup("and ends on the second line", "[GREEN]and ends on the second line.[]")
        expectRows("tester: This line shows the < escape, which writes a literal less-than sign.")
        for (moving in listOf("a shake effect", "two named params", "positional params", "a bounce effect")) {
            expect(line(moving).hasEffects) { "The line with <$moving> should animate" }
        }
        expect(!line("inline color tags").hasEffects) { "A colored line has no moving glyphs" }
        val shaking = line("a shake effect")
        val before = shaking.animationTime
        settle()
        expect(shaking.animationTime > before) { "The animated line must advance with the frames" }
        expectRaw(
            "styles: color=red", "styles: color=ff8800 (hex ff8800ff)", "styles: color=f80 (hex ff8800ff)",
            "styles: color=ff8800cc (hex ff8800cc)", "styles: color=0xff8800 (hex ff8800ff)", "styles: color=#ff8800 (hex ff8800ff)",
            "styles: color=notacolor", "styles: b\n", "styles: b + em", "styles: b + color=red", "styles: color=blue + color=red",
            "styles: shake amp=2", "styles: shake amp=3 speed=0.5", "styles: wave=2,0.5", "styles: bounce amp=3", "styles: color=green",
        )
        shot("04-styles")
    }

    ui.scenario(report, "style edge cases: open over a choice, save and load, the cap, a stray close tag") {
        click("Style edge cases")
        expectRows("tester: This line opens an orange style and leaves it open across the choice below.", "(style still open: color=orange)")
        expectMarkup("leaves it open", "[ORANGE]an orange style and leaves it open across the choice below.[]")
        expectRaw("OPEN STYLES (1)", "open      : color=orange", "styles: color=orange")
        expectInfo("counter=0")
        shot("05-style-open")
        val savedRows = log
        val savedChoices = buttons.map(::buttonText)
        command("save")
        click("The style carries")
        expectMarkup("still orange", "[ORANGE]This line follows the choice, still orange, and closes it here.[]")
        expectRaw("OPEN STYLES (0)")
        expectRows(
            "tester: The harness sets maxOpenStyles to 4, and the next lines each leave a style open.",
            "tester: A close tag that was never opened is ignored.", hubLine,
        )
        expectMarkup("This is line 0", "[#ffd27f]")
        expectMarkup("This is line 5", "[#ffd27f]This is line 5, and it opens an emphasis style without closing it.[]")
        expect("[em]" !in plain(markupRow("This is line 5"))) { "A style that stays open across lines is not noted as closed" }
        expectMarkup("these four closes", "[#8a8a8a][[em][][#8a8a8a][[em][][#8a8a8a][[em][][#8a8a8a][[em][]")
        val cap = screen.consoleLines.filter { it.startsWith("engine: Style: more than 4 styles open, dropped the oldest <em>") }
        expectEquals(2, cap.size, "console lines about the cap")
        expect(screen.consoleLines.any { it.startsWith("engine: Style: '</b>' was never opened, ignored") }) { "Stray close tag in the console: ${screen.consoleLines.takeLast(6)}" }
        shot("06-style-cap")

        command("load")
        expectEquals(savedRows, log, "rows after load")
        expectEquals(savedChoices, buttons.map(::buttonText), "choices after load")
        expectRaw("note      : loaded save", "OPEN STYLES (1)", "open      : color=orange")
        click("The style carries")
        expectMarkup("still orange", "[ORANGE]This line follows the choice, still orange, and closes it here.[]")
    }

    ui.scenario(report, "markers: pause, slam, sound and bare markers in the text and the raw panel") {
        click("Point markers")
        expectRows(
            "tester: This line has a pause|pause=0.5| in the middle.",
            "tester: This line has a long pause|pause=1.5| that the typewriter honors until a tap or click skips it.",
            "tester: This line has a |slam|slam marker, drawn as a flash.",
            "tester: This line has a sound marker|sfx name=click vol=0.8| with two named params.",
            "tester: This line has a marker with a value|mark=3| and a bare marker|beat| at the end.",
        )
        expectRaw("pause=0.5 @21", "pause=1.5 @26", "slam @16", "sfx name=click vol=0.8 @28", "mark=3 @35", "beat @53")
        expect(markupRow("a pause").contains("[#8a8a8a]|pause=0.5|[]")) { "Markers are drawn dim: ${markupRow("a pause")}" }
        shot("07-markers")
    }

    ui.scenario(report, "typewriter: a pause marker holds the reveal, a slam flashes, a click skips the rest") {
        command("typewriter on")
        click("Point markers")
        expect(screen.isRevealing) { "The typewriter should still be revealing right after the step" }
        val first = screen.renderedLines[0]
        waitUntil("the first pause") { first.isPaused }
        val held = first.revealedChars
        expectEquals("tester: This line has a pause|pause=0.5|".length, held, "reveal stops right after the pause marker")
        expect(!screen.renderedLines[1].started) { "The next line waits for the first one" }
        repeat(5) { skipFrame() }
        expectEquals(held, first.revealedChars, "reveal while paused")
        waitUntil("the first line to finish") { first.isDone }
        waitUntil("the second pause") { screen.renderedLines[1].isPaused }
        waitUntil("the slam flash") { screen.renderedLines[2].isFlashing }
        shot("08-typewriter-flash")
        tap(screen.renderedLines[2], "the flashing line")
        expect(!screen.isRevealing) { "A tap finishes the reveal" }
        expect(screen.renderedLines.all { it.isDone && !it.isPaused }) { "Every line is whole after the tap" }
        command("typewriter off")
        expectInfo("choices: grey")
    }

    ui.scenario(report, "typewriter: a tap during a long pause skips it") {
        command("typewriter on")
        click("Point markers")
        val first = screen.renderedLines[0]
        waitUntil("the first pause") { first.isPaused }
        tap(first, "the paused line")
        expect(first.isDone && !screen.isRevealing) { "The tap skips the pause and the rest" }
        command("typewriter nonsense")
        expect(screen.consoleLines.last() == "Typewriter takes on or off") { "Bad argument: ${screen.consoleLines.last()}" }
    }

    ui.scenario(report, "stats: every type through stat, stat_of, has_stat, stat_text and stat_text_of") {
        click("Stats")
        expectRows(
            "tester: The int stat intStat is 7, read with stat().",
            "tester: The float stat floatStat is 2.75, read with stat() as a raw float.",
            "tester: The string stat textStat is alpha, read with stat().",
            "tester: The bool stat flagStat is true, read with stat().",
            "tester: The enum stat modeStat is Steady, because an enum arrives as its name.",
            "tester: The missing stat missingStat is absent according to has_stat().",
            "tester: The stat intStat is present according to has_stat().",
            "tester: The subject's intStat is 3, read with stat_of().",
            "tester: The subject's modeStat is Erratic, and has_stat_of() says present.",
            "tester: The subject's missingStat is absent according to has_stat_of().",
            "tester: The int stat as text is 7, from stat_text().",
            "tester: The string stat as text is alpha, from stat_text().",
            "tester: The bool stat as text is true, from stat_text().",
            "tester: The enum stat as text is Steady, from stat_text().",
            "tester: The missing stat as text is 0, from stat_text().",
            "tester: The player's coins as text are 12, from stat_text_of().",
            "tester: The subject's floatStat as text is 1, from stat_text_of().",
        )
        shot("09-stats")
        for (c in listOf("stat tester intStat 9", "npc tester missingStat 5", "npc subject missingStat 1", "npc tester textStat omega", "divert statGroup")) command(c)
        expectRows(
            "tester: The int stat intStat is 9, read with stat().",
            "tester: The string stat textStat is omega, read with stat().",
            "tester: The missing stat missingStat is present according to has_stat().",
            "tester: The subject's missingStat is present according to has_stat_of().",
            "tester: The missing stat as text is 5, from stat_text().",
        )
    }

    ui.scenario(report, "numbers: fmt in every mode, FLOOR CEILING INT, the harness override and numfmt") {
        click("Number formats")
        expectRows(
            "tester: The tester's floatStat is 2.75, formatted with 2 decimals by the harness override.",
            "tester: The subject's floatStat is 1, formatted with the default of 0 decimals.",
            "tester: This line formats 2.75 with one decimal: floor 2.7, ceil 2.8, round 2.8, trunc 2.7.",
            "tester: This line formats -2.5 with no decimals: floor -3, ceil -2, round -3, trunc -2.",
            "tester: This line uses plain ink on 2.75: FLOOR 2.0, CEILING 3.0, INT 2.",
            "tester: This line uses plain ink on -2.5: FLOOR -3.0, CEILING -2.0, INT -2.",
        )
        shot("10-numbers")
        command("numfmt 1 trunc")
        command("divert formatGroup")
        expectRows(
            "tester: The tester's floatStat is 2.75, formatted with 2 decimals by the harness override.",
            "tester: The subject's floatStat is 0.5, formatted with the default of 0 decimals.",
        )
        command("numfmt 0 floor")
        command("divert formatGroup")
        expectRows("tester: The subject's floatStat is 0, formatted with the default of 0 decimals.")
    }

    ui.scenario(report, "util helpers and combined metrics follow the stats") {
        click("Util helpers and combined metrics")
        expectRows(
            "tester: This line uses clamp(150, 0, 100), which gives 100, and clamp(0 - 5, 0, 100), which gives 0.",
            "tester: This line uses avg2(10, 25), which gives 17.5.",
            "tester: This line uses avg3(10, 20, 60), which gives 30.0.",
            "tester: This line uses blend(10, 1, 40, 3), which gives 32.5 because the second value weighs three times as much.",
            "tester: This line uses invert(30), which gives 70.",
            "tester: This line uses scale100(30, 20, 60), which gives 25.0, the place of 30 between 20 and 60 as a percent.",
            "tester: This line uses chance(100), which gives true, and chance(0), which gives false.",
            "tester: This line shows pressure(), an average of inverted energy, inverted calm and focus: 30.0.",
            "tester: This line shows spread(), energy and calm blended two to one: 73.3.",
            "tester: This line shows readiness(), a scaled focus plus half the calm, clamped: 35.",
        )
        shot("11-util")
        command("npc tester energy 100")
        command("divert utilGroup")
        expectRows(
            "tester: This line shows pressure(), an average of inverted energy, inverted calm and focus: 23.3.",
            "tester: This line shows spread(), energy and calm blended two to one: 86.7.",
        )
    }

    ui.scenario(report, "glue: across a stat call, across an emit and plain, and the branch that does not glue") {
        click("Glue")
        expectRows(
            "tester: This line is joined to the next one by glue after a stat call, and the stat call reads intStat as 7.",
            "tester: This line is joined to the next one across an emit and the event fires once.",
            "tester: This line ends in plain glue and continues on the next source line.",
            "! event glued(payload: String)",
        )
        expectEquals(1, log.count { it.startsWith("! event glued") }, "glued events")
        expectRaw("glued(payload (String))  lineIndex=1")
        shot("12-glue")
        command("stat tester intStat 4")
        command("divert glueGroup")
        expectRows("tester: This line is joined to the next one by glue after a stat call,")
        expect(log.any { it.contains("and the stat call reads intStat as 4.") && !it.contains("after a stat call") }) { "Without the glue the halves are two rows: $log" }
    }

    ui.scenario(report, "events: emit with every argument count, typed args, the game acts and ink reads it back") {
        click("Events")
        expectRows(
            "! event noArgs()", "! event oneArg(text: String)", "! event twoArgs(7: Int, 2.5: Float)",
            "! event threeArgs(text: String, 21: Int, true: Boolean)",
        )
        expect(log.indexOf("! event noArgs()") < log.indexOf("tester: This line is followed by emit1() with a string.")) { "Event before the next line: $log" }
        expectRaw(
            "noArgs()  lineIndex=1  id=1", "oneArg(text (String))  lineIndex=2  id=2", "twoArgs(7 (Int), 2.5 (Float))  lineIndex=3  id=3",
            "threeArgs(text (String), 21 (Int), true (Boolean))  lineIndex=4  id=4", "EVENTS (4)",
        )
        shot("13-events")
        click("Spend 3 coins with a spend event")
        expectRows("! event spend(coins: String, 3: Int)", "tester: The ink variable player_coins is 12 while this step is read, and the choices below see the new coins.")
        expectInfo("player_coins=9")
        expectRaw("spend(coins (String), 3 (Int))")
        repeat(3) { click("Spend 3 coins again") }
        expectInfo("player_coins=0")
        expectNote("Spend 3 coins again", "(needs player.coins >= 3 (have 0))", available = false)
        expect(screen.consoleLines.any { it.startsWith("event #") && "spend(coins, 3)" in it }) { "Events in the console: ${screen.consoleLines.takeLast(6)}" }
        shot("14-events-spent")
        click("Back to the event group")
        expectNote("Spend 3 coins with a spend event", "(needs player.coins >= 3 (have 0))", available = false)
    }

    ui.scenario(report, "bound functions and observed variables") {
        command("fixroll 3")
        click("Bound functions and observed variables")
        expectRows(
            "tester: The bound function safeDouble(21) gives 42, and it is bound as lookahead safe.",
            "tester: The bound function unsafeRoll(6) gives 3, and it is not lookahead safe, so it stays out of choice text.",
            "tester: The observed variable counter is 0, and the harness shows it in the info line.",
            "tester: The observed variable switchOn is false.",
            "tester: The synced variable player_coins is 12, copied from the player's coins stat.",
        )
        expectEquals(
            listOf("Safe function in choice text: safeDouble(4) is 8", "Back to the hub"), buttons.map(::buttonText), "choices with a safe function in the text",
        )
        shot("15-bindings")
        command("set counter 5")
        expectInfo("counter=5")
        expect(screen.consoleLines.any { it == "counter: 0 -> 5" }) { "The observer reports the change: ${screen.consoleLines.takeLast(5)}" }
        command("divert bindingGroup")
        expectRows("tester: The observed variable counter is 5, and the harness shows it in the info line.")
        command("fixroll 5")
        command("divert bindingGroup")
        expectRows("tester: The bound function unsafeRoll(6) gives 5, and it is not lookahead safe, so it stays out of choice text.")
    }

    ui.scenario(report, "requirements: every kind, with its note and its place in the raw panel") {
        click("Requirements")
        expectEquals(23, buttons.size, "requirement choices")
        expectNote("Numeric, met", "(met tester.intStat >= 5 (have 7))", true)
        expectNote("Numeric, unmet", "(needs tester.intStat >= 9 (have 7))", false)
        expectNote("Float, met", "(met tester.floatStat > 2.5 (have 2.75))", true)
        expectNote("String, met", "(met tester.textStat == alpha (have alpha))", true)
        expectNote("String, unmet", "(needs tester.textStat == beta (have alpha))", false)
        expectNote("Bool, met", "(met tester.flagStat == true (have true))", true)
        expectNote("Bool, unmet", "(needs subject.flagStat == true (have false))", false)
        expectNote("Enum compared", "(met tester.modeStat == Steady (have Steady))", true)
        expectNote("No entity, read", "(met tester.intStat >= 5 (have 7))", true)
        expectNote("Explicit entity", "(met subject.intStat < 5 (have 3))", true)
        expectNote("Quoted names", "(met subject.two words == a b (have a b))", true)
        expectNote("Missing stat", "(needs subject.luck > 0 (stat missing))", false)
        expectNote("Type mismatch", "(needs tester.intStat >= high (have 7, type mismatch))", false)
        expectNote("Unreadable clause", "(needs bad requirement 'this is not a clause')", false)
        expectNote("Or with the word or", "(met any of [tester.intStat >= 9 (have 7) = no | subject.flagStat == false (have false) = ok])", true)
        expectNote("Or with an escaped pipe", "(met any of [tester.intStat >= 9 (have 7) = no | subject.intStat == 3 (have 3) = ok])", true)
        expectNote("Or with neither", "(needs any of [tester.intStat >= 9 (have 7) = no | subject.flagStat == true (have false) = no])", false)
        expectNote("Not, met", "(met not [subject.flagStat == true (have false) = no])", true)
        expectNote("Not, unmet", "(needs not [tester.flagStat == true (have true) = ok])", false)
        expectNote("Not inside", "(met any of [not [tester.intStat >= 5 (have 7) = ok] = no | tester.textStat == alpha (have alpha) = ok])", true)
        expectNote("Two clauses", "(met tester.intStat >= 5 (have 7); subject.intStat <= 3 (have 3))", true)
        expectNote("Two requires tags", "(needs subject.intStat >= 9 (have 3))", false)
        expectRaw(
            "entity=subject stat=two words op===", "result=Met isMet=true", "result=Unmet isMet=false", "result=MissingStat isMet=false",
            "result=Mismatch isMet=false", "result=Unreadable isMet=false", "result=AnyOf isMet=true", "result=AnyOf isMet=false",
            "result=Not isMet=true", "result=Not isMet=false", "any of (isMet=true)", "not (isMet=false)", "-> Unmet", "-> Met", "requires  : 2",
            "target=Steady (String) actual=Steady (String)", "entity=subject stat=luck op=>",
        )
        shot("16-requirements-grey")
        val before = log.size
        click(button("Numeric, unmet"))
        expectEquals(before, log.size, "rows after clicking a greyed choice")
        click("Or with the word or")
        expectRows("tester: This line follows a choice that was available when it was chosen.")
        expectEquals(23, buttons.size, "sticky requirement choices after a pick")

        command("choicemode hide")
        expectEquals(14, buttons.size, "hide mode keeps the available ones")
        expect(buttons.none { it.isDisabled }) { "Nothing greyed in hide mode" }
        expectRaw("available : false")
        shot("17-requirements-hide")
        command("choicemode show")
        expectEquals(23, buttons.size, "show mode")
        expect(buttons.none { it.isDisabled }) { "Show keeps every choice clickable" }
        expect("(needs tester.intStat >= 9" in buttonText(button("Numeric, unmet"))) { "Show mode keeps the needs note" }
        expectInfo("choices: show")
        shot("18-requirements-show")
        command("choicemode grey")
        command("stat tester intStat 9")
        command("divert requirementGroup")
        expectNote("Numeric, unmet", "(met tester.intStat >= 9 (have 9))", true)
        expectNote("Or with neither", "(met any of [tester.intStat >= 9 (have 9) = ok | subject.flagStat == true (have false) = no])", true)
    }

    ui.scenario(report, "choice kinds: once-only, sticky, conditional and the observed variables") {
        click("Choice kinds")
        expectEquals(
            listOf("Once-only choice, gone after one use", "Sticky choice, offered every time", "Raise counter, currently 0", "Flip switchOn, currently false", "Back to the hub"),
            buttons.map(::buttonText), "choice kinds at counter 0",
        )
        click("Once-only")
        expectRows("tester: This line follows the once-only choice.")
        expect(buttons.none { buttonText(it).startsWith("Once-only") }) { "A once-only choice is gone after use: ${buttons.map(::buttonText)}" }
        click("Sticky choice")
        expectRows("tester: This line follows the sticky choice.")
        expect(buttons.any { buttonText(it).startsWith("Sticky choice") }) { "A sticky choice stays" }
        click("Raise counter")
        expectRows("tester: The observed variable counter is now 1.")
        expectInfo("counter=1")
        expect(buttons.none { buttonText(it).startsWith("Conditional") }) { "The conditional choice waits for counter 2" }
        click("Raise counter")
        expectInfo("counter=2")
        expect(screen.consoleLines.contains("counter: 1 -> 2")) { "Observer: ${screen.consoleLines.takeLast(4)}" }
        expectEquals("Raise counter, currently 2", buttonText(button("Raise counter")), "choice text reads the variable")
        click("Conditional choice")
        expectRows("tester: This line follows the conditional choice.")
        click("Flip switchOn")
        expectRows("tester: The observed variable switchOn is now true.")
        expectInfo("switchOn=true")
        shot("19-choice-kinds")
    }

    ui.scenario(report, "randomness: shuffles, cycles and chance repeat under a seed") {
        fun run(seed: Int): List<String> {
            screen.runCommand("restart")
            screen.runCommand("seed $seed")
            screen.runCommand("divert randomGroup")
            return log + raw
        }
        val first = run(7)
        settle()
        expect(first.size > 1) { "randomGroup produced nothing" }
        val numbers = listOf("one", "two", "three", "four", "five", "six")
        expect(log.any { row -> numbers.any { row == "tester: This line shows a shuffle, which picked $it." } }) { "Shuffle row: $log" }
        expectRows(
            "tester: This line shows a cycle, which is at one.", "tester: This line shows a stopping sequence, which is at one.",
            "tester: This line shows a once-only sequence, which is at one.",
        )
        val random = log.first { it.startsWith("tester: This line shows RANDOM(1, 100), which gave ") }.removePrefix("tester: This line shows RANDOM(1, 100), which gave ").removeSuffix(".").toInt()
        expect(random in 1..100) { "RANDOM out of range: $random" }
        shot("20-random")
        val again = run(7)
        expectEquals(first.dropLast(1), again.dropLast(1), "rows with the same seed")
        expectEquals(first.last().substringAfter("LINES"), again.last().substringAfter("LINES"), "raw panel with the same seed")
        val seeds = (1..6).map { run(it).first { row -> row.contains("RANDOM(1, 100)") } }.toSet()
        expect(seeds.size > 1) { "Different seeds should give different numbers: $seeds" }
    }

    ui.scenario(report, "validation: odd requirements the engine reports, formatter coverage") {
        click("Validation and debug cases")
        click("Odd requirements")
        expectRows("This knot has no speaker, so a requirement without an entity names no entity.")
        expectNote("Order comparison", "(needs tester.textStat > 3 (have alpha, type mismatch))", false)
        expectNote("Boolean compared", "(needs tester.flagStat == 4 (have true, type mismatch))", false)
        expectNote("Boolean with an order", "(needs tester.flagStat > true (have true, type mismatch))", false)
        expectNote("No entity and no speaker", "(needs ?.intStat > 1 (stat missing))", false)
        expectNote("Two requires tags", "(met tester.intStat > 1 (have 7); subject.intStat > 1 (have 3))", true)
        expectRaw("entity=null stat=intStat op=>", "result=Mismatch isMet=false", "requires  : 2")
        shot("21-odd-requirements")
        click("Back to the validation group")
        click("Formatter coverage")
        expectRows(
            "tester: This line uses has_stat(), which gives true for intStat and false for missingStat.",
            "tester: This line uses fmt(12.9, 0, \"floor\"), which gives 12, and fmt(12.346, 2, \"round\"), which gives 12.35.",
            "tester: This line uses the alias ceiling, fmt(12.1, 0, \"ceiling\") gives 13, and the alias truncate, fmt(-12.9, 0, \"truncate\") gives -12.",
            "tester: This line uses an unknown mode, which the engine logs before it rounds: fmt(12.5, 0, \"sideways\") gives 13.",
        )
        expect(screen.consoleLines.contains("engine: fmt: unknown mode 'sideways', using round")) { "The unknown mode is logged: ${screen.consoleLines.takeLast(6)}" }
        command("npc tester missingStat 4")
        command("divert formatCoverage")
        expectRows("tester: This line uses has_stat(), which gives true for intStat and true for missingStat.")
        shot("22-format-coverage")
    }

    ui.scenario(report, "validation: an unsafe bound function in choice text fails with the partial step") {
        click("Validation and debug cases")
        click("Unsafe function in choice text")
        expectRows("tester: This line is built before the failure, because the choice below calls an unsafe function in its text.")
        expectRaw("STORY ERROR", "This line is built before the failure", "lookahead")
        expect("Story error" in screen.statusLabel.text.toString()) { "Status: ${screen.statusLabel.text}" }
        expect(screen.restartButton.isVisible) { "A failed step offers Restart" }
        expectEquals(0, buttons.size, "choices of a failed step")
        shot("23-unsafe-error")
        click(screen.restartButton)
        expectEquals(hubLine, log[0], "restart after a failure")
    }

    ui.scenario(report, "validation: the console report on the showcase story") {
        val before = screen.consoleLines.size
        command("validate")
        val output = screen.consoleLines.drop(before)
        expect(output.firstOrNull()?.startsWith("Validation: 0 errors") == true) { "validate: $output" }
        expect(output.none { it.contains("UnboundExternal") }) { "validate: $output" }
        expect(output.any { it.contains("UnreadableRequirement") }) { "The unreadable clause is reported: $output" }
    }

    ui.scenario(report, "ending: no choices, an ended event, restart") {
        click("Ending")
        expectRows("tester: This line leads to the ending, and an ended event follows it.", "tester: This is the last line, and the step after it has no choices.", "! event ended(showcase: String)")
        expectEquals(emptyList(), buttons.map(::buttonText), "choices after the end")
        expectEquals("Story ended.", screen.statusLabel.text.toString(), "status")
        expect(screen.restartButton.isVisible) { "Restart shows at the end" }
        expectRaw("ended     : true", "CHOICES (0)", "ended(showcase (String))  lineIndex=1")
        shot("24-ended")
        click(screen.restartButton)
        expectEquals(hubLine, log[0], "first row after restart")
        expectRaw("STEP 1", "ended     : false")
        expectInfo("player_coins=12")
        expect(!screen.restartButton.isVisible) { "Restart hides again" }
        expectEquals(16, buttons.size, "hub choices after restart")
    }

    ui.scenario(report, "layouts: split, raw only, rendered only, F2, and the raw panel scrolls") {
        click("Styles")
        expectEquals(ViewMode.Split, screen.viewMode, "default view")
        expect(screen.rawVisible && screen.renderedVisible) { "Both panels in split view" }
        expect(screen.rawMaxScrollY > 0f) { "The raw panel of a long step must scroll" }
        screen.stage.mouseMoved(100, 300)
        settle()
        val before = screen.rawScrollY
        screen.stage.scrolled(0f, 6f)
        settle()
        expect(screen.rawScrollY > before) { "Raw panel did not scroll: $before -> ${screen.rawScrollY}" }

        command("view raw")
        expect(screen.rawVisible && !screen.renderedVisible) { "Raw only" }
        shot("25-view-raw")
        command("view rendered")
        expect(!screen.rawVisible && screen.renderedVisible) { "Rendered only" }
        shot("26-view-rendered")
        command("view split")
        expect(screen.rawVisible && screen.renderedVisible) { "Split again" }

        pressKey(Input.Keys.F2)
        expectEquals(ViewMode.Raw, screen.viewMode, "after F2")
        pressKey(Input.Keys.F2)
        expectEquals(ViewMode.Rendered, screen.viewMode, "after F2 twice")
        pressKey(Input.Keys.F2)
        expectEquals(ViewMode.Split, screen.viewMode, "after F2 three times")
        expectInfo("view: split")
    }

    ui.scenario(report, "console: vars, var and reload") {
        val before = screen.consoleLines.size
        command("vars")
        val listed = screen.consoleLines.drop(before)
        expect("counter = 0" in listed && "switchOn = false" in listed && "player_coins = 12" in listed) { "vars: $listed" }
        command("var counter")
        expect("counter = 0 (Integer)" in screen.consoleLines) { "var: ${screen.consoleLines.takeLast(3)}" }
        command("var nope")
        expect("nope is not a declared variable" in screen.consoleLines) { "var of nothing: ${screen.consoleLines.takeLast(3)}" }
        command("set counter 4")
        click("Requirements")
        val mark = screen.consoleLines.size
        command("reload")
        waitUntil("the reload") { screen.consoleLines.drop(mark).any { it == "Reloaded" || it.startsWith("Reload failed") } }
        expect("Reloaded" in screen.consoleLines.drop(mark)) { "reload: ${screen.consoleLines.drop(mark)}" }
        settle()
        expectEquals(hubLine, log[0], "A reload starts the story again")
        expectEquals("0", screen.getVariable("counter").toString(), "counter after the reload")
    }

    ui.scenario(report, "console: the Copy action works") {
        val contextClass = Class.forName("com.strongjoshua.console.ConsoleContext")
        val contextConstructor = contextClass.getDeclaredConstructor(Class::class.java, Class::class.java, Skin::class.java, String::class.java)
        contextConstructor.isAccessible = true
        val skin = Skin(Gdx.files.classpath("default_skin/uiskin.json"))
        try {
            val context = contextConstructor.newInstance(Table::class.java, Label::class.java, skin, "default-round")
            val source = Label("  text to copy  ", skin)
            contextClass.getDeclaredMethod("setLabel", Label::class.java).apply { isAccessible = true }.invoke(context, source)
            val copy = contextClass.getDeclaredField("copy").apply { isAccessible = true }.get(context) as Label
            Gdx.app.clipboard.contents = "before"
            val event = InputEvent().apply { type = InputEvent.Type.touchUp; pointer = 0 }
            val click = copy.listeners.filterIsInstance<ClickListener>().first()
            click.clicked(event, 0f, 0f)
            expectEquals("text to copy", Gdx.app.clipboard.contents, "clipboard after Copy")
        } finally {
            skin.dispose()
        }
    }
}
