package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Command
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 真的用 GitHub 登录（docs/账号设计说明.md）。平时跳过；JXY_LIVE_GITHUB=数据目录 才跑：
 * 登录地址写到 数据目录\github-url.txt，在浏览器里打开、点同意，跳回本机后打印绑上的 GitHub 用户名。要本机有应用密钥文件（打包时放进程序）。
 */
class LiveAccountTest {
    @Test
    fun realGitHubSignIn() = runBlocking<Unit> {
        val path = System.getenv("JXY_LIVE_GITHUB") ?: return@runBlocking
        val dir = File(path).apply { mkdirs() }
        val e = Engine(Storage(dir))
        e.call(Command.SaveSettings(e.state.settings.copy(proxy = System.getenv("JXY_LIVE_PROXY") ?: "http://127.0.0.1:10809")))
        val r = e.call(Command.AccountGitHubSignIn)
        assertTrue(r.ok, r.message)
        File(dir, "github-url.txt").writeText(r.data)
        println("GITHUB_URL_WRITTEN")
        withTimeout(6 * 60_000) { while (e.state.account.githubSigningIn) delay(300) }
        println("ACCOUNT ${e.state.account}")
        assertTrue(e.state.account.githubLogin.isNotEmpty(), e.state.account.githubError)
        assertTrue(File(dir, "account/identity.json").readText().let { "gho_" !in it && "ghu_" !in it }, "令牌不存")
    }
}
