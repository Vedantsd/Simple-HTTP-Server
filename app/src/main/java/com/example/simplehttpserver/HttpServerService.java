package com.example.simplehttpserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;

public class HttpServerService extends Service {

    public static final String ACTION_START = "com.example.simplehttpserver.ACTION_START";
    public static final String ACTION_STOP = "com.example.simplehttpserver.ACTION_STOP";
    public static final String EXTRA_PORT = "EXTRA_PORT";
    public static final String EXTRA_FILE_URI = "EXTRA_FILE_URI";
    public static final String EXTRA_FOLDER_URI = "EXTRA_FOLDER_URI";

    private static final String CHANNEL_ID = "http_server_channel";
    private static final int NOTIFICATION_ID = 1001;

    public interface ServerStateListener {
        void onServerStarted(int port, String ip);
        void onServerStopped();
        void onServerError(String error);
        void onLog(String log);
    }

    private static final List<ServerStateListener> listeners = new ArrayList<>();
    private static SimpleHttpServer currentServer;
    private static int currentPort = 8080;
    private static boolean isServerRunning = false;
    private static final List<String> logHistory = new ArrayList<>();

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public HttpServerService getService() {
            return HttpServerService.this;
        }
    }

    public static void addListener(ServerStateListener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public static void removeListener(ServerStateListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    public static boolean isRunning() {
        return isServerRunning;
    }

    public static int getCurrentPort() {
        return currentPort;
    }

    public static List<String> getLogHistory() {
        synchronized (logHistory) {
            return new ArrayList<>(logHistory);
        }
    }

    public static void clearLogs() {
        synchronized (logHistory) {
            logHistory.clear();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopServerInternal();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            } else if (ACTION_START.equals(action)) {
                int port = intent.getIntExtra(EXTRA_PORT, 8080);
                String fileUriStr = intent.getStringExtra(EXTRA_FILE_URI);
                String folderUriStr = intent.getStringExtra(EXTRA_FOLDER_URI);

                Uri fileUri = fileUriStr != null ? Uri.parse(fileUriStr) : null;
                Uri folderUri = folderUriStr != null ? Uri.parse(folderUriStr) : null;

                startServerInternal(port, fileUri, folderUri);
            }
        }
        return START_NOT_STICKY;
    }

    private void startServerInternal(int port, Uri fileUri, Uri folderUri) {
        stopServerInternal();

        currentPort = port;
        currentServer = new SimpleHttpServer(this, port, fileUri, folderUri, this::broadcastLog);

        try {
            currentServer.start();
            isServerRunning = true;
            String ip = NetworkUtils.getLocalIpAddress(this);

            Notification notification = buildNotification("Running at http://" + (ip != null ? ip : "localhost") + ":" + port);
            startForeground(NOTIFICATION_ID, notification);

            broadcastStarted(port, ip);
        } catch (Exception e) {
            isServerRunning = false;
            broadcastError(e.getMessage());
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void stopServerInternal() {
        if (currentServer != null) {
            currentServer.stop();
            currentServer = null;
        }
        isServerRunning = false;
        broadcastStopped();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "HTTP Server",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows notification when local HTTP server is running");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String content) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, HttpServerService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Simple HTTP Server")
                .setContentText(content)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void broadcastStarted(int port, String ip) {
        synchronized (listeners) {
            for (ServerStateListener listener : listeners) {
                listener.onServerStarted(port, ip);
            }
        }
    }

    private void broadcastStopped() {
        synchronized (listeners) {
            for (ServerStateListener listener : listeners) {
                listener.onServerStopped();
            }
        }
    }

    private void broadcastError(String error) {
        synchronized (listeners) {
            for (ServerStateListener listener : listeners) {
                listener.onServerError(error);
            }
        }
    }

    private void broadcastLog(String log) {
        synchronized (logHistory) {
            if (logHistory.size() > 200) {
                logHistory.remove(0);
            }
            logHistory.add(log);
        }
        synchronized (listeners) {
            for (ServerStateListener listener : listeners) {
                listener.onLog(log);
            }
        }
    }

    @Override
    public void onDestroy() {
        stopServerInternal();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
