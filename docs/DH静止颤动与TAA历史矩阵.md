# 静止颤动：TAA 历史矩阵缺失修复

更新：用户已确认 dh3.25 下当前场景不再颤动；后续 F8 记录为 1790003373440-84ff82c2。当前场景视觉验证通过，其他光影/维度仍未全面验证。

2026-09-21，dh3.25。

用户确认人物和鼠标静止时仍有颤动。日志确认加载 dh3.24 + 诊断 0.2.1，最新记录 1790002782693-a228417a 的合并开关为 false，累计合并量为零。不能继续将现象归因于正在运行的合面。

当前 Complementary Reimagined r5.8.1 的 dh_terrain 在顶点阶段执行 TAAJitter；lib/antialiasing/taa.glsl 对 DH 深度使用 Reprojection(dhCoord, dhProjectionInverse, dhPreviousProjection)。兼容版此前 CommonUniforms 只提供 dhProjection 与 dhProjectionInverse，全工程未注册 dhPreviousProjection。缺失的 uniform 会破坏该重投影路径，是已确认的兼容性缺口；它是否完全解释用户颤动仍需实景验证。

修复在桥接层注册 dhPreviousProjection，历史矩阵使用真实 DH 渲染投影更新，同一 frameCounter 内只推进一次。首帧和帧不连续时以当前投影初始化历史，管线建立/销毁时重置，无 DH 时返回单位矩阵。不修改用户光影文件、不关闭 TAA。

ProjectionHistoryCheck 验证首帧、相邻帧、同帧透明 pass、不引用外部数组、帧间断、重置与计数回绕；构建和 diff 检查通过。尚未完成游戏视觉验证，未声称已彻底解决颤动。

最新采集：3432 帧，P50/P95/P99 为 8.668/11.887/13.603 ms；不透明 GPU 均值 2.479 ms，透明 0.169 ms，有一帧超过 100 ms。样本不是受控的 .23/.24 对比，不据此宣称绑定优化收益。视觉颤动与帧时间尖峰分别调查。

验证：重启后在原场景保持鼠标/人物静止，观察远山轮廓和表面；再缓慢转动，检查拖影。无需新档或新一轮 F8。若问题仍在，再结合关闭光影的对照，核查深度、时间抖动和后台网格替换。
