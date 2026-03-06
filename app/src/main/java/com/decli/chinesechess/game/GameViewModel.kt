package com.decli.chinesechess.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    data class Speak(val text: String) : GameEvent
    data class Notify(val title: String, val text: String) : GameEvent
}

class GameViewModel : ViewModel() {
    private val engine = XiangqiEngine
    private val ai = XiangqiAi(engine)
    private val rng = Random(20260306)
    private val history = mutableListOf(XiangqiStartPosition.create())
    private var aiJob: Job? = null

    private val _uiState = MutableStateFlow(GameUiState(position = history.last()))
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

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
    }

    fun requestHint() {
        val state = _uiState.value
        if (state.aiThinking || state.winner != null || state.position.sideToMove != Side.RED) {
            return
        }
        _uiState.update { it.copy(aiThinking = true, banner = "正在为你推演提示着法……", selectedSquare = null, legalTargets = emptySet()) }
        viewModelScope.launch(Dispatchers.Default) {
            val result = ai.findBestMove(state.position, state.difficulty)
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
    }

    fun toggleSound() {
        _uiState.update { it.copy(soundEnabled = !it.soundEnabled) }
    }

    fun toggleTts() {
        _uiState.update { it.copy(ttsEnabled = !it.ttsEnabled) }
    }

    fun toggleNotifications() {
        _uiState.update { it.copy(notificationsEnabled = !it.notificationsEnabled) }
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

        aiJob = viewModelScope.launch(Dispatchers.Default) {
            val position = _uiState.value.position
            val result = ai.findBestMove(position, _uiState.value.difficulty)
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

            val line = robotLine(move, result.depthReached)
            commitMove(move, side = Side.BLACK, banner = line)
            _events.tryEmit(GameEvent.Speak(line))
            _events.tryEmit(GameEvent.Notify("象棋乐斗", line))
        }
    }

    private fun commitMove(move: Move, side: Side, banner: String) {
        val current = history.last()
        val next = engine.applyMove(current, move)
        history += next
        val winner = engine.resolveWinner(next)
        _uiState.update {
            it.copy(
                position = next,
                selectedSquare = null,
                legalTargets = emptySet(),
                hintMove = null,
                aiThinking = false,
                winner = winner,
                banner = if (winner == null) banner else "${winner.title}。${banner.takeIf { message -> message.isNotBlank() } ?: ""}".trim(),
            )
        }
        _events.tryEmit(GameEvent.PlayMoveSound(side = side, capture = move.isCapture))
    }

    private fun clearSelection() {
        _uiState.update { it.copy(selectedSquare = null, legalTargets = emptySet()) }
    }

    private fun robotLine(move: Move, depth: Int): String {
        val capturePart = if (move.isCapture) {
            listOf("这一口吃得很稳。", "这步顺手收子。", "这枚棋子我先借走了。").random(rng)
        } else {
            listOf("先给你垫个节奏。", "这步下得有点老辣。", "我先把局面收紧一点。").random(rng)
        }
        val depthPart = when {
            depth >= 6 -> "我已经往后多看了几层。"
            depth >= 4 -> "这步是认真算过的。"
            else -> "先走稳当些。"
        }
        return "电脑走 ${formatMove(move)}。$capturePart$depthPart"
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

    override fun onCleared() {
        aiJob?.cancel()
        super.onCleared()
    }
}
