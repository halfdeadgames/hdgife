package com.github.halfdeadgames.hdgife.lwjgl3

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.github.halfdeadgames.hdgife.harness.*
import ktx.async.skipFrame

internal fun Ui.expectRaw(vararg parts: String) {
    for (part in parts) expect(part in raw) { "Raw panel is missing <$part>.\n--- raw panel ---\n$raw" }
}

internal fun Ui.expectRows(vararg rows: String) {
    val renderedRows = log
    for (row in rows) expect(row in renderedRows) { "Rendered panel is missing the row <$row>.\n--- rows ---\n${renderedRows.joinToString("\n")}" }
}

internal fun Ui.markupRow(part: String): String =
    markup.firstOrNull { part in plain(it) } ?: throw Check("No rendered row contains <$part>.\n--- rows ---\n${log.joinToString("\n")}")

internal fun Ui.expectInfo(part: String) {
    val info = screen.infoLabel.text.toString()
    expect(part in info) { "Info line is missing <$part>: $info" }
}

private fun pressKey(keycode: Int) {
    Gdx.input.inputProcessor.keyDown(keycode)
    Gdx.input.inputProcessor.keyUp(keycode)
}

internal suspend fun scenarios(ui: Ui, report: Report) {
    ui.settle()

    ui.scenario(report, "arrival: tags, speakers, styles, markers, an event and the hub", reset = false) {
        expectEquals("The storm hits The Lantern Pass like a thrown door.", log[0], "first row")
        expectRows(
            "# sfx: thunder  # mood: ominous",
            "You are Wren, and the toll lodge is the only light for ten miles. |pause=0.6|A sign on the post reads: \"Toll 3 coin. Blades <a hand long may pass.\"",
            "# sfx: wind  # sfx: door",
            "! event door_opened()",
            "ilse: \"Shut that door, you're letting the weather in!\"",
            "# speaker: ilse  # shout",
            "Your pack holds 12 coin. Grit 7, wits 6, 10 hp.",
            "The common room smells of wet wool and lamp oil.",
        )
        expect("[#ffffff]The [][#ffd27f]Lantern[][#ffffff] Pass[]" in markupRow("The storm hits")) { "Nested styles wrong: ${markupRow("The storm hits")}" }
        val lamp = markupRow("The lamp burns")
        expect("[#ff8800ff]amber[]" in lamp && "[#9ecbffff]pale blue[]" in lamp && "[ORANGE]raw[]" in lamp) { "Colours wrong: $lamp" }
        expect(log.indexOf("! event door_opened()") < log.indexOf("ilse: \"Shut that door, you're letting the weather in!\"")) { "Event order: $log" }
        expectEquals(
            listOf("Speak to Sergeant Varga", "Sit by Ilse's fire", "Follow the lamplighter's muttering", "Read the toll ledger",
                "Ask about the rumor", "Pay the toll and leave"),
            buttons.map(::buttonText), "hub choices (the side door is hidden by ink: door_open is false)",
        )
        expect(buttons.none { it.isDisabled }) { "Nothing is greyed on arrival" }
        expectRaw(
            "STEP 1", "ended     : false", "LINES (6)",
            "tags.raw  : [\"sfx: thunder\", \"mood: ominous\"]", "tags.map  : {sfx=thunder, mood=ominous}",
            "tags.raw  : [\"sfx: wind\", \"sfx: door\"]", "tags.map  : {sfx=door}",
            "speaker   : ilse", "tags.map  : {speaker=ilse, shout=}", "speaker   : null",
            "styles: b + em", "styles: color=ff8800 (hex ff8800ff)", "styles: color=#9ecbff (hex 9ecbffff)", "styles: color=orange",
            "pause=0.6 @66",
            "STEP TAGS", "door_opened()  lineIndex=3  id=1",
            "OPEN STYLES (0)", "CHOICES (6)",
            "target=3 (Int) actual=12 (Int)", "target=Hostile (String) actual=Wary (String)", "result=Met isMet=true",
        )
        expectInfo("suspicion=0")
        expectInfo("door_open=false")
        expectInfo("player_coin=12")
        shot("01-arrival")
    }

    ui.scenario(report, "varga: combined metrics, sanity chance, glue and the stat types") {
        for (c in listOf("npc varga sanity 100", "npc varga tiredness 0", "npc varga mood 100", "divert varga_talk")) command(c)
        expectEquals("varga: \"Papers.\" He stands crisp and alert. He nearly smiles.", log[0], "calm opening")
        expectRows(
            "varga: Strain 0.0, composure 100, unease 50, trust 25.5.",
            "varga: He keeps his eyes on you, patience at 100 percent.",
            "varga: He serves the crown. Stance: Wary. His hands are steady. He owns no lucky charm.",
        )
        expectRaw(
            "text      : \"He keeps his eyes on you, patience at 100 percent.\"", "speaker   : varga",
            "text      : \"Strain 0.0, composure 100, unease 50, trust 25.5.\"",
        )
        shot("02-varga-calm")

        for (c in listOf("npc varga sanity 0", "npc varga tiredness 100", "npc varga mood 0", "divert varga_talk")) command(c)
        val opening = log[0]
        expect(opening.startsWith("varga: \"Papers.\" He sways on his feet, wrung out. He scowls. \"Don't make me regret this.\" ")) { "Wrung out opening: $opening" }
        val flavor = opening.removePrefix("varga: \"Papers.\" He sways on his feet, wrung out. He scowls. \"Don't make me regret this.\" ")
        expect(flavor in listOf("He counts the rafters under his breath.", "A muscle in his jaw jumps.", "He laughs once, for no reason.")) { "Madness flavor: <$flavor>" }
        expectRows(
            "varga: Strain 100.0, composure 0, unease 83, trust 25.5.",
            "varga: He keeps his eyes on you,",
            "varga: patience at 0 percent.",
        )
        expectRaw("STEP 3", "text      : \"He keeps his eyes on you,\"", "text      : \"patience at 0 percent.\"")
        shot("03-varga-wrung")
    }

    ui.scenario(report, "varga: every kind of requirement, and hide, grey and show") {
        command("divert varga_talk")
        val names = buttons.map(::buttonText)
        expectEquals(6, names.size, "choices (Apologize is hidden by ink while suspicion is 0): $names")
        for (open in listOf("Show your papers", "Ask about the ice road", "Back to the room")) expect(!button(open).isDisabled) { "$open should be open" }
        expect(buttonText(button("Bluff")).endsWith("(needs player.wits >= 7 (have 6))")) { "Bluff note: ${names[1]}" }
        expect(buttonText(button("Arm-wrestle")).endsWith("(needs player.grit >= 8 (have 7))")) { "Arm note: ${names[2]}" }
        expect(buttonText(button("Rub a lucky charm")).endsWith("(needs player.luck >= 1 (stat missing))")) { "Luck note: ${names[3]}" }
        for (shut in listOf("Bluff", "Arm-wrestle", "Rub a lucky charm")) expect(button(shut).isDisabled) { "$shut should be greyed" }
        expectRaw(
            "target=drifter (String) actual=drifter (String)",
            "target=7 (Int) actual=6 (Int)", "result=Unmet isMet=false",
            "target=20 (Int) actual=25.5 (Float)",
            "target=false (Boolean) actual=false (Boolean)",
            "entity=player stat=luck op=>=", "target=1 (Int)", "result=MissingStat isMet=false",
            "available : false", "available : true",
        )
        shot("04-varga-choices-grey")
        val before = buttons.size
        click(button("Bluff"))
        expectEquals(before, buttons.size, "choices after clicking a greyed button")

        command("choicemode hide")
        expectEquals(listOf("Show your papers", "Ask about the ice road", "Back to the room"), buttons.map(::buttonText), "hidden mode")
        expectRaw("available : false")
        shot("05-varga-choices-hidden")

        command("choicemode show")
        expectEquals(6, buttons.size, "shown mode")
        expect(buttons.none { it.isDisabled }) { "Show mode keeps every choice clickable" }
        expect("(needs player.wits >= 7" in buttonText(button("Bluff"))) { "Show mode keeps the needs note" }
        expectInfo("choices: show")
    }

    ui.scenario(report, "varga: dice through a bound function, events with arguments, an observed variable") {
        for (c in listOf("stat player wits 9", "fixroll 3", "divert varga_talk")) command(c)
        expect(!button("Bluff").isDisabled) { "wits 9 opens the bluff" }
        click("Bluff")
        expectRows("varga: You roll 3, and your wits make it 12.", "# dice: d20", "varga: \"I know the Colonel. You are not him.\"", "! event suspicion_up(varga, 2)")
        expect(log.indexOf("varga: \"I know the Colonel. You are not him.\"") < log.indexOf("! event suspicion_up(varga, 2)")) { "Event order: $log" }
        expectInfo("suspicion=2")
        expectRaw("tags.map  : {dice=d20}", "suspicion_up(varga (String), 2 (Int))  lineIndex=2", "EVENTS (1)")
        expect(buttons.any { buttonText(it) == "Apologize" }) { "Apologize appears while suspicion > 0 (an ink condition): ${buttons.map(::buttonText)}" }
        shot("06-varga-bluff-fails")

        command("fixroll 18")
        click("Bluff")
        expectRows("varga: You roll 18, and your wits make it 27.", "! event give_item(pass)")
        expect(markup.any { "He stamps something.|slam|" in plain(it) }) { "Slam marker missing: $log" }
        expectInfo("door_open=true")
        expectRaw("give_item(pass (String))", "slam @")

        click("Apologize")
        expectRows("varga: \"Forget it,\" he says.")
        expectInfo("suspicion=0")
        expect(buttons.none { buttonText(it) == "Apologize" }) { "Apologize should go once suspicion is 0" }

        for (c in listOf("stat player grit 9", "fixroll 12", "divert varga_talk")) command(c)
        click("Arm-wrestle")
        expectRows("varga: You roll 12 on top of your grit, total 21.", "varga: His wrist goes down with a bang. |slam|\"Again tomorrow,\" he mutters.", "! event duel(varga, 21, true)")
        expectRaw("duel(varga (String), 21 (Int), true (Boolean))")
    }

    ui.scenario(report, "ilse: coin spent by an event is seen by the choices, the side door, the ending, restart") {
        for (c in listOf("npc ilse sanity 100", "divert ilse_talk")) command(c)
        val first = log[0]
        expect(first.startsWith("ilse: Ilse pours something amber into a chipped cup. She winces and shifts her bandaged arm. \"Sit, sit.\" The fire ")) { "Opening: $first" }
        expectRows("# mood: warm", "ilse: \"Rule one: never ask what is in the crates.\" She says it lightly, and she means it.",
            "narrator: She nods at Varga, who is at 40 percent of a good mood.", "# speaker: narrator")
        expect("[#ffffff]Rule one: [][#ffd27f]never[][#ffffff] ask what is in the crates.[]" in markupRow("Rule one")) { "Nested bold and em: ${markupRow("Rule one")}" }
        expect("[#ffd27f]She lowers her voice.[]" in markupRow("She lowers her voice")) { "em, first line" }
        expect("[#ffd27f]Her eyes keep going to the cellar door, the way a dog watches a pot.[]" in markupRow("Her eyes keep going")) { "em must carry onto the second line" }
        expectEquals(5, buttons.size, "ilse choices")
        expect(buttons.none { it.isDisabled }) { "Everything is open for Ilse by default: ${buttons.map(::buttonText)}" }
        expect(buttonText(buttons[3]) == "Say it |pause=0.4|with charm") { "Marker in a choice: ${buttonText(buttons[3])}" }
        expect("[GOLD]charm[]" in buttonMarkup(buttons[3])) { "Colour in a choice: ${buttonMarkup(buttons[3])}" }
        expectRaw(
            "entity=ilse stat=trust op=>", "target=50 (Int) actual=60.25 (Float)", "target=guild (String) actual=guild (String)",
            "entity=ilse stat=is_injured op===", "target=true (Boolean) actual=true (Boolean)",
            "target=4.5 (Float) actual=4.5 (Float)", "pause=0.4 @",
        )
        shot("07-ilse")

        command("stat player coin 5")
        click("Buy a flask")
        expectRows("! event spend(coin, 3)", "! event give_item(rum)")
        expect(markup.any { "She slides a flask across. |slam|\"Not a word to Varga.\"" in plain(it) }) { "Slam marker: $log" }
        expectInfo("player_coin=2")
        val buy = button("Buy a flask")
        expect(buy.isDisabled) { "With 2 coin left the choices must be read again and Buy greyed" }
        expect("(needs player.coin >= 3 (have 2))" in buttonText(buy)) { "Needs note: ${buttonText(buy)}" }
        expectRaw("spend(coin (String), 3 (Int))  lineIndex=", "id=2", "give_item(rum (String))", "id=3", "target=3 (Int) actual=2 (Int)")
        expect("note      :" !in raw) { "The harness no longer reads the choices again: $raw" }
        expect(screen.consoleLines.any { it.startsWith("event #2 spend(coin, 3)") } && screen.consoleLines.any { it.startsWith("event #3 give_item(rum)") }) { "Event ids in the console: ${screen.consoleLines.takeLast(8)}" }
        shot("08-ilse-bought")

        click("Ask for the side door")
        expectRows("! event flag(side_door, true)")
        expectInfo("door_open=true")
        expectRaw("flag(side_door (String), true (Boolean))")
        click("Back to the room")
        expect(buttons.any { buttonText(it) == "Slip out through the side door" }) { "Side door should be offered now: ${buttons.map(::buttonText)}" }
        expect(button("Pay the toll and leave").isDisabled) { "With 2 coin the toll cannot be paid" }
        expect("(needs player.coin >= 3 (have 2)" in buttonText(button("Pay the toll"))) { "Toll note" }
        click("Slip out")

        expectRows("You ease the side door open and the storm swallows you. |pause=1.0|Behind you the lamps gutter, one by one.", "! event left_pass(side_door)")
        expectEquals(emptyList(), buttons.map(::buttonText), "choices after the end")
        expectEquals("Story ended.", screen.statusLabel.text.toString(), "status")
        expect(screen.restartButton.isVisible) { "Restart should show at the end" }
        expectRaw("ended     : true", "CHOICES (0)", "left_pass(side_door (String))", "pause=1.0 @", "OPEN STYLES (0)")
        shot("09-ended")

        click(screen.restartButton)
        expectEquals("The storm hits The Lantern Pass like a thrown door.", log[0], "first row after restart")
        expectRaw("STEP 1", "ended     : false")
        expectInfo("player_coin=12")
        expectInfo("door_open=false")
        expect(!screen.restartButton.isVisible) { "Restart should hide again" }
        expectEquals(6, buttons.size, "hub choices after restart")
    }

    ui.scenario(report, "tobin: effects, markers, prose tags, styles open across a choice, save and load") {
        for (c in listOf("npc tobin sanity 0", "divert tobin_talk")) command(c)
        expect(log[0].startsWith("tobin: The lamplighter hugs his pole. ")) { "Opening: ${log[0]}" }
        expect(log[0].removePrefix("tobin: The lamplighter hugs his pole. ").let { it.startsWith("He whispers") || it.startsWith("He tallies") || it.startsWith("He sings") }) { "Sanity 0 always gives a flavor line: ${log[0]}" }
        val know = markupRow("The lamps know")
        expect("know[#8a8a8a]~shake[]" in know) { "Effect note on the shaking span: $know" }
        expect("|sfx name=thunder vol=0.8|" in plain(know)) { "Marker with params: ${plain(know)}" }
        expect("twice[#8a8a8a]~wave[]" in markupRow("Count them") && "again[#8a8a8a]~bounce[]" in markupRow("Count them")) { "wave and bounce notes: ${markupRow("Count them")}" }
        expect("|slam|" in plain(markupRow("Count them"))) { "slam marker" }
        expectRows("# mood: manic", "# Note to self: tobin counts in threes", "(style still open: color=#9ecbff, shake amp=1)")
        val shaking = screen.renderedLabels.first { "The lamps know" in plain(it.text.toString()) }
        expect(shaking.hasActions()) { "The shake line should be moving" }
        expectEquals(
            listOf("Listen closer", "Ask what he saw  (needs tobin.trust >= 40 (have 10))", "Leave him to it"),
            buttons.map(::buttonText), "tobin choices",
        )
        expect(button("Ask what he saw").isDisabled) { "Trust 10 is below 40" }
        expectRaw(
            "styles: shake amp=2 speed=0.5", "styles: wave=2,0.5", "styles: bounce amp=3", "sfx name=thunder vol=0.8 @", "slam @",
            "tags.map  : {Note to self: tobin counts in threes=}", "tags.map  : {mood=manic}",
            "OPEN STYLES (2)", "open      : color=#9ecbff (hex 9ecbffff) + shake amp=1",
            "styles: color=#9ecbff (hex 9ecbffff) + shake amp=1", "speaker   : tobin", "target=40 (Int) actual=10 (Int)",
        )
        shot("10-tobin-open-styles")

        val savedRows = log
        val savedChoices = buttons.map(::buttonText)
        command("save")
        click("Listen closer")
        val closing = markupRow("everything you did")
        expect(closing.contains("[#9ecbffff]everything you did.[]")) { "The colour left open over the choice should carry: $closing" }
        expect("~shake" in closing) { "and so should the shake: $closing" }
        expectRaw("#0 \"everything you did.\"  styles: color=#9ecbff (hex 9ecbffff) + shake amp=1", "STEP 3")
        shot("11-tobin-listen")
        val listened = closing

        command("load")
        expectEquals(savedRows, log, "rows after load")
        expectEquals(savedChoices, buttons.map(::buttonText), "choices after load")
        expectRaw("note      : loaded save: lines kept by the harness, choices and openStyles read again from the engine",
            "OPEN STYLES (2)", "open      : color=#9ecbff (hex 9ecbffff) + shake amp=1", "STEP 2")
        shot("12-tobin-loaded")
        click("Listen closer")
        expectEquals(listened, markupRow("everything you did"), "the same click after load gives the same styled row")
    }

    ui.scenario(report, "ledger: stat_text, stat_text_of and fmt in every mode, FLOOR CEILING INT, number format") {
        click("Read the toll ledger")
        expectRows(
            "The toll ledger is a column of figures in Ilse's slanted hand.",
            "Her trust, one decimal: floor 60.2, ceil 60.3, round 60.3, trunc 60.2.",
            "A debt of -2.5, no decimals: floor -3, ceil -2, round -3, trunc -2.",
            "Plain ink on her trust: FLOOR 60.0, CEILING 61.0, INT 60.",
            "Game formats: Ilse 60.3, Varga 25.5, Tobin 10, your charm 5, your faction drifter, injured true.",
            "Average of all three moods: 46.67.",
            "Stat check: Wren has charm, Ilse has no luck.",
        )
        expectRaw("text      : \"A debt of -2.5, no decimals: floor -3, ceil -2, round -3, trunc -2.\"", "STEP 2", "text      : \"The common room smells of wet wool and lamp oil.\"")
        shot("13-ledger")
        command("numfmt 1 trunc")
        command("divert ledger")
        expectRows("Game formats: Ilse 60.3, Varga 25.5, Tobin 10.0, your charm 4.5, your faction drifter, injured true.")
        command("numfmt 0 floor")
        command("divert ledger")
        expectRows("Game formats: Ilse 60.3, Varga 25.5, Tobin 10, your charm 4, your faction drifter, injured true.")
        expectRaw("your charm 4, your faction drifter")
    }

    ui.scenario(report, "rumor: an emit between a line and its glue joins and fires once") {
        click("Ask about the rumor")
        expectRows("ilse: Word has it that the pass closes at dawn.", "! event rumor_heard(pass)")
        expectEquals(1, log.count { it.startsWith("! event") }, "event rows")
        expect(log.indexOf("! event rumor_heard(pass)") < log.indexOf("ilse: Word has it that the pass closes at dawn.")) { "Event first: $log" }
        expectRaw("EVENTS (1)", "rumor_heard(pass (String))  lineIndex=0", "text      : \"Word has it that the pass closes at dawn.\"")
        shot("14-rumor")
    }

    ui.scenario(report, "pay the toll: an ink choice with two requirements, and the other ending") {
        click("Pay the toll and leave")
        expectRows("varga: \"Three coin.\" He stamps your papers. \"Mind the ice.\"", "! event spend(coin, 3)", "! event left_pass(front_door)")
        expectInfo("player_coin=9")
        expectRaw("ended     : true", "spend(coin (String), 3 (Int))")
        expect(screen.restartButton.isVisible) { "Restart shows at the end" }
    }

    ui.scenario(report, "layouts: split, raw only, rendered only, F2, and the raw panel scrolls") {
        expectEquals(ViewMode.Split, screen.viewMode, "default view")
        expect(screen.rawVisible && screen.renderedVisible) { "Both panels in split view" }
        expect(screen.rawMaxScrollY > 0f) { "The raw panel of the arrival step is longer than the window and must scroll" }
        screen.stage.mouseMoved(100, 300)
        settle()
        val before = screen.rawScrollY
        screen.stage.scrolled(0f, 6f)
        settle()
        expect(screen.rawScrollY > before) { "Raw panel did not scroll: $before -> ${screen.rawScrollY}" }

        command("view raw")
        expect(screen.rawVisible && !screen.renderedVisible) { "Raw only" }
        expectRaw("STEP 1")
        shot("15-view-raw")
        command("view rendered")
        expect(!screen.rawVisible && screen.renderedVisible) { "Rendered only" }
        expectEquals(6, buttons.size, "buttons in rendered view")
        shot("16-view-rendered")
        command("view split")
        expect(screen.rawVisible && screen.renderedVisible) { "Split again" }

        pressKey(Input.Keys.F2)
        expectEquals(ViewMode.Raw, screen.viewMode, "after F2")
        pressKey(Input.Keys.F2)
        expectEquals(ViewMode.Rendered, screen.viewMode, "after F2 twice")
        pressKey(Input.Keys.F2)
        expectEquals(ViewMode.Split, screen.viewMode, "after F2 three times")
        expectInfo("view: split")
        command("view raw")
        command("choose 0")
        expectRaw("STEP 2", "Papers.")
        command("view split")
    }

    ui.scenario(report, "debug: console set, divert and talk") {
        command("set player_hp 12")
        command("divert debug_status")
        expectRows("You have 12 hp.", "You feel fine.")
        expectRaw("text      : \"You have 12 hp.\"")
        command("stat player hp 0")
        command("divert debug_status")
        expectRows("You feel awful.")
        command("talk guard_chat guard")
        expect(log.any { it.startsWith("guard: \"Halt, traveler.\"") }) { "talk should show the guard line: $log" }
        expectRaw("speaker   : guard")
    }

    ui.scenario(report, "debug: guard scene follows npc stats and glue") {
        command("divert debug_menu")
        click("Guard chat")
        expectRows("guard: He keeps his eyes on you, patience at 60 percent.", "guard: Strength 12, strain 26.7, mood 60.", "guard: He serves the crown and you carry 0 pass.")
        val scene = log.joinToString("\n")
        expect("He stands crisp and alert." in scene && "He regards you flatly." in scene && "He holds his spear like a man who has used it." in scene) { "Calm guard:\n$scene" }
        shot("17-guard-calm")
        for (c in listOf("npc guard tiredness 90", "npc guard mood 10", "npc guard sanity 10", "npc guard strength 18")) command(c)
        click("Ask about the road")
        val after = log.joinToString("\n")
        expect("He sways on his feet, completely wrung out." in after && "He scowls at you." in after && "His grip on the spear looks iron-hard." in after) { "Tired guard:\n$after" }
        expectRows("guard: Strength 18, strain 90.0, mood 10.", "guard: He keeps his eyes on you,", "guard: patience at 10 percent.")
        expectRaw("text      : \"He keeps his eyes on you,\"", "text      : \"patience at 10 percent.\"")
    }

    ui.scenario(report, "debug: gate exchange has styles, two speakers and fx, and em spans two lines") {
        command("divert gate_exchange")
        val quiet = markupRow("Quiet!")
        expect("[#ff5555ff]Quiet![]" in quiet && "[#ffaa00ff]Strength[]" in quiet) { "Hex colours: $quiet" }
        expect("[#66ccffff]Hurry[]" in markupRow("Hurry")) { "0x colour: ${markupRow("Hurry")}" }
        expect("[GOLD]very[]" in markupRow("practiced")) { "Named colour: ${markupRow("practiced")}" }
        expect("[#ffd27f]He does not lower the spear.[]" in markupRow("does not lower")) { "em line 1" }
        expect("[#ffd27f]His eyes never leave you, not even for a blink.[]" in markupRow("never leave you")) { "em must carry onto line 2" }
        expect(log.any { it.startsWith("crowd: ") } && log.any { it.startsWith("merchant: ") }) { "Speakers: $log" }
        expect("shows[#8a8a8a]~shake[]" in quiet && "|slam|" in plain(quiet) && "Rank[#8a8a8a]~wave[]" in quiet) { "fx notes: $quiet" }
        expectRaw("speaker   : crowd", "speaker   : merchant", "styles: shake amp=2", "styles: wave=2,0.5", "slam @", "tags.map  : {speaker=crowd}")
        shot("18-gate-styles")
    }

    ui.scenario(report, "debug: requirement choices are greyed, hidden or shown with their needs") {
        command("divert gate_exchange")
        val unavailable = listOf("Pull rank", "Call his bluff", "Stand aside")
        for (name in unavailable) expect(button(name).isDisabled) { "$name should be disabled" }
        for (name in listOf("Back the merchant up", "Leave")) expect(!button(name).isDisabled) { "$name should be enabled" }
        expect(button("Show your papers").isDisabled && "(needs player.faction == rebels (have drifter))" in buttonText(button("Show your papers"))) { "papers note" }
        expect("(needs player.rank >= 3 (have 2))" in buttonText(button("Pull rank"))) { "rank note" }
        expect("(needs guard.mood < 50 (have 60))" in buttonText(button("Call his bluff"))) { "mood note" }
        expect("(needs player.hp >= 15 (have 10))" in buttonText(button("Stand aside"))) { "hp note" }
        expectRaw("entity=merchant stat=mood op=>=", "target=3 (Int) actual=2 (Int)", "target=50 (Int) actual=60.0 (Float)")
        val before = log.size
        click(button("Pull rank"))
        expectEquals(before, log.size, "rows after clicking a greyed button")
        command("choicemode hide")
        expectEquals(2, buttons.size, "hide mode")
        command("choicemode show")
        expectEquals(6, buttons.size, "show mode")
        expect(buttons.none { it.isDisabled }) { "Show keeps every choice clickable" }
        command("choicemode grey")
        command("stat player hp 20")
        command("stat player faction rebels")
        command("divert gate_exchange")
        expect(!button("Stand aside").isDisabled && "(needs" !in buttonText(button("Stand aside"))) { "Stand aside opens with hp 20" }
        expect(!button("Show your papers").isDisabled) { "Papers open for a rebel" }
        expectRaw("target=15 (Int) actual=20 (Int)")
    }

    ui.scenario(report, "debug: events reach the game, which acts on them and ink reads it back") {
        command("stat player hp 20")
        command("divert gate_exchange")
        click("Back the merchant up")
        val told = log.indexOfFirst { it.contains("Told you") }
        val event = log.indexOf("! event give_item(pass)")
        val menu = log.indexOf("Debug scenes.")
        expect(told >= 0 && event >= 0 && menu >= 0 && told < event && event < menu) { "Event should sit between the line and the menu: $log" }
        expectRaw("give_item(pass (String))  lineIndex=")
        click("Guard chat")
        expectRows("guard: He serves the crown and you carry 1 pass.")
        command("divert gate_exchange")
        click("Stand aside")
        expectRows("! event reputation(guard, 5)")
        expectRaw("reputation(guard (String), 5 (Int))")
        shot("19-debug-event")
    }

    ui.scenario(report, "debug: an emit before a glue line, a style open over a choice, save and load") {
        command("divert rumor_open")
        expectRows("Word has it that the gate falls tonight.", "! event rumor_heard(gate)", "(style still open: color=ff5555)")
        expectEquals(1, log.count { it.startsWith("! event") }, "event rows")
        expectRaw("rumor_heard(gate (String))  lineIndex=0", "OPEN STYLES (1)", "open      : color=ff5555 (hex ff5555ff)")
        val savedRows = log
        command("save")
        click("Listen")
        val listened = markupRow("he says")
        expect(listened.contains("[#ff5555ff]\"Run,\" he says, ") && "[#ffffff]fast[]" in listened) { "Red carries over the choice, bold inside it: $listened" }
        expectRaw("styles: color=ff5555 (hex ff5555ff)", "styles: color=ff5555 (hex ff5555ff) + b")
        shot("20-rumor-listen")
        command("load")
        expectEquals(savedRows.filterNot { it.startsWith("! event") }, log, "rows after load")
        expectRaw("note      : loaded save", "EVENTS (0)", "OPEN STYLES (1)", "open      : color=ff5555 (hex ff5555ff)")
        click("Listen")
        expectEquals(listened, markupRow("he says"), "line after load")
    }

    ui.scenario(report, "debug: requirements the engine reports as problems") {
        command("divert debug_requirements")
        val notes = mapOf(
            "Number against a word" to "(needs guard.strength >= high (have 12, type mismatch))",
            "Order on a string" to "(needs guard.faction > 3 (have crown, type mismatch))",
            "Boolean against a number" to "(needs player.is_injured == 4 (have false, type mismatch))",
            "Unreadable" to "(needs bad requirement 'this is not a clause')",
            "Missing stat" to "(needs guard.luck > 0 (stat missing))",
            "No entity, no speaker" to "(needs ?.mood > 1 (stat missing))",
        )
        for ((name, note) in notes) {
            expect(button(name).isDisabled && note in buttonText(button(name))) { "$name: ${buttonText(button(name))}" }
        }
        expect(!button("Two tags in one choice").isDisabled) { "Two requires tags are both read and both met" }
        expectRaw(
            "target=high (String) actual=12.0 (Float)", "result=Mismatch isMet=false",
            "target=3 (Int) actual=crown (String)", "target=4 (Int) actual=false (Boolean)",
            "result=Unreadable isMet=false",
            "entity=guard stat=luck op=>", "entity=null stat=mood op=>", "requires  : 2",
        )
        shot("21-debug-requirements")
    }

    ui.scenario(report, "debug: has_stat true, fmt floor and round, the aliases and the unknown fmt mode") {
        command("divert debug_menu")
        click("Coverage lab")
        expectRows(
            "guard: Has mood true, has luck false.",
            "guard: Floor 12, round 12.35.",
            "guard: Ceiling 13, truncate -12.",
            "guard: Sideways 13.",
        )
        expect(screen.consoleLines.any { it == "engine: fmt: unknown mode 'sideways', using round" }) {
            "The engine should log the unknown mode: ${screen.consoleLines}"
        }
        command("npc guard luck 4")
        command("divert coverage_lab")
        expectRows("guard: Has mood true, has luck true.")
        shot("22-coverage-lab")
    }

    ui.scenario(report, "debug: an unsafe bound function in choice text fails with the partial step in the raw panel") {
        command("divert debug_menu")
        click("Unsafe function in choice text")
        expectRows("This line is built before the failure.")
        expectRaw("STORY ERROR", "This line is built before the failure.", "lookahead")
        expect("Story error" in screen.statusLabel.text.toString()) { "Status: ${screen.statusLabel.text}" }
        expect(screen.restartButton.isVisible) { "A failed step offers Restart" }
        expectEquals(0, buttons.size, "choices of a failed step")
        shot("23-unsafe-error")
        click(screen.restartButton)
        expect(log[0].startsWith("The storm hits")) { "Restart after a failure: $log" }
    }

    ui.scenario(report, "validate: the console report on the showcase story") {
        val before = screen.consoleLines.size
        command("validate")
        val output = screen.consoleLines.drop(before)
        expect(output.firstOrNull()?.startsWith("Validation: 0 errors") == true) { "validate: $output" }
        expect(output.none { it.contains("UnboundExternal") }) { "validate: $output" }
    }

    ui.scenario(report, "console: vars, var and reload") {
        val before = screen.consoleLines.size
        command("vars")
        val listed = screen.consoleLines.drop(before)
        expect("suspicion = 0" in listed && "door_open = false" in listed && "player_coin = 12" in listed) { "vars: $listed" }
        command("var suspicion")
        expect("suspicion = 0 (Integer)" in screen.consoleLines) { "var: ${screen.consoleLines.takeLast(3)}" }
        command("var nope")
        expect("nope is not a declared variable" in screen.consoleLines) { "var of nothing: ${screen.consoleLines.takeLast(3)}" }
        command("set suspicion 4")
        command("divert debug_menu")
        val mark = screen.consoleLines.size
        command("reload")
        var frames = 0
        while (screen.consoleLines.drop(mark).none { it == "Reloaded" || it.startsWith("Reload failed") } && frames++ < 600) skipFrame()
        val after = screen.consoleLines.drop(mark)
        expect("Reloaded" in after) { "reload: $after" }
        settle()
        expect(log[0].startsWith("The storm hits")) { "A reload starts the story again: $log" }
        expectEquals("0", screen.getVariable("suspicion").toString(), "suspicion after the reload")
    }

    ui.scenario(report, "seed makes the guard repeatable") {
        fun guardRun(): List<String> {
            screen.runCommand("restart")
            screen.runCommand("seed 7")
            screen.runCommand("npc guard sanity 10")
            screen.runCommand("talk guard_chat guard")
            return log + raw
        }
        val first = guardRun()
        settle()
        val second = guardRun()
        expect(first.size > 1) { "talk produced nothing" }
        expectEquals(first.dropLast(1), second.dropLast(1), "guard scene rows with the same seed")
        expectEquals(first.last().substringAfter("LINES"), second.last().substringAfter("LINES"), "raw panel with the same seed")
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

private fun buttonMarkup(button: TextButton): String = button.label.text.toString()
