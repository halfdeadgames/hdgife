// Demo scenes for the stat functions, speakers, styles and choice requirements.
// Stats are 0..100 except strength. Nothing here is built into the engine, these names are just this story's.
//
// Markers in text: <em>..</em>, <color=red>..</color>, <shake>..</shake>. The harness colors em and color and
// ignores shake. Choice requirements go INSIDE the brackets: [Text # requires: strength >= 12].

// one combined metric: tiredness plus low mood plus low sanity
=== function strain() ===
~ return avg3(stat("tiredness"), invert(stat("mood")), invert(stat("sanity")))

=== guard_chat ===
~ speak("guard")
~ temp madness = invert(stat("sanity"))
"Halt, traveler." {strain() > 66: He sways on his feet, completely wrung out.|{strain() > 33: He looks worn but steady.|He stands crisp and alert.}} {stat("mood") < 30: He scowls at you.|{stat("mood") > 70: He grins at you.|He regards you flatly.}} {stat("strength") >= 15: His grip on the spear looks iron-hard.|{stat("strength") >= 8: He holds his spear like a man who has used it.|The spear seems too heavy for him.}} {chance(madness): {~He mutters to someone who isn't there.|His eye twitches.|He laughs, briefly and for no reason.}}
Strength {fmt(stat("strength"), 0, "floor")}, strain {fmt(strain(), 1, "round")}, mood {stat_text("mood")}.
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
+ [Leave] -> hub

// Two speakers. The first line names itself with a tag (that line only), the others use speak().
=== gate_exchange ===
~ speak("guard")
"Halt. State your business." <em>He does not lower the spear.</em>
~ speak("merchant")
"He's with me, officer." Her smile is <color=gold>very</color> practiced. {stat("mood") > 50: She sounds calm.|She sounds rattled.}
A voice from the queue: "Hurry up!" # speaker: crowd
~ speak("guard")
"<color=red>Quiet!</color> Strength {stat_text("strength")}, mood {stat_text("mood")}." He means it, and it <shake>shows</shake>.
+ [Back the merchant up # requires: merchant.mood >= 50]
  ~ speak("merchant")
  "Told you," she says. "You owe me a <em>drink</em>."
  -> hub
+ [Pull rank # requires: player.rank >= 3]
  The guard salutes.
  -> hub
+ [Call his bluff # requires: mood < 50, strength <= 12]
  He flinches. Just a little.
  -> hub
+ [Stand aside # requires: player.hp >= 5]
  You step back and let them argue.
  -> hub
+ [Leave] -> hub
