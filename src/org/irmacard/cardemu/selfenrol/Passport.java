package org.irmacard.cardemu.selfenrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.app.PendingIntent;
import android.content.res.Resources;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.*;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.google.gson.Gson;

import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.CardServiceException;
import net.sf.scuba.smartcards.IsoDepCardService;

import org.irmacard.cardemu.R;
import org.irmacard.cardemu.selfenrol.government.GovernmentEnrol;
import org.irmacard.cardemu.selfenrol.government.GovernmentEnrolImpl;
import org.irmacard.cardemu.selfenrol.government.MockupPersonalRecordDatabase;
import org.irmacard.cardemu.selfenrol.government.PersonalRecordDatabase;
import org.irmacard.cardemu.selfenrol.mno.MNOEnrol;
import org.irmacard.cardemu.selfenrol.mno.MNOEnrollImpl;
import org.irmacard.cardemu.selfenrol.mno.MockupSubscriberDatabase;
import org.irmacard.cardemu.selfenrol.mno.SubscriberDatabase;
import org.irmacard.cardemu.selfenrol.mno.SubscriberInfo;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.IdemixCredentials;
import org.irmacard.credentials.idemix.smartcard.IRMACard;
import org.irmacard.credentials.idemix.smartcard.SmartCardEmulatorService;
import org.irmacard.credentials.info.CredentialDescription;
import org.irmacard.credentials.info.InfoException;
import org.irmacard.idemix.IdemixService;
import org.jmrtd.PassportService;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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

    private int screen;
    private static final int SCREEN_START = 1;
    private static final int SCREEN_PASSPORT = 2;
    private static final int SCREEN_ISSUE = 3;
    private static final int SCREEN_ERROR = 4;
    private String imsi;

    private final String CARD_STORAGE = "card";
    private final String SETTINGS = "cardemu";

    private AlertDialog urldialog = null;
    private String enrollServerUrl;
    private SharedPreferences settings;

    private EnrollClient client;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // NFC stuff
        nfcA = NfcAdapter.getDefaultAdapter(getApplicationContext());
        mPendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);

        // Setup an intent filter for all TECH based dispatches
        IntentFilter tech = new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED);
        mFilters = new IntentFilter[] { tech };

        // Setup a tech list for all IsoDep cards
        mTechLists = new String[][] { new String[] { IsoDep.class.getName() } };

        // Attempt to get the enroll server URL from the settings. If none is there,
        // use the default value (from res/values/strings.xml)
        settings = getSharedPreferences(SETTINGS, 0);
        enrollServerUrl = settings.getString("enroll_server_url", "");
        if (enrollServerUrl.length() == 0)
            enrollServerUrl = getString(R.string.enroll_default_url);

        if(getIntent() != null) {
            onNewIntent(getIntent());
        }

        setContentView(R.layout.enroll_activity_start);
        updateHelpText();
        setTitle(R.string.app_name_enroll);

        TextView descriptionTextView = (TextView)findViewById(R.id.se_feedback_text);
        descriptionTextView.setMovementMethod(LinkMovementMethod.getInstance());
        descriptionTextView.setLinksClickable(true);

        screen = SCREEN_START;
        enableContinueButton();

        if (nfcA == null)
            showErrorScreen(R.string.error_nfc_notsupported);
        if (!nfcA.isEnabled())
            showErrorScreen(R.string.error_nfc_disabled);
    }

    private void updateHelpText() {
        String helpHtml = String.format(getString(R.string.se_connect_mno), enrollServerUrl);
        TextView helpTextView = (TextView)findViewById(R.id.se_feedback_text);
        helpTextView.setText(Html.fromHtml(helpHtml));
    }

    private void enableForegroundDispatch() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        Intent intent = new Intent(getApplicationContext(), this.getClass());
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        String[][] filter = new String[][] { new String[] { "android.nfc.tech.IsoDep" } };
        adapter.enableForegroundDispatch(this, pendingIntent, null, filter);
    }

    public void onNewIntent(Intent intent) {
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(getIntent().getAction())) {
            processIntent(getIntent());
        }

        if (nfcA != null) {
            nfcA.enableForegroundDispatch(this, mPendingIntent, mFilters, mTechLists);
        }

        Intent intent = getIntent();
        Log.i(TAG, "Action: " + intent.getAction());
        if (intent.hasExtra("card_json")) {
            loadCard();
            Log.d(TAG,"loaded card");
            try {
                is.open ();
            } catch (CardServiceException e) {
                e.printStackTrace();
            }
        }

        Context context = getApplicationContext ();
        TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        imsi = telephonyManager.getSubscriberId();

        if (imsi == null)
            imsi = "FAKE_IMSI_" +  Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (screen == SCREEN_START) {
            ((TextView) findViewById(R.id.IMSI)).setText("IMSI: " + imsi);
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        if (client != null)
            client.closeConnection();
        finish();
    }

    //TODO: move all card functionality to a specific class, so we don't need this ugly code duplication and can do explicit card state checks there.
    protected void logCard(){
        Log.d(TAG, "Current card contents");
        // Retrieve list of credentials from the card
        IdemixCredentials ic = new IdemixCredentials(is);
        List<CredentialDescription> credentialDescriptions = new ArrayList<CredentialDescription>();
        // HashMap<CredentialDescription,Attributes> credentialAttributes = new HashMap<CredentialDescription,Attributes>();
        try {
            ic.connect();
            is.sendCardPin("000000".getBytes());
            credentialDescriptions = ic.getCredentials();
            for(CredentialDescription cd : credentialDescriptions) {
                Log.d(TAG,cd.getName());
            }
        } catch (CardServiceException e) {
            e.printStackTrace();
        } catch (InfoException e) {
            e.printStackTrace();
        } catch (CredentialsException e) {
            e.printStackTrace();
        }
    }

    private void storeCard() {
        Log.d(TAG,"Storing card");
        SharedPreferences.Editor editor = settings.edit();
        Gson gson = new Gson();
        editor.putString(CARD_STORAGE, gson.toJson(card));
        editor.commit();
    }

    private void loadCard() {
        String card_json = settings.getString(CARD_STORAGE, "");
        Gson gson = new Gson();
        card = gson.fromJson(card_json, IRMACard.class);
        is = new IdemixService(new SmartCardEmulatorService(card));
    }

    @Override
    public void onPause() {
        super.onPause();
        if (nfcA != null) {
            nfcA.disableForegroundDispatch(this);
        }
    }

    public void processIntent(Intent intent) {
        // Only handle this event if we expect it
        if (screen != SCREEN_PASSPORT)
            return;

        advanceScreen();

        Tag tagFromIntent = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        assert (tagFromIntent != null);

        IsoDep tag = IsoDep.get(tagFromIntent);
        CardService cs = new IsoDepCardService(tag);
        PassportService passportService = null;

        try {
            cs.open();
            passportService = new PassportService(cs);
        } catch (CardServiceException e) {
            // TODO under what circumstances does this happen? Maybe handle it more intelligently?
            showErrorScreen(e.getMessage());
        }

        client = new EnrollClient();
        client.enroll(passportService, is, new Handler() {
            @Override
            public void handleMessage(Message msg) {
                if (msg.obj == null)
                    enableContinueButton();
                else
                    showErrorScreen((String)msg.obj);
            }
        });
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


    /**
     * Check if an IP address is valid.
     *
     * @param url The IP to check
     * @return True if valid, false otherwise.
     */
    private static Boolean isValidIPAddress(String url) {
        Pattern IP_ADDRESS = Pattern.compile(
                "((25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9])\\.(25[0-5]|2[0-4]"
                        + "[0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1]"
                        + "[0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}"
                        + "|[1-9][0-9]|[0-9]))");
        return IP_ADDRESS.matcher(url).matches();
    }

    /**
     * Check if a given domain name is valid. We consider it valid if it consists of
     * alphanumeric characters and dots, and if the first character is not a dot.
     *
     * @param url The domain to check
     * @return True if valid, false otherwise
     */
    private static Boolean isValidDomainName(String url) {
        Pattern VALID_DOMAIN = Pattern.compile("([\\w]+[\\.\\w]*)");
        return VALID_DOMAIN.matcher(url).matches();
    }


    /**
     * Check if a given URL is valid. We consider it valid if it is either a valid
     * IP address or a valid domain name, which is checked using
     * using {@link #isValidDomainName(String)} Boolean} and
     * {@link #isValidIPAddress(String) Boolean}.
     *
     * @param url The URL to check
     * @return True if valid, false otherwise
     */
    private static Boolean isValidURL(String url) {
        String[] parts = url.split("\\.");

        // If the part of the url after the rightmost dot consists
        // only of numbers, it must be an IP address
        if (Pattern.matches("[\\d]+", parts[parts.length-1]))
            return isValidIPAddress(url);
        else
            return isValidDomainName(url);
    }


    private void updateProgressCounter() {
        Resources r = getResources();
        switch (screen) {
            case SCREEN_ISSUE:
                ((TextView)findViewById(R.id.step3_text)).setTextColor(r.getColor(R.color.irmadarkblue));
            case SCREEN_PASSPORT:
                ((TextView)findViewById(R.id.step_text)).setTextColor(r.getColor(R.color.irmadarkblue));
                ((TextView)findViewById(R.id.step1_text)).setTextColor(r.getColor(R.color.irmadarkblue));
                ((TextView)findViewById(R.id.step2_text)).setTextColor(r.getColor(R.color.irmadarkblue));
        }
    }

    private void showErrorScreen(int errormsgId) {
        showErrorScreen(getString(errormsgId));
    }

    private void showErrorScreen(String errormsg) {
        setContentView(R.layout.enroll_activity_error);

        Resources r = getResources();
        switch (screen) {
            case SCREEN_ISSUE:
                ((TextView)findViewById(R.id.step3_text)).setTextColor(r.getColor(R.color.irmared));
            case SCREEN_PASSPORT:
                ((TextView)findViewById(R.id.step1_text)).setTextColor(r.getColor(R.color.irmared));
                ((TextView)findViewById(R.id.step2_text)).setTextColor(r.getColor(R.color.irmared));
            case SCREEN_START:
                ((TextView)findViewById(R.id.step_text)).setTextColor(r.getColor(R.color.irmared));
        }

        TextView view = (TextView)findViewById(R.id.enroll_error_msg);
        view.setText(errormsg);

        screen = SCREEN_ERROR;
        enableContinueButton();
    }

    private void advanceScreen() {
        switch (screen) {
        case SCREEN_START:
            setContentView(R.layout.enroll_activity_passport);
            screen = SCREEN_PASSPORT;
            invalidateOptionsMenu();
            updateProgressCounter();
            final Button button = (Button) findViewById(R.id.se_button_continue);
            button.setEnabled(false);
            break;

        case SCREEN_PASSPORT:
            setContentView(R.layout.enroll_activity_issue);
            screen = SCREEN_ISSUE;
            updateProgressCounter();
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
        Intent data = new Intent();
        Log.d(TAG,"Storing card");
        storeCard();
        setResult(RESULT_OK, data);
        super.finish();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (screen == SCREEN_START) {
            // Inflate the menu; this adds items to the action bar if it is present.
            getMenuInflater().inflate(R.menu.enroll_activity_start, menu);
            return true;
        }

        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.d(TAG, "enroll menu press registered");

        // Handle item selection
        switch (item.getItemId()) {
        case R.id.set_enroll_url:
            Log.d(TAG, "set_enroll_url pressed");

            // Create the dialog only once
            if (urldialog == null)
                urldialog = getUrlDialog();

            // Show the dialog
            urldialog.show();
            // Pop up the keyboard
            urldialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);

            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }


    // Helper function to build the URL dialog and set the listeners.
    private AlertDialog getUrlDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        // Simple view containing the actual input field
        View v = this.getLayoutInflater().inflate(R.layout.enroll_url_dialog, null);

        // Set the URL field to the appropriate value
        final EditText urlfield = (EditText)v.findViewById(R.id.enroll_url_field);
        urlfield.setText(enrollServerUrl);

        // Build the dialog
        builder.setTitle(R.string.enroll_url_dialog_title)
                .setView(v)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        enrollServerUrl = urlfield.getText().toString();
                        settings.edit().putString("enroll_server_url", enrollServerUrl).apply();
                        updateHelpText();
                        Log.d("Passport", enrollServerUrl);
                    }
                }).setNeutralButton(R.string.default_string, null)
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // Reset the URL field to the last known valid value
                        urlfield.setText(enrollServerUrl);
                    }
                });

        final AlertDialog urldialog = builder.create();


        urldialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                // By overriding the neutral button's onClick event in this onShow listener,
                // we prevent the dialog from closing when the default button is pressed.
                Button defaultbutton = urldialog.getButton(DialogInterface.BUTTON_NEUTRAL);
                defaultbutton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        enrollServerUrl = getString(R.string.enroll_default_url);
                        urlfield.setText(enrollServerUrl);
                        settings.edit().putString("enroll_server_url", enrollServerUrl).apply();
                        // Move cursor to end of field
                        urlfield.setSelection(urlfield.getText().length());
                        updateHelpText();
                    }
                });

                // Move cursor to end of field
                urlfield.setSelection(urlfield.getText().length());
            }
        });

        // If the text from the input field changes to something that we do not consider valid
        // (i.e., it is not a valid IP or domain name), we disable the OK button
        urlfield.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {  }
            @Override
            public void afterTextChanged(Editable s) {
                Button okbutton = urldialog.getButton(DialogInterface.BUTTON_POSITIVE);
                okbutton.setEnabled(isValidURL(s.toString()));
            }
        });

        return urldialog;
    }

    private class EnrollException extends Exception {
        public EnrollException(String message) {
            super(message);
        }
        public EnrollException(int msgId) {
            this(getString(msgId));
        }
        public EnrollException(Exception e) {
            super(e);
        }
    }

    /**
     * Contains all the network and issuing code, which it executes on its own
     * separate thread. See the enroll() method.
     */
    private class EnrollClient {
        private static final String TAG = "cardemu.Passport";

        private static final int MNO_SERVER_PORT = 6789;
        private static final int GOV_SERVER_PORT = 6788;
        private Socket clientSocket;
        private DataOutputStream outToServer;
        private BufferedReader inFromServer;
        private Handler networkHandler;
        private Handler uiHandler;
        private String response;
        private boolean resp = false;

        private SubscriberInfo subscriberInfo = null;
        private SubscriberDatabase subscribers = new MockupSubscriberDatabase();
        private MNOEnrol mno = new MNOEnrollImpl(subscribers);
        private PersonalRecordDatabase personalRecordDatabase = new MockupPersonalRecordDatabase();
        private GovernmentEnrol governmentEnrol = new GovernmentEnrolImpl(personalRecordDatabase);

        public EnrollClient() {
            HandlerThread dbThread = new HandlerThread("network");
            dbThread.start();
            networkHandler = new Handler(dbThread.getLooper());
        }

        //region Main method

        /**
         * The main enrolling method. This method talks to the MNO server and does all the work.
         * It does so on the networkHandler thread, and when it is done it reports its result
         * to the specified handler. This handler will receive a message whose .obj is either
         * null when everything went fine, or a string containing an error message if some  
         * problem occured.
         *
         * @param passportService The passport to talk to
         * @param is The IdemixService to issue the credential to
         * @param h A handler whose handleMessage() method will be called when done
         */
        private void enroll(final PassportService passportService, final IdemixService is, Handler h) {
            uiHandler = h;
            // The address in enrollServerUrl should already have been checked on valididy
            // by the urldialog, so there is no need to check it here
            Log.d(TAG, "Connecting to: " + enrollServerUrl);

            openConnection(enrollServerUrl, MNO_SERVER_PORT);

            sendAndListen("IMSI: " + imsi, new Runnable() {
                @Override
                public void run() {
                    if (!response.startsWith("SI: "))
                        return; // TODO do something here?

                    if (inFromServer == null) // openConnection didn't work
                        return;

                    SimpleDateFormat iso = new SimpleDateFormat("yyyyMMdd");
                    String[] res = response.substring(4).split(", ");
                    MNOEnrol.MNOEnrolResult mnoResult;

                    // Get the MNO credential
                    try {
                        subscriberInfo = new SubscriberInfo(iso.parse(res[0]), iso.parse(res[1]), res[2]);
                        sendMessage("PASP: found\n");
                        mnoResult = mno.enroll(imsi, subscriberInfo, "0000".getBytes(), passportService, is);
                        if (mnoResult == MNOEnrol.MNOEnrolResult.SUCCESS) {
                            sendMessage("PASP: verified");
                            sendMessage("ISSU: succesfull");
                        }
                    } catch (ParseException e) {
                        reportError(e.getMessage());
                        return;
                    } finally { // Always close the connection
                        closeConnection();
                    }

                    // Get the government credential
                    final GovernmentEnrol.GovernmentEnrolResult govResult = governmentEnrol.enroll("0000".getBytes(), is);
                    if (mnoResult != MNOEnrol.MNOEnrolResult.SUCCESS
                            || govResult != GovernmentEnrol.GovernmentEnrolResult.SUCCESS)
                        reportError(R.string.error_enroll_issuing_failed);
                    else {
                        storeCard();
                        reportSuccess();
                    }
                }
            });
        }

        //endregion

        //region Connection methods

        private void openConnection(final String ip, final int port) {
            inFromServer = null;

            networkHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Log.i(TAG, "deInBackground: Creating socket");
                        InetAddress serverAddr = InetAddress.getByName(ip);
                        clientSocket = new Socket(serverAddr, port);
                        outToServer = new DataOutputStream(clientSocket.getOutputStream());
                        inFromServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    } catch (IOException e) {
                        e.printStackTrace();
                        Log.i(TAG, "doInBackground: Exception");
                        reportError(R.string.error_enroll_cantconnect);
                    }
                }
            });
        }

        private void closeConnection() {
            networkHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        inFromServer.close();
                        outToServer.close();
                        clientSocket.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }

        //endregion

        //region Sending methods

        private void sendMessage(final String msg) { sendAndListen(msg, null); }
        private void sendAndListen(final String msg, final Runnable runnable) {
            networkHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (outToServer == null || inFromServer == null)
                            return;
                        outToServer.writeBytes(msg + "\n");
                        if (runnable != null && (response = inFromServer.readLine()) != null)
                            runnable.run();
                    } catch (IOException e) {
                        reportError(e.getMessage());
                        e.printStackTrace();
                    }
                }
            });
        }

        //endregion

        //region Methods for reporting to the handler given to enroll()

        private void reportSuccess() { report(null); }
        private void reportError(final String msg) { report(msg); }
        private void reportError(final int msgId) {
            reportError(getString(msgId));
        }
        private void report(final String msg) {
            Message m = new Message();
            m.obj = msg;
            uiHandler.sendMessage(m);
        }

        //endregion
    }
}
