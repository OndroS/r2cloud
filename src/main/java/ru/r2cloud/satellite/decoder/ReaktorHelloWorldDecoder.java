package ru.r2cloud.satellite.decoder;

import ru.r2cloud.jradio.Beacon;
import ru.r2cloud.jradio.BeaconSource;
import ru.r2cloud.jradio.Endianness;
import ru.r2cloud.jradio.FloatInput;
import ru.r2cloud.jradio.blocks.CorrelateAccessCodeTag;
import ru.r2cloud.jradio.blocks.FixedLengthTagger;
import ru.r2cloud.jradio.blocks.TaggedStreamToPdu;
import ru.r2cloud.jradio.blocks.UnpackedToPacked;
import ru.r2cloud.jradio.cc11xx.Cc11xxReceiver;
import ru.r2cloud.jradio.rhw.ReaktorHelloWorld;
import ru.r2cloud.model.ObservationRequest;
import ru.r2cloud.satellite.Predict;
import ru.r2cloud.util.Configuration;

public class ReaktorHelloWorldDecoder extends TelemetryDecoder {

	public ReaktorHelloWorldDecoder(Configuration config, Predict predict) {
		super(config, predict);
	}

	@Override
	public BeaconSource<? extends Beacon> createBeaconSource(FloatInput source, ObservationRequest req) {
		float gainMu = 0.175f * 3;
		GmskDemodulator gmsk = new GmskDemodulator(source, 9600, gainMu);
		CorrelateAccessCodeTag correlateTag = new CorrelateAccessCodeTag(gmsk, 4, "00110101001011100011010100101110", false);
		TaggedStreamToPdu pdu = new TaggedStreamToPdu(new UnpackedToPacked(new FixedLengthTagger(correlateTag, 120 * 8), 1, Endianness.GR_MSB_FIRST, Byte.class));
		Cc11xxReceiver cc11 = new Cc11xxReceiver(pdu, true, true);
		return new ReaktorHelloWorld(cc11);
	}

}
