package com.example.simplehttpserver;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SimpleHttpServer {

    public interface LogListener {
        void onLog(String message);
    }

    private final Context context;
    private final int port;
    private final Uri singleFileUri;
    private final Uri folderTreeUri;
    private final LogListener logListener;

    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean running = false;

    private static final Map<String, String> MIME_TYPES = new HashMap<>();

    static {
        MIME_TYPES.put("html", "text/html; charset=UTF-8");
        MIME_TYPES.put("htm", "text/html; charset=UTF-8");
        MIME_TYPES.put("css", "text/css; charset=UTF-8");
        MIME_TYPES.put("js", "application/javascript; charset=UTF-8");
        MIME_TYPES.put("mjs", "application/javascript; charset=UTF-8");
        MIME_TYPES.put("json", "application/json; charset=UTF-8");
        MIME_TYPES.put("xml", "application/xml; charset=UTF-8");
        MIME_TYPES.put("txt", "text/plain; charset=UTF-8");
        MIME_TYPES.put("png", "image/png");
        MIME_TYPES.put("jpg", "image/jpeg");
        MIME_TYPES.put("jpeg", "image/jpeg");
        MIME_TYPES.put("gif", "image/gif");
        MIME_TYPES.put("svg", "image/svg+xml");
        MIME_TYPES.put("webp", "image/webp");
        MIME_TYPES.put("ico", "image/x-icon");
        MIME_TYPES.put("woff", "font/woff");
        MIME_TYPES.put("woff2", "font/woff2");
        MIME_TYPES.put("ttf", "font/ttf");
        MIME_TYPES.put("otf", "font/otf");
        MIME_TYPES.put("mp3", "audio/mpeg");
        MIME_TYPES.put("mp4", "video/mp4");
        MIME_TYPES.put("webm", "video/webm");
        MIME_TYPES.put("pdf", "application/pdf");
    }

    public SimpleHttpServer(Context context, int port, Uri singleFileUri, Uri folderTreeUri, LogListener logListener) {
        this.context = context.getApplicationContext();
        this.port = port;
        this.singleFileUri = singleFileUri;
        this.folderTreeUri = folderTreeUri;
        this.logListener = logListener;
    }

    public synchronized void start() throws Exception {
        if (running) {
            return;
        }
        serverSocket = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"));
        threadPool = Executors.newCachedThreadPool();
        running = true;

        threadPool.execute(() -> {
            while (running && !serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    threadPool.execute(() -> handleClient(socket));
                } catch (Exception e) {
                    if (!running) {
                        break;
                    }
                }
            }
        });
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
            serverSocket = null;
        }
        if (threadPool != null) {
            threadPool.shutdownNow();
            threadPool = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    private void handleClient(Socket socket) {
        try (socket;
             InputStream in = socket.getInputStream();
             OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }

            String method = parts[0];
            String rawUri = parts[1];

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    break;
                }
            }

            if (!method.equalsIgnoreCase("GET") && !method.equalsIgnoreCase("HEAD")) {
                sendResponse(out, 405, "Method Not Allowed", "text/plain; charset=UTF-8",
                        "Method Not Allowed".getBytes(StandardCharsets.UTF_8), method.equalsIgnoreCase("HEAD"));
                log(method + " " + rawUri + " -> 405");
                return;
            }

            String path = rawUri;
            int queryIndex = path.indexOf('?');
            if (queryIndex != -1) {
                path = path.substring(0, queryIndex);
            }
            path = URLDecoder.decode(path, "UTF-8");
            if (path.isEmpty() || path.equals("/")) {
                path = "/index.html";
            }

            if (path.contains("..")) {
                sendResponse(out, 403, "Forbidden", "text/plain; charset=UTF-8",
                        "Forbidden".getBytes(StandardCharsets.UTF_8), method.equalsIgnoreCase("HEAD"));
                log(method + " " + rawUri + " -> 403 Forbidden");
                return;
            }

            serveResource(out, path, rawUri, method.equalsIgnoreCase("HEAD"));

        } catch (Exception ignored) {
        }
    }

    private void serveResource(OutputStream out, String path, String originalUri, boolean isHead) {
        try {
            if (singleFileUri != null) {
                ContentResolver resolver = context.getContentResolver();
                String mimeType = "text/html; charset=UTF-8";
                try (InputStream stream = resolver.openInputStream(singleFileUri)) {
                    if (stream == null) {
                        send404(out, originalUri, isHead);
                        return;
                    }
                    sendStreamResponse(out, 200, "OK", mimeType, stream, isHead);
                    log("GET " + originalUri + " -> 200 OK (Single File)");
                }
                return;
            }

            if (folderTreeUri != null) {
                DocumentFile rootDoc = DocumentFile.fromTreeUri(context, folderTreeUri);
                if (rootDoc == null || !rootDoc.isDirectory()) {
                    send404(out, originalUri, isHead);
                    return;
                }

                DocumentFile targetFile = resolveDocumentFile(rootDoc, path);
                if (targetFile != null && targetFile.isDirectory()) {
                    targetFile = targetFile.findFile("index.html");
                }

                if (targetFile == null || !targetFile.isFile() || !targetFile.exists()) {
                    send404(out, originalUri, isHead);
                    return;
                }

                String mimeType = getMimeType(targetFile.getName());
                ContentResolver resolver = context.getContentResolver();
                try (InputStream stream = resolver.openInputStream(targetFile.getUri())) {
                    if (stream == null) {
                        send404(out, originalUri, isHead);
                        return;
                    }
                    sendStreamResponse(out, 200, "OK", mimeType, stream, isHead);
                    log("GET " + originalUri + " -> 200 OK (" + targetFile.getName() + ")");
                }
                return;
            }

            send404(out, originalUri, isHead);
        } catch (Exception e) {
            try {
                sendResponse(out, 500, "Internal Server Error", "text/plain; charset=UTF-8",
                        ("500 Internal Server Error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8), isHead);
                log("GET " + originalUri + " -> 500 Error: " + e.getMessage());
            } catch (Exception ignored) {
            }
        }
    }

    private DocumentFile resolveDocumentFile(DocumentFile root, String path) {
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        if (cleanPath.isEmpty()) {
            return root.findFile("index.html");
        }

        String[] segments = cleanPath.split("/");
        DocumentFile current = root;

        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) {
                continue;
            }

            DocumentFile next = current.findFile(segment);
            if (next == null) {
                return null;
            }

            if (i == segments.length - 1) {
                return next;
            }

            if (!next.isDirectory()) {
                return null;
            }

            current = next;
        }

        return current;
    }

    private String getMimeType(String filename) {
        if (filename == null) {
            return "application/octet-stream";
        }
        int dot = filename.lastIndexOf('.');
        if (dot != -1 && dot < filename.length() - 1) {
            String ext = filename.substring(dot + 1).toLowerCase();
            String mapped = MIME_TYPES.get(ext);
            if (mapped != null) {
                return mapped;
            }
            String systemMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (systemMime != null) {
                return systemMime;
            }
        }
        return "application/octet-stream";
    }

    private void send404(OutputStream out, String originalUri, boolean isHead) throws Exception {
        String body = "<!DOCTYPE html><html><head><title>404 Not Found</title></head>"
                + "<body style=\"font-family:sans-serif;text-align:center;padding:50px;\">"
                + "<h1>404 Not Found</h1><p>Resource <code>" + originalUri + "</code> was not found on this server.</p>"
                + "</body></html>";
        sendResponse(out, 404, "Not Found", "text/html; charset=UTF-8", body.getBytes(StandardCharsets.UTF_8), isHead);
        log("GET " + originalUri + " -> 404 Not Found");
    }

    private void sendResponse(OutputStream out, int statusCode, String statusText, String contentType, byte[] body, boolean isHead) throws Exception {
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("Content-Length: ").append(body.length).append("\r\n");
        header.append("Connection: close\r\n");
        header.append("Access-Control-Allow-Origin: *\r\n");
        header.append("\r\n");

        out.write(header.toString().getBytes(StandardCharsets.UTF_8));
        if (!isHead) {
            out.write(body);
        }
        out.flush();
    }

    private void sendStreamResponse(OutputStream out, int statusCode, String statusText, String contentType, InputStream data, boolean isHead) throws Exception {
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(statusCode).append(" ").append(statusText).append("\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("Connection: close\r\n");
        header.append("Access-Control-Allow-Origin: *\r\n");
        header.append("\r\n");

        out.write(header.toString().getBytes(StandardCharsets.UTF_8));
        if (!isHead) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = data.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        out.flush();
    }

    private void log(String message) {
        if (logListener != null) {
            logListener.onLog(message);
        }
    }
}
