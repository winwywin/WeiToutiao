# test_microTT 项目长期记忆

## 项目概况
微头条发布器仿写练习项目。MVI 架构（PublishIntent → PublishViewModel → PublishState → View）。
最新 commit：`fe5c869` Day 17：图片压缩 + 分步上传模拟

## 已完成功能清单
| Commit 标签 | 功能 |
|---|---|
| Day 1-4 | MVC→MVI 架构迁移，PublishState/Intent/ViewModel 骨架 |
| Day 5 | MVI 主项目逻辑：PhotoPicker + 话题 + 九宫格 |
| Day 6 | SavedStateHandle 旋转恢复 |
| Day 7 | 空状态加号可见 + 动态相册上限 + 拖拽排序 |
| Day 8 (旧) | 打字卡顿修复 + Loading 状态修复 |
| Day 9 (旧) | 动态相册上限 + 取消按钮 |
| Day 10 | 全局警告清理 |
| Day 11 | 拖拽吸附 + 图片选择去重 |
| Day 12 | 拖拽空白区修复 + 自定义相册选择器 |
| Day 13 | @提及：AlertDialog + 蓝色 Span |
| Day 14 | 运行时照片权限 |
| Day 15 | 相册下采样缩略图（替代全分辨率） |
| Day 16 | 富文本：粗体/斜体/颜色，Span 序列化持久化 |
| Day 8+9 (新) | ImageCompressor + ThumbnailCache + ViewPager2 大图预览 |
| perf | GalleryPickerAdapter 协程线程池 / 单次fd解码 / 精准缓存清理 |
| Day 17 | 分步发布：压缩→上传→完成，水平进度条 |

## 架构约定
- MVI 单向数据流：View → PublishIntent → PublishViewModel → PublishState → View
- ViewModel 继承 `AndroidViewModel`（Day 17 升级，需 cacheDir）
- 图片缩略图用 ThumbnailCache (4MB LruCache)，key=uri.toString()
- 九宫格缩略图 400px，预览 1080px，发布压缩 1920px
- GalleryPickerAdapter/ImageGridAdapter 均用 CoroutineScope 注入，ViewHolder 持有 loadJob 取消
- 拖拽排序：松手吸附（onMove 返回 false，clearView 做单次 moveSingleItem）

## 已知问题 / 未来方向
- GalleryPickerAdapter.submitList 仍用 notifyDataSetChanged（可升级为 DiffUtil）
- 上传模拟 delay(600ms/张)，如需更真实可换 OkHttp 上传 + FileRequestBody
- cacheDir/compress/ 仅在发布完成时清理，异常中断不清理（可在 onCleared 里补）

## 关键文件路径
- `app/src/main/java/com/example/test_micrott/`
  - `model/` — PublishState, PublishIntent, SpanDescriptor
  - `util/` — ImageCompressor, ThumbnailCache
  - `view/` — MainActivity, ImageGridAdapter, GalleryPickerAdapter, ImagePreviewActivity...
  - `viewmodels/PublishViewModel.kt`
- `app/src/main/res/layout/activity_main.xml`

## 编译注意事项
- Kotlin daemon 经常崩溃，走 fallback 策略可正常编译（无需处理）
- `onViewRecycled` override 必须用具体 ViewHolder 类型，不能用泛型（Kotlin 1.9+ 严格检查）
- `adapterPosition` 替代 `absoluteAdapterPosition`（旧版 RecyclerView 兼容）
