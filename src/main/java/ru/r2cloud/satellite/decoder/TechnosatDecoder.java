package ru.r2cloud.satellite.decoder;

import ru.r2cloud.jradio.Beacon;
import ru.r2cloud.jradio.BeaconSource;
import ru.r2cloud.jradio.Endianness;
import ru.r2cloud.jradio.FloatInput;
import ru.r2cloud.jradio.blocks.CorrelateAccessCodeTag;
import ru.r2cloud.jradio.blocks.FixedLengthTagger;
import ru.r2cloud.jradio.blocks.TaggedStreamToPdu;
import ru.r2cloud.jradio.blocks.UnpackedToPacked;
import ru.r2cloud.jradio.technosat.Technosat;
import ru.r2cloud.jradio.tubix20.CMX909bBeacon;
import ru.r2cloud.model.ObservationRequest;
import ru.r2cloud.satellite.Predict;
import ru.r2cloud.util.Configuration;

public class TechnosatDecoder extends TelemetryDecoder {

	public TechnosatDecoder(Configuration config, Predict predict) {
		super(config, predict);
	}

	@Override
	public BeaconSource<? extends Beacon> createBeaconSource(FloatInput source, ObservationRequest req) {
		float gainMu = 0.175f;
		GmskDemodulator gmsk = new GmskDemodulator(source, 4800, gainMu);
		CorrelateAccessCodeTag correlateTag = new CorrelateAccessCodeTag(gmsk, 4, "111011110000111011110000", false);
		TaggedStreamToPdu pdu = new TaggedStreamToPdu(new UnpackedToPacked(new FixedLengthTagger(correlateTag, CMX909bBeacon.MAX_SIZE * 8), 1, Endianness.GR_MSB_FIRST, Byte.class));
		return new Technosat(pdu);
	}

}
