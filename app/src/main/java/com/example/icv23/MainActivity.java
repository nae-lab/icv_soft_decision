package com.example.icv23;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.TonemapCurve;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.view.Surface;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {
    /*** Fixed values ***/
    private static final String TAG = "MyApp";
    private int REQUEST_CODE_FOR_PERMISSIONS = 1234;;
    private final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA", "android.permission.WRITE_EXTERNAL_STORAGE"};

    /*** Views ***/
    private PreviewView previewView;
    private ImageView imageView;
    private TextView textView;
    /*** For CameraX ***/
    private Camera camera = null;
    private Preview preview = null;
    private ImageAnalysis imageAnalysis = null;
    private ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private FileWriter filewriter = null;
    private FileWriter filewriter_time = null;
    private PrintWriter printwriter = null;
    private PrintWriter printwriter_time = null;
    private int totalMeasurement = 288; //football:278 minecraft:288 walking:300
    private int NumofMeasurement = 0;
    private int currentAttempt, successfulDecodes;
    private long start_time, end_time;
    private java.util.LinkedList<Mat> queue_rely = new java.util.LinkedList<>(); // 毎回の復号結果を保存するdeque
    private boolean is_saturated = false;
    private int num_saturated = 13; // ここでパラメータ設計
    Mat result_decode = new Mat(600,800,CvType.CV_8UC1); // matのheightたちとrows,colsは違うみたい


    static {
        System.loadLibrary("opencv_java4");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        imageView = findViewById(R.id.imageView);
        textView = findViewById(R.id.textView);

        Button button = findViewById(R.id.button);

        Button button_reset = findViewById(R.id.button_reset);

        button.setOnClickListener( v -> {
            if (filewriter == null) {
                final DateFormat df = new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss");
                final Date date = new Date(System.currentTimeMillis());
                try {
                    filewriter = new FileWriter(getApplicationContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS).toString() + "/Log-" + df.format(date) + ".csv", false);
                    printwriter = new PrintWriter(new BufferedWriter(filewriter));
                    currentAttempt = 0;
                    successfulDecodes = 0;
                    NumofMeasurement = 1;
                    printwriter.println("# Measurement " + String.valueOf(NumofMeasurement));
                    Log.i(TAG, "[OnClick] Start writing to CSV file.");
                    Toast.makeText(MainActivity.this, "Start Measuring", Toast.LENGTH_SHORT).show();
                    textView.setText("Start Measuremet " + String.valueOf(NumofMeasurement));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        button_reset.setOnClickListener(v -> {
            if(filewriter_time == null){
                queue_rely.clear();
                is_saturated = false;
                final DateFormat df = new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss");
                final Date date = new Date(System.currentTimeMillis());
                try {
                    filewriter_time = new FileWriter(getApplicationContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS).toString() + "/Log-time" + df.format(date) + ".csv", false);
                    printwriter_time = new PrintWriter(new BufferedWriter(filewriter_time));
                    currentAttempt = 0;
                    successfulDecodes = 0;
                    NumofMeasurement = 1;
                    printwriter_time.println("# Measurement " + String.valueOf(NumofMeasurement));
                    Log.i(TAG, "[OnClick] Start writing decode time to CSV file.");
                    Toast.makeText(MainActivity.this, "Start Measuring Time", Toast.LENGTH_SHORT).show();
                    textView.setText("Start Time Measurement " + String.valueOf(NumofMeasurement));
                    queue_rely.clear();
                    is_saturated = false;
                    start_time = System.currentTimeMillis();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        /*button_reset.setOnClickListener(v -> { test用
            textView.setText("button test");
            Timer timer = new Timer(false);
            TimerTask task = new TimerTask(){
                @Override
                public void run(){
                    textView.setText("preview");
                    timer.cancel();
                }
            };
            timer.schedule(task, 2000);
        });*/

        if (checkPermissions()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_FOR_PERMISSIONS);
        }
    }

    private void startCamera() {
        final ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        Context context = this;
        cameraProviderFuture.addListener(new Runnable() {
            @SuppressLint("UnsafeOptInUsageError")
            @Override
            public void run() {
                try {
                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    preview = new Preview.Builder().build();

//                    imageAnalysis = new ImageAnalysis.Builder().build();
                    /*** camera settings ***/
                    ImageAnalysis.Builder builder = new ImageAnalysis.Builder();
                    builder.setTargetResolution(new android.util.Size(600, 800)); // 600 800
                    @SuppressLint("UnsafeOptInUsageError") Camera2Interop.Extender ext = new Camera2Interop.Extender<>(builder);
                    ext.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, 8333333l);
//                    ext.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, 41666667l);
                    ext.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, 200);
                    ext.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                    ext.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 1.0f / 30f /* focus[cm] */ * 100f);

                    float[] curve = new float[]{0.0000f, 0.0000f, 0.0667f, 0.2864f, 0.1333f, 0.4007f, 0.2000f, 0.4845f, 0.2667f, 0.5532f, 0.3333f, 0.6125f, 0.4000f, 0.6652f, 0.4667f, 0.7130f, 0.5333f, 0.7569f, 0.6000f, 0.7977f, 0.6667f, 0.8360f, 0.7333f, 0.8721f, 0.8000f, 0.9063f, 0.8667f, 0.9389f, 0.9333f, 0.9701f, 1.0000f, 1.0000f};
                    ext.setCaptureRequestOption(CaptureRequest.TONEMAP_CURVE, new TonemapCurve(curve, curve, curve));
                    ext.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);

                    ext.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
                    ext.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
                    ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<Integer>(24, 24));

//                    ext.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
//                    ext.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, new RggbChannelVector(2.0f, 1.0f, 1.0f, 2.0f));
//                    ext.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, new ColorSpaceTransform(new int[]{
//                                                                                                                                1, 1, 0, 1, 0, 1,
//                                                                                                                                0, 1, 1, 1, 0, 1,
//                                                                                                                                0, 1, 0, 1, 1, 1,
//                                                                                                                            }));

                    ImageAnalysis imageAnalysis = builder.setBackpressureStrategy(ImageAnalysis.STRATEGY_BLOCK_PRODUCER).build();

                    imageAnalysis.setAnalyzer(cameraExecutor, new MyImageAnalyzer());
                    CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build();

                    cameraProvider.unbindAll();
                    camera = cameraProvider.bindToLifecycle((LifecycleOwner)context, cameraSelector, preview, imageAnalysis);
//                    preview.setSurfaceProvider(previewView.createSurfaceProvider(camera.getCameraInfo()));
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());
                } catch(Exception e) {
                    Log.e(TAG, "[startCamera] Use case binding failed", e);
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private class MyImageAnalyzer implements ImageAnalysis.Analyzer {
        private Mat matPrevious = null;
        private Mat matSrcOdd = null, matSrcEven = null;
        private Mat matDiffa = null, matDiffb = null;
        private Mat matDiff1 = null, matDiff2 = null;
        private Mat matDiffOdd = null, matDiffEven = null;
        private Mat matAdd = null;
        private Mat matDst = null; // 最終的な差分画像
        private Mat matBlur = null; // ノイズ除去後の画像
        private Mat matBinary = null; // 白黒画像
        private Mat matOutput = null; // 表示画像
        private Mat mat_hard_decision = null; // 全体の結果
        private Mat mat_binary_hard = null; // 個別の結果
        private boolean odd = true;
        private int add_count = 0;

        @Override
        synchronized public void analyze(@NonNull ImageProxy image) {
            /* Create cv::mat(RGB888) from image(NV21) */
            Mat matOrg = getMatFromImage(image);

            /* Fix image rotation (it looks image in PreviewView is automatically fixed by CameraX???) */
            Mat mat = fixMatRotation(matOrg);

            Log.i(TAG, "[analyze] width = " + image.getWidth() + ", height = " + image.getHeight() + ", Rotation = " + previewView.getDisplay().getRotation());
            Log.i(TAG, "[analyze] mat width = " + mat.cols() + ", mat height = " + mat.rows());
//            Log.d(TAG, "[analyze] mat type = " + mat.type());

            // 1. Calculate the difference from the two previous frames
            List<Mat> matList = new ArrayList(3);
            List<Mat> matPrevList = new ArrayList(3);
            // 0°: 0.5, -0.5    90°: -0.5, 0.5
            // double alpha = 1.0, beta = -1.0;
            // -θ の　回転行列
            // 0°&90° : 1.0, 0.0    45°&135° : 0.7071, 0.7071
            //           0.0, 1.0             -0.7071, 0.7071
//            double element11 = 1.0, element12 = 0.0;
//            double element21 = 0.0, element22 = 1.0;
            double element11 = 0.0, element12 = 1.0;
            double element21 = -1.0, element22 = 0.0;
//            double element11 = 0.7071, element12 = 0.7071;
//            double element21 = -0.7071, element22 = 0.7071;
            if (odd && matSrcOdd != null) {
                Core.split(mat, matList);
                Core.split(matSrcOdd, matPrevList);

//                matDiff1 = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
//                matDiff2 = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
//                Core.absdiff(matList.get(1), matPrevList.get(1), matDiff1);
//                Core.absdiff(matList.get(2), matPrevList.get(2), matDiff2);
//                matDiffOdd = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
//                Core.addWeighted(matDiff1, alpha, matDiff2, beta, 128, matDiffOdd);

                matDiffa = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
                matDiffb = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
                // L*a*b*の時は(1)->a, (2)->b,  YUV(YCrCb)の時は(2)->a, (1)->b
                Core.addWeighted(matList.get(1), 1.0, matPrevList.get(1), -1.0, 128, matDiffb);
                Core.addWeighted(matList.get(2), 1.0, matPrevList.get(2), -1.0, 128, matDiffa);
                matDiff1 = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
                matDiff2 = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
                Core.addWeighted(matDiffa, element11, matDiffb, element12, -128 * (element11 + element12 - 1.0), matDiff1);
                Core.addWeighted(matDiffa, element21, matDiffb, element22, -128 * (element21 + element22 - 1.0), matDiff2);
                Core.absdiff(matDiff1, new Scalar(128), matDiff1);
                Core.absdiff(matDiff2, new Scalar(128), matDiff2);
                matDiffOdd = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
                Core.addWeighted(matDiff1, 1.0, matDiff2, -1.0, 128, matDiffOdd); // calculate |delta V| - |delta U| + gamma
                queue_rely.add(matDiffOdd);
                Log.i(TAG,"queue added");
            } else if (!odd && matSrcEven != null) {
                Core.split(mat, matList);
                Core.split(matSrcEven, matPrevList);

//                matDiff1 = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
//                matDiff2 = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
//                Core.absdiff(matList.get(1), matPrevList.get(1), matDiff1);
//                Core.absdiff(matList.get(2), matPrevList.get(2), matDiff2);
//                matDiffEven = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
//                Core.addWeighted(matDiff1, alpha, matDiff2, beta, 128, matDiffEven);

                matDiffa = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
                matDiffb = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
                Core.addWeighted(matList.get(1), -1.0, matPrevList.get(1), 1.0, 128, matDiffb);
                Core.addWeighted(matList.get(2), -1.0, matPrevList.get(2), 1.0, 128, matDiffa);
                matDiff1 = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
                matDiff2 = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
                Core.addWeighted(matDiffa, element11, matDiffb, element12, -128 * (element11 + element12 - 1.0), matDiff1);
                Core.addWeighted(matDiffa, element21, matDiffb, element22, -128 * (element21 + element22 - 1.0), matDiff2);
                Core.absdiff(matDiff1, new Scalar(128), matDiff1);
                Core.absdiff(matDiff2, new Scalar(128), matDiff2);
                matDiffEven = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
                Core.addWeighted(matDiff1, 1.0, matDiff2, -1.0, 128, matDiffEven);
                queue_rely.add(matDiffEven);
                Log.i(TAG,"queue added");
            }

            // 2. Add to one previous difference image
            // maxバージョン
            /*if (matDiffOdd!=null && matDiffEven!=null && add_count==0) {
                matDst = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
                Core.addWeighted(matDiffOdd,0.5, matDiffEven,0.5,0,matDst);
                matAdd = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
                matDst.copyTo(matAdd);
                add_count=2;
                //Core.addWeighted(matDiffOdd,1.0, matDiffEven,1.0,-128,matDst);
            }else if(add_count >= 2 && add_count <= 10){
                double rate = (double)(add_count)/(add_count+1);
                Core.addWeighted(matAdd,rate,queue_rely.get(queue_rely.size()-1),1.0-rate,0,matAdd);
                matDst = max_mat(matAdd,matDst);
                //Core.addWeighted(matDst,1.0, queue_rely.get(queue_rely.size()-1),1.0,-128,matDst);
                add_count++;
                queue_rely.remove();
            }else if(add_count==11){
                Core.addWeighted(matDiffOdd,0.5, matDiffEven,0.5,0,matDst);
                matDst.copyTo(matAdd);
                //Core.addWeighted(matDiffOdd,1.0, matDiffEven,1.0,-128,matDst);
                add_count=2;
                queue_rely.remove();
            }*/
            // 加算バージョン(改良版)

            /*if(queue_rely.size()>=2){
                matDst = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
                if(queue_rely.size()>2) {
                    if(is_saturated){// すでにqueueが貯まった状態の場合
                        double rate = (double)1/num_saturated;
                        Core.addWeighted(result_decode,1.0, queue_rely.get(queue_rely.size()-1),rate,0,matDst); // 最新結果をたす
                        Core.addWeighted(matDst,1.0, queue_rely.get(queue_rely.size()-num_saturated),-1.0*rate,0,matDst); // 最も昔のやつを排除
                    }else{
                        double rate = (double)1/(queue_rely.size());
                        Core.addWeighted(matDst,1.0 - rate, queue_rely.get(queue_rely.size()-1),rate,0,matDst); // 最新結果をたすだけで良い
                    }
                    for(int i=1;i<queue_rely.size();i++){
                        if(i==1){Core.addWeighted(matDiffOdd,0.5, matDiffEven,0.5,0,matDst);}
                        else{
                            double rate = (double)1/(i+1);
                            Core.addWeighted(matDst,1.0 - rate, queue_rely.get(queue_rely.size()-(i+1)),rate,0,matDst);
                        }
                    }

                }else{
                    Core.addWeighted(matDiffOdd,0.5, matDiffEven,0.5,0,matDst);
                }
                result_decode = matDst.clone();
                if(is_saturated){ // すでに貯まった状態の場合はnum_saturated+1になるので削除
                    queue_rely.remove();
                }
                if(queue_rely.size()==num_saturated){
                    is_saturated = true;
                }
            }*/
            // 加算バージョン(完成版)
            if(queue_rely.size()>=2){
                matDst = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
                for(int i=1;i<queue_rely.size();i++){
                    if(i==1){Core.addWeighted(matDiffOdd,0.5, matDiffEven,0.5,0,matDst);}
                    else{
                        double rate = (double)1/(i+1);
                        Core.addWeighted(matDst,1.0 - rate, queue_rely.get(queue_rely.size()-(i+1)),rate,0,matDst);
                    }
                }
                if(queue_rely.size()==2){queue_rely.remove();}
            }
            //従来手法, hard decisionでも使用
            /*if(matDiffOdd!=null && matDiffEven!=null){
                matDst = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
                Core.addWeighted(matDiffOdd,0.5, matDiffEven,0.5,0,matDst);
                // queue_rely.remove(); hard decisionの場合はコメントアウト
            }*/

            // 3. Blur
            if (matDst != null) {
                Log.i(TAG,"decode tried");
                matBlur = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
                Imgproc.blur(matDst, matBlur, new Size(7, 7));
            }

            // 4. Binarize
            if (matBlur != null) {
                 matBinary = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
                 mat_hard_decision = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);
            //     // threshold(BINARY_INV) : dst = src > thresh ? 0 : maxVal
            //     // Diff1 > Diff2 の時 : src >= 129 なので 0(黒)
            //     // Diff1 = Diff2 の時 : src = 128 なので 0(黒)
            //     // Diff1 < Diff2 の時 : src <= 127 なので 255(白)
                // 通常用
                Imgproc.threshold(matBlur, matBinary, 127, 255, Imgproc.THRESH_BINARY_INV);
                result_decode = matBinary.clone();

                // hard decision
                /*Imgproc.threshold(matBlur, matBinary, 127, 1, Imgproc.THRESH_BINARY_INV);
                if(queue_rely.size()==2){
                    Imgproc.threshold(matBlur, matBinary, 127, 255, Imgproc.THRESH_BINARY_INV);
                }
                if(queue_rely.size()>=3){
                    int num_add;
                    if(queue_rely.size()%2==0){
                        num_add = queue_rely.size()-1;
                    }else{num_add=queue_rely.size()-2;}

                    // 足す数が多すぎる時は小さめに調整
                    if(num_add>7)num_add=7;

                    double threshold = (double)num_add/2;

                    mat_binary_hard = new Mat(mat.rows(), mat.cols(), CvType.CV_8UC1);

                    for(int i=0;i<num_add;i++){
                        Core.addWeighted(queue_rely.get(queue_rely.size()-(i+2)),0.5, queue_rely.get(queue_rely.size()-(i+1)),0.5,0,mat_binary_hard);
                        Core.addWeighted(mat_binary_hard,1, mat_hard_decision,1,0,mat_hard_decision);
                    }
                    if(queue_rely.size()==8){queue_rely.remove();}
                    Imgproc.threshold(mat_hard_decision, matBinary, threshold, 255, Imgproc.THRESH_BINARY_INV);
                }*/
             }

            // 5. Decode 2D barcode
            if (matBinary != null) {
                String data = null;
                Bitmap bbitmap = Bitmap.createBitmap(matBinary.cols(), matBinary.rows(),Bitmap.Config.ARGB_8888);
                Utils.matToBitmap(matBinary, bbitmap);
                int[] intArray = new int[bbitmap.getWidth()*bbitmap.getHeight()];
                bbitmap.getPixels(intArray, 0, bbitmap.getWidth(), 0, 0, bbitmap.getWidth(), bbitmap.getHeight());
                LuminanceSource source = new RGBLuminanceSource(bbitmap.getWidth(), bbitmap.getHeight(),intArray);

                try {
                    // BinaryBitmap binarybitmap = new BinaryBitmap(new HybridBinarizer(source));
                    BinaryBitmap binarybitmap = new BinaryBitmap(new GlobalHistogramBinarizer(source));
                    QRCodeReader reader1 = new QRCodeReader();
                    Result result1 = reader1.decode(binarybitmap);
                    data = result1.getText();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (data != null) {
                    //　時間計測の時の分岐
                    if(filewriter_time!=null){
                        Log.i(TAG, "[analyze] QR reader1 successed");
                        Log.i(TAG, "[analyze] QR decoded(1) : " + data);
                        if (successfulDecodes==0) { //初めて復号に成功した時の分岐
                            end_time = System.currentTimeMillis();
                            long decode_time = end_time - start_time;
                            printwriter_time.println(decode_time + "ms");
                            final Handler mainHandler = new Handler(Looper.getMainLooper());
                            mainHandler.post(() -> {
                                textView.setText("Decode " + NumofMeasurement);
                            });
                        }
                        currentAttempt++;
                        successfulDecodes++;
                    }else{
                        Log.i(TAG, "[analyze] QR reader1 successed");
                        Log.i(TAG, "[analyze] QR decoded(1) : " + data);
//                    Toast.makeText(MainActivity.this, data, Toast.LENGTH_SHORT).show();
                        successfulDecodes++;
                        if (filewriter != null) {printwriter.println(new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss.SSS").format(System.currentTimeMillis()) + "," + data); currentAttempt++;}
                    }
                } else {
                    Log.i(TAG, "[analyze] QR reader failed");
                    Log.i(TAG, "[analyze] no QR >^<");
                    if (filewriter != null) {printwriter.println(new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss.SSS").format(System.currentTimeMillis()) + "," + "No QR."); currentAttempt++;}
                    if(filewriter_time != null){currentAttempt++;}
                }
            }

//            if (matBinary != null) {
//                QRCodeDetector decoder1 = new QRCodeDetector();
//                String data = decoder1.detectAndDecode(matBinary);
//                if (data.length() > 0) {
//                    Log.i(TAG, "[analyze] QR decoded! : " + data);
////                    Toast.makeText(getApplicationContext(), data, Toast.LENGTH_LONG).show();
//                    if (filewriter != null) {printwriter.println(new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss.SSS").format(System.currentTimeMillis()) + "," + data); currentAttempt++;}
//                } else {
//                    Core.bitwise_not(matBinary, matBinary);
//                    QRCodeDetector decoder2 = new QRCodeDetector();
//                    data = decoder2.detectAndDecode(matBinary);
//                    if (data.length() > 0) {
//                        Log.i(TAG, "[analyze] QR decoded! : " + data);
////                        Toast.makeText(getApplicationContext(), data, Toast.LENGTH_LONG).show();
//                        if (filewriter != null) {printwriter.println(new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss.SSS").format(System.currentTimeMillis()) + "," + data); currentAttempt++;}
//                    } else {
//                        Log.i(TAG, "[analyze] no QR >^<");
//                        if (filewriter != null) {printwriter.println(new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss.SSS").format(System.currentTimeMillis()) + "," + "No QR."); currentAttempt++;}
//                    }
//                }
//                if (filewriter != null && currentAttempt == totalMeasurement) {printwriter.close(); filewriter = null; Log.i(TAG, "[OnClick] Finish writing to CSV file.");}
//            }

//            if (filewriter != null) {printwriter.println(new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss.SSS").format(System.currentTimeMillis()) + "," + "Done."); currentAttempt++;}

            if (filewriter != null && currentAttempt == totalMeasurement) {
                if (NumofMeasurement == 10) {
                    printwriter.close();
                    filewriter = null;
                    Log.i(TAG, "[analyze] Finish writing to CSV file.");
                    Log.i(TAG, "[analyze] " + String.valueOf((double) successfulDecodes / (double) totalMeasurement) + " %");
                    //                Toast.makeText(MainActivity.this, "finished.", Toast.LENGTH_SHORT).show();
                    final Handler mainHandler = new Handler(Looper.getMainLooper());
                    mainHandler.post(() -> {
                        textView.setText("Finished");
                    });
                } else {
                    NumofMeasurement += 1;
                    printwriter.println("# Measurement " + String.valueOf(NumofMeasurement));
                    currentAttempt = 0;
                    successfulDecodes = 0;
                    final Handler mainHandler = new Handler(Looper.getMainLooper());
                    mainHandler.post(() -> {
                        textView.setText("Start Measuremet " + String.valueOf(NumofMeasurement));
                    });
                }
            }



            if (odd) {
                matSrcOdd = mat;
            } else {
                matSrcEven = mat;
            }
            odd = !odd;

            if ((matOutput = matBinary) == null) {
                matOutput = new Mat();
                Core.absdiff(mat, mat, matOutput);
                Log.i(TAG, "[analyze] Calc Diff Image Failed.");
//                matOutput = mat;
            }

            /* Convert cv::mat to bitmap for drawing */
            Bitmap bitmap = Bitmap.createBitmap(matOutput.cols(), matOutput.rows(),Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(matOutput, bitmap);

            /* Display the result onto ImageView */
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    imageView.setImageBitmap(bitmap);
                }
            });


            if (filewriter_time != null && currentAttempt == (totalMeasurement+5)) { //およそ0.2秒ずつずれるようにしている
                if (NumofMeasurement == (totalMeasurement/5)*2+1) {// 動画2周するように
                    printwriter_time.close();
                    filewriter_time = null;
                    Log.i(TAG, "[analyze] Finish writing to CSV file.");
                    //                Toast.makeText(MainActivity.this, "finished.", Toast.LENGTH_SHORT).show();
                    final Handler mainHandler = new Handler(Looper.getMainLooper());
                    mainHandler.post(() -> {
                        textView.setText("Finished");
                    });
                } else {
                    NumofMeasurement += 1;
                    printwriter_time.println("# Measurement " + String.valueOf(NumofMeasurement));
                    currentAttempt = 0;
                    successfulDecodes = 0;
                    final Handler mainHandler = new Handler(Looper.getMainLooper());
                    mainHandler.post(() -> {
                        textView.setText("Start Measuremet " + String.valueOf(NumofMeasurement));
                    });
                    queue_rely.clear();
                    is_saturated = false;
                    matSrcOdd = null;
                    matSrcEven = null;
                    matDiffa = null;
                    matDiffb = null;
                    matDiff1 = null;
                    matDiff2 = null;
                    matDiffOdd = null;
                    matDiffEven = null;
                    matDst = null; // 最終的な差分画像
                    matBlur = null; // ノイズ除去後の画像
                    matBinary = null; // 白黒画像
                    matOutput = null; // 表示画像
                    odd = true;
                    start_time = System.currentTimeMillis();
                    Log.i(TAG,"all reset");
                    Log.i(TAG,"queue size: " + queue_rely.size());
                }
            }

            /* Close the image otherwise, this function is not called next time */
            Log.i(TAG,"frame finished");
            image.close();
        }

        private Mat getMatFromImage(ImageProxy image) {
            /* https://stackoverflow.com/questions/30510928/convert-android-camera2-api-yuv-420-888-to-rgb */
            ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
            ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
            ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();
            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();
            byte[] nv21 = new byte[ySize + uSize + vSize];
            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);
            Mat yuv = new Mat(image.getHeight() + image.getHeight() / 2, image.getWidth(), CvType.CV_8UC1);
            yuv.put(0, 0, nv21);
            Mat mat = new Mat();
            Imgproc.cvtColor(yuv, mat, Imgproc.COLOR_YUV2RGB_NV21, 3);
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGB2YCrCb, 3);
//            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGB2Lab, 3);
            //Size sz = new Size(400,300); // resizeする大きさ
            //Imgproc.resize(mat,mat,sz);
            /*int width = mat.width();
            int height = mat.height();

            // 中心の半分の領域の幅と高さを計算
            int newWidth = width / 3;
            int newHeight = (int)(height / 2.5);

            // 中心領域の開始座標を計算
            int startX = (width - newWidth) / 2;
            int startY = (height - newHeight) / 2;

            // 中心領域を定義するRectを作成
            org.opencv.core.Rect roi = new org.opencv.core.Rect(startX, startY, newWidth, newHeight);

            // 中心領域を抽出
            Mat cropped = new Mat(mat, roi);
            return cropped;*/
            return mat;
        }

        private Mat fixMatRotation(Mat matOrg) {
            Mat mat;
            switch (previewView.getDisplay().getRotation()){
                default:
                case Surface.ROTATION_0:
                    mat = new Mat(matOrg.cols(), matOrg.rows(), matOrg.type());
                    Core.transpose(matOrg, mat);
                    Core.flip(mat, mat, 1);
                    break;
                case Surface.ROTATION_90:
                    mat = matOrg;
                    break;
                case Surface.ROTATION_270:
                    mat = matOrg;
                    Core.flip(mat, mat, -1);
                    break;
            }
            return mat;
        }

        // 二つの信頼度の最大値を取ったものを生成する関数
        private Mat max_mat(Mat mat1, Mat mat2){
            Mat diff_rely;
            diff_rely = new Mat(mat1.cols(), mat1.rows(), mat1.type());
            //Size org_size = new Size(mat1.cols(),mat1.rows());
            //Size sz = new Size(600,400); // resizeする大きさ
            //Imgproc.resize(mat1,mat1,sz);
            //Imgproc.resize(mat2,mat2,sz);
            Mat abs_mat1, abs_mat2;
            abs_mat1 = new Mat(mat1.cols(), mat1.rows(), mat1.type());
            abs_mat2 = new Mat(mat1.cols(), mat1.rows(), mat1.type());
            Core.absdiff(mat1, new Scalar(128), abs_mat1);
            Core.absdiff(mat2, new Scalar(128), abs_mat2);
            Core.addWeighted(abs_mat1, 1.0, abs_mat2, -1.0, 128,diff_rely);
            Mat mask;
            mask = new Mat(mat1.cols(), mat1.rows(), mat1.type());
            Imgproc.threshold(diff_rely, mask, 127, 255, Imgproc.THRESH_BINARY);
            Mat out_mat;
            out_mat = new Mat(mat1.cols(), mat1.rows(), mat1.type());
            mat1.copyTo(out_mat,mask);
            Core.bitwise_not(mask,mask);
            mat2.copyTo(out_mat,mask);
            //Imgproc.resize(out_mat,out_mat,org_size);
            return out_mat;
        }
    }

    private boolean checkPermissions(){
        for(String permission : REQUIRED_PERMISSIONS){
            if(ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED){
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == REQUEST_CODE_FOR_PERMISSIONS){
            if(checkPermissions()){
                startCamera();
            } else{
                Log.i(TAG, "[onRequestPermissionsResult] Failed to get permissions");
                this.finish();
            }
        }
    }
}
