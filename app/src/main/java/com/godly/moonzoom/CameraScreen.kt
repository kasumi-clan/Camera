package com.godly.moonzoom

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Frame(val label: String, val ratio: Float) {
    R34("3:4", 3f / 4f),
    R11("1:1", 1f),
    R916("9:16", 9f / 16f),
    FULL("FULL", 0f)
}

private const val AI_FRAMES = 5

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider = suspendCoroutine { cont ->
    val f = ProcessCameraProvider.getInstance(this)
    f.addListener({ cont.resume(f.get()) }, ContextCompat.getMainExecutor(this))
}

private suspend fun ImageCapture.takeToFile(executor: Executor, file: File) =
    suspendCancellableCoroutine<Unit> { c ->
        takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    c.resume(Unit)
                }

                override fun onError(exception: ImageCaptureException) {
                    c.resumeWithException(exception)
                }
            }
        )
    }

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraContent()
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Cần quyền Camera để hoạt động.", color = Color.White, textAlign = TextAlign.Center)
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Cấp quyền") }
                Button(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null))
                    )
                }) { Text("Mở cài đặt ứng dụng") }
            }
        }
    }
}

@Composable
private fun CameraContent() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val settings = remember { AppSettings(context.getSharedPreferences("moonzoom", Context.MODE_PRIVATE)) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE // TextureView: cho phép phóng số + hiệu ứng GPU
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val applier = remember { ZoomApplier(previewView) }
    val previewFx: PreviewFx? = remember { if (Build.VERSION.SDK_INT >= 33) PreviewFx(previewView) else null }
    LaunchedEffect(previewFx) { applier.onFx = { e, v -> previewFx?.update(e, v) } }
    val controller = remember { SmoothZoomController(scope) { z, dt, fin -> applier.apply(z, dt, fin) } }
    val zoomUi = remember { ZoomUi() }
    ZoomAutoCollapse(zoomUi)

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var flashState by remember { mutableIntStateOf(0) } // 0 tắt, 1 tự động, 2 bật, 3 đèn liên tục
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusTick by remember { mutableIntStateOf(0) }
    var expIndex by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    var flashFx by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var procJob by remember { mutableStateOf<Job?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var frameMenu by remember { mutableStateOf(false) }
    var cameraNote by remember { mutableStateOf("") }
    var refocusJob by remember { mutableStateOf<Job?>(null) }
    var selfTestText by remember { mutableStateOf("") }
    var selfTestRunning by remember { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val procMutex = remember { Mutex() }

    val cameraNow by rememberUpdatedState(camera)
    val frame = Frame.values()[settings.frame.coerceIn(0, 3)]
    val fullRatio = if (boxSize.height > 0) boxSize.width.toFloat() / boxSize.height else 9f / 20f
    fun ratioOf(f: Frame): Float = if (f == Frame.FULL) fullRatio else f.ratio

    // ---- Album gốc: xin quyền đọc ảnh để thấy lại ảnh đã chụp sau khi mở lại app ----
    val albumPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    var albumGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, albumPerm) == PackageManager.PERMISSION_GRANTED)
    }
    val albumLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { albumGranted = it }
    LaunchedEffect(Unit) { if (!albumGranted) albumLauncher.launch(albumPerm) }

    suspend fun refreshAlbum() {
        GalleryHelper.latest(context)?.let { u ->
            lastUri = u
            thumb = GalleryHelper.thumb(context, u)?.asImageBitmap()
        }
    }
    LaunchedEffect(albumGranted) { refreshAlbum() }

    // Quay lại app (từ album...): camera có thể đã mở lại ở zoom mặc định -> áp lại zoom hiện tại
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    delay(350)
                    applier.forceReapply(controller.value)
                    refreshAlbum()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Zoom dừng hẳn -> lấy nét/đo sáng lại ở giữa khung cho rõ nét (giúp cả ảnh để AI xử lý)
    LaunchedEffect(Unit) {
        applier.onSettled = { z ->
            if (z > 1.2f) {
                refocusJob?.cancel()
                refocusJob = scope.launch {
                    delay(350)
                    val cam = cameraNow
                    if (cam != null && previewView.width > 0) {
                        val pt = previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
                        cam.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).build()
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(message) {
        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); message = null }
    }

    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            try {
                val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    val s = min(1f, 512f / max(bmp.width, bmp.height))
                    val scaled = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
                    FileOutputStream(File(context.filesDir, "wm_logo.png")).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    settings.wmLogo = "custom"
                    message = "Đã đặt logo"
                } else message = "Không đọc được ảnh logo"
            } catch (e: Exception) {
                message = "Không đọc được ảnh logo"
            }
        }
    }

    LaunchedEffect(lensFacing) {
        try {
            val provider = context.awaitCameraProvider()
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            if (!provider.hasCamera(selector)) {
                message = "Thiết bị không có camera này"
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                    CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                return@LaunchedEffect
            }
            val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .setJpegQuality(98)
                .build()
            provider.unbindAll()
            val cam = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            imageCapture = capture
            camera = cam
            flashState = 0
            expIndex = 0f
            focusPoint = null
            applier.camera = cam
            applier.nativeMax = (cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f).coerceAtLeast(1f)
            applier.reset()
            cameraNote = CameraTuning.apply(cam)
            controller.setTarget(1f, immediate = true)
            cam.cameraInfo.cameraState.removeObservers(lifecycleOwner)
            cam.cameraInfo.cameraState.observe(lifecycleOwner) { st ->
                if (st.type == CameraState.Type.OPEN) applier.forceReapply(controller.value)
            }
        } catch (e: Exception) {
            message = "Không mở được camera: ${e.message}"
        }
    }

    LaunchedEffect(flashState, camera, imageCapture) {
        imageCapture?.flashMode = when (flashState) {
            1 -> ImageCapture.FLASH_MODE_AUTO
            2 -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        try {
            camera?.cameraControl?.enableTorch(flashState == 3)
        } catch (e: Exception) {
        }
    }
    LaunchedEffect(focusTick) {
        if (focusTick > 0) {
            delay(4500); focusPoint = null
        }
    }

    val fxAlpha = animateFloatAsState(if (flashFx) 0.8f else 0f, tween(110), label = "fx")

    fun wmOptions() = WmOptions(
        settings.wmDeviceAuto, settings.wmDeviceName, settings.wmLogo,
        settings.wmLogoPos, settings.wmShowInfo, settings.wmShowDate
    )

    suspend fun runProcessing(files: List<File>, extra: Float, ratio: Float, ai: Boolean, wm: Boolean, keepOrig: Boolean) {
        progress = 0f
        try {
            val opts = wmOptions()
            val uri: Uri
            if (!ai) {
                uri = CaptureSaver.saveNormal(context, files[0], extra, ratio, wm, opts) { p, _ -> progress = p }
            } else {
                if (keepOrig) {
                    progress = 0.02f
                    CaptureSaver.saveNormal(context, files[0], extra, ratio, false, opts, "orig") { _, _ -> }
                }
                val res = MoonAIEnhancer.process(context, files, extra, ratio) { p, _ -> progress = 0.05f + p * 0.85f }
                settings.lastAiReport = res.note
                val meta = ImageMeta.read(files[0])
                uri = CaptureSaver.finishBitmap(
                    context, res.bitmap, meta, extra, wm,
                    if (extra > 1.01f) "AI_digital" else "AI", false, opts
                ) { p, _ -> progress = 0.9f + p * 0.1f }
            }
            lastUri = uri
            thumb = GalleryHelper.thumb(context, uri)?.asImageBitmap()
        } catch (e: CancellationException) {
            message = "Đã hủy xử lý"
            throw e
        } catch (e: OutOfMemoryError) {
            message = "Hết bộ nhớ khi xử lý ảnh"
            settings.lastAiReport = "Lỗi: hết bộ nhớ khi xử lý ảnh"
        } catch (e: Exception) {
            message = "Lỗi xử lý ảnh: ${e.message}"
            settings.lastAiReport = "Lỗi: ${e.message}"
        } finally {
            progress = null
        }
    }

    fun shoot() {
        val ic = imageCapture ?: return
        if (busy) return
        busy = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch { flashFx = true; delay(90); flashFx = false }
        val extra = applier.extra
        val ratio = ratioOf(frame)
        val ai = settings.aiOn
        val wm = settings.wmOn
        val keepOrig = settings.saveOriginal
        val st = flashState
        scope.launch {
            val temps = mutableListOf<File>()
            val cam = camera
            try {
                val files = ArrayList<File>()
                val count = if (ai) (if (extra > 2f) 8 else AI_FRAMES) else 1
                if (ai) {
                    ic.flashMode = ImageCapture.FLASH_MODE_OFF
                    if (st == 2) cam?.cameraControl?.enableTorch(true)
                }
                for (i in 0 until count) {
                    val f = File.createTempFile("cap_", ".jpg", context.cacheDir)
                    temps += f
                    ic.takeToFile(mainExecutor, f)
                    files += f
                }
                // Chụp xong: nhả nút chụp, xử lý ở nền (chạy 0–100% ngay trên nút album)
                val owned = files.toList()
                temps.clear()
                procJob = scope.launch {
                    procMutex.withLock {
                        try {
                            runProcessing(owned, extra, ratio, ai, wm, keepOrig)
                        } finally {
                            owned.forEach { it.delete() }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Lỗi chụp: ${e.message}"
            } finally {
                if (ai && st == 2) try { cam?.cameraControl?.enableTorch(false) } catch (e: Exception) { }
                ic.flashMode = when (st) {
                    1 -> ImageCapture.FLASH_MODE_AUTO
                    2 -> ImageCapture.FLASH_MODE_ON
                    else -> ImageCapture.FLASH_MODE_OFF
                }
                temps.forEach { it.delete() }
                busy = false
            }
        }
    }

    val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true

    // Mở thư viện ảnh của máy (toàn bộ album), không chỉ 1 ảnh
    fun openAlbum() {
        val tries = listOf<() -> Intent?>(
            { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY) },
            { Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI) },
            {
                lastUri?.let { Intent(Intent.ACTION_VIEW).setDataAndType(it, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        )
        for (mk in tries) {
            try {
                val intent = mk() ?: continue
                context.startActivity(intent)
                return
            } catch (e: Exception) {
            }
        }
        message = "Không mở được thư viện ảnh"
    }

    Box(Modifier.fillMaxSize().onSizeChanged { boxSize = it }) {
        // ---------------- Preview ----------------
        val previewMod = if (frame == Frame.FULL) Modifier.fillMaxSize()
        else Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 60.dp)
            .fillMaxWidth().aspectRatio(frame.ratio)
        Box(previewMod.clipToBounds()) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { off ->
                            val cam = cameraNow ?: return@detectTapGestures
                            val ex = previewView.scaleX
                            val cx = previewView.width / 2f
                            val cy = previewView.height / 2f
                            val x = cx + (off.x - cx) / ex
                            val y = cy + (off.y - cy) / ex
                            val point = previewView.meteringPointFactory.createPoint(x, y)
                            val action = FocusMeteringAction.Builder(
                                point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                            ).setAutoCancelDuration(5, TimeUnit.SECONDS).build()
                            cam.cameraControl.startFocusAndMetering(action)
                            focusPoint = off
                            focusTick++
                        }
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoomChange, _ ->
                            if (zoomChange != 1f) {
                                controller.setTarget(controller.target * zoomChange)
                                zoomUi.touch()
                            }
                        }
                    }
            )

            focusPoint?.let { p ->
                val ring = remember { Animatable(1.5f) }
                LaunchedEffect(focusTick) { ring.snapTo(1.5f); ring.animateTo(1f, tween(260)) }
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Orange.copy(alpha = 0.35f), 34.dp.toPx() * ring.value, p, style = Stroke(5.dp.toPx()))
                    drawCircle(Color.White, 34.dp.toPx() * ring.value, p, style = Stroke(1.6.dp.toPx()))
                    drawCircle(Color.White.copy(0.9f), 2.5.dp.toPx(), p)
                }
                val expState = camera?.cameraInfo?.exposureState
                val range = expState?.exposureCompensationRange
                if (expState != null && range != null && expState.isExposureCompensationSupported && range.upper > range.lower) {
                    val ctrlW = 44.dp
                    val ctrlH = 168.dp
                    Column(
                        Modifier.offset {
                            val pw = previewView.width.toFloat()
                            val ph = previewView.height.toFloat()
                            val gap = 52.dp.toPx()
                            val x = if (p.x + gap + ctrlW.toPx() > pw) p.x - gap - ctrlW.toPx() else p.x + gap
                            val y = (p.y - ctrlH.toPx() / 2f).coerceIn(0f, max(0f, ph - ctrlH.toPx() - 24.dp.toPx()))
                            IntOffset(x.roundToInt(), y.roundToInt())
                        },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "EV " + String.format(
                                java.util.Locale.US, "%+.1f",
                                expIndex.roundToInt() * expState.exposureCompensationStep.toFloat()
                            ),
                            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        ExposureControl(
                            index = expIndex, lower = range.lower, upper = range.upper,
                            onChange = { v ->
                                val i = v.roundToInt()
                                if (i.toFloat() != expIndex) {
                                    expIndex = i.toFloat()
                                    cameraNow?.cameraControl?.setExposureCompensationIndex(i)
                                }
                            },
                            onTouch = { focusTick++ }
                        )
                    }
                }
            }
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = fxAlpha.value)))
        }

        // ---------------- Thanh trên: flash, khung hình | AI, WM, cài đặt ----------------
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hasFlash) {
                        GlassButton(Modifier.height(38.dp).width(78.dp), selected = flashState != 0, onClick = {
                            flashState = (flashState + 1) % 4
                        }) {
                            GlassLabel(
                                when (flashState) {
                                    1 -> "⚡ AUTO"
                                    2 -> "⚡ ON"
                                    3 -> "ĐÈN"
                                    else -> "⚡ OFF"
                                },
                                flashState != 0, 12
                            )
                        }
                    }
                    GlassButton(Modifier.height(38.dp).width(64.dp), selected = frameMenu, onClick = { frameMenu = !frameMenu }) {
                        GlassLabel(frame.label, frameMenu, 12)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconToggle(R.drawable.ic_ai, settings.aiOn, 42.dp) {
                        settings.aiOn = !settings.aiOn
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    IconToggle(R.drawable.ic_wm, settings.wmOn, 42.dp) {
                        settings.wmOn = !settings.wmOn
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    GlassButton(Modifier.size(38.dp), onClick = { showSettings = true }) { GlassLabel("⚙", false, 17) }
                }
            }
            if (frameMenu) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (f in Frame.values()) {
                        GlassButton(Modifier.width(60.dp).height(36.dp), selected = frame == f, onClick = {
                            settings.frame = f.ordinal
                            frameMenu = false
                        }) { GlassLabel(f.label, frame == f, 12) }
                    }
                }
            }
        }

        // ---------------- Dưới: thước zoom (chỉ hiện khi zoom) + bảng điều khiển ----------------
        val solidPanel = frame == Frame.R34 || frame == Frame.R11
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            ZoomArc(controller, zoomUi)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (solidPanel) Brush.verticalGradient(listOf(Color.Black, Color.Black))
                        else Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))
                    )
                    .navigationBarsPadding()
                    .padding(top = 6.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ZoomPresets(controller, zoomUi)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AlbumButton(
                        thumb = thumb, progress = progress,
                        onTap = {
                            val p = progress
                            if (p != null) {
                                message = "Đang xử lý ${(p * 100).toInt()}% — nhấn giữ nút album để hủy"
                            } else {
                                openAlbum()
                            }
                        },
                        onLongPress = { if (progress != null) procJob?.cancel() }
                    )
                    ShutterButton(enabled = !busy && camera != null, onClick = { shoot() })
                    GlassButton(Modifier.size(58.dp), onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                    }) { FlipIcon(28.dp) }
                }
            }
        }

        if (showSettings) {
            SettingsPanel(
                settings = settings,
                onClose = { showSettings = false },
                onPickLogo = { logoPicker.launch("image/*") },
                onSelfTest = {
                    selfTestRunning = true
                    selfTestText = ""
                    scope.launch(Dispatchers.Default) {
                        val r = try { OnnxEngine.selfTest(context) } catch (e: Throwable) { "Lỗi: ${e.message}" }
                        selfTestText = r
                        selfTestRunning = false
                    }
                },
                selfTestText = selfTestText,
                selfTestRunning = selfTestRunning,
                cameraNote = cameraNote,
                retinexPresent = OnnxEngine.has(context, OnnxEngine.RETINEX_FILE),
                swinPresent = OnnxEngine.has(context, OnnxEngine.SWINIR_FILE)
            )
        }
    }
}
