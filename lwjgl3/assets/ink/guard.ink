// Demo scenes for the stat functions, speakers, styles, events and choice requirements.
// Stats are 0..100 except strength, rank (int), faction (string) and is_injured (bool). Nothing here is built
// into the engine, these names are just this story's.
//
// Markers in text, written without '#' because '#' starts an ink tag:
//   <em>..</em>  <b>..</b>                   the harness colors these
//   <color=red>..</color>                    a color name, resolved by the harness
//   <color=ff5555>..</color>                 hex (also 0xRRGGBB, RGB, RRGGBBAA), see Style.hexColor()
//   <color=\#66ccff>..</color>               "\#" is ink's escape for a literal '#', also accepted
//   <shake amp=2>..</shake>, <wave=2,0.5>    effects: the harness only logs them
//   <pause=0.5/>, <slam/>                    point markers: logged with their character offset
// A style stays open across lines until its closing marker.
// Choice requirements go INSIDE the brackets: [Text # requires: strength >= 12, faction == crown].
// ~ emit("give_item", "pass") tells the game something happened. The harness logs it and acts on it.
//
// Declared here, bound by the harness (DialogScreen), with an ink fallback for when nothing is bound.
EXTERNAL inventory_count(item)

// one combined metric: tiredness plus low mood plus low sanity
=== function strain() ===
~ return avg3(stat("tiredness"), invert(stat("mood")), invert(stat("sanity")))

=== function inventory_count(item) ===
~ return 0

=== guard_chat ===
~ speak("guard")
~ temp madness = invert(stat("sanity"))
"Halt, traveler." {strain() > 66: He sways on his feet, completely wrung out.|{strain() > 33: He looks worn but steady.|He stands crisp and alert.}} {stat("mood") < 30: He scowls at you.|{stat("mood") > 70: He grins at you.|He regards you flatly.}} {stat("strength") >= 15: His grip on the spear looks iron-hard.|{stat("strength") >= 8: He holds his spear like a man who has used it.|The spear seems too heavy for him.}} {chance(madness): {~He mutters to someone who isn't there.|His eye twitches.|He laughs, briefly and for no reason.}}
Strength {fmt(stat("strength"), 0, "floor")}, strain {fmt(strain(), 1, "round")}, mood {stat_text("mood")}.
// glue with a stat: the stat call sits between the line break and the glue, so ink needs lookahead-safe functions
He keeps his eyes on you,
{stat("mood") > 50: <>} patience at {stat_text("mood")} percent.
He serves the {stat("faction")} and you carry {inventory_count("pass")} pass.
{has_stat("luck"): He rubs a lucky charm.}
+ [Ask about the road] "Watch your step out there."
  -> guard_chat
+ {stat("strength") >= 10} [Arm-wrestle him] You win, barely.
  -> guard_chat
+ [Impress him with <em>your</em> muscles # requires: player.strength >= 10]
  He nods, grudgingly.
  -> guard_chat
+ [Ask him to bend the rules # requires: mood >= 50, sanity > 30]
  "Fine. Just this once." <em>He glances around.</em>
  -> guard_chat
+ [Whisper that you are with the <color=ff5555>rebels</color> # requires: player.faction == rebels, faction != rebels]
  His hand drifts toward his sword. <pause=0.8/> "I did not hear that."
  -> guard_chat
+ [Walk past him # requires: player.is_injured == false, faction == crown]
  He lets you through. <slam/>
  -> hub
+ [Leave] -> hub

// Two speakers. The first line names itself with a tag (that line only), the others use speak().
=== gate_exchange ===
~ speak("guard")
"Halt.<pause=0.4/> State your business." <em>He does not lower the spear.
His eyes never leave you, not even for a blink.</em>
~ speak("merchant")
"He's with me, officer." Her smile is <color=gold>very</color> practiced. {stat("mood") > 50: She sounds calm.|She sounds rattled.}
A voice from the queue: "<color=0x66ccff>Hurry</color> up!" # speaker: crowd
~ speak("guard")
"<color=ff5555>Quiet!</color> <color=\#ffaa00>Strength</color> {stat_text("strength")}, mood {stat_text("mood")}." He means it, and it <shake amp=2>shows</shake><slam/>. <wave=2,0.5>Rank</wave> {stat_of("player", "rank")}.
+ [Back the merchant up # requires: merchant.mood >= 50]
  ~ speak("merchant")
  "Told you," she says. "You owe me a <em>drink</em>."
  ~ emit("give_item", "pass")
  -> hub
+ [Pull rank # requires: player.rank >= 3]
  The guard salutes.
  -> hub
+ [Call his bluff # requires: mood < 50, strength <= 12]
  He flinches. Just a little.
  -> hub
+ [Stand aside # requires: player.hp >= 5]
  You step back and let them argue.
  ~ emit2("reputation", "guard", 5)
  -> hub
+ [Show your papers # requires: player.is_injured == false, player.faction == rebels]
  He squints at them. <b>Forged</b>, obviously, but he waves you on.
  ~ emit("give_item", "pass")
  -> hub
+ [Leave] -> hub

// An emit between a line and its glue line still joins, and fires once. The red style stays open over the choice,
// so a save made here brings it back on load.
=== rumor ===
Word has it
~ emit("rumor_heard", "gate")
<> that the gate falls tonight.
A man leans in and <color=ff5555>speaks low.
+ [Listen] "Run," he says, <b>fast</b> and quiet.</color> Then he is gone.
  -> hub
