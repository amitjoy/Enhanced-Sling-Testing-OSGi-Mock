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

import java.io.IOException;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import org.osgi.framework.Constants;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import com.amitinside.sling.testing.osgi.mock.OsgiMetadataUtil.OsgiMetadata;

/**
 * Map util methods.
 */
final class MapUtil {

	public static Dictionary<String, Object> propertiesMergeWithOsgiMetadata(final Object target,
			final ConfigurationAdmin configAdmin, final Dictionary<String, Object> properties) {
		return toDictionary(propertiesMergeWithOsgiMetadata(target, configAdmin, toMap(properties)));
	}

	/**
	 * Merge service properties from three sources (with this precedence): 1.
	 * Properties defined in calling unit test code 2. Properties from
	 * ConfigurationAdmin 3. Properties from OSGi SCR metadata
	 *
	 * @param target
	 *            Target service
	 * @param configAdmin
	 *            Configuration admin or null if none is registered
	 * @param properties
	 *            Properties from unit test code or null if none where passed
	 * @return Merged properties
	 */
	public static Map<String, Object> propertiesMergeWithOsgiMetadata(final Object target,
			final ConfigurationAdmin configAdmin, final Map<String, Object> properties) {
		final Map<String, Object> mergedProperties = new HashMap<String, Object>();

		final OsgiMetadata metadata = OsgiMetadataUtil.getMetadata(target.getClass());
		if (metadata != null) {
			final Map<String, Object> metadataProperties = metadata.getProperties();
			if (metadataProperties != null) {
				mergedProperties.putAll(metadataProperties);

				// merge with configuration from config admin
				if (configAdmin != null) {
					final Object pid = metadataProperties.get(Constants.SERVICE_PID);
					if (pid != null) {
						try {
							final Configuration config = configAdmin.getConfiguration(pid.toString());
							mergedProperties.putAll(toMap(config.getProperties()));
						} catch (final IOException ex) {
							throw new RuntimeException("Unable to read config for pid " + pid, ex);
						}
					}
				}
			}
		}

		// merge with properties from calling unit test code
		if (properties != null) {
			mergedProperties.putAll(properties);
		}

		return mergedProperties;
	}

	public static <T, U> Dictionary<T, U> toDictionary(final Map<T, U> map) {
		if (map == null) {
			return null;
		}
		return new Hashtable<T, U>(map);
	}

	public static <T, U> Map<T, U> toMap(final Dictionary<T, U> dictionary) {
		if (dictionary == null) {
			return null;
		}
		final Map<T, U> map = new HashMap<T, U>();
		final Enumeration<T> keys = dictionary.keys();
		while (keys.hasMoreElements()) {
			final T key = keys.nextElement();
			map.put(key, dictionary.get(key));
		}
		return map;
	}

}
