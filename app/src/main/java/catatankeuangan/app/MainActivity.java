package catatankeuangan.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.View;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.widget.ProgressBar;
import android.app.DownloadManager;
import android.net.Uri;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.widget.Toast;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.Toast;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.view.Menu;
import android.view.MenuItem;
import android.app.AlertDialog;
import android.net.Uri;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.view.Gravity;
import android.webkit.ValueCallback;
import android.provider.MediaStore;
import android.os.Environment;
import android.content.ContentValues;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
import androidx.biometric.BiometricPrompt;
import androidx.biometric.BiometricManager;
import java.util.concurrent.Executor;

public class MainActivity extends androidx.appcompat.app.AppCompatActivity {

    private WebView webView;
    private ProgressBar progressBar;
    private String fcmTokenForWebView = "";
    private static final String WEBSITE_URL = "https://share.gemini.google/N5bJLogWWW7C";
    private SwipeRefreshLayout swipeRefresh;
    private ValueCallback<Uri[]> fileUploadCallback;
    private Uri cameraImageUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Set system bar colors
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int systemBarColor = Color.parseColor("#4F46E5");
            getWindow().setStatusBarColor(systemBarColor);
            getWindow().setNavigationBarColor(systemBarColor);
        }
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setColorSchemeColors(Color.parseColor("#6366F1"));
        swipeRefresh.setOnRefreshListener(() -> {
            webView.reload();
        });
        checkAndShowRateDialog(5);
        // Clear cache on start
        webView.clearCache(true);
        webView.clearHistory();
        
        setupWebView();
        
        // Enable Service Worker support (API 24+)
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            ServiceWorkerController swController = ServiceWorkerController.getInstance();
            swController.setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    return null;
                }
            });
        }

        // Request POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }

        // Explicitly fetch and send FCM token at app startup
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        final String fcmToken = task.getResult();
                        fcmTokenForWebView = fcmToken;
                        android.util.Log.d("FCM_TOKEN", "Token received: " + fcmToken);

                        // Show token status via JavaScript in WebView (debug)
                        runOnUiThread(() -> {
                            if (webView != null) {
                                webView.evaluateJavascript(
                                    "window.FCM_TOKEN = '" + fcmToken + "'; window.dispatchEvent(new Event('fcm_token_ready')); console.log('FCM Token set');", null);
                            }
                        });

                        new Thread(() -> {
                            try {
                                // Endpoint is generated by PHP based on Website URL:
                                // User app  => /fcm_token.php
                                // Admin app => /admin/fcm_token.php
                                String endpoint = "https://share.gemini.google/fcm_token.php";
                                android.util.Log.d("FCM_TOKEN", "Sending token to: " + endpoint);

                                java.net.URL tokenUrl = new java.net.URL(endpoint);
                                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) tokenUrl.openConnection();
                                conn.setRequestMethod("POST");
                                conn.setRequestProperty("Content-Type", "application/json");
                                conn.setRequestProperty("User-Agent", "BPWalletApp/1.0");
                                conn.setDoOutput(true);
                                conn.setConnectTimeout(15000);
                                conn.setReadTimeout(15000);
                                String body = "{\"token\":\"" + fcmToken + "\"}";
                                try (java.io.OutputStream os = conn.getOutputStream()) {
                                    os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                }
                                int status = conn.getResponseCode();
                                android.util.Log.d("FCM_TOKEN", "POST response: HTTP " + status);
                                conn.disconnect();

                                // Fallback: GET request if POST fails
                                if (status < 200 || status >= 300) {
                                    android.util.Log.d("FCM_TOKEN", "POST failed, trying GET fallback...");
                                    String t = java.net.URLEncoder.encode(fcmToken, "UTF-8");
                                    java.net.URL fallbackUrl = new java.net.URL(endpoint + "?token=" + t);
                                    java.net.HttpURLConnection fallbackConn = (java.net.HttpURLConnection) fallbackUrl.openConnection();
                                    fallbackConn.setRequestMethod("GET");
                                    fallbackConn.setRequestProperty("User-Agent", "BPWalletApp/1.0");
                                    fallbackConn.setConnectTimeout(15000);
                                    fallbackConn.setReadTimeout(15000);
                                    int fbStatus = fallbackConn.getResponseCode();
                                    android.util.Log.d("FCM_TOKEN", "GET fallback response: HTTP " + fbStatus);
                                    fallbackConn.disconnect();
                                }
                            } catch (Exception e) {
                                android.util.Log.e("FCM_TOKEN", "Error sending token: " + e.getMessage(), e);
                            }
                        }).start();
                    } else {
                        android.util.Log.e("FCM_TOKEN", "Failed to get token: " + (task.getException() != null ? task.getException().getMessage() : "unknown error"));
                        // Show error as Toast so user can see
                        runOnUiThread(() -> {
                            String errMsg = task.getException() != null ? task.getException().getMessage() : "Unknown FCM error";
                            Toast.makeText(MainActivity.this, "FCM Error: " + errMsg, Toast.LENGTH_LONG).show();
                        });
                    }
                });
        } catch (Exception e) {
            android.util.Log.e("FCM_TOKEN", "Firebase init error: " + e.getMessage(), e);
            Toast.makeText(this, "Firebase Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        // Schedule background notification polling every 15 minutes
        PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
            NotificationWorker.class, 15, TimeUnit.MINUTES)
            .build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "notification_check",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest);
        
        // Request runtime permissions
        java.util.List<String> permissionsNeeded = new java.util.ArrayList<>();
        String[] requiredPerms = new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR, Manifest.permission.USE_BIOMETRIC};
        for (String perm : requiredPerms) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(perm);
            }
        }
        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), 2001);
        }
        
        setupFAB();
        authenticateUser();
        
        // Handle deep link intent
        handleIntent(getIntent());
        
        // Load directly; ConnectivityManager can be unreliable on some devices/VPNs.
        // WebView will show its own error page if the connection is actually unavailable.
        webView.loadUrl(WEBSITE_URL);
    }

    private void checkAndShowRateDialog(int targetLaunches) {
        android.content.SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        boolean ratedAlready = prefs.getBoolean("rated", false);
        if (ratedAlready) return;
        int launches = prefs.getInt("launch_count", 0) + 1;
        prefs.edit().putInt("launch_count", launches).apply();
        if (launches == targetLaunches) {
            new AlertDialog.Builder(this)
                .setTitle("Enjoying the app?")
                .setMessage("If you like the app, please take a moment to rate it. It won't take more than a minute. Thanks!")
                .setPositiveButton("Rate Now", (d, w) -> {
                    prefs.edit().putBoolean("rated", true).apply();
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + getPackageName())));
                    } catch (android.content.ActivityNotFoundException e) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + getPackageName())));
                    }
                })
                .setNeutralButton("Remind Later", null)
                .setNegativeButton("No Thanks", (d, w) -> prefs.edit().putBoolean("rated", true).apply())
                .show();
        }
    }

    private void setupWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setSupportZoom(true);
        webSettings.setDefaultTextEncodingName("utf-8");
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webSettings.setDatabaseEnabled(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        // Force dark mode following system setting
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(webView.getSettings(), WebSettingsCompat.FORCE_DARK_AUTO);
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            WebSettingsCompat.setForceDarkStrategy(webView.getSettings(), WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                // Save cookies for background notification worker
                String cookies = CookieManager.getInstance().getCookie(WEBSITE_URL);
                if (cookies != null && !cookies.isEmpty()) {
                    getSharedPreferences("bp_prefs", MODE_PRIVATE)
                        .edit()
                        .putString("session_cookies", cookies)
                        .putString("website_url", WEBSITE_URL)
                        .apply();
                }
                // Re-inject FCM token after every page load so website JS can POST it with user_id/session.
                if (fcmTokenForWebView != null && !fcmTokenForWebView.isEmpty()) {
                    view.evaluateJavascript("window.FCM_TOKEN = '" + fcmTokenForWebView + "'; window.dispatchEvent(new Event('fcm_token_ready'));", null);
                }
                // Custom JavaScript injection
                view.evaluateJavascript("(() => {     \"use strict\";      /*      * ==========================================      * FINANCATAT PRO — OFFLINE ENGINE      * ==========================================      * Penyimpanan:      * localStorage      *      * Tidak membutuhkan:      * - Server      * - Database online      * - API      * - Internet      */      const STORAGE_KEY = \"financatat_pro_transactions_v1\";      const CATEGORY = {         income: [             \"Gaji\",             \"Bonus\",             \"Bisnis\",             \"Investasi\",             \"Hadiah\",             \"Lainnya\"         ],          expense: [             \"Makanan\",             \"Transportasi\",             \"Belanja\",             \"Tagihan\",             \"Hiburan\",             \"Kesehatan\",             \"Pendidikan\",             \"Lainnya\"         ]     };      let transactions = loadTransactions();      /* ==========================================        STORAGE     ========================================== */      function loadTransactions() {         try {             const data = localStorage.getItem(STORAGE_KEY);              if (!data) return [];              const parsed = JSON.parse(data);              return Array.isArray(parsed) ? parsed : [];          } catch (error) {             console.error(                 \"FinanCatat Pro: gagal membaca data\",                 error             );              return [];         }     }      function saveTransactions() {         localStorage.setItem(             STORAGE_KEY,             JSON.stringify(transactions)         );     }      /* ==========================================        TRANSACTION ID     ========================================== */      function createId() {         return Date.now().toString(36) +             Math.random()                 .toString(36)                 .substring(2, 8);     }      /* ==========================================        ADD TRANSACTION     ========================================== */      function addTransaction({         type,         amount,         category,         note = \"\",         date     }) {          const transaction = {             id: createId(),              type:                 type === \"income\"                     ? \"income\"                     : \"expense\",              amount:                 Math.max(                     0,                     Number(amount) || 0                 ),              category:                 String(category || \"Lainnya\"),              note:                 String(note || \"\").trim(),              date:                 date || getToday(),              createdAt:                 new Date().toISOString()         };          if (transaction.amount <= 0) {             throw new Error(                 \"Jumlah transaksi harus lebih dari 0.\"             );         }          transactions.unshift(transaction);          saveTransactions();          dispatchUpdate();          return transaction;     }      /* ==========================================        UPDATE TRANSACTION     ========================================== */      function updateTransaction(id, changes) {          const index =             transactions.findIndex(                 item => item.id === id             );          if (index === -1) {             return false;         }          const current = transactions[index];          transactions[index] = {             ...current,             ...changes,             amount:                 Math.max(                     0,                     Number(changes.amount ?? current.amount)                 )         };          saveTransactions();          dispatchUpdate();          return true;     }      /* ==========================================        DELETE TRANSACTION     ========================================== */      function deleteTransaction(id) {          const before =             transactions.length;          transactions =             transactions.filter(                 item => item.id !== id             );          if (             transactions.length !== before         ) {             saveTransactions();              dispatchUpdate();              return true;         }          return false;     }      /* ==========================================        GET TRANSACTIONS     ========================================== */      function getTransactions() {          return [...transactions]             .sort((a, b) => {                  const dateCompare =                     new Date(b.date) -                     new Date(a.date);                  if (dateCompare !== 0) {                     return dateCompare;                 }                  return String(b.id)                     .localeCompare(String(a.id));             });     }      /* ==========================================        FINANCIAL SUMMARY     ========================================== */      function getSummary(list = transactions) {          let income = 0;         let expense = 0;          list.forEach(transaction => {              const amount =                 Number(transaction.amount) || 0;              if (                 transaction.type === \"income\"             ) {                 income += amount;             } else {                 expense += amount;             }         });          return {             income,             expense,             balance: income - expense,             total: list.length         };     }      /* ==========================================        DATE FILTER     ========================================== */      function filterByDate(         startDate = null,         endDate = null     ) {          return getTransactions()             .filter(transaction => {                  const date =                     transaction.date;                  if (                     startDate &&                     date < startDate                 ) {                     return false;                 }                  if (                     endDate &&                     date > endDate                 ) {                     return false;                 }                  return true;             });     }      /* ==========================================        CATEGORY FILTER     ========================================== */      function filterByCategory(         category     ) {          return getTransactions()             .filter(                 transaction =>                     transaction.category === category             );     }      /* ==========================================        SEARCH     ========================================== */      function searchTransactions(query) {          const keyword =             String(query || \"\")                 .trim()                 .toLowerCase();          if (!keyword) {             return getTransactions();         }          return getTransactions()             .filter(transaction => {                  return [                     transaction.category,                     transaction.note,                     transaction.date,                     transaction.type                 ]                     .join(\" \")                     .toLowerCase()                     .includes(keyword);             });     }      /* ==========================================        FORMAT RUPIAH     ========================================== */      function formatRupiah(value) {          return new Intl.NumberFormat(             \"id-ID\",             {                 style: \"currency\",                 currency: \"IDR\",                 maximumFractionDigits: 0             }         ).format(             Number(value) || 0         );     }      /* ==========================================        FORMAT DATE     ========================================== */      function formatDate(date) {          if (!date) return \"-\";          const parsed =             new Date(date + \"T00:00:00\");          return new Intl.DateTimeFormat(             \"id-ID\",             {                 day: \"numeric\",                 month: \"long\",                 year: \"numeric\"             }         ).format(parsed);     }      function getToday() {          const now = new Date();          const year =             now.getFullYear();          const month =             String(                 now.getMonth() + 1             ).padStart(2, \"0\");          const day =             String(                 now.getDate()             ).padStart(2, \"0\");          return `${year}-${month}-${day}`;     }      /* ==========================================        EXPORT DATA     ========================================== */      function exportData() {          const data = {             app: \"FinanCatat Pro\",             version: 1,             exportedAt:                 new Date().toISOString(),             transactions:                 getTransactions()         };          const blob =             new Blob(                 [                     JSON.stringify(                         data,                         null,                         2                     )                 ],                 {                     type:                         \"application/json\"                 }             );          const url =             URL.createObjectURL(blob);          const link =             document.createElement(\"a\");          link.href = url;          link.download =             `financatat-backup-${getToday()}.json`;          link.click();          URL.revokeObjectURL(url);     }      /* ==========================================        IMPORT DATA     ========================================== */      function importData(file) {          if (!file) return false;          const reader =             new FileReader();          reader.onload = event => {              try {                  const data =                     JSON.parse(                         event.target.result                     );                  if (                     !data ||                     !Array.isArray(                         data.transactions                     )                 ) {                     throw new Error(                         \"Format backup tidak valid.\"                     );                 }                  transactions =                     data.transactions;                  saveTransactions();                  dispatchUpdate();                  alert(                     \"Data berhasil dipulihkan.\"                 );              } catch (error) {                  alert(                     \"Backup tidak dapat dipulihkan.\"                 );                  console.error(error);             }         };          reader.readAsText(file);          return true;     }      /* ==========================================        CLEAR ALL     ========================================== */      function clearAllData() {          if (!transactions.length) {             return;         }          const confirmed =             confirm(                 \"Hapus semua transaksi? Data yang dihapus tidak dapat dikembalikan kecuali kamu memiliki backup.\"             );          if (!confirmed) {             return;         }          transactions = [];          saveTransactions();          dispatchUpdate();     }      /* ==========================================        ONLINE / OFFLINE STATUS     ========================================== */      function getConnectionStatus() {          return navigator.onLine             ? \"online\"             : \"offline\";     }      function showOfflineStatus() {          document.body             .classList.add(                 \"is-offline\"             );          window.dispatchEvent(             new CustomEvent(                 \"financatat:offline\"             )         );     }      function showOnlineStatus() {          document.body             .classList.remove(                 \"is-offline\"             );          window.dispatchEvent(             new CustomEvent(                 \"financatat:online\"             )         );     }      window.addEventListener(         \"offline\",         showOfflineStatus     );      window.addEventListener(         \"online\",         showOnlineStatus     );      /* ==========================================        UPDATE EVENT     ========================================== */      function dispatchUpdate() {          window.dispatchEvent(             new CustomEvent(                 \"financatat:update\",                 {                     detail: {                         transactions:                             getTransactions(),                          summary:                             getSummary()                     }                 }             )         );     }      /* ==========================================        PUBLIC API        Bisa dipanggil dari UI utama     ========================================== */      window.FinanCatat = {          addTransaction,          updateTransaction,          deleteTransaction,          getTransactions,          getSummary,          filterByDate,          filterByCategory,          searchTransactions,          formatRupiah,          formatDate,          getToday,          exportData,          importData,          clearAllData,          getConnectionStatus,          categories: CATEGORY      };      /* ==========================================        INITIALIZE     ========================================== */      document.addEventListener(         \"DOMContentLoaded\",         () => {              if (                 navigator.onLine             ) {                 showOnlineStatus();             } else {                 showOfflineStatus();             }              dispatchUpdate();         }     );  })();", null);
                // Custom CSS injection via Base64 to avoid quote escaping issues
                String cssStr = ":root {     --primary: #3157e8;     --primary-dark: #2444c7;     --primary-light: #eef2ff;      --success: #10b981;     --success-bg: #ecfdf5;      --danger: #ef4444;     --danger-bg: #fef2f2;      --text: #172033;     --text-secondary: #667085;     --text-muted: #98a2b3;      --background: #f6f8fc;     --surface: #ffffff;     --border: #e8ebf2;      --radius-sm: 10px;     --radius-md: 16px;     --radius-lg: 22px;     --radius-xl: 28px;      --shadow-sm: 0 4px 16px rgba(31, 41, 55, .05);     --shadow-md: 0 10px 30px rgba(31, 41, 55, .08); }  /* =========================    GLOBAL ========================= */  * {     box-sizing: border-box;     margin: 0;     padding: 0;     -webkit-tap-highlight-color: transparent; }  html, body {     width: 100%;     min-height: 100%; }  body {     background: var(--background);     color: var(--text);     font-family:         Inter,         -apple-system,         BlinkMacSystemFont,         \"Segoe UI\",         Roboto,         Arial,         sans-serif;     -webkit-font-smoothing: antialiased; }  button, input, select {     font: inherit; }  button {     border: 0;     cursor: pointer; }  button:active {     transform: scale(.97); }  ::-webkit-scrollbar {     width: 0;     height: 0; }  ::selection {     background: rgba(49, 87, 232, .15); }  /* =========================    OFFLINE PAGE ========================= */  .offline-page {     min-height: 100vh;     min-height: 100dvh;      display: flex;     align-items: center;     justify-content: center;      padding: 24px;      background:         radial-gradient(             circle at top right,             rgba(49, 87, 232, .12),             transparent 35%         ),         linear-gradient(             180deg,             #f8faff 0%,             #f3f6fc 100%         ); }  .offline-card {     width: 100%;     max-width: 430px;      padding: 36px 26px 28px;      text-align: center;      background: rgba(255, 255, 255, .96);     border: 1px solid rgba(255, 255, 255, .8);      border-radius: var(--radius-xl);      box-shadow:         0 20px 60px rgba(31, 41, 55, .10);      animation: offlineEnter .45s ease-out; }  @keyframes offlineEnter {     from {         opacity: 0;         transform: translateY(18px);     }      to {         opacity: 1;         transform: translateY(0);     } }  .offline-icon {     width: 76px;     height: 76px;      margin: 0 auto 18px;      display: flex;     align-items: center;     justify-content: center;      border-radius: 24px;      background:         linear-gradient(             145deg,             var(--primary),             var(--primary-dark)         );      color: white;      box-shadow:         0 12px 28px rgba(49, 87, 232, .25); }  .offline-icon span {     font-size: 34px;     font-weight: 800; }  .offline-badge {     display: inline-flex;     align-items: center;     gap: 7px;      padding: 7px 11px;      margin-bottom: 14px;      border-radius: 999px;      background: var(--primary-light);     color: var(--primary);      font-size: 10px;     font-weight: 800;     letter-spacing: .08em; }  .offline-badge span {     width: 6px;     height: 6px;      border-radius: 50%;      background: var(--primary); }  .offline-card h1 {     font-size: 27px;     line-height: 1.2;     font-weight: 800;     letter-spacing: -.04em; }  .offline-title {     margin-top: 8px;      color: var(--primary);      font-size: 15px;     font-weight: 700; }  .offline-description {     margin: 12px auto 24px;      max-width: 340px;      color: var(--text-secondary);      font-size: 13px;     line-height: 1.65; }  .offline-features {     display: flex;     flex-direction: column;     gap: 10px;      text-align: left; }  .offline-feature {     display: flex;     align-items: center;     gap: 12px;      padding: 13px;      border: 1px solid var(--border);     border-radius: var(--radius-md);      background: #fff; }  .feature-icon {     width: 34px;     height: 34px;      flex: 0 0 34px;      display: flex;     align-items: center;     justify-content: center;      border-radius: 11px;      background: var(--success-bg);     color: var(--success);      font-size: 15px;     font-weight: 800; }  .offline-feature strong {     display: block;      color: var(--text);      font-size: 12px;     font-weight: 750; }  .offline-feature small {     display: block;      margin-top: 3px;      color: var(--text-muted);      font-size: 10px; }  .offline-button {     width: 100%;      margin-top: 22px;      padding: 14px 18px;      border-radius: 15px;      background:         linear-gradient(             135deg,             var(--primary),             var(--primary-dark)         );      color: white;      font-size: 13px;     font-weight: 750;      box-shadow:         0 9px 22px rgba(49, 87, 232, .20);      transition:         transform .2s ease,         box-shadow .2s ease; }  .offline-button:hover {     box-shadow:         0 12px 28px rgba(49, 87, 232, .28); }  .offline-footer {     margin-top: 20px;      color: var(--text-muted);      font-size: 9px;     font-weight: 600; }  /* =========================    RESPONSIVE ========================= */  @media (max-width: 360px) {      .offline-page {         padding: 16px;     }      .offline-card {         padding: 28px 20px 22px;     }      .offline-icon {         width: 68px;         height: 68px;     }      .offline-card h1 {         font-size: 24px;     } }";
                String b64Css = android.util.Base64.encodeToString(cssStr.getBytes(), android.util.Base64.NO_WRAP);
                view.evaluateJavascript("(function(){var s=document.createElement('style');s.textContent=atob('" + b64Css + "');document.head.appendChild(s);})()", null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleWebViewUrl(view, url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && request != null && request.getUrl() != null) {
                    return handleWebViewUrl(view, request.getUrl().toString());
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar != null) progressBar.setProgress(newProgress);
            }
            
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;

                // Camera intent
                Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.TITLE, "camera_photo");
                cameraImageUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);

                // File chooser intent
                Intent fileIntent = new Intent(Intent.ACTION_GET_CONTENT);
                fileIntent.addCategory(Intent.CATEGORY_OPENABLE);
                fileIntent.setType("*/*");

                // Combine into chooser
                Intent chooserIntent = Intent.createChooser(fileIntent, "Select file");
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});

                fileUploadLauncher.launch(chooserIntent);
                return true;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                String cookies = CookieManager.getInstance().getCookie(url);
                request.addRequestHeader("cookie", cookies);
                request.addRequestHeader("User-Agent", userAgent);
                request.setDescription("Downloading file...");
                request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType));
                request.allowScanningByMediaScanner();
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimeType));
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                dm.enqueue(request);
                Toast.makeText(getApplicationContext(), "Downloading File", Toast.LENGTH_LONG).show();
            }
        });
    }


    private boolean handleWebViewUrl(WebView view, String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String lower = url.toLowerCase();
        if (lower.startsWith("tel:") || lower.startsWith("mailto:") || lower.startsWith("sms:") || lower.startsWith("smsto:") || lower.startsWith("whatsapp:") || lower.startsWith("market:") || lower.startsWith("intent:")) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                startActivity(intent);
                return true;
            } catch (Exception ignored) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                    return true;
                } catch (Exception ignoredAgain) {
                    return true;
                }
            }
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return true;
        }
        try {
            java.net.URL baseUrl = new java.net.URL(WEBSITE_URL);
            java.net.URL targetUrl = new java.net.URL(url);
            if (targetUrl.getHost() != null && !targetUrl.getHost().equalsIgnoreCase(baseUrl.getHost())) {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                startActivity(browserIntent);
                return true;
            }
        } catch (Exception e) { /* ignore, load in webview */ }
        view.loadUrl(url);
        return true;
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getData() != null) {
            String deepUrl = intent.getData().toString();
            // Custom URL scheme: convert myapp://path to website URL
            if (deepUrl.startsWith("catatankeuanganapp://")) {
                String path = deepUrl.replace("catatankeuanganapp://", "");
                String baseUrl = WEBSITE_URL.endsWith("/") ? WEBSITE_URL : WEBSITE_URL + "/";
                deepUrl = baseUrl + path;
            }
            if (deepUrl.startsWith("http")) {
                webView.loadUrl(deepUrl);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void setupFAB() {
        android.widget.FrameLayout rootLayout = (android.widget.FrameLayout) findViewById(android.R.id.content);
        
        android.widget.FrameLayout fabContainer = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams containerParams = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        containerParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        containerParams.setMargins(0, 0, dpToPx(20), dpToPx(20));
        
        android.widget.Button fabBtn = new android.widget.Button(this);
        fabBtn.setText("💬");
        fabBtn.setTextSize(24);
        int size = dpToPx(56);
        android.widget.FrameLayout.LayoutParams btnParams = new android.widget.FrameLayout.LayoutParams(size, size);
        fabBtn.setLayoutParams(btnParams);
        
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#25D366"));
        fabBtn.setBackground(bg);
        fabBtn.setElevation(dpToPx(6));
        fabBtn.setPadding(0, 0, 0, 0);
        
        fabBtn.setOnClickListener(v -> {
            try {
                String waUrl = "https://wa.me/85813293658";
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(waUrl)));
            } catch (Exception e) { }
        });
        
        fabContainer.addView(fabBtn);
        rootLayout.addView(fabContainer, containerParams);
    }
    
    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private final ActivityResultLauncher<Intent> fileUploadLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            if (fileUploadCallback == null) return;
            if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                fileUploadCallback.onReceiveValue(new Uri[]{result.getData().getData()});
            } else if (result.getResultCode() == RESULT_OK && cameraImageUri != null) {
                fileUploadCallback.onReceiveValue(new Uri[]{cameraImageUri});
            } else {
                fileUploadCallback.onReceiveValue(null);
            }
            fileUploadCallback = null;
        });

    private void authenticateUser() {
        BiometricManager biometricManager = BiometricManager.from(this);
        int canAuth = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) { return; }
        
        webView.setVisibility(View.INVISIBLE);
        
        java.util.concurrent.Executor executor = getMainExecutor();
        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                webView.setVisibility(View.VISIBLE);
            }
            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    finish();
                }
            }
        });
        
        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock App")
            .setSubtitle("Verify your identity to continue")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build();
        
        biometricPrompt.authenticate(promptInfo);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Share").setIcon(android.R.drawable.ic_menu_share)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, 999, 0, "Privacy Policy");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, webView.getUrl());
            startActivity(Intent.createChooser(shareIntent, "Share via"));
            return true;
        }
        if (item.getItemId() == 999) {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://bitter-hall-4409.soniqpoint.workers.dev/")));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
        }
    }
}