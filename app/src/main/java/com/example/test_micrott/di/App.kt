package com.example.test_micrott.di

import android.app.Application

/**
 * 自定义 Application，提供全局 DI 容器入口。
 *
 * 用法：
 *   val container = (applicationContext as App).container
 */
class App : Application() {

    /** 唯一的 DI 容器实例，随进程生命周期存活 */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }
}
