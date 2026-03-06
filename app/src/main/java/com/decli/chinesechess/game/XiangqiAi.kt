package com.decli.chinesechess.game

import kotlin.math.abs
import kotlin.random.Random

class XiangqiAi(
    private val engine: XiangqiEngine = XiangqiEngine,
) {
    private val zobrist = Array(BOARD_SIZE) { LongArray(15) }
    private val sideKey: Long
    private val transposition = HashMap<Long, TranspositionEntry>(200_000)
    private val killerMoves = Array(MAX_PLY) { arrayOfNulls<Move>(2) }
    private val historyHeuristic = IntArray(BOARD_SIZE * BOARD_SIZE)

    init {
        val random = Random(20260306)
        for (square in 0 until BOARD_SIZE) {
            for (index in 0 until 15) {
                zobrist[square][index] = random.nextLong()
            }
        }
        sideKey = random.nextLong()
    }

    fun findBestMove(position: Position, difficulty: Difficulty): SearchResult {
        transposition.clear()
        clearSearchHeuristics()
        val state = SearchState(
            deadlineNanos = System.nanoTime() + difficulty.timeLimitMillis * 1_000_000L,
            nodeLimit = difficulty.nodeLimit,
        )
        val rootHash = positionHash(position)

        val legalMoves = engine.generateLegalMoves(position)
        if (legalMoves.isEmpty()) {
            return SearchResult(move = null, score = -MATE_SCORE, depthReached = 0, nodes = 0)
        }

        var bestMove = legalMoves.first()
        var bestScore = Int.MIN_VALUE
        var completedDepth = 0
        var scoreGuess = 0

        state.pushPath(rootHash)
        try {
            for (depth in 1..difficulty.maxDepth) {
                try {
                    var aspiration = if (depth >= 3) 72 else MATE_SCORE
                    var alpha = if (depth >= 3) scoreGuess - aspiration else -MATE_SCORE
                    var beta = if (depth >= 3) scoreGuess + aspiration else MATE_SCORE
                    while (true) {
                        val result = rootSearch(position, depth, state, bestMove, alpha, beta)
                        if (result.score <= alpha && aspiration < MATE_SCORE / 2) {
                            aspiration *= 2
                            alpha = result.score - aspiration
                            beta = result.score + aspiration
                            continue
                        }
                        if (result.score >= beta && aspiration < MATE_SCORE / 2) {
                            aspiration *= 2
                            alpha = result.score - aspiration
                            beta = result.score + aspiration
                            continue
                        }
                        if (result.move != null) {
                            bestMove = result.move
                            bestScore = result.score
                            completedDepth = depth
                            scoreGuess = result.score
                        }
                        break
                    }
                } catch (_: SearchTimeout) {
                    break
                }
            }
        } finally {
            state.popPath()
        }

        return SearchResult(
            move = bestMove,
            score = bestScore,
            depthReached = completedDepth,
            nodes = state.nodes,
        )
    }

    private fun rootSearch(
        position: Position,
        depth: Int,
        state: SearchState,
        previousBest: Move?,
        alphaInput: Int,
        betaInput: Int,
    ): SearchResult {
        var alpha = alphaInput
        val beta = betaInput
        var bestMove: Move? = null
        var bestScore = -MATE_SCORE
        val orderedMoves = orderMoves(engine.generateLegalMoves(position), previousBest, 0)
        orderedMoves.forEachIndexed { index, move ->
            state.step()
            val next = engine.applyMove(position, move)
            val score = if (index == 0) {
                -negamax(next, depth - 1, -beta, -alpha, 1, state)
            } else {
                var candidate = -negamax(next, depth - 1, -alpha - 1, -alpha, 1, state)
                if (candidate > alpha && candidate < beta) {
                    candidate = -negamax(next, depth - 1, -beta, -alpha, 1, state)
                }
                candidate
            }
            if (score > bestScore) {
                bestScore = score
                bestMove = move
            }
            if (score > alpha) {
                alpha = score
            }
        }
        return SearchResult(bestMove, bestScore, depth, state.nodes)
    }

    private fun negamax(
        position: Position,
        depth: Int,
        alphaInput: Int,
        beta: Int,
        ply: Int,
        state: SearchState,
    ): Int {
        state.step()
        val hash = positionHash(position)
        repetitionScore(position, hash, state)?.let { score ->
            return score
        }
        state.pushPath(hash)
        try {
            val winner = engine.resolveWinner(position)
            if (winner != null) {
                return when (winner) {
                    Winner.DRAW -> 0
                    Winner.RED -> if (position.sideToMove == Side.RED) -MATE_SCORE + ply else MATE_SCORE - ply
                    Winner.BLACK -> if (position.sideToMove == Side.BLACK) -MATE_SCORE + ply else MATE_SCORE - ply
                }
            }

            if (depth <= 0) {
                return quiescence(position, alphaInput, beta, state)
            }

            val cached = transposition[hash]
            var alpha = alphaInput
            if (cached != null && cached.depth >= depth) {
                when (cached.flag) {
                    EntryFlag.EXACT -> return cached.score
                    EntryFlag.LOWER -> alpha = maxOf(alpha, cached.score)
                    EntryFlag.UPPER -> if (cached.score < beta && cached.score < alpha) return cached.score
                }
                if (alpha >= beta) {
                    return cached.score
                }
            }

            val legalMoves = engine.generateLegalMoves(position)
            if (legalMoves.isEmpty()) {
                return -MATE_SCORE + ply
            }

            val inCheck = engine.isInCheck(position.board, position.sideToMove)
            var bestMove: Move? = null
            var bestScore = -MATE_SCORE
            val originalAlpha = alphaInput
            val orderedMoves = orderMoves(legalMoves, cached?.bestMove, ply)

            for ((index, move) in orderedMoves.withIndex()) {
                val next = engine.applyMove(position, move)
                val score = if (index == 0) {
                    -negamax(next, depth - 1, -beta, -alpha, ply + 1, state)
                } else {
                    val canReduce =
                        depth >= 3 &&
                            index >= 3 &&
                            !inCheck &&
                            !move.isCapture &&
                            PieceCodec.typeOf(move.movedPiece) != PieceType.GENERAL
                    val reducedDepth = if (canReduce) depth - 2 else depth - 1
                    var candidate = -negamax(next, reducedDepth, -alpha - 1, -alpha, ply + 1, state)
                    if (canReduce && candidate > alpha) {
                        candidate = -negamax(next, depth - 1, -alpha - 1, -alpha, ply + 1, state)
                    }
                    if (candidate > alpha && candidate < beta) {
                        candidate = -negamax(next, depth - 1, -beta, -alpha, ply + 1, state)
                    }
                    candidate
                }
                if (score > bestScore) {
                    bestScore = score
                    bestMove = move
                }
                if (score > alpha) {
                    alpha = score
                }
                if (alpha >= beta) {
                    if (!move.isCapture) {
                        storeKiller(ply, move)
                        historyHeuristic[historyIndex(move)] += depth * depth
                    }
                    break
                }
            }

            val flag = when {
                bestScore <= originalAlpha -> EntryFlag.UPPER
                bestScore >= beta -> EntryFlag.LOWER
                else -> EntryFlag.EXACT
            }
            transposition[hash] = TranspositionEntry(depth, bestScore, flag, bestMove)
            return bestScore
        } finally {
            state.popPath()
        }
    }

    private fun quiescence(
        position: Position,
        alphaInput: Int,
        beta: Int,
        state: SearchState,
    ): Int {
        var alpha = alphaInput
        val standPat = evaluate(position)
        if (standPat >= beta) {
            return beta
        }
        if (standPat > alpha) {
            alpha = standPat
        }

        val captures = orderCaptures(engine.generateLegalMoves(position).filter { it.isCapture })
        for (move in captures) {
            state.step()
            val score = -quiescence(engine.applyMove(position, move), -beta, -alpha, state)
            if (score >= beta) {
                return beta
            }
            if (score > alpha) {
                alpha = score
            }
        }
        return alpha
    }

    private fun evaluate(position: Position): Int {
        var redScore = 0
        var blackScore = 0
        var redGuards = 0
        var blackGuards = 0
        var redElephants = 0
        var blackElephants = 0
        for (square in 0 until BOARD_SIZE) {
            val piece = position.board[square]
            if (piece == EMPTY) {
                continue
            }
            val side = PieceCodec.sideOf(piece) ?: continue
            val type = PieceCodec.typeOf(piece) ?: continue
            val score = type.baseValue + positionBonus(piece, square)
            if (side == Side.RED) {
                redScore += score
                if (type == PieceType.ADVISOR) redGuards++
                if (type == PieceType.ELEPHANT) redElephants++
            } else {
                blackScore += score
                if (type == PieceType.ADVISOR) blackGuards++
                if (type == PieceType.ELEPHANT) blackElephants++
            }
        }

        redScore += redGuards * 18 + redElephants * 12
        blackScore += blackGuards * 18 + blackElephants * 12

        val perspectiveScore = redScore - blackScore
        return if (position.sideToMove == Side.RED) perspectiveScore else -perspectiveScore
    }

    private fun positionBonus(piece: Int, square: Int): Int {
        val type = PieceCodec.typeOf(piece) ?: return 0
        val side = PieceCodec.sideOf(piece) ?: return 0
        val rank = if (side == Side.RED) 9 - rankOf(square) else rankOf(square)
        val fileDistance = abs(4 - fileOf(square))
        return when (type) {
            PieceType.GENERAL -> -(rank * 2) - fileDistance * 3
            PieceType.ADVISOR -> 6 - fileDistance * 2
            PieceType.ELEPHANT -> 8 - fileDistance
            PieceType.HORSE -> rank * 12 + (4 - fileDistance) * 10
            PieceType.ROOK -> rank * 8 + (4 - fileDistance) * 6
            PieceType.CANNON -> rank * 10 + (4 - fileDistance) * 8
            PieceType.PAWN -> rank * 18 + if (rank >= 5) 36 else 0
        }
    }

    private fun orderMoves(moves: List<Move>, preferredMove: Move?, ply: Int): List<Move> =
        moves.sortedByDescending { move ->
            var score = 0
            if (preferredMove != null && move.from == preferredMove.from && move.to == preferredMove.to) {
                score += 2_000_000
            }
            if (move.isCapture) {
                val capturedValue = PieceCodec.typeOf(move.capturedPiece)?.baseValue ?: 0
                val attackerValue = PieceCodec.typeOf(move.movedPiece)?.baseValue ?: 0
                score += 1_000_000 + capturedValue * 16 - attackerValue
            }
            val killers = killerMoves[ply]
            if (killers[0]?.sameRoute(move) == true) {
                score += 180_000
            } else if (killers[1]?.sameRoute(move) == true) {
                score += 150_000
            }
            score += historyHeuristic[historyIndex(move)]
            score += centralBonus(move)
            score
        }

    private fun orderCaptures(moves: List<Move>): List<Move> =
        moves.sortedByDescending { move ->
            val capturedValue = PieceCodec.typeOf(move.capturedPiece)?.baseValue ?: 0
            val attackerValue = PieceCodec.typeOf(move.movedPiece)?.baseValue ?: 0
            capturedValue * 16 - attackerValue
        }

    private fun positionHash(position: Position): Long {
        var hash = 0L
        for (square in 0 until BOARD_SIZE) {
            val piece = position.board[square]
            if (piece == EMPTY) {
                continue
            }
            hash = hash xor zobrist[square][pieceToIndex(piece)]
        }
        if (position.sideToMove == Side.BLACK) {
            hash = hash xor sideKey
        }
        return hash
    }

    private fun pieceToIndex(piece: Int): Int = if (piece > 0) piece else abs(piece) + 7

    private fun centralBonus(move: Move): Int {
        val fileBias = 4 - abs(4 - fileOf(move.to))
        val rankBias = if (move.movedPiece > 0) BOARD_RANKS - 1 - rankOf(move.to) else rankOf(move.to)
        return fileBias * 12 + rankBias * 4
    }

    private fun historyIndex(move: Move): Int = move.from * BOARD_SIZE + move.to

    private fun storeKiller(ply: Int, move: Move) {
        if (ply >= MAX_PLY) {
            return
        }
        val killers = killerMoves[ply]
        if (killers[0]?.sameRoute(move) == true) {
            return
        }
        killers[1] = killers[0]
        killers[0] = move
    }

    private fun clearSearchHeuristics() {
        for (ply in 0 until MAX_PLY) {
            killerMoves[ply][0] = null
            killerMoves[ply][1] = null
        }
        historyHeuristic.fill(0)
    }

    private fun repetitionScore(position: Position, hash: Long, state: SearchState): Int? {
        if (!state.hasSeen(hash)) {
            return null
        }
        return if (engine.isInCheck(position.board, position.sideToMove)) {
            REPETITION_CHECK_SCORE
        } else {
            0
        }
    }

    private data class SearchState(
        val deadlineNanos: Long,
        val nodeLimit: Int,
        var nodes: Int = 0,
        val pathHashes: LongArray = LongArray(MAX_PLY + 8),
        var pathSize: Int = 0,
    ) {
        fun step() {
            nodes += 1
            if (nodes and 255 == 0) {
                if (nodes >= nodeLimit || System.nanoTime() >= deadlineNanos) {
                    throw SearchTimeout
                }
            }
        }

        fun pushPath(hash: Long) {
            if (pathSize < pathHashes.size) {
                pathHashes[pathSize] = hash
                pathSize += 1
            }
        }

        fun popPath() {
            if (pathSize > 0) {
                pathSize -= 1
            }
        }

        fun hasSeen(hash: Long): Boolean {
            for (index in 0 until pathSize) {
                if (pathHashes[index] == hash) {
                    return true
                }
            }
            return false
        }
    }

    private data class TranspositionEntry(
        val depth: Int,
        val score: Int,
        val flag: EntryFlag,
        val bestMove: Move?,
    )

    private enum class EntryFlag {
        EXACT,
        LOWER,
        UPPER,
    }

    private object SearchTimeout : RuntimeException()

    private companion object {
        const val MATE_SCORE = 30_000
        const val REPETITION_CHECK_SCORE = 120
        const val MAX_PLY = 64
    }
}

private fun Move.sameRoute(other: Move): Boolean = from == other.from && to == other.to
