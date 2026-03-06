package com.decli.chinesechess.game

import android.content.Context

private const val PREFS_NAME = "chinese_chess_game"
private const val KEY_STATE = "saved_state"

data class SavedGame(
    val moves: List<Move>,
    val difficulty: Difficulty,
    val soundEnabled: Boolean,
    val ttsEnabled: Boolean,
    val notificationsEnabled: Boolean,
)

class GameStorage(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(savedGame: SavedGame) {
        val movePart = savedGame.moves.joinToString("|") { move ->
            listOf(move.from, move.to, move.movedPiece, move.capturedPiece).joinToString(",")
        }
        val serialized = buildString {
            append("v1")
            append(';')
            append(savedGame.difficulty.name)
            append(';')
            append(if (savedGame.soundEnabled) '1' else '0')
            append(';')
            append(if (savedGame.ttsEnabled) '1' else '0')
            append(';')
            append(if (savedGame.notificationsEnabled) '1' else '0')
            append(';')
            append(movePart)
        }
        prefs.edit().putString(KEY_STATE, serialized).apply()
    }

    fun load(): SavedGame? {
        val serialized = prefs.getString(KEY_STATE, null) ?: return null
        val parts = serialized.split(';')
        if (parts.size < 6 || parts[0] != "v1") {
            return null
        }

        val difficulty = Difficulty.entries.firstOrNull { it.name == parts[1] } ?: Difficulty.MEDIUM
        val soundEnabled = parts[2] == "1"
        val ttsEnabled = parts[3] == "1"
        val notificationsEnabled = parts[4] == "1"
        val moves = if (parts[5].isBlank()) {
            emptyList()
        } else {
            parts[5].split('|').mapNotNull { token ->
                val fields = token.split(',')
                if (fields.size != 4) {
                    null
                } else {
                    Move(
                        from = fields[0].toInt(),
                        to = fields[1].toInt(),
                        movedPiece = fields[2].toInt(),
                        capturedPiece = fields[3].toInt(),
                    )
                }
            }
        }
        return SavedGame(
            moves = moves,
            difficulty = difficulty,
            soundEnabled = soundEnabled,
            ttsEnabled = ttsEnabled,
            notificationsEnabled = notificationsEnabled,
        )
    }
}
