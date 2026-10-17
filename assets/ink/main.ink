VAR player_hp = 0

Welcome to the test story. #intro
Player HP is {player_hp}.

-> hub

=== hub ===
What now?
+ [Look around] You see a quiet room. #scene:room
  -> hub
+ [Check my health] -> status
* [Leave] -> farewell

=== status ===
You have {player_hp} hp.
{player_hp <= 0: You feel awful.|You feel fine.}
-> hub

=== farewell ===
You walk away. #end
-> END
