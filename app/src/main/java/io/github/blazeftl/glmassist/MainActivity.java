/*
Copyright (c) 2017-2019 Divested Computing Group
This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/
package io.github.blazeftl.glmassist;

import static android.webkit.WebView.HitTestResult.IMAGE_TYPE;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.URLUtilCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "glmAssist";
    private static final String START_URL = "https://chat.z.ai/";
    private static final String ORIGIN = "https://chat.z.ai";
    private static final String BRIDGE = "glmAssistDownload";
    private static final boolean SPOOF_UA = true;
    private static final int REQ_FILE = 1;
    private static final int REQ_MEDIA = 123;
    private static final int REQ_STORAGE = 101;

    private WebView webView;
    private CookieManager cookies;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingMedia;
    private String[] pendingDownload;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        registerForContextMenu(webView);

        cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        if (SPOOF_UA) settings.setUserAgentString(modUserAgent());

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message.message().contains("NotAllowedError: Write permission denied.")) {
                    Toast.makeText(MainActivity.this, R.string.error_copy, Toast.LENGTH_LONG).show();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(intent, REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                ArrayList<String> missing = new ArrayList<>();
                for (String resource : request.getResources()) {
                    String permission = androidPermission(resource);
                    if (permission != null && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                        missing.add(permission);
                    }
                }
                if (missing.isEmpty()) {
                    grantMedia(request);
                    return;
                }
                if (pendingMedia != null) pendingMedia.deny();
                pendingMedia = request;
                requestPermissions(missing.toArray(new String[0]), REQ_MEDIA);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme) || "about".equals(scheme)
                        || "blob".equals(scheme) || "data".equals(scheme)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) ->
                download(url, contentDisposition, mimetype));

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, BRIDGE, Collections.singleton(ORIGIN),
                    (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                        String data = message.getData();
                        if (data != null) onBlobReceived(data);
                    });
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView,
                    "(function(){var revoke=URL.revokeObjectURL;" +
                    "URL.revokeObjectURL=function(u){setTimeout(function(){revoke.call(URL,u);},60000);};})();",
                    Collections.singleton(ORIGIN));
        }

        webView.loadUrl(START_URL);
    }

    @Override
    protected void onPause() {
        cookies.flush();
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (webView.canGoBack()) webView.goBack();
            else finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, intent));
            fileCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_MEDIA && pendingMedia != null) {
            PermissionRequest request = pendingMedia;
            pendingMedia = null;
            grantMedia(request);
            if (!granted) Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show();
        }
        if (requestCode == REQ_STORAGE) {
            String[] pending = pendingDownload;
            pendingDownload = null;
            if (granted && pending != null) download(pending[0], pending[1], pending[2]);
            else Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo info) {
        WebView.HitTestResult result = webView.getHitTestResult();
        if (result.getType() == IMAGE_TYPE && result.getExtra() != null) {
            download(result.getExtra(), null, "image/jpeg");
        }
    }

    private static String androidPermission(String resource) {
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) return Manifest.permission.RECORD_AUDIO;
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) return Manifest.permission.CAMERA;
        return null;
    }

    private void grantMedia(PermissionRequest request) {
        ArrayList<String> allowed = new ArrayList<>();
        for (String resource : request.getResources()) {
            String permission = androidPermission(resource);
            if (permission != null && checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                allowed.add(resource);
            }
        }
        if (allowed.isEmpty()) request.deny();
        else request.grant(allowed.toArray(new String[0]));
    }

    private String modUserAgent() {
        String ua = WebSettings.getDefaultUserAgent(this);
        int end = ua.indexOf(')');
        if (end < 0) return ua;
        return "Mozilla/5.0 (X11; Linux " + System.getProperty("os.arch") + ")" + ua.substring(end + 1);
    }

    private void download(String url, String contentDisposition, String mimetype) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = new String[]{url, contentDisposition, mimetype};
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        String name = contentDisposition == null ? null : URLUtilCompat.getFilenameFromContentDisposition(contentDisposition);
        if (url.startsWith("blob:")) downloadBlob(url, name, mimetype);
        else if (url.startsWith("data:")) saveDataUrl(url, name);
        else if (url.startsWith("http")) enqueue(url, name, contentDisposition, mimetype);
        else Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
    }

    private void enqueue(String url, String name, String contentDisposition, String mimetype) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            String cookie = cookies.getCookie(url);
            if (cookie != null) request.addRequestHeader("Cookie", cookie);
            request.addRequestHeader("User-Agent", webView.getSettings().getUserAgentString());
            request.addRequestHeader("Referer", START_URL);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (name == null) name = URLUtil.guessFileName(url, contentDisposition, mimetype);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            Toast.makeText(this, getString(R.string.download) + "\n" + name, Toast.LENGTH_SHORT).show();
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
        } catch (Exception e) {
            Log.e(TAG, "[enqueue] " + url, e);
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void downloadBlob(String url, String filename, String mimetype) {
        if (!url.startsWith("blob:" + ORIGIN + "/") || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(TAG, "[downloadBlob] Unsupported: " + url);
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        String js = "(function(){" +
                "var post=function(m){" + BRIDGE + ".postMessage(JSON.stringify(m));};" +
                "fetch(" + JSONObject.quote(url) + ")" +
                ".then(function(r){return r.blob();})" +
                ".then(function(b){" +
                "var fr=new FileReader();" +
                "fr.onload=function(){post({name:" + JSONObject.quote(filename == null ? "" : filename) +
                ",type:b.type||" + JSONObject.quote(mimetype == null ? "" : mimetype) + ",data:fr.result});};" +
                "fr.onerror=function(){post({error:String(fr.error)});};" +
                "fr.readAsDataURL(b);})" +
                ".catch(function(e){post({error:String(e)});});" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    private void onBlobReceived(String json) {
        new Thread(() -> {
            try {
                JSONObject blob = new JSONObject(json);
                if (blob.has("error")) throw new IOException(blob.getString("error"));
                String data = blob.getString("data");
                byte[] bytes = Base64.decode(data.substring(data.indexOf(',') + 1), Base64.DEFAULT);
                saveBytes(bytes, blob.optString("name"), blob.optString("type"));
            } catch (Exception e) {
                Log.e(TAG, "[onBlobReceived]", e);
                runOnUiThread(() -> Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void saveDataUrl(String url, String name) {
        new Thread(() -> {
            try {
                int comma = url.indexOf(',');
                String meta = url.substring(5, comma);
                String payload = url.substring(comma + 1);
                byte[] bytes = meta.endsWith(";base64")
                        ? Base64.decode(payload, Base64.DEFAULT)
                        : URLDecoder.decode(payload, "UTF-8").getBytes(StandardCharsets.UTF_8);
                saveBytes(bytes, name == null ? "" : name, meta.split(";")[0]);
            } catch (Exception e) {
                Log.e(TAG, "[saveDataUrl]", e);
                runOnUiThread(() -> Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void saveBytes(byte[] bytes, String name, String mimetype) {
        try {
            String filename = fileName(name, mimetype);
            String saved = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? saveToMediaStore(bytes, filename, mimetype)
                    : saveToDownloadsDir(bytes, filename, mimetype);
            runOnUiThread(() -> Toast.makeText(this, getString(R.string.download_saved) + "\n" + saved, Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            Log.e(TAG, "[saveBytes]", e);
            runOnUiThread(() -> Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show());
        }
    }

    private static String fileName(String name, String mimetype) {
        name = name == null ? "" : name.replaceAll("[/\\\\:]", "_");
        if (name.isEmpty() || name.startsWith(".")) {
            name = "GLM_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        }
        String extension = mimetype == null ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(mimetype);
        if (extension != null && !name.contains(".")) name += "." + extension;
        return name;
    }

    private String saveToMediaStore(byte[] bytes, String filename, String mimetype) throws IOException {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
        values.put(MediaStore.Downloads.MIME_TYPE, mimetype == null || mimetype.isEmpty() ? "application/octet-stream" : mimetype);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri item = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (item == null) throw new IOException("MediaStore insert failed for " + filename);
        try (OutputStream out = resolver.openOutputStream(item)) {
            if (out == null) throw new IOException("Cannot open " + item);
            out.write(bytes);
        } catch (IOException e) {
            resolver.delete(item, null, null);
            throw e;
        }
        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(item, values, null, null);
        return filename;
    }

    private String saveToDownloadsDir(byte[] bytes, String filename, String mimetype) throws IOException {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String extension = dot > 0 ? filename.substring(dot) : "";
        File file = new File(dir, filename);
        for (int i = 1; file.exists(); i++) file = new File(dir, base + " (" + i + ")" + extension);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()},
                mimetype == null || mimetype.isEmpty() ? null : new String[]{mimetype}, null);
        return file.getName();
    }
}
