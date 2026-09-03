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
 * chessdb.cn 云库查询（对齐桌面端 engine/chessdb.rs 的 querypv）。
 * 命中云库可秒出招并带完整后续线；未命中/网络失败按 unknown 处理，回退本地引擎。
 * 必须在工作线程调用（内部为阻塞 HTTP）。
 */
public final class ChessDB {
    private static final String URL = "http://www.chessdb.cn/chessdb.php";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/60.0.3112.113 Safari/537.36";
    private static final String REFERER = "https://www.chessdb.cn/query/";

    public static final String STATE_SUCCESS = "success";
    public static final String STATE_UNKNOWN = "unknown";
    public static final String STATE_INVALID = "invalid";

    public static class Result {
        public String state = STATE_UNKNOWN;    // success / unknown / invalid
        public String bestMove;                 // pv 第一着（4 字符 iccs，如 h2e2）
        public int score;
        public int depth;
        public List<String> pv = new ArrayList<>();
        public final String source = "云库";
    }

    /** 阻塞查询。任何网络/解析失败都按 unknown 返回，由调用方回退本地引擎 */
    public static Result query(String fen, int timeoutSec) {
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
            if (code != 200) return r;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            String text = sb.toString();
            if (text.endsWith("\0")) text = text.substring(0, text.length() - 1);

            // 响应协议（对齐桌面端）：""/"unknown"=库中无此局面；
            // "invalid board"/"checkmate"/"stalemate"=非法或终局；其余为 score:..,depth:..,pv:a1b2|c3d4
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
            return r;
        } catch (Exception e) {
            return r;   // unknown → 本地引擎
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
