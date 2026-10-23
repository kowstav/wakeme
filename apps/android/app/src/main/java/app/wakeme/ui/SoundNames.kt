package app.wakeme.ui

import app.wakeme.designsystem.WMFace

/**
 * A call a friend can send as a quack: its sound, the word it makes and the face that shows it.
 * Same ids and order as iOS (`AnimalSounds`) and `ANIMAL_SOUNDS` in packages/policy; the duck's
 * quack is first and is the default.
 */
data class AnimalSound(val id: String, val name: String, val face: WMFace)

object AnimalSounds {
    val all = listOf(
        AnimalSound("bundled:quack.classic", "Quack", WMFace.Duck),
        AnimalSound("bundled:frog.ribbit", "Ribbit", WMFace.Frog),
        AnimalSound("bundled:dog.woof", "Woof", WMFace.Dog),
        AnimalSound("bundled:cat.meow", "Meow", WMFace.Cat),
        AnimalSound("bundled:cow.moo", "Moo", WMFace.Cow),
        AnimalSound("bundled:monkey.hoot", "Ooh-ooh", WMFace.Monkey),
        AnimalSound("bundled:donkey.heehaw", "Hee-haw", WMFace.Donkey),
        AnimalSound("bundled:pig.oink", "Oink", WMFace.Pig),
        AnimalSound("bundled:sheep.baa", "Baa", WMFace.Sheep),
        AnimalSound("bundled:owl.hoot", "Hoot", WMFace.Owl),
        AnimalSound("bundled:rooster.crow", "Cock-a-doodle", WMFace.Rooster),
    )
    val ids = all.map { it.id }
    const val DEFAULT_ID = "bundled:quack.classic"

    fun sound(id: String?): AnimalSound? = all.firstOrNull { it.id == id }
}

/** What people see for a bundled sound id (same names as iOS `soundName`). */
fun soundName(id: String): String = when (id) {
    "bundled:quack.double" -> "Double quack"
    "bundled:chime.soft" -> "Soft chime"
    "bundled:bell.bright" -> "Bright bell"
    "bundled:rise.gentle" -> "Gentle rise"
    "bundled:pulse.wake" -> "Wake pulse"
    else -> AnimalSounds.sound(id)?.name
        ?: if (id.startsWith("media:")) "Voice recording ${id.removePrefix("media:").take(6)}" else id.removePrefix("bundled:").replace('.', ' ')
}
