package com.decli.chinesechess.game

import kotlin.math.abs

const val BOARD_FILES = 9
const val BOARD_RANKS = 10
const val BOARD_SIZE = BOARD_FILES * BOARD_RANKS
const val EMPTY = 0

enum class Side(val sign: Int, val title: String) {
    RED(1, "红方"),
    BLACK(-1, "黑方");

    fun opposite(): Side = if (this == RED) BLACK else RED
}

enum class PieceType(
    val code: Int,
    val label: String,
    val baseValue: Int,
) {
    GENERAL(1, "将", 12000),
    ADVISOR(2, "士", 140),
    ELEPHANT(3, "象", 150),
    HORSE(4, "马", 340),
    ROOK(5, "车", 720),
    CANNON(6, "炮", 380),
    PAWN(7, "兵", 110),
}

enum class Difficulty(
    val title: String,
    val maxDepth: Int,
    val timeLimitMillis: Long,
    val nodeLimit: Int,
) {
    EASY("入门", 2, 350L, 20_000),
    MEDIUM("中级", 4, 900L, 120_000),
    HARD("高手", 6, 1_800L, 420_000),
}

data class Move(
    val from: Int,
    val to: Int,
    val movedPiece: Int,
    val capturedPiece: Int = EMPTY,
) {
    val isCapture: Boolean get() = capturedPiece != EMPTY
}

data class SearchResult(
    val move: Move?,
    val score: Int,
    val depthReached: Int,
    val nodes: Int,
)

data class Position(
    val board: IntArray,
    val sideToMove: Side,
    val plyCount: Int = 0,
    val quietHalfMoves: Int = 0,
    val lastMove: Move? = null,
) {
    fun copyBoard(): IntArray = board.copyOf()
}

fun samePosition(a: Position, b: Position): Boolean =
    a.sideToMove == b.sideToMove &&
        a.board.contentEquals(b.board)

fun repetitionCount(history: List<Position>, target: Position): Int =
    history.count { position -> samePosition(position, target) }

enum class Winner(val title: String) {
    RED("红方胜"),
    BLACK("黑方胜"),
    DRAW("和棋"),
}

object PieceCodec {
    fun make(side: Side, type: PieceType): Int = type.code * side.sign

    fun sideOf(piece: Int): Side? = when {
        piece > 0 -> Side.RED
        piece < 0 -> Side.BLACK
        else -> null
    }

    fun typeOf(piece: Int): PieceType? = when (abs(piece)) {
        1 -> PieceType.GENERAL
        2 -> PieceType.ADVISOR
        3 -> PieceType.ELEPHANT
        4 -> PieceType.HORSE
        5 -> PieceType.ROOK
        6 -> PieceType.CANNON
        7 -> PieceType.PAWN
        else -> null
    }

    fun displayLabel(piece: Int): String {
        val type = typeOf(piece) ?: return ""
        return when {
            piece > 0 && type == PieceType.GENERAL -> "帅"
            piece > 0 && type == PieceType.ADVISOR -> "仕"
            piece > 0 && type == PieceType.ELEPHANT -> "相"
            piece > 0 && type == PieceType.PAWN -> "兵"
            piece < 0 && type == PieceType.GENERAL -> "将"
            piece < 0 && type == PieceType.ADVISOR -> "士"
            piece < 0 && type == PieceType.ELEPHANT -> "象"
            piece < 0 && type == PieceType.PAWN -> "卒"
            else -> type.label
        }
    }
}

fun fileOf(square: Int): Int = square % BOARD_FILES

fun rankOf(square: Int): Int = square / BOARD_FILES

fun squareOf(file: Int, rank: Int): Int = rank * BOARD_FILES + file

fun insideBoard(file: Int, rank: Int): Boolean =
    file in 0 until BOARD_FILES && rank in 0 until BOARD_RANKS

fun sameSide(a: Int, b: Int): Boolean =
    a != EMPTY && b != EMPTY && PieceCodec.sideOf(a) == PieceCodec.sideOf(b)

fun sideMultiplier(side: Side): Int = if (side == Side.RED) 1 else -1

object XiangqiStartPosition {
    fun create(): Position {
        val board = IntArray(BOARD_SIZE)
        fun put(file: Int, rank: Int, side: Side, type: PieceType) {
            board[squareOf(file, rank)] = PieceCodec.make(side, type)
        }

        put(0, 0, Side.BLACK, PieceType.ROOK)
        put(1, 0, Side.BLACK, PieceType.HORSE)
        put(2, 0, Side.BLACK, PieceType.ELEPHANT)
        put(3, 0, Side.BLACK, PieceType.ADVISOR)
        put(4, 0, Side.BLACK, PieceType.GENERAL)
        put(5, 0, Side.BLACK, PieceType.ADVISOR)
        put(6, 0, Side.BLACK, PieceType.ELEPHANT)
        put(7, 0, Side.BLACK, PieceType.HORSE)
        put(8, 0, Side.BLACK, PieceType.ROOK)
        put(1, 2, Side.BLACK, PieceType.CANNON)
        put(7, 2, Side.BLACK, PieceType.CANNON)
        put(0, 3, Side.BLACK, PieceType.PAWN)
        put(2, 3, Side.BLACK, PieceType.PAWN)
        put(4, 3, Side.BLACK, PieceType.PAWN)
        put(6, 3, Side.BLACK, PieceType.PAWN)
        put(8, 3, Side.BLACK, PieceType.PAWN)

        put(0, 9, Side.RED, PieceType.ROOK)
        put(1, 9, Side.RED, PieceType.HORSE)
        put(2, 9, Side.RED, PieceType.ELEPHANT)
        put(3, 9, Side.RED, PieceType.ADVISOR)
        put(4, 9, Side.RED, PieceType.GENERAL)
        put(5, 9, Side.RED, PieceType.ADVISOR)
        put(6, 9, Side.RED, PieceType.ELEPHANT)
        put(7, 9, Side.RED, PieceType.HORSE)
        put(8, 9, Side.RED, PieceType.ROOK)
        put(1, 7, Side.RED, PieceType.CANNON)
        put(7, 7, Side.RED, PieceType.CANNON)
        put(0, 6, Side.RED, PieceType.PAWN)
        put(2, 6, Side.RED, PieceType.PAWN)
        put(4, 6, Side.RED, PieceType.PAWN)
        put(6, 6, Side.RED, PieceType.PAWN)
        put(8, 6, Side.RED, PieceType.PAWN)

        return Position(board = board, sideToMove = Side.RED)
    }
}

