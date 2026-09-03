// This file interfaces with Pikafish (GPLv3, https://github.com/official-pikafish/Pikafish).
// See THIRD_PARTY_NOTICES.md and LICENSE at the project root.
package com.yieye.xiangqi;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class EngineHelper {
    private static final String TAG = "EngineHelper";
    
    static {
        System.loadLibrary("pikafish");
    }

    private Context context;

    public String engineType = "uci";
    public String engineName = "";
    public String engineAuthor = "";
    // Handshake flags
    private volatile boolean isUciOk = false;
    private volatile boolean isReadyOk = false;

    public interface InfoCallback {
        void onInfo(String cmd, Map<String, String> infos);
    }

    public InfoCallback infoEvent;

    /** 一次阻塞式搜索的结果（对齐桌面端 QueryResult 的核心字段） */
    /** 次优候选：首着 + 与最优的相对分数（≤0，负得越多越劣） */
    public static class AltCandidate {
        public String move;
        public int relScore;
        public AltCandidate(String move, int relScore) {
            this.move = move;
            this.relScore = relScore;
        }
    }

    public static class SearchResult {
        public String bestMove;
        public String ponderMove;
        public List<AltCandidate> alternatives;   // MultiPV 次优候选首着（分数差 ≤ altScoreGap）
        public int score;                   // 行棋方视角分数（cp）
        public int depth;                   // 搜索深度
        public String source;               // 结果来源："引擎" / "云库"
    }

    // 在途搜索：searchSync 与引擎读线程通过它配对 position/go 与 bestmove。
    // 单线程串行调用 searchSync，任何时刻最多一个在途搜索。
    // volatile + 无锁 abortSearch：服务销毁时要在 searchSync 持锁阻塞期间也能中止它
    private volatile SearchResult pendingResult;
    private volatile CountDownLatch pendingLatch;
    private volatile SearchCollector collector;
    // 对齐桌面端 alt_score_gap：候选分数与最优差 ≤ 该值才列为次优
    private static final int ALT_SCORE_GAP = 300;

    public Map<String, String> configs = new HashMap<>();
    
    private volatile String currentFen = "";
    
    public volatile boolean initialized = false;

    public native void initJNI(String workDir);
    public native void sendCommandJNI(String cmd);

    public EngineHelper(Context context, Map<String, String> configs) {
        this.context = context;
        if (configs != null) {
            this.configs = configs;
        }
    }

    public void stop() {
        sendCommand("quit");
    }

    public void init() {
        initialized = false;
        isUciOk = false;
        isReadyOk = false;
        currentFen = "";
        
        deployAssets(context);

        String workDir = context.getFilesDir().getAbsolutePath();

        Thread engineThread = new Thread(() -> {
            try {
                // 不设置 THREAD_PRIORITY_BACKGROUND：该优先级会把引擎线程（含其创建的搜索线程）
                // 归入系统后台 cgroup 被 CPU 限速，导致搜索深度/速度明显打折
                initJNI(workDir);

                int waitCount = 0;
                while (!isUciOk && waitCount < 50) {
                    Thread.sleep(100);
                    waitCount++;
                }

                if (!isUciOk) {
                    LogUtil.e(TAG, context.getString(R.string.engine_load_failed) + " (uciok timeout)");
                }

                File debugLogFile = new File(context.getFilesDir(), "engine_debug.log");
                if (debugLogFile.exists()) debugLogFile.delete();
                setOption("Debug Log File", debugLogFile.getAbsolutePath());

                File nnueFile = new File(context.getFilesDir(), "pikafish.nnue");
                if (nnueFile.exists()) {
                    setOption("EvalFile", nnueFile.getAbsolutePath());
                }

                // 引擎设置对齐桌面端 chessboard（lib.rs: Hash=64 / Threads=4 / ShowWDL=false，
                // Sixty Move Rule=false 于 Engine::new 设置；Slow Mover 桌面端不设置=默认 100）
                setOption("Hash", "64");
                setOption("Threads", "4");
                setOption("Sixty Move Rule", "false");
                // 对齐桌面端：MultiPV=3 提供次优候选（搜索会在同等时间内略降深度）
                setOption("MultiPV", "3");

                for (Map.Entry<String, String> option : configs.entrySet()) {
                    setOption(option.getKey(), option.getValue());
                }

                sendCommand("isready");
                waitCount = 0;
                while (!isReadyOk && waitCount < 50) {
                    Thread.sleep(100);
                    waitCount++;
                }

                initialized = true;
                LogUtil.d(TAG, "Engine fully initialized and ready.");

            } catch (Exception e) {
                LogUtil.e(TAG, context.getString(R.string.engine_load_failed) + e.getMessage(), e);
            }
        });
        engineThread.start();
    }

    private void deployAssets(Context context) {
        copyAssetAndRename(context, "pikafish.nnue", "pikafish.nnue");
    }

    private void copyAssetAndRename(Context context, String assetName, String targetName) {
        File targetFile = new File(context.getFilesDir(), targetName);
        try {
            InputStream is = context.getAssets().open(assetName);
            long assetSize = is.available();
            if (targetFile.exists() && targetFile.length() == assetSize) {
                is.close();
                return;
            }
            if (targetFile.exists()) targetFile.delete();
            FileOutputStream os = new FileOutputStream(targetFile);
            byte[] buffer = new byte[1024 * 64]; 
            int byteCount;
            while ((byteCount = is.read(buffer)) != -1) {
                os.write(buffer, 0, byteCount);
            }
            os.close();
            is.close();
        } catch (IOException e) {
            LogUtil.e(TAG, "Failed to copy asset: " + assetName, e);
        }
    }

    // This method is called from C++
    public void onEngineOutput(String line) {
        if (line == null || line.trim().isEmpty()) return;
        handleOutputLine(line);
    }

    public void handleOutputLine(String line) {
//        LogUtil.d(TAG, "Engine Output: " + line);
//        if (line.contains("\n")) {
//            for (String l : line.split("\n")) {
//                if (!l.trim().isEmpty()) handleOutputLine(l);
//            }
//            return;
//        }
        String[] args = line.trim().split("\\s+");
        if (args.length == 0) return;
        String cmd = args[0];

        if (cmd.equals("uciok")) {
            isUciOk = true;
        } else if (cmd.equals("readyok")) {
            isReadyOk = true;
        } else if (cmd.equals("info")) {
            // ... (keep existing info logic)
            Map<String, String> infos = new HashMap<>();
            List<String> infoTypes = Arrays.asList("depth", "seldepth", "time", "nodes", "pv", "multipv", "score", "currmove", "currmovenumber", "hashfull", "nps", "tbhits", "cpuload", "string", "refutation", "currline");
            for (int i = 1; i < args.length; i++) {
                if (args[i].equals("pv")) {
                    StringBuilder pv = new StringBuilder();
                    for (int j = i + 1; j < args.length; j++) {
                        pv.append(args[j]).append(" ");
                    }
                    infos.put(args[i], pv.toString().trim());
                    break;
                } else if (args[i].equals("string")) {
                    StringBuilder str = new StringBuilder();
                    for (int j = i + 1; j < args.length; j++) {
                        str.append(args[j]).append(" ");
                    }
                    infos.put(args[i], str.toString().trim());
                    break;
                } else if (args[i].equals("score")) {
                    if (i + 2 < args.length && args[i + 1].equals("cp")) {
                        infos.put(args[i], args[i + 2]);
                        i += 2;
                    } else if (i + 2 < args.length && args[i + 1].equals("mate")) {
                        // 额外保留原始的绝杀步数(可能为负数)，方便调用方做数值判断，不用去解析本地化后的字符串
                        infos.put("mate", args[i + 2]);
                        infos.put(args[i], context.getString(R.string.mate_in, args[i + 2]));
                        i += 2;
                    } else if (i + 1 < args.length) {
                        infos.put(args[i], args[i + 1]);
                        i++;
                    }
                } else {
                    if (args.length > i + 1 && !infoTypes.contains(args[i + 1])) {
                        infos.put(args[i], args[i + 1]);
                        i++;
                    } else {
                        infos.put(args[i], "");
                    }
                }
            }
            // 喂给在途搜索的候选收集器（MultiPV 名次/分数/首着）
            SearchCollector c = collector;
            if (c != null) c.feed(infos);
            if (infoEvent != null) infoEvent.onInfo(cmd, infos);
        } else if (cmd.equals("bestmove")) {
            String bestMove = args.length > 1 ? args[1] : "";
            String ponderMove = "";
            if (args.length > 3 && args[2].equals("ponder")) {
                ponderMove = args[3];
            }
            LogUtil.d(TAG, "Processing bestmove: " + bestMove + " sourceFen: " + this.currentFen);
            // 唤醒阻塞中的 searchSync；没有在途搜索时（stop 触发的残留回调）直接丢弃
            SearchResult p = this.pendingResult;
            if (p != null) {
                p.bestMove = bestMove;
                p.ponderMove = ponderMove;
                CountDownLatch l = this.pendingLatch;
                if (l != null) l.countDown();
            }
            this.currentFen = "";
        } else if (cmd.equals("option")) {
            // option 行仅握手期出现，无下游消费，不缓存
        } else if (cmd.equals("id")) {
            if (args.length >= 3) {
                String type = args[1].toLowerCase();
                if (type.equals("name")) {
                    engineName = line.substring(line.indexOf("name") + 5).trim();
                } else if (type.equals("author")) {
                    engineAuthor = line.substring(line.indexOf("author") + 7).trim();
                }
            }
        }
    }

    public void setOption(String key, String value) {
        if (value != null && !value.isEmpty()) {
            sendCommand("setoption name " + key + " value " + value);
        } else {
            sendCommand("setoption name " + key);
        }
    }

    public void sendCommand(String cmd) {
        sendCommandJNI(cmd);
    }

    /**
     * 阻塞式搜索（对齐桌面端 block_on(engine.search())）：发出 position+go 后等待
     * bestmove 才返回。与截屏循环在同一条线程串行调用，任何时刻最多一个在途搜索，
     * position/go 永远不会发进正在搜索的引擎——并发分析类竞态在结构上不存在。
     *
     * @return 引擎结果；非法局面/引擎未就绪/超时无结果返回 null
     */
    public synchronized SearchResult searchSync(String fen, double timeSec, int depth) {
        if (!isValidFen(fen)) {
            LogUtil.w(TAG, "非法 FEN，跳过分析: " + fen);
            return null;
        }
        // 引擎异步初始化（uciok 握手/NNUE 拷贝），首局分析最多等 5 秒就绪
        long waitStart = System.currentTimeMillis();
        while (!initialized) {
            if (System.currentTimeMillis() - waitStart > 5000) {
                LogUtil.w(TAG, "引擎未就绪，跳过分析");
                return null;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        pendingResult = new SearchResult();
        pendingLatch = new CountDownLatch(1);
        collector = new SearchCollector();
        this.currentFen = fen;
        sendCommand("position fen " + fen);
        // 对齐 Windows EngineHelper.StartAnalyze：depth 与 movetime 双重限制，谁先到谁生效
        if (timeSec > 0) {
            sendCommand("go movetime " + (int) (timeSec * 1000) + " depth " + depth);
        } else {
            sendCommand("go depth " + depth);
        }
        try {
            // movetime 是引擎硬上限，正常一定在此之前收到 bestmove；超时说明引擎卡死，
            // 发 stop 兜底后再短暂等待残留的 bestmove
            if (!pendingLatch.await((long) (timeSec * 1000) + 3000, TimeUnit.MILLISECONDS)) {
                LogUtil.w(TAG, "引擎搜索超时，强制停止");
                sendCommand("stop");
                pendingLatch.await(2000, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        SearchResult r = pendingResult;
        SearchCollector c = collector;
        pendingResult = null;
        pendingLatch = null;
        collector = null;
        if (r != null && c != null) {
            r.alternatives = c.alternatives(ALT_SCORE_GAP);
            r.score = c.bestScore;
            r.depth = c.bestDepth;
            LogUtil.i(TAG, "MultiPV 候选: 最优分=" + c.bestScore + " 深度=" + c.bestDepth
                    + " 名次首着=" + c.rankFirstMove + " 名次分=" + c.rankScore);
        }
        return r;
    }

    /** 单次搜索的候选收集器：按 MultiPV 名次记录各候选首着与分数（对齐桌面端 multipv 解析） */
    public static class SearchCollector {
        public int bestScore = Integer.MIN_VALUE;
        public int bestDepth = 0;
        public final java.util.Map<Integer, String> rankFirstMove = new java.util.HashMap<>();
        public final java.util.Map<Integer, Integer> rankScore = new java.util.HashMap<>();

        /** 引擎读线程在 info 分支调用 */
        public void feed(Map<String, String> infos) {
            String mpv = infos.get("multipv");
            String pv = infos.get("pv");
            if (mpv == null || pv == null || pv.isEmpty()) return;
            int rank;
            try {
                rank = Integer.parseInt(mpv.trim());
            } catch (NumberFormatException e) {
                return;
            }
            String firstMove = pv.split("\\s+")[0];
            // 分数解析：cp 为普通分；mate 为将杀步数编码（对齐桌面端 formatEval：
            // 正 N = 行棋方 N 步杀 → 30000−N；负 N = 行棋方 |N| 步被杀 → −30000−N）。
            // 此前 mate 解析失败写死 30000，导致绝杀局面的所有 MultiPV 候选
            //（包括必输的走法）分数都与最优相同、全部通过过滤显示为备选
            int score;
            String mateStr = infos.get("mate");
            if (mateStr != null) {
                try {
                    int n = Integer.parseInt(mateStr);
                    score = n > 0 ? 30000 - n : -30000 - n;
                } catch (NumberFormatException e) {
                    score = 0;
                }
            } else {
                try {
                    score = Integer.parseInt(infos.get("score"));
                } catch (NumberFormatException e) {
                    score = 0;
                }
            }
            if (rank == 1) {
                bestScore = score;
                try {
                    bestDepth = Integer.parseInt(infos.get("depth"));
                } catch (NumberFormatException ignored) {
                }
            }
            if (rank >= 2) {
                rankFirstMove.put(rank, firstMove);
                rankScore.put(rank, score);
            }
        }

        /** 次优候选：名次 ≥2 且分数与最优差 ≤ altScoreGap（桌面端 alt_score_gap 语义），带相对分数 */
        public List<AltCandidate> alternatives(int altScoreGap) {
            List<AltCandidate> alts = new ArrayList<>();
            if (bestScore == Integer.MIN_VALUE) return alts;
            for (int rank = 2; rank <= 9; rank++) {
                String mv = rankFirstMove.get(rank);
                if (mv == null) break;
                Integer sc = rankScore.get(rank);
                if (sc != null && sc >= bestScore - altScoreGap) {
                    alts.add(new AltCandidate(mv, sc - bestScore));
                }
            }
            return alts;
        }
    }

    /** 立即放弃在途搜索（服务销毁时用），让阻塞中的 searchSync 马上返回。
     *  不能加 synchronized：searchSync 持锁阻塞等待中，加锁会互相等死 */
    public void abortSearch() {
        CountDownLatch l = pendingLatch;
        if (l != null) {
            l.countDown();
        }
    }

    /**
     * 校验 FEN 是否为合法象棋局面：
     * 1. 必须有 10 行，每行列数之和为 9；
     * 2. 双王各一，且必须在九宫格内；
     * 3. 士必须在宫心五点、象必须在本方七个象位、兵/卒不得越位
     *    （位置规则对齐桌面端 chess.rs 的 board_check）；
     * 4. 各棋子数量不得超过合法上限（士象车马炮 ≤2、兵 ≤5）。
     * 识别错误时可能产生多余或错位棋子，Pikafish 原生层会写溢出内部数组导致
     * evaluate 崩溃，因此这里严格校验，非法局面直接跳过。
     */
    public boolean isValidFen(String fen) {
        if (fen == null || fen.isEmpty()) return false;
        String boardPart = fen.split(" ")[0];
        String[] rows = boardPart.split("/");
        if (rows.length != 10) return false;

        // 各棋子计数（小写=黑，大写=红）；x 为列(0..8)，y 为 FEN 行号(0..9，0=黑方底线)
        java.util.Map<Character, Integer> count = new java.util.HashMap<>();
        for (int y = 0; y < 10; y++) {
            String row = rows[y];
            int x = 0;
            for (int i = 0; i < row.length(); i++) {
                char c = row.charAt(i);
                if (Character.isDigit(c)) {
                    x += c - '0';
                    continue;
                }
                count.merge(c, 1, Integer::sum);
                // 位置合法性（对齐桌面端 board_check 的位置规则）
                switch (c) {
                    case 'k': // 黑将：九宫上区
                        if (y > 2 || x < 3 || x > 5) return false;
                        break;
                    case 'a': // 黑士：宫心五点
                        if (!((x == 3 && (y == 0 || y == 2)) || (x == 4 && y == 1)
                                || (x == 5 && (y == 0 || y == 2)))) return false;
                        break;
                    case 'b': // 黑象：七个象位
                        if (!((y == 0 && (x == 2 || x == 6)) || (y == 2 && (x == 0 || x == 4 || x == 8))
                                || (y == 4 && (x == 2 || x == 6)))) return false;
                        break;
                    case 'p': // 黑卒：未过河（y<5）只在己方半场（y≥3）且奇数列
                        if (y < 3) return false;
                        if (y < 5 && x % 2 == 1) return false;
                        break;
                    case 'K': // 红帅：九宫下区
                        if (y < 7 || x < 3 || x > 5) return false;
                        break;
                    case 'A': // 红仕：宫心五点
                        if (!((x == 3 && (y == 7 || y == 9)) || (x == 4 && y == 8)
                                || (x == 5 && (y == 7 || y == 9)))) return false;
                        break;
                    case 'B': // 红相：七个相位
                        if (!((y == 9 && (x == 2 || x == 6)) || (y == 7 && (x == 0 || x == 4 || x == 8))
                                || (y == 5 && (x == 2 || x == 6)))) return false;
                        break;
                    case 'P': // 红兵：未过河（y>4）只在己方半场（y≤6）且奇数列
                        if (y > 6) return false;
                        if (y > 4 && x % 2 == 1) return false;
                        break;
                }
                x++;
            }
            if (x != 9) return false;
        }

        // 双方将各一个
        if (cnt(count, 'K') != 1 || cnt(count, 'k') != 1) return false;
        // 各兵种数量上限
        if (cnt(count, 'A') > 2 || cnt(count, 'a') > 2) return false; // 士
        if (cnt(count, 'B') > 2 || cnt(count, 'b') > 2) return false; // 象
        if (cnt(count, 'N') > 2 || cnt(count, 'n') > 2) return false; // 马
        if (cnt(count, 'R') > 2 || cnt(count, 'r') > 2) return false; // 车
        if (cnt(count, 'C') > 2 || cnt(count, 'c') > 2) return false; // 炮
        if (cnt(count, 'P') > 5 || cnt(count, 'p') > 5) return false; // 兵
        return true;
    }

    private int cnt(java.util.Map<Character, Integer> m, char c) {
        Integer v = m.get(c);
        return v == null ? 0 : v;
    }
}
