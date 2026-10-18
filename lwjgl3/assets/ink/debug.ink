// Debug scenes. The older test scenes, kept so their coverage stays: a guard and a merchant (the "guard" and
// "merchant" entities of the harness), glue across a stat call, two speakers, style spans, effects and markers,
// requirement choices (greater/less, string, bool, speaker default), events, a style left open over a choice,
// and requirements that are mismatched, unreadable, missing or have no entity. Reach them with: divert debug_menu
// Nothing here is built into the engine, these names are just this story's.
// player_hp is synced from the harness. strain() is declared in main.ink.

=== debug_menu ===
~ speak("")
Debug scenes.
+ [Guard chat] -> guard_chat
+ [Gate exchange] -> gate_exchange
+ [Open rumor] -> rumor_open
+ [Odd requirements] -> debug_requirements
+ [Health] -> debug_status
+ [Coverage lab] -> coverage_lab
+ [Unsafe function in choice text] -> unsafe_lab
* [Leave the debug menu] -> lodge

=== debug_status ===
You have {player_hp} hp.
{player_hp <= 0: You feel awful.|You feel fine.}
-> debug_menu

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
  -> debug_menu
+ [Leave] -> debug_menu

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
  -> debug_menu
+ [Pull rank # requires: player.rank >= 3]
  The guard salutes.
  -> debug_menu
+ [Call his bluff # requires: mood < 50, strength <= 12]
  He flinches. Just a little.
  -> debug_menu
+ [Stand aside # requires: player.hp >= 15]
  You step back and let them argue.
  ~ emit2("reputation", "guard", 5)
  -> debug_menu
+ [Show your papers # requires: player.is_injured == false, player.faction == rebels]
  He squints at them. <b>Forged</b>, obviously, but he waves you on.
  ~ emit("give_item", "pass")
  -> debug_menu
+ [Leave] -> debug_menu

// An emit between a line and its glue line still joins, and fires once. The red style stays open over the choice,
// so a save made here brings it back on load.
=== rumor_open ===
Word has it
~ emit("rumor_heard", "gate")
<> that the gate falls tonight.
A man leans in and <color=ff5555>speaks low.
+ [Listen] "Run," he says, <b>fast</b> and quiet.</color> Then he is gone.
  -> debug_menu

// Requirements the engine reports as problems: a value that does not fit the stat's type (mismatch), a clause
// it cannot read (invalid), a missing stat, and no entity with no speaker.
=== debug_requirements ===
~ speak("")
Odd requirements. The raw panel shows what the engine made of each one.
+ [Number against a word # requires: guard.strength >= high]
  -> debug_requirements
+ [Order on a string # requires: guard.faction > 3]
  -> debug_requirements
+ [Boolean against a number # requires: player.is_injured == 4]
  -> debug_requirements
+ [Unreadable # requires: this is not a clause]
  -> debug_requirements
+ [Missing stat # requires: guard.luck > 0]
  -> debug_requirements
+ [No entity, no speaker # requires: mood > 1]
  -> debug_requirements
+ [Two tags in one choice # requires: guard.mood > 10 # requires: guard.sanity > 10]
  -> debug_requirements
+ [Back] -> debug_menu

// Small engine functions the showcase skipped: has_stat true, floor_text, round_text, the ceiling and truncate
// aliases of fmt, and an fmt mode that does not exist (the engine logs it and rounds).
=== coverage_lab ===
~ speak("guard")
Has mood {has_stat("mood")}, has luck {has_stat("luck")}.
Floor {floor_text(12.9)}, round {round_text(12.346, 2)}.
Ceiling {fmt(12.1, 0, "ceiling")}, truncate {fmt(-12.9, 0, "truncate")}.
Sideways {fmt(12.5, 0, "sideways")}.
+ [Back] -> debug_menu

// roll() is bound by the harness without lookaheadSafe, because it rolls dice. Ink evaluates choice text ahead of
// time, so this step fails. The engine throws StoryStepException with the line built before it.
=== unsafe_lab ===
~ speak("")
This line is built before the failure.
+ [Take the roll of {roll(6)}] -> debug_menu
