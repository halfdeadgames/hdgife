// Feature showcase for the hdgife harness. Each line says which feature it shows and uses that feature where it can.
// Entities: tester (the default speaker), subject and player. Their stats are set in Showcase.kt.
// Game side: safeDouble() is bound lookahead-safe, unsafeRoll() is not. The harness handles the spend event and watches
// counter, switchOn and player_coins. Markers are written without a hash because a hash starts an ink tag, so a hex
// color is written with \# when it needs the hash.
INCLUDE hdgife.ink
INCLUDE hdgife_util.ink
INCLUDE debug.ink

EXTERNAL safeDouble(value)
EXTERNAL unsafeRoll(sides)

VAR counter = 0
VAR switchOn = false
VAR player_coins = 0
VAR player_health = 0

~ speak("tester")
-> hub

=== function safeDouble(value) ===
~ return value * 2

=== function unsafeRoll(sides) ===
~ return 1

// Combined metrics built from the util helpers.
=== function pressure() ===
~ return avg3(invert(stat("energy")), invert(stat("calm")), stat("focus"))

=== function spread() ===
~ return blend(stat("energy"), 2, stat("calm"), 1)

=== function readiness() ===
~ return clamp(scale100(stat("focus"), 20, 60) + stat("calm") / 2.0 - 20, 0, 100)

=== hub ===
~ speak("tester")
This is the hub, and each choice opens one group of features.
+ [Plain text and speakers] -> textGroup
+ [Tags] -> tagGroup
+ [Styles] -> styleGroup
+ [Style edge cases] -> styleEdge
+ [Point markers] -> markerGroup
+ [Stats] -> statGroup
+ [Number formats] -> formatGroup
+ [Util helpers and combined metrics] -> utilGroup
+ [Glue] -> glueGroup
+ [Events] -> eventGroup
+ [Bound functions and observed variables] -> bindingGroup
+ [Requirements] -> requirementGroup
+ [Choice kinds] -> choiceKinds
+ [Randomness] -> randomGroup
+ [Validation and debug cases] -> validationGroup
+ [Ending] -> endingGroup

=== textGroup ===
~ speak("tester")
This line is plain text with no markup.
This line is spoken by tester, which speak("tester") set.
The speaker stays tester until speak() is called again.
~ speak("subject")
This line is spoken by subject, which speak("subject") set.
The speaker of this line is set with a speaker tag. # speaker: player
This line is back to the speaker that speak() set last.
~ speak("")
This line has no speaker because speak("") cleared it.
~ speak("tester")
This line is spoken by tester again after a second speak("tester").
This is the last line, and a trailing speak("subject") follows it before the choices.
~ speak("subject")
+ [A requirement without an entity reads the trailing speaker # requires: intStat < 5] -> hub
+ [Back to the hub] -> hub

=== tagGroup ===
~ speak("tester")
This line has a tag with a key and a value. # color: red
This line has a bare tag. # flagged
This line has a repeated key, and the map keeps the last value. # layer: one # layer: two
This line has an int tag. # intTag: 42
This line has a float tag. # floatTag: 1.5
This line has a bool tag. # boolTag: true
This line has a tag whose value is text. # note: hello there
# carried: from the line above
This line receives the tag from the tag line above it.
The speaker of this line is set with a speaker tag. # speaker: tester
+ [A choice carries tags too # choiceTag: yes # choiceCount: 3] -> hub
+ [Back to the hub] -> hub

=== styleGroup ===
~ speak("tester")
This line shows <color=red>inline color tags</color>.
This line shows named colors <color=gold>gold</color>, <color=coral>coral</color> and <color=sky>sky</color>.
This line shows a <color=ff8800>hex color</color>.
This line shows a <color=f80>three digit hex color</color>.
This line shows an <color=ff8800cc>eight digit hex color with alpha</color>.
This line shows a <color=0xff8800>0x prefixed hex color</color>.
This line shows a <color=\#ff8800>hash prefixed hex color</color>, escaped in the ink source.
This line shows an <color=notacolor>unknown color name</color>, which the display cannot draw.
This line shows <b>bold</b> and <em>emphasis</em>.
This line shows <b>nesting with <em>emphasis inside bold</em> and <color=red>red inside bold</color></b>.
This line shows <color=blue>blue with <color=red>red nested</color> inside it</color>.
This line uses <shake amp=2>a shake effect</shake>.
This line uses <shake amp=3 speed=0.5>a shake with two named params</shake>.
This line uses <wave=2,0.5>a wave effect with positional params</wave>.
This line uses <bounce amp=3>a bounce effect</bounce>.
This line shows a <color=green>style that spans two lines
and ends on the second line.</color>
This line shows the << escape, which writes a literal less-than sign.
-> hub

=== styleEdge ===
~ speak("tester")
This line opens <color=orange>an orange style and leaves it open across the choice below.
+ [The style carries onto the line that follows this choice] This line follows the choice, still orange, and closes it here.</color>
  -> styleCap

=== styleCap ===
The harness sets maxOpenStyles to 4, and the next lines each leave a style open.
~ temp opened = 0
- (openLoop)
This is line {opened}, and it opens <em>an emphasis style without closing it.
~ opened++
{ opened < 6: -> openLoop }
The oldest styles were dropped at the cap, so these four closes </em></em></em></em> end what is left.
A close tag that was never opened </b> is ignored.
-> hub

=== markerGroup ===
~ speak("tester")
This line has a pause<pause=0.5/> in the middle.
This line has a long pause<pause=1.5/> that the typewriter honors until a tap or click skips it.
This line has a <slam/>slam marker, drawn as a flash.
This line has a sound marker<sfx name=click vol=0.8/> with two named params.
This line has a marker with a value<mark=3/> and a bare marker<beat/> at the end.
-> hub

=== statGroup ===
~ speak("tester")
The int stat intStat is {stat("intStat")}, read with stat().
The float stat floatStat is {stat("floatStat")}, read with stat() as a raw float.
The string stat textStat is {stat("textStat")}, read with stat().
The bool stat flagStat is {stat("flagStat")}, read with stat().
The enum stat modeStat is {stat("modeStat")}, because an enum arrives as its name.
The missing stat missingStat is {has_stat("missingStat"): present|absent} according to has_stat().
The stat intStat is {has_stat("intStat"): present|absent} according to has_stat().
The subject's intStat is {stat_of("subject", "intStat")}, read with stat_of().
The subject's modeStat is {stat_of("subject", "modeStat")}, and has_stat_of() says {has_stat_of("subject", "modeStat"): present|absent}.
The subject's missingStat is {has_stat_of("subject", "missingStat"): present|absent} according to has_stat_of().
The int stat as text is {stat_text("intStat")}, from stat_text().
The string stat as text is {stat_text("textStat")}, from stat_text().
The bool stat as text is {stat_text("flagStat")}, from stat_text().
The enum stat as text is {stat_text("modeStat")}, from stat_text().
The missing stat as text is {stat_text("missingStat")}, from stat_text().
The player's coins as text are {stat_text_of("player", "coins")}, from stat_text_of().
The subject's floatStat as text is {stat_text_of("subject", "floatStat")}, from stat_text_of().
-> hub

=== formatGroup ===
~ speak("tester")
~ temp ratio = stat("floatStat")
~ temp debt = 0 - 2.5
The tester's floatStat is {stat_text("floatStat")}, formatted with 2 decimals by the harness override.
The subject's floatStat is {stat_text_of("subject", "floatStat")}, formatted with the default of 0 decimals.
The console command numfmt changes the default, for example numfmt 1 trunc.
This line formats 2.75 with one decimal: floor {fmt(ratio, 1, "floor")}, ceil {fmt(ratio, 1, "ceil")}, round {fmt(ratio, 1, "round")}, trunc {fmt(ratio, 1, "trunc")}.
This line formats -2.5 with no decimals: floor {fmt(debt, 0, "floor")}, ceil {fmt(debt, 0, "ceil")}, round {fmt(debt, 0, "round")}, trunc {fmt(debt, 0, "trunc")}.
This line uses plain ink on 2.75: FLOOR {FLOOR(ratio)}, CEILING {CEILING(ratio)}, INT {INT(ratio)}.
This line uses plain ink on -2.5: FLOOR {FLOOR(debt)}, CEILING {CEILING(debt)}, INT {INT(debt)}.
-> hub

=== utilGroup ===
~ speak("tester")
This line uses clamp(150, 0, 100), which gives {clamp(150, 0, 100)}, and clamp(0 - 5, 0, 100), which gives {clamp(0 - 5, 0, 100)}.
This line uses avg2(10, 25), which gives {fmt(avg2(10, 25), 1, "round")}.
This line uses avg3(10, 20, 60), which gives {fmt(avg3(10, 20, 60), 1, "round")}.
This line uses blend(10, 1, 40, 3), which gives {fmt(blend(10, 1, 40, 3), 1, "round")} because the second value weighs three times as much.
This line uses invert(30), which gives {invert(30)}.
This line uses scale100(30, 20, 60), which gives {fmt(scale100(30, 20, 60), 1, "round")}, the place of 30 between 20 and 60 as a percent.
This line uses chance(100), which gives {chance(100): true|false}, and chance(0), which gives {chance(0): true|false}.
This line shows pressure(), an average of inverted energy, inverted calm and focus: {fmt(pressure(), 1, "round")}.
This line shows spread(), energy and calm blended two to one: {fmt(spread(), 1, "round")}.
This line shows readiness(), a scaled focus plus half the calm, clamped: {fmt(readiness(), 0, "round")}.
-> hub

=== glueGroup ===
~ speak("tester")
This line is joined to the next one by glue after a stat call,
{stat("intStat") > 5: <>} and the stat call reads intStat as {stat_text("intStat")}.
This line is joined to the next one across an emit
~ emit1("glued", "payload")
<> and the event fires once.
This line ends in plain glue <>
and continues on the next source line.
-> hub

=== eventGroup ===
~ speak("tester")
This line is followed by emit() with no arguments.
~ emit("noArgs")
This line is followed by emit1() with a string.
~ emit1("oneArg", "text")
This line is followed by emit2() with an int and a float.
~ emit2("twoArgs", 7, 2.5)
This line is followed by emit3() with a string, an int and a bool.
~ emit3("threeArgs", "text", 21, true)
+ [Spend 3 coins with a spend event # requires: player.coins >= 3] -> eventSpend
+ [Back to the hub] -> hub

=== eventSpend ===
~ emit2("spend", "coins", 3)
This line follows a spend event, which the game handles by lowering the player's coins.
The ink variable player_coins is {player_coins} while this step is read, and the choices below see the new coins.
+ [Spend 3 coins again # requires: player.coins >= 3] -> eventSpend
+ [Back to the event group] -> eventGroup
+ [Back to the hub] -> hub

=== bindingGroup ===
~ speak("tester")
The bound function safeDouble(21) gives {safeDouble(21)}, and it is bound as lookahead safe.
The bound function unsafeRoll(6) gives {unsafeRoll(6)}, and it is not lookahead safe, so it stays out of choice text.
The observed variable counter is {counter}, and the harness shows it in the info line.
The observed variable switchOn is {switchOn}.
The synced variable player_coins is {player_coins}, copied from the player's coins stat.
+ [Safe function in choice text: safeDouble(4) is {safeDouble(4)}] -> hub
+ [Back to the hub] -> hub

=== requirementGroup ===
~ speak("tester")
These choices carry requirement tags, and the rendered panel lists what each one needs.
-> requirementChoices

= requirementChoices
+ [Numeric, met # requires: tester.intStat >= 5] -> requirementPicked
+ [Numeric, unmet # requires: tester.intStat >= 9] -> requirementPicked
+ [Float, met # requires: tester.floatStat > 2.5] -> requirementPicked
+ [String, met # requires: tester.textStat == alpha] -> requirementPicked
+ [String, unmet # requires: tester.textStat == beta] -> requirementPicked
+ [Bool, met # requires: tester.flagStat == true] -> requirementPicked
+ [Bool, unmet # requires: subject.flagStat == true] -> requirementPicked
+ [Enum compared as a string # requires: tester.modeStat == Steady] -> requirementPicked
+ [No entity, read for the speaker tester # requires: intStat >= 5] -> requirementPicked
+ [Explicit entity, not the speaker # requires: subject.intStat < 5] -> requirementPicked
+ [Quoted names # requires: "subject"."two words" == "a b"] -> requirementPicked
+ [Missing stat # requires: subject.luck > 0] -> requirementPicked
+ [Type mismatch, a number against a word # requires: tester.intStat >= high] -> requirementPicked
+ [Unreadable clause # requires: this is not a clause] -> requirementPicked
+ [Or with the word or, one side met # requires: tester.intStat >= 9 or subject.flagStat == false] -> requirementPicked
+ [Or with an escaped pipe, one side met # requires: tester.intStat >= 9 \| subject.intStat == 3] -> requirementPicked
+ [Or with neither side met # requires: tester.intStat >= 9 or subject.flagStat == true] -> requirementPicked
+ [Not, met # requires: !subject.flagStat == true] -> requirementPicked
+ [Not, unmet # requires: not tester.flagStat == true] -> requirementPicked
+ [Not inside an or # requires: !tester.intStat >= 5 or tester.textStat == alpha] -> requirementPicked
+ [Two clauses separated by a comma # requires: tester.intStat >= 5, subject.intStat <= 3] -> requirementPicked
+ [Two requires tags # requires: tester.intStat >= 5 # requires: subject.intStat >= 9] -> requirementPicked
+ [Back to the hub] -> hub

= requirementPicked
This line follows a choice that was available when it was chosen.
-> requirementChoices

=== choiceKinds ===
~ speak("tester")
This group shows an ink conditional choice, a sticky choice and a once-only choice.
-> kindChoices

= kindChoices
* [Once-only choice, gone after one use] This line follows the once-only choice.
  -> kindChoices
+ [Sticky choice, offered every time] This line follows the sticky choice.
  -> kindChoices
+ {counter >= 2} [Conditional choice, offered once counter reaches 2] This line follows the conditional choice.
  -> kindChoices
+ [Raise counter, currently {counter}]
  ~ counter++
  The observed variable counter is now {counter}.
  -> kindChoices
+ [Flip switchOn, currently {switchOn}]
  ~ switchOn = not switchOn
  The observed variable switchOn is now {switchOn}.
  -> kindChoices
+ [Back to the hub] -> hub

=== randomGroup ===
~ speak("tester")
This line shows a shuffle, which picked {~one|two|three|four|five|six}.
This line shows a second shuffle, which picked {~one|two|three|four|five|six}.
This line shows a cycle, which is at {&one|two|three}.
This line shows a stopping sequence, which is at {one|two|three}.
This line shows a once-only sequence, which is at {!one|two|three}.
This line shows RANDOM(1, 100), which gave {RANDOM(1, 100)}.
This line shows chance(50), which gave {chance(50): true|false}.
The console command seed makes the shuffles, RANDOM and chance repeat.
-> hub

=== endingGroup ===
~ speak("tester")
This line leads to the ending, and an ended event follows it.
~ emit1("ended", "showcase")
This is the last line, and the step after it has no choices.
-> END
