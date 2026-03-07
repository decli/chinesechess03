package com.decli.chinesechess.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiangqiEngineTest {
    @Test
    fun startPositionHasManyLegalMoves() {
        val position = XiangqiStartPosition.create()
        val moves = XiangqiEngine.generateLegalMoves(position)

        assertTrue("opening moves should be generated", moves.size >= 30)
        assertFalse("red should not start in check", XiangqiEngine.isInCheck(position.board, Side.RED))
        assertFalse("black should not start in check", XiangqiEngine.isInCheck(position.board, Side.BLACK))
    }

    @Test
    fun aiReturnsLegalMoveOnEasyDifficulty() {
        val position = XiangqiStartPosition.create()
        val ai = XiangqiAi()
        val result = ai.findBestMove(position, Difficulty.EASY)

        assertNotNull("ai should always return a move from the initial position", result.move)
        assertTrue(
            "returned move must be legal",
            XiangqiEngine.generateLegalMoves(position).any { it.from == result.move?.from && it.to == result.move?.to },
        )
    }

    @Test
    fun repetitionHelpersMatchBoardAndSideToMove() {
        val start = XiangqiStartPosition.create()
        val same = XiangqiStartPosition.create()
        val moved = XiangqiEngine.applyMove(
            start,
            XiangqiEngine.generateLegalMoves(start).first(),
        )

        assertTrue(samePosition(start, same))
        assertFalse(samePosition(start, moved))
        assertEquals(2, repetitionCount(listOf(start, moved, same), start))
    }

    @Test
    fun hardAiFinishesLoneKingMateInsteadOfShuffling() {
        val board = IntArray(BOARD_SIZE)
        put(board, 4, 9, Side.RED, PieceType.GENERAL)
        put(board, 4, 0, Side.BLACK, PieceType.GENERAL)
        put(board, 4, 6, Side.BLACK, PieceType.ROOK)
        put(board, 2, 7, Side.BLACK, PieceType.HORSE)
        put(board, 6, 7, Side.BLACK, PieceType.HORSE)

        val position = Position(board = board, sideToMove = Side.BLACK)
        val ai = XiangqiAi()
        val result = ai.findBestMove(position, Difficulty.HARD)

        assertNotNull("hard ai should find the mating move", result.move)
        assertEquals(squareOf(4, 6), result.move?.from)
        assertEquals(PieceType.ROOK, PieceCodec.typeOf(result.move!!.movedPiece))

        val next = XiangqiEngine.applyMove(position, result.move!!)
        assertEquals(Winner.BLACK, XiangqiEngine.resolveWinner(next))
    }

    private fun put(board: IntArray, file: Int, rank: Int, side: Side, type: PieceType) {
        board[squareOf(file, rank)] = PieceCodec.make(side, type)
    }
}
