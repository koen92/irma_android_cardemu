package org.irmacard.cardemu.protocols;

import android.os.AsyncTask;
import android.util.Log;
import com.google.gson.reflect.TypeToken;
import org.acra.ACRA;
import org.irmacard.cardemu.*;
import org.irmacard.cardemu.httpclient.HttpClient;
import org.irmacard.cardemu.httpclient.HttpClientException;
import org.irmacard.cardemu.httpclient.HttpResultHandler;
import org.irmacard.credentials.CredentialsException;
import org.irmacard.credentials.idemix.messages.IssueCommitmentMessage;
import org.irmacard.credentials.idemix.messages.IssueSignatureMessage;
import org.irmacard.credentials.idemix.proofs.ProofList;
import org.irmacard.api.common.IssuingRequest;
import org.irmacard.api.common.DisclosureProofRequest;
import org.irmacard.api.common.DisclosureProofResult;
import org.irmacard.api.common.util.GsonUtil;
import org.irmacard.credentials.info.InfoException;

import java.lang.reflect.Type;
import java.util.ArrayList;

public class JsonProtocol extends Protocol {
	private static String TAG = "CardEmuJson";

	private String server;

	// While asking for permission for disclosure during a bound issue/disclosure session, this keeps track
	// of the issuing request
	private IssuingRequest issuingRequest;

	public void connect(String url) {
		if (!url.endsWith("/"))
			url = url + "/";
		server = url;

		if (server.contains("/verification/"))
			startDisclosure();
		else if (server.contains("/issue/"))
			startIssuance();
	}

	/**
	 * Report the specified exception to the MainActivity
	 */
	private void fail(HttpClientException e) {
		String feedback;
		if (e.getCause() != null)
			feedback =  e.getCause().getMessage();
		else
			feedback = "Server returned status " + e.status;

		fail(feedback);
	}

	/**
	 * Report the specified exception to the MainActivity
	 */
	private void fail(Exception e) {
		fail(e.getMessage());
	}

	/**
	 * Report the specified feedback as a failure to the MainActivity
	 */
	private void fail(String feedback) {
		Log.w(TAG, feedback);

		activity.setFeedback(feedback, "failure");
		activity.setState(MainActivity.STATE_IDLE);
	}

	@Override
	public void onDiscloseOK(DisclosureProofRequest request) {
		if (issuingRequest != null) {
			finishIssuance(issuingRequest);
			issuingRequest = null;
		}
		else
			disclose(request);
	}

	@Override
	public void onIssueOK(IssuingRequest request) {
		finishIssuance(request);
	}

	@Override
	public void onIssueCancel() {
		cancelSession();
	}

	/**
	 * Retrieve an {@link IssuingRequest} from the server
	 */
	public void startIssuance() {
		Log.i(TAG, "Retrieving issuing request: " + server);

		activity.setState(MainActivity.STATE_CONNECTING_TO_SERVER);
		final String server = this.server;
		final HttpClient client = new HttpClient(GsonUtil.getGson());
		
		client.get(IssuingRequest.class, server, new HttpResultHandler<IssuingRequest>() {
			@Override public void onSuccess(IssuingRequest result) {
				Log.i(TAG, result.toString());
				askForIssuancePermission(result);
			}

			@Override public void onError(HttpClientException exception) {
				fail(exception);
			}
		});
	}

	/**
	 * Given an {@link IssuingRequest}, compute the first issuing message and post it to the server
	 * (using the specified {@link HttpClient}). If the server returns corresponding CL signatures,
	 * construct and save the new Idemix credentials.
	 */
	private void finishIssuance(IssuingRequest request) {
		Log.i(TAG, "Posting issuing commitments");

		final HttpClient client = new HttpClient(GsonUtil.getGson());
		IssueCommitmentMessage msg;
		try {
			msg = CredentialManager.getIssueCommitments(request);
		} catch (InfoException e) {
			e.printStackTrace();
			activity.setFeedback("Issuing failed: wrong credential type", "failure");
			cancelSession();
			return;
		} catch (CredentialsException e) {
			activity.setFeedback("Missing required attributes", "failure");
			cancelSession();
			return;
		}

		Type t = new TypeToken<ArrayList<IssueSignatureMessage>>(){}.getType();
		client.post(t, server + "commitments", msg, new HttpResultHandler<ArrayList<IssueSignatureMessage>>() {
			@Override public void onSuccess(ArrayList<IssueSignatureMessage> result) {
				try {
					CredentialManager.constructCredentials(result);
					activity.setFeedback("Issuing was successfull", "success");
					activity.setState(MainActivity.STATE_IDLE);
					done();
				} catch (InfoException|CredentialsException e) {
					fail(e);
				}
			}

			@Override public void onError(HttpClientException exception) {
				fail(exception);
			}
		});
	}

	/**
	 * Retrieve a {@link DisclosureProofRequest} from the server, see if we can satisfy it, and if so,
	 * ask the user which attributes she wants to disclose.
	 */
	private void startDisclosure() {
		Log.i(TAG, "Retrieving disclosure request: " + server);

		activity.setState(MainActivity.STATE_CONNECTING_TO_SERVER);
		final String server = this.server;
		final HttpClient client = new HttpClient(GsonUtil.getGson());

		client.get(DisclosureProofRequest.class, server, new HttpResultHandler<DisclosureProofRequest>() {
			@Override public void onSuccess(DisclosureProofRequest result) {
				if (result.getContent().size() == 0 || result.getNonce() == null || result.getContext() == null) {
					activity.setFeedback("Got malformed disclosure request", "failure");
					cancelSession();
					return;
				}
				activity.setState(MainActivity.STATE_READY);
				askForVerificationPermission(result);
			}

			@Override public void onError(HttpClientException exception) {
				cancelSession();
				fail(exception);
			}
		});
	}

	/**
	 * Given a {@link DisclosureProofRequest} with selected attributes, perform the disclosure.
	 */
	public void disclose(final DisclosureProofRequest request) {
		activity.setState(MainActivity.STATE_COMMUNICATING);
		Log.i(TAG, "Sending disclosure proofs to " + server);

		ProofList proofs;
		try {
			proofs = CredentialManager.getProofs(request);
		} catch (CredentialsException e) {
			e.printStackTrace();
			cancelSession();
			fail(e);
			return;
		}

		HttpClient client = new HttpClient(GsonUtil.getGson());
		client.post(DisclosureProofResult.Status.class, server + "proofs", proofs,
			new HttpResultHandler<DisclosureProofResult.Status>() {
			@Override public void onSuccess(DisclosureProofResult.Status result) {
				if (result == DisclosureProofResult.Status.VALID) {
					activity.setFeedback("Successfully disclosed attributes", "success");
					activity.setState(MainActivity.STATE_IDLE);
					done();
				} else { // We successfully computed a proof but server rejects it? That's fishy, report it
					String feedback = "Server rejected proof: " + result.name().toLowerCase();
					ACRA.getErrorReporter().handleException(new Exception(feedback));
					fail(feedback);
				}
			}

			@Override public void onError(HttpClientException exception) {
				fail(exception);
			}
		});
	}

	/**
	 * Cancels the current disclosure session by DELETE-ing the specified url and setting the state to idle.
	 */
	@Override
	public void cancelSession() {
		super.cancelSession();
		Log.i(TAG, "Canceling disclosure to " + server);

		new AsyncTask<String,Void,Void>() {
			@Override protected Void doInBackground(String... params) {
				try {
					new HttpClient(GsonUtil.getGson()).doDelete(params[0]);
				} catch (HttpClientException e) {
					e.printStackTrace();
				}
				return null;
			}
		}.execute(server);

		server = null;
	}
}
