package com.guixing.jixunying.engine

/**
 * 这条命令是哪台配对手机经中转发来的（RelayHost 放进协程上下文）。
 * 用在要记「谁做的」的地方：手机遥控电脑时扫码绑的微信记成那台手机绑的、手机来拿微信绑定时只给它该拿的（2026-10-30 多微信）。
 */
class RemoteDevice(val id: String) : kotlin.coroutines.AbstractCoroutineContextElement(RemoteDevice) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<RemoteDevice>
}
