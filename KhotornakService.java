package com.khotornak.firewall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class KhotornakService extends VpnService {
    private static final String TAG = "KhotornakService";
    private static final String CHANNEL_ID = "KhotornakVPN";
    private static final int NOTIFICATION_ID = 1001;
    
    // AdGuard DNS Servers
    private static final String PRIMARY_DNS = "94.140.14.14";
    private static final String SECONDARY_DNS = "94.140.15.15";
    
    private ParcelFileDescriptor vpnInterface;
    private Thread vpnThread;
    private volatile boolean isRunning = false;
    
    // Statistics
    public static AtomicLong totalBytesReceived = new AtomicLong(0);
    public static AtomicLong totalBytesSent = new AtomicLong(0);
    public static AtomicLong requestsLogged = new AtomicLong(0);
    public static AtomicLong blockedRequests = new AtomicLong(0);
    
    // Blocked apps
    private static Set<String> blockedApps = new HashSet<>();
    
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("STOP_VPN".equals(action)) {
                stopVPN();
                return START_NOT_STICKY;
            } else if ("UPDATE_BLOCKED_APPS".equals(action)) {
                String[] apps = intent.getStringArrayExtra("blocked_apps");
                if (apps != null) {
                    blockedApps.clear();
                    for (String app : apps) {
                        blockedApps.add(app);
                    }
                    // Restart VPN with new settings
                    restartVPN();
                }
            }
        }
        
        startVPN();
        return START_STICKY;
    }

    private void startVPN() {
        if (isRunning) {
            return;
        }
        
        startForeground(NOTIFICATION_ID, createNotification());
        
        try {
            Builder builder = new Builder();
            
            // Configure VPN
            builder.setSession("Khotornak Firewall")
                   .addAddress("10.0.0.2", 24)
                   .addRoute("0.0.0.0", 0)
                   .addDnsServer(PRIMARY_DNS)
                   .addDnsServer(SECONDARY_DNS)
                   .setMtu(1500);
            
            // Block specific apps
            for (String packageName : blockedApps) {
                try {
                    builder.addDisallowedApplication(packageName);
                    Log.d(TAG, "Blocked app: " + packageName);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.e(TAG, "App not found: " + packageName, e);
                }
            }
            
            // Don't block ourselves
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Own package not found", e);
            }
            
            vpnInterface = builder.establish();
            
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN");
                stopSelf();
                return;
            }
            
            isRunning = true;
            
            // Start packet processing thread
            vpnThread = new Thread(this::processPackets, "VPN-Thread");
            vpnThread.start();
            
            Log.d(TAG, "VPN Started Successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting VPN", e);
            stopSelf();
        }
    }

    private void processPackets() {
        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
        
        ByteBuffer packet = ByteBuffer.allocate(32767);
        
        try {
            DatagramChannel tunnel = DatagramChannel.open();
            tunnel.connect(new InetSocketAddress(PRIMARY_DNS, 53));
            
            while (isRunning) {
                packet.clear();
                
                int length = in.read(packet.array());
                if (length > 0) {
                    packet.limit(length);
                    
                    // Update statistics
                    totalBytesReceived.addAndGet(length);
                    requestsLogged.incrementAndGet();
                    
                    // Simple packet inspection
                    if (shouldBlockPacket(packet)) {
                        blockedRequests.incrementAndGet();
                        continue; // Drop the packet
                    }
                    
                    // Forward packet
                    out.write(packet.array(), 0, length);
                    totalBytesSent.addAndGet(length);
                }
            }
        } catch (Exception e) {
            if (isRunning) {
                Log.e(TAG, "Error processing packets", e);
            }
        } finally {
            try {
                in.close();
                out.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing streams", e);
            }
        }
    }

    private boolean shouldBlockPacket(ByteBuffer packet) {
        // Basic ad-blocking logic - check for common ad domains
        // In production, you'd use a more sophisticated approach
        try {
            byte[] data = packet.array();
            String packetData = new String(data, 0, Math.min(data.length, 100));
            
            // Block common ad patterns
            String[] adPatterns = {
                "doubleclick", "googleads", "facebook.com/ads", 
                "analytics", "adservice", "ad-", "banner"
            };
            
            for (String pattern : adPatterns) {
                if (packetData.toLowerCase().contains(pattern)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Don't block on error
        }
        
        return false;
    }

    private void restartVPN() {
        stopVPN();
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        startVPN();
    }

    private void stopVPN() {
        isRunning = false;
        
        if (vpnThread != null) {
            vpnThread.interrupt();
            try {
                vpnThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            vpnThread = null;
        }
        
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN interface", e);
            }
            vpnInterface = null;
        }
        
        stopForeground(true);
        stopSelf();
        
        Log.d(TAG, "VPN Stopped");
    }

    @Override
    public void onDestroy() {
        stopVPN();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopVPN();
        super.onRevoke();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Khotornak Firewall VPN Service");
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        );
        
        Intent stopIntent = new Intent(this, KhotornakService.class);
        stopIntent.setAction("STOP_VPN");
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Khotornak Firewall Active")
            .setContentText("VPN & Ad-blocking enabled")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
}
