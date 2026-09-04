package com.yieye.xiangqi;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * chessdb.cn 云库查询（对齐桌面端 engine/chessdb.rs 的 querypv + queryall）。
 * 命中云库可秒出招并带完整后续线与次优候选；库中无此局面按 unknown 处理（正常应答），
 * 网络不可达按 server_error 处理（供调用方计熔断）；两者都回退本地引擎。
 * 必须在工作线程调用（内部为阻塞 HTTP）。
 */
public final class ChessDB {
    private static final String URL = "http://www.chessdb.cn/chessdb.php";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/60.0.3112.113 Safari/537.36";
    private static final String REFERER = "https://www.chessdb.cn/query/";

    public static final String STATE_SUCCESS = "success";
    public static final String STATE_UNKNOWN = "unknown";           // 云库已应答，但库中无此局面（正常）
    public static final String STATE_INVALID = "invalid";
    public static final String STATE_SERVER_ERROR = "server_error"; // 网络不可达/非 200（对齐桌面端 ServerInternalError）

    public static class Result {
        public String state = STATE_UNKNOWN;    // success / unknown / invalid
        public String bestMove;                 // pv 第一着（4 字符 iccs，如 h2e2）
        public int score;
        public int depth;
        public List<String> pv = new ArrayList<>();
        public List<EngineHelper.AltCandidate> alternatives = new ArrayList<>();
        public final String source = "云库";
    }

    /**
     * 阻塞查询：querypv 拿最优线 + queryall 拿次优候选（multipv>1 时，对齐桌面端）。
     * 状态语义（对齐桌面端 QueryState）：
     *   SUCCESS = 命中最优线；UNKNOWN = 云库已应答但库中无此局面（正常，不计熔断）；
     *   INVALID = 非法局面/终局；SERVER_ERROR = 网络不可达/非 200（计熔断）。
     */
    public static Result query(String fen, int timeoutSec, int multipv, int altScoreGap) {
        Result r = new Result();
        HttpURLConnection conn = null;
        try {
            String url = URL + "?action=querypv&board=" + URLEncoder.encode(fen, "UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(timeoutSec * 1000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", REFERER);

            int code = conn.getResponseCode();
            if (code != 200) {
                r.state = STATE_SERVER_ERROR;
                return r;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            String text = sb.toString();
            android.util.Log.d("ChessDB", "querypv raw: " + text);
            if (text.endsWith("\0")) text = text.substring(0, text.length() - 1);

            // 响应协议（对齐桌面端）：""/"unknown"=库中无此局面；
            // "invalid board"/"checkmate"/"stalemate"=非法或终局；其余为 score:..,depth:..,pv:..
            if (text.isEmpty() || "unknown".equals(text)) return r;
            if (text.startsWith("invalid") || "checkmate".equals(text) || "stalemate".equals(text)) {
                r.state = STATE_INVALID;
                return r;
            }
            for (String pair : text.split(",")) {
                int i = pair.indexOf(':');
                if (i <= 0) continue;
                String k = pair.substring(0, i);
                String v = pair.substring(i + 1);
                switch (k) {
                    case "score":
                        r.score = Integer.parseInt(v);
                        break;
                    case "depth":
                        r.depth = Integer.parseInt(v);
                        break;
                    case "pv":
                        for (String m : v.split("\\|")) {
                            if (!m.isEmpty()) r.pv.add(m);
                        }
                        break;
                }
            }
            if (!r.pv.isEmpty()) {
                r.bestMove = r.pv.get(0);
                r.state = STATE_SUCCESS;
            }

            // 2. queryall 获取次优候选首着（multipv > 1 时；对齐桌面端 chessdb.rs 第 2 步）
            if (r.state == STATE_SUCCESS && multipv > 1) {
                r.alternatives = queryAll(fen, timeoutSec, altScoreGap, r.score, r.bestMove, multipv - 1);
            }
            return r;
        } catch (Exception e) {
            // 网络/超时等异常：标记不可达（计熔断）；不覆盖已成功的结果
            if (STATE_UNKNOWN.equals(r.state)) r.state = STATE_SERVER_ERROR;
            return r;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * queryall：该局面所有合法应走的云端评分。
     * 响应按 | 分隔，每项形如 move:xxxx,score:nnn,rank:n；
     * 跳过与 best 相同的着法，只保留分数 ≥ 最优分 − altScoreGap 的，最多 maxAlt 个。
     * 任何失败都返回空列表（由调用方用本地引擎补充次优，不影响熔断计数）。
     */
    private static List<EngineHelper.AltCandidate> queryAll(String fen, int timeoutSec, int altScoreGap,
                                                            int cloudScore, String bestMove, int maxAlt) {
        List<EngineHelper.AltCandidate> alts = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            String url = URL + "?action=queryall&board=" + URLEncoder.encode(fen, "UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(timeoutSec * 1000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", REFERER);
            if (conn.getResponseCode() != 200) return alts;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            android.util.Log.d("ChessDB", "queryall raw: " + sb.toString());
            // 协议：move:xxxx,score:nnn,rank:n 项以 | 分隔
            for (String item : sb.toString().split("\\|")) {
                if (alts.size() >= maxAlt) break;
                if (!item.startsWith("move:")) continue;
                String[] kv = item.substring("move:".length()).split(",");
                String mv = kv[0];
                Integer sc = null;
                for (int i = 1; i < kv.length; i++) {
                    if (kv[i].startsWith("score:")) {
                        try {
                            sc = Integer.parseInt(kv[i].substring("score:".length()));
                        } catch (NumberFormatException ignored) {
                        }
                        break;
                    }
                }
                if (mv.isEmpty() || mv.equals(bestMove)) continue;
                // score 缺失时不过滤（对齐桌面端 map_or(true)）
                if (sc == null || sc >= cloudScore - altScoreGap) {
                    alts.add(new EngineHelper.AltCandidate(mv, sc == null ? 0 : sc - cloudScore));
                }
            }
        } catch (Exception ignored) {
        }
        return alts;
    }
}
