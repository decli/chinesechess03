package com.decli.chinesechess.game

import kotlin.math.abs
import kotlin.random.Random

class XiangqiAi(
    private val engine: XiangqiEngine = XiangqiEngine,
) {
    private val zobrist = Array(BOARD_SIZE) { LongArray(15) }
    private val sideKey: Long
    private val transposition = HashMap<Long, TranspositionEntry>(200_000)

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
        val state = SearchState(
            deadlineNanos = System.nanoTime() + difficulty.timeLimitMillis * 1_000_000L,
            nodeLimit = difficulty.nodeLimit,
        )

        val legalMoves = engine.generateLegalMoves(position)
        if (legalMoves.isEmpty()) {
            return SearchResult(move = null, score = -MATE_SCORE, depthReached = 0, nodes = 0)
        }

        var bestMove = legalMoves.first()
        var bestScore = Int.MIN_VALUE
        var completedDepth = 0

        for (depth in 1..difficulty.maxDepth) {
            try {
                val result = rootSearch(position, depth, state, bestMove)
                if (result.move != null) {
                    bestMove = result.move
                    bestScore = result.score
                    completedDepth = depth
                }
            } catch (_: SearchTimeout) {
                break
            }
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
    ): SearchResult {
        var alpha = -MATE_SCORE
        val beta = MATE_SCORE
        var bestMove: Move? = null
        var bestScore = -MATE_SCORE
        val orderedMoves = orderMoves(position, engine.generateLegalMoves(position), previousBest)
        for (move in orderedMoves) {
            state.step()
            val next = engine.applyMove(position, move)
            val score = -negamax(next, depth - 1, -beta, -alpha, 1, state)
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

        val hash = positionHash(position)
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

        var bestMove: Move? = null
        var bestScore = -MATE_SCORE
        val originalAlpha = alphaInput
        val orderedMoves = orderMoves(position, legalMoves, cached?.bestMove)

        for (move in orderedMoves) {
            val next = engine.applyMove(position, move)
            val score = -negamax(next, depth - 1, -beta, -alpha, ply + 1, state)
            if (score > bestScore) {
                bestScore = score
                bestMove = move
            }
            if (score > alpha) {
                alpha = score
            }
            if (alpha >= beta) {
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

        val captures = engine.generateLegalMoves(position).filter { it.isCapture }
        for (move in orderMoves(position, captures, null)) {
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

    private fun orderMoves(position: Position, moves: List<Move>, preferredMove: Move?): List<Move> =
        moves.sortedByDescending { move ->
            var score = 0
            if (preferredMove != null && move.from == preferredMove.from && move.to == preferredMove.to) {
                score += 10_000
            }
            if (move.isCapture) {
                val capturedValue = PieceCodec.typeOf(move.capturedPiece)?.baseValue ?: 0
                val attackerValue = PieceCodec.typeOf(move.movedPiece)?.baseValue ?: 0
                score += 5_000 + capturedValue - attackerValue / 4
            }
            if (move.to == 40 || move.to == 49) {
                score += 40
            }
            val next = engine.applyMove(position, move)
            if (engine.isInCheck(next.board, next.sideToMove)) {
                score += 350
            }
            score
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

    private data class SearchState(
        val deadlineNanos: Long,
        val nodeLimit: Int,
        var nodes: Int = 0,
    ) {
        fun step() {
            nodes += 1
            if (nodes and 255 == 0) {
                if (nodes >= nodeLimit || System.nanoTime() >= deadlineNanos) {
                    throw SearchTimeout
                }
            }
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
    }
}
