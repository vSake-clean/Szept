package pl.szept.call

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Połączenie z jednym uczestnikiem rozmowy grupowej. */
interface RtcPeer {
    suspend fun createOffer(): String
    suspend fun createAnswer(offer: String): String
    suspend fun applyAnswer(answer: String)
    fun close()
}

/** Wspólny mikrofon i kamera dla wielu połączeń (rozmowa grupowa). */
interface RtcSession {
    fun newPeer(listener: RtcEngine.Listener): RtcPeer
    fun setMuted(muted: Boolean)
    fun setVideoEnabled(on: Boolean)
    fun switchCamera()
    fun close()
}

fun interface RtcSessionFactory {
    fun create(video: Boolean, ice: List<IceServer>): RtcSession
}

/** Rozmowa grupowa przez WebRTC: każdy z każdym, ten sam dźwięk/obraz wysyłany do wszystkich. */
class WebRtcSession private constructor(private val ctx: Context, private val video: Boolean, private val ice: List<IceServer>) : RtcSession {

    companion object {
        fun factory(ctx: Context): RtcSessionFactory? =
            if (!WebRtcEngine.supported()) null else RtcSessionFactory { video, ice -> WebRtcSession(ctx.applicationContext, video, ice) }
    }

    private val f = WebRtcEngine.pcf(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + pl.szept.core.CrashLog.handler)
    private val audioSource: AudioSource = f.createAudioSource(MediaConstraints())
    private val audioTrack: AudioTrack = f.createAudioTrack("a0", audioSource)
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var texHelper: SurfaceTextureHelper? = null
    private val peers = java.util.concurrent.CopyOnWriteArrayList<Peer>()
    @Volatile private var closed = false
    var localVideo: VideoTrack? = null
        private set
    val frontCamera = MutableStateFlow(true)

    init { if (video) startCamera() }

    private fun startCamera() {
        try {
            val en = Camera2Enumerator(ctx)
            val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.firstOrNull() ?: return
            frontCamera.value = en.isFrontFacing(name)
            val cap = en.createCapturer(name, null) ?: return
            val helper = SurfaceTextureHelper.create("szept-gcam", WebRtcEngine.egl.eglBaseContext)
            val src = f.createVideoSource(false)
            cap.initialize(helper, ctx, src.capturerObserver)
            // kilka odbiorców naraz — mniejsza rozdzielczość
            cap.startCapture(640, 360, 20)
            capturer = cap; texHelper = helper; videoSource = src
            localVideo = f.createVideoTrack("v0", src)
        } catch (e: Exception) { Log.w("Szept", "kamera (grupa)", e) }
    }

    override fun newPeer(listener: RtcEngine.Listener): RtcPeer = Peer(listener).also { peers += it }

    override fun setMuted(muted: Boolean) { audioTrack.setEnabled(!muted) }

    override fun setVideoEnabled(on: Boolean) {
        localVideo?.setEnabled(on)
        try { if (on) capturer?.startCapture(640, 360, 20) else capturer?.stopCapture() } catch (e: Exception) { Log.w("Szept", "kamera", e) }
    }

    override fun switchCamera() {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) { frontCamera.value = front }
            override fun onCameraSwitchError(e: String?) {}
        })
    }

    override fun close() {
        if (closed) return
        closed = true
        peers.forEach { it.close() }
        scope.launch {
            delay(300)   // połączenia najpierw, źródła potem
            runCatching { capturer?.stopCapture() }
            runCatching { capturer?.dispose() }
            runCatching { videoSource?.dispose() }
            runCatching { audioSource.dispose() }
            runCatching { texHelper?.dispose() }
        }
    }

    inner class Peer(private val listener: RtcEngine.Listener) : RtcPeer {
        private val gathered = CompletableDeferred<Unit>()
        @Volatile private var done = false
        @Volatile private var connectedOnce = false
        val remoteVideo = MutableStateFlow<VideoTrack?>(null)

        private val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
            }
            override fun onIceCandidate(c: IceCandidate) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(t: RtpTransceiver) { (t.receiver.track() as? VideoTrack)?.let { remoteVideo.value = it } }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                if (done) return
                when (s) {
                    PeerConnection.PeerConnectionState.CONNECTED -> { connectedOnce = true; listener.onConnected() }
                    PeerConnection.PeerConnectionState.FAILED -> if (connectedOnce) listener.onDisconnected() else listener.onFailed("ice")
                    PeerConnection.PeerConnectionState.DISCONNECTED -> scope.launch {
                        delay(10_000)
                        if (!done && pc.connectionState() == PeerConnection.PeerConnectionState.DISCONNECTED) listener.onDisconnected()
                    }
                    else -> {}
                }
            }
        }

        private val pc: PeerConnection = run {
            val servers = ice.map { s ->
                PeerConnection.IceServer.builder(s.url).apply { if (s.user.isNotEmpty()) { setUsername(s.user); setPassword(s.pass) } }.createIceServer()
            }
            val cfg = PeerConnection.RTCConfiguration(servers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            }
            (f.createPeerConnection(cfg, observer) ?: throw IllegalStateException("WebRTC: brak połączenia")).also { p ->
                p.addTrack(audioTrack, listOf("s0"))
                localVideo?.let { p.addTrack(it, listOf("s0")) }
            }
        }

        private fun constraints() = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (video) "true" else "false"))
        }

        private suspend fun create(offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
            val obs = object : SdpObserver {
                override fun onCreateSuccess(d: SessionDescription) { if (cont.isActive) cont.resume(d) }
                override fun onSetSuccess() {}
                override fun onCreateFailure(e: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(e ?: "sdp")) }
                override fun onSetFailure(e: String?) {}
            }
            if (offer) pc.createOffer(obs, constraints()) else pc.createAnswer(obs, constraints())
        }

        private suspend fun set(local: Boolean, d: SessionDescription) = suspendCancellableCoroutine { cont ->
            val obs = object : SdpObserver {
                override fun onCreateSuccess(d: SessionDescription) {}
                override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
                override fun onCreateFailure(e: String?) {}
                override fun onSetFailure(e: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException(e ?: "sdp")) }
            }
            if (local) pc.setLocalDescription(obs, d) else pc.setRemoteDescription(obs, d)
        }

        private suspend fun local(): String {
            withTimeoutOrNull(6_000) { gathered.await() }
            return pc.localDescription?.description ?: throw IllegalStateException("brak opisu sesji")
        }

        override suspend fun createOffer(): String { set(true, create(true)); return local() }

        override suspend fun createAnswer(offer: String): String {
            set(false, SessionDescription(SessionDescription.Type.OFFER, offer))
            set(true, create(false))
            return local()
        }

        override suspend fun applyAnswer(answer: String) = set(false, SessionDescription(SessionDescription.Type.ANSWER, answer))

        override fun close() {
            if (done) return
            done = true
            peers.remove(this)
            remoteVideo.value = null
            scope.launch { runCatching { pc.close() }; runCatching { pc.dispose() } }
        }
    }
}
