package com.blockblast.solver;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.blockblast.solver.service.OverlayService;

public class MainActivity extends Activity {

    private static final int REQ_OVERLAY  = 1001;
    private static final int REQ_CAPTURE  = 1002;

    private MediaProjectionManager mpm;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mpm        = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        statusText = findViewById(R.id.statusText);

        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop  = findViewById(R.id.btnStop);

        btnStart.setOnClickListener(v -> checkAndStart());
        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, OverlayService.class));
            statusText.setText("Status: Stopped");
        });
    }

    private void checkAndStart() {
        // 1) Overlay permission
        if (!Settings.canDrawOverlays(this)) {
            statusText.setText("Status: Need overlay permission…");
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_OVERLAY);
            return;
        }
        // 2) Screen capture permission
        statusText.setText("Status: Requesting screen capture…");
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) checkAndStart();
            else Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show();

        } else if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent svc = new Intent(this, OverlayService.class);
                svc.putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode);
                svc.putExtra(OverlayService.EXTRA_RESULT_DATA,  data);
                startForegroundService(svc);
                statusText.setText("Status: Running ✓ — switch to Block Blast!");
            } else {
                statusText.setText("Status: Screen capture denied");
            }
        }
    }
}
