package ru.r2cloud.satellite;

import java.io.File;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.r2cloud.Lifecycle;
import ru.r2cloud.RtlSdrLock;
import ru.r2cloud.model.FrequencySource;
import ru.r2cloud.model.IQData;
import ru.r2cloud.model.ObservationRequest;
import ru.r2cloud.model.Satellite;
import ru.r2cloud.satellite.decoder.DecoderTask;
import ru.r2cloud.satellite.reader.IQReader;
import ru.r2cloud.satellite.reader.RtlFmReader;
import ru.r2cloud.satellite.reader.RtlSdrReader;
import ru.r2cloud.util.Clock;
import ru.r2cloud.util.ConfigListener;
import ru.r2cloud.util.Configuration;
import ru.r2cloud.util.NamingThreadFactory;
import ru.r2cloud.util.ProcessFactory;
import ru.r2cloud.util.ThreadPoolFactory;
import ru.r2cloud.util.Util;

public class Scheduler implements Lifecycle, ConfigListener {

	private static final Logger LOG = LoggerFactory.getLogger(Scheduler.class);

	private final ObservationFactory factory;
	private final SatelliteDao satellites;
	private final Configuration config;
	private final RtlSdrLock lock;
	private final ThreadPoolFactory threadpoolFactory;
	private final Clock clock;
	private final ProcessFactory processFactory;
	private final ObservationResultDao dao;
	private final DecoderTask decoderTask;
	private final Schedule<ScheduledObservation> schedule;

	private ScheduledExecutorService startThread = null;
	private ScheduledExecutorService stopThread = null;
	private ScheduledExecutorService decoderThread = null;

	public Scheduler(Schedule<ScheduledObservation> schedule, Configuration config, SatelliteDao satellites, RtlSdrLock lock, ObservationFactory factory, ThreadPoolFactory threadpoolFactory, Clock clock, ProcessFactory processFactory, ObservationResultDao dao, DecoderTask decoderTask) {
		this.schedule = schedule;
		this.config = config;
		this.config.subscribe(this, "locaiton.lat");
		this.config.subscribe(this, "locaiton.lon");
		this.satellites = satellites;
		this.lock = lock;
		this.factory = factory;
		this.threadpoolFactory = threadpoolFactory;
		this.clock = clock;
		this.processFactory = processFactory;
		this.dao = dao;
		this.decoderTask = decoderTask;
	}

	@Override
	public void onConfigUpdated() {
		if (startThread == null) {
			return;
		}

		boolean updateSchedule;
		if (config.getProperty("locaiton.lat") != null && config.getProperty("locaiton.lon") != null) {
			updateSchedule = true;
		} else {
			updateSchedule = false;
		}
		List<Satellite> supportedSatellites = satellites.findAll();
		for (Satellite cur : supportedSatellites) {
			if (!cur.isEnabled()) {
				continue;
			}
			if (updateSchedule) {
				cancel(cur);
				schedule(cur, false);
			} else {
				cancel(cur);
			}
		}
	}

	// protection from calling start 2 times and more
	@Override
	public synchronized void start() {
		if (startThread != null) {
			return;
		}
		startThread = threadpoolFactory.newScheduledThreadPool(1, new NamingThreadFactory("sch-start"));
		stopThread = threadpoolFactory.newScheduledThreadPool(1, new NamingThreadFactory("sch-stop"));
		decoderThread = threadpoolFactory.newScheduledThreadPool(1, new NamingThreadFactory("decoder"));
		onConfigUpdated();

		LOG.info("started");
	}

	public ObservationRequest schedule(Satellite cur, boolean immediately) {
		long current = clock.millis();
		ObservationRequest observation = create(current, cur, immediately);
		if (observation == null) {
			return null;
		}
		LOG.info("scheduled next pass for {}. start: {} end: {}", cur.getId(), new Date(observation.getStartTimeMillis()), new Date(observation.getEndTimeMillis()));
		IQReader reader = createReader(observation);
		Runnable readTask = new Runnable() {

			@Override
			public void run() {
				if (clock.millis() > observation.getEndTimeMillis()) {
					LOG.info("[{}] observation time passed. skip {}", observation.getId(), cur.getId());
					return;
				}
				if (!lock.tryLock(Scheduler.this)) {
					LOG.info("[{}] unable to acquire lock for {}", observation.getId(), cur.getId());
					return;
				}
				IQData data;
				try {
					data = reader.start();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				} finally {
					lock.unlock(Scheduler.this);
				}

				schedule(cur, false);

				if (data == null || !data.hasDataFile()) {
					return;
				}
				// actual start/end might be different
				observation.setStartTimeMillis(data.getActualStart());
				observation.setEndTimeMillis(data.getActualEnd());

				File dataFile = dao.insert(observation, data.getDataFile());
				if (dataFile == null) {
					return;
				}
				
				synchronized (Scheduler.this) {
					if (startThread == null) {
						return;
					}
					
					decoderThread.execute(new Runnable() {
						
						@Override
						public void run() {
							decoderTask.run(dataFile, observation);
						}
					});
				}

			}
		};
		synchronized (this) {
			if (startThread == null) {
				return null;
			}
			Future<?> future = startThread.schedule(readTask, observation.getStartTimeMillis() - current, TimeUnit.MILLISECONDS);
			Runnable completeTask = new Runnable() {

				@Override
				public void run() {
					reader.complete();
				}
			};
			Future<?> reaperFuture = stopThread.schedule(completeTask, observation.getEndTimeMillis() - current, TimeUnit.MILLISECONDS);
			schedule.add(new ScheduledObservation(observation, future, reaperFuture, readTask, completeTask));
			return observation;
		}
	}

	private ObservationRequest create(long current, Satellite cur, boolean immediately) {
		long next = current;
		while (!Thread.currentThread().isInterrupted()) {
			ObservationRequest observation = factory.create(new Date(next), cur, immediately);
			if (observation == null) {
				return null;
			}

			ScheduledObservation overlapped = schedule.getOverlap(observation.getStartTimeMillis(), observation.getEndTimeMillis());
			if (overlapped == null) {
				return observation;
			}

			if (immediately) {
				overlapped.cancel();
				return observation;
			}

			// find next
			next = observation.getEndTimeMillis();
		}
		return null;
	}

	private IQReader createReader(ObservationRequest req) {
		FrequencySource source = req.getSource();
		switch (source) {
		case APT:
			return new RtlFmReader(config, processFactory, req);
		case LRPT:
		case TELEMETRY:
			return new RtlSdrReader(config, processFactory, req);
		default:
			throw new IllegalArgumentException("unsupported source: " + source);
		}
	}

	public ScheduleEntry getNextObservation(String id) {
		return schedule.get(id);
	}

	// protection from calling stop 2 times and more
	@Override
	public synchronized void stop() {
		Util.shutdown(startThread, config.getThreadPoolShutdownMillis());
		Util.shutdown(stopThread, config.getThreadPoolShutdownMillis());
		Util.shutdown(decoderThread, config.getThreadPoolShutdownMillis());
		startThread = null;
		LOG.info("stopped");
	}

	public void cancel(Satellite satelliteToEdit) {
		schedule.cancel(satelliteToEdit.getId());
	}

	public ObservationRequest startImmediately(Satellite cur) {
		schedule.cancel(cur.getId());
		return schedule(cur, true);
	}

	public void completeImmediately(String id) {
		ScheduledObservation previous = schedule.cancel(id);
		if (previous == null) {
			return;
		}
		stopThread.submit(previous.getCompleteTask());
	}

}
