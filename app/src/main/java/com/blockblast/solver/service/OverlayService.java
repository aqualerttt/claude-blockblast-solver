package com.blockblast.solver.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import androidx.core.app.NotificationCompat;

import com.blockblast.solver.detector.BoardDetector;
import com.blockblast.solver.overlay.OverlayView;
import com.blockblast.solver.solver.BlockSolver;

import java.nio.ByteBuffer;

public class OverlayService extends Service {

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID   = "blockblast_solver";
    private static final int    NOTIF_ID     = 42;
    private static final long   INTERVAL_MS  = 500; // analyse every 500ms

    private MediaProjection  projection;
    private VirtualDisplay   virtualDisplay;
    private ImageReader      imageReader;
    private OverlayView      overlayView;
    private WindowManager    windowManager;
    private Handler          handler;

    private int screenW, screenH, screenDpi;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());

        int      resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW   = dm.widthPixels;
        screenH   = dm.heightPixels;
        screenDpi = dm.densityDpi;

        // Set up MediaProjection using your variable names
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, resultData);

        // Android 14+ Security Callback Registration
        if (android.os.Build.VERSION.SDK_INT >= 34 && projection != null) {
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    super.onStop();
                }
            }, new Handler(Looper.getMainLooper()));
        }

        // ImageReader captures screen frames
        imageReader = ImageReader.newInstance(screenW, screenH,
                PixelFormat.RGBA_8888, 2);

        // Create the actual virtual display underneath the callback
        if (projection != null) {
            virtualDisplay = projection.createVirtualDisplay(
                    "BlockBlastCapture",
                    screenW, screenH, screenDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, null);
        }

        // Add overlay
        addOverlay();

        // Start periodic analysis
        handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(analyseLoop, INTERVAL_MS);

        return START_NOT_STICKY;
    }

    // ── Overlay ────────────────────────────────────────────────────────────

    private void addOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayView   = new OverlayView(this);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(overlayView, params);
    }

    // ── Analysis loop ──────────────────────────────────────────────────────

    private final Runnable analyseLoop = new Runnable() {
        @Override public void run() {
            analyseFrame();
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    private void analyseFrame() {
        if (imageReader == null) return;
        Image img = imageReader.acquireLatestImage();
        if (img == null) return;

        try {
            Bitmap bmp = imageToBitmap(img);
            if (bmp == null) return;

            BoardDetector detector = new BoardDetector();
            detector.detect(bmp, this);

            BlockSolver.Placement[] placements =
                    BlockSolver.solve(detector.board, detector.pieces);

            overlayView.update(detector.board, placements, screenW, screenH,
                    detector.debugPieceX, detector.debugCellSize,
                    detector.debugTrayTop + (detector.debugTrayBottom - detector.debugTrayTop) / 2);
            bmp.recycle();
        } finally {
            img.close();
        }
    }

    private Bitmap imageToBitmap(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer    buf    = planes[0].getBuffer();
            int rowStride  = planes[0].getRowStride();
            int pixelStride= planes[0].getPixelStride();
            int W = image.getWidth();
            int H = image.getHeight();

            // Handle row stride padding
            int rowPadding = rowStride - pixelStride * W;
            Bitmap bmp = Bitmap.createBitmap(
                    W + rowPadding / pixelStride, H, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buf);

            // Crop to exact screen size if padded
            if (rowPadding != 0) {
                Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, W, H);
                bmp.recycle();
                return cropped;
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void onDestroy() {
        if (handler     != null) handler.removeCallbacksAndMessages(null);
        if (overlayView != null) windowManager.removeView(overlayView);
        if (virtualDisplay != null) virtualDisplay.release();
        if (projection  != null) projection.stop();
        if (imageReader != null) imageReader.close();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ── Notification ───────────────────────────────────────────────────────

    private Notification buildNotification() {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Block Blast Solver",
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Block Blast Solver")
                .setContentText("Overlay active — switch to your game")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
    }
}
