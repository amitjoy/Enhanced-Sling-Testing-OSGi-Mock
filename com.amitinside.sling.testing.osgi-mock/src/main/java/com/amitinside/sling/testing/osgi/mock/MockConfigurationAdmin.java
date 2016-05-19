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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

/**
 * Mock implementation of {@link ConfigurationAdmin}.
 */
class MockConfigurationAdmin implements ConfigurationAdmin {

	private final ConcurrentMap<String, Configuration> configs = new ConcurrentHashMap<String, Configuration>();

	@Override
	public Configuration createFactoryConfiguration(final String factoryPid) {
		throw new UnsupportedOperationException();
	}

	// --- unsupported operations ---

	@Override
	public Configuration createFactoryConfiguration(final String factoryPid, final String location) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Configuration getConfiguration(final String pid) {
		this.configs.putIfAbsent(pid, new MockConfiguration(pid));
		return this.configs.get(pid);
	}

	@Override
	public Configuration getConfiguration(final String pid, final String location) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Configuration[] listConfigurations(final String filter) {
		throw new UnsupportedOperationException();
	}

}
