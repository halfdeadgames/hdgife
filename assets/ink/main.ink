// Showcase story for the harness. Each line names a feature and uses it where it can.
// Entities: tester (default speaker), subject, player. Stats are set in Showcase.kt.
// safeDouble() is bound lookahead-safe, unsafeRoll() is not. The harness handles the spend event and watches
// counter, switchOn and player_coins. A bare # starts an ink tag, so hex colors use \#.
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
Hub. Each choice opens a feature group.
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
Plain text, no markup.
The speaker stays tester until speak() is called again.
~ speak("subject")
Now subject, via speak("subject").
Speaker tag here, set to player. # speaker: player
Back to the speaker speak() set last.
~ speak("")
No speaker, speak("") cleared it.
~ speak("tester")
tester again, after a second speak("tester").
A trailing speak("subject") follows this last line.
~ speak("subject")
+ [A requirement without an entity reads the trailing speaker # requires: intStat < 5] -> hub
+ [Back to the hub] -> hub

=== tagGroup ===
~ speak("tester")
Tag with a key and value. # color: red
Bare tag. # flagged
Repeated key, the map keeps the last value. # layer: one # layer: two
Int tag. # intTag: 42
Float tag. # floatTag: 1.5
Bool tag. # boolTag: true
Tag with text as its value. # note: hello there
# carried: from the line above
This line gets the tag from the line above it.
Speaker tag again, set to tester. # speaker: tester
+ [A choice carries tags too # choiceTag: yes # choiceCount: 3] -> hub
+ [Back to the hub] -> hub

=== styleGroup ===
~ speak("tester")
Inline <color=red>color tags</color>.
Color by name: <color=gold>gold</color>, <color=coral>coral</color>, <color=sky>sky</color>.
Hex, six digits: <color=ff8800>ff8800</color>.
Hex, three digits: <color=f80>f80</color>.
Hex with alpha: <color=ff8800cc>ff8800cc</color>.
0x prefix: <color=0xff8800>0xff8800</color>.
Hash prefix, escaped in the source: <color=\#ff8800>this one</color>.
An <color=notacolor>unknown color name</color> is noted, not drawn.
<b>Bold</b> and <em>emphasis</em>.
Nesting: <b>bold with <em>emphasis inside bold</em> and <color=red>red inside bold</color></b>.
Nested colors: <color=blue>blue with <color=red>red nested</color> inside it</color>.
Shake: <shake amp=2>a shake effect</shake>.
Shake again, <shake amp=3 speed=0.5>with two named params</shake>.
Wave takes <wave=2,0.5>positional params</wave>.
Bounce: <bounce amp=3>a bounce effect</bounce>.
Span: <color=green>one style over two lines
and ends on the second line.</color>
The << escape writes a literal less-than sign.
-> hub

=== styleEdge ===
~ speak("tester")
Opening <color=orange>an orange style, left open across the choice below.
+ [The style carries onto the line that follows this choice] Still orange here, and the style closes.</color>
  -> styleCap

=== styleCap ===
maxOpenStyles is 4 in the harness. The lines below each leave a style open.
~ temp opened = 0
- (openLoop)
Line {opened} opens <em>an emphasis style and never closes it.
~ opened++
{ opened < 6: -> openLoop }
The cap dropped the oldest styles, so these four closes </em></em></em></em> end what is left.
A close tag that was never opened </b> is ignored.
-> hub

=== markerGroup ===
~ speak("tester")
This one pauses<pause=0.5/> here.
A longer pause<pause=1.5/> that a tap or click skips.
The <slam/>slam marker flashes.
A sound marker<sfx name=click vol=0.8/> with two named params.
A value marker<mark=3/> and a bare one<beat/> at the end.
-> hub

=== statGroup ===
~ speak("tester")
intStat (int) is {stat("intStat")} via stat().
floatStat (float) is {stat("floatStat")}, raw.
textStat (string) is {stat("textStat")}.
flagStat (bool) is {stat("flagStat")}.
modeStat (enum) is {stat("modeStat")}, the entry name.
missingStat is {has_stat("missingStat"): present|absent} per has_stat().
intStat is {has_stat("intStat"): present|absent} per has_stat().
subject.intStat via stat_of(): {stat_of("subject", "intStat")}.
subject.modeStat is {stat_of("subject", "modeStat")}, and has_stat_of() says {has_stat_of("subject", "modeStat"): present|absent}.
subject.missingStat per has_stat_of(): {has_stat_of("subject", "missingStat"): present|absent}.
stat_text() on intStat: {stat_text("intStat")}.
stat_text() on textStat: {stat_text("textStat")}.
stat_text() on flagStat: {stat_text("flagStat")}.
stat_text() on modeStat: {stat_text("modeStat")}.
stat_text() on missingStat: {stat_text("missingStat")}.
stat_text_of() on player coins: {stat_text_of("player", "coins")}.
stat_text_of() on subject floatStat: {stat_text_of("subject", "floatStat")}.
-> hub

=== formatGroup ===
~ speak("tester")
~ temp ratio = stat("floatStat")
~ temp debt = 0 - 2.5
tester floatStat via stat_text(): {stat_text("floatStat")}, two decimals from the harness override.
subject floatStat via stat_text_of(): {stat_text_of("subject", "floatStat")}, default format of 0 decimals.
numfmt in the console changes the default, e.g. numfmt 1 trunc.
Formatting 2.75 to one decimal: floor {fmt(ratio, 1, "floor")}, ceil {fmt(ratio, 1, "ceil")}, round {fmt(ratio, 1, "round")}, trunc {fmt(ratio, 1, "trunc")}.
Formatting -2.5 to no decimals: floor {fmt(debt, 0, "floor")}, ceil {fmt(debt, 0, "ceil")}, round {fmt(debt, 0, "round")}, trunc {fmt(debt, 0, "trunc")}.
Plain ink on 2.75: FLOOR {FLOOR(ratio)}, CEILING {CEILING(ratio)}, INT {INT(ratio)}.
Plain ink on -2.5: FLOOR {FLOOR(debt)}, CEILING {CEILING(debt)}, INT {INT(debt)}.
-> hub

=== utilGroup ===
~ speak("tester")
clamp(150, 0, 100) gives {clamp(150, 0, 100)}, clamp(0 - 5, 0, 100) gives {clamp(0 - 5, 0, 100)}.
avg2(10, 25) gives {fmt(avg2(10, 25), 1, "round")}.
avg3(10, 20, 60) gives {fmt(avg3(10, 20, 60), 1, "round")}.
blend(10, 1, 40, 3) gives {fmt(blend(10, 1, 40, 3), 1, "round")}, the second value weighs three times as much.
invert(30) gives {invert(30)}.
scale100(30, 20, 60) gives {fmt(scale100(30, 20, 60), 1, "round")}, where 30 sits between 20 and 60 in percent.
chance(100) gives {chance(100): true|false}, chance(0) gives {chance(0): true|false}.
pressure() averages inverted energy, inverted calm and focus: {fmt(pressure(), 1, "round")}.
spread() blends energy and calm two to one: {fmt(spread(), 1, "round")}.
readiness() scales focus, adds half the calm and clamps: {fmt(readiness(), 0, "round")}.
-> hub

=== glueGroup ===
~ speak("tester")
Glue after a stat call joins this line to the next,
{stat("intStat") > 5: <>} and the stat call reads intStat as {stat_text("intStat")}.
Glue across an emit
~ emit1("glued", "payload")
<> and the event fires once.
Plain glue at the end of a line <>
continues on the next source line.
-> hub

=== eventGroup ===
~ speak("tester")
emit() with no arguments comes next.
~ emit("noArgs")
Next, emit1() with a string.
~ emit1("oneArg", "text")
Then emit2() with an int and a float.
~ emit2("twoArgs", 7, 2.5)
Last, emit3() with a string, an int and a bool.
~ emit3("threeArgs", "text", 21, true)
+ [Spend 3 coins with a spend event # requires: player.coins >= 3] -> eventSpend
+ [Back to the hub] -> hub

=== eventSpend ===
~ emit2("spend", "coins", 3)
The game handles the spend event by lowering the player's coins.
player_coins is {player_coins} when this line is read. The choices below see the new value.
+ [Spend 3 coins again # requires: player.coins >= 3] -> eventSpend
+ [Back to the event group] -> eventGroup
+ [Back to the hub] -> hub

=== bindingGroup ===
~ speak("tester")
safeDouble(21) gives {safeDouble(21)}, bound as lookahead safe.
unsafeRoll(6) gives {unsafeRoll(6)}, not lookahead safe, so it stays out of choice text.
counter is {counter}, shown in the harness info line.
switchOn is {switchOn}.
player_coins is {player_coins}, synced from the player's coins stat.
+ [Safe function in choice text: safeDouble(4) is {safeDouble(4)}] -> hub
+ [Back to the hub] -> hub

=== requirementGroup ===
~ speak("tester")
Each choice below has a requirement tag. The rendered panel lists what it needs.
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
That choice was available when picked.
-> requirementChoices

=== choiceKinds ===
~ speak("tester")
Conditional, sticky and once-only choices follow.
-> kindChoices

= kindChoices
* [Once-only choice, gone after one use] After the once-only choice.
  -> kindChoices
+ [Sticky choice, offered every time] After the sticky choice.
  -> kindChoices
+ {counter >= 2} [Conditional choice, offered once counter reaches 2] After the conditional choice.
  -> kindChoices
+ [Raise counter, currently {counter}]
  ~ counter++
  counter is now {counter}.
  -> kindChoices
+ [Flip switchOn, currently {switchOn}]
  ~ switchOn = not switchOn
  switchOn is now {switchOn}.
  -> kindChoices
+ [Back to the hub] -> hub

=== randomGroup ===
~ speak("tester")
Shuffle picked {~one|two|three|four|five|six}.
Another shuffle picked {~one|two|three|four|five|six}.
Cycle is at {&one|two|three}.
Stopping sequence is at {one|two|three}.
Once-only sequence is at {!one|two|three}.
RANDOM(1, 100) gave {RANDOM(1, 100)}.
chance(50) gave {chance(50): true|false}.
seed in the console makes shuffles, RANDOM and chance repeat.
-> hub

=== endingGroup ===
~ speak("tester")
Heading for the end, then an ended event.
~ emit1("ended", "showcase")
Last line. The step after it has no choices.
-> END
