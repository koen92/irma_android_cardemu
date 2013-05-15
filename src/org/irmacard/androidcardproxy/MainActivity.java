package org.irmacard.androidcardproxy;

import java.io.StringReader;
import java.io.UnsupportedEncodingException;

import net.sourceforge.scuba.smartcards.CardServiceException;
import net.sourceforge.scuba.smartcards.CommandAPDU;
import net.sourceforge.scuba.smartcards.ISO7816;
import net.sourceforge.scuba.smartcards.IsoDepCardService;
import net.sourceforge.scuba.smartcards.ProtocolCommand;
import net.sourceforge.scuba.smartcards.ProtocolResponse;
import net.sourceforge.scuba.smartcards.ProtocolResponses;
import net.sourceforge.scuba.util.Hex;

import org.apache.http.entity.StringEntity;
import org.irmacard.android.util.pindialog.EnterPINDialogFragment;
import org.irmacard.android.util.pindialog.EnterPINDialogFragment.PINDialogListener;
import org.irmacard.androidcardproxy.messages.EventArguments;
import org.irmacard.androidcardproxy.messages.ReaderMessage;
import org.irmacard.androidcardproxy.messages.ReaderMessageDeserializer;
import org.irmacard.androidcardproxy.messages.ResponseArguments;
import org.irmacard.androidcardproxy.messages.SelectAppletArguments;
import org.irmacard.androidcardproxy.messages.TransmitCommandSetArguments;
import org.irmacard.idemix.IdemixService;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.PendingIntent;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;


public class MainActivity extends Activity implements PINDialogListener {
	private String TAG = "CardProxyMainActivity";
	private NfcAdapter nfcA;
	private PendingIntent mPendingIntent;
	private IntentFilter[] mFilters;
	private String[][] mTechLists;

	
	
	// State variables
	private IsoDep lastTag = null;
	
	private int activityState = STATE_WAITING;
	
	private static final int STATE_WAITING = 0;
	private static final int STATE_CHECKING = 1;
	private static final int STATE_RESULT_OK = 2;
	private static final int STATE_RESULT_MISSING = 3;
	private static final int STATE_RESULT_WARNING = 4;
	private static final int STATE_CONNECTING_TO_SERVER = 5;
	private static final int STATE_IDLE = 10;
	
	private CountDownTimer cdt = null;
	
	private static final int WAITTIME = 6000; // Time until the status jumps back to STATE_IDLE
	
	private void setState(int state) {
		setState(state, null);
	}
	
	private void setState(int state, String feedback) {
		// TODO: this might need some work now as well?
    	Log.i(TAG,"Set state: " + state);
    	activityState = state;
    	int imageResource = 0;
    	int statusTextResource = 0;
    	switch (activityState) {
    	case STATE_WAITING:
    		imageResource = R.drawable.irma_icon_place_card_520px;
    		statusTextResource = R.string.status_waiting;    		
    		break;
		case STATE_CHECKING:
			imageResource = R.drawable.irma_icon_card_found_520px;
			statusTextResource = R.string.status_checking;
			break;
		case STATE_RESULT_OK:
			imageResource = R.drawable.irma_icon_ok_520px;
			statusTextResource = R.string.status_ok;
			break;
		case STATE_RESULT_MISSING:
			imageResource = R.drawable.irma_icon_missing_520px;
			statusTextResource = R.string.status_missing;
			break;
		case STATE_RESULT_WARNING:
			imageResource = R.drawable.irma_icon_warning_520px;
			statusTextResource = R.string.status_warning;
			break;
		case STATE_IDLE:
			imageResource = R.drawable.irma_icon_place_card_520px;
			statusTextResource = R.string.status_idle;
			lastTag = null;
			break;
		case STATE_CONNECTING_TO_SERVER:
			imageResource = R.drawable.irma_icon_place_card_520px;
			statusTextResource = R.string.connectserver;
			break;
		default:
			break;
		}
    	
    	if (activityState == STATE_RESULT_OK ||
    			activityState == STATE_RESULT_MISSING || 
    			activityState == STATE_RESULT_WARNING) {
        	if (cdt != null) {
        		cdt.cancel();
        	}
        	cdt = new CountDownTimer(WAITTIME, 100) {
        	     public void onTick(long millisUntilFinished) {
        	     }

        	     public void onFinish() {
        	    	 if (activityState != STATE_CHECKING) {
        	    		 // TODO: what is actually the proper state to return to?
        	    		 setState(STATE_IDLE);
        	    	 }
        	     }
        	}.start();
    	}
    	
    	if (feedback == null) {
    		((TextView)findViewById(R.id.statustext)).setText(statusTextResource);
    	} else {
    		((TextView)findViewById(R.id.statustext)).setText(feedback);
    	}
		((ImageView)findViewById(R.id.statusimage)).setImageResource(imageResource);
	}
	

	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
        // NFC stuff
        nfcA = NfcAdapter.getDefaultAdapter(getApplicationContext());
        mPendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);

        // Setup an intent filter for all TECH based dispatches
        IntentFilter tech = new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED);
        mFilters = new IntentFilter[] { tech };

        // Setup a tech list for all IsoDep cards
        mTechLists = new String[][] { new String[] { IsoDep.class.getName() } };

	    setState(STATE_IDLE);
	}


	@Override
	protected void onPause() {
		super.onPause();
    	if (nfcA != null) {
    		nfcA.disableForegroundDispatch(this);
    	}
	}
	
	@Override
	protected void onResume() {
        super.onResume();
        Log.i(TAG, "Action: " + getIntent().getAction());
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(getIntent().getAction())) {
            processIntent(getIntent());
        } else if (Intent.ACTION_VIEW.equals(getIntent().getAction()) && "cardproxy".equals(getIntent().getScheme())) {
        	// TODO: this is legacy code to have the cardproxy app respond to cardproxy:// urls. This doesn't
        	// work anymore, should check whether we want te re-enable it.
        	Uri uri = getIntent().getData();
        	String startURL = "http://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
        	startChannelListening(startURL);
        }
        if (nfcA != null) {
        	nfcA.enableForegroundDispatch(this, mPendingIntent, mFilters, mTechLists);
        }
	}
	
	@Override
	protected void onDestroy() {
		super.onDestroy();
	}
	private static final int MESSAGE_STARTGET = 1;
	String currentReaderURL = "";
	int currentHandlers = 0;
	
	Handler handler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MESSAGE_STARTGET:
				Log.i(TAG,"MESSAGE_STARTGET received in handler!");
				AsyncHttpClient client = new AsyncHttpClient();
				client.setTimeout(50000); // timeout of 50 seconds
				client.setUserAgent("org.irmacard.androidcardproxy");
				
				client.get(MainActivity.this, currentReaderURL, new AsyncHttpResponseHandler() {
					@Override
					public void onSuccess(int arg0, String responseData) {
						if (!responseData.equals("")) {
							Toast.makeText(MainActivity.this, responseData, Toast.LENGTH_SHORT).show();
							handleChannelData(responseData);
						}
						
						// Do a new request, but only if no new requests have started
						// in the mean time
						if (currentHandlers <= 1) {
							Message newMsg = new Message();
							newMsg.what = MESSAGE_STARTGET;
							handler.sendMessageDelayed(newMsg, 200);
						}
					}
					@Override
					public void onFailure(Throwable arg0, String arg1) {
						Toast.makeText(MainActivity.this, "Unable to connect, retrying...", Toast.LENGTH_SHORT).show();
						// TODO: retry only a certain number of times (3?) and then return to STATE_IDLE
						// We should try again, but only if no new requests have started
						// and we should wait a bit longer
						if (currentHandlers <= 1) {
							Message newMsg = new Message();
							newMsg.what = MESSAGE_STARTGET;
							handler.sendMessageDelayed(newMsg, 5000);
						}
						
					}
					public void onStart() {
						currentHandlers += 1;
					};
					public void onFinish() {
						currentHandlers -= 1;
					};
				});
			
				break;

			default:
				break;
			}
		}
	};
	
	private boolean firstMessage = true;
	private String currentWriteURL = null;
	private ReaderMessage lastReaderMessage = null;
	
	private void handleChannelData(String data) {
		Gson gson = new GsonBuilder().
				registerTypeAdapter(ProtocolCommand.class, new ProtocolCommandDeserializer()).
				registerTypeAdapter(ReaderMessage.class, new ReaderMessageDeserializer()).
				create();
		if (firstMessage) {
			// this is the message that containts the url to write to
			JsonParser p = new JsonParser();
			String write_url = p.parse(data).getAsJsonObject().get("write_url").getAsString();
			currentWriteURL = write_url;
			setState(STATE_WAITING);			
			firstMessage = false;
			// Signal to the other end that we we are ready accept commands
			postMessage(
					new ReaderMessage(ReaderMessage.TYPE_EVENT, ReaderMessage.NAME_EVENT_CARDREADERFOUND, null,
							new EventArguments().withEntry("type", "phone")));
		} else {
			ReaderMessage rm;
			try {
				Log.i(TAG, "Length (real): " + data);
				JsonReader reader = new JsonReader(new StringReader(data));
				reader.setLenient(true);
				rm = gson.fromJson(reader, ReaderMessage.class);
			} catch(Exception e) {
				e.printStackTrace();
				return;
			}
			lastReaderMessage = rm;
			if (rm.type.equals(ReaderMessage.TYPE_COMMAND)) {
				Log.i(TAG, "Got command message");
				setState(STATE_CHECKING);
				if (rm.name.equals(ReaderMessage.NAME_COMMAND_AUTHPIN)) {
					askForPIN();
				} else {
					new ProcessReaderMessage().execute(new ReaderInput(lastTag, rm));
				}
			} else if (rm.type.equals(ReaderMessage.TYPE_EVENT)) {
				EventArguments ea = (EventArguments)rm.arguments;
				if (rm.name.equals(ReaderMessage.NAME_EVENT_STATUSUPDATE)) {
					String state = ea.data.get("state");
					String feedback = ea.data.get("feedback");
					if (state != null) {
						if (state.equals("success")) {
							setState(STATE_RESULT_OK, feedback);
						} if (state.equals("warning")) {
							setState(STATE_RESULT_WARNING, feedback);
						} if (state.equals("failure")) {
							setState(STATE_RESULT_MISSING, feedback);
						}
					}
				}
			}
		}
	}
	
	
	private void postMessage(ReaderMessage rm) {
		if (currentWriteURL != null) {
			Gson gson = new GsonBuilder().
					registerTypeAdapter(ProtocolResponse.class, new ProtocolResponseSerializer()).
					create();
			String data = gson.toJson(rm);
			AsyncHttpClient client = new AsyncHttpClient();
			try {
				client.post(MainActivity.this, currentWriteURL, new StringEntity(data) , "application/json",  new AsyncHttpResponseHandler() {
					@Override
					public void onSuccess(int arg0, String arg1) {
						// TODO: Should there be some simple user feedback?
						super.onSuccess(arg0, arg1);
					}
					@Override
					public void onFailure(Throwable arg0, String arg1) {
						// TODO: Give proper feedback to the user that we are unable to send stuff
						super.onFailure(arg0, arg1);
					}
				});
			} catch (UnsupportedEncodingException e) {
				// Ignore, shouldn't happen ;)
				e.printStackTrace();
			}
		}
	}
	
	public void onMainTouch(View v) {
		if (activityState == STATE_IDLE) {
			lastTag = null;
			startQRScanner("Scan the QR image in the browser.");
		}
	}
	
    @Override
    public void onNewIntent(Intent intent) {
        setIntent(intent);
    }
    
    public void processIntent(Intent intent) {
        Tag tagFromIntent = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
    	IsoDep tag = IsoDep.get(tagFromIntent);
    	// Only proces tag when we're actually expecting a card.
    	if (tag != null && activityState == STATE_WAITING) {
    		postMessage(new ReaderMessage(ReaderMessage.TYPE_EVENT, ReaderMessage.NAME_EVENT_CARDFOUND, null));
    		lastTag = tag;
    	}    	
    }
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		IntentResult scanResult = IntentIntegrator
				.parseActivityResult(requestCode, resultCode, data);

		// Process the results from the QR-scanning activity
		if (scanResult != null) {
			String contents = scanResult.getContents();
			if (contents != null) {
				startChannelListening(contents);
			}
		}
	}
	
	private void startChannelListening(String url) {
		Log.i(TAG, "Start channel listening: " + url);
		currentReaderURL = url;
		Message msg = new Message();
		msg.what = MESSAGE_STARTGET;
		setState(STATE_CONNECTING_TO_SERVER);
		handler.sendMessage(msg);
	}
	
	public void askForPIN() {
		DialogFragment newFragment = new EnterPINDialogFragment();
	    newFragment.show(getFragmentManager(), "pinentry");
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.activity_main, menu);
		return true;
	}
	
	public void startQRScanner(String message) {
		IntentIntegrator integrator = new IntentIntegrator(this);
    	integrator.initiateScan(IntentIntegrator.QR_CODE_TYPES, message);
	}
	
	
	private class ReaderInput {
		public IsoDep tag;
		public ReaderMessage message;
		public String pincode = null;
		public ReaderInput(IsoDep tag, ReaderMessage message) {
			this.tag = tag;
			this.message = message;
		}
		
		public ReaderInput(IsoDep tag, ReaderMessage message, String pincode) {
			this.tag = tag;
			this.message = message;
			this.pincode = pincode;
		}
	}

    /**
     * INStruction to select an application.
     */
    private static final byte INS_SELECT_APPLICATION = (byte) 0xA4;

    /**
     * P1 parameter for select by name.
     */
    private static final byte P1_SELECT_BY_NAME = 0x04;
    
	private class ProcessReaderMessage extends AsyncTask<ReaderInput, Void, ReaderMessage> {
		

		@Override
		protected ReaderMessage doInBackground(ReaderInput... params) {
			ReaderInput input = params[0];
			IsoDep tag = input.tag;
			ReaderMessage rm = input.message;
			// Make sure time-out is long enough (10 seconds)
			tag.setTimeout(10000);
			
			// TODO: The current version of the cardproxy shouldn't depend on idemix terminal, but for now
			// it is convenient.
			IdemixService is = new IdemixService(new IsoDepCardService(tag));
			try {
				if (!is.isOpen()) {
					// TODO: this is dangerous, this call to IdemixService already does a "select applet"
					is.open();
				}
				if (rm.name.equals("selectApplet")) {
					SelectAppletArguments a = (SelectAppletArguments) rm.arguments;
					byte[] aidBytes = Hex.hexStringToBytes(a.AID);
				    ProtocolCommand selectApplicationCommand =
				    		new ProtocolCommand(
				    				"selectapplet",
				    				"Select IRMAcard application",
				    				 new CommandAPDU(ISO7816.CLA_ISO7816,
				    			                INS_SELECT_APPLICATION, P1_SELECT_BY_NAME, 0x00, aidBytes, 256)); // LE == 0 is required.
				    
				    // All this stuff is mostly the same as for transmitCommandSet. Why do we actually have a separate 
				    // "selectApplet" command? Can't it just be put in a separate call to transmitCommandSet?				    
				    ProtocolResponses responses = new ProtocolResponses();
				    responses.put(selectApplicationCommand.getKey(), 
				    		new ProtocolResponse(selectApplicationCommand.getKey(),
				    				is.transmit(selectApplicationCommand.getAPDU())));
					return new ReaderMessage(ReaderMessage.TYPE_RESPONSE, rm.name, rm.id,new ResponseArguments(responses));
				} else if (rm.name.equals(ReaderMessage.NAME_COMMAND_AUTHPIN)) {
					if (input.pincode != null) {
						// TODO: this should be done properly, maybe without using IdemixService?
						is.sendPin(input.pincode.getBytes());
						// TODO: the following statement needs to be redone, currently always returns success
						// How can we actually determine success?
						return new ReaderMessage("response", rm.name, rm.id, new ResponseArguments("success"));
					}
				} else if (rm.name.equals(ReaderMessage.NAME_COMMAND_TRANSMIT)) {
					TransmitCommandSetArguments arg = (TransmitCommandSetArguments)rm.arguments;
					ProtocolResponses responses = new ProtocolResponses();
					for (ProtocolCommand c: arg.commands) {
						responses.put(c.getKey(), 
								new ProtocolResponse(c.getKey(), is.transmit(c.getAPDU())));
					}
					return new ReaderMessage(ReaderMessage.TYPE_RESPONSE, rm.name, rm.id, new ResponseArguments(responses));
				}
				
			} catch (CardServiceException e) {
				e.printStackTrace();
				// TODO: maybe also include the information about the exception in the event?
				return new ReaderMessage(ReaderMessage.TYPE_EVENT, ReaderMessage.NAME_EVENT_CARDLOST, null);
			}
			return null;
		}
		
		@Override
		protected void onPostExecute(ReaderMessage result) {
			if (result != null) {
				// TODO: what we could consider here is checking whether any of the responses indicate
				// that a pincode entry is required, ask for the pincode and retry the ReaderMessage?
				postMessage(result);
			}
		}
	}	

	@Override
	public void onPINEntry(String dialogPincode) {
		// TODO: in the final version, the following debug code should go :)
		Log.i(TAG, "PIN entered: " + dialogPincode);
		new ProcessReaderMessage().execute(new ReaderInput(lastTag, lastReaderMessage, dialogPincode));
	}

	@Override
	public void onPINCancel() {
		Log.i(TAG, "PIN entry canceled!");
		postMessage(
				new ReaderMessage(ReaderMessage.TYPE_RESPONSE, 
						ReaderMessage.NAME_COMMAND_AUTHPIN, 
						lastReaderMessage.id, 
						new ResponseArguments("cancel")));
		
		setState(STATE_IDLE);
	}
	
	public static class ErrorFeedbackDialogFragment extends DialogFragment {
		public static ErrorFeedbackDialogFragment newInstance(String title, String message) {
			ErrorFeedbackDialogFragment f = new ErrorFeedbackDialogFragment();
			Bundle args = new Bundle();
			args.putString("message", message);
			args.putString("title", title);
			f.setArguments(args);
			return f;
		}
		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
			builder.setMessage(getArguments().getString("message"))
			.setTitle(getArguments().getString("title"))
			.setPositiveButton("OK", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int id) {
					dialog.dismiss();
				}
			});
			return builder.create();
		}
	}
}
