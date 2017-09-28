package ru.r2cloud.satellite;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.r2cloud.model.Satellite;
import ru.r2cloud.util.Configuration;

import com.google.common.base.Splitter;

public class SatelliteDao {

	private static final Pattern SATELLITE_ID = Pattern.compile("(\\d+)\\((.*?)\\)");
	private final List<Satellite> satellites;

	public SatelliteDao(Configuration config) {
		satellites = new ArrayList<Satellite>();
		for (String cur : Splitter.on(",").trimResults().omitEmptyStrings().split(config.getProperty("satellites.supported"))) {
			Matcher m = SATELLITE_ID.matcher(cur);
			if (m.find()) {
				Satellite curSatellite = new Satellite();
				curSatellite.setName(m.group(2));
				curSatellite.setNoradCatId(m.group(1));
				satellites.add(curSatellite);
			}
		}
	}

	public List<Satellite> findSupported() {
		return satellites;
	}

}