/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.amitinside.sling.testing.osgi.mock;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mock implementation of {@link EventAdmin}. From {@link EventConstants}
 * currently only {@link EventConstants#EVENT_TOPIC} is supported.
 */
public final class MockEventAdmin implements EventAdmin {

	private static class EventHandlerItem {

		private static final Pattern WILDCARD_PATTERN = Pattern.compile("[^*]+|(\\*)");

		private static Pattern[] generateTopicPatterns(final Object topic) {
			String[] topics;
			if (topic == null) {
				topics = new String[0];
			} else if (topic instanceof String) {
				topics = new String[] { (String) topic };
			} else if (topic instanceof String[]) {
				topics = (String[]) topic;
			} else {
				throw new IllegalArgumentException("Invalid topic: " + topic);
			}
			final Pattern[] patterns = new Pattern[topics.length];
			for (int i = 0; i < topics.length; i++) {
				patterns[i] = toWildcardPattern(topics[i]);
			}
			return patterns;
		}

		/**
		 * Converts a wildcard string with * to a regex pattern (from
		 * http://stackoverflow.com/questions/24337657/wildcard-matching-in-java)
		 *
		 * @param wildcard
		 * @return Regexp pattern
		 */
		private static Pattern toWildcardPattern(final String wildcard) {
			final Matcher matcher = WILDCARD_PATTERN.matcher(wildcard);
			final StringBuffer result = new StringBuffer();
			while (matcher.find()) {
				if (matcher.group(1) != null) {
					matcher.appendReplacement(result, ".*");
				} else {
					matcher.appendReplacement(result, "\\\\Q" + matcher.group(0) + "\\\\E");
				}
			}
			matcher.appendTail(result);
			return Pattern.compile(result.toString());
		}

		private final EventHandler eventHandler;

		private final Pattern[] topicPatterns;

		public EventHandlerItem(final EventHandler eventHandler, final Map<String, Object> props) {
			this.eventHandler = eventHandler;
			this.topicPatterns = generateTopicPatterns(props.get(EventConstants.EVENT_TOPIC));
		}

		public EventHandler getEventHandler() {
			return this.eventHandler;
		}

		public boolean matches(final Event event) {
			if (this.topicPatterns.length == 0) {
				return true;
			}
			final String topic = event.getTopic();
			if (topic != null) {
				for (final Pattern topicPattern : this.topicPatterns) {
					if (topicPattern.matcher(topic).matches()) {
						return true;
					}
				}
			}
			return false;
		}

	}

	private static final Logger log = LoggerFactory.getLogger(MockEventAdmin.class);

	private ExecutorService asyncHandler;

	private final Map<Object, EventHandlerItem> eventHandlers = new TreeMap<Object, EventHandlerItem>();

	protected void activate(final ComponentContext componentContext) {
		this.asyncHandler = Executors.newCachedThreadPool();
	}

	protected void bindEventHandler(final EventHandler eventHandler, final Map<String, Object> props) {
		synchronized (this.eventHandlers) {
			this.eventHandlers.put(ServiceUtil.getComparableForServiceRanking(props, Order.DESCENDING),
					new EventHandlerItem(eventHandler, props));
		}
	}

	protected void deactivate(final ComponentContext componentContext) {
		this.asyncHandler.shutdownNow();
	}

	private void distributeEvent(final Event event) {
		synchronized (this.eventHandlers) {
			for (final EventHandlerItem item : this.eventHandlers.values()) {
				if (item.matches(event)) {
					try {
						item.getEventHandler().handleEvent(event);
					} catch (final Throwable ex) {
						log.error("Error handlihng event {} in {}", event, item.getEventHandler());
					}
				}
			}
		}
	}

	@Override
	public void postEvent(final Event event) {
		try {
			this.asyncHandler.execute(new Runnable() {
				@Override
				public void run() {
					MockEventAdmin.this.distributeEvent(event);
				}
			});
		} catch (final RejectedExecutionException ex) {
			// ignore
			log.debug("Ignore rejected execution: " + ex.getMessage(), ex);
		}
	}

	@Override
	public void sendEvent(final Event event) {
		this.distributeEvent(event);
	}

	protected void unbindEventHandler(final EventHandler eventHandler, final Map<String, Object> props) {
		synchronized (this.eventHandlers) {
			this.eventHandlers.remove(ServiceUtil.getComparableForServiceRanking(props, Order.DESCENDING));
		}
	}

}
