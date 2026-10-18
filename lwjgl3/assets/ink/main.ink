// THE LANTERN PASS: a short showcase story for the hdgife harness. A storm, a toll lodge and three people who
// all want something. It uses every feature of the engine; the coverage matrix is in the README.
//
// Cast (stats live in the game, ink reads them with stat(), stat_of(), has_stat(), stat_text()):
//   varga  customs sergeant   sanity tiredness mood (0..100), trust (float), faction (string), is_injured (bool), stance (enum)
//   ilse   innkeeper          same set
//   tobin  lamplighter        same set, trust is a whole number here
//   player grit wits (int), charm (float), coin, hp (int), faction, is_injured
// Game side: roll(sides) and player_name() are bound by the harness. ~ emit(...) tells the game things; the harness
// acts on "spend" (takes coin) and "give_item" (inventory). It also watches suspicion, door_open and player_coin.
//
// Markers in text are written without '#' (that starts an ink tag): <color=ff8800>, <color=\#9ecbff> (the escape),
// <color=orange>, <em>, <b>, <shake amp=2 speed=0.5>, <wave=2,0.5>, point markers <pause=0.6/> <slam/> <sfx name=x/>.
// "Debug" scenes (the older test scenes) live in debug.ink: divert debug_menu.
INCLUDE hdgife.ink
INCLUDE hdgife_util.ink
INCLUDE debug.ink

EXTERNAL roll(sides)
EXTERNAL player_name()
EXTERNAL inventory_count(item)

VAR suspicion = 0
VAR door_open = false
VAR player_hp = 0
VAR player_coin = 0

-> arrival

// Fallbacks for when no game binds them.
=== function roll(sides) ===
~ return 1

=== function player_name() ===
~ return "Stranger"

=== function inventory_count(item) ===
~ return 0

// Combined metrics. avg3, blend, invert, scale100 and clamp come from hdgife.ink.
=== function strain() ===
~ return avg3(stat("tiredness"), invert(stat("mood")), invert(stat("sanity")))

=== function unease() ===
~ return blend(invert(stat("trust")), 2, invert(stat("mood")), 1)

=== function composure() ===
~ return clamp(scale100(stat("sanity"), 20, 90) - stat("tiredness") / 2.0, 0, 100)

=== arrival ===
~ speak("")
# sfx: thunder
# mood: ominous
The storm hits <b>The <em>Lantern</em> Pass</b> like a thrown door.
You are {player_name()}, and the toll lodge is the only light for ten miles. <pause=0.6/> A sign on the post reads: "Toll 3 coin. Blades <<a hand long may pass."
The lamp burns <color=ff8800>amber</color>, the snow is <color=\#9ecbff>pale blue</color> and your hands are <color=orange>raw</color>. # sfx: wind # sfx: door
~ emit("door_opened")
"Shut that door, you're letting the weather in!" # speaker: ilse # shout
Your pack holds {stat_text_of("player", "coin")} coin. Grit {stat_text_of("player", "grit")}, wits {stat_text_of("player", "wits")}, {stat_text_of("player", "hp")} hp.
-> lodge

=== lodge ===
~ speak("")
The common room smells of wet wool and lamp oil. {suspicion >= 3: Every eye in the room is on you.|{suspicion > 0: A few heads turn your way.}}
+ [Speak to Sergeant Varga] -> varga_talk
+ [Sit by Ilse's fire] -> ilse_talk
+ [Follow the lamplighter's muttering] -> tobin_talk
* [Read the toll ledger] -> ledger
* [Ask about the rumor] -> rumor
+ {door_open} [Slip out through the side door] -> escape
+ [Pay the toll and leave # requires: player.coin >= 3, varga.stance != HOSTILE] -> pay_and_leave

// Varga: combined metrics, sanity-scaled flavor, glue across a stat call, dice through a bound function, requirements.
=== varga_talk ===
~ speak("varga")
~ temp madness = invert(stat("sanity"))
"Papers." {strain() > 60: He sways on his feet, wrung out.|{strain() > 35: He looks worn but steady.|He stands crisp and alert.}} {stat("mood") < 30: He scowls.|{stat("mood") > 70: He nearly smiles.|He regards you flatly.}} {unease() > 80: "Don't make me regret this."} {chance(madness): {~He counts the rafters under his breath.|A muscle in his jaw jumps.|He laughs once, for no reason.}}
Strain {fmt(strain(), 1, "round")}, composure {fmt(composure(), 0, "floor")}, unease {fmt(unease(), 0, "round")}, trust {stat_text("trust")}.
He keeps his eyes on you,
{stat("mood") > 50: <>} patience at {stat_text("mood")} percent.
He serves the {stat("faction")}. Stance: {stat("stance")}. {stat("is_injured"): He favors his bad arm.|His hands are steady.} {has_stat("luck"): He rubs a lucky charm.|He owns no lucky charm.}
+ [Show your papers # requires: player.faction == drifter, faction == crown]
  He reads them twice. <pause=0.4/> "Drifter. Fine."
  -> varga_talk
+ [Bluff: "I know the Colonel." # requires: player.wits >= 7, trust > 20]
  ~ temp r = roll(20)
  ~ temp total = r + stat_of("player", "wits")
  You roll {r}, and your wits make it {total}. # dice: d20
  { total >= 14:
    "The Colonel, is it." He stamps something. <slam/>
    ~ door_open = true
    ~ emit1("give_item", "pass")
  - else:
    "I know the Colonel. You are not him."
    ~ suspicion += 2
    ~ emit2("suspicion_up", "varga", 2)
  }
  -> varga_talk
+ [Arm-wrestle him # requires: player.grit >= 8, is_injured == false]
  ~ temp pull = roll(20)
  ~ temp force = pull + stat_of("player", "grit")
  You roll {pull} on top of your grit, total {force}. # dice: d20
  { force >= 20:
    His wrist goes down with a bang. <slam/> "Again tomorrow," he mutters.
    ~ emit3("duel", "varga", force, true)
  - else:
    He pins you in a breath. "Not tonight."
    ~ suspicion += 1
    ~ emit3("duel", "varga", force, false)
  }
  -> varga_talk
+ [Rub a lucky charm # requires: player.luck >= 1]
  Nothing happens. You have no luck stat at all.
  -> varga_talk
+ {suspicion > 0} [Apologize]
  ~ suspicion = 0
  "Forget it," he says.
  -> varga_talk
* [Ask about the ice road]
  "Closed, mostly." He taps the wall map. "Dawn, maybe."
  -> varga_talk
+ [Back to the room] -> lodge

// Ilse: speaker tags, nested styles, a style left open across two lines, enum, bool and string requirements, events.
=== ilse_talk ===
~ speak("ilse")
Ilse pours something amber into a chipped cup. {stat("is_injured"): She winces and shifts her bandaged arm.} {stat("mood") > 60: "Sit, sit."|"What do you want?"} The fire {&crackles|spits|settles}. # mood: warm
"<b>Rule one: <em>never</em> ask what is in the crates.</b>" She says it {stat("tiredness") > 60: wearily|lightly}, and she means it.
She nods at Varga, who is at {stat_text_of("varga", "mood")} percent of a good mood. # speaker: narrator
<em>She lowers her voice, and her eyes keep going to the cellar door,
the way a dog watches a pot.</em> {chance(invert(stat("sanity"))): {~She hums off key.|She counts her keys twice.}}
+ [Buy a flask of rum for 3 coin # requires: player.coin >= 3]
  ~ emit2("spend", "coin", 3)
  ~ emit1("give_item", "rum")
  She slides a flask across. <slam/> "Not a word to Varga."
  -> ilse_talk
+ [Ask for the side door # requires: trust > 50, faction == guild]
  "Third stair from the back. It sticks."
  ~ door_open = true
  ~ emit2("flag", "side_door", true)
  -> ilse_talk
+ [Offer to dress her wound # requires: is_injured == true, player.wits >= 5]
  You work quickly. She hisses, then <color=gold>laughs</color>. "You have done this before."
  ~ emit1("give_item", "bandage")
  -> ilse_talk
+ [Say it <pause=0.4/>with <color=gold>charm</color> # requires: player.charm >= 4.5]
  "Madam, your rum is the only warm thing between here and the sea."
  -> ilse_talk
+ [Back to the room] -> lodge

// Tobin: low sanity, effects and point markers, a tag that is prose, styles left open over a choice.
=== tobin_talk ===
~ speak("tobin")
~ temp madness = invert(stat("sanity"))
The lamplighter hugs his pole. {chance(madness): {~He whispers to the flame.|He tallies something on his fingers.|He sings three notes and stops.}} {chance(madness / 2): {&He flinches at the thunder.|He flinches at nothing.}}
"The lamps <shake amp=2 speed=0.5>know</shake> who counts the coins." <sfx name=thunder vol=0.8/> # mood: manic
"Count them <wave=2,0.5>twice</wave>.<slam/> Then count them <bounce amp=3>again</bounce>." # Note to self: tobin counts in threes
Sanity {stat_text("sanity")}, composure {fmt(composure(), 0, "floor")}, he trusts you {stat_text("trust")}. {stat("stance")}.
"<color=\#9ecbff><shake amp=1>They watch from the glass, they do,
and the glass remembers
+ [Listen closer]
  everything you did.</shake></color>" He goes quiet.
  -> tobin_talk
+ [Ask what he saw # requires: tobin.trust >= 40]
  "Ask me tomorrow."</shake></color>
  -> tobin_talk
+ [Leave him to it]
  </shake></color>He does not notice you go.
  -> lodge

// Numbers: stat_text, stat_text_of and fmt in every mode, plus FLOOR, CEILING and INT on floats.
=== ledger ===
~ speak("")
~ temp t = stat_of("ilse", "trust")
~ temp debt = 0 - 2.5
The toll ledger is a column of figures in Ilse's slanted hand.
Her trust, one decimal: floor {fmt(t, 1, "floor")}, ceil {fmt(t, 1, "ceil")}, round {fmt(t, 1, "round")}, trunc {fmt(t, 1, "trunc")}.
A debt of -2.5, no decimals: floor {fmt(debt, 0, "floor")}, ceil {fmt(debt, 0, "ceil")}, round {fmt(debt, 0, "round")}, trunc {fmt(debt, 0, "trunc")}.
Plain ink on her trust: FLOOR {FLOOR(t)}, CEILING {CEILING(t)}, INT {INT(t)}.
Game formats: Ilse {stat_text_of("ilse", "trust")}, Varga {stat_text_of("varga", "trust")}, Tobin {stat_text_of("tobin", "trust")}, your charm {stat_text_of("player", "charm")}, your faction {stat_text_of("player", "faction")}, injured {stat_text_of("ilse", "is_injured")}.
Average of all three moods: {fmt(avg3(stat_of("varga", "mood"), stat_of("ilse", "mood"), stat_of("tobin", "mood")), 2, "round")}.
Stat check: Wren {has_stat_of("player", "charm"): has charm|has no charm}, Ilse {has_stat_of("ilse", "luck"): has luck|has no luck}.
-> lodge

// A glue line after an emit: the event fires once, the lines join.
=== rumor ===
~ speak("ilse")
Word has it
~ emit1("rumor_heard", "pass")
<> that the pass closes at dawn.
-> lodge

=== escape ===
~ speak("")
You ease the side door open and the storm swallows you. <pause=1.0/> Behind you the lamps gutter, one by one.
~ emit1("left_pass", "side_door")
-> END

=== pay_and_leave ===
~ speak("varga")
~ emit2("spend", "coin", 3)
"Three coin." He stamps your papers. {suspicion > 2: "And I will remember your face."|"Mind the ice."}
~ emit1("left_pass", "front_door")
-> END
