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
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import java.nio.ByteBuffer;
import java.util.HashMap;

/**
 * 完全对齐桌面端 chessboard 原型（server/src/worker.rs）的单线程同步架构：
 *
 *   loop { 等待间隔 → 截屏识别 → 状态机分类 → （需要时）阻塞式引擎搜索 → 显示/更新预期 }
 *
 * 引擎搜索是阻塞调用（EngineHelper.searchSync：发出 go 后等 bestmove 才返回），并且与
 * 截屏在同一条线程上串行执行。因此"引擎思考期间不可能发起第二次分析"、"悬浮窗显示
 * 过期提示"这类异步竞态在结构上不存在：搜索返回后下一拍立即截取最新画面重新分类，
 * 对手毫秒级跟走也会被下一拍正常捕获。
 *
 * 旧版方案（异步 bestmove 回调 + isAnalyzing 标志门控截屏 + 18s 看门狗）在红黑快速
 * 连走的场景下反复出现并发分析/提示过期/永挂问题，整套机制随本次重构移除。
 */
public class AnalysisService extends Service {
    private static final String TAG = "AnalysisService";
    private static final String CHANNEL_ID = "AnalysisChannel";

    // 桌面端 timer_interval=200ms；Android 单拍含截屏+哈希开销更大，取 300ms 兼顾反应速度与功耗
    private static final long LOOP_INTERVAL_MS = 300;
    private static final long CONFIRM_INTERVAL_MS = 200;    // 桌面端 confirm_interval
    private static final double ENGINE_STEP_TIME_SEC = 5.0; // Windows EngineStepTime
    private static final int NO_BOARD_RESET_THRESHOLD = 3;  // 连续识别失败后重置棋盘定位缓存
    private static final int PHASH_SIZE = 32;
    private static final int PHASH_THRESHOLD = 2;
    // 标准开局 FEN（parser 输出的棋盘段恒为"红在下"的标准方向，可直接字符串比较）
    private static final String STARTPOS_BOARD = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C2C4/9/RNBAKABNR";

    /** 桌面端 ChessboardState：StartPos 并入 GENERIC（后续行为一致，仅首次启发式行棋方不同） */
    private enum ChessboardState { INITIAL, GENERIC, INVALID }

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread analysisThread;
    private Handler handler;
    private WindowManager windowManager;
    private int screenWidth, screenHeight, screenDensity;

    private EngineHelper engineHelper;
    private volatile boolean isRunning = false;
    private int calcDepth = 14;

    // 引擎进程级单例（对齐桌面端 SHARED_STATE/OnceLock 语义）：native 层的输出回调是
    // 全局注册的，若每个 Service 实例新建 EngineHelper，重开会话后 bestmove 会回给
    // 已销毁的旧实例——新会话的搜索永远超时。引擎只创建一次，跨会话复用
    private static EngineHelper sEngineHelper;

    // 分析上下文（桌面端 AnalysisContext）
    private ChessboardState state = ChessboardState.INITIAL;
    private String[][] lastBoard = null;      // 上一次分析的棋盘（归一化红在下）
    private String lastBoardPart = null;      // 对应的 FEN 棋盘段
    private String expectBoardFen = null;     // 预期棋盘（bestMove 走完后）的 FEN 棋盘段
    private String expectNextTurn = null;     // 预期棋盘的行棋方
    private String nextTurn = "w";            // 上一次分析局面的行棋方（奇偶合并帧推断用）
    private int invalidChangeCount = 0;       // 桌面端 invalid_change_count
    private int untrustedCount = 0;           // 连续不可信帧（幻影/王不可见）计数
    // 最近一次有效识别的将/帅位置 {x,y}。跨重锚定/跨会话保留（SharedPreferences）：
    // 将军高亮会让 YOLO 持续丢识别王，而缺王局面无法分析，王位记忆是唯一可靠修复来源
    private int[] lastKnownRedKing = null;
    private int[] lastKnownBlackKing = null;
    private static final String PREFS_KING_MEM = "king_memory";
    private int noBoardCount = 0;             // 连续识别失败计数（无棋子/无棋盘）
    private String lastResult = "";           // 最近一次成功显示的提示（静默期恢复用）
    private boolean windowShowsAnalyzing = false; // 悬浮窗当前是否停留在"正在分析"（用于静默期恢复）
    private int cloudFailStreak = 0;          // 云库连续不可达计数
    private long cloudCooldownUntil = 0;      // 云库熔断截止时间（elapsedRealtime）
    private long[] lastCroppedHash = null;    // 上一次裁剪图片的感知哈希
    private boolean hasLastCroppedHash = false;
    private long[] pendingPrevHash = null;    // 本次提交前的哈希（噪声帧回滚用）
    private boolean pendingPrevHas = false;

    /** 回滚最近一次 pHash 提交：下一拍将重新识别同一画面（噪声帧重试用） */
    private void rollbackPHash() {
        if (pendingPrevHas) {
            lastCroppedHash = pendingPrevHash;
            hasLastCroppedHash = pendingPrevHas;
        }
    }

    /** 一帧识别结果 */
    private static class Frame {
        String fen;          // 完整 FEN（第二段 = 屏幕底部方）
        String boardPart;    // FEN 棋盘段（恒为红在下标准方向）
        String[][] board;    // 归一化棋盘数组（供逐格 diff）
        boolean bottomIsRed;
        boolean degraded;    // 将/帅不可见且无法安全补回：本轮不分析不锚定
    }

    /** 桌面端 board_diff 分类结果（丢王修复的"王外着法"分类用） */
    private static class DiffResult {
        static final int ONE = 0;      // 单格变化
        static final int MOVE = 1;     // 正常一步棋（一空一占成对）
        static final int UNKNOWN = 2;  // 多格未知变化
        int kind;
        String moverCamp;              // MOVE 时有效："w"/"b"
        String fromPiece;              // 消失格原子
    }

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

        if (sEngineHelper == null) {
            sEngineHelper = new EngineHelper(getApplicationContext(), new HashMap<>());
        }
        engineHelper = sEngineHelper;
        // 注意：桌面端原型没有"分数够大/绝杀就提前 stop"的逻辑，实测本工程的 JNI 层
        // Pikafish 收到 stop 后再也不返回 bestmove（搜索直接卡死到超时），
        // 因此这里绝不能注册 infoEvent 提前终止，一切搜索都等 movetime 自然结束。
        // 引擎为进程级单例，仅在未完成握手时 init（重复 init 会触发 uciok 超时与回调错位）；
        // 若上次初始化失败，这里会重试握手，自愈失败的单例
        if (!engineHelper.initialized) {
            engineHelper.init();
        }

        // 加载跨会话的王位记忆（将军高亮导致 YOLO 持续丢王时的修复参照）
        android.content.SharedPreferences kingMem = getSharedPreferences(PREFS_KING_MEM, Context.MODE_PRIVATE);
        if (kingMem.getBoolean("red_set", false))
            lastKnownRedKing = new int[]{kingMem.getInt("red_x", 3), kingMem.getInt("red_y", 9)};
        if (kingMem.getBoolean("black_set", false))
            lastKnownBlackKing = new int[]{kingMem.getInt("black_x", 4), kingMem.getInt("black_y", 0)};

        // 显示悬浮窗
        FloatWindowManager.getInstance(this).show();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent resultData = intent.getParcelableExtra("data");
        calcDepth = intent.getIntExtra("depth", 20);

        if (resultCode != 0 && resultData != null) {
            if (virtualDisplay != null) {
                // 防重复启动：快速双击/服务未停再次启动时，旧的 VirtualDisplay 会被覆盖泄漏
                LogUtil.w(TAG, "capture already running, ignore duplicate start");
                return START_NOT_STICKY;
            }
            MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData);
            if (mediaProjection != null) {
                // Android 14 强制要求必须先注册 Callback 才能创建 VirtualDisplay。
                // 回调必须挂在主线程：分析线程的 Looper 被阻塞式分析循环长期占用，
                // 注册到它上面 onStop 永远得不到派发
                mediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        super.onStop();
                        isRunning = false;
                        if (virtualDisplay != null) virtualDisplay.release();
                    }
                }, new Handler(Looper.getMainLooper()));
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

        // 整个分析循环就是这一条阻塞 while，跑在分析线程上
        handler.post(this::loopForever);
        // 看门狗：native 推理（ONNX session.run）偶发永久挂死，循环会无声卡住。
        // 超时未心跳则 dump 全线程堆栈定位卡点，并重建检测器、换新线程继续分析
        Thread watchdog = new Thread(this::watchdogLoop, "YiEyeWatchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private volatile long lastLoopHeartbeat = 0;
    private volatile int loopGeneration = 0;
    private static final long HEARTBEAT_TIMEOUT_MS = 30000;

    private void loopForever() {
        final int generation = loopGeneration;
        LogUtil.i(TAG, "分析循环启动 (gen=" + generation + ", thread=" + Thread.currentThread().getName() + ")");
        while (isRunning && generation == loopGeneration) {
            lastLoopHeartbeat = SystemClock.elapsedRealtime();
            long started = SystemClock.elapsedRealtime();
            try {
                step();
            } catch (Throwable t) {
                // Throwable：连 OutOfMemoryError 也不放过，循环绝不静默死亡
                LogUtil.e(TAG, "分析循环异常", t);
            }
            long cost = SystemClock.elapsedRealtime() - started;
            SystemClock.sleep(Math.max(50, LOOP_INTERVAL_MS - cost));
        }
        LogUtil.i(TAG, "分析循环退出 (gen=" + generation + ")");
    }

    private void watchdogLoop() {
        while (isRunning) {
            SystemClock.sleep(3000);
            if (!isRunning) break;
            long idle = SystemClock.elapsedRealtime() - lastLoopHeartbeat;
            if (idle < HEARTBEAT_TIMEOUT_MS) continue;

            LogUtil.e(TAG, "分析循环 " + (idle / 1000) + "s 无心跳，疑似 native 推理挂死，dump 线程堆栈并重建");
            for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                String name = e.getKey().getName();
                if (name.contains("YiEye") || name.contains("OrtInference") || name.contains("ort")) {
                    StringBuilder sb = new StringBuilder("Thread " + name + ":\n");
                    for (StackTraceElement el : e.getValue()) sb.append("  at ").append(el).append("\n");
                    LogUtil.e(TAG, sb.toString());
                }
            }
            // 丢弃可能已挂死的检测器会话，换新线程新会话继续；旧线程若从 native 返回，
            // 会因 generation 不匹配自动退出
            ChessBoardParser.resetDetector();
            loopGeneration++;
            analysisThread.quitSafely();
            analysisThread = new HandlerThread("YiEyeAnalysis");
            analysisThread.start();
            handler = new Handler(analysisThread.getLooper());
            handler.post(this::loopForever);
        }
    }

    /** 一拍：截屏识别 → 状态机分类 → 需要时阻塞分析（桌面端循环主体） */
    private void step() {
        // INITIAL 状态（首次/重置后重新锚定）必须强制识别：进入 INITIAL 时画面往往
        // 与上一拍相同，pHash 去重会把这一拍吞掉，导致永远停在 INITIAL 不分析
        Frame f = captureFrame(state == ChessboardState.INITIAL);
        if (f == null) {
            // 无棋盘 / 画面未变化：等下一拍（桌面端 continue）
            return;
        }
        if (f.degraded) {
            // 将/帅被高亮遮挡且疑似已移动：保留悬浮窗旧提示，不锚定坏帧，等识别恢复
            return;
        }

        switch (state) {
            case INITIAL: {
                // 非法局面（缺王/棋子超限，常见于复盘标号遮挡、切页动画帧）不能作为锚定
                // 基线：不锚定、保持 INITIAL 等下一拍重试。否则状态机会冻结在坏帧上，
                // 悬浮窗永远停在"等待识别"且后续所有帧都因对比基线是坏帧而无法恢复
                if (!engineHelper.isValidFen(f.fen)) {
                    LogUtil.w(TAG, "INITIAL 锚定帧无效，等待有效识别: " + f.fen);
                    FloatWindowManager.getInstance(this).updateMove(getString(R.string.waiting_recognition));
                    return;
                }
                // 桌面端 Initial：首次/重置后重新锚定。初始棋盘红先手，否则按屏幕底部方近似
                String turn = isStartpos(f) || f.bottomIsRed ? "w" : "b";
                LogUtil.i(TAG, "INITIAL 重新锚定，按" + ("w".equals(turn) ? "红" : "黑") + "方行棋分析");
                analyzePosition(f, turn);
                lastBoard = f.board;
                lastBoardPart = f.boardPart;
                state = ChessboardState.GENERIC;
                break;
            }
            case INVALID:
                // 桌面端 Invalid：复位到初始状态，下一拍由 Initial 重新锚定
                state = ChessboardState.INITIAL;
                break;
            case GENERIC: {
                // 棋盘未变化，跳过分析（桌面端 board == last_board）
                if (f.boardPart.equals(lastBoardPart)) break;

                // 符合预期棋盘：对手走了预测着法，行棋方已知，直接分析下一行动方
                if (expectBoardFen != null && expectBoardFen.equals(f.boardPart)) {
                    LogUtil.i(TAG, "对手按预期着法走子，直接进入下一回合分析");
                    lastBoard = f.board;
                    lastBoardPart = f.boardPart;
                    analyzePosition(f, expectNextTurn);
                    break;
                }

                // 延迟确认：隔 200ms 复抓一帧，局面一致才继续（桌面端 confirm_board）
                if (!confirmBoard(f)) {
                    LogUtil.d(TAG, "棋盘延迟确认失败");
                    break;
                }

                // 棋盘有效性检查（桌面端 board_check；非法局面会导致引擎原生层崩溃）
                if (!engineHelper.isValidFen(f.fen)) {
                    LogUtil.w(TAG, "棋盘识别无效: " + f.fen);
                    break;
                }

                // 行棋方推断（对齐桌面端 board_diff 的 One/Move/Unknown 语义，并针对识别
                // 抖动加固）：
                // 1) 成对变化（一空一占）= 正常一步棋（含吃子），走子方 = 消失格原子——
                //    它是上一稳定帧识别过的棋子，比终点格新子可靠；
                // 2) 多格变化且双方子数不变 = 两步并一帧的偶数步（无吃子），行棋方不变；
                // 3) 其余（半步、幻影、不成对、带丢子的合并帧）一律视为识别噪声：
                //    不分析、不锚定坏帧、回滚 pHash 让下一拍重试同画面；连续过多才
                //    强制按底部方兜底出一次招，保证不无限沉默。
                //    ——错误提示比短暂沉默危害大：对"缺子/幻影"的局面出招必然是错的。
                Utils.BoardCompareResult cmp = Utils.compareBoard(lastBoard, f.board);
                boolean pairMove = cmp.diffCount == 2 && cmp.from.x >= 0 && cmp.to.x >= 0 && cmp.movedChess != null;
                boolean countsStable = cmp.redDiff == 0 && cmp.blackDiff == 0;

                if (pairMove) {
                    nextTurn = cmp.movedChess.startsWith("r_") ? "b" : "w";
                    LogUtil.i(TAG, "走子 " + cmp.movedChess + "，轮到" + ("w".equals(nextTurn) ? "黑" : "红") + "方");
                    lastBoard = f.board;
                    lastBoardPart = f.boardPart;
                    analyzePosition(f, nextTurn);
                } else if (cmp.diffCount > 2 && countsStable) {
                    LogUtil.i(TAG, "多格变化且无吃子(" + cmp.diffCount + "格)，按偶数步合并帧处理，行棋方不变");
                    lastBoard = f.board;
                    lastBoardPart = f.boardPart;
                    analyzePosition(f, nextTurn);
                } else {
                    // 识别噪声：跳过本轮。回滚 pHash 让下一拍重新识别同一画面；
                    // 连续过多噪声时强制兜底出招一次，避免无限沉默
                    untrustedCount++;
                    LogUtil.i(TAG, "疑似识别噪声(" + cmp.diffCount + "格, 子数差 " + cmp.redDiff + "/" + cmp.blackDiff
                            + ")，跳过第 " + untrustedCount + " 拍等待干净识别");
                    rollbackPHash();
                    if (untrustedCount >= 8) {
                        LogUtil.w(TAG, "连续噪声帧达到阈值，强制按底部方重锚定当前画面");
                        untrustedCount = 0;
                        lastBoard = f.board;
                        lastBoardPart = f.boardPart;
                        analyzePosition(f, f.bottomIsRed ? "w" : "b");
                    }
                }
                break;
            }
        }
    }

    /**
     * 阻塞式分析指定行棋方并更新预期（桌面端 analyze_board + analyse）。
     * 搜索返回后先验证局面是否仍与 analyzed 一致——对手在搜索期间已行棋时
     * 该结果已过期，不显示，等下一拍循环去分析新局面。
     */
    private void analyzePosition(Frame f, String turn) {
        nextTurn = turn;
        untrustedCount = 0; // 成功进入分析 = 帧已可信，重置噪声计数
        String finalFen = f.boardPart + " " + turn + " - - 0 1";
        // 非法局面会导致 Pikafish 原生层崩溃，提前拦截（双保险，searchSync 内部也会校验）
        if (!engineHelper.isValidFen(finalFen)) {
            FloatWindowManager.getInstance(this).updateMove(getString(R.string.waiting_recognition));
            return;
        }

        LogUtil.i(TAG, ("w".equals(turn) ? getString(R.string.turn_red) : getString(R.string.turn_black)) + ", start analyze...");
        FloatWindowManager.getInstance(this).updateMove(getString(R.string.analyzing));
        windowShowsAnalyzing = true;

        long startedAt = System.currentTimeMillis();
        String source = null;
        EngineHelper.SearchResult r;

        // ① 云库优先（桌面端 search 语义）：chessdb 命中则秒出招，未命中/网络失败
        //    落本地引擎；云库判非法（多为识别缺子/错子）则按无效帧跳过
        ChessDB.Result cloud = queryCloudSafe(f.boardPart + " " + turn, 5);
        if (ChessDB.STATE_INVALID.equals(cloud.state)) {
            LogUtil.w(TAG, "云库判定局面非法，跳过本轮等待识别恢复");
            return;
        }
        if (ChessDB.STATE_SUCCESS.equals(cloud.state) && !cloud.pv.isEmpty()) {
            source = cloud.source;
            r = new EngineHelper.SearchResult();
            r.bestMove = cloud.pv.get(0);
            r.ponderMove = cloud.pv.size() > 1 ? cloud.pv.get(1) : null;
            r.score = cloud.score;
            r.depth = cloud.depth;
        } else {
            source = "引擎";
            r = engineHelper.searchSync(finalFen, ENGINE_STEP_TIME_SEC, calcDepth);
        }
        long engineTime = System.currentTimeMillis() - startedAt;

        if (r != null && "(none)".equals(r.bestMove)) {
            // 行棋方无合法着法（被将死/困毙）：如实提示，避免悬浮窗停在"正在分析"
            LogUtil.i(TAG, "该局面已无棋可走（将死/困毙）");
            windowShowsAnalyzing = false;
            FloatWindowManager.getInstance(this).updateMove("已无棋可走（将死/困毙）");
            return;
        }
        if (r == null || r.bestMove == null || r.bestMove.length() < 4) {
            // 引擎无有效结果：保留悬浮窗当前内容（多为"正在分析"），等下一拍重试
            LogUtil.w(TAG, "引擎无有效结果 (耗时 " + engineTime + "ms)");
            return;
        }

        // 出招先验货：搜索期间对手可能已行棋。过期提示不显示——否则旧招法会在
        // 对方思考的时间段里一直挂在悬浮窗上。
        // 验货帧若是 degraded（王不可见、局面不可判），无法断定对手是否行棋，按未行棋处理
        Frame now = captureFrame(true);
        if (now != null && !now.degraded && !now.boardPart.equals(f.boardPart)) {
            LogUtil.i(TAG, "引擎出招时对手已行棋，丢弃过期提示，下一拍分析新局面");
            return;
        }

        // 预期棋盘：bestMove 走完后的局面 + 轮到对方；对手照着走时下一拍直接命中
        String expectFen = Utils.applyMoveToFen(finalFen, r.bestMove);
        if (expectFen != null && expectFen.contains(" ")) {
            expectBoardFen = expectFen.split(" ")[0];
            expectNextTurn = expectFen.split(" ")[1];
        } else {
            expectBoardFen = null;
            expectNextTurn = null;
        }

        // 记谱需要"屏幕底部方"视角（不是行棋方），否则黑方行棋时中文记谱错位
        String chinaMove = Utils.fenToChina(this, finalFen, r.bestMove, f.bottomIsRed);
        String chinaMove2 = (r.ponderMove != null && r.ponderMove.length() >= 4 && !"(none)".equals(r.ponderMove))
                ? Utils.fenToChina(this, finalFen, r.ponderMove, f.bottomIsRed) : getString(R.string.none_ponder);

        String turnStr = finalFen.contains(" w ") ? getString(R.string.turn_red) : getString(R.string.turn_black);

        // ③ 次优候选（仅本地引擎 MultiPV 提供）：分数与最优差 ≤ alt_score_gap 的名次首着，
        //    括号内为与最优的相对分数（负值 = 比最优差多少），供直观判断候选质量
        StringBuilder altTextSb = new StringBuilder();
        if (r.alternatives != null) {
            for (EngineHelper.AltCandidate a : r.alternatives) {
                if (altTextSb.length() > 0) altTextSb.append("  ");
                altTextSb.append(Utils.fenToChina(this, finalFen, a.move, f.bottomIsRed))
                         .append("(").append(a.relScore).append(")");
            }
        }
        String altText = altTextSb.toString();
        String evalText = formatEval(r.score);

        // 悬浮窗富文本（配色对齐桌面端 Analyse.vue）：
        // 推荐招法=蓝(info) 深度=橙(warning) 备选招法=紫(#9b59b6) 形势=优绿/劣红 均势灰
        // 不显示括号内的 iccs 坐标编码（如 g7e6）——用户看的是中文记谱
        SpannableStringBuilder display = new SpannableStringBuilder();
        appendColored(display, turnStr + "\n", 0xFFFFFFFF, true);
        appendColored(display, getString(R.string.recommend), 0xFFB0B0B0, false);
        appendColored(display, chinaMove, 0xFF2080F0, true);
        if (source != null) appendColored(display, " [" + source + "]", 0xFF9B59B6, false);
        appendColored(display, " + " + getString(R.string.time_consumed) + engineTime / 100 / 10f + "s\n", 0xFFB0B0B0, false);
        appendColored(display, getString(R.string.ponder) + chinaMove2 + "\n", 0xFFB0B0B0, false);
        if (altText.length() > 0) {
            appendColored(display, "备选: ", 0xFFB0B0B0, false);
            appendColored(display, altText + "\n", 0xFF9B59B6, true);
        }
        if (r.score != Integer.MIN_VALUE) {
            int evalColor = r.score > 30 ? 0xFF18A058 : r.score < -30 ? 0xFFD03050 : 0xFFB0B0B0;
            appendColored(display, "形势: ", 0xFFB0B0B0, false);
            appendColored(display, evalText, evalColor, true);
            appendColored(display, "  深度: " + r.depth + "\n", 0xFFF0A020, false);
        }

        String displayStr = display.toString();
        LogUtil.d(TAG, getString(R.string.log_best_move, engineTime, displayStr));

        // 悬浮窗更新必须用富文本对象 display（SpannableStringBuilder），
        // 传 toString() 会丢弃全部颜色 span（纯文本覆写悬浮窗）
        lastResult = displayStr;
        windowShowsAnalyzing = false;
        FloatWindowManager.getInstance(this).updateMove(display);

        Intent intentBroadcast = new Intent("com.example.CHESS_RESULT");
        intentBroadcast.putExtra("displayStr", displayStr);
        sendBroadcast(intentBroadcast);
    }

    /** 云库查询（带熔断）：连续 2 次不可达则 5 分钟内直接走本地引擎，避免离线时每步白等 */
    private ChessDB.Result queryCloudSafe(String fen, int timeoutSec) {
        long now = SystemClock.elapsedRealtime();
        if (now < cloudCooldownUntil) {
            return new ChessDB.Result();   // unknown → 本地引擎
        }
        ChessDB.Result r = ChessDB.query(fen, timeoutSec);
        if (ChessDB.STATE_UNKNOWN.equals(r.state)) {
            cloudFailStreak++;
            if (cloudFailStreak >= 2) {
                cloudFailStreak = 0;
                cloudCooldownUntil = now + 300_000;
                LogUtil.w(TAG, "云库连续不可达，5 分钟内直接走本地引擎");
            }
        } else {
            cloudFailStreak = 0;
        }
        return r;
    }

    /** 追加一段带颜色/粗体的富文本 */
    private void appendColored(SpannableStringBuilder b, String text, int color, boolean bold) {
        int start = b.length();
        b.append(text);
        b.setSpan(new android.text.style.ForegroundColorSpan(color), start, b.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            b.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, b.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /**
     * 局面评估翻译（对齐桌面端 Analyse.vue 的 formatEval，行棋方视角）：
     * ±29000 以上为将杀步数编码，|分数|<30 为均势，其余按差值分四档优劣。
     */
    private String formatEval(int score) {
        int abs = Math.abs(score);
        if (score >= 29000) return (30000 - score) + "步杀";
        if (score <= -30001) return (-score - 30000) + "步被杀";
        if (score <= -29000) return (30000 + score) + "步被杀";
        if (abs < 30) return "均势";
        String[] gradePos = {"略优", "较优", "大优", "胜势"};
        String[] gradeNeg = {"略差", "较差", "大差", "败势"};
        int idx = abs < 150 ? 0 : abs < 400 ? 1 : abs < 800 ? 2 : 3;
        return (score > 0 ? "+" + abs : "-" + abs) + " " + (score > 0 ? gradePos[idx] : gradeNeg[idx]);
    }

    /**
     * 延迟确认（桌面端 confirm_board）：隔一小段再抓一帧识别，局面一致才算稳定。
     * 消灭走子动画帧/触摸高亮帧造成的误识别。
     */
    private boolean confirmBoard(Frame f) {
        SystemClock.sleep(CONFIRM_INTERVAL_MS);
        Frame conf = captureFrame(true);
        return conf != null && conf.boardPart.equals(f.boardPart);
    }

    private boolean isStartpos(Frame f) {
        return STARTPOS_BOARD.equals(f.boardPart);
    }

    /**
     * 截屏 → 智能裁剪 → （可选 pHash 去重）→ 棋子识别。
     *
     * @param force true 时跳过 pHash 去重强制识别（确认帧/出招验货帧）
     * @return 识别成功返回 Frame；无画面/无棋盘/识别失败/画面未变化返回 null
     */
    private Frame captureFrame(boolean force) {
        Bitmap bitmap = captureScreen();
        if (bitmap == null) return null;
        try {
            Bitmap cropped = ChessBoardParser.smartCrop(this, bitmap);
            if (cropped == null) {
                onNoBoard();
                return null;
            }

            long[] prevHash = lastCroppedHash;
            boolean prevHas = hasLastCroppedHash;
            if (!force) {
                long[] croppedHash = computePHash(cropped);
                if (prevHas && hammingDistance(croppedHash, prevHash) <= PHASH_THRESHOLD) {
                    lastCroppedHash = croppedHash;
                    // 画面未变化。若悬浮窗还停留在"正在分析"（上一次分析无果被丢弃，
                    // 且画面已回到锚定位置外观），恢复上一条有效提示，
                    // 避免假的分析状态长期挂屏、让用户误以为仍在计算
                    if (windowShowsAnalyzing) {
                        windowShowsAnalyzing = false;
                        if (lastResult != null && !lastResult.isEmpty()) {
                            FloatWindowManager.getInstance(this).updateMove(lastResult);
                        }
                    }
                    return null; // 画面未变化，等下一拍
                }
                lastCroppedHash = croppedHash;
                hasLastCroppedHash = true;
                pendingPrevHash = prevHash;
                pendingPrevHas = prevHas;
            }

            String fen = parseCropped(cropped);
            if (fen == null || fen.isEmpty()) {
                onNoBoard();
                return null;
            }
            noBoardCount = 0;

            Frame f = new Frame();
            f.fen = fen;
            String[] parts = fen.split(" ");
            f.boardPart = parts[0];
            f.bottomIsRed = parts.length > 1 && "w".equals(parts[1]);
            f.board = Utils.fenToBoard(fen);
            repairKings(f);

            // 不可信帧守卫：子数凭空增加 = 幻影子；王不可见且补不回 = 局面不可判。
            // 这类帧不进入状态机（杜绝幻影局面出招），并回滚 pHash 提交让下一拍重试识别。
            // 若连续多帧都不可信，说明锚定局面已与现实脱节（如复盘箭头/标号被当成棋子、
            // 或期间有帧被跳过）：强制全量重锚定到当前真实画面，否则旧提示永远挂死无出口
            if (!force && lastBoard != null) {
                Utils.BoardCompareResult guard = Utils.compareBoard(lastBoard, f.board);
                boolean untrusted = guard.redDiff < 0 || guard.blackDiff < 0 || f.degraded;
                if (untrusted) {
                    untrustedCount++;
                    LogUtil.i(TAG, "识别帧不可信(连续第 " + untrustedCount + " 帧，子数差 "
                            + guard.redDiff + "/" + guard.blackDiff
                            + (f.degraded ? ",王不可见" : "") + ")");
                    if (untrustedCount >= 5) {
                        // 复位到 INITIAL 重新锚定。注意必须保留 lastBoard：它是
                        // repairKings 相似度护栏的参照——清空后无法区分"同一棋盘的
                        // 识别噪声"与"换了一个全新棋盘"，王位记忆会把旧局的王补进新局面
                        LogUtil.w(TAG, "连续不可信帧达到阈值，复位到 INITIAL 重新锚定");
                        expectBoardFen = null;
                        expectNextTurn = null;
                        state = ChessboardState.INITIAL;
                        untrustedCount = 0;
                    }
                    rollbackPHash();
                    return null;
                }
                // 注意：这里不能清零 untrustedCount——子数减少类的噪声帧会通过本守卫、
                // 在 step() 的噪声跳过分支里计数；清零会让"连续跳过达阈值→强制兜底"
                // 的恢复出口永远无法触发（计数器互踩，悬浮窗挂死在旧提示上）
            }
            return f;
        } finally {
            // 缓存帧是服务持有的，不能回收
            if (lastScreenRecyclable) bitmap.recycle();
        }
    }

    /**
     * 识别丢王修复（带一致性约束）：将军高亮会让 YOLO 丢识别将/帅，缺王局面无法分析。
     * 但只有"王以外的部分恰好是一步干净棋"才说明走的是别的子、王只是被挡住——
     * 此时把王补回原位是安全的。若王以外的部分无变化（说明对方走的就是王、逃将后的
     * 新位置同样识别不到），补回旧位置就是在对幻影局面出招——本轮标记 degraded，
     * 跳过分析、不锚定，等高亮退去识别恢复。
     */
    private Frame repairKings(Frame f) {
        f.degraded = false;
        rememberKings(f.board);
        boolean rPresent = hasKing(f.board, "r_jiang");
        boolean bPresent = hasKing(f.board, "b_jiang");
        if (rPresent && bPresent) return f;              // 双王都在，正常
        if (!rPresent && !bPresent) {                    // 双王全丢：画面大面积不可信
            f.degraded = true;
            return f;
        }
        String missing = rPresent ? "b_jiang" : "r_jiang";

        // 有锚定基线：先校验"王以外的部分恰好是一步干净棋"（说明对方走的是别的子、
        // 王只是被高亮挡住），才从基线补回。若王以外的部分无变化，说明对方走的就是王
        // （逃将后的新位置同样识别不到）——此时落到下方王位记忆兜底
        int[] memSpot = "r_jiang".equals(missing) ? lastKnownRedKing : lastKnownBlackKing;
        if (lastBoard != null) {
            int same = 0;
            for (int y = 0; y < 10; y++)
                for (int x = 0; x < 9; x++)
                    if (lastBoard[x][y] == null ? f.board[x][y] == null : lastBoard[x][y].equals(f.board[x][y]))
                        same++;
            if (same < 70) {
                LogUtil.i(TAG, "与上一局面差异过大(" + same + "/90)，跳过丢王修复");
                return f;
            }
            String[][] lbNoKing = cloneBoard(lastBoard);
            removeKing(lbNoKing, missing);
            String[][] fNoKing = cloneBoard(f.board);
            removeKing(fNoKing, missing);
            DiffResult d = boardDiff(lbNoKing, fNoKing);
            if (d.kind == DiffResult.MOVE && restoreKing(f.board, lastBoard, missing)) {
                f.fen = Utils.boardToFen(f.board, f.bottomIsRed);
                f.boardPart = f.fen.split(" ")[0];
                LogUtil.i(TAG, "识别丢失将/帅（对方走的是其他子），已从上一局面补回: " + f.boardPart);
                return f;
            }
        }

        // 王位记忆兜底（对齐桌面端场景：基线缺王/噪声帧时也能恢复分析）：
        // 将/帅离不开九宫，记忆位在当前帧为空即可按记忆补回
        if (memSpot != null && f.board[memSpot[0]][memSpot[1]] == null) {
            f.board[memSpot[0]][memSpot[1]] = missing;
            f.fen = Utils.boardToFen(f.board, f.bottomIsRed);
            f.boardPart = f.fen.split(" ")[0];
            LogUtil.i(TAG, "识别丢失将/帅，按王位记忆(" + memSpot[0] + "," + memSpot[1] + ")补回: " + f.boardPart);
            return f;
        }

        // 记忆位被占或无记忆（王真的移动了且新位置也未识别出）：识别噪声，不分析不锚定
        LogUtil.i(TAG, "将/帅不可见且无法可靠补回，跳过本轮分析等待识别恢复");
        f.degraded = true;
        return f;
    }

    /** 从当前帧记忆两王位置（只在王可见时更新；持久化到 SharedPreferences 跨会话使用） */
    private void rememberKings(String[][] board) {
        int[] rk = findKing(board, "r_jiang");
        if (rk != null) {
            lastKnownRedKing = rk;
            getSharedPreferences(PREFS_KING_MEM, Context.MODE_PRIVATE).edit()
                    .putInt("red_x", rk[0]).putInt("red_y", rk[1]).apply();
        }
        int[] bk = findKing(board, "b_jiang");
        if (bk != null) {
            lastKnownBlackKing = bk;
            getSharedPreferences(PREFS_KING_MEM, Context.MODE_PRIVATE).edit()
                    .putInt("black_x", bk[0]).putInt("black_y", bk[1]).apply();
        }
    }

    private int[] findKing(String[][] board, String king) {
        for (int y = 0; y < 10; y++)
            for (int x = 0; x < 9; x++)
                if (king.equals(board[x][y])) return new int[]{x, y};
        return null;
    }

    private boolean hasKing(String[][] board, String king) {
        for (int y = 0; y < 10; y++)
            for (int x = 0; x < 9; x++)
                if (king.equals(board[x][y])) return true;
        return false;
    }

    private String[][] cloneBoard(String[][] src) {
        String[][] out = new String[9][10];
        for (int x = 0; x < 9; x++) out[x] = src[x].clone();
        return out;
    }

    private void removeKing(String[][] board, String king) {
        for (int y = 0; y < 10; y++)
            for (int x = 0; x < 9; x++)
                if (king.equals(board[x][y])) board[x][y] = null;
    }

    /** 本帧缺这颗王、且它在上一局面的位置现在为空时，补回并返回 true */
    private boolean restoreKing(String[][] board, String[][] prev, String king) {
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 9; x++) {
                if (king.equals(board[x][y])) return false; // 本帧王还在，无需修复
            }
        }
        int cx = -1, cy = -1;
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 9; x++) {
                if (king.equals(prev[x][y])) {
                    cx = x;
                    cy = y;
                }
            }
        }
        if (cx < 0 || board[cx][cy] != null) return false;
        board[cx][cy] = king;
        return true;
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
     * 从 ImageReader 取整屏帧。
     * 关键差异：桌面端 xcap 是拉式截屏（每拍都拿到当前画面），而 Android 的
     * VirtualDisplay+ImageReader 是推式的——画面静止时不产生新帧，acquireLatestImage
     * 返回空。若直接返回 null，循环会在"最后一帧没分析成功、画面随后静止"时饿死
     * （新局面永远得不到分析，直到画面再次变化）。因此缓存最近一帧，推帧为空时返回缓存，
     * 使循环与桌面端语义等价；pHash 去重仍保证静止期不重复跑识别。
     */
    private Bitmap lastScreen = null;      // 服务持有的最近一帧（captureFrame 不回收它）
    private boolean lastScreenRecyclable = false;

    private Bitmap captureScreen() {
        Image image = imageReader.acquireLatestImage();
        if (image == null) {
            lastScreenRecyclable = false;
            return lastScreen;
        }
        lastScreenRecyclable = true;
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;
            Bitmap bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            if (lastScreen != null) lastScreen.recycle();
            lastScreen = bitmap;
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
     * 桌面端 board_diff：对比两局面的逐格差异并分类。
     * - 恰好一格变化 → ONE（半步/漏检帧）；
     * - 恰好两格且"一空一占"成对 → MOVE，走子方 = 消失格原子（上一稳定帧识别过的棋子，
     *   比终点格新子可靠——终点格属于刚变化区域，最容易被误检翻转颜色）；
     * - 其余（多格/两格但不成对）→ UNKNOWN。
     */
    private DiffResult boardDiff(String[][] from, String[][] to) {
        DiffResult r = new DiffResult();
        if (from == null || to == null) {
            r.kind = DiffResult.UNKNOWN;
            return r;
        }
        int count = 0;
        boolean hasFrom = false, hasTo = false;
        String vanished = null;
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 9; x++) {
                boolean same = from[x][y] == null ? to[x][y] == null : from[x][y].equals(to[x][y]);
                if (!same) {
                    count++;
                    if (to[x][y] == null) {
                        hasFrom = true;
                        vanished = from[x][y];
                    } else {
                        hasTo = true;
                    }
                }
            }
        }
        if (count == 1) {
            r.kind = DiffResult.ONE;
        } else if (count == 2 && hasFrom && hasTo && vanished != null) {
            r.kind = DiffResult.MOVE;
            r.fromPiece = vanished;
            r.moverCamp = vanished.startsWith("r_") ? "w" : "b";
        } else {
            r.kind = DiffResult.UNKNOWN;
        }
        return r;
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
        if (engineHelper != null) {
            // 引擎为进程级单例：会话结束只中止在途搜索，不 quit——
            // 下次会话直接复用（native 回调全局注册，销毁重建会导致回调错位）
            engineHelper.abortSearch();
        }
        if (analysisThread != null) analysisThread.quitSafely();
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) mediaProjection.stop();
        if (lastScreen != null) {
            lastScreen.recycle();
            lastScreen = null;
        }
        ChessBoardParser.clearCropCache();
        FloatWindowManager.getInstance(this).hide();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
