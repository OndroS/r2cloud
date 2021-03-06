package ru.r2cloud.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.http.HttpResponse;

import org.junit.Test;

import com.eclipsesource.json.JsonObject;

import ru.r2cloud.it.util.RegisteredTest;

public class ScheduleSaveIT extends RegisteredTest {

	@Test
	public void testUpdateConfiguration() {
		String satelliteId = "40069";
		JsonObject result = client.updateSchedule(satelliteId, true);
		assertNotNull(result.get("nextPass"));
		assertTrue(result.getBoolean("enabled", false));
		result = client.updateSchedule(satelliteId, false);
		assertNull(result.get("nextPass"));
	}

	@Test
	public void testSaveUnknownSatellite() {
		HttpResponse<String> response = client.updateScheduleWithResponse("-1", true);
		assertEquals(400, response.statusCode());
		assertErrorInField("id", response);
	}
	
	@Test
	public void testSaveUnknownSatellite2() {
		HttpResponse<String> response = client.updateScheduleWithResponse(null, true);
		assertEquals(400, response.statusCode());
		assertErrorInField("id", response);
	}

}
