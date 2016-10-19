package piuk.blockchain.android.data.datamanagers;

import android.support.annotation.VisibleForTesting;
import android.util.Log;

import info.blockchain.api.WalletPayload;
import info.blockchain.wallet.exceptions.DecryptionException;
import info.blockchain.wallet.exceptions.HDWalletException;
import info.blockchain.wallet.exceptions.InvalidCredentialsException;
import info.blockchain.wallet.exceptions.PayloadException;
import info.blockchain.wallet.payload.BlockchainWallet;
import info.blockchain.wallet.payload.Payload;
import info.blockchain.wallet.payload.PayloadManager;
import info.blockchain.wallet.util.CharSequenceX;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import piuk.blockchain.android.R;
import piuk.blockchain.android.data.access.AccessState;
import piuk.blockchain.android.data.rxjava.RxUtil;
import piuk.blockchain.android.data.services.WalletPayloadService;
import piuk.blockchain.android.util.AESUtilWrapper;
import piuk.blockchain.android.util.AppUtil;
import piuk.blockchain.android.util.PrefsUtil;
import piuk.blockchain.android.util.StringUtils;
import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.exceptions.Exceptions;
import rx.schedulers.Schedulers;

@SuppressWarnings("WeakerAccess")
public class AuthDataManager {

    private WalletPayloadService walletPayloadService;
    private PayloadManager payloadManager;
    private PrefsUtil prefsUtil;
    private AppUtil appUtil;
    private AESUtilWrapper aesUtilWrapper;
    private AccessState accessState;
    private StringUtils stringUtils;
    @VisibleForTesting protected int timer;

    public AuthDataManager(PayloadManager payloadManager,
                           PrefsUtil prefsUtil,
                           WalletPayloadService walletPayloadService,
                           AppUtil appUtil,
                           AESUtilWrapper aesUtilWrapper,
                           AccessState accessState,
                           StringUtils stringUtils) {

        this.payloadManager = payloadManager;
        this.prefsUtil = prefsUtil;
        this.walletPayloadService = walletPayloadService;
        this.appUtil = appUtil;
        this.aesUtilWrapper = aesUtilWrapper;
        this.accessState = accessState;
        this.stringUtils = stringUtils;
    }

    public Observable<String> getEncryptedPayload(String guid, String sessionId) {
        return walletPayloadService.getEncryptedPayload(guid, sessionId);
    }

    public Observable<String> getSessionId(String guid) {
        return walletPayloadService.getSessionId(guid);
    }

    public Observable<Void> updatePayload(String sharedKey, String guid, CharSequenceX password) {
        return getUpdatePayloadObservable(sharedKey, guid, password)
                .compose(RxUtil.applySchedulers());
    }

    public Observable<CharSequenceX> validatePin(String pin) {
        return accessState.validatePin(pin);
    }

    public Observable<Boolean> createPin(CharSequenceX password, String pin) {
        return accessState.createPin(password, pin)
                .compose(RxUtil.applySchedulers());
    }

    public Observable<Payload> createHdWallet(String password, String walletName) {
        return Observable.fromCallable(() -> payloadManager.createHDWallet(password, walletName))
                .compose(RxUtil.applySchedulers())
                .doOnNext(payload -> {
                    if (payload != null) {
                        // Successfully created and saved
                        appUtil.setNewlyCreated(true);
                        prefsUtil.setValue(PrefsUtil.KEY_GUID, payload.getGuid());
                        appUtil.setSharedKey(payload.getSharedKey());
                    }
                });
    }

    public Observable<Payload> restoreHdWallet(String email, String password, String passphrase) {
        payloadManager.setEmail(email);
        return Observable.fromCallable(() -> payloadManager.restoreHDWallet(
                password, passphrase, stringUtils.getString(R.string.default_wallet_name)))
                .doOnNext(payload -> {
                    if (payload == null) {
                        throw Exceptions.propagate(new Throwable("Save failed"));
                    } else {
                        prefsUtil.setValue(PrefsUtil.KEY_GUID, payload.getGuid());
                        appUtil.setSharedKey(payload.getSharedKey());
                    }
                })
                .compose(RxUtil.applySchedulers());
    }

    public Observable<String> startPollingAuthStatus(String guid) {
        // Get session id
        return getSessionId(guid)
                // return Observable that emits ticks every two seconds, pass in Session ID
                .flatMap(sessionId -> Observable.interval(2, TimeUnit.SECONDS)
                        // For each emission from the timer, try to get the payload
                        .map(tick -> getEncryptedPayload(guid, sessionId).toBlocking().first())
                        // If auth not required, emit payload
                        .filter(s -> !s.equals(WalletPayload.KEY_AUTH_REQUIRED))
                        // If error called, emit Auth Required
                        .onErrorReturn(throwable -> Observable.just(WalletPayload.KEY_AUTH_REQUIRED).toBlocking().first())
                        // Make sure threading is correct
                        .compose(RxUtil.applySchedulers())
                        // Only emit the first object
                        .first());
    }

    private Observable<Void> getUpdatePayloadObservable(String sharedKey, String guid, CharSequenceX password) {
        return Observable.defer(() -> Observable.create(subscriber -> {
            try {
                payloadManager.initiatePayload(
                        sharedKey,
                        guid,
                        password,
                        () -> {
                            payloadManager.setTempPassword(password);
                            if (!subscriber.isUnsubscribed()) {
                                subscriber.onNext(null);
                                subscriber.onCompleted();
                            }
                        });
            } catch (Exception e) {
                if (!subscriber.isUnsubscribed()) {
                    subscriber.onError(e);
                }
            }
        }));
    }

    public Observable<Integer> createCheckEmailTimer() {
        timer = 2 * 60;

        return Observable.interval(0, 1, TimeUnit.SECONDS, Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .map(aLong -> timer--)
                .takeUntil(aLong -> timer < 0);
    }

    /*
     * TODO - move to jar and make more testable
     */
    public void attemptDecryptPayload(CharSequenceX password, String guid, String payload, DecryptPayloadListener listener) {
        try {
            JSONObject jsonObject = new JSONObject(payload);

            if (jsonObject.has("payload")) {
                String encryptedPayload = jsonObject.getString("payload");

                int iterations = BlockchainWallet.DEFAULT_PBKDF2_ITERATIONS_V2;
                if (jsonObject.has("pbkdf2_iterations")) {
                    iterations = jsonObject.getInt("pbkdf2_iterations");
                }

                String decryptedPayload = null;
                try {
                    decryptedPayload = aesUtilWrapper.decrypt(encryptedPayload, password, iterations);
                } catch (Exception e) {
                    listener.onFatalError();
                }

                if (decryptedPayload != null) {
                    attemptUpdatePayload(password, guid, decryptedPayload, listener);
                } else {
                    // Decryption failed
                    listener.onAuthFail();
                }
            }
        } catch (JSONException e) {
            // Most likely a V1 Wallet, attempt parse
            try {
                BlockchainWallet v1Wallet = new BlockchainWallet(payload, password);
                attemptUpdatePayload(password, guid, v1Wallet.getPayload().getDecryptedPayload(), listener);
            } catch (DecryptionException | NullPointerException authException) {
                Log.e(getClass().getSimpleName(), "attemptDecryptPayload: ", authException);
                listener.onAuthFail();
            } catch (Exception fatalException) {
                Log.e(getClass().getSimpleName(), "attemptDecryptPayload: ", fatalException);
                listener.onFatalError();
            }
        }
    }

    private void attemptUpdatePayload(CharSequenceX password, String guid, String decryptedPayload, DecryptPayloadListener listener) throws JSONException {
        JSONObject decryptedJsonObject = new JSONObject(decryptedPayload);

        if (decryptedJsonObject.has("sharedKey")) {
            prefsUtil.setValue(PrefsUtil.KEY_GUID, guid);
            payloadManager.setTempPassword(password);

            String sharedKey = decryptedJsonObject.getString("sharedKey");
            appUtil.setSharedKey(sharedKey);

            updatePayload(sharedKey, guid, password)
                    .compose(RxUtil.applySchedulers())
                    .subscribe(new Subscriber<Void>() {
                        @Override
                        public void onCompleted() {
                            prefsUtil.setValue(PrefsUtil.KEY_EMAIL_VERIFIED, true);
                            listener.onSuccess();
                        }

                        @Override
                        public void onError(Throwable throwable) {

                            if (throwable instanceof InvalidCredentialsException) {
                                listener.onAuthFail();

                            } else if (throwable instanceof PayloadException) {
                                // This shouldn't happen - Payload retrieved from server couldn't be parsed
                                listener.onFatalError();

                            } else if (throwable instanceof HDWalletException) {
                                // This shouldn't happen. HD fatal error - not safe to continue - don't clear credentials
                                listener.onFatalError();

                            } else {
                                listener.onPairFail();
                            }
                        }

                        @Override
                        public void onNext(Void aVoid) {
                            // No-op
                        }
                    });
        } else {
            listener.onFatalError();
        }
    }

    public interface DecryptPayloadListener {

        void onSuccess();

        void onPairFail();

        void onAuthFail();

        void onFatalError();
    }
}
