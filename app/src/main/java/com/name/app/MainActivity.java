package com.example.ussdwebview;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private static final int REQUEST_CALL_PERMISSION = 1;
    private String pendingUSSDCode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        setupWebView();

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new JSBridge(), "AndroidUSSD");
    }

    private class JSBridge {

        @JavascriptInterface
        public void openExternal(String url) {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(android.net.Uri.parse(url));
                startActivity(intent);
            });
        }

        @JavascriptInterface
        public void runUssd(String code, int simSlot) {
            runOnUiThread(() -> executeUSSD(code, simSlot));
        }

        @JavascriptInterface
        public void reloadApp() {
            runOnUiThread(MainActivity.this::restartApp);
        }

        @JavascriptInterface
        public void setSystemBarsColor(String colorString) {
            runOnUiThread(() -> changeSystemBarsColor(colorString));
        }
    }

    private void restartApp() {
        Intent intent = getPackageManager()
                .getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
        finish();
    }

    private void changeSystemBarsColor(String colorString) {
        try {
            int color = Color.parseColor(colorString);
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.setStatusBarColor(color);
                window.setNavigationBarColor(color);
            }

            boolean isLightColor = isColorLight(color);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (window.getInsetsController() != null) {
                    if (isLightColor) {
                        window.getInsetsController().setSystemBarsAppearance(
                                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                        );
                    } else {
                        window.getInsetsController().setSystemBarsAppearance(
                                0,
                                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                        );
                    }
                }
            } else {
                View decorView = window.getDecorView();
                int flags = decorView.getSystemUiVisibility();

                if (isLightColor) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                } else {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                }

                decorView.setSystemUiVisibility(flags);
            }

        } catch (Exception e) {
            Log.e("SYSTEM_BAR", "Invalid color: " + colorString);
        }
    }

    private boolean isColorLight(int color) {
        double darkness = 1 - (0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255;
        return darkness < 0.5;
    }

    private void executeUSSD(String code, int simSlot) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            sendResultToWeb("USSD supported on Android 8.0+ only");
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {

            pendingUSSDCode = code + "|" + simSlot;

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.READ_PHONE_STATE
                    },
                    REQUEST_CALL_PERMISSION
            );
            return;
        }

        SubscriptionManager subscriptionManager =
                (SubscriptionManager) getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);

        if (subscriptionManager == null) {
            sendResultToWeb("Subscription service unavailable");
            return;
        }

        List<SubscriptionInfo> subscriptionInfoList =
                subscriptionManager.getActiveSubscriptionInfoList();

        if (subscriptionInfoList == null || subscriptionInfoList.size() <= simSlot) {
            sendResultToWeb("Selected SIM not available");
            return;
        }

        int subscriptionId =
                subscriptionInfoList.get(simSlot).getSubscriptionId();

        TelephonyManager telephonyManager =
                ((TelephonyManager) getSystemService(TELEPHONY_SERVICE))
                        .createForSubscriptionId(subscriptionId);

        try {
            telephonyManager.sendUssdRequest(code,
                    new TelephonyManager.UssdResponseCallback() {

                        @Override
                        public void onReceiveUssdResponse(
                                TelephonyManager telephonyManager,
                                String request,
                                CharSequence response) {
                            sendResultToWeb(response.toString());
                        }

                        @Override
                        public void onReceiveUssdResponseFailed(
                                TelephonyManager telephonyManager,
                                String request,
                                int failureCode) {
                            sendResultToWeb("USSD failed: " + failureCode);
                        }

                    }, new Handler(Looper.getMainLooper()));

        } catch (SecurityException e) {
            sendResultToWeb("Permission denied for USSD");
        } catch (Exception e) {
            sendResultToWeb("USSD error: " + e.getMessage());
        }
    }

    private void sendResultToWeb(String message) {
        String safeMessage = message
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n");

        webView.post(() ->
                webView.evaluateJavascript(
                        "showResult('" + safeMessage + "')", null));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CALL_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

            if (pendingUSSDCode != null) {
                String[] parts = pendingUSSDCode.split("\\|");
                executeUSSD(parts[0], Integer.parseInt(parts[1]));
                pendingUSSDCode = null;
            }

        } else {
            sendResultToWeb("Permission denied");
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}