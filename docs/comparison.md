# 弈眼（Android）与桌面端 chessboard 实现对照

> 对照基准：桌面端 `chessboard`（Tauri + Rust，`server/src/`）vs Android 弈眼 0.0.19（`app/src/main/java/com/yieye/xiangqi/`）。
> 结论先行：**识别层、状态机语义、阻塞式搜索已对齐桌面端**；Android 多出移动端补偿机制；
> 功能上 Android 尚缺云库之外的**完整候选展示历史**、**九宫位置校验已于 0.0.19 补齐**等，详见文末差异清单。

## 一、总体架构

| | 桌面端 chessboard | 弈眼 Android |
|---|---|---|
| 形态 | Tauri 桌面应用（Rust 后端 + Vue 前端） | Android Service（Java）+ 悬浮窗 |
| 截屏 | xcap **拉式**窗口捕获（每拍必有画面） | VirtualDisplay+ImageReader **推式** + 最近帧缓存（等价改造） |
| 识别 | ONNX Runtime，`large.onnx`，置信度 0.7，NMS 内嵌按类 LIMIT | ONNX Runtime，`middle.onnx`（= `large.onnx`，MD5 一致），0.7 + `applyClassLimits`，王类九宫内 0.2 |
| 引擎 | Pikafish 独立进程（UCI 管道），Threads=4 | Pikafish JNI 进程内，Threads=1 + Slow Mover 50 |
| 搜索 | `block_on(engine.search())` 阻塞，单搜索在飞 | `EngineHelper.searchSync` latch 阻塞，单搜索在飞 |
| 循环 | `process_analysis_loop` 200ms 节拍单线程 | `loopForever` 300ms 节拍单线程 |
| 引擎生命周期 | 应用级单例（`OnceLock`），不随监听重建 | **进程级单例**（0.0.18 修复：原随会话重建导致回调错位、搜索全超时） |
| 云库 | chessdb querypv（5s 超时，命中秒出招） | ✅ `ChessDB.query`（0.0.19 起，带 5 分钟熔断） |
| MultiPV 次优候选 | ✅ multipv=3 + alt_score_gap=300 | ✅ 0.0.19 起（悬浮窗"备选:"行） |

## 二、状态机与行棋方推断对照

桌面端 `worker.rs` 状态：Initial / StartPos / OurTurn / OpponentTurn / Invalid。
Android 0.0.18+：INITIAL / GENERIC / INVALID（StartPos 并入 GENERIC，行为等价）。

| 情形 | 桌面端 | Android |
|---|---|---|
| 棋盘未变化 | 保持状态 | `boardPart` 相同 → 跳过 |
| 命中预期棋盘 | 分析 `expect_move.camp.opposite()` | 相同（`expectNextTurn`） |
| 延迟确认失败 | sleep confirm_interval，保持 | 相同（200ms） |
| board_check 不过 | 保持 | `isValidFen` 失败 → 跳过 |
| diff=2（一空一占） | Move：走子方 = 消失格棋子 | 相同（`movedChess`） |
| diff=1 | One：计数，3 次重锚定 | 半步（起点消失）视为走子立即分析；幻影出现则跳过计数 |
| diff≥3 | Unknown：重锚定 | 子数不变 → 偶数步合并帧（行棋方不变）；恰一方少一子 → 轮到被吃方；其余 → 底部方兜底；连续 8 拍噪声强制兜底 |

> 差异说明：Android 的识别（手机端腾讯象棋 UI 特效）比桌面端（PC 象棋软件窗口）抖动大，
> 因此在桌面端语义之上增加了**噪声帧不分析不锚定**与**合并帧奇偶/吃子推断**两层加固。

## 三、识别后处理对照

| | 桌面端 yolo.rs | Android |
|---|---|---|
| 置信度门槛 | 0.7（单门：obj × 类别概率） | 0.7；王类九宫内放宽到 0.2（将军高亮/装饰环拉低王置信度的补偿） |
| 每类数量上限 | NMS 内嵌 LIMIT（车马炮士象 ≤2、兵 ≤5、board 1） | NMS 后 `applyClassLimits` 同规则 |
| 整帧校验 | `board_check`：双王 + 上限 + **九宫/兵卒位置** | `isValidFen`：0.0.19 起对齐九宫/兵卒位置规则 |

## 四、云库（chessdb）对照

| | 桌面端 | Android（0.0.19 起） |
|---|---|---|
| 接口 | `querypv?board=<fen>` | 相同 |
| 响应 | ""/unknown/invalid board/checkmate/stalemate/score+depth+pv | 相同协议解析 |
| 命中行为 | 直接采用 pv 首着，source=云库 | 相同（悬浮窗推荐后缀 `[云库]`） |
| 失败回退 | 落本地引擎 | 相同；另有**熔断**：连续 2 次不可达 → 5 分钟内直落本地 |

## 五、Android 相对桌面端多出的机制（移动端补偿）

- 看门狗（ONNX 推理挂死 30s 自愈：dump 堆栈 + 重建检测器 + 换线程）
- 截屏最近帧缓存（推式截屏的静止期饥饿补偿）
- 丢王一致性修复 + 跨会话王位记忆（SharedPreferences）
- 幻影/半步/不成对帧的显式拦截与 pHash 回滚
- pHash 去重（功耗优化；桌面端每拍全量识别）
- 引擎进程内 JNI（无子进程；代价是引擎崩溃会波及主进程，由 isValidFen 前置拦截兜底）

## 六、Android 尚缺 / 可选跟进

- 完整棋盘镜像 UI 与着法列表（当前仅悬浮窗文本）
- config.json 式的可配置化（循环节拍/门槛/云库开关目前为常量）
- 桌面端 GPU 执行提供（Android 为 CPU EP）
- 残局挑战装饰环的模型微调（识别根治方案）
