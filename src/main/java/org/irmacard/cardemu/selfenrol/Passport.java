/*
 * Copyright (c) 2015, the IRMA Team
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 *  Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 *  Neither the name of the IRMA project nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.irmacard.cardemu.selfenrol;

import android.app.*;
import android.content.*;
import android.content.res.Resources;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.*;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.*;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.gson.Gson;

import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import net.sf.scuba.smartcards.*;

import org.acra.ACRA;
import org.irmacard.cardemu.*;
import org.irmacard.cardemu.BuildConfig;
import org.irmacard.credentials.Attributes;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.IdemixCredentials;
import org.irmacard.credentials.idemix.descriptions.IdemixVerificationDescription;
import org.irmacard.credentials.idemix.smartcard.IRMACard;
import org.irmacard.credentials.idemix.smartcard.SmartCardEmulatorService;
import org.irmacard.credentials.info.CredentialDescription;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.cardemu.HttpClient.HttpClientException;
import org.irmacard.idemix.IdemixService;
import org.irmacard.idemix.IdemixSmartcard;
import org.irmacard.mno.common.*;
import org.jmrtd.BACKey;
import org.jmrtd.PassportService;

import java.io.*;
import java.lang.reflect.Type;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import com.google.gson.GsonBuilder;
import org.jmrtd.lds.DG14File;
import org.jmrtd.lds.DG15File;
import org.jmrtd.lds.DG1File;
import org.jmrtd.lds.SODFile;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

public class Passport extends Activity {
    private NfcAdapter nfcA;
    private PendingIntent mPendingIntent;
    private IntentFilter[] mFilters;
    private String[][] mTechLists;

    private String TAG = "cardemu.Passport";

    // PIN handling
    private int tries = -1;

    // State variables
    private IRMACard card = null;
    private IdemixService is = null;
    private int passportAttempts = 0;

    private final static int MAX_PASSPORT_ATTEMPTS = 5;
    private final static int MAX_PASSPORT_TIME = 15 * 1000;

    private int screen;
    private static final int SCREEN_START = 1;
    private static final int SCREEN_BAC = 2;
    private static final int SCREEN_PASSPORT = 3;
    private static final int SCREEN_ISSUE = 4;
    private static final int SCREEN_ERROR = 5;
    private String imsi;

    public static final String CARD_STORAGE = "card";
    public static final String SETTINGS = "cardemu";

    private AlertDialog urldialog = null;
    private SharedPreferences settings;
    private SimpleDateFormat bacDateFormat = new SimpleDateFormat("yyMMdd");
    private DateFormat hrDateFormat = DateFormat.getDateInstance();

    private Handler handler = new Handler();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "onCreate() called");

        // Disable screenshots in release builds
        if (!BuildConfig.DEBUG) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        }

        // NFC stuff
        nfcA = NfcAdapter.getDefaultAdapter(getApplicationContext());
        mPendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);

        // Setup an intent filter for all TECH based dispatches
        IntentFilter tech = new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED);
        mFilters = new IntentFilter[] { tech };

        // Setup a tech list for all IsoDep cards
        mTechLists = new String[][] { new String[] { IsoDep.class.getName() } };

        settings = getSharedPreferences(SETTINGS, 0);
        client = new HttpClient(gson, new SecureSSLSocketFactory(getSocketFactory()));

        // Load the card and open the IdemixService
        card = CardManager.loadCard();
        is = new IdemixService(new SmartCardEmulatorService(card));
        try {
            is.open ();
        } catch (CardServiceException e) {
            e.printStackTrace();
        }

        setContentView(R.layout.enroll_activity_start);
        setTitle(R.string.app_name_enroll);

        String enrollServer = BuildConfig.enrollServer.substring(8); // Strip "https://"
        enrollServer = enrollServer.substring(0, enrollServer.indexOf('/')); // Strip path from the url
        String helpHtml = String.format(getString(R.string.se_connect_mno), enrollServer);

        TextView helpTextView = (TextView)findViewById(R.id.se_feedback_text);
        helpTextView.setText(Html.fromHtml(helpHtml));

        helpTextView.setMovementMethod(LinkMovementMethod.getInstance());
        helpTextView.setLinksClickable(true);

        Context context = getApplicationContext();
        TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        imsi = telephonyManager.getSubscriberId();

        if (imsi == null)
            imsi = "FAKE_IMSI_" +  Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
        ((TextView) findViewById(R.id.IMSI)).setText("IMSI: " + imsi);

        screen = SCREEN_START;
        enableContinueButton();

        if (nfcA == null) {
            showErrorScreen(R.string.error_nfc_notsupported);
            return;
        }
        if (!nfcA.isEnabled())
            showErrorScreen(R.string.error_nfc_disabled);
    }

    private void enableForegroundDispatch() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        Intent intent = new Intent(getApplicationContext(), this.getClass());
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        String[][] filter = new String[][] { new String[] { "android.nfc.tech.IsoDep" } };
        adapter.enableForegroundDispatch(this, pendingIntent, null, filter);
    }

    @Override
    public void onNewIntent(Intent intent) {
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();

        Intent intent = getIntent();
        Log.i(TAG, "onResume() called, action: " + intent.getAction());

        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
             processIntent(intent);
        }

        if (nfcA != null) {
            nfcA.enableForegroundDispatch(this, mPendingIntent, mFilters, mTechLists);
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        Log.i(TAG, "onBackPressed() called");
        finish();
    }

    @Override
    public void onPause() {
        super.onPause();

        Log.i(TAG, "onPause() called");

        if (nfcA != null) {
            nfcA.disableForegroundDispatch(this);
        }
    }

    /*
     * Process NFC intents. If we're on the passport screen, and there's an enroll session containing a nonce, this
     * method asynchroniously does BAC and AA with the passport, also extracting some necessary data files.
     *
     * As the ACTION_TECH_DISCOVERED intent can occur multiple times, this method can be called multiple times
     * without problems. If it is called for the MAX_PASSPORT_ATTEMPTS's time, however, it will advance the screen
     * either to the issue screen or the error screen, depending if we've successfully read the passport or not.
     */
    public void processIntent(final Intent intent) {
        // Only handle this event if we expect it
        if (screen != SCREEN_PASSPORT)
            return;

        if (enrollSession == null) {
            // We need to have an enroll session before we can do AA, because the enroll session contains the nonce.
            // So retry later. This will not cause an endless loop if the server is unreachable, because after a timeout
            // (5 sec) the getEnrollmentSession method will go to the error screen, so the if-clause above will trigger.
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    processIntent(intent);
                }
            }, 250);
            return;
        }

        TextView feedbackTextView = (TextView) findViewById(R.id.se_feedback_text);
        if (feedbackTextView != null) {
            feedbackTextView.setText(R.string.feedback_communicating_passport);
        } else {
            // Judging from some crash reports, the se_feedback_text textview is not always here at this point.
            // If so, report it. This may be an old problem that now does not occur anymore (in which case this line
            // should disappear at some point).
            ACRA.getErrorReporter().handleException(new Exception("se_feedback_text not found in passport screen"));
        }

        Tag tagFromIntent = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        assert (tagFromIntent != null);

        IsoDep tag = IsoDep.get(tagFromIntent);
        // Prevents "Tag is lost" messages (at least on a Nexus 5)
        // TODO how about on other devices?
        tag.setTimeout(1500);

        CardService cs = new IsoDepCardService(tag);
        PassportService passportService = null;

        try {
            cs.open();
            passportService = new PassportService(cs);
            passportService.sendSelectApplet(false);

            if (passportMsg == null) {
                passportMsg = new PassportDataMessage(enrollSession.getSessionToken(), imsi,enrollSession.getNonce());
            }
            readPassport(passportService, passportMsg);
        } catch (CardServiceException e) {
            // TODO under what circumstances does this happen? Maybe handle it more intelligently?
            ACRA.getErrorReporter().handleException(e);
            showErrorScreen(getString(R.string.error_enroll_passporterror),
                    getString(R.string.abort), 0,
                    getString(R.string.retry), SCREEN_PASSPORT);
        }
    }

    /**
     * Reads the datagroups 1, 14 and 15, and the SOD file and requests an active authentication from an e-passport
     * in a seperate thread.
     */
    private void readPassport(PassportService ps, PassportDataMessage pdm) {
        AsyncTask<Object,Void,PassportDataMessage> task = new AsyncTask<Object,Void,PassportDataMessage>(){
            ProgressBar progressBar = (ProgressBar) findViewById(R.id.se_progress_bar);
            boolean passportError = false;
            boolean bacError = false;

            long start;
            long stop;

            @Override
            protected PassportDataMessage doInBackground(Object... params) {
                if (params.length <2) {
                    return null; //TODO appropriate error
                }
                PassportService ps = (PassportService) params[0];
                PassportDataMessage pdm = (PassportDataMessage) params[1];

                if (passportAttempts == 0) {
                    start = System.currentTimeMillis();
                }
                passportAttempts++;

                // Do the BAC separately from generating the pdm, so we can be specific in our error message if
                // necessary. (Note: the IllegalStateException should not happen, but if it does for some unforseen
                // reason there is no need to let it crash the app.)
                try {
                    ps.doBAC(getBACKey());
                    Log.i(TAG, "Passport: doing BAC");
                } catch (CardServiceException | IllegalStateException e) {
                    bacError = true;
                    Log.e(TAG, "Passport: doing BAC failed");
                    return null;
                }

                Exception ex = null;
                try {
                    Log.i(TAG, "Passport: reading attempt " + passportAttempts);
                    generatePassportDataMessage(ps, pdm);
                } catch (IOException|CardServiceException e) {
                    Log.w(TAG, "Passport: reading attempt " + passportAttempts + " failed, stack trace:");
                    Log.w(TAG, "          " + e.getMessage());
                    ex = e;
                }

                passportError = !pdm.isComplete();
                if (!pdm.isComplete() && passportAttempts == MAX_PASSPORT_ATTEMPTS && ex != null) {
                    // Build a fancy report saying which fields we did and which we did not manage to get
                    Log.e(TAG, "Passport: too many attempts failed, aborting");
                    ACRA.getErrorReporter().reportBuilder()
                            .customData("sod", String.valueOf(pdm.getSodFile() == null))
                            .customData("dg1File", String.valueOf(pdm.getDg1File() == null))
                            .customData("dg14File", String.valueOf(pdm.getDg14File() == null))
                            .customData("dg15File", String.valueOf(pdm.getDg15File() == null))
                            .customData("response", String.valueOf(pdm.getResponse() == null))
                            .exception(ex)
                            .send();
                }

                return pdm;
            }

            @Override
            protected void onProgressUpdate(Void... values) {
                if (progressBar != null) { // progressBar can vanish if the progress goes wrong halfway through
                    progressBar.incrementProgressBy(1);
                }
            }

            /**
             * Do the AA protocol with the passport using the passportService, and put the response in a new
             * PassportDataMessage. Also read some data groups.
             */
            public void generatePassportDataMessage(PassportService passportService, PassportDataMessage pdm)
                    throws CardServiceException, IOException {
                publishProgress();

                try {
                    if (pdm.getDg1File() == null) {
                        pdm.setDg1File(new DG1File(passportService.getInputStream(PassportService.EF_DG1)));
                        Log.i(TAG, "Passport: reading DG1");
                        publishProgress();
                    }
                    if (pdm.getSodFile() == null) {
                        pdm.setSodFile(new SODFile(passportService.getInputStream(PassportService.EF_SOD)));
                        Log.i(TAG, "Passport: reading SOD");
                        publishProgress();
                    }
                    if (pdm.getSodFile() != null) { // We need the SOD file to check if DG14 exists
                        if (pdm.getSodFile().getDataGroupHashes().get(14) != null) { // Checks if DG14 exists
                            if (pdm.getDg14File() == null) {
                                pdm.setDg14File(new DG14File(passportService.getInputStream(PassportService.EF_DG14)));
                                Log.i(TAG, "Passport: reading DG14");
                                publishProgress();
                            }
                        } else { // If DG14 does not exist, just advance the progress bar
                            Log.i(TAG, "Passport: reading DG14 not necessary, skipping");
                            publishProgress();
                        }
                    }
                    if (pdm.getDg15File() == null) {
                        pdm.setDg15File(new DG15File(passportService.getInputStream(PassportService.EF_DG15)));
                        Log.i(TAG, "Passport: reading DG15");
                        publishProgress();
                    }
                    // The doAA() method does not use its first three arguments, it only passes the challenge
                    // on to another functio within JMRTD.
                    if (pdm.getResponse() == null) {
                        pdm.setResponse(passportService.doAA(null, null, null, pdm.getChallenge()));
                        Log.i(TAG, "Passport: doing AA");
                        publishProgress();
                    }
                } catch (NullPointerException e) {
                    // JMRTD sometimes throws a nullpointer exception if the passport communcation goes wrong
                    // (I've seen it happening if the passport is removed from the device halfway through)
                    throw new IOException("NullPointerException during passport communication", e);
                }
            }

            @Override
            protected void onPostExecute(PassportDataMessage pdm) {
                // First set the result, since it may be partially okay
                passportMsg = pdm;

                Boolean done = pdm != null && pdm.isComplete();

                Log.i(TAG, "Passport: attempt " + passportAttempts + " finished, done: " + done);

                // If we're not yet done, we should not advance the screen but just wait for further attempts
                if (passportAttempts < MAX_PASSPORT_ATTEMPTS && !done) {
                    return;
                }

                stop = System.currentTimeMillis();
                MetricsReporter.getInstance().reportMeasurement("passport_data_attempts", passportAttempts, false);
                MetricsReporter.getInstance().reportMeasurement("passport_data_time", stop-start);

                // If we're here, we're done. Check for errors or failures, and advance the screen
                if (!bacError && !passportError) {
                    advanceScreen();
                }

                if (bacError) {
                    showErrorScreen(getString(R.string.error_enroll_bacfailed),
                            getString(R.string.abort), 0,
                            getString(R.string.retry), SCREEN_BAC);
                }

                if (passportError) {
                    showErrorScreen(R.string.error_enroll_passporterror);
                }
            }
        }.execute(ps,pdm);
    }



    private void enableContinueButton(){
        final Button button = (Button) findViewById(R.id.se_button_continue);
        button.setVisibility(View.VISIBLE);
        button.setEnabled(true);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Log.d(TAG, "continue button pressed");
                advanceScreen();
            }
        });
    }

    private void updateProgressCounter() {
        Resources r = getResources();
        switch (screen) {
            case SCREEN_ISSUE:
                ((TextView)findViewById(R.id.step3_text)).setTextColor(r.getColor(R.color.irmadarkblue));
            case SCREEN_PASSPORT:
                ((TextView)findViewById(R.id.step2_text)).setTextColor(r.getColor(R.color.irmadarkblue));
            case SCREEN_BAC:
                ((TextView)findViewById(R.id.step1_text)).setTextColor(r.getColor(R.color.irmadarkblue));
                ((TextView)findViewById(R.id.step_text)).setTextColor(r.getColor(R.color.irmadarkblue));
        }
    }

    private void showErrorScreen(int errormsgId) {
        showErrorScreen(getString(errormsgId));
    }

    private void showErrorScreen(String errormsg) {
        showErrorScreen(errormsg, getString(R.string.abort), 0, null, 0);
    }

    /**
     * Show the error screen.
     *
     * @param errormsg The message to show.
     * @param rightButtonString The text that the right button should show.
     * @param leftButtonScreen The screen that we shoul;d go to when the right button is clicked. Pass 0 if the
     *                         activity should be canceled.
     * @param leftButtonString The text that the left button should show. Pass null if this button should be hidden.
     * @param rightButtonScreen The screen that we should go to when the left button is clicked.
     */
    private void showErrorScreen(String errormsg, final String rightButtonString, final int rightButtonScreen,
                                 final String leftButtonString, final int leftButtonScreen) {
        prepareErrowScreen();

        TextView view = (TextView)findViewById(R.id.enroll_error_msg);
        Button rightButton = (Button)findViewById(R.id.se_button_continue);
        Button leftButton = (Button)findViewById(R.id.se_button_cancel);

        view.setText(errormsg);

        rightButton.setText(rightButtonString);
        rightButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (rightButtonScreen == 0) {
                    screen = SCREEN_START;
                    finish();
                } else {
                    screen = rightButtonScreen - 1;
                    advanceScreen();
                }
            }
        });

        if (leftButtonString != null) {
            leftButton.setText(leftButtonString);
            leftButton.setVisibility(View.VISIBLE);
            leftButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (leftButtonScreen == 0) {
                        screen = SCREEN_START;
                        finish();
                    } else {
                        screen = leftButtonScreen - 1;
                        advanceScreen();
                    }
                }
            });
        } else {
            leftButton.setVisibility(View.INVISIBLE);
        }
    }

    private void prepareErrowScreen() {
        setContentView(R.layout.enroll_activity_error);

        Resources r = getResources();
        switch (screen) {
            case SCREEN_ISSUE:
                ((TextView)findViewById(R.id.step3_text)).setTextColor(r.getColor(R.color.irmared));
            case SCREEN_PASSPORT:
                ((TextView)findViewById(R.id.step2_text)).setTextColor(r.getColor(R.color.irmared));
            case SCREEN_BAC:
                ((TextView)findViewById(R.id.step1_text)).setTextColor(r.getColor(R.color.irmared));
            case SCREEN_START:
                ((TextView)findViewById(R.id.step_text)).setTextColor(r.getColor(R.color.irmared));
        }

        screen = SCREEN_ERROR;
    }

    private void setBacFieldWatcher() {
        final EditText docnrEditText = (EditText) findViewById(R.id.doc_nr_edittext);
        final EditText dobEditText = (EditText) findViewById(R.id.dob_edittext);
        final EditText doeEditText = (EditText) findViewById(R.id.doe_edittext);
        final Button continueButton = (Button) findViewById(R.id.se_button_continue);

        TextWatcher bacFieldWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean enableButton = docnrEditText.getText().length() > 0
                        && dobEditText.getText().length() > 0
                        && doeEditText.getText().length() > 0;
                continueButton.setEnabled(enableButton);
            }
        };

        docnrEditText.addTextChangedListener(bacFieldWatcher);
        dobEditText.addTextChangedListener(bacFieldWatcher);
        doeEditText.addTextChangedListener(bacFieldWatcher);

        bacFieldWatcher.onTextChanged("", 0, 0, 0);
    }

    private void advanceScreen() {
        switch (screen) {
        case SCREEN_START:
            setContentView(R.layout.enroll_activity_bac);
            screen = SCREEN_BAC;
            updateProgressCounter();
            enableContinueButton();

            // Restore the BAC input fields from the settings, if present
            long bacDob = settings.getLong("enroll_bac_dob", 0);
            long bacDoe = settings.getLong("enroll_bac_doe", 0);
            String docnr = settings.getString("enroll_bac_docnr", "");

            EditText docnrEditText = (EditText) findViewById(R.id.doc_nr_edittext);
            EditText dobEditText = (EditText) findViewById(R.id.dob_edittext);
            EditText doeEditText = (EditText) findViewById(R.id.doe_edittext);

            Calendar c = Calendar.getInstance();
            if (bacDob != 0) {
                c.setTimeInMillis(bacDob);
                setDateEditText(dobEditText, c);
            }
            if (bacDoe != 0) {
                c.setTimeInMillis(bacDoe);
                setDateEditText(doeEditText, c);
            }
            if (docnr.length() != 0)
                docnrEditText.setText(docnr);

            setBacFieldWatcher();

            break;

        case SCREEN_BAC:
            // Store the entered document number and dates in the settings.
            docnrEditText = (EditText) findViewById(R.id.doc_nr_edittext);
            dobEditText = (EditText) findViewById(R.id.dob_edittext);
            doeEditText = (EditText) findViewById(R.id.doe_edittext);

            if (docnrEditText != null && dobEditText != null && doeEditText != null) {
                bacDob = 0;
                bacDoe = 0;
                try {
                    String dobString = dobEditText.getText().toString();
                    String doeString = doeEditText.getText().toString();
                    if (dobString.length() != 0)
                        bacDob = hrDateFormat.parse(dobString).getTime();
                    if (doeString.length() != 0)
                        bacDoe = hrDateFormat.parse(doeString).getTime();
                } catch (ParseException e) {
                    // Should not happen: the DOB and DOE EditTexts are set only by the DatePicker's,
                    // OnDateSetListener, which should always set a properly formatted string.
                    e.printStackTrace();
                }

                if (bacDoe < System.currentTimeMillis()) {
                    showErrorScreen(getString(R.string.error_enroll_passport_expired),
                            getString(R.string.abort), 0,
                            getString(R.string.retry), SCREEN_BAC);
                    return;
                }

                settings.edit()
                        .putLong("enroll_bac_dob", bacDob)
                        .putLong("enroll_bac_doe", bacDoe)
                        .putString("enroll_bac_docnr", docnrEditText.getText().toString())
                        .apply();
            }

            // Get the BasicClientMessage containing our nonce to send to the passport.
            getEnrollmentSession(new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    EnrollmentStartResult result = (EnrollmentStartResult) msg.obj;

                    if (result.exception != null) { // Something went wrong
                        showErrorScreen(result.errorId);
                    } else {
                        TextView connectedTextView = (TextView) findViewById(R.id.se_connected);
                        connectedTextView.setTextColor(getResources().getColor(R.color.irmagreen));
                        connectedTextView.setText(R.string.se_connected_mno);

                        ((TextView) findViewById(R.id.se_feedback_text)).setVisibility(View.VISIBLE);
                        ((ProgressBar) findViewById(R.id.se_progress_bar)).setVisibility(View.VISIBLE);

                        enrollSession = result.msg;
                    }
                }
            });

            // Spongycastle provides the MAC ISO9797Alg3Mac, which JMRTD usesin the doBAC method below (at
            // DESedeSecureMessagingWrapper.java, line 115)
            Security.addProvider(new org.spongycastle.jce.provider.BouncyCastleProvider());

            // Update the UI
            setContentView(R.layout.enroll_activity_passport);
            screen = SCREEN_PASSPORT;
            updateProgressCounter();

            // The next advanceScreen() is called when the passport reading was successful (see onPostExecute() in
            // readPassport() above). Thus, if no passport arrives or we can't successfully read it, we have to
            // ensure here that we don't stay on the passport screen forever with this timeout.
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (screen == SCREEN_PASSPORT && (passportMsg == null || !passportMsg.isComplete())) {
                        showErrorScreen(getString(R.string.error_enroll_passporterror));
                    }
                }
            }, MAX_PASSPORT_TIME);

            break;

        case SCREEN_PASSPORT:
            setContentView(R.layout.enroll_activity_issue);
            screen = SCREEN_ISSUE;
            updateProgressCounter();

            // Save the card before messing with it so we can roll back if
            // something goes wrong
            CardManager.storeCard();

            // Do it!
            enroll(new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    if (msg.obj == null) {
                        // Success, save our new credentials
                        CardManager.storeCard();
                        enableContinueButton();
                        ((TextView) findViewById(R.id.se_done_text)).setVisibility(View.VISIBLE);
                    } else {
                        // Rollback the card
                        card = CardManager.loadCard();
                        is = new IdemixService(new SmartCardEmulatorService(card));

                        String errormsg;
                        if (msg.what != 0) // .what may contain a string identifier saying what went wrong
                            showErrorScreen(msg.what);
                        else
                            showErrorScreen(R.string.unknown_error);
                    }
                }
            });

            break;

        case SCREEN_ISSUE:
        case SCREEN_ERROR:
            screen = SCREEN_START;
            finish();
            break;

        default:
            Log.e(TAG, "Error, screen switch fall through");
            break;
        }
    }

    @Override
    public void finish() {
        // Prepare data intent
        if (is != null) {
            is.close();
        }
        //remove "old" passportdatamessage object
        passportMsg = null;
        Intent data = new Intent();
        Log.d(TAG,"Storing card");
        CardManager.storeCard();
        setResult(RESULT_OK, data);
        super.finish();
    }

    public void onDateTouch(View v) {
        final EditText dateView = (EditText) v;
        final String name = v.getId() == R.id.dob_edittext ? "dob" : "doe";
        Long current = settings.getLong("enroll_bac_" + name, 0);

        final Calendar c = Calendar.getInstance();
        if (current != 0)
            c.setTimeInMillis(current);

        int currentYear = c.get(Calendar.YEAR);
        int currentMonth = c.get(Calendar.MONTH);
        int currentDay = c.get(Calendar.DAY_OF_MONTH);

        DatePickerDialog dpd = new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int year, int monthOfYear, int dayOfMonth) {
                Calendar date = new GregorianCalendar(year, monthOfYear, dayOfMonth);
                setDateEditText(dateView, date);
            }
        }, currentYear, currentMonth, currentDay);
        dpd.show();
    }

    private void setDateEditText(EditText dateView, Calendar c) {
        dateView.setText(hrDateFormat.format(c.getTime()));
    }

    /**
     * Get the BAC key using the input from the user from the BAC screen.
     *
     * @return The BAC key.
     * @throws IllegalStateException when the BAC fields in the BAC screen have not been set.
     */
    private BACKey getBACKey() {
        long dob = settings.getLong("enroll_bac_dob", 0);
        long doe = settings.getLong("enroll_bac_doe", 0);
        String docnr = settings.getString("enroll_bac_docnr", "");

        String dobString = bacDateFormat.format(new Date(dob));
        String doeString = bacDateFormat.format(new Date(doe));
        return new BACKey(docnr, dobString, doeString);
    }

    //region Network and issuing

    /**
     * Get a SSLSocketFactory that uses public key pinning: it only accepts the
     * CA certificate obtained from the file res/raw/ca.cert.
     * See https://developer.android.com/training/articles/security-ssl.html#Pinning
     * and https://op-co.de/blog/posts/java_sslsocket_mitm/
     *
     * Alternatively, https://github.com/Flowdalic/java-pinning
     *
     * If we want to trust our own CA instead, we can import it using
     * keyStore.setCertificateEntry("ourCa", ca);
     * instead of using the keyStore.load method. See the first link.
     *
     * @return A client whose SSL with our certificate pinnned. Will be null
     * if something went wrong.
     */
    private SSLSocketFactory getSocketFactory() {
        try {
            Resources r = getResources();

            // Get the certificate from the res/raw folder and parse it
            InputStream ins = r.openRawResource(r.getIdentifier("ca", "raw", getPackageName()));
            Certificate ca;
            try {
                ca = CertificateFactory.getInstance("X.509").generateCertificate(ins);
            } finally {
                ins.close();
            }

            // Put the certificate in the keystore, put that in the TrustManagerFactory,
            // put that in the SSLContext, from which we get the SSLSocketFactory
            KeyStore keyStore = KeyStore.getInstance("BKS");
            keyStore.load(null, null);
            keyStore.setCertificateEntry("ca", ca);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("X509");
            tmf.init(keyStore);
            final SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);

            return new SecureSSLSocketFactory(context.getSocketFactory());
        } catch (KeyManagementException|NoSuchAlgorithmException|KeyStoreException|CertificateException|IOException e) {
            ACRA.getErrorReporter().handleException(e);
            e.printStackTrace();
            return null;
        }
    }

    final Gson gson = new GsonBuilder()
            .registerTypeAdapter(ProtocolCommand.class, new ProtocolCommandDeserializer())
            .registerTypeAdapter(ProtocolResponse.class, new ProtocolResponseSerializer())
            .registerTypeHierarchyAdapter(byte[].class, new ByteArrayToBase64TypeAdapter())
            .registerTypeAdapter(PassportDataMessage.class, new PassportDataMessageSerializer())
            .create();
    HttpClient client = null;
    EnrollmentStartMessage enrollSession = null;
    PassportDataMessage passportMsg = null;

    /**
     * Simple class to store the result of getEnrollmentSession
     * (either an EnrollmentStartMessage or an exception plus error message id (use R.strings))
     */
    private class EnrollmentStartResult {
        public EnrollmentStartMessage msg = null;
        public HttpClientException exception = null;
        public int errorId = 0;

        public EnrollmentStartResult(EnrollmentStartMessage msg) {
            this(msg, null, 0);
        }

        public EnrollmentStartResult(HttpClientException exception) {
            this(null, exception, 0);
        }

        public EnrollmentStartResult(HttpClientException exception, int errorId) {
            this(null, exception, errorId);
        }

        public EnrollmentStartResult(EnrollmentStartMessage msg, HttpClientException exception, int errorId) {
            this.msg = msg;
            this.exception = exception;
            this.errorId = errorId;
        }
    }

    public void getEnrollmentSession(final Handler uiHandler) {
        final String serverUrl = BuildConfig.enrollServer;

        AsyncTask<Void, Void, EnrollmentStartResult> task = new AsyncTask<Void, Void, EnrollmentStartResult>() {
            @Override
            protected EnrollmentStartResult doInBackground(Void... params) {
                try {
                    EnrollmentStartMessage msg = client.doGet(EnrollmentStartMessage.class, serverUrl + "/start");
                    return new EnrollmentStartResult(msg);
                } catch (HttpClientException e) {
                    if (e.cause instanceof JsonSyntaxException) {
                        ACRA.getErrorReporter().handleException(e);
                        return new EnrollmentStartResult(e, R.string.error_enroll_invalidresponse);
                    }
                    else {
                        return new EnrollmentStartResult(e, R.string.error_enroll_cantconnect);
                    }
                }
            }

            @Override
            protected void onPostExecute(EnrollmentStartResult result) {
                Message msg = Message.obtain();
                msg.obj = result;
                uiHandler.sendMessage(msg);
            }
        }.execute();
    }


    /**
     * Do the enrolling and send a message to uiHandler when done. If something
     * went wrong, the .obj of the message sent to uiHandler will be an exception;
     * if everything went OK the .obj will be null.
     * TODO return our result properly using a class like EnrollmentStartResult above
     *
     * @param uiHandler The handler to message when done.
     */
    public void enroll(final Handler uiHandler) {
        final String serverUrl = BuildConfig.enrollServer;

        // Doing HTTP(S) stuff on the main thread is not allowed.
        AsyncTask<PassportDataMessage, Void, Message> task = new AsyncTask<PassportDataMessage, Void, Message>() {
            @Override
            protected Message doInBackground(PassportDataMessage... params) {
                Message msg = Message.obtain();
                try {
                    // Get a passportMsg token
                    PassportDataMessage passportMsg = params[0];

                    // Send passport response and let server check it
                    PassportVerificationResultMessage result = client.doPost(
                            PassportVerificationResultMessage.class,
                            serverUrl + "/verify-passport",
                            passportMsg
                    );

                    if (result.getResult() != PassportVerificationResult.SUCCESS) {
                        throw new CardServiceException("Server rejected passport proof");
                    }

                    // Get a list of credential that the client can issue
                    BasicClientMessage bcm = new BasicClientMessage(passportMsg.getSessionToken());
                    Type t = new TypeToken<HashMap<String, Map<String, String>>>() {}.getType();
                    HashMap<String, Map<String, String>> credentialList =
                            client.doPost(t, serverUrl + "/issue/credential-list", bcm);

                    // Get them all!
                    for (String credentialType : credentialList.keySet()) {
                        issue(credentialType, passportMsg);
                    }
                } catch (CardServiceException // Issuing the credential to the card failed
                        |InfoException // VerificationDescription not found in configurarion
                        |CredentialsException e) { // Verification went wrong
                    ACRA.getErrorReporter().handleException(e);
                    //e.printStackTrace();
                    msg.obj = e;
                    msg.what = R.string.error_enroll_issuing_failed;
                } catch (HttpClientException e) {
                    msg.obj = e;
                    if (e.cause instanceof JsonSyntaxException) {
                        ACRA.getErrorReporter().handleException(e);
                        msg.what = R.string.error_enroll_invalidresponse;
                    }
                    else {
                        msg.what = R.string.error_enroll_cantconnect;
                    }
                }
                return msg;
            }

            private void issue(String credentialType, BasicClientMessage session)
            throws HttpClientException, CardServiceException, InfoException, CredentialsException {
                // Get the first batch of commands for issuing
                RequestStartIssuanceMessage startMsg = new RequestStartIssuanceMessage(
                        session.getSessionToken(),
                        is.execute(IdemixSmartcard.selectApplicationCommand).getData()
                );
                ProtocolCommands issueCommands = client.doPost(ProtocolCommands.class,
                        serverUrl + "/issue/" + credentialType + "/start", startMsg);

                // Execute the retrieved commands
                is.sendCardPin("000000".getBytes());
                is.sendCredentialPin("0000".getBytes());
                ProtocolResponses responses = is.execute(issueCommands);

                // Get the second batch of commands for issuing
                RequestFinishIssuanceMessage finishMsg
                        = new RequestFinishIssuanceMessage(session.getSessionToken(), responses);
                issueCommands = client.doPost(ProtocolCommands.class,
                        serverUrl + "/issue/" + credentialType + "/finish", finishMsg);

                // Execute the retrieved commands
                is.execute(issueCommands);

                // Check if it worked
                IdemixCredentials ic = new IdemixCredentials(is);
                IdemixVerificationDescription ivd = new IdemixVerificationDescription(
                        "MijnOverheid", credentialType + "All");
                Attributes attributes = ic.verify(ivd);

                if (attributes != null)
                    Log.d(TAG, "Enrollment issuing succes!");
                else
                    Log.d(TAG, "Enrollment issuing failed.");
            }

            @Override
            protected void onPostExecute(Message msg) {
                uiHandler.sendMessage(msg);
            }
        }.execute(passportMsg);
    }

    //endregion
}