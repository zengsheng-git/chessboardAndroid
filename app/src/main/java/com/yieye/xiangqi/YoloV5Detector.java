// Loads middle.onnx via ONNX Runtime (MIT); the model itself is derived from the
// VinXiangQi project (GPLv3). See THIRD_PARTY_NOTICES.md and LICENSE at the project root.
package com.yieye.xiangqi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.util.Log;
//
//import com.microsoft.onnxruntime.OnnxTensor;
//import com.microsoft.onnxruntime.OrtEnvironment;
//import com.microsoft.onnxruntime.OrtSession;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

public class YoloV5Detector implements ChessDetector {
    private static final String TAG = "YoloV5Detector";
    private final OrtEnvironment env;
    private final OrtSession session;

    private static final int INPUT_WIDTH = 640;
    private static final int INPUT_HEIGHT = 640;
    // 模型与桌面端相同（middle.onnx = large.onnx），但采集域不同：手机 720P 屏幕上
    // 棋子更小、走子高亮会压低置信度，桌面端实测稳定的 0.7 在手机上会系统性漏检，
    // 故下调到 0.35。噪声由 CLASS_LIMITS 与 AnalysisService 的帧级守卫兜底
    public static final float CONF_THRESHOLD = 0.35f;
    private static final float NMS_THRESHOLD = 0.45f;
    // 对齐桌面端 yolo.rs 的 LIMIT：每类棋子数量上限，超出只保留得分最高的——
    // 将军高亮/装饰环被误检成棋子时在此被结构性过滤，不再污染后续局面分析
    private static final int[] CLASS_LIMITS = {
            2, 2, 2, 1, 2, 2, 5,   // b_ma b_xiang b_shi b_jiang b_che b_pao b_bing
            2, 2, 2, 1, 2, 2, 5,   // r_che r_ma r_shi r_jiang r_xiang r_pao r_bing
            1                      // board
    };

    public static final String[] LABELS = {
            "b_ma", "b_xiang", "b_shi", "b_jiang", "b_che", "b_pao", "b_bing",
            "r_che", "r_ma", "r_shi", "r_jiang", "r_xiang", "r_pao", "r_bing", "board"
    };

    public YoloV5Detector(Context context, String modelPath) throws Exception {
        env = OrtEnvironment.getEnvironment();
        byte[] modelBytes = Utils.readAsset(context, modelPath);
        session = env.createSession(modelBytes);
    }

    @Override
    public List<YoloResult> detect(Bitmap bitmap) throws Exception {
        // 与桌面端 yolo.rs 一致的拉伸缩放到 640x640（resize_exact，不保持比例）
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_WIDTH, INPUT_HEIGHT, true);

        float[] imgData = new float[1 * 3 * INPUT_WIDTH * INPUT_HEIGHT];
        // 整图一次 getPixels 取回再逐格读：getPixel 每次都有 JNI 开销，
        // 640x640 ≈ 40 万次调用会占掉单帧数百毫秒（对齐 YoloV11Detector 的做法）
        int[] pixels = new int[INPUT_WIDTH * INPUT_HEIGHT];
        resizedBitmap.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT);
        resizedBitmap.recycle();

        int plane = INPUT_WIDTH * INPUT_HEIGHT;
        for (int i = 0; i < plane; i++) {
            int p = pixels[i];
            // 绝大多数导出到 ONNX 的 YOLOv5 模型使用 0-1 归一化
            imgData[i] = ((p >> 16) & 0xFF) / 255.0f;          // R
            imgData[plane + i] = ((p >> 8) & 0xFF) / 255.0f;   // G
            imgData[2 * plane + i] = (p & 0xFF) / 255.0f;      // B
        }

        OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(imgData), new long[]{1, 3, INPUT_WIDTH, INPUT_HEIGHT});
        
        OrtSession.Result result = session.run(Collections.singletonMap("images", inputTensor));
        float[][][] output = (float[][][]) result.get(0).getValue();

        return postProcess(output[0], bitmap.getWidth(), bitmap.getHeight());
    }

    private List<YoloResult> postProcess(float[][] output, int origW, int origH) {
        List<YoloResult> detections = new ArrayList<>();

        for (float[] row : output) {
            // 对齐桌面端 yolo.rs：唯一门槛 = objConf × 类别概率（桌面端 0.7）
            int maxClassIdx = -1;
            float maxClassProb = -1f;
            for (int i = 5; i < 20; i++) {
                if (row[i] > maxClassProb) {
                    maxClassProb = row[i];
                    maxClassIdx = i - 5;
                }
            }

            float finalScore = row[4] * maxClassProb;
            if (finalScore > CONF_THRESHOLD) {
                float cx = row[0] * origW / (float) INPUT_WIDTH;
                float cy = row[1] * origH / (float) INPUT_HEIGHT;
                float w = row[2] * origW / (float) INPUT_WIDTH;
                float h = row[3] * origH / (float) INPUT_HEIGHT;
                detections.add(new YoloResult(
                        new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2),
                        finalScore, maxClassIdx, LABELS[maxClassIdx]
                ));
            }
        }
        return applyClassLimits(applyNMS(detections));
    }

    /** 每类棋子只保留得分最高的 N 个（桌面端 LIMIT 语义） */
    private List<YoloResult> applyClassLimits(List<YoloResult> boxes) {
        int[] counts = new int[LABELS.length];
        List<YoloResult> out = new ArrayList<>(boxes.size());
        // applyNMS 已按分数降序，直接顺序保留即可
        for (YoloResult b : boxes) {
            if (counts[b.labelId] < CLASS_LIMITS[b.labelId]) {
                counts[b.labelId]++;
                out.add(b);
            }
        }
        return out;
    }

    // 将/帅常被将军高亮、装饰环等 UI 元素拉低置信度；对王类放宽门槛。
    // 下游（ChessBoardParser.resultsToFen）只在九宫格内且格位为空时采纳，误检风险可控
    public static final float KING_CONF_THRESHOLD = 0.2f;

    private List<YoloResult> applyNMS(List<YoloResult> boxes) {
        if (boxes.isEmpty()) return boxes;

        Collections.sort(boxes, (a, b) -> Float.compare(b.score, a.score));
        List<YoloResult> selected = new ArrayList<>();
        boolean[] removed = new boolean[boxes.size()];

        for (int i = 0; i < boxes.size(); i++) {
            if (removed[i]) continue;
            YoloResult best = boxes.get(i);
            selected.add(best);
            for (int j = i + 1; j < boxes.size(); j++) {
                if (!removed[j] && calculateIoU(best.rect, boxes.get(j).rect) > NMS_THRESHOLD) {
                    removed[j] = true;
                }
            }
        }
        return selected;
    }

    private float calculateIoU(RectF a, RectF b) {
        float interLeft = Math.max(a.left, b.left);
        float interTop = Math.max(a.top, b.top);
        float interRight = Math.min(a.right, b.right);
        float interBottom = Math.min(a.bottom, b.bottom);

        if (interLeft >= interRight || interTop >= interBottom) return 0f;

        float interArea = (interRight - interLeft) * (interBottom - interTop);
        float areaA = (a.right - a.left) * (a.bottom - a.top);
        float areaB = (b.right - b.left) * (b.bottom - b.top);

        return interArea / (areaA + areaB - interArea);
    }
}
