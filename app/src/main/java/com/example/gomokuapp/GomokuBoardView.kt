package com.example.gomokuapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class GomokuBoardView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val boardSize = 15
    private val linePaint = Paint().apply {
        color = Color.DKGRAY
        strokeWidth = 2f
    }
    private val blackStonePaint = Paint().apply { color = Color.BLACK }
    private val whiteStonePaint = Paint().apply { color = Color.WHITE }

    // 石のデータ
    var moves: List<Move> = emptyList()
    var player1Id: String? = null

    // タッチされた時に呼び出す連絡先
    var onCellClicked: ((Int, Int) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cellSize = w / boardSize

        // 背景色 (肌色)
        canvas.drawColor(Color.rgb(210, 180, 140))

        // 線を引く
        for (i in 0 until boardSize) {
            val pos = i * cellSize + cellSize / 2
            canvas.drawLine(pos, cellSize / 2, pos, h - cellSize / 2, linePaint)
            canvas.drawLine(cellSize / 2, pos, w - cellSize / 2, pos, linePaint)
        }

        // 石を描く
        moves.forEach { move ->
            val cx = move.x * cellSize + cellSize / 2
            val cy = move.y * cellSize + cellSize / 2
            val radius = cellSize * 0.4f

            val paint = if (move.playerId == player1Id) blackStonePaint else whiteStonePaint
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val w = width.toFloat()
            val cellSize = w / boardSize

            val x = (event.x / cellSize).toInt()
            val y = (event.y / cellSize).toInt()

            if (x in 0 until boardSize && y in 0 until boardSize) {
                onCellClicked?.invoke(x, y)
            }
        }
        return true
    }
}