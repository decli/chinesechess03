package com.decli.chinesechess.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.decli.chinesechess.BuildConfig
import com.decli.chinesechess.debug.DebugLogger
import com.decli.chinesechess.game.BOARD_FILES
import com.decli.chinesechess.game.BOARD_RANKS
import com.decli.chinesechess.game.Difficulty
import com.decli.chinesechess.game.GameEvent
import com.decli.chinesechess.game.GameUiState
import com.decli.chinesechess.game.GameViewModel
import com.decli.chinesechess.game.Move
import com.decli.chinesechess.game.PieceCodec
import com.decli.chinesechess.game.Position
import com.decli.chinesechess.game.Side
import com.decli.chinesechess.game.fileOf
import com.decli.chinesechess.game.rankOf
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ChineseChessApp(
    viewModel: GameViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val latestState by rememberUpdatedState(state)
    val feedbackController = remember(context) { FeedbackController(context) }
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsGranted = granted
    }

    DisposableEffect(Unit) {
        onDispose {
            feedbackController.release()
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is GameEvent.PlayMoveSound -> if (latestState.soundEnabled) {
                    feedbackController.playMove(event.side, event.capture)
                }
                is GameEvent.Speak -> if (latestState.ttsEnabled) {
                    feedbackController.speak(event.text, event.clips)
                }
                is GameEvent.Notify -> if (latestState.notificationsEnabled) {
                    feedbackController.notify(event.text, notificationsGranted)
                }
                GameEvent.ExportDebugLog -> {
                    val file = DebugLogger.export(context)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${BuildConfig.APPLICATION_ID}.fileprovider",
                        file,
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "象棋乐斗调试日志")
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "导出调试日志"))
                    Toast.makeText(context, "已生成调试日志，可直接发给我。", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val colorScheme = androidx.compose.material3.lightColorScheme(
        primary = Color(0xFF8E2E18),
        secondary = Color(0xFF496053),
        tertiary = Color(0xFF8B6B3C),
        background = Color(0xFFD6DAD0),
        surface = Color(0xFFF3EFE5),
        onPrimary = Color.White,
        onSecondary = Color.White,
        onBackground = Color(0xFF2A1A12),
        onSurface = Color(0xFF2A1A12),
    )

    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            ChessScreen(
                state = state,
                notificationsGranted = notificationsGranted,
                onSquareTapped = viewModel::onSquareTapped,
                onUndo = viewModel::undoLastTurn,
                onRestart = viewModel::restartGame,
                onHint = viewModel::requestHint,
                onDifficultyChange = viewModel::setDifficulty,
                onToggleSound = viewModel::toggleSound,
                onToggleTts = viewModel::toggleTts,
                onExportLog = viewModel::exportDebugLog,
                onToggleNotifications = {
                    if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    viewModel.toggleNotifications()
                },
            )
        }
    }
}

@Composable
private fun ChessScreen(
    state: GameUiState,
    notificationsGranted: Boolean,
    onSquareTapped: (Int) -> Unit,
    onUndo: () -> Unit,
    onRestart: () -> Unit,
    onHint: () -> Unit,
    onDifficultyChange: (Difficulty) -> Unit,
    onToggleSound: () -> Unit,
    onToggleTts: () -> Unit,
    onExportLog: () -> Unit,
    onToggleNotifications: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp >= configuration.screenHeightDp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFD7DBD2), Color(0xFFB4BCAF)),
                ),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (landscape) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    ChessBoard(
                        position = state.position,
                        selectedSquare = state.selectedSquare,
                        legalTargets = state.legalTargets,
                        hintMove = state.hintMove,
                        onSquareTapped = onSquareTapped,
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                ControlPanel(
                    state = state,
                    notificationsGranted = notificationsGranted,
                    onUndo = onUndo,
                    onRestart = onRestart,
                    onHint = onHint,
                    onDifficultyChange = onDifficultyChange,
                    onToggleSound = onToggleSound,
                    onToggleTts = onToggleTts,
                    onExportLog = onExportLog,
                    onToggleNotifications = onToggleNotifications,
                    modifier = Modifier.width(332.dp),
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    ChessBoard(
                        position = state.position,
                        selectedSquare = state.selectedSquare,
                        legalTargets = state.legalTargets,
                        hintMove = state.hintMove,
                        onSquareTapped = onSquareTapped,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                ControlPanel(
                    state = state,
                    notificationsGranted = notificationsGranted,
                    onUndo = onUndo,
                    onRestart = onRestart,
                    onHint = onHint,
                    onDifficultyChange = onDifficultyChange,
                    onToggleSound = onToggleSound,
                    onToggleTts = onToggleTts,
                    onExportLog = onExportLog,
                    onToggleNotifications = onToggleNotifications,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ControlPanel(
    state: GameUiState,
    notificationsGranted: Boolean,
    onUndo: () -> Unit,
    onRestart: () -> Unit,
    onHint: () -> Unit,
    onDifficultyChange: (Difficulty) -> Unit,
    onToggleSound: () -> Unit,
    onToggleTts: () -> Unit,
    onExportLog: () -> Unit,
    onToggleNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F0E6)),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = state.winner?.title ?: if (state.position.sideToMove == Side.RED) "轮到你走" else "电脑回合",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                )
                Text(
                    text = state.banner,
                    fontSize = 19.sp,
                    lineHeight = 28.sp,
                    color = Color(0xFF533524),
                )
                if (state.aiThinking) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        Text("电脑推演中", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoPill("自动续局")
                    InfoPill("已下${state.historyDepth}步")
                    InfoPill("大字号")
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF6F2E9)),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("难度", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Difficulty.entries.forEach { difficulty ->
                        FilterChip(
                            selected = state.difficulty == difficulty,
                            onClick = { onDifficultyChange(difficulty) },
                            label = { Text(difficulty.title, fontSize = 18.sp) },
                        )
                    }
                }
                if (!notificationsGranted && state.notificationsEnabled) {
                    Text(
                        text = "通知权限未打开，机器人语音通知只会在应用内播报。",
                        color = Color(0xFF8E2E18),
                        fontSize = 16.sp,
                    )
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF6F2E9)),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile("悔棋", Icons.AutoMirrored.Filled.Redo, state.canUndo, onUndo, Modifier.weight(1f))
                    ActionTile("提示", Icons.Default.TipsAndUpdates, !state.aiThinking && state.winner == null, onHint, Modifier.weight(1f))
                    ActionTile("新局", Icons.Default.Refresh, true, onRestart, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToggleTile("音效", Icons.AutoMirrored.Filled.VolumeUp, state.soundEnabled, onToggleSound, Modifier.weight(1f))
                    ToggleTile("语音", Icons.Default.RecordVoiceOver, state.ttsEnabled, onToggleTts, Modifier.weight(1f))
                    ToggleTile("通知", Icons.Default.NotificationsActive, state.notificationsEnabled, onToggleNotifications, Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("当前对局会自动保存", fontSize = 16.sp, color = Color(0xFF5B6259))
                    TextButton(onClick = onExportLog) {
                        androidx.compose.material3.Icon(
                            Icons.Default.BugReport,
                            contentDescription = "导出日志",
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("导出日志", fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoPill(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE5E0D2)),
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            fontSize = 14.sp,
            color = Color(0xFF564235),
        )
    }
}

@Composable
private fun ActionTile(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .height(92.dp)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) Color(0xFFE8DFC8) else Color(0xFFD2CEC3),
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.Icon(icon, contentDescription = title, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ToggleTile(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .height(92.dp)
            .border(
                width = 2.dp,
                color = if (enabled) Color(0xFF385B48) else Color.Transparent,
                shape = RoundedCornerShape(24.dp),
            )
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF4EFE4)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.Icon(icon, contentDescription = title, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text("$title ${if (enabled) "开" else "关"}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ChessBoard(
    position: Position,
    selectedSquare: Int?,
    legalTargets: Set<Int>,
    hintMove: Move?,
    onSquareTapped: (Int) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxHeight()
            .aspectRatio(0.9f),
    ) {
        val metrics = rememberBoardMetrics(
            widthPx = constraints.maxWidth.toFloat(),
            heightPx = constraints.maxHeight.toFloat(),
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(position, selectedSquare, legalTargets, hintMove) {
                    detectTapGestures { offset ->
                        metrics.squareAt(offset.x, offset.y)?.let(onSquareTapped)
                    }
                },
        ) {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFE7C98D), Color(0xFFD3A864)),
                ),
                cornerRadius = CornerRadius(38f, 38f),
            )

            drawBoardLines(metrics)

            position.lastMove?.let { move ->
                drawMarker(metrics.centerOf(move.from), Color(0x66385B48), metrics.cell * 0.28f)
                drawMarker(metrics.centerOf(move.to), Color(0x6656A36C), metrics.cell * 0.28f)
            }
            hintMove?.let { move ->
                drawCircle(
                    color = Color(0x888E2E18),
                    radius = metrics.cell * 0.44f,
                    center = metrics.centerOf(move.from),
                    style = Stroke(width = metrics.cell * 0.08f),
                )
                drawCircle(
                    color = Color(0x888E2E18),
                    radius = metrics.cell * 0.44f,
                    center = metrics.centerOf(move.to),
                    style = Stroke(width = metrics.cell * 0.08f),
                )
            }
            if (selectedSquare != null) {
                drawCircle(
                    color = Color(0xAAE8A33A),
                    radius = metrics.cell * 0.47f,
                    center = metrics.centerOf(selectedSquare),
                    style = Stroke(width = metrics.cell * 0.1f),
                )
            }
            legalTargets.forEach { target ->
                drawMarker(metrics.centerOf(target), Color(0x8856A36C), metrics.cell * 0.16f)
            }

            for (square in 0 until position.board.size) {
                val piece = position.board[square]
                if (piece == 0) {
                    continue
                }
                drawPiece(metrics, square, piece)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBoardLines(metrics: BoardMetrics) {
    val boardColor = Color(0xFF5A3215)
    for (file in 0 until BOARD_FILES) {
        val x = metrics.originX + metrics.margin + file * metrics.cell
        drawLine(
            color = boardColor,
            start = Offset(x, metrics.originY + metrics.margin),
            end = Offset(x, metrics.originY + metrics.margin + 4 * metrics.cell),
            strokeWidth = metrics.stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = boardColor,
            start = Offset(x, metrics.originY + metrics.margin + 5 * metrics.cell),
            end = Offset(x, metrics.originY + metrics.margin + 9 * metrics.cell),
            strokeWidth = metrics.stroke,
            cap = StrokeCap.Round,
        )
    }
    for (rank in 0 until BOARD_RANKS) {
        val y = metrics.originY + metrics.margin + rank * metrics.cell
        drawLine(
            color = boardColor,
            start = Offset(metrics.originX + metrics.margin, y),
            end = Offset(metrics.originX + metrics.margin + 8 * metrics.cell, y),
            strokeWidth = metrics.stroke,
            cap = StrokeCap.Round,
        )
    }

    fun palaceLine(startFile: Int, startRank: Int, endFile: Int, endRank: Int) {
        drawLine(
            color = boardColor,
            start = metrics.centerOf(startFile, startRank),
            end = metrics.centerOf(endFile, endRank),
            strokeWidth = metrics.stroke,
        )
    }
    palaceLine(3, 0, 5, 2)
    palaceLine(5, 0, 3, 2)
    palaceLine(3, 7, 5, 9)
    palaceLine(5, 7, 3, 9)

    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#7B4820")
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = metrics.cell * 0.62f
            isFakeBoldText = true
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
        }
        drawText(
            "楚 河",
            metrics.originX + metrics.boardWidth * 0.28f,
            metrics.originY + metrics.margin + 4.78f * metrics.cell,
            paint,
        )
        drawText(
            "汉 界",
            metrics.originX + metrics.boardWidth * 0.72f,
            metrics.originY + metrics.margin + 4.78f * metrics.cell,
            paint,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPiece(
    metrics: BoardMetrics,
    square: Int,
    piece: Int,
) {
    val center = metrics.centerOf(square)
    val pieceColor = if (piece > 0) Color(0xFFF8F3E2) else Color(0xFFF5E8D3)
    val textColor = if (piece > 0) Color(0xFFB5311D) else Color(0xFF2A1A12)
    drawCircle(
        color = Color(0x22000000),
        radius = metrics.pieceRadius,
        center = center + Offset(metrics.cell * 0.03f, metrics.cell * 0.04f),
    )
    drawCircle(color = pieceColor, radius = metrics.pieceRadius, center = center)
    drawCircle(
        color = Color(0xFFAE8952),
        radius = metrics.pieceRadius,
        center = center,
        style = Stroke(width = metrics.cell * 0.08f),
    )
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            color = textColor.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = metrics.cell * 0.56f
            isFakeBoldText = true
            typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD)
        }
        val baseline = center.y - (paint.descent() + paint.ascent()) / 2
        drawText(PieceCodec.displayLabel(piece), center.x, baseline, paint)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMarker(
    center: Offset,
    color: Color,
    radius: Float,
) {
    drawCircle(color = color, radius = radius, center = center)
}

@Composable
private fun rememberBoardMetrics(
    widthPx: Float,
    heightPx: Float,
): BoardMetrics {
    val boardHeight = heightPx
    val boardWidth = min(widthPx, boardHeight * 0.9f)
    val cell = min(boardWidth / 9f, boardHeight / 10f)
    val computedWidth = cell * 9f
    val computedHeight = cell * 10f
    val originX = (widthPx - computedWidth) / 2f
    val originY = (heightPx - computedHeight) / 2f
    return remember(widthPx, heightPx) {
        BoardMetrics(
            originX = originX,
            originY = originY,
            boardWidth = computedWidth,
            boardHeight = computedHeight,
            margin = cell / 2f,
            cell = cell,
            stroke = cell * 0.045f,
            pieceRadius = cell * 0.42f,
        )
    }
}

private data class BoardMetrics(
    val originX: Float,
    val originY: Float,
    val boardWidth: Float,
    val boardHeight: Float,
    val margin: Float,
    val cell: Float,
    val stroke: Float,
    val pieceRadius: Float,
) {
    fun centerOf(square: Int): Offset = centerOf(fileOf(square), rankOf(square))

    fun centerOf(file: Int, rank: Int): Offset =
        Offset(originX + margin + file * cell, originY + margin + rank * cell)

    fun squareAt(x: Float, y: Float): Int? {
        val normalizedX = ((x - originX - margin) / cell).roundToInt()
        val normalizedY = ((y - originY - margin) / cell).roundToInt()
        if (normalizedX !in 0 until BOARD_FILES || normalizedY !in 0 until BOARD_RANKS) {
            return null
        }
        val center = centerOf(normalizedX, normalizedY)
        val dx = x - center.x
        val dy = y - center.y
        if (dx * dx + dy * dy > cell * cell * 0.42f * 0.42f * 4f) {
            return null
        }
        return normalizedY * BOARD_FILES + normalizedX
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).roundToInt(),
    (red * 255).roundToInt(),
    (green * 255).roundToInt(),
    (blue * 255).roundToInt(),
)
