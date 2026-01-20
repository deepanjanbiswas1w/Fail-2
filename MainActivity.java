package com.khotornak.firewall;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    
    private static final int VPN_REQUEST_CODE = 100;
    
    private Switch vpnSwitch;
    private Button blockAppsButton;
    private TextView dataUsedText;
    private TextView requestsText;
    private TextView blockedText;
    private TextView statusText;
    
    private Handler statsHandler = new Handler(Looper.getMainLooper());
    private Runnable statsRunnable;
    
    private Set<String> blockedApps = new HashSet<>();
    
    private ActivityResultLauncher<Intent> vpnPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, 
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
        
        initViews();
        setupVpnPermissionLauncher();
        startStatsUpdate();
    }

    private void initViews() {
        vpnSwitch = findViewById(R.id.vpnSwitch);
        blockAppsButton = findViewById(R.id.blockAppsButton);
        dataUsedText = findViewById(R.id.dataUsedText);
        requestsText = findViewById(R.id.requestsText);
        blockedText = findViewById(R.id.blockedText);
        statusText = findViewById(R.id.statusText);
        
        vpnSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                startVpnService();
            } else {
                stopVpnService();
            }
        });
        
        blockAppsButton.setOnClickListener(v -> showBlockAppsDialog());
    }

    private void setupVpnPermissionLauncher() {
        vpnPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    // Permission granted, start the service
                    Intent intent = new Intent(this, KhotornakService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent);
                    } else {
                        startService(intent);
                    }
                    statusText.setText("Status: Active");
                    statusText.setTextColor(getColor(R.color.neon_green));
                } else {
                    // Permission denied
                    vpnSwitch.setChecked(false);
                    statusText.setText("Status: Permission Denied");
                    statusText.setTextColor(getColor(R.color.neon_red));
                    Toast.makeText(this, "VPN permission required", Toast.LENGTH_SHORT).show();
                }
            }
        );
    }

    private void startVpnService() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            // Need to request permission
            vpnPermissionLauncher.launch(intent);
        } else {
            // Permission already granted
            Intent serviceIntent = new Intent(this, KhotornakService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            statusText.setText("Status: Active");
            statusText.setTextColor(getColor(R.color.neon_green));
        }
    }

    private void stopVpnService() {
        Intent intent = new Intent(this, KhotornakService.class);
        intent.setAction("STOP_VPN");
        startService(intent);
        statusText.setText("Status: Inactive");
        statusText.setTextColor(getColor(R.color.neon_red));
    }

    private void showBlockAppsDialog() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        
        List<String> appNames = new ArrayList<>();
        List<String> packageNames = new ArrayList<>();
        boolean[] checkedItems = new boolean[apps.size()];
        
        int index = 0;
        for (ApplicationInfo app : apps) {
            // Skip system apps
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                String name = pm.getApplicationLabel(app).toString();
                String packageName = app.packageName;
                
                appNames.add(name);
                packageNames.add(packageName);
                checkedItems[index] = blockedApps.contains(packageName);
                index++;
            }
        }
        
        // Resize arrays to actual size
        boolean[] finalChecked = new boolean[index];
        System.arraycopy(checkedItems, 0, finalChecked, 0, index);
        
        String[] appNamesArray = appNames.toArray(new String[0]);
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Apps to Block");
        builder.setMultiChoiceItems(appNamesArray, finalChecked, 
            (dialog, which, isChecked) -> {
                String packageName = packageNames.get(which);
                if (isChecked) {
                    blockedApps.add(packageName);
                } else {
                    blockedApps.remove(packageName);
                }
            });
        
        builder.setPositiveButton("Apply", (dialog, which) -> {
            updateBlockedApps();
            Toast.makeText(this, 
                blockedApps.size() + " apps blocked", 
                Toast.LENGTH_SHORT).show();
        });
        
        builder.setNegativeButton("Cancel", null);
        builder.create().show();
    }

    private void updateBlockedApps() {
        Intent intent = new Intent(this, KhotornakService.class);
        intent.setAction("UPDATE_BLOCKED_APPS");
        intent.putExtra("blocked_apps", blockedApps.toArray(new String[0]));
        startService(intent);
    }

    private void startStatsUpdate() {
        statsRunnable = new Runnable() {
            @Override
            public void run() {
                updateStats();
                statsHandler.postDelayed(this, 1000);
            }
        };
        statsHandler.post(statsRunnable);
    }

    private void updateStats() {
        long totalBytes = KhotornakService.totalBytesReceived.get() + 
                         KhotornakService.totalBytesSent.get();
        long requests = KhotornakService.requestsLogged.get();
        long blocked = KhotornakService.blockedRequests.get();
        
        dataUsedText.setText(formatBytes(totalBytes));
        requestsText.setText(String.valueOf(requests));
        blockedText.setText(String.valueOf(blocked));
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        statsHandler.removeCallbacks(statsRunnable);
    }
}
