package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2019 by Marcel Bokhorst (M66B)
*/

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;
import com.google.android.material.snackbar.Snackbar;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

abstract class ActivityBilling extends ActivityBase implements PurchasesUpdatedListener {
    private BillingClient billingClient = null;
    private Map<String, SkuDetails> skuDetails = new HashMap<>();
    private List<IBillingListener> listeners = new ArrayList<>();

    static final String ACTION_PURCHASE = BuildConfig.APPLICATION_ID + ".ACTION_PURCHASE";
    static final String ACTION_ACTIVATE_PRO = BuildConfig.APPLICATION_ID + ".ACTIVATE_PRO";

    static final String SKU_PRO = BuildConfig.APPLICATION_ID + ".pro";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Helper.isPlayStoreInstall(this)) {
            Log.i("IAB start");
            billingClient = BillingClient.newBuilder(this).setListener(this).build();
            billingClient.startConnection(billingClientStateListener);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        IntentFilter iff = new IntentFilter();
        iff.addAction(ACTION_PURCHASE);
        iff.addAction(ACTION_ACTIVATE_PRO);
        lbm.registerReceiver(receiver, iff);

        if (billingClient != null && billingClient.isReady())
            queryPurchases();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.unregisterReceiver(receiver);
    }

    @Override
    protected void onDestroy() {
        if (billingClient != null)
            billingClient.endConnection();
        super.onDestroy();
    }

    protected Intent getIntentPro() {
        if (Helper.isPlayStoreInstall(this))
            return null;

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(BuildConfig.PRO_FEATURES_URI + "?challenge=" + getChallenge()));
            return intent;
        } catch (NoSuchAlgorithmException ex) {
            Log.e(ex);
            return null;
        }
    }

    private String getChallenge() throws NoSuchAlgorithmException {
        String android_id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        return Helper.sha256(android_id);
    }

    private String getResponse() throws NoSuchAlgorithmException {
        return Helper.sha256(BuildConfig.APPLICATION_ID + getChallenge());
    }

    BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_PURCHASE.equals(intent.getAction()))
                onPurchase(intent);
            else if (ACTION_ACTIVATE_PRO.equals(intent.getAction()))
                onActivatePro(intent);
        }
    };

    private void onPurchase(Intent intent) {
        if (Helper.isPlayStoreInstall(this)) {
            BillingFlowParams.Builder flowParams = BillingFlowParams.newBuilder();
            if (skuDetails.containsKey(SKU_PRO)) {
                Log.i("IAB purchase SKU=" + skuDetails.get(SKU_PRO));
                flowParams.setSkuDetails(skuDetails.get(SKU_PRO));
            } else {
                Log.i("IAB purchase SKU=" + SKU_PRO);
                flowParams.setSku(SKU_PRO).setType(BillingClient.SkuType.INAPP);
            }

            int responseCode = billingClient.launchBillingFlow(this, flowParams.build());
            String text = getBillingResponseText(responseCode);
            Log.i("IAB launch billing flow response=" + text);
            if (responseCode != BillingClient.BillingResponse.OK)
                Snackbar.make(getVisibleView(), text, Snackbar.LENGTH_LONG).show();
        } else
            Helper.view(this, this, getIntentPro());
    }

    private void onActivatePro(Intent intent) {
        try {
            Uri data = intent.getParcelableExtra("uri");
            String challenge = getChallenge();
            String response = data.getQueryParameter("response");
            Log.i("Challenge=" + challenge);
            Log.i("Response=" + response);
            String expected = getResponse();
            if (expected.equals(response)) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                prefs.edit()
                        .putBoolean("pro", true)
                        .putBoolean("play_store", false)
                        .apply();
                Log.i("Response valid");
                Snackbar snackbar = Snackbar.make(getVisibleView(), R.string.title_pro_valid, Snackbar.LENGTH_LONG);
                snackbar.setAction(R.string.title_check, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
                        fragmentTransaction.replace(R.id.content_frame, new FragmentPro()).addToBackStack("pro");
                        fragmentTransaction.commit();
                    }
                });
                snackbar.show();
            } else {
                Log.i("Response invalid");
                Snackbar.make(getVisibleView(), R.string.title_pro_invalid, Snackbar.LENGTH_LONG).show();
            }
        } catch (NoSuchAlgorithmException ex) {
            Log.e(ex);
            Helper.unexpectedError(this, this, ex);
        }
    }

    private BillingClientStateListener billingClientStateListener = new BillingClientStateListener() {
        private int backoff = 4; // seconds

        @Override
        public void onBillingSetupFinished(@BillingClient.BillingResponse int responseCode) {
            String text = getBillingResponseText(responseCode);
            Log.i("IAB connected response=" + text);

            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED))
                return;

            if (responseCode == BillingClient.BillingResponse.OK) {
                backoff = 4;
                queryPurchases();
                querySkuDetails();
            } else
                Snackbar.make(getVisibleView(), text, Snackbar.LENGTH_LONG).show();
        }

        @Override
        public void onBillingServiceDisconnected() {
            backoff *= 2;
            Log.i("IAB disconnected retry in " + backoff + " s");
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!billingClient.isReady())
                        billingClient.startConnection(billingClientStateListener);
                }
            }, backoff * 1000L);
        }
    };

    @Override
    public void onPurchasesUpdated(int responseCode, @Nullable List<Purchase> purchases) {
        String text = getBillingResponseText(responseCode);
        Log.i("IAB purchases updated response=" + text);

        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED))
            return;

        if (responseCode == BillingClient.BillingResponse.OK)
            checkPurchases(purchases);
        else
            Snackbar.make(getVisibleView(), text, Snackbar.LENGTH_LONG).show();
    }

    private void queryPurchases() {
        Purchase.PurchasesResult result = billingClient.queryPurchases(BillingClient.SkuType.INAPP);
        String text = getBillingResponseText(result.getResponseCode());
        Log.i("IAB query purchases response=" + text);

        if (result.getResponseCode() == BillingClient.BillingResponse.OK)
            checkPurchases(result.getPurchasesList());
        else
            Snackbar.make(getVisibleView(), text, Snackbar.LENGTH_LONG).show();
    }

    private void querySkuDetails() {
        Log.i("IAB query SKUs");
        SkuDetailsParams.Builder builder = SkuDetailsParams.newBuilder();
        builder.setSkusList(Arrays.asList(SKU_PRO));
        builder.setType(BillingClient.SkuType.INAPP);
        billingClient.querySkuDetailsAsync(builder.build(),
                new SkuDetailsResponseListener() {
                    @Override
                    public void onSkuDetailsResponse(int responseCode, List<SkuDetails> skuDetailsList) {
                        String text = getBillingResponseText(responseCode);
                        Log.i("IAB query SKUs response=" + text);
                        if (responseCode == BillingClient.BillingResponse.OK) {
                            for (SkuDetails skuDetail : skuDetailsList) {
                                Log.i("IAB SKU detail=" + skuDetail);
                                skuDetails.put(skuDetail.getSku(), skuDetail);
                                for (IBillingListener listener : listeners)
                                    listener.onSkuDetails(skuDetail.getSku(), skuDetail.getPrice());
                            }
                        }
                    }
                });
    }

    interface IBillingListener {
        void onSkuDetails(String sku, String price);
    }

    void addBillingListener(final IBillingListener listener, LifecycleOwner owner) {
        Log.i("Adding billing listener=" + listener);
        listeners.add(listener);

        for (SkuDetails skuDetail : skuDetails.values())
            listener.onSkuDetails(skuDetail.getSku(), skuDetail.getPrice());

        owner.getLifecycle().addObserver(new LifecycleObserver() {
            @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            public void onDestroyed() {
                Log.i("Removing billing listener=" + listener);
                listeners.remove(listener);
            }
        });
    }

    private void checkPurchases(List<Purchase> purchases) {
        if (purchases != null) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            SharedPreferences.Editor editor = prefs.edit();
            if (prefs.getBoolean("play_store", true))
                editor.remove("pro");

            for (Purchase purchase : purchases)
                try {
                    Log.i("IAB SKU=" + purchase.getSku());

                    byte[] decodedKey = Base64.decode(getString(R.string.public_key), Base64.DEFAULT);
                    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                    PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(decodedKey));
                    Signature sig = Signature.getInstance("SHA1withRSA");
                    sig.initVerify(publicKey);
                    sig.update(purchase.getOriginalJson().getBytes());
                    if (sig.verify(Base64.decode(purchase.getSignature(), Base64.DEFAULT))) {
                        if (SKU_PRO.equals(purchase.getSku())) {
                            editor.putBoolean("pro", true);
                            Log.i("IAB pro features activated");
                        }
                    } else {
                        Log.w("Invalid signature");
                        Snackbar.make(getVisibleView(), R.string.title_pro_invalid, Snackbar.LENGTH_LONG).show();
                    }
                } catch (Throwable ex) {
                    Log.e(ex);
                    Snackbar.make(getVisibleView(), ex.getMessage(), Snackbar.LENGTH_LONG).show();
                }

            editor.apply();
        }
    }

    static String getBillingResponseText(@BillingClient.BillingResponse int responseCode) {
        switch (responseCode) {
            case BillingClient.BillingResponse.BILLING_UNAVAILABLE:
                // Billing API version is not supported for the type requested
                return "BILLING_UNAVAILABLE";

            case BillingClient.BillingResponse.DEVELOPER_ERROR:
                // Invalid arguments provided to the API.
                return "DEVELOPER_ERROR";

            case BillingClient.BillingResponse.ERROR:
                // Fatal error during the API action
                return "ERROR";

            case BillingClient.BillingResponse.FEATURE_NOT_SUPPORTED:
                // Requested feature is not supported by Play Store on the current device.
                return "FEATURE_NOT_SUPPORTED";

            case BillingClient.BillingResponse.ITEM_ALREADY_OWNED:
                // Failure to purchase since item is already owned
                return "ITEM_ALREADY_OWNED";

            case BillingClient.BillingResponse.ITEM_NOT_OWNED:
                // Failure to consume since item is not owned
                return "ITEM_NOT_OWNED";

            case BillingClient.BillingResponse.ITEM_UNAVAILABLE:
                // Requested product is not available for purchase
                return "ITEM_UNAVAILABLE";

            case BillingClient.BillingResponse.OK:
                // Success
                return "OK";

            case BillingClient.BillingResponse.SERVICE_DISCONNECTED:
                // Play Store service is not connected now - potentially transient state.
                return "SERVICE_DISCONNECTED";

            case BillingClient.BillingResponse.SERVICE_UNAVAILABLE:
                // Network connection is down
                return "SERVICE_UNAVAILABLE";

            case BillingClient.BillingResponse.USER_CANCELED:
                // User pressed back or canceled a dialog
                return "USER_CANCELED";

            default:
                return Integer.toString(responseCode);
        }
    }
}
