// Demo scene for the stat functions. Speaker is "guard" (set with StoryManager.beginScene or `talk`).
// Stats are 0..100 except strength. Nothing here is built into the engine, these names are just this story's.

// one combined metric: tiredness plus low mood plus low sanity
=== function strain() ===
~ return avg3(stat("tiredness"), invert(stat("mood")), invert(stat("sanity")))

=== guard_chat ===
~ temp madness = invert(stat("sanity"))
"Halt, traveler." {strain() > 66: He sways on his feet, completely wrung out.|{strain() > 33: He looks worn but steady.|He stands crisp and alert.}} {stat("mood") < 30: He scowls at you.|{stat("mood") > 70: He grins at you.|He regards you flatly.}} {stat("strength") >= 15: His grip on the spear looks iron-hard.|{stat("strength") >= 8: He holds his spear like a man who has used it.|The spear seems too heavy for him.}} {chance(madness): {~He mutters to someone who isn't there.|His eye twitches.|He laughs, briefly and for no reason.}}
Strength {fmt(stat("strength"), 0, "floor")}, strain {fmt(strain(), 1, "round")}, mood {stat_text("mood")}.
{has_stat("luck"): He rubs a lucky charm.}
+ [Ask about the road] "Watch your step out there."
  -> guard_chat
+ {stat("strength") >= 10} [Arm-wrestle him] You win, barely.
  -> guard_chat
+ [Leave] -> hub
