package com.guixing.jixunying

import com.guixing.jixunying.engine.code.McpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MCP 客户端（2026-10-18，开发补齐第 12 样）：用一个 Python 写的假服务器（stdio，一行一个 JSON）走一遍
 * initialize → notifications/initialized → tools/list → tools/call。电脑上没有 python 就跳过。
 */
class McpTest {
    private val tmp = kotlin.io.path.createTempDirectory("jxy-mcp").toFile()

    @AfterTest
    fun cleanup() { tmp.deleteRecursively() }

    private fun fakeServer(): File = File(tmp, "fake_mcp.py").apply {
        writeText(
            """
            import sys, json
            for line in sys.stdin:
                m = json.loads(line)
                if "id" not in m:
                    continue
                meth = m.get("method")
                if meth == "initialize":
                    r = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}}, "serverInfo": {"name": "fake", "version": "1"}}
                elif meth == "tools/list":
                    r = {"tools": [{"name": "echo", "description": "原样返回", "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}}]}
                elif meth == "tools/call":
                    r = {"content": [{"type": "text", "text": "ECHO:" + m["params"]["arguments"]["text"]}]}
                else:
                    sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": m["id"], "error": {"code": -32601, "message": "no"}}) + "\n"); sys.stdout.flush(); continue
                sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": m["id"], "result": r}, ensure_ascii=False) + "\n")
                sys.stdout.flush()
            """.trimIndent(), Charsets.UTF_8,
        )
    }

    private fun hasPython() = runCatching { ProcessBuilder("cmd.exe", "/c", "python", "--version").start().waitFor() == 0 }.getOrDefault(false)

    @Test
    fun handshakeListAndCall() = runBlocking {
        if (!hasPython()) { println("没有 python，跳过"); return@runBlocking }
        val c = McpClient("fake", "python -u -X utf8 \"${fakeServer().absolutePath}\"", tmp)
        try {
            c.start()
            assertEquals(listOf("echo"), c.tools.map { it.name })
            assertEquals("ECHO:你好", c.call("echo", buildJsonObject { put("text", JsonPrimitive("你好")) }))
        } finally {
            c.close()
        }
        Unit
    }
}
