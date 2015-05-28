/**
    Copyright (C) 2014  AIT / Austrian Institute of Technology
    http://www.ait.ac.at

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see http://www.gnu.org/licenses/agpl-3.0.html
 */
package at.ac.ait.ubicity.rss.impl;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.apache.log4j.Logger;

import at.ac.ait.ubicity.commons.broker.BrokerProducer;
import at.ac.ait.ubicity.commons.broker.events.EventEntry;
import at.ac.ait.ubicity.commons.broker.events.EventEntry.Property;
import at.ac.ait.ubicity.commons.cron.AbstractTask;
import at.ac.ait.ubicity.commons.exceptions.UbicityBrokerException;
import at.ac.ait.ubicity.commons.util.PropertyLoader;
import at.ac.ait.ubicity.rss.dto.RssDTO;

public class RssReaderTask extends AbstractTask {

	private final static Logger logger = Logger.getLogger(RssReaderTask.class);
	private static PropertyLoader config = new PropertyLoader(RssReaderTask.class.getResource("/rss.cfg"));

	private Producer producer;

	private String esIndex;
	private String pluginDest;

	class Producer extends BrokerProducer {

		public Producer(PropertyLoader config) throws UbicityBrokerException {
			super.init(config.getString("plugin.rss.broker.user"), config.getString("plugin.rss.broker.pwd"));
			pluginDest = config.getString("plugin.rss.broker.dest");
		}
	}

	public RssReaderTask() {
		try {
			esIndex = config.getString("plugin.rss.elasticsearch.index");
			producer = new Producer(config);
		} catch (Exception e) {
			logger.error("Exc. while creating producer", e);
		}
	}

	@Override
	public void executeTask() {

		try {
			RssFetcher rf = new RssFetcher((String) getProperty("URL"), (String) getProperty("lastGuid"));
			rf.start();

			// Wait one minute then interrupt Fetcher thread
			for (int i = 0; i < 60 && rf.isAlive(); i++) {
				Thread.sleep(1000);
			}

			if (rf.isAlive()) {
				rf.interrupt();
			}
		} catch (Exception e) {
			logger.warn("Caught exc. while fetching updates", e);
		}
	}

	private EventEntry createEvent(RssDTO data) {
		HashMap<Property, String> header = new HashMap<Property, String>();
		header.put(Property.ES_INDEX, this.esIndex);
		header.put(Property.ES_TYPE, getName());
		header.put(Property.ID, data.getId());
		header.put(Property.PLUGIN_CHAIN, EventEntry.formatPluginChain(Arrays.asList(pluginDest)));

		return new EventEntry(header, data.toJson());
	}

	/**
	 * Outsource fetching in Thread to kill it after certain time.
	 * 
	 * @author ruggenthalerc
	 *
	 */
	class RssFetcher extends Thread {

		private final String urlString, lastGuid;

		RssFetcher(String urlString, String lastGuid) {
			this.urlString = urlString;
			this.lastGuid = lastGuid;
		}

		@Override
		public void run() {
			try {
				RssParser parser = new RssParser(urlString, lastGuid);

				List<RssDTO> dtoList = parser.fetchUpdates();

				dtoList.stream().forEach((dto) -> {
					try {
						EventEntry e = createEvent(dto);

						producer.publish(e);
					} catch (Exception e) {
						logger.warn("Caught exc. while publishing", e);
					}
				});

				if (dtoList.size() > 0) {
					setProperty("lastGuid", dtoList.get(0).getId());
				}

			} catch (Exception e) {
				logger.warn("Caught exc. while fetching updates", e);
			}
		}
	}

}