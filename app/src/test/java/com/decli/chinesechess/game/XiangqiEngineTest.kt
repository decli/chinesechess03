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
}
