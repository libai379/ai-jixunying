package com.guixing.jixunying.client

import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Attachment
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.CommandResult
import com.guixing.jixunying.model.Event
import com.guixing.jixunying.model.Message
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface ConnState {
    data object Local : ConnState
    data object Connecting : ConnState
    data class Connected(val host: String) : ConnState
    data class Failed(val reason: String) : ConnState
    data object NotPaired : ConnState
}

/**
 * 界面只认这个接口。桌面端是 LocalBackend（引擎就在本机），
 * 手机端是 RemoteBackend（经局域网连桌面）。两边界面代码完全一样。
 */
interface Backend {
    val store: ClientStore
    val conn: StateFlow<ConnState>
    suspend fun call(command: Command): CommandResult
    suspend fun upload(name: String, mime: String, bytes: ByteArray): Attachment?
    suspend fun fileBytes(id: String): ByteArray?
    /** 本机是不是引擎所在的电脑（决定显示「手机连接」还是「连接电脑」）。 */
    val isHost: Boolean
}

/** 把事件流落成界面可观察的状态。本地和远程共用。 */
class ClientStore {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()

    private val _notices = MutableSharedFlow<Event.Notice>(extraBufferCapacity = 16)
    val notices: SharedFlow<Event.Notice> = _notices.asSharedFlow()

    fun apply(event: Event) {
        when (event) {
            is Event.State -> _state.value = event.state
            is Event.Messages -> _messages.update { it + (event.convId to event.messages) }
            is Event.MessageUpsert -> _messages.update { all ->
                val list = all[event.message.convId] ?: return@update all
                val idx = list.indexOfFirst { it.id == event.message.id }
                val next = if (idx >= 0) list.toMutableList().also { it[idx] = event.message } else list + event.message
                all + (event.message.convId to next)
            }
            is Event.MessageDelta -> _messages.update { all ->
                val list = all[event.convId] ?: return@update all
                val idx = list.indexOfFirst { it.id == event.messageId }
                if (idx < 0) return@update all
                val old = list[idx]
                val next = list.toMutableList()
                next[idx] = old.copy(content = old.content + event.content, reasoning = old.reasoning + event.reasoning)
                all + (event.convId to next)
            }
            is Event.MessageRemoved -> _messages.update { all ->
                val list = all[event.convId] ?: return@update all
                all + (event.convId to list.filterNot { it.id == event.messageId })
            }
            is Event.ConversationRemoved -> _messages.update { it - event.convId }
            is Event.Notice -> _notices.tryEmit(event)
        }
    }

    fun hasMessages(convId: String) = _messages.value.containsKey(convId)
}
