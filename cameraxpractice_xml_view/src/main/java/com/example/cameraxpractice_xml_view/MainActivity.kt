package com.example.cameraxpractice_xml_view

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.example.cameraxpractice_xml_view.databinding.ActivityMainBinding
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// 이미지 분석 도구에서 사용하는 새로운 타입 정의 코드입니다.
// 일반적으로 이러한 코드는 콜백을 정의하는데 사용합니다.
typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    // 이미지 프로세싱 같은 카메라와 관련된 작업은
    // UI 스레드에서 작업시 ANR 오류나 성능의 문제를 일으킬 수 있기 때문에
    // 별도의 백그라운드 스레드에서 작업을 해야 합니다.
    // ExecutorService를 사용하면 별도의 스레드를 생성하고 비동기적으로 관리하는 작업을
    // 직접 구현하지 않아도 가능하게 해줍니다.
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // 권한 체크
        if (allPermissionsGranted()) {
            // 이미 권한이 허용되어 있으면 카메라 실행
            startCamera()
        } else {
            // 권한이 허용되지 않았으면 권한 요청
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        // 일반적으로 카메라 작업은 한 번에 하나의 작업만 처리하면 되기 때문에 작업들이 서로 충돌하지 않도록
        // newSingleThreadExecutor를 사용해 단일 스레드에서 작업을 순차적으로 처리합니다.
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    // 카메라 관련 기능 초기화 및 실행
    private fun startCamera() {
        // ProcessCameraProvider는 카메라의 수명주기와 LifecycleOwner를 바인딩(연결)합니다.
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // cameraProviderFuture.get()은 ProcessCameraProvider의 인스턴스를 비동기적으로 초기화하는 기능입니다.
            // 인스턴스가 초기화가 완료될 때까지 기다린 후 그 ProcessCameraProvider의 인스턴스를 반환합니다.
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview 객체 초기화 및 뷰와 연결
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            // ImageCapture 인스턴스 생성
            imageCapture = ImageCapture.Builder()
                .build()

            // 비디오 캡쳐를 위한 Recorder 인스턴스 생성
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(
                    Quality.HIGHEST,
                    // 선택한 화질이 지원되지 않는 경우 설정한 화질에 가장 가까운 화질을 설정합니다.
                    FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                ))
                .build()

            // VideoCapture 인스턴스 생성
            videoCapture = VideoCapture.withOutput(recorder)

//            // 이미지 분석도구 생성
//            val imageAnalyzer = ImageAnalysis.Builder()
//                .build()
//                .also {
//                    it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
//                        Log.d(TAG, "Average luminosity: $luma")
//                    })
//                }

            // CameraSelector를 사용해 기본 카메라를 선택할 수 있습니다
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // cameraProvider에 카메라와 Preview 객체를 연결하기 전에 모든 연결을 해제합니다.
                cameraProvider.unbindAll()

                // cameraProvider에 카메라와 Preview 객체를 연결합니다.
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture,
                    videoCapture
                )
            } catch (exc: Exception) {
                // cameraProvider에 연결 실패시 로그를 발생시킵니다.

                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // 사진 촬영 기능
    private fun takePhoto() {
        // 이미지 캡쳐가 설정되기 전 캡쳐버튼 클릭 상황 처리로 return 생략시 앱이 비정상 종료 됩니다.
        val imageCapture = imageCapture ?: return

        // 사진을 찍은 시간으로 파일 이름으로 설정하기 위해 필요
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            DateTimeFormatter
                .ofPattern(FILENAME_FORMAT)
                .withLocale(Locale.KOREA)
                .format(LocalDateTime.now())
        } else {
            SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis())
        }

        // 이미지를 보관할 MediaStore 콘텐츠 값 생성
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // OutputFileOptions는 이미지 캡처 결과물을 저장할 파일의 위치와 파일명, 메타데이터 등을 지정합니다.
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(
                // MediaStore 접근에 필요
                contentResolver,
                // 이미지 파일 저장 할 위치 설정 (세부 위치는 contentValues에서 설정)
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                // 저장할 파일(메타데이터) 설정
                contentValues
            )
            .build()

        // 이미지 캡쳐 실행 Listener
        imageCapture.takePicture(
            // 이미지 캡쳐 결과물 옵션
            outputOptions,
            // 이미지 캡쳐 후 UI 관련 작업을 위해 UI 스레드를 반환하는 Executor 전달
            ContextCompat.getMainExecutor(this),
            // 이미지 캡쳐 결과 콜백
            object : ImageCapture.OnImageSavedCallback {
                // 이미지 캡쳐 실패
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                // 이미지 캡쳐 성공
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${output.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, msg)
                }
            }
        )
    }

    // 비디오 촬영 기능
    private fun captureVideo() {
        // 비디오 캡쳐가 설정되기 전 캡쳐버튼 클릭 상황 처리로 return 생략시 앱이 비정상 종료 됩니다.
        val videoCapture = this.videoCapture ?: return

        // CameraX에서 요청 작업을 완료할 때까지 비디오 캡쳐 버튼을 비활성화 합니다.
        viewBinding.videoCaptureButton.isEnabled = false

        val curRecording = recording
        // 진행중인 비디오 촬영이 있는지 확인하는 기능입니다.
        if (curRecording != null) {
            // 촬영중이라면 촬영을 중지합니다.
            curRecording.stop()
            recording = null
            return
        }

        // 비디오를 찍은 시간으로 파일 이름으로 설정하기 위해 필요
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            DateTimeFormatter
                .ofPattern(FILENAME_FORMAT)
                .withLocale(Locale.KOREA)
                .format(LocalDateTime.now())
        } else {
            SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis())
        }
        // 비디오를 보관할 MediaStore 콘텐츠 값 생성
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(
                // MediaStore 접근에 필요
                contentResolver,
                // 비디오 파일 저장 할 위치 설정 (세부 위치는 contentValues에서 설정)
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            )
            // 저장할 파일(메타데이터) 설정
            .setContentValues(contentValues)
            .build()

        //
        recording = videoCapture
            .output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                // 오디오 녹음 권한이 허용되어있으면 오디오 녹음 실행
                if (
                    PermissionChecker.checkSelfPermission(this@MainActivity,
                        Manifest.permission.RECORD_AUDIO) ==
                    PermissionChecker.PERMISSION_GRANTED
                )
                {
                    withAudioEnabled()
                }
            }
            // 비디오 캡쳐 시작
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {
                    // 비디오 캡쳐가 시작되는 경우
                    is VideoRecordEvent.Start -> {
                        // 비디오 캡쳐 시작 버튼을 중지 버튼으로 변경
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.stop_capture)
                            setBackgroundColor(context.getColor(R.color.red))
                            isEnabled = true
                        }
                    }
                    // 비디오 캡쳐가 완료된 경우
                    is VideoRecordEvent.Finalize -> {
                        // 비디오 캡쳐가 성공했는지 실패했는지 확인
                        if (!recordEvent.hasError()) {
                            // 비디오 캡쳐 성공시 토스트 메세지 보여줌
                            val msg = "Video capture succeeded: " +
                                    "${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT)
                                .show()
                            Log.d(TAG, msg)
                        } else {
                            // 비디오 캡쳐 실패시 에러 로그 발생
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: " +
                                    "${recordEvent.error}")
                        }

                        // 비디오 캡쳐 중지 버튼을 시작 버튼으로 변경
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.start_capture)
                            setBackgroundColor(context.getColor(R.color.green))
                            isEnabled = true
                        }
                    }
                }
            }
    }

    // 모든 권한이 허용되었는지 확인하는 기능
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    // 권한 요청 후 권한이 허용되었는지 확인하는 기능
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                // 권한 허용시 카메라 기능 실행
                startCamera()
            } else {
                // 권한 거부시 앱 토스트 메세지 보여주고 앱 종료
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXPractice_xml_view"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

//    // 이미지 밝기(luma) 분석 도구
//    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {
//        private fun ByteBuffer.toByteArray(): ByteArray {
//            rewind()    // Rewind the buffer to zero
//            val data = ByteArray(remaining())
//            get(data)   // Copy the buffer into a byte array
//            return data // Return the byte array
//        }
//
//        override fun analyze(image: ImageProxy) {
//            val buffer = image.planes[0].buffer
//            val data = buffer.toByteArray()
//            val pixels = data.map { it.toInt() and 0xFF }
//            val luma = pixels.average()
//
//            listener(luma)
//
//            image.close()
//        }
//    }
}