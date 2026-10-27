package com.example.simplehttpserver;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.button.MaterialButton;

import java.util.List;

public class MainActivity extends AppCompatActivity implements HttpServerService.ServerStateListener {

    private Button btnSelectFile;
    private Button btnSelectFolder;
    private MaterialButton btnStartServer;
    private Button btnCopyURL;
    private Button btnOpenBrowser;
    private TextView btnClearLogs;

    private TextView tvFolderSelect;
    private TextView tvSourceDetails;
    private EditText etPort;
    private TextView tvServerStatusBadge;
    private TextView tvServerStatus;
    private TextView tvIP;
    private TextView tvPort;
    private TextView tvURL;
    private TextView tvLogConsole;
    private ScrollView scrollLogs;
    private LinearLayout layoutActionButtons;

    private Uri selectedFileUri = null;
    private Uri selectedFolderUri = null;
    private String currentServerUrl = "";

    private final ActivityResultLauncher<String[]> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );
                    } catch (Exception ignored) {
                    }
                    selectedFileUri = uri;
                    selectedFolderUri = null;
                    onFileSelected(uri);
                }
            });

    private final ActivityResultLauncher<Uri> folderPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );
                    } catch (Exception ignored) {
                    }
                    selectedFolderUri = uri;
                    selectedFileUri = null;
                    onFolderSelected(uri);
                }
            });

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        setupListeners();
        checkNotificationPermission();
    }

    private void initViews() {
        btnSelectFile = findViewById(R.id.btnSelectFile);
        btnSelectFolder = findViewById(R.id.btnSelectFolder);
        btnStartServer = findViewById(R.id.btnStartServer);
        btnCopyURL = findViewById(R.id.btnCopyURL);
        btnOpenBrowser = findViewById(R.id.btnOpenBrowser);
        btnClearLogs = findViewById(R.id.btnClearLogs);

        tvFolderSelect = findViewById(R.id.TVFolderSelect);
        tvSourceDetails = findViewById(R.id.TVSourceDetails);
        etPort = findViewById(R.id.ETPort);
        tvServerStatusBadge = findViewById(R.id.TVServerStatusBadge);
        tvServerStatus = findViewById(R.id.TVServerStatus);
        tvIP = findViewById(R.id.TVIP);
        tvPort = findViewById(R.id.TVPort);
        tvURL = findViewById(R.id.TVURL);
        tvLogConsole = findViewById(R.id.TVLogConsole);
        scrollLogs = findViewById(R.id.scrollLogs);
        layoutActionButtons = findViewById(R.id.layoutActionButtons);
    }

    private void setupListeners() {
        btnSelectFile.setOnClickListener(v -> filePickerLauncher.launch(new String[]{"text/html", "*/*"}));

        btnSelectFolder.setOnClickListener(v -> folderPickerLauncher.launch(null));

        btnStartServer.setOnClickListener(v -> {
            if (HttpServerService.isRunning()) {
                stopServer();
            } else {
                startServer();
            }
        });

        btnCopyURL.setOnClickListener(v -> {
            if (!currentServerUrl.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Server URL", currentServerUrl);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "URL copied to clipboard", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnOpenBrowser.setOnClickListener(v -> {
            if (!currentServerUrl.isEmpty()) {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(currentServerUrl));
                startActivity(browserIntent);
            }
        });

        btnClearLogs.setOnClickListener(v -> {
            HttpServerService.clearLogs();
            tvLogConsole.setText("");
        });
    }

    private void onFileSelected(Uri uri) {
        String fileName = getFileName(uri);
        tvFolderSelect.setText("File: " + fileName);
        tvSourceDetails.setText("Single HTML file mode ready to serve");
        Toast.makeText(this, "Selected: " + fileName, Toast.LENGTH_SHORT).show();
    }

    private void onFolderSelected(Uri uri) {
        DocumentFile doc = DocumentFile.fromTreeUri(this, uri);
        String folderName = doc != null && doc.getName() != null ? doc.getName() : "Selected Folder";
        tvFolderSelect.setText("Folder: " + folderName);

        boolean hasIndex = doc != null && doc.findFile("index.html") != null;
        if (hasIndex) {
            tvSourceDetails.setText("Folder website mode (index.html found)");
            Toast.makeText(this, "index.html detected in " + folderName, Toast.LENGTH_SHORT).show();
        } else {
            tvSourceDetails.setText("Folder website mode (Note: index.html not found in root)");
            Toast.makeText(this, "Folder selected. Note: index.html was not found in root", Toast.LENGTH_LONG).show();
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index != -1) {
                        result = cursor.getString(index);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result != null ? result : "Selected File";
    }

    private void startServer() {
        if (selectedFileUri == null && selectedFolderUri == null) {
            Toast.makeText(this, "Please select an HTML file or a folder first", Toast.LENGTH_SHORT).show();
            return;
        }

        int port = 8080;
        String portStr = etPort.getText().toString().trim();
        if (!portStr.isEmpty()) {
            try {
                port = Integer.parseInt(portStr);
                if (port < 1024 || port > 65535) {
                    Toast.makeText(this, "Please enter a valid port between 1024 and 65535", Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Intent intent = new Intent(this, HttpServerService.class);
        intent.setAction(HttpServerService.ACTION_START);
        intent.putExtra(HttpServerService.EXTRA_PORT, port);
        if (selectedFileUri != null) {
            intent.putExtra(HttpServerService.EXTRA_FILE_URI, selectedFileUri.toString());
        }
        if (selectedFolderUri != null) {
            intent.putExtra(HttpServerService.EXTRA_FOLDER_URI, selectedFolderUri.toString());
        }

        ContextCompat.startForegroundService(this, intent);
    }

    private void stopServer() {
        Intent intent = new Intent(this, HttpServerService.class);
        intent.setAction(HttpServerService.ACTION_STOP);
        startService(intent);
    }

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        HttpServerService.addListener(this);
        updateUiState();
        refreshLogConsole();
    }

    @Override
    protected void onPause() {
        HttpServerService.removeListener(this);
        super.onPause();
    }

    private void updateUiState() {
        boolean isRunning = HttpServerService.isRunning();
        int port = HttpServerService.getCurrentPort();
        String ip = NetworkUtils.getLocalIpAddress(this);

        if (isRunning) {
            tvServerStatusBadge.setText("RUNNING");
            tvServerStatusBadge.setBackgroundResource(R.drawable.bg_badge_running);
            tvServerStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_running));

            tvServerStatus.setText("Server is running and serving requests");
            tvIP.setText("Wi-Fi IP: " + (ip != null ? ip : "No active Wi-Fi"));
            tvPort.setText("Port: " + port);

            String host = ip != null ? ip : "localhost";
            currentServerUrl = "http://" + host + ":" + port + "/";
            tvURL.setText("URL: " + currentServerUrl);

            layoutActionButtons.setVisibility(View.VISIBLE);
            btnStartServer.setText("Stop Server");
            btnStartServer.setBackgroundColor(ContextCompat.getColor(this, R.color.status_stopped));
            etPort.setEnabled(false);
            btnSelectFile.setEnabled(false);
            btnSelectFolder.setEnabled(false);
        } else {
            tvServerStatusBadge.setText("STOPPED");
            tvServerStatusBadge.setBackgroundResource(R.drawable.bg_badge_stopped);
            tvServerStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_stopped));

            tvServerStatus.setText("Server is stopped");
            tvIP.setText("Wi-Fi IP: " + (ip != null ? ip : "Not connected"));
            tvPort.setText("Port: " + (etPort.getText().toString().isEmpty() ? "8080" : etPort.getText().toString()));
            tvURL.setText("URL: -");
            currentServerUrl = "";

            layoutActionButtons.setVisibility(View.GONE);
            btnStartServer.setText("Start Server");
            btnStartServer.setBackgroundColor(ContextCompat.getColor(this, R.color.primary));
            etPort.setEnabled(true);
            btnSelectFile.setEnabled(true);
            btnSelectFolder.setEnabled(true);
        }
    }

    private void refreshLogConsole() {
        List<String> logs = HttpServerService.getLogHistory();
        if (logs.isEmpty()) {
            if (!HttpServerService.isRunning()) {
                tvLogConsole.setText("Ready to host. Start server to see logs.");
            } else {
                tvLogConsole.setText("Server started. Awaiting incoming requests...");
            }
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (String log : logs) {
            sb.append(log).append("\n");
        }
        tvLogConsole.setText(sb.toString().trim());
        scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public void onServerStarted(int port, String ip) {
        runOnUiThread(() -> {
            updateUiState();
            refreshLogConsole();
            Toast.makeText(this, "Server started successfully on port " + port, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onServerStopped() {
        runOnUiThread(() -> {
            updateUiState();
            Toast.makeText(this, "Server stopped", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onServerError(String error) {
        runOnUiThread(() -> {
            updateUiState();
            Toast.makeText(this, "Server error: " + error, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onLog(String log) {
        runOnUiThread(() -> {
            String current = tvLogConsole.getText().toString();
            if (current.equals("Ready to host. Start server to see logs.") ||
                    current.equals("Server started. Awaiting incoming requests...")) {
                tvLogConsole.setText(log);
            } else {
                tvLogConsole.append("\n" + log);
            }
            scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
        });
    }
}