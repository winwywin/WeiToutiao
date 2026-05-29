package com.example.test_micrott.view // 1. 声明当前文件所在的包命名空间（请务必与你本地实际的文件夹单复数对齐！）

// ==========================================
// 2. Android 系统与 Jetpack 官方核心依赖库导入区
// ==========================================
import android.os.Bundle // 导入系统组件，用于承载 Activity 销毁重建时的现场恢复数据
import android.util.Log // 导入系统日志工具，用于在 Logcat 输出单向数据流的合围轨迹
import android.view.View // 导入视图根类，用于控制 View.VISIBLE 与 View.GONE 的状态物理切换
import androidx.activity.viewModels // 导入架构扩展组件，支持使用 by viewModels() 委托机制注入大脑
import androidx.appcompat.app.AppCompatActivity // 导入官方标准兼容基类，作为承载页面的核心物理容器
import androidx.core.widget.doAfterTextChanged // 导入文本监听增强扩展，用于极简、零延迟捕获键盘打字动作
import androidx.lifecycle.lifecycleScope // 导入协程生命周期感知域，当页面销毁时自动掐断并回收内部所有协程
import kotlinx.coroutines.launch // 导入协程启动器，用于在生命周期域内开辟轻量级线程去捕捉数据流

// ==========================================
// 3. 本地项目业务组件（请根据你本地实际的单复数路径进行微调 ⚠️）
// ==========================================
import com.example.test_micrott.databinding.ActivityMainBinding// 导入由 XML 自动生成的 ViewBinding 物理视图绑定类
import com.example.test_micrott.model.PublishIntent // 导入密封动作舱（如果本地是model，请删掉此处的s）
import com.example.test_micrott.model.PublishState // 导入唯一状态源数据类（如果本地是model，请删掉此处的s）
import com.example.test_micrott.viewmodels.PublishViewModel // 导入MVI控制中枢大脑（如果本地是viewmodel，请删掉此处的s）

/**
 * 【提线木偶层 - MainActivity】
 * 规范修正说明：
 * 1. 物理移除未使用的 kotlinx.coroutines.flow.collect 导入。
 * 2. 严格遵循 Kotlin 命名规范，将大写 TAG 改为小写 tag。
 * 3. 核心输入框变量全量由 etMainInput 改为 ktg，与逆向后的 activity_main.xml 的 ID 严丝合缝闭环。
 */
class MainActivity : AppCompatActivity() {

    // 物理视图绑定对象声明（使用 lateinit 延迟初始化，规避空指针异常并消灭 findViewById）
    private lateinit var binding: ActivityMainBinding

    // 依托 activity-ktx 官方库实现的 ViewModel 生命周期隔离委托注入，确保屏幕旋转时大脑不被销毁
    private val viewModel: PublishViewModel by viewModels()

    // ⚠️ 规范修复：遵循 Kotlin 变量命名规范，采用小驼峰驼峰或普通小写标识符 tag
    private val tag = "MVI_FRAMEWORK"

    /**
     * Activity 生命周期的核心物理入口
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState) // 调用父类标准模板方法，执行系统级的页面初始化准备

        // 执行 ViewBinding 充气泵操作，将布局 XML 物理文件瞬间编译实例化为内存对象
        binding = ActivityMainBinding.inflate(layoutInflater)

        // 将充气实例化后的根节点布局（CoordinatorLayout）喂给系统，正式将其平铺挂载到手机屏幕上
        setContentView(binding.root)

        // 【合围总攻战术链】
        initIntentEmitters() // 第一步：架设用户操作的“事件发射器”，收拢键盘打字与按钮戳击动作
        observeUiState()     // 第二步：架设状态流的“合围监听器”，建立与 ViewModel 大脑的专属通信管道
    }

    /**
     * 【战术模块一：事件发射器架设（View -> ViewModel）】
     * * 职责：机械地拦截用户在屏幕上触发的任何微小动作，不允许在本地执行逻辑，必须一秒打包成不可变 Intent 轰炸给 ViewModel。
     */
    private fun initIntentEmitters() {
        // 1. ⚙️ 修复对齐：拦截用户在编辑内容区（对应逆向报告中的 @id/ktg）的打字或删除文本动作
        binding.ktg.doAfterTextChanged { text ->
            // 将最新改变的文本内容包装进不可变的 TextChanged 意图舱，像子弹一样塞进 sendIntent 入口
            viewModel.sendIntent(PublishIntent.TextChanged(text.toString()))
        }

        // 2. ⚙️ 修复对齐：拦截用户点击顶部标题栏右侧“发布”按钮的戳击动作
        binding.btnPublish.setOnClickListener {
            // 将点击发布动作包装进 ClickPublish 动作舱，直接向 ViewModel 大脑下达发布军令
            viewModel.sendIntent(PublishIntent.ClickPublish)
        }
    }

    /**
     * 【战术模块二：只读状态流监听（ViewModel -> View）】
     * * 职责：使用协程生命周期感知域安全挂起监听，一旦大脑吐出任何全新修订的账本，立刻无脑触发界面重新渲染。
     */
    private fun observeUiState() {
        // 绑定 Activity 专属的协程生命周期域（当用户退出应用或销毁页面时，内部子协程物理切断，绝对不留内存泄漏）
        lifecycleScope.launch {
            // 实时收集大脑中对外只读暴露的只读 StateFlow 账本数据（注意：Kotlin中Flow的collect是一个挂起函数，不需要单独import它的包）
            viewModel.state.collect { state ->
                // 每当账本发生变动（哪怕只是少了一个字），立刻拉起全量渲染引擎
                render(state)
            }
        }
    }

    /**
     * 【战术模块三：机械化增量渲染引擎】
     * * 职责：承接只读账本，机械化刷新 UI 呈现。重点突破光标回弹屏障。
     */
    private fun render(state: PublishState) {
        // 在控制台打印出瀑布流日志，直观反映当前界面正在被哪个具体的状态数据所驱动
        Log.d(tag, "📺 [View] 收到新 State 账本，开始执行全量机械化渲染: $state")

        // 1. 刷新“发布”按钮的亮灭可用性（严格根据状态源中的布尔值驱动，实现组件解耦）
        binding.btnPublish.isEnabled = state.isPublishButtonEnabled

        // 2. 刷新全局 Loading 进度条遮罩（当状态为发布中时显现拦截二次点击，日常隐藏不抢占事件）
        binding.progressBarOverlay.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        // ==========================================
        // 🛡️ 核心护城河代码：精细化增量视图过滤闸门
        // ==========================================
        // ⚙️ 修复对齐：将 etMainInput 全量修正为线上真实的 ktg 变量
        if (binding.ktg.text.toString() != state.text) {
            // 场景触发：首屏加载存盘草稿，或者在 Day 3 中硬核拦截删除键从而整块剔除 #话题# 标签时，内容真发生错位变动
            binding.ktg.setText(state.text)
            // 闸门合围：一旦强制执行了 setText，Android 原生会将光标甩到最前，我们必须用 setSelection 物理级把光标重新吸附到文字最末尾
            binding.ktg.setSelection(state.text.length)
        }
    }
}