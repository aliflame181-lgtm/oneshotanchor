# OneShotAnchor (Fabric, Minecraft 1.21.11, Java 21)

Client-side only. Needs Fabric API.
- H  = toggle AutoAnchor ON/OFF (saved across restarts)
- 3  = run one anchor sequence (only while ON)
Both keys are rebindable in Controls. Config: .minecraft/config/oneshotanchor.json

## Get the .jar (pick one)
A) No setup: make a free GitHub repo, upload this folder's contents, open the Actions tab,
   run "Build mod jar", download the artifact "oneshotanchor-jar" -> put the jar in .minecraft/mods.
B) Locally: install JDK 21 + Gradle 9.2+, then in this folder run `gradle build`.
   The jar is build/libs/oneshotanchor-1.0.0.jar (not the -sources one).
