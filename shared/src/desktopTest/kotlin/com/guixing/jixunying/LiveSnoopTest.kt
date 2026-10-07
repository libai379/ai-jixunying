package com.guixing.jixunying

import com.guixing.jixunying.model.PairingCode
import com.guixing.jixunying.relay.MqttMulti
import com.guixing.jixunying.relay.pairWithHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/** 手动排查用：监听某台电脑在中转上的全部主题，同时用配对码试配一次，看消息有没有过去、电脑有没有回。 */
class LiveSnoopTest {
    @Test
    fun snoop() = runBlocking {
        val text = System.getenv("JXY_SNOOP") ?: return@runBlocking
        val code = PairingCode.decode(text) ?: error("配对码解析失败")
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val start = System.currentTimeMillis()
        val spy = MqttMulti(code.b, listOf("jxy1/${code.h}/#")) { topic, payload ->
            log += "${System.currentTimeMillis() - start}ms  $topic  ${payload.size}B"
        }
        spy.start()
        while (spy.readyCount.value < code.b.size) delay(200)
        println("监听就绪：${spy.readyCount.value} 个中转")
        val r = pairWithHost(code, "排查用")
        println("配对结果：" + (r.exceptionOrNull()?.message ?: "成功"))
        delay(2000)
        spy.stop()
        log.forEach { println(it) }
    }
}
