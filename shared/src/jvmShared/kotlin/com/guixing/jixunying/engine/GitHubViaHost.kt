package com.guixing.jixunying.engine

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.Command
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 手机的 GitHub 登录：浏览器点完同意跳回手机，手机把授权码经加密联机线路交给配对的电脑，电脑去换令牌、查用户资料，
 * 手机只拿回用户名和头像绑到自己的账号上（2026-10-24 用户选的方案一，docs/账号设计说明.md 第 2 节）。
 * 为什么：别的 App 是自己的服务器换令牌，我们没有服务器；国内手机直连 github.com 时通时不通（用户手机 App 被 VPN 排除在外，直连超时）。
 * 电脑没配对、没连上：返回 null，手机自己试着换（多半连不上，卡片上会写明要开着电脑）。
 *
 * 还有更省事的（用户提的）：电脑上登录过，手机连上电脑就把电脑绑的 GitHub 带过来，手机上不用登。只从电脑到手机一个方向；
 * 电脑没绑、手机自己绑了的不动。
 */
class GitHubViaHost(
    private val hub: Hub,
    private val engine: Engine,
    private val scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
) {
    fun start() {
        install()
        scope.launch {
            while (isActive) {
                runCatching { syncFromHost() }
                delay(3_000)
            }
        }
    }

    internal fun syncFromHost(): Boolean {
        val remote = hub.remote.value ?: return false
        if (remote.conn.value !is ConnState.Connected) return false
        val a = remote.store.state.value.account
        if (a.githubLogin.isBlank()) return false
        return engine.adoptGithub(Account.GithubUser(a.githubLogin, 0, a.githubName, a.githubAvatar))
    }

    fun install() {
        engine.githubViaHost = { code, redirect, verifier ->
            val remote = hub.remote.value
            if (remote == null || remote.conn.value !is ConnState.Connected) null
            else {
                val r = remote.call(Command.GitHubExchange(code, redirect, verifier))
                if (!r.ok) throw Account.AccountException(r.message)
                AppJson.decodeFromString(Account.GithubUser.serializer(), r.data)
            }
        }
    }
}
