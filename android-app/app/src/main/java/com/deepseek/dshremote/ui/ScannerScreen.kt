package com.deepseek.dshremote.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.deepseek.dshremote.core.CameraPermissionGate
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 当前系统是否已授予相机权限。 */
fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * 扫码页（CameraX 预览 + ML Kit 条码识别）。
 *
 * ## D6 修复
 * 修复前**根本没有这个页面**：「打开扫码」按钮无条件发权限请求，而系统对已授予的请求
 * 既不弹框也不回调 ⇒ 真机上第二次点击毫无反应、相机从未打开。
 * 现在：点击时用 [CameraPermissionGate] 决定「请求权限」还是「**直接启动相机**」，
 * 并且权限一旦授予就自动进入本页（[LaunchedEffect]），两条路径都能落到预览。
 *
 * 契约：本页**不涉及**前台服务/心跳/退避/状态机，故不影响 D1–D5 的任何修复。
 *
 * `ImageProxy.image`（ML Kit 识别必需）是 CameraX 实验 API，lint 将未标注视为 **error**，
 * 故整页显式 opt-in —— 而不是用 lint.xml 压制。
 */
@androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
@Composable
fun ScannerScreen(
    onResult: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember { mutableStateOf(hasCameraPermission(context)) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var handled by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) cameraError = "未授予相机权限，无法扫码"
    }

    // 进入页面后若已授权，立即进入预览；否则请求权限
    LaunchedEffect(Unit) {
        when (CameraPermissionGate.actionFor(hasCameraPermission(context))) {
            CameraPermissionGate.Action.START_CAMERA -> hasPermission = true
            CameraPermissionGate.Action.REQUEST_PERMISSION -> permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (hasPermission) {
            CameraPreview(
                onBarcode = { text ->
                    if (!handled) {          // 只认第一次识别结果
                        handled = true
                        onResult(text)
                    }
                },
                onError = { cameraError = it },
            )
        }
        val err = cameraError
        if (err != null) {
            Text(
                err,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = androidx.compose.ui.graphics.Color.Red,
            )
        }
        androidx.compose.material3.TextButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        ) { Text("返回") }
    }
}

/**
 * CameraX 预览 + ML Kit 识别。
 *
 * 关键点：**所有 CameraX 调用都显式捕获异常并上报**，不再静默失败
 * （D6 排查时最大的干扰就是「logcat 里什么都没有」）。
 */
/**
 * CameraX 预览 + ML Kit 识别。
 *
 * `ImageProxy.image` 属 CameraX 实验 API（lint `UnsafeOptInUsageError` 为 error 级，
 * 不允许压制），故本函数显式 opt-in。
 */
@androidx.camera.core.ExperimentalGetImage
@Composable
private fun CameraPreview(
    onBarcode: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner: BarcodeScanner = remember {
        val opts = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)
            .build()
        BarcodeScanning.getClient(opts)
    }
    val consumed = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { scanner.close() }
            runCatching { executor.shutdown() }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { proxy -> analyze(scanner, proxy, consumed, onBarcode, onError) }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (e: Exception) {
                    // 绝不静默吞掉：D6 排查最缺的就是这条日志
                    onError("相机启动失败：${e.javaClass.simpleName}: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/**
 * 单帧识别。
 *
 * `ImageProxy.image` 是 CameraX 的实验 API，必须**显式 opt-in**（lint `UnsafeOptInUsageError`
 * 是 error 级，不允许用 lint.xml 压制）。ML Kit 的 `InputImage.fromMediaImage` 正是要它。
 */
@androidx.camera.core.ExperimentalGetImage
private fun analyze(
    scanner: BarcodeScanner,
    proxy: ImageProxy,
    consumed: AtomicBoolean,
    onBarcode: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val media = proxy.image
    if (media == null || consumed.get()) { proxy.close(); return }
    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { codes ->
            val text = codes.firstNotNullOfOrNull { it.rawValue?.takeIf { v -> v.isNotBlank() } }
            if (text != null && consumed.compareAndSet(false, true)) onBarcode(text)
        }
        .addOnFailureListener { /* 单帧识别失败很正常（模糊/无条码），不视为错误 */ }
        .addOnCompleteListener { proxy.close() }
}
