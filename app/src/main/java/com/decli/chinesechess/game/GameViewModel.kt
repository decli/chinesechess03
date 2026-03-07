package com.decli.chinesechess.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.decli.chinesechess.R
import com.decli.chinesechess.debug.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

data class GameUiState(
    val position: Position = XiangqiStartPosition.create(),
    val selectedSquare: Int? = null,
    val legalTargets: Set<Int> = emptySet(),
    val hintMove: Move? = null,
    val aiThinking: Boolean = false,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val winner: Winner? = null,
    val banner: String = "中级电脑已就位，请先手。",
    val soundEnabled: Boolean = true,
    val ttsEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
) {
    val historyDepth: Int = position.plyCount
    val canUndo: Boolean get() = historyDepth > 0
}

sealed interface GameEvent {
    data class PlayMoveSound(val side: Side, val capture: Boolean) : GameEvent
    data class Speak(val text: String, val clips: List<RobotClip> = emptyList()) : GameEvent
    data class Notify(val title: String, val text: String) : GameEvent
    data object ExportDebugLog : GameEvent
}

enum class RobotClip {
    HORSE,
    ELEPHANT,
    ROOK,
    CANNON,
    PAWN,
    GUARD,
    GENERAL,
    CHECK,
    CAPTURE,
    EAT,
    RED_WIN,
    BLACK_WIN,
    DRAW,
    TAUNT,
    STEADY,
    DEEP,
    GAIN,
    CALM,
}

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = XiangqiEngine
    private val ai = XiangqiAi(engine)
    private val rng = Random(20260306)
    private val storage = GameStorage(application.applicationContext)
    private val appName = application.getString(R.string.app_name)
    private val history = mutableListOf<Position>()
    private var aiJob: Job? = null
    private val restoredGame = restoreSavedGame()

    private val _uiState = MutableStateFlow(
        GameUiState(
            position = history.last(),
            difficulty = restoredGame?.difficulty ?: Difficulty.MEDIUM,
            banner = if ((restoredGame?.moves?.isNotEmpty() == true)) "已恢复上次对局。请继续。" else "中级电脑已就位，请先手。",
            soundEnabled = restoredGame?.soundEnabled ?: true,
            ttsEnabled = restoredGame?.ttsEnabled ?: true,
            notificationsEnabled = restoredGame?.notificationsEnabled ?: true,
        ),
    )
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    init {
        DebugLogger.log("VM", "init restored_moves=${history.size - 1}")
        if (history.last().sideToMove == Side.BLACK && _uiState.value.winner == null) {
            scheduleAiTurn()
        } else {
            persistGame()
        }
    }

    fun onSquareTapped(square: Int) {
        val state = _uiState.value
        val winner = state.winner
        if (winner != null || state.aiThinking || state.position.sideToMove != Side.RED) {
            return
        }

        val piece = state.position.board[square]
        val selectedSquare = state.selectedSquare
        if (selectedSquare != null) {
            val move = engine.legalMovesForSquare(state.position, selectedSquare).firstOrNull { it.to == square }
            if (move != null) {
                DebugLogger.log("MOVE", "player ${formatMove(move)} capture=${move.isCapture}")
                commitMove(move, side = Side.RED, banner = "你走 ${formatMove(move)}。")
                scheduleAiTurn()
                return
            }
        }

        if (PieceCodec.sideOf(piece) == Side.RED) {
            val legalMoves = engine.legalMovesForSquare(state.position, square)
            _uiState.update {
                it.copy(
                    selectedSquare = square,
                    legalTargets = legalMoves.mapTo(mutableSetOf()) { move -> move.to },
                    hintMove = null,
                )
            }
        } else {
            clearSelection()
        }
    }

    fun undoLastTurn() {
        aiJob?.cancel()
        if (history.size <= 1) {
            return
        }
        var removeCount = if (history.size >= 3) 2 else 1
        while (removeCount > 0 && history.size > 1) {
            history.removeAt(history.lastIndex)
            removeCount--
        }
        val restored = history.last()
        _uiState.value = GameUiState(
            position = restored,
            difficulty = _uiState.value.difficulty,
            banner = "已悔棋，轮到你。".takeUnless { restored.sideToMove == Side.BLACK } ?: "已悔棋，电脑重新思考。",
            soundEnabled = _uiState.value.soundEnabled,
            ttsEnabled = _uiState.value.ttsEnabled,
            notificationsEnabled = _uiState.value.notificationsEnabled,
        )
        persistGame()
        if (restored.sideToMove == Side.BLACK) {
            scheduleAiTurn()
        }
    }

    fun restartGame() {
        aiJob?.cancel()
        history.clear()
        history += XiangqiStartPosition.create()
        _uiState.value = GameUiState(
            position = history.last(),
            difficulty = _uiState.value.difficulty,
            banner = "${_uiState.value.difficulty.title}电脑已重新布阵，请先手。",
            soundEnabled = _uiState.value.soundEnabled,
            ttsEnabled = _uiState.value.ttsEnabled,
            notificationsEnabled = _uiState.value.notificationsEnabled,
        )
        persistGame()
    }

    fun requestHint() {
        val state = _uiState.value
        if (state.aiThinking || state.winner != null || state.position.sideToMove != Side.RED) {
            return
        }
        _uiState.update { it.copy(aiThinking = true, banner = "正在为你推演提示着法……", selectedSquare = null, legalTargets = emptySet()) }
        viewModelScope.launch(Dispatchers.Default) {
            val result = ai.findBestMove(state.position, state.difficulty, history.toList())
            val hintMove = result.move
            _uiState.update {
                it.copy(
                    aiThinking = false,
                    hintMove = hintMove,
                    banner = hintMove?.let { move -> "提示：试试 ${formatMove(move)}。" } ?: "当前没有可用提示。",
                )
            }
        }
    }

    fun setDifficulty(difficulty: Difficulty) {
        _uiState.update {
            it.copy(
                difficulty = difficulty,
                banner = "难度已切换到${difficulty.title}。",
                hintMove = null,
            )
        }
        persistGame()
    }

    fun toggleSound() {
        _uiState.update { it.copy(soundEnabled = !it.soundEnabled) }
        DebugLogger.log("SETTINGS", "sound=${_uiState.value.soundEnabled}")
        persistGame()
    }

    fun toggleTts() {
        _uiState.update { it.copy(ttsEnabled = !it.ttsEnabled) }
        DebugLogger.log("SETTINGS", "tts=${_uiState.value.ttsEnabled}")
        persistGame()
    }

    fun toggleNotifications() {
        _uiState.update { it.copy(notificationsEnabled = !it.notificationsEnabled) }
        DebugLogger.log("SETTINGS", "notifications=${_uiState.value.notificationsEnabled}")
        persistGame()
    }

    fun exportDebugLog() {
        DebugLogger.log("EXPORT", "requested_by_user")
        _events.tryEmit(GameEvent.ExportDebugLog)
    }

    private fun scheduleAiTurn() {
        val state = _uiState.value
        if (state.winner != null || state.position.sideToMove != Side.BLACK) {
            return
        }

        aiJob?.cancel()
        _uiState.update {
            it.copy(
                aiThinking = true,
                selectedSquare = null,
                legalTargets = emptySet(),
                hintMove = null,
                banner = "电脑正在思考，请稍候……",
            )
        }
        DebugLogger.log("AI", "thinking difficulty=${_uiState.value.difficulty.name} moves=${history.size - 1}")
        aiJob = viewModelScope.launch(Dispatchers.Default) {
            val position = _uiState.value.position
            val result = ai.findBestMove(position, _uiState.value.difficulty, history.toList())
            val move = result.move
            if (move == null) {
                val winner = engine.resolveWinner(position)
                _uiState.update { current ->
                    current.copy(
                        aiThinking = false,
                        winner = winner,
                        banner = winner?.title ?: "本局结束。",
                    )
                }
                return@launch
            }

            val nextPosition = engine.applyMove(position, move)
            val projectedWinner =
                if (repetitionCount(history + nextPosition, nextPosition) >= 3) {
                    Winner.DRAW
                } else {
                    engine.resolveWinner(nextPosition)
                }
            val commentary = robotLine(move, nextPosition, result.depthReached)
            val line = commentary.text
            DebugLogger.log(
                "AI",
                "move=${formatMove(move)} depth=${result.depthReached} nodes=${result.nodes} clips=${commentary.clips.joinToString(",")}",
            )
            commitMove(move, side = Side.BLACK, banner = line)
            if (projectedWinner == null) {
                _events.tryEmit(GameEvent.Speak(line, clips = commentary.clips))
                _events.tryEmit(GameEvent.Notify(appName, line))
            }
        }
    }

    private fun commitMove(move: Move, side: Side, banner: String) {
        val current = history.last()
        val next = engine.applyMove(current, move)
        history += next
        val repeated = repetitionCount(history, next) >= 3
        val winner = if (repeated) Winner.DRAW else engine.resolveWinner(next)
        val resolvedBanner = winnerAnnouncement(winner, repeated) ?: banner
        _uiState.update {
            it.copy(
                position = next,
                selectedSquare = null,
                legalTargets = emptySet(),
                hintMove = null,
                aiThinking = false,
                winner = winner,
                banner = if (winner == null) resolvedBanner else "${winner.title}。${resolvedBanner.takeIf { message -> message.isNotBlank() } ?: ""}".trim(),
            )
        }
        DebugLogger.log("STATE", "commit side=${side.name} capture=${move.isCapture} repeated=$repeated winner=${winner?.name ?: "none"}")
        _events.tryEmit(GameEvent.PlayMoveSound(side = side, capture = move.isCapture))
        if (winner != null) {
            _events.tryEmit(GameEvent.Speak(resolvedBanner, winnerAnnouncementClips(winner, repeated)))
            _events.tryEmit(GameEvent.Notify(appName, resolvedBanner))
        }
        persistGame()
    }

    private fun clearSelection() {
        _uiState.update { it.copy(selectedSquare = null, legalTargets = emptySet()) }
    }

    private fun robotLine(move: Move, nextPosition: Position, depth: Int): RobotCommentary {
        val clips = mutableListOf<RobotClip>()
        val actionClip = when (PieceCodec.typeOf(move.movedPiece)) {
            PieceType.HORSE -> RobotClip.HORSE
            PieceType.ELEPHANT -> RobotClip.ELEPHANT
            PieceType.ROOK -> RobotClip.ROOK
            PieceType.CANNON -> RobotClip.CANNON
            PieceType.PAWN -> RobotClip.PAWN
            PieceType.ADVISOR -> RobotClip.GUARD
            PieceType.GENERAL -> RobotClip.GENERAL
            null -> RobotClip.TAUNT
        }
        clips += actionClip

        val actionText = when (actionClip) {
            RobotClip.HORSE -> listOf("我跳马。", "马先起。").random(rng)
            RobotClip.ELEPHANT -> listOf("我飞象。", "象先飞。").random(rng)
            RobotClip.ROOK -> listOf("我出车。", "车来了。").random(rng)
            RobotClip.CANNON -> listOf("我架炮。", "炮位摆好。").random(rng)
            RobotClip.PAWN -> listOf("我拱兵。", "兵先过来。").random(rng)
            RobotClip.GUARD -> listOf("我补士。", "士先补上。").random(rng)
            RobotClip.GENERAL -> listOf("我挪帅。", "帅换个位。").random(rng)
            else -> "这步我先走了。"
        }

        val check = engine.isInCheck(nextPosition.board, nextPosition.sideToMove)
        val cannonCapture = PieceCodec.typeOf(move.movedPiece) == PieceType.CANNON
        val captureVerb = if (cannonCapture) "我打你" else "我吃你"
        val outcomeText = when {
            check && move.isCapture -> {
                clips += if (cannonCapture) RobotClip.CAPTURE else RobotClip.EAT
                clips += RobotClip.CHECK
                "$captureVerb，再将军。"
            }
            check -> {
                clips += RobotClip.CHECK
                "将军。"
            }
            move.isCapture -> {
                val clip = if (cannonCapture) {
                    listOf(RobotClip.CAPTURE, RobotClip.GAIN).random(rng)
                } else {
                    RobotClip.EAT
                }
                clips += clip
                if (clip == RobotClip.CAPTURE) {
                    "$captureVerb。"
                } else if (clip == RobotClip.GAIN) {
                    "这步赚到了。"
                } else {
                    "$captureVerb。"
                }
            }
            else -> ""
        }

        return RobotCommentary(
            text = actionText + outcomeText,
            clips = clips.distinct(),
        )
    }

    private fun formatMove(move: Move): String {
        val piece = PieceCodec.displayLabel(move.movedPiece)
        return "$piece${squareLabel(move.from)}到${squareLabel(move.to)}"
    }

    private fun squareLabel(square: Int): String {
        val file = fileOf(square) + 1
        val rank = BOARD_RANKS - rankOf(square)
        return "${file}路${rank}线"
    }

    private fun winnerAnnouncement(winner: Winner?, repeated: Boolean = false): String? =
        when {
            repeated -> "局面三次重复，和棋。请重新开局。"
            winner == Winner.RED -> "红方获胜。请重新开局。"
            winner == Winner.BLACK -> "黑方获胜。请重新开局。"
            winner == Winner.DRAW -> "和棋。请重新开局。"
            else -> null
        }

    private fun winnerAnnouncementClips(winner: Winner?, repeated: Boolean = false): List<RobotClip> =
        when {
            repeated -> listOf(RobotClip.DRAW)
            winner == Winner.RED -> listOf(RobotClip.RED_WIN)
            winner == Winner.BLACK -> listOf(RobotClip.BLACK_WIN)
            winner == Winner.DRAW -> listOf(RobotClip.DRAW)
            else -> emptyList()
        }

    override fun onCleared() {
        aiJob?.cancel()
        super.onCleared()
    }

    private fun restoreSavedGame(): SavedGame? {
        val savedGame = storage.load()
        if (savedGame == null) {
            history += XiangqiStartPosition.create()
            DebugLogger.log("SAVE", "no_saved_game")
            return null
        }

        history.clear()
        var position = XiangqiStartPosition.create()
        history += position
        savedGame.moves.forEach { move ->
            position = engine.applyMove(position, move)
            history += position
        }
        DebugLogger.log("SAVE", "restored moves=${savedGame.moves.size} difficulty=${savedGame.difficulty.name}")
        return savedGame
    }

    private fun persistGame() {
        val state = _uiState.value
        storage.save(
            SavedGame(
                moves = history.drop(1).mapNotNull { it.lastMove },
                difficulty = state.difficulty,
                soundEnabled = state.soundEnabled,
                ttsEnabled = state.ttsEnabled,
                notificationsEnabled = state.notificationsEnabled,
            ),
        )
        DebugLogger.log("SAVE", "persisted moves=${history.size - 1}")
    }

    private data class RobotCommentary(
        val text: String,
        val clips: List<RobotClip>,
    )
}
