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
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Rozmowa przez WebRTC: dźwięk (Opus) i obraz szyfrowane DTLS-SRTP, bezpośrednio między telefonami. */
class WebRtcEngine private constructor(
    private val ctx: Context, private val video: Boolean, ice: List<IceServer>, private val listener: RtcEngine.Listener,
) : RtcEngine {

    companion object {
        private const val TAG = "Szept"
        private var pcf: PeerConnectionFactory? = null
        val egl: EglBase by lazy { EglBase.create() }
        @Volatile private var libState = 0

        /** Czy biblioteka WebRTC jest dostępna (telefony ARM). */
        fun supported(): Boolean {
            if (libState == 0) libState = try { System.loadLibrary("jingle_peerconnection_so"); 1 } catch (t: Throwable) { -1 }
            return libState == 1
        }

        fun factory(ctx: Context): RtcFactory? =
            if (!supported()) null else RtcFactory { video, ice, l -> WebRtcEngine(ctx.applicationContext, video, ice, l) }

        @Synchronized
        internal fun pcf(ctx: Context): PeerConnectionFactory = pcf ?: run {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
            val adm = JavaAudioDeviceModule.builder(ctx)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
                .also { pcf = it }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + pl.szept.core.CrashLog.handler)
    private val f = pcf(ctx)
    private val gathered = CompletableDeferred<Unit>()
    @Volatile private var closed = false
    @Volatile private var connectedOnce = false
    private val audioSource: AudioSource = f.createAudioSource(MediaConstraints())
    private val audioTrack: AudioTrack = f.createAudioTrack("a0", audioSource)
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var texHelper: SurfaceTextureHelper? = null
    var localVideo: VideoTrack? = null
        private set
    /** Obraz rozmówcy (gdy przyjdzie). */
    val remoteVideo = MutableStateFlow<VideoTrack?>(null)
    /** Czy podgląd z przedniej kamery (lustrzane odbicie). */
    val frontCamera = MutableStateFlow(true)

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
        override fun onTrack(t: RtpTransceiver) {
            (t.receiver.track() as? VideoTrack)?.let { remoteVideo.value = it }
        }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
            if (closed) return
            when (s) {
                PeerConnection.PeerConnectionState.CONNECTED -> { connectedOnce = true; listener.onConnected() }
                PeerConnection.PeerConnectionState.FAILED -> if (connectedOnce) listener.onDisconnected() else listener.onFailed("ice")
                PeerConnection.PeerConnectionState.DISCONNECTED -> scope.launch {
                    // chwilowa utrata sieci — WebRTC często sam wraca
                    delay(10_000)
                    if (!closed && pc.connectionState() == PeerConnection.PeerConnectionState.DISCONNECTED) listener.onDisconnected()
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
        f.createPeerConnection(cfg, observer) ?: throw IllegalStateException("WebRTC: brak połączenia")
    }

    init {
        pc.addTrack(audioTrack, listOf("s0"))
        if (video) startCamera()
    }

    private fun startCamera() {
        val en = Camera2Enumerator(ctx)
        val names = en.deviceNames
        val name = names.firstOrNull { en.isFrontFacing(it) } ?: names.firstOrNull() ?: return
        frontCamera.value = en.isFrontFacing(name)
        val cap = en.createCapturer(name, null) ?: return
        val helper = SurfaceTextureHelper.create("szept-cam", egl.eglBaseContext)
        val src = f.createVideoSource(false)
        cap.initialize(helper, ctx, src.capturerObserver)
        cap.startCapture(960, 540, 24)
        val track = f.createVideoTrack("v0", src)
        pc.addTrack(track, listOf("s0"))
        capturer = cap; texHelper = helper; videoSource = src; localVideo = track
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

    /** Czekamy na kandydatów ICE (adresy), żeby wysłać je w jednej kopercie. */
    private suspend fun localWithCandidates(): String {
        withTimeoutOrNull(6_000) { gathered.await() }
        return pc.localDescription?.description ?: throw IllegalStateException("brak opisu sesji")
    }

    override suspend fun createOffer(): String {
        set(true, create(offer = true))
        return localWithCandidates()
    }

    override suspend fun createAnswer(offer: String): String {
        set(false, SessionDescription(SessionDescription.Type.OFFER, offer))
        set(true, create(offer = false))
        return localWithCandidates()
    }

    override suspend fun applyAnswer(answer: String) {
        set(false, SessionDescription(SessionDescription.Type.ANSWER, answer))
    }

    override fun setMuted(muted: Boolean) { audioTrack.setEnabled(!muted) }

    override fun setVideoEnabled(on: Boolean) {
        localVideo?.setEnabled(on)
        try { if (on) capturer?.startCapture(960, 540, 24) else capturer?.stopCapture() } catch (e: Exception) { Log.w(TAG, "kamera", e) }
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
        scope.launch {
            runCatching { capturer?.stopCapture() }
            runCatching { pc.close() }
            runCatching { capturer?.dispose() }
            runCatching { pc.dispose() }
            runCatching { videoSource?.dispose() }
            runCatching { audioSource.dispose() }
            runCatching { texHelper?.dispose() }
            remoteVideo.value = null
        }
    }
}
