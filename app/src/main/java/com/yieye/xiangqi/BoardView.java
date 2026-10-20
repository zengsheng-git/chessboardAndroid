package com.yieye.xiangqi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/**
 * 棋盘镜像悬浮窗的自绘棋盘（桌面端 Chessboard.vue"镜像 + 招法箭头"的等价物）。
 * 输入为识别管线的屏幕方向数组（Frame.board：数组底部 = 真实屏幕底部，直接绘制即
 * 与实际棋盘方向一致，无需再翻转）；最优招与次优候选的 iccs 经 Utils.move2Point(move,
 * bottomIsRed) 转换到同一数组坐标系，以箭头绘制（对齐桌面端 src/arrows.ts）：
 * 最优按行棋方着色（红方行棋=红箭头、黑方行棋=蓝箭头），次优为紫色细箭头。
 */
public class BoardView extends View {
    private static final int COLOR_BG = 0x80EAC98F;        // 木纹底（50% 透明，与文字悬浮框一致，透出真实棋盘）
    private static final int COLOR_LINE = 0xFF5D4037;      // 棋盘线
    private static final int COLOR_RED = 0xFFC62828;       // 红方棋子
    private static final int COLOR_BLACK = 0xFF263238;     // 黑方棋子
    private static final int COLOR_PIECE_BG = 0xFFFFF3D9;  // 棋子底色
    private static final int COLOR_RIVER = 0xFF8D6E63;     // 楚河汉界
    // 招法箭头（桌面端 #e74c3c/#3498db/#9b59b6, 整体 85% 不透明度）
    private static final int ARROW_BEST_RED = 0xD9E74C3C;  // 最优招: 红方行棋
    private static final int ARROW_BEST_BLUE = 0xD93498DB; // 最优招: 黑方行棋
    private static final int ARROW_ALT = 0xD99B59B6;       // 次优候选

    private String[][] board;        // 归一化棋盘（红在下），null = 空棋盘
    private boolean bottomIsRed = true;
    private String bestMove;         // 最优招 iccs（如 h2e2）
    private List<String> altMoves;   // 次优候选 iccs
    private boolean redToMove = true; // 最优招的行棋方（true=红方），决定箭头颜色

    private final Paint bgPaint = paint(COLOR_BG, Paint.Style.FILL, 0);
    private final Paint linePaint = paint(COLOR_LINE, Paint.Style.STROKE, 2f);
    private final Paint borderPaint = paint(COLOR_LINE, Paint.Style.STROKE, 3f);
    private final Paint riverPaint = paint(COLOR_RIVER, Paint.Style.FILL, 0);
    private final Paint pieceFillPaint = paint(COLOR_PIECE_BG, Paint.Style.FILL, 0);
    private final Paint pieceStrokePaint = paint(COLOR_BLACK, Paint.Style.STROKE, 2f);
    private final Paint pieceTextPaint = paint(COLOR_BLACK, Paint.Style.FILL, 0);
    private final Paint arrowPaint = paint(0, Paint.Style.STROKE, 0);
    private final Paint arrowHeadPaint = paint(0, Paint.Style.FILL, 0);
    private final Path arrowHeadPath = new Path();

    public BoardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        riverPaint.setTextAlign(Paint.Align.CENTER);
        pieceTextPaint.setTextAlign(Paint.Align.CENTER);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    private static Paint paint(int color, Paint.Style style, float width) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(style);
        p.setStrokeWidth(width);
        return p;
    }

    /** 更新棋盘与提示箭头；任意参数可为 null（清空对应元素）。可在任意线程调用 */
    public void setState(String[][] board, boolean bottomIsRed, String bestMove, List<String> altMoves, boolean redToMove) {
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
        this.redToMove = redToMove;
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

        // 箭头叠在棋子上方（对齐桌面端 z-index 布局）：最优招按行棋方红/蓝着色，次优紫细箭头
        drawMoveArrow(canvas, ox, oy, cell, bestMove, redToMove ? ARROW_BEST_RED : ARROW_BEST_BLUE, 0.125f, 0.35f, 0.20f);
        if (altMoves != null) {
            for (String mv : altMoves) drawMoveArrow(canvas, ox, oy, cell, mv, ARROW_ALT, 0.0875f, 0.275f, 0.1625f);
        }
    }

    /**
     * 以箭头绘制一步招法（iccs 4 字符，如 h2e2）：起点交点 -> 终点交点。
     * 箭尾从起点棋子边缘让开（0.30 cell），箭尖直达终点交点（盖在落点棋子上，去向一目了然）；
     * stroke/head/half 为箭杆粗细、箭头长度与半宽相对格距的比例（对齐桌面端 5/40、14/40、8/40）。
     */
    private void drawMoveArrow(Canvas c, float ox, float oy, float cell, String mv, int color,
                               float stroke, float head, float half) {
        if (mv == null || mv.length() < 4) return;
        android.graphics.Point from = Utils.move2Point(mv.substring(0, 2), bottomIsRed);
        android.graphics.Point to = Utils.move2Point(mv.substring(2, 4), bottomIsRed);
        if (from.x < 0 || from.x > 8 || from.y < 0 || from.y > 9) return;
        if (to.x < 0 || to.x > 8 || to.y < 0 || to.y > 9) return;
        float[] a = pt(ox, oy, cell, from.x, from.y);
        float[] b = pt(ox, oy, cell, to.x, to.y);
        float dx = b[0] - a[0], dy = b[1] - a[1];
        double len = Math.hypot(dx, dy);
        if (len < cell * 0.5) return; // 起落点重合或过近，不绘制
        float ux = (float) (dx / len), uy = (float) (dy / len);
        float px = -uy, py = ux;
        float tailX = a[0] + ux * cell * 0.30f, tailY = a[1] + uy * cell * 0.30f;
        float headLen = cell * head;
        // 箭杆略探入箭头底部，避免杆头与三角间留缝
        float endX = b[0] - ux * (headLen - cell * 0.05f), endY = b[1] - uy * (headLen - cell * 0.05f);
        float baseX = b[0] - ux * headLen, baseY = b[1] - uy * headLen;
        float hw = cell * half;
        arrowPaint.setColor(color);
        arrowPaint.setStrokeWidth(cell * stroke);
        c.drawLine(tailX, tailY, endX, endY, arrowPaint);
        arrowHeadPath.rewind();
        arrowHeadPath.moveTo(b[0], b[1]);
        arrowHeadPath.lineTo(baseX + px * hw, baseY + py * hw);
        arrowHeadPath.lineTo(baseX - px * hw, baseY - py * hw);
        arrowHeadPath.close();
        arrowHeadPaint.setColor(color);
        c.drawPath(arrowHeadPath, arrowHeadPaint);
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
