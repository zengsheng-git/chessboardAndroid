package com.yieye.xiangqi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import java.nio.ByteBuffer;
import java.util.HashMap;

public class AnalysisService extends Service {
    private static final String TAG = "AnalysisService";
    private static final String CHANNEL_ID = "AnalysisChannel";

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    // 分析循环整体跑在独立的 HandlerThread 上（参考 chessboard 原型的单线程循环），
    // 识别/确认/启动分析串行执行，主线程只负责服务生命周期，不再被推理阻塞
    private HandlerThread analysisThread;
    private Handler handler;
    private WindowManager windowManager;
    private int screenWidth, screenHeight, screenDensity;

    private String lastFen = "";
    private String lastResult = "";
    private String[][] lastBoardArray = null;
    private String nextTurn = "w";
    private volatile String mySide = null; // 屏幕底部方颜色，由识别帧更新，JNI 回调线程读取
    private EngineHelper engineHelper;
    private boolean isRunning = false;
    private volatile boolean isAnalyzing = false;
    private volatile long lastAnalyzeStartTime = 0;
    // 预期棋盘（参考 chessboard 的 expect_board）：分析完成后用 bestMove 推演出的局面，
    // 对手按预期走子时直接命中 → 行棋方零猜测，且免去稳定确认的等待
    private volatile String expectBoardFen = null;
    private volatile String expectNextTurn = null;
    private long[] lastCroppedHash = null; // 上一次裁剪图片的感知哈希
    private boolean hasLastCroppedHash = false;
    private int noBoardCount = 0; // 连续识别失败计数（无棋子/无棋盘）
    private static final int NO_BOARD_RESET_THRESHOLD = 3; // 连续失败达到该值后重置棋盘定位缓存
    private static final int PHASH_SIZE = 32; // 感知哈希采样网格 32x32 = 1024 bit
    private static final int PHASH_THRESHOLD = 2; // 汉明距离阈值，小于等于此值认为相同
    private int calcDepth = 14;
    private static final long CONFIRM_INTERVAL_MS = 200; // 局面稳定确认的复抓间隔 (Windows: confirm_interval)
    private static final long RELEASE_DELAY_MS = 200; // 分析结果回调后解锁下一轮分析的延迟

    private volatile boolean stopTriggeredForCurrentAnalysis = false;
    private static final int STOP_SCORE = 2000; // 分数超过该阈值提前终止搜索 (Windows: StopScore)
    private static final double ENGINE_STEP_TIME_SEC = 5.0; // 每步引擎最长思考时间 (Windows: EngineStepTime)

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, getNotification());

        windowManager = (WindowManager) getApplicationContext().getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.getCurrentWindowMetrics(); // Warming up
        }
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        analysisThread = new HandlerThread("YiEyeAnalysis");
        analysisThread.start();
        handler = new Handler(analysisThread.getLooper());

        engineHelper = new EngineHelper(this, new HashMap<>());
        engineHelper.bestMoveEvent = (sourceFen, bestMove, ponderMove) -> {
            long engineTime = System.currentTimeMillis() - lastAnalyzeStartTime;
            if (sourceFen != null && !sourceFen.isEmpty()) {
                // 生成预期棋盘：bestMove 走完后的局面 + 轮到对方。
                // 对手照着走时下一帧直接命中，行棋方无需再从画面 diff 推导
                String expectFen = Utils.applyMoveToFen(sourceFen, bestMove);
                if (expectFen != null && expectFen.contains(" ")) {
                    expectBoardFen = expectFen.split(" ")[0];
                    expectNextTurn = expectFen.split(" ")[1];
                } else {
                    expectBoardFen = null;
                    expectNextTurn = null;
                }

                // 记谱需要"屏幕底部方"视角（不是行棋方），否则黑方行棋时中文记谱错位
                boolean bottomIsRed = (mySide == null) || mySide.equals("w");
                String chinaMove = (bestMove != null && bestMove.length() >= 4) ? Utils.fenToChina(this, sourceFen, bestMove, bottomIsRed) : getString(R.string.unknown);
                String chinaMove2 = (ponderMove != null && ponderMove.length() >= 4) ? Utils.fenToChina(this, sourceFen, ponderMove, bottomIsRed) : getString(R.string.none_ponder);

                String turnStr = sourceFen.contains(" w ") ? getString(R.string.turn_red) : getString(R.string.turn_black);
                String displayStr = turnStr + "\n" +
                        getString(R.string.recommend) + chinaMove + " (" + (bestMove != null ? bestMove : "none") + ") + " + getString(R.string.time_consumed) + engineTime/100/10f + "s\n" +
                        getString(R.string.ponder) + chinaMove2 + " (" + (ponderMove != null ? ponderMove : "none") + ")";
                LogUtil.d(TAG, getString(R.string.log_best_move, engineTime, displayStr));

                lastResult = displayStr;
                // 1. 更新悬浮窗
                FloatWindowManager.getInstance(this).updateMove(displayStr);

                // 2. 发送广播给 Activity
                Intent intentBroadcast = new Intent("com.example.CHESS_RESULT");
                intentBroadcast.putExtra("displayStr", displayStr);
                sendBroadcast(intentBroadcast);
            }
            // 短暂延迟后再允许下一次分析，给 UI 留出反应时间并降低功耗
            handler.postDelayed(() -> isAnalyzing = false, RELEASE_DELAY_MS);
        };
        engineHelper.infoEvent = (cmd, infos) -> {
            // 对齐 Windows StopScore/StopWhenMate：一旦分数已经足够大或已经出现绝杀，
            // 不必再等满深度/满时间，直接提前终止搜索并使用当前的 pv 作为结果。
            // 注意：score 键在 "score mate N" 时存的是本地化字符串（parseInt 会抛异常被吞掉，无害），
            // mate 的步数在独立的 "mate" 键里，始终是纯数字。
            if (stopTriggeredForCurrentAnalysis) return;
            try {
                if (infos.containsKey("mate")) {
                    if (Integer.parseInt(infos.get("mate")) > 0) {
                        stopTriggeredForCurrentAnalysis = true;
                        LogUtil.i(TAG, "检测到必胜的绝杀，提前终止搜索");
                        engineHelper.sendCommand("stop");
                    }
                } else if (infos.containsKey("score")) {
                    if (Integer.parseInt(infos.get("score")) > STOP_SCORE) {
                        stopTriggeredForCurrentAnalysis = true;
                        LogUtil.i(TAG, "分数已超过 " + STOP_SCORE + "，提前终止搜索");
                        engineHelper.sendCommand("stop");
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        };
        engineHelper.init();

        // 显示悬浮窗
        FloatWindowManager.getInstance(this).show();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent resultData = intent.getParcelableExtra("data");
        calcDepth = intent.getIntExtra("depth", 14);

        if (resultCode != 0 && resultData != null) {
            if (virtualDisplay != null) {
                // 防重复启动：快速双击/服务未停再次启动时，旧的 VirtualDisplay 会被覆盖泄漏
                LogUtil.w(TAG, "capture already running, ignore duplicate start");
                return START_NOT_STICKY;
            }
            MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);
            if (mediaProjection != null) {
                // Android 14 强制要求必须先注册 Callback 才能创建 VirtualDisplay
                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        super.onStop();
                        isRunning = false;
                        if (virtualDisplay != null) virtualDisplay.release();
                    }
                }, handler);
                startCaptureLoop();
            }
        }
        return START_NOT_STICKY;
    }

    private void startCaptureLoop() {
        isRunning = true;
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay("ScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);

        handler.post(captureRunnable);
    }

    private Runnable captureRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            // 先调度下一拍，保证节拍固定；分析线程串行执行，本拍耗时会自然顺延下一拍
            handler.postDelayed(this, 2000); // 每 2 秒截屏一次，pHash 会跳过未变化的帧，兼顾速度与功耗

            if (isAnalyzing) {
                // 功耗优化：缩短超时检查，防止后台持续高能耗
                if (System.currentTimeMillis() - lastAnalyzeStartTime > 18000) {
                    LogUtil.w(TAG, getString(R.string.log_analyze_timeout));
                    isAnalyzing = false;
                }
                return;
            }

            try {
                captureAndAnalyze();
            } catch (Exception e) {
                LogUtil.e(TAG, "Capture error", e);
                isAnalyzing = false;
            }
        }
    };

    /**
     * 从 ImageReader 取整屏帧，转成 Bitmap。调用方负责 recycle。
     */
    private Bitmap captureScreen() {
        Image image = imageReader.acquireLatestImage();
        if (image == null) return null;
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;
            Bitmap bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            return bitmap;
        } finally {
            image.close();
        }
    }

    /**
     * 对裁剪图做棋子识别并返回 FEN。ChessBoardParser.parse 内部会回收传入的 bitmap。
     */
    private String parseCropped(Bitmap cropped) {
        final String[] out = {null};
        ChessBoardParser.parse(this, cropped, (fen, results) -> out[0] = fen);
        return out[0];
    }

    /**
     * 稳定确认的复抓：截屏 → smartCrop → 识别（跳过 pHash，确认帧明确要识别）
     */
    private String captureAndParseOnce() {
        Bitmap full = captureScreen();
        if (full == null) return null;
        try {
            Bitmap cropped = ChessBoardParser.smartCrop(this, full);
            if (cropped == null) return null;
            return parseCropped(cropped);
        } finally {
            full.recycle();
        }
    }

    private void captureAndAnalyze() {
        long startTime = System.currentTimeMillis();
        Bitmap bitmap = captureScreen();
        if (bitmap == null) return;

        try {
            long captureTime = System.currentTimeMillis() - startTime;

            // 智能裁剪棋盘（有缓存时仅做矩形裁剪，跳过 YOLO）
            long detectStart = System.currentTimeMillis();
            Bitmap cropped = ChessBoardParser.smartCrop(this, bitmap);
            long detectTime = System.currentTimeMillis() - detectStart;

            if (cropped == null) {
                LogUtil.d(TAG, getString(R.string.log_no_board_found, captureTime, detectTime));
                onNoBoard();
                return;
            }

            // 感知哈希对比：如果裁剪后的图片与上一次相同，跳过后续识别
            long[] croppedHash = computePHash(cropped);
            if (hasLastCroppedHash) {
                int dist = hammingDistance(croppedHash, lastCroppedHash);
                LogUtil.d(TAG, "感知哈希差异: " + dist);
                if (dist <= PHASH_THRESHOLD) {
                    lastCroppedHash = croppedHash;
                    return;
                }
            }
            lastCroppedHash = croppedHash;
            hasLastCroppedHash = true;

            long parseStart = System.currentTimeMillis();
            String fen = parseCropped(cropped);
            long parseTime = System.currentTimeMillis() - parseStart;

            if (fen == null || fen.isEmpty()) {
                LogUtil.w(TAG, String.format("未能识别有效局面 (识别耗时: %dms)", parseTime));
                onNoBoard();
                return;
            }

            // pHash 变了但局面没变（悬浮窗文字/光照/动画边缘干扰）
            if (fen.equals(lastFen)) return;

            // 识别成功，清零连续失败计数
            noBoardCount = 0;

            LogUtil.d(TAG, getString(R.string.log_fen_recognized, fen, captureTime, detectTime, parseTime));

            // 参考原型 expect_board：识别局面 == 引擎 bestMove 推演局面 → 对手按预期走子，
            // 行棋方已知（expectNextTurn），跳过确认直接分析
            String boardPart = fen.split(" ")[0];
            if (expectBoardFen != null && expectBoardFen.equals(boardPart)) {
                lastFen = fen;
                handleNewPosition(fen, true);
                return;
            }

            // —— 稳定确认（参考原型 confirm_board）：隔 200ms 复抓一帧，局面一致才分析，
            //    消灭走子动画帧/触摸高亮帧造成的误识别 ——
            isAnalyzing = true;
            lastAnalyzeStartTime = System.currentTimeMillis();
            SystemClock.sleep(CONFIRM_INTERVAL_MS);
            String confirmFen = captureAndParseOnce();
            if (confirmFen == null || !confirmFen.equals(fen)) {
                LogUtil.d(TAG, "局面未稳定，跳过本轮，等待下一帧");
                isAnalyzing = false;
                return;
            }

            lastFen = fen;
            handleNewPosition(fen, false);
        } catch (Exception e) {
            LogUtil.e(TAG, "Capture error", e);
            isAnalyzing = false;
        } finally {
            bitmap.recycle();
        }
    }

    /**
     * 连续识别失败累计：达到阈值后清空棋盘定位缓存，强制下一帧重新用 YOLO 定位棋盘。
     * 解决回退首页再进新棋局时 cachedCropRect 仍指向旧位置导致持续识别不到棋盘的问题。
     */
    private void onNoBoard() {
        noBoardCount++;
        if (noBoardCount >= NO_BOARD_RESET_THRESHOLD) {
            LogUtil.w(TAG, "连续 " + noBoardCount + " 次未识别到棋盘，清空裁剪缓存重新定位");
            ChessBoardParser.clearCropCache();
            noBoardCount = 0;
        }
    }

    /**
     * 处理已确认稳定的新局面：推导行棋方并启动引擎分析。
     * 参考原型的 board_diff 分类 + expect_board 命中机制。
     */
    private void handleNewPosition(String fen, boolean expectHit) {
        String[][] currentBoard = Utils.fenToBoard(fen);
        String bottomSide = fen.split(" ")[1]; // 识别 FEN 的第二段 = 屏幕底部方
        mySide = bottomSide;

        if (expectHit) {
            // 对手走了我方推荐的着法 → 行棋方已知，零猜测
            nextTurn = expectNextTurn;
            LogUtil.i(TAG, "对手按预期着法走子，直接进入下一回合分析");
        } else if (lastBoardArray != null) {
            Utils.BoardCompareResult cmp = Utils.compareBoard(lastBoardArray, currentBoard);
            LogUtil.i(TAG, getString(R.string.log_board_change, cmp.diffCount));

            if (cmp.diffCount >= 1 && cmp.diffCount <= 2) {
                // 走子方 = 变化格新子的颜色；若终点没识别出来（只看到起点消失），
                // 用消失格原子的颜色兜底——它只是移动到了没识别出的新位置
                String mover = (cmp.chess != null) ? cmp.chess : cmp.movedChess;
                nextTurn = (mover != null) ? (mover.startsWith("r_") ? "b" : "w") : bottomSide;
            } else {
                // diffCount > 2：两步并一帧/新开局/摆子（原型 Unknown 场景）；diffCount == 0 为防御分支。
                // 用底部方兜底：双方回合都分析，即使行棋方猜错，下一帧也会修正
                nextTurn = bottomSide;
            }
        } else {
            // 第一次识别，使用底部方
            nextTurn = bottomSide;
        }
        lastBoardArray = currentBoard;
        expectBoardFen = null; // 旧预期已消费，分析完成后会生成新预期

        // 构造最终发送给引擎的 FEN
        String finalFen = fen.split(" ")[0] + " " + nextTurn + " - - 0 1";

        // 双方回合都分析：悬浮窗任何时刻都有推荐（对方回合的推荐可用来预判对手着法）
        LogUtil.i(TAG, (nextTurn.equals("w") ? getString(R.string.turn_red) : getString(R.string.turn_black)) + ", start analyze...");

        // 先启动分析，成功才把悬浮窗切到"正在分析"；
        // 否则噪声局面会让悬浮窗永远停在"正在分析"却等不到结果
        stopTriggeredForCurrentAnalysis = false;
        boolean started = engineHelper.startAnalyze(finalFen, ENGINE_STEP_TIME_SEC, calcDepth);
        LogUtil.i(TAG, "started="+started);
        if (!started) {
            // 非法局面：保留 lastFen/lastBoardArray（该局面就是分析不了，跳过即可；
            // 不能清空 lastFen，否则同一噪声局面每帧重试 → "正在分析"永挂），
            // 局面正常变化（识别 FEN 改变）后会自然恢复分析
            FloatWindowManager.getInstance(this).updateMove(getString(R.string.waiting_recognition));
            isAnalyzing = false;
        } else {
            FloatWindowManager.getInstance(this).updateMove(getString(R.string.analyzing));
        }
    }


    /**
     * 计算图片的感知哈希 (aHash)：
     * 缩放到 PHASH_SIZE x PHASH_SIZE 灰度图，求平均灰度，
     * 每个像素大于等于平均值为 1，否则为 0，得到 PHASH_SIZE*PHASH_SIZE 位哈希（用 long[] 存储）。
     */
    private long[] computePHash(Bitmap bitmap) {
        int n = PHASH_SIZE * PHASH_SIZE;
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, PHASH_SIZE, PHASH_SIZE, true);
        int[] pixels = new int[n];
        scaled.getPixels(pixels, 0, PHASH_SIZE, 0, 0, PHASH_SIZE, PHASH_SIZE);
        if (scaled != bitmap) scaled.recycle();

        int[] gray = new int[n];
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000;
            sum += gray[i];
        }
        long avg = sum / n;

        long[] hash = new long[(n + 63) / 64];
        for (int i = 0; i < n; i++) {
            if (gray[i] >= avg) {
                hash[i >> 6] |= (1L << (i & 63));
            }
        }
        return hash;
    }

    /**
     * 计算两个哈希的汉明距离（不同位的数量）。
     */
    private int hammingDistance(long[] a, long[] b) {
        int dist = 0;
        for (int i = 0; i < a.length; i++) {
            dist += Long.bitCount(a[i] ^ b[i]);
        }
        return dist;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, "Analysis Service Channel",
                    NotificationManager.IMPORTANCE_LOW); // Reduced importance can sometimes help with power keeper
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private Notification getNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.service_notification_title))
                .setContentText(getString(R.string.service_notification_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (analysisThread != null) analysisThread.quitSafely();
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        if (engineHelper != null) engineHelper.stopAnalyze();
        if (engineHelper != null) engineHelper.stop();
        ChessBoardParser.clearCropCache();
        FloatWindowManager.getInstance(this).hide();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
