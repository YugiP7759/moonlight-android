package com.limelight.yugi;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Submits the client-generated PIN to Sunshine's admin API so pairing needs no typing.
 *
 * Current Sunshine (2026.x): GET /api/pin lists pending pairings {"pairings":[{"id","name","address"}]},
 * then POST /api/pin {"pairing_id","pin","name"} with Content-Type application/json and Basic auth.
 * No Origin/Referer header is sent, so Sunshine's CSRF check does not apply.
 * Older Sunshine: POST /api/pin {"pin","name"}.
 */
public final class SunshinePairing {
    private static final MediaType JSON = MediaType.get("application/json");

    private SunshinePairing() {}

    public static boolean hasCredentials() {
        return !BuildConfig.SUNSHINE_USER.isEmpty() && !BuildConfig.SUNSHINE_PASS.isEmpty();
    }

    private static OkHttpClient insecureClient() throws Exception {
        // Sunshine's web UI uses a self-signed cert; this app only talks to it inside the tailnet.
        X509TrustManager tm = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
        HostnameVerifier hv = (h, s) -> true;
        return new OkHttpClient.Builder()
                .sslSocketFactory(ctx.getSocketFactory(), tm)
                .hostnameVerifier(hv)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    private static String baseUrl() {
        return "https://" + YugiConfig.host + ":" + YugiConfig.API_PORT;
    }

    /**
     * Polls for this device's pending pairing and submits the PIN. Runs concurrently with
     * PairingManager.pair(), which blocks until the server receives the PIN.
     *
     * @param deviceName the "devicename" the pairing request sent (DeviceUtils.getModel()).
     * @param stop       set to true to abort polling (pairing finished or failed).
     * @return true if Sunshine accepted the PIN.
     */
    public static boolean submitPin(String pin, String deviceName, long timeoutMs, java.util.concurrent.atomic.AtomicBoolean stop) {
        if (!hasCredentials()) {
            LimeLog.warning("YugiPC: no Sunshine credentials built in");
            return false;
        }
        String auth = Credentials.basic(BuildConfig.SUNSHINE_USER, BuildConfig.SUNSHINE_PASS);
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            OkHttpClient client = insecureClient();
            boolean legacy = false;
            while (System.currentTimeMillis() < deadline && !stop.get()) {
                String pairingId = null;
                if (!legacy) {
                    Request get = new Request.Builder().url(baseUrl() + "/api/pin")
                            .header("Authorization", auth).get().build();
                    try (Response r = client.newCall(get).execute()) {
                        if (r.code() == 404 || r.code() == 405) {
                            legacy = true;
                        } else if (r.isSuccessful() && r.body() != null) {
                            JSONArray arr = new JSONObject(r.body().string()).optJSONArray("pairings");
                            if (arr != null && arr.length() > 0) {
                                // Prefer the entry with our device name; otherwise take the newest.
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject p = arr.getJSONObject(i);
                                    if (deviceName != null && deviceName.equals(p.optString("name"))) {
                                        pairingId = p.optString("id");
                                    }
                                }
                                if (pairingId == null) {
                                    pairingId = arr.getJSONObject(arr.length() - 1).optString("id");
                                }
                            }
                        } else {
                            LimeLog.warning("YugiPC: GET /api/pin -> HTTP " + r.code());
                        }
                    }
                }

                if (legacy || pairingId != null) {
                    JSONObject body = new JSONObject();
                    if (pairingId != null) body.put("pairing_id", pairingId);
                    body.put("pin", pin);
                    body.put("name", YugiConfig.CLIENT_NAME);
                    Request post = new Request.Builder().url(baseUrl() + "/api/pin")
                            .header("Authorization", auth)
                            .post(RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8), JSON))
                            .build();
                    try (Response r = client.newCall(post).execute()) {
                        String resp = r.body() != null ? r.body().string() : "";
                        if (r.isSuccessful() && new JSONObject(resp).optBoolean("status", false)) {
                            return true;
                        }
                        LimeLog.warning("YugiPC: POST /api/pin -> HTTP " + r.code() + " " + resp);
                    }
                }
                Thread.sleep(500);
            }
        } catch (Exception e) {
            LimeLog.warning("YugiPC: PIN submit failed: " + e);
        }
        return false;
    }
}
