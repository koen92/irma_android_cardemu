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

package org.irmacard.cardemu.disclosuredialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
import org.irmacard.api.common.*;
import org.irmacard.mno.common.util.GsonUtil;
import org.irmacard.cardemu.R;
import org.irmacard.credentials.info.CredentialDescription;
import org.irmacard.credentials.info.InfoException;

import java.util.Map;

/**
 * DialogFragment for asking permission of a user to disclose specified attributes, issue new credentials, or both.
 * If attributes are to be disclosed the user can also choose which ones. The user's choice is stored in each of
 * the {@link AttributeDisjunction#getSelected()} members.
 */
public class SessionDialogFragment extends DialogFragment {
	private DisclosureProofRequest proofRequest;
	private SignatureProofRequest signRequest;
	private IssuingRequest issuingRequest;
	private boolean issuing;
	private boolean signing;
	private boolean dislosing;
	private static SessionDialogListener listener;

	public interface SessionDialogListener {
		void onSignOK(SignatureProofRequest request);
		void onSignCancel();
		void onDiscloseOK(DisclosureProofRequest request);
		void onDiscloseCancel();
		void onIssueOK(IssuingRequest request);
		void onIssueCancel();
	}

	/**
	 * Constructs and returns a new SessionDialogFragment for disclosing. Users must implement the SessionDialogListener
	 * interface.
	 */
	public static SessionDialogFragment newDiscloseDialog(DisclosureProofRequest request, SessionDialogListener listener) {
		SessionDialogFragment.listener = listener;
		SessionDialogFragment dialog = new SessionDialogFragment();

		Bundle args = new Bundle();
		args.putSerializable("proofRequest", request);
		dialog.setArguments(args);

		return dialog;
	}

	/**
	 * Constructs and returns a new SessionDialogFragment for signing. Users must implement the SessionDialogListener
	 * interface.
	 * TODO: merge with Disclosedialog?
	 */
	public static SessionDialogFragment newSignDialog(SignatureProofRequest request, SessionDialogListener listener) {
		SessionDialogFragment.listener = listener;
		SessionDialogFragment dialog = new SessionDialogFragment();

		Bundle args = new Bundle();
		args.putSerializable("signRequest", request);
		dialog.setArguments(args);

		return dialog;
	}

	/**
	 * Constructs and returns a new SessionDialogFragment for issuing. Users must implement the SessionDialogListener
	 * interface.
	 */
	public static SessionDialogFragment newIssueDialog(IssuingRequest request, SessionDialogListener listener) {
		SessionDialogFragment.listener = listener;
		SessionDialogFragment dialog = new SessionDialogFragment();

		Bundle args = new Bundle();
		args.putSerializable("issuingRequest", request);
		dialog.setArguments(args);

		return dialog;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		proofRequest = (DisclosureProofRequest) getArguments().getSerializable("proofRequest");
		signRequest = (SignatureProofRequest) getArguments().getSerializable("signRequest");
		issuingRequest = (IssuingRequest) getArguments().getSerializable("issuingRequest");

		issuing = issuingRequest != null;
		signing = signRequest != null;
		dislosing = issuingRequest == null || !issuingRequest.getRequiredAttributes().isEmpty();
	}

	private static void populateDisclosurePart(Activity activity, View view, final DisclosureProofRequest request) {
		LayoutInflater inflater = activity.getLayoutInflater();
		Resources resources = activity.getResources();
		LinearLayout list = (LinearLayout) view.findViewById(R.id.attributes_container);

		if (list == null)
			throw new IllegalArgumentException("Can't populate view: of incorrect type" +
					" (should be R.layout.dialog_disclosure)");

		// When a user chooses an item in the spinner, this listener notifies the adapter of the spinner which item
		// was selected
		AdapterView.OnItemSelectedListener spinnerListener = new AdapterView.OnItemSelectedListener() {
			@Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				AttributesPickerAdapter<AttributeDisjunction> adapter =
						(AttributesPickerAdapter<AttributeDisjunction>) parent.getAdapter();
				AttributeDisjunction disjunction = adapter.setSelected(position);
				request.getContent().set(adapter.getIndex(), disjunction);
			}
			@Override public void onNothingSelected(AdapterView<?> parent) {
				((AttributesPickerAdapter) parent.getAdapter()).setSelected(-1);
			}
		};

		for (int i = 0; i < request.getContent().size(); ++i) {
			AttributeDisjunction disjunction = request.getContent().get(i);

			View attributeView = inflater.inflate(R.layout.attribute_picker, list, false);
			TextView name = (TextView) attributeView.findViewById(R.id.detail_attribute_name);
			name.setText(disjunction.getLabel());

			Spinner spinner = (Spinner) attributeView.findViewById(R.id.attribute_spinner);
			spinner.setAdapter(new AttributesPickerAdapter(activity, disjunction, i));

			spinner.setOnItemSelectedListener(spinnerListener);

			list.addView(attributeView);
		}

		String question1 = resources
				.getQuantityString(R.plurals.disclose_question_1, request.getContent().size());
		((TextView) view.findViewById(R.id.disclosure_question_1)).setText(question1);
	}

	/**
	 * TODO merge with populateDisclosurePart
	 * @param activity
	 * @param view
	 * @param request
	 */
	private static void populateSigningPart(Activity activity, View view, final SignatureProofRequest request) {
		LayoutInflater inflater = activity.getLayoutInflater();
		Resources resources = activity.getResources();
		LinearLayout list = (LinearLayout) view.findViewById(R.id.attributes_container);

		if (list == null)
			throw new IllegalArgumentException("Can't populate view: of incorrect type" +
					" (should be R.layout.dialog_disclosure)");

		// When a user chooses an item in the spinner, this listener notifies the adapter of the spinner which item
		// was selected
		AdapterView.OnItemSelectedListener spinnerListener = new AdapterView.OnItemSelectedListener() {
			@Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				AttributesPickerAdapter<SigAttributeDisjunction> adapter =
						(AttributesPickerAdapter<SigAttributeDisjunction>) parent.getAdapter();
				SigAttributeDisjunction disjunction = adapter.setSelected(position);
				request.getContent().set(adapter.getIndex(), disjunction);
			}
			@Override public void onNothingSelected(AdapterView<?> parent) {
				((AttributesPickerAdapter) parent.getAdapter()).setSelected(-1);
			}
		};

		for (int i = 0; i < request.getContent().size(); ++i) {
			SigAttributeDisjunction disjunction = request.getContent().get(i);

			View attributeView = inflater.inflate(R.layout.attribute_picker, list, false);
			TextView name = (TextView) attributeView.findViewById(R.id.detail_attribute_name);
			name.setText(disjunction.getLabel());

			Spinner spinner = (Spinner) attributeView.findViewById(R.id.attribute_spinner);
			spinner.setAdapter(new AttributesPickerAdapter(activity, disjunction, i));

			spinner.setOnItemSelectedListener(spinnerListener);

			list.addView(attributeView);
		}

		String message = request.getMessage();
		((TextView) view.findViewById(R.id.sign_content)).setText(message);

		String question1 = resources
				.getQuantityString(R.plurals.sign_question_1, request.getContent().size());
		((TextView) view.findViewById(R.id.sign_question_1)).setText(question1);
	}

	private void populateIssuingPart(Activity activity, View view, final IssuingRequest request) {
		LayoutInflater inflater = activity.getLayoutInflater();
		LinearLayout list = (LinearLayout) view.findViewById(R.id.issuance_container);
		if (list == null)
			throw new IllegalArgumentException("Can't populate view: of incorrect type" +
					" (should be R.layout.dialog_disclosure)");

		for (CredentialRequest cred : request.getCredentials()) {
			View credContainer = inflater.inflate(R.layout.disjunction_fragment, list, false);
			CredentialDescription cd;
			try {
				cd = cred.getCredentialDescription();
			} catch (InfoException e) {
				throw new RuntimeException(e);
			}

			String credentialname = cred.getIssuerName() + " - " + cd.getShortName();
			((TextView) credContainer.findViewById(R.id.disjunction_title)).setText(credentialname);
			LinearLayout attrList = (LinearLayout) credContainer.findViewById(R.id.disjunction_content);

			// We loop here over the attribute names as specified by the DescriptionStore, instead of
			// those from the CredentialRequest, because those from the DescriptionStore are odered.
			// This is safe because if these don't match, then an exception will have been thrown long
			// before we're here.
			for (String attrName : cd.getAttributeNames()) {
				String attrValue = cred.getAttributes().get(attrName);
				View attrView = inflater.inflate(R.layout.credential_item_attribute, attrList, false);

				((TextView) attrView.findViewById(R.id.credential_attribute_value)).setText(attrValue);
				TextView name = (TextView) attrView.findViewById(R.id.credential_attribute_name);
				name.setText(attrName);
				name.setPadding(0, 0, 0, 0);

				attrList.addView(attrView);
			}

			list.addView(credContainer);
		}
	}

	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		LayoutInflater inflater = getActivity().getLayoutInflater();
		View view;

		if (!issuing) {
			if (signing) {
				view = inflater.inflate(R.layout.dialog_sign, null);
				populateSigningPart(getActivity(), view, signRequest);
			} else {
				view = inflater.inflate(R.layout.dialog_disclosure, null);
				view.findViewById(R.id.issuance_question).setVisibility(View.GONE);
				view.findViewById(R.id.issuance_container).setVisibility(View.GONE);
				populateDisclosurePart(getActivity(), view, proofRequest);
			}
		}
		else {
			view = inflater.inflate(R.layout.dialog_disclosure, null);
			populateIssuingPart(getActivity(), view, issuingRequest);

			AttributeDisjunctionList requiredAttrs = issuingRequest.getRequiredAttributes();
			if (requiredAttrs.isEmpty()) {
				((TextView) view.findViewById(R.id.disclosure_question_2)).setText("Continue issuance?");
				view.findViewById(R.id.attributes_container).setVisibility(View.GONE);
				view.findViewById(R.id.disclosure_question_1).setVisibility(View.GONE);
			} else {
				DisclosureProofRequest disclosureRequest = new DisclosureProofRequest(
						issuingRequest.getNonce(), issuingRequest.getContext(), requiredAttrs);
				populateDisclosurePart(getActivity(), view, disclosureRequest);

				((TextView) view.findViewById(R.id.disclosure_question_1))
						.setText("However, the following attributes will be sent to the issuer during issuance.");
				((TextView) view.findViewById(R.id.disclosure_question_2))
						.setText("Disclose these attributes and continue issuance?");
			}
		}

		String title;
		if (signing) {
			title = "Sign using";
		} else {
			title = (issuing ? "Receive" : "Disclose");
		}

		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity())
				.setTitle(title + " attributes?")
				.setView(view)
				.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						if (signing) {
							listener.onSignOK(signRequest);
						} else if (!issuing)
							listener.onDiscloseOK(proofRequest);
						else
							listener.onIssueOK(issuingRequest);
					}
				})
				.setNegativeButton("No", new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						if (signing) {
							listener.onSignCancel();
						}
						else if (!issuing)
							listener.onDiscloseCancel();
						else
							listener.onIssueCancel();
					}
				});

		if (dislosing)
				builder.setNeutralButton("More Information", null);

		final AlertDialog d = builder.create();

		d.setCanceledOnTouchOutside(false);

		// We set the listener for the neutral ("More Information") button instead of above, because if we set it
		// above then the dialog is dismissed afterwards and we don't want that.
		if (signing) {
			final SigAttributeDisjunctionList disjunctions = signRequest.getContent();
			d.setOnShowListener(new DialogInterface.OnShowListener() {
				@Override
				public void onShow(DialogInterface dialog) {
					d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							Intent intent = new Intent(getActivity(), DisclosureInformationActivity.class);
							intent.putExtra("disjunctions", GsonUtil.getGson().toJson(disjunctions.toAttributeDisjunctionList()));
							startActivity(intent);
						}
					});
				}
			});
		} else if (dislosing) {
			final AttributeDisjunctionList disjunctions = (!issuing) ?
					proofRequest.getContent() : issuingRequest.getRequiredAttributes();

			d.setOnShowListener(new DialogInterface.OnShowListener() {
				@Override
				public void onShow(DialogInterface dialog) {
					d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
						@Override
						public void onClick(View v) {
							Intent intent = new Intent(getActivity(), DisclosureInformationActivity.class);
							intent.putExtra("disjunctions", GsonUtil.getGson().toJson(disjunctions));
							startActivity(intent);
						}
					});
				}
			});
		}

		return d;
	}
}
