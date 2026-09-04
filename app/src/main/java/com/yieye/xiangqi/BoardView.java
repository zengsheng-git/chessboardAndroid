package com.yieye.xiangqi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/**
 * 棋盘镜像悬浮窗的自绘棋盘（桌面端 Chessboard.vue"镜像 + 走子/候选高亮"的等价物）。
 * 输入为识别管线的屏幕方向数组（Frame.board：数组底部 = 真实屏幕底部，直接绘制即
 * 与实际棋盘方向一致，无需再翻转）；最优招（蓝）与次优候选（紫）的 iccs 高亮经
 * Utils.move2Point(move, bottomIsRed) 转换到同一数组坐标系。
 */
public class BoardView extends View {
    private static final int COLOR_BG = 0x80EAC98F;        // 木纹底（50% 透明，与文字悬浮框一致，透出真实棋盘）
    private static final int COLOR_LINE = 0xFF5D4037;      // 棋盘线
    private static final int COLOR_RED = 0xFFC62828;       // 红方棋子
    private static final int COLOR_BLACK = 0xFF263238;     // 黑方棋子
    private static final int COLOR_PIECE_BG = 0xFFFFF3D9;  // 棋子底色
    private static final int COLOR_RIVER = 0xFF8D6E63;     // 楚河汉界
    private static final int HL_BEST_FILL = 0x403498DB;    // 最优招高亮：3px 级描边 + 25% 底（桌面端 b/r-select）
    private static final int HL_BEST_LINE = 0xFF3498DB;
    private static final int HL_ALT_FILL = 0x339B59B6;     // 次优候选高亮：2px 级描边（桌面端 alt-select）
    private static final int HL_ALT_LINE = 0xFF9B59B6;

    private String[][] board;        // 归一化棋盘（红在下），null = 空棋盘
    private boolean bottomIsRed = true;
    private String bestMove;         // 最优招 iccs（如 h2e2）
    private List<String> altMoves;   // 次优候选 iccs

    private final Paint bgPaint = paint(COLOR_BG, Paint.Style.FILL, 0);
    private final Paint linePaint = paint(COLOR_LINE, Paint.Style.STROKE, 2f);
    private final Paint borderPaint = paint(COLOR_LINE, Paint.Style.STROKE, 3f);
    private final Paint riverPaint = paint(COLOR_RIVER, Paint.Style.FILL, 0);
    private final Paint pieceFillPaint = paint(COLOR_PIECE_BG, Paint.Style.FILL, 0);
    private final Paint pieceStrokePaint = paint(COLOR_BLACK, Paint.Style.STROKE, 2f);
    private final Paint pieceTextPaint = paint(COLOR_BLACK, Paint.Style.FILL, 0);
    private final Paint hlFillPaint = paint(0, Paint.Style.FILL, 0);
    private final Paint hlLinePaint = paint(0, Paint.Style.STROKE, 2f);

    public BoardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        riverPaint.setTextAlign(Paint.Align.CENTER);
        pieceTextPaint.setTextAlign(Paint.Align.CENTER);
        hlLinePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    private static Paint paint(int color, Paint.Style style, float width) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(style);
        p.setStrokeWidth(width);
        return p;
    }

    /** 更新棋盘与高亮；任意参数可为 null（清空对应元素）。可在任意线程调用 */
    public void setState(String[][] board, boolean bottomIsRed, String bestMove, List<String> altMoves) {
        if (board != null) {
            String[][] copy = new String[9][10];
            for (int x = 0; x < 9; x++) System.arraycopy(board[x], 0, copy[x], 0, 10);
            this.board = copy;
        } else {
            this.board = null;
        }
        this.bottomIsRed = bottomIsRed;
        this.bestMove = bestMove;
        this.altMoves = altMoves;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        // 线距 = cell；四周留 0.55 cell 边距（棋子半径 0.44 cell 不出界）
        float cell = Math.min(w / 9.1f, h / 10.1f);
        float ox = (w - 8 * cell) / 2f, oy = (h - 9 * cell) / 2f;

        canvas.drawRoundRect(new RectF(2, 2, w - 2, h - 2), 10, 10, bgPaint);

        // 高亮画在底色之上、网格与棋子之下（最优招边框加粗至格宽 12%，次优 5%，
        // 按格宽比例随缩放同步变化）
        drawMoveHighlight(canvas, ox, oy, cell, bestMove, HL_BEST_FILL, HL_BEST_LINE, 0.12f);
        if (altMoves != null) {
            for (String mv : altMoves) drawMoveHighlight(canvas, ox, oy, cell, mv, HL_ALT_FILL, HL_ALT_LINE, 0.05f);
        }

        // 横线 10 条
        for (int y = 0; y < 10; y++) {
            float[] a = pt(ox, oy, cell, 0, y), b = pt(ox, oy, cell, 8, y);
            canvas.drawLine(a[0], a[1], b[0], b[1], linePaint);
        }
        // 竖线：两侧贯通，中间列以楚河汉界为界断开
        for (int x = 0; x < 9; x++) {
            if (x == 0 || x == 8) {
                float[] a = pt(ox, oy, cell, x, 0), b = pt(ox, oy, cell, x, 9);
                canvas.drawLine(a[0], a[1], b[0], b[1], linePaint);
            } else {
                float[] a1 = pt(ox, oy, cell, x, 0), b1 = pt(ox, oy, cell, x, 4);
                float[] a2 = pt(ox, oy, cell, x, 5), b2 = pt(ox, oy, cell, x, 9);
                canvas.drawLine(a1[0], a1[1], b1[0], b1[1], linePaint);
                canvas.drawLine(a2[0], a2[1], b2[0], b2[1], linePaint);
            }
        }
        // 九宫斜线
        drawSeg(canvas, ox, oy, cell, 3, 0, 5, 2);
        drawSeg(canvas, ox, oy, cell, 5, 0, 3, 2);
        drawSeg(canvas, ox, oy, cell, 3, 7, 5, 9);
        drawSeg(canvas, ox, oy, cell, 5, 7, 3, 9);
        // 外框
        float[] c0 = pt(ox, oy, cell, 0, 0), c1 = pt(ox, oy, cell, 8, 9);
        float m = cell * 0.13f;
        canvas.drawRect(new RectF(Math.min(c0[0], c1[0]) - m, Math.min(c0[1], c1[1]) - m,
                Math.max(c0[0], c1[0]) + m, Math.max(c0[1], c1[1]) + m), borderPaint);
        // 楚河汉界
        riverPaint.setTextSize(cell * 0.5f);
        float[] r1 = pt(ox, oy, cell, 2f, 4.5f), r2 = pt(ox, oy, cell, 6f, 4.5f);
        float toff = (riverPaint.ascent() + riverPaint.descent()) / 2f;
        canvas.drawText("楚  河", r1[0], r1[1] - toff, riverPaint);
        canvas.drawText("汉  界", r2[0], r2[1] - toff, riverPaint);

        // 棋子
        if (board != null) {
            float radius = cell * 0.44f;
            pieceTextPaint.setTextSize(cell * 0.52f);
            float ptoff = (pieceTextPaint.ascent() + pieceTextPaint.descent()) / 2f;
            for (int x = 0; x < 9; x++) {
                for (int y = 0; y < 10; y++) {
                    String name = board[x][y];
                    if (name == null || name.length() < 2) continue;
                    boolean red = name.startsWith("r");
                    float[] p = pt(ox, oy, cell, x, y);
                    pieceStrokePaint.setColor(red ? COLOR_RED : COLOR_BLACK);
                    canvas.drawCircle(p[0], p[1], radius, pieceFillPaint);
                    canvas.drawCircle(p[0], p[1], radius, pieceStrokePaint);
                    String ch = Utils.nameToChina(getContext(), name);
                    if (ch == null || ch.isEmpty()) continue;
                    pieceTextPaint.setColor(red ? COLOR_RED : COLOR_BLACK);
                    canvas.drawText(ch, p[0], p[1] - ptoff, pieceTextPaint);
                }
            }
        }
    }

    /** 高亮一步招法的起点与终点（iccs 4 字符，如 h2e2）；borderScale 为相对格宽的边框粗细 */
    private void drawMoveHighlight(Canvas c, float ox, float oy, float cell, String mv, int fill, int line, float borderScale) {
        if (mv == null || mv.length() < 4) return;
        hlFillPaint.setColor(fill);
        hlLinePaint.setColor(line);
        hlLinePaint.setStrokeWidth(cell * borderScale);
        for (int i = 0; i < 2; i++) {
            String sq = mv.substring(i * 2, i * 2 + 2);
            android.graphics.Point p = Utils.move2Point(sq, bottomIsRed);
            if (p.x < 0 || p.x > 8 || p.y < 0 || p.y > 9) continue;
            float[] pt = pt(ox, oy, cell, p.x, p.y);
            float s = cell * 0.46f;
            RectF rect = new RectF(pt[0] - s, pt[1] - s, pt[0] + s, pt[1] + s);
            c.drawRoundRect(rect, 6, 6, hlFillPaint);
            c.drawRoundRect(rect, 6, 6, hlLinePaint);
        }
    }

    private void drawSeg(Canvas c, float ox, float oy, float cell, int x1, int y1, int x2, int y2) {
        float[] a = pt(ox, oy, cell, x1, y1), b = pt(ox, oy, cell, x2, y2);
        c.drawLine(a[0], a[1], b[0], b[1], linePaint);
    }

    /** 棋盘坐标（x:0-8, y:0-9，数组即屏幕方向）→ 视图像素坐标，直接绘制不翻转 */
    private float[] pt(float ox, float oy, float cell, float x, float y) {
        return new float[]{ox + x * cell, oy + y * cell};
    }
}
