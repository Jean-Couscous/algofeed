package algofeed

import algofeed.rank.Weights
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { System, Light, Dark }

@Serializable
data class Settings(
    val theme: ThemeMode = ThemeMode.System,
    /** Material You colors from the wallpaper, where the platform has them. */
    val dynamicColor: Boolean = false,
    val weights: Weights = Weights(),
    val refreshMinutes: Int = 30,
    /** Background refresh (Android): wait for an unmetered network, or for the charger. */
    val refreshUnmeteredOnly: Boolean = false,
    val refreshWhileChargingOnly: Boolean = false,
    val retentionDays: Int = 30,
    /** Show entries already scrolled past in the home stream. */
    val includeSeen: Boolean = false,
    /** Order Home newest-first instead of by the learned ranking (which keeps learning either way). */
    val chronologicalHome: Boolean = true,
)
