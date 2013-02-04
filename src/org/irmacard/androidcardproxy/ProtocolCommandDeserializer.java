package org.irmacard.androidcardproxy;

import java.lang.reflect.Type;

import service.ProtocolCommand;
import service.ProtocolResponse;

import net.sourceforge.scuba.smartcards.CommandAPDU;
import net.sourceforge.scuba.smartcards.ResponseAPDU;
import net.sourceforge.scuba.util.Hex;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

/**
 * Helper class to deserialize a ProtocolResponse from json
 *
 */
public class ProtocolCommandDeserializer implements JsonDeserializer<ProtocolCommand> {
	@Override
	public ProtocolCommand deserialize(JsonElement json, Type typeOfT,
			JsonDeserializationContext context) throws JsonParseException {
		return new ProtocolCommand(
				json.getAsJsonObject().get("key").getAsString(), "",
				new CommandAPDU(Hex.hexStringToBytes(json.getAsJsonObject().get("command").getAsString())));
	}
}
