package ai.flow.android.sensor;

import static android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_AUTO;
import static android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
import static android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
import static android.hardware.camera2.CameraMetadata.LENS_FACING_BACK;
import static android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT;

import static ai.flow.android.sensor.Utils.fillYUVBuffer;
import static ai.flow.common.transformations.Camera.CAMERA_TYPE_ROAD;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import ai.flow.definitions.Definitions;
import android.graphics.SurfaceTexture;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;
import androidx.core.app.ActivityCompat;

import org.opencv.core.Core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;

import ai.flow.common.transformations.Camera;
import ai.flow.common.utils;
import ai.flow.modeld.ModelExecutor;
import ai.flow.modeld.messages.MsgFrameData;
import ai.flow.sensor.SensorInterface;
import ai.flow.sensor.messages.MsgFrameBuffer;
import messaging.ZMQPubHandler;

import com.jiangdg.usbcamera.UVCCameraHelper;
import com.jiangdg.usbcamera.utils.FileUtils;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.common.AbstractUVCCameraHandler;


public class CameraHandler implements SensorInterface {

    private final String TAG = "CameraHandler";

    private final Context context;
    private MsgFrameBuffer msgFrameBuffer;
    private MsgFrameData msgFrameData;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private ImageReader reader;
    private CaptureRequest.Builder previewBuilder;
    private CameraCaptureSession previewSession;
    private CameraDevice cameraDevice;
    private CameraCharacteristics cameraCharacteristics;
    public int W = Camera.frameSize[0];
    public int H = Camera.frameSize[1];
    ByteBuffer yuvBuffer;
    public ZMQPubHandler ph;
    public int frameID = 0;

    private UVCCameraHelper mCameraHelper;
    private boolean isUVCCamera = false;
    private CameraViewInterface mUVCCameraView;

    public CameraHandler(Context context) {
        this.context = context;
        // 初始化 UVC 摄像头
        initUVCCamera();
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        msgFrameData = new MsgFrameData(Camera.CAMERA_TYPE_WIDE);
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);

        msgFrameBuffer = new MsgFrameBuffer(W * H * 3/2, Camera.CAMERA_TYPE_WIDE);
        yuvBuffer = msgFrameBuffer.frameBuffer.getImage().asByteBuffer();
        msgFrameBuffer.frameBuffer.setEncoding(Definitions.FrameBuffer.Encoding.YUV);
        msgFrameBuffer.frameBuffer.setFrameHeight(H);
        msgFrameBuffer.frameBuffer.setFrameWidth(W);

        ph = new ZMQPubHandler();
        ph.createPublishers(Arrays.asList("wideRoadCameraState", "wideRoadCameraBuffer"));
    }

    private void initUVCCamera() {
        mCameraHelper = UVCCameraHelper.getInstance();
        mCameraHelper.setDefaultFrameFormat(UVCCameraHelper.FRAME_FORMAT_YUYV);
        mCameraHelper.initUSBMonitor(context, new UVCCameraHelper.OnMyDevConnectListener() {
            @Override
            public void onAttachDev(UsbDevice device) {
                // 检测到设备插入
                if (!isUVCCamera) {
                    mCameraHelper.requestPermission(device);
                }
            }

            @Override
            public void onDettachDev(UsbDevice device) {
                // 设备拔出
                if (isUVCCamera) {
                    isUVCCamera = false;
                    // 切换回内置摄像头
                    start();
                }
            }

            @Override
            public void onConnectDev(UsbDevice device, boolean isConnected) {
                if (isConnected) {
                    isUVCCamera = true;
                    // 配置摄像头参数
                    mCameraHelper.updateResolution(W, H);
                }
            }

            @Override
            public void onDisConnectDev(UsbDevice device) {
                isUVCCamera = false;
            }
        });

        // 设置预览回调
        mCameraHelper.setPreviewCallback(new AbstractUVCCameraHandler.OnPreViewResultListener() {
            @Override
            public void onPreviewResult(byte[] data) {
                if (data != null) {
                    // 将 UVC 摄像头数据转换为 YUV 格式
                    ByteBuffer buffer = ByteBuffer.wrap(data);
                    // 填充到现有的 YUV buffer
                    yuvBuffer.put(buffer);
                    
                    // 更新帧信息
                    msgFrameData.frameData.setFrameId(frameID);
                    
                    // 执行模型
                    ModelExecutor.instance.ExecuteModel(
                            msgFrameData.frameData.asReader(),
                            msgFrameBuffer.frameBuffer.asReader(),
                            System.currentTimeMillis());

                    // 发布数据
                    ph.publishBuffer("wideRoadCameraState", msgFrameData.serialize(true));
                    ph.publishBuffer("wideRoadCameraBuffer", msgFrameBuffer.serialize(true));

                    frameID += 1;
                }
            }
        });
    }
    @Override
    public void dispose() {
        if (mCameraHelper != null) {
            mCameraHelper.release();
        }
    }

    public void stop(){
        if (isUVCCamera && mCameraHelper != null) {
            mCameraHelper.stopPreview();
        }
    }

    public void start() {
        if (mCameraHelper != null && mCameraHelper.getUsbDeviceCount() > 0) {
            // 优先使用 USB 摄像头
            mCameraHelper.startPreview();
        } else {
            android.hardware.camera2.CameraManager manager = (android.hardware.camera2.CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            if (manager == null) {
                throw new RuntimeException("Unable to get camera manager.");
            }

            String cameraId = "0";

            try {
                cameraCharacteristics = manager.getCameraCharacteristics(cameraId);
                StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice device) {
                        cameraDevice = device;
                        startCamera();
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice device) {}

                    @Override
                    public void onError(@NonNull CameraDevice device, int error) {
                        Log.w(TAG, "Error opening camera: " + error);
                    }
                }, backgroundHandler);
            } catch (CameraAccessException e) {
                Log.w(TAG, "Error getting camera configuration.", e);
            }
        }
    }

    private void startCamera() {
        List<Surface> list = new ArrayList<>();

        final int width = 1280, height = 720;
        reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 5);

        list.add(reader.getSurface());

        ImageReader.OnImageAvailableListener imageAvailableListener = new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                try {
                    Image image = reader.acquireLatestImage();
                    if (image == null) return;
                    long startTimestamp = System.currentTimeMillis();
                    fillYUVBuffer(image, yuvBuffer);

                    Image.Plane yPlane = image.getPlanes()[0];

                    msgFrameBuffer.frameBuffer.setYWidth(W);
                    msgFrameBuffer.frameBuffer.setYHeight(H);
                    msgFrameBuffer.frameBuffer.setYPixelStride(yPlane.getPixelStride());
                    msgFrameBuffer.frameBuffer.setUvWidth(W /2);
                    msgFrameBuffer.frameBuffer.setUvHeight(H /2);
                    msgFrameBuffer.frameBuffer.setUvPixelStride(image.getPlanes()[1].getPixelStride());
                    msgFrameBuffer.frameBuffer.setUOffset(W * H);
                    if (image.getPlanes()[1].getPixelStride() == 2)
                        msgFrameBuffer.frameBuffer.setVOffset(W * H + 1);
                    else
                        msgFrameBuffer.frameBuffer.setVOffset(W * H + W * H /4);
                    msgFrameBuffer.frameBuffer.setStride(yPlane.getRowStride());

                    msgFrameData.frameData.setFrameId(frameID);

                    ModelExecutor.instance.ExecuteModel(
                            msgFrameData.frameData.asReader(),
                            msgFrameBuffer.frameBuffer.asReader(),
                            startTimestamp);

                    ph.publishBuffer("wideRoadCameraState", msgFrameData.serialize(true));
                    ph.publishBuffer("wideRoadCameraBuffer", msgFrameBuffer.serialize(true));

                    frameID += 1;
                    image.close();
                    image.close();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        };

        reader.setOnImageAvailableListener(imageAvailableListener, backgroundHandler);

        try {
            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            previewBuilder.addTarget(list.get(0));

            Integer afMode = CONTROL_AF_MODE_AUTO;//afMode(cameraCharacteristics);
//            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
//            previewBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            previewBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(20, 20));
            previewBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[]{
                new MeteringRectangle((int)Math.floor(W*0.4f), (int)Math.floor(H*0.5f),
                                      (int)Math.floor(W*0.2f), (int)Math.floor(H*0.2f), 1000)});

            if (afMode != null) {
                previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, afMode);
                Log.i(TAG, "Setting af mode to: " + afMode);
                if (afMode == CONTROL_AF_MODE_AUTO) {
                    previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                } else {
                    previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        try {
            List<OutputConfiguration> confs = new ArrayList<>();
            for (Surface surface : list) {
                confs.add(new OutputConfiguration(surface));
            }

            cameraDevice.createCaptureSession(
                    new SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            confs,
                            context.getMainExecutor(),
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(CameraCaptureSession session) {
                                    previewSession = session;
                                    startPreview();
                                }

                                @Override
                                public void onConfigureFailed(CameraCaptureSession session) {
                                    System.out.println("### Configuration Fail ###");
                                }
                            }
                    )
            );
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void startPreview() {
        CameraCaptureSession.CaptureCallback listener = new CameraCaptureSession.CaptureCallback() {
            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                super.onCaptureCompleted(session, request, result);
            }
        };

        if (cameraDevice == null) return;

        try {
            previewSession.setRepeatingRequest(previewBuilder.build(), listener, backgroundHandler);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
