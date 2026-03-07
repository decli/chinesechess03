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

    fun findBestMove(
        position: Position,
        difficulty: Difficulty,
        history: List<Position> = listOf(position),
    ): SearchResult {
        val endgameProfile = detectEndgameProfile(position)
        transposition.clear()
        clearSearchHeuristics()
        val state = SearchState(
            deadlineNanos =
                System.nanoTime() + (difficulty.timeLimitMillis + (endgameProfile?.extraTimeMillis ?: 0L)) * 1_000_000L,
            nodeLimit = difficulty.nodeLimit + (endgameProfile?.extraNodeBudget ?: 0),
        )
        seedHistory(state, history, position)

        val legalMoves = engine.generateLegalMoves(position)
        if (legalMoves.isEmpty()) {
            return SearchResult(move = null, score = -MATE_SCORE, depthReached = 0, nodes = 0)
        }
        if (endgameProfile?.defenderOnlyGeneral == true && position.sideToMove == endgameProfile.attacker) {
            val forcingResult = findForcedMateMove(position, difficulty, endgameProfile, history)
            if (forcingResult != null) {
                return forcingResult
            }
        }

        var bestMove = legalMoves.first()
        var bestScore = Int.MIN_VALUE
        var completedDepth = 0
        var scoreGuess = 0

        val maxDepth = difficulty.maxDepth + (endgameProfile?.extraDepth ?: 0)
        for (depth in 1..maxDepth) {
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
        for ((index, move) in orderedMoves.withIndex()) {
            state.step()
            val next = engine.applyMove(position, move)
            val nextHash = positionHash(next)
            val moveIntent = describeMove(position, move, next)
            val extension = if (moveIntent.givesCheck) 1 else 0
            val searchDepth = depth - 1 + extension
            val score = classifyLoopScore(next, nextHash, moveIntent, state) ?: run {
                state.push(next, nextHash, moveIntent)
                val value = if (index == 0) {
                    -negamax(next, searchDepth, -beta, -alpha, 1, state)
                } else {
                    var candidate = -negamax(next, searchDepth, -alpha - 1, -alpha, 1, state)
                    if (candidate > alpha && candidate < beta) {
                        candidate = -negamax(next, searchDepth, -beta, -alpha, 1, state)
                    }
                    candidate
                }
                state.pop()
                value
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
            val nextHash = positionHash(next)
            val moveIntent = describeMove(position, move, next)
            val extension = if (moveIntent.givesCheck) 1 else 0
            val searchDepth = depth - 1 + extension
            val score = classifyLoopScore(next, nextHash, moveIntent, state) ?: run {
                state.push(next, nextHash, moveIntent)
                val value = if (index == 0) {
                    -negamax(next, searchDepth, -beta, -alpha, ply + 1, state)
                } else {
                    val canReduce =
                        depth >= 3 &&
                            index >= 3 &&
                            !inCheck &&
                            !move.isCapture &&
                            PieceCodec.typeOf(move.movedPiece) != PieceType.GENERAL &&
                            !moveIntent.givesCheck &&
                            moveIntent.unprotectedVictimCount == 0
                    val reducedDepth = if (canReduce) maxOf(0, searchDepth - 1) else searchDepth
                    var candidate = -negamax(next, reducedDepth, -alpha - 1, -alpha, ply + 1, state)
                    if (canReduce && candidate > alpha) {
                        candidate = -negamax(next, searchDepth, -alpha - 1, -alpha, ply + 1, state)
                    }
                    if (candidate > alpha && candidate < beta) {
                        candidate = -negamax(next, searchDepth, -beta, -alpha, ply + 1, state)
                    }
                    candidate
                }
                state.pop()
                value
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
            val next = engine.applyMove(position, move)
            val nextHash = positionHash(next)
            val moveIntent = describeMove(position, move, next)
            val score = classifyLoopScore(next, nextHash, moveIntent, state) ?: run {
                state.push(next, nextHash, moveIntent)
                val value = -quiescence(next, -beta, -alpha, state)
                state.pop()
                value
            }
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
        var redNonKingMaterial = 0
        var blackNonKingMaterial = 0
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
                if (type != PieceType.GENERAL) redNonKingMaterial += type.baseValue
            } else {
                blackScore += score
                if (type == PieceType.ADVISOR) blackGuards++
                if (type == PieceType.ELEPHANT) blackElephants++
                if (type != PieceType.GENERAL) blackNonKingMaterial += type.baseValue
            }
        }

        redScore += redGuards * 18 + redElephants * 12
        blackScore += blackGuards * 18 + blackElephants * 12
        if (blackNonKingMaterial == 0 && redNonKingMaterial > 0) {
            redScore += loneKingAttackBonus(position, attacker = Side.RED, defender = Side.BLACK)
        }
        if (redNonKingMaterial == 0 && blackNonKingMaterial > 0) {
            blackScore += loneKingAttackBonus(position, attacker = Side.BLACK, defender = Side.RED)
        }

        val perspectiveScore = redScore - blackScore
        return if (position.sideToMove == Side.RED) perspectiveScore else -perspectiveScore
    }

    private fun loneKingAttackBonus(
        position: Position,
        attacker: Side,
        defender: Side,
    ): Int {
        val defenderGeneral = engine.findGeneral(position.board, defender) ?: return 0
        val defenderLegalMoves = engine.generateLegalMoves(position, defender).size
        val defenderFile = fileOf(defenderGeneral)
        val defenderRank = rankOf(defenderGeneral)
        var bonus = (6 - defenderLegalMoves.coerceAtMost(6)) * 140
        if (defenderFile != 4) {
            bonus += 110
        }
        val homeRank = if (defender == Side.RED) 9 else 0
        if (defenderRank == homeRank) {
            bonus += 130
        } else {
            bonus += 50
        }

        for (square in 0 until BOARD_SIZE) {
            val piece = position.board[square]
            if (PieceCodec.sideOf(piece) != attacker) {
                continue
            }
            val type = PieceCodec.typeOf(piece) ?: continue
            if (type == PieceType.GENERAL || type == PieceType.ADVISOR || type == PieceType.ELEPHANT) {
                continue
            }
            val distance = abs(fileOf(square) - defenderFile) + abs(rankOf(square) - defenderRank)
            bonus += when (type) {
                PieceType.ROOK -> maxOf(0, 11 - distance) * 26
                PieceType.CANNON -> maxOf(0, 11 - distance) * 22
                PieceType.HORSE -> maxOf(0, 10 - distance) * 20
                PieceType.PAWN -> maxOf(0, 9 - distance) * 16
                else -> 0
            }
            val attacksGeneral = engine.pseudoCapturesForPiece(position.board, square).any { capture ->
                capture.to == defenderGeneral
            }
            if (attacksGeneral) {
                bonus += when (type) {
                    PieceType.ROOK -> 260
                    PieceType.CANNON -> 220
                    PieceType.HORSE -> 190
                    PieceType.PAWN -> 140
                    else -> 0
                }
            }
        }
        return bonus
    }

    private fun detectEndgameProfile(position: Position): EndgameProfile? {
        var redNonKingMaterial = 0
        var blackNonKingMaterial = 0
        var redRooks = 0
        var blackRooks = 0
        var redCannons = 0
        var blackCannons = 0
        var redHorses = 0
        var blackHorses = 0
        var redPawns = 0
        var blackPawns = 0
        var redOffenseValue = 0
        var blackOffenseValue = 0

        for (square in 0 until BOARD_SIZE) {
            val piece = position.board[square]
            val side = PieceCodec.sideOf(piece) ?: continue
            val type = PieceCodec.typeOf(piece) ?: continue
            if (type != PieceType.GENERAL) {
                if (side == Side.RED) {
                    redNonKingMaterial += type.baseValue
                } else {
                    blackNonKingMaterial += type.baseValue
                }
            }
            when (type) {
                PieceType.ROOK -> if (side == Side.RED) redRooks++ else blackRooks++
                PieceType.CANNON -> if (side == Side.RED) redCannons++ else blackCannons++
                PieceType.HORSE -> if (side == Side.RED) redHorses++ else blackHorses++
                PieceType.PAWN -> if (side == Side.RED) redPawns++ else blackPawns++
                else -> Unit
            }
        }

        redOffenseValue =
            redRooks * PieceType.ROOK.baseValue +
                redCannons * PieceType.CANNON.baseValue +
                redHorses * PieceType.HORSE.baseValue +
                redPawns * PieceType.PAWN.baseValue
        blackOffenseValue =
            blackRooks * PieceType.ROOK.baseValue +
                blackCannons * PieceType.CANNON.baseValue +
                blackHorses * PieceType.HORSE.baseValue +
                blackPawns * PieceType.PAWN.baseValue

        if (blackNonKingMaterial == 0 &&
            hasLikelyMatingMaterial(redRooks, redCannons, redHorses, redPawns, redOffenseValue)
        ) {
            return endgameProfileFor(Side.RED, redOffenseValue, defenderOnlyGeneral = true)
        }
        if (redNonKingMaterial == 0 &&
            hasLikelyMatingMaterial(blackRooks, blackCannons, blackHorses, blackPawns, blackOffenseValue)
        ) {
            return endgameProfileFor(Side.BLACK, blackOffenseValue, defenderOnlyGeneral = true)
        }
        return null
    }

    private fun hasLikelyMatingMaterial(
        rooks: Int,
        cannons: Int,
        horses: Int,
        pawns: Int,
        offenseValue: Int,
    ): Boolean =
        rooks >= 1 ||
            (cannons >= 1 && horses >= 1) ||
            cannons >= 2 ||
            horses >= 2 ||
            (pawns >= 2 && offenseValue >= 320) ||
            offenseValue >= 650

    private fun endgameProfileFor(
        attacker: Side,
        offenseValue: Int,
        defenderOnlyGeneral: Boolean,
    ): EndgameProfile {
        val pressureFactor = when {
            offenseValue >= 1_600 -> 3
            offenseValue >= 900 -> 2
            else -> 1
        }
        return EndgameProfile(
            attacker = attacker,
            defender = attacker.opposite(),
            defenderOnlyGeneral = defenderOnlyGeneral,
            extraDepth = 1 + pressureFactor,
            extraNodeBudget = 40_000 * pressureFactor,
            extraTimeMillis = 120L * pressureFactor,
            mateSearchDepth = 5 + pressureFactor * 2,
        )
    }

    private fun findForcedMateMove(
        position: Position,
        difficulty: Difficulty,
        profile: EndgameProfile,
        history: List<Position>,
    ): SearchResult? {
        val state = MateSearchState(
            deadlineNanos =
                System.nanoTime() +
                    when (difficulty) {
                        Difficulty.EASY -> 160_000_000L
                        Difficulty.MEDIUM -> 320_000_000L
                        Difficulty.HARD -> 650_000_000L
                    },
            nodeLimit =
                when (difficulty) {
                    Difficulty.EASY -> 10_000
                    Difficulty.MEDIUM -> 35_000
                    Difficulty.HARD -> 90_000
                },
        )
        normalizedHistory(history, position).forEach { previous ->
            state.push(positionHash(previous), previous.sideToMove)
        }

        for (depth in 1..profile.mateSearchDepth) {
            try {
                val move = mateSearchRoot(position, profile, depth, state)
                if (move != null) {
                    return SearchResult(
                        move = move,
                        score = MATE_SCORE - depth,
                        depthReached = depth,
                        nodes = state.nodes,
                    )
                }
            } catch (_: SearchTimeout) {
                break
            }
        }
        return null
    }

    private fun mateSearchRoot(
        position: Position,
        profile: EndgameProfile,
        depth: Int,
        state: MateSearchState,
    ): Move? {
        val moves = generateLoneKingAttackerMoves(position, profile, state, root = true)
        val attackerWinner = winnerFor(profile.attacker)
        for (move in moves) {
            state.step()
            val next = engine.applyMove(position, move)
            val nextHash = positionHash(next)
            if (state.contains(nextHash, next.sideToMove)) {
                continue
            }
            if (engine.resolveWinner(next) == attackerWinner) {
                return move
            }
            state.push(nextHash, next.sideToMove)
            val forced = mateSearch(next, profile, depth - 1, state)
            state.pop()
            if (forced) {
                return move
            }
        }
        return null
    }

    private fun mateSearch(
        position: Position,
        profile: EndgameProfile,
        depth: Int,
        state: MateSearchState,
    ): Boolean {
        state.step()
        val winner = engine.resolveWinner(position)
        if (winner != null) {
            return winner == winnerFor(profile.attacker)
        }
        if (depth <= 0) {
            return false
        }

        val attackerToMove = position.sideToMove == profile.attacker
        val legalMoves =
            if (attackerToMove) {
                generateLoneKingAttackerMoves(position, profile, state, root = false)
            } else {
                engine.generateLegalMoves(position)
            }
        if (legalMoves.isEmpty()) {
            return !attackerToMove
        }

        return if (attackerToMove) {
            for (move in legalMoves) {
                val next = engine.applyMove(position, move)
                val nextHash = positionHash(next)
                if (state.contains(nextHash, next.sideToMove)) {
                    continue
                }
                state.push(nextHash, next.sideToMove)
                val forced = mateSearch(next, profile, depth - 1, state)
                state.pop()
                if (forced) {
                    return true
                }
            }
            false
        } else {
            for (move in legalMoves) {
                val next = engine.applyMove(position, move)
                val nextHash = positionHash(next)
                if (state.contains(nextHash, next.sideToMove)) {
                    return false
                }
                state.push(nextHash, next.sideToMove)
                val forced = mateSearch(next, profile, depth - 1, state)
                state.pop()
                if (!forced) {
                    return false
                }
            }
            true
        }
    }

    private fun generateLoneKingAttackerMoves(
        position: Position,
        profile: EndgameProfile,
        state: MateSearchState,
        root: Boolean,
    ): List<Move> {
        val defender = profile.defender
        val currentMobility = engine.generateLegalMoves(position, defender).size
        val scoredMoves =
            engine.generateLegalMoves(position)
                .map { move ->
                    val next = engine.applyMove(position, move)
                    val nextHash = positionHash(next)
                    val repeated = state.contains(nextHash, next.sideToMove)
                    val givesCheck = engine.isInCheck(next.board, defender)
                    val immediateWin = engine.resolveWinner(next) == winnerFor(profile.attacker)
                    val nextMobility = if (immediateWin) 0 else engine.generateLegalMoves(next, defender).size
                    ScoredLoneKingMove(
                        move = move,
                        givesCheck = givesCheck,
                        immediateWin = immediateWin,
                        score =
                            scoreLoneKingMove(
                                next = next,
                                move = move,
                                profile = profile,
                                currentMobility = currentMobility,
                                nextMobility = nextMobility,
                                repeated = repeated,
                                givesCheck = givesCheck,
                                immediateWin = immediateWin,
                            ),
                    )
                }.sortedByDescending { candidate -> candidate.score }

        val forcingMoves = scoredMoves.filter { candidate -> candidate.immediateWin || candidate.givesCheck }
        val quietQuota = if (root) 4 else 2
        val candidateCap = if (root) ROOT_LONE_KING_CANDIDATES else LONE_KING_CANDIDATES
        val selected =
            if (forcingMoves.isNotEmpty()) {
                forcingMoves.take(candidateCap) +
                    scoredMoves
                        .asSequence()
                        .filterNot { candidate -> candidate.immediateWin || candidate.givesCheck }
                        .take(quietQuota)
                        .toList()
            } else {
                scoredMoves.take(candidateCap)
            }
        return selected.distinctBy { candidate -> candidate.move.from * BOARD_SIZE + candidate.move.to }.map { candidate -> candidate.move }
    }

    private fun scoreLoneKingMove(
        next: Position,
        move: Move,
        profile: EndgameProfile,
        currentMobility: Int,
        nextMobility: Int,
        repeated: Boolean,
        givesCheck: Boolean,
        immediateWin: Boolean,
    ): Int {
        val attacker = profile.attacker
        val defender = profile.defender
        val defenderGeneral = engine.findGeneral(next.board, defender) ?: return if (immediateWin) MATE_SCORE else 0
        val movedType = PieceCodec.typeOf(move.movedPiece)
        val distance =
            abs(fileOf(move.to) - fileOf(defenderGeneral)) +
                abs(rankOf(move.to) - rankOf(defenderGeneral))

        var score = loneKingAttackBonus(next, attacker, defender) * 10
        score += (currentMobility - nextMobility) * 18_000
        score += palaceControlCount(next.board, attacker, defender) * 4_200
        score += maxOf(0, 12 - distance) * 800
        if (givesCheck) {
            score += 220_000
        }
        if (immediateWin) {
            score += 1_000_000
        }
        if (move.isCapture) {
            score += 24_000
        }
        if (repeated) {
            score -= 450_000
        }
        score += when (movedType) {
            PieceType.ROOK -> 9_000
            PieceType.CANNON -> 7_500
            PieceType.HORSE -> 7_000
            PieceType.PAWN -> 5_500
            PieceType.GENERAL -> -3_000
            PieceType.ADVISOR,
            PieceType.ELEPHANT,
            null,
            -> -2_000
        }
        return score
    }

    private fun palaceControlCount(
        board: IntArray,
        attacker: Side,
        defender: Side,
    ): Int {
        var count = 0
        val rankRange = if (defender == Side.RED) 7..9 else 0..2
        for (rank in rankRange) {
            for (file in 3..5) {
                if (engine.isSquareAttacked(board, squareOf(file, rank), attacker)) {
                    count += 1
                }
            }
        }
        return count
    }

    private fun winnerFor(side: Side): Winner = if (side == Side.RED) Winner.RED else Winner.BLACK

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

    private fun seedHistory(
        state: SearchState,
        history: List<Position>,
        current: Position,
    ) {
        val normalizedHistory = normalizedHistory(history, current)

        normalizedHistory.forEachIndexed { index, position ->
            val moveIntent =
                if (index == 0) {
                    null
                } else {
                    val move = position.lastMove ?: return@forEachIndexed
                    describeMove(normalizedHistory[index - 1], move, position)
                }
            state.push(position, positionHash(position), moveIntent)
        }
    }

    private fun normalizedHistory(
        history: List<Position>,
        current: Position,
    ): List<Position> =
        when {
            history.isEmpty() -> listOf(current)
            samePosition(history.last(), current) -> history
            else -> history + current
        }.takeLast(MAX_HISTORY_FRAMES)

    private fun describeMove(
        position: Position,
        move: Move,
        next: Position,
    ): MoveIntent {
        val moverSide = position.sideToMove
        val givesCheck = engine.isInCheck(next.board, next.sideToMove)
        val unprotectedVictimCount =
            engine.pseudoCapturesForPiece(next.board, move.to)
                .asSequence()
                .filter { capture -> PieceCodec.typeOf(capture.capturedPiece) != PieceType.GENERAL }
                .filter { capture ->
                    val victimSide = PieceCodec.sideOf(capture.capturedPiece) ?: return@filter false
                    !engine.isSquareAttacked(next.board, capture.to, victimSide)
                }
                .map { capture -> capture.to }
                .distinct()
                .count()
        return MoveIntent(
            moverSide = moverSide,
            givesCheck = givesCheck,
            unprotectedVictimCount = unprotectedVictimCount,
            irreversible = move.isCapture || PieceCodec.typeOf(move.movedPiece) == PieceType.PAWN,
        )
    }

    private fun classifyLoopScore(
        next: Position,
        nextHash: Long,
        moveIntent: MoveIntent,
        state: SearchState,
    ): Int? {
        val repeatIndex = state.findRepeatIndex(nextHash, next.sideToMove) ?: return null
        val cycleMoves = mutableListOf<MoveIntent>()
        for (index in repeatIndex + 1 until state.path.size) {
            val info = state.path[index].lastMoveIntent ?: continue
            if (info.irreversible) {
                return null
            }
            if (info.moverSide == moveIntent.moverSide) {
                cycleMoves += info
            }
        }
        cycleMoves += moveIntent
        if (cycleMoves.size < 2) {
            return null
        }

        val allChecks = cycleMoves.all { info -> info.givesCheck }
        if (allChecks) {
            return FORBIDDEN_LOOP_SCORE
        }

        val allSingleTargetChases = cycleMoves.all { info -> !info.givesCheck && info.unprotectedVictimCount == 1 }
        if (allSingleTargetChases) {
            return FORBIDDEN_LOOP_SCORE + CHASE_LOOP_OFFSET
        }

        val threatLoop = cycleMoves.any { info -> info.givesCheck || info.unprotectedVictimCount > 0 }
        return drawBias(next, moveIntent.moverSide, threatLoop)
    }

    private fun drawBias(
        position: Position,
        moverSide: Side,
        threatLoop: Boolean,
    ): Int {
        val moverPerspective = if (position.sideToMove == moverSide) evaluate(position) else -evaluate(position)
        val endgameProfile = detectEndgameProfile(position)
        if (endgameProfile?.defenderOnlyGeneral == true && endgameProfile.attacker == moverSide) {
            return -3_800 - moverPerspective / 5 - if (threatLoop) 650 else 220
        }
        return when {
            moverPerspective >= 500 -> -420 - moverPerspective / 8 - if (threatLoop) 120 else 0
            moverPerspective >= 180 -> -240 - moverPerspective / 10 - if (threatLoop) 80 else 0
            moverPerspective >= 0 -> -90 - if (threatLoop) 50 else 0
            moverPerspective <= -320 -> 35
            moverPerspective <= -120 -> 15
            else -> -20 - if (threatLoop) 25 else 0
        }
    }

    private data class SearchState(
        val deadlineNanos: Long,
        val nodeLimit: Int,
        var nodes: Int = 0,
        val path: MutableList<PathFrame> = mutableListOf(),
    ) {
        fun step() {
            nodes += 1
            if (nodes and 255 == 0) {
                if (nodes >= nodeLimit || System.nanoTime() >= deadlineNanos) {
                    throw SearchTimeout
                }
            }
        }

        fun push(position: Position, hash: Long, lastMoveIntent: MoveIntent?) {
            path += PathFrame(
                hash = hash,
                sideToMove = position.sideToMove,
                lastMoveIntent = lastMoveIntent,
            )
        }

        fun pop() {
            if (path.isNotEmpty()) {
                path.removeAt(path.lastIndex)
            }
        }

        fun findRepeatIndex(hash: Long, sideToMove: Side): Int? {
            for (index in path.lastIndex downTo 0) {
                val frame = path[index]
                if (frame.hash == hash && frame.sideToMove == sideToMove) {
                    return index
                }
            }
            return null
        }
    }

    private data class PathFrame(
        val hash: Long,
        val sideToMove: Side,
        val lastMoveIntent: MoveIntent?,
    )

    private data class EndgameProfile(
        val attacker: Side,
        val defender: Side,
        val defenderOnlyGeneral: Boolean,
        val extraDepth: Int,
        val extraNodeBudget: Int,
        val extraTimeMillis: Long,
        val mateSearchDepth: Int,
    )

    private data class ScoredLoneKingMove(
        val move: Move,
        val score: Int,
        val givesCheck: Boolean,
        val immediateWin: Boolean,
    )

    private data class MoveIntent(
        val moverSide: Side,
        val givesCheck: Boolean,
        val unprotectedVictimCount: Int,
        val irreversible: Boolean,
    )

    private data class MateSearchState(
        val deadlineNanos: Long,
        val nodeLimit: Int,
        var nodes: Int = 0,
        val path: MutableList<MateFrame> = mutableListOf(),
    ) {
        fun step() {
            nodes += 1
            if (nodes and 127 == 0) {
                if (nodes >= nodeLimit || System.nanoTime() >= deadlineNanos) {
                    throw SearchTimeout
                }
            }
        }

        fun push(hash: Long, sideToMove: Side) {
            path += MateFrame(hash = hash, sideToMove = sideToMove)
        }

        fun pop() {
            if (path.isNotEmpty()) {
                path.removeAt(path.lastIndex)
            }
        }

        fun contains(hash: Long, sideToMove: Side): Boolean =
            path.any { frame -> frame.hash == hash && frame.sideToMove == sideToMove }
    }

    private data class MateFrame(
        val hash: Long,
        val sideToMove: Side,
    )

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
        const val FORBIDDEN_LOOP_SCORE = -24_000
        const val CHASE_LOOP_OFFSET = 600
        const val MAX_HISTORY_FRAMES = 48
        const val MAX_PLY = 64
        const val LONE_KING_CANDIDATES = 8
        const val ROOT_LONE_KING_CANDIDATES = 12
    }
}

private fun Move.sameRoute(other: Move): Boolean = from == other.from && to == other.to
