# Changelog

## 1.8.1-dh3.30

- 近景区域排序默认关闭：首次实测未证明净收益。F6 仍可手动切换，`-Doculus.experimentalTerrainOrder=true` 可在启动时启用实验。
- 根据链接后 depthtex2 uniform 的实际使用情况跳过未消费的逐帧整屏深度复制。首次创建及 resize 仍初始化；通过公开 getter 访问的外部消费者保留复制。`-Doculus.legacyDepthCopies=true` 恢复原路径。
- 不修改第三方光影内容、画质设置、几何或着色计算。此项减少冗余搬运，尚无游戏实测帧率结论。

## 1.8.1-dh3.29

- 对兼容的多重绘制后端，将近景不透明与裁剪透明区域按中心距离稳定排序，尝试减少被遮挡片元的计算。限定 ComplementaryReimagined_r5.8.1.zip 和标准深度状态；透明混合与阴影路径保持原顺序。
- 配合诊断模块 0.8.0，通过 F6 即时切换、F8 记录实际重排计数。默认启用，启动参数 `-Doculus.legacyTerrainOrder=true` 可关闭。
- 构建及排序检查通过；游戏内画面与实际性能收益待验证，详见 docs/近景区域提交顺序优化.md。

## 1.8.1-dh3.28

- Add an optional F8 diagnostics observer for near-terrain layers, shadow terrain/total and shader fullscreen stages. The companion 0.7.0 installs callbacks only during capture; no rendering settings or geometry are changed.

## 1.8.1-dh3.27

- Preserve screen-space scale, offset and asymmetric perspective when extending the DH depth range. Retain the previous projection path for unsupported matrices or `-Doculus.dh.legacyProjection=true`.
- Supply `dhModelView`, `dhModelViewInverse`, `dhPreviousModelView`, `dhNearPlane` and `dhFarPlane`; reset history with the pipeline and advance once per frame.
- Run projection-depth and matrix-history checks as part of Gradle check. In-game validation remains pending.

## 1.8.1-dh3.26

- Expose the actual DH projection/model-view matrix to the optional tight mesh-bounds culling experiment in dh-performance-compat 0.4.0. Restrict the bridge to the audited ComplementaryReimagined_r5.8.1.zip opaque pass; other passes and shadow rendering return no matrix. Experiment defaults off and requires in-game validation.

## 1.8.1-dh3.25

- Supply the missing `dhPreviousProjection` uniform used by Complementary's distant-terrain TAA reprojection. Advance history once per frame and initialize it from the current projection on first use or a frame discontinuity. Visual verification pending.

## 1.8.1-dh3.24

- Reuse the DH vertex format on OpenGL 4.3+ and update only its vertex-buffer binding per draw. Preserve vertex data, index order, shader inputs and the older OpenGL path.
- JVM option `-Doculus.dh.legacyVertexBinding=true` restores the previous binding path for diagnostics. In-game visual/performance validation is pending.

## 1.8.1-dh3.23

- Added Distant Horizons 3.2.x shader support for Minecraft 1.16.5.
- Added DH framebuffer, shader program, depth sampler, opaque pass, and transparent pass integration.
- Reloads DH rendering state when changing dimensions or shader packs.
- Fixed startup when Distant Horizons is not installed.
- Verified with and without Distant Horizons, with shaders disabled, while switching shader packs, and in multiple dimensions.
- Verified on AMD and NVIDIA graphics hardware.
