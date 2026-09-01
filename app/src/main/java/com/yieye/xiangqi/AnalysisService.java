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
    private HandlerThread workerThread;
    private Handler handler; // 绑定到后台线程：避免 YOLO 推理阻塞服务主线程
    private WindowManager windowManager;
    private int screenWidth, screenHeight, screenDensity;

    private String lastFen = "";
    private String lastResult = "";
    private String[][] lastBoardArray = null;
    private String nextTurn = "w";
    private String mySide = null; // 初始为空，由第一帧自动识别
    private int mySideStreak = 0;                       // 朝向识别连续不一致的帧数（迟滞防抖）
    private static final int SIDE_SWITCH_THRESHOLD = 3; // 连续 N 帧不一致才允许切换我方颜色
    private EngineHelper engineHelper;
    private boolean isRunning = false;
    private volatile boolean isAnalyzing = false;
    private long lastAnalyzeStartTime = 0;
    private long[] lastCroppedHash = null; // 上一次裁剪图片的感知哈希
    private boolean hasLastCroppedHash = false;
    private static final int PHASH_SIZE = 32; // 感知哈希采样网格 32x32 = 1024 bit
    private static final int PHASH_THRESHOLD = 2; // 汉明距离阈值，小于等于此值认为相同
    private int calcDepth = 14;

    private volatile boolean stopTriggeredForCurrentAnalysis = false;
    private static final int STOP_SCORE = 2000; // 分数超过该阈值提前终止搜索 (Windows: StopScore)
    private static final double ENGINE_STEP_TIME_SEC = 5.0; // 每步引擎最长思考时间 (Windows: EngineStepTime)

    @Override
    public void onCreate() {
        super.onCreate();

        // 用独立后台线程跑「截屏 → YOLO 识别 → FEN → 引擎指令」整条链路，
        // 避免中低端机上每次推理把服务主线程卡住数百毫秒
        workerThread = new HandlerThread("AnalysisWorker");
        workerThread.start();
        handler = new Handler(workerThread.getLooper());

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

        engineHelper = new EngineHelper(this, new HashMap<>());
        engineHelper.bestMoveEvent = (sourceFen, bestMove, ponderMove) -> {
            long engineTime = System.currentTimeMillis() - lastAnalyzeStartTime;
            if (sourceFen != null && !sourceFen.isEmpty()) {
                // 先给默认值，避免 Java 确定性赋值分析报"可能未初始化"
                String chinaMove = getString(R.string.unknown);
                String chinaMove2 = getString(R.string.none_ponder);
                boolean hasBest = bestMove != null && bestMove.length() >= 4;
                boolean hasPonder = ponderMove != null && ponderMove.length() >= 4;
                if (hasBest && hasPonder) {
                    // 修复：续着必须作用在「走了最佳走法之后」的棋盘上。
                    // 旧实现两次都传原始 sourceFen，续着的起点格在原局面下可能是空格，
                    // 导致记谱失败回退成原始 UCI 串，或按错误的棋子记谱。
                    String[] decoded = Utils.fenToChina(this, sourceFen,
                            new String[]{bestMove, ponderMove}).split(" ");
                    chinaMove = (decoded.length > 0 && !decoded[0].isEmpty()) ? decoded[0] : chinaMove;
                    chinaMove2 = (decoded.length > 1 && !decoded[1].isEmpty()) ? decoded[1] : chinaMove2;
                } else if (hasBest) {
                    chinaMove = Utils.fenToChina(this, sourceFen, bestMove);
                } else if (hasPonder) {
                    chinaMove2 = Utils.fenToChina(this, sourceFen, ponderMove);
                }

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
            // 延迟 0.5 秒后再允许下一次分析，给 UI 留出反应时间并降低功耗
            handler.postDelayed(() -> isAnalyzing = false, 500);
        };
        engineHelper.infoEvent = (cmd, infos) -> {
            // 对齐 Windows StopScore/StopWhenMate：一旦分数已经足够大或已经出现绝杀，
            // 不必再等满深度/满时间，直接提前终止搜索并使用当前的 pv 作为结果
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

            if (isAnalyzing) {
                // 功耗优化：缩短超时检查，防止后台持续高能耗
                if (System.currentTimeMillis() - lastAnalyzeStartTime > 18000) {
                    LogUtil.w(TAG, getString(R.string.log_analyze_timeout));
                    isAnalyzing = false;
                }
            }

            if (!isAnalyzing) {
                captureAndAnalyze();
            }

            handler.postDelayed(this, 3000); // 每 3 秒截屏一次，兼顾速度与发热/功耗
        }
    };

    private void captureAndAnalyze() {
        long startTime = System.currentTimeMillis();
        Image image = imageReader.acquireLatestImage();
        if (image == null) return;

        Bitmap bitmap = null;
        try {
            isAnalyzing = true;
            lastAnalyzeStartTime = System.currentTimeMillis();

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;

            bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            long captureTime = System.currentTimeMillis() - startTime;


            // 使用 OpenCV 进行棋盘识别与裁剪
            long detectStart = System.currentTimeMillis();
            Bitmap cropped = ChessBoardParser.smartCrop(this, bitmap);

            long detectTime = System.currentTimeMillis() - detectStart;

            if (cropped == null) {
                LogUtil.d(TAG, getString(R.string.log_no_board_found, captureTime, detectTime));
                isAnalyzing = false;
            } else {
                // 感知哈希对比：如果裁剪后的图片与上一次相同，跳过后续分析
                long[] croppedHash = computePHash(cropped);
                if (hasLastCroppedHash) {
                    int dist = hammingDistance(croppedHash, lastCroppedHash);
                    LogUtil.d(TAG, "感知哈希差异: " + dist);
                    if (dist <= PHASH_THRESHOLD) {
                        LogUtil.d(TAG, "裁剪图片与上一次相同，跳过分析");
                        lastCroppedHash = croppedHash;
                        cropped.recycle();
                        isAnalyzing = false;
                        return;
                    }
                }
                lastCroppedHash = croppedHash;
                hasLastCroppedHash = true;

                long parseStart = System.currentTimeMillis();
                ChessBoardParser.parse(this, cropped, (fen, results) -> {
                    long parseTime = System.currentTimeMillis() - parseStart;
                    if (fen != null && !fen.equals(lastFen)) {
                        LogUtil.d(TAG, getString(R.string.log_fen_recognized, fen, captureTime, detectTime, parseTime));
                        lastFen = fen;


                        // 2. 自动识别我方颜色（默认底部为我方），带迟滞防抖：
                        // 需连续 SIDE_SWITCH_THRESHOLD 帧一致才允许切换，
                        // 避免单帧 YOLO 误判 r_jiang 导致朝向翻转、分析时机错乱
                        String detectedSide = fen.split(" ")[1];
                        if (mySide == null) {
                            mySide = detectedSide;
                            mySideStreak = 1;
                            LogUtil.i(TAG, getString(R.string.auto_identify_side) + (mySide.equals("w") ? getString(R.string.side_red) : getString(R.string.side_black)) + getString(R.string.screen_bottom));
                        } else if (detectedSide.equals(mySide)) {
                            mySideStreak = 1;
                        } else {
                            mySideStreak++;
                            if (mySideStreak >= SIDE_SWITCH_THRESHOLD) {
                                LogUtil.w(TAG, "朝向识别变化，切换我方为 " + (detectedSide.equals("w") ? getString(R.string.side_red) : getString(R.string.side_black)));
                                mySide = detectedSide;
                                mySideStreak = 1;
                            }
                        }
                        // 1. 解析当前棋盘数组 (Utils.fenToBoard 会根据 FEN 里的 w/b 自动处理旋转)
                        String[][] currentBoard = Utils.fenToBoard(fen);

                        // 3. 比较上一次局面，自动推导待行方
                        if (lastBoardArray != null) {
                            Utils.BoardCompareResult cmp = Utils.compareBoard(lastBoardArray, currentBoard);
                            // 如果只有 1-2 个格子变化（起终点），说明走了一步
                            LogUtil.i(TAG, getString(R.string.log_board_change, cmp.diffCount));

                            if (cmp.diffCount > 0 && cmp.diffCount <= 2 && cmp.chess != null) {
                                if (cmp.chess.startsWith("r_")) {
                                    nextTurn = "b"; // 红方刚刚移动，下一手该黑方
                                } else if (cmp.chess.startsWith("b_")) {
                                    nextTurn = "w"; // 黑方刚刚移动，下一手该红方
                                } else {
                                    nextTurn = mySide;
                                }
                            } else if (cmp.diffCount > 2) {
                                // 变化较大（超过2格），可能是新开局或摆子，
                                // 使用防抖后的我方方位作为默认行棋方，避免单帧噪声干扰
                                nextTurn = mySide;
                            }
                        } else {
                            // 第一次识别，使用防抖后的我方方位
                            nextTurn = mySide;
                        }
                        lastBoardArray = currentBoard;

                        // 3. 构造最终发送给引擎的 FEN
                        String finalFen = fen.split(" ")[0] + " " + nextTurn + " - - 0 1";


                        // 只有轮到我方走棋时才分析
                        if (nextTurn.equals(mySide)) {
                            LogUtil.i(TAG, String.format(getString(R.string.board_change_my_turn), (nextTurn.equals("w") ? getString(R.string.side_red) : getString(R.string.side_black))));

                            FloatWindowManager.getInstance(this).updateMove(getString(R.string.analyzing));
                            // 功耗优化：限制线程数为 1 (在 EngineHelper 中设置)；depth 与 movetime 双重限制，谁先到谁生效
                            stopTriggeredForCurrentAnalysis = false;
                            boolean started = engineHelper.startAnalyze(finalFen, ENGINE_STEP_TIME_SEC, calcDepth);
                            LogUtil.i(TAG, "started="+started);
                            if (!started) {
                                // 非法局面，清空 lastFen，以便下次重新识别同一局面
                                lastFen = "";
                                isAnalyzing = false;
                                lastCroppedHash = null;
                                hasLastCroppedHash = false;
                            }
                        } else {
                            LogUtil.i(TAG, String.format(getString(R.string.board_change_oppo_turn), (nextTurn.equals("w") ? getString(R.string.side_red) : getString(R.string.side_black))));
                            FloatWindowManager.getInstance(this).updateMove(getString(R.string.waiting));
                            isAnalyzing = false; // 对方走棋，不分析，直接释放状态
                        }
                    } else {
                        // 局面没变，直接释放状态
                        LogUtil.w(TAG, String.format("未能识别有效局面 (识别耗时: %dms)", parseTime) + fen);
                        isAnalyzing = false;
                    }
                });
            }

        } catch (Exception e) {
            LogUtil.e(TAG, "Capture error", e);
            isAnalyzing = false;
        } finally {
            image.close();
            if (bitmap != null) bitmap.recycle();
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
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        if (engineHelper != null) engineHelper.stopAnalyze();
        if (engineHelper != null) engineHelper.stop();
        ChessBoardParser.clearCropCache();
        FloatWindowManager.getInstance(this).hide();
        if (workerThread != null) workerThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
