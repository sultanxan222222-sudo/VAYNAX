package com.vaynax.messenger

import android.content.Context
import org.json.JSONObject
import okhttp3.*
import org.webrtc.*
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WebRTC transport for VAYNAX audio/video calls.
 * The caller must obtain RECORD_AUDIO (and CAMERA for video) permission before starting.
 * The server must be exposed through TLS as wss:// in production and provide reachable STUN/TURN.
 */
class VaynaxCallManager(
    context: Context,
    private val baseUrl: String,
    private val token: String,
    private val localUserId: String,
    private val listener: Listener
) {
    interface Listener {
        fun onIncomingCall(callId: String, callerId: String, video: Boolean)
        fun onCallState(state: String)
        fun onRemoteAudioTrack(track: AudioTrack)
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val egl = EglBase.create()
    private val factory: PeerConnectionFactory
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var camera: CameraVideoCapturer? = null
    private var callId: String? = null
    private var remoteUserId: String? = null
    private var incomingOffer: String? = null
    private var videoCall = false
    private var closed = false

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setEnableInternalTracer(false).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun connect() {
        val url = baseUrl.trimEnd('/').replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/v1/socket?token=" + token
        if (!url.startsWith("wss://") && !url.startsWith("ws://localhost") &&
            !url.startsWith("ws://127.0.0.1") && !url.startsWith("ws://[::1]")) {
            listener.onError("Для звонков через интернет требуется защищённый WSS-сервер.")
            return
        }
        socket?.cancel()
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onCallState("Соединение для звонков установлено")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val packet = JSONObject(text)
                    if (packet.optString("type") != "call_signal") return
                    val id = packet.optString("callId")
                    val sender = packet.optString("senderId")
                    val signal = packet.optJSONObject("signal") ?: return
                    when (signal.optString("kind")) {
                        "offer" -> {
                            if (callId == null) {
                                callId = id
                                remoteUserId = sender
                                incomingOffer = signal.optString("sdp")
                                videoCall = signal.optBoolean("video", false)
                                listener.onIncomingCall(id, sender, videoCall)
                            } else if (callId == id && remoteUserId == sender) {
                                incomingOffer = signal.optString("sdp")
                            }
                        }
                        "answer" -> if (id == callId) {
                            val sdp = SessionDescription(SessionDescription.Type.ANSWER, signal.optString("sdp"))
                            peer?.setRemoteDescription(object : SdpObserverAdapter() {
                                override fun onSetSuccess() { listener.onCallState("Звонок соединяется…") }
                                override fun onSetFailure(error: String) { listener.onError("Не удалось принять ответ: $error") }
                            }, sdp)
                        }
                        "ice" -> if (id == callId) {
                            val candidate = IceCandidate(signal.optString("sdpMid"), signal.optInt("sdpMLineIndex"), signal.optString("candidate"))
                            peer?.addIceCandidate(candidate)
                        }
                        "hangup", "reject", "busy" -> if (id == callId) endLocal(signal.optString("kind"))
                    }
                } catch (e: Exception) {
                    listener.onError("Ошибка сигнала звонка: ${e.message ?: "неизвестная ошибка"}")
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError("Нет соединения для звонков: ${t.message ?: "ошибка сети"}")
            }
        })
    }

    fun startCall(contactId: String, withVideo: Boolean) {
        if (closed) return
        if (socket == null) { listener.onError("Сначала подключи соединение для звонков."); return }
        if (peer != null || callId != null) { listener.onError("Сначала заверши текущий звонок."); return }
        callId = UUID.randomUUID().toString()
        remoteUserId = contactId
        videoCall = withVideo
        createPeer()
        createLocalMedia(withVideo)
        peer?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                peer?.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        sendSignal("offer", JSONObject().put("sdp", desc.description).put("video", withVideo))
                        listener.onCallState(if (withVideo) "Видеовызов…" else "Аудиовызов…")
                    }
                    override fun onSetFailure(error: String) { listener.onError("Не удалось установить offer: $error"); endLocal("error") }
                }, desc)
            }
            override fun onCreateFailure(error: String) { listener.onError("Не удалось создать звонок: $error"); endLocal("error") }
        }, MediaConstraints())
    }

    fun acceptCall() {
        val sdp = incomingOffer ?: run { listener.onError("Нет входящего вызова."); return }
        if (peer != null) return
        createPeer()
        createLocalMedia(videoCall)
        peer?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                peer?.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        peer?.setLocalDescription(object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                sendSignal("answer", JSONObject().put("sdp", desc.description))
                                incomingOffer = null
                                listener.onCallState("Вызов принят, устанавливается связь…")
                            }
                            override fun onSetFailure(error: String) { listener.onError("Не удалось установить answer: $error"); endLocal("error") }
                        }, desc)
                    }
                    override fun onCreateFailure(error: String) { listener.onError("Не удалось ответить: $error"); endLocal("error") }
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String) { listener.onError("Не удалось обработать входящий вызов: $error"); endLocal("error") }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    fun rejectCall() {
        sendSignal("reject", JSONObject())
        endLocal("reject")
    }

    fun hangUp() {
        sendSignal("hangup", JSONObject())
        endLocal("hangup")
    }

    fun localVideoTrack(): VideoTrack? = videoTrack
    fun eglContext(): EglBase.Context = egl.eglBaseContext

    private fun createPeer() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> listener.onCallState("Звонок активен")
                    PeerConnection.IceConnectionState.DISCONNECTED -> listener.onCallState("Связь прервана, пытаемся восстановить…")
                    PeerConnection.IceConnectionState.FAILED -> listener.onError("Не удалось установить соединение. Может потребоваться TURN-сервер.")
                    PeerConnection.IceConnectionState.CLOSED -> listener.onCallState("Звонок завершён")
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                sendSignal("ice", JSONObject().put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex).put("candidate", candidate.sdp))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {
                stream.audioTracks.firstOrNull()?.let(listener::onRemoteAudioTrack)
                stream.videoTracks.firstOrNull()?.let(listener::onRemoteVideoTrack)
            }
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                when (val track = receiver.track()) {
                    is AudioTrack -> listener.onRemoteAudioTrack(track)
                    is VideoTrack -> listener.onRemoteVideoTrack(track)
                }
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                when (val track = transceiver.receiver.track()) {
                    is AudioTrack -> listener.onRemoteAudioTrack(track)
                    is VideoTrack -> listener.onRemoteVideoTrack(track)
                }
            }
        })
        if (peer == null) listener.onError("Не удалось создать WebRTC-соединение.")
    }

    private fun createLocalMedia(withVideo: Boolean) {
        val connection = peer ?: return
        val audioConstraints = MediaConstraints()
        audioSource = factory.createAudioSource(audioConstraints)
        audioTrack = factory.createAudioTrack("vaynax_audio", audioSource)
        connection.addTrack(audioTrack, listOf("vaynax_stream"))
        if (withVideo) {
            val enumerator = Camera2Enumerator(appContext)
            val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
            if (cameraName == null) { listener.onError("Камера не найдена."); return }
            camera = enumerator.createCapturer(cameraName, null)
            val capturer = camera ?: return
            val helper = SurfaceTextureHelper.create("VaynaxCamera", egl.eglBaseContext)
            videoSource = factory.createVideoSource(capturer.isScreencast)
            capturer.initialize(helper, appContext, videoSource!!.capturerObserver)
            try { capturer.startCapture(640, 480, 24) }
            catch (e: Exception) { listener.onError("Не удалось включить камеру: ${e.message}"); return }
            videoTrack = factory.createVideoTrack("vaynax_video", videoSource)
            connection.addTrack(videoTrack, listOf("vaynax_stream"))
        }
    }

    private fun sendSignal(kind: String, payload: JSONObject) {
        val recipient = remoteUserId ?: return
        val id = callId ?: return
        val signal = JSONObject().put("kind", kind)
        val keys = payload.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            signal.put(key, payload.get(key))
        }
        val packet = JSONObject().put("type", "call_signal").put("recipientId", recipient)
            .put("callId", id).put("signal", signal)
        socket?.send(packet.toString())
    }

    private fun endLocal(reason: String) {
        camera?.let { runCatching { it.stopCapture() }; it.dispose() }
        camera = null
        videoTrack?.dispose(); videoTrack = null
        videoSource?.dispose(); videoSource = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        peer?.close(); peer?.dispose(); peer = null
        incomingOffer = null
        callId = null
        remoteUserId = null
        listener.onCallState(if (reason == "reject") "Вызов отклонён" else "Звонок завершён")
    }

    fun close() {
        closed = true
        endLocal("hangup")
        socket?.close(1000, "Activity closed")
        socket = null
        factory.dispose()
        egl.release()
        http.dispatcher.executorService.shutdown()
    }
}

private open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
