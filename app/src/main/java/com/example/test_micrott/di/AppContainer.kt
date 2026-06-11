package com.example.test_micrott.di

import android.content.Context
import com.example.test_micrott.data.DraftManager
import com.example.test_micrott.repository.DraftRepository

/**
 * 手动 DI 容器。
 *
 * 不引入 Hilt/Koin 等第三方 DI 框架，只做一个简单的 Service Locator，
 * 统一管理各层依赖，方便后续单元测试时注入 Mock。
 */
class AppContainer(private val context: Context) {

    /** 草稿仓库单例（SQLite DB + 文件缓存） */
    val draftRepository: DraftRepository by lazy {
        DraftManager(context)
    }
}
