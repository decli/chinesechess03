package com.decli.chinesechess.game

import kotlin.math.abs

object XiangqiEngine {
    private val orthogonalDirections = arrayOf(
        intArrayOf(1, 0),
        intArrayOf(-1, 0),
        intArrayOf(0, 1),
        intArrayOf(0, -1),
    )

    private val advisorDirections = arrayOf(
        intArrayOf(1, 1),
        intArrayOf(-1, 1),
        intArrayOf(1, -1),
        intArrayOf(-1, -1),
    )

    private val elephantDirections = arrayOf(
        intArrayOf(2, 2),
        intArrayOf(-2, 2),
        intArrayOf(2, -2),
        intArrayOf(-2, -2),
    )

    private val horseOffsets = arrayOf(
        intArrayOf(1, 2, 0, 1),
        intArrayOf(-1, 2, 0, 1),
        intArrayOf(1, -2, 0, -1),
        intArrayOf(-1, -2, 0, -1),
        intArrayOf(2, 1, 1, 0),
        intArrayOf(2, -1, 1, 0),
        intArrayOf(-2, 1, -1, 0),
        intArrayOf(-2, -1, -1, 0),
    )

    fun generateLegalMoves(position: Position, side: Side = position.sideToMove): List<Move> {
        val pseudoMoves = mutableListOf<Move>()
        for (square in 0 until BOARD_SIZE) {
            val piece = position.board[square]
            if (PieceCodec.sideOf(piece) == side) {
                generatePseudoMovesForPiece(position.board, square, piece, pseudoMoves)
            }
        }
        if (pseudoMoves.isEmpty()) {
            return emptyList()
        }
        return pseudoMoves.filter { move ->
            val next = applyMove(position, move)
            !isInCheck(next.board, side)
        }
    }

    fun legalMovesForSquare(position: Position, square: Int): List<Move> =
        generateLegalMoves(position).filter { it.from == square }

    fun applyMove(position: Position, move: Move): Position {
        val nextBoard = position.copyBoard()
        nextBoard[move.to] = nextBoard[move.from]
        nextBoard[move.from] = EMPTY
        return Position(
            board = nextBoard,
            sideToMove = position.sideToMove.opposite(),
            plyCount = position.plyCount + 1,
            quietHalfMoves = if (move.isCapture || PieceCodec.typeOf(move.movedPiece) == PieceType.PAWN) 0 else position.quietHalfMoves + 1,
            lastMove = move,
        )
    }

    fun resolveWinner(position: Position): Winner? {
        val legalMoves = generateLegalMoves(position)
        if (legalMoves.isEmpty()) {
            return if (position.sideToMove == Side.RED) Winner.BLACK else Winner.RED
        }
        if (position.quietHalfMoves >= 120) {
            return Winner.DRAW
        }
        return null
    }

    fun isInCheck(board: IntArray, side: Side): Boolean {
        val generalSquare = findGeneral(board, side) ?: return true
        return isSquareAttacked(board, generalSquare, side.opposite())
    }

    fun findGeneral(board: IntArray, side: Side): Int? {
        val target = PieceCodec.make(side, PieceType.GENERAL)
        return board.indexOfFirst { it == target }.takeIf { it >= 0 }
    }

    fun isSquareAttacked(board: IntArray, targetSquare: Int, attackerSide: Side): Boolean {
        val targetFile = fileOf(targetSquare)
        val targetRank = rankOf(targetSquare)

        for (offset in horseOffsets) {
            val sourceFile = targetFile - offset[0]
            val sourceRank = targetRank - offset[1]
            val legFile = targetFile - offset[2]
            val legRank = targetRank - offset[3]
            if (!insideBoard(sourceFile, sourceRank) || !insideBoard(legFile, legRank)) {
                continue
            }
            val sourceSquare = squareOf(sourceFile, sourceRank)
            val legSquare = squareOf(legFile, legRank)
            if (board[legSquare] == EMPTY && board[sourceSquare] == PieceCodec.make(attackerSide, PieceType.HORSE)) {
                return true
            }
        }

        for (offset in advisorDirections) {
            val sourceFile = targetFile - offset[0]
            val sourceRank = targetRank - offset[1]
            if (!insideBoard(sourceFile, sourceRank)) {
                continue
            }
            val sourceSquare = squareOf(sourceFile, sourceRank)
            if (board[sourceSquare] == PieceCodec.make(attackerSide, PieceType.ADVISOR) &&
                insidePalace(sourceFile, sourceRank, attackerSide)
            ) {
                return true
            }
        }

        for (offset in elephantDirections) {
            val sourceFile = targetFile - offset[0]
            val sourceRank = targetRank - offset[1]
            val eyeFile = targetFile - offset[0] / 2
            val eyeRank = targetRank - offset[1] / 2
            if (!insideBoard(sourceFile, sourceRank) || !insideBoard(eyeFile, eyeRank)) {
                continue
            }
            val sourceSquare = squareOf(sourceFile, sourceRank)
            if (board[squareOf(eyeFile, eyeRank)] != EMPTY) {
                continue
            }
            if (board[sourceSquare] == PieceCodec.make(attackerSide, PieceType.ELEPHANT) &&
                elephantOwnSide(sourceRank, attackerSide)
            ) {
                return true
            }
        }

        val generalPiece = PieceCodec.make(attackerSide, PieceType.GENERAL)
        for (direction in orthogonalDirections) {
            val sourceFile = targetFile - direction[0]
            val sourceRank = targetRank - direction[1]
            if (!insideBoard(sourceFile, sourceRank)) {
                continue
            }
            val sourceSquare = squareOf(sourceFile, sourceRank)
            if (board[sourceSquare] == generalPiece && insidePalace(sourceFile, sourceRank, attackerSide)) {
                return true
            }
        }

        if (attackerSide == Side.RED) {
            val sourceRank = targetRank + 1
            if (insideBoard(targetFile, sourceRank) &&
                board[squareOf(targetFile, sourceRank)] == PieceCodec.make(Side.RED, PieceType.PAWN)
            ) {
                return true
            }
            if (targetRank <= 4) {
                if (insideBoard(targetFile - 1, targetRank) &&
                    board[squareOf(targetFile - 1, targetRank)] == PieceCodec.make(Side.RED, PieceType.PAWN)
                ) {
                    return true
                }
                if (insideBoard(targetFile + 1, targetRank) &&
                    board[squareOf(targetFile + 1, targetRank)] == PieceCodec.make(Side.RED, PieceType.PAWN)
                ) {
                    return true
                }
            }
        } else {
            val sourceRank = targetRank - 1
            if (insideBoard(targetFile, sourceRank) &&
                board[squareOf(targetFile, sourceRank)] == PieceCodec.make(Side.BLACK, PieceType.PAWN)
            ) {
                return true
            }
            if (targetRank >= 5) {
                if (insideBoard(targetFile - 1, targetRank) &&
                    board[squareOf(targetFile - 1, targetRank)] == PieceCodec.make(Side.BLACK, PieceType.PAWN)
                ) {
                    return true
                }
                if (insideBoard(targetFile + 1, targetRank) &&
                    board[squareOf(targetFile + 1, targetRank)] == PieceCodec.make(Side.BLACK, PieceType.PAWN)
                ) {
                    return true
                }
            }
        }

        for (direction in orthogonalDirections) {
            var file = targetFile + direction[0]
            var rank = targetRank + direction[1]
            var screenSeen = false
            while (insideBoard(file, rank)) {
                val square = squareOf(file, rank)
                val piece = board[square]
                if (piece != EMPTY) {
                    val pieceSide = PieceCodec.sideOf(piece)
                    val type = PieceCodec.typeOf(piece)
                    if (!screenSeen) {
                        if (pieceSide == attackerSide && type == PieceType.ROOK) {
                            return true
                        }
                        if (pieceSide == attackerSide && type == PieceType.GENERAL &&
                            direction[0] == 0 &&
                            findGeneral(board, attackerSide.opposite()) == targetSquare
                        ) {
                            return true
                        }
                        screenSeen = true
                    } else {
                        if (pieceSide == attackerSide && type == PieceType.CANNON) {
                            return true
                        }
                        break
                    }
                }
                file += direction[0]
                rank += direction[1]
            }
        }

        return false
    }

    private fun generatePseudoMovesForPiece(
        board: IntArray,
        square: Int,
        piece: Int,
        output: MutableList<Move>,
    ) {
        when (PieceCodec.typeOf(piece)) {
            PieceType.GENERAL -> generateGeneralMoves(board, square, piece, output)
            PieceType.ADVISOR -> generateAdvisorMoves(board, square, piece, output)
            PieceType.ELEPHANT -> generateElephantMoves(board, square, piece, output)
            PieceType.HORSE -> generateHorseMoves(board, square, piece, output)
            PieceType.ROOK -> generateRookMoves(board, square, piece, output)
            PieceType.CANNON -> generateCannonMoves(board, square, piece, output)
            PieceType.PAWN -> generatePawnMoves(board, square, piece, output)
            null -> Unit
        }
    }

    private fun generateGeneralMoves(board: IntArray, square: Int, piece: Int, output: MutableList<Move>) {
        val side = PieceCodec.sideOf(piece) ?: return
        val file = fileOf(square)
        val rank = rankOf(square)
        for (direction in orthogonalDirections) {
            val nextFile = file + direction[0]
            val nextRank = rank + direction[1]
            if (!insideBoard(nextFile, nextRank) || !insidePalace(nextFile, nextRank, side)) {
                continue
            }
            addMoveIfAllowed(board, square, squareOf(nextFile, nextRank), output)
        }

        val enemyGeneral = PieceCodec.make(side.opposite(), PieceType.GENERAL)
        var nextRank = rank + if (side == Side.RED) -1 else 1
        while (insideBoard(file, nextRank)) {
            val targetSquare = squareOf(file, nextRank)
            val occupant = board[targetSquare]
            if (occupant != EMPTY) {
                if (occupant == enemyGeneral) {
                    output += Move(square, targetSquare, piece, occupant)
                }
                break
            }
            nextRank += if (side == Side.RED) -1 else 1
        }
    }

    private fun generateAdvisorMoves(board: IntArray, square: Int, piece: Int, output: MutableList<Move>) {
        val side = PieceCodec.sideOf(piece) ?: return
        val file = fileOf(square)
        val rank = rankOf(square)
        for (direction in advisorDirections) {
            val nextFile = file + direction[0]
            val nextRank = rank + direction[1]
            if (!insideBoard(nextFile, nextRank) || !insidePalace(nextFile, nextRank, side)) {
                continue
            }
            addMoveIfAllowed(board, square, squareOf(nextFile, nextRank), output)
        }
    }

    private fun generateElephantMoves(board: IntArray, square: Int, piece: Int, output: MutableList<Move>) {
        val side = PieceCodec.sideOf(piece) ?: return
        val file = fileOf(square)
        val rank = rankOf(square)
        for (direction in elephantDirections) {
            val nextFile = file + direction[0]
            val nextRank = rank + direction[1]
            val eyeFile = file + direction[0] / 2
            val eyeRank = rank + direction[1] / 2
            if (!insideBoard(nextFile, nextRank) || !insideBoard(eyeFile, eyeRank)) {
                continue
            }
            if (!elephantOwnSide(nextRank, side) || board[squareOf(eyeFile, eyeRank)] != EMPTY) {
                continue
            }
            addMoveIfAllowed(board, square, squareOf(nextFile, nextRank), output)
        }
    }

    private fun generateHorseMoves(board: IntArray, square: Int, piece: Int, output: MutableList<Move>) {
        val file = fileOf(square)
        val rank = rankOf(square)
        for (offset in horseOffsets) {
            val nextFile = file + offset[0]
            val nextRank = rank + offset[1]
            val legFile = file + offset[2]
            val legRank = rank + offset[3]
            if (!insideBoard(nextFile, nextRank) || !insideBoard(legFile, legRank)) {
                continue
            }
            if (board[squareOf(legFile, legRank)] != EMPTY) {
                continue
            }
            addMoveIfAllowed(board, square, squareOf(nextFile, nextRank), output)
        }
    }

    private fun generateRookMoves(board: IntArray, square: Int, piece: Int, output: MutableList<Move>) {
        slideMoves(board, square, piece, output, requireScreen = false)
    }

    private fun generateCannonMoves(board: IntArray, square: Int, piece: Int, output: MutableList<Move>) {
        slideMoves(board, square, piece, output, requireScreen = true)
    }

    private fun slideMoves(
        board: IntArray,
        square: Int,
        piece: Int,
        output: MutableList<Move>,
        requireScreen: Boolean,
    ) {
        val file = fileOf(square)
        val rank = rankOf(square)
        for (direction in orthogonalDirections) {
            var nextFile = file + direction[0]
            var nextRank = rank + direction[1]
            var screenSeen = false
            while (insideBoard(nextFile, nextRank)) {
                val targetSquare = squareOf(nextFile, nextRank)
                val occupant = board[targetSquare]
                if (!requireScreen) {
                    if (occupant == EMPTY) {
                        output += Move(square, targetSquare, piece, EMPTY)
                    } else {
                        if (!sameSide(piece, occupant)) {
                            output += Move(square, targetSquare, piece, occupant)
                        }
                        break
                    }
                } else {
                    if (!screenSeen) {
                        if (occupant == EMPTY) {
                            output += Move(square, targetSquare, piece, EMPTY)
                        } else {
                            screenSeen = true
                        }
                    } else if (occupant != EMPTY) {
                        if (!sameSide(piece, occupant)) {
                            output += Move(square, targetSquare, piece, occupant)
                        }
                        break
                    }
                }
                nextFile += direction[0]
                nextRank += direction[1]
            }
        }
    }

    private fun generatePawnMoves(board: IntArray, square: Int, piece: Int, output: MutableList<Move>) {
        val side = PieceCodec.sideOf(piece) ?: return
        val file = fileOf(square)
        val rank = rankOf(square)
        val forwardRank = rank + if (side == Side.RED) -1 else 1
        if (insideBoard(file, forwardRank)) {
            addMoveIfAllowed(board, square, squareOf(file, forwardRank), output)
        }

        val crossedRiver = if (side == Side.RED) rank <= 4 else rank >= 5
        if (crossedRiver) {
            if (insideBoard(file - 1, rank)) {
                addMoveIfAllowed(board, square, squareOf(file - 1, rank), output)
            }
            if (insideBoard(file + 1, rank)) {
                addMoveIfAllowed(board, square, squareOf(file + 1, rank), output)
            }
        }
    }

    private fun addMoveIfAllowed(
        board: IntArray,
        from: Int,
        to: Int,
        output: MutableList<Move>,
    ) {
        val moved = board[from]
        val captured = board[to]
        if (!sameSide(moved, captured)) {
            output += Move(from, to, moved, captured)
        }
    }

    private fun insidePalace(file: Int, rank: Int, side: Side): Boolean {
        val rankRange = if (side == Side.RED) 7..9 else 0..2
        return file in 3..5 && rank in rankRange
    }

    private fun elephantOwnSide(rank: Int, side: Side): Boolean =
        if (side == Side.RED) rank >= 5 else rank <= 4
}

