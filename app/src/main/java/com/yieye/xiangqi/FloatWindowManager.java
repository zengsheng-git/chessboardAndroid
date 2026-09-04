package com.yieye.xiangqi;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class FloatWindowManager {
    private static FloatWindowManager instance;
    private WindowManager windowManager;
    private WindowManager.LayoutParams layoutParams;
    private View floatView;
    private TextView moveTextView;
    private Context context;

    // 棋盘镜像悬浮窗（桌面端完整棋盘 UI 的移动端等价物）
    public static final String PREFS_UI = "ui_prefs";
    public static final String KEY_BOARD_MIRROR = "board_mirror";
    public static final String KEY_BOARD_MIRROR_SIZE = "board_mirror_size";
    private static final float BOARD_SIZE_DEFAULT_DP = 260f;
    private static final float BOARD_SIZE_MIN_DP = 140f;
    private static final float BOARD_SIZE_MAX_DP = 440f;
    private View boardView;
    private BoardView boardContent;
    private WindowManager.LayoutParams boardParams;
    private float boardSizeDp = BOARD_SIZE_DEFAULT_DP;
    private float boardSizeMaxDp = BOARD_SIZE_MAX_DP;

    private FloatWindowManager(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    public static synchronized FloatWindowManager getInstance(Context context) {
        if (instance == null) {
            instance = new FloatWindowManager(context);
        }
        return instance;
    }

    public void show() {
        if (floatView != null) return;

        floatView = LayoutInflater.from(context).inflate(R.layout.float_window_layout, null);
        moveTextView = floatView.findViewById(R.id.float_move_text);

        layoutParams = buildOverlayParams();
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        layoutParams.x = 100;
        layoutParams.y = 100;

        floatView.setOnTouchListener(dragListener(layoutParams));
        windowManager.addView(floatView, layoutParams);
    }

    /** 棋盘镜像悬浮窗：默认停靠右上角，可拖动，双指捏合缩放（大小持久化） */
    public void showBoard() {
        if (boardView != null) return;

        boardView = LayoutInflater.from(context).inflate(R.layout.board_window_layout, null);
        boardContent = boardView.findViewById(R.id.board_mirror_view);

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        float density = metrics.density;
        boardSizeMaxDp = Math.min(BOARD_SIZE_MAX_DP, metrics.widthPixels / density - 24f);
        boardSizeDp = Math.max(BOARD_SIZE_MIN_DP,
                Math.min(context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                        .getFloat(KEY_BOARD_MIRROR_SIZE, BOARD_SIZE_DEFAULT_DP), boardSizeMaxDp));

        boardParams = buildOverlayParams();
        boardParams.gravity = Gravity.TOP | Gravity.START;
        applyBoardSize();
        boardParams.x = Math.max(16, metrics.widthPixels - boardParams.width - 16);
        boardParams.y = (int) (96 * density);

        boardView.setOnTouchListener(new BoardTouchListener());
        windowManager.addView(boardView, boardParams);
    }

    public void hideBoard() {
        if (boardView != null) {
            windowManager.removeView(boardView);
            boardView = null;
            boardContent = null;
        }
    }

    /** 按当前 boardSizeDp 设置窗口尺寸（容器 4dp padding，棋盘高宽比 1.12） */
    private void applyBoardSize() {
        float density = context.getResources().getDisplayMetrics().density;
        boardParams.width = Math.round(boardSizeDp * density);
        boardParams.height = Math.round(boardSizeDp * 1.12f * density);
    }

    private WindowManager.LayoutParams buildOverlayParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            params.type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        params.format = PixelFormat.RGBA_8888;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        return params;
    }

    /** 文字悬浮窗的拖动手势 */
    private View.OnTouchListener dragListener(WindowManager.LayoutParams params) {
        return new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(v, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.performClick();
                        return true;
                }
                return false;
            }
        };
    }

    /**
     * 棋盘窗手势：单指拖动 + 双指捏合缩放（140~440dp，随屏幕宽度上限钳制），
     * 松手持久化大小。缩放结束回到单指时以剩余指针为基准重置拖拽，避免跳变。
     */
    private class BoardTouchListener implements View.OnTouchListener {
        private static final int MODE_NONE = 0, MODE_DRAG = 1, MODE_SCALE = 2;
        private int mode = MODE_NONE;
        private int initialX, initialY;
        private float initialTouchX, initialTouchY;
        private float initialSpan, initialSize;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mode = MODE_DRAG;
                    initialX = boardParams.x;
                    initialY = boardParams.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() == 2) {
                        mode = MODE_SCALE;
                        initialSpan = pointerSpan(event);
                        initialSize = boardSizeDp;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mode == MODE_SCALE && event.getPointerCount() >= 2) {
                        float span = pointerSpan(event);
                        if (initialSpan > 0) {
                            boardSizeDp = Math.max(BOARD_SIZE_MIN_DP, Math.min(initialSize * span / initialSpan, boardSizeMaxDp));
                            applyBoardSize();
                            windowManager.updateViewLayout(v, boardParams);
                        }
                    } else if (mode == MODE_DRAG) {
                        boardParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        boardParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(v, boardParams);
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (mode == MODE_SCALE) {
                        // 剩余指针（抬起指针之外的另一个）继续作为拖拽基准；
                        // 视图随窗口移动，view 相对坐标 + 窗口位置 ≈ 屏幕坐标
                        int idx = event.getActionIndex() == 0 ? 1 : 0;
                        mode = MODE_DRAG;
                        initialX = boardParams.x;
                        initialY = boardParams.y;
                        initialTouchX = boardParams.x + event.getX(idx);
                        initialTouchY = boardParams.y + event.getY(idx);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mode == MODE_SCALE) {
                        context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE).edit()
                                .putFloat(KEY_BOARD_MIRROR_SIZE, boardSizeDp).apply();
                    }
                    mode = MODE_NONE;
                    v.performClick();
                    return true;
            }
            return false;
        }

        private float pointerSpan(MotionEvent event) {
            float dx = event.getX(0) - event.getX(1);
            float dy = event.getY(0) - event.getY(1);
            return (float) Math.hypot(dx, dy);
        }
    }

    public void updateMove(String move) {
        updateMove((CharSequence) move);
    }

    /** 支持富文本（Spannable 着色）的更新入口 */
    public void updateMove(CharSequence text) {
        if (moveTextView != null) {
            moveTextView.post(() -> moveTextView.setText(text));
        }
    }

    /**
     * 更新棋盘镜像：屏幕方向棋盘（Frame.board，数组底部=屏幕底部，直接绘制即镜像
     * 实际方向）+ 最优招/次优候选的 iccs 高亮（经 move2Point 转换）。
     * 棋盘窗未开启时静默忽略。可在任意线程调用。
     */
    public void updateBoard(String[][] board, boolean bottomIsRed, String bestMove, java.util.List<String> altMoves) {
        final BoardView v = boardContent;
        if (v == null) return;
        v.post(() -> v.setState(board, bottomIsRed, bestMove, altMoves));
    }

    public void hide() {
        hideBoard();
        if (floatView != null) {
            windowManager.removeView(floatView);
            floatView = null;
        }
    }
}
