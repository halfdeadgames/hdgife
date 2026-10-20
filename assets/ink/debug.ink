// Validation and debug cases: requirements the engine reports as problems, formatter edge cases and an unsafe function.

=== validationGroup ===
~ speak("tester")
This group holds cases the engine reports as problems, and the console command validate lists them.
+ [Odd requirements] -> oddRequirements
+ [Formatter coverage] -> formatCoverage
+ [Unsafe function in choice text] -> unsafeLab
+ [Back to the hub] -> hub

=== oddRequirements ===
~ speak("")
This knot has no speaker, so a requirement without an entity names no entity.
+ [Order comparison on a string # requires: tester.textStat > 3] -> oddRequirements
+ [Boolean compared with a number # requires: tester.flagStat == 4] -> oddRequirements
+ [Boolean with an order comparison # requires: tester.flagStat > true] -> oddRequirements
+ [No entity and no speaker # requires: intStat > 1] -> oddRequirements
+ [Two requires tags, both met # requires: tester.intStat > 1 # requires: subject.intStat > 1] -> oddRequirements
+ [Back to the validation group] -> validationGroup

=== formatCoverage ===
~ speak("tester")
This line uses has_stat(), which gives {has_stat("intStat")} for intStat and {has_stat("missingStat")} for missingStat.
This line uses fmt(12.9, 0, "floor"), which gives {fmt(12.9, 0, "floor")}, and fmt(12.346, 2, "round"), which gives {fmt(12.346, 2, "round")}.
This line uses the alias ceiling, fmt(12.1, 0, "ceiling") gives {fmt(12.1, 0, "ceiling")}, and the alias truncate, fmt(-12.9, 0, "truncate") gives {fmt(-12.9, 0, "truncate")}.
This line uses an unknown mode, which the engine logs before it rounds: fmt(12.5, 0, "sideways") gives {fmt(12.5, 0, "sideways")}.
+ [Back to the validation group] -> validationGroup

=== unsafeLab ===
~ speak("tester")
This line is built before the failure, because the choice below calls an unsafe function in its text.
+ [Choice text with unsafeRoll(6), which gives {unsafeRoll(6)}] -> validationGroup
