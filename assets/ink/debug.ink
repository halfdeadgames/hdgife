// Cases the engine reports as problems: bad requirements, formatter edge cases, an unsafe function.

=== validationGroup ===
~ speak("tester")
Cases the engine reports as problems. validate in the console lists them.
+ [Odd requirements] -> oddRequirements
+ [Formatter coverage] -> formatCoverage
+ [Unsafe function in choice text] -> unsafeLab
+ [Back to the hub] -> hub

=== oddRequirements ===
~ speak("")
No speaker here, so an entity-less requirement names no entity.
+ [Order comparison on a string # requires: tester.textStat > 3] -> oddRequirements
+ [Boolean compared with a number # requires: tester.flagStat == 4] -> oddRequirements
+ [Boolean with an order comparison # requires: tester.flagStat > true] -> oddRequirements
+ [No entity and no speaker # requires: intStat > 1] -> oddRequirements
+ [Two requires tags, both met # requires: tester.intStat > 1 # requires: subject.intStat > 1] -> oddRequirements
+ [Back to the validation group] -> validationGroup

=== formatCoverage ===
~ speak("tester")
has_stat() gives {has_stat("intStat")} for intStat and {has_stat("missingStat")} for missingStat.
fmt(12.9, 0, "floor") gives {fmt(12.9, 0, "floor")}, fmt(12.346, 2, "round") gives {fmt(12.346, 2, "round")}.
Aliases: fmt(12.1, 0, "ceiling") gives {fmt(12.1, 0, "ceiling")}, fmt(-12.9, 0, "truncate") gives {fmt(-12.9, 0, "truncate")}.
Unknown mode, logged by the engine before it rounds: fmt(12.5, 0, "sideways") gives {fmt(12.5, 0, "sideways")}.
+ [Back to the validation group] -> validationGroup

=== unsafeLab ===
~ speak("tester")
Built before the failure: the choice below calls an unsafe function in its text.
+ [Choice text with unsafeRoll(6), which gives {unsafeRoll(6)}] -> validationGroup
